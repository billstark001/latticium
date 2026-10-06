package io.github.billstark001.latticium.mc;

import io.github.billstark001.latticium.dsl.Compiler;
import io.github.billstark001.latticium.dsl.FactDependencies;
import io.github.billstark001.latticium.dsl.Model.*;
import io.github.billstark001.latticium.planning.ActivationTracker;
import io.github.billstark001.latticium.planning.Host;
import io.github.billstark001.latticium.planning.Profile;
import io.github.billstark001.latticium.planning.ProfileReader;
import io.github.billstark001.latticium.planning.SectionScanner.Bounds;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

/** Client lifecycle and public registration point shared by loader entrypoints and bridge mods. */
public final class LatticiumClient {
  /**
   * A bridge supplies inclusive active bounds and exact or explicitly unknown targets. Calls use a
   * job-specific session on the client thread; a bridge should keep snapshots separate by session.
   */
  public interface BlueprintProvider extends Host.TargetSource, Host.SelectionSource {}

  private static final LatticiumClient INSTANCE = new LatticiumClient();
  private static final int MAX_TRACKED_BLOCK_UPDATES = 16_384;
  private static final TargetCell.Unknown QUERY_TARGET_UNKNOWN =
      new TargetCell.Unknown("Query has no target source");

  private final BlueprintProviders providers = new BlueprintProviders();
  private final Map<String, List<Bounds>> selections = new HashMap<>();
  private final Map<String, String> profiles = new HashMap<>();
  private final Set<String> enabled = new HashSet<>();
  private final Map<String, Profile.Bound> autoBindings = new HashMap<>();
  private final Map<String, ActivationTracker> autoTrackers = new HashMap<>();
  private final Map<String, String> autoErrors = new HashMap<>();
  private final Map<Position, Long> serverUpdates =
      new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Position, Long> eldest) {
          return size() > MAX_TRACKED_BLOCK_UPDATES;
        }
      };
  private long serverUpdateSequence;
  private ClientLevel world;
  private Host.SessionId session;
  private ClientJob job;
  private final List<ClientJob> settling = new ArrayList<>();
  private String activeAutoId;
  private boolean automaticSuspended;
  private Position lastActivationPlayer;
  private int lastActivationRadius;
  private boolean activationDirty = true;
  private long activationTicks;
  private Path storagePath;
  private String storageError = "";
  private BlockPos firstCorner;
  private BlockPos secondCorner;

  private LatticiumClient() {}

  public static LatticiumClient get() {
    return INSTANCE;
  }

  /** Registers a target source during client initialization; duplicate IDs are rejected. */
  public void registerTargetProvider(ResourceId id, Host.TargetSource source) {
    providers.registerTarget(id, source);
  }

  /** Registers an active blueprint source and its named finite selection. */
  public void registerBlueprintProvider(ResourceId id, BlueprintProvider provider) {
    providers.registerBlueprint(id, provider);
  }

  /** Ends jobs with old bindings while allowing already submitted receipts to settle. */
  void registryReloaded(Minecraft minecraft) {
    if (world == null || world != minecraft.level) return;
    boolean hadJob = job != null;
    cancel();
    autoBindings.clear();
    autoTrackers.clear();
    autoErrors.clear();
    activationDirty = true;
    if (hadJob && minecraft.player != null)
      minecraft.player.sendSystemMessage(
          net.minecraft.network.chat.Component.translatable("latticium.registry_reload"));
  }

  public void tick(Minecraft minecraft) {
    if (!minecraft.isSameThread()) throw new IllegalStateException("Tick on client thread");
    if (world != minecraft.level) {
      if (job != null) job.leaveWorld();
      settling.forEach(ClientJob::leaveWorld);
      settling.clear();
      job = null;
      world = minecraft.level;
      session = world == null ? null : new Host.SessionId();
      storagePath = WorldStorage.path(minecraft);
      try {
        WorldStorage.load(storagePath, profiles, selections, enabled);
        storageError = "";
      } catch (RuntimeException error) {
        profiles.clear();
        selections.clear();
        enabled.clear();
        storageError =
            "Preferences unavailable: "
                + (error.getMessage() == null
                    ? error.getClass().getSimpleName()
                    : error.getMessage());
      }
      autoBindings.clear();
      autoTrackers.clear();
      autoErrors.clear();
      activeAutoId = null;
      lastActivationPlayer = null;
      lastActivationRadius = 0;
      activationDirty = true;
      activationTicks = 0;
      serverUpdates.clear();
      serverUpdateSequence = 0;
      firstCorner = null;
      secondCorner = null;
    }
    if (job != null) job.tick();
    settling.forEach(ClientJob::tick);
    settling.removeIf(ClientJob::isSettled);
    activationTicks++;
    sampleActivations(minecraft);
  }

  private void sampleActivations(Minecraft minecraft) {
    if (automaticSuspended) return;
    if (enabled.isEmpty() || session == null || minecraft.player == null) return;
    if (job != null && !job.isFinished() && activeAutoId == null) return;
    var feet = minecraft.player.blockPosition();
    var dimension = MinecraftStateCodec.id(minecraft.level.dimension().identifier());
    var center = new Position(dimension, feet.getX(), feet.getY(), feet.getZ());
    if (!activationDirty && center.equals(lastActivationPlayer) && activationTicks % 200 != 0)
      return;
    int radius = 0;
    boolean hasActivation = false;
    var dependencies = FactDependencies.NONE;
    for (var id : enabled.stream().sorted().toList()) {
      if (job != null && !job.isFinished() && !id.equals(activeAutoId)) continue;
      try {
        var bound =
            autoBindings.computeIfAbsent(
                id,
                key -> {
                  var json = profiles.get(key);
                  if (json == null) throw new IllegalArgumentException("Unknown profile: " + key);
                  return compileProfile(minecraft, json);
                });
        if (bound.activation() != null) {
          radius = Math.max(radius, bound.activation().radius());
          hasActivation = true;
          dependencies = dependencies.union(bound.activation().dependencies());
        }
      } catch (RuntimeException error) {
        autoErrors.put(id, Objects.toString(error.getMessage(), error.getClass().getSimpleName()));
      }
    }
    if (!hasActivation) return;
    lastActivationPlayer = center;
    lastActivationRadius = radius;
    activationDirty = false;
    var capture =
        new MinecraftSectionSource(
                minecraft, session, ClientRegistryState.epochs(), null, selections, dependencies)
            .captureAround(session, center, radius);
    if (!(capture instanceof Host.Capture.Ready ready)) return;
    for (var id : enabled.stream().sorted().toList()) {
      if (job != null && !job.isFinished() && !id.equals(activeAutoId)) continue;
      var bound = autoBindings.get(id);
      if (bound == null || bound.activation() == null) continue;
      var tracker = autoTrackers.computeIfAbsent(id, ignored -> new ActivationTracker());
      var decision =
          tracker.sample(
              bound.profile().activation(), bound.activation(), ready.snapshot().facts());
      if (decision == ActivationTracker.Decision.STOP && id.equals(activeAutoId)) {
        cancel();
        activeAutoId = null;
      } else if (decision == ActivationTracker.Decision.START
          && (job == null || job.isFinished())) {
        try {
          start(minecraft, id);
          activeAutoId = id;
          autoErrors.remove(id);
        } catch (RuntimeException error) {
          autoErrors.put(
              id, Objects.toString(error.getMessage(), error.getClass().getSimpleName()));
        }
      }
    }
  }

  public BlockPos setCorner(Minecraft minecraft, int which) {
    requireWorld(minecraft);
    var pos = minecraft.player.blockPosition();
    if (which == 1) firstCorner = pos;
    else if (which == 2) secondCorner = pos;
    else throw new IllegalArgumentException("Corner must be 1 or 2");
    return pos;
  }

  /** Called after a vanilla server block-update packet has been applied to the client world. */
  public void noteServerBlockUpdate(BlockPos blockPos) {
    var minecraft = Minecraft.getInstance();
    if (!minecraft.isSameThread() || world == null || world != minecraft.level) return;
    var dimension = MinecraftStateCodec.id(world.dimension().identifier());
    var position = new Position(dimension, blockPos.getX(), blockPos.getY(), blockPos.getZ());
    serverUpdates.put(position, ++serverUpdateSequence);
    if (job != null) job.noteBlockUpdate(position);
    if (lastActivationPlayer != null
        && lastActivationPlayer.dimension().equals(position.dimension())
        && Math.abs((long) position.x() - lastActivationPlayer.x()) <= lastActivationRadius
        && Math.abs((long) position.y() - lastActivationPlayer.y()) <= lastActivationRadius
        && Math.abs((long) position.z() - lastActivationPlayer.z()) <= lastActivationRadius)
      activationDirty = true;
  }

  long serverRevision(Position pos) {
    return serverUpdates.getOrDefault(pos, 0L);
  }

  public void saveSelection(Minecraft minecraft, String name) {
    requireWorld(minecraft);
    if (firstCorner == null || secondCorner == null)
      throw new IllegalStateException("Set both selection corners first");
    var dimension = MinecraftStateCodec.id(minecraft.level.dimension().identifier());
    var previous =
        selections.put(
            name,
            List.of(
                new Bounds(
                    dimension,
                    Math.min(firstCorner.getX(), secondCorner.getX()),
                    Math.min(firstCorner.getY(), secondCorner.getY()),
                    Math.min(firstCorner.getZ(), secondCorner.getZ()),
                    Math.max(firstCorner.getX(), secondCorner.getX()),
                    Math.max(firstCorner.getY(), secondCorner.getY()),
                    Math.max(firstCorner.getZ(), secondCorner.getZ()))));
    try {
      savePreferences();
    } catch (RuntimeException error) {
      if (previous == null) selections.remove(name);
      else selections.put(name, previous);
      throw error;
    }
    activationDirty = true;
  }

  /** Saves a parsed profile for the current world; live binding occurs when enabled or started. */
  public void loadProfile(String json) {
    requireWorld(Minecraft.getInstance());
    var profile = new ProfileReader().read(json);
    var id = profile.id().toString();
    var previous = profiles.put(id, json);
    try {
      savePreferences();
    } catch (RuntimeException error) {
      if (previous == null) profiles.remove(id);
      else profiles.put(id, previous);
      throw error;
    }
    autoBindings.remove(id);
    autoTrackers.remove(id);
    activationDirty = true;
  }

  public void setEnabled(Minecraft minecraft, String id, boolean value) {
    requireWorld(minecraft);
    if (!value) {
      boolean wasEnabled = enabled.remove(id);
      try {
        savePreferences();
      } catch (RuntimeException error) {
        if (wasEnabled) enabled.add(id);
        throw error;
      }
      autoBindings.remove(id);
      autoTrackers.remove(id);
      autoErrors.remove(id);
      activationDirty = true;
      if (id.equals(activeAutoId)) cancel();
      return;
    }
    var json = profiles.get(id);
    if (json == null) throw new IllegalArgumentException("Unknown profile: " + id);
    var bound = compileProfile(minecraft, json);
    if (bound.activation() == null)
      throw new IllegalArgumentException("Profile has no automatic activation: " + id);
    boolean wasEnabled = !enabled.add(id);
    try {
      savePreferences();
    } catch (RuntimeException error) {
      if (!wasEnabled) enabled.remove(id);
      throw error;
    }
    autoBindings.put(id, bound);
    autoTrackers.put(id, new ActivationTracker());
    autoErrors.remove(id);
    activationDirty = true;
  }

  /** Binds a profile to the live 26.2/26.3 registry without starting it. */
  public Profile.Bound compileProfile(Minecraft minecraft, String json) {
    requireWorld(minecraft);
    var reader = new ProfileReader();
    return reader.bind(reader.read(json), new Compiler(new MinecraftRegistry(minecraft.level), 16));
  }

  public Map<String, Boolean> capabilities() {
    return Map.of(
        "finite_scanning",
        true,
        "ordinary_placement",
        true,
        "normal_break",
        true,
        "blueprint_provider",
        providers.hasBlueprint());
  }

  /** Read-only bounded scan; the result marks sections that remain unexamined or unavailable. */
  public ClientJob.Preview preview(Minecraft minecraft, String id, int maxSections) {
    requireWorld(minecraft);
    var json = profiles.get(id);
    if (json == null) throw new IllegalArgumentException("Unknown profile: " + id);
    var profile = new ProfileReader().read(json);
    var source =
        profile.target() instanceof Profile.Source external
            ? providers.target(external.id())
            : null;
    var previewSession = new Host.SessionId();
    return new ClientJob(
            minecraft, previewSession, json, source, jobSelections(profile, previewSession))
        .preview(maxSections);
  }

  /** Queries a position predicate inside the saved build selection without submitting actions. */
  public ClientJob.Preview query(Minecraft minecraft, String expression, int maxSections) {
    requireWorld(minecraft);
    if (!selections.containsKey("build"))
      throw new IllegalStateException("Save a build selection before querying");
    return new ClientJob(
            minecraft,
            new Host.SessionId(),
            CommandProfiles.query(expression),
            (position, requestedSession) -> QUERY_TARGET_UNKNOWN,
            Map.copyOf(selections))
        .preview(maxSections);
  }

  public void submit(Minecraft minecraft, String id) {
    start(minecraft, id);
  }

  public void start(Minecraft minecraft, String id) {
    requireWorld(minecraft);
    var json = profiles.get(id);
    if (json == null) throw new IllegalArgumentException("Unknown profile: " + id);
    startJson(minecraft, json, false);
  }

  private void startJson(Minecraft minecraft, String json, boolean rememberProfile) {
    var profile = new ProfileReader().read(json);
    var source =
        profile.target()
                instanceof io.github.billstark001.latticium.planning.Profile.Source external
            ? providers.target(external.id())
            : null;
    if (!settling.isEmpty())
      throw new IllegalStateException("Previous submitted action is still settling");
    if (job != null && job.hasPendingAction())
      throw new IllegalStateException("Current submitted action is still settling");
    var jobSession = new Host.SessionId();
    var jobSelections = jobSelections(profile, jobSession);
    var replacement = new ClientJob(minecraft, jobSession, json, source, jobSelections);
    if (rememberProfile) {
      var id = profile.id().toString();
      var previous = profiles.put(id, json);
      try {
        savePreferences();
      } catch (RuntimeException error) {
        if (previous == null) profiles.remove(id);
        else profiles.put(id, previous);
        throw error;
      }
      autoBindings.remove(id);
      autoTrackers.remove(id);
    }
    if (job != null) job.cancel();
    job = replacement;
    activeAutoId = null;
  }

  private Map<String, List<Bounds>> jobSelections(Profile profile, Host.SessionId jobSession) {
    var jobSelections = new HashMap<>(selections);
    if (profile.target() instanceof Profile.Source external) {
      var blueprint = providers.blueprint(external.id());
      if (blueprint != null) {
        var bounds = blueprint.finiteBounds("active_blueprint", jobSession);
        if (bounds.isEmpty()) throw new IllegalStateException("No active blueprint selection");
        jobSelections.put("active_blueprint", bounds);
      }
    }
    return jobSelections;
  }

  public void startInline(Minecraft minecraft, String json) {
    requireWorld(minecraft);
    startJson(minecraft, json, true);
  }

  public void pause() {
    if (job != null) job.pause();
  }

  public void resume() {
    if (job != null) job.resume();
  }

  /** Requests a bounded rescan now, even for a profile with manual refresh mode. */
  public void refresh() {
    requireWorld(Minecraft.getInstance());
    if (job == null) throw new IllegalStateException("No job to refresh");
    job.refresh();
  }

  public void cancel() {
    if (job != null) {
      job.cancel();
      if (!job.isSettled()) settling.add(job);
    }
    job = null;
    activeAutoId = null;
  }

  public String status() {
    return "%s enabled=%d autoErrors=%s%s"
        .formatted(
            job == null ? "idle" : job.status(),
            enabled.size(),
            autoErrors,
            storageError.isEmpty() ? "" : " storageError=" + storageError);
  }

  private void savePreferences() {
    WorldStorage.save(storagePath, profiles, selections, enabled);
    storageError = "";
  }

  public Map<Position, String> blocked() {
    return job == null ? Map.of() : job.blocked();
  }

  public ClientUiState uiState() {
    return new ClientUiState(
        world != null,
        job == null ? null : job.snapshot(),
        settling.size(),
        automaticSuspended,
        enabled,
        autoErrors,
        storageError,
        providers.blueprintIds());
  }

  public List<String> profileIds() {
    return profiles.keySet().stream().sorted().toList();
  }

  /** Stops new work and holds automatic activation until explicitly restored. */
  public void stopAll() {
    automaticSuspended = true;
    cancel();
  }

  public void restoreAutomaticActivation() {
    automaticSuspended = false;
    autoTrackers.clear();
    activationDirty = true;
  }

  private void requireWorld(Minecraft minecraft) {
    if (!minecraft.isSameThread() || minecraft.level == null || minecraft.player == null)
      throw new IllegalStateException("A client world is required");
    if (world != minecraft.level) tick(minecraft);
  }
}

package io.github.billstark001.latticium.mc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.billstark001.latticium.dsl.Compiler;
import io.github.billstark001.latticium.dsl.Model.*;
import io.github.billstark001.latticium.planning.ActivationTracker;
import io.github.billstark001.latticium.planning.Host;
import io.github.billstark001.latticium.planning.Profile;
import io.github.billstark001.latticium.planning.ProfileReader;
import io.github.billstark001.latticium.planning.SectionScanner.Bounds;
import io.github.billstark001.latticium.planning.SectionScanner.SectionKey;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

/** Client lifecycle and public registration point shared by loader entrypoints and bridge mods. */
public final class LatticiumClient {
  /** A bridge supplies both enumerable active bounds and per-position target facts. */
  public interface BlueprintProvider extends Host.TargetSource, Host.SelectionSource {}

  private static final LatticiumClient INSTANCE = new LatticiumClient();

  private final Map<ResourceId, Host.TargetSource> providers = new HashMap<>();
  private final Map<ResourceId, BlueprintProvider> blueprints = new HashMap<>();
  private final Map<String, List<Bounds>> selections = new HashMap<>();
  private final Map<String, String> profiles = new HashMap<>();
  private final Set<String> enabled = new HashSet<>();
  private final Map<String, Profile.Bound> autoBindings = new HashMap<>();
  private final Map<String, ActivationTracker> autoTrackers = new HashMap<>();
  private final Map<String, String> autoErrors = new HashMap<>();
  private final Map<Position, Long> serverUpdates = new HashMap<>();
  private long serverUpdateSequence;
  private ClientLevel world;
  private Host.SessionId session;
  private ClientJob job;
  private final List<ClientJob> settling = new ArrayList<>();
  private String activeAutoId;
  private Path storagePath;
  private BlockPos firstCorner;
  private BlockPos secondCorner;

  private LatticiumClient() {}

  public static LatticiumClient get() {
    return INSTANCE;
  }

  public synchronized void registerTargetProvider(ResourceId id, Host.TargetSource source) {
    Objects.requireNonNull(id);
    Objects.requireNonNull(source);
    if (providers.putIfAbsent(id, source) != null)
      throw new IllegalArgumentException("Duplicate target provider: " + id);
  }

  public synchronized void registerBlueprintProvider(ResourceId id, BlueprintProvider provider) {
    registerTargetProvider(id, provider);
    blueprints.put(id, provider);
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
      WorldStorage.load(storagePath, profiles, selections, enabled);
      autoBindings.clear();
      autoTrackers.clear();
      autoErrors.clear();
      activeAutoId = null;
      serverUpdates.clear();
      serverUpdateSequence = 0;
      firstCorner = null;
      secondCorner = null;
    }
    if (job != null) job.tick();
    settling.forEach(ClientJob::tick);
    settling.removeIf(ClientJob::isSettled);
    sampleActivations(minecraft);
  }

  private void sampleActivations(Minecraft minecraft) {
    if (enabled.isEmpty() || session == null || minecraft.player == null) return;
    int radius = 0;
    for (var id : enabled) {
      try {
        var bound =
            autoBindings.computeIfAbsent(
                id, key -> compileProfile(minecraft, Objects.requireNonNull(profiles.get(key))));
        if (bound.activation() != null) radius = Math.max(radius, bound.activation().radius());
      } catch (RuntimeException error) {
        autoErrors.put(id, error.getMessage());
      }
    }
    var feet = minecraft.player.blockPosition();
    var dimension = MinecraftStateCodec.id(minecraft.level.dimension().identifier());
    var section = new SectionKey(dimension, feet.getX() >> 4, feet.getY() >> 4, feet.getZ() >> 4);
    var capture =
        new MinecraftSectionSource(
                minecraft, session, new Host.Epochs(0, 0, 0, 0, 0, 0), null, selections)
            .capture(new Host.SectionCaptureKey(session, section), radius);
    if (!(capture instanceof Host.Capture.Ready ready)) return;
    for (var id : List.copyOf(enabled)) {
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
          autoErrors.put(id, error.getMessage());
        }
      }
    }
  }

  public void setCorner(Minecraft minecraft, int which) {
    requireWorld(minecraft);
    var pos = minecraft.player.blockPosition();
    if (which == 1) firstCorner = pos;
    else if (which == 2) secondCorner = pos;
    else throw new IllegalArgumentException("Corner must be 1 or 2");
  }

  /** Called after a vanilla server block-update packet has been applied to the client world. */
  public void noteServerBlockUpdate(BlockPos blockPos) {
    var minecraft = Minecraft.getInstance();
    if (!minecraft.isSameThread() || world == null || world != minecraft.level) return;
    var dimension = MinecraftStateCodec.id(world.dimension().identifier());
    if (serverUpdates.size() >= 16_384) serverUpdates.clear();
    serverUpdates.put(
        new Position(dimension, blockPos.getX(), blockPos.getY(), blockPos.getZ()),
        ++serverUpdateSequence);
  }

  long serverRevision(Position pos) {
    return serverUpdates.getOrDefault(pos, 0L);
  }

  public void saveSelection(Minecraft minecraft, String name) {
    requireWorld(minecraft);
    if (firstCorner == null || secondCorner == null)
      throw new IllegalStateException("Set both selection corners first");
    var dimension = MinecraftStateCodec.id(minecraft.level.dimension().identifier());
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
    WorldStorage.save(storagePath, profiles, selections, enabled);
  }

  public void loadProfile(String json) {
    var profile = new ProfileReader().read(json);
    profiles.put(profile.id().toString(), json);
    autoBindings.remove(profile.id().toString());
    autoTrackers.remove(profile.id().toString());
    WorldStorage.save(storagePath, profiles, selections, enabled);
  }

  public void setEnabled(Minecraft minecraft, String id, boolean value) {
    requireWorld(minecraft);
    var json = profiles.get(id);
    if (json == null) throw new IllegalArgumentException("Unknown profile: " + id);
    var bound = compileProfile(minecraft, json);
    if (bound.activation() == null)
      throw new IllegalArgumentException("Profile has no automatic activation: " + id);
    if (value) {
      enabled.add(id);
      autoBindings.put(id, bound);
      autoTrackers.put(id, new ActivationTracker());
    } else {
      enabled.remove(id);
      autoBindings.remove(id);
      autoTrackers.remove(id);
      if (id.equals(activeAutoId)) cancel();
    }
    WorldStorage.save(storagePath, profiles, selections, enabled);
  }

  /** Binds a profile to the live 26.2/26.3 registry without starting it. */
  public Profile.Bound compileProfile(Minecraft minecraft, String json) {
    requireWorld(minecraft);
    return new ProfileReader()
        .bind(
            new ProfileReader().read(json),
            new Compiler(new MinecraftRegistry(minecraft.level), 16));
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
        !blueprints.isEmpty());
  }

  /** Read-only bounded scan; the result marks sections that remain unexamined or unavailable. */
  public ClientJob.Preview preview(Minecraft minecraft, String id, int maxSections) {
    requireWorld(minecraft);
    var json = profiles.get(id);
    if (json == null) throw new IllegalArgumentException("Unknown profile: " + id);
    var profile = new ProfileReader().read(json);
    var source =
        profile.target() instanceof Profile.Source external ? providers.get(external.id()) : null;
    return new ClientJob(minecraft, session, json, source, jobSelections(profile))
        .preview(maxSections);
  }

  /** Queries a position predicate inside the saved build selection without submitting actions. */
  public ClientJob.Preview query(Minecraft minecraft, String expression, int maxSections) {
    requireWorld(minecraft);
    if (!selections.containsKey("build"))
      throw new IllegalStateException("Save a build selection before querying");
    try {
      var quoted = new ObjectMapper().writeValueAsString(expression);
      var json =
          "{\"schema\":1,\"id\":\"user:query\","
              + "\"scope\":\"selection(\\\"build\\\")\","
              + "\"select\":{\"where\":"
              + quoted
              + "},\"target\":{\"clear\":true}}";
      return new ClientJob(minecraft, session, json, null, Map.copyOf(selections))
          .preview(maxSections);
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("Invalid query expression", error);
    }
  }

  public void submit(Minecraft minecraft, String id) {
    start(minecraft, id);
  }

  public void start(Minecraft minecraft, String id) {
    requireWorld(minecraft);
    var json = profiles.get(id);
    if (json == null) throw new IllegalArgumentException("Unknown profile: " + id);
    var profile = new ProfileReader().read(json);
    var source =
        profile.target()
                instanceof io.github.billstark001.latticium.planning.Profile.Source external
            ? providers.get(external.id())
            : null;
    var jobSelections = jobSelections(profile);
    if (job != null) {
      job.cancel();
      if (!job.isSettled()) settling.add(job);
    }
    job = null;
    if (!settling.isEmpty())
      throw new IllegalStateException("Previous submitted action is still settling");
    job = new ClientJob(minecraft, session, json, source, jobSelections);
    activeAutoId = null;
  }

  private Map<String, List<Bounds>> jobSelections(Profile profile) {
    var jobSelections = new HashMap<>(selections);
    if (profile.target() instanceof Profile.Source external) {
      var blueprint = blueprints.get(external.id());
      if (blueprint != null) {
        var bounds = blueprint.finiteBounds("active_blueprint", session);
        if (bounds.isEmpty()) throw new IllegalStateException("No active blueprint selection");
        jobSelections.put("active_blueprint", bounds);
      }
    }
    return jobSelections;
  }

  public void startInline(Minecraft minecraft, String json) {
    var profile = new ProfileReader().read(json);
    loadProfile(json);
    start(minecraft, profile.id().toString());
  }

  public void pause() {
    if (job != null) job.pause();
  }

  public void resume() {
    if (job != null) job.resume();
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
    var state = job == null ? "idle" : job.status();
    return state + " enabled=" + enabled.size() + " autoErrors=" + autoErrors;
  }

  public Map<Position, String> blocked() {
    return job == null ? Map.of() : job.blocked();
  }

  private void requireWorld(Minecraft minecraft) {
    if (!minecraft.isSameThread() || minecraft.level == null || minecraft.player == null)
      throw new IllegalStateException("A client world is required");
    if (world != minecraft.level) tick(minecraft);
  }
}

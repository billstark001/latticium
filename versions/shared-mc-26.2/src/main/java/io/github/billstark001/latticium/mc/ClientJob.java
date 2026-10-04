package io.github.billstark001.latticium.mc;

import io.github.billstark001.latticium.dsl.Compiler;
import io.github.billstark001.latticium.dsl.Model.*;
import io.github.billstark001.latticium.planning.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;

/** One finite profile activation, scanned and acted on in bounded client ticks. */
public final class ClientJob {
  private record WorkSection(SectionScanner.Bounds bounds, SectionScanner.SectionKey key) {}

  /** A bounded read-only estimate; unscanned and unavailable sections remain explicit. */
  public record Preview(
      int scannedSections,
      int totalSections,
      int unavailableSections,
      int knownCandidates,
      int unknownCells) {}

  private final Minecraft minecraft;
  private final Host.SessionId session;
  private final Profile.Bound profile;
  private final Host.TargetSource targets;
  private final Map<String, List<SectionScanner.Bounds>> selections;
  private final List<WorkSection> sections;
  private final List<WorkSection> deferredSections = new ArrayList<>();
  private final MinecraftPlacementOracle oracle;
  private final MinecraftActionGateway gateway;
  private final List<Position> pending = new ArrayList<>();
  private final Set<Position> claimed = new HashSet<>();
  private final Map<Position, TargetCell> frozen = new HashMap<>();
  private final Map<Position, String> blocked = new HashMap<>();
  private final Map<Position, Long> retryAfter = new HashMap<>();
  private int nextSection;
  private int completed;
  private int submittedActions;
  private int submittedThisTick;
  private long ticks;
  private boolean paused;
  private boolean cancelled;
  private JobController active;
  private Position activePosition;

  public ClientJob(
      Minecraft minecraft,
      Host.SessionId session,
      String json,
      Host.TargetSource targets,
      Map<String, List<SectionScanner.Bounds>> selections) {
    if (!minecraft.isSameThread()) throw new IllegalStateException("Start on client thread");
    if (minecraft.level == null || minecraft.player == null)
      throw new IllegalStateException("No world or player");
    this.minecraft = minecraft;
    this.session = session;
    this.targets = targets;
    this.selections = Map.copyOf(selections);
    var reader = new ProfileReader();
    var parsed = reader.read(json);
    profile = reader.bind(parsed, new Compiler(new MinecraftRegistry(minecraft.level), 16));
    if (parsed.target() instanceof Profile.Source && targets == null)
      throw new IllegalArgumentException("Target provider unavailable");
    var feet = minecraft.player.blockPosition();
    var anchor =
        new Position(
            MinecraftStateCodec.id(minecraft.level.dimension().identifier()),
            feet.getX(),
            feet.getY(),
            feet.getZ());
    var selectionSource =
        (Host.SelectionSource)
            (name, requestedSession) ->
                session.equals(requestedSession)
                    ? this.selections.getOrDefault(name, List.of())
                    : List.of();
    var bounds = FiniteScope.bounds(parsed.scope(), anchor, selectionSource, session);
    sections = enumerate(bounds);
    oracle = new MinecraftPlacementOracle(minecraft);
    gateway =
        new MinecraftActionGateway(
            minecraft, session, this::epochs, targetSource(), profile.items(), selections);
  }

  private static List<WorkSection> enumerate(List<SectionScanner.Bounds> bounds) {
    var result = new ArrayList<WorkSection>();
    for (var box : bounds)
      for (int y = Math.floorDiv(box.minY(), 16); y <= Math.floorDiv(box.maxY(), 16); y++)
        for (int z = Math.floorDiv(box.minZ(), 16); z <= Math.floorDiv(box.maxZ(), 16); z++)
          for (int x = Math.floorDiv(box.minX(), 16); x <= Math.floorDiv(box.maxX(), 16); x++) {
            var key = new SectionScanner.SectionKey(box.dimension(), x, y, z);
            result.add(new WorkSection(box, key));
            if (result.size() > 65_536)
              throw new IllegalArgumentException("Scope exceeds 65,536 sections");
          }
    return List.copyOf(result);
  }

  private Host.Epochs epochs() {
    return new Host.Epochs(0, 0, 0, 0, 0, 0);
  }

  private Host.TargetSource targetSource() {
    if (profile.profile().target() instanceof Profile.Clear)
      return (pos, requestedSession) -> new TargetCell.Clear();
    if (profile.profile().target() instanceof Profile.Source source)
      return (pos, requestedSession) -> {
        var target = targets.target(pos, requestedSession);
        if (target instanceof TargetCell.Exact exact && isAir(exact.state()))
          return source.includeAir() ? new TargetCell.Clear() : new TargetCell.DontCare();
        return target;
      };
    return targets;
  }

  private Host.Capture capture(SectionScanner.SectionKey key) {
    return new MinecraftSectionSource(minecraft, session, epochs(), targetSource(), selections)
        .capture(
            new Host.SectionCaptureKey(session, key),
            Math.max(profile.scope().radius(), profile.select().radius()));
  }

  public void tick() {
    if (cancelled) {
      if (active != null && active.status() == JobController.Status.WAITING)
        active.observe(gateway);
      if (active == null || active.status() != JobController.Status.WAITING) gateway.close();
      return;
    }
    if (minecraft.level == null || minecraft.player == null) return;
    ticks++;
    submittedThisTick = 0;
    if (active != null) {
      active.observe(gateway);
      if (active.status() == JobController.Status.COMPLETE) finishActive();
      else if (active.status() == JobController.Status.REPLAN) active = null;
      else if (active.status() == JobController.Status.BLOCKED) {
        blocked.put(activePosition, active.reason());
        pending.remove(activePosition);
        active = null;
      } else if (active.status() == JobController.Status.READY && !paused) submitActive();
    }
    if (paused) return;
    if (active != null) return;
    if (nextSection < sections.size()) scanOne();
    else if (!deferredSections.isEmpty() && ticks % 20 == 0) scan(deferredSections.removeFirst());
    if (!pending.isEmpty()
        && submittedActions < profile.profile().policy().maxActionsPerActivation()) planNext();
  }

  private void scanOne() {
    scan(sections.get(nextSection++));
  }

  public Preview preview(int maxSections) {
    if (maxSections <= 0) throw new IllegalArgumentException("Positive preview budget required");
    var known = new HashSet<Position>();
    int unknown = 0;
    int unavailable = 0;
    int scanned = Math.min(maxSections, sections.size());
    for (int index = 0; index < scanned; index++) {
      var section = sections.get(index);
      var capture = capture(section.key());
      if (!(capture instanceof Host.Capture.Ready ready)) {
        unavailable++;
        continue;
      }
      var mask =
          new SectionScanner()
              .scanSection(
                  section.bounds(),
                  section.key(),
                  profile.scope(),
                  profile.select(),
                  ready.snapshot().facts());
      unknown += mask.unknownCount();
      var candidates = mask.trueMask();
      for (int i = candidates.nextSetBit(0); i >= 0; i = candidates.nextSetBit(i + 1))
        known.add(
            new Position(
                section.key().dimension(),
                (section.key().x() << 4) + (i & 15),
                (section.key().y() << 4) + (i >> 8),
                (section.key().z() << 4) + ((i >> 4) & 15)));
    }
    return new Preview(scanned, sections.size(), unavailable, known.size(), unknown);
  }

  private void scan(WorkSection section) {
    var result = capture(section.key());
    if (!(result instanceof Host.Capture.Ready ready)) {
      deferredSections.add(section);
      return;
    }
    var mask =
        new SectionScanner()
            .scanSection(
                section.bounds(),
                section.key(),
                profile.scope(),
                profile.select(),
                ready.snapshot().facts());
    var candidates = mask.trueMask();
    for (int i = candidates.nextSetBit(0); i >= 0; i = candidates.nextSetBit(i + 1)) {
      var pos =
          new Position(
              section.key().dimension(),
              (section.key().x() << 4) + (i & 15),
              (section.key().y() << 4) + (i >> 8),
              (section.key().z() << 4) + ((i >> 4) & 15));
      if (claimed.add(pos)) pending.add(pos);
    }
  }

  private void planNext() {
    pending.sort(comparator());
    var pos =
        pending.stream()
            .filter(p -> retryAfter.getOrDefault(p, 0L) <= ticks)
            .findFirst()
            .orElse(null);
    if (pos == null) return;
    var capture =
        capture(
            new SectionScanner.SectionKey(
                pos.dimension(), pos.x() >> 4, pos.y() >> 4, pos.z() >> 4));
    if (!(capture instanceof Host.Capture.Ready ready)) return;
    var facts = ready.snapshot().facts();
    var world = facts.world(pos);
    if (world.isEmpty() || world.get().state() == null) return;
    var current = world.get().state();
    TargetCell target = frozen.get(pos);
    if (target == null) {
      target = resolveTarget(pos, current, facts);
      if (target == null || target instanceof TargetCell.Unknown) {
        blocked.put(
            pos,
            target instanceof TargetCell.Unknown unknown ? unknown.reason() : "Target unavailable");
        retryAfter.put(pos, ticks + 20);
        return;
      }
      blocked.remove(pos);
      frozen.put(pos, target);
    }
    if (target instanceof TargetCell.DontCare || matches(current, target)) {
      pending.remove(pos);
      completed++;
      return;
    }
    Planner.Result result;
    boolean partial = false;
    if (!isAir(current)
        && profile.profile().policy().breakMode() == Profile.Policy.BreakMode.SELECTED) {
      var air = new BlockState(ResourceId.parse("minecraft:air"), Map.of());
      var proposal =
          new Planner.Proposal(
              Planner.Action.BREAK,
              air,
              Set.of(pos),
              new Planner.Cost(1, 0, 1),
              "latticium:normal_break");
      result = new Planner.Result.Ready(current, List.of(proposal), proposal.cost());
      partial = !(target instanceof TargetCell.Clear);
    } else
      result =
          new Planner()
              .plan(
                  pos,
                  current,
                  target,
                  profile.profile().policy(),
                  (at, state, goal) -> allowedPrediction(at, state, goal, facts),
                  128);
    if (result instanceof Planner.Result.Ready plan) {
      active = new JobController(session, profile.profile().policy(), pos, target, plan, partial);
      activePosition = pos;
      submitActive();
      if (active.status() == JobController.Status.READY && !active.reason().isEmpty()) {
        retryAfter.put(pos, ticks + 20);
        active = null;
      }
    } else if (result instanceof Planner.Result.NoPlan noPlan) {
      blocked.put(pos, noPlan.reason());
      retryAfter.put(pos, ticks + 40);
    }
  }

  private TargetCell resolveTarget(Position pos, BlockState current, Facts facts) {
    if (profile.profile().target() instanceof Profile.Clear) return new TargetCell.Clear();
    if (profile.profile().target() instanceof Profile.Source source) {
      var target = targets.target(pos, session);
      if (target instanceof TargetCell.Exact exact && isAir(exact.state()))
        return source.includeAir() ? new TargetCell.Clear() : new TargetCell.DontCare();
      return target;
    }
    var items = (Profile.Items) profile.profile().target();
    var amounts = new HashMap<ResourceId, Integer>();
    var inventory = minecraft.player.getInventory();
    for (int i = 0; i < inventory.getContainerSize(); i++) {
      var stack = inventory.getItem(i);
      if (!stack.isEmpty())
        amounts.merge(
            MinecraftStateCodec.id(BuiltInRegistries.ITEM.getKey(stack.getItem())),
            stack.getCount(),
            Integer::sum);
    }
    var choice =
        new MaterialSelector()
            .choose(
                pos,
                current,
                profile.items(),
                profile.states(),
                facts,
                amounts,
                items.preferred(),
                oracle);
    return switch (choice) {
      case MaterialSelector.Choice.Frozen selected -> selected.target();
      case MaterialSelector.Choice.Deferred deferred -> new TargetCell.Unknown(deferred.reason());
      case MaterialSelector.Choice.Unsupported unsupported ->
          new TargetCell.Unknown(unsupported.reason());
      case MaterialSelector.Choice.NoTarget noTarget -> new TargetCell.Unknown(noTarget.reason());
    };
  }

  private Planner.Prediction allowedPrediction(
      Position pos, BlockState current, TargetCell target, Facts facts) {
    var predicted = oracle.predict(pos, current, target);
    if (profile.items() == null || !(predicted instanceof Planner.Prediction.Proposals proposals))
      return predicted;
    var allowed = new ArrayList<Planner.Proposal>();
    boolean unknown = false;
    for (var proposal : proposals.steps()) {
      if (proposal.action() != Planner.Action.PLACE) {
        allowed.add(proposal);
        continue;
      }
      var interaction = proposal.interaction();
      if (interaction == null || interaction.inventorySlot() < 0) continue;
      var stack = minecraft.player.getInventory().getItem(interaction.inventorySlot());
      if (stack.isEmpty()) continue;
      var item = MinecraftStateCodec.id(BuiltInRegistries.ITEM.getKey(stack.getItem()));
      var membership = profile.items().contains(facts, item);
      if (membership == Truth.TRUE) allowed.add(proposal);
      else if (membership == Truth.UNKNOWN) unknown = true;
    }
    if (!allowed.isEmpty()) return new Planner.Prediction.Proposals(allowed);
    return unknown
        ? new Planner.Prediction.Unknown("Allowed material facts unavailable")
        : new Planner.Prediction.Unsupported("No allowed item predicts the exact state");
  }

  private void submitActive() {
    if (submittedThisTick >= profile.profile().policy().maxActionsPerTick()
        || submittedActions >= profile.profile().policy().maxActionsPerActivation()) return;
    var pos = activePosition;
    var capture =
        capture(
            new SectionScanner.SectionKey(
                pos.dimension(), pos.x() >> 4, pos.y() >> 4, pos.z() >> 4));
    if (!(capture instanceof Host.Capture.Ready ready)) return;
    ready
        .snapshot()
        .facts()
        .world(pos)
        .ifPresent(
            cell -> {
              if (cell.state() != null) {
                active.submit(ready.snapshot(), cell.state(), gateway);
                if (active.status() == JobController.Status.WAITING) {
                  submittedThisTick++;
                  submittedActions++;
                }
              }
            });
  }

  private void finishActive() {
    pending.remove(activePosition);
    completed++;
    active = null;
    activePosition = null;
  }

  private Comparator<Position> comparator() {
    var player = minecraft.player.blockPosition();
    Comparator<Position> yzx =
        Comparator.comparingInt(Position::y)
            .thenComparingInt(Position::z)
            .thenComparingInt(Position::x);
    var distance =
        Comparator.comparingLong(
            (Position pos) -> {
              long dx = (long) pos.x() - player.getX();
              long dy = (long) pos.y() - player.getY();
              long dz = (long) pos.z() - player.getZ();
              return dx * dx + dy * dy + dz * dz;
            });
    return switch (profile.profile().select().choose()) {
      case NEAREST -> distance.thenComparing(yzx);
      case FARTHEST -> distance.reversed().thenComparing(yzx);
      case Y_ASC -> yzx;
      case Y_DESC -> yzx.reversed();
      case SCAN -> (a, b) -> 0;
    };
  }

  private static boolean isAir(BlockState state) {
    return io.github.billstark001.latticium.dsl.Model.isVanillaAir(state);
  }

  private static boolean matches(BlockState current, TargetCell target) {
    return target instanceof TargetCell.Exact exact && current.equals(exact.state())
        || target instanceof TargetCell.Clear && isAir(current);
  }

  public String status() {
    return "scanned="
        + nextSection
        + "/"
        + sections.size()
        + " pending="
        + pending.size()
        + " completed="
        + completed
        + " actions="
        + submittedActions
        + "/"
        + profile.profile().policy().maxActionsPerActivation()
        + " deferredSections="
        + deferredSections.size()
        + " blocked="
        + blocked.size()
        + (active == null ? "" : " active=" + active.status());
  }

  public Map<Position, String> blocked() {
    return Map.copyOf(blocked);
  }

  public boolean isFinished() {
    return !cancelled
        && active == null
        && pending.isEmpty()
        && nextSection == sections.size()
        && deferredSections.isEmpty();
  }

  public void pause() {
    paused = true;
    if (active != null) active.pause();
  }

  public void resume() {
    paused = false;
    if (active != null) active.resume();
  }

  public void cancel() {
    cancelled = true;
    if (active != null) active.cancel();
    gateway.cancelFurtherWork();
    if (isSettled()) gateway.close();
  }

  public boolean isSettled() {
    return cancelled && (active == null || active.status() != JobController.Status.WAITING);
  }

  public void leaveWorld() {
    cancelled = true;
    if (active != null) active.leaveWorld();
    gateway.close();
  }
}

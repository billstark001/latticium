package io.github.billstark001.latticium.mc;

import io.github.billstark001.latticium.dsl.Compiler;
import io.github.billstark001.latticium.dsl.Model.*;
import io.github.billstark001.latticium.dsl.Parser;
import io.github.billstark001.latticium.dsl.TargetReads;
import io.github.billstark001.latticium.planning.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;

/** One finite profile activation, scanned and acted on in bounded client ticks. */
public final class ClientJob {
  private static final int MAX_JOB_SECTIONS = 65_536;
  private static final int MAX_PLANNER_NODES = 128;
  private static final long RETRY_TICKS = 20;
  private static final long NO_PLAN_RETRY_TICKS = 40;
  private static final SectionScanner SCANNER = new SectionScanner();
  private static final BlockState AIR = new BlockState(ResourceId.parse("minecraft:air"), Map.of());

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
  private final ClientTargetResolver targetResolver;
  private final Map<String, List<SectionScanner.Bounds>> selections;
  private final List<SectionScanner.SectionGroup> sections;
  private final List<SectionScanner.SectionGroup> deferredSections = new ArrayList<>();
  private final MinecraftPlacementOracle oracle;
  private final MinecraftSectionSource sectionSource;
  private final MinecraftActionGateway gateway;
  private final List<Position> pending = new ArrayList<>();
  private final Set<Position> claimed = new HashSet<>();
  private final Map<Position, TargetCell> frozen = new HashMap<>();
  private final Map<Position, BlockState> partialExpected = new HashMap<>();
  private final Map<Position, String> blocked = new HashMap<>();
  private final Map<Position, Long> retryAfter = new HashMap<>();
  private int nextSection;
  private int completed;
  private int submittedActions;
  private int submittedThisTick;
  private int unsupportedSections;
  private long ticks;
  private boolean paused;
  private boolean cancelled;
  private boolean budgetExhausted;
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
    this.selections = Map.copyOf(selections);
    var reader = new ProfileReader();
    var parsed = reader.read(json);
    profile = reader.bind(parsed, new Compiler(new MinecraftRegistry(minecraft.level), 16));
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
    if (bounds.stream().anyMatch(box -> !box.dimension().equals(anchor.dimension())))
      throw new IllegalArgumentException("Scope includes another dimension");
    sections = SCANNER.group(bounds, MAX_JOB_SECTIONS);
    oracle = new MinecraftPlacementOracle(minecraft);
    targetResolver = new ClientTargetResolver(minecraft, session, profile, targets, oracle);
    var targetSource = targetResolver.targetSource();
    var scanTargets =
        TargetReads.in(Parser.expression(parsed.select().where())) ? targetSource : null;
    sectionSource =
        new MinecraftSectionSource(minecraft, session, epochs(), scanTargets, this.selections);
    gateway =
        new MinecraftActionGateway(
            minecraft, session, this::epochs, targetSource, profile.items(), this.selections);
  }

  private Host.Epochs epochs() {
    return new Host.Epochs(0, 0, 0, 0, 0, 0);
  }

  private Host.Capture capture(SectionScanner.SectionKey key) {
    return sectionSource.capture(
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
      else if (active.status() == JobController.Status.REPLAN) {
        partialExpected.put(activePosition, active.confirmedState());
        active = null;
        activePosition = null;
      } else if (active.status() == JobController.Status.BLOCKED) {
        blocked.put(activePosition, active.reason());
        dropCandidate(activePosition);
        active = null;
        activePosition = null;
      } else if (active.status() == JobController.Status.READY && !paused) submitActive();
    }
    if (paused) return;
    if (active != null) return;
    if (submittedActions >= profile.profile().policy().maxActionsPerActivation()) {
      budgetExhausted = true;
      return;
    }
    if (!deferredSections.isEmpty() && ticks % RETRY_TICKS == 0)
      scan(deferredSections.removeFirst());
    else if (nextSection < sections.size()) scan(sections.get(nextSection++));
    if (!pending.isEmpty()
        && submittedActions < profile.profile().policy().maxActionsPerActivation()) planNext();
  }

  public Preview preview(int maxSections) {
    if (maxSections <= 0) throw new IllegalArgumentException("Positive preview budget required");
    int known = 0;
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
          SCANNER.scanGroup(section, profile.scope(), profile.select(), ready.snapshot().facts());
      unknown += mask.unknownCount();
      known += mask.trueCount();
    }
    return new Preview(scanned, sections.size(), unavailable, known, unknown);
  }

  private void scan(SectionScanner.SectionGroup section) {
    var result = capture(section.key());
    if (result instanceof Host.Capture.Unsupported) {
      unsupportedSections++;
      return;
    }
    if (!(result instanceof Host.Capture.Ready ready)) {
      deferredSections.add(section);
      return;
    }
    var mask =
        SCANNER.scanGroup(section, profile.scope(), profile.select(), ready.snapshot().facts());
    if (mask.unknownCount() > 0) deferredSections.add(section);
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
    var order = comparator();
    Position pos = null;
    for (var candidate : pending)
      if (retryAfter.getOrDefault(candidate, 0L) <= ticks
          && (pos == null || order.compare(candidate, pos) < 0)) pos = candidate;
    if (pos == null) return;
    var capture =
        capture(
            new SectionScanner.SectionKey(
                pos.dimension(), pos.x() >> 4, pos.y() >> 4, pos.z() >> 4));
    if (!(capture instanceof Host.Capture.Ready ready)) {
      retryAfter.put(pos, ticks + RETRY_TICKS);
      return;
    }
    var facts = ready.snapshot().facts();
    var world = facts.world(pos);
    if (world.isEmpty() || world.get().state() == null) {
      retryAfter.put(pos, ticks + RETRY_TICKS);
      return;
    }
    var current = world.get().state();
    var expected = partialExpected.get(pos);
    if (expected != null && !expected.equals(current)) {
      blocked.put(pos, "World state changed after a confirmed partial step");
      dropCandidate(pos);
      return;
    }
    if (expected == null) {
      var eligibility = selectionAt(profile, facts, pos);
      if (eligibility == Truth.FALSE) {
        blocked.remove(pos);
        dropCandidate(pos);
        return;
      }
      if (eligibility == Truth.UNKNOWN) {
        blocked.put(pos, "Selection facts unavailable");
        retryAfter.put(pos, ticks + RETRY_TICKS);
        return;
      }
    }
    TargetCell target = frozen.get(pos);
    if (target == null) {
      target = targetResolver.resolve(pos, current, facts);
      if (target == null || target instanceof TargetCell.Unknown) {
        blocked.put(
            pos,
            target instanceof TargetCell.Unknown unknown ? unknown.reason() : "Target unavailable");
        retryAfter.put(pos, ticks + RETRY_TICKS);
        return;
      }
      blocked.remove(pos);
      frozen.put(pos, target);
    }
    if (target instanceof TargetCell.DontCare || matches(current, target)) {
      blocked.remove(pos);
      dropCandidate(pos);
      completed++;
      return;
    }
    var remainingPolicy = profile.profile().policy().afterActions(submittedActions);
    Planner.Result result =
        new Planner()
            .plan(
                pos,
                current,
                target,
                remainingPolicy,
                (at, state, goal) -> allowedPrediction(at, state, goal, facts),
                MAX_PLANNER_NODES);
    boolean partial = false;
    if (result instanceof Planner.Result.NoPlan
        && !isAir(current)
        && remainingPolicy.breakMode() == Profile.Policy.BreakMode.SELECTED) {
      if (!ClientTargetResolver.hasReplacementBudget(target, remainingPolicy)) {
        blocked.put(pos, "Replacement needs at least two remaining actions");
        dropCandidate(pos);
        return;
      }
      if (target instanceof TargetCell.Exact exact
          && !oracle.hasHotbarBlockItem(exact.state().block())) {
        blocked.put(pos, "No hotbar block item for replacement target");
        retryAfter.put(pos, ticks + RETRY_TICKS);
        return;
      }
      var proposal =
          new Planner.Proposal(
              Planner.Action.BREAK,
              AIR,
              Set.of(pos),
              new Planner.Cost(1, 0, 1),
              "latticium:normal_break");
      result = new Planner.Result.Ready(current, List.of(proposal), proposal.cost());
      partial = !(target instanceof TargetCell.Clear);
    }
    if (result instanceof Planner.Result.Ready plan) {
      blocked.remove(pos);
      active = new JobController(session, remainingPolicy, pos, target, plan, partial);
      activePosition = pos;
      submitActive();
    } else if (result instanceof Planner.Result.NoPlan noPlan) {
      blocked.put(pos, noPlan.reason());
      boolean budgetLimited =
          submittedActions > 0
              && new Planner()
                      .plan(
                          pos,
                          current,
                          target,
                          profile.profile().policy(),
                          (at, state, goal) -> allowedPrediction(at, state, goal, facts),
                          MAX_PLANNER_NODES)
                  instanceof Planner.Result.Ready;
      if (budgetLimited) {
        blocked.put(pos, "Remaining action budget too small");
        dropCandidate(pos);
      } else retryAfter.put(pos, ticks + NO_PLAN_RETRY_TICKS);
    } else if (result instanceof Planner.Result.Deferred deferred) {
      blocked.put(pos, deferred.reason());
      retryAfter.put(pos, ticks + RETRY_TICKS);
    }
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
    var level = minecraft.level;
    if (level == null || !level.hasChunk(pos.x() >> 4, pos.z() >> 4)) {
      retryAfter.put(pos, ticks + RETRY_TICKS);
      if (active.confirmedSteps() > 0) partialExpected.put(pos, active.confirmedState());
      active = null;
      activePosition = null;
      return;
    }
    var current =
        MinecraftStateCodec.state(level.getBlockState(new BlockPos(pos.x(), pos.y(), pos.z())));
    active.submit(new Host.Snapshot(session, epochs(), null), current, gateway);
    if (active.status() == JobController.Status.WAITING) {
      submittedThisTick++;
      submittedActions++;
    } else if (active.status() == JobController.Status.READY && !active.reason().isEmpty()) {
      retryAfter.put(pos, ticks + RETRY_TICKS);
      if (active.confirmedSteps() > 0) partialExpected.put(pos, active.confirmedState());
      active = null;
      activePosition = null;
    }
  }

  private void finishActive() {
    dropCandidate(activePosition);
    completed++;
    active = null;
    activePosition = null;
  }

  private void dropCandidate(Position pos) {
    pending.remove(pos);
    frozen.remove(pos);
    partialExpected.remove(pos);
    retryAfter.remove(pos);
  }

  static Truth selectionAt(Profile.Bound profile, Facts facts, Position pos) {
    var inScope = profile.scope().at(facts, pos);
    return inScope == Truth.FALSE ? Truth.FALSE : inScope.and(profile.select().at(facts, pos));
  }

  private Comparator<Position> comparator() {
    var player = minecraft.player.blockPosition();
    var origin =
        new Position(
            MinecraftStateCodec.id(minecraft.level.dimension().identifier()),
            player.getX(),
            player.getY(),
            player.getZ());
    Comparator<Position> yzx =
        Comparator.comparingInt(Position::y)
            .thenComparingInt(Position::z)
            .thenComparingInt(Position::x);
    Comparator<Position> distance = (a, b) -> PositionDistances.compare(a, b, origin);
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
    var example =
        blocked.entrySet().stream()
            .min(Comparator.comparing(entry -> entry.getKey().toString()))
            .orElse(null);
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
        + " unsupportedSections="
        + unsupportedSections
        + " blocked="
        + blocked.size()
        + (example == null ? "" : " firstBlocked=" + example.getKey() + ": " + example.getValue())
        + (budgetExhausted ? " budgetExhausted=true" : "")
        + (active == null ? "" : " active=" + active.status());
  }

  public Map<Position, String> blocked() {
    return Map.copyOf(blocked);
  }

  public boolean isFinished() {
    return !cancelled
        && active == null
        && (budgetExhausted
            || pending.isEmpty() && nextSection == sections.size() && deferredSections.isEmpty());
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

  /** A submitted action must settle before another job can replace this one. */
  public boolean hasPendingAction() {
    return active != null && active.status() == JobController.Status.WAITING;
  }

  public void leaveWorld() {
    cancelled = true;
    if (active != null) active.leaveWorld();
    gateway.close();
  }
}

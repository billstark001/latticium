package io.github.billstark001.latticium.mc;

import io.github.billstark001.latticium.dsl.Compiler;
import io.github.billstark001.latticium.dsl.Model.*;
import io.github.billstark001.latticium.planning.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

/** One finite profile activation, scanned and acted on in bounded client ticks. */
public final class ClientJob {
  private static final int MAX_PLANNER_NODES = 128;
  private static final long RETRY_TICKS = 20;
  private static final long NO_PLAN_RETRY_TICKS = 40;
  private static final SectionScanner SCANNER = new SectionScanner();
  private static final BlockState AIR = new BlockState(ResourceId.parse("minecraft:air"), Map.of());
  private final Host.Epochs epochs;

  /** A bounded read-only estimate; unscanned and unavailable sections remain explicit. */
  public record Preview(
      int scannedSections,
      int totalSections,
      int unavailableSections,
      int knownCandidates,
      int unknownCells) {}

  private final Minecraft minecraft;
  private final ClientLevel world;
  private final ClientMaterialGate materialGate;
  private final Host.SessionId session;
  private final Profile.Bound profile;
  private final ClientTargetResolver targetResolver;
  private final Map<String, List<SectionScanner.Bounds>> selections;
  private final RefreshCoordinator refresh;
  private final MinecraftPlacementOracle oracle;
  private final MinecraftSectionSource sectionSource;
  private final MinecraftSectionSource planningSource;
  private final MinecraftActionGateway gateway;
  private final List<Position> pending = new ArrayList<>();
  private final Set<Position> claimed = new HashSet<>();
  private final Map<Position, TargetCell> frozen = new HashMap<>();
  private final Map<Position, BlockState> partialExpected = new HashMap<>();
  private final Map<Position, String> blocked = new HashMap<>();
  private final Map<Position, Long> retryAfter = new HashMap<>();
  private int completed;
  private int submittedActions;
  private int submittedThisTick;
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
    world = minecraft.level;
    this.session = session;
    epochs = ClientRegistryState.epochs();
    this.selections = SelectionBounds.copy(selections);
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
    refresh = new RefreshCoordinator(profile, session, this.selections, anchor);
    oracle = new MinecraftPlacementOracle(minecraft);
    materialGate = new ClientMaterialGate(minecraft, oracle, profile.items());
    targetResolver = new ClientTargetResolver(minecraft, session, profile, targets, oracle);
    var targetSource = targetResolver.targetSource();
    sectionSource =
        new MinecraftSectionSource(
            minecraft, session, epochs, targetSource, this.selections, profile.scanDependencies());
    planningSource =
        new MinecraftSectionSource(
            minecraft,
            session,
            epochs,
            targetSource,
            this.selections,
            profile.planningDependencies());
    gateway =
        new MinecraftActionGateway(
            minecraft,
            session,
            ClientRegistryState::epochs,
            targetSource,
            profile.items(),
            this.selections);
  }

  private Host.Capture capture(SectionScanner.SectionKey key) {
    return sectionSource.capture(new Host.SectionCaptureKey(session, key), refresh.readRadius());
  }

  public void tick() {
    if (minecraft.level != world) {
      leaveWorld();
      return;
    }
    if (!cancelled && !epochs.equals(ClientRegistryState.epochs())) cancel();
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
    if (active != null) return;
    var feet = minecraft.player.blockPosition();
    refresh.playerMoved(
        new Position(
            MinecraftStateCodec.id(minecraft.level.dimension().identifier()),
            feet.getX(),
            feet.getY(),
            feet.getZ()));
    refresh.periodicRefresh(ticks);
    for (var dirty : refresh.nextDirtyPositions(ticks, 8)) refreshCandidate(dirty);
    var next = refresh.nextSection(ticks);
    if (next != null) scan(next);
    if (submittedActions >= profile.profile().policy().maxActionsPerActivation()) {
      budgetExhausted = true;
      return;
    }
    if (!paused
        && !pending.isEmpty()
        && submittedActions < profile.profile().policy().maxActionsPerActivation()) planNext();
  }

  public Preview preview(int maxSections) {
    return ClientJobPreview.scan(profile, refresh.sections(), this::capture, maxSections);
  }

  private void scan(SectionScanner.SectionGroup section) {
    var result = capture(section.key());
    if (result instanceof Host.Capture.Unsupported) {
      refresh.unsupportedSection();
      return;
    }
    if (!(result instanceof Host.Capture.Ready ready)) {
      refresh.deferSection(section);
      return;
    }
    var mask =
        SCANNER.scanGroup(section, profile.scope(), profile.select(), ready.snapshot().facts());
    if (mask.unknownCount() > 0) refresh.deferSection(section);
    var candidates = mask.trueMask();
    for (int i = candidates.nextSetBit(0); i >= 0; i = candidates.nextSetBit(i + 1)) {
      var pos =
          new Position(
              section.key().dimension(),
              (section.key().x() << 4) + (i & 15),
              (section.key().y() << 4) + (i >> 8),
              (section.key().z() << 4) + ((i >> 4) & 15));
      if (claimed.add(pos)) pending.add(pos);
      else if (!partialExpected.containsKey(pos)) frozen.remove(pos);
    }
  }

  private void refreshCandidate(Position pos) {
    if (pos.equals(activePosition)) {
      refresh.deferPosition(pos, ticks);
      return;
    }
    // A confirmed first step owns this candidate even when its source predicate became false.
    if (partialExpected.containsKey(pos)) return;
    var capture = sectionSource.captureAround(session, pos, refresh.readRadius());
    if (!(capture instanceof Host.Capture.Ready ready)) {
      refresh.deferPosition(pos, ticks);
      return;
    }
    var eligible = selectionAt(profile, ready.snapshot().facts(), pos);
    if (eligible == Truth.TRUE) {
      if (claimed.add(pos)) pending.add(pos);
      else if (!partialExpected.containsKey(pos)) frozen.remove(pos);
      blocked.remove(pos);
    } else if (eligible == Truth.FALSE) {
      if (claimed.contains(pos)) dropCandidate(pos);
      blocked.remove(pos);
    } else refresh.deferPosition(pos, ticks);
  }

  private void planNext() {
    var player = minecraft.player.blockPosition();
    var order =
        ClientJobView.order(
            profile.profile().select().choose(),
            new Position(
                MinecraftStateCodec.id(minecraft.level.dimension().identifier()),
                player.getX(),
                player.getY(),
                player.getZ()));
    Position pos = null;
    for (var candidate : pending)
      if (retryAfter.getOrDefault(candidate, 0L) <= ticks
          && (pos == null || order.compare(candidate, pos) < 0)) pos = candidate;
    if (pos == null) return;
    var capture = planningSource.captureAround(session, pos, profile.planningRadius());
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
    if (target instanceof TargetCell.DontCare || ClientJobView.matches(current, target)) {
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
                materialGate.forFacts(facts),
                MAX_PLANNER_NODES);
    boolean partial = false;
    if (result instanceof Planner.Result.NoPlan
        && !ClientJobView.isAir(current)
        && remainingPolicy.breakMode() == Profile.Policy.BreakMode.SELECTED) {
      var breakDeferral = oracle.breakDeferral(pos);
      if (breakDeferral != null) {
        blocked.put(pos, breakDeferral);
        retryAfter.put(pos, ticks + RETRY_TICKS);
        return;
      }
      if (!ClientTargetResolver.hasReplacementBudget(target, remainingPolicy)) {
        blocked.put(pos, "Replacement needs at least two remaining actions");
        dropCandidate(pos);
        return;
      }
      if (target instanceof TargetCell.Exact exact
          && !oracle.hasPlacementAfterBreak(pos, exact.state())) {
        blocked.put(pos, "No verifiable post-break placement for exact target");
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
                          materialGate.forFacts(facts),
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

  private void submitActive() {
    if (submittedThisTick >= profile.profile().policy().maxActionsPerTick()
        || submittedActions >= profile.profile().policy().maxActionsPerActivation()) return;
    var pos = activePosition;
    var level = minecraft.level;
    if (level == null || !level.getChunkSource().hasChunk(pos.x() >> 4, pos.z() >> 4)) {
      retryAfter.put(pos, ticks + RETRY_TICKS);
      if (active.confirmedSteps() > 0) partialExpected.put(pos, active.confirmedState());
      active = null;
      activePosition = null;
      return;
    }
    if (active.confirmedSteps() == 0 && !partialExpected.containsKey(pos)) {
      var check = sectionSource.captureAround(session, pos, refresh.readRadius());
      if (!(check instanceof Host.Capture.Ready ready)
          || selectionAt(profile, ready.snapshot().facts(), pos) != Truth.TRUE) {
        retryAfter.put(pos, ticks + RETRY_TICKS);
        active = null;
        activePosition = null;
        return;
      }
    }
    var current =
        MinecraftStateCodec.state(level.getBlockState(new BlockPos(pos.x(), pos.y(), pos.z())));
    active.submit(new Host.Snapshot(session, epochs, null), current, gateway);
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
    claimed.remove(pos);
    frozen.remove(pos);
    partialExpected.remove(pos);
    retryAfter.remove(pos);
  }

  static Truth selectionAt(Profile.Bound profile, Facts facts, Position pos) {
    var inScope = profile.scope().at(facts, pos);
    return inScope == Truth.FALSE ? Truth.FALSE : inScope.and(profile.select().at(facts, pos));
  }

  public String status() {
    return ClientJobView.status(snapshot(), blocked, active);
  }

  public ClientJobSnapshot snapshot() {
    return ClientJobView.snapshot(
        profile.profile(),
        refresh,
        pending.size(),
        completed,
        submittedActions,
        blocked.size(),
        budgetExhausted,
        paused,
        active);
  }

  public Map<Position, String> blocked() {
    return Map.copyOf(blocked);
  }

  public boolean isFinished() {
    return ClientJobView.isFinished(cancelled, active, budgetExhausted, refresh, pending.isEmpty());
  }

  public void noteBlockUpdate(Position pos) {
    if (!cancelled && !budgetExhausted) refresh.blockChanged(pos);
  }

  public void refresh() {
    if (cancelled) throw new IllegalStateException("Job was cancelled");
    if (budgetExhausted && active == null) {
      submittedActions = 0;
      budgetExhausted = false;
    }
    frozen.keySet().removeIf(pos -> !partialExpected.containsKey(pos));
    retryAfter.clear();
    blocked.clear();
    var feet = minecraft.player.blockPosition();
    refresh.requestFullRefresh(
        new Position(
            MinecraftStateCodec.id(minecraft.level.dimension().identifier()),
            feet.getX(),
            feet.getY(),
            feet.getZ()));
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

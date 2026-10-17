package io.github.billstark001.latticium.mc;

import io.github.billstark001.latticium.dsl.Model.*;
import io.github.billstark001.latticium.planning.*;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

/**
 * One finite profile activation, scanned and acted on in bounded client ticks. All control and
 * snapshot access belongs to the client thread; the host calls tick once per client tick. A paused
 * or cancelled job still observes an accepted action until its receipt settles.
 */
public final class ClientJob {
  private final long retryTicks;
  private static final long NO_PLAN_RETRY_TICKS = 40;
  private static final SectionScanner SCANNER = new SectionScanner();
  private final Host.Epochs epochs;
  private ClientIntent intent;
  private ClientProof activeProof;

  /** A bounded read-only estimate; unscanned and unavailable sections remain explicit. */
  public record Preview(
      int scannedSections,
      int totalSections,
      int unavailableSections,
      int knownCandidates,
      int unknownCells) {}

  private final Minecraft minecraft;
  private final ClientLevel world;
  private final ClientPlanning planning;
  private final Host.SessionId session;
  private final Profile.Bound profile;
  private final ClientTargetResolver targetResolver;
  private final Map<String, List<SectionScanner.Bounds>> selections;
  private final RefreshCoordinator refresh;
  private final MinecraftSectionSource sectionSource;
  private final MinecraftSectionSource planningSource;
  private final MinecraftActionGateway gateway;
  private final ClientScheduling scheduling;
  private final ClientCandidates candidates;
  private final Map<Position, String> blocked;
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
    this(minecraft, session, json, targets, selections, new MinecraftPlacementOracle(minecraft));
  }

  ClientJob(
      Minecraft minecraft,
      Host.SessionId session,
      String json,
      Host.TargetSource targets,
      Map<String, List<SectionScanner.Bounds>> selections,
      MinecraftPlacementOracle oracle) {
    if (!minecraft.isSameThread()) throw new IllegalStateException("Start on client thread");
    if (minecraft.level == null || minecraft.player == null)
      throw new IllegalStateException("No world or player");
    this.minecraft = minecraft;
    world = minecraft.level;
    this.session = session;
    var setup = ClientJobSetup.create(minecraft, session, json, targets, selections, oracle);
    epochs = setup.epochs();
    this.selections = setup.selections();
    profile = setup.profile();
    var parsed = profile.profile();
    candidates =
        new ClientCandidates(
            parsed.execution().planning().maxCandidates(),
            parsed.execution().consistency().targets() == Execution.Targets.PINNED);
    blocked = ClientScheduling.diagnostics(parsed.execution().planning().maxCandidates());
    retryTicks = parsed.execution().planning().retryTicks();
    intent = new ClientIntent(parsed);
    refresh = setup.refresh();
    planning = setup.planning();
    targetResolver = setup.targets();
    var targetSource = targetResolver.targetSource();
    sectionSource = setup.scans();
    planningSource = setup.plans();
    gateway =
        new MinecraftActionGateway(
            minecraft,
            session,
            ClientRegistryState::epochs,
            targetSource,
            profile.items(),
            this.selections);
    gateway.predictionValidity(() -> activeProof != null && intent.valid() && activeProof.valid());
    gateway.transientConfirmation(
        parsed.execution().dynamics().motion() == Execution.Motion.SETTLING);
    scheduling =
        new ClientScheduling(
            minecraft, parsed, candidates, refresh, planning, targetResolver, blocked);
  }

  private Host.Capture capture(SectionScanner.SectionKey key) {
    return sectionSource.capture(new Host.SectionCaptureKey(session, key), refresh.readRadius());
  }

  public void tick() {
    if (minecraft.level != world) {
      leaveWorld();
      return;
    }
    if (!cancelled
        && (epochs.registry() != ClientRegistryState.epochs().registry() || !intent.valid()))
      cancel();
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
        candidates.confirmed(activePosition, active.confirmedState());
        active = null;
        activePosition = null;
      } else if (active.status() == JobController.Status.BLOCKED) {
        blocked.put(activePosition, active.reason());
        if (active.confirmedSteps() == 0 && candidates.expected(activePosition) == null) {
          candidates.reconsider(activePosition);
          candidates.retry(activePosition, ticks + retryTicks);
        } else dropCandidate(activePosition);
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
            feet.getZ()),
        minecraft.player.blockInteractionRange() + 2);
    refresh.periodicRefresh(ticks);
    for (var dirty : refresh.nextDirtyPositions(ticks, 8)) refreshCandidate(dirty);
    var next = refresh.nextSection(ticks);
    if (next != null) scan(next);
    if (submittedActions >= profile.profile().policy().maxActionsPerActivation()) {
      budgetExhausted = true;
      return;
    }
    if (!paused
        && !candidates.isEmpty()
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
    if (ready.snapshot().inputs() != null
        && !ClientRevisions.LEDGER.valid(
            ready.snapshot().inputs(), session, minecraft.level.getGameTime())) {
      refresh.deferSection(section);
      return;
    }
    if (mask.unknownCount() > 0) refresh.deferSection(section);
    var selected = mask.trueMask();
    var order = scheduling.order();
    for (int i = selected.nextSetBit(0); i >= 0; i = selected.nextSetBit(i + 1)) {
      var pos =
          new Position(
              section.key().dimension(),
              (section.key().x() << 4) + (i & 15),
              (section.key().y() << 4) + (i >> 8),
              (section.key().z() << 4) + ((i >> 4) & 15));
      scheduling.admit(pos, order, ticks, ready.snapshot().inputs());
    }
  }

  private void refreshCandidate(Position pos) {
    if (pos.equals(activePosition)) {
      refresh.deferPosition(pos, ticks);
      return;
    }
    // A confirmed first step owns this candidate even when its source predicate became false.
    if (candidates.expected(pos) != null) return;
    var capture = sectionSource.captureAround(session, pos, refresh.readRadius());
    if (!(capture instanceof Host.Capture.Ready ready)) {
      refresh.deferPosition(pos, ticks);
      return;
    }
    var eligible = selectionAt(profile, ready.snapshot().facts(), pos);
    if (eligible == Truth.TRUE) {
      scheduling.admit(pos, scheduling.order(), ticks, ready.snapshot().inputs());
      blocked.remove(pos);
    } else if (eligible == Truth.FALSE) {
      dropCandidate(pos);
      blocked.remove(pos);
    } else refresh.deferPosition(pos, ticks);
  }

  private void planNext() {
    Position pos = candidates.next(scheduling.order(), ticks);
    if (pos == null) return;
    var capture = planningSource.captureAround(session, pos, profile.planningRadius());
    if (!(capture instanceof Host.Capture.Ready ready)) {
      candidates.retry(pos, ticks + retryTicks);
      return;
    }
    var facts = ready.snapshot().facts();
    var world = facts.world(pos);
    if (world.isEmpty() || world.get().state() == null) {
      candidates.retry(pos, ticks + retryTicks);
      return;
    }
    var current = world.get().state();
    var expected = candidates.expected(pos);
    if (expected != null && !expected.equals(current)) {
      if (profile.profile().execution().dynamics().motion() == Execution.Motion.SETTLING) {
        candidates.unconfirm(pos);
        candidates.retry(pos, ticks + profile.profile().execution().dynamics().settleTicks());
        blocked.put(pos, "Waiting for a fresh selection after external settling");
        return;
      }
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
        candidates.retry(pos, ticks + retryTicks);
        return;
      }
    }
    TargetCell target = candidates.target(pos);
    if (target == null) {
      target = targetResolver.resolve(pos, current, facts);
      if (target == null || target instanceof TargetCell.Unknown) {
        blocked.put(
            pos,
            target instanceof TargetCell.Unknown unknown ? unknown.reason() : "Target unavailable");
        candidates.retry(pos, ticks + retryTicks);
        return;
      }
      blocked.remove(pos);
      candidates.freeze(pos, target);
      targetResolver.pin(pos, target);
    }
    if (target instanceof TargetCell.DontCare || ClientJobView.matches(current, target)) {
      blocked.remove(pos);
      if (profile.profile().execution().dynamics().repeat() == Execution.Repeat.WHILE_SELECTED) {
        candidates.retry(pos, ticks + profile.profile().execution().dynamics().settleTicks());
        return;
      }
      candidates.satisfy(pos);
      dropCandidate(pos);
      completed++;
      return;
    }
    var remainingPolicy = profile.profile().policy().afterActions(submittedActions);
    var proof =
        ClientProof.capture(minecraft, session, profile, pos, targetResolver.targetSource());
    var outcome = planning.plan(pos, current, target, remainingPolicy, facts);
    var result = outcome.result();
    if (result instanceof Planner.Result.Ready plan) {
      var narrowed = proof.forPlan(profile, pos, plan, outcome.preconditions());
      if (narrowed.isEmpty()) {
        blocked.put(pos, "Prediction dependencies exceed the captured window");
        candidates.retry(pos, ticks + retryTicks);
        return;
      }
      activeProof = narrowed.get();
      blocked.remove(pos);
      active =
          new JobController(
              session,
              remainingPolicy,
              pos,
              target,
              plan.withInputs(activeProof.stamp()),
              outcome.partial());
      activePosition = pos;
      submitActive();
    } else if (result instanceof Planner.Result.NoPlan noPlan) {
      blocked.put(pos, noPlan.reason());
      if (outcome.budgetExhausted()) dropCandidate(pos);
      else candidates.retry(pos, ticks + NO_PLAN_RETRY_TICKS);
    } else if (result instanceof Planner.Result.Deferred deferred) {
      blocked.put(pos, deferred.reason());
      candidates.retry(pos, ticks + retryTicks);
    }
  }

  private void submitActive() {
    if (submittedThisTick >= profile.profile().policy().maxActionsPerTick()
        || submittedActions >= profile.profile().policy().maxActionsPerActivation()) return;
    var pos = activePosition;
    if (activeProof == null || !activeProof.valid()) {
      if (active.confirmedSteps() > 0) candidates.confirmed(pos, active.confirmedState());
      candidates.retry(pos, ticks + 1);
      active = null;
      activePosition = null;
      activeProof = null;
      return;
    }
    var level = minecraft.level;
    if (level == null || !level.getChunkSource().hasChunk(pos.x() >> 4, pos.z() >> 4)) {
      candidates.retry(pos, ticks + retryTicks);
      if (active.confirmedSteps() > 0) candidates.confirmed(pos, active.confirmedState());
      active = null;
      activePosition = null;
      return;
    }
    if (active.confirmedSteps() == 0 && candidates.expected(pos) == null) {
      var check = sectionSource.captureAround(session, pos, refresh.readRadius());
      if (!(check instanceof Host.Capture.Ready ready)
          || selectionAt(profile, ready.snapshot().facts(), pos) != Truth.TRUE) {
        candidates.retry(pos, ticks + retryTicks);
        active = null;
        activePosition = null;
        return;
      }
    }
    var current =
        MinecraftStateCodec.state(level.getBlockState(new BlockPos(pos.x(), pos.y(), pos.z())));
    active.submit(
        new Host.Snapshot(session, ClientRegistryState.epochs(), null, activeProof.stamp()),
        current,
        gateway);
    if (active.status() == JobController.Status.WAITING) {
      submittedThisTick++;
      submittedActions++;
    } else if (active.status() == JobController.Status.READY && !active.reason().isEmpty()) {
      blocked.put(pos, active.reason());
      candidates.retry(pos, ticks + retryTicks);
      if (active.confirmedSteps() > 0) candidates.confirmed(pos, active.confirmedState());
      active = null;
      activePosition = null;
    }
  }

  private void finishActive() {
    var position = activePosition;
    boolean repeat =
        profile.profile().execution().dynamics().repeat() == Execution.Repeat.WHILE_SELECTED;
    if (!repeat) candidates.satisfy(position);
    dropCandidate(position);
    completed++;
    active = null;
    activePosition = null;
    activeProof = null;
    if (repeat) {
      scheduling.admit(position, scheduling.order(), ticks);
      candidates.retry(position, ticks + profile.profile().execution().dynamics().settleTicks());
    }
  }

  private void dropCandidate(Position pos) {
    candidates.remove(pos);
    planning.forget(pos);
    targetResolver.forget(pos);
  }

  void acceptCurrentIntent() {
    intent = new ClientIntent(profile.profile());
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
        candidates.size(),
        completed,
        submittedActions,
        blocked.size(),
        budgetExhausted,
        paused,
        active,
        cancelled);
  }

  public Map<Position, String> blocked() {
    return Map.copyOf(blocked);
  }

  public boolean isFinished() {
    return ClientJobView.isFinished(
        cancelled, active, budgetExhausted, refresh, candidates.isEmpty());
  }

  public void noteBlockUpdate(Position pos) {
    if (!cancelled && !budgetExhausted) refresh.blockChanged(pos, candidates);
  }

  public void refresh() {
    if (cancelled) throw new IllegalStateException("Job was cancelled");
    if (budgetExhausted && active == null) {
      submittedActions = 0;
      budgetExhausted = false;
    }
    candidates.refresh();
    planning.clear();
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

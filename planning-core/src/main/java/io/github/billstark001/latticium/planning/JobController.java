package io.github.billstark001.latticium.planning;

import static io.github.billstark001.latticium.dsl.Model.isVanillaAir;

import io.github.billstark001.latticium.dsl.Model.*;
import java.util.List;
import java.util.Objects;

/** Single-session, single-candidate action lifecycle. The caller supplies fresh snapshots. */
public final class JobController {
  public enum Status {
    READY,
    WAITING,
    PAUSED,
    COMPLETE,
    BLOCKED,
    CANCELLED
  }

  private final Host.SessionId session;
  private final Profile.Policy policy;
  private final Position position;
  private final TargetCell target;
  private final BlockState initial;
  private final List<Planner.Proposal> steps;
  private int next;
  private Host.Receipt receipt;
  private Status status = Status.READY;
  private String reason = "";
  private boolean pauseRequested;
  private boolean cancelRequested;

  public JobController(
      Host.SessionId session,
      Profile.Policy policy,
      Position position,
      TargetCell target,
      Planner.Result.Ready plan) {
    this.session = Objects.requireNonNull(session);
    this.policy = Objects.requireNonNull(policy);
    this.position = Objects.requireNonNull(position);
    this.target = Objects.requireNonNull(target);
    this.initial = Objects.requireNonNull(plan.initial());
    this.steps = plan.steps();
    if (steps.isEmpty()) throw new IllegalArgumentException("Empty action plan");
    if (reaches(initial, target))
      throw new IllegalArgumentException("Action plan starts at its target");
    BlockState before = initial;
    for (int i = 0; i < steps.size(); i++) {
      var step = steps.get(i);
      if (step.result().equals(before))
        throw new IllegalArgumentException("Action plan contains an unchanged state");
      if (i < steps.size() - 1 && reaches(step.result(), target))
        throw new IllegalArgumentException("Action plan continues after reaching its target");
      before = step.result();
    }
    if (!reaches(steps.getLast().result(), target))
      throw new IllegalArgumentException("Action plan does not reach its target");
    long actions = 0;
    for (var step : steps) actions += step.cost().actions();
    if (actions > policy.maxActionsPerActivation())
      throw new IllegalArgumentException("Action plan exceeds activation budget");
  }

  private static boolean reaches(BlockState state, TargetCell target) {
    return switch (target) {
      case TargetCell.Exact exact -> exact.state().equals(state);
      case TargetCell.Clear ignored -> isVanillaAir(state);
      default -> false;
    };
  }

  public Status status() {
    return status;
  }

  public String reason() {
    return reason;
  }

  public int confirmedSteps() {
    return next;
  }

  /** Pauses before the next submission; an accepted action still waits for observation. */
  public void pause() {
    if (status == Status.READY) status = Status.PAUSED;
    else if (status == Status.WAITING) pauseRequested = true;
  }

  /** Clears a pending pause and permits the next submission after observation. */
  public void resume() {
    pauseRequested = false;
    if (status == Status.PAUSED) status = Status.READY;
  }

  /** Cancels future submissions while allowing an accepted action to settle. */
  public void cancel() {
    if (status != Status.COMPLETE) {
      cancelRequested = true;
      reason = "Cancelled";
      if (status != Status.WAITING) status = Status.CANCELLED;
    }
  }

  /** Invalidates the session immediately, discarding any outstanding receipt. */
  public void leaveWorld() {
    if (status != Status.COMPLETE) {
      status = Status.CANCELLED;
      reason = "Session ended";
      receipt = null;
    }
  }

  /** Submits the next step for the original session after rechecking the prior result. */
  public void submit(Host.Snapshot fresh, BlockState current, Host.ActionGateway gateway) {
    if (status != Status.READY) return;
    if (!session.equals(fresh.session())) {
      status = Status.BLOCKED;
      reason = "Session changed";
      return;
    }
    if (next >= steps.size()) {
      status = Status.COMPLETE;
      return;
    }
    BlockState expected = next == 0 ? initial : steps.get(next - 1).result();
    if (!expected.equals(current)) {
      status = Status.BLOCKED;
      reason = "World state changed since planning or confirmation";
      return;
    }
    var step = steps.get(next);
    if (!step.affectsOnly(position)) {
      status = Status.BLOCKED;
      reason = "Step effects exceed the single-cell job";
      return;
    }
    if (step.action() == Planner.Action.BREAK
        && policy.breakMode() == Profile.Policy.BreakMode.DENY) {
      status = Status.BLOCKED;
      reason = "Breaking denied";
      return;
    }
    var submission =
        gateway.submit(
            step,
            new Host.Preconditions(session, fresh.epochs(), position, current, target),
            policy);
    if (submission instanceof Host.Submission.Accepted accepted) {
      if (!session.equals(accepted.receipt().session())) {
        status = Status.BLOCKED;
        reason = "Receipt session mismatch";
        return;
      }
      receipt = accepted.receipt();
      status = Status.WAITING;
      reason = "";
    } else if (submission instanceof Host.Submission.Stale stale) {
      status = Status.BLOCKED;
      reason = stale.reason();
    } else if (submission instanceof Host.Submission.Rejected rejected) {
      status = Status.BLOCKED;
      reason = rejected.reason();
    } else reason = ((Host.Submission.Deferred) submission).reason();
  }

  /** Advances a submitted step only after the observation source confirms its result. */
  public void observe(Host.ObservationSource source) {
    if (status != Status.WAITING) return;
    var observation = source.observe(receipt, steps.get(next).result());
    switch (observation) {
      case CONFIRMED -> {
        receipt = null;
        next++;
        status =
            cancelRequested
                ? Status.CANCELLED
                : next == steps.size()
                    ? Status.COMPLETE
                    : pauseRequested ? Status.PAUSED : Status.READY;
      }
      case STILL_PENDING -> {}
      case CONTRADICTED, TIMED_OUT -> {
        receipt = null;
        status = cancelRequested ? Status.CANCELLED : Status.BLOCKED;
        reason = observation.name();
      }
    }
  }
}

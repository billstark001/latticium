package io.github.billstark001.latticium.planning;

import io.github.billstark001.latticium.dsl.Model.BlockState;
import java.util.Objects;

/** Server-confirmation boundary for one accepted action, independent of client prediction. */
public record ActionConfirmation(
    BlockState initial, BlockState expected, long startedTick, long serverRevision) {
  public ActionConfirmation {
    Objects.requireNonNull(initial, "initial");
    Objects.requireNonNull(expected, "expected");
  }

  /**
   * A matching client prediction alone cannot confirm an action. An authoritative change to a third
   * state contradicts it, so a continuing break must stop before damaging the new block. Null
   * current state prevents confirmation; an authoritative contradiction still terminates the
   * action, otherwise it waits or times out.
   */
  public Host.Observation observe(
      BlockState current, ServerUpdates.Update update, long tick, long timeoutTicks) {
    if (timeoutTicks < 0) throw new IllegalArgumentException("Negative confirmation timeout");
    if (update != null && update.revision() > serverRevision) {
      if (!expected.equals(update.state()) && !initial.equals(update.state()))
        return Host.Observation.CONTRADICTED;
      if (expected.equals(update.state()) && expected.equals(current))
        return Host.Observation.CONFIRMED;
    }
    return tick - startedTick > timeoutTicks
        ? Host.Observation.TIMED_OUT
        : Host.Observation.STILL_PENDING;
  }

  /**
   * An opt-in transient operation confirms that the expected server transition occurred, not that
   * its result remained stable. An unloaded cell, forgotten payload or local prediction is never
   * evidence. The caller stops a continuing break immediately after this confirmation.
   */
  public Host.Observation observeTransition(
      BlockState current,
      ServerUpdates.Update latest,
      ServerUpdates.Update expectedPayload,
      long tick,
      long timeoutTicks) {
    if (current != null
        && latest != null
        && expectedPayload != null
        && expectedPayload.revision() > serverRevision
        && expectedPayload.revision() <= latest.revision()
        && expected.equals(expectedPayload.state())) return Host.Observation.CONFIRMED;
    return observe(current, latest, tick, timeoutTicks);
  }
}

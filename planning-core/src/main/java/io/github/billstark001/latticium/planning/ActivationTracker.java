package io.github.billstark001.latticium.planning;

import io.github.billstark001.latticium.dsl.Compiler;
import io.github.billstark001.latticium.dsl.Model.*;

/** Interprets enter/while activation without treating Unknown as a false edge. */
public final class ActivationTracker {
  public enum Decision {
    START,
    CONTINUE,
    STOP,
    DEFER,
    IDLE
  }

  private Truth previous;
  private boolean active;

  public Decision sample(Profile.Activation activation, Compiler.Bound where, Facts facts) {
    var player = facts.player();
    if (player.isEmpty()) return Decision.DEFER;
    Truth now = where.at(facts, player.get());
    if (now == Truth.UNKNOWN) return Decision.DEFER;
    if (activation.mode() == Profile.Activation.Mode.ENTER) {
      boolean fire =
          now == Truth.TRUE
              && (previous == Truth.FALSE || previous == null && activation.fireIfInside());
      previous = now;
      return fire ? Decision.START : Decision.IDLE;
    }
    previous = now;
    if (now == Truth.FALSE) {
      if (active) {
        active = false;
        return Decision.STOP;
      }
      return Decision.IDLE;
    }
    if (!active) {
      active = true;
      return Decision.START;
    }
    return Decision.CONTINUE;
  }

  public void reset() {
    previous = null;
    active = false;
  }
}

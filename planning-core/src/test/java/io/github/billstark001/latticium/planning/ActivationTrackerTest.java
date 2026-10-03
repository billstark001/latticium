package io.github.billstark001.latticium.planning;

import static org.junit.jupiter.api.Assertions.*;

import io.github.billstark001.latticium.dsl.Compiler;
import io.github.billstark001.latticium.dsl.Model.*;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ActivationTrackerTest {
  private static final Position PLAYER =
      new Position(ResourceId.parse("minecraft:overworld"), 0, 0, 0);
  private static final Facts FACTS =
      new Facts() {
        public Optional<WorldCell> world(Position position) {
          return Optional.empty();
        }

        public TargetCell target(Position position) {
          return new TargetCell.DontCare();
        }

        public Optional<Position> player() {
          return Optional.of(PLAYER);
        }

        public Optional<Set<ResourceId>> inventory() {
          return Optional.empty();
        }

        public Truth selection(String name, Position position) {
          return Truth.FALSE;
        }
      };

  @Test
  void whileActivationKeepsStateAcrossUnknownSamples() {
    var now = new Truth[] {Truth.TRUE};
    var where = new Compiler.Bound(SetType.POS, (facts, pos, value) -> now[0], 0);
    var activation = new Profile.Activation("", Profile.Activation.Mode.WHILE, false);
    var tracker = new ActivationTracker();
    assertEquals(ActivationTracker.Decision.START, tracker.sample(activation, where, FACTS));
    assertEquals(ActivationTracker.Decision.CONTINUE, tracker.sample(activation, where, FACTS));
    now[0] = Truth.UNKNOWN;
    assertEquals(ActivationTracker.Decision.DEFER, tracker.sample(activation, where, FACTS));
    now[0] = Truth.TRUE;
    assertEquals(ActivationTracker.Decision.CONTINUE, tracker.sample(activation, where, FACTS));
    now[0] = Truth.FALSE;
    assertEquals(ActivationTracker.Decision.STOP, tracker.sample(activation, where, FACTS));
    assertEquals(ActivationTracker.Decision.IDLE, tracker.sample(activation, where, FACTS));
  }

  @Test
  void enterCanFireInitiallyAndAfterReset() {
    var now = new Truth[] {Truth.TRUE};
    var where = new Compiler.Bound(SetType.POS, (facts, pos, value) -> now[0], 0);
    var activation = new Profile.Activation("", Profile.Activation.Mode.ENTER, true);
    var tracker = new ActivationTracker();
    assertEquals(ActivationTracker.Decision.START, tracker.sample(activation, where, FACTS));
    assertEquals(ActivationTracker.Decision.IDLE, tracker.sample(activation, where, FACTS));
    now[0] = Truth.FALSE;
    assertEquals(ActivationTracker.Decision.IDLE, tracker.sample(activation, where, FACTS));
    now[0] = Truth.TRUE;
    assertEquals(ActivationTracker.Decision.START, tracker.sample(activation, where, FACTS));
    tracker.reset();
    assertEquals(ActivationTracker.Decision.START, tracker.sample(activation, where, FACTS));
  }
}

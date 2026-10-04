package io.github.billstark001.latticium.planning;

import static org.junit.jupiter.api.Assertions.*;

import io.github.billstark001.latticium.dsl.Model.Position;
import io.github.billstark001.latticium.dsl.Model.ResourceId;
import io.github.billstark001.latticium.dsl.Model.TargetCell;
import org.junit.jupiter.api.Test;

class TargetSourcesTest {
  private static final Position POSITION =
      new Position(ResourceId.parse("minecraft:overworld"), 0, 64, 0);

  @Test
  void wrongSessionNeverCallsProvider() {
    var session = new Host.SessionId();
    var guarded =
        TargetSources.guarded((pos, requested) -> fail("Stale session reached provider"), session);
    assertInstanceOf(TargetCell.Unknown.class, guarded.target(POSITION, new Host.SessionId()));
  }

  @Test
  void providerFailuresBecomeExplicitUnknownTargets() {
    var session = new Host.SessionId();
    var absent = TargetSources.guarded((pos, requested) -> null, session);
    assertEquals(
        "Target provider returned null",
        assertInstanceOf(TargetCell.Unknown.class, absent.target(POSITION, session)).reason());
    var failed =
        TargetSources.guarded(
            (pos, requested) -> {
              throw new IllegalStateException("placement removed");
            },
            session);
    assertTrue(
        assertInstanceOf(TargetCell.Unknown.class, failed.target(POSITION, session))
            .reason()
            .contains("placement removed"));
  }
}

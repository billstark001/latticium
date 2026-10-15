package io.github.billstark001.latticium.planning;

import static org.junit.jupiter.api.Assertions.*;

import io.github.billstark001.latticium.dsl.Model.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TransientConfirmationTest {
  private static final Position POS = new Position(ResourceId.parse("overworld"), 0, 64, 0);
  private static final BlockState AIR = new BlockState(ResourceId.parse("air"), Map.of());
  private static final BlockState SAND = new BlockState(ResourceId.parse("sand"), Map.of());

  @Test
  void explicitTransitionIntentRequiresANewerRetainedAuthoritativeExpectedPayload() {
    var updates = new ServerUpdates(2);
    updates.record(POS, AIR);
    var receipt = new ActionConfirmation(AIR, SAND, 0, updates.revision(POS));
    assertEquals(
        Host.Observation.STILL_PENDING,
        receipt.observeTransition(SAND, updates.get(POS), null, 1, 100));
    updates.record(POS, SAND);
    updates.record(POS, AIR);
    var expected = updates.expectedAfter(POS, SAND, receipt.serverRevision());
    assertNotNull(expected);
    assertEquals(Host.Observation.STILL_PENDING, receipt.observe(AIR, updates.get(POS), 2, 100));
    assertEquals(
        Host.Observation.CONFIRMED,
        receipt.observeTransition(AIR, updates.get(POS), expected, 2, 100));
    assertEquals(
        Host.Observation.STILL_PENDING,
        receipt.observeTransition(null, updates.get(POS), expected, 2, 100));
    for (int i = 0; i < 8; i++) updates.record(POS, AIR);
    assertNull(updates.expectedAfter(POS, SAND, receipt.serverRevision()));
    assertEquals(
        Host.Observation.STILL_PENDING,
        receipt.observeTransition(AIR, updates.get(POS), null, 3, 100));
  }

  @Test
  void receiptHistoryCannotCrossPositionOrWorldEviction() {
    var updates = new ServerUpdates(1);
    updates.record(POS, SAND);
    updates.record(POS.offset(1, 0, 0), SAND);
    assertNull(updates.expectedAfter(POS, SAND, 0));
    updates.clear();
    assertNull(updates.expectedAfter(POS.offset(1, 0, 0), SAND, 0));
  }

  @Test
  void declarativeReceiptTransitionsNeverAdvanceUnconfirmedActions() {
    for (var observation : Host.Observation.values())
      for (boolean cancelled : new boolean[] {false, true})
        for (boolean finished : new boolean[] {false, true})
          for (boolean partial : new boolean[] {false, true})
            for (boolean paused : new boolean[] {false, true}) {
              var row =
                  ActionTransitions.resolve(observation, cancelled, finished, partial, paused);
              assertEquals(observation == Host.Observation.CONFIRMED, row.advance());
              if (observation == Host.Observation.STILL_PENDING)
                assertEquals(JobController.Status.WAITING, row.next());
              else if (cancelled) assertEquals(JobController.Status.CANCELLED, row.next());
            }
  }
}

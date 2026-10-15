package io.github.billstark001.latticium.planning;

import static org.junit.jupiter.api.Assertions.*;

import io.github.billstark001.latticium.dsl.Model.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ServerUpdatesTest {
  private static final Position POS = new Position(ResourceId.parse("overworld"), 0, 64, 0);
  private static final BlockState STONE = new BlockState(ResourceId.parse("stone"), Map.of());
  private static final BlockState AIR = new BlockState(ResourceId.parse("air"), Map.of());

  @Test
  void unrelatedPacketsDoNotChangeACandidatesRevisionOrState() {
    var updates = new ServerUpdates(2);
    updates.record(POS, STONE);
    var before = updates.get(POS);
    updates.record(POS.offset(1, 0, 0), AIR);
    assertEquals(before, updates.get(POS));
    updates.record(POS, AIR);
    assertTrue(updates.revision(POS) > before.revision());
    assertEquals(AIR, updates.get(POS).state());
  }

  @Test
  void evictionCannotBecomeFalseConfirmationOrReuseAnOldRevision() {
    var updates = new ServerUpdates(1);
    updates.record(POS, STONE);
    long before = updates.revision(POS);
    var action = new ActionConfirmation(STONE, AIR, 0, before);
    updates.record(POS.offset(1, 0, 0), AIR);
    assertNull(updates.get(POS));
    assertEquals(Host.Observation.STILL_PENDING, action.observe(AIR, updates.get(POS), 1, 100));
    updates.record(POS, AIR);
    assertTrue(updates.revision(POS) > before);
    assertEquals(Host.Observation.CONFIRMED, action.observe(AIR, updates.get(POS), 1, 100));
    updates.clear();
    assertNull(updates.get(POS));
    assertEquals(0, updates.revision(POS));
  }
}

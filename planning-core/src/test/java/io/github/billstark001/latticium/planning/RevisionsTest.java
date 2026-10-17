package io.github.billstark001.latticium.planning;

import static org.junit.jupiter.api.Assertions.*;

import io.github.billstark001.latticium.planning.Revisions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class RevisionsTest {
  @Test
  void catalogEventsInvalidateOnlyTheirDeclaredResources() {
    for (var event : RevisionEvents.all()) {
      var ledger = new Ledger(64);
      var session = new Host.SessionId();
      var reads = event.domains().stream().map(d -> new Key(d, "relevant")).toList();
      for (var key : reads) ledger.sample(key, "A", Reliability.VERSIONED);
      var stamp = ledger.capture(session, 1, reads);
      assertTrue(ledger.valid(stamp, session, 1), event.id());
      ledger.event(event.id(), "unrelated");
      assertTrue(ledger.valid(stamp, session, 1), "Unrelated " + event.id());
      ledger.event(event.id(), "relevant");
      assertFalse(ledger.valid(stamp, session, 1), "Stale " + event.id());
    }
  }

  @Test
  void returningToOldDataDoesNotResurrectALeaseAndSessionsAreIndependent() {
    var ledger = new Ledger(4);
    var session = new Host.SessionId();
    var cell = new Key(Domain.STATE, "cell");
    ledger.sample(cell, "A", Reliability.VERSIONED);
    var old = ledger.capture(session, 1, List.of(cell));
    ledger.sample(cell, "B", Reliability.VERSIONED);
    ledger.sample(cell, "A", Reliability.VERSIONED);
    assertFalse(ledger.valid(old, session, 1));
    var fresh = ledger.capture(session, 1, List.of(cell));
    assertTrue(ledger.valid(fresh, session, 20));
    assertFalse(ledger.valid(fresh, new Host.SessionId(), 1));
    assertTrue(fresh.reusable());
  }

  @Test
  void shortLivedSourcesAndMissingOrEvictedEvidenceCannotQualifyForReuse() {
    var ledger = new Ledger(1);
    var session = new Host.SessionId();
    var target = new Key(Domain.TARGET, "provider");
    ledger.sample(target, "A", Reliability.SHORT_LIVED);
    var stamp = ledger.capture(session, 1, List.of(target));
    assertFalse(stamp.reusable());
    assertTrue(ledger.valid(stamp, session, 1));
    assertFalse(ledger.valid(stamp, session, 2));
    ledger.sample(new Key(Domain.STATE, "other"), "B", Reliability.VERSIONED);
    assertEquals(1, ledger.size());
    assertFalse(ledger.valid(stamp, session, 1));
    ledger.sample(target, "A", Reliability.SHORT_LIVED);
    assertFalse(ledger.valid(stamp, session, 1));
    ledger.sample(target, null, Reliability.VERSIONED);
    var missing = ledger.capture(session, 1, List.of(target));
    assertFalse(ledger.valid(missing, session, 1));
    assertFalse(missing.reusable());
  }

  @Test
  void changedWorldFactsDoNotInvalidateAnIndependentInventoryOrCellRead() {
    var ledger = new Ledger(8);
    var session = new Host.SessionId();
    var cell = new Key(Domain.STATE, "x0");
    var neighbor = new Key(Domain.STATE, "x1");
    var inventory = new Key(Domain.INVENTORY, 0);
    for (var key : List.of(cell, neighbor, inventory))
      ledger.sample(key, "A", Reliability.VERSIONED);
    var independent = ledger.capture(session, 0, List.of(cell));
    var dependent = ledger.capture(session, 0, List.of(cell, neighbor));
    ledger.event("block_update", "x1");
    assertTrue(ledger.valid(independent, session, 1));
    assertFalse(ledger.valid(dependent, session, 1));
    ledger.event("inventory_edit", 0);
    assertTrue(ledger.valid(independent, session, 1));
    assertThrows(IllegalArgumentException.class, () -> ledger.event("invented", "x1"));
  }
}

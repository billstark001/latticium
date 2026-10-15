package io.github.billstark001.latticium.planning;

import static org.junit.jupiter.api.Assertions.*;

import io.github.billstark001.latticium.dsl.FactDependencies;
import io.github.billstark001.latticium.dsl.FactDependencies.Fact;
import io.github.billstark001.latticium.dsl.Model.*;
import io.github.billstark001.latticium.planning.Revisions.*;
import io.github.billstark001.latticium.planning.SectionScanner.Bounds;
import io.github.billstark001.latticium.planning.SectionScanner.SectionKey;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpatialRevisionsTest {
  private static final ResourceId DIM = ResourceId.parse("overworld");
  private static final Position POS = new Position(DIM, 4, 64, 4);
  private static final Bounds SECTION = new Bounds(DIM, 0, 64, 0, 15, 79, 15);

  @Test
  void candidateProjectionDoesNotInheritUnrelatedReadsInItsCapturedSection() {
    var ledger = new Ledger(64);
    var session = new Host.SessionId();
    var region =
        new SpatialReads(
            SECTION,
            FactDependencies.of(Fact.STATE),
            0,
            Execution.Granularity.CELL,
            Reliability.VERSIONED);
    var captured = ledger.capture(session, 1, List.of()).withSpatial(region);
    var candidate = captured.at(POS);
    ledger.event("block_update", POS.offset(5, 0, 0));
    assertFalse(ledger.valid(captured, session, 2));
    assertTrue(ledger.valid(candidate, session, 2));
    ledger.event("block_update", POS);
    assertFalse(ledger.valid(candidate, session, 2));
  }

  @Test
  void exactColumnOffsetsAndConservativeSectionTrackingHaveDifferentContracts() {
    for (var granularity : Execution.Granularity.values()) {
      var ledger = new Ledger(64);
      var session = new Host.SessionId();
      var state = FactDependencies.of(Fact.STATE).shift(0, -1, 0);
      var reads =
          new SpatialReads(
              new Bounds(DIM, 4, 64, 4, 4, 64, 4), state, 1, granularity, Reliability.VERSIONED);
      var stamp = ledger.capture(session, 1, List.of()).withSpatial(reads);
      ledger.event("light_update", new SectionKey(DIM, 0, 3, 0));
      assertTrue(ledger.valid(stamp, session, 2));
      ledger.event("block_update", POS.offset(3, -1, 0));
      ledger.event("block_update", new SectionKey(DIM, 0, 3, 0));
      assertEquals(granularity == Execution.Granularity.CELL, ledger.valid(stamp, session, 2));
      ledger.event("block_update", POS.offset(0, -1, 0));
      assertFalse(ledger.valid(stamp, session, 2));
    }
  }

  @Test
  void unregisteredMutationsResidencyAndForgottenHistoryInvalidateSpatialEvidence() {
    var ledger = new Ledger(4);
    var session = new Host.SessionId();
    var region =
        new SpatialReads(
            SECTION,
            FactDependencies.of(Fact.STATE),
            0,
            Execution.Granularity.CELL,
            Reliability.VERSIONED);
    var stamp = ledger.capture(session, 1, List.of()).withSpatial(region);
    ledger.changed(new Key(Domain.RESIDENCY, new Position(DIM, 0, 0, 0)));
    assertFalse(ledger.valid(stamp, session, 2));
    var next = ledger.capture(session, 2, List.of()).withSpatial(region);
    for (int i = 0; i < 8; i++) ledger.event("block_update", POS.offset(100 + i, 0, 0));
    assertFalse(ledger.valid(next, session, 3), "Forgotten changes are not an unchanged lease");
  }

  @Test
  void previewTargetNamespacesAndShortLivedReadsCannotBeConfusedWithRunningJobs() {
    var ledger = new Ledger(64);
    var job = new Host.SessionId();
    var preview = new Host.SessionId();
    var region =
        new SpatialReads(
            SECTION,
            FactDependencies.of(Fact.TARGET),
            0,
            Execution.Granularity.CELL,
            Reliability.SHORT_LIVED);
    var stamp = ledger.capture(job, 1, List.of()).withSpatial(region);
    assertFalse(stamp.reusable());
    ledger.changed(new Key(Domain.TARGET, new SourceCell(preview, POS)));
    assertTrue(ledger.valid(stamp, job, 1));
    assertFalse(ledger.valid(stamp, job, 2));
    ledger.changed(new Key(Domain.TARGET, new SourceCell(job, POS)));
    assertFalse(ledger.valid(stamp, job, 1));
  }

  @Test
  void observingChangedDataDuringCaptureDiffersFromASourceMutatingDuringCapture() {
    var ledger = new Ledger(16);
    var session = new Host.SessionId();
    var key = new Key(Domain.STATE, POS);
    var region =
        new SpatialReads(
            SECTION,
            FactDependencies.of(Fact.STATE),
            0,
            Execution.Granularity.CELL,
            Reliability.VERSIONED);
    ledger.sample(key, "old", Reliability.VERSIONED);
    long fence = ledger.fence();
    ledger.sample(key, "new", Reliability.VERSIONED);
    assertTrue(ledger.eventsUnchanged(fence, session, region));
    ledger.event("block_update", POS);
    assertFalse(ledger.eventsUnchanged(fence, session, region));
  }
}

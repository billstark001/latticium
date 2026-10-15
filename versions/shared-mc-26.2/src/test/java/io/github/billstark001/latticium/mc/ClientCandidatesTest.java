package io.github.billstark001.latticium.mc;

import static org.junit.jupiter.api.Assertions.*;

import io.github.billstark001.latticium.dsl.Model.*;
import java.util.Comparator;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClientCandidatesTest {
  private static final Position POS = new Position(ResourceId.parse("overworld"), 0, 64, 0);
  private static final BlockState AIR = new BlockState(ResourceId.parse("air"), Map.of());
  private static final TargetCell TARGET =
      new TargetCell.Exact(new BlockState(ResourceId.parse("dirt"), Map.of()));

  @Test
  void rediscoveryCannotLoseAConfirmedPartialTargetOrRetry() {
    var candidates = new ClientCandidates();
    candidates.claim(POS);
    candidates.freeze(POS, TARGET);
    candidates.confirmed(POS, AIR);
    candidates.retry(POS, 20);
    candidates.claim(POS);
    assertEquals(1, candidates.size());
    assertEquals(TARGET, candidates.target(POS));
    assertEquals(AIR, candidates.expected(POS));
    assertNull(candidates.next((a, b) -> 0, 19));
    assertEquals(POS, candidates.next((a, b) -> 0, 20));
    candidates.refresh();
    assertEquals(TARGET, candidates.target(POS));
    assertEquals(AIR, candidates.expected(POS));
    assertEquals(POS, candidates.next((a, b) -> 0, 0));
  }

  @Test
  void oneTickRetriesGiveOtherReadyWorkAnOpportunity() {
    var candidates = new ClientCandidates(3, false);
    var order = Comparator.comparingInt(Position::x);
    for (int x = 0; x < 3; x++) candidates.claim(POS.offset(x, 0, 0));
    for (int tick = 0; tick < 12; tick++) {
      var chosen = candidates.next(order, tick);
      assertEquals(POS.offset(tick % 3, 0, 0), chosen);
      candidates.retry(chosen, tick + 1);
    }
  }

  @Test
  void rescanAndRefreshReconsiderOnlyUnstartedTargets() {
    var candidates = new ClientCandidates();
    candidates.claim(POS);
    candidates.freeze(POS, TARGET);
    candidates.claim(POS);
    assertNull(candidates.target(POS));
    candidates.freeze(POS, TARGET);
    candidates.retry(POS, 20);
    candidates.refresh();
    assertNull(candidates.target(POS));
    assertEquals(POS, candidates.next((a, b) -> 0, 0));
    candidates.remove(POS);
    candidates.claim(POS);
    assertNull(candidates.target(POS));
    assertNull(candidates.expected(POS));
  }

  @Test
  void schedulingSkipsRetriesAndPreservesScanOrderOnTies() {
    var candidates = new ClientCandidates();
    var earlierX = POS.offset(-1, 0, 0);
    candidates.claim(POS);
    candidates.claim(earlierX);
    assertEquals(POS, candidates.next((a, b) -> 0, 0));
    assertEquals(earlierX, candidates.next(Comparator.comparingInt(Position::x), 0));
    candidates.retry(earlierX, 10);
    assertEquals(POS, candidates.next(Comparator.comparingInt(Position::x), 0));
    candidates.remove(POS);
    assertNull(candidates.next((a, b) -> 0, 0));
    candidates.remove(earlierX);
    assertTrue(candidates.isEmpty());
  }

  @Test
  void boundedAdmissionPrefersNearbyWorkAndRotatesDeferredWorkForRediscovery() {
    var candidates = new ClientCandidates(2, false);
    var order = Comparator.comparingInt(Position::x);
    var far = POS.offset(10, 0, 0);
    candidates.admit(far, order, 0);
    candidates.admit(POS.offset(5, 0, 0), order, 0);
    var admission = candidates.admit(POS, order, 0);
    assertTrue(admission.admitted());
    assertEquals(far, admission.displaced());
    assertEquals(2, candidates.size());
    assertFalse(candidates.admit(far, order, 0).admitted());
    candidates.retry(POS, 20);
    var rotated = candidates.admit(far, order, 1);
    assertTrue(rotated.admitted());
    assertEquals(POS, rotated.displaced());
    assertEquals(2, candidates.size());
  }

  @Test
  void admissionProtectsConfirmedProgressAndExplicitlyPinnedIntent() {
    for (boolean confirmed : new boolean[] {false, true}) {
      var candidates = new ClientCandidates(1, true);
      candidates.claim(POS);
      candidates.freeze(POS, TARGET);
      if (confirmed) candidates.confirmed(POS, AIR);
      candidates.retry(POS, 100);
      assertFalse(
          candidates
              .admit(POS.offset(-1, 0, 0), Comparator.comparingInt(Position::x), 1)
              .admitted());
      assertEquals(TARGET, candidates.target(POS));
      assertEquals(confirmed ? AIR : null, candidates.expected(POS));
    }
  }

  @Test
  void compactSatisfiedMasksAllowStreamingBeyondCapacityWithoutRediscoveringFinishedCells() {
    var candidates = new ClientCandidates(1, false);
    var order = Comparator.comparingInt(Position::x);
    for (int x = -100; x < 100; x++) {
      var position = POS.offset(x, 0, 0);
      assertTrue(candidates.admit(position, order, x + 100).admitted());
      assertEquals(1, candidates.size());
      candidates.satisfy(position);
      candidates.remove(position);
      assertTrue(candidates.satisfied(position));
    }
    for (int x = -100; x < 100; x++) candidates.admit(POS.offset(x, 0, 0), order, 300);
    assertTrue(candidates.isEmpty());
    candidates.changed(POS);
    assertTrue(candidates.admit(POS, order, 301).admitted());
    assertEquals(1, candidates.size());
    candidates.refresh();
    assertFalse(candidates.satisfied(POS.offset(1, 0, 0)));
  }
}

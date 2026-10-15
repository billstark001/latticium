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
}

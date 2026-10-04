package io.github.billstark001.latticium.planning;

import static org.junit.jupiter.api.Assertions.*;

import io.github.billstark001.latticium.dsl.Parser;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RefreshReadsTest {
  @Test
  void offsetAndAdjacentIdentifyOnlyAffectedWorldPositions() {
    var reads =
        RefreshReads.in(
            Parser.expression("current(b{minecraft:lava}) & offset(1,0,0,adjacent(solid()))"));
    assertEquals(
        Set.of(
            new RefreshReads.Offset(0, 0, 0),
            new RefreshReads.Offset(2, 0, 0),
            new RefreshReads.Offset(1, 1, 0),
            new RefreshReads.Offset(1, -1, 0),
            new RefreshReads.Offset(1, 0, 1),
            new RefreshReads.Offset(1, 0, -1)),
        reads.worldOffsets());
    assertFalse(reads.usesPlayer());
    assertFalse(reads.periodic());
  }

  @Test
  void playerSphereRetainsItsEvaluationOffset() {
    var reads = RefreshReads.in(Parser.expression("offset(2,0,-1,sphere(player,3))"));
    assertEquals(
        new RefreshReads.PlayerSphere(new RefreshReads.Offset(2, 0, -1), 3),
        reads.playerSpheres().getFirst());
    assertTrue(reads.worldOffsets().isEmpty());
  }

  @Test
  void lightNeedsPeriodicFallbackButOrdinaryBlocksDoNot() {
    assertTrue(RefreshReads.in(Parser.expression("light(0..7)")).periodic());
    assertFalse(RefreshReads.in(Parser.expression("current(b{minecraft:stone})")).periodic());
  }

  @Test
  void deeplyNestedAdjacencyFallsBackWithoutExponentialExpansion() {
    String expression = "solid()";
    for (int i = 0; i < 10; i++) expression = "adjacent(" + expression + ")";
    var reads = RefreshReads.in(Parser.expression(expression));
    assertTrue(reads.broadWorld());
    assertTrue(reads.broadPlayer());
  }
}

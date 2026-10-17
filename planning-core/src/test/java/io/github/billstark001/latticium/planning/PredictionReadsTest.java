package io.github.billstark001.latticium.planning;

import static org.junit.jupiter.api.Assertions.*;

import io.github.billstark001.latticium.dsl.FactDependencies.Fact;
import io.github.billstark001.latticium.dsl.FactDependencies.Offset;
import io.github.billstark001.latticium.dsl.Model.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PredictionReadsTest {
  private static final Position POS = new Position(ResourceId.parse("overworld"), 0, 64, 0);
  private static final BlockState RESULT = new BlockState(ResourceId.parse("dirt"), Map.of());

  @Test
  void everyActionHasAnExecutableReadContractAndPlacementUsesItsActualSupport() {
    assertEquals(Planner.Action.values().length, PredictionReads.contracts().size());
    var clicked = POS.offset(1, 0, 0);
    var interaction =
        new Planner.Interaction(0, Planner.Interaction.Face.WEST, 1, 64.5, .5, clicked);
    var proposal =
        new Planner.Proposal(
            Planner.Action.PLACE,
            RESULT,
            Set.of(POS),
            new Planner.Cost(1, 1, 0),
            "test:place",
            interaction);
    var required = PredictionReads.in(POS, List.of(proposal)).orElseThrow();
    assertEquals(1, required.radius());
    assertEquals(
        Set.of(new Offset(0, 0, 0), new Offset(1, 0, 0)),
        required.dependencies().columns().get(Fact.STATE).offsets());
    assertTrue(required.dependencies().needs(Fact.SOLID));
  }

  @Test
  void clearDoesNotAcquireAnUnneededSupportDependencyAndUndeclaredRemoteEffectsAreUnavailable() {
    var clear =
        new Planner.Proposal(
            Planner.Action.BREAK, RESULT, Set.of(POS), new Planner.Cost(1, 0, 1), "test:break");
    var reads = PredictionReads.in(POS, List.of(clear)).orElseThrow();
    assertEquals(0, reads.radius());
    assertFalse(reads.dependencies().needs(Fact.SOLID));
    var remote =
        new Planner.Proposal(
            Planner.Action.PLACE,
            RESULT,
            Set.of(POS.offset(17, 0, 0)),
            new Planner.Cost(1, 1, 0),
            "test:remote");
    assertTrue(PredictionReads.in(POS, List.of(remote)).isEmpty());
  }
}

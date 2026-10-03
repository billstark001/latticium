package io.github.billstark001.latticium.planning;

import static org.junit.jupiter.api.Assertions.*;

import io.github.billstark001.latticium.dsl.Model.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlannerEdgeTest {
  private static final ResourceId DIMENSION = ResourceId.parse("minecraft:overworld");
  private static final Position POSITION = new Position(DIMENSION, 0, 0, 0);
  private static final BlockState START = state("stone");
  private static final BlockState HEAVY = state("dirt");
  private static final BlockState CHEAP = state("granite");
  private static final BlockState GOAL = state("air");

  private static BlockState state(String name) {
    return new BlockState(ResourceId.parse(name), Map.of());
  }

  private static Planner.Proposal step(BlockState result, int materials) {
    return new Planner.Proposal(
        Planner.Action.INTERACT,
        result,
        Set.of(POSITION),
        new Planner.Cost(1, materials, 0),
        "test");
  }

  @Test
  void oneOverflowingCostDoesNotAbortOtherPaths() {
    Planner.Oracle oracle =
        (position, current, goal) -> {
          if (current.equals(START))
            return new Planner.Prediction.Proposals(
                List.of(step(HEAVY, Integer.MAX_VALUE), step(CHEAP, 0)));
          return new Planner.Prediction.Proposals(List.of(step(GOAL, 1)));
        };
    var result =
        assertInstanceOf(
            Planner.Result.Ready.class,
            new Planner()
                .plan(
                    POSITION,
                    START,
                    new TargetCell.Exact(GOAL),
                    new Profile.Policy(Profile.Policy.BreakMode.DENY, 1, 8),
                    oracle,
                    4));
    assertEquals(new Planner.Cost(2, 1, 0), result.cost());
  }
}

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

  @Test
  void transitionMustDeclareTheChangedCell() {
    Planner.Oracle oracle =
        (position, current, goal) ->
            new Planner.Prediction.Proposals(
                List.of(
                    new Planner.Proposal(
                        Planner.Action.INTERACT,
                        GOAL,
                        Set.of(),
                        new Planner.Cost(1, 0, 0),
                        "missing-effect")));
    assertInstanceOf(
        Planner.Result.NoPlan.class,
        new Planner()
            .plan(
                POSITION,
                START,
                new TargetCell.Exact(GOAL),
                new Profile.Policy(Profile.Policy.BreakMode.SELECTED, 1, 8),
                oracle,
                4));
  }

  @Test
  void invalidPublicPolicyAndProposalCannotBypassBreakChecks() {
    assertThrows(NullPointerException.class, () -> new Profile.Policy(null, 1, 8));
    assertThrows(
        NullPointerException.class,
        () -> new Planner.Proposal(null, GOAL, Set.of(POSITION), new Planner.Cost(1, 0, 0), "x"));
    assertThrows(NullPointerException.class, () -> new Planner.Prediction.Unknown(null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Planner.Result.Ready(START, List.of(), new Planner.Cost(0, 0, 0)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Planner.Result.Ready(START, List.of(step(GOAL, 0)), new Planner.Cost(2, 0, 0)));
  }

  @Test
  void clearGoalAcceptsVanillaCaveAirWithoutAnAction() {
    var caveAir = state("cave_air");
    assertInstanceOf(
        Planner.Result.Complete.class,
        new Planner()
            .plan(
                POSITION,
                caveAir,
                new TargetCell.Clear(),
                new Profile.Policy(Profile.Policy.BreakMode.SELECTED, 1, 1),
                (pos, current, goal) -> fail("Already clear; no prediction needed"),
                1));
  }

  @Test
  void remainingActivationBudgetCannotStartAnUnfinishablePlan() {
    var policy = new Profile.Policy(Profile.Policy.BreakMode.DENY, 1, 3);
    Planner.Oracle oracle =
        (position, current, goal) ->
            new Planner.Prediction.Proposals(
                List.of(current.equals(START) ? step(CHEAP, 0) : step(GOAL, 0)));
    var planner = new Planner();
    var target = new TargetCell.Exact(GOAL);
    assertInstanceOf(
        Planner.Result.Ready.class, planner.plan(POSITION, START, target, policy, oracle, 4));
    assertInstanceOf(
        Planner.Result.NoPlan.class,
        planner.plan(POSITION, START, target, policy.afterActions(2), oracle, 4));
    assertThrows(IllegalArgumentException.class, () -> policy.afterActions(3));
    assertThrows(IllegalArgumentException.class, () -> policy.afterActions(-1));
  }

  @Test
  void brokenOracleDefersInsteadOfAbortingSearch() {
    var planner = new Planner();
    var policy = new Profile.Policy(Profile.Policy.BreakMode.DENY, 1, 2);
    var target = new TargetCell.Exact(GOAL);
    var nullResult = planner.plan(POSITION, START, target, policy, (pos, current, goal) -> null, 2);
    var thrownResult =
        planner.plan(
            POSITION,
            START,
            target,
            policy,
            (pos, current, goal) -> {
              throw new IllegalStateException("provider failed");
            },
            2);
    assertInstanceOf(Planner.Result.Deferred.class, nullResult);
    assertInstanceOf(Planner.Result.Deferred.class, thrownResult);
  }

  @Test
  void searchLimitCannotBeMistakenForNoLegalPath() {
    var planner = new Planner();
    var policy = new Profile.Policy(Profile.Policy.BreakMode.SELECTED, 1, 3);
    var target = new TargetCell.Exact(GOAL);
    Planner.Oracle oracle =
        (position, current, goal) ->
            new Planner.Prediction.Proposals(
                List.of(step(current.equals(START) ? CHEAP : GOAL, 0)));
    assertInstanceOf(
        Planner.Result.Deferred.class, planner.plan(POSITION, START, target, policy, oracle, 1));
    assertInstanceOf(
        Planner.Result.Ready.class, planner.plan(POSITION, START, target, policy, oracle, 2));
  }
}

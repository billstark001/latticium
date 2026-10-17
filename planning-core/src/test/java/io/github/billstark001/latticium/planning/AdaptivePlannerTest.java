package io.github.billstark001.latticium.planning;

import static org.junit.jupiter.api.Assertions.*;

import io.github.billstark001.latticium.dsl.Model.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AdaptivePlannerTest {
  private static final Position POS = new Position(ResourceId.parse("overworld"), 0, 64, 0);
  private static final Profile.Policy POLICY =
      new Profile.Policy(Profile.Policy.BreakMode.DENY, 1, 100);

  private static BlockState state(int n) {
    return new BlockState(ResourceId.parse("test:chain"), Map.of("index", Integer.toString(n)));
  }

  private static final Planner.Oracle CHAIN =
      (p, current, goal) -> {
        int n = Integer.parseInt(current.properties().get("index"));
        return new Planner.Prediction.Proposals(
            List.of(
                new Planner.Proposal(
                    Planner.Action.INTERACT,
                    state(n + 1),
                    Set.of(p),
                    new Planner.Cost(1, 0, 0),
                    "test:chain")));
      };

  @Test
  void progressivelyLargerFreshSearchesFindAPathBeyondTheInitialCap() {
    var planner = new AdaptivePlanner(2, 16, 4);
    var goal = new TargetCell.Exact(state(9));
    for (int nodes : new int[] {2, 4, 8}) {
      var attempt = planner.plan(POS, state(0), goal, POLICY, CHAIN, 16);
      assertInstanceOf(Planner.Result.Deferred.class, attempt.result());
      assertEquals(nodes, attempt.expandedNodes());
    }
    var attempt = planner.plan(POS, state(0), goal, POLICY, CHAIN, 16);
    var ready = assertInstanceOf(Planner.Result.Ready.class, attempt.result());
    assertEquals(9, ready.steps().size());
    assertEquals(9, attempt.expandedNodes());
  }

  @Test
  void nativeSizedBudgetsEventuallyReachAThreeHundredActionPath() {
    var planner = new AdaptivePlanner(128, 1024, 256);
    var goal = new TargetCell.Exact(state(300));
    var policy = new Profile.Policy(Profile.Policy.BreakMode.SELECTED, 1, 512);
    for (int nodes : new int[] {128, 256}) {
      var attempt = planner.plan(POS, state(0), goal, policy, CHAIN, 1024);
      assertInstanceOf(Planner.Result.Deferred.class, attempt.result());
      assertEquals(nodes, attempt.expandedNodes());
    }
    var ready =
        assertInstanceOf(
            Planner.Result.Ready.class,
            planner.plan(POS, state(0), goal, policy, CHAIN, 1024).result());
    assertEquals(300, ready.steps().size());
    assertTrue(ready.steps().stream().noneMatch(step -> step.action() == Planner.Action.BREAK));
  }

  @Test
  void callerBudgetAndConfiguredCapBothBoundActualWork() {
    var planner = new AdaptivePlanner(2, 8, 4);
    var goal = new TargetCell.Exact(state(20));
    for (int i = 0; i < 10; i++) {
      var attempt = planner.plan(POS, state(0), goal, POLICY, CHAIN, 5);
      assertInstanceOf(Planner.Result.Deferred.class, attempt.result());
      assertTrue(attempt.expandedNodes() <= 5);
    }
    assertEquals(8, planner.plan(POS, state(0), goal, POLICY, CHAIN, 100).expandedNodes());
  }

  @Test
  void changedInputsResetAndEvictionPromotesOnlyWorkBudgetsWithoutReusingPredictions() {
    var planner = new AdaptivePlanner(2, 16, 1);
    var goal = new TargetCell.Exact(state(9));
    planner.plan(POS, state(0), goal, POLICY, CHAIN, 16);
    assertEquals(4, planner.plan(POS, state(0), goal, POLICY, CHAIN, 16).expandedNodes());
    assertEquals(2, planner.plan(POS, state(1), goal, POLICY, CHAIN, 16).expandedNodes());
    planner.plan(POS.offset(1, 0, 0), state(0), goal, POLICY, CHAIN, 16);
    assertEquals(4, planner.plan(POS, state(1), goal, POLICY, CHAIN, 16).expandedNodes());
    var fresh =
        planner.plan(
            POS,
            state(1),
            goal,
            POLICY,
            (p, s, g) -> new Planner.Prediction.Unknown("Support unloaded"),
            16);
    assertEquals(new Planner.Result.Deferred("Support unloaded"), fresh.result());
    assertEquals(1, fresh.expandedNodes());
    assertEquals(4, planner.plan(POS, state(1), goal, POLICY, CHAIN, 16).expandedNodes());
    assertEquals(2, planner.plan(POS, state(2), goal, POLICY, CHAIN, 16).expandedNodes());
    planner.forget(POS);
    assertEquals(4, planner.plan(POS, state(1), goal, POLICY, CHAIN, 16).expandedNodes());
    planner.clear();
    assertEquals(2, planner.plan(POS, state(1), goal, POLICY, CHAIN, 16).expandedNodes());
  }

  @Test
  void moreRoundRobinCandidatesThanRetryEntriesStillReachSolutionsBeyondTheFirstCap() {
    var planner = new AdaptivePlanner(2, 16, 1);
    var solved = new java.util.HashSet<Position>();
    var goal = new TargetCell.Exact(state(9));
    for (int round = 0; round < 5 && solved.size() < 3; round++)
      for (int candidate = 0; candidate < 3; candidate++) {
        var pos = POS.offset(candidate, 0, 0);
        if (solved.contains(pos)) continue;
        var attempt = planner.plan(pos, state(0), goal, POLICY, CHAIN, 16);
        assertTrue(attempt.expandedNodes() <= 16);
        if (attempt.result() instanceof Planner.Result.Ready ready) {
          assertEquals(9, ready.steps().size());
          assertTrue(
              ready.steps().stream().noneMatch(step -> step.action() == Planner.Action.BREAK));
          solved.add(pos);
        } else assertInstanceOf(Planner.Result.Deferred.class, attempt.result());
      }
    assertEquals(3, solved.size());
  }
}

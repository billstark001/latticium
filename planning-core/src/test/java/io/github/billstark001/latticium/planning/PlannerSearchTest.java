package io.github.billstark001.latticium.planning;

import static org.junit.jupiter.api.Assertions.*;

import io.github.billstark001.latticium.dsl.Model.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlannerSearchTest {
  private static final Position POS =
      new Position(ResourceId.parse("minecraft:overworld"), 0, 64, 0);
  private static final Profile.Policy POLICY =
      new Profile.Policy(Profile.Policy.BreakMode.DENY, 1, 100);

  private static BlockState state(int index) {
    return new BlockState(ResourceId.parse("test:state"), Map.of("index", Integer.toString(index)));
  }

  private static Planner.Proposal step(BlockState state, int materials) {
    return new Planner.Proposal(
        Planner.Action.INTERACT,
        state,
        Set.of(POS),
        new Planner.Cost(1, materials, 0),
        "test:transition");
  }

  @Test
  void boundedSlicesProgressWithoutRepeatingExpansions() {
    var calls = new HashMap<BlockState, Integer>();
    Planner.Oracle oracle =
        (position, current, target) -> {
          calls.merge(current, 1, Integer::sum);
          int index = Integer.parseInt(current.properties().get("index"));
          return new Planner.Prediction.Proposals(
              List.of(step(state(index + 1), 0), step(current, 0)));
        };
    var search =
        new Planner().start(POS, state(0), new TargetCell.Exact(state(30)), POLICY, oracle);
    for (int slice = 0; slice < 9; slice++) {
      assertInstanceOf(Planner.Result.Deferred.class, search.advance(3));
      assertTrue(search.pending());
      assertEquals((slice + 1) * 3, search.expandedNodes());
    }
    var result = assertInstanceOf(Planner.Result.Ready.class, search.advance(3));
    assertEquals(30, result.steps().size());
    assertEquals(30, result.cost().actions());
    assertFalse(search.pending());
    assertEquals(30, search.expandedNodes());
    assertTrue(calls.values().stream().allMatch(count -> count == 1));
    assertSame(result, search.advance(1));
    assertEquals(30, calls.size());
    assertThrows(IllegalArgumentException.class, () -> search.advance(0));
  }

  @Test
  void seededGraphsChooseSameCheapestPlanAcrossWorkBudgets() {
    var random = new Random(0x10062026);
    for (int sample = 0; sample < 50; sample++) {
      var graph = new HashMap<BlockState, List<Planner.Proposal>>();
      for (int node = 0; node < 25; node++) {
        var edges = new ArrayList<Planner.Proposal>();
        if (node < 24) edges.add(step(state(node + 1), random.nextInt(4)));
        for (int edge = 0; edge < 5; edge++)
          edges.add(step(state(random.nextInt(25)), random.nextInt(4)));
        graph.put(state(node), edges);
      }
      Planner.Oracle oracle =
          (position, current, target) -> new Planner.Prediction.Proposals(graph.get(current));
      var planner = new Planner();
      var expected =
          planner.plan(POS, state(0), new TargetCell.Exact(state(24)), POLICY, oracle, 100);
      var search = planner.start(POS, state(0), new TargetCell.Exact(state(24)), POLICY, oracle);
      Planner.Result actual;
      do {
        actual = search.advance(1 + random.nextInt(3));
      } while (search.pending());
      assertEquals(expected, actual, "Graph " + sample);
    }
  }

  @Test
  void unavailableFactsTerminateFrozenSearchAndNeedFreshInputs() {
    int[] calls = {0};
    var search =
        new Planner()
            .start(
                POS,
                state(0),
                new TargetCell.Exact(state(1)),
                POLICY,
                (p, s, g) -> {
                  calls[0]++;
                  return new Planner.Prediction.Unknown("Missing neighbor");
                });
    var result = search.advance(1);
    assertEquals(new Planner.Result.Deferred("Missing neighbor"), result);
    assertFalse(search.pending());
    assertSame(result, search.advance(1));
    assertEquals(1, calls[0]);
    var completed =
        new Planner()
            .start(
                POS,
                state(0),
                new TargetCell.Exact(state(0)),
                POLICY,
                (p, s, g) -> fail("Already satisfied"));
    assertInstanceOf(Planner.Result.Complete.class, completed.advance(1));
    assertEquals(0, completed.expandedNodes());
  }

  @Test
  void retainedStateLimitDefersInsteadOfGrowingWithoutBound() {
    var search =
        new Planner()
            .start(
                POS,
                state(0),
                new TargetCell.Exact(state(-1)),
                POLICY,
                (p, s, g) -> {
                  var steps = new ArrayList<Planner.Proposal>();
                  for (int i = 1; i <= Planner.MAX_SEARCH_STATES; i++) steps.add(step(state(i), 0));
                  return new Planner.Prediction.Proposals(steps);
                });
    assertEquals(new Planner.Result.Deferred("Search state limit exceeded"), search.advance(1));
    assertFalse(search.pending());
    assertEquals(1, search.expandedNodes());
  }
}

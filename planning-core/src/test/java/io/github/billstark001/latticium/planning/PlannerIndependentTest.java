package io.github.billstark001.latticium.planning;

import static org.junit.jupiter.api.Assertions.*;

import io.github.billstark001.latticium.dsl.Model.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Independent action-layer dynamic programming, rather than another invocation of the search. */
class PlannerIndependentTest {
  private static final Position POS = new Position(ResourceId.parse("overworld"), 0, 64, 0);
  private static final int STATES = 12;
  private static final List<BlockState> VALUES =
      IntStream.range(0, STATES)
          .mapToObj(
              i ->
                  new BlockState(
                      ResourceId.parse("test:node"), Map.of("index", Integer.toString(i))))
          .toList();

  @Test
  void seededGraphsMatchAnIndependentBoundedOptimum() {
    var random = new Random(0x15_10_2026L);
    for (int sample = 0; sample < 1000; sample++) {
      int actionBudget = 1 + random.nextInt(10);
      var breaking =
          random.nextBoolean() ? Profile.Policy.BreakMode.SELECTED : Profile.Policy.BreakMode.DENY;
      var policy = new Profile.Policy(breaking, 1, actionBudget);
      var graph = new ArrayList<List<Planner.Proposal>>();
      for (int state = 0; state < STATES; state++) {
        var edges = new ArrayList<Planner.Proposal>();
        for (int edge = 0; edge < 6; edge++) {
          int effects = random.nextInt(8);
          edges.add(
              new Planner.Proposal(
                  random.nextInt(4) == 0 ? Planner.Action.BREAK : Planner.Action.INTERACT,
                  VALUES.get(random.nextInt(STATES)),
                  effects == 0
                      ? Set.of()
                      : effects == 1 ? Set.of(POS, POS.offset(1, 0, 0)) : Set.of(POS),
                  new Planner.Cost(1 + random.nextInt(3), random.nextInt(5), random.nextInt(5)),
                  "test:graph"));
        }
        graph.add(List.copyOf(edges));
      }
      var expected = optimum(graph, policy);
      var actual =
          new Planner()
              .plan(
                  POS,
                  VALUES.getFirst(),
                  new TargetCell.Exact(VALUES.getLast()),
                  policy,
                  (p, current, target) ->
                      new Planner.Prediction.Proposals(graph.get(index(current))),
                  1000);
      if (expected == null)
        assertInstanceOf(Planner.Result.NoPlan.class, actual, "Graph " + sample);
      else {
        var ready = assertInstanceOf(Planner.Result.Ready.class, actual, "Graph " + sample);
        assertEquals(expected, ready.cost(), "Graph " + sample);
        assertEquals(VALUES.getLast(), ready.steps().getLast().result(), "Graph " + sample);
      }
    }
  }

  private static Planner.Cost optimum(List<List<Planner.Proposal>> graph, Profile.Policy policy) {
    var layers = new Planner.Cost[policy.maxActionsPerActivation() + 1][STATES];
    layers[0][0] = new Planner.Cost(0, 0, 0);
    for (int actions = 0; actions < layers.length; actions++) {
      for (int state = 0; state < STATES; state++) {
        var prefix = layers[actions][state];
        if (prefix == null) continue;
        for (var edge : graph.get(state)) {
          if (edge.affected().size() != 1
              || !edge.affected().contains(POS)
              || edge.action() == Planner.Action.BREAK
                  && policy.breakMode() == Profile.Policy.BreakMode.DENY) continue;
          int next = actions + edge.cost().actions();
          if (next >= layers.length) continue;
          var cost =
              new Planner.Cost(
                  next,
                  prefix.materials() + edge.cost().materials(),
                  prefix.risk() + edge.cost().risk());
          int to = index(edge.result());
          if (layers[next][to] == null || cost.compareTo(layers[next][to]) < 0)
            layers[next][to] = cost;
        }
      }
    }
    for (var layer : layers) if (layer[STATES - 1] != null) return layer[STATES - 1];
    return null;
  }

  private static int index(BlockState state) {
    return Integer.parseInt(state.properties().get("index"));
  }
}

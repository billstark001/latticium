package io.github.billstark001.latticium.planning;

import io.github.billstark001.latticium.dsl.Model.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/** Bounded state graph search. An oracle supplies version-specific legal transitions. */
public final class Planner {
  public enum Action {
    PLACE,
    USE_ITEM,
    INTERACT,
    BREAK
  }

  public record Cost(int actions, int materials, int risk) implements Comparable<Cost> {
    public Cost {
      if (actions < 0 || materials < 0 || risk < 0)
        throw new IllegalArgumentException("Negative cost");
    }

    public Cost plus(Cost c) {
      return new Cost(
          Math.addExact(actions, c.actions),
          Math.addExact(materials, c.materials),
          Math.addExact(risk, c.risk));
    }

    @Override
    public int compareTo(Cost c) {
      int a = Integer.compare(actions, c.actions);
      if (a != 0) return a;
      a = Integer.compare(materials, c.materials);
      return a != 0 ? a : Integer.compare(risk, c.risk);
    }
  }

  public record Proposal(
      Action action, BlockState result, Set<Position> affected, Cost cost, String ruleId) {
    public Proposal {
      affected = Set.copyOf(affected);
      if (cost.actions() == 0) throw new IllegalArgumentException("A step must cost an action");
    }
  }

  public interface Oracle {
    Prediction predict(Position pos, BlockState current, TargetCell goal);
  }

  public sealed interface Prediction
      permits Prediction.Proposals,
          Prediction.Unsupported,
          Prediction.Unknown,
          Prediction.NoLegalPlacement {
    record Proposals(List<Proposal> steps) implements Prediction {
      public Proposals {
        steps = List.copyOf(steps);
      }
    }

    record Unsupported(String reason) implements Prediction {}

    record Unknown(String reason) implements Prediction {}

    record NoLegalPlacement(String reason) implements Prediction {}
  }

  public sealed interface Result
      permits Result.Ready, Result.Complete, Result.NoPlan, Result.Deferred {
    record Ready(List<Proposal> steps, Cost cost) implements Result {
      public Ready {
        steps = List.copyOf(steps);
      }
    }

    record Complete() implements Result {}

    record NoPlan(String reason) implements Result {}

    record Deferred(String reason) implements Result {}
  }

  private record Node(BlockState state, Cost cost, List<Proposal> path) {}

  public Result plan(
      Position pos,
      BlockState current,
      TargetCell goal,
      Profile.Policy policy,
      Oracle oracle,
      int maxNodes) {
    if (maxNodes <= 0) throw new IllegalArgumentException("Positive node budget required");
    if (goal instanceof TargetCell.Unknown x) return new Result.Deferred(x.reason());
    if (goal instanceof TargetCell.DontCare) return new Result.Complete();
    if (matches(current, goal)) return new Result.Complete();
    var queue = new PriorityQueue<Node>(Comparator.comparing(Node::cost));
    var best = new HashMap<BlockState, Cost>();
    queue.add(new Node(current, new Cost(0, 0, 0), List.of()));
    best.put(current, new Cost(0, 0, 0));
    int visited = 0;
    String last = "No allowed transition";
    while (!queue.isEmpty() && visited++ < maxNodes) {
      Node node = queue.remove();
      if (!node.cost().equals(best.get(node.state()))) continue;
      var prediction = oracle.predict(pos, node.state(), goal);
      if (prediction instanceof Prediction.Unknown x) return new Result.Deferred(x.reason());
      if (prediction instanceof Prediction.Unsupported x) {
        last = x.reason();
        continue;
      }
      if (prediction instanceof Prediction.NoLegalPlacement x) {
        last = x.reason();
        continue;
      }
      for (var step : ((Prediction.Proposals) prediction).steps()) {
        if (step.action() == Action.BREAK && policy.breakMode() == Profile.Policy.BreakMode.DENY)
          continue;
        if (!step.affected().isEmpty() && !step.affected().equals(Set.of(pos)))
          continue; // no undeclared collateral effects in single-cell planner
        Cost cost = node.cost().plus(step.cost());
        if (cost.actions() > policy.maxActionsPerActivation()) continue;
        if (best.containsKey(step.result()) && best.get(step.result()).compareTo(cost) <= 0)
          continue;
        var path = new ArrayList<>(node.path());
        path.add(step);
        if (matches(step.result(), goal)) return new Result.Ready(path, cost);
        best.put(step.result(), cost);
        queue.add(new Node(step.result(), cost, path));
      }
    }
    return new Result.NoPlan(visited >= maxNodes ? "Search budget exhausted" : last);
  }

  private static boolean matches(BlockState state, TargetCell goal) {
    if (goal instanceof TargetCell.Exact x) return x.state().equals(state);
    return goal instanceof TargetCell.Clear
        && state.block().equals(ResourceId.parse("minecraft:air"));
  }
}

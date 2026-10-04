package io.github.billstark001.latticium.planning;

import static io.github.billstark001.latticium.dsl.Model.isVanillaAir;

import io.github.billstark001.latticium.dsl.Model.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
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

  /** A concrete normal-player interaction, chosen by a version-specific oracle. */
  public record Interaction(
      int inventorySlot, Face face, double hitX, double hitY, double hitZ, Position clicked) {
    public Interaction(int inventorySlot, Face face, double hitX, double hitY, double hitZ) {
      this(inventorySlot, face, hitX, hitY, hitZ, null);
    }

    public enum Face {
      DOWN,
      UP,
      NORTH,
      SOUTH,
      WEST,
      EAST
    }

    public Interaction {
      Objects.requireNonNull(face, "face");
      if (inventorySlot < -1 || inventorySlot > 8)
        throw new IllegalArgumentException("Invalid hotbar slot");
      if (!Double.isFinite(hitX) || !Double.isFinite(hitY) || !Double.isFinite(hitZ))
        throw new IllegalArgumentException("Non-finite hit position");
    }
  }

  /** Nonnegative lexicographic cost: actions first, then materials, then risk. */
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
      Action action,
      BlockState result,
      Set<Position> affected,
      Cost cost,
      String ruleId,
      Interaction interaction) {
    public Proposal(
        Action action, BlockState result, Set<Position> affected, Cost cost, String ruleId) {
      this(action, result, affected, cost, ruleId, null);
    }

    public Proposal {
      Objects.requireNonNull(action, "action");
      Objects.requireNonNull(result, "result");
      Objects.requireNonNull(cost, "cost");
      Objects.requireNonNull(ruleId, "ruleId");
      affected = Set.copyOf(affected);
      if (cost.actions() == 0) throw new IllegalArgumentException("A step must cost an action");
    }

    /** A single-cell step must explicitly declare its changed position. */
    public boolean affectsOnly(Position pos) {
      return affected.size() == 1 && affected.contains(pos);
    }
  }

  public interface Oracle {
    /** Predicts legal transitions from one state without performing an action. */
    Prediction predict(Position pos, BlockState current, TargetCell goal);
  }

  /** Oracle result: proposals, missing facts, unsupported behavior or no legal move. */
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

    record Unsupported(String reason) implements Prediction {
      public Unsupported {
        Objects.requireNonNull(reason, "reason");
      }
    }

    record Unknown(String reason) implements Prediction {
      public Unknown {
        Objects.requireNonNull(reason, "reason");
      }
    }

    record NoLegalPlacement(String reason) implements Prediction {
      public NoLegalPlacement {
        Objects.requireNonNull(reason, "reason");
      }
    }
  }

  /** Search result; Ready still requires gateway submission and world observation. */
  public sealed interface Result
      permits Result.Ready, Result.Complete, Result.NoPlan, Result.Deferred {
    record Ready(BlockState initial, List<Proposal> steps, Cost cost) implements Result {
      public Ready {
        Objects.requireNonNull(initial, "initial");
        steps = List.copyOf(steps);
        Objects.requireNonNull(cost, "cost");
        if (steps.isEmpty()) throw new IllegalArgumentException("Ready plan has no actions");
        Cost total = new Cost(0, 0, 0);
        for (var step : steps) total = total.plus(step.cost());
        if (!cost.equals(total)) throw new IllegalArgumentException("Plan cost differs from steps");
      }
    }

    record Complete() implements Result {}

    record NoPlan(String reason) implements Result {}

    record Deferred(String reason) implements Result {}
  }

  private record Node(BlockState state, Cost cost, Node previous, Proposal step) {}

  /**
   * Searches legal single-cell transitions in lexicographic cost order. {@code maxNodes} limits
   * expanded states; the policy limits action count. A ready path is cheapest among known
   * transitions; unknown predictions may hide alternatives. If no known path exists, missing facts
   * yield Deferred. The returned steps are proposals, never confirmed actions.
   */
  public Result plan(
      Position pos,
      BlockState current,
      TargetCell goal,
      Profile.Policy policy,
      Oracle oracle,
      int maxNodes) {
    Objects.requireNonNull(pos, "pos");
    Objects.requireNonNull(current, "current");
    Objects.requireNonNull(goal, "goal");
    Objects.requireNonNull(policy, "policy");
    Objects.requireNonNull(oracle, "oracle");
    if (maxNodes <= 0) throw new IllegalArgumentException("Positive node budget required");
    if (goal instanceof TargetCell.Unknown x) return new Result.Deferred(x.reason());
    if (goal instanceof TargetCell.DontCare) return new Result.Complete();
    if (matches(current, goal)) return new Result.Complete();
    var queue = new PriorityQueue<Node>(Comparator.comparing(Node::cost));
    var best = new HashMap<BlockState, Cost>();
    queue.add(new Node(current, new Cost(0, 0, 0), null, null));
    best.put(current, new Cost(0, 0, 0));
    int visited = 0;
    String last = "No allowed transition";
    String unknownReason = null;
    while (!queue.isEmpty()) {
      Node node = queue.remove();
      if (!node.cost().equals(best.get(node.state()))) continue;
      // A goal is optimal only when it leaves the cost-ordered queue.
      if (matches(node.state(), goal)) return new Result.Ready(current, pathTo(node), node.cost());
      if (visited++ >= maxNodes) return new Result.NoPlan("Search budget exhausted");
      var prediction = oracle.predict(pos, node.state(), goal);
      if (prediction instanceof Prediction.Unknown x) {
        if (unknownReason == null) unknownReason = x.reason();
        continue;
      }
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
        if (!step.affectsOnly(pos))
          continue; // no undeclared collateral effects in single-cell planner
        Cost cost;
        try {
          cost = node.cost().plus(step.cost());
        } catch (ArithmeticException overflow) {
          continue;
        }
        if (cost.actions() > policy.maxActionsPerActivation()) continue;
        var previous = best.get(step.result());
        if (previous != null && previous.compareTo(cost) <= 0) continue;
        best.put(step.result(), cost);
        queue.add(new Node(step.result(), cost, node, step));
      }
    }
    return unknownReason == null ? new Result.NoPlan(last) : new Result.Deferred(unknownReason);
  }

  private static boolean matches(BlockState state, TargetCell goal) {
    if (goal instanceof TargetCell.Exact x) return x.state().equals(state);
    return goal instanceof TargetCell.Clear && isVanillaAir(state);
  }

  private static List<Proposal> pathTo(Node goal) {
    var reversed = new ArrayList<Proposal>();
    for (Node node = goal; node.previous() != null; node = node.previous())
      reversed.add(node.step());
    return reversed.reversed();
  }
}

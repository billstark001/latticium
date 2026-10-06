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
    /** Predicts legal transitions from one state without performing an action. Failure defers. */
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
   * transitions; unknown predictions may hide alternatives. Missing facts or an exhausted node
   * budget yield Deferred rather than proving that no path exists. The returned steps are
   * proposals, never confirmed actions.
   */
  public Result plan(
      Position pos,
      BlockState current,
      TargetCell goal,
      Profile.Policy policy,
      Oracle oracle,
      int maxNodes) {
    return start(pos, current, goal, policy, oracle).advance(maxNodes);
  }

  /** Maximum retained states/frontier entries; an exhausted memory limit remains Deferred. */
  public static final int MAX_SEARCH_STATES = 65_536;

  /**
   * Starts a resumable search over one frozen input view. The oracle must keep the same facts,
   * inventory, rules and prediction behavior throughout its lifetime. The host must discard the
   * search when any relevant input changes; a search is never an authorization to act on stale
   * data. Calls and returned proposals are pure planning work and are not thread-safe.
   */
  public Search start(
      Position pos, BlockState current, TargetCell goal, Profile.Policy policy, Oracle oracle) {
    return new Search(pos, current, goal, policy, oracle);
  }

  /** Retains the Dijkstra frontier between bounded work slices instead of repeating expansions. */
  public static final class Search {
    private final Position pos;
    private final BlockState initial;
    private final TargetCell goal;
    private final Profile.Policy policy;
    private final Oracle oracle;
    private final PriorityQueue<Node> queue = new PriorityQueue<>(Comparator.comparing(Node::cost));
    private final HashMap<BlockState, Cost> best = new HashMap<>();
    private String last = "No allowed transition";
    private String unknownReason;
    private Result terminal;
    private long expanded;

    private Search(
        Position pos, BlockState current, TargetCell goal, Profile.Policy policy, Oracle oracle) {
      this.pos = Objects.requireNonNull(pos, "pos");
      this.initial = Objects.requireNonNull(current, "current");
      this.goal = Objects.requireNonNull(goal, "goal");
      this.policy = Objects.requireNonNull(policy, "policy");
      this.oracle = Objects.requireNonNull(oracle, "oracle");
      if (goal instanceof TargetCell.Unknown unknown)
        terminal = new Result.Deferred(unknown.reason());
      else if (goal instanceof TargetCell.DontCare || matches(current, goal))
        terminal = new Result.Complete();
      else {
        var zero = new Cost(0, 0, 0);
        queue.add(new Node(current, zero, null, null));
        best.put(current, zero);
      }
    }

    /** True only for a work-budget deferral with a frontier that can still make progress. */
    public boolean pending() {
      return terminal == null;
    }

    /** Number of oracle calls already made across all slices, including unavailable predictions. */
    public long expandedNodes() {
      return expanded;
    }

    /**
     * Expands at most {@code maxNodes} additional states. Budget exhaustion preserves the next
     * unexpanded node. Unknown predictions and exhausted graphs terminate this frozen search;
     * obtaining new facts requires a new search. A terminal result is stable on repeated calls.
     */
    public Result advance(int maxNodes) {
      if (maxNodes <= 0) throw new IllegalArgumentException("Positive node budget required");
      if (terminal != null) return terminal;
      int visited = 0;
      while (!queue.isEmpty()) {
        Node node = queue.peek();
        if (!node.cost().equals(best.get(node.state()))) {
          queue.remove();
          continue;
        }
        // A goal is optimal only when it leaves the cost-ordered queue.
        if (matches(node.state(), goal))
          return finish(new Result.Ready(initial, pathTo(node), node.cost()));
        if (visited >= maxNodes) return new Result.Deferred("Search budget exhausted");
        queue.remove();
        visited++;
        expanded++;
        Prediction prediction;
        try {
          prediction = Objects.requireNonNull(oracle.predict(pos, node.state(), goal));
        } catch (RuntimeException error) {
          if (unknownReason == null)
            unknownReason = "Placement oracle unavailable: " + error.getClass().getSimpleName();
          continue;
        }
        if (prediction instanceof Prediction.Unknown unknown) {
          if (unknownReason == null) unknownReason = unknown.reason();
          continue;
        }
        if (prediction instanceof Prediction.Unsupported unsupported) {
          last = unsupported.reason();
          continue;
        }
        if (prediction instanceof Prediction.NoLegalPlacement unavailable) {
          last = unavailable.reason();
          continue;
        }
        for (var step : ((Prediction.Proposals) prediction).steps()) {
          if (step.action() == Action.BREAK && policy.breakMode() == Profile.Policy.BreakMode.DENY)
            continue;
          if (!step.affectsOnly(pos)) continue;
          Cost cost;
          try {
            cost = node.cost().plus(step.cost());
          } catch (ArithmeticException overflow) {
            continue;
          }
          if (cost.actions() > policy.maxActionsPerActivation()) continue;
          var previous = best.get(step.result());
          if (previous != null && previous.compareTo(cost) <= 0) continue;
          if ((previous == null && best.size() >= MAX_SEARCH_STATES)
              || queue.size() >= MAX_SEARCH_STATES)
            return finish(new Result.Deferred("Search state limit exceeded"));
          best.put(step.result(), cost);
          queue.add(new Node(step.result(), cost, node, step));
        }
      }
      return finish(
          unknownReason == null ? new Result.NoPlan(last) : new Result.Deferred(unknownReason));
    }

    private Result finish(Result result) {
      terminal = result;
      queue.clear();
      best.clear();
      return result;
    }
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

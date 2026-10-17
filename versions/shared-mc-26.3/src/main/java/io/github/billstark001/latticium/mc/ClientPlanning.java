package io.github.billstark001.latticium.mc;

import io.github.billstark001.latticium.dsl.Model.*;
import io.github.billstark001.latticium.planning.AdaptivePlanner;
import io.github.billstark001.latticium.planning.Execution;
import io.github.billstark001.latticium.planning.Planner;
import io.github.billstark001.latticium.planning.Profile;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Fresh native planning, bounded retries and the conservative destructive fallback. */
final class ClientPlanning {
  private static final BlockState AIR = new BlockState(ResourceId.parse("minecraft:air"), Map.of());
  private final AdaptivePlanner adaptive;
  private final int maxNodesPerTick;
  private final MinecraftPlacementOracle oracle;
  private final ClientMaterialGate materials;
  private final Profile.Policy fullPolicy;

  record Outcome(
      Planner.Result result,
      boolean partial,
      boolean budgetExhausted,
      List<Planner.Proposal> preconditions) {
    Outcome(Planner.Result result, boolean partial, boolean budgetExhausted) {
      this(result, partial, budgetExhausted, List.of());
    }

    Outcome {
      preconditions = List.copyOf(preconditions);
    }
  }

  ClientPlanning(
      MinecraftPlacementOracle oracle, ClientMaterialGate materials, Profile.Policy policy) {
    this(oracle, materials, policy, Execution.DEFAULT.planning());
  }

  ClientPlanning(
      MinecraftPlacementOracle oracle,
      ClientMaterialGate materials,
      Profile.Policy policy,
      Execution.Planning limits) {
    adaptive =
        new AdaptivePlanner(
            limits.initialNodes(), limits.maxNodesPerTick(), limits.maxRetryEntries());
    maxNodesPerTick = limits.maxNodesPerTick();
    this.oracle = oracle;
    this.materials = materials;
    fullPolicy = policy;
  }

  Outcome plan(
      Position pos, BlockState current, TargetCell target, Profile.Policy policy, Facts facts) {
    var attempt =
        adaptive.plan(pos, current, target, policy, materials.forFacts(facts), maxNodesPerTick);
    var result = attempt.result();
    if (result instanceof Planner.Result.NoPlan
        && !ClientJobView.isAir(current)
        && policy.breakMode() == Profile.Policy.BreakMode.SELECTED) {
      try {
        var reason = oracle.breakDeferral(pos);
        if (reason != null) return deferred(reason);
        if (!ClientTargetResolver.hasReplacementBudget(target, policy))
          return new Outcome(
              new Planner.Result.NoPlan("Replacement needs at least two remaining actions"),
              false,
              true);
        List<Planner.Proposal> preconditions = List.of();
        if (target instanceof TargetCell.Exact exact) {
          var proof = materials.placementAfterBreak(pos, exact.state(), facts);
          if (proof.isEmpty())
            return deferred("No allowed verifiable post-break placement for exact target");
          preconditions = List.of(proof.get());
        }
        var step =
            new Planner.Proposal(
                Planner.Action.BREAK,
                AIR,
                Set.of(pos),
                new Planner.Cost(1, 0, 1),
                "latticium:normal_break");
        return new Outcome(
            new Planner.Result.Ready(current, List.of(step), step.cost()),
            !(target instanceof TargetCell.Clear),
            false,
            preconditions);
      } catch (RuntimeException unavailable) {
        return deferred(
            "Post-break prediction unavailable: " + unavailable.getClass().getSimpleName());
      }
    }
    // Diagnostics share the same hard work cap as the primary search.
    int remaining = maxNodesPerTick - attempt.expandedNodes();
    if (result instanceof Planner.Result.NoPlan
        && !policy.equals(fullPolicy)
        && remaining > 0
        && new Planner()
                .plan(pos, current, target, fullPolicy, materials.forFacts(facts), remaining)
            instanceof Planner.Result.Ready)
      return new Outcome(
          new Planner.Result.NoPlan("Remaining action budget too small"), false, true);
    return new Outcome(result, false, false);
  }

  private static Outcome deferred(String reason) {
    return new Outcome(new Planner.Result.Deferred(reason), false, false);
  }

  void forget(Position pos) {
    adaptive.forget(pos);
  }

  void clear() {
    adaptive.clear();
  }
}

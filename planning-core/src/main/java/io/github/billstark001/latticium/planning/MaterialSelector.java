package io.github.billstark001.latticium.planning;

import io.github.billstark001.latticium.dsl.Compiler;
import io.github.billstark001.latticium.dsl.Model.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Chooses one concrete, verifiable state for an item-targeted candidate. */
public final class MaterialSelector {
  public interface Oracle {
    /** Returns verifiable placement states, unknown data, or an unsupported capability. */
    Outcome statesFor(ResourceId item, Position pos);
  }

  /** Placement oracle result; Unknown means more facts may resolve the question. */
  public sealed interface Outcome permits Outcome.States, Outcome.Unknown, Outcome.Unsupported {
    record States(Set<BlockState> states) implements Outcome {
      public States {
        states = Set.copyOf(states);
      }
    }

    record Unknown(String reason) implements Outcome {}

    record Unsupported(String reason) implements Outcome {}
  }

  /** Frozen target, retryable deferral, missing capability or known absence of a target. */
  public sealed interface Choice
      permits Choice.Frozen, Choice.Deferred, Choice.Unsupported, Choice.NoTarget {
    record Frozen(ResourceId item, TargetCell.Exact target) implements Choice {}

    record Deferred(String reason) implements Choice {}

    record Unsupported(String reason) implements Choice {}

    record NoTarget(String reason) implements Choice {}
  }

  /**
   * Preserves the current block if any available item accepts it; otherwise chooses a verifiable
   * state in preference order. Unknown facts about an earlier preference defer a changed target.
   */
  public Choice choose(
      Position pos,
      BlockState current,
      Compiler.Bound items,
      Compiler.Bound states,
      Facts facts,
      Map<ResourceId, Integer> inventory,
      List<ResourceId> preferred,
      Oracle oracle) {
    if (items.type() != SetType.ITEM || states != null && states.type() != SetType.STATE)
      throw new IllegalArgumentException("Invalid target set");
    var quantities = Map.copyOf(inventory);
    var ids = new ArrayList<ResourceId>();
    for (var entry : quantities.entrySet()) if (entry.getValue() > 0) ids.add(entry.getKey());
    Facts itemFacts = withInventory(facts, Set.copyOf(ids));
    boolean unknownHigherPriority = false;
    boolean fallbackUncertain = false;
    Choice.Frozen fallback = null;
    String unsupported = null;
    var preferenceRank = new HashMap<ResourceId, Integer>();
    for (int i = 0; i < preferred.size(); i++) preferenceRank.putIfAbsent(preferred.get(i), i);
    ids.sort(
        Comparator.comparingInt(
                (ResourceId id) -> preferenceRank.getOrDefault(id, Integer.MAX_VALUE))
            .thenComparing(Comparator.<ResourceId>comparingInt(quantities::get).reversed())
            .thenComparing(ResourceId::compareTo));
    for (var item : ids) {
      Truth itemMembership = items.contains(itemFacts, item);
      if (itemMembership == Truth.FALSE) continue;
      if (itemMembership == Truth.UNKNOWN) {
        unknownHigherPriority = true;
        continue;
      }
      var result = oracle.statesFor(item, pos);
      if (result instanceof Outcome.Unknown) {
        unknownHigherPriority = true;
        continue;
      }
      if (result instanceof Outcome.Unsupported unavailable) {
        if (unsupported == null) unsupported = unavailable.reason();
        continue;
      }
      if (!(result instanceof Outcome.States s)) continue;
      var candidates = new ArrayList<BlockState>();
      boolean unknownState = false;
      for (var state : s.states()) {
        Truth membership = states == null ? Truth.TRUE : states.contains(itemFacts, state);
        if (membership == Truth.TRUE) candidates.add(state);
        else if (membership == Truth.UNKNOWN) unknownState = true;
      }
      if (candidates.isEmpty()) {
        unknownHigherPriority |= unknownState;
        continue;
      }
      if (candidates.contains(current))
        return new Choice.Frozen(item, new TargetCell.Exact(current));
      if (fallback == null) {
        var chosen =
            candidates.stream().min(Comparator.comparing(BlockState::canonicalId)).orElseThrow();
        fallback = new Choice.Frozen(item, new TargetCell.Exact(chosen));
        fallbackUncertain = unknownHigherPriority || unknownState;
      }
    }
    if (fallback != null)
      return fallbackUncertain ? new Choice.Deferred("Placement facts unavailable") : fallback;
    if (unknownHigherPriority) return new Choice.Deferred("Placement facts unavailable");
    if (unsupported != null) return new Choice.Unsupported(unsupported);
    return new Choice.NoTarget("No available item has a verifiable accepted state");
  }

  private static Facts withInventory(Facts base, Set<ResourceId> available) {
    return new Facts() {
      public Optional<WorldCell> world(Position pos) {
        return base == null ? Optional.empty() : base.world(pos);
      }

      public TargetCell target(Position pos) {
        return base == null ? new TargetCell.Unknown("Target unavailable") : base.target(pos);
      }

      public Optional<Position> player() {
        return base == null ? Optional.empty() : base.player();
      }

      public Optional<Set<ResourceId>> inventory() {
        return Optional.of(available);
      }

      public Truth selection(String name, Position pos) {
        return base == null ? Truth.UNKNOWN : base.selection(name, pos);
      }
    };
  }
}

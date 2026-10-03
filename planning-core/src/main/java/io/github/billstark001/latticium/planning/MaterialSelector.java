package io.github.billstark001.latticium.planning;

import io.github.billstark001.latticium.dsl.Compiler;
import io.github.billstark001.latticium.dsl.Model.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Chooses one concrete, verifiable state for an item-targeted candidate. */
public final class MaterialSelector {
  public interface Oracle {
    /** Returns verifiable placement states, unknown data, or an unsupported capability. */
    Outcome statesFor(ResourceId item, Position pos);
  }

  public sealed interface Outcome permits Outcome.States, Outcome.Unknown, Outcome.Unsupported {
    record States(Set<BlockState> states) implements Outcome {
      public States {
        states = Set.copyOf(states);
      }
    }

    record Unknown(String reason) implements Outcome {}

    record Unsupported(String reason) implements Outcome {}
  }

  public sealed interface Choice
      permits Choice.Frozen, Choice.Deferred, Choice.Unsupported, Choice.NoTarget {
    record Frozen(ResourceId item, TargetCell.Exact target) implements Choice {}

    record Deferred(String reason) implements Choice {}

    record Unsupported(String reason) implements Choice {}

    record NoTarget(String reason) implements Choice {}
  }

  /** Chooses an available item with a verifiable accepted state, honoring preference order. */
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
    var ids = new ArrayList<ResourceId>();
    boolean unknown = false;
    String unsupported = null;
    for (var entry : inventory.entrySet())
      if (entry.getValue() > 0) {
        Truth membership = items.contains(facts, entry.getKey());
        if (membership == Truth.TRUE) ids.add(entry.getKey());
        else if (membership == Truth.UNKNOWN) unknown = true;
      }
    var preferenceRank = new HashMap<ResourceId, Integer>();
    for (int i = 0; i < preferred.size(); i++) preferenceRank.putIfAbsent(preferred.get(i), i);
    ids.sort(
        Comparator.comparingInt(
                (ResourceId id) -> preferenceRank.getOrDefault(id, Integer.MAX_VALUE))
            .thenComparing(Comparator.<ResourceId>comparingInt(inventory::get).reversed())
            .thenComparing(ResourceId::compareTo));
    for (var item : ids) {
      var result = oracle.statesFor(item, pos);
      if (result instanceof Outcome.Unknown) {
        unknown = true;
        continue;
      }
      if (result instanceof Outcome.Unsupported unavailable) {
        if (unsupported == null) unsupported = unavailable.reason();
        continue;
      }
      if (!(result instanceof Outcome.States s)) continue;
      var candidates = new ArrayList<BlockState>();
      for (var state : s.states()) {
        Truth membership = states == null ? Truth.TRUE : states.contains(facts, state);
        if (membership == Truth.TRUE) candidates.add(state);
        else if (membership == Truth.UNKNOWN) unknown = true;
      }
      if (candidates.isEmpty()) continue;
      var chosen =
          candidates.contains(current)
              ? current
              : candidates.stream()
                  .min(Comparator.comparing(BlockState::canonicalId))
                  .orElseThrow();
      return new Choice.Frozen(item, new TargetCell.Exact(chosen));
    }
    if (unknown) return new Choice.Deferred("Placement facts unavailable");
    if (unsupported != null) return new Choice.Unsupported(unsupported);
    return new Choice.NoTarget("No available item has a verifiable accepted state");
  }
}

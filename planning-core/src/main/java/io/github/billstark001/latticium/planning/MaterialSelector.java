package io.github.billstark001.latticium.planning;

import io.github.billstark001.latticium.dsl.Compiler;
import io.github.billstark001.latticium.dsl.Model.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Chooses one concrete, verifiable state for an item-targeted candidate. */
public final class MaterialSelector {
  public interface Oracle {
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

  public sealed interface Choice permits Choice.Frozen, Choice.Deferred, Choice.NoTarget {
    record Frozen(ResourceId item, TargetCell.Exact target) implements Choice {}

    record Deferred(String reason) implements Choice {}

    record NoTarget(String reason) implements Choice {}
  }

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
    for (var entry : inventory.entrySet())
      if (entry.getValue() > 0) {
        Truth membership = items.contains(facts, entry.getKey());
        if (membership == Truth.TRUE) ids.add(entry.getKey());
        else if (membership == Truth.UNKNOWN) unknown = true;
      }
    ids.sort(
        Comparator.comparingInt(
                (ResourceId id) -> {
                  int index = preferred.indexOf(id);
                  return index < 0 ? Integer.MAX_VALUE : index;
                })
            .thenComparing(Comparator.<ResourceId>comparingInt(inventory::get).reversed())
            .thenComparing(ResourceId::compareTo));
    for (var item : ids) {
      var result = oracle.statesFor(item, pos);
      if (result instanceof Outcome.Unknown) {
        unknown = true;
        continue;
      }
      if (!(result instanceof Outcome.States s)) continue;
      if (states != null
          && s.states().stream().anyMatch(state -> states.contains(facts, state) == Truth.UNKNOWN))
        unknown = true;
      var candidates =
          s.states().stream()
              .filter(state -> states == null || states.contains(facts, state) == Truth.TRUE)
              .sorted(Comparator.comparing(BlockState::toString))
              .toList();
      if (candidates.isEmpty()) continue;
      var chosen = candidates.contains(current) ? current : candidates.getFirst();
      return new Choice.Frozen(item, new TargetCell.Exact(chosen));
    }
    return unknown
        ? new Choice.Deferred("Placement facts unavailable")
        : new Choice.NoTarget("No available item has a verifiable accepted state");
  }
}

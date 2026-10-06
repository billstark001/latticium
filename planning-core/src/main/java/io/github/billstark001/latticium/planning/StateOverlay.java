package io.github.billstark001.latticium.planning;

import io.github.billstark001.latticium.dsl.Model.*;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Read-only hypothetical single-cell state for rule guards; never changes captured or live facts.
 */
public final class StateOverlay {
  private StateOverlay() {}

  /**
   * Establishes only the hypothetical block state and frozen target. Biomes and other block states
   * remain available. A changed block can alter light or fluid beyond face neighbors, so derived
   * fluid/light/solidity facts in its dimension become Unknown until the host captures them again.
   * If the state already matches the capture, all captured facts are retained.
   */
  public static Facts at(Facts source, Position at, BlockState state, TargetCell target) {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(at, "at");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(target, "target");
    return new Facts() {
      private Optional<WorldCell> originalAt;
      private Boolean changed;

      private Optional<WorldCell> originalAt() {
        if (originalAt == null) originalAt = source.world(at);
        return originalAt;
      }

      private boolean speculative() {
        if (changed == null) {
          var original = originalAt();
          changed = original.isEmpty() || !state.equals(original.get().state());
        }
        return changed;
      }

      public Optional<WorldCell> world(Position p) {
        var original = p.equals(at) ? originalAt() : source.world(p);
        if (!p.dimension().equals(at.dimension()) || !speculative()) return original;
        if (p.equals(at))
          return Optional.of(
              new WorldCell(state, original.map(WorldCell::biome).orElse(null), null, null, null));
        return original.map(cell -> new WorldCell(cell.state(), cell.biome(), null, null, null));
      }

      public TargetCell target(Position p) {
        return p.equals(at) ? target : source.target(p);
      }

      public Optional<Position> player() {
        return source.player();
      }

      public Optional<Set<ResourceId>> inventory() {
        return source.inventory();
      }

      public Truth selection(String name, Position p) {
        return source.selection(name, p);
      }
    };
  }
}

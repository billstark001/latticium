package io.github.billstark001.latticium.planning;

import io.github.billstark001.latticium.dsl.FactDependencies;
import io.github.billstark001.latticium.dsl.FactDependencies.Fact;
import io.github.billstark001.latticium.dsl.FactDependencies.Offset;
import java.util.List;
import java.util.Objects;

/**
 * Profile intent and finite scheduling limits; none of these options bypass action confirmation.
 */
public record Execution(
    Consistency consistency, Dependencies dependencies, Dynamics dynamics, Planning planning) {
  public enum Granularity {
    CELL,
    SECTION
  }

  public enum Edits {
    STOP,
    FREEZE
  }

  public enum Targets {
    LIVE,
    PINNED
  }

  public enum Inventory {
    RELEVANT,
    HOTBAR,
    FULL
  }

  public enum Reads {
    DERIVED,
    FACES,
    HALO
  }

  public enum Motion {
    STABLE,
    SETTLING
  }

  public enum Repeat {
    ONCE,
    WHILE_SELECTED
  }

  public record Consistency(
      Granularity granularity,
      Edits selections,
      Edits rules,
      Targets targets,
      Inventory inventory) {
    public Consistency(Granularity granularity, Edits selections, Edits rules, Targets targets) {
      this(granularity, selections, rules, targets, Inventory.RELEVANT);
    }

    public Consistency {
      Objects.requireNonNull(granularity);
      Objects.requireNonNull(selections);
      Objects.requireNonNull(rules);
      Objects.requireNonNull(targets);
      Objects.requireNonNull(inventory);
    }
  }

  /** Extensions only widen the dependencies derived by the compiler and native predictor. */
  public record Dependencies(Reads reads, int radius, List<Offset> extraOffsets) {
    public Dependencies {
      Objects.requireNonNull(reads);
      extraOffsets = List.copyOf(extraOffsets);
      if (radius < 0 || radius > 16)
        throw new IllegalArgumentException("Dependency radius must be 0..16");
      if (extraOffsets.size() > 512)
        throw new IllegalArgumentException("At most 512 extra offsets");
      for (var offset : extraOffsets)
        if (Math.abs((long) offset.x()) > 16
            || Math.abs((long) offset.y()) > 16
            || Math.abs((long) offset.z()) > 16)
          throw new IllegalArgumentException("Extra offsets must be within 16 cells");
    }

    public FactDependencies widen(FactDependencies required) {
      var state = FactDependencies.of(Fact.STATE, Fact.FLUID, Fact.SOLID);
      var result = required;
      if (reads == Reads.FACES) result = result.union(state).union(state.adjacent());
      if (reads == Reads.HALO) result = result.union(state.asBroad());
      for (var offset : extraOffsets)
        result = result.union(state.shift(offset.x(), offset.y(), offset.z()));
      return result;
    }

    public int requiredRadius(int derived) {
      int result = Math.max(derived, reads == Reads.FACES ? 1 : reads == Reads.HALO ? radius : 0);
      for (var offset : extraOffsets)
        result =
            Math.max(
                result,
                Math.max(
                    Math.abs(offset.x()), Math.max(Math.abs(offset.y()), Math.abs(offset.z()))));
      return result;
    }
  }

  public record Dynamics(Motion motion, Repeat repeat, int settleTicks) {
    public Dynamics {
      Objects.requireNonNull(motion);
      Objects.requireNonNull(repeat);
      if (settleTicks < 1 || settleTicks > 1200)
        throw new IllegalArgumentException("Settle ticks must be 1..1200");
    }
  }

  public record Planning(
      int initialNodes,
      int maxNodesPerTick,
      int maxRetryEntries,
      int maxCandidates,
      int retryTicks) {
    public Planning {
      if (initialNodes < 1 || maxNodesPerTick < initialNodes || maxNodesPerTick > 65_536)
        throw new IllegalArgumentException("Planning nodes require 1 <= initial <= max <= 65536");
      if (maxRetryEntries < 1 || maxRetryEntries > 65_536)
        throw new IllegalArgumentException("Retry entries must be 1..65536");
      if (maxCandidates < 1 || maxCandidates > 1_000_000)
        throw new IllegalArgumentException("Candidate capacity must be 1..1000000");
      if (retryTicks < 1 || retryTicks > 1200)
        throw new IllegalArgumentException("Retry ticks must be 1..1200");
    }
  }

  public static final Execution DEFAULT =
      new Execution(
          new Consistency(Granularity.CELL, Edits.STOP, Edits.STOP, Targets.LIVE),
          new Dependencies(Reads.DERIVED, 1, List.of()),
          new Dynamics(Motion.STABLE, Repeat.ONCE, 20),
          new Planning(128, 1024, 256, 4096, 20));

  public Execution {
    Objects.requireNonNull(consistency);
    Objects.requireNonNull(dependencies);
    Objects.requireNonNull(dynamics);
    Objects.requireNonNull(planning);
  }
}

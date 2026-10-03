package io.github.billstark001.latticium.planning;

import io.github.billstark001.latticium.dsl.Compiler;
import io.github.billstark001.latticium.dsl.Model.ResourceId;
import java.util.List;

public record Profile(
    int schema,
    ResourceId id,
    List<ResourceId> uses,
    Activation activation,
    String scope,
    Select select,
    Target target,
    Policy policy) {
  public record Activation(String where, Mode mode, boolean fireIfInside) {
    public enum Mode {
      ENTER,
      WHILE
    }
  }

  public record Select(String where, Choose choose) {
    public enum Choose {
      NEAREST,
      FARTHEST,
      Y_ASC,
      Y_DESC,
      SCAN
    }
  }

  public sealed interface Target permits Items, Clear, Source {}

  public record Items(String expression, String states, List<ResourceId> preferred)
      implements Target {}

  public record Clear() implements Target {}

  public record Source(ResourceId id, String using, boolean includeAir) implements Target {}

  public record Policy(BreakMode breakMode, int maxActionsPerTick, int maxActionsPerActivation) {
    public enum BreakMode {
      DENY,
      SELECTED
    }

    public Policy {
      if (maxActionsPerTick <= 0 || maxActionsPerActivation <= 0)
        throw new IllegalArgumentException("Action budgets must be positive");
    }
  }

  public record Bound(
      Profile profile,
      Compiler.Bound activation,
      Compiler.Bound scope,
      Compiler.Bound select,
      Compiler.Bound items,
      Compiler.Bound states) {}
}

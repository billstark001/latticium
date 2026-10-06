package io.github.billstark001.latticium.planning;

import io.github.billstark001.latticium.dsl.Compiler;
import io.github.billstark001.latticium.dsl.FactDependencies;
import io.github.billstark001.latticium.dsl.Model.ResourceId;
import io.github.billstark001.latticium.dsl.Model.SetType;
import java.util.List;
import java.util.Objects;

/** Neutral schema-1 job configuration; expression strings are compiled by {@link ProfileReader}. */
public record Profile(
    int schema,
    ResourceId id,
    List<ResourceId> uses,
    Activation activation,
    String scope,
    Select select,
    Target target,
    Policy policy) {
  /** JSON profile schema understood by this offline core. */
  public static final int SCHEMA_VERSION = 1;

  public Profile {
    if (schema != SCHEMA_VERSION)
      throw new IllegalArgumentException("Unsupported profile schema: " + schema);
    Objects.requireNonNull(id, "id");
    uses = List.copyOf(uses);
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(select, "select");
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(policy, "policy");
  }

  /** Optional automatic trigger; a null activation on {@link Profile} means manual start. */
  public record Activation(String where, Mode mode, boolean fireIfInside) {
    public Activation {
      Objects.requireNonNull(where, "where");
      Objects.requireNonNull(mode, "mode");
    }

    public enum Mode {
      ENTER,
      WHILE
    }
  }

  /** Candidate predicate and scheduling preference within the finite scope. */
  public record Select(String where, Choose choose) {
    public Select {
      Objects.requireNonNull(where, "where");
      Objects.requireNonNull(choose, "choose");
    }

    public enum Choose {
      NEAREST,
      FARTHEST,
      Y_ASC,
      Y_DESC,
      SCAN
    }
  }

  public sealed interface Target permits Items, Clear, Source {}

  /** Item target; {@code states == null} accepts any state verified by the placement oracle. */
  public record Items(String expression, String states, List<ResourceId> preferred)
      implements Target {
    public Items {
      Objects.requireNonNull(expression, "expression");
      preferred = List.copyOf(preferred);
    }
  }

  public record Clear() implements Target {}

  /** External target source; {@code using == null} leaves item selection to the adapter. */
  public record Source(ResourceId id, String using, boolean includeAir) implements Target {
    public Source {
      Objects.requireNonNull(id, "id");
    }
  }

  /** Break permission, refresh mode, and positive action budgets. */
  public record Policy(
      BreakMode breakMode,
      int maxActionsPerTick,
      int maxActionsPerActivation,
      RefreshMode refreshMode) {
    public enum BreakMode {
      DENY,
      SELECTED
    }

    public enum RefreshMode {
      CONTINUOUS,
      MANUAL
    }

    public Policy(BreakMode breakMode, int maxActionsPerTick, int maxActionsPerActivation) {
      this(breakMode, maxActionsPerTick, maxActionsPerActivation, RefreshMode.CONTINUOUS);
    }

    public Policy {
      Objects.requireNonNull(breakMode, "breakMode");
      Objects.requireNonNull(refreshMode, "refreshMode");
      if (maxActionsPerTick <= 0 || maxActionsPerActivation <= 0)
        throw new IllegalArgumentException("Action budgets must be positive");
    }

    /** Returns the policy for the actions still available in this activation. */
    public Policy afterActions(int submittedActions) {
      if (submittedActions < 0 || submittedActions >= maxActionsPerActivation)
        throw new IllegalArgumentException("No remaining action budget");
      return new Policy(
          breakMode, maxActionsPerTick, maxActionsPerActivation - submittedActions, refreshMode);
    }
  }

  /** Compiled fields; absent activation and target-specific expressions remain null. */
  public record Bound(
      Profile profile,
      Compiler.Bound activation,
      Compiler.Bound scope,
      Compiler.Bound select,
      Compiler.Bound items,
      Compiler.Bound states) {
    /** Columns needed to enumerate candidates; the action state is captured separately. */
    public FactDependencies scanDependencies() {
      return scope.dependencies().union(select.dependencies());
    }

    /** Columns needed to recheck eligibility and choose a target/material for one candidate. */
    public FactDependencies planningDependencies() {
      var dependencies = scanDependencies().union(FactDependencies.of(FactDependencies.Fact.STATE));
      if (items != null) dependencies = dependencies.union(items.dependencies());
      if (states != null) dependencies = dependencies.union(states.dependencies());
      return dependencies;
    }

    /** Largest local window needed by any planning-time expression. */
    public int planningRadius() {
      return Math.max(
          Math.max(scope.radius(), select.radius()),
          Math.max(items == null ? 0 : items.radius(), states == null ? 0 : states.radius()));
    }

    public Bound {
      Objects.requireNonNull(profile, "profile");
      Objects.requireNonNull(scope, "scope");
      Objects.requireNonNull(select, "select");
      if (scope.type() != SetType.POS || select.type() != SetType.POS)
        throw new IllegalArgumentException("Scope and selection must be position sets");
      if ((profile.activation() == null) != (activation == null)
          || activation != null && activation.type() != SetType.POS)
        throw new IllegalArgumentException("Activation binding differs from profile");
      boolean hasItems =
          profile.target() instanceof Items
              || profile.target() instanceof Source source && source.using() != null;
      if (hasItems != (items != null) || items != null && items.type() != SetType.ITEM)
        throw new IllegalArgumentException("Item binding differs from profile");
      boolean hasStates = profile.target() instanceof Items target && target.states() != null;
      if (hasStates != (states != null) || states != null && states.type() != SetType.STATE)
        throw new IllegalArgumentException("State binding differs from profile");
    }
  }
}

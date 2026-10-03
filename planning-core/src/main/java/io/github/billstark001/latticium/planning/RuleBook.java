package io.github.billstark001.latticium.planning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.billstark001.latticium.dsl.Compiler;
import io.github.billstark001.latticium.dsl.Model.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Declarative, exact-state offline rules. Native placement behavior remains an oracle. */
public final class RuleBook {
  private static final int SCHEMA_VERSION = 1;

  /**
   * Exact transition; {@code when} and {@code requires} inspect the prior state, {@code verify} the
   * predicted result.
   */
  public record Rule(
      ResourceId id,
      BlockState before,
      BlockState after,
      Planner.Action action,
      Compiler.Bound when,
      Compiler.Bound requires,
      Compiler.Bound verify,
      Planner.Cost cost) {}

  private final List<Rule> rules;
  private final Map<BlockState, List<Rule>> byBefore;

  private RuleBook(List<Rule> rules) {
    this.rules = List.copyOf(rules);
    var grouped = new HashMap<BlockState, List<Rule>>();
    for (var rule : rules)
      grouped.computeIfAbsent(rule.before(), ignored -> new ArrayList<>()).add(rule);
    grouped.replaceAll((ignored, matching) -> List.copyOf(matching));
    byBefore = Map.copyOf(grouped);
  }

  public List<Rule> rules() {
    return rules;
  }

  /** Parses a rule bundle without exact-state registry validation. */
  public static RuleBook parse(String json, Compiler compiler) {
    return parse(json, compiler, null);
  }

  /** A registry validates exact before/after states against the chosen version. */
  public static RuleBook parse(String json, Compiler compiler, Registry registry) {
    boolean previousTargetAvailability = compiler.targetAvailable();
    try {
      var root = object(StrictJson.parse(json), "");
      keys(root, "", "schema", "rules");
      if (!root.path("schema").isIntegralNumber()
          || !root.path("schema").canConvertToInt()
          || root.path("schema").intValue() != SCHEMA_VERSION)
        throw new IllegalArgumentException("/schema: expected " + SCHEMA_VERSION);
      var array = root.get("rules");
      if (array == null || !array.isArray())
        throw new IllegalArgumentException("/rules: expected array");
      var rules = new ArrayList<Rule>();
      var ids = new HashSet<ResourceId>();
      int index = 0;
      compiler.targetAvailable(true);
      for (var raw : array) {
        String path = "/rules/" + index++;
        var o = object(raw, path);
        keys(o, path, "id", "before", "after", "action", "when", "requires", "verify", "cost");
        var id = resourceId(string(o, "id", path), path + "/id");
        if (!ids.add(id)) throw new IllegalArgumentException(path + ": duplicate rule ID");
        var before = state(o.get("before"), path + "/before");
        var after = state(o.get("after"), path + "/after");
        if (before.equals(after))
          throw new IllegalArgumentException(path + "/after: transition leaves state unchanged");
        if (registry != null) {
          if (!registry.states(before.block()).contains(before))
            throw new IllegalArgumentException(path + "/before: illegal state for registry");
          if (!registry.states(after.block()).contains(after))
            throw new IllegalArgumentException(path + "/after: illegal state for registry");
        }
        Planner.Action action;
        String actionName = string(o, "action", path);
        try {
          action = Planner.Action.valueOf(actionName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
          throw new IllegalArgumentException(path + "/action: invalid action");
        }
        Compiler.Bound when = guard(o, "when", compiler, path),
            requires = guard(o, "requires", compiler, path),
            verify = guard(o, "verify", compiler, path);
        var cost = o.has("cost") ? object(o.get("cost"), path + "/cost") : StrictJson.emptyObject();
        keys(cost, path + "/cost", "materials", "risk");
        int
            materials =
                positiveOrZero(
                    cost, "materials", path + "/cost", action == Planner.Action.PLACE ? 1 : 0),
            risk = positiveOrZero(cost, "risk", path + "/cost", 0);
        rules.add(
            new Rule(
                id,
                before,
                after,
                action,
                when,
                requires,
                verify,
                new Planner.Cost(1, materials, risk)));
      }
      return new RuleBook(rules);
    } catch (IOException ex) {
      throw new IllegalArgumentException("Invalid rule JSON: " + ex.getMessage(), ex);
    } finally {
      compiler.targetAvailable(previousTargetAvailability);
    }
  }

  /** Produces transitions only after all read-only guards and postconditions agree. */
  public Planner.Oracle oracle(Facts base) {
    return (pos, current, goal) -> {
      var proposals = new ArrayList<Planner.Proposal>();
      var matching = byBefore.getOrDefault(current, List.of());
      if (matching.isEmpty())
        return new Planner.Prediction.NoLegalPlacement("No matching declared transition");
      var before = overlay(base, pos, current, goal);
      boolean unknown = false;
      for (var rule : matching) {
        Truth when = rule.when() == null ? Truth.TRUE : rule.when().at(before, pos);
        if (when == Truth.FALSE) continue;
        Truth requires = rule.requires() == null ? Truth.TRUE : rule.requires().at(before, pos);
        Truth allowed = when.and(requires);
        if (allowed == Truth.UNKNOWN) {
          unknown = true;
          continue;
        }
        if (allowed == Truth.FALSE) continue;
        if (rule.verify() != null) {
          Truth verified = rule.verify().at(overlay(base, pos, rule.after(), goal), pos);
          if (verified == Truth.UNKNOWN) {
            unknown = true;
            continue;
          }
          if (verified != Truth.TRUE) continue;
        }
        proposals.add(
            new Planner.Proposal(
                rule.action(), rule.after(), Set.of(pos), rule.cost(), rule.id().toString()));
      }
      if (!proposals.isEmpty()) return new Planner.Prediction.Proposals(proposals);
      return unknown
          ? new Planner.Prediction.Unknown("Rule facts unavailable")
          : new Planner.Prediction.NoLegalPlacement("No matching declared transition");
    };
  }

  private static Facts overlay(Facts source, Position at, BlockState state, TargetCell target) {
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
        if (!p.equals(at)) {
          var neighbor = source.world(p);
          if (!faceNeighbor(at, p) || !speculative()) return neighbor;
          return neighbor.map(cell -> new WorldCell(cell.state(), cell.biome(), null, null, null));
        }
        var original = originalAt();
        if (!speculative()) return original;
        // Fluid, light and solidity can change with the block or its neighbors. A speculative
        // transition only establishes the block state; keep the independent biome fact.
        return Optional.of(
            new WorldCell(state, original.map(WorldCell::biome).orElse(null), null, null, null));
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

  private static boolean faceNeighbor(Position a, Position b) {
    if (!a.dimension().equals(b.dimension())) return false;
    long distance =
        Math.abs((long) a.x() - b.x())
            + Math.abs((long) a.y() - b.y())
            + Math.abs((long) a.z() - b.z());
    return distance == 1;
  }

  private static Compiler.Bound guard(ObjectNode n, String key, Compiler compiler, String path) {
    if (!n.has(key)) return null;
    try {
      return compiler.compile(string(n, key, path), SetType.POS);
    } catch (io.github.billstark001.latticium.dsl.Syntax.Failure ex) {
      throw new IllegalArgumentException(path + "/" + key + ": " + ex.getMessage());
    }
  }

  private static BlockState state(JsonNode n, String path) {
    var o = object(n, path);
    keys(o, path, "block", "properties");
    var id = resourceId(string(o, "block", path), path + "/block");
    var properties = new HashMap<String, String>();
    if (o.has("properties")) {
      var p = object(o.get("properties"), path + "/properties");
      p.properties()
          .forEach(
              e -> {
                if (!e.getValue().isTextual())
                  throw new IllegalArgumentException(
                      path + "/properties/" + e.getKey() + ": expected string");
                properties.put(e.getKey(), e.getValue().asText());
              });
    }
    return new BlockState(id, properties);
  }

  private static ObjectNode object(JsonNode n, String path) {
    if (!(n instanceof ObjectNode o))
      throw new IllegalArgumentException(path + ": expected object");
    return o;
  }

  private static String string(ObjectNode n, String key, String path) {
    var value = n.get(key);
    if (value == null || !value.isTextual())
      throw new IllegalArgumentException(path + "/" + key + ": expected string");
    return value.asText();
  }

  private static ResourceId resourceId(String raw, String path) {
    try {
      return ResourceId.parse(raw);
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException(path + ": " + ex.getMessage(), ex);
    }
  }

  private static int positiveOrZero(ObjectNode n, String key, String path, int fallback) {
    var value = n.get(key);
    if (value == null) return fallback;
    if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0)
      throw new IllegalArgumentException(path + "/" + key + ": expected nonnegative integer");
    return value.intValue();
  }

  private static void keys(ObjectNode n, String path, String... names) {
    var allowed = Set.of(names);
    n.fieldNames()
        .forEachRemaining(
            k -> {
              if (!allowed.contains(k))
                throw new IllegalArgumentException(path + "/" + k + ": unknown field");
            });
  }
}

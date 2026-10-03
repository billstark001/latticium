package io.github.billstark001.latticium.planning;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.billstark001.latticium.dsl.Compiler;
import io.github.billstark001.latticium.dsl.Model.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Declarative, exact-state offline rules. Native placement behavior remains an oracle. */
public final class RuleBook {
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

  private RuleBook(List<Rule> rules) {
    this.rules = List.copyOf(rules);
  }

  public List<Rule> rules() {
    return rules;
  }

  public static RuleBook parse(String json, Compiler compiler) {
    return parse(json, compiler, null);
  }

  /** A registry validates exact before/after states against the chosen version. */
  public static RuleBook parse(String json, Compiler compiler, Registry registry) {
    try {
      var mapper =
          JsonMapper.builder(
                  JsonFactory.builder()
                      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                      .build())
              .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
              .build();
      var root = object(mapper.readTree(json), "");
      keys(root, "", "schema", "rules");
      if (!root.path("schema").canConvertToInt() || root.path("schema").intValue() != 1)
        throw new IllegalArgumentException("/schema: expected 1");
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
        var id = ResourceId.parse(string(o, "id", path));
        if (!ids.add(id)) throw new IllegalArgumentException(path + ": duplicate rule ID");
        var before = state(o.get("before"), path + "/before");
        var after = state(o.get("after"), path + "/after");
        if (registry != null) {
          if (!registry.states(before.block()).contains(before))
            throw new IllegalArgumentException(path + "/before: illegal state for registry");
          if (!registry.states(after.block()).contains(after))
            throw new IllegalArgumentException(path + "/after: illegal state for registry");
        }
        Planner.Action action;
        try {
          action = Planner.Action.valueOf(string(o, "action", path).toUpperCase());
        } catch (IllegalArgumentException ex) {
          throw new IllegalArgumentException(path + "/action: invalid action");
        }
        Compiler.Bound when = guard(o, "when", compiler, path),
            requires = guard(o, "requires", compiler, path),
            verify = guard(o, "verify", compiler, path);
        var cost =
            o.has("cost") ? object(o.get("cost"), path + "/cost") : mapper.createObjectNode();
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
      throw new IllegalArgumentException("Invalid rule JSON", ex);
    }
  }

  /** Produces transitions only after all read-only guards and postconditions agree. */
  public Planner.Oracle oracle(Facts base) {
    return (pos, current, goal) -> {
      var proposals = new ArrayList<Planner.Proposal>();
      boolean unknown = false;
      for (var rule : rules) {
        if (!rule.before().equals(current)) continue;
        var before = overlay(base, pos, current, goal);
        Truth when = rule.when() == null ? Truth.TRUE : rule.when().at(before, pos);
        Truth requires = rule.requires() == null ? Truth.TRUE : rule.requires().at(before, pos);
        if (when == Truth.UNKNOWN || requires == Truth.UNKNOWN) {
          unknown = true;
          continue;
        }
        if (when != Truth.TRUE || requires != Truth.TRUE) continue;
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
      public java.util.Optional<WorldCell> world(Position p) {
        if (!p.equals(at)) return source.world(p);
        var original = source.world(p);
        return java.util.Optional.of(
            original
                .map(w -> new WorldCell(state, w.biome(), w.fluid(), w.light(), w.solid()))
                .orElse(new WorldCell(state, null, null, null, null)));
      }

      public TargetCell target(Position p) {
        return p.equals(at) ? target : source.target(p);
      }

      public java.util.Optional<Position> player() {
        return source.player();
      }

      public java.util.Optional<Set<ResourceId>> inventory() {
        return source.inventory();
      }

      public Truth selection(String name, Position p) {
        return source.selection(name, p);
      }
    };
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
    var id = ResourceId.parse(string(o, "block", path));
    var properties = new java.util.HashMap<String, String>();
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

  private static int positiveOrZero(ObjectNode n, String key, String path, int fallback) {
    var value = n.get(key);
    if (value == null) return fallback;
    if (!value.canConvertToInt() || value.intValue() < 0)
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

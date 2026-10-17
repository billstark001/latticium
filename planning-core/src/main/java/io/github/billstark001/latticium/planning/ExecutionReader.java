package io.github.billstark001.latticium.planning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.billstark001.latticium.dsl.FactDependencies.Offset;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;

/** Strict additive schema-1 execution options with diagnostics at the offending option. */
final class ExecutionReader {
  private ExecutionReader() {}

  static Execution read(JsonNode node) {
    if (node == null) return Execution.DEFAULT;
    var root = object(node, "/execution");
    keys(root, "/execution", "consistency", "dependencies", "dynamics", "planning");
    var defaults = Execution.DEFAULT;
    var c = child(root, "consistency");
    keys(c, "/execution/consistency", "granularity", "selections", "rules", "targets", "inventory");
    var consistency =
        new Execution.Consistency(
            choice(
                c, "granularity", defaults.consistency().granularity(), "/execution/consistency"),
            choice(c, "selections", defaults.consistency().selections(), "/execution/consistency"),
            choice(c, "rules", defaults.consistency().rules(), "/execution/consistency"),
            choice(c, "targets", defaults.consistency().targets(), "/execution/consistency"),
            choice(c, "inventory", defaults.consistency().inventory(), "/execution/consistency"));
    var d = child(root, "dependencies");
    keys(d, "/execution/dependencies", "reads", "radius", "extra_offsets");
    var offsets = new ArrayList<Offset>();
    if (d.has("extra_offsets")) {
      var values = d.get("extra_offsets");
      if (!values.isArray() || values.size() > 512)
        throw error("/execution/dependencies/extra_offsets", "Expected at most 512 offsets");
      for (int i = 0; i < values.size(); i++) {
        var v = values.get(i);
        var path = "/execution/dependencies/extra_offsets/" + i;
        if (!v.isArray() || v.size() != 3) throw error(path, "Expected [x,y,z]");
        int[] xyz = new int[3];
        for (int j = 0; j < 3; j++) xyz[j] = integer(v.get(j), path + "/" + j, -16, 16);
        offsets.add(new Offset(xyz[0], xyz[1], xyz[2]));
      }
    }
    var dependencies =
        new Execution.Dependencies(
            choice(d, "reads", defaults.dependencies().reads(), "/execution/dependencies"),
            number(d, "radius", 1, 0, 16, "/execution/dependencies"),
            offsets);
    var m = child(root, "dynamics");
    keys(m, "/execution/dynamics", "motion", "repeat", "settle_ticks");
    var dynamics =
        new Execution.Dynamics(
            choice(m, "motion", defaults.dynamics().motion(), "/execution/dynamics"),
            choice(m, "repeat", defaults.dynamics().repeat(), "/execution/dynamics"),
            number(m, "settle_ticks", 20, 1, 1200, "/execution/dynamics"));
    var p = child(root, "planning");
    keys(
        p,
        "/execution/planning",
        "initial_nodes",
        "max_nodes_per_tick",
        "max_retry_entries",
        "max_candidates",
        "retry_ticks");
    int initial = number(p, "initial_nodes", 128, 1, 65_536, "/execution/planning");
    int maximum = number(p, "max_nodes_per_tick", 1024, initial, 65_536, "/execution/planning");
    var planning =
        new Execution.Planning(
            initial,
            maximum,
            number(p, "max_retry_entries", 256, 1, 65_536, "/execution/planning"),
            number(p, "max_candidates", 4096, 1, 1_000_000, "/execution/planning"),
            number(p, "retry_ticks", 20, 1, 1200, "/execution/planning"));
    return new Execution(consistency, dependencies, dynamics, planning);
  }

  private static ObjectNode child(ObjectNode root, String name) {
    return root.has(name) ? object(root.get(name), "/execution/" + name) : StrictJson.emptyObject();
  }

  private static ObjectNode object(JsonNode node, String path) {
    if (!(node instanceof ObjectNode object)) throw error(path, "Expected object");
    return object;
  }

  private static void keys(ObjectNode node, String path, String... keys) {
    var allowed = Set.of(keys);
    node.fieldNames()
        .forEachRemaining(
            key -> {
              if (!allowed.contains(key)) throw error(path + "/" + key, "Unknown field");
            });
  }

  private static int number(
      ObjectNode node, String key, int fallback, int min, int max, String path) {
    if (node.has(key)) return integer(node.get(key), path + "/" + key, min, max);
    if (fallback < min || fallback > max)
      throw error(path + "/" + key, "Default is incompatible with the other limits");
    return fallback;
  }

  private static int integer(JsonNode node, String path, int min, int max) {
    if (!node.isIntegralNumber()
        || !node.canConvertToInt()
        || node.intValue() < min
        || node.intValue() > max) throw error(path, "Expected integer in " + min + ".." + max);
    return node.intValue();
  }

  private static <T extends Enum<T>> T choice(
      ObjectNode node, String key, T fallback, String path) {
    if (!node.has(key)) return fallback;
    var value = node.get(key);
    if (value.isTextual()) {
      for (var candidate : fallback.getDeclaringClass().getEnumConstants())
        if (candidate.name().toLowerCase(Locale.ROOT).equals(value.textValue())) return candidate;
    }
    throw error(path + "/" + key, "Invalid choice");
  }

  private static ProfileReader.Error error(String path, String message) {
    return new ProfileReader.Error(path, message);
  }
}

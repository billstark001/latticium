package io.github.billstark001.latticium.planning.offline;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.billstark001.latticium.dsl.Model.BlockState;
import io.github.billstark001.latticium.dsl.Model.Registry;
import io.github.billstark001.latticium.dsl.Model.ResourceId;
import io.github.billstark001.latticium.dsl.Model.SetType;
import io.github.billstark001.latticium.planning.StrictJson;
import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Read-only catalog baked from Mojang's data reports; never needs game classes. */
public final class ReportRegistry implements Registry {
  private static final int SCHEMA_VERSION = 1;

  private final String version;
  private final Map<SetType, Set<ResourceId>> universes = new EnumMap<>(SetType.class);
  private final Map<SetType, Map<ResourceId, Set<ResourceId>>> tags = new EnumMap<>(SetType.class);
  private final Map<ResourceId, Set<BlockState>> states = new HashMap<>();
  private final Map<ResourceId, Map<String, Set<String>>> properties = new HashMap<>();

  private ReportRegistry(JsonNode root) {
    object(root, "catalog");
    if (!root.path("schema").isIntegralNumber()
        || !root.path("schema").canConvertToInt()
        || root.path("schema").intValue() != SCHEMA_VERSION)
      throw new IllegalArgumentException("Unsupported catalog schema");
    version = text(root.get("version"), "version");
    if (version.isBlank()) throw new IllegalArgumentException("Empty catalog version");
    var universeData = object(root.get("universes"), "universes");
    var tagData = object(root.get("tags"), "tags");
    var blockData = object(root.get("blocks"), "blocks");
    for (var type :
        Map.of(
                SetType.BLOCK,
                "block",
                SetType.ITEM,
                "item",
                SetType.BIOME,
                "biome",
                SetType.FLUID,
                "fluid")
            .entrySet()) {
      var ids = resourceIds(universeData.get(type.getValue()), "universes/" + type.getValue());
      universes.put(type.getKey(), ids);
      var group = new HashMap<ResourceId, Set<ResourceId>>();
      object(tagData.get(type.getValue()), "tags/" + type.getValue())
          .properties()
          .forEach(
              entry -> {
                var members = resourceIds(entry.getValue(), "tag " + entry.getKey());
                if (!ids.containsAll(members))
                  throw new IllegalArgumentException(
                      "Tag references unknown ID: " + entry.getKey());
                var id = ResourceId.parse(entry.getKey());
                if (group.putIfAbsent(id, members) != null)
                  throw new IllegalArgumentException("Duplicate tag ID: " + id);
              });
      tags.put(type.getKey(), Map.copyOf(group));
    }
    universes.put(SetType.STATE, universes.get(SetType.BLOCK));
    tags.put(SetType.STATE, tags.get(SetType.BLOCK));
    blockData
        .properties()
        .forEach(
            entry -> {
              var block = ResourceId.parse(entry.getKey());
              var definition = entry.getValue();
              var schema = new HashMap<String, Set<String>>();
              object(definition.get("properties"), "block properties " + block)
                  .properties()
                  .forEach(
                      property -> {
                        schema.put(
                            property.getKey(),
                            strings(property.getValue(), "property " + property.getKey()));
                      });
              properties.put(block, Map.copyOf(schema));
              var legal = new HashSet<BlockState>();
              array(definition.get("states"), "states for " + block)
                  .forEach(
                      state -> {
                        var values = new HashMap<String, String>();
                        object(state, "state for " + block)
                            .properties()
                            .forEach(
                                property ->
                                    values.put(
                                        property.getKey(),
                                        text(property.getValue(), "state value")));
                        if (!values.keySet().equals(schema.keySet())
                            || values.entrySet().stream()
                                .anyMatch(e -> !schema.get(e.getKey()).contains(e.getValue())))
                          throw new IllegalArgumentException("Illegal state for " + block);
                        if (!legal.add(new BlockState(block, values)))
                          throw new IllegalArgumentException("Duplicate state for " + block);
                      });
              if (legal.isEmpty()) throw new IllegalArgumentException("No states for " + block);
              if (states.putIfAbsent(block, Set.copyOf(legal)) != null)
                throw new IllegalArgumentException("Duplicate block ID: " + block);
            });
    if (!states.keySet().equals(universes.get(SetType.BLOCK)))
      throw new IllegalArgumentException("Block catalog mismatch");
  }

  private static JsonNode object(JsonNode node, String name) {
    if (node == null || !node.isObject())
      throw new IllegalArgumentException("Expected object: " + name);
    return node;
  }

  private static JsonNode array(JsonNode node, String name) {
    if (node == null || !node.isArray())
      throw new IllegalArgumentException("Expected array: " + name);
    return node;
  }

  private static String text(JsonNode node, String name) {
    if (node == null || !node.isTextual())
      throw new IllegalArgumentException("Expected string: " + name);
    return node.asText();
  }

  private static Set<ResourceId> resourceIds(JsonNode node, String name) {
    var ids = new HashSet<ResourceId>();
    array(node, name)
        .forEach(
            value -> {
              if (!ids.add(ResourceId.parse(text(value, name))))
                throw new IllegalArgumentException("Duplicate ID in " + name);
            });
    return Set.copyOf(ids);
  }

  private static Set<String> strings(JsonNode node, String name) {
    var values = new HashSet<String>();
    array(node, name)
        .forEach(
            value -> {
              if (!values.add(text(value, name)))
                throw new IllegalArgumentException("Duplicate value in " + name);
            });
    return Set.copyOf(values);
  }

  /** Reads one strict catalog emitted by the offline baker. */
  public static ReportRegistry load(Path path) throws IOException {
    return new ReportRegistry(StrictJson.parse(path));
  }

  /** Minecraft version recorded in this catalog. */
  public String version() {
    return version;
  }

  /** Legal property values for a block, or an empty map when it is absent. */
  public Map<String, Set<String>> stateSchema(ResourceId block) {
    return properties.getOrDefault(block, Map.of());
  }

  private static void requireRegistryKind(SetType kind) {
    if (kind == null || kind == SetType.POS)
      throw new IllegalArgumentException("Position sets have no registry domain");
  }

  @Override
  public Resolution resolve(SetType kind, ResourceId id) {
    requireRegistryKind(kind);
    return universes.get(kind).contains(id) ? Resolution.FOUND : Resolution.MISSING;
  }

  @Override
  public Resolution resolveTag(SetType kind, ResourceId id) {
    requireRegistryKind(kind);
    return tags.get(kind).containsKey(id) ? Resolution.FOUND : Resolution.MISSING;
  }

  @Override
  public Set<ResourceId> tag(SetType kind, ResourceId id) {
    requireRegistryKind(kind);
    return tags.get(kind).getOrDefault(id, Set.of());
  }

  @Override
  public Set<ResourceId> universe(SetType kind) {
    requireRegistryKind(kind);
    return universes.get(kind);
  }

  @Override
  public Set<BlockState> states(ResourceId block) {
    return states.getOrDefault(block, Set.of());
  }
}

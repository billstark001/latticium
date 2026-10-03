package io.github.billstark001.latticium.planning.offline;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.billstark001.latticium.dsl.Model.BlockState;
import io.github.billstark001.latticium.dsl.Model.Registry;
import io.github.billstark001.latticium.dsl.Model.ResourceId;
import io.github.billstark001.latticium.dsl.Model.SetType;
import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Read-only catalog baked from Mojang's data reports; never needs game classes. */
public final class ReportRegistry implements Registry {
  private final String version;
  private final Map<SetType, Set<ResourceId>> universes = new EnumMap<>(SetType.class);
  private final Map<SetType, Map<ResourceId, Set<ResourceId>>> tags = new EnumMap<>(SetType.class);
  private final Map<ResourceId, Set<BlockState>> states = new HashMap<>();
  private final Map<ResourceId, Map<String, Set<String>>> properties = new HashMap<>();

  private ReportRegistry(JsonNode root) {
    object(root, "catalog");
    if (!root.path("schema").isIntegralNumber()
        || !root.path("schema").canConvertToInt()
        || root.path("schema").intValue() != 1)
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
      var ids = new HashSet<ResourceId>();
      var universe = array(universeData.get(type.getValue()), "universes/" + type.getValue());
      universe.forEach(value -> ids.add(ResourceId.parse(text(value, "universe ID"))));
      if (ids.size() != universe.size())
        throw new IllegalArgumentException("Duplicate universe ID: " + type.getValue());
      universes.put(type.getKey(), Set.copyOf(ids));
      var group = new HashMap<ResourceId, Set<ResourceId>>();
      object(tagData.get(type.getValue()), "tags/" + type.getValue())
          .properties()
          .forEach(
              entry -> {
                var members = new HashSet<ResourceId>();
                array(entry.getValue(), "tag " + entry.getKey())
                    .forEach(value -> members.add(ResourceId.parse(text(value, "tag member"))));
                if (!ids.containsAll(members))
                  throw new IllegalArgumentException(
                      "Tag references unknown ID: " + entry.getKey());
                group.put(ResourceId.parse(entry.getKey()), Set.copyOf(members));
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
                        var values = new HashSet<String>();
                        array(property.getValue(), "property " + property.getKey())
                            .forEach(value -> values.add(text(value, "property value")));
                        schema.put(property.getKey(), Set.copyOf(values));
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
                        legal.add(new BlockState(block, values));
                      });
              if (legal.isEmpty()) throw new IllegalArgumentException("No states for " + block);
              states.put(block, Set.copyOf(legal));
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

  public static ReportRegistry load(Path path) throws IOException {
    var mapper =
        JsonMapper.builder(
                JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    return new ReportRegistry(mapper.readTree(path.toFile()));
  }

  public String version() {
    return version;
  }

  public Map<String, Set<String>> stateSchema(ResourceId block) {
    return properties.getOrDefault(block, Map.of());
  }

  @Override
  public Resolution resolve(SetType kind, ResourceId id) {
    return universes.get(kind).contains(id) ? Resolution.FOUND : Resolution.MISSING;
  }

  @Override
  public Resolution resolveTag(SetType kind, ResourceId id) {
    return tags.get(kind).containsKey(id) ? Resolution.FOUND : Resolution.MISSING;
  }

  @Override
  public Set<ResourceId> tag(SetType kind, ResourceId id) {
    return tags.get(kind).getOrDefault(id, Set.of());
  }

  @Override
  public Set<ResourceId> universe(SetType kind) {
    return universes.get(kind);
  }

  @Override
  public Set<BlockState> states(ResourceId block) {
    return states.getOrDefault(block, Set.of());
  }
}

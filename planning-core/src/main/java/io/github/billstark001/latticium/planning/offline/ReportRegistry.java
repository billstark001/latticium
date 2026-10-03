package io.github.billstark001.latticium.planning.offline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    if (root.path("schema").asInt() != 1)
      throw new IllegalArgumentException("Unsupported catalog schema");
    version = root.path("version").asText();
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
      root.path("universes")
          .path(type.getValue())
          .forEach(value -> ids.add(ResourceId.parse(value.asText())));
      universes.put(type.getKey(), Set.copyOf(ids));
      var group = new HashMap<ResourceId, Set<ResourceId>>();
      root.path("tags")
          .path(type.getValue())
          .properties()
          .forEach(
              entry -> {
                var members = new HashSet<ResourceId>();
                entry.getValue().forEach(value -> members.add(ResourceId.parse(value.asText())));
                group.put(ResourceId.parse(entry.getKey()), Set.copyOf(members));
              });
      tags.put(type.getKey(), Map.copyOf(group));
    }
    universes.put(SetType.STATE, universes.get(SetType.BLOCK));
    tags.put(SetType.STATE, tags.get(SetType.BLOCK));
    root.path("blocks")
        .properties()
        .forEach(
            entry -> {
              var block = ResourceId.parse(entry.getKey());
              var definition = entry.getValue();
              var schema = new HashMap<String, Set<String>>();
              definition
                  .path("properties")
                  .properties()
                  .forEach(
                      property -> {
                        var values = new HashSet<String>();
                        property.getValue().forEach(value -> values.add(value.asText()));
                        schema.put(property.getKey(), Set.copyOf(values));
                      });
              properties.put(block, Map.copyOf(schema));
              var legal = new HashSet<BlockState>();
              definition
                  .path("states")
                  .forEach(
                      state -> {
                        var values = new HashMap<String, String>();
                        state
                            .properties()
                            .forEach(
                                property ->
                                    values.put(property.getKey(), property.getValue().asText()));
                        legal.add(new BlockState(block, values));
                      });
              states.put(block, Set.copyOf(legal));
            });
    if (!states.keySet().equals(universes.get(SetType.BLOCK)))
      throw new IllegalArgumentException("Block catalog mismatch");
  }

  public static ReportRegistry load(Path path) throws IOException {
    return new ReportRegistry(new ObjectMapper().readTree(path.toFile()));
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

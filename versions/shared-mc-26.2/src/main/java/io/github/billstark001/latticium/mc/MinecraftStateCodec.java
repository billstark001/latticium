package io.github.billstark001.latticium.mc;

import io.github.billstark001.latticium.dsl.Model;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.properties.Property;

/** Converts live registry values to immutable values understood by the pure core. */
public final class MinecraftStateCodec {
  private static final Map<net.minecraft.world.level.block.state.BlockState, Model.BlockState>
      STATE_CACHE = new ConcurrentHashMap<>();

  private MinecraftStateCodec() {}

  public static Model.ResourceId id(Identifier id) {
    return Model.ResourceId.parse(id.toString());
  }

  public static Identifier id(Model.ResourceId id) {
    return Identifier.fromNamespaceAndPath(id.namespace(), id.path());
  }

  public static Model.BlockState state(net.minecraft.world.level.block.state.BlockState source) {
    return STATE_CACHE.computeIfAbsent(source, MinecraftStateCodec::encodeState);
  }

  private static Model.BlockState encodeState(
      net.minecraft.world.level.block.state.BlockState source) {
    var properties = new HashMap<String, String>();
    for (Property<?> property : source.getProperties())
      properties.put(property.getName(), valueName(source, property));
    return new Model.BlockState(id(BuiltInRegistries.BLOCK.getKey(source.getBlock())), properties);
  }

  private static <T extends Comparable<T>> String valueName(
      net.minecraft.world.level.block.state.BlockState state, Property<T> property) {
    return property.getName(state.getValue(property));
  }

  /** Resolves an exact state in property-count time; missing or invalid properties are rejected. */
  public static Optional<net.minecraft.world.level.block.state.BlockState> state(
      Model.BlockState desired) {
    var block = BuiltInRegistries.BLOCK.getOptional(id(desired.block()));
    if (block.isEmpty()) return Optional.empty();
    var definition = block.get().getStateDefinition();
    // Exact states require every property, not a partial default-state match.
    if (definition.getProperties().size() != desired.properties().size()) return Optional.empty();
    var candidate = block.get().defaultBlockState();
    for (var entry : desired.properties().entrySet()) {
      var property = definition.getProperty(entry.getKey());
      if (property == null) return Optional.empty();
      var updated = applyProperty(candidate, property, entry.getValue());
      if (updated.isEmpty()) return Optional.empty();
      candidate = updated.get();
    }
    return Optional.of(candidate);
  }

  private static <T extends Comparable<T>>
      Optional<net.minecraft.world.level.block.state.BlockState> applyProperty(
          net.minecraft.world.level.block.state.BlockState state,
          Property<T> property,
          String value) {
    return property
        .getValue(value)
        .filter(parsed -> property.getName(parsed).equals(value))
        .map(parsed -> state.setValue(property, parsed));
  }

  public static Map<String, String> properties(
      net.minecraft.world.level.block.state.BlockState source) {
    return state(source).properties();
  }
}

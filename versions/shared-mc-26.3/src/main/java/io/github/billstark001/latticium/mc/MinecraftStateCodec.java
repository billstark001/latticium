package io.github.billstark001.latticium.mc;

import io.github.billstark001.latticium.dsl.Model;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.properties.Property;

/** Converts live registry values to immutable values understood by the pure core. */
public final class MinecraftStateCodec {
  private MinecraftStateCodec() {}

  public static Model.ResourceId id(Identifier id) {
    return Model.ResourceId.parse(id.toString());
  }

  public static Identifier id(Model.ResourceId id) {
    return Identifier.fromNamespaceAndPath(id.namespace(), id.path());
  }

  public static Model.BlockState state(net.minecraft.world.level.block.state.BlockState source) {
    var properties = new HashMap<String, String>();
    for (Property<?> property : source.getProperties())
      properties.put(property.getName(), valueName(source, property));
    return new Model.BlockState(id(BuiltInRegistries.BLOCK.getKey(source.getBlock())), properties);
  }

  private static <T extends Comparable<T>> String valueName(
      net.minecraft.world.level.block.state.BlockState state, Property<T> property) {
    return property.getName(state.getValue(property));
  }

  public static Optional<net.minecraft.world.level.block.state.BlockState> state(
      Model.BlockState desired) {
    var block = BuiltInRegistries.BLOCK.getOptional(id(desired.block()));
    if (block.isEmpty()) return Optional.empty();
    for (var candidate : block.get().getStateDefinition().getPossibleStates()) {
      if (state(candidate).equals(desired)) return Optional.of(candidate);
    }
    return Optional.empty();
  }

  public static Map<String, String> properties(
      net.minecraft.world.level.block.state.BlockState source) {
    return state(source).properties();
  }
}

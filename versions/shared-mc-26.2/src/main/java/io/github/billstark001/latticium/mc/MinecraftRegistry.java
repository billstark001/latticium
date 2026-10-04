package io.github.billstark001.latticium.mc;

import io.github.billstark001.latticium.dsl.Model;
import io.github.billstark001.latticium.dsl.Model.ResourceId;
import io.github.billstark001.latticium.dsl.Model.SetType;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;

/** Binds symbolic DSL IDs against the registries of the current client world. */
public final class MinecraftRegistry implements Model.Registry {
  private final ClientLevel level;

  public MinecraftRegistry(ClientLevel level) {
    this.level = level;
  }

  private Registry<?> registry(SetType kind) {
    return switch (kind) {
      case BLOCK, STATE -> BuiltInRegistries.BLOCK;
      case ITEM -> BuiltInRegistries.ITEM;
      case FLUID -> BuiltInRegistries.FLUID;
      case BIOME -> level.registryAccess().lookup(Registries.BIOME).orElse(null);
      case POS -> null;
    };
  }

  @Override
  public Resolution resolve(SetType kind, ResourceId id) {
    Registry<?> registry = registry(kind);
    if (registry == null) return Resolution.UNAVAILABLE;
    return registry.containsKey(MinecraftStateCodec.id(id)) ? Resolution.FOUND : Resolution.MISSING;
  }

  @Override
  public Resolution resolveTag(SetType kind, ResourceId id) {
    Registry<?> registry = registry(kind);
    if (registry == null) return Resolution.UNAVAILABLE;
    return registry
            .getTags()
            .anyMatch(tag -> tag.key().location().equals(MinecraftStateCodec.id(id)))
        ? Resolution.FOUND
        : Resolution.MISSING;
  }

  @Override
  public Set<ResourceId> tag(SetType kind, ResourceId id) {
    Registry<?> registry = registry(kind);
    if (registry == null) return Set.of();
    return tagMembers(registry, id);
  }

  private static <T> Set<ResourceId> tagMembers(Registry<T> registry, ResourceId id) {
    var key = TagKey.create(registry.key(), MinecraftStateCodec.id(id));
    return java.util.stream.StreamSupport.stream(registry.getTagOrEmpty(key).spliterator(), false)
        .map(holder -> MinecraftStateCodec.id(registry.getKey(holder.value())))
        .collect(Collectors.toUnmodifiableSet());
  }

  @Override
  public Set<ResourceId> universe(SetType kind) {
    Registry<?> registry = registry(kind);
    if (registry == null) return Set.of();
    return registry.keySet().stream()
        .map(MinecraftStateCodec::id)
        .collect(Collectors.toUnmodifiableSet());
  }

  @Override
  public Set<Model.BlockState> states(ResourceId block) {
    return BuiltInRegistries.BLOCK
        .getOptional(MinecraftStateCodec.id(block))
        .map(
            value ->
                value.getStateDefinition().getPossibleStates().stream()
                    .map(MinecraftStateCodec::state)
                    .collect(Collectors.toUnmodifiableSet()))
        .orElse(Set.of());
  }
}

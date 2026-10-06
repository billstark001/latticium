package io.github.billstark001.latticium.dsl;

import static io.github.billstark001.latticium.dsl.Model.*;

import java.util.Set;

/** Registry placeholders used for syntax and type checking before a world is available. */
final class SymbolicRegistry implements Registry {
  private static void requireRegistryKind(SetType kind) {
    if (kind == null || kind == SetType.POS)
      throw new IllegalArgumentException("Position sets have no registry domain");
  }

  public Resolution resolve(SetType kind, ResourceId id) {
    requireRegistryKind(kind);
    return Resolution.FOUND;
  }

  public Resolution resolveTag(SetType kind, ResourceId id) {
    requireRegistryKind(kind);
    return Resolution.FOUND;
  }

  public Set<ResourceId> tag(SetType kind, ResourceId id) {
    requireRegistryKind(kind);
    return Set.of();
  }

  public Set<ResourceId> universe(SetType kind) {
    requireRegistryKind(kind);
    return Set.of();
  }

  public Set<BlockState> states(ResourceId block) {
    return Set.of();
  }
}

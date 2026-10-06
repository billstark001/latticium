package io.github.billstark001.latticium.mc;

import io.github.billstark001.latticium.dsl.Model.ResourceId;
import io.github.billstark001.latticium.planning.Host;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Atomic provider registration and immutable capability views. */
final class BlueprintProviders {
  private final Map<ResourceId, Host.TargetSource> targets = new HashMap<>();
  private final Map<ResourceId, LatticiumClient.BlueprintProvider> blueprints = new HashMap<>();

  synchronized void registerTarget(ResourceId id, Host.TargetSource source) {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(source, "source");
    if (targets.putIfAbsent(id, source) != null)
      throw new IllegalArgumentException("Duplicate target provider: " + id);
  }

  synchronized void registerBlueprint(ResourceId id, LatticiumClient.BlueprintProvider provider) {
    registerTarget(id, provider);
    blueprints.put(id, provider);
  }

  synchronized Host.TargetSource target(ResourceId id) {
    return targets.get(id);
  }

  synchronized LatticiumClient.BlueprintProvider blueprint(ResourceId id) {
    return blueprints.get(id);
  }

  synchronized Set<ResourceId> blueprintIds() {
    return Set.copyOf(blueprints.keySet());
  }

  synchronized boolean hasBlueprint() {
    return !blueprints.isEmpty();
  }
}

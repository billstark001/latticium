package io.github.billstark001.latticium.mc;

import io.github.billstark001.latticium.dsl.Model.ResourceId;
import java.util.Map;
import java.util.Set;

/** A read-only client-thread snapshot shared by native screens and both loaders' HUDs. */
public record ClientUiState(
    boolean inWorld,
    ClientJobSnapshot job,
    int settlingJobs,
    boolean automaticSuspended,
    Set<String> enabledProfiles,
    Map<String, String> activationErrors,
    String storageError,
    Set<ResourceId> blueprintProviders) {
  public ClientUiState {
    enabledProfiles = Set.copyOf(enabledProfiles);
    activationErrors = Map.copyOf(activationErrors);
    blueprintProviders = Set.copyOf(blueprintProviders);
  }
}

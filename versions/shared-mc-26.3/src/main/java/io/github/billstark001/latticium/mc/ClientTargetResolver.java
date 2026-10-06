package io.github.billstark001.latticium.mc;

import io.github.billstark001.latticium.dsl.Model.*;
import io.github.billstark001.latticium.planning.Host;
import io.github.billstark001.latticium.planning.MaterialSelector;
import io.github.billstark001.latticium.planning.Profile;
import io.github.billstark001.latticium.planning.TargetSources;
import java.util.HashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;

/** Resolves a profile's clear, external, or inventory-backed target for one candidate. */
final class ClientTargetResolver {
  private static final TargetCell.Clear CLEAR = new TargetCell.Clear();
  private static final TargetCell.DontCare DONT_CARE = new TargetCell.DontCare();

  private final Minecraft minecraft;
  private final Host.SessionId session;
  private final Profile.Bound profile;
  private final Host.TargetSource targets;
  private final MinecraftPlacementOracle oracle;

  ClientTargetResolver(
      Minecraft minecraft,
      Host.SessionId session,
      Profile.Bound profile,
      Host.TargetSource targets,
      MinecraftPlacementOracle oracle) {
    if (profile.profile().target() instanceof Profile.Source && targets == null)
      throw new IllegalArgumentException("Target provider unavailable");
    this.minecraft = minecraft;
    this.session = session;
    this.profile = profile;
    this.targets = targets == null ? null : TargetSources.guarded(targets, session);
    this.oracle = oracle;
  }

  Host.TargetSource targetSource() {
    if (profile.profile().target() instanceof Profile.Clear)
      return (pos, requestedSession) -> CLEAR;
    if (profile.profile().target() instanceof Profile.Source)
      return TargetSources.map(targets, this::applyAirPolicy);
    return null;
  }

  TargetCell resolve(Position pos, BlockState current, Facts facts) {
    if (profile.profile().target() instanceof Profile.Clear) return CLEAR;
    if (profile.profile().target() instanceof Profile.Source) return sourceTarget(pos, session);
    var items = (Profile.Items) profile.profile().target();
    var amounts = new HashMap<ResourceId, Integer>();
    var inventory = minecraft.player.getInventory();
    for (int i = 0; i < inventory.getContainerSize(); i++) {
      var stack = inventory.getItem(i);
      if (!stack.isEmpty())
        amounts.merge(
            MinecraftStateCodec.id(BuiltInRegistries.ITEM.getKey(stack.getItem())),
            stack.getCount(),
            Integer::sum);
    }
    var choice =
        new MaterialSelector()
            .choose(
                pos,
                current,
                profile.items(),
                profile.states(),
                facts,
                amounts,
                items.preferred(),
                (item, at) -> {
                  var normal = oracle.statesFor(item, at);
                  return normal instanceof MaterialSelector.Outcome.Unsupported
                          && profile.profile().policy().breakMode()
                              == Profile.Policy.BreakMode.SELECTED
                      ? oracle.statesAfterBreak(item, at)
                      : normal;
                });
    return switch (choice) {
      case MaterialSelector.Choice.Frozen selected -> selected.target();
      case MaterialSelector.Choice.Deferred deferred -> new TargetCell.Unknown(deferred.reason());
      case MaterialSelector.Choice.Unsupported unsupported ->
          new TargetCell.Unknown(unsupported.reason());
      case MaterialSelector.Choice.NoTarget noTarget -> new TargetCell.Unknown(noTarget.reason());
    };
  }

  private TargetCell sourceTarget(Position pos, Host.SessionId requestedSession) {
    return applyAirPolicy(targets.target(pos, requestedSession));
  }

  private TargetCell applyAirPolicy(TargetCell target) {
    if (target instanceof TargetCell.Exact exact
        && io.github.billstark001.latticium.dsl.Model.isVanillaAir(exact.state()))
      return ((Profile.Source) profile.profile().target()).includeAir() ? CLEAR : DONT_CARE;
    return target;
  }

  static boolean hasReplacementBudget(TargetCell target, Profile.Policy remainingPolicy) {
    return target instanceof TargetCell.Clear || remainingPolicy.maxActionsPerActivation() >= 2;
  }
}

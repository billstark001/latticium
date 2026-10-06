package io.github.billstark001.latticium.mc;

import io.github.billstark001.latticium.dsl.Compiler;
import io.github.billstark001.latticium.dsl.Model.*;
import io.github.billstark001.latticium.planning.Planner;
import java.util.ArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;

/** Applies bound material eligibility to native placement proposals for one fact snapshot. */
final class ClientMaterialGate {
  private final Minecraft minecraft;
  private final MinecraftPlacementOracle oracle;
  private final Compiler.Bound allowedItems;

  ClientMaterialGate(
      Minecraft minecraft, MinecraftPlacementOracle oracle, Compiler.Bound allowedItems) {
    this.minecraft = minecraft;
    this.oracle = oracle;
    this.allowedItems = allowedItems;
  }

  Planner.Oracle forFacts(Facts facts) {
    return (pos, current, target) -> predict(pos, current, target, facts);
  }

  private Planner.Prediction predict(
      Position pos, BlockState current, TargetCell target, Facts facts) {
    var predicted = oracle.predict(pos, current, target);
    if (allowedItems == null || !(predicted instanceof Planner.Prediction.Proposals proposals))
      return predicted;
    var allowed = new ArrayList<Planner.Proposal>();
    boolean unknown = false;
    for (var proposal : proposals.steps()) {
      if (proposal.action() != Planner.Action.PLACE) {
        allowed.add(proposal);
        continue;
      }
      var interaction = proposal.interaction();
      if (interaction == null || interaction.inventorySlot() < 0) continue;
      var stack = minecraft.player.getInventory().getItem(interaction.inventorySlot());
      if (stack.isEmpty()) continue;
      var item = MinecraftStateCodec.id(BuiltInRegistries.ITEM.getKey(stack.getItem()));
      var membership = allowedItems.contains(facts, item);
      if (membership == Truth.TRUE) allowed.add(proposal);
      else if (membership == Truth.UNKNOWN) unknown = true;
    }
    if (!allowed.isEmpty()) return new Planner.Prediction.Proposals(allowed);
    return unknown
        ? new Planner.Prediction.Unknown("Allowed material facts unavailable")
        : new Planner.Prediction.Unsupported("No allowed item predicts the exact state");
  }
}

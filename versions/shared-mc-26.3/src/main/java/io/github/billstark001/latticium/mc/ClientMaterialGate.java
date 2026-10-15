package io.github.billstark001.latticium.mc;

import io.github.billstark001.latticium.dsl.Compiler;
import io.github.billstark001.latticium.dsl.Model.*;
import io.github.billstark001.latticium.planning.Planner;
import io.github.billstark001.latticium.planning.StateOverlay;
import java.util.ArrayList;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;

/** Applies bound material eligibility to native placement proposals for one fact snapshot. */
final class ClientMaterialGate {
  private static final BlockState AIR = new BlockState(ResourceId.parse("minecraft:air"), Map.of());
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
    return (pos, current, target) -> filter(oracle.predict(pos, current, target), facts);
  }

  boolean hasPlacementAfterBreak(Position pos, BlockState target, Facts facts) {
    var afterBreak = StateOverlay.at(facts, pos, AIR, new TargetCell.Exact(target));
    return filter(oracle.predictAfterBreak(pos, target), afterBreak)
            instanceof Planner.Prediction.Proposals proposals
        && !proposals.steps().isEmpty();
  }

  private Planner.Prediction filter(Planner.Prediction predicted, Facts facts) {
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

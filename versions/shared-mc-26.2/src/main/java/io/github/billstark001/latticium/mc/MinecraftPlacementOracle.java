package io.github.billstark001.latticium.mc;

import io.github.billstark001.latticium.dsl.Model.*;
import io.github.billstark001.latticium.planning.MaterialSelector;
import io.github.billstark001.latticium.planning.Planner;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Predicts ordinary hotbar block placement without changing the live world. */
public final class MinecraftPlacementOracle implements Planner.Oracle, MaterialSelector.Oracle {
  private final Minecraft minecraft;

  public MinecraftPlacementOracle(Minecraft minecraft) {
    this.minecraft = minecraft;
  }

  @Override
  public MaterialSelector.Outcome statesFor(ResourceId item, Position pos) {
    if (!minecraft.isSameThread())
      return new MaterialSelector.Outcome.Unknown("Not on client thread");
    if (minecraft.player == null || minecraft.level == null)
      return new MaterialSelector.Outcome.Unknown("No world or player");
    var states = new HashSet<BlockState>();
    for (var placement : placements(pos)) {
      if (placement.item().equals(item)) states.add(placement.state());
    }
    return states.isEmpty()
        ? new MaterialSelector.Outcome.Unsupported("No predictable placement for " + item)
        : new MaterialSelector.Outcome.States(states);
  }

  @Override
  public Planner.Prediction predict(Position pos, BlockState current, TargetCell goal) {
    if (!(goal instanceof TargetCell.Exact exact))
      return new Planner.Prediction.NoLegalPlacement("Placement needs an exact target");
    if (!minecraft.isSameThread()) return new Planner.Prediction.Unknown("Not on client thread");
    var proposals = new ArrayList<Planner.Proposal>();
    var repeater = repeaterClick(pos, current);
    if (repeater != null) proposals.add(repeater);
    var snow = snowLayerStep(pos, current);
    if (snow != null) proposals.add(snow);
    for (var placement : placements(pos)) {
      if (!placement.state().equals(exact.state())) continue;
      proposals.add(
          new Planner.Proposal(
              Planner.Action.PLACE,
              placement.state(),
              Set.of(pos),
              new Planner.Cost(1, 1, 0),
              "latticium:ordinary_block_placement",
              placement.interaction()));
    }
    return proposals.isEmpty()
        ? new Planner.Prediction.NoLegalPlacement("No reachable ordinary placement")
        : new Planner.Prediction.Proposals(proposals);
  }

  private record Placement(ResourceId item, BlockState state, Planner.Interaction interaction) {}

  private Planner.Proposal snowLayerStep(Position pos, BlockState current) {
    if (!current.block().equals(ResourceId.parse("minecraft:snow"))
        || minecraft.player == null
        || minecraft.level == null) return null;
    int layers;
    try {
      layers = Integer.parseInt(current.properties().get("layers"));
    } catch (NumberFormatException error) {
      return null;
    }
    if (layers < 1 || layers >= 8) return null;
    var target = new BlockPos(pos.x(), pos.y(), pos.z());
    var support = target.below();
    if (!minecraft.level.hasChunk(support.getX() >> 4, support.getZ() >> 4)
        || !minecraft.player.isWithinBlockInteractionRange(support, 0)) return null;
    var inventory = minecraft.player.getInventory();
    for (int slot = 0; slot < Math.min(9, inventory.getContainerSize()); slot++) {
      var stack = inventory.getItem(slot);
      if (!(stack.getItem() instanceof BlockItem item)
          || !(item.getBlock() instanceof SnowLayerBlock)
          || stack.isEmpty()) continue;
      var hit = new Vec3(pos.x() + 0.5, pos.y(), pos.z() + 0.5);
      var result = new BlockHitResult(hit, Direction.UP, support, false);
      var context =
          new BlockPlaceContext(minecraft.player, InteractionHand.MAIN_HAND, stack, result);
      if (!context.canPlace() || !context.getClickedPos().equals(target)) continue;
      var properties = new java.util.HashMap<>(current.properties());
      properties.put("layers", Integer.toString(layers + 1));
      return new Planner.Proposal(
          Planner.Action.PLACE,
          new BlockState(current.block(), Map.copyOf(properties)),
          Set.of(pos),
          new Planner.Cost(1, 1, 0),
          "latticium:vanilla_snow_layer_stack",
          new Planner.Interaction(
              slot,
              Planner.Interaction.Face.UP,
              hit.x,
              hit.y,
              hit.z,
              new Position(pos.dimension(), support.getX(), support.getY(), support.getZ())));
    }
    return null;
  }

  private Planner.Proposal repeaterClick(Position pos, BlockState current) {
    if (!current.block().equals(ResourceId.parse("minecraft:repeater"))
        || minecraft.player == null
        || minecraft.level == null
        || !minecraft.player.getAbilities().mayBuild) return null;
    int delay;
    try {
      delay = Integer.parseInt(current.properties().get("delay"));
    } catch (NumberFormatException error) {
      return null;
    }
    if (delay < 1 || delay > 4) return null;
    int emptySlot = -1;
    var inventory = minecraft.player.getInventory();
    for (int slot = 0; slot < Math.min(9, inventory.getContainerSize()); slot++)
      if (inventory.getItem(slot).isEmpty()) {
        emptySlot = slot;
        break;
      }
    if (emptySlot < 0) return null;
    var blockPos = new BlockPos(pos.x(), pos.y(), pos.z());
    if (!minecraft.player.isWithinBlockInteractionRange(blockPos, 0)) return null;
    var properties = new java.util.HashMap<>(current.properties());
    properties.put("delay", Integer.toString(delay == 4 ? 1 : delay + 1));
    return new Planner.Proposal(
        Planner.Action.INTERACT,
        new BlockState(current.block(), Map.copyOf(properties)),
        Set.of(pos),
        new Planner.Cost(1, 0, 0),
        "latticium:vanilla_repeater_cycle",
        new Planner.Interaction(
            emptySlot,
            Planner.Interaction.Face.UP,
            pos.x() + 0.5,
            pos.y() + 1.0,
            pos.z() + 0.5,
            pos));
  }

  private List<Placement> placements(Position pos) {
    var level = minecraft.level;
    var player = minecraft.player;
    if (level == null || player == null) return List.of();
    if (!MinecraftStateCodec.id(level.dimension().identifier()).equals(pos.dimension())
        || !level.hasChunk(pos.x() >> 4, pos.z() >> 4)) return List.of();
    var target = new BlockPos(pos.x(), pos.y(), pos.z());
    var found = new ArrayList<Placement>();
    var inventory = player.getInventory();
    for (int slot = 0; slot < Math.min(9, inventory.getContainerSize()); slot++) {
      var stack = inventory.getItem(slot);
      if (!(stack.getItem() instanceof BlockItem item) || stack.isEmpty()) continue;
      var itemId = MinecraftStateCodec.id(BuiltInRegistries.ITEM.getKey(item));
      for (var direction : Direction.values()) {
        var support = target.relative(direction);
        if (!level.hasChunk(support.getX() >> 4, support.getZ() >> 4)) continue;
        var face = direction.getOpposite();
        var hit =
            Vec3.atCenterOf(support)
                .add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        if (!player.isWithinBlockInteractionRange(support, 0)) continue;
        var result = new BlockHitResult(hit, face, support, false);
        var context = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack, result);
        if (!context.canPlace() || !context.getClickedPos().equals(target)) continue;
        var state = item.getBlock().getStateForPlacement(context);
        if (state == null || !state.canSurvive(level, target)) continue;
        found.add(
            new Placement(
                itemId,
                MinecraftStateCodec.state(state),
                new Planner.Interaction(
                    slot,
                    Planner.Interaction.Face.valueOf(face.name()),
                    hit.x,
                    hit.y,
                    hit.z,
                    new Position(
                        pos.dimension(), support.getX(), support.getY(), support.getZ()))));
      }
      var existing = level.getBlockState(target);
      if (existing.getBlock() instanceof SnowLayerBlock && item.getBlock() == existing.getBlock()) {
        int layers = existing.getValue(SnowLayerBlock.LAYERS);
        if (layers < 8) {
          var hit = new Vec3(pos.x() + 0.5, pos.y() + layers / 8.0, pos.z() + 0.5);
          var result = new BlockHitResult(hit, Direction.UP, target, false);
          var context = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack, result);
          if (context.canPlace() && context.getClickedPos().equals(target)) {
            var state = item.getBlock().getStateForPlacement(context);
            if (state != null && state.canSurvive(level, target))
              found.add(
                  new Placement(
                      itemId,
                      MinecraftStateCodec.state(state),
                      new Planner.Interaction(
                          slot, Planner.Interaction.Face.UP, hit.x, hit.y, hit.z, pos)));
          }
        }
      }
    }
    return found;
  }
}

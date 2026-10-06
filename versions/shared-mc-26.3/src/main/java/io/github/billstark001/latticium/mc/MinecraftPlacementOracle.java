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
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Predicts ordinary hotbar block placement without changing the live world. */
public final class MinecraftPlacementOracle implements Planner.Oracle, MaterialSelector.Oracle {
  private final Minecraft minecraft;
  private final ClientLevel world;

  public MinecraftPlacementOracle(Minecraft minecraft) {
    this.minecraft = minecraft;
    world = minecraft.level;
  }

  /** Null only when the current native block has a modelled, single-cell dry break to air. */
  public String breakDeferral(Position pos) {
    if (!minecraft.isSameThread()) return "Not on client thread";
    var level = minecraft.level;
    if (level == null || level != world || minecraft.player == null) return "Client world changed";
    if (!MinecraftStateCodec.id(level.dimension().identifier()).equals(pos.dimension()))
      return "Dimension changed";
    if (!level.getChunkSource().hasChunk(pos.x() >> 4, pos.z() >> 4)) return "Chunk unloaded";
    var at = new BlockPos(pos.x(), pos.y(), pos.z());
    if (!level.getFluidState(at).isEmpty())
      return "Breaking fluid-containing blocks is not modelled";
    var state = level.getBlockState(at);
    var block = state.getBlock();
    if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
        || block instanceof BedBlock
        || block instanceof PistonHeadBlock
        || block instanceof MovingPistonBlock
        || block instanceof PistonBaseBlock && state.getValue(PistonBaseBlock.EXTENDED)
        || block instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE)
      return "Breaking a multi-block structure is not modelled";
    return null;
  }

  /**
   * Predicts post-break material states only for plain property-free blocks on a plain full-block
   * support. Their placement state is independent of the old target. No live block is changed.
   */
  public MaterialSelector.Outcome statesAfterBreak(ResourceId item, Position pos) {
    var reason = breakDeferral(pos);
    if (reason != null) return new MaterialSelector.Outcome.Unknown(reason);
    var states = new HashSet<BlockState>();
    for (var placement : placements(pos, item, true)) states.add(placement.state());
    return states.isEmpty()
        ? new MaterialSelector.Outcome.Unsupported("No verifiable post-break placement for " + item)
        : new MaterialSelector.Outcome.States(states);
  }

  /** Verifies the exact fallback goal before authorizing a destructive first step. */
  public boolean hasPlacementAfterBreak(Position pos, BlockState target) {
    return breakDeferral(pos) == null
        && placements(pos, null, true).stream().anyMatch(p -> p.state().equals(target));
  }

  @Override
  public MaterialSelector.Outcome statesFor(ResourceId item, Position pos) {
    if (!minecraft.isSameThread())
      return new MaterialSelector.Outcome.Unknown("Not on client thread");
    if (minecraft.player == null || minecraft.level == null || minecraft.level != world)
      return new MaterialSelector.Outcome.Unknown("No world or player");
    var states = new HashSet<BlockState>();
    for (var placement : placements(pos, item)) {
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
    if (minecraft.level == null || minecraft.level != world)
      return new Planner.Prediction.Unknown("Client world changed");
    var proposals = new ArrayList<Planner.Proposal>();
    var repeater = repeaterClick(pos, current);
    if (repeater != null) proposals.add(repeater);
    var snow = snowLayerStep(pos, current);
    if (snow != null) proposals.add(snow);
    for (var placement : placements(pos, null)) {
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
    if (!minecraft.level.getChunkSource().hasChunk(support.getX() >> 4, support.getZ() >> 4)
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

  /** A material query only predicts matching hotbar stacks; planning checks all stacks. */
  private List<Placement> placements(Position pos, ResourceId requiredItem) {
    return placements(pos, requiredItem, false);
  }

  private List<Placement> placements(Position pos, ResourceId requiredItem, boolean afterBreak) {
    var level = minecraft.level;
    var player = minecraft.player;
    if (level == null || player == null) return List.of();
    if (!MinecraftStateCodec.id(level.dimension().identifier()).equals(pos.dimension())
        || !level.getChunkSource().hasChunk(pos.x() >> 4, pos.z() >> 4)) return List.of();
    var target = new BlockPos(pos.x(), pos.y(), pos.z());
    var found = new ArrayList<Placement>();
    var inventory = player.getInventory();
    for (int slot = 0; slot < Math.min(9, inventory.getContainerSize()); slot++) {
      var stack = inventory.getItem(slot);
      if (!(stack.getItem() instanceof BlockItem item) || stack.isEmpty()) continue;
      var itemId = MinecraftStateCodec.id(BuiltInRegistries.ITEM.getKey(item));
      if (requiredItem != null && !requiredItem.equals(itemId)) continue;
      if (afterBreak
          && (item.getBlock().getClass() != Block.class
              || !item.getBlock().getStateDefinition().getProperties().isEmpty())) continue;
      for (var direction : Direction.values()) {
        var support = target.relative(direction);
        if (!level.getChunkSource().hasChunk(support.getX() >> 4, support.getZ() >> 4)) continue;
        if (afterBreak) {
          var supportState = level.getBlockState(support);
          if (supportState.getBlock().getClass() != Block.class
              || !supportState.isCollisionShapeFullBlock(level, support)) continue;
        }
        var face = direction.getOpposite();
        var hit =
            Vec3.atCenterOf(support)
                .add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        if (!player.isWithinBlockInteractionRange(support, 0)) continue;
        var result = new BlockHitResult(hit, face, support, false);
        var context =
            afterBreak
                ? new PostBreakContext(player, stack, result, target)
                : new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack, result);
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
      if (afterBreak) continue;
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

  /** Ignores only the old target's replaceability; support and click geometry remain real. */
  private static final class PostBreakContext extends BlockPlaceContext {
    private final BlockPos target;

    PostBreakContext(
        net.minecraft.world.entity.player.Player player,
        net.minecraft.world.item.ItemStack stack,
        BlockHitResult hit,
        BlockPos target) {
      super(player, InteractionHand.MAIN_HAND, stack, hit);
      this.target = target;
    }

    @Override
    public boolean canPlace() {
      return getClickedPos().equals(target);
    }
  }
}

import io.github.billstark001.latticium.dsl.Model.*;
import io.github.billstark001.latticium.mc.*;
import io.github.billstark001.latticium.planning.*;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** Important unsupported replacements must preserve the source before any destructive fallback. */
final class ReplacementSafetyAudit {
  private final AuditProbe probe;
  private final Minecraft minecraft;
  private final Position position;
  private final BlockPos target;
  private ClientJob job;
  private int scenario;
  private int stage;
  private int ticks;
  private long next;
  private BlockState source;

  ReplacementSafetyAudit(
      AuditProbe probe, Minecraft minecraft, Position position, BlockPos target) {
    this.probe = probe;
    this.minecraft = minecraft;
    this.position = position;
    this.target = target;
  }

  boolean tick() throws Exception {
    if (stage == 0) {
      source =
          scenario == 0
              ? Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true)
              : scenario == 3
                  ? Blocks.OAK_DOOR.defaultBlockState()
                  : Blocks.STONE.defaultBlockState();
      var dimension = minecraft.level.dimension();
      var uuid = minecraft.player.getUUID();
      int fixture = scenario;
      BlockState fixtureState = source;
      minecraft
          .getSingleplayerServer()
          .execute(
              () -> {
                var level = minecraft.getSingleplayerServer().getLevel(dimension);
                for (var direction : Direction.values())
                  level.setBlock(target.relative(direction), Blocks.AIR.defaultBlockState(), 3);
                if (fixture != 1)
                  level.setBlock(target.below(), Blocks.STONE.defaultBlockState(), 3);
                level.setBlock(target, fixtureState, 3);
                if (fixture == 3)
                  level.setBlock(
                      target.above(),
                      fixtureState.setValue(
                          BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER),
                      3);
                var player = minecraft.getSingleplayerServer().getPlayerList().getPlayer(uuid);
                player
                    .getInventory()
                    .setItem(0, new ItemStack(fixture == 2 ? Items.OAK_STAIRS : Items.DIRT, 64));
                player.inventoryMenu.broadcastChanges();
              });
      stage = 1;
      next = System.currentTimeMillis() + 1000;
      return false;
    }
    if (stage == 1) {
      if (System.currentTimeMillis() < next
          || !minecraft.level.getBlockState(target).equals(source)) return false;
      String json =
          """
          {"schema":1,"id":"test:replacement_safety","scope":"box(%d,%d,%d,%d,%d,%d)",
           "select":{"where":"all()"},"target":{"source":"test:safety"},
           "policy":{"break":"selected","max_actions_per_activation":2}}
          """
              .formatted(
                  position.x(),
                  position.y(),
                  position.z(),
                  position.x(),
                  position.y(),
                  position.z());
      TargetCell goal =
          scenario == 3
              ? new TargetCell.Clear()
              : new TargetCell.Exact(
                  MinecraftStateCodec.state(
                      scenario == 2
                          ? Blocks.OAK_STAIRS.defaultBlockState()
                          : Blocks.DIRT.defaultBlockState()));
      job = new ClientJob(minecraft, new Host.SessionId(), json, (p, s) -> goal, Map.of());
      stage = 2;
      ticks = 0;
    }
    job.tick();
    if (++ticks < 25) return false;
    probe.check(
        job.snapshot().submittedActions() == 0,
        "Unsupported replacement submitted a destructive action: " + scenario);
    probe.check(
        minecraft.level.getBlockState(target).equals(source),
        "Unsupported replacement changed its source: " + scenario);
    if (scenario == 3)
      probe.check(
          minecraft.level.getBlockState(target.above()).is(Blocks.OAK_DOOR),
          "Single-cell clear destroyed the other door half");
    probe.pass(
        switch (scenario) {
          case 0 ->
              "fluid-containing replacement preserves its waterlogged source without breaking";
          case 1 -> "replacement without verifiable post-break support preserves its source";
          case 2 -> "property-sensitive exact replacement defers before destructive fallback";
          default -> "single-cell clear defers a door without destroying either half";
        });
    job.leaveWorld();
    job = null;
    stage = 0;
    return ++scenario == 4;
  }

  void close() {
    if (job != null) job.leaveWorld();
  }
}

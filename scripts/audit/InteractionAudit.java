import io.github.billstark001.latticium.dsl.Model.*;
import io.github.billstark001.latticium.mc.*;
import io.github.billstark001.latticium.planning.*;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.SnowLayerBlock;

/** Non-breaking property transitions and survival exhaustion/replenishment against real packets. */
final class InteractionAudit {
  private final AuditProbe probe;
  private final Minecraft minecraft;
  private final Position position;
  private final BlockPos target;
  private ClientJob job;
  private int scenario;
  private int stage;
  private long next;
  private net.minecraft.world.level.block.state.BlockState source;
  private net.minecraft.world.level.block.state.BlockState goal;
  private volatile boolean verified;
  private volatile Throwable failure;

  InteractionAudit(AuditProbe probe, Minecraft minecraft, Position position, BlockPos target) {
    this.probe = probe;
    this.minecraft = minecraft;
    this.position = position;
    this.target = target;
  }

  boolean tick() throws Exception {
    probe.trace("interaction=" + scenario + ", stage=" + stage, job, position);
    if (stage == 0) {
      source =
          scenario == 0 || scenario >= 3
              ? Blocks.SNOW.defaultBlockState()
              : scenario == 1
                  ? Blocks.REPEATER.defaultBlockState()
                  : Blocks.AIR.defaultBlockState();
      goal =
          scenario == 0 || scenario >= 3
              ? source.setValue(SnowLayerBlock.LAYERS, 4)
              : scenario == 1
                  ? source.setValue(RepeaterBlock.DELAY, 4)
                  : Blocks.DIRT.defaultBlockState();
      var dimension = minecraft.level.dimension();
      var uuid = minecraft.player.getUUID();
      minecraft
          .getSingleplayerServer()
          .execute(
              () -> {
                var level = minecraft.getSingleplayerServer().getLevel(dimension);
                for (int dz = -1; dz <= 2; dz++) {
                  var at = target.offset(0, 0, dz);
                  level.setBlock(at.above(), Blocks.AIR.defaultBlockState(), 3);
                  level.setBlock(at.below(), Blocks.STONE.defaultBlockState(), 3);
                  level.setBlock(at, Blocks.AIR.defaultBlockState(), 3);
                }
                level.setBlock(target, source, 3);
                var player = minecraft.getSingleplayerServer().getPlayerList().getPlayer(uuid);
                player.setGameMode(survival() ? GameType.SURVIVAL : GameType.CREATIVE);
                for (int slot = 0; slot < 9; slot++)
                  player.getInventory().setItem(slot, ItemStack.EMPTY);
                player
                    .getInventory()
                    .setItem(
                        0,
                        new ItemStack(
                            scenario == 0 || scenario >= 3 ? Items.SNOW : Items.DIRT,
                            survival() ? 2 : 64));
                player.inventoryMenu.broadcastChanges();
              });
      next = System.currentTimeMillis() + 1000;
      stage = 1;
      return false;
    }
    if (stage == 1) {
      if (System.currentTimeMillis() < next
          || !minecraft.level.getBlockState(target).equals(source)
          || minecraft.player.getInventory().getItem(0).getCount() != (survival() ? 2 : 64)
          || minecraft.player.getAbilities().instabuild == survival()) return false;
      String json =
          """
          {"schema":1,"id":"test:interaction","scope":"box(%d,%d,%d,%d,%d,%d)",
           "select":{"where":"all()"},"target":{"source":"test:interaction"},
           "policy":{"break":"selected","refresh":"manual","max_actions_per_activation":6}}
          """
              .formatted(
                  position.x(),
                  position.y(),
                  position.z(),
                  position.x(),
                  position.y(),
                  position.z() + (scenario == 2 ? 2 : 0));
      var frozen = new TargetCell.Exact(MinecraftStateCodec.state(goal));
      job = new ClientJob(minecraft, new Host.SessionId(), json, (p, s) -> frozen, Map.of());
      stage = 2;
    }
    if (stage == 4) {
      if (!verified) return false;
      if (failure != null)
        throw new AssertionError("Server interaction assertions failed", failure);
      probe.pass(
          switch (scenario) {
            case 0 -> "snow layers 1 to 4 use exactly three confirmed placements and never break";
            case 1 ->
                "repeater delay 1 to 4 uses exactly three confirmed empty-hand interactions and never breaks";
            case 2 ->
                "survival fill consumes two dirt items, waits without a phantom third placement, and completes after one-item replenishment";
            case 3 ->
                "survival snow stacking retains its confirmed three-layer state and frozen four-layer target through exhaustion and replenishment";
            default ->
                "paused snow stacking replans after its snow stack moves to a different hotbar slot";
          });
      close();
      stage = 0;
      return ++scenario == 5;
    }
    job.tick();
    if (scenario == 4) return relocatedSlot();
    if (scenario < 2) {
      probe.check(
          !minecraft.level.getBlockState(target).isAir(),
          "Non-breaking transition removed its source");
      if (!job.isFinished()) return false;
      probe.check(
          job.snapshot().submittedActions() == 3,
          "Wrong non-breaking action count: " + job.status());
      verifyServer();
      return false;
    }
    if (stage == 2
        && job.snapshot().submittedActions() == 2
        && !job.hasPendingAction()
        && minecraft.player.getInventory().getItem(0).isEmpty()) {
      probe.check(
          survivalProgressValid(),
          "Survival actions did not produce the expected intermediate state");
      next = minecraft.level.getGameTime() + 45;
      stage = 3;
    }
    if (stage == 3 && minecraft.level.getGameTime() >= next) {
      probe.check(
          job.snapshot().submittedActions() == 2 && survivalProgressValid(),
          "Survival wait changed: "
              + job.status()
              + "; cells="
              + completedCells()
              + "; targets="
              + java.util.stream.IntStream.range(0, 3)
                  .mapToObj(dz -> minecraft.level.getBlockState(target.offset(0, 0, dz)).toString())
                  .toList()
              + "; inventory="
              + java.util.stream.IntStream.range(
                      0, minecraft.player.getInventory().getContainerSize())
                  .filter(slot -> !minecraft.player.getInventory().getItem(slot).isEmpty())
                  .mapToObj(slot -> slot + "=" + minecraft.player.getInventory().getItem(slot))
                  .toList());
      var uuid = minecraft.player.getUUID();
      minecraft
          .getSingleplayerServer()
          .execute(
              () -> {
                var player = minecraft.getSingleplayerServer().getPlayerList().getPlayer(uuid);
                player
                    .getInventory()
                    .setItem(0, new ItemStack(scenario == 3 ? Items.SNOW : Items.DIRT, 1));
                player.inventoryMenu.broadcastChanges();
              });
      stage = 5;
    }
    if (stage == 5 && job.isFinished()) {
      probe.check(
          job.snapshot().submittedActions() == 3 && completedCells() == (scenario == 2 ? 3 : 1),
          "Replenishment did not finish exactly the third action: " + job.status());
      verifyServer();
    }
    return false;
  }

  private boolean survival() {
    return scenario == 2 || scenario == 3;
  }

  private boolean relocatedSlot() throws Exception {
    if (stage == 2 && job.snapshot().submittedActions() == 1 && job.hasPendingAction()) {
      job.pause();
      stage = 6;
    }
    if (stage == 6 && !job.hasPendingAction()) {
      probe.check(
          minecraft.level.getBlockState(target).equals(source.setValue(SnowLayerBlock.LAYERS, 2)),
          "Paused snow action did not confirm its first layer");
      var uuid = minecraft.player.getUUID();
      minecraft
          .getSingleplayerServer()
          .execute(
              () -> {
                var player = minecraft.getSingleplayerServer().getPlayerList().getPlayer(uuid);
                player.getInventory().setItem(0, new ItemStack(Items.DIRT, 64));
                player.getInventory().setItem(1, new ItemStack(Items.SNOW, 64));
                player.inventoryMenu.broadcastChanges();
              });
      stage = 7;
    }
    if (stage == 7
        && minecraft.player.getInventory().getItem(0).is(Items.DIRT)
        && minecraft.player.getInventory().getItem(1).is(Items.SNOW)) {
      job.resume();
      stage = 8;
    }
    if (stage == 8 && job.isFinished()) {
      probe.check(
          job.snapshot().submittedActions() == 3 && completedCells() == 1,
          "Hotbar relocation lost confirmed progress: " + job.status());
      verifyServer();
    }
    return false;
  }

  private int completedCells() {
    int count = 0;
    for (int dz = 0; dz < (scenario == 2 ? 3 : 1); dz++)
      if (minecraft.level.getBlockState(target.offset(0, 0, dz)).equals(goal)) count++;
    return count;
  }

  private boolean survivalProgressValid() {
    return scenario == 2
        ? completedCells() == 2
        : minecraft.level.getBlockState(target).equals(source.setValue(SnowLayerBlock.LAYERS, 3));
  }

  private void verifyServer() {
    var dimension = minecraft.level.dimension();
    var uuid = minecraft.player.getUUID();
    verified = false;
    failure = null;
    stage = 4;
    minecraft
        .getSingleplayerServer()
        .execute(
            () -> {
              try {
                var server = minecraft.getSingleplayerServer();
                var level = server.getLevel(dimension);
                for (int dz = 0; dz < (scenario == 2 ? 3 : 1); dz++)
                  if (!level.getBlockState(target.offset(0, 0, dz)).equals(goal))
                    throw new AssertionError("Server target state differs from the goal");
                var player = server.getPlayerList().getPlayer(uuid);
                if (survival() && !player.getInventory().getItem(0).isEmpty())
                  throw new AssertionError("Survival item consumption differs on the server");
                player.setGameMode(GameType.CREATIVE);
              } catch (Throwable error) {
                failure = error;
              } finally {
                verified = true;
              }
            });
  }

  void close() {
    if (job != null) job.leaveWorld();
    job = null;
  }
}

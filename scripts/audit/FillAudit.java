import io.github.billstark001.latticium.dsl.Model.*;
import io.github.billstark001.latticium.mc.*;
import io.github.billstark001.latticium.planning.*;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Native quick-fill commands against normal, cave and void air in an ephemeral world. */
final class FillAudit {
  private static final List<Block> AIR =
      List.of(Blocks.AIR, Blocks.CAVE_AIR, Blocks.VOID_AIR, Blocks.STONE);
  private final AuditProbe probe;
  private final Minecraft minecraft;
  private final Position position;
  private final BlockPos target;
  private ClientJob job;
  private int variant;
  private int stage;
  private long next;

  FillAudit(AuditProbe probe, Minecraft minecraft, Position position, BlockPos target) {
    this.probe = probe;
    this.minecraft = minecraft;
    this.position = position;
    this.target = target;
  }

  boolean tick() throws Exception {
    if (stage == 0) {
      var dimension = minecraft.level.dimension();
      var uuid = minecraft.player.getUUID();
      var air = AIR.get(variant);
      minecraft
          .getSingleplayerServer()
          .execute(
              () -> {
                var level = minecraft.getSingleplayerServer().getLevel(dimension);
                level.setBlock(target.below(), Blocks.STONE.defaultBlockState(), 3);
                level.setBlock(target, air.defaultBlockState(), 3);
                var player = minecraft.getSingleplayerServer().getPlayerList().getPlayer(uuid);
                player.getInventory().setItem(0, new ItemStack(Items.DIRT, 64));
                player.inventoryMenu.broadcastChanges();
              });
      stage = 1;
      next = System.currentTimeMillis() + 1000;
      return false;
    }
    if (stage == 1) {
      if (System.currentTimeMillis() < next
          || !minecraft.level.getBlockState(target).is(AIR.get(variant))
          || !minecraft.player.getInventory().getItem(0).is(Items.DIRT)) return false;
      var bounds =
          new SectionScanner.Bounds(
              position.dimension(),
              position.x(),
              position.y(),
              position.z(),
              position.x(),
              position.y(),
              position.z());
      job =
          new ClientJob(
              minecraft,
              new Host.SessionId(),
              variant == 3
                  ? CommandProfiles.replace(ResourceId.parse("stone"), ResourceId.parse("dirt"))
                  : CommandProfiles.fill(ResourceId.parse("dirt")),
              null,
              Map.of("build", List.of(bounds)));
      stage = 2;
    }
    job.tick();
    if (!minecraft.level.getBlockState(target).is(Blocks.DIRT) || job.hasPendingAction())
      return false;
    probe.check(
        job.snapshot().submittedActions() == (variant == 3 ? 2 : 1)
            && job.snapshot().satisfiedCandidates() == 1,
        "Quick fill failed or used extra actions for " + AIR.get(variant));
    probe.pass(
        variant == 3
            ? "quick replace selected a verified post-break dirt target and completed in two server-confirmed actions"
            : "quick fill placed dirt into "
                + AIR.get(variant)
                + " with one server-confirmed action");
    job.leaveWorld();
    job = null;
    stage = 0;
    return ++variant == AIR.size();
  }

  void close() {
    if (job != null) job.leaveWorld();
  }
}

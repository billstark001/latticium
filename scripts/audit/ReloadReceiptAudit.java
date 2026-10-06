import io.github.billstark001.latticium.dsl.Model.*;
import io.github.billstark001.latticium.mc.*;
import io.github.billstark001.latticium.planning.*;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.world.level.block.Blocks;

/** A tag reload stops follow-up work without discarding an already accepted server receipt. */
final class ReloadReceiptAudit {
  private final AuditProbe probe;
  private final Minecraft minecraft;
  private final Position position;
  private final BlockPos target;
  private ClientJob job;
  private int stage;
  private long next;

  ReloadReceiptAudit(AuditProbe probe, Minecraft minecraft, Position position, BlockPos target) {
    this.probe = probe;
    this.minecraft = minecraft;
    this.position = position;
    this.target = target;
  }

  boolean tick() throws Exception {
    if (stage == 0) {
      var dimension = minecraft.level.dimension();
      minecraft
          .getSingleplayerServer()
          .execute(
              () ->
                  minecraft
                      .getSingleplayerServer()
                      .getLevel(dimension)
                      .setBlock(target, Blocks.STONE.defaultBlockState(), 3));
      stage = 1;
      next = System.currentTimeMillis() + 1000;
      return false;
    }
    if (stage == 1) {
      if (System.currentTimeMillis() < next
          || !minecraft.level.getBlockState(target).is(Blocks.STONE)) return false;
      String json =
          """
          {"schema":1,"id":"test:reload_receipt","scope":"box(%d,%d,%d,%d,%d,%d)",
           "select":{"where":"current(b{stone})"},"target":{"clear":true},
           "policy":{"break":"selected","max_actions_per_activation":1}}
          """
              .formatted(
                  position.x(),
                  position.y(),
                  position.z(),
                  position.x(),
                  position.y(),
                  position.z());
      job = new ClientJob(minecraft, new Host.SessionId(), json, null, Map.of());
      stage = 2;
    }
    job.tick();
    if (stage == 2 && job.hasPendingAction()) {
      probe.check(
          job.snapshot().submittedActions() == 1, "Receipt fixture submitted unexpected actions");
      minecraft.player.connection.handleUpdateTags(new ClientboundUpdateTagsPacket(Map.of()));
      probe.check(job.hasPendingAction(), "Registry update discarded the accepted receipt");
      stage = 3;
      return false;
    }
    if (stage == 3 && job.isSettled()) {
      probe.check(
          job.status().contains("active=CANCELLED"),
          "Accepted receipt did not confirm after reload");
      probe.check(
          job.snapshot().submittedActions() == 1 && !job.hasPendingAction(),
          "Reload allowed follow-up or retained receipt");
      probe.check(
          minecraft.level.getBlockState(target).isAir(),
          "Accepted clear was not observed after reload");
      probe.pass(
          "tag reload preserves an accepted receipt through server confirmation and allows no further submissions");
      job.leaveWorld();
      job = null;
      return true;
    }
    return false;
  }

  void close() {
    if (job != null) job.leaveWorld();
  }
}

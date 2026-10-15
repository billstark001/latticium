import io.github.billstark001.latticium.dsl.Model.*;
import io.github.billstark001.latticium.mc.*;
import io.github.billstark001.latticium.planning.*;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Pending receipts and confirmed partial replacements must not authorize changed inputs. */
final class ReceiptInterruptionAudit {
  private static final BlockState AIR = MinecraftStateCodec.state(Blocks.AIR.defaultBlockState());
  private static final TargetCell DIRT =
      new TargetCell.Exact(MinecraftStateCodec.state(Blocks.DIRT.defaultBlockState()));
  private final AuditProbe probe;
  private final Minecraft minecraft;
  private final Position position;
  private final BlockPos target;
  private final TargetCell[] goal = {DIRT};
  private ClientJob job;
  private int scenario;
  private int stage;
  private int ticks;
  private long next;

  ReceiptInterruptionAudit(
      AuditProbe probe, Minecraft minecraft, Position position, BlockPos target) {
    this.probe = probe;
    this.minecraft = minecraft;
    this.position = position;
    this.target = target;
  }

  boolean tick() throws Exception {
    if (stage == 0) {
      var dimension = minecraft.level.dimension();
      var uuid = minecraft.player.getUUID();
      minecraft
          .getSingleplayerServer()
          .execute(
              () -> {
                var level = minecraft.getSingleplayerServer().getLevel(dimension);
                level.setBlock(target.above(), Blocks.AIR.defaultBlockState(), 3);
                level.setBlock(target.below(), Blocks.STONE.defaultBlockState(), 3);
                level.setBlock(target, Blocks.STONE.defaultBlockState(), 3);
                var player = minecraft.getSingleplayerServer().getPlayerList().getPlayer(uuid);
                player.getInventory().setItem(0, new ItemStack(Items.DIRT, 64));
                player.inventoryMenu.broadcastChanges();
              });
      goal[0] = DIRT;
      stage = 1;
      next = System.currentTimeMillis() + 1000;
      return false;
    }
    if (stage == 1) {
      if (System.currentTimeMillis() < next
          || !minecraft.level.getBlockState(target).is(Blocks.STONE)) return false;
      if (scenario < 3) {
        checkReceipt();
        return advance();
      }
      String json =
          """
          {"schema":1,"id":"test:partial_interrupt","scope":"box(%d,%d,%d,%d,%d,%d)",
           "select":{"where":"current(b{stone})"},"target":{"source":"test:interrupt"},
           "policy":{"break":"selected","max_actions_per_activation":2}}
          """
              .formatted(
                  position.x(),
                  position.y(),
                  position.z(),
                  position.x(),
                  position.y(),
                  position.z());
      job = new ClientJob(minecraft, new Host.SessionId(), json, (p, s) -> goal[0], Map.of());
      stage = 2;
    }
    if (stage == 2) {
      job.tick();
      if (!job.hasPendingAction()) return false;
      job.pause();
      stage = 3;
      return false;
    }
    if (stage == 3) {
      job.tick();
      if (job.hasPendingAction()) return false;
      probe.check(minecraft.level.getBlockState(target).isAir(), "First break did not confirm");
      probe.check(job.snapshot().submittedActions() == 1, "Pause allowed a follow-up placement");
      if (scenario == 3) {
        var dimension = minecraft.level.dimension();
        minecraft
            .getSingleplayerServer()
            .execute(
                () ->
                    minecraft
                        .getSingleplayerServer()
                        .getLevel(dimension)
                        .setBlock(target, Blocks.GOLD_BLOCK.defaultBlockState(), 3));
      } else goal[0] = new TargetCell.Unknown("Placement switched after confirmed break");
      stage = 4;
      return false;
    }
    if (stage == 4) {
      if (scenario == 3 && !minecraft.level.getBlockState(target).is(Blocks.GOLD_BLOCK))
        return false;
      job.resume();
      stage = 5;
      ticks = 0;
    }
    job.tick();
    if (++ticks < 10) return false;
    probe.check(job.snapshot().submittedActions() == 1, "Changed partial input allowed placement");
    probe.check(
        scenario == 3
            ? minecraft.level.getBlockState(target).is(Blocks.GOLD_BLOCK)
            : minecraft.level.getBlockState(target).isAir(),
        "Interrupted replacement changed the new state");
    probe.pass(
        scenario == 3
            ? "state changed after confirmed break blocks follow-up placement and preserves the new block"
            : "target unavailable after confirmed break prevents placement against the frozen old target");
    return advance();
  }

  private void checkReceipt() throws Exception {
    var session = new Host.SessionId();
    var gateway =
        new MinecraftActionGateway(
            minecraft, session, ClientRegistryState::epochs, (p, s) -> goal[0], null, Map.of());
    try {
      var step =
          new Planner.Proposal(
              Planner.Action.BREAK,
              AIR,
              Set.of(position),
              new Planner.Cost(1, 0, 1),
              "test:interrupted_break");
      var before =
          new Host.Preconditions(
              session,
              ClientRegistryState.epochs(),
              position,
              MinecraftStateCodec.state(minecraft.level.getBlockState(target)),
              DIRT);
      var result =
          gateway.submit(step, before, new Profile.Policy(Profile.Policy.BreakMode.SELECTED, 1, 2));
      probe.check(
          result instanceof Host.Submission.Accepted, "Receipt fixture did not submit: " + result);
      var receipt = ((Host.Submission.Accepted) result).receipt();
      if (scenario == 0) goal[0] = new TargetCell.Unknown("Placement removed while breaking");
      else
        minecraft.player.connection.handleBlockUpdate(
            new ClientboundBlockUpdatePacket(target, Blocks.DIRT.defaultBlockState()));
      var expected = scenario == 2 ? ((TargetCell.Exact) DIRT).state() : AIR;
      probe.check(
          gateway.observe(receipt, expected) == Host.Observation.CONTRADICTED,
          "Changed receipt context was still accepted: " + scenario);
      probe.check(
          gateway.observe(receipt, expected) == Host.Observation.CONTRADICTED,
          "Terminated receipt was retained");
      probe.pass(
          switch (scenario) {
            case 0 -> "pending break stops when its target provider becomes unavailable";
            case 1 ->
                "authoritative third-state packet contradicts an in-flight break before continuation";
            default ->
                "receipt cannot confirm a caller-supplied result different from the submitted proposal";
          });
    } finally {
      gateway.close();
    }
  }

  private boolean advance() {
    close();
    stage = 0;
    return ++scenario == 5;
  }

  void close() {
    if (job != null) job.leaveWorld();
    job = null;
  }
}

import io.github.billstark001.latticium.dsl.Model.*;
import io.github.billstark001.latticium.mc.*;
import io.github.billstark001.latticium.planning.*;
import io.github.billstark001.minedriver.api.*;
import java.time.Duration;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.*;

/**
 * Registered custom stateful BlockItem and throwing placement implementation, not synthetic IDs.
 */
public final class ModdedScenario implements Scenario {
  @Override
  public void run(ProbeContext context) {
    context.call("world.create", Map.of("flat", true, "seed", "20261016", "timeoutMillis", 150000));
    verifyWorld(context);
    context.call("world.disconnect");
  }

  static void verifyWorld(ProbeContext context) {
    NativeWorld.quiet(context);
    var at = context.onClient(() -> Minecraft.getInstance().player.blockPosition().offset(2, 0, 0));
    var id = Identifier.fromNamespaceAndPath("latticiumaudit", "stateful");
    var block =
        BuiltInRegistries.BLOCK
            .getOptional(id)
            .orElseThrow(() -> new AssertionError("Native modded fixture was not installed"));
    var item = BuiltInRegistries.ITEM.getOptional(id).orElseThrow();
    for (int variant = 0; variant < 4; variant++) {
      final int index = variant;
      var axis = Direction.Axis.values()[variant % 3];
      context.onServer(
          () -> {
            var server = Minecraft.getInstance().getSingleplayerServer();
            var world = server.overworld();
            var player = server.getPlayerList().getPlayers().getFirst();
            player.setGameMode(GameType.SURVIVAL);
            for (Direction face : Direction.values())
              world.setBlock(at.relative(face), Blocks.AIR.defaultBlockState(), 3);
            world.setBlock(
                at.below(),
                index == 3 ? Blocks.DIRT.defaultBlockState() : Blocks.STONE.defaultBlockState(),
                3);
            world.setBlock(at.west(), Blocks.STONE.defaultBlockState(), 3);
            world.setBlock(at.north(), Blocks.STONE.defaultBlockState(), 3);
            world.setBlock(
                at,
                index == 3
                    ? Blocks.SHORT_GRASS.defaultBlockState()
                    : Blocks.AIR.defaultBlockState(),
                3);
            for (int slot = 0; slot < 9; slot++)
              player.getInventory().setItem(slot, ItemStack.EMPTY);
            player
                .getInventory()
                .setItem(
                    0,
                    new ItemStack(
                        index == 3
                            ? BuiltInRegistries.ITEM
                                .getOptional(
                                    Identifier.fromNamespaceAndPath("latticiumaudit", "failing"))
                                .orElseThrow()
                            : item,
                        1));
            if (index == 3) player.getInventory().setItem(8, new ItemStack(Items.DIRT, 64));
            player.inventoryMenu.broadcastChanges();
            return null;
          });
      context.await(
          "registered modded fixture " + index,
          Duration.ofSeconds(10),
          () ->
              context.onClient(
                  () -> {
                    var mc = Minecraft.getInstance();
                    return mc.level
                            .getBlockState(at)
                            .is(index == 3 ? Blocks.SHORT_GRASS : Blocks.AIR)
                        && mc.player
                            .getInventory()
                            .getItem(0)
                            .is(
                                index == 3
                                    ? BuiltInRegistries.ITEM
                                        .getOptional(
                                            Identifier.fromNamespaceAndPath(
                                                "latticiumaudit", "failing"))
                                        .orElseThrow()
                                    : item);
                  }));
      var desired =
          index == 3
              ? Blocks.DIRT.defaultBlockState()
              : block.defaultBlockState().setValue(RotatedPillarBlock.AXIS, axis);
      String json =
          """
          {"schema":1,"id":"test:modded","scope":"box(%d,%d,%d,%d,%d,%d)",
           "select":{"where":"all()"},"target":{"source":"test:registered"},
           "policy":{"break":"selected","refresh":"manual","max_actions_per_activation":4},
           "execution":{"planning":{"retry_ticks":1}}}
          """
              .formatted(at.getX(), at.getY(), at.getZ(), at.getX(), at.getY(), at.getZ());
      var job =
          context.onClient(
              () ->
                  new ClientJob(
                      Minecraft.getInstance(),
                      new Host.SessionId(),
                      json,
                      (p, s) -> new TargetCell.Exact(MinecraftStateCodec.state(desired)),
                      Map.of()));
      try {
        int[] ticks = {0};
        context.await(
            "modded native action " + index,
            Duration.ofSeconds(20),
            () ->
                context.onClient(
                    () -> {
                      job.tick();
                      ticks[0]++;
                      return index == 3 ? ticks[0] >= 10 : job.isFinished();
                    }));
        if (index == 3) {
          context.require(
              context.onClient(
                  () ->
                      job.snapshot().submittedActions() == 0
                          && job.status().contains("Placement oracle unavailable")),
              "Modded exception escaped/deferred incorrectly: " + context.onClient(job::status));
          context.require(
              context.onServer(
                  () ->
                      Minecraft.getInstance()
                          .getSingleplayerServer()
                          .overworld()
                          .getBlockState(at)
                          .is(Blocks.SHORT_GRASS)),
              "Throwing modded predictor authorized destruction");
        } else {
          context.require(
              context.onClient(
                  () ->
                      job.snapshot().submittedActions() == 1
                          && job.snapshot().satisfiedCandidates() == 1),
              "Stateful modded placement was not confirmed");
          context.require(
              context.onServer(
                  () ->
                      Minecraft.getInstance()
                          .getSingleplayerServer()
                          .overworld()
                          .getBlockState(at)
                          .equals(desired)),
              "Wrong authoritative modded axis");
        }
      } finally {
        context.onClient(
            () -> {
              job.leaveWorld();
              return null;
            });
      }
    }
    context.onServer(
        () -> {
          Minecraft.getInstance()
              .getSingleplayerServer()
              .getPlayerList()
              .getPlayers()
              .getFirst()
              .setGameMode(GameType.CREATIVE);
          return null;
        });
    System.out.println(
        "LATTICIUM_AUDIT registered stateful BlockItem axis=x/y/z and throwing modded placement predictor passed");
  }
}

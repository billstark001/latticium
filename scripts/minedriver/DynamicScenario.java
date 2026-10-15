import io.github.billstark001.latticium.mc.*;
import io.github.billstark001.latticium.planning.Host;
import io.github.billstark001.minedriver.api.ProbeContext;
import io.github.billstark001.minedriver.api.Scenario;
import java.time.Duration;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/** Opt-in fixed-position sand feed/mining with real gravity, inventory and server packets. */
public final class DynamicScenario implements Scenario {
  @Override
  public void run(ProbeContext context) {
    context.call("world.create", Map.of("flat", true, "seed", "20261016", "timeoutMillis", 150000));
    verifyWorld(context);
    context.call("world.disconnect");
  }

  static void verifyWorld(ProbeContext context) {
    NativeWorld.quiet(context);
    var feet = context.onClient(() -> Minecraft.getInstance().player.blockPosition());
    for (boolean mining : new boolean[] {false, true}) {
      // Leave an eight-block shaft inside the flat world's build height.
      var at = feet.offset(2, mining ? 0 : 4, 0);
      context.onServer(
          () -> {
            var minecraft = Minecraft.getInstance();
            var server = minecraft.getSingleplayerServer();
            var world = server.overworld();
            var player = server.getPlayerList().getPlayers().getFirst();
            player.setGameMode(GameType.SURVIVAL);
            for (int slot = 0; slot < 9; slot++)
              player.getInventory().setItem(slot, ItemStack.EMPTY);
            if (mining) {
              for (int dy = -8; dy <= 6; dy++)
                world.setBlock(at.above(dy), Blocks.AIR.defaultBlockState(), 3);
              world.setBlock(at.below(), Blocks.STONE.defaultBlockState(), 3);
              for (int dy = 0; dy < 4; dy++)
                world.setBlock(at.above(dy), Blocks.SAND.defaultBlockState(), 3);
            } else {
              for (int dy = -8; dy <= 5; dy++)
                world.setBlock(at.above(dy), Blocks.AIR.defaultBlockState(), 3);
              world.setBlock(at.below(8), Blocks.STONE.defaultBlockState(), 3);
              world.setBlock(at.west(), Blocks.STONE.defaultBlockState(), 3);
              player.getInventory().setItem(0, new ItemStack(Items.SAND, 4));
            }
            player.inventoryMenu.broadcastChanges();
            return null;
          });
      context.await(
          "sand fixture " + mining,
          Duration.ofSeconds(10),
          () ->
              context.onClient(
                  () -> {
                    var minecraft = Minecraft.getInstance();
                    return minecraft.level.getBlockState(at).is(mining ? Blocks.SAND : Blocks.AIR)
                        && minecraft.player.getAbilities().instabuild == false
                        && (mining || minecraft.player.getInventory().getItem(0).getCount() == 4);
                  }));
      String json = profile(at, mining);
      var job =
          context.onClient(
              () ->
                  new ClientJob(
                      Minecraft.getInstance(), new Host.SessionId(), json, null, Map.of()));
      try {
        context.await(
            "four confirmed fixed-position sand operations " + mining,
            Duration.ofSeconds(45),
            () ->
                context.onClient(
                    () -> {
                      job.tick();
                      return job.snapshot().submittedActions() == 4 && !job.hasPendingAction();
                    }));
        context.require(
            context.onClient(
                () ->
                    job.snapshot().submittedActions() == 4
                        && job.snapshot().satisfiedCandidates() == 4),
            "Each sand operation must have a confirmed receipt: " + context.onClient(job::status));
        try {
          context.await(
              "authoritative sand settlement " + mining,
              Duration.ofSeconds(10),
              () ->
                  context.onServer(
                      () -> {
                        var minecraft = Minecraft.getInstance();
                        var world = minecraft.getSingleplayerServer().overworld();
                        int count = 0;
                        for (int dy = -7; dy <= 5; dy++)
                          if (world.getBlockState(at.above(dy)).is(Blocks.SAND)) count++;
                        if (mining)
                          return count == 0 && world.getBlockState(at.below()).is(Blocks.STONE);
                        var player =
                            minecraft
                                .getSingleplayerServer()
                                .getPlayerList()
                                .getPlayers()
                                .getFirst();
                        return count == 4
                            && world.getBlockState(at).isAir()
                            && player.getInventory().getItem(0).isEmpty();
                      }));
        } catch (RuntimeException error) {
          System.out.println(
              "LATTICIUM_AUDIT sand failure "
                  + context.onServer(
                      () -> {
                        var world = Minecraft.getInstance().getSingleplayerServer().overworld();
                        var states = new java.util.ArrayList<String>();
                        for (int dy = -9; dy <= 6; dy++)
                          states.add(dy + "=" + world.getBlockState(at.above(dy)));
                        var player =
                            Minecraft.getInstance()
                                .getSingleplayerServer()
                                .getPlayerList()
                                .getPlayers()
                                .getFirst();
                        return states + " inventory=" + player.getInventory().getItem(0);
                      }));
          throw error;
        }
        context.onClient(
            () -> {
              for (int i = 0; i < 10; i++) job.tick();
              return null;
            });
        context.require(
            context.onClient(() -> job.snapshot().submittedActions() == 4),
            "Repeated sand work exceeded its activation budget");
        System.out.println(
            "LATTICIUM_AUDIT "
                + (mining ? "sand mining" : "sand feed")
                + " completed four server-confirmed transitions at one position with a finite action budget");
      } finally {
        context.onClient(
            () -> {
              job.leaveWorld();
              return null;
            });
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
      }
    }
  }

  static String profile(BlockPos at, boolean mining) {
    return """
        {"schema":1,"id":"test:dynamic_sand","scope":"box(%d,%d,%d,%d,%d,%d)",
         "select":{"where":"%s"},"target":%s,
         "policy":{"break":"%s","refresh":"manual","max_actions_per_activation":4},
         "execution":{"dynamics":{"motion":"settling","repeat":"while_selected","settle_ticks":10},
         "planning":{"retry_ticks":1}}}
        """
        .formatted(
            at.getX(),
            at.getY(),
            at.getZ(),
            at.getX(),
            at.getY(),
            at.getZ(),
            mining
                ? "current(b{minecraft:sand})"
                : "current(b{minecraft:air,minecraft:cave_air,minecraft:void_air})",
            mining ? "{\"clear\":true}" : "{\"items\":\"i{minecraft:sand}\"}",
            mining ? "selected" : "deny");
  }
}

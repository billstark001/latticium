import io.github.billstark001.latticium.mc.*;
import io.github.billstark001.minedriver.api.ProbeContext;
import io.github.billstark001.minedriver.api.Scenario;
import java.time.Duration;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RepeaterBlock;

/** Native adaptive search, cap exhaustion and exception acceptance; no frontier survives a tick. */
public final class PlanningScenario implements Scenario {
  @Override
  public void run(ProbeContext context) {
    context.call("world.create", Map.of("flat", true, "seed", "20261016", "timeoutMillis", 150000));
    NativeWorld.quiet(context);
    verifyWorld(context);
    PerformanceScenario.verifyWorld(context);
    context.call("world.disconnect");
  }

  static void verifyWorld(ProbeContext context) {
    var at = context.onClient(() -> Minecraft.getInstance().player.blockPosition().offset(2, 0, 0));
    for (int variant = 0; variant < 5; variant++) verify(context, at, variant);
  }

  private static void verify(ProbeContext context, BlockPos at, int variant) {
    boolean success = variant == 0 || variant == 4;
    context.onServer(
        () -> {
          var minecraft = Minecraft.getInstance();
          var server = minecraft.getSingleplayerServer();
          var player = server.getPlayerList().getPlayers().getFirst();
          var world = server.overworld();
          world.setBlock(at.below(), Blocks.STONE.defaultBlockState(), 3);
          world.setBlock(
              at,
              success ? Blocks.REPEATER.defaultBlockState() : Blocks.STONE.defaultBlockState(),
              3);
          if (variant == 4) {
            world.setBlock(at.south().below(), Blocks.STONE.defaultBlockState(), 3);
            world.setBlock(at.south(), Blocks.REPEATER.defaultBlockState(), 3);
          }
          for (int slot = 0; slot < 9; slot++) player.getInventory().setItem(slot, ItemStack.EMPTY);
          if (!success)
            player
                .getInventory()
                .setItem(8, new ItemStack(net.minecraft.world.item.Items.DIRT, 64));
          player.inventoryMenu.broadcastChanges();
          return null;
        });
    context.await(
        "native predictor fixture",
        Duration.ofSeconds(10),
        () ->
            context.onClient(
                () -> {
                  var minecraft = Minecraft.getInstance();
                  return minecraft
                          .level
                          .getBlockState(at)
                          .is(success ? Blocks.REPEATER : Blocks.STONE)
                      && (variant != 4
                          || minecraft.level.getBlockState(at.south()).is(Blocks.REPEATER))
                      && minecraft.player.getInventory().getItem(0).isEmpty()
                      && (success
                          || minecraft
                              .player
                              .getInventory()
                              .getItem(8)
                              .is(net.minecraft.world.item.Items.DIRT));
                }));
    var probe =
        context.onClient(
            () ->
                new NativePlanningProbe(
                    at,
                    variant == 1 ? 1500 : 300,
                    variant == 4 ? 0 : variant,
                    MinecraftStateCodec.state(
                        success
                            ? Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.DELAY, 2)
                            : Blocks.DIRT.defaultBlockState()),
                    variant == 4));
    try {
      context.await(
          "native adaptive predictor variant " + variant,
          Duration.ofSeconds(30),
          () ->
              context.onClient(
                  () -> {
                    boolean complete = probe.tick();
                    return success ? complete : probe.attempts() >= (variant == 1 ? 5 : 3);
                  }));
      if (success) {
        context.require(
            context.onClient(
                () ->
                    probe.submitted() == (variant == 4 ? 2 : 1)
                        && probe.attempts() >= 3
                        && probe.peakWork() > 256
                        && (variant != 4 || probe.peakRetries() == 1)),
            "Adaptive native search did not advance beyond its first two caps");
        context.require(
            context.onServer(
                () ->
                    Minecraft.getInstance()
                                .getSingleplayerServer()
                                .overworld()
                                .getBlockState(at)
                                .getValue(RepeaterBlock.DELAY)
                            == 2
                        && (variant != 4
                            || Minecraft.getInstance()
                                    .getSingleplayerServer()
                                    .overworld()
                                    .getBlockState(at.south())
                                    .getValue(RepeaterBlock.DELAY)
                                == 2)),
            "Native non-breaking action did not reach the authoritative repeater target");
      } else {
        context.require(
            context.onClient(() -> probe.submitted() == 0),
            "Exhausted/failed predictor submitted an action");
        context.require(
            context.onServer(
                () ->
                    Minecraft.getInstance()
                        .getSingleplayerServer()
                        .overworld()
                        .getBlockState(at)
                        .is(Blocks.STONE)),
            "Exhausted/failed predictor destroyed its source");
        context.require(
            context.onClient(
                () ->
                    probe
                        .status()
                        .contains(
                            variant == 1
                                ? "Search budget exhausted"
                                : variant == 3
                                    ? "Post-break prediction unavailable"
                                    : "Placement oracle unavailable")),
            "Native deferral reason missing: " + context.onClient(probe::status));
      }
      System.out.println(
          "LATTICIUM_AUDIT predictor variant="
              + variant
              + " attempts="
              + context.onClient(probe::attempts)
              + " peakNodes="
              + context.onClient(probe::peakWork)
              + " maxJobNanos="
              + context.onClient(probe::maxNanos)
              + " retryPeak="
              + context.onClient(probe::peakRetries));
    } finally {
      context.onClient(
          () -> {
            probe.close();
            return null;
          });
    }
  }
}

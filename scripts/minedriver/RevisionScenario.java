import io.github.billstark001.latticium.mc.NativeRevisionProbe;
import io.github.billstark001.minedriver.api.ProbeContext;
import io.github.billstark001.minedriver.api.Scenario;
import java.time.Duration;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.Blocks;

/** Real client revision scopes, local prediction invalidation and configurable frozen intent. */
public final class RevisionScenario implements Scenario {
  @Override
  public void run(ProbeContext context) {
    context.call("world.create", Map.of("flat", true, "seed", "20261016", "timeoutMillis", 150000));
    verifyWorld(context);
    context.call("world.disconnect");
  }

  static void verifyWorld(ProbeContext context) {
    var at = context.onClient(() -> Minecraft.getInstance().player.blockPosition().offset(2, 0, 0));
    context.onServer(
        () -> {
          var world = Minecraft.getInstance().getSingleplayerServer().overworld();
          world.setBlock(at, Blocks.AIR.defaultBlockState(), 3);
          world.setBlock(at.below(), Blocks.STONE.defaultBlockState(), 3);
          return null;
        });
    context.await(
        "revision support fixture",
        Duration.ofSeconds(10),
        () ->
            context.onClient(
                () -> Minecraft.getInstance().level.getBlockState(at.below()).is(Blocks.STONE)));
    var labels = context.onClient(() -> NativeRevisionProbe.verify(at));
    for (var label : labels) context.require(true, label);
    System.out.println("LATTICIUM_AUDIT native dependency revisions: " + labels.size() + " cases");
  }
}

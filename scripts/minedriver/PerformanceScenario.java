import io.github.billstark001.latticium.mc.NativePerformanceProbe;
import io.github.billstark001.minedriver.api.*;
import java.util.Map;

public final class PerformanceScenario implements Scenario {
  @Override
  public void run(ProbeContext context) {
    context.call("world.create", Map.of("flat", true, "seed", "20261016", "timeoutMillis", 150000));
    verifyWorld(context);
    context.call("world.disconnect");
  }

  static void verifyWorld(ProbeContext context) {
    context.await(
        "benchmark chunk residency",
        java.time.Duration.ofSeconds(20),
        () ->
            context.onClient(
                () -> {
                  var mc = net.minecraft.client.Minecraft.getInstance();
                  var at = mc.player.blockPosition();
                  for (int dx = -1; dx <= 1; dx++)
                    for (int dz = -1; dz <= 1; dz++)
                      if (!mc.level
                          .getChunkSource()
                          .hasChunk((at.getX() >> 4) + dx, (at.getZ() >> 4) + dz)) return false;
                  return true;
                }));
    for (var label : context.onClient(NativePerformanceProbe::measure)) {
      context.require(true, label);
      System.out.println("LATTICIUM_AUDIT " + label);
    }
  }
}

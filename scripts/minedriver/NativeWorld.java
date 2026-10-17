import io.github.billstark001.minedriver.api.ProbeContext;
import net.minecraft.client.Minecraft;
import net.minecraft.world.Difficulty;

/**
 * Deterministic owned fixture conditions; avoids hostile knockback during inventory/reach audits.
 */
final class NativeWorld {
  private NativeWorld() {}

  static void quiet(ProbeContext context) {
    context.onClient(
        () -> {
          // Only the disposable audit client: inspecting another window must not pause
          // its integrated server while authoritative packet assertions are pending.
          Minecraft.getInstance().options.pauseOnLostFocus = false;
          return null;
        });
    context.onServer(
        () -> {
          var server = Minecraft.getInstance().getSingleplayerServer();
          server.setDifficulty(Difficulty.PEACEFUL, true);
          server
              .overworld()
              .getGameRules()
              .set(net.minecraft.world.level.gamerules.GameRules.SPAWN_MOBS, false, server);
          return null;
        });
  }
}

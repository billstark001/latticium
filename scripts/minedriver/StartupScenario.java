import io.github.billstark001.minedriver.api.ProbeContext;
import io.github.billstark001.minedriver.api.Scenario;
import java.util.Map;

/** Proves menu readiness; mod/audio initialization alone can precede a blocking warning screen. */
public final class StartupScenario implements Scenario {
  @Override
  public void run(ProbeContext context) {
    context.inspect();
    context.call(
        "assert.state",
        Map.of("path", "screen", "equals", "net.minecraft.client.gui.screens.TitleScreen"));
    context.call("assert.state", Map.of("path", "inWorld", "equals", false));
    context.screenshot("startup-title");
  }
}

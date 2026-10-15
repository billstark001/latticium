import io.github.billstark001.minedriver.api.ProbeContext;
import io.github.billstark001.minedriver.api.Scenario;
import java.time.Duration;
import java.util.Map;

/** Runs the existing typed gameplay probes inside MineDriver's isolated client and world. */
public final class LatticiumScenario implements Scenario {
  @Override
  public void run(ProbeContext context) throws Exception {
    context.call("session.commands");
    context.call("session.capabilities");
    context.inspect();
    BridgeConcurrencyScenario.verify(context);
    context.call("world.create", Map.of("flat", true, "seed", "20261015", "timeoutMillis", 150000));
    context.call("profile.start");
    var probe = context.onClient(() -> new AuditProbe(context.outputDirectory()));
    context.await(
        "Latticium gameplay checks",
        Duration.ofMinutes(5),
        () ->
            context.onClient(
                () -> {
                  probe.run();
                  return AuditProbe.isDone();
                }));
    context.require(probe.failure() == null, "Gameplay probe failed: " + probe.failure());
    context.call("profile.stop", Map.of("name", "latticium-gameplay"));
    context.screenshot("latticium-gameplay");
    ActivationScenario.verifyWorld(context);
    ResidencyScenario.verifyWorld(context);
    UiScenario.verifyWorld(context);
    context.call("world.disconnect");
  }
}

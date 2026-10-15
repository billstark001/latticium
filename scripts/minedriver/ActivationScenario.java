import io.github.billstark001.latticium.mc.LatticiumClient;
import io.github.billstark001.minedriver.api.ProbeContext;
import io.github.billstark001.minedriver.api.Scenario;
import java.time.Duration;
import java.util.Map;
import net.minecraft.client.Minecraft;

/** A while activation must retry a failed start when its missing selection becomes available. */
public final class ActivationScenario implements Scenario {
  private static final String ID = "test:activation_retry";
  private static final String SELECTION = "audit_activation_retry";

  @Override
  public void run(ProbeContext context) {
    context.call("world.create", Map.of("flat", true, "seed", "20261015", "timeoutMillis", 150000));
    verifyWorld(context);
    context.call("world.disconnect");
  }

  static void verifyWorld(ProbeContext context) {
    context.onClient(
        () -> {
          var minecraft = Minecraft.getInstance();
          var client = LatticiumClient.get();
          client.stopAll();
          client.loadProfile(
              """
          {"schema":1,"id":"test:activation_retry", "activation":{"where":"all()","mode":"while"},
           "scope":"selection(audit_activation_retry)", "select":{"where":"all()"},
           "target":{"clear":true},"policy":{"break":"deny"}}
          """);
          client.setEnabled(minecraft, ID, true);
          client.restoreAutomaticActivation();
          return null;
        });
    try {
      context.await(
          "initial missing-selection activation error",
          Duration.ofSeconds(5),
          () ->
              context.onClient(
                  () -> LatticiumClient.get().uiState().activationErrors().containsKey(ID)));
      context.require(
          context.onClient(() -> LatticiumClient.get().uiState().job() == null),
          "Failed activation must not install a job");
      context.onClient(
          () -> {
            var minecraft = Minecraft.getInstance();
            var client = LatticiumClient.get();
            client.setCorner(minecraft, 1);
            client.setCorner(minecraft, 2);
            client.saveSelection(minecraft, SELECTION);
            return null;
          });
      context.await(
          "while activation retry after selection recovery",
          Duration.ofSeconds(5),
          () ->
              context.onClient(
                  () -> {
                    var state = LatticiumClient.get().uiState();
                    return state.job() != null && !state.activationErrors().containsKey(ID);
                  }));
      context.require(
          context.onClient(() -> LatticiumClient.get().uiState().job().submittedActions() == 0),
          "Break-denied activation fixture must not submit actions");
    } finally {
      context.onClient(
          () -> {
            var client = LatticiumClient.get();
            client.setEnabled(Minecraft.getInstance(), ID, false);
            client.stopAll();
            return null;
          });
    }
  }
}

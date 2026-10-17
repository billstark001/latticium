import io.github.billstark001.latticium.mc.*;
import io.github.billstark001.minedriver.api.*;
import java.time.Duration;
import java.util.Map;

/** Ordinary built JAR discovery, as distinct from development source-directory mod loading. */
public final class JarInstallScenario implements Scenario {
  @Override
  public void run(ProbeContext context) {
    context.require(
        Boolean.getBoolean("latticium.audit.jarInstall"),
        "JAR-install launch mode was not selected");
    var location =
        ClientJob.class.getProtectionDomain().getCodeSource().getLocation().toExternalForm();
    context.require(
        location.contains(".jar"), "Native mod loaded from development directories: " + location);
    context.call("world.create", Map.of("flat", true, "seed", "20261016", "timeoutMillis", 150000));
    NativeWorld.quiet(context);
    context.onClient(
        () -> {
          var client = LatticiumClient.get();
          var mc = net.minecraft.client.Minecraft.getInstance();
          client.stopAll();
          client.loadProfile(
              """
              {"schema":1,"id":"test:disabled_jar","activation":{"where":"all()","mode":"while"},
               "scope":"box(0,80,0,0,80,0)","select":{"where":"all()"},"target":{"clear":true},"policy":{"break":"deny"}}
              """);
          client.setEnabled(mc, "test:disabled_jar", false);
          client.restoreAutomaticActivation();
          return null;
        });
    context.await(
        "disabled profile observation",
        Duration.ofSeconds(5),
        new java.util.function.BooleanSupplier() {
          int frames;

          public boolean getAsBoolean() {
            context.require(
                context.onClient(
                    () ->
                        LatticiumClient.get().uiState().job() == null
                            && !LatticiumClient.get().uiState().automaticSuspended()),
                "Disabled profiles created a task");
            return ++frames >= 20;
          }
        });
    context.require(
        context.onClient(
            () -> {
              var client = LatticiumClient.get();
              for (String field : new String[] {"autoBindings", "autoTrackers"}) {
                var value = client.getClass().getDeclaredField(field);
                value.setAccessible(true);
                if (!((Map<?, ?>) value.get(client)).isEmpty()) return false;
              }
              var anchor = client.getClass().getDeclaredField("lastActivationPlayer");
              anchor.setAccessible(true);
              return anchor.get(client) == null;
            }),
        "Disabled profiles bound or sampled an automatic activation");
    context.require(
        context.onClient(
            () -> {
              var providers = LatticiumClient.get().uiState().blueprintProviders();
              return Boolean.getBoolean("latticium.audit.expectBridge")
                  ? !providers.isEmpty()
                  : providers.isEmpty();
            }),
        "Installed optional bridge discovery mismatch");
    System.out.println(
        "LATTICIUM_AUDIT ordinary artifact mod location="
            + location
            + " disabled profiles retained zero tasks");
    context.call("world.disconnect");
  }
}

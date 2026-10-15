import io.github.billstark001.latticium.mc.LatticiumClient;
import io.github.billstark001.latticium.mc.ui.LatticiumScreen;
import io.github.billstark001.minedriver.api.ProbeContext;
import io.github.billstark001.minedriver.api.Scenario;
import io.github.billstark001.minedriver.api.Selector;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;

/** Native key/button dispatch and small-window task/settings screens in every shipped language. */
public final class UiScenario implements Scenario {
  @Override
  public void run(ProbeContext context) {
    context.call("session.commands");
    context.call("session.capabilities");
    context.inspect();
    context.call("world.create", Map.of("flat", true, "seed", "20261015", "timeoutMillis", 150000));
    verifyWorld(context);
    context.call("world.disconnect");
  }

  static void verifyWorld(ProbeContext context) {
    context.call("window.configure", Map.of("width", 640, "height", 480, "guiScale", 2));
    for (var language : List.of("en_us", "zh_cn", "zh_tw", "ja_jp")) {
      context.call("resources.language", Map.of("code", language));
      context.onClient(
          () -> {
            var minecraft = Minecraft.getInstance();
            var client = LatticiumClient.get();
            client.stopAll();
            var at = minecraft.player.blockPosition().offset(2, 0, 0);
            String profile =
                """
            {"schema":1,"id":"test:ui","scope":"box(%d,%d,%d,%d,%d,%d)",
             "select":{"where":"all()"},"target":{"clear":true},"policy":{"break":"deny"}}
            """
                    .formatted(at.getX(), at.getY(), at.getZ(), at.getX(), at.getY(), at.getZ());
            client.startInline(minecraft, profile);
            client.pause();
            return null;
          });
      context.call("input.key", Map.of("key", "F8", "frames", 3));
      context.call(
          "wait.state", Map.of("path", "screen", "equals", LatticiumScreen.class.getName()));
      inspectWidgets(context);
      context.click(Selector.key("latticium.screen.resume"));
      context.require(
          context.onClient(() -> !LatticiumClient.get().uiState().job().paused()),
          "Resume button must resume the job");
      context.call("input.key", Map.of("key", "F9", "frames", 3));
      context.require(
          context.onClient(() -> !LatticiumClient.get().uiState().job().paused()),
          "Gameplay pause shortcut must not fire through an open screen");
      context.click(Selector.key("latticium.screen.pause"));
      context.require(
          context.onClient(() -> LatticiumClient.get().uiState().job().paused()),
          "Pause button must pause the job");
      context.screenshot("tasks-" + language);
      context.click(Selector.key("latticium.screen.diagnostics"));
      inspectWidgets(context);
      context.call(
          "assert.widget", Map.of("selector", Map.of("key", "latticium.screen.copy_report")));
      context.screenshot("diagnostics-" + language);
      context.click(Selector.key("latticium.screen.settings"));
      context.require(
          context.onClient(
              () -> !(Minecraft.getInstance().gui.screen() instanceof LatticiumScreen)),
          "Settings button must open the registered Cloth screen");
      context.call("ui.tree");
      context.screenshot("settings-" + language);
      context.call("input.key", Map.of("key", "ESCAPE"));
      context.call(
          "wait.state", Map.of("path", "screen", "equals", LatticiumScreen.class.getName()));
      context.click(Selector.key("latticium.screen.tasks"));
      inspectWidgets(context);
      context.click(Selector.key("latticium.screen.stop_all"));
      context.require(
          context.onClient(
              () ->
                  LatticiumClient.get().uiState().job() == null
                      && LatticiumClient.get().uiState().automaticSuspended()),
          "Stop-all must cancel and hold activation");
      context.click(Selector.key("latticium.screen.restore_auto"));
      context.require(
          context.onClient(() -> !LatticiumClient.get().uiState().automaticSuspended()),
          "Restore button must clear the automatic hold");
      context.click(Selector.key("latticium.screen.done"));
      context.require(
          context.onClient(() -> Minecraft.getInstance().gui.screen() == null),
          "Done must return to gameplay");
    }
  }

  private static void inspectWidgets(ProbeContext context) {
    context.call("ui.tree");
    context.require(
        context.onClient(
            () -> {
              var screen = Minecraft.getInstance().gui.screen();
              for (var child : screen.children())
                if (child instanceof AbstractWidget widget) {
                  if (widget.getX() < 0
                      || widget.getY() < 0
                      || widget.getX() + widget.getWidth() > screen.width
                      || widget.getY() + widget.getHeight() > screen.height) return false;
                  if (widget.getMessage().getString().startsWith("latticium.")) return false;
                }
              return true;
            }),
        "Task widgets must fit the small viewport and resolve their translations");
  }
}

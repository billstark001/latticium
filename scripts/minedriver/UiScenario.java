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
      String before = context.onClient(() -> LatticiumClient.get().status());
      context.click(Selector.key("latticium.screen.execution"));
      context.require(
          context.onClient(
              () -> !(Minecraft.getInstance().gui.screen() instanceof LatticiumScreen)),
          "Execution options must open the per-profile editor");
      context.call("ui.tree");
      context.screenshot("execution-" + language);
      context.call("input.key", Map.of("key", "ESCAPE"));
      context.call(
          "wait.state", Map.of("path", "screen", "equals", LatticiumScreen.class.getName()));
      context.require(
          context.onClient(
              () ->
                  LatticiumClient.get().uiState().job() != null
                      && LatticiumClient.get().uiState().job().paused()),
          "Opening and cancelling execution options must preserve the paused task: " + before);
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
    saveExecutionOptions(context);
  }

  private static void saveExecutionOptions(ProbeContext context) {
    var saved = new String[1];
    context.onClient(
        () -> {
          var mc = Minecraft.getInstance();
          mc.gui.setScreen(
              io.github.billstark001.latticium.mc.ui.ProfileExecutionScreen.screen(
                  null,
                  """
          {"schema":1,"id":"test:ui_save","scope":"box(0,0,0,0,0,0)",
           "select":{"where":"all()"},"target":{"clear":true},"policy":{"break":"deny"}}
          """,
                  value -> saved[0] = value));
          return null;
        });
    context.call("render.await", Map.of("frames", 3));
    clickClothButton(context, "latticium.execution.value.cell");
    context.call("render.await", Map.of("frames", 3));
    clickClothButton(context, "text.cloth-config.save_and_done");
    context.require(saved[0] != null, "Execution editor did not invoke its save callback");
    var profile = new io.github.billstark001.latticium.planning.ProfileReader().read(saved[0]);
    context.require(
        profile.execution().consistency().granularity()
            == io.github.billstark001.latticium.planning.Execution.Granularity.SECTION,
        "Execution editor did not save the selected consistency policy");
    context.require(
        profile.id().toString().equals("test:ui_save"),
        "Execution editor changed profile identity");
  }

  private static void clickClothButton(ProbeContext context, String key) {
    // Cloth list entries use isActive for narration, not editability; use actual child bounds.
    var point =
        context.onClient(
            () -> {
              var widget = findButton(Minecraft.getInstance().gui.screen(), key);
              if (widget == null || !widget.active)
                throw new AssertionError("Missing enabled Cloth button " + key);
              return Map.of(
                  "x",
                  widget.getX() + widget.getWidth() / 2.0,
                  "y",
                  widget.getY() + widget.getHeight() / 2.0);
            });
    context.call("input.click", point);
  }

  private static AbstractWidget findButton(
      net.minecraft.client.gui.components.events.GuiEventListener node, String key) {
    if (node instanceof AbstractWidget widget
        && widget.getMessage().getContents()
            instanceof net.minecraft.network.chat.contents.TranslatableContents contents
        && contents.getKey().equals(key)) return widget;
    if (node instanceof net.minecraft.client.gui.components.events.ContainerEventHandler container)
      for (var child : container.children()) {
        var found = findButton(child, key);
        if (found != null) return found;
      }
    return null;
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

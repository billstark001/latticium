package io.github.billstark001.latticium.mc.ui;

import io.github.billstark001.latticium.mc.ClientDiagnostics;
import io.github.billstark001.latticium.mc.ClientUiState;
import io.github.billstark001.latticium.mc.LatticiumClient;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Non-pausing task controls and diagnostics; opening or rendering never starts a job. */
public final class LatticiumScreen extends Screen {
  private final Screen parent;
  private boolean diagnostics;
  private String selectedProfile = "";
  private List<String> profiles = List.of();
  private List<Component> currentLines = List.of();
  private Component failure;
  private UiTextList text;
  private Button start;
  private Button pause;
  private Button cancel;
  private Button refresh;
  private Button stop;
  private Button restore;
  private int ticks;

  public LatticiumScreen(Screen parent) {
    super(UiText.tr("screen.title"));
    this.parent = parent;
  }

  public static void open() {
    var minecraft = Minecraft.getInstance();
    if (ClientConfig.get().pauseOnOpen) LatticiumClient.get().pause();
    minecraft.gui.setScreen(new LatticiumScreen(minecraft.gui.screen()));
  }

  @Override
  protected void init() {
    var client = LatticiumClient.get();
    var state = client.uiState();
    profiles = client.profileIds();
    if (!profiles.contains(selectedProfile))
      selectedProfile = profiles.isEmpty() ? "" : profiles.getFirst();
    int panelWidth = Math.min(480, width - 24);
    int left = (width - panelWidth) / 2;
    int tabWidth = (panelWidth - 8) / 3;
    button("screen.tasks", left, 32, tabWidth, () -> switchTab(false)).active = diagnostics;
    button("screen.diagnostics", left + tabWidth + 4, 32, tabWidth, () -> switchTab(true)).active =
        !diagnostics;
    button(
        "screen.settings",
        left + (tabWidth + 4) * 2,
        32,
        tabWidth,
        () -> minecraft.gui.setScreen(ClientConfig.screen(this)));
    int half = (panelWidth - 4) / 2;
    if (diagnostics) {
      text =
          addRenderableWidget(
              new UiTextList(minecraft, panelWidth, Math.max(24, height - 112), 60, left));
      button(
          "screen.copy_report",
          left,
          height - 44,
          half,
          () -> minecraft.keyboardHandler.setClipboard(reportText()));
    } else {
      var choices = profiles.isEmpty() ? List.of("") : profiles;
      var selector =
          addRenderableWidget(
              CycleButton.<String>builder(
                      id -> id.isEmpty() ? UiText.tr("screen.no_profiles") : Component.literal(id),
                      selectedProfile)
                  .withValues(choices)
                  .displayOnlyValue()
                  .create(
                      left,
                      60,
                      panelWidth - 100,
                      20,
                      UiText.tr("screen.profile"),
                      (button, id) -> selectedProfile = id));
      selector.active = !profiles.isEmpty();
      selector.setTooltip(Tooltip.create(UiText.tr("screen.profile_tip")));
      start =
          button(
              "screen.start",
              left + panelWidth - 96,
              60,
              96,
              () -> client.start(minecraft, selectedProfile));
      start.setTooltip(Tooltip.create(UiText.tr("screen.start_tip")));
      text =
          addRenderableWidget(
              new UiTextList(minecraft, panelWidth, Math.max(24, height - 188), 88, left));
      pause =
          button(
              "screen.pause",
              left,
              height - 92,
              half,
              () -> {
                var job = client.uiState().job();
                if (job != null && job.paused()) client.resume();
                else client.pause();
              });
      cancel = button("screen.cancel", left + half + 4, height - 92, half, client::cancel);
      cancel.setTooltip(Tooltip.create(UiText.tr("screen.cancel_tip")));
      refresh = button("screen.refresh", left, height - 68, half, client::refresh);
      refresh.setTooltip(Tooltip.create(UiText.tr("screen.refresh_tip")));
      stop = button("screen.stop_all", left + half + 4, height - 68, half, client::stopAll);
      stop.setTooltip(Tooltip.create(UiText.tr("screen.stop_tip")));
      restore =
          button(
              "screen.restore_auto", left, height - 44, half, client::restoreAutomaticActivation);
    }
    button("screen.done", left + half + 4, height - 44, half, this::onClose);
    update(state);
  }

  private Button button(String key, int x, int y, int width, Runnable action) {
    return addRenderableWidget(
        Button.builder(UiText.tr(key), button -> run(action)).bounds(x, y, width, 20).build());
  }

  private void run(Runnable action) {
    try {
      action.run();
      failure = null;
    } catch (RuntimeException error) {
      failure =
          UiText.tr(
                  "screen.error",
                  error.getMessage() == null
                      ? error.getClass().getSimpleName()
                      : error.getMessage())
              .withStyle(ChatFormatting.RED);
    }
    update(LatticiumClient.get().uiState());
  }

  private void switchTab(boolean value) {
    diagnostics = value;
    rebuildWidgets();
  }

  @Override
  public void tick() {
    super.tick();
    var client = LatticiumClient.get();
    if (++ticks % ClientConfig.get().diagnostics.refreshTicks != 0) return;
    if (!profiles.equals(client.profileIds())) rebuildWidgets();
    else update(client.uiState());
  }

  private void update(ClientUiState state) {
    var client = LatticiumClient.get();
    var rows = new ArrayList<Component>();
    if (failure != null) rows.add(failure);
    if (diagnostics) {
      var report =
          ClientDiagnostics.capture(client.blocked(), ClientConfig.get().diagnostics.maxGroups);
      rows.addAll(UiText.diagnostics(state, report));
    } else {
      rows.addAll(UiText.summary(state));
      if (profiles.isEmpty())
        rows.add(UiText.tr("screen.load_hint").withStyle(ChatFormatting.GRAY));
      var job = state.job();
      start.active =
          state.inWorld()
              && !selectedProfile.isEmpty()
              && state.settlingJobs() == 0
              && (job == null || !job.waiting());
      pause.active = state.inWorld() && job != null;
      pause.setMessage(UiText.tr(job != null && job.paused() ? "screen.resume" : "screen.pause"));
      cancel.active = state.inWorld() && job != null;
      refresh.active = state.inWorld() && job != null;
      stop.active = state.inWorld();
      restore.active = state.inWorld() && state.automaticSuspended();
    }
    currentLines = List.copyOf(rows);
    text.update(currentLines);
  }

  private String reportText() {
    var rows = new ArrayList<Component>(UiText.summary(LatticiumClient.get().uiState()));
    rows.addAll(currentLines);
    return UiText.tr("diagnostics.report_title").getString()
        + "\n"
        + rows.stream()
            .map(Component::getString)
            .collect(java.util.stream.Collectors.joining("\n"));
  }

  @Override
  public void extractRenderState(
      GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
    super.extractRenderState(graphics, mouseX, mouseY, delta);
    graphics.centeredText(font, title, width / 2, 12, 0xFFFFFFFF);
  }

  @Override
  public boolean isPauseScreen() {
    return false;
  }

  @Override
  public void onClose() {
    minecraft.gui.setScreen(parent);
  }
}

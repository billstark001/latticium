package io.github.billstark001.latticium.mc.ui;

import io.github.billstark001.latticium.mc.LatticiumClient;
import java.util.ArrayList;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** A small shared HUD, registered through each loader's native GUI-layer API. */
public final class LatticiumHud {
  private LatticiumHud() {}

  public static void extract(GuiGraphicsExtractor graphics, DeltaTracker delta) {
    var minecraft = Minecraft.getInstance();
    var config = ClientConfig.get().hud;
    if (!config.enabled || minecraft.gui.hud.isHidden() || minecraft.gui.screen() != null) return;
    var state = LatticiumClient.get().uiState();
    if (!state.inWorld()) return;
    if (state.job() == null
        && state.settlingJobs() == 0
        && !state.automaticSuspended()
        && state.activationErrors().isEmpty()
        && !config.showWhenIdle) return;
    var lines = new ArrayList<Component>();
    lines.add(UiText.tr("hud.title", UiText.state(state)));
    var job = state.job();
    if (job != null) {
      lines.add(Component.literal(job.profileId()));
      lines.add(UiText.tr("status.scan", job.scannedSections(), job.totalSections()));
      lines.add(UiText.tr("status.candidates", job.satisfiedCandidates(), job.pendingCandidates()));
      lines.add(
          UiText.tr(
              "hud.actions", job.submittedActions(), job.actionLimit(), job.blockedCandidates()));
      if (job.deferredReads() > 0 || job.unsupportedSections() > 0)
        lines.add(UiText.tr("status.unavailable", job.deferredReads(), job.unsupportedSections()));
    }
    if (state.automaticSuspended()) lines.add(UiText.tr("automatic.suspended"));
    if (!state.activationErrors().isEmpty())
      lines.add(UiText.tr("hud.auto_errors", state.activationErrors().size()));
    int width = Math.min(config.width, graphics.guiWidth() - 8);
    int height = lines.size() * 12 + 8;
    int x = config.alignRight ? graphics.guiWidth() - width - config.marginX : config.marginX;
    int y = config.alignBottom ? graphics.guiHeight() - height - config.marginY : config.marginY;
    x = Math.clamp(x, 0, Math.max(0, graphics.guiWidth() - width));
    y = Math.clamp(y, 0, Math.max(0, graphics.guiHeight() - height));
    int background = (config.backgroundOpacity * 255 / 100) << 24;
    graphics.fill(x, y, x + width, y + height, background);
    for (int i = 0; i < lines.size(); i++)
      graphics.text(
          minecraft.font,
          minecraft.font.plainSubstrByWidth(lines.get(i).getString(), width - 8),
          x + 4,
          y + 4 + i * 12,
          0xFFFFFFFF);
  }
}

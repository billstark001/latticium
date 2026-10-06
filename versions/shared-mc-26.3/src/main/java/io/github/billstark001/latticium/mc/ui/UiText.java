package io.github.billstark001.latticium.mc.ui;

import io.github.billstark001.latticium.mc.ClientDiagnostics;
import io.github.billstark001.latticium.mc.ClientUiState;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** Localized presentation of structured snapshots; engine details remain available for reports. */
final class UiText {
  private UiText() {}

  static MutableComponent tr(String key, Object... arguments) {
    return Component.translatable("latticium." + key, arguments);
  }

  static Component state(ClientUiState state) {
    if (!state.inWorld()) return tr("state.no_world");
    if (state.job() == null) {
      if (state.settlingJobs() > 0) return tr("state.settling");
      return tr(state.automaticSuspended() ? "state.stopped" : "state.idle");
    }
    var job = state.job();
    if (job.waiting() && job.paused()) return tr("state.pausing");
    return tr("state." + job.phase().name().toLowerCase(Locale.ROOT));
  }

  static List<Component> summary(ClientUiState state) {
    var rows = new ArrayList<Component>();
    rows.add(tr("status.state", state(state)));
    var job = state.job();
    if (job != null) {
      rows.add(tr("status.profile", job.profileId()));
      rows.add(tr("status.scan", job.scannedSections(), job.totalSections()));
      rows.add(tr("status.candidates", job.satisfiedCandidates(), job.pendingCandidates()));
      rows.add(tr("status.actions", job.submittedActions(), job.actionLimit()));
      rows.add(tr("status.blocked", job.blockedCandidates()));
      rows.add(tr("status.unavailable", job.deferredReads(), job.unsupportedSections()));
      rows.add(tr("status.rescan", job.dirtyReads(), job.refreshRemaining()));
      rows.add(
          tr(job.continuous() ? "status.continuous" : "status.manual")
              .withStyle(ChatFormatting.GRAY));
      if (job.waiting()) rows.add(tr("status.confirmation").withStyle(ChatFormatting.YELLOW));
    }
    rows.add(
        tr(
            "status.automatic",
            state.enabledProfiles().size(),
            tr(state.automaticSuspended() ? "automatic.suspended" : "automatic.allowed")));
    if (state.settlingJobs() > 0) rows.add(tr("status.settling", state.settlingJobs()));
    return rows;
  }

  static List<Component> diagnostics(ClientUiState state, ClientDiagnostics report) {
    var rows = new ArrayList<Component>();
    rows.add(tr("diagnostics.summary", report.totalPositions(), report.totalGroups()));
    if (state.job() != null) {
      rows.add(
          tr("status.unavailable", state.job().deferredReads(), state.job().unsupportedSections()));
      if (state.job().phase()
          == io.github.billstark001.latticium.mc.ClientJobSnapshot.Phase.BUDGET_EXHAUSTED)
        rows.add(tr("diagnostics.hint.budget").withStyle(ChatFormatting.YELLOW));
    }
    if (report.totalGroups() > report.groups().size())
      rows.add(tr("diagnostics.limited", report.groups().size(), report.totalGroups()));
    for (var group : report.groups()) {
      String kind = group.kind().name().toLowerCase(Locale.ROOT);
      rows.add(
          tr("diagnostics.group", tr("diagnostics.kind." + kind), group.count())
              .withStyle(ChatFormatting.YELLOW));
      var pos = group.example();
      rows.add(tr("diagnostics.position", pos.dimension().toString(), pos.x(), pos.y(), pos.z()));
      rows.add(tr("diagnostics.hint." + kind));
      if (ClientConfig.get().diagnostics.showTechnicalDetails)
        rows.add(tr("diagnostics.detail", group.detail()).withStyle(ChatFormatting.GRAY));
    }
    if (report.totalPositions() == 0) rows.add(tr("diagnostics.no_blocked"));
    if (!state.storageError().isEmpty()) rows.add(tr("diagnostics.storage", state.storageError()));
    state.activationErrors().entrySet().stream()
        .sorted(java.util.Map.Entry.comparingByKey())
        .forEach(entry -> rows.add(tr("diagnostics.activation", entry.getKey(), entry.getValue())));
    rows.add(tr("diagnostics.blueprints", state.blueprintProviders().size()));
    state.blueprintProviders().stream()
        .map(Object::toString)
        .sorted()
        .forEach(id -> rows.add(Component.literal(id).withStyle(ChatFormatting.GRAY)));
    rows.add(tr("diagnostics.scan_note").withStyle(ChatFormatting.GRAY));
    return rows;
  }
}

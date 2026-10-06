package io.github.billstark001.latticium.mc;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.billstark001.latticium.dsl.Model.Position;
import io.github.billstark001.latticium.dsl.Model.ResourceId;
import io.github.billstark001.latticium.mc.ui.ClientConfig;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import me.shedaniel.autoconfig.annotation.ConfigEntry;
import org.junit.jupiter.api.Test;

class UiPresentationTest {
  @Test
  void waitingAndExhaustionCannotLookCompleteOrContinuouslyIdle() {
    assertEquals(ClientJobSnapshot.Phase.WAITING, phase(true, true, false, false, false, 1, 0, 0));
    assertEquals(
        ClientJobSnapshot.Phase.BUDGET_EXHAUSTED, phase(false, false, true, false, true, 0, 0, 0));
    assertEquals(ClientJobSnapshot.Phase.PAUSED, phase(true, false, false, true, true, 1, 0, 0));
    assertEquals(
        ClientJobSnapshot.Phase.MONITORING, phase(false, false, false, false, true, 0, 0, 0));
    assertEquals(
        ClientJobSnapshot.Phase.COMPLETE, phase(false, false, false, false, false, 0, 0, 0));
    assertEquals(
        ClientJobSnapshot.Phase.BLOCKED, phase(false, false, false, false, false, 0, 1, 0));
    assertEquals(
        ClientJobSnapshot.Phase.BLOCKED, phase(false, false, false, false, false, 0, 0, 1));
    assertEquals(
        ClientJobSnapshot.Phase.SCANNING, phase(false, false, false, true, false, 0, 0, 0));
    assertEquals(
        ClientJobSnapshot.Phase.RUNNING, phase(false, false, false, false, false, 1, 20, 0));
  }

  private static ClientJobSnapshot.Phase phase(
      boolean paused,
      boolean waiting,
      boolean exhausted,
      boolean scanning,
      boolean continuous,
      int pending,
      int blocked,
      int unsupported) {
    return ClientJobSnapshot.phase(
        paused, waiting, exhausted, scanning, continuous, pending, blocked, unsupported);
  }

  @Test
  void boundedDiagnosticsKeepExactTotalsAndDeterministicSamples() {
    var blocked = new HashMap<Position, String>();
    var dimension = ResourceId.parse("minecraft:overworld");
    var first = new Position(dimension, -1, 64, 0);
    blocked.put(new Position(dimension, 8, 64, 0), "Outside interaction reach");
    blocked.put(first, "Outside interaction reach");
    blocked.put(new Position(dimension, 0, 64, 0), "TIMED_OUT");
    var report = ClientDiagnostics.capture(blocked, 1);
    assertEquals(3, report.totalPositions());
    assertEquals(2, report.totalGroups());
    assertEquals(1, report.groups().size());
    var group = report.groups().getFirst();
    assertEquals(2, group.count());
    assertEquals(first, group.example());
    assertEquals(ClientDiagnostics.Kind.REACH, group.kind());
    blocked.clear();
    assertEquals(2, group.count());
    assertThrows(UnsupportedOperationException.class, () -> report.groups().clear());
    assertThrows(IllegalArgumentException.class, () -> ClientDiagnostics.capture(Map.of(), 0));
  }

  @Test
  void diagnosticsSeparateUnavailableFactsFromMissingMaterials() {
    assertEquals(
        ClientDiagnostics.Kind.DATA, ClientDiagnostics.Kind.classify("Material facts unavailable"));
    assertEquals(
        ClientDiagnostics.Kind.MATERIAL,
        ClientDiagnostics.Kind.classify("Hotbar block item required for replacement target"));
    assertEquals(
        ClientDiagnostics.Kind.BUDGET,
        ClientDiagnostics.Kind.classify("Replacement needs at least two remaining actions"));
    assertEquals(
        ClientDiagnostics.Kind.CONFIRMATION, ClientDiagnostics.Kind.classify("CONTRADICTED"));
    assertEquals(
        ClientDiagnostics.Kind.OTHER, ClientDiagnostics.Kind.classify("Custom provider detail"));
  }

  @Test
  void presentationSnapshotsCannotMutateEnabledProfilesOrErrors() {
    var enabled = new HashSet<>(Set.of("user:build"));
    var errors = new HashMap<>(Map.of("user:build", "Target unavailable"));
    var state = new ClientUiState(true, null, 1, true, enabled, errors, "", Set.of());
    enabled.clear();
    errors.clear();
    assertEquals(Set.of("user:build"), state.enabledProfiles());
    assertEquals("Target unavailable", state.activationErrors().get("user:build"));
    assertThrows(UnsupportedOperationException.class, () -> state.enabledProfiles().clear());
  }

  @Test
  void persistedConfigurationRepairsNullGroupsAndOutOfRangeValues() {
    var config = new ClientConfig();
    config.hud.width = 0;
    config.hud.marginX = -10;
    config.hud.backgroundOpacity = 1000;
    config.diagnostics.refreshTicks = 0;
    config.diagnostics.maxGroups = Integer.MAX_VALUE;
    config.validatePostLoad();
    assertEquals(160, config.hud.width);
    assertEquals(0, config.hud.marginX);
    assertEquals(100, config.hud.backgroundOpacity);
    assertEquals(1, config.diagnostics.refreshTicks);
    assertEquals(256, config.diagnostics.maxGroups);
    config.hud = null;
    config.diagnostics = null;
    config.validatePostLoad();
    assertTrue(config.hud.enabled);
    assertFalse(config.hud.showWhenIdle);
    assertFalse(config.pauseOnOpen);
    assertEquals(10, config.diagnostics.refreshTicks);
  }

  @Test
  void registrationStateIsExcludedFromGeneratedSettings() {
    for (var field : ClientConfig.class.getDeclaredFields()) {
      if (Modifier.isStatic(field.getModifiers()))
        assertTrue(field.isAnnotationPresent(ConfigEntry.Gui.Excluded.class), field.getName());
    }
  }

  @Test
  void allFourLanguagesCoverStatesDiagnosticsSettingsAndMatchingPlaceholders() throws IOException {
    var english = language("en_us");
    assertConfigKeys(english, ClientConfig.class, "text.autoconfig.latticium.option.");
    for (var phase : ClientJobSnapshot.Phase.values())
      assertTrue(english.containsKey("latticium.state." + phase.name().toLowerCase(Locale.ROOT)));
    for (var kind : ClientDiagnostics.Kind.values()) {
      var name = kind.name().toLowerCase(Locale.ROOT);
      assertTrue(english.containsKey("latticium.diagnostics.kind." + name));
      assertTrue(english.containsKey("latticium.diagnostics.hint." + name));
    }
    var placeholders = Pattern.compile("%(?:[0-9]+\\$)?[sd]");
    for (var locale : List.of("zh_cn", "zh_tw", "ja_jp")) {
      var translated = language(locale);
      assertEquals(english.keySet(), translated.keySet(), locale);
      for (var key : english.keySet()) {
        assertFalse(translated.get(key).isBlank(), locale + ": " + key);
        assertEquals(
            placeholders.matcher(english.get(key)).results().map(result -> result.group()).toList(),
            placeholders
                .matcher(translated.get(key))
                .results()
                .map(result -> result.group())
                .toList(),
            locale + ": " + key);
      }
    }
  }

  private static Map<String, String> language(String locale) throws IOException {
    try (var input =
        UiPresentationTest.class.getResourceAsStream(
            "/assets/latticium/lang/" + locale + ".json")) {
      assertNotNull(input, locale);
      return new ObjectMapper().readValue(input, new TypeReference<Map<String, String>>() {});
    }
  }

  private static void assertConfigKeys(Map<String, String> language, Class<?> type, String prefix) {
    for (var field : type.getFields()) {
      if (Modifier.isStatic(field.getModifiers())) continue;
      String key = prefix + field.getName();
      assertTrue(language.containsKey(key), key);
      if (field.isAnnotationPresent(ConfigEntry.Gui.Tooltip.class))
        assertTrue(language.containsKey(key + ".@Tooltip"), key);
      if (field.isAnnotationPresent(ConfigEntry.Gui.CollapsibleObject.class))
        assertConfigKeys(language, field.getType(), key + ".");
    }
  }
}

package io.github.billstark001.latticium.mc.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.billstark001.latticium.planning.*;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;

/** Edits one saved profile through the existing strict reader; opening never edits or starts it. */
public final class ProfileExecutionScreen {
  private static final ObjectMapper JSON = new ObjectMapper();

  private ProfileExecutionScreen() {}

  public static Screen screen(Screen parent, String json, Consumer<String> save) {
    var profile = new ProfileReader().read(json);
    final ObjectNode root;
    try {
      root = (ObjectNode) JSON.readTree(json);
    } catch (java.io.IOException error) {
      throw new IllegalArgumentException("Invalid saved profile", error);
    }
    var options = profile.execution();
    var defaults = Execution.DEFAULT;
    var builder =
        ConfigBuilder.create()
            .setDoesConfirmSave(false)
            .setParentScreen(parent)
            .setTitle(UiText.tr("execution.title", profile.id().toString()))
            .setSavingRunnable(() -> save.accept(root.toString()));
    var entries = builder.entryBuilder();
    var consistency = builder.getOrCreateCategory(UiText.tr("execution.consistency"));
    choice(
        consistency,
        entries,
        root,
        "consistency",
        "granularity",
        options.consistency().granularity(),
        defaults.consistency().granularity());
    choice(
        consistency,
        entries,
        root,
        "consistency",
        "selections",
        options.consistency().selections(),
        defaults.consistency().selections());
    choice(
        consistency,
        entries,
        root,
        "consistency",
        "rules",
        options.consistency().rules(),
        defaults.consistency().rules());
    choice(
        consistency,
        entries,
        root,
        "consistency",
        "targets",
        options.consistency().targets(),
        defaults.consistency().targets());
    choice(
        consistency,
        entries,
        root,
        "consistency",
        "inventory",
        options.consistency().inventory(),
        defaults.consistency().inventory());
    var dynamics = builder.getOrCreateCategory(UiText.tr("execution.dynamics"));
    choice(
        dynamics,
        entries,
        root,
        "dynamics",
        "motion",
        options.dynamics().motion(),
        defaults.dynamics().motion());
    choice(
        dynamics,
        entries,
        root,
        "dynamics",
        "repeat",
        options.dynamics().repeat(),
        defaults.dynamics().repeat());
    integer(
        dynamics,
        entries,
        root,
        "dynamics",
        "settle_ticks",
        options.dynamics().settleTicks(),
        20,
        1,
        1200);
    var dependencies = builder.getOrCreateCategory(UiText.tr("execution.dependencies"));
    choice(
        dependencies,
        entries,
        root,
        "dependencies",
        "reads",
        options.dependencies().reads(),
        defaults.dependencies().reads());
    integer(
        dependencies,
        entries,
        root,
        "dependencies",
        "radius",
        options.dependencies().radius(),
        1,
        0,
        16);
    String offsets =
        options.dependencies().extraOffsets().stream()
            .map(o -> o.x() + "," + o.y() + "," + o.z())
            .collect(Collectors.joining("; "));
    dependencies.addEntry(
        entries
            .startTextField(UiText.tr("execution.extra_offsets"), offsets)
            .setDefaultValue("")
            .setTooltip(UiText.tr("execution.extra_offsets_tip"))
            .setErrorSupplier(
                value -> {
                  try {
                    offsets(value);
                    return Optional.empty();
                  } catch (IllegalArgumentException invalid) {
                    return Optional.of(UiText.tr("execution.offsets_error"));
                  }
                })
            .setSaveConsumer(
                value -> group(root, "dependencies").set("extra_offsets", offsets(value)))
            .build());
    var planning = builder.getOrCreateCategory(UiText.tr("execution.planning"));
    planning.addEntry(
        entries
            .startTextField(
                UiText.tr("execution.node_budgets"),
                options.planning().initialNodes() + "," + options.planning().maxNodesPerTick())
            .setDefaultValue("128,1024")
            .setTooltip(UiText.tr("execution.node_budgets_tip"))
            .setErrorSupplier(
                value -> {
                  try {
                    budgets(value);
                    return Optional.empty();
                  } catch (IllegalArgumentException invalid) {
                    return Optional.of(UiText.tr("execution.budgets_error"));
                  }
                })
            .setSaveConsumer(
                value -> {
                  var values = budgets(value);
                  group(root, "planning")
                      .put("initial_nodes", values[0])
                      .put("max_nodes_per_tick", values[1]);
                })
            .build());
    integer(
        planning,
        entries,
        root,
        "planning",
        "max_retry_entries",
        options.planning().maxRetryEntries(),
        256,
        1,
        65_536);
    integer(
        planning,
        entries,
        root,
        "planning",
        "max_candidates",
        options.planning().maxCandidates(),
        4096,
        1,
        1_000_000);
    integer(
        planning,
        entries,
        root,
        "planning",
        "retry_ticks",
        options.planning().retryTicks(),
        20,
        1,
        1200);
    return builder.build();
  }

  private static ObjectNode group(ObjectNode root, String group) {
    ObjectNode execution =
        root.has("execution") ? (ObjectNode) root.get("execution") : root.putObject("execution");
    return execution.has(group) ? (ObjectNode) execution.get(group) : execution.putObject(group);
  }

  private static <T extends Enum<T>> void choice(
      ConfigCategory category,
      ConfigEntryBuilder entries,
      ObjectNode root,
      String group,
      String key,
      T value,
      T fallback) {
    category.addEntry(
        entries
            .startEnumSelector(UiText.tr("execution." + key), value.getDeclaringClass(), value)
            .setDefaultValue(fallback)
            .setEnumNameProvider(
                option -> UiText.tr("execution.value." + option.name().toLowerCase(Locale.ROOT)))
            .setTooltip(UiText.tr("execution." + key + "_tip"))
            .setSaveConsumer(
                option -> group(root, group).put(key, option.name().toLowerCase(Locale.ROOT)))
            .build());
  }

  private static void integer(
      ConfigCategory category,
      ConfigEntryBuilder entries,
      ObjectNode root,
      String group,
      String key,
      int value,
      int fallback,
      int minimum,
      int maximum) {
    category.addEntry(
        entries
            .startIntField(UiText.tr("execution." + key), value)
            .setMin(minimum)
            .setMax(maximum)
            .setDefaultValue(fallback)
            .setTooltip(UiText.tr("execution." + key + "_tip"))
            .setSaveConsumer(number -> group(root, group).put(key, number))
            .build());
  }

  private static int[] budgets(String value) {
    var parts = value.split(",", -1);
    if (parts.length != 2) throw new IllegalArgumentException("Two node limits required");
    int initial = Integer.parseInt(parts[0].strip()), maximum = Integer.parseInt(parts[1].strip());
    if (initial < 1 || initial > maximum || maximum > 65_536)
      throw new IllegalArgumentException("Invalid node limits");
    return new int[] {initial, maximum};
  }

  private static ArrayNode offsets(String value) {
    var result = JSON.createArrayNode();
    if (value.isBlank()) return result;
    var triples = value.split(";", -1);
    if (triples.length > 512) throw new IllegalArgumentException("Too many offsets");
    for (var triple : triples) {
      var coordinates = triple.split(",", -1);
      if (coordinates.length != 3) throw new IllegalArgumentException("Three coordinates required");
      var offset = result.addArray();
      for (var coordinate : coordinates) {
        int number = Integer.parseInt(coordinate.strip());
        if (number < -16 || number > 16) throw new IllegalArgumentException("Offset outside halo");
        offset.add(number);
      }
    }
    return result;
  }
}

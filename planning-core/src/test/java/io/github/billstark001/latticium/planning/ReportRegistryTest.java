package io.github.billstark001.latticium.planning;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.JsonParseException;
import io.github.billstark001.latticium.dsl.Model.ResourceId;
import io.github.billstark001.latticium.dsl.Model.SetType;
import io.github.billstark001.latticium.planning.offline.ReportRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportRegistryTest {
  @TempDir Path directory;

  private ReportRegistry load(String json) throws Exception {
    Path catalog = directory.resolve("catalog.json");
    Files.writeString(catalog, json);
    return ReportRegistry.load(catalog);
  }

  @Test
  void rejectsMissingSectionsAndIllegalStates() {
    assertThrows(IllegalArgumentException.class, () -> load("{\"schema\":1}"));
    String valid =
        """
        {"schema":1,"version":"test","universes":{"block":["minecraft:stone"],
        "item":[],"fluid":[],"biome":[]},"tags":{"block":{},"item":{},"fluid":{},"biome":{}},
        "blocks":{"minecraft:stone":{"properties":{},"states":[{}]}}}
        """;
    var registry = assertDoesNotThrow(() -> load(valid));
    assertThrows(
        IllegalArgumentException.class,
        () -> registry.resolve(SetType.POS, ResourceId.parse("minecraft:stone")));
    assertThrows(IllegalArgumentException.class, () -> registry.universe(SetType.POS));
    assertThrows(
        IllegalArgumentException.class,
        () -> load(valid.replace("\"states\":[{}]", "\"states\":[{\"axis\":\"x\"}]")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            load(
                valid.replace(
                    "\"block\":{}", "\"block\":{\"minecraft:bad\":[\"minecraft:missing\"]}")));
    assertThrows(
        JsonParseException.class,
        () -> load(valid.replace("\"schema\":1", "\"schema\":1,\"schema\":1")));
    assertThrows(IOException.class, () -> load(valid + "{}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            load(
                valid.replace(
                    "\"block\":[\"minecraft:stone\"]",
                    "\"block\":[\"minecraft:stone\",\"minecraft:stone\"]")));
    assertThrows(
        IllegalArgumentException.class,
        () -> load(valid.replace("\"states\":[{}]", "\"states\":[{},{}]")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            load(
                valid.replace(
                    "\"block\":{}",
                    "\"block\":{\"minecraft:rocks\":[\"minecraft:stone\",\"minecraft:stone\"]}")));
  }
}

package io.github.billstark001.latticium.mc;

import static org.junit.jupiter.api.Assertions.*;

import io.github.billstark001.latticium.planning.SectionScanner.Bounds;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldStorageTest {
  @TempDir Path directory;

  @Test
  void localWorldIdentityUsesTheNormalizedSaveDirectory() {
    var first = directory.resolve("Same Name");
    var second = directory.resolve("Same Name (1)");
    assertNotEquals(WorldStorage.singleplayerKey(first), WorldStorage.singleplayerKey(second));
    assertEquals(
        WorldStorage.singleplayerKey(first), WorldStorage.singleplayerKey(first.resolve(".")));
  }

  @Test
  void trailingEmptySelectionEntryIsRejectedInsteadOfDiscarded() throws Exception {
    var path = directory.resolve("trailing.properties");
    Files.writeString(path, "selection.build=minecraft:overworld,0,0,0,1,1,1;\n");
    assertThrows(
        IllegalArgumentException.class,
        () -> WorldStorage.load(path, new HashMap<>(), new HashMap<>(), new HashSet<>()));
  }

  @Test
  void dimensionFilesStayInsideServerDirectoryAndDoNotCollide() {
    var first = WorldStorage.dimensionPath(directory, "a:b_c");
    var second = WorldStorage.dimensionPath(directory, "a_b:c");
    var traversal = WorldStorage.dimensionPath(directory, "a:../../../outside");
    assertNotEquals(first, second);
    assertEquals(directory, traversal.getParent());
    assertTrue(traversal.startsWith(directory));
  }

  @Test
  void readsExistingSafeLegacyFileUntilHashedFileExists() throws Exception {
    var hashed = WorldStorage.dimensionPath(directory, "minecraft:overworld");
    var legacy = directory.resolve("minecraft_overworld.properties");
    Files.writeString(legacy, "old");
    assertEquals(legacy, WorldStorage.dimensionPath(directory, "minecraft:overworld"));
    Files.writeString(hashed, "new");
    assertEquals(hashed, WorldStorage.dimensionPath(directory, "minecraft:overworld"));
  }

  @Test
  void malformedSelectionDoesNotPartiallyLoadPreferences() throws Exception {
    var path = directory.resolve("preferences.properties");
    Files.writeString(path, "profile.example={}\nselection.bad=invalid\nenabled.example=true\n");
    var profiles = new HashMap<String, String>();
    var selections = new HashMap<String, List<Bounds>>();
    var enabled = new HashSet<String>();
    assertThrows(
        IllegalArgumentException.class,
        () -> WorldStorage.load(path, profiles, selections, enabled));
    assertTrue(profiles.isEmpty());
    assertTrue(selections.isEmpty());
    assertTrue(enabled.isEmpty());
  }

  @Test
  void malformedEnabledFlagDoesNotPartiallyLoadPreferences() throws Exception {
    var path = directory.resolve("preferences.properties");
    Files.writeString(path, "profile.example={}\nenabled.example=perhaps\n");
    var profiles = new HashMap<String, String>();
    var selections = new HashMap<String, List<Bounds>>();
    var enabled = new HashSet<String>();
    assertThrows(
        IllegalArgumentException.class,
        () -> WorldStorage.load(path, profiles, selections, enabled));
    assertTrue(profiles.isEmpty());
    assertTrue(selections.isEmpty());
    assertTrue(enabled.isEmpty());
  }

  @Test
  void savesAndReloadsProfilesSelectionsAndEnabledIds() throws Exception {
    var path = directory.resolve("preferences.properties");
    var profile = Map.of("user:build", "{\"id\":\"user:build\"}");
    var bounds =
        List.of(
            new Bounds(
                io.github.billstark001.latticium.dsl.Model.ResourceId.parse("minecraft:overworld"),
                Integer.MIN_VALUE,
                -64,
                3,
                Integer.MAX_VALUE,
                320,
                7));
    WorldStorage.save(path, profile, Map.of("build", bounds), Set.of("user:build"));
    var loadedProfiles = new HashMap<String, String>();
    var loadedSelections = new HashMap<String, List<Bounds>>();
    var loadedEnabled = new HashSet<String>();
    WorldStorage.load(path, loadedProfiles, loadedSelections, loadedEnabled);
    assertEquals(profile, loadedProfiles);
    assertEquals(Map.of("build", bounds), loadedSelections);
    assertEquals(Set.of("user:build"), loadedEnabled);
    WorldStorage.save(path, Map.of("user:new", "{}"), Map.of(), Set.of());
    WorldStorage.load(path, loadedProfiles, loadedSelections, loadedEnabled);
    assertEquals(Map.of("user:new", "{}"), loadedProfiles);
    assertTrue(loadedSelections.isEmpty());
    assertTrue(loadedEnabled.isEmpty());
    try (var files = Files.list(directory)) {
      assertEquals(List.of(path), files.toList());
    }
  }
}

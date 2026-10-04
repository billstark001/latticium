package io.github.billstark001.latticium.mc;

import io.github.billstark001.latticium.dsl.Model.ResourceId;
import io.github.billstark001.latticium.planning.SectionScanner.Bounds;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import net.minecraft.client.Minecraft;

/** Per-server, per-dimension profile and selection preferences. No job starts when loaded. */
final class WorldStorage {
  private WorldStorage() {}

  static Path path(Minecraft minecraft) {
    if (minecraft.level == null) return null;
    String server;
    if (minecraft.getCurrentServer() != null) server = minecraft.getCurrentServer().ip;
    else if (minecraft.getSingleplayerServer() != null)
      server = "singleplayer:" + minecraft.getSingleplayerServer().getWorldData().getLevelName();
    else return null;
    var dimension = minecraft.level.dimension().identifier().toString();
    try {
      var bytes =
          MessageDigest.getInstance("SHA-256").digest(server.getBytes(StandardCharsets.UTF_8));
      var key = HexFormat.of().formatHex(bytes);
      return minecraft
          .gameDirectory
          .toPath()
          .resolve("config/latticium/worlds")
          .resolve(key)
          .resolve(dimension.replace(':', '_') + ".properties");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  static void load(
      Path path,
      Map<String, String> profiles,
      Map<String, List<Bounds>> selections,
      Set<String> enabled) {
    profiles.clear();
    selections.clear();
    enabled.clear();
    if (path == null || !Files.exists(path)) return;
    var values = new Properties();
    try (var input = Files.newInputStream(path)) {
      values.load(input);
    } catch (IOException error) {
      throw new IllegalStateException("Cannot read Latticium preferences: " + path, error);
    }
    for (var name : values.stringPropertyNames()) {
      if (name.startsWith("profile.")) profiles.put(name.substring(8), values.getProperty(name));
      if (name.startsWith("selection."))
        selections.put(name.substring(10), parseBounds(values.getProperty(name)));
      if (name.startsWith("enabled.") && Boolean.parseBoolean(values.getProperty(name)))
        enabled.add(name.substring(8));
    }
  }

  static void save(
      Path path,
      Map<String, String> profiles,
      Map<String, List<Bounds>> selections,
      Set<String> enabled) {
    if (path == null) return;
    var values = new Properties();
    profiles.forEach((name, json) -> values.setProperty("profile." + name, json));
    selections.forEach(
        (name, bounds) -> values.setProperty("selection." + name, encodeBounds(bounds)));
    enabled.forEach(name -> values.setProperty("enabled." + name, "true"));
    try {
      Files.createDirectories(path.getParent());
      var temporary = path.resolveSibling(path.getFileName() + ".part");
      try {
        try (var output = Files.newOutputStream(temporary)) {
          values.store(output, "Latticium client preferences");
        }
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
      } finally {
        Files.deleteIfExists(temporary);
      }
    } catch (IOException error) {
      throw new IllegalStateException("Cannot write Latticium preferences: " + path, error);
    }
  }

  private static String encodeBounds(List<Bounds> bounds) {
    var entries = new ArrayList<String>();
    for (var box : bounds)
      entries.add(
          box.dimension()
              + ","
              + box.minX()
              + ","
              + box.minY()
              + ","
              + box.minZ()
              + ","
              + box.maxX()
              + ","
              + box.maxY()
              + ","
              + box.maxZ());
    return String.join(";", entries);
  }

  private static List<Bounds> parseBounds(String encoded) {
    var result = new ArrayList<Bounds>();
    if (encoded.isEmpty()) return List.of();
    try {
      for (var entry : encoded.split(";")) {
        var parts = entry.split(",", -1);
        if (parts.length != 7) throw new IllegalArgumentException("Invalid selection");
        result.add(
            new Bounds(
                ResourceId.parse(parts[0]),
                Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2]),
                Integer.parseInt(parts[3]),
                Integer.parseInt(parts[4]),
                Integer.parseInt(parts[5]),
                Integer.parseInt(parts[6])));
      }
    } catch (RuntimeException error) {
      throw new IllegalArgumentException("Invalid stored Latticium selection", error);
    }
    return List.copyOf(result);
  }
}

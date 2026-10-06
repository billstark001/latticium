import io.github.billstark001.latticium.dsl.Model.*;
import io.github.billstark001.latticium.mc.*;
import io.github.billstark001.latticium.planning.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/** Reads user fixtures; never invokes a schematic save or file-write method. */
final class RealBlueprintAudit {
  private final Minecraft mc = Minecraft.getInstance();
  private final LatticiumClient.BlueprintProvider bridge;
  private final Object manager;
  private final Path folder;
  private final List<Path> samples;
  private final Path output;
  private final StringBuilder report = new StringBuilder();
  private Object placement;
  private Host.SessionId session;
  private long readyAt;
  private int next;
  private int exactCells;
  private final int fileCount;

  static Object call(Object receiver, String name, Object... args) throws Exception {
    return AuditProbe.call(receiver, name, args);
  }

  static void check(boolean good, String message) {
    if (!good) throw new AssertionError(message);
  }

  RealBlueprintAudit(
      LatticiumClient.BlueprintProvider bridge, Object manager, Path folder, Path output)
      throws Exception {
    this.bridge = bridge;
    this.manager = manager;
    this.folder = folder;
    this.output = output;
    List<Path> files;
    try (var stream = Files.walk(folder)) {
      files =
          stream
              .filter(Files::isRegularFile)
              .filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".litematic"))
              .sorted()
              .toList();
    }
    fileCount = files.size();
    check(fileCount > 0, "No .litematic fixtures found");
    int regions = 0, cells = 0;
    var uniqueStates = new HashSet<BlockState>();
    var random = new Random(10062026);
    for (var file : files) {
      Object schematic = load(file);
      var areas = (Map<?, ?>) call(schematic, "getAreaSizes");
      regions += areas.size();
      for (var entry : areas.entrySet()) {
        var container = call(schematic, "getSubRegionContainer", entry.getKey());
        var size = (net.minecraft.core.Vec3i) call(container, "getSize");
        for (int i = 0; i < 100; i++) {
          var state =
              (net.minecraft.world.level.block.state.BlockState)
                  call(
                      container,
                      "get",
                      random.nextInt(size.getX()),
                      random.nextInt(size.getY()),
                      random.nextInt(size.getZ()));
          var neutral = MinecraftStateCodec.state(state);
          check(
              MinecraftStateCodec.state(neutral).orElseThrow() == state,
              "Real palette state roundtrip failed: " + file.getFileName());
          uniqueStates.add(neutral);
          cells++;
        }
      }
      report
          .append("READ ")
          .append(file.getFileName())
          .append(" regions=")
          .append(areas.size())
          .append("\n");
    }
    var chosen = new ArrayList<>(files);
    Collections.shuffle(chosen, new Random(10062026));
    samples = List.copyOf(chosen.subList(0, Math.min(12, chosen.size())));
    report
        .append("Read ")
        .append(files.size())
        .append(" files / ")
        .append(regions)
        .append(" regions; ")
        .append(cells)
        .append(" sampled palette cells / ")
        .append(uniqueStates.size())
        .append(" distinct exact states.\n");
    Files.writeString(output.resolve("real-schematics.txt"), report);
  }

  private Object load(Path file) throws Exception {
    Object schematic =
        call(
            Class.forName("fi.dy.masa.litematica.schematic.LitematicaSchematic"),
            "createFromFile",
            file.getParent(),
            file.getFileName().toString());
    check(schematic != null, "Read-only schematic loading failed: " + file.getFileName());
    return schematic;
  }

  boolean tick() throws Exception {
    if (placement == null) {
      if (next >= samples.size()) {
        check(exactCells > 0, "No loaded real blueprint cells checked");
        return true;
      }
      var file = samples.get(next);
      placement =
          call(
              Class.forName("fi.dy.masa.litematica.schematic.placement.SchematicPlacement"),
              "createFor",
              load(file),
              BlockPos.ZERO,
              "Read-only audit " + next,
              true,
              true);
      call(placement, "setShouldBeSaved", false);
      var rotations = net.minecraft.world.level.block.Rotation.values();
      var mirrors = net.minecraft.world.level.block.Mirror.values();
      call(placement, "setRotation", rotations[next % rotations.length], null);
      call(placement, "setMirror", mirrors[next % mirrors.length], null);
      var enabled =
          (Map<?, ?>)
              call(
                  placement,
                  "getSubRegionBoxes",
                  Class.forName(
                          "fi.dy.masa.litematica.schematic.placement.SubRegionPlacement$RequiredEnabled")
                      .getField("PLACEMENT_ENABLED")
                      .get(null));
      var corner = (BlockPos) call(enabled.values().iterator().next(), "getPos1");
      var feet = mc.player.blockPosition();
      call(
          placement,
          "setOrigin",
          new BlockPos(
              feet.getX() + 3 - corner.getX(),
              feet.getY() + 2 - corner.getY(),
              feet.getZ() - corner.getZ()),
          null);
      call(manager, "addSchematicPlacement", placement, true);
      call(manager, "setSelectedSchematicPlacement", placement);
      session = new Host.SessionId();
      check(!bridge.finiteBounds("active_blueprint", session).isEmpty(), "Real bounds missing");
      readyAt = System.currentTimeMillis() + 2000;
      return false;
    }
    if (System.currentTimeMillis() < readyAt) return false;
    var bounds = bridge.finiteBounds("active_blueprint", session);
    var world =
        (net.minecraft.world.level.Level)
            call(
                Class.forName("fi.dy.masa.litematica.world.SchematicWorldHandler"),
                "getSchematicWorld");
    int checked = 0, unknown = 0;
    for (var box : bounds) {
      int x = box.minX(), y = box.minY(), z = box.minZ();
      var window =
          new SectionScanner.Bounds(
              box.dimension(),
              x,
              y,
              z,
              Math.min(box.maxX(), x + 3),
              Math.min(box.maxY(), y + 3),
              Math.min(box.maxZ(), z + 3));
      var slice = bridge.slice(window, session).orElseThrow();
      for (int zz = window.minZ(); zz <= window.maxZ(); zz++)
        for (int yy = window.minY(); yy <= window.maxY(); yy++)
          for (int xx = window.minX(); xx <= window.maxX(); xx++) {
            var p = new Position(box.dimension(), xx, yy, zz);
            var cell = bridge.target(p, session);
            check(cell.equals(slice.target(p)), "Real batch/point mismatch");
            if (cell instanceof TargetCell.Exact exact) {
              check(
                  world.getChunkSource().hasChunk(xx >> 4, zz >> 4),
                  "Exact cell in unloaded schematic chunk");
              check(
                  exact
                      .state()
                      .equals(
                          MinecraftStateCodec.state(world.getBlockState(new BlockPos(xx, yy, zz)))),
                  "Real source properties were lost");
              exactCells++;
              checked++;
            } else if (cell instanceof TargetCell.Unknown) unknown++;
            else throw new AssertionError("Inside real placement was DontCare/Clear");
          }
    }
    String json =
        "{\"schema\":1,\"id\":\"test:readonly\",\"scope\":\"selection(\\\"active_blueprint\\\")\",\"select\":{\"where\":\"matches_target()\"},\"target\":{\"source\":\"latticium:active_blueprint\"}}";
    var preview = new ClientJob(mc, session, json, bridge, Map.of("active_blueprint", bounds));
    try {
      preview.preview(2);
      check(preview.snapshot().submittedActions() == 0, "Read-only preview submitted action");
    } finally {
      preview.leaveWorld();
    }
    call(manager, "removeSchematicPlacement", placement);
    check(
        bridge.target(
                new Position(
                    bounds.getFirst().dimension(),
                    bounds.getFirst().minX(),
                    bounds.getFirst().minY(),
                    bounds.getFirst().minZ()),
                session)
            instanceof TargetCell.Unknown,
        "Removed real placement stayed valid");
    report
        .append("PLACE ")
        .append(samples.get(next).getFileName())
        .append(" rotation=")
        .append(next % 4)
        .append(" mirror=")
        .append(next % 3)
        .append(" exact=")
        .append(checked)
        .append(" unknown=")
        .append(unknown)
        .append("; preview and removal passed\n");
    Files.writeString(output.resolve("real-schematics.txt"), report);
    System.out.println(
        "LATTICIUM_REAL_SCHEMATIC "
            + samples.get(next).getFileName()
            + " exact="
            + checked
            + " unknown="
            + unknown);
    placement = null;
    next++;
    return false;
  }

  String summary() {
    return "read-only real schematics: "
        + fileCount
        + " files loaded, "
        + samples.size()
        + " rotated/mirrored placement previews, "
        + exactCells
        + " exact bridge cells checked";
  }
}

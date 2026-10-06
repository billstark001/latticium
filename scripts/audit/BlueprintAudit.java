import io.github.billstark001.latticium.dsl.FactDependencies;
import io.github.billstark001.latticium.dsl.FactDependencies.Fact;
import io.github.billstark001.latticium.dsl.Model.*;
import io.github.billstark001.latticium.mc.*;
import io.github.billstark001.latticium.planning.*;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/** Source-mod fixture that exercises actual placement and target-provider APIs. */
final class BlueprintAudit {
  private final AuditProbe probe;
  private final Minecraft minecraft;
  private final Position position;
  private final BlockPos target;
  private Object schematic, placement, manager;
  private LatticiumClient.BlueprintProvider bridge;
  private Host.SessionId blueprintSession;
  private BlockPos blueprintOrigin;

  BlueprintAudit(AuditProbe probe, Minecraft minecraft, Position position, BlockPos target) {
    this.probe = probe;
    this.minecraft = minecraft;
    this.position = position;
    this.target = target;
  }

  LatticiumClient.BlueprintProvider provider() {
    return bridge;
  }

  Object manager() {
    return manager;
  }

  private static Object call(Object receiver, String name, Object... args) throws Exception {
    return AuditProbe.call(receiver, name, args);
  }

  boolean create() throws Exception {
    Class<?> data;
    try {
      data = Class.forName("fi.dy.masa.litematica.data.DataManager");
    } catch (ClassNotFoundException unavailable) {
      return false;
    }
    probe.check(
        LatticiumClient.get()
            .uiState()
            .blueprintProviders()
            .contains(ResourceId.parse("latticium:active_blueprint")),
        "Blueprint bridge did not register");
    // Inspect the registered instance: NeoForge entrypoint construction registers the mod,
    // so constructing a second instance would invoke initialization twice.
    var providerField = LatticiumClient.class.getDeclaredField("providers");
    providerField.setAccessible(true);
    Object providers = providerField.get(LatticiumClient.get());
    var lookup = providers.getClass().getDeclaredMethod("blueprint", ResourceId.class);
    lookup.setAccessible(true);
    bridge =
        (LatticiumClient.BlueprintProvider)
            lookup.invoke(providers, ResourceId.parse("latticium:active_blueprint"));
    manager = call(data, "getSchematicPlacementManager");
    Object area =
        Class.forName("fi.dy.masa.litematica.selection.AreaSelection")
            .getConstructor()
            .newInstance();
    call(area, "setName", "Audit memory blueprint");
    call(area, "setExplicitOrigin", BlockPos.ZERO);
    Object box =
        Class.forName("fi.dy.masa.litematica.selection.Box")
            .getConstructor(BlockPos.class, BlockPos.class, String.class)
            .newInstance(BlockPos.ZERO, new BlockPos(1, 0, 0), "audit");
    call(area, "addSubRegionBox", box, true);
    schematic =
        call(
            Class.forName("fi.dy.masa.litematica.schematic.LitematicaSchematic"),
            "createEmptySchematic",
            area,
            "Latticium audit");
    Object cells = call(schematic, "getSubRegionContainer", "audit");
    call(cells, "set", 0, 0, 0, Blocks.DIRT.defaultBlockState());
    blueprintOrigin = target.offset(0, 2, 0);
    placement =
        call(
            Class.forName("fi.dy.masa.litematica.schematic.placement.SchematicPlacement"),
            "createFor",
            schematic,
            blueprintOrigin,
            "Audit selected",
            true,
            true);
    call(placement, "setShouldBeSaved", false);
    call(manager, "addSchematicPlacement", placement, true);
    call(manager, "setSelectedSchematicPlacement", placement);
    blueprintSession = new Host.SessionId();
    probe.check(
        !bridge.finiteBounds("active_blueprint", blueprintSession).isEmpty(),
        "Blueprint finite bounds unavailable");
    return true;
  }

  boolean checks() throws Exception {
    var p =
        new Position(
            position.dimension(),
            blueprintOrigin.getX(),
            blueprintOrigin.getY(),
            blueprintOrigin.getZ());
    var first = bridge.target(p, blueprintSession);
    if (!(first instanceof TargetCell.Exact exact)
        || !exact.state().equals(MinecraftStateCodec.state(Blocks.DIRT.defaultBlockState())))
      return false;
    var air = new Position(p.dimension(), p.x() + 1, p.y(), p.z());
    probe.check(
        bridge.target(air, blueprintSession) instanceof TargetCell.Exact empty
            && io.github.billstark001.latticium.dsl.Model.isVanillaAir(empty.state()),
        "Known blueprint air was not exact");
    var outside = new Position(p.dimension(), p.x() - 1, p.y(), p.z());
    probe.check(
        bridge.target(outside, blueprintSession) instanceof TargetCell.DontCare,
        "Outside blueprint not DontCare");
    var window =
        new SectionScanner.Bounds(p.dimension(), p.x() - 1, p.y(), p.z(), p.x() + 2, p.y(), p.z());
    var slice = bridge.slice(window, blueprintSession).orElseThrow();
    for (int x = window.minX(); x <= window.maxX(); x++) {
      var cell = new Position(p.dimension(), x, p.y(), p.z());
      probe.check(
          bridge.target(cell, blueprintSession).equals(slice.target(cell)),
          "Blueprint batch/point mismatch");
    }
    var previewSession = new Host.SessionId();
    bridge.finiteBounds("active_blueprint", previewSession);
    probe.check(bridge.target(p, blueprintSession).equals(first), "Preview replaced job snapshot");
    var key =
        new Host.SectionCaptureKey(
            blueprintSession,
            new SectionScanner.SectionKey(p.dimension(), p.x() >> 4, p.y() >> 4, p.z() >> 4));
    var epochs = new Host.Epochs(0, 0, 0, 0, 0, 0);
    var batch =
        new MinecraftSectionSource(
            minecraft,
            blueprintSession,
            epochs,
            bridge,
            Map.of(),
            FactDependencies.of(Fact.TARGET));
    var points =
        new MinecraftSectionSource(
            minecraft,
            blueprintSession,
            epochs,
            (pos, session) -> bridge.target(pos, session),
            Map.of(),
            FactDependencies.of(Fact.TARGET));
    CaptureAudit.benchmark(probe, "blueprint batch", batch, key);
    CaptureAudit.benchmark(probe, "blueprint points", points, key);
    Object conflict =
        call(
            Class.forName("fi.dy.masa.litematica.schematic.placement.SchematicPlacement"),
            "createFor",
            schematic,
            blueprintOrigin,
            "Audit conflict",
            true,
            true);
    call(conflict, "setShouldBeSaved", false);
    call(manager, "addSchematicPlacement", conflict, false);
    probe.check(
        bridge.target(p, blueprintSession) instanceof TargetCell.Unknown,
        "Overlap was not Unknown");
    call(manager, "setSelectedSchematicPlacement", conflict);
    probe.check(
        bridge.target(p, blueprintSession) instanceof TargetCell.Unknown,
        "Placement switch reused session");
    probe.check(
        bridge.finiteBounds("active_blueprint", blueprintSession).isEmpty(),
        "Repeated finiteBounds rebased old session");
    call(manager, "removeSchematicPlacement", conflict);
    call(manager, "setSelectedSchematicPlacement", placement);
    call(placement, "setOrigin", new BlockPos(1_000_000, p.y(), 1_000_000), null);
    probe.check(
        bridge.target(p, blueprintSession) instanceof TargetCell.Unknown,
        "Bounds change reused session");
    var farSession = new Host.SessionId();
    bridge.finiteBounds("active_blueprint", farSession);
    var far = new Position(p.dimension(), 1_000_000, p.y(), 1_000_000);
    probe.check(
        bridge.target(far, farSession) instanceof TargetCell.Unknown,
        "Unloaded schematic became exact air");
    call(manager, "removeSchematicPlacement", placement);
    probe.check(
        bridge.target(far, farSession) instanceof TargetCell.Unknown,
        "Removed placement reused targets");
    probe.pass(
        "active in-memory blueprint: exact dirt/air, outside DontCare, batch parity, overlap, preview isolation, placement switch/removal/bounds change, unloaded target Unknown");
    return true;
  }
}

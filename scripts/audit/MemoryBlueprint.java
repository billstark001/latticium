import io.github.billstark001.latticium.dsl.Model.ResourceId;
import io.github.billstark001.latticium.mc.LatticiumClient;
import io.github.billstark001.latticium.planning.Host;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/** Unsaved in-memory real source-mod fixture; never writes the user's schematic library. */
final class MemoryBlueprint {
  final LatticiumClient.BlueprintProvider provider;
  private Object manager, placement, schematic;

  MemoryBlueprint(BlockPos origin, int width, int depth) throws Exception {
    if (!LatticiumClient.get()
        .uiState()
        .blueprintProviders()
        .contains(ResourceId.parse("latticium:active_blueprint"))) {
      provider = null;
      return;
    }
    var field = LatticiumClient.class.getDeclaredField("providers");
    field.setAccessible(true);
    var providers = field.get(LatticiumClient.get());
    var lookup = providers.getClass().getDeclaredMethod("blueprint", ResourceId.class);
    lookup.setAccessible(true);
    provider =
        (LatticiumClient.BlueprintProvider)
            lookup.invoke(providers, ResourceId.parse("latticium:active_blueprint"));
    manager =
        call(
            Class.forName("fi.dy.masa.litematica.data.DataManager"),
            "getSchematicPlacementManager");
    var area =
        Class.forName("fi.dy.masa.litematica.selection.AreaSelection")
            .getConstructor()
            .newInstance();
    call(area, "setName", "Latticium capacity acceptance");
    call(area, "setExplicitOrigin", BlockPos.ZERO);
    var box =
        Class.forName("fi.dy.masa.litematica.selection.Box")
            .getConstructor(BlockPos.class, BlockPos.class, String.class)
            .newInstance(BlockPos.ZERO, new BlockPos(width - 1, 0, depth - 1), "capacity");
    call(area, "addSubRegionBox", box, true);
    schematic =
        call(
            Class.forName("fi.dy.masa.litematica.schematic.LitematicaSchematic"),
            "createEmptySchematic",
            area,
            "Latticium audit");
    var cells = call(schematic, "getSubRegionContainer", "capacity");
    for (int x = 0; x < width; x++)
      for (int z = 0; z < depth; z++) call(cells, "set", x, 0, z, Blocks.DIRT.defaultBlockState());
    placement =
        call(
            Class.forName("fi.dy.masa.litematica.schematic.placement.SchematicPlacement"),
            "createFor",
            schematic,
            origin,
            "Capacity acceptance",
            true,
            true);
    call(placement, "setShouldBeSaved", false);
    call(manager, "addSchematicPlacement", placement, true);
    call(manager, "setSelectedSchematicPlacement", placement);
  }

  Host.TargetSource source(Host.SessionId session) {
    if (provider == null) return null;
    provider.finiteBounds("active_blueprint", session);
    return provider;
  }

  Object metadata() throws Exception {
    var data = call(schematic, "getMetadata");
    return java.util.List.of(call(data, "getTimeModified"), call(data, "wasModifiedSinceSaved"));
  }

  void editFirstCell(net.minecraft.world.level.block.state.BlockState state) throws Exception {
    call(call(schematic, "getSubRegionContainer", "capacity"), "set", 0, 0, 0, state);
    call(manager, "markChunksForRebuild", placement);
  }

  public void close() throws Exception {
    if (placement != null) call(manager, "removeSchematicPlacement", placement);
  }

  private static Object call(Object receiver, String method, Object... args) throws Exception {
    return AuditProbe.call(receiver, method, args);
  }
}

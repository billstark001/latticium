package io.github.billstark001.latticium.bridge;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement.RequiredEnabled;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import io.github.billstark001.latticium.dsl.Model.*;
import io.github.billstark001.latticium.mc.LatticiumClient;
import io.github.billstark001.latticium.mc.MinecraftStateCodec;
import io.github.billstark001.latticium.planning.Host;
import io.github.billstark001.latticium.planning.SectionScanner.Bounds;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/** Reads the selected, already loaded Litematica placement without importing schematic files. */
public final class LitematicaBridge
    implements ClientModInitializer, LatticiumClient.BlueprintProvider {
  private UUID selectedId;
  private ResourceId dimension;
  private List<Bounds> selectedBounds = List.of();

  @Override
  public void onInitializeClient() {
    LatticiumClient.get()
        .registerBlueprintProvider(ResourceId.parse("latticium:active_blueprint"), this);
  }

  @Override
  public List<Bounds> finiteBounds(String name, Host.SessionId session) {
    if (!name.equals("active_blueprint")) return List.of();
    var minecraft = Minecraft.getInstance();
    if (!minecraft.isSameThread() || minecraft.level == null) return List.of();
    var selected = selected();
    if (selected == null) return List.of();
    var bounds = new ArrayList<Bounds>();
    var worldId = MinecraftStateCodec.id(minecraft.level.dimension().identifier());
    for (var box : selected.getSubRegionBoxes(RequiredEnabled.PLACEMENT_ENABLED).values()) {
      if (box.getPos1() == null || box.getPos2() == null) continue;
      bounds.add(bounds(worldId, box));
    }
    if (!bounds.isEmpty()) {
      selectedId = selected.getHashId();
      dimension = worldId;
      selectedBounds = List.copyOf(bounds);
    }
    return List.copyOf(bounds);
  }

  @Override
  public TargetCell target(Position pos, Host.SessionId session) {
    var minecraft = Minecraft.getInstance();
    if (!minecraft.isSameThread() || minecraft.level == null)
      return new TargetCell.Unknown("No client world");
    var selected = selected();
    if (selected == null || selectedId == null || !selectedId.equals(selected.getHashId()))
      return new TargetCell.Unknown("Active placement changed");
    if (!pos.dimension().equals(dimension))
      return new TargetCell.Unknown("Blueprint dimension changed");
    var currentBounds = new ArrayList<Bounds>();
    for (var box : selected.getSubRegionBoxes(RequiredEnabled.PLACEMENT_ENABLED).values()) {
      if (box.getPos1() != null && box.getPos2() != null) currentBounds.add(bounds(dimension, box));
    }
    if (!currentBounds.equals(selectedBounds))
      return new TargetCell.Unknown("Active placement bounds changed");
    var blockPos = new BlockPos(pos.x(), pos.y(), pos.z());
    int inside = 0;
    for (var box : selected.getSubRegionBoxes(RequiredEnabled.PLACEMENT_ENABLED).values())
      if (contains(box, blockPos)) inside++;
    if (inside == 0) return new TargetCell.DontCare();
    if (inside > 1) return new TargetCell.Unknown("Overlapping blueprint subregions");
    for (var placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()) {
      if (placement == selected || !placement.isEnabled()) continue;
      for (var box : placement.getSubRegionBoxes(RequiredEnabled.PLACEMENT_ENABLED).values())
        if (contains(box, blockPos)) return new TargetCell.Unknown("Overlapping active placements");
    }
    var world = SchematicWorldHandler.getSchematicWorld();
    if (world == null || !world.hasChunk(pos.x() >> 4, pos.z() >> 4))
      return new TargetCell.Unknown("Schematic chunk unavailable");
    return new TargetCell.Exact(MinecraftStateCodec.state(world.getBlockState(blockPos)));
  }

  private static SchematicPlacement selected() {
    var manager = DataManager.getSchematicPlacementManager();
    var selected = manager == null ? null : manager.getSelectedSchematicPlacement();
    return selected != null && selected.isEnabled() ? selected : null;
  }

  private static Bounds bounds(ResourceId dimension, Box box) {
    var a = box.getPos1();
    var b = box.getPos2();
    return new Bounds(
        dimension,
        Math.min(a.getX(), b.getX()),
        Math.min(a.getY(), b.getY()),
        Math.min(a.getZ(), b.getZ()),
        Math.max(a.getX(), b.getX()),
        Math.max(a.getY(), b.getY()),
        Math.max(a.getZ(), b.getZ()));
  }

  private static boolean contains(Box box, BlockPos pos) {
    if (box.getPos1() == null || box.getPos2() == null) return false;
    var a = box.getPos1();
    var b = box.getPos2();
    return pos.getX() >= Math.min(a.getX(), b.getX())
        && pos.getX() <= Math.max(a.getX(), b.getX())
        && pos.getY() >= Math.min(a.getY(), b.getY())
        && pos.getY() <= Math.max(a.getY(), b.getY())
        && pos.getZ() >= Math.min(a.getZ(), b.getZ())
        && pos.getZ() <= Math.max(a.getZ(), b.getZ());
  }
}

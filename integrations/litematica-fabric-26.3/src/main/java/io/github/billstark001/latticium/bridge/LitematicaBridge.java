package io.github.billstark001.latticium.bridge;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement.RequiredEnabled;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import io.github.billstark001.latticium.dsl.Model.*;
import io.github.billstark001.latticium.mc.LatticiumClient;
import io.github.billstark001.latticium.mc.MinecraftStateCodec;
import io.github.billstark001.latticium.planning.BlueprintSnapshots;
import io.github.billstark001.latticium.planning.Host;
import io.github.billstark001.latticium.planning.SectionScanner.Bounds;
import io.github.billstark001.latticium.planning.TargetSlice;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/** Reads the selected, already loaded Litematica placement without importing schematic files. */
public final class LitematicaBridge
    implements ClientModInitializer, LatticiumClient.BlueprintProvider {
  private final BlueprintSnapshots snapshots =
      new BlueprintSnapshots("active_blueprint", LitematicaBridge::currentView);

  @Override
  public void onInitializeClient() {
    LatticiumClient.get()
        .registerBlueprintProvider(ResourceId.parse("latticium:active_blueprint"), this);
  }

  @Override
  public List<Bounds> finiteBounds(String name, Host.SessionId session) {
    return snapshots.finiteBounds(name, session);
  }

  @Override
  public TargetCell target(Position position, Host.SessionId session) {
    return snapshots.target(position, session);
  }

  @Override
  public Optional<TargetSlice> slice(Bounds bounds, Host.SessionId session) {
    return snapshots.slice(bounds, session);
  }

  private static BlueprintSnapshots.View currentView() {
    var minecraft = Minecraft.getInstance();
    if (!minecraft.isSameThread() || minecraft.level == null) return null;
    var selected = selected();
    if (selected == null) return null;
    var dimension = MinecraftStateCodec.id(minecraft.level.dimension().identifier());
    var bounds = placementBounds(dimension, selected);
    var conflicts = new ArrayList<Bounds>();
    for (var placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements())
      if (placement != selected && placement.isEnabled())
        conflicts.addAll(placementBounds(dimension, placement));
    var world = SchematicWorldHandler.getSchematicWorld();
    return new BlueprintSnapshots.View(
        minecraft.level,
        selected,
        selected.getHashId(),
        dimension,
        bounds,
        conflicts,
        position -> {
          if (world == null
              || !world.getChunkSource().hasChunk(position.x() >> 4, position.z() >> 4))
            return new TargetCell.Unknown("Schematic chunk unavailable");
          return new TargetCell.Exact(
              MinecraftStateCodec.state(
                  world.getBlockState(new BlockPos(position.x(), position.y(), position.z()))));
        });
  }

  private static List<Bounds> placementBounds(ResourceId dimension, SchematicPlacement placement) {
    var bounds = new ArrayList<Bounds>();
    for (var box : placement.getSubRegionBoxes(RequiredEnabled.PLACEMENT_ENABLED).values())
      if (box.getPos1() != null && box.getPos2() != null) bounds.add(bounds(dimension, box));
    return bounds;
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
}

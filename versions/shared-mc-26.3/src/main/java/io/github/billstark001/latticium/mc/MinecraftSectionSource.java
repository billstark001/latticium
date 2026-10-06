package io.github.billstark001.latticium.mc;

import io.github.billstark001.latticium.dsl.FactDependencies;
import io.github.billstark001.latticium.dsl.FactDependencies.Fact;
import io.github.billstark001.latticium.dsl.Model.*;
import io.github.billstark001.latticium.planning.CaptureColumns;
import io.github.billstark001.latticium.planning.CellWindow;
import io.github.billstark001.latticium.planning.FactWindow;
import io.github.billstark001.latticium.planning.Host;
import io.github.billstark001.latticium.planning.SectionScanner.Bounds;
import io.github.billstark001.latticium.planning.SelectionBounds;
import io.github.billstark001.latticium.planning.TargetSlice;
import io.github.billstark001.latticium.planning.TargetSources;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;

/** Captures one section and a bounded halo on the client thread. */
public final class MinecraftSectionSource implements Host.SectionSnapshotSource {
  private final FactDependencies dependencies;
  private final Minecraft minecraft;
  private final ClientLevel world;
  private final Host.SessionId session;
  private final Host.Epochs epochs;
  private final Host.TargetSource targets;
  private final Map<String, List<Bounds>> selections;

  public MinecraftSectionSource(
      Minecraft minecraft,
      Host.SessionId session,
      Host.Epochs epochs,
      Host.TargetSource targets,
      Map<String, List<Bounds>> selections) {
    this(minecraft, session, epochs, targets, selections, FactDependencies.ALL);
  }

  /**
   * Captures only the bound expression's requested columns; unrequested facts remain unavailable.
   */
  public MinecraftSectionSource(
      Minecraft minecraft,
      Host.SessionId session,
      Host.Epochs epochs,
      Host.TargetSource targets,
      Map<String, List<Bounds>> selections,
      FactDependencies dependencies) {
    this.dependencies = java.util.Objects.requireNonNull(dependencies, "dependencies");
    this.minecraft = minecraft;
    world = minecraft.level;
    this.session = session;
    this.epochs = epochs;
    this.targets = targets;
    this.selections =
        dependencies.needs(Fact.SELECTION) ? SelectionBounds.copy(selections) : Map.of();
  }

  @Override
  public Host.Capture capture(Host.SectionCaptureKey key, int halo) {
    if (halo < 0 || halo > 16) return new Host.Capture.Unsupported("Halo exceeds 16");
    long baseX = (long) key.section().x() << 4;
    long baseY = (long) key.section().y() << 4;
    long baseZ = (long) key.section().z() << 4;
    return captureCells(
        key.session(),
        key.section().dimension(),
        baseX,
        baseY,
        baseZ,
        baseX + 15,
        baseY + 15,
        baseZ + 15,
        halo);
  }

  /** Captures only the cells an activation at the player position can read. */
  public Host.Capture captureAround(Host.SessionId requestedSession, Position center, int radius) {
    if (radius < 0 || radius > 16)
      return new Host.Capture.Unsupported("Activation radius exceeds 16");
    return captureCells(
        requestedSession,
        center.dimension(),
        center.x(),
        center.y(),
        center.z(),
        center.x(),
        center.y(),
        center.z(),
        radius);
  }

  private Host.Capture captureCells(
      Host.SessionId requestedSession,
      ResourceId requestedDimension,
      long minX,
      long minY,
      long minZ,
      long maxX,
      long maxY,
      long maxZ,
      int halo) {
    if (!minecraft.isSameThread())
      throw new IllegalStateException("Capture must run on client thread");
    if (!session.equals(requestedSession)) return new Host.Capture.Deferred("Session changed");
    if (minX < Integer.MIN_VALUE
        || minY < Integer.MIN_VALUE
        || minZ < Integer.MIN_VALUE
        || maxX > Integer.MAX_VALUE
        || maxY > Integer.MAX_VALUE
        || maxZ > Integer.MAX_VALUE)
      return new Host.Capture.Unsupported("Capture exceeds coordinate range");
    var level = minecraft.level;
    if (level == null || level != world) return new Host.Capture.Deferred("Client world changed");
    var dimension = MinecraftStateCodec.id(level.dimension().identifier());
    if (!dimension.equals(requestedDimension))
      return new Host.Capture.Deferred("Dimension changed");
    // Missing world columns are Unknown per cell; coordinate/selection branches can still
    // decide a predicate in an unloaded chunk without inventing world facts.
    var evaluated =
        new Bounds(
            dimension, (int) minX, (int) minY, (int) minZ, (int) maxX, (int) maxY, (int) maxZ);
    var allowed =
        new CellWindow(
            new Bounds(
                dimension,
                (int) Math.max(Integer.MIN_VALUE, minX - halo),
                (int) Math.max(Integer.MIN_VALUE, minY - halo),
                (int) Math.max(Integer.MIN_VALUE, minZ - halo),
                (int) Math.min(Integer.MAX_VALUE, maxX + halo),
                (int) Math.min(Integer.MAX_VALUE, maxY + halo),
                (int) Math.min(Integer.MAX_VALUE, maxZ + halo)));
    var window = CaptureColumns.window(allowed, evaluated, dependencies);
    var columns = new CaptureColumns(window, evaluated, dependencies);
    var bounds = window.bounds();
    boolean usesWorld = dependencies.usesWorld();
    var cells = usesWorld ? new WorldCell[window.size()] : null;
    var targetSlice = Optional.<TargetSlice>empty();
    var targetBounds = columns.bounds(Fact.TARGET);
    if (targets != null && targetBounds.isPresent()) {
      targetSlice = TargetSources.checkedSlice(targets, targetBounds.get(), session);
      if (targetSlice.isEmpty())
        targetSlice =
            Optional.of(
                TargetSlice.capture(
                    session,
                    targetBounds.get(),
                    p ->
                        columns.needs(Fact.TARGET, window.index(p))
                            ? targets.target(p, session)
                            : new TargetCell.Unknown("Target not requested")));
    }
    boolean stateOnly =
        dependencies.needs(Fact.STATE)
            && !dependencies.needs(Fact.BIOME)
            && !dependencies.needs(Fact.FLUID)
            && !dependencies.needs(Fact.LIGHT)
            && !dependencies.needs(Fact.SOLID);
    var resourceIds = new HashMap<net.minecraft.resources.Identifier, ResourceId>();
    var statePalette = new HashMap<net.minecraft.world.level.block.state.BlockState, WorldCell>();
    if (usesWorld) {
      int width = bounds.maxX() - bounds.minX() + 1,
          height = bounds.maxY() - bounds.minY() + 1,
          depth = bounds.maxZ() - bounds.minZ() + 1;
      var loaded = new boolean[width * depth];
      for (int z = 0; z < depth; z++)
        for (int x = 0; x < width; x++)
          loaded[z * width + x] =
              level.getChunkSource().hasChunk((bounds.minX() + x) >> 4, (bounds.minZ() + z) >> 4);
      var blockPos = new BlockPos.MutableBlockPos();
      for (int index = columns.nextWorldIndex(0);
          index >= 0;
          index = columns.nextWorldIndex(index + 1)) {
        int x = index % width, y = index / width % height, z = index / (width * height);
        if (!loaded[z * width + x]) continue;
        blockPos.set(bounds.minX() + x, bounds.minY() + y, bounds.minZ() + z);
        boolean stateNeeded = columns.needs(Fact.STATE, index),
            solidNeeded = columns.needs(Fact.SOLID, index);
        var state = stateNeeded || solidNeeded ? level.getBlockState(blockPos) : null;
        if (stateOnly) {
          cells[index] = statePalette.computeIfAbsent(state, MinecraftSectionSource::stateCell);
          continue;
        }
        var biome =
            columns.needs(Fact.BIOME, index)
                ? level
                    .getBiome(blockPos)
                    .unwrapKey()
                    .map(
                        keyId ->
                            resourceIds.computeIfAbsent(
                                keyId.identifier(), MinecraftStateCodec::id))
                    .orElse(null)
                : null;
        var fluid =
            columns.needs(Fact.FLUID, index) ? level.getFluidState(blockPos).getType() : null;
        cells[index] =
            new WorldCell(
                stateNeeded ? MinecraftStateCodec.state(state) : null,
                biome,
                fluid == null
                    ? null
                    : resourceIds.computeIfAbsent(
                        BuiltInRegistries.FLUID.getKey(fluid), MinecraftStateCodec::id),
                columns.needs(Fact.LIGHT, index) ? level.getRawBrightness(blockPos, 0) : null,
                solidNeeded ? state.isCollisionShapeFullBlock(level, blockPos) : null);
      }
    }
    Position player = null;
    Set<ResourceId> inventory = null;
    if (minecraft.player != null) {
      if (dependencies.needs(Fact.PLAYER)) {
        var feet = minecraft.player.blockPosition();
        player = new Position(dimension, feet.getX(), feet.getY(), feet.getZ());
      }
      if (dependencies.needs(Fact.INVENTORY)) {
        inventory = new HashSet<>();
        var stacks = minecraft.player.getInventory();
        for (int i = 0; i < stacks.getContainerSize(); i++) {
          var stack = stacks.getItem(i);
          if (!stack.isEmpty())
            inventory.add(MinecraftStateCodec.id(BuiltInRegistries.ITEM.getKey(stack.getItem())));
        }
      }
    }
    var captured =
        new FactWindow(
            session, window, cells, targetSlice.orElse(null), selections, player, inventory);
    return new Host.Capture.Ready(new Host.Snapshot(session, epochs, captured));
  }

  private static WorldCell stateCell(net.minecraft.world.level.block.state.BlockState state) {
    return new WorldCell(MinecraftStateCodec.state(state), null, null, null, null);
  }
}

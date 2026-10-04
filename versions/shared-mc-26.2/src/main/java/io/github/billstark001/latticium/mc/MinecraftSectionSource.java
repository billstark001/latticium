package io.github.billstark001.latticium.mc;

import io.github.billstark001.latticium.dsl.Model.*;
import io.github.billstark001.latticium.planning.Host;
import io.github.billstark001.latticium.planning.SectionScanner.Bounds;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;

/** Captures one section and a bounded halo on the client thread. */
public final class MinecraftSectionSource implements Host.SectionSnapshotSource {
  private final Minecraft minecraft;
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
    this.minecraft = minecraft;
    this.session = session;
    this.epochs = epochs;
    this.targets = targets;
    this.selections = Map.copyOf(selections);
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
        key.section().x(),
        key.section().z(),
        baseX - halo,
        baseY - halo,
        baseZ - halo,
        baseX + 15 + halo,
        baseY + 15 + halo,
        baseZ + 15 + halo);
  }

  /** Captures only the cells an activation at the player position can read. */
  public Host.Capture captureAround(Host.SessionId requestedSession, Position center, int radius) {
    if (radius < 0 || radius > 16)
      return new Host.Capture.Unsupported("Activation radius exceeds 16");
    return captureCells(
        requestedSession,
        center.dimension(),
        center.x() >> 4,
        center.z() >> 4,
        (long) center.x() - radius,
        (long) center.y() - radius,
        (long) center.z() - radius,
        (long) center.x() + radius,
        (long) center.y() + radius,
        (long) center.z() + radius);
  }

  private Host.Capture captureCells(
      Host.SessionId requestedSession,
      ResourceId requestedDimension,
      int requiredChunkX,
      int requiredChunkZ,
      long minX,
      long minY,
      long minZ,
      long maxX,
      long maxY,
      long maxZ) {
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
    if (level == null) return new Host.Capture.Deferred("No client world");
    var dimension = MinecraftStateCodec.id(level.dimension().identifier());
    if (!dimension.equals(requestedDimension))
      return new Host.Capture.Deferred("Dimension changed");
    if (!level.hasChunk(requiredChunkX, requiredChunkZ)) return new Host.Capture.Unloaded();
    var cells = new HashMap<Position, WorldCell>();
    var goals = new HashMap<Position, TargetCell>();
    for (long z = minZ; z <= maxZ; z++)
      for (long x = minX; x <= maxX; x++) {
        boolean loaded = level.hasChunk((int) x >> 4, (int) z >> 4);
        for (long y = minY; y <= maxY; y++) {
          var pos = new Position(dimension, (int) x, (int) y, (int) z);
          if (targets != null) goals.put(pos, targets.target(pos, session));
          if (!loaded) continue;
          var blockPos = new BlockPos((int) x, (int) y, (int) z);
          var state = level.getBlockState(blockPos);
          var biome = level.getBiome(blockPos).unwrapKey();
          var fluid = level.getFluidState(blockPos).getType();
          cells.put(
              pos,
              new WorldCell(
                  MinecraftStateCodec.state(state),
                  biome.map(keyId -> MinecraftStateCodec.id(keyId.identifier())).orElse(null),
                  MinecraftStateCodec.id(BuiltInRegistries.FLUID.getKey(fluid)),
                  level.getRawBrightness(blockPos, 0),
                  state.isCollisionShapeFullBlock(level, blockPos)));
        }
      }
    Position player = null;
    Set<ResourceId> inventory = null;
    if (minecraft.player != null) {
      var feet = minecraft.player.blockPosition();
      player = new Position(dimension, feet.getX(), feet.getY(), feet.getZ());
      inventory = new HashSet<>();
      var stacks = minecraft.player.getInventory();
      for (int i = 0; i < stacks.getContainerSize(); i++) {
        var stack = stacks.getItem(i);
        if (!stack.isEmpty())
          inventory.add(MinecraftStateCodec.id(BuiltInRegistries.ITEM.getKey(stack.getItem())));
      }
    }
    var captured = new CapturedFacts(cells, goals, selections, player, inventory);
    return new Host.Capture.Ready(new Host.Snapshot(session, epochs, captured));
  }

  private record CapturedFacts(
      Map<Position, WorldCell> cells,
      Map<Position, TargetCell> goals,
      Map<String, List<Bounds>> selections,
      Position playerPosition,
      Set<ResourceId> items)
      implements Facts {
    private CapturedFacts {
      cells = Map.copyOf(cells);
      goals = Map.copyOf(goals);
      selections = Map.copyOf(selections);
      if (items != null) items = Set.copyOf(items);
    }

    @Override
    public Optional<WorldCell> world(Position pos) {
      return Optional.ofNullable(cells.get(pos));
    }

    @Override
    public TargetCell target(Position pos) {
      return goals.getOrDefault(pos, new TargetCell.Unknown("Target not captured"));
    }

    @Override
    public Optional<Position> player() {
      return Optional.ofNullable(playerPosition);
    }

    @Override
    public Optional<Set<ResourceId>> inventory() {
      return Optional.ofNullable(items);
    }

    @Override
    public Truth selection(String name, Position pos) {
      var bounds = selections.get(name);
      if (bounds == null) return Truth.UNKNOWN;
      for (var box : bounds) if (box.contains(pos)) return Truth.TRUE;
      return Truth.FALSE;
    }
  }
}

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
    if (!minecraft.isSameThread())
      throw new IllegalStateException("Capture must run on client thread");
    if (!session.equals(key.session())) return new Host.Capture.Deferred("Session changed");
    if (halo < 0 || halo > 16) return new Host.Capture.Unsupported("Halo exceeds 16");
    var level = minecraft.level;
    if (level == null) return new Host.Capture.Deferred("No client world");
    var dimension = MinecraftStateCodec.id(level.dimension().identifier());
    if (!dimension.equals(key.section().dimension()))
      return new Host.Capture.Deferred("Dimension changed");
    int baseX = key.section().x() << 4;
    int baseY = key.section().y() << 4;
    int baseZ = key.section().z() << 4;
    var cells = new HashMap<Position, WorldCell>();
    var goals = new HashMap<Position, TargetCell>();
    for (int y = baseY - halo; y < baseY + 16 + halo; y++)
      for (int z = baseZ - halo; z < baseZ + 16 + halo; z++)
        for (int x = baseX - halo; x < baseX + 16 + halo; x++) {
          var pos = new Position(dimension, x, y, z);
          if (targets != null) goals.put(pos, targets.target(pos, session));
          if (!level.hasChunk(x >> 4, z >> 4)) continue;
          var blockPos = new BlockPos(x, y, z);
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
      for (var box : bounds)
        if (box.dimension().equals(pos.dimension())
            && pos.x() >= box.minX()
            && pos.x() <= box.maxX()
            && pos.y() >= box.minY()
            && pos.y() <= box.maxY()
            && pos.z() >= box.minZ()
            && pos.z() <= box.maxZ()) return Truth.TRUE;
      return Truth.FALSE;
    }
  }
}

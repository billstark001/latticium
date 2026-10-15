package io.github.billstark001.latticium.mc;

import io.github.billstark001.latticium.dsl.Model.*;
import io.github.billstark001.latticium.planning.Host;
import io.github.billstark001.latticium.planning.Revisions;
import io.github.billstark001.latticium.planning.Revisions.*;
import io.github.billstark001.latticium.planning.SectionScanner.SectionKey;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;

/** Game-thread revision namespace. Coarse epochs are diagnostics; read sets authorize reuse. */
public final class ClientRevisions {
  static final Revisions.Ledger LEDGER = new Revisions.Ledger(65_536);
  static final Object UNAVAILABLE = new Object();
  private static ClientLevel world;
  private static long worldRevision = 1,
      worldGeneration = 1,
      targetRevision = 1,
      selectionRevision = 1,
      inventoryRevision = 1,
      ruleRevision = 1;
  private static List<Object> inventory = List.of();

  private record StackValue(ResourceId item, int count, Object components) {}

  private ClientRevisions() {}

  static void sampleWorld(Minecraft minecraft) {
    if (minecraft.level == world) return;
    world = minecraft.level;
    worldGeneration++;
    worldRevision++;
    inventory = List.of();
    LEDGER.clear();
  }

  static long generation(Minecraft minecraft) {
    sampleWorld(minecraft);
    return worldGeneration;
  }

  static List<Object> inventory(Minecraft minecraft) {
    var result = new ArrayList<Object>();
    if (minecraft.player != null) {
      var stacks = minecraft.player.getInventory();
      for (int i = 0; i < stacks.getContainerSize(); i++) {
        result.add(slot(minecraft, i));
      }
    }
    var value = List.copyOf(result);
    if (!inventory.equals(value)) {
      inventoryRevision++;
      inventory = value;
    }
    return value;
  }

  static Object slot(Minecraft minecraft, int index) {
    if (minecraft.player == null
        || index < 0
        || index >= minecraft.player.getInventory().getContainerSize()) return UNAVAILABLE;
    var stack = minecraft.player.getInventory().getItem(index);
    return new StackValue(
        MinecraftStateCodec.id(BuiltInRegistries.ITEM.getKey(stack.getItem())),
        stack.getCount(),
        stack.getComponentsPatch());
  }

  static long named(Domain domain, String name) {
    var key = new Key(domain, name);
    // Sampling the same marker preserves event-advanced sequence numbers.
    return LEDGER.sample(key, Boolean.TRUE, Reliability.VERSIONED);
  }

  static void edited(String event, String name) {
    LEDGER.event(event, name);
    if (event.equals("selection_edit")) selectionRevision++;
    if (event.equals("rule_edit")) ruleRevision++;
  }

  static void blockUpdated(Position position) {
    worldRevision++;
    LEDGER.event("block_update", position);
    LEDGER.event("block_update", section(position));
  }

  public static void chunkResidencyChanged(int x, int z) {
    worldRevision++;
    // Forgetting a loaded observation invalidates both unload/reload and ABA residency.
    if (world != null) {
      var dimension = MinecraftStateCodec.id(world.dimension().identifier());
      var key = new Key(Domain.RESIDENCY, new Position(dimension, x, 0, z));
      LEDGER.changed(key);
      LEDGER.forget(key);
    }
  }

  static TargetCell target(Host.SessionId session, Position position, TargetCell value) {
    var key = new Key(Domain.TARGET, new Revisions.SourceCell(session, position));
    var before = LEDGER.version(key);
    long after = LEDGER.sample(key, value, Reliability.SHORT_LIVED);
    if (before == null || before.sequence() != after) targetRevision++;
    return value;
  }

  static Host.Epochs epochs(long registry) {
    var minecraft = Minecraft.getInstance();
    sampleWorld(minecraft);
    inventory(minecraft);
    return new Host.Epochs(
        registry,
        worldRevision,
        targetRevision,
        selectionRevision,
        inventoryRevision,
        ruleRevision);
  }

  static Revisions.Stamp snapshotInputs(Host.SessionId session) {
    var minecraft = Minecraft.getInstance();
    var worldKey = new Key(Domain.WORLD, "connection");
    var registryKey = new Key(Domain.REGISTRY, "registry");
    LEDGER.sample(worldKey, generation(minecraft), Reliability.VERSIONED);
    LEDGER.sample(registryKey, ClientRegistryState.epochs().registry(), Reliability.VERSIONED);
    return LEDGER.capture(session, minecraft.level.getGameTime(), List.of(worldKey, registryKey));
  }

  static SectionKey section(Position position) {
    return new SectionKey(
        position.dimension(), position.x() >> 4, position.y() >> 4, position.z() >> 4);
  }
}

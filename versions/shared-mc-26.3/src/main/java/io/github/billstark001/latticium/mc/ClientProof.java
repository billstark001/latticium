package io.github.billstark001.latticium.mc;

import io.github.billstark001.latticium.dsl.FactDependencies;
import io.github.billstark001.latticium.dsl.FactDependencies.Fact;
import io.github.billstark001.latticium.dsl.Model.*;
import io.github.billstark001.latticium.planning.*;
import io.github.billstark001.latticium.planning.Revisions.*;
import io.github.billstark001.latticium.planning.SectionScanner.Bounds;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;

/** One short-lived native prediction read set. No live sampler is exposed to worker planning. */
final class ClientProof {
  private final Minecraft minecraft;
  private final Host.SessionId session;
  private final Map<Key, Supplier<Object>> readers = new LinkedHashMap<>();
  private final Revisions.Stamp stamp;
  private final boolean captureConsistent;

  private record CellRead(Position position, java.util.Set<Domain> columns) {}

  private ClientProof(
      Minecraft minecraft,
      Host.SessionId session,
      Profile.Bound profile,
      Position position,
      Host.TargetSource target) {
    this.minecraft = minecraft;
    this.session = session;
    read(
        Domain.WORLD,
        "connection",
        () -> ClientRevisions.generation(minecraft),
        Reliability.VERSIONED);
    read(
        Domain.REGISTRY,
        "registry",
        () -> ClientRegistryState.epochs().registry(),
        Reliability.VERSIONED);
    long capturedTick = tick(), fence = ClientRevisions.LEDGER.fence();
    var consistency = profile.profile().execution().consistency();
    int slots =
        consistency.inventory() == Execution.Inventory.FULL
                || profile.planningDependencies().needs(Fact.INVENTORY)
            ? minecraft.player.getInventory().getContainerSize()
            : 9;
    for (int i = 0; i < slots; i++) {
      int slot = i;
      read(
          Domain.INVENTORY,
          slot,
          () -> ClientRevisions.slot(minecraft, slot),
          Reliability.SHORT_LIVED);
    }
    read(
        Domain.INVENTORY,
        "selected_slot",
        () -> minecraft.player.getInventory().getSelectedSlot(),
        Reliability.SHORT_LIVED);
    read(
        Domain.PLAYER,
        "context",
        () ->
            minecraft.player == null
                ? ClientRevisions.UNAVAILABLE
                : List.of(
                    minecraft.player.getX(),
                    minecraft.player.getY(),
                    minecraft.player.getZ(),
                    minecraft.player.getYRot(),
                    minecraft.player.getXRot(),
                    minecraft.player.isShiftKeyDown(),
                    minecraft.player.getAbilities().instabuild),
        Reliability.SHORT_LIVED);
    if (consistency.selections() == Execution.Edits.STOP)
      for (var name : SelectionReads.in(profile.profile())) named(Domain.SELECTION, name);
    if (consistency.rules() == Execution.Edits.STOP)
      named(Domain.RULES, profile.profile().id().toString());
    if (target != null) {
      read(
          Domain.TARGET,
          "source/" + session.value(),
          () ->
              target.revision(session).map(Object.class::cast).orElse(ClientRevisions.UNAVAILABLE),
          Reliability.SHORT_LIVED);
      read(
          Domain.TARGET,
          new Revisions.SourceCell(session, position),
          () -> ClientRevisions.target(session, position, target.target(position, session)),
          Reliability.SHORT_LIVED);
    }
    // Native placement reads the target, six possible supports, fluid and collision facts.
    // Opaque third-party getStateForPlacement remains short-lived and is re-run by the gateway.
    var support = FactDependencies.of(Fact.STATE, Fact.FLUID, Fact.SOLID);
    var requirements = profile.planningDependencies().union(support).union(support.adjacent());
    int radius = Math.max(1, profile.planningRadius());
    var center =
        new Bounds(
            position.dimension(),
            position.x(),
            position.y(),
            position.z(),
            position.x(),
            position.y(),
            position.z());
    var allowed =
        new CellWindow(
            new Bounds(
                position.dimension(),
                clip((long) position.x() - radius),
                clip((long) position.y() - radius),
                clip((long) position.z() - radius),
                clip((long) position.x() + radius),
                clip((long) position.y() + radius),
                clip((long) position.z() + radius)));
    var window = CaptureColumns.window(allowed, center, requirements);
    var columns = new CaptureColumns(window, center, requirements);
    for (int index = 0; index < window.size(); index++) {
      var pos = window.position(index);
      var worldColumns = java.util.EnumSet.noneOf(Domain.class);
      for (var fact : requirements.columns().keySet()) {
        if (!columns.needs(fact, index)) continue;
        if (fact == Fact.TARGET) {
          if (target != null)
            read(
                Domain.TARGET,
                new Revisions.SourceCell(session, pos),
                () -> ClientRevisions.target(session, pos, target.target(pos, session)),
                Reliability.SHORT_LIVED);
          continue;
        }
        var domain = Domain.valueOf(fact.name());
        worldColumns.add(domain);
        var chunk = new Position(pos.dimension(), pos.x() >> 4, 0, pos.z() >> 4);
        read(Domain.RESIDENCY, chunk, () -> loaded(pos), Reliability.VERSIONED);
        if (consistency.granularity() == Execution.Granularity.SECTION) {
          var section = ClientRevisions.section(pos);
          var key = new Key(domain, section);
          ClientRevisions.LEDGER.sample(key, Boolean.TRUE, Reliability.VERSIONED);
          readers.putIfAbsent(key, () -> Boolean.TRUE);
        }
      }
      if (!worldColumns.isEmpty()) {
        var cellRead = new CellRead(pos, java.util.Set.copyOf(worldColumns));
        read(
            Domain.STATE,
            cellRead,
            () -> {
              var values = new java.util.EnumMap<Domain, Object>(Domain.class);
              for (var domain : cellRead.columns()) {
                var value = cell(pos, domain);
                values.put(domain, value == null ? ClientRevisions.UNAVAILABLE : value);
              }
              return Map.copyOf(values);
            },
            Reliability.SHORT_LIVED);
      }
    }
    var sampled = ClientRevisions.LEDGER.capture(session, capturedTick, readers.keySet());
    var region =
        new SpatialReads(
            center, requirements, radius, consistency.granularity(), Reliability.SHORT_LIVED);
    captureConsistent = ClientRevisions.LEDGER.eventsUnchanged(fence, session, region);
    stamp = sampled.withSpatial(region);
  }

  static ClientProof capture(
      Minecraft minecraft,
      Host.SessionId session,
      Profile.Bound profile,
      Position position,
      Host.TargetSource targets) {
    return new ClientProof(minecraft, session, profile, position, targets);
  }

  private void named(Domain domain, String name) {
    ClientRevisions.named(domain, name);
    readers.put(new Key(domain, name), () -> Boolean.TRUE);
  }

  private void read(
      Domain domain, Object resource, Supplier<Object> value, Reliability reliability) {
    var key = new Key(domain, resource);
    if (readers.putIfAbsent(key, value) == null)
      ClientRevisions.LEDGER.sample(key, value.get(), reliability);
  }

  Revisions.Stamp stamp() {
    return stamp;
  }

  /** Retain required expression reads and the support/effects declared by the chosen proposal. */
  java.util.Optional<ClientProof> forPlan(
      Profile.Bound profile,
      Position position,
      Planner.Result.Ready plan,
      List<Planner.Proposal> preconditions) {
    var reads = new java.util.ArrayList<>(plan.steps());
    reads.addAll(preconditions);
    var inferred = PredictionReads.in(position, reads);
    if (inferred.isEmpty()) return java.util.Optional.empty();
    var required = inferred.get();
    for (var column : required.dependencies().columns().entrySet()) {
      var domain = Domain.valueOf(column.getKey().name());
      for (var offset : column.getValue().offsets()) {
        Position cell;
        try {
          cell = position.offset(offset.x(), offset.y(), offset.z());
        } catch (ArithmeticException outside) {
          return java.util.Optional.empty();
        }
        if (readers.keySet().stream()
            .noneMatch(
                key ->
                    key.resource() instanceof CellRead read
                        && read.position().equals(cell)
                        && read.columns().contains(domain))) return java.util.Optional.empty();
      }
    }
    var center =
        new Bounds(
            position.dimension(),
            position.x(),
            position.y(),
            position.z(),
            position.x(),
            position.y(),
            position.z());
    var region =
        new SpatialReads(
            center,
            profile.planningDependencies().union(required.dependencies()),
            Math.max(profile.planningRadius(), required.radius()),
            profile.profile().execution().consistency().granularity(),
            Reliability.SHORT_LIVED);
    var selected = new LinkedHashMap<Key, Supplier<Object>>();
    var slots = new java.util.HashSet<Integer>();
    boolean breaking = reads.stream().anyMatch(step -> step.action() == Planner.Action.BREAK);
    for (var step : reads)
      if (step.interaction() != null && step.interaction().inventorySlot() >= 0)
        slots.add(step.interaction().inventorySlot());
    if (breaking && minecraft.player != null)
      slots.add(minecraft.player.getInventory().getSelectedSlot());
    boolean keepInventory =
        profile.planningDependencies().needs(Fact.INVENTORY)
            || profile.profile().execution().consistency().inventory()
                != Execution.Inventory.RELEVANT;
    for (var entry : readers.entrySet()) {
      var key = entry.getKey();
      boolean include = true;
      if (key.domain() == Domain.INVENTORY && key.resource() instanceof Integer slot)
        include = keepInventory || slots.contains(slot);
      else if (key.domain() == Domain.INVENTORY && key.resource().equals("selected_slot"))
        include = breaking;
      else if (key.resource() instanceof CellRead read)
        include =
            read.columns().stream()
                .anyMatch(domain -> region.includes(new Key(domain, read.position())));
      else if (key.resource() instanceof SectionScanner.SectionKey
          || key.domain() == Domain.RESIDENCY) include = region.includes(key);
      else if (key.resource() instanceof Revisions.SourceCell source)
        include = source.position().equals(position) || region.includes(key);
      if (include) selected.put(key, entry.getValue());
    }
    return java.util.Optional.of(new ClientProof(this, selected, region));
  }

  private ClientProof(
      ClientProof original, Map<Key, Supplier<Object>> selected, SpatialReads region) {
    minecraft = original.minecraft;
    session = original.session;
    readers.putAll(selected);
    captureConsistent = original.captureConsistent;
    var versions = new LinkedHashMap<Key, Version>();
    for (var key : selected.keySet()) versions.put(key, original.stamp.reads().get(key));
    stamp =
        new Revisions.Stamp(
            session, original.stamp.tick(), versions, original.stamp.fence(), List.of(region));
  }

  boolean valid() {
    if (!captureConsistent) return false;
    for (var read : readers.entrySet()) {
      var expected = stamp.reads().get(read.getKey());
      ClientRevisions.LEDGER.sample(read.getKey(), read.getValue().get(), expected.reliability());
    }
    return ClientRevisions.LEDGER.valid(stamp, session, tick());
  }

  private long tick() {
    return minecraft.level == null ? -1 : minecraft.level.getGameTime();
  }

  private boolean loaded(Position pos) {
    return minecraft.level != null
        && MinecraftStateCodec.id(minecraft.level.dimension().identifier()).equals(pos.dimension())
        && minecraft.level.getChunkSource().hasChunk(pos.x() >> 4, pos.z() >> 4);
  }

  private Object cell(Position pos, Domain domain) {
    if (!loaded(pos)) return ClientRevisions.UNAVAILABLE;
    var level = minecraft.level;
    var block = new BlockPos(pos.x(), pos.y(), pos.z());
    return switch (domain) {
      case STATE -> MinecraftStateCodec.state(level.getBlockState(block));
      case FLUID ->
          MinecraftStateCodec.id(
              BuiltInRegistries.FLUID.getKey(level.getFluidState(block).getType()));
      case SOLID -> level.getBlockState(block).isCollisionShapeFullBlock(level, block);
      case LIGHT -> level.getRawBrightness(block, 0);
      case BIOME ->
          level
              .getBiome(block)
              .unwrapKey()
              .map(k -> MinecraftStateCodec.id(k.identifier()))
              .orElse(null);
      default -> throw new IllegalArgumentException("Not a world column: " + domain);
    };
  }

  private static int clip(long value) {
    return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value));
  }
}

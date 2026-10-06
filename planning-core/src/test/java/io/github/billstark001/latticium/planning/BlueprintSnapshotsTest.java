package io.github.billstark001.latticium.planning;

import static org.junit.jupiter.api.Assertions.*;

import io.github.billstark001.latticium.dsl.Model.*;
import io.github.billstark001.latticium.planning.SectionScanner.Bounds;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BlueprintSnapshotsTest {
  private static final ResourceId DIM = ResourceId.parse("minecraft:overworld");
  private static final Bounds BOX = new Bounds(DIM, 0, 0, 0, 2, 0, 0);
  private static final BlockState AIR =
      new BlockState(ResourceId.parse("minecraft:air"), java.util.Map.of());

  private static Position p(int x) {
    return new Position(DIM, x, 0, 0);
  }

  private static BlueprintSnapshots.View view(
      Object world,
      Object placement,
      Object revision,
      List<Bounds> bounds,
      List<Bounds> conflicts) {
    return new BlueprintSnapshots.View(
        world,
        placement,
        revision,
        DIM,
        bounds,
        conflicts,
        pos -> pos.x() == 2 ? new TargetCell.Unknown("Unloaded") : new TargetCell.Exact(AIR));
  }

  @Test
  void batchMatchesPointsAndValidatesMetadataOnlyOnce() {
    var current =
        view(
            new Object(),
            new Object(),
            1,
            List.of(BOX),
            List.of(new Bounds(DIM, 1, 0, 0, 1, 0, 0)));
    var calls = new AtomicInteger();
    var snapshots =
        new BlueprintSnapshots(
            "blueprint",
            () -> {
              calls.incrementAndGet();
              return current;
            });
    var session = new Host.SessionId();
    assertEquals(List.of(), snapshots.finiteBounds("other", session));
    assertEquals(0, calls.get());
    assertEquals(List.of(BOX), snapshots.finiteBounds("blueprint", session));
    var window = new Bounds(DIM, -1, 0, 0, 3, 0, 0);
    var slice = snapshots.slice(window, session).orElseThrow();
    assertEquals(2, calls.get());
    for (int x = -1; x <= 3; x++) assertEquals(snapshots.target(p(x), session), slice.target(p(x)));
    assertInstanceOf(TargetCell.Exact.class, slice.target(p(0)));
    assertInstanceOf(TargetCell.DontCare.class, slice.target(p(-1)));
    assertEquals(
        "Overlapping active placements",
        assertInstanceOf(TargetCell.Unknown.class, slice.target(p(1))).reason());
    assertEquals(
        "Unloaded", assertInstanceOf(TargetCell.Unknown.class, slice.target(p(2))).reason());
    assertTrue(snapshots.slice(window, new Host.SessionId()).isEmpty());
  }

  @Test
  void sessionCannotBeRebasedAfterWorldPlacementRevisionOrBoundsChanges() {
    Object world = new Object(), placement = new Object();
    var original = view(world, placement, 1, List.of(BOX), List.of());
    var current = new AtomicReference<>(original);
    var snapshots = new BlueprintSnapshots("blueprint", current::get);
    var session = new Host.SessionId();
    snapshots.finiteBounds("blueprint", session);
    for (var changed :
        List.of(
            view(new Object(), placement, 1, List.of(BOX), List.of()),
            view(world, new Object(), 1, List.of(BOX), List.of()),
            view(world, placement, 2, List.of(BOX), List.of()),
            view(world, placement, 1, List.of(new Bounds(DIM, 0, 0, 0, 3, 0, 0)), List.of()))) {
      current.set(changed);
      assertTrue(snapshots.finiteBounds("blueprint", session).isEmpty());
      assertInstanceOf(TargetCell.Unknown.class, snapshots.target(p(0), session));
      assertTrue(snapshots.slice(BOX, session).isEmpty());
    }
    current.set(null);
    assertInstanceOf(TargetCell.Unknown.class, snapshots.target(p(0), session));
    current.set(original);
    assertInstanceOf(TargetCell.Exact.class, snapshots.target(p(0), session));
  }

  @Test
  void enumerationOrderDoesNotInvalidateButDuplicateRegionsRemainOverlaps() {
    Object world = new Object(), placement = new Object();
    var other = new Bounds(DIM, 5, 0, 0, 6, 0, 0);
    var current =
        new AtomicReference<>(view(world, placement, null, List.of(BOX, other), List.of()));
    var snapshots = new BlueprintSnapshots("blueprint", current::get);
    var session = new Host.SessionId();
    snapshots.finiteBounds("blueprint", session);
    current.set(view(world, placement, null, List.of(other, BOX), List.of()));
    assertInstanceOf(TargetCell.Exact.class, snapshots.target(p(0), session));
    current.set(view(world, placement, null, List.of(BOX, BOX), List.of()));
    var newSession = new Host.SessionId();
    snapshots.finiteBounds("blueprint", newSession);
    assertEquals(
        "Overlapping blueprint subregions",
        assertInstanceOf(TargetCell.Unknown.class, snapshots.target(p(0), newSession)).reason());
  }
}

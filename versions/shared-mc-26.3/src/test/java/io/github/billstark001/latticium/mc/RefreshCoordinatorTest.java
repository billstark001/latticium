package io.github.billstark001.latticium.mc;

import static org.junit.jupiter.api.Assertions.*;

import io.github.billstark001.latticium.dsl.Compiler;
import io.github.billstark001.latticium.dsl.Model.*;
import io.github.billstark001.latticium.dsl.Model.Position;
import io.github.billstark001.latticium.dsl.Model.ResourceId;
import io.github.billstark001.latticium.planning.Host;
import io.github.billstark001.latticium.planning.ProfileReader;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class RefreshCoordinatorTest {
  private static final ResourceId NETHER = ResourceId.parse("minecraft:the_nether");

  @Test
  void blockUpdateQueuesOnlyCandidatesWhoseReadsCanChange() {
    var refresh =
        coordinator(
            "box(0,0,0,4,0,0)",
            "current(b{minecraft:lava}) & offset(1,0,0,solid())",
            "continuous",
            at(0));
    refresh.blockChanged(at(2));
    assertEquals(Set.of(at(1), at(2)), Set.copyOf(refresh.nextDirtyPositions(1, 8)));
    assertEquals(0, refresh.dirtyCount());
  }

  @Test
  void placingAStoneMakesTheNextLavaCellEligible() {
    var reader = new ProfileReader();
    var profile =
        reader.read(
            """
            {"schema":1,"id":"test:frontier","scope":"box(0,0,0,2,0,0)",
             "select":{"where":"current(b{minecraft:lava}) & offset(1,0,0,solid())"},
             "target":{"items":"i{minecraft:stone}"}}
            """);
    var bound = reader.bind(profile, Compiler.symbolic());
    var cells = new HashMap<Position, WorldCell>();
    var lava = new BlockState(ResourceId.parse("minecraft:lava"), Map.of());
    var stone = new BlockState(ResourceId.parse("minecraft:stone"), Map.of());
    cells.put(at(1), new WorldCell(lava, null, null, null, false));
    cells.put(at(2), new WorldCell(lava, null, null, null, false));
    Facts facts =
        new Facts() {
          public Optional<WorldCell> world(Position pos) {
            return Optional.ofNullable(cells.get(pos));
          }

          public TargetCell target(Position pos) {
            return new TargetCell.Unknown("No target");
          }

          public Optional<Position> player() {
            return Optional.of(at(0));
          }

          public Optional<Set<ResourceId>> inventory() {
            return Optional.of(Set.of());
          }

          public Truth selection(String name, Position pos) {
            return Truth.FALSE;
          }
        };
    assertEquals(Truth.FALSE, ClientJob.selectionAt(bound, facts, at(1)));
    cells.put(at(2), new WorldCell(stone, null, null, null, true));
    var refresh = new RefreshCoordinator(bound, new Host.SessionId(), Map.of(), at(0));
    refresh.blockChanged(at(2));
    assertTrue(refresh.nextDirtyPositions(1, 8).contains(at(1)));
    assertEquals(Truth.TRUE, ClientJob.selectionAt(bound, facts, at(1)));
  }

  @Test
  void manualModeIgnoresEventsButExplicitRefreshSchedulesAFullScan() {
    var refresh = coordinator("box(0,0,0,4,0,0)", "all()", "manual", at(0));
    refresh.blockChanged(at(2));
    assertEquals(0, refresh.dirtyCount());
    refresh.requestFullRefresh(at(0));
    assertNotNull(refresh.nextSection(1));
  }

  @Test
  void movingNearParkedWorkAcceleratesDiscoveryWithoutReanchoringAManualScope() {
    var refresh = coordinator("box(0,0,0,48,0,0)", "all()", "manual", at(0));
    while (refresh.scannedSections() < refresh.sectionCount())
      assertNotNull(refresh.nextSection(1));
    refresh.park(at(32));
    assertNull(refresh.nextSection(1));
    refresh.playerMoved(at(33));
    assertEquals(2, refresh.nextSection(2).key().x());
    var relative = coordinator("sphere(player,2)", "all()", "manual", at(0));
    relative.playerMoved(at(48));
    relative.requestFullRefresh(at(48));
    assertTrue(relative.sections().stream().anyMatch(section -> section.key().x() == 3));
  }

  @Test
  void reachableParkedWorkAcrossASectionBoundaryPrecedesDistantRetry() {
    var refresh = coordinator("box(0,0,0,64,0,0)", "all()", "manual", at(0));
    while (refresh.scannedSections() < refresh.sectionCount())
      assertNotNull(refresh.nextSection(1));
    refresh.park(at(64));
    refresh.park(at(16));
    refresh.playerMoved(at(15), 6.5);
    assertEquals(1, refresh.nextSection(20).key().x());
    assertEquals(4, refresh.nextSection(20).key().x());
    assertNull(refresh.nextSection(21));
  }

  @Test
  void playerMovementQueuesOnlyChangedSphereCells() {
    var refresh = coordinator("box(0,0,0,48,0,0)", "sphere(player,2)", "continuous", at(0));
    refresh.playerMoved(at(16));
    assertEquals(
        Set.of(at(0), at(1), at(2), at(14), at(15), at(16), at(17), at(18)),
        Set.copyOf(refresh.nextDirtyPositions(1, 8)));
  }

  @Test
  void MovingSphereScopeReplacesItsFiniteBounds() {
    var refresh = coordinator("sphere(player,2)", "all()", "continuous", at(0));
    refresh.blockChanged(at(0));
    refresh.playerMoved(at(20));
    assertEquals(
        Set.of(1),
        refresh.sections().stream().map(section -> section.key().x()).collect(Collectors.toSet()));
    assertTrue(refresh.nextDirtyPositions(1, 8).stream().allMatch(position -> position.x() >= 18));
  }

  @Test
  void broadPlayerInfluenceStaysInsideFiniteSectionsAtCoordinateEdges() {
    for (int edge : new int[] {Integer.MIN_VALUE, Integer.MAX_VALUE}) {
      int low = edge == Integer.MIN_VALUE ? edge : edge - 15;
      int high = edge == Integer.MIN_VALUE ? edge + 15 : edge;
      var refresh =
          coordinator(
              "box(" + low + ",0,0," + high + ",0,0)", "sphere(player,32)", "continuous", at(edge));
      while (refresh.nextSection(1) != null) {
        /* Finish the initial scan. */
      }
      refresh.playerMoved(at(edge == Integer.MIN_VALUE ? Integer.MAX_VALUE : Integer.MIN_VALUE));
      assertEquals(1, refresh.dirtyCount());
      var dirty = refresh.nextSection(1);
      assertNotNull(dirty);
      assertEquals(Math.floorDiv(edge, 16), dirty.key().x());
      assertNull(refresh.nextSection(1));
    }
  }

  private static RefreshCoordinator coordinator(
      String scope, String select, String mode, Position player) {
    var reader = new ProfileReader();
    var profile =
        reader.read(
            """
            {"schema":1,"id":"test:refresh","scope":"%s",
             "select":{"where":"%s"},"target":{"items":"i{minecraft:stone}"},
             "policy":{"refresh":"%s"}}
            """
                .formatted(scope, select, mode));
    return new RefreshCoordinator(
        reader.bind(profile, Compiler.symbolic()), new Host.SessionId(), Map.of(), player);
  }

  private static Position at(int x) {
    return new Position(NETHER, x, 0, 0);
  }
}

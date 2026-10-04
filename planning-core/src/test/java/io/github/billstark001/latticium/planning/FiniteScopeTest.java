package io.github.billstark001.latticium.planning;

import static org.junit.jupiter.api.Assertions.*;

import io.github.billstark001.latticium.dsl.Model.Position;
import io.github.billstark001.latticium.dsl.Model.ResourceId;
import java.util.List;
import org.junit.jupiter.api.Test;

class FiniteScopeTest {
  private static final ResourceId DIMENSION = ResourceId.parse("minecraft:overworld");
  private static final Position PLAYER = new Position(DIMENSION, 8, 64, 8);

  @Test
  void unionPreservesDisjointSelectionsInsideTheSameSection() {
    var first = new SectionScanner.Bounds(DIMENSION, 1, 64, 1, 1, 64, 1);
    var second = new SectionScanner.Bounds(DIMENSION, 3, 64, 3, 3, 64, 3);
    var session = new Host.SessionId();
    var bounds =
        FiniteScope.bounds(
            "selection(\"a\") | selection(\"b\")",
            PLAYER,
            (name, requested) -> name.equals("a") ? List.of(first) : List.of(second),
            session);
    assertEquals(List.of(first, second), bounds);
  }

  @Test
  void intersectionClipsToTheFiniteSide() {
    var session = new Host.SessionId();
    var box =
        FiniteScope.bounds(
            "box(0,64,0,2,64,2) & current(s{minecraft:air})",
            PLAYER,
            (name, requested) -> List.of(),
            session);
    assertEquals(1, box.size());
    assertEquals(0, box.getFirst().minX());
    assertEquals(2, box.getFirst().maxZ());
  }

  @Test
  void missingSelectionIsNotMistakenForAnUnboundedPredicate() {
    var session = new Host.SessionId();
    var error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                FiniteScope.bounds(
                    "selection(\"missing\") & box(0,64,0,2,64,2)",
                    PLAYER,
                    (name, requested) -> List.of(),
                    session));
    assertTrue(error.getMessage().contains("Selection unavailable or empty: missing"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            FiniteScope.bounds(
                "selection(\"missing\")", PLAYER, (name, requested) -> null, session));
  }

  @Test
  void sphereBoundsReportCoordinateOverflow() {
    var edge = new Position(DIMENSION, Integer.MAX_VALUE, 64, 0);
    var error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                FiniteScope.bounds(
                    "sphere(player,1)",
                    edge,
                    (name, requested) -> List.of(),
                    new Host.SessionId()));
    assertEquals("Sphere bounds exceed coordinate range", error.getMessage());
  }
}

package io.github.billstark001.latticium.planning;

import static org.junit.jupiter.api.Assertions.*;

import io.github.billstark001.latticium.dsl.Model.Position;
import io.github.billstark001.latticium.dsl.Model.ResourceId;
import org.junit.jupiter.api.Test;

class PositionDistancesTest {
  private static final ResourceId DIMENSION = ResourceId.parse("minecraft:overworld");

  @Test
  void extremeCoordinatesRetainExactNearestOrdering() {
    var origin = new Position(DIMENSION, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE);
    var farther = new Position(DIMENSION, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
    var nearer =
        new Position(DIMENSION, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE - 1);
    assertEquals(-1, PositionDistances.squaredIfLong(farther, origin));
    assertTrue(PositionDistances.compare(nearer, farther, origin) < 0);
    assertTrue(PositionDistances.compare(farther, nearer, origin) > 0);
  }

  @Test
  void ordinaryCoordinatesUseLongDistance() {
    var origin = new Position(DIMENSION, 0, 0, 0);
    var x = new Position(DIMENSION, 3, 4, 0);
    var y = new Position(DIMENSION, 0, 0, 6);
    assertEquals(25, PositionDistances.squaredIfLong(x, origin));
    assertTrue(PositionDistances.compare(x, y, origin) < 0);
  }

  @Test
  void crossDimensionDistanceIsUndefined() {
    var origin = new Position(DIMENSION, 0, 0, 0);
    var nether = new Position(ResourceId.parse("minecraft:the_nether"), 0, 0, 0);
    assertThrows(
        IllegalArgumentException.class, () -> PositionDistances.squaredIfLong(nether, origin));
    assertThrows(
        IllegalArgumentException.class, () -> PositionDistances.squaredExact(nether, origin));
    assertThrows(
        IllegalArgumentException.class, () -> PositionDistances.compare(origin, nether, origin));
  }
}

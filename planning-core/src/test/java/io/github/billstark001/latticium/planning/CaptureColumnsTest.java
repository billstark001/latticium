package io.github.billstark001.latticium.planning;

import static io.github.billstark001.latticium.dsl.FactDependencies.Fact.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.billstark001.latticium.dsl.FactDependencies;
import io.github.billstark001.latticium.dsl.Model.*;
import io.github.billstark001.latticium.planning.SectionScanner.Bounds;
import java.util.Random;
import org.junit.jupiter.api.Test;

class CaptureColumnsTest {
  private static final ResourceId DIM = ResourceId.parse("minecraft:overworld");
  private static final Bounds EVALUATED = new Bounds(DIM, 0, 0, 0, 15, 15, 15);
  private static final CellWindow ALLOWED =
      new CellWindow(new Bounds(DIM, -16, -16, -16, 31, 31, 31));

  @Test
  void eachColumnGetsItsOwnMaskAndWorldIterationSkipsGaps() {
    var requirements =
        FactDependencies.of(STATE)
            .shift(16, 0, 0)
            .union(FactDependencies.of(BIOME).shift(-16, 0, 0));
    var window = CaptureColumns.window(ALLOWED, EVALUATED, requirements);
    assertEquals(new Bounds(DIM, -16, 0, 0, 31, 15, 15), window.bounds());
    var columns = new CaptureColumns(window, EVALUATED, requirements);
    int count = 0;
    for (int index = columns.nextWorldIndex(0);
        index >= 0;
        index = columns.nextWorldIndex(index + 1)) {
      var position = window.position(index);
      assertTrue(position.x() < 0 || position.x() > 15);
      assertEquals(position.x() > 15, columns.needs(STATE, index));
      assertEquals(position.x() < 0, columns.needs(BIOME, index));
      count++;
    }
    assertEquals(8192, count);
  }

  @Test
  void surfaceMarksOnlyCenterAndFacesInsteadOfCubeCorners() {
    var requirements = FactDependencies.of(STATE).union(FactDependencies.of(STATE).adjacent());
    var window = CaptureColumns.window(ALLOWED, EVALUATED, requirements);
    assertEquals(new Bounds(DIM, -1, -1, -1, 16, 16, 16), window.bounds());
    var columns = new CaptureColumns(window, EVALUATED, requirements);
    int count = 0;
    for (int index = columns.nextWorldIndex(0);
        index >= 0;
        index = columns.nextWorldIndex(index + 1)) count++;
    assertEquals(4096 + 6 * 256, count);
    assertFalse(columns.needs(STATE, window.index(new Position(DIM, -1, -1, -1))));
  }

  @Test
  void seededOffsetsMatchDirectShiftedBoxMembership() {
    var random = new Random(10062026);
    for (int sample = 0; sample < 50; sample++) {
      int dx = random.nextInt(33) - 16, dy = random.nextInt(33) - 16, dz = random.nextInt(33) - 16;
      var requirements = FactDependencies.of(STATE).shift(dx, dy, dz);
      var columns = new CaptureColumns(ALLOWED, EVALUATED, requirements);
      for (int index = 0; index < ALLOWED.size(); index += 17) {
        var p = ALLOWED.position(index);
        assertEquals(EVALUATED.contains(p.offset(-dx, -dy, -dz)), columns.needs(STATE, index));
      }
    }
  }

  @Test
  void broadReadsKeepTheHaloAndGlobalReadsDoNotAllocateSpatialMasks() {
    var broad = new FactDependencies(java.util.Set.of(STATE));
    assertEquals(ALLOWED, CaptureColumns.window(ALLOWED, EVALUATED, broad));
    var columns = new CaptureColumns(ALLOWED, EVALUATED, broad);
    assertTrue(columns.needs(STATE, ALLOWED.size() - 1));
    var globals = FactDependencies.of(PLAYER, INVENTORY, SELECTION);
    assertEquals(new CellWindow(EVALUATED), CaptureColumns.window(ALLOWED, EVALUATED, globals));
    assertEquals(-1, new CaptureColumns(ALLOWED, EVALUATED, globals).nextWorldIndex(0));
  }

  @Test
  void shiftingNearIntegerLimitsClipsWithoutWrapping() {
    var evaluated = new Bounds(DIM, Integer.MAX_VALUE - 1, 0, 0, Integer.MAX_VALUE, 0, 0);
    var allowed =
        new CellWindow(new Bounds(DIM, Integer.MAX_VALUE - 2, -1, -1, Integer.MAX_VALUE, 1, 1));
    var requests = FactDependencies.of(STATE).shift(1, 0, 0);
    var window = CaptureColumns.window(allowed, evaluated, requests);
    assertEquals(
        new Bounds(DIM, Integer.MAX_VALUE, 0, 0, Integer.MAX_VALUE, 0, 0), window.bounds());
    assertEquals(0, new CaptureColumns(window, evaluated, requests).nextWorldIndex(0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CaptureColumns(
                allowed, new Bounds(ResourceId.parse("test:other"), 0, 0, 0, 0, 0, 0), requests));
  }
}

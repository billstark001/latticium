package io.github.billstark001.latticium.planning;

import static org.junit.jupiter.api.Assertions.*;

import io.github.billstark001.latticium.dsl.Compiler;
import io.github.billstark001.latticium.dsl.Model.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class FiniteScopeSpatialTest {
  private static final ResourceId DIMENSION = ResourceId.parse("minecraft:overworld");
  private static final Position PLAYER = new Position(DIMENSION, 0, 0, 0);
  private static final Host.SessionId SESSION = new Host.SessionId();
  private static final Host.SelectionSource NO_SELECTIONS = (name, session) -> List.of();

  @Test
  void offsetMovesCandidateBoundsInTheOppositeDirectionToItsReads() {
    var bounds =
        FiniteScope.bounds("offset(2,-3,1,box(10,20,30,12,22,32))", PLAYER, NO_SELECTIONS, SESSION);
    assertEquals(List.of(new SectionScanner.Bounds(DIMENSION, 8, 23, 29, 10, 25, 31)), bounds);
  }

  @Test
  void shiftedAndAdjacentDomainsCoverExactlyThePredicatesAfterScanning() {
    var random = new Random(20261006L);
    var compiler = Compiler.symbolic();
    var scanner = new SectionScanner();
    for (int sample = 0; sample < 30; sample++) {
      int x = random.nextInt(9) - 4, y = random.nextInt(9) - 4, z = random.nextInt(9) - 4;
      int dx = random.nextInt(5) - 2, dy = random.nextInt(5) - 2, dz = random.nextInt(5) - 2;
      String expression =
          "offset(%d,%d,%d,adjacent(box(%d,%d,%d,%d,%d,%d)))"
              .formatted(dx, dy, dz, x, y, z, x + 2, y + 2, z + 2);
      var scope = compiler.compile(expression, SetType.POS);
      var bounds = FiniteScope.bounds(expression, PLAYER, NO_SELECTIONS, SESSION);
      var groups = scanner.group(bounds, 64);
      var positions = new java.util.HashSet<Position>();
      for (var group : groups) {
        var mask = scanner.scanGroup(group, scope, compiler.compile("all()", SetType.POS), null);
        assertEquals(0, mask.unknownCount());
        var truth = mask.trueMask();
        for (int bit = truth.nextSetBit(0); bit >= 0; bit = truth.nextSetBit(bit + 1))
          positions.add(
              new Position(
                  DIMENSION,
                  (group.key().x() << 4) + (bit & 15),
                  (group.key().y() << 4) + (bit >> 8),
                  (group.key().z() << 4) + ((bit >> 4) & 15)));
      }
      for (int py = -10; py <= 10; py++)
        for (int pz = -10; pz <= 10; pz++)
          for (int px = -10; px <= 10; px++) {
            var position = new Position(DIMENSION, px, py, pz);
            assertEquals(
                scope.at(null, position) == Truth.TRUE, positions.contains(position), expression);
          }
    }
  }

  @Test
  void duplicateBoundsDoNotMultiplyAcrossRepeatedUnionsAndIntersections() {
    var box = new SectionScanner.Bounds(DIMENSION, 0, 0, 0, 2, 2, 2);
    Host.SelectionSource source = (name, session) -> List.of(box, box);
    assertEquals(
        List.of(box),
        FiniteScope.bounds(
            "(selection(\"a\") | selection(\"b\")) & selection(\"c\")", PLAYER, source, SESSION));
  }

  @Test
  void overflowingOffsetsAndExcessiveCrossProductsFailWithAnExplicitBudgetError() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            FiniteScope.bounds(
                "offset(1,0,0,box(-2147483648,0,0,-2147483648,0,0))",
                PLAYER,
                NO_SELECTIONS,
                SESSION));
    var first = new ArrayList<SectionScanner.Bounds>();
    var second = new ArrayList<SectionScanner.Bounds>();
    for (int i = 0; i < 1001; i++) {
      first.add(new SectionScanner.Bounds(DIMENSION, i, 0, 0, i, 0, 0));
      second.add(new SectionScanner.Bounds(DIMENSION, i, 1, 0, i, 1, 0));
    }
    var error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                FiniteScope.bounds(
                    "selection(\"a\") & selection(\"b\")",
                    PLAYER,
                    (name, session) -> name.equals("a") ? first : second,
                    SESSION));
    assertTrue(error.getMessage().contains("intersection work budget"));
  }
}

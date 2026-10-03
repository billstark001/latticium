package io.github.billstark001.latticium.planning;

import static org.junit.jupiter.api.Assertions.*;

import io.github.billstark001.latticium.dsl.Compiler;
import io.github.billstark001.latticium.dsl.Model.*;
import org.junit.jupiter.api.Test;

class SectionScannerEdgeTest {
  @Test
  void scansOnlyBoundedCellsAtIntegerCoordinateExtremes() {
    var dimension = ResourceId.parse("minecraft:overworld");
    int[] evaluations = {0};
    var predicate =
        new Compiler.Bound(
            SetType.POS,
            (facts, position, value) -> {
              evaluations[0]++;
              return Truth.TRUE;
            },
            0);
    for (int coordinate : new int[] {Integer.MIN_VALUE, Integer.MAX_VALUE}) {
      var bounds =
          new SectionScanner.Bounds(
              dimension, coordinate, coordinate, coordinate, coordinate, coordinate, coordinate);
      var result = new SectionScanner().scan(bounds, predicate, predicate, null, 1).getFirst();
      assertEquals(1, result.trueCount());
      assertEquals(0, result.unknownCount());
      assertEquals(2, evaluations[0]);
      evaluations[0] = 0;
    }
  }
}

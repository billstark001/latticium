package io.github.billstark001.latticium.planning;

import static org.junit.jupiter.api.Assertions.*;

import io.github.billstark001.latticium.dsl.Compiler;
import io.github.billstark001.latticium.dsl.Model.*;
import java.util.BitSet;
import java.util.List;
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

  @Test
  void singleSectionEntryPointMatchesBatchScan() {
    var dimension = ResourceId.parse("minecraft:overworld");
    var bounds = new SectionScanner.Bounds(dimension, 15, 0, 0, 16, 0, 0);
    var predicate = new Compiler.Bound(SetType.POS, (facts, position, value) -> Truth.TRUE, 0);
    var scanner = new SectionScanner();
    var batch = scanner.scan(bounds, predicate, predicate, null, 2);
    assertEquals(2, batch.size());
    for (var section : batch) {
      var single = scanner.scanSection(bounds, section.key(), predicate, predicate, null);
      assertEquals(section.trueMask(), single.trueMask());
      assertEquals(section.knownMask(), single.knownMask());
      assertEquals(1, single.trueCount());
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            scanner.scanSection(
                bounds,
                new SectionScanner.SectionKey(ResourceId.parse("minecraft:the_nether"), 0, 0, 0),
                predicate,
                predicate,
                null));
  }

  @Test
  void sectionMasksCannotClaimUnknownOrOutOfRangeCells() {
    var key = new SectionScanner.SectionKey(ResourceId.parse("minecraft:overworld"), 0, 0, 0);
    var trueMask = new BitSet();
    trueMask.set(0);
    assertThrows(
        IllegalArgumentException.class,
        () -> new SectionScanner.SectionResult(key, trueMask, new BitSet()));
    var knownMask = new BitSet();
    knownMask.set(0);
    knownMask.set(SectionScanner.SECTION_VOLUME);
    assertThrows(
        IllegalArgumentException.class,
        () -> new SectionScanner.SectionResult(key, trueMask, knownMask));
  }

  @Test
  void overlappingBoundsProduceOneSectionAndOneUnknownCell() {
    var dimension = ResourceId.parse("minecraft:overworld");
    var first = new SectionScanner.Bounds(dimension, 1, 64, 1, 2, 64, 1);
    var second = new SectionScanner.Bounds(dimension, 2, 64, 1, 3, 64, 1);
    var scanner = new SectionScanner();
    var groups = scanner.group(List.of(first, second, first), 1);
    assertEquals(1, groups.size());
    assertEquals(List.of(first, second), groups.getFirst().bounds());
    var scope =
        new Compiler.Bound(
            SetType.POS, (facts, pos, value) -> pos.x() == 2 ? Truth.UNKNOWN : Truth.TRUE, 0);
    var select = new Compiler.Bound(SetType.POS, (facts, pos, value) -> Truth.TRUE, 0);
    var result = scanner.scanGroup(groups.getFirst(), scope, select, null);
    assertEquals(2, result.trueCount());
    assertEquals(1, result.unknownCount());
    var anotherSection = new SectionScanner.Bounds(dimension, 16, 64, 1, 16, 64, 1);
    assertThrows(
        IllegalArgumentException.class, () -> scanner.group(List.of(first, anotherSection), 1));
  }

  @Test
  void inclusiveBoundsRejectOtherDimensions() {
    var overworld = ResourceId.parse("minecraft:overworld");
    var box = new SectionScanner.Bounds(overworld, -1, 0, -1, 1, 2, 1);
    assertTrue(box.contains(new Position(overworld, -1, 0, 1)));
    assertTrue(box.contains(new Position(overworld, 1, 2, -1)));
    assertFalse(box.contains(new Position(overworld, 2, 1, 0)));
    assertFalse(box.contains(new Position(ResourceId.parse("minecraft:the_nether"), 0, 1, 0)));
  }
}

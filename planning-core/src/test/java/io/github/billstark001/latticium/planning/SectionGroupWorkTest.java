package io.github.billstark001.latticium.planning;

import static io.github.billstark001.latticium.dsl.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.billstark001.latticium.dsl.Compiler;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SectionGroupWorkTest {
  private static final ResourceId DIM = ResourceId.parse("overworld");

  @Test
  void heavilyOverlappingBoundsEvaluateEachPositionOnce() {
    var boxes = new ArrayList<SectionScanner.Bounds>();
    for (int i = 0; i < 512; i++)
      boxes.add(new SectionScanner.Bounds(DIM, i % 16, 0, i / 16 % 16, 15, 31, 15));
    var scanner = new SectionScanner();
    int[] reads = {0};
    var scope = new Compiler.Bound(SetType.POS, (f, p, v) -> Truth.TRUE, 0);
    var select =
        new Compiler.Bound(
            SetType.POS,
            (f, p, v) -> {
              reads[0]++;
              return p.x() == 7 ? Truth.UNKNOWN : Truth.TRUE;
            },
            0);
    var groups = scanner.group(boxes, 2);
    assertEquals(2, groups.size());
    for (var group : groups) {
      var result = scanner.scanGroup(group, scope, select, null);
      assertEquals(15 * 16 * 16, result.trueCount());
      assertEquals(16 * 16, result.unknownCount());
    }
    assertEquals(2 * SectionScanner.SECTION_VOLUME, reads[0]);
  }

  @Test
  void groupMasksMatchUnionOfIndependentReadsForRandomNegativeAndEdgeSections() {
    var scanner = new SectionScanner();
    var random = new Random(602603);
    var scope =
        new Compiler.Bound(
            SetType.POS, (f, p, v) -> p.y() % 3 == 0 ? Truth.UNKNOWN : Truth.TRUE, 0);
    var select =
        new Compiler.Bound(SetType.POS, (f, p, v) -> p.x() % 5 == 0 ? Truth.FALSE : Truth.TRUE, 0);
    for (int sample = 0; sample < 40; sample++) {
      int base = sample == 0 ? Integer.MIN_VALUE : sample == 1 ? Integer.MAX_VALUE - 15 : -16;
      var boxes = new ArrayList<SectionScanner.Bounds>();
      for (int i = 0; i < 8; i++) {
        int x = random.nextInt(16), y = random.nextInt(16), z = random.nextInt(16);
        boxes.add(
            new SectionScanner.Bounds(
                DIM,
                base + x,
                base + y,
                base + z,
                base + x + random.nextInt(16 - x),
                base + y + random.nextInt(16 - y),
                base + z + random.nextInt(16 - z)));
      }
      var group = scanner.group(boxes, 1).getFirst();
      SectionScanner.SectionResult expected = null;
      for (var box : boxes) {
        var part = scanner.scanSection(box, group.key(), scope, select, null);
        expected = expected == null ? part : expected.or(part);
      }
      assertEquals(expected, scanner.scanGroup(group, scope, select, null));
    }
  }

  @Test
  void contributionBudgetBoundsLargeOverlappingScopesEvenWithFewDistinctSections() {
    int sectionsPerBox = 1000;
    var boxes = new ArrayList<SectionScanner.Bounds>();
    int count = SectionScanner.MAX_GROUP_CONTRIBUTIONS / sectionsPerBox + 1;
    for (int i = 0; i < count; i++)
      boxes.add(
          new SectionScanner.Bounds(
              DIM, (i / 256) % 16, i % 16, (i / 16) % 16, sectionsPerBox * 16 - 1, 15, 15));
    var error =
        assertThrows(
            IllegalArgumentException.class,
            () -> new SectionScanner().group(boxes, sectionsPerBox));
    assertTrue(error.getMessage().contains("section contributions"));
    var box = new SectionScanner.Bounds(DIM, 0, 0, 0, 15, 15, 15);
    assertEquals(
        List.of(box),
        new SectionScanner()
            .group(java.util.Collections.nCopies(10_000, box), 1)
            .getFirst()
            .bounds());
  }
}

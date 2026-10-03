package io.github.billstark001.latticium.planning;

import static io.github.billstark001.latticium.dsl.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.BitSet;
import org.junit.jupiter.api.Test;

class SectionMaskAlgebraTest {
  private static final SectionScanner.SectionKey KEY =
      new SectionScanner.SectionKey(ResourceId.parse("minecraft:overworld"), 0, 0, 0);

  private static SectionScanner.SectionResult mask(Truth[] values) {
    var truth = new BitSet();
    var known = new BitSet();
    for (int i = 0; i < values.length; i++) {
      if (values[i] != Truth.UNKNOWN) known.set(i);
      if (values[i] == Truth.TRUE) truth.set(i);
    }
    return new SectionScanner.SectionResult(KEY, truth, known);
  }

  private static Truth at(SectionScanner.SectionResult mask, int index) {
    if (!mask.knownMask().get(index)) return Truth.UNKNOWN;
    return mask.trueMask().get(index) ? Truth.TRUE : Truth.FALSE;
  }

  @Test
  void maskOperatorsMatchAllNinePointwiseTruthCombinations() {
    var cases = Truth.values();
    var leftValues = new Truth[9];
    var rightValues = new Truth[9];
    for (int i = 0; i < 9; i++) {
      leftValues[i] = cases[i / 3];
      rightValues[i] = cases[i % 3];
    }
    var left = mask(leftValues);
    var right = mask(rightValues);
    var intersection = left.and(right);
    var union = left.or(right);
    var domain = new BitSet();
    domain.set(0, 9);
    var complement = left.not(domain);
    for (int i = 0; i < 9; i++) {
      assertEquals(leftValues[i].and(rightValues[i]), at(intersection, i));
      assertEquals(leftValues[i].or(rightValues[i]), at(union, i));
      assertEquals(leftValues[i].not(), at(complement, i));
    }
    assertEquals(Truth.UNKNOWN, at(intersection, 9));
    assertEquals(Truth.FALSE, at(complement, 9));
  }

  @Test
  void masksRejectCrossSectionOperationsAndDoNotExposeMutableBits() {
    var original = mask(new Truth[] {Truth.TRUE});
    var returned = original.trueMask();
    returned.clear();
    assertEquals(Truth.TRUE, at(original, 0));
    var otherKey = new SectionScanner.SectionKey(ResourceId.parse("minecraft:overworld"), 1, 0, 0);
    var other = new SectionScanner.SectionResult(otherKey, new BitSet(), new BitSet());
    assertThrows(IllegalArgumentException.class, () -> original.and(other));
    assertThrows(IllegalArgumentException.class, () -> original.or(other));
  }
}

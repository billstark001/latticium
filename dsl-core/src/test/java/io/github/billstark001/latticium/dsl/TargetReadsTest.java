package io.github.billstark001.latticium.dsl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TargetReadsTest {
  @Test
  void detectsNestedTargetViewsWithoutMatchingQuotedText() {
    assertTrue(TargetReads.in(Parser.expression("all() & !matches_target()")));
    assertTrue(TargetReads.in(Parser.expression("offset(1,0,0,target(s{minecraft:stone}))")));
    assertFalse(
        TargetReads.in(Parser.expression("selection(\"target\") & current(s{minecraft:air})")));
  }
}

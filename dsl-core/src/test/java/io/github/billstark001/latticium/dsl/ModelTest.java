package io.github.billstark001.latticium.dsl;

import static io.github.billstark001.latticium.dsl.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ModelTest {
  @Test
  void resourceIdsValidateShortFullAndMalformedForms() {
    assertEquals(ResourceId.parse("minecraft:stone"), ResourceId.parse("stone"));
    assertEquals("mod:path/to_block", ResourceId.parse("mod:path/to_block").toString());
    for (String invalid : new String[] {"", ":stone", "mod:", "mod:a:b", "Bad:stone"}) {
      assertThrows(IllegalArgumentException.class, () -> ResourceId.parse(invalid), invalid);
    }
  }

  @Test
  void positionTranslationRejectsIntegerOverflow() {
    var edge = new Position(ResourceId.parse("minecraft:overworld"), Integer.MAX_VALUE, 0, 0);
    assertThrows(ArithmeticException.class, () -> edge.offset(1, 0, 0));
  }

  @Test
  void blockStateOrderingUsesSortedProperties() {
    var state =
        new BlockState(
            ResourceId.parse("minecraft:oak_stairs"),
            Map.of("shape", "straight", "facing", "north"));
    assertEquals("minecraft:oak_stairs[facing=north,shape=straight]", state.canonicalId());
  }
}

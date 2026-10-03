package io.github.billstark001.latticium.dsl;

import static io.github.billstark001.latticium.dsl.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class BoundElementTest {
  @Test
  void crossTypeElementsAreOutsideEveryRegistrySet() {
    var compiler = Compiler.symbolic();
    var block = ResourceId.parse("minecraft:stone");
    var state = new BlockState(block, Map.of());
    assertEquals(
        Truth.FALSE,
        compiler.compile("states_of(b{minecraft:stone})", SetType.STATE).contains(null, block));
    assertEquals(
        Truth.FALSE,
        compiler.compile("blocks_of(s{minecraft:stone})", SetType.BLOCK).contains(null, state));
    assertEquals(
        Truth.FALSE, compiler.compile("i{minecraft:stone}", SetType.ITEM).contains(null, null));
    assertThrows(
        NullPointerException.class, () -> compiler.compile("all()", SetType.POS).at(null, null));
  }
}

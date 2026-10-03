package io.github.billstark001.latticium.dsl;

import static io.github.billstark001.latticium.dsl.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TypeInferenceEdgeTest {
  @Test
  void typedOperandCanResolveAChainOfUnprefixedLiterals() {
    var compiler = Compiler.symbolic();
    var result = compiler.compile("{stone} | {dirt} | b{stone}", null);
    assertEquals(SetType.BLOCK, result.type());
    assertEquals(Truth.TRUE, result.contains(null, ResourceId.parse("minecraft:stone")));
    assertEquals(Truth.TRUE, result.contains(null, ResourceId.parse("minecraft:dirt")));
    assertEquals(SetType.BLOCK, compiler.compile("!{stone} & b{dirt}", null).type());
    assertThrows(Syntax.Failure.class, () -> compiler.compile("{stone} | {dirt}", null));
    assertThrows(
        Syntax.Failure.class,
        () -> compiler.compile("{stone} | {dirt} | i{stone} & b{stone}", null));
  }
}

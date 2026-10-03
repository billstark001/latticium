package io.github.billstark001.latticium.dsl;

import static io.github.billstark001.latticium.dsl.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SphereEdgeTest {
  @Test
  void integerRadiusBoundaryDoesNotRoundInOutsidePositions() {
    var sphere =
        new Compiler(Compiler.symbolic().registry(), Integer.MAX_VALUE)
            .compile("sphere(point(0,0,0),2147483647)", SetType.POS);
    var dimension = ResourceId.parse("minecraft:overworld");
    assertEquals(Truth.TRUE, sphere.at(null, new Position(dimension, Integer.MAX_VALUE, 0, 0)));
    assertEquals(Truth.FALSE, sphere.at(null, new Position(dimension, Integer.MAX_VALUE, 1, 0)));
    assertEquals(Truth.FALSE, sphere.at(null, new Position(dimension, Integer.MIN_VALUE, 0, 0)));
  }
}

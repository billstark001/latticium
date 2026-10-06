package io.github.billstark001.latticium.dsl;

import static io.github.billstark001.latticium.dsl.FactDependencies.Fact.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.billstark001.latticium.dsl.FactDependencies.Offset;
import io.github.billstark001.latticium.dsl.Model.SetType;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FactReadOffsetsTest {
  @Test
  void transformedReadsSurviveFunctionAndDeclarationExpansion() {
    var compiler = Compiler.symbolic();
    compiler.compile(
        Parser.document(
            "near: PosSet = adjacent(current(b{stone})); def moved(p: PosSet, n: Int): PosSet = offset(n,0,0,p);"));
    var dependencies =
        compiler
            .compile("moved(near,2) | offset(0,0,3,light(0..15)) | sphere(player,16)", SetType.POS)
            .dependencies();
    assertEquals(Set.of(STATE, LIGHT, PLAYER), dependencies.facts());
    assertEquals(
        Set.of(
            new Offset(3, 0, 0),
            new Offset(1, 0, 0),
            new Offset(2, 1, 0),
            new Offset(2, -1, 0),
            new Offset(2, 0, 1),
            new Offset(2, 0, -1)),
        dependencies.columns().get(STATE).offsets());
    assertEquals(Set.of(new Offset(0, 0, 3)), dependencies.columns().get(LIGHT).offsets());
    assertFalse(dependencies.columns().containsKey(PLAYER));
  }

  @Test
  void surfaceNeedsCenterAndSixFacesAndCoordinateRadiusDoesNotWidenStateReads() {
    var compiler = Compiler.symbolic();
    var direct = compiler.compile("sphere(player,16) & current(b{stone})", SetType.POS);
    assertEquals(0, direct.radius());
    assertEquals(Set.of(new Offset(0, 0, 0)), direct.dependencies().columns().get(STATE).offsets());
    var surface = compiler.compile("surface()", SetType.POS).dependencies();
    assertEquals(7, surface.columns().get(STATE).offsets().size());
    assertTrue(surface.columns().get(STATE).offsets().contains(new Offset(0, 0, 0)));
  }

  @Test
  void unionsDoNotLoseDifferentOffsetsOfTheSameColumn() {
    var dependencies = FactDependencies.of(STATE).union(FactDependencies.of(STATE).shift(16, 0, 0));
    assertEquals(
        Set.of(new Offset(0, 0, 0), new Offset(16, 0, 0)),
        dependencies.columns().get(STATE).offsets());
    assertTrue(
        dependencies.union(new FactDependencies(Set.of(STATE))).columns().get(STATE).broad());
    assertThrows(UnsupportedOperationException.class, () -> dependencies.columns().clear());
    assertThrows(
        UnsupportedOperationException.class,
        () -> dependencies.columns().get(STATE).offsets().clear());
  }

  @Test
  void unknownOrExcessiveOffsetsStayConservativeWithoutOverflow() {
    var reads = FactDependencies.of(STATE);
    for (int i = 0; i < 16; i++) reads = reads.adjacent();
    assertTrue(reads.columns().get(STATE).broad());
    assertTrue(
        FactDependencies.of(STATE)
            .shift(Integer.MAX_VALUE, 0, 0)
            .shift(1, 0, 0)
            .columns()
            .get(STATE)
            .broad());
    assertEquals(FactDependencies.ALL, FactDependencies.ALL.shift(16, -16, 0));
    assertEquals(FactDependencies.NONE, FactDependencies.NONE.adjacent());
  }
}

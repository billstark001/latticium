package io.github.billstark001.latticium.planning;

import static io.github.billstark001.latticium.dsl.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.billstark001.latticium.dsl.Compiler;
import io.github.billstark001.latticium.dsl.Parser;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RefreshReadsBoundTest {
  @Test
  void expandedDeclarationReadsAreNotLostBySyntaxOnlyAnalysis() {
    var compiler = Compiler.symbolic();
    compiler.compile(Parser.document("far: PosSet = offset(7,0,0,current(b{stone}));"));
    var expression = Parser.expression("far");
    assertTrue(RefreshReads.in(expression).worldOffsets().isEmpty());
    var reads = RefreshReads.in(expression, compiler.compile(expression, SetType.POS));
    assertEquals(Set.of(new RefreshReads.Offset(7, 0, 0)), reads.worldOffsets());
    assertFalse(reads.broadWorld());
    assertFalse(reads.periodic());
  }

  @Test
  void expandedPlayerGeometryFallsBackConservativelyAndTargetOnlyReadsArePeriodic() {
    var compiler = Compiler.symbolic().targetAvailable(true);
    compiler.compile(
        Parser.document(
            "near: PosSet = sphere(player,8); want: PosSet = offset(3,0,0,target(b{stone}));"));
    var near = Parser.expression("near");
    var playerReads = RefreshReads.in(near, compiler.compile(near, SetType.POS));
    assertTrue(playerReads.broadPlayer());
    assertTrue(playerReads.usesPlayer());
    var want = Parser.expression("want");
    var targets = RefreshReads.in(want, compiler.compile(want, SetType.POS));
    assertTrue(targets.periodic());
    assertTrue(targets.worldOffsets().isEmpty());
    var direct = Parser.expression("offset(1,0,0,sphere(player,8))");
    var geometry = RefreshReads.in(direct, compiler.compile(direct, SetType.POS));
    assertFalse(geometry.broadPlayer());
    assertEquals(1, geometry.playerSpheres().size());
    assertEquals(new RefreshReads.Offset(1, 0, 0), geometry.playerSpheres().getFirst().offset());
  }
}

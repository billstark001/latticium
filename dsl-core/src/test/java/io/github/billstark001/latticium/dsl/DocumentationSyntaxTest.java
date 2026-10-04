package io.github.billstark001.latticium.dsl;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DocumentationSyntaxTest {
  @Test
  void declarationAndQueryExampleCompiles() {
    var document =
        Parser.document(
            """
            stone: BlockSet = b{minecraft:stone};
            def near_stone(area: PosSet): PosSet = area & adjacent(current(stone));
            query near_stone(box(0,60,0,10,80,10)) order by y asc, z asc, x asc limit 20;
            count current(stone) & box(0,60,0,10,80,10);
            exists current(stone) & box(0,60,0,10,80,10);
            """);
    assertEquals(3, Compiler.symbolic().compile(document).size());
  }

  @Test
  void positionExamplesBindWithTheirDocumentedTypes() {
    var compiler = Compiler.symbolic().targetAvailable(true);
    assertEquals(
        Model.SetType.POS,
        compiler
            .compile(
                "box(0,60,0,10,80,10) & current(b{minecraft:stone}) & surface()", Model.SetType.POS)
            .type());
    assertEquals(
        Model.SetType.POS,
        compiler
            .compile("selection(\"build\") & current(s{minecraft:air})", Model.SetType.POS)
            .type());
    assertEquals(
        Model.SetType.POS,
        compiler.compile("has_target() & !matches_target()", Model.SetType.POS).type());
  }
}

package io.github.billstark001.latticium.dsl;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FunctionDepthTest {
  @Test
  void longDependencyChainFailsWithADiagnosticBeforeStackExhaustion() {
    var source = new StringBuilder();
    for (int i = 0; i < Compiler.MAX_EXPANSION_DEPTH + 2; i++) {
      String body = i == Compiler.MAX_EXPANSION_DEPTH + 1 ? "all()" : "f" + (i + 1) + "()";
      source.append("def f").append(i).append("(): PosSet = ").append(body).append(';');
    }
    var error =
        assertThrows(
            Syntax.Failure.class,
            () -> Compiler.symbolic().compile(Parser.document(source.toString())));
    assertTrue(error.getMessage().contains("depth exceeded"));
  }

  @Test
  void directDocumentsCannotBypassTheCombinedBindingBudget() {
    var span = new Syntax.Span(0, 0);
    var body = new Syntax.Call("all", List.of(), span);
    var functions = new ArrayList<Syntax.Function>();
    for (int i = 0; i <= Compiler.MAX_TOP_LEVEL_BINDINGS; i++)
      functions.add(new Syntax.Function("f" + i, List.of(), Model.SetType.POS, body, span));
    var document = new Syntax.Document(List.of(), functions, List.of());
    assertThrows(IllegalArgumentException.class, () -> Compiler.symbolic().compile(document));
  }

  @Test
  void flatFunctionExpressionCannotBypassTheExpansionDepth() {
    var body = "all()" + " & all()".repeat(Compiler.MAX_EXPANSION_DEPTH + 2);
    var source = "def deep(): PosSet = " + body + ";";
    var error =
        assertThrows(
            Syntax.Failure.class, () -> Compiler.symbolic().compile(Parser.document(source)));
    assertTrue(error.getMessage().contains("depth exceeded"));
  }
}

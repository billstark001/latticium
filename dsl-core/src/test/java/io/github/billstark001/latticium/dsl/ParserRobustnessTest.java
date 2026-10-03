package io.github.billstark001.latticium.dsl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Random;
import org.junit.jupiter.api.Test;

class ParserRobustnessTest {
  @Test
  void publicSyntaxValuesRejectInvalidShapes() {
    assertThrows(IllegalArgumentException.class, () -> new Syntax.Span(-1, 0));
    assertThrows(IllegalArgumentException.class, () -> new Syntax.Span(2, 1));
    assertThrows(
        IllegalArgumentException.class, () -> new Syntax.Parameter("x", Model.SetType.POS, true));
    assertThrows(IllegalArgumentException.class, () -> new Syntax.Parameter("x", null, false));
    var span = new Syntax.Span(0, 1);
    var left = new Syntax.Name("a", span);
    assertThrows(IllegalArgumentException.class, () -> new Syntax.Binary('^', left, left, span));
  }

  @Test
  void quotedStringLengthLimitCountsContentCharacters() {
    var allowed = "a".repeat(8_192);
    assertEquals(allowed, ((Syntax.Text) Parser.expression("\"" + allowed + "\"")).value());
    assertThrows(Syntax.Failure.class, () -> Parser.expression("\"" + "a".repeat(8_193) + "\""));
  }

  @Test
  void unterminatedEscapeKeepsDiagnosticInsideSource() {
    String source = "\"text\\";
    var error = assertThrows(Syntax.Failure.class, () -> Parser.expression(source));
    assertEquals(source.length(), error.diagnostic().span().end());
  }

  @Test
  void lexerCountsTheEndTokenWithinItsBudget() {
    assertEquals(4_096, Lexer.lex("x ".repeat(4_095)).size());
    assertThrows(Syntax.Failure.class, () -> Lexer.lex("x ".repeat(4_096)));
  }

  @Test
  void lineCommentsStopAtCrAndUnicodeLineSeparators() {
    assertEquals(1, Parser.document("// note\rquery all();").terminals().size());
    assertEquals(1, Parser.document("// note\u2028query all();").terminals().size());
    assertEquals(1, Parser.document("query x=1// attached\n;").terminals().size());
  }

  @Test
  void resourceIdsAndTheirPrefixesRequireAdjacentTokens() {
    for (String source :
        new String[] {
          "minecraft :stone", "minecraft: stone", "b{# minecraft:stone}", "$ minecraft:plains"
        }) {
      var error = assertThrows(Syntax.Failure.class, () -> Parser.expression(source), source);
      assertTrue(error.diagnostic().span().end() <= source.length(), source);
    }
    assertDoesNotThrow(() -> Parser.expression("b{#minecraft:stone}"));
  }

  @Test
  void malformedInputsProduceDiagnosticsInsteadOfInternalExceptions() {
    var random = new Random(0x1A771C1L);
    String alphabet = "abcxyz0123_:-+./#${}[](),;=!&|\" \\ \n";
    for (int sample = 0; sample < 1_000; sample++) {
      int length = random.nextInt(64);
      var source = new StringBuilder(length);
      for (int i = 0; i < length; i++)
        source.append(alphabet.charAt(random.nextInt(alphabet.length())));
      for (boolean document : new boolean[] {false, true}) {
        try {
          if (document) Parser.document(source.toString());
          else Parser.expression(source.toString());
        } catch (Syntax.Failure expected) {
          // Invalid syntax must retain a source span.
          if (expected.diagnostic().span() == null) fail("Missing span for: " + source);
          if (expected.diagnostic().span().end() > source.length())
            fail("Span exceeds source length for: " + source);
        } catch (RuntimeException unexpected) {
          fail("Internal parser exception for: " + source, unexpected);
        }
      }
    }
  }
}

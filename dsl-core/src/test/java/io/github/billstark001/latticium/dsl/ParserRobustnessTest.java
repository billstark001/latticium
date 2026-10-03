package io.github.billstark001.latticium.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Random;
import org.junit.jupiter.api.Test;

class ParserRobustnessTest {
  @Test
  void quotedStringLengthLimitCountsContentCharacters() {
    var allowed = "a".repeat(8_192);
    assertEquals(allowed, ((Syntax.Text) Parser.expression("\"" + allowed + "\"")).value());
    assertThrows(Syntax.Failure.class, () -> Parser.expression("\"" + "a".repeat(8_193) + "\""));
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
        } catch (RuntimeException unexpected) {
          fail("Internal parser exception for: " + source, unexpected);
        }
      }
    }
  }
}

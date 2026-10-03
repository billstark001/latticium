package io.github.billstark001.latticium.planning;

import static org.junit.jupiter.api.Assertions.*;

import io.github.billstark001.latticium.dsl.Compiler;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class LocaleParsingTest {
  @Test
  void enumChoicesDoNotDependOnDefaultLocale() {
    Locale previous = Locale.getDefault();
    Locale.setDefault(Locale.forLanguageTag("tr-TR"));
    try {
      var profile =
          new ProfileReader()
              .read(
                  """
                  {"schema":1,"id":"test:locale","activation":{"where":"all()","mode":"while"},
                  "scope":"box(0,0,0,0,0,0)","select":{"where":"all()"},
                  "target":{"items":"i{minecraft:stone}"}}
                  """);
      assertEquals(Profile.Activation.Mode.WHILE, profile.activation().mode());
      var rules =
          RuleBook.parse(
              """
              {"schema":1,"rules":[{"id":"test:interact","before":{"block":"minecraft:stone"},
              "after":{"block":"minecraft:dirt"},"action":"interact"}]}
              """,
              Compiler.symbolic());
      assertEquals(Planner.Action.INTERACT, rules.rules().getFirst().action());
    } finally {
      Locale.setDefault(previous);
    }
  }
}

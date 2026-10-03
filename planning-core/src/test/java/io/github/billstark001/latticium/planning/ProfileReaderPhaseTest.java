package io.github.billstark001.latticium.planning;

import static org.junit.jupiter.api.Assertions.*;

import io.github.billstark001.latticium.dsl.Compiler;
import io.github.billstark001.latticium.dsl.Model.SetType;
import io.github.billstark001.latticium.dsl.Syntax;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProfileReaderPhaseTest {
  @Test
  void importedDeclarationsCannotCaptureTargetAccessFromCaller() {
    var reader = new ProfileReader();
    var profile =
        reader.read(
            """
            {"schema":1,"id":"test:phase","use":["test:module"],
            "scope":"box(0,0,0,0,0,0)","select":{"where":"all()"},
            "target":{"items":"i{minecraft:stone}"}}
            """);
    var compiler = Compiler.symbolic().targetAvailable(true);
    assertThrows(
        ProfileReader.Error.class,
        () ->
            reader.bind(
                profile,
                compiler,
                id ->
                    Optional.of(
                        new ModuleLoader.Module(
                            "leak: PosSet = target(b{minecraft:stone});", List.of()))));
    assertTrue(compiler.targetAvailable());
    assertThrows(Syntax.Failure.class, () -> compiler.compile("leak", SetType.POS));
  }

  @Test
  void profileImportsDoNotLeakAfterLaterBindingFailure() {
    var reader = new ProfileReader();
    var profile =
        reader.read(
            """
            {"schema":1,"id":"test:isolated","use":["test:module"],
            "scope":"box(0,0,0,0,0,0)","select":{"where":"missing"},
            "target":{"items":"i{minecraft:stone}"}}
            """);
    var compiler = Compiler.symbolic();
    assertThrows(
        ProfileReader.Error.class,
        () ->
            reader.bind(
                profile,
                compiler,
                id ->
                    Optional.of(new ModuleLoader.Module("imported: PosSet = all();", List.of()))));
    assertThrows(Syntax.Failure.class, () -> compiler.compile("imported", SetType.POS));
  }

  @Test
  void clearTargetIsAvailableToSelectionButItemTargetIsNotYetFrozen() {
    var reader = new ProfileReader();
    String profile =
        """
        {"schema":1,"id":"test:phase","scope":"box(0,0,0,0,0,0)",
         "select":{"where":"!matches_target()"},
         "target":%s,"policy":{"break":"selected"}}
        """;
    assertEquals(
        SetType.POS,
        reader
            .bind(reader.read(profile.formatted("{\"clear\":true}")), Compiler.symbolic())
            .select()
            .type());
    assertThrows(
        ProfileReader.Error.class,
        () ->
            reader.bind(
                reader.read(profile.formatted("{\"items\":\"i{minecraft:stone}\"}")),
                Compiler.symbolic()));
  }

  @Test
  void clearTargetCanBindWhenBreakingIsDenied() {
    var profile =
        new ProfileReader()
            .read(
                """
                {"schema":1,"id":"test:clear","scope":"box(0,0,0,0,0,0)",
                 "select":{"where":"all()"},"target":{"clear":true},
                 "policy":{"break":"deny"}}
                """);
    assertEquals(
        SetType.POS, new ProfileReader().bind(profile, Compiler.symbolic()).select().type());
  }
}

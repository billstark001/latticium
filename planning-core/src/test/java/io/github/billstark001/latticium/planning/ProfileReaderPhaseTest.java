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
}

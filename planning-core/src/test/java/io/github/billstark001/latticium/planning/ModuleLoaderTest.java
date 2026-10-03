package io.github.billstark001.latticium.planning;

import static org.junit.jupiter.api.Assertions.*;

import io.github.billstark001.latticium.dsl.Compiler;
import io.github.billstark001.latticium.dsl.Model.ResourceId;
import io.github.billstark001.latticium.dsl.Model.SetType;
import io.github.billstark001.latticium.dsl.Syntax;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ModuleLoaderTest {
  private static final ResourceId BASE = ResourceId.parse("user:base");
  private static final ResourceId BROKEN = ResourceId.parse("user:broken");

  @Test
  void failedBatchLeavesCompilerAndLoaderReadyForRetry() {
    var compiler = Compiler.symbolic();
    var loader =
        new ModuleLoader(
            id ->
                Optional.of(
                    id.equals(BASE)
                        ? new ModuleLoader.Module("base: PosSet = all();", List.of())
                        : new ModuleLoader.Module("broken: PosSet = missing;", List.of())),
            compiler);
    assertThrows(Syntax.Failure.class, () -> loader.loadAll(List.of(BASE, BROKEN)));
    assertThrows(Syntax.Failure.class, () -> compiler.compile("base", SetType.POS));
    loader.loadAll(List.of(BASE));
    assertEquals(SetType.POS, compiler.compile("base", SetType.POS).type());
  }

  @Test
  void dependentModuleCanUseEarlierDeclaration() {
    var compiler = Compiler.symbolic();
    var loader =
        new ModuleLoader(
            id ->
                Optional.of(
                    id.equals(BASE)
                        ? new ModuleLoader.Module("base: PosSet = all();", List.of())
                        : new ModuleLoader.Module("derived: PosSet = base;", List.of(BASE))),
            compiler);
    loader.loadAll(List.of(BROKEN));
    assertEquals(SetType.POS, compiler.compile("derived", SetType.POS).type());
  }

  @Test
  void wideImportGraphHasANodeBudget() {
    var compiler = Compiler.symbolic();
    var loader =
        new ModuleLoader(id -> Optional.of(new ModuleLoader.Module("", List.of())), compiler);
    var ids =
        IntStream.range(0, 513)
            .mapToObj(index -> ResourceId.parse("user:module_" + index))
            .toList();
    assertThrows(IllegalArgumentException.class, () -> loader.loadAll(ids));
    loader.loadAll(ids.subList(0, 512));
  }

  @Test
  void syntaxErrorsIdentifyTheImportedModule() {
    var loader =
        new ModuleLoader(
            id -> Optional.of(new ModuleLoader.Module("broken: PosSet = ;", List.of())),
            Compiler.symbolic());
    var error = assertThrows(Syntax.Failure.class, () -> loader.loadAll(List.of(BROKEN)));
    assertTrue(error.getMessage().contains(BROKEN.toString()));
  }
}

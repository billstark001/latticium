package io.github.billstark001.latticium.dsl;

import static io.github.billstark001.latticium.dsl.FactDependencies.Fact.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.billstark001.latticium.dsl.Model.*;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class FactDependenciesTest {
  static Stream<Arguments> builtinReads() {
    return Stream.of(
        Arguments.of("all() & !none() & x=0..10 & dimension(minecraft:overworld)", Set.of()),
        Arguments.of("sphere(point(0,0,0),2) | box(0,0,0,1,1,1)", Set.of()),
        Arguments.of("sphere(player,2)", Set.of(PLAYER)),
        Arguments.of("selection(\"build\")", Set.of(SELECTION)),
        Arguments.of("minecraft:stone", Set.of(STATE)),
        Arguments.of("$minecraft:plains", Set.of(BIOME)),
        Arguments.of("current(states_of(b{stone}))", Set.of(STATE)),
        Arguments.of("current(blocks_of(s{stone}))", Set.of(STATE)),
        Arguments.of("state(open=true)", Set.of(STATE)),
        Arguments.of("surface()", Set.of(STATE)),
        Arguments.of("solid() & light(0..15)", Set.of(SOLID, LIGHT)),
        Arguments.of("biome(m{plains}) | fluid(f{water})", Set.of(BIOME, FLUID)),
        Arguments.of("target(b{stone}) | has_target()", Set.of(TARGET)),
        Arguments.of("matches_target() | changed(facing) | same(facing)", Set.of(STATE, TARGET)),
        Arguments.of("compare(layers,lt)", Set.of(STATE, TARGET)),
        Arguments.of("offset(2,0,0,adjacent(biome(m{plains})))", Set.of(BIOME)));
  }

  @ParameterizedTest
  @MethodSource("builtinReads")
  void builtinsRequestOnlyTheirFactColumns(String expression, Set<FactDependencies.Fact> facts) {
    var bound = Compiler.symbolic().targetAvailable(true).compile(expression, SetType.POS);
    assertEquals(facts, bound.dependencies().facts());
    var requested = bound.dependencies();
    Facts projection =
        new Facts() {
          public Optional<WorldCell> world(Position pos) {
            assertTrue(requested.usesWorld(), "Unexpected world read");
            return CompilerTest.FACTS
                .world(pos)
                .map(
                    w ->
                        new WorldCell(
                            requested.needs(STATE) ? w.state() : null,
                            requested.needs(BIOME) ? w.biome() : null,
                            requested.needs(FLUID) ? w.fluid() : null,
                            requested.needs(LIGHT) ? w.light() : null,
                            requested.needs(SOLID) ? w.solid() : null));
          }

          public TargetCell target(Position pos) {
            assertTrue(requested.needs(TARGET), "Unexpected target read");
            return CompilerTest.FACTS.target(pos);
          }

          public Optional<Position> player() {
            assertTrue(requested.needs(PLAYER), "Unexpected player read");
            return CompilerTest.FACTS.player();
          }

          public Optional<Set<ResourceId>> inventory() {
            assertTrue(requested.needs(INVENTORY), "Unexpected inventory read");
            return CompilerTest.FACTS.inventory();
          }

          public Truth selection(String name, Position pos) {
            assertTrue(requested.needs(SELECTION), "Unexpected selection read");
            return CompilerTest.FACTS.selection(name, pos);
          }
        };
    for (int x = -4; x <= 4; x++) {
      var pos = CompilerTest.ORIGIN.offset(x, 0, 0);
      assertEquals(bound.at(CompilerTest.FACTS, pos), bound.at(projection, pos));
    }
  }

  @Test
  void expandedFunctionsRetainArgumentAndDeclarationReadsAndHalo() {
    var compiler = Compiler.symbolic().targetAvailable(true);
    var bound =
        compiler
            .compile(
                Parser.document(
                    """
        wet: PosSet = fluid(f{water});
        def around(area: PosSet, dx: Int): PosSet = offset(dx,0,0,area) & wet;
        query around(adjacent(target(b{stone})),2);
        """))
            .getFirst();
    assertEquals(Set.of(FLUID, TARGET), bound.dependencies().facts());
    assertEquals(3, bound.radius());
    assertEquals(
        bound.dependencies(),
        compiler
            .fork()
            .compile("around(adjacent(target(b{stone})),2)", SetType.POS)
            .dependencies());
  }

  @Test
  void registrySetsPreserveDynamicInventoryReadsThroughConversionsAndNegation() {
    assertEquals(
        Set.of(INVENTORY),
        Compiler.symbolic()
            .compile("!inventory(i{stone}) | i{dirt}", SetType.ITEM)
            .dependencies()
            .facts());
    assertEquals(
        Set.of(),
        Compiler.symbolic()
            .compile("property(open=true) | property_range(layers,1..4)", SetType.STATE)
            .dependencies()
            .facts());
  }

  @Test
  void customMembershipsAndLegacyPrimitivesAreConservative() {
    assertEquals(
        FactDependencies.ALL,
        new Compiler.Bound(SetType.POS, (facts, pos, value) -> Truth.TRUE, 0).dependencies());
    var primitive =
        new Compiler.Primitive() {
          public List<SetType> parameters() {
            return List.of();
          }

          public SetType result() {
            return SetType.POS;
          }

          public int radius() {
            return 1;
          }

          public Compiler.Membership bind(List<Compiler.Bound> arguments) {
            return (facts, pos, value) -> Truth.UNKNOWN;
          }
        };
    assertEquals(
        FactDependencies.ALL,
        Compiler.symbolic()
            .registerPrimitive("legacy", primitive)
            .compile("legacy()", SetType.POS)
            .dependencies());
  }

  @Test
  void primitiveMetadataIsFrozenAndArgumentRequirementsAreUnioned() {
    var declared = new AtomicReference<>(FactDependencies.of(LIGHT));
    var primitive =
        new Compiler.Primitive() {
          public List<SetType> parameters() {
            return List.of(SetType.POS);
          }

          public SetType result() {
            return SetType.POS;
          }

          public int radius() {
            return 2;
          }

          public FactDependencies dependencies() {
            return declared.get();
          }

          public Compiler.Membership bind(List<Compiler.Bound> arguments) {
            return arguments.getFirst().membership();
          }
        };
    var compiler = Compiler.symbolic().registerPrimitive("probe", primitive);
    declared.set(FactDependencies.NONE);
    var bound = compiler.compile("probe(adjacent(solid())) & sphere(player,2)", SetType.POS);
    assertEquals(Set.of(LIGHT, SOLID, PLAYER), bound.dependencies().facts());
    assertEquals(3, bound.radius());
  }

  @Test
  void dependencySetsCannotChangeAfterBinding() {
    var source = new HashSet<>(Set.of(STATE));
    var dependencies = new FactDependencies(source);
    source.add(TARGET);
    assertEquals(Set.of(STATE), dependencies.facts());
    assertThrows(UnsupportedOperationException.class, () -> dependencies.facts().add(TARGET));
    assertFalse(FactDependencies.of(PLAYER, INVENTORY).usesWorld());
    assertTrue(FactDependencies.of(BIOME).usesWorld());
  }
}

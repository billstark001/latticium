package io.github.billstark001.latticium.planning;

import static org.junit.jupiter.api.Assertions.*;

import io.github.billstark001.latticium.dsl.Compiler;
import io.github.billstark001.latticium.dsl.Model.*;
import io.github.billstark001.latticium.dsl.Parser;
import io.github.billstark001.latticium.dsl.Syntax;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class QueryRunnerEdgeTest {
  private static final ResourceId OVERWORLD = ResourceId.parse("minecraft:overworld");
  private static final ResourceId NETHER = ResourceId.parse("minecraft:the_nether");
  private static final Facts FACTS =
      new Facts() {
        public Optional<WorldCell> world(Position position) {
          return Optional.empty();
        }

        public TargetCell target(Position position) {
          return new TargetCell.DontCare();
        }

        public Optional<Position> player() {
          return Optional.of(new Position(OVERWORLD, 0, 0, 0));
        }

        public Optional<Set<ResourceId>> inventory() {
          return Optional.empty();
        }

        public Truth selection(String name, Position position) {
          return Truth.FALSE;
        }
      };

  @Test
  void rejectsInvalidDirectTerminalAndCrossDimensionDistance() {
    var terminal =
        Parser.document("query all() order by distance2(player);").terminals().getFirst();
    var expression = Compiler.symbolic().compile("all()", SetType.POS);
    var bounds = List.of(new SectionScanner.Bounds(NETHER, 0, 0, 0, 0, 0, 0));
    var runner = new QueryRunner();
    assertThrows(
        IllegalArgumentException.class,
        () -> runner.run(terminal, expression, bounds, null, FACTS, 1));
    var zeroLimit =
        new Syntax.Terminal(
            "query",
            terminal.expression(),
            List.of(new Syntax.Order("x", false)),
            0,
            false,
            terminal.span());
    assertThrows(
        IllegalArgumentException.class,
        () -> runner.run(zeroLimit, expression, bounds, null, FACTS, 1));
  }

  @Test
  void stateIdOrderingUsesCanonicalPropertiesWithTopK() {
    var block = ResourceId.parse("minecraft:oak_stairs");
    var north = new BlockState(block, Map.of("facing", "north"));
    var south = new BlockState(block, Map.of("facing", "south"));
    Registry registry =
        new Registry() {
          public Resolution resolve(SetType kind, ResourceId id) {
            return Resolution.FOUND;
          }

          public Resolution resolveTag(SetType kind, ResourceId id) {
            return Resolution.MISSING;
          }

          public Set<ResourceId> tag(SetType kind, ResourceId id) {
            return Set.of();
          }

          public Set<ResourceId> universe(SetType kind) {
            return Set.of(block);
          }

          public Set<BlockState> states(ResourceId id) {
            return Set.of(south, north);
          }
        };
    var terminal =
        Parser.document("query s{minecraft:oak_stairs} order by id limit 1;")
            .terminals()
            .getFirst();
    var expression = Compiler.symbolic().compile("s{minecraft:oak_stairs}", SetType.STATE);
    var output = new QueryRunner().run(terminal, expression, List.of(), registry, FACTS, 2);
    assertEquals(List.of(north), output.matches());
  }
}

package io.github.billstark001.latticium.planning;

import static org.junit.jupiter.api.Assertions.*;

import io.github.billstark001.latticium.dsl.Compiler;
import io.github.billstark001.latticium.dsl.Model.*;
import io.github.billstark001.latticium.dsl.Parser;
import io.github.billstark001.latticium.dsl.Syntax;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class QueryRunnerEdgeTest {
  private static final ResourceId OVERWORLD = ResourceId.parse("minecraft:overworld");
  private static final ResourceId NETHER = ResourceId.parse("minecraft:the_nether");

  private static SectionScanner.Bounds pointBounds(Position p) {
    return new SectionScanner.Bounds(p.dimension(), p.x(), p.y(), p.z(), p.x(), p.y(), p.z());
  }

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
    var orphanAny =
        new Syntax.Terminal("query", terminal.expression(), List.of(), null, true, terminal.span());
    assertThrows(
        IllegalArgumentException.class,
        () -> runner.run(orphanAny, expression, bounds, null, FACTS, 1));
    var orderedAny =
        new Syntax.Terminal(
            "query",
            terminal.expression(),
            List.of(new Syntax.Order("x", false)),
            1,
            true,
            terminal.span());
    assertThrows(
        IllegalArgumentException.class,
        () -> runner.run(orderedAny, expression, bounds, null, FACTS, 1));
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

  @Test
  void overlappingBoundsStillConsumeTheVisitBudget() {
    var terminal = Parser.document("count all();").terminals().getFirst();
    var expression = Compiler.symbolic().compile("all()", SetType.POS);
    var single = new SectionScanner.Bounds(OVERWORLD, 0, 0, 0, 0, 0, 0);
    var runner = new QueryRunner();
    assertThrows(
        IllegalArgumentException.class,
        () -> runner.run(terminal, expression, List.of(single, single), null, FACTS, 1));
    assertEquals(
        1, runner.run(terminal, expression, List.of(single, single), null, FACTS, 2).count());
  }

  @Test
  void distanceOrderingBreaksFloatingPointTiesExactly() {
    int far = 1_000_000_000;
    var farther = new Position(OVERWORLD, far + 1, far - 1, 0);
    var nearer = new Position(OVERWORLD, far, far, 0);
    var terminal =
        Parser.document("query all() order by distance2(player) limit 1;").terminals().getFirst();
    var expression = Compiler.symbolic().compile("all()", SetType.POS);
    var bounds = List.of(pointBounds(farther), pointBounds(nearer));
    assertEquals(
        List.of(nearer),
        new QueryRunner().run(terminal, expression, bounds, null, FACTS, 2).matches());

    var extremeNear = new Position(OVERWORLD, Integer.MAX_VALUE, Integer.MAX_VALUE, 0);
    var extremeFar = new Position(OVERWORLD, Integer.MAX_VALUE, Integer.MAX_VALUE, 1);
    var extremeBounds = List.of(pointBounds(extremeFar), pointBounds(extremeNear));
    assertEquals(
        List.of(extremeNear),
        new QueryRunner().run(terminal, expression, extremeBounds, null, FACTS, 2).matches());
  }

  @Test
  void distanceOrderingSamplesThePlayerOnlyOncePerQuery() {
    int[] reads = {0};
    Facts sampled =
        new Facts() {
          public Optional<WorldCell> world(Position position) {
            return FACTS.world(position);
          }

          public TargetCell target(Position position) {
            return FACTS.target(position);
          }

          public Optional<Position> player() {
            reads[0]++;
            return Optional.of(new Position(OVERWORLD, 0, 0, 0));
          }

          public Optional<Set<ResourceId>> inventory() {
            return FACTS.inventory();
          }

          public Truth selection(String name, Position position) {
            return FACTS.selection(name, position);
          }
        };
    var terminal =
        Parser.document("query all() order by distance2(player);").terminals().getFirst();
    var expression = Compiler.symbolic().compile("all()", SetType.POS);
    new QueryRunner()
        .run(
            terminal,
            expression,
            List.of(pointBounds(new Position(OVERWORLD, 1, 0, 0))),
            null,
            sampled,
            1);
    assertEquals(1, reads[0]);
  }

  @Test
  void topKMatchesThePrefixOfFullOrdering() {
    var random = new Random(0x51A7L);
    var bounds =
        IntStream.range(0, 50)
            .mapToObj(
                ignored ->
                    pointBounds(
                        new Position(
                            OVERWORLD,
                            random.nextInt(-3, 4),
                            random.nextInt(-3, 4),
                            random.nextInt(-3, 4))))
            .toList();
    var expression = Compiler.symbolic().compile("all()", SetType.POS);
    var runner = new QueryRunner();
    for (String order :
        List.of(
            "x asc, y desc, z asc",
            "distance2(player) asc, y desc, z asc, x asc",
            "distance2(player) desc")) {
      var full = Parser.document("query all() order by " + order + ";").terminals().getFirst();
      var limited =
          Parser.document("query all() order by " + order + " limit 7;").terminals().getFirst();
      var all = runner.run(full, expression, bounds, null, FACTS, bounds.size()).matches();
      var first = runner.run(limited, expression, bounds, null, FACTS, bounds.size()).matches();
      assertEquals(all.subList(0, Math.min(7, all.size())), first, order);
    }
  }
}

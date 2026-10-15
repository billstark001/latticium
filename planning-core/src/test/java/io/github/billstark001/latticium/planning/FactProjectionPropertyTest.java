package io.github.billstark001.latticium.planning;

import static io.github.billstark001.latticium.dsl.FactDependencies.Fact.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.billstark001.latticium.dsl.Compiler;
import io.github.billstark001.latticium.dsl.Model.*;
import io.github.billstark001.latticium.planning.SectionScanner.Bounds;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Capturing only declared reads must preserve three-valued results for composed expressions. */
class FactProjectionPropertyTest {
  private static final ResourceId DIM = ResourceId.parse("overworld");
  private static final ResourceId STONE = ResourceId.parse("stone");
  private static final ResourceId AIR = ResourceId.parse("air");
  private static final ResourceId WATER = ResourceId.parse("water");
  private static final ResourceId PLAINS = ResourceId.parse("plains");
  private static final List<String> LEAVES =
      List.of(
          "current(b{stone})",
          "target(b{stone})",
          "light(5..10)",
          "solid()",
          "surface()",
          "fluid(f{water})",
          "biome(m{plains})",
          "sphere(player,2)",
          "selection(\"build\")",
          "x=-2..2",
          "has_target()",
          "matches_target()");

  @Test
  void seededCompositionsPreservePartialFactsAndIntegerEdgePaths() {
    var random = new Random(0x20261015L);
    var compiler = Compiler.symbolic().targetAvailable(true);
    var session = new Host.SessionId();
    for (int sample = 0; sample < 500; sample++) {
      String expression = expression(random, 4);
      var bound = compiler.compile(expression, SetType.POS);
      int x =
          switch (sample % 3) {
            case 0 -> Integer.MIN_VALUE;
            case 1 -> Integer.MAX_VALUE - 2;
            default -> -1;
          };
      var evaluated = new Bounds(DIM, x, -1, -1, x + 2, 1, 1);
      var player = new Position(DIM, x, 0, 0);
      var full = facts(player, sample);
      var allowed =
          new CellWindow(
              new Bounds(
                  DIM,
                  (int) Math.max(Integer.MIN_VALUE, (long) x - bound.radius()),
                  -1 - bound.radius(),
                  -1 - bound.radius(),
                  (int) Math.min(Integer.MAX_VALUE, (long) x + 2 + bound.radius()),
                  1 + bound.radius(),
                  1 + bound.radius()));
      var window = CaptureColumns.window(allowed, evaluated, bound.dependencies());
      var columns = new CaptureColumns(window, evaluated, bound.dependencies());
      var cells = new WorldCell[window.size()];
      for (int index = columns.nextWorldIndex(0);
          index >= 0;
          index = columns.nextWorldIndex(index + 1)) {
        var original = full.world(window.position(index)).orElse(null);
        if (original == null) continue;
        cells[index] =
            new WorldCell(
                columns.needs(STATE, index) ? original.state() : null,
                columns.needs(BIOME, index) ? original.biome() : null,
                columns.needs(FLUID, index) ? original.fluid() : null,
                columns.needs(LIGHT, index) ? original.light() : null,
                columns.needs(SOLID, index) ? original.solid() : null);
      }
      var targets =
          columns.bounds(TARGET).map(box -> TargetSlice.capture(session, box, full::target));
      var projected =
          new FactWindow(
              session,
              window,
              cells,
              targets.orElse(null),
              bound.dependencies().needs(SELECTION)
                  ? Map.of("build", List.of(evaluated))
                  : Map.of(),
              bound.dependencies().needs(PLAYER) ? player : null,
              null);
      for (var position : List.of(player, player.offset(1, 0, 0), player.offset(2, 0, 0)))
        assertEquals(
            bound.at(full, position),
            bound.at(projected, position),
            () -> expression + " at " + position);
    }
  }

  private static String expression(Random random, int depth) {
    if (depth == 0 || random.nextInt(4) == 0) return LEAVES.get(random.nextInt(LEAVES.size()));
    return switch (random.nextInt(5)) {
      case 0 -> "!(" + expression(random, depth - 1) + ")";
      case 1 -> "adjacent(" + expression(random, depth - 1) + ")";
      case 2 ->
          "offset("
              + (random.nextInt(5) - 2)
              + ",0,"
              + (random.nextInt(5) - 2)
              + ","
              + expression(random, depth - 1)
              + ")";
      default ->
          "("
              + expression(random, depth - 1)
              + (random.nextBoolean() ? " & " : " | ")
              + expression(random, depth - 1)
              + ")";
    };
  }

  private static Facts facts(Position player, int salt) {
    return new Facts() {
      private int hash(Position position) {
        return Math.floorMod(position.x() * 31 + position.y() * 17 + position.z() * 7 + salt, 19);
      }

      public Optional<WorldCell> world(Position position) {
        int h = hash(position);
        return h == 0
            ? Optional.empty()
            : Optional.of(
                new WorldCell(
                    h % 7 == 0 ? null : new BlockState(h % 3 == 0 ? AIR : STONE, Map.of()),
                    h % 5 == 0 ? null : PLAINS,
                    h % 4 == 0 ? null : WATER,
                    h % 6 == 0 ? null : h % 16,
                    h % 8 == 0 ? null : h % 2 == 0));
      }

      public TargetCell target(Position position) {
        return switch (hash(position) % 4) {
          case 0 -> new TargetCell.Unknown("Missing fixture target");
          case 1 -> new TargetCell.DontCare();
          case 2 -> new TargetCell.Clear();
          default -> new TargetCell.Exact(new BlockState(STONE, Map.of()));
        };
      }

      public Optional<Position> player() {
        return Optional.of(player);
      }

      public Optional<Set<ResourceId>> inventory() {
        return Optional.empty();
      }

      public Truth selection(String name, Position position) {
        return position.x() >= player.x()
                && (long) position.x() <= (long) player.x() + 2
                && Math.abs((long) position.y()) <= 1
                && Math.abs((long) position.z()) <= 1
            ? Truth.TRUE
            : Truth.FALSE;
      }
    };
  }
}

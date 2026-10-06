package io.github.billstark001.latticium.dsl;

import static io.github.billstark001.latticium.dsl.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class AdjacentEvaluationTest {
  private static final ResourceId DIM = ResourceId.parse("overworld");
  private static final Position ORIGIN = new Position(DIM, 0, 0, 0);

  @Test
  void sixteenNestedUnionsReadEachReachableCellOnce() {
    var facts = new CountingFacts(p -> false);
    var bound = Compiler.symbolic().compile(nested(16, false), SetType.POS);
    assertTimeout(Duration.ofSeconds(2), () -> assertEquals(Truth.FALSE, bound.at(facts, ORIGIN)));
    assertEquals(reachableCount(16), facts.reads);
  }

  @Test
  void mixedExpressionsShareOneBoundedEvaluationFrame() {
    var facts = new CountingFacts(p -> false);
    var bound = Compiler.symbolic().compile(nested(16, true), SetType.POS);
    assertTimeout(Duration.ofSeconds(2), () -> assertEquals(Truth.FALSE, bound.at(facts, ORIGIN)));
    assertEquals(reachableCount(16), facts.reads);
    var expensive = Compiler.symbolic().compile(nested(21, true), SetType.POS);
    facts.reads = 0;
    assertTimeout(
        Duration.ofSeconds(2), () -> assertEquals(Truth.UNKNOWN, expensive.at(facts, ORIGIN)));
    assertTrue(facts.reads < EvaluationFacts.MAX_EVALUATIONS);
  }

  @Test
  void separateTopLevelCallsDoNotShareMemoizedFacts() {
    var bound = Compiler.symbolic().compile(nested(4, true), SetType.POS);
    var falseFacts = new CountingFacts(p -> false);
    var trueFacts = new CountingFacts(p -> true);
    assertEquals(Truth.FALSE, bound.at(falseFacts, ORIGIN));
    assertEquals(Truth.TRUE, bound.at(trueFacts, ORIGIN));
    assertEquals(Truth.FALSE, bound.at(falseFacts, ORIGIN));
    assertEquals(2 * reachableCount(4), falseFacts.reads);
  }

  @Test
  void collapsedUnionsMatchNaiveThreeValuedPathsIncludingIntegerEdges() {
    var facts =
        new CountingFacts(
            p -> {
              long hash = 31L * p.x() + 17L * p.y() + p.z();
              return hash % 7 == 0 ? null : hash % 11 == 0;
            });
    var leaf = Compiler.symbolic().compile("solid()", SetType.POS);
    for (int depth = 1; depth <= 5; depth++) {
      var bound = Compiler.symbolic().compile(nested(depth, false), SetType.POS);
      for (var pos :
          new Position[] {
            ORIGIN,
            ORIGIN.offset(3, -2, 5),
            new Position(DIM, Integer.MAX_VALUE, 0, 0),
            new Position(DIM, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE)
          })
        assertEquals(
            naive(leaf, facts, pos, depth), bound.at(facts, pos), "depth=" + depth + ", " + pos);
    }
    var falseFacts = new CountingFacts(p -> false);
    var edge = new Position(DIM, Integer.MAX_VALUE, 0, 0);
    assertEquals(
        Truth.UNKNOWN,
        Compiler.symbolic().compile(nested(16, false), SetType.POS).at(falseFacts, edge));
    assertTrue(falseFacts.reads < 10_000);
  }

  private static String nested(int depth, boolean mixed) {
    String result = "solid()";
    for (int i = 0; i < depth; i++)
      result = "adjacent(" + (mixed ? "!(!" + result + ")" : result) + ")";
    return result;
  }

  private static int reachableCount(int depth) {
    int result = 0;
    for (int x = -depth; x <= depth; x++)
      for (int y = -depth; y <= depth; y++)
        for (int z = -depth; z <= depth; z++) {
          int distance = Math.abs(x) + Math.abs(y) + Math.abs(z);
          if (distance <= depth && (depth - distance) % 2 == 0) result++;
        }
    return result;
  }

  private static Truth naive(Compiler.Bound leaf, Facts facts, Position pos, int depth) {
    if (depth == 0) return leaf.at(facts, pos);
    Truth result = Truth.FALSE;
    for (int[] delta :
        new int[][] {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}) {
      Position next;
      try {
        next = pos.offset(delta[0], delta[1], delta[2]);
      } catch (ArithmeticException overflow) {
        result = result.or(Truth.UNKNOWN);
        continue;
      }
      result = result.or(naive(leaf, facts, next, depth - 1));
      if (result == Truth.TRUE) break;
    }
    return result;
  }

  private static final class CountingFacts implements Facts {
    private final Function<Position, Boolean> solid;
    private int reads;

    CountingFacts(Function<Position, Boolean> solid) {
      this.solid = solid;
    }

    public Optional<WorldCell> world(Position pos) {
      reads++;
      return Optional.of(new WorldCell(null, null, null, null, solid.apply(pos)));
    }

    public TargetCell target(Position pos) {
      return new TargetCell.Unknown("not captured");
    }

    public Optional<Position> player() {
      return Optional.empty();
    }

    public Optional<Set<ResourceId>> inventory() {
      return Optional.empty();
    }

    public Truth selection(String name, Position pos) {
      return Truth.UNKNOWN;
    }
  }
}

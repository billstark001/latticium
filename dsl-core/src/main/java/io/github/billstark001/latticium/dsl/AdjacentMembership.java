package io.github.billstark001.latticium.dsl;

import static io.github.billstark001.latticium.dsl.Model.*;

import io.github.billstark001.latticium.dsl.Compiler.Bound;
import io.github.billstark001.latticium.dsl.FactDependencies.Offset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Collapses repeated face-neighbor unions while preserving intermediate overflow semantics. */
final class AdjacentMembership implements Compiler.Membership {
  private static final int MAX_FLAT_DEPTH = 32;
  private static final List<Offset> FACES =
      List.of(
          new Offset(1, 0, 0),
          new Offset(-1, 0, 0),
          new Offset(0, 1, 0),
          new Offset(0, -1, 0),
          new Offset(0, 0, 1),
          new Offset(0, 0, -1));
  // Each depth has one immutable table shared by independently compiled expressions.
  private static final Map<Integer, List<Offset>> TABLES = new ConcurrentHashMap<>();
  private final Bound child;
  private final Bound leaf;
  private final int depth;
  private final List<Offset> reachable;

  AdjacentMembership(Bound child) {
    this.child = child;
    if (child.membership() instanceof AdjacentMembership inner) {
      leaf = inner.leaf;
      depth = inner.depth + 1;
    } else {
      leaf = child;
      depth = 1;
    }
    reachable =
        depth <= MAX_FLAT_DEPTH
            ? TABLES.computeIfAbsent(depth, AdjacentMembership::reachable)
            : List.of();
  }

  public Truth test(Facts facts, Position pos, Object element) {
    if (!reachable.isEmpty() && safeSteps(pos, depth)) {
      Facts frame =
          facts instanceof EvaluationFacts || leaf.radius() == 0
              ? facts
              : new EvaluationFacts(facts);
      Truth result = Truth.FALSE;
      for (var offset : reachable) {
        result = result.or(atOffset(leaf, frame, pos, offset));
        if (result == Truth.TRUE) break;
      }
      return result;
    }
    // Near signed-integer boundaries, net offsets alone would lose paths that overflow midway.
    Facts frame =
        facts instanceof EvaluationFacts || child.radius() == 0
            ? facts
            : new EvaluationFacts(facts);
    Truth result = Truth.FALSE;
    for (var face : FACES) {
      result = result.or(atOffset(child, frame, pos, face));
      if (result == Truth.TRUE) break;
    }
    return result;
  }

  private static boolean safeSteps(Position pos, int depth) {
    return safeAxis(pos.x(), depth) && safeAxis(pos.y(), depth) && safeAxis(pos.z(), depth);
  }

  private static boolean safeAxis(int coordinate, int depth) {
    return (long) coordinate - depth >= Integer.MIN_VALUE
        && (long) coordinate + depth <= Integer.MAX_VALUE;
  }

  private static Truth atOffset(Bound bound, Facts facts, Position pos, Offset offset) {
    Position neighbor;
    try {
      neighbor = pos.offset(offset.x(), offset.y(), offset.z());
    } catch (ArithmeticException overflow) {
      return Truth.UNKNOWN;
    }
    return bound.at(facts, neighbor);
  }

  private static List<Offset> reachable(int depth) {
    if (depth == 1) return FACES;
    var result = new ArrayList<Offset>();
    for (int x = -depth; x <= depth; x++) {
      int yz = depth - Math.abs(x);
      for (int y = -yz; y <= yz; y++) {
        int zMax = yz - Math.abs(y);
        for (int z = -zMax; z <= zMax; z++) {
          int distance = Math.abs(x) + Math.abs(y) + Math.abs(z);
          if ((depth - distance) % 2 == 0) result.add(new Offset(x, y, z));
        }
      }
    }
    return List.copyOf(result);
  }
}

package io.github.billstark001.latticium.planning;

import io.github.billstark001.latticium.dsl.Compiler;
import io.github.billstark001.latticium.dsl.Model.*;
import io.github.billstark001.latticium.dsl.Syntax;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/** Executes one read-only terminal against an explicit finite enumeration domain. */
public final class QueryRunner {
  public record Output(List<Object> matches, long unknownCount, Truth exists, long count) {
    public Output {
      matches = List.copyOf(matches);
    }
  }

  public Output run(
      Syntax.Terminal terminal,
      Compiler.Bound expression,
      List<SectionScanner.Bounds> bounds,
      Registry registry,
      Facts facts,
      int maxCells) {
    if (maxCells <= 0) throw new IllegalArgumentException("Positive query budget required");
    var matches = new ArrayList<Object>();
    long unknown = 0;
    int examined = 0;
    if (expression.type() == SetType.POS) {
      if (bounds.isEmpty())
        throw new IllegalArgumentException("PosSet query requires finite bounds");
      var seen = new HashSet<Position>();
      for (var b : bounds)
        for (long y = b.minY(); y <= b.maxY(); y++)
          for (long z = b.minZ(); z <= b.maxZ(); z++)
            for (long x = b.minX(); x <= b.maxX(); x++) {
              var p = new Position(b.dimension(), (int) x, (int) y, (int) z);
              if (!seen.add(p)) continue;
              if (++examined > maxCells)
                throw new IllegalArgumentException("Query cell budget exceeded");
              Truth t = expression.at(facts, p);
              if (t == Truth.TRUE) matches.add(p);
              else if (t == Truth.UNKNOWN) unknown++;
            }
    } else {
      if (expression.type() == SetType.STATE) {
        for (var block : registry.universe(SetType.BLOCK))
          for (var state : registry.states(block)) {
            if (++examined > maxCells)
              throw new IllegalArgumentException("Query cell budget exceeded");
            Truth t = expression.contains(facts, state);
            if (t == Truth.TRUE) matches.add(state);
            else if (t == Truth.UNKNOWN) unknown++;
          }
      } else
        for (var id : registry.universe(expression.type())) {
          if (++examined > maxCells)
            throw new IllegalArgumentException("Query cell budget exceeded");
          Truth t = expression.contains(facts, id);
          if (t == Truth.TRUE) matches.add(id);
          else if (t == Truth.UNKNOWN) unknown++;
        }
    }
    long total = matches.size();
    Truth exists = total > 0 ? Truth.TRUE : unknown > 0 ? Truth.UNKNOWN : Truth.FALSE;
    if (terminal.kind().equals("query")) {
      Comparator<Object> comparator = null;
      for (var order : terminal.order()) {
        Comparator<Object> next = orderComparator(order, facts);
        comparator = comparator == null ? next : comparator.thenComparing(next);
      }
      if (comparator != null) matches.sort(comparator);
      if (terminal.limit() != null && matches.size() > terminal.limit())
        matches = new ArrayList<>(matches.subList(0, terminal.limit()));
      return new Output(matches, unknown, exists, total);
    }
    return new Output(List.of(), unknown, exists, total);
  }

  private static Comparator<Object> orderComparator(Syntax.Order order, Facts facts) {
    Comparator<Object> comparator =
        switch (order.key()) {
          case "x" -> Comparator.comparingInt(v -> ((Position) v).x());
          case "y" -> Comparator.comparingInt(v -> ((Position) v).y());
          case "z" -> Comparator.comparingInt(v -> ((Position) v).z());
          case "id" -> Comparator.comparing(Object::toString);
          case "distance2(player)" -> {
            var player =
                facts
                    .player()
                    .orElseThrow(() -> new IllegalArgumentException("Player position unavailable"));
            yield Comparator.comparingDouble(
                v -> {
                  var p = (Position) v;
                  double x = (double) p.x() - player.x(),
                      y = (double) p.y() - player.y(),
                      z = (double) p.z() - player.z();
                  return x * x + y * y + z * z;
                });
          }
          default -> throw new IllegalArgumentException("Invalid order key");
        };
    return order.descending() ? comparator.reversed() : comparator;
  }
}

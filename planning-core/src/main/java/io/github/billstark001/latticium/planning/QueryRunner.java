package io.github.billstark001.latticium.planning;

import io.github.billstark001.latticium.dsl.Compiler;
import io.github.billstark001.latticium.dsl.Model.*;
import io.github.billstark001.latticium.dsl.Syntax;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/** Executes one read-only terminal against an explicit finite enumeration domain. */
public final class QueryRunner {
  private static final Set<String> TERMINAL_KINDS = Set.of("query", "count", "exists");

  private record Match(Object value, long sequence, String idKey) {}

  private static final class MatchCollector {
    private final boolean query;
    private final Integer limit;
    private final boolean needsIdKey;
    private final Comparator<Match> order;
    private final List<Match> matches = new ArrayList<>();
    private final PriorityQueue<Match> best;
    private long count;
    private long unknown;

    MatchCollector(Syntax.Terminal terminal, Facts facts) {
      query = terminal.kind().equals("query");
      limit = terminal.limit();
      needsIdKey = terminal.order().stream().anyMatch(item -> item.key().equals("id"));
      Comparator<Match> comparator = null;
      if (query) {
        for (var item : terminal.order()) {
          var next = orderComparator(item, facts);
          comparator = comparator == null ? next : comparator.thenComparing(next);
        }
      }
      order = comparator == null ? null : comparator.thenComparingLong(Match::sequence);
      best = order != null && limit != null ? new PriorityQueue<>(order.reversed()) : null;
    }

    void accept(Truth truth, Object value) {
      if (truth == Truth.UNKNOWN) {
        unknown++;
        return;
      }
      if (truth != Truth.TRUE) return;
      long sequence = count++;
      if (!query) return;
      var match = new Match(value, sequence, needsIdKey ? idKey(value) : null);
      if (best != null) {
        if (best.size() < limit) best.add(match);
        else if (order.compare(match, best.peek()) < 0) {
          best.remove();
          best.add(match);
        }
      } else if (limit == null || matches.size() < limit) {
        matches.add(match);
      }
    }

    Output output() {
      if (!query) return new Output(List.of(), unknown, exists(), count);
      if (best != null) matches.addAll(best);
      if (order != null) matches.sort(order);
      return new Output(matches.stream().map(Match::value).toList(), unknown, exists(), count);
    }

    private Truth exists() {
      return count > 0 ? Truth.TRUE : unknown > 0 ? Truth.UNKNOWN : Truth.FALSE;
    }
  }

  /** Query values plus complete true/unknown counts over the enumerated domain. */
  public record Output(List<Object> matches, long unknownCount, Truth exists, long count) {
    public Output {
      matches = List.copyOf(matches);
    }
  }

  /** Enumerates a finite domain, counting unknowns separately and enforcing the cell budget. */
  public Output run(
      Syntax.Terminal terminal,
      Compiler.Bound expression,
      List<SectionScanner.Bounds> bounds,
      Registry registry,
      Facts facts,
      int maxCells) {
    if (maxCells <= 0) throw new IllegalArgumentException("Positive query budget required");
    validate(terminal, expression.type(), bounds, facts);
    var collector = new MatchCollector(terminal, facts);
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
              collector.accept(expression.at(facts, p), p);
            }
    } else {
      if (expression.type() == SetType.STATE) {
        for (var block : registry.universe(SetType.BLOCK))
          for (var state : registry.states(block)) {
            if (++examined > maxCells)
              throw new IllegalArgumentException("Query cell budget exceeded");
            collector.accept(expression.contains(facts, state), state);
          }
      } else
        for (var id : registry.universe(expression.type())) {
          if (++examined > maxCells)
            throw new IllegalArgumentException("Query cell budget exceeded");
          collector.accept(expression.contains(facts, id), id);
        }
    }
    return collector.output();
  }

  private static void validate(
      Syntax.Terminal terminal, SetType type, List<SectionScanner.Bounds> bounds, Facts facts) {
    if (!TERMINAL_KINDS.contains(terminal.kind())
        || terminal.limit() != null && terminal.limit() <= 0
        || !terminal.kind().equals("query")
            && (!terminal.order().isEmpty() || terminal.limit() != null)
        || terminal.limit() != null && terminal.order().isEmpty() && !terminal.any())
      throw new IllegalArgumentException("Invalid query terminal");
    if (terminal.order().stream()
        .anyMatch(
            order -> type == SetType.POS ? order.key().equals("id") : !order.key().equals("id")))
      throw new IllegalArgumentException("Invalid order for set type");
    if (terminal.order().stream().anyMatch(order -> order.key().equals("distance2(player)"))) {
      var player =
          facts
              .player()
              .orElseThrow(() -> new IllegalArgumentException("Player position unavailable"));
      if (bounds.stream().anyMatch(bound -> !bound.dimension().equals(player.dimension())))
        throw new IllegalArgumentException("Cannot order positions across dimensions by distance");
    }
  }

  private static String idKey(Object value) {
    return value instanceof BlockState state ? state.canonicalId() : value.toString();
  }

  private static Comparator<Match> orderComparator(Syntax.Order order, Facts facts) {
    Comparator<Match> comparator =
        switch (order.key()) {
          case "x" -> Comparator.comparingInt(match -> ((Position) match.value()).x());
          case "y" -> Comparator.comparingInt(match -> ((Position) match.value()).y());
          case "z" -> Comparator.comparingInt(match -> ((Position) match.value()).z());
          case "id" -> Comparator.comparing(Match::idKey);
          case "distance2(player)" -> {
            var player =
                facts
                    .player()
                    .orElseThrow(() -> new IllegalArgumentException("Player position unavailable"));
            yield Comparator.comparingDouble(
                match -> {
                  var p = (Position) match.value();
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

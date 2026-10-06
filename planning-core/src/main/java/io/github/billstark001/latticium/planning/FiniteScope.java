package io.github.billstark001.latticium.planning;

import io.github.billstark001.latticium.dsl.Model.Position;
import io.github.billstark001.latticium.dsl.Parser;
import io.github.billstark001.latticium.dsl.Syntax.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/** Conservative finite enumeration bounds for a validated scope expression. */
public final class FiniteScope {
  private static final int MAX_BOUNDS = 65_536;
  private static final int MAX_INTERSECTION_COMPARISONS = 1_000_000;

  private record Shift(int x, int y, int z) {}

  private static final List<Shift> NEIGHBORS =
      List.of(
          new Shift(1, 0, 0),
          new Shift(-1, 0, 0),
          new Shift(0, 1, 0),
          new Shift(0, -1, 0),
          new Shift(0, 0, 1),
          new Shift(0, 0, -1));

  private static final class Budget {
    private int comparisons;

    void compare() {
      if (++comparisons > MAX_INTERSECTION_COMPARISONS)
        throw new IllegalArgumentException("Finite scope intersection work budget exceeded");
    }
  }

  private static final class Unbounded extends IllegalArgumentException {
    Unbounded(String message) {
      super(message);
    }
  }

  private FiniteScope() {}

  /** Returns inclusive finite bounds, rejecting missing selections and coordinate overflow. */
  public static List<SectionScanner.Bounds> bounds(
      String expression, Position player, Host.SelectionSource selections, Host.SessionId session) {
    return bounds(Parser.expression(expression), player, selections, session, new Budget());
  }

  private static List<SectionScanner.Bounds> bounds(
      Expr expression,
      Position player,
      Host.SelectionSource selections,
      Host.SessionId session,
      Budget budget) {
    if (expression instanceof Call call) {
      var args = call.args();
      if (call.name().equals("box") && args.size() == 6) {
        int x0 = integer(args.get(0)), y0 = integer(args.get(1)), z0 = integer(args.get(2));
        int x1 = integer(args.get(3)), y1 = integer(args.get(4)), z1 = integer(args.get(5));
        return List.of(
            new SectionScanner.Bounds(
                player.dimension(),
                Math.min(x0, x1),
                Math.min(y0, y1),
                Math.min(z0, z1),
                Math.max(x0, x1),
                Math.max(y0, y1),
                Math.max(z0, z1)));
      }
      if (call.name().equals("selection") && args.size() == 1) {
        String name =
            args.getFirst() instanceof Text text
                ? text.value()
                : args.getFirst() instanceof Name nameExpr ? nameExpr.value() : null;
        if (name == null) throw new IllegalArgumentException("Invalid selection name");
        var supplied = selections.finiteBounds(name, session);
        if (supplied == null)
          throw new IllegalArgumentException("Selection provider returned null: " + name);
        var selected = bounded(supplied);
        if (selected.isEmpty())
          throw new IllegalArgumentException("Selection unavailable or empty: " + name);
        return selected;
      }
      if (call.name().equals("offset") && args.size() == 4) {
        var inner = bounds(args.get(3), player, selections, session, budget);
        return shifted(inner, integer(args.get(0)), integer(args.get(1)), integer(args.get(2)));
      }
      if (call.name().equals("adjacent") && args.size() == 1) {
        var inner = bounds(args.getFirst(), player, selections, session, budget);
        var result = new LinkedHashSet<SectionScanner.Bounds>();
        for (var shift : NEIGHBORS) {
          result.addAll(shifted(inner, shift.x(), shift.y(), shift.z()));
          checkSize(result.size());
        }
        return List.copyOf(result);
      }
      if (call.name().equals("sphere") && args.size() == 2) {
        int radius = integer(args.get(1));
        if (radius < 0) throw new IllegalArgumentException("Negative radius");
        int x = player.x(), y = player.y(), z = player.z();
        if (args.getFirst() instanceof Call point && point.name().equals("point")) {
          if (point.args().size() != 3) throw new IllegalArgumentException("Invalid point");
          x = integer(point.args().get(0));
          y = integer(point.args().get(1));
          z = integer(point.args().get(2));
        } else if (!(args.getFirst() instanceof Name n) || !n.value().equals("player"))
          throw new IllegalArgumentException("Invalid sphere anchor");
        try {
          return List.of(
              new SectionScanner.Bounds(
                  player.dimension(),
                  Math.subtractExact(x, radius),
                  Math.subtractExact(y, radius),
                  Math.subtractExact(z, radius),
                  Math.addExact(x, radius),
                  Math.addExact(y, radius),
                  Math.addExact(z, radius)));
        } catch (ArithmeticException error) {
          throw new IllegalArgumentException("Sphere bounds exceed coordinate range", error);
        }
      }
    }
    if (expression instanceof Binary binary) {
      var left = safeBounds(binary.left(), player, selections, session, budget);
      var right = safeBounds(binary.right(), player, selections, session, budget);
      if (binary.operator() == '|') {
        if (left == null || right == null) throw new Unbounded("Union has no finite bounds");
        var result = new LinkedHashSet<>(left);
        result.addAll(right);
        checkSize(result.size());
        return List.copyOf(result);
      }
      if (left == null) {
        if (right == null) throw new Unbounded("Intersection has no finite bounds");
        return right;
      }
      if (right == null) return left;
      if (left.equals(right)) return left;
      var result = new LinkedHashSet<SectionScanner.Bounds>();
      for (var a : left)
        for (var b : right) {
          budget.compare();
          if (!a.dimension().equals(b.dimension())) continue;
          int x0 = Math.max(a.minX(), b.minX()), x1 = Math.min(a.maxX(), b.maxX());
          int y0 = Math.max(a.minY(), b.minY()), y1 = Math.min(a.maxY(), b.maxY());
          int z0 = Math.max(a.minZ(), b.minZ()), z1 = Math.min(a.maxZ(), b.maxZ());
          if (x0 <= x1 && y0 <= y1 && z0 <= z1) {
            result.add(new SectionScanner.Bounds(a.dimension(), x0, y0, z0, x1, y1, z1));
            checkSize(result.size());
          }
        }
      return List.copyOf(result);
    }
    throw new Unbounded("Scope has no enumerable finite bounds");
  }

  private static List<SectionScanner.Bounds> safeBounds(
      Expr expression,
      Position player,
      Host.SelectionSource selections,
      Host.SessionId session,
      Budget budget) {
    try {
      return bounds(expression, player, selections, session, budget);
    } catch (Unbounded ex) {
      return null;
    }
  }

  private static void checkSize(int size) {
    if (size > MAX_BOUNDS)
      throw new IllegalArgumentException("Finite scope exceeds " + MAX_BOUNDS + " bounds");
  }

  private static List<SectionScanner.Bounds> bounded(Collection<SectionScanner.Bounds> input) {
    checkSize(input.size());
    return List.copyOf(new LinkedHashSet<>(input));
  }

  /** offset(d,P) selects candidates at P's bounds minus d, not plus d. */
  private static List<SectionScanner.Bounds> shifted(
      List<SectionScanner.Bounds> input, int x, int y, int z) {
    var result = new ArrayList<SectionScanner.Bounds>(input.size());
    try {
      for (var box : input)
        result.add(
            new SectionScanner.Bounds(
                box.dimension(),
                Math.subtractExact(box.minX(), x),
                Math.subtractExact(box.minY(), y),
                Math.subtractExact(box.minZ(), z),
                Math.subtractExact(box.maxX(), x),
                Math.subtractExact(box.maxY(), y),
                Math.subtractExact(box.maxZ(), z)));
    } catch (ArithmeticException error) {
      throw new IllegalArgumentException("Offset bounds exceed coordinate range", error);
    }
    return List.copyOf(result);
  }

  private static int integer(Expr expression) {
    if (expression instanceof Name name)
      try {
        return Integer.parseInt(name.value());
      } catch (NumberFormatException ignored) {
        throw new IllegalArgumentException("Expected integer");
      }
    if (expression instanceof Range range
        && range.axis() == '\0'
        && range.range().min() != null
        && range.range().min().equals(range.range().max())) return range.range().min();
    throw new IllegalArgumentException("Expected integer");
  }
}

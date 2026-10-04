package io.github.billstark001.latticium.planning;

import io.github.billstark001.latticium.dsl.Model.Position;
import io.github.billstark001.latticium.dsl.Parser;
import io.github.billstark001.latticium.dsl.Syntax.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Conservative finite enumeration bounds for a validated scope expression. */
public final class FiniteScope {
  private FiniteScope() {}

  public static List<SectionScanner.Bounds> bounds(
      String expression, Position player, Host.SelectionSource selections, Host.SessionId session) {
    return bounds(Parser.expression(expression), player, selections, session);
  }

  private static List<SectionScanner.Bounds> bounds(
      Expr expression, Position player, Host.SelectionSource selections, Host.SessionId session) {
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
        return List.copyOf(selections.finiteBounds(name, session));
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
        return List.of(
            new SectionScanner.Bounds(
                player.dimension(),
                Math.subtractExact(x, radius),
                Math.subtractExact(y, radius),
                Math.subtractExact(z, radius),
                Math.addExact(x, radius),
                Math.addExact(y, radius),
                Math.addExact(z, radius)));
      }
    }
    if (expression instanceof Binary binary) {
      var left = safeBounds(binary.left(), player, selections, session);
      var right = safeBounds(binary.right(), player, selections, session);
      if (binary.operator() == '|') {
        if (left == null || right == null)
          throw new IllegalArgumentException("Union has no finite bounds");
        var result = new ArrayList<>(left);
        result.addAll(right);
        return List.copyOf(result);
      }
      if (left == null) return Objects.requireNonNull(right, "Intersection has no finite bounds");
      if (right == null) return left;
      var result = new ArrayList<SectionScanner.Bounds>();
      for (var a : left)
        for (var b : right) {
          if (!a.dimension().equals(b.dimension())) continue;
          int x0 = Math.max(a.minX(), b.minX()), x1 = Math.min(a.maxX(), b.maxX());
          int y0 = Math.max(a.minY(), b.minY()), y1 = Math.min(a.maxY(), b.maxY());
          int z0 = Math.max(a.minZ(), b.minZ()), z1 = Math.min(a.maxZ(), b.maxZ());
          if (x0 <= x1 && y0 <= y1 && z0 <= z1)
            result.add(new SectionScanner.Bounds(a.dimension(), x0, y0, z0, x1, y1, z1));
        }
      return List.copyOf(result);
    }
    throw new IllegalArgumentException("Scope has no enumerable finite bounds");
  }

  private static List<SectionScanner.Bounds> safeBounds(
      Expr expression, Position player, Host.SelectionSource selections, Host.SessionId session) {
    try {
      return bounds(expression, player, selections, session);
    } catch (IllegalArgumentException ex) {
      return null;
    }
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

package io.github.billstark001.latticium.dsl;

import static io.github.billstark001.latticium.dsl.Model.*;
import static io.github.billstark001.latticium.dsl.Syntax.*;

import io.github.billstark001.latticium.dsl.Compiler.Bound;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Bind the fixed, read-only DSL functions without mixing them with user declarations. */
final class BuiltinCompiler {
  private static final Pattern INTEGER_PROPERTY = Pattern.compile("-?[0-9]+");
  private static final Set<String> COMPARISONS = Set.of("lt", "le", "gt", "ge");

  private record Delta(int x, int y, int z) {}

  private record Point(int x, int y, int z) {}

  private static final List<Delta> NEIGHBORS =
      List.of(
          new Delta(1, 0, 0),
          new Delta(-1, 0, 0),
          new Delta(0, 1, 0),
          new Delta(0, -1, 0),
          new Delta(0, 0, 1),
          new Delta(0, 0, -1));

  private final Compiler compiler;

  BuiltinCompiler(Compiler compiler) {
    this.compiler = compiler;
  }

  Bound bind(Call c, Map<String, Bound> locals, int depth) {
    var a = c.args();
    String n = c.name();
    return switch (n) {
      case "all", "none" -> {
        arity(c, 0);
        yield constant(n.equals("all"));
      }
      case "current", "target" -> {
        arity(c, 1);
        if (n.equals("target")) compiler.needsTarget(c);
        var b = compiler.bind(a.getFirst(), null, locals, depth);
        if (b.type() != SetType.BLOCK && b.type() != SetType.STATE)
          throw new Failure("Expected BlockSet or StateSet", a.getFirst().span());
        yield new Bound(
            SetType.POS,
            (f, p, v) -> {
              if (n.equals("current"))
                return f.world(p)
                    .map(
                        w ->
                            w.state() == null
                                ? Truth.UNKNOWN
                                : b.contains(
                                    f, b.type() == SetType.BLOCK ? w.state().block() : w.state()))
                    .orElse(Truth.UNKNOWN);
              var t = f.target(p);
              if (t instanceof TargetCell.Unknown) return Truth.UNKNOWN;
              if (t instanceof TargetCell.Exact x)
                return b.contains(f, b.type() == SetType.BLOCK ? x.state().block() : x.state());
              return Truth.FALSE;
            },
            b.radius());
      }
      case "biome", "fluid" -> {
        arity(c, 1);
        var b =
            compiler.bind(
                a.getFirst(), n.equals("biome") ? SetType.BIOME : SetType.FLUID, locals, depth);
        yield new Bound(
            SetType.POS,
            (f, p, v) ->
                f.world(p)
                    .map(
                        w -> {
                          var id = n.equals("biome") ? w.biome() : w.fluid();
                          return id == null ? Truth.UNKNOWN : b.contains(f, id);
                        })
                    .orElse(Truth.UNKNOWN),
            b.radius());
      }
      case "states_of" -> {
        arity(c, 1);
        var b = compiler.bind(a.getFirst(), SetType.BLOCK, locals, depth);
        yield new Bound(
            SetType.STATE,
            (f, p, v) -> {
              var state = (BlockState) v;
              return compiler.legalState(state) ? b.contains(f, state.block()) : Truth.FALSE;
            },
            b.radius());
      }
      case "blocks_of" -> {
        arity(c, 1);
        var b = compiler.bind(a.getFirst(), SetType.STATE, locals, depth);
        yield new Bound(
            SetType.BLOCK,
            (f, p, v) -> {
              Truth result = Truth.FALSE;
              for (var state : compiler.registry().states((ResourceId) v)) {
                result = result.or(b.contains(f, state));
                if (result == Truth.TRUE) break;
              }
              return result;
            },
            b.radius());
      }
      case "property" -> {
        arity(c, 1);
        var pair = pair(a.getFirst());
        yield new Bound(
            SetType.STATE,
            (f, p, v) -> {
              var state = (BlockState) v;
              return truth(
                  compiler.legalState(state)
                      && pair.value().equals(state.properties().get(pair.key())));
            },
            0);
      }
      case "state" -> {
        arity(c, 1);
        var pair = pair(a.getFirst());
        yield new Bound(
            SetType.POS,
            (f, p, v) ->
                f.world(p)
                    .map(
                        w ->
                            w.state() == null
                                ? Truth.UNKNOWN
                                : truth(
                                    pair.value().equals(w.state().properties().get(pair.key()))))
                    .orElse(Truth.UNKNOWN),
            0);
      }
      case "property_range" -> {
        arity(c, 2);
        String key = name(a.get(0));
        var range = range(a.get(1));
        requireIntegerProperty(key, a.getFirst());
        yield new Bound(
            SetType.STATE,
            (f, p, v) -> {
              var state = (BlockState) v;
              if (!compiler.legalState(state)) return Truth.FALSE;
              String value = state.properties().get(key);
              if (value == null || !INTEGER_PROPERTY.matcher(value).matches()) return Truth.FALSE;
              try {
                return truth(range.contains(Integer.parseInt(value)));
              } catch (NumberFormatException ex) {
                return Truth.FALSE;
              }
            },
            0);
      }
      case "dimension" -> {
        arity(c, 1);
        var id = id(a.getFirst());
        yield new Bound(SetType.POS, (f, p, v) -> truth(p.dimension().equals(id)), 0);
      }
      case "box" -> {
        arity(c, 6);
        int[] q = new int[6];
        for (int i = 0; i < 6; i++) q[i] = number(a.get(i));
        int x0 = Math.min(q[0], q[3]),
            x1 = Math.max(q[0], q[3]),
            y0 = Math.min(q[1], q[4]),
            y1 = Math.max(q[1], q[4]),
            z0 = Math.min(q[2], q[5]),
            z1 = Math.max(q[2], q[5]);
        yield new Bound(
            SetType.POS,
            (f, p, v) ->
                truth(
                    p.x() >= x0
                        && p.x() <= x1
                        && p.y() >= y0
                        && p.y() <= y1
                        && p.z() >= z0
                        && p.z() <= z1),
            0);
      }
      case "selection" -> {
        arity(c, 1);
        String s = name(a.getFirst());
        yield new Bound(SetType.POS, (f, p, v) -> f.selection(s, p), 0);
      }
      case "offset" -> {
        arity(c, 4);
        int x = number(a.get(0)), y = number(a.get(1)), z = number(a.get(2));
        var b = compiler.bind(a.get(3), SetType.POS, locals, depth);
        long radius =
            Math.max(Math.max(Math.abs((long) x), Math.abs((long) y)), Math.abs((long) z))
                + b.radius();
        if (radius > compiler.maxRadius())
          throw new Failure("Read radius exceeds budget", c.span());
        yield new Bound(SetType.POS, (f, p, v) -> atOffset(b, f, p, x, y, z), (int) radius);
      }
      case "adjacent" -> {
        arity(c, 1);
        var b = compiler.bind(a.getFirst(), SetType.POS, locals, depth);
        if (b.radius() >= compiler.maxRadius())
          throw new Failure("Read radius exceeds budget", c.span());
        yield new Bound(
            SetType.POS,
            (f, p, v) -> {
              Truth result = Truth.FALSE;
              for (var d : NEIGHBORS) {
                result = result.or(atOffset(b, f, p, d.x(), d.y(), d.z()));
                if (result == Truth.TRUE) break;
              }
              return result;
            },
            b.radius() + 1);
      }
      case "has_target", "matches_target" -> {
        arity(c, 0);
        compiler.needsTarget(c);
        yield new Bound(
            SetType.POS,
            (f, p, v) -> {
              var t = f.target(p);
              if (t instanceof TargetCell.Unknown) return Truth.UNKNOWN;
              if (n.equals("has_target"))
                return truth(t instanceof TargetCell.Exact || t instanceof TargetCell.Clear);
              if (t instanceof TargetCell.DontCare) return Truth.FALSE;
              var world = f.world(p);
              if (world.isEmpty() || world.get().state() == null) return Truth.UNKNOWN;
              return t instanceof TargetCell.Exact x
                  ? truth(x.state().equals(world.get().state()))
                  : truth(isVanillaAir(world.get().state()));
            },
            0);
      }
      case "changed", "same", "compare" -> {
        arity(c, n.equals("compare") ? 2 : 1);
        compiler.needsTarget(c);
        String key = name(a.getFirst());
        String relation = n.equals("compare") ? name(a.get(1)) : "";
        if (n.equals("compare") && !COMPARISONS.contains(relation))
          throw new Failure("Invalid relation", c.span());
        if (n.equals("compare")) requireIntegerProperty(key, a.getFirst());
        yield new Bound(
            SetType.POS,
            (f, p, v) -> {
              var t = f.target(p);
              if (t instanceof TargetCell.Unknown) return Truth.UNKNOWN;
              if (!(t instanceof TargetCell.Exact x)) return Truth.FALSE;
              var w = f.world(p);
              if (w.isEmpty() || w.get().state() == null) return Truth.UNKNOWN;
              String current = w.get().state().properties().get(key),
                  target = x.state().properties().get(key);
              if (current == null || target == null) return Truth.FALSE;
              if (n.equals("changed")) return truth(!current.equals(target));
              if (n.equals("same")) return truth(current.equals(target));
              try {
                int diff = Integer.compare(Integer.parseInt(current), Integer.parseInt(target));
                return truth(
                    switch (relation) {
                      case "lt" -> diff < 0;
                      case "le" -> diff <= 0;
                      case "gt" -> diff > 0;
                      default -> diff >= 0;
                    });
              } catch (NumberFormatException ex) {
                return Truth.FALSE;
              }
            },
            0);
      }
      case "inventory" -> {
        arity(c, 1);
        var b = compiler.bind(a.getFirst(), SetType.ITEM, locals, depth);
        yield new Bound(
            SetType.ITEM,
            (f, p, v) -> {
              Truth eligible = b.contains(f, v);
              if (eligible == Truth.FALSE) return Truth.FALSE;
              return f.inventory()
                  .map(items -> eligible.and(truth(items.contains(v))))
                  .orElse(Truth.UNKNOWN);
            },
            b.radius());
      }
      case "sphere" -> {
        arity(c, 2);
        int r = number(a.get(1));
        if (r < 0 || r > compiler.maxRadius())
          throw new Failure("Sphere radius exceeds budget", c.span());
        Point fixed = null;
        if (a.getFirst() instanceof Call point && point.name().equals("point")) {
          arity(point, 3);
          fixed =
              new Point(
                  number(point.args().get(0)),
                  number(point.args().get(1)),
                  number(point.args().get(2)));
        } else if (!(a.getFirst() instanceof Name anchor) || !anchor.value().equals("player"))
          throw new Failure("Expected player or point anchor", a.getFirst().span());
        Point anchorPoint = fixed;
        yield new Bound(
            SetType.POS,
            (f, p, v) -> {
              if (anchorPoint != null)
                return truth(
                    withinSphere(
                        p.x(), p.y(), p.z(), anchorPoint.x(), anchorPoint.y(), anchorPoint.z(), r));
              var player = f.player();
              if (player.isEmpty()) return Truth.UNKNOWN;
              var center = player.get();
              return truth(
                  p.dimension().equals(center.dimension())
                      && withinSphere(p.x(), p.y(), p.z(), center.x(), center.y(), center.z(), r));
            },
            0);
      }
      case "light" -> {
        arity(c, 1);
        var r = range(a.getFirst());
        yield new Bound(
            SetType.POS,
            (f, p, v) ->
                f.world(p)
                    .map(w -> w.light() == null ? Truth.UNKNOWN : truth(r.contains(w.light())))
                    .orElse(Truth.UNKNOWN),
            0);
      }
      case "solid" -> {
        arity(c, 0);
        yield new Bound(
            SetType.POS,
            (f, p, v) ->
                f.world(p)
                    .map(w -> w.solid() == null ? Truth.UNKNOWN : truth(w.solid()))
                    .orElse(Truth.UNKNOWN),
            0);
      }
      case "surface" -> {
        arity(c, 0);
        yield new Bound(
            SetType.POS,
            (f, p, v) -> {
              var w = f.world(p);
              if (w.isEmpty() || w.get().state() == null) return Truth.UNKNOWN;
              if (isVanillaAir(w.get().state())) return Truth.FALSE;
              Truth result = Truth.FALSE;
              for (var d : NEIGHBORS) {
                Position neighbor;
                try {
                  neighbor = p.offset(d.x(), d.y(), d.z());
                } catch (ArithmeticException ex) {
                  result = result.or(Truth.UNKNOWN);
                  continue;
                }
                var near = f.world(neighbor);
                result =
                    result.or(
                        near.map(
                                cell ->
                                    cell.state() == null
                                        ? Truth.UNKNOWN
                                        : truth(isVanillaAir(cell.state())))
                            .orElse(Truth.UNKNOWN));
                if (result == Truth.TRUE) break;
              }
              return result;
            },
            1);
      }
      default -> compiler.bindPrimitive(c, locals, depth);
    };
  }

  private static Truth atOffset(Bound bound, Facts facts, Position pos, int x, int y, int z) {
    Position neighbor;
    try {
      neighbor = pos.offset(x, y, z);
    } catch (ArithmeticException ex) {
      return Truth.UNKNOWN;
    }
    return bound.at(facts, neighbor);
  }

  private void requireIntegerProperty(String key, Expr source) {
    if (compiler.symbolicRegistry()) return;
    boolean found = false;
    for (var block : compiler.registry().universe(SetType.BLOCK))
      for (var state : compiler.registry().states(block)) {
        var value = state.properties().get(key);
        if (value == null) continue;
        found = true;
        if (!INTEGER_PROPERTY.matcher(value).matches())
          throw new Failure("Property is not integer-valued: " + key, source.span());
        try {
          Integer.parseInt(value);
        } catch (NumberFormatException ex) {
          throw new Failure("Property value exceeds integer range: " + key, source.span());
        }
      }
    if (!found) throw new Failure("Unknown integer property: " + key, source.span());
  }

  private static Bound constant(boolean value) {
    return new Bound(SetType.POS, (f, p, v) -> truth(value), 0);
  }

  private static Truth truth(boolean value) {
    return value ? Truth.TRUE : Truth.FALSE;
  }

  private static boolean withinSphere(int px, int py, int pz, int qx, int qy, int qz, int radius) {
    long remaining = (long) radius * radius;
    long dx = (long) px - qx;
    if (Math.abs(dx) > radius) return false;
    remaining -= dx * dx;
    long dy = (long) py - qy;
    if (Math.abs(dy) > radius || dy * dy > remaining) return false;
    remaining -= dy * dy;
    long dz = (long) pz - qz;
    return Math.abs(dz) <= radius && dz * dz <= remaining;
  }

  static void arity(Call c, int expected) {
    if (c.args().size() != expected)
      throw new Failure("Expected " + expected + " arguments", c.span());
  }

  private static String name(Expr e) {
    if (e instanceof Name n) return n.value();
    if (e instanceof Text t) return t.value();
    throw new Failure("Expected name", e.span());
  }

  private static Pair pair(Expr e) {
    if (e instanceof Pair p) return p;
    throw new Failure("Expected property pair", e.span());
  }

  static int number(Expr e) {
    if (!(e instanceof Name n)) throw new Failure("Expected integer", e.span());
    try {
      return Integer.parseInt(n.value());
    } catch (NumberFormatException ex) {
      throw new Failure("Expected integer", e.span());
    }
  }

  private static IntRange range(Expr e) {
    if (e instanceof Range r && r.axis() == '\0') return r.range();
    if (e instanceof Name) return new IntRange(number(e), number(e));
    throw new Failure("Expected integer range", e.span());
  }

  private static ResourceId id(Expr e) {
    if (e instanceof Atom a && !a.member().tag() && a.member().properties().isEmpty())
      return a.member().id();
    throw new Failure("Expected resource ID", e.span());
  }
}

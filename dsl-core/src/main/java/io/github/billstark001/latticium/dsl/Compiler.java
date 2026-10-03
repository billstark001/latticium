package io.github.billstark001.latticium.dsl;

import static io.github.billstark001.latticium.dsl.Model.*;
import static io.github.billstark001.latticium.dsl.Syntax.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Binds symbolic expressions to a registry without retaining game objects. */
public final class Compiler {
  @FunctionalInterface
  public interface Membership {
    Truth test(Facts facts, Position pos, Object element);
  }

  public record Bound(SetType type, Membership membership, int radius) {
    public Truth at(Facts facts, Position pos) {
      if (type != SetType.POS) throw new IllegalStateException("Not PosSet");
      return membership.test(facts, pos, null);
    }

    public Truth contains(Facts facts, Object element) {
      if (type == SetType.POS) throw new IllegalStateException("PosSet needs a position");
      return membership.test(facts, null, element);
    }
  }

  public interface Primitive {
    List<SetType> parameters();

    SetType result();

    int radius();

    Membership bind(List<Bound> arguments);
  }

  private static final Set<String> BUILTINS =
      Set.of(
          "all",
          "none",
          "current",
          "target",
          "biome",
          "fluid",
          "states_of",
          "blocks_of",
          "property",
          "property_range",
          "state",
          "dimension",
          "box",
          "selection",
          "offset",
          "adjacent",
          "has_target",
          "matches_target",
          "changed",
          "same",
          "compare",
          "inventory",
          "sphere",
          "light",
          "solid",
          "surface");
  private final Registry registry;
  private final Map<String, Bound> declarations = new HashMap<>();
  private final Map<String, Function> functions = new HashMap<>();
  private final Map<String, Primitive> primitives = new HashMap<>();
  private final Set<String> active = new HashSet<>();
  private final int maxRadius;
  private boolean targetAvailable;
  private int nodes;

  /** Type-check without a live registry. Rebind with a real registry before evaluating. */
  public static Compiler symbolic() {
    return new Compiler(new SymbolicRegistry());
  }

  private static final class SymbolicRegistry implements Registry {
    public Resolution resolve(SetType kind, ResourceId id) {
      return Resolution.FOUND;
    }

    public Resolution resolveTag(SetType kind, ResourceId id) {
      return Resolution.FOUND;
    }

    public Set<ResourceId> tag(SetType kind, ResourceId id) {
      return Set.of();
    }

    public Set<ResourceId> universe(SetType kind) {
      return Set.of();
    }

    public Set<BlockState> states(ResourceId block) {
      return Set.of();
    }
  }

  public Compiler(Registry registry) {
    this(registry, 32);
  }

  public Compiler(Registry registry, int maxRadius) {
    this.registry = registry;
    this.maxRadius = maxRadius;
  }

  public Compiler registerPrimitive(String name, Primitive primitive) {
    java.util.Objects.requireNonNull(primitive);
    if (primitive.radius() < 0 || primitive.radius() > maxRadius)
      throw new IllegalArgumentException("Invalid primitive radius");
    if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")
        || BUILTINS.contains(name)
        || primitives.putIfAbsent(name, primitive) != null)
      throw new IllegalArgumentException("Duplicate or reserved primitive: " + name);
    return this;
  }

  public Compiler targetAvailable(boolean available) {
    targetAvailable = available;
    return this;
  }

  public Bound compile(String source, SetType expected) {
    nodes = 0;
    return bind(Parser.expression(source), expected, Map.of(), 0);
  }

  public List<Bound> compile(Document doc) {
    nodes = 0;
    for (var fn : doc.functions())
      if (BUILTINS.contains(fn.name())
          || primitives.containsKey(fn.name())
          || functions.putIfAbsent(fn.name(), fn) != null)
        throw new Failure("Duplicate or reserved function", fn.span());
    for (var d : doc.declarations()) {
      if (declarations.containsKey(d.name())
          || functions.containsKey(d.name())
          || BUILTINS.contains(d.name())
          || primitives.containsKey(d.name())) throw new Failure("Duplicate name", d.span());
      declarations.put(d.name(), bind(d.expression(), d.type(), Map.of(), 0));
    }
    var results = new ArrayList<Bound>();
    for (var t : doc.terminals()) {
      var b = bind(t.expression(), null, Map.of(), 0);
      if (b.type() == SetType.POS && t.order().stream().anyMatch(o -> o.key().equals("id"))
          || b.type() != SetType.POS && t.order().stream().anyMatch(o -> !o.key().equals("id")))
        throw new Failure("Invalid order for set type", t.span());
      results.add(b);
    }
    return List.copyOf(results);
  }

  private Bound bind(Expr e, SetType expected, Map<String, Bound> locals, int depth) {
    if (depth > 64) throw new Failure("Expansion depth exceeded", e.span());
    if (++nodes > 10000) throw new Failure("Expanded node budget exceeded", e.span());
    Bound result;
    if (e instanceof Literal l) result = literal(l, expected);
    else if (e instanceof Atom a) {
      if (expected != null && expected != SetType.POS)
        throw new Failure("Position atom requires PosSet", e.span());
      var lit =
          new Literal(a.biome() ? SetType.BIOME : SetType.STATE, List.of(a.member()), a.span());
      var b = literal(lit, null);
      result =
          a.biome()
              ? new Bound(
                  SetType.POS,
                  (f, p, v) ->
                      f.world(p)
                          .map(w -> w.biome() == null ? Truth.UNKNOWN : b.contains(f, w.biome()))
                          .orElse(Truth.UNKNOWN),
                  0)
              : new Bound(
                  SetType.POS,
                  (f, p, v) ->
                      f.world(p)
                          .map(w -> w.state() == null ? Truth.UNKNOWN : b.contains(f, w.state()))
                          .orElse(Truth.UNKNOWN),
                  0);
    } else if (e instanceof Range r) {
      if (r.axis() == '\0') throw new Failure("Range needs an axis", r.span());
      result =
          new Bound(
              SetType.POS,
              (f, p, v) ->
                  Truth.valueOf(
                      Boolean.toString(
                              r.range()
                                  .contains(
                                      switch (r.axis()) {
                                        case 'x' -> p.x();
                                        case 'y' -> p.y();
                                        default -> p.z();
                                      }))
                          .toUpperCase()),
              0);
    } else if (e instanceof Binary b) {
      var left = bind(b.left(), expected, locals, depth + 1);
      var right = bind(b.right(), left.type(), locals, depth + 1);
      require(right, left.type(), b.right());
      result =
          new Bound(
              left.type(),
              (f, p, v) -> {
                Truth first = left.membership().test(f, p, v);
                if (b.operator() == '&')
                  return first == Truth.FALSE
                      ? Truth.FALSE
                      : first.and(right.membership().test(f, p, v));
                return first == Truth.TRUE
                    ? Truth.TRUE
                    : first.or(right.membership().test(f, p, v));
              },
              Math.max(left.radius(), right.radius()));
    } else if (e instanceof Negate n) {
      var child = bind(n.inner(), expected, locals, depth + 1);
      result =
          new Bound(
              child.type(),
              (f, p, v) -> {
                if (child.type() == SetType.STATE
                    && (!(v instanceof BlockState s) || !registry.states(s.block()).contains(s)))
                  return Truth.FALSE;
                if (child.type() != SetType.POS
                    && child.type() != SetType.STATE
                    && (!(v instanceof ResourceId id)
                        || !registry.universe(child.type()).contains(id))) return Truth.FALSE;
                return child.membership().test(f, p, v).not();
              },
              child.radius());
    } else if (e instanceof Name n) {
      result = locals.getOrDefault(n.value(), declarations.get(n.value()));
      if (result == null) throw new Failure("Unknown name: " + n.value(), n.span());
    } else if (e instanceof Call c) result = call(c, locals, depth + 1);
    else throw new Failure("Not a set expression", e.span());
    if (expected != null) require(result, expected, e);
    if (result.radius() > maxRadius) throw new Failure("Read radius exceeds budget", e.span());
    return result;
  }

  private static void require(Bound bound, SetType type, Expr e) {
    if (bound.type() != type)
      throw new Failure("Expected " + type + ", got " + bound.type(), e.span());
  }

  private Bound literal(Literal l, SetType expected) {
    SetType type = l.type() == null ? expected : l.type();
    if (type == null || type == SetType.POS)
      throw new Failure("Literal needs a registry set type", l.span());
    if (expected != null && type != expected)
      throw new Failure("Expected " + expected + ", got " + type, l.span());
    for (var member : l.members()) {
      if (type != SetType.STATE && !member.properties().isEmpty())
        throw new Failure("Properties require StateSet", member.span());
      var status =
          member.tag()
              ? registry.resolveTag(type, member.id())
              : registry.resolve(type == SetType.STATE ? SetType.BLOCK : type, member.id());
      if (status != Registry.Resolution.FOUND)
        throw new Failure(status + " ID/tag: " + member.id(), member.span());
      if (!(registry instanceof SymbolicRegistry)
          && type == SetType.STATE
          && !member.tag()
          && !member.properties().isEmpty()
          && registry.states(member.id()).stream()
              .noneMatch(s -> matches(s.properties(), member.properties())))
        throw new Failure("Invalid state properties", member.span());
    }
    return new Bound(
        type,
        (f, p, v) -> {
          for (var member : l.members()) {
            var ids = member.tag() ? registry.tag(type, member.id()) : Set.of(member.id());
            if (type == SetType.STATE && v instanceof BlockState state) {
              if (ids.contains(state.block()) && matches(state.properties(), member.properties()))
                return Truth.TRUE;
            } else if (v instanceof ResourceId id && ids.contains(id)) return Truth.TRUE;
          }
          return Truth.FALSE;
        },
        0);
  }

  private static boolean matches(Map<String, String> actual, Map<String, String> wanted) {
    return wanted.entrySet().stream().allMatch(e -> e.getValue().equals(actual.get(e.getKey())));
  }

  private Bound call(Call c, Map<String, Bound> locals, int depth) {
    var a = c.args();
    String n = c.name();
    if (functions.containsKey(n)) {
      var fn = functions.get(n);
      arity(c, fn.parameters().size());
      if (!active.add(n)) throw new Failure("Recursive function: " + n, c.span());
      try {
        var env = new HashMap<String, Bound>(locals);
        var integers = new HashMap<String, Expr>();
        for (int i = 0; i < a.size(); i++) {
          var p = fn.parameters().get(i);
          if (p.integer()) {
            number(a.get(i));
            integers.put(p.name(), a.get(i));
          } else env.put(p.name(), bind(a.get(i), p.type(), locals, depth + 1));
        }
        return bind(substitute(fn.body(), integers), fn.result(), env, depth + 1);
      } finally {
        active.remove(n);
      }
    }
    return switch (n) {
      case "all", "none" -> {
        arity(c, 0);
        yield constant(n.equals("all"));
      }
      case "current", "target" -> {
        arity(c, 1);
        if (n.equals("target")) needsTarget(c);
        var b = bind(a.getFirst(), null, locals, depth);
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
            bind(a.getFirst(), n.equals("biome") ? SetType.BIOME : SetType.FLUID, locals, depth);
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
        var b = bind(a.getFirst(), SetType.BLOCK, locals, depth);
        yield new Bound(
            SetType.STATE, (f, p, v) -> b.contains(f, ((BlockState) v).block()), b.radius());
      }
      case "blocks_of" -> {
        arity(c, 1);
        var b = bind(a.getFirst(), SetType.STATE, locals, depth);
        yield new Bound(
            SetType.BLOCK,
            (f, p, v) ->
                registry.states((ResourceId) v).stream()
                        .anyMatch(s -> b.contains(f, s) == Truth.TRUE)
                    ? Truth.TRUE
                    : Truth.FALSE,
            b.radius());
      }
      case "property" -> {
        arity(c, 1);
        var pair = pair(a.getFirst());
        yield new Bound(
            SetType.STATE,
            (f, p, v) -> truth(pair.value().equals(((BlockState) v).properties().get(pair.key()))),
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
        yield new Bound(
            SetType.STATE,
            (f, p, v) -> {
              String value = ((BlockState) v).properties().get(key);
              if (value == null || !value.matches("-?[0-9]+")) return Truth.FALSE;
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
        var b = bind(a.get(3), SetType.POS, locals, depth);
        int radius = Math.max(Math.max(Math.abs(x), Math.abs(y)), Math.abs(z)) + b.radius();
        yield new Bound(SetType.POS, (f, p, v) -> b.at(f, p.offset(x, y, z)), radius);
      }
      case "adjacent" -> {
        arity(c, 1);
        var b = bind(a.getFirst(), SetType.POS, locals, depth);
        yield new Bound(
            SetType.POS,
            (f, p, v) -> {
              Truth result = Truth.FALSE;
              for (int[] d :
                  new int[][] {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}})
                result = result.or(b.at(f, p.offset(d[0], d[1], d[2])));
              return result;
            },
            b.radius() + 1);
      }
      case "has_target", "matches_target" -> {
        arity(c, 0);
        needsTarget(c);
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
                  : truth(world.get().state().block().equals(ResourceId.parse("minecraft:air")));
            },
            0);
      }
      case "changed", "same", "compare" -> {
        arity(c, n.equals("compare") ? 2 : 1);
        needsTarget(c);
        String key = name(a.getFirst());
        String relation = n.equals("compare") ? name(a.get(1)) : "";
        if (n.equals("compare") && !List.of("lt", "le", "gt", "ge").contains(relation))
          throw new Failure("Invalid relation", c.span());
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
        var b = bind(a.getFirst(), SetType.ITEM, locals, depth);
        yield new Bound(
            SetType.ITEM,
            (f, p, v) ->
                f.inventory()
                    .map(items -> truth(items.contains(v)).and(b.contains(f, v)))
                    .orElse(Truth.UNKNOWN),
            0);
      }
      case "sphere" -> {
        arity(c, 2);
        int r = number(a.get(1));
        if (r < 0 || r > maxRadius) throw new Failure("Sphere radius exceeds budget", c.span());
        Position fixed = null;
        if (a.getFirst() instanceof Call point && point.name().equals("point")) {
          arity(point, 3);
          fixed =
              new Position(
                  ResourceId.parse("minecraft:overworld"),
                  number(point.args().get(0)),
                  number(point.args().get(1)),
                  number(point.args().get(2)));
        } else if (!(a.getFirst() instanceof Name anchor) || !anchor.value().equals("player"))
          throw new Failure("Expected player or point anchor", a.getFirst().span());
        Position anchorPoint = fixed;
        yield new Bound(
            SetType.POS,
            (f, p, v) -> {
              var center =
                  anchorPoint == null
                      ? f.player()
                      : Optional.of(
                          new Position(
                              p.dimension(), anchorPoint.x(), anchorPoint.y(), anchorPoint.z()));
              return center
                  .map(
                      q ->
                          truth(
                              p.dimension().equals(q.dimension())
                                  && squared(p, q) <= ((long) r * r)))
                  .orElse(Truth.UNKNOWN);
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
              if (w.isEmpty() || w.get().solid() == null) return Truth.UNKNOWN;
              if (!w.get().solid()) return Truth.FALSE;
              Truth result = Truth.FALSE;
              for (int[] d :
                  new int[][] {
                    {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
                  }) {
                var near = f.world(p.offset(d[0], d[1], d[2]));
                result =
                    result.or(
                        near.map(
                                cell ->
                                    cell.state() == null
                                        ? Truth.UNKNOWN
                                        : truth(
                                            cell.state()
                                                .block()
                                                .equals(ResourceId.parse("minecraft:air"))))
                            .orElse(Truth.UNKNOWN));
              }
              return result;
            },
            1);
      }
      default -> {
        var primitive = primitives.get(n);
        if (primitive == null) throw new Failure("Unknown function: " + n, c.span());
        arity(c, primitive.parameters().size());
        var bound = new ArrayList<Bound>();
        int radius = primitive.radius();
        for (int i = 0; i < a.size(); i++) {
          var child = bind(a.get(i), primitive.parameters().get(i), locals, depth);
          bound.add(child);
          radius = Math.max(radius, Math.addExact(primitive.radius(), child.radius()));
        }
        yield new Bound(primitive.result(), primitive.bind(List.copyOf(bound)), radius);
      }
    };
  }

  private void needsTarget(Call c) {
    if (!targetAvailable) throw new Failure("Target view unavailable in this phase", c.span());
  }

  private static Bound constant(boolean value) {
    return new Bound(SetType.POS, (f, p, v) -> truth(value), 0);
  }

  private static Truth truth(boolean value) {
    return value ? Truth.TRUE : Truth.FALSE;
  }

  private static double squared(Position p, Position q) {
    double x = (double) p.x() - q.x(), y = (double) p.y() - q.y(), z = (double) p.z() - q.z();
    return x * x + y * y + z * z;
  }

  private static void arity(Call c, int expected) {
    if (c.args().size() != expected)
      throw new Failure("Expected " + expected + " arguments", c.span());
  }

  private static String name(Expr e) {
    if (e instanceof Name n) return n.value();
    throw new Failure("Expected name", e.span());
  }

  private static Pair pair(Expr e) {
    if (e instanceof Pair p) return p;
    throw new Failure("Expected property pair", e.span());
  }

  private static int number(Expr e) {
    try {
      return Integer.parseInt(name(e));
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

  private static Expr substitute(Expr expression, Map<String, Expr> integers) {
    if (integers.isEmpty()) return expression;
    if (expression instanceof Name n) return integers.getOrDefault(n.value(), n);
    if (expression instanceof Binary b)
      return new Binary(
          b.operator(), substitute(b.left(), integers), substitute(b.right(), integers), b.span());
    if (expression instanceof Negate n)
      return new Negate(substitute(n.inner(), integers), n.span());
    if (expression instanceof Call c)
      return new Call(
          c.name(), c.args().stream().map(e -> substitute(e, integers)).toList(), c.span());
    return expression;
  }
}

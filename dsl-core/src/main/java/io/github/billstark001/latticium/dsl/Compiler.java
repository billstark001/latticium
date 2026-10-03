package io.github.billstark001.latticium.dsl;

import static io.github.billstark001.latticium.dsl.Model.*;
import static io.github.billstark001.latticium.dsl.Syntax.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Binds symbolic expressions to a registry without retaining game objects. */
public final class Compiler {
  private static final int DEFAULT_MAX_RADIUS = 32;
  private static final int MAX_EXPANSION_DEPTH = 64;
  private static final int MAX_EXPANDED_NODES = 10_000;

  private record ResolvedMember(Set<ResourceId> ids, Map<String, String> properties) {}

  @FunctionalInterface
  public interface Membership {
    Truth test(Facts facts, Position pos, Object element);
  }

  public record Bound(SetType type, Membership membership, int radius) {
    /** Evaluates a position predicate; unavailable captured facts remain {@link Truth#UNKNOWN}. */
    public Truth at(Facts facts, Position pos) {
      if (type != SetType.POS) throw new IllegalStateException("Not PosSet");
      return membership.test(facts, pos, null);
    }

    /** Tests a registry element; position predicates must use {@link #at(Facts, Position)}. */
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
    this(registry, DEFAULT_MAX_RADIUS);
  }

  public Compiler(Registry registry, int maxRadius) {
    this.registry = java.util.Objects.requireNonNull(registry);
    if (maxRadius < 0) throw new IllegalArgumentException("Negative read radius budget");
    this.maxRadius = maxRadius;
  }

  /** Registers a trusted, read-only primitive before binding expressions that call it. */
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

  /** Controls whether target-view expressions may bind in the current compilation phase. */
  public Compiler targetAvailable(boolean available) {
    targetAvailable = available;
    return this;
  }

  /** Returns whether target-view expressions are currently allowed during binding. */
  public boolean targetAvailable() {
    return targetAvailable;
  }

  /** Binds one expression against this compiler's declarations and registry. */
  public Bound compile(String source, SetType expected) {
    nodes = 0;
    return bind(Parser.expression(source), expected, Map.of(), 0);
  }

  /** Adds a document atomically; a failed bind leaves prior declarations and functions intact. */
  public List<Bound> compile(Document doc) {
    nodes = 0;
    var previousDeclarations = new HashMap<>(declarations);
    var previousFunctions = new HashMap<>(functions);
    try {
      for (var fn : doc.functions()) {
        var parameterNames = new HashSet<String>();
        for (var parameter : fn.parameters())
          if (!parameterNames.add(parameter.name()))
            throw new Failure("Duplicate function parameter: " + parameter.name(), fn.span());
        if (BUILTINS.contains(fn.name())
            || declarations.containsKey(fn.name())
            || primitives.containsKey(fn.name())
            || functions.putIfAbsent(fn.name(), fn) != null)
          throw new Failure("Duplicate or reserved function", fn.span());
      }
      validateFunctionCycles();
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
    } catch (RuntimeException ex) {
      declarations.clear();
      declarations.putAll(previousDeclarations);
      functions.clear();
      functions.putAll(previousFunctions);
      throw ex;
    }
  }

  Bound bind(Expr e, SetType expected, Map<String, Bound> locals, int depth) {
    if (depth > MAX_EXPANSION_DEPTH) throw new Failure("Expansion depth exceeded", e.span());
    if (++nodes > MAX_EXPANDED_NODES) throw new Failure("Expanded node budget exceeded", e.span());
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
      if ("xyz".indexOf(r.axis()) < 0) throw new Failure("Range needs x, y or z axis", r.span());
      result =
          new Bound(
              SetType.POS,
              (f, p, v) -> {
                int coordinate =
                    switch (r.axis()) {
                      case 'x' -> p.x();
                      case 'y' -> p.y();
                      default -> p.z();
                    };
                return r.range().contains(coordinate) ? Truth.TRUE : Truth.FALSE;
              },
              0);
    } else if (e instanceof Binary b) {
      Bound left, right;
      if (expected == null && b.left() instanceof Literal l && l.type() == null) {
        right = bind(b.right(), null, locals, depth + 1);
        left = bind(b.left(), right.type(), locals, depth + 1);
      } else {
        left = bind(b.left(), expected, locals, depth + 1);
        right = bind(b.right(), left.type(), locals, depth + 1);
      }
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
    var resolved = new ArrayList<ResolvedMember>();
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
      resolved.add(
          new ResolvedMember(
              member.tag() ? Set.copyOf(registry.tag(type, member.id())) : Set.of(member.id()),
              Map.copyOf(member.properties())));
    }
    return new Bound(
        type,
        (f, p, v) -> {
          for (var member : resolved) {
            if (type == SetType.STATE && v instanceof BlockState state) {
              if (member.ids().contains(state.block())
                  && matches(state.properties(), member.properties())) return Truth.TRUE;
            } else if (type != SetType.STATE
                && v instanceof ResourceId id
                && member.ids().contains(id)) return Truth.TRUE;
          }
          return Truth.FALSE;
        },
        0);
  }

  private static boolean matches(Map<String, String> actual, Map<String, String> wanted) {
    for (var entry : wanted.entrySet())
      if (!entry.getValue().equals(actual.get(entry.getKey()))) return false;
    return true;
  }

  private Bound call(Call c, Map<String, Bound> locals, int depth) {
    var a = c.args();
    String n = c.name();
    if (functions.containsKey(n)) {
      var fn = functions.get(n);
      BuiltinCompiler.arity(c, fn.parameters().size());
      if (!active.add(n)) throw new Failure("Recursive function: " + n, c.span());
      try {
        var env = new HashMap<String, Bound>(locals);
        var integers = new HashMap<String, Expr>();
        for (int i = 0; i < a.size(); i++) {
          var p = fn.parameters().get(i);
          if (p.integer()) {
            BuiltinCompiler.number(a.get(i));
            integers.put(p.name(), a.get(i));
          } else env.put(p.name(), bind(a.get(i), p.type(), locals, depth + 1));
        }
        return bind(substitute(fn.body(), integers), fn.result(), env, depth + 1);
      } finally {
        active.remove(n);
      }
    }
    return new BuiltinCompiler(this).bind(c, locals, depth);
  }

  void needsTarget(Call c) {
    if (!targetAvailable) throw new Failure("Target view unavailable in this phase", c.span());
  }

  Registry registry() {
    return registry;
  }

  int maxRadius() {
    return maxRadius;
  }

  Bound bindPrimitive(Call c, Map<String, Bound> locals, int depth) {
    var primitive = primitives.get(c.name());
    if (primitive == null) throw new Failure("Unknown function: " + c.name(), c.span());
    BuiltinCompiler.arity(c, primitive.parameters().size());
    var arguments = new ArrayList<Bound>();
    int radius = primitive.radius();
    for (int i = 0; i < c.args().size(); i++) {
      var child = bind(c.args().get(i), primitive.parameters().get(i), locals, depth);
      arguments.add(child);
      long combined = (long) primitive.radius() + child.radius();
      if (combined > maxRadius) throw new Failure("Read radius exceeds budget", c.span());
      radius = Math.max(radius, (int) combined);
    }
    return new Bound(primitive.result(), primitive.bind(List.copyOf(arguments)), radius);
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

  private void validateFunctionCycles() {
    var complete = new HashSet<String>();
    var visiting = new HashSet<String>();
    for (var name : functions.keySet()) visitFunction(name, visiting, complete);
  }

  private void visitFunction(String name, Set<String> visiting, Set<String> complete) {
    if (complete.contains(name)) return;
    var function = functions.get(name);
    if (!visiting.add(name)) throw new Failure("Recursive function: " + name, function.span());
    visitCalls(function.body(), visiting, complete);
    visiting.remove(name);
    complete.add(name);
  }

  private void visitCalls(Expr expression, Set<String> visiting, Set<String> complete) {
    if (expression instanceof Call call) {
      if (functions.containsKey(call.name())) visitFunction(call.name(), visiting, complete);
      for (var argument : call.args()) visitCalls(argument, visiting, complete);
    } else if (expression instanceof Binary binary) {
      visitCalls(binary.left(), visiting, complete);
      visitCalls(binary.right(), visiting, complete);
    } else if (expression instanceof Negate negate) visitCalls(negate.inner(), visiting, complete);
  }
}

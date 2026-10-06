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
  private static final int DEFAULT_MAX_RADIUS = 32;
  static final int MAX_EXPANSION_DEPTH = 64;
  private static final int MAX_EXPANDED_NODES = 10_000;

  /** Maximum combined declarations and functions retained by one compiler. */
  public static final int MAX_TOP_LEVEL_BINDINGS = 10_000;

  private static final Facts UNAVAILABLE_FACTS =
      new Facts() {
        public Optional<WorldCell> world(Position pos) {
          return Optional.empty();
        }

        public TargetCell target(Position pos) {
          return new TargetCell.Unknown("Target unavailable");
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
      };

  @FunctionalInterface
  public interface Membership {
    Truth test(Facts facts, Position pos, Object element);
  }

  public record Bound(
      SetType type, Membership membership, int radius, FactDependencies dependencies) {
    /** Legacy/custom memberships conservatively request every fact. */
    public Bound(SetType type, Membership membership, int radius) {
      this(type, membership, radius, FactDependencies.ALL);
    }

    public Bound {
      java.util.Objects.requireNonNull(type, "type");
      java.util.Objects.requireNonNull(membership, "membership");
      java.util.Objects.requireNonNull(dependencies, "dependencies");
      if (radius < 0) throw new IllegalArgumentException("Negative read radius");
    }

    /** Evaluates a position predicate; unavailable captured facts remain {@link Truth#UNKNOWN}. */
    public Truth at(Facts facts, Position pos) {
      if (type != SetType.POS) throw new IllegalStateException("Not PosSet");
      java.util.Objects.requireNonNull(pos, "pos");
      return facts instanceof EvaluationFacts frame
          ? frame.evaluate(this, pos)
          : membership.test(facts, pos, null);
    }

    Truth test(Facts facts, Position pos, Object element) {
      return type == SetType.POS ? at(facts, pos) : membership.test(facts, pos, element);
    }

    /** Tests a registry element; position predicates must use {@link #at(Facts, Position)}. */
    public Truth contains(Facts facts, Object element) {
      if (type == SetType.POS) throw new IllegalStateException("PosSet needs a position");
      if (type == SetType.STATE
          ? !(element instanceof BlockState)
          : !(element instanceof ResourceId)) return Truth.FALSE;
      return membership.test(facts, null, element);
    }
  }

  public interface Primitive {
    List<SetType> parameters();

    SetType result();

    int radius();

    Membership bind(List<Bound> arguments);

    /** Facts read directly by this primitive; argument requirements are added by the binder. */
    default FactDependencies dependencies() {
      return FactDependencies.ALL;
    }
  }

  private record RegisteredPrimitive(
      List<SetType> parameters,
      SetType result,
      int radius,
      FactDependencies dependencies,
      Primitive implementation) {}

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
  private final Map<String, RegisteredPrimitive> primitives = new HashMap<>();
  private final Set<String> active = new HashSet<>();
  private final int maxRadius;
  private boolean targetAvailable;
  private int nodes;

  /** Type-check without a live registry. Rebind with a real registry before evaluating. */
  public static Compiler symbolic() {
    return new Compiler(new SymbolicRegistry());
  }

  public Compiler(Registry registry) {
    this(registry, DEFAULT_MAX_RADIUS);
  }

  public Compiler(Registry registry, int maxRadius) {
    this.registry = java.util.Objects.requireNonNull(registry);
    if (maxRadius < 0) throw new IllegalArgumentException("Negative read radius budget");
    this.maxRadius = maxRadius;
  }

  /** Copies bindings and primitive signatures into an independent namespace. */
  public Compiler fork() {
    var copy = new Compiler(registry, maxRadius);
    copy.declarations.putAll(declarations);
    copy.functions.putAll(functions);
    copy.primitives.putAll(primitives);
    copy.targetAvailable = targetAvailable;
    return copy;
  }

  /** Registers a trusted, read-only primitive before binding expressions that call it. */
  public Compiler registerPrimitive(String name, Primitive primitive) {
    java.util.Objects.requireNonNull(primitive);
    var parameters = List.copyOf(primitive.parameters());
    var result = java.util.Objects.requireNonNull(primitive.result(), "primitive result");
    int radius = primitive.radius();
    if (radius < 0 || radius > maxRadius)
      throw new IllegalArgumentException("Invalid primitive radius");
    var registered =
        new RegisteredPrimitive(
            parameters,
            result,
            radius,
            java.util.Objects.requireNonNull(primitive.dependencies(), "primitive dependencies")
                .asBroad(),
            primitive);
    if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")
        || BUILTINS.contains(name)
        || declarations.containsKey(name)
        || functions.containsKey(name)
        || primitives.putIfAbsent(name, registered) != null)
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

  /** Tests a registry member with all dynamic facts unavailable; FALSE is a definite exclusion. */
  public Truth membershipWithoutFacts(Bound bound, ResourceId member) {
    if (registry instanceof SymbolicRegistry) return Truth.UNKNOWN;
    return bound.contains(UNAVAILABLE_FACTS, member);
  }

  /** Binds one expression against this compiler's declarations and registry. */
  public Bound compile(String source, SetType expected) {
    return compile(Parser.expression(source), expected);
  }

  /** Binds a previously parsed expression without reparsing its source. */
  public Bound compile(Expr expression, SetType expected) {
    nodes = 0;
    return bind(expression, expected, Map.of(), 0);
  }

  /** Adds a document atomically; a failed bind leaves prior declarations and functions intact. */
  public List<Bound> compile(Document doc) {
    long totalBindings =
        (long) declarations.size()
            + functions.size()
            + doc.declarations().size()
            + doc.functions().size();
    if (totalBindings > MAX_TOP_LEVEL_BINDINGS)
      throw new IllegalArgumentException("Top-level binding budget exceeded");
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
      ExpressionAnalysis.validateFunctionCycles(functions);
      for (var d : doc.declarations()) {
        if (declarations.containsKey(d.name())
            || functions.containsKey(d.name())
            || BUILTINS.contains(d.name())
            || primitives.containsKey(d.name())) throw new Failure("Duplicate name", d.span());
        if (d.type() == null && ExpressionAnalysis.hasUncontextualizedAtom(d.expression()))
          throw new Failure("Position atom needs an explicit PosSet declaration", d.span());
        declarations.put(d.name(), bind(d.expression(), d.type(), Map.of(), 0));
      }
      validateFunctions(doc.functions());
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
                  0,
                  FactDependencies.of(FactDependencies.Fact.BIOME))
              : new Bound(
                  SetType.POS,
                  (f, p, v) ->
                      f.world(p)
                          .map(w -> w.state() == null ? Truth.UNKNOWN : b.contains(f, w.state()))
                          .orElse(Truth.UNKNOWN),
                  0,
                  FactDependencies.of(FactDependencies.Fact.STATE));
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
              0,
              FactDependencies.NONE);
    } else if (e instanceof Binary b) {
      Bound left, right;
      if (expected == null && ExpressionAnalysis.needsTypeFromRight(b.left())) {
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
                Truth first = left.test(f, p, v);
                if (b.operator() == '&')
                  return first == Truth.FALSE ? Truth.FALSE : first.and(right.test(f, p, v));
                return first == Truth.TRUE ? Truth.TRUE : first.or(right.test(f, p, v));
              },
              Math.max(left.radius(), right.radius()),
              left.dependencies().union(right.dependencies()));
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
                return child.test(f, p, v).not();
              },
              child.radius(),
              child.dependencies());
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
    return LiteralCompiler.bind(l, expected, registry, registry instanceof SymbolicRegistry);
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
        return bind(
            ExpressionAnalysis.substitute(fn.body(), integers), fn.result(), env, depth + 1);
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

  boolean legalState(BlockState state) {
    return registry instanceof SymbolicRegistry || registry.states(state.block()).contains(state);
  }

  boolean symbolicRegistry() {
    return registry instanceof SymbolicRegistry;
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
    var dependencies = primitive.dependencies();
    for (int i = 0; i < c.args().size(); i++) {
      var child = bind(c.args().get(i), primitive.parameters().get(i), locals, depth);
      arguments.add(child);
      dependencies = dependencies.union(child.dependencies().asBroad());
      long combined = (long) primitive.radius() + child.radius();
      if (combined > maxRadius) throw new Failure("Read radius exceeds budget", c.span());
      radius = Math.max(radius, (int) combined);
    }
    return new Bound(
        primitive.result(),
        primitive.implementation().bind(List.copyOf(arguments)),
        radius,
        dependencies);
  }

  private void validateFunctions(List<Function> added) {
    boolean previousTargetAvailability = targetAvailable;
    try {
      // A definition may be used later in a target-aware phase. Type-check its body now,
      // then enforce phase access again when a call is bound.
      targetAvailable = true;
      for (var function : added) {
        var locals = new HashMap<String, Bound>();
        var integers = new HashMap<String, Expr>();
        for (var parameter : function.parameters()) {
          if (parameter.integer()) integers.put(parameter.name(), new Name("0", function.span()));
          else
            locals.put(
                parameter.name(),
                new Bound(
                    parameter.type(),
                    (facts, pos, element) -> Truth.UNKNOWN,
                    0,
                    FactDependencies.NONE));
        }
        bind(
            ExpressionAnalysis.substitute(function.body(), integers), function.result(), locals, 0);
      }
    } finally {
      targetAvailable = previousTargetAvailability;
    }
  }
}

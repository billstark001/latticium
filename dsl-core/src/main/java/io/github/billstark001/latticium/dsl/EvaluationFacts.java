package io.github.billstark001.latticium.dsl;

import static io.github.billstark001.latticium.dsl.Model.*;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** One bounded evaluation frame; never reused across snapshots or top-level calls. */
final class EvaluationFacts implements Facts {
  static final int MAX_EVALUATIONS = 65_536;
  private final Facts source;
  private final Map<Compiler.Bound, Map<Position, Truth>> results = new IdentityHashMap<>();
  private int evaluations;

  EvaluationFacts(Facts source) {
    this.source = source;
  }

  Truth evaluate(Compiler.Bound bound, Position pos) {
    var positions = results.computeIfAbsent(bound, ignored -> new HashMap<>());
    var previous = positions.get(pos);
    if (previous != null) return previous;
    if (evaluations == MAX_EVALUATIONS) return Truth.UNKNOWN;
    evaluations++;
    // A trusted extension that reenters the same bound cannot recurse forever.
    positions.put(pos, Truth.UNKNOWN);
    Truth result = bound.membership().test(this, pos, null);
    positions.put(pos, result);
    return result;
  }

  public Optional<WorldCell> world(Position pos) {
    return source.world(pos);
  }

  public TargetCell target(Position pos) {
    return source.target(pos);
  }

  public Optional<Position> player() {
    return source.player();
  }

  public Optional<Set<ResourceId>> inventory() {
    return source.inventory();
  }

  public Truth selection(String name, Position pos) {
    return source.selection(name, pos);
  }
}

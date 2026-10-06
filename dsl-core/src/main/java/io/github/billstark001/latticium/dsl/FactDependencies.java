package io.github.billstark001.latticium.dsl;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Conservative fact columns and spatial reads, including expanded functions and declarations. */
public record FactDependencies(Set<Fact> facts, Map<Fact, Reads> columns) {
  public enum Fact {
    STATE,
    BIOME,
    FLUID,
    LIGHT,
    SOLID,
    TARGET,
    PLAYER,
    INVENTORY,
    SELECTION
  }

  public record Offset(int x, int y, int z) {
    Offset plus(int dx, int dy, int dz) {
      return new Offset(Math.addExact(x, dx), Math.addExact(y, dy), Math.addExact(z, dz));
    }
  }

  /**
   * Broad reads request the entire bounded halo; exact offsets are relative to each evaluated cell.
   */
  public record Reads(boolean broad, Set<Offset> offsets) {
    public Reads {
      offsets = Set.copyOf(offsets);
      if (offsets.size() > MAX_EXACT_OFFSETS) broad = true;
      if (broad) offsets = Set.of();
      else if (offsets.isEmpty())
        throw new IllegalArgumentException("Exact column reads need an offset");
    }

    private Reads union(Reads other) {
      if (broad || other.broad) return BROAD;
      var merged = new HashSet<>(offsets);
      merged.addAll(other.offsets);
      return new Reads(false, merged);
    }

    private Reads shift(int dx, int dy, int dz) {
      if (broad) return this;
      var shifted = new HashSet<Offset>();
      try {
        for (var offset : offsets) shifted.add(offset.plus(dx, dy, dz));
      } catch (ArithmeticException overflow) {
        return BROAD;
      }
      return new Reads(false, shifted);
    }
  }

  public static final int MAX_EXACT_OFFSETS = 512;
  private static final Set<Fact> WORLD =
      Set.of(Fact.STATE, Fact.BIOME, Fact.FLUID, Fact.LIGHT, Fact.SOLID);
  private static final Set<Fact> SPATIAL =
      Set.of(Fact.STATE, Fact.BIOME, Fact.FLUID, Fact.LIGHT, Fact.SOLID, Fact.TARGET);
  private static final Offset ORIGIN = new Offset(0, 0, 0);
  private static final Set<Offset> NEIGHBORS =
      Set.of(
          new Offset(1, 0, 0),
          new Offset(-1, 0, 0),
          new Offset(0, 1, 0),
          new Offset(0, -1, 0),
          new Offset(0, 0, 1),
          new Offset(0, 0, -1));
  private static final Reads BROAD = new Reads(true, Set.of());
  private static final Reads DIRECT = new Reads(false, Set.of(ORIGIN));
  public static final FactDependencies NONE = new FactDependencies(Set.of());
  public static final FactDependencies ALL = new FactDependencies(EnumSet.allOf(Fact.class));

  /** Unknown spatial behavior conservatively requests every declared column across the halo. */
  public FactDependencies(Set<Fact> facts) {
    this(facts, broadColumns(facts));
  }

  public FactDependencies {
    facts = Set.copyOf(facts);
    columns = Map.copyOf(columns);
    var spatial = EnumSet.noneOf(Fact.class);
    spatial.addAll(facts);
    spatial.retainAll(SPATIAL);
    if (!columns.keySet().equals(spatial))
      throw new IllegalArgumentException("Spatial columns differ from fact requirements");
  }

  /** Direct spatial reads at the evaluated position; global facts have no per-cell column. */
  public static FactDependencies of(Fact... facts) {
    var required = Set.of(facts);
    var columns = new EnumMap<Fact, Reads>(Fact.class);
    for (var fact : required) if (SPATIAL.contains(fact)) columns.put(fact, DIRECT);
    return new FactDependencies(required, columns);
  }

  public boolean needs(Fact fact) {
    return facts.contains(fact);
  }

  public boolean usesWorld() {
    return facts.stream().anyMatch(WORLD::contains);
  }

  public FactDependencies union(FactDependencies other) {
    if (equals(other) || other.facts.isEmpty()) return this;
    if (facts.isEmpty()) return other;
    var required = EnumSet.noneOf(Fact.class);
    required.addAll(facts);
    required.addAll(other.facts);
    var merged = new EnumMap<Fact, Reads>(Fact.class);
    merged.putAll(columns);
    other.columns.forEach((fact, reads) -> merged.merge(fact, reads, Reads::union));
    return new FactDependencies(required, merged);
  }

  /** Applies a known coordinate transform without widening global facts. */
  public FactDependencies shift(int dx, int dy, int dz) {
    if (columns.isEmpty() || dx == 0 && dy == 0 && dz == 0) return this;
    var shifted = new EnumMap<Fact, Reads>(Fact.class);
    columns.forEach((fact, reads) -> shifted.put(fact, reads.shift(dx, dy, dz)));
    return new FactDependencies(facts, shifted);
  }

  /** Reads the inner expression at all six face neighbors. */
  public FactDependencies adjacent() {
    var result = NONE;
    for (var neighbor : NEIGHBORS) result = result.union(shift(neighbor.x, neighbor.y, neighbor.z));
    return result;
  }

  /** For an extension that may evaluate arguments at unknown offsets within its declared radius. */
  public FactDependencies asBroad() {
    return columns.values().stream().allMatch(Reads::broad) ? this : new FactDependencies(facts);
  }

  private static Map<Fact, Reads> broadColumns(Set<Fact> facts) {
    var columns = new EnumMap<Fact, Reads>(Fact.class);
    for (var fact : facts) if (SPATIAL.contains(fact)) columns.put(fact, BROAD);
    return columns;
  }
}

package io.github.billstark001.latticium.planning;

import io.github.billstark001.latticium.dsl.Compiler;
import io.github.billstark001.latticium.dsl.Model.*;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** Pure section scanner; the caller supplies immutable, session-bound facts. */
public final class SectionScanner {
  public static final int SECTION_SIZE = 16;
  public static final int SECTION_VOLUME = SECTION_SIZE * SECTION_SIZE * SECTION_SIZE;
  private static final int SECTION_SHIFT = 4;
  private static final int LOCAL_MASK = SECTION_SIZE - 1;

  public record Bounds(
      ResourceId dimension, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    public Bounds {
      Objects.requireNonNull(dimension, "dimension");
      if (minX > maxX || minY > maxY || minZ > maxZ)
        throw new IllegalArgumentException("Empty bounds");
    }

    /** Tests inclusive membership in this box and its dimension. */
    public boolean contains(Position pos) {
      return dimension.equals(pos.dimension())
          && pos.x() >= minX
          && pos.x() <= maxX
          && pos.y() >= minY
          && pos.y() <= maxY
          && pos.z() >= minZ
          && pos.z() <= maxZ;
    }
  }

  public record SectionKey(ResourceId dimension, int x, int y, int z) {
    public SectionKey {
      Objects.requireNonNull(dimension, "dimension");
    }
  }

  /** One section with all finite bounds that can contribute cells to it. */
  public record SectionGroup(SectionKey key, List<Bounds> bounds) {
    public SectionGroup {
      Objects.requireNonNull(key, "key");
      bounds = List.copyOf(bounds);
      if (bounds.isEmpty()
          || bounds.stream().anyMatch(box -> !box.dimension().equals(key.dimension())))
        throw new IllegalArgumentException("Section group needs bounds in its dimension");
    }
  }

  /** Known cells are either true or false; cells absent from knownMask remain unknown. */
  public record SectionResult(SectionKey key, BitSet trueMask, BitSet knownMask) {
    public SectionResult {
      Objects.requireNonNull(key, "key");
      trueMask = (BitSet) trueMask.clone();
      knownMask = (BitSet) knownMask.clone();
      if (trueMask.length() > SECTION_VOLUME || knownMask.length() > SECTION_VOLUME)
        throw new IllegalArgumentException("Section mask exceeds " + SECTION_VOLUME + " cells");
      var unknownTrue = (BitSet) trueMask.clone();
      unknownTrue.andNot(knownMask);
      if (!unknownTrue.isEmpty())
        throw new IllegalArgumentException("True cells must also be known");
    }

    @Override
    public BitSet trueMask() {
      return (BitSet) trueMask.clone();
    }

    @Override
    public BitSet knownMask() {
      return (BitSet) knownMask.clone();
    }

    public int trueCount() {
      return trueMask.cardinality();
    }

    public int unknownCount() {
      return SECTION_VOLUME - knownMask.cardinality();
    }

    /** Complements known cells inside a finite domain; cells outside it stay known false. */
    public SectionResult not(BitSet domain) {
      Objects.requireNonNull(domain, "domain");
      if (domain.length() > SECTION_VOLUME)
        throw new IllegalArgumentException("Section domain exceeds " + SECTION_VOLUME + " cells");
      var truth = falseMask();
      truth.and(domain);
      var known = (BitSet) knownMask.clone();
      known.and(domain);
      var outside = new BitSet(SECTION_VOLUME);
      outside.set(0, SECTION_VOLUME);
      outside.andNot(domain);
      known.or(outside);
      return new SectionResult(key, truth, known);
    }

    /** Combines two masks from the same section using three-valued conjunction. */
    public SectionResult and(SectionResult other) {
      sameKey(other);
      var truth = (BitSet) trueMask.clone();
      truth.and(other.trueMask);
      var known = falseMask();
      known.or(other.falseMask());
      known.or(truth);
      return new SectionResult(key, truth, known);
    }

    /** Combines two masks from the same section using three-valued disjunction. */
    public SectionResult or(SectionResult other) {
      sameKey(other);
      var truth = (BitSet) trueMask.clone();
      truth.or(other.trueMask);
      var known = falseMask();
      known.and(other.falseMask());
      known.or(truth);
      return new SectionResult(key, truth, known);
    }

    private BitSet falseMask() {
      var falseMask = (BitSet) knownMask.clone();
      falseMask.andNot(trueMask);
      return falseMask;
    }

    private void sameKey(SectionResult other) {
      if (!key.equals(Objects.requireNonNull(other, "other").key))
        throw new IllegalArgumentException("Cannot combine different sections");
    }
  }

  /**
   * Scans the first {@code maxSections} in y/z/x order, without retaining a cursor. Cells outside
   * bounds are known false in the masks; use {@link #scanSection} to schedule later keys.
   */
  public List<SectionResult> scan(
      Bounds bounds, Compiler.Bound scope, Compiler.Bound select, Facts facts, int maxSections) {
    if (maxSections <= 0) throw new IllegalArgumentException("Positive section budget required");
    var results = new ArrayList<SectionResult>();
    for (int sy = Math.floorDiv(bounds.minY(), SECTION_SIZE);
        sy <= Math.floorDiv(bounds.maxY(), SECTION_SIZE);
        sy++)
      for (int sz = Math.floorDiv(bounds.minZ(), SECTION_SIZE);
          sz <= Math.floorDiv(bounds.maxZ(), SECTION_SIZE);
          sz++)
        for (int sx = Math.floorDiv(bounds.minX(), SECTION_SIZE);
            sx <= Math.floorDiv(bounds.maxX(), SECTION_SIZE);
            sx++) {
          if (results.size() >= maxSections) return List.copyOf(results);
          results.add(
              scanSection(
                  bounds, new SectionKey(bounds.dimension(), sx, sy, sz), scope, select, facts));
        }
    return List.copyOf(results);
  }

  /** Enumerates distinct sections while retaining all overlapping bounds for mask union. */
  public List<SectionGroup> group(List<Bounds> bounds, int maxSections) {
    if (maxSections <= 0) throw new IllegalArgumentException("Positive section budget required");
    var grouped = new LinkedHashMap<SectionKey, ArrayList<Bounds>>();
    for (var box : bounds)
      for (int y = Math.floorDiv(box.minY(), SECTION_SIZE);
          y <= Math.floorDiv(box.maxY(), SECTION_SIZE);
          y++)
        for (int z = Math.floorDiv(box.minZ(), SECTION_SIZE);
            z <= Math.floorDiv(box.maxZ(), SECTION_SIZE);
            z++)
          for (int x = Math.floorDiv(box.minX(), SECTION_SIZE);
              x <= Math.floorDiv(box.maxX(), SECTION_SIZE);
              x++) {
            var key = new SectionKey(box.dimension(), x, y, z);
            var contributions = grouped.computeIfAbsent(key, ignored -> new ArrayList<>(1));
            if (!contributions.contains(box)) contributions.add(box);
            if (grouped.size() > maxSections)
              throw new IllegalArgumentException("Scope exceeds " + maxSections + " sections");
          }
    var result = new ArrayList<SectionGroup>(grouped.size());
    grouped.forEach((key, contributions) -> result.add(new SectionGroup(key, contributions)));
    return List.copyOf(result);
  }

  /** Unions the three-valued masks from a section's contributing finite bounds. */
  public SectionResult scanGroup(
      SectionGroup group, Compiler.Bound scope, Compiler.Bound select, Facts facts) {
    SectionResult result = null;
    for (var box : group.bounds()) {
      var part = scanSection(box, group.key(), scope, select, facts);
      result = result == null ? part : result.or(part);
    }
    return Objects.requireNonNull(result);
  }

  /**
   * Scans one section from a caller-supplied capture. Cells outside {@code bounds} are known false;
   * the key must belong to the bounds' dimension.
   */
  public SectionResult scanSection(
      Bounds bounds, SectionKey key, Compiler.Bound scope, Compiler.Bound select, Facts facts) {
    if (scope.type() != SetType.POS || select.type() != SetType.POS)
      throw new IllegalArgumentException("Expected PosSet");
    if (!key.dimension().equals(bounds.dimension()))
      throw new IllegalArgumentException("Section dimension differs from bounds");
    var trueMask = new BitSet(SECTION_VOLUME);
    var knownMask = new BitSet(SECTION_VOLUME);
    knownMask.set(0, SECTION_VOLUME);
    long baseX = (long) key.x() * SECTION_SIZE,
        baseY = (long) key.y() * SECTION_SIZE,
        baseZ = (long) key.z() * SECTION_SIZE;
    for (long y = Math.max(baseY, bounds.minY());
        y <= Math.min(baseY + LOCAL_MASK, bounds.maxY());
        y++)
      for (long z = Math.max(baseZ, bounds.minZ());
          z <= Math.min(baseZ + LOCAL_MASK, bounds.maxZ());
          z++)
        for (long x = Math.max(baseX, bounds.minX());
            x <= Math.min(baseX + LOCAL_MASK, bounds.maxX());
            x++) {
          int index =
              (int)
                  (((y - baseY) << (2 * SECTION_SHIFT))
                      | ((z - baseZ) << SECTION_SHIFT)
                      | (x - baseX));
          var p = new Position(bounds.dimension(), (int) x, (int) y, (int) z);
          Truth inScope = scope.at(facts, p);
          Truth value = inScope == Truth.FALSE ? Truth.FALSE : inScope.and(select.at(facts, p));
          if (value == Truth.UNKNOWN) knownMask.clear(index);
          if (value == Truth.TRUE) trueMask.set(index);
        }
    return new SectionResult(key, trueMask, knownMask);
  }
}

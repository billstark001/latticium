package io.github.billstark001.latticium.planning;

import io.github.billstark001.latticium.dsl.Compiler;
import io.github.billstark001.latticium.dsl.Model.*;
import java.util.ArrayList;
import java.util.BitSet;
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
  }

  public record SectionKey(ResourceId dimension, int x, int y, int z) {}

  public record SectionResult(SectionKey key, BitSet trueMask, BitSet knownMask) {
    public SectionResult {
      trueMask = (BitSet) trueMask.clone();
      knownMask = (BitSet) knownMask.clone();
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
  }

  /** Scans at most {@code maxSections}; cells outside bounds are known false in the masks. */
  public List<SectionResult> scan(
      Bounds bounds, Compiler.Bound scope, Compiler.Bound select, Facts facts, int maxSections) {
    if (scope.type() != SetType.POS || select.type() != SetType.POS)
      throw new IllegalArgumentException("Expected PosSet");
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
          var key = new SectionKey(bounds.dimension(), sx, sy, sz);
          var trueMask = new BitSet(SECTION_VOLUME);
          var knownMask = new BitSet(SECTION_VOLUME);
          knownMask.set(0, SECTION_VOLUME);
          long baseX = (long) sx * SECTION_SIZE,
              baseY = (long) sy * SECTION_SIZE,
              baseZ = (long) sz * SECTION_SIZE;
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
                Truth value =
                    inScope == Truth.FALSE ? Truth.FALSE : inScope.and(select.at(facts, p));
                if (value == Truth.UNKNOWN) knownMask.clear(index);
                if (value == Truth.TRUE) trueMask.set(index);
              }
          results.add(new SectionResult(key, trueMask, knownMask));
        }
    return List.copyOf(results);
  }
}

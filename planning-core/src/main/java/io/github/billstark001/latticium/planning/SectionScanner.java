package io.github.billstark001.latticium.planning;

import io.github.billstark001.latticium.dsl.Compiler;
import io.github.billstark001.latticium.dsl.Model.*;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/** Pure section scanner; the caller supplies immutable, session-bound facts. */
public final class SectionScanner {
  public record Bounds(
      ResourceId dimension, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    public Bounds {
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
      return 4096 - knownMask.cardinality();
    }
  }

  public List<SectionResult> scan(
      Bounds bounds, Compiler.Bound scope, Compiler.Bound select, Facts facts, int maxSections) {
    if (scope.type() != SetType.POS || select.type() != SetType.POS)
      throw new IllegalArgumentException("Expected PosSet");
    if (maxSections <= 0) throw new IllegalArgumentException("Positive section budget required");
    var results = new ArrayList<SectionResult>();
    for (int sy = Math.floorDiv(bounds.minY(), 16); sy <= Math.floorDiv(bounds.maxY(), 16); sy++)
      for (int sz = Math.floorDiv(bounds.minZ(), 16); sz <= Math.floorDiv(bounds.maxZ(), 16); sz++)
        for (int sx = Math.floorDiv(bounds.minX(), 16);
            sx <= Math.floorDiv(bounds.maxX(), 16);
            sx++) {
          if (results.size() >= maxSections) return List.copyOf(results);
          var key = new SectionKey(bounds.dimension(), sx, sy, sz);
          var trueMask = new BitSet(4096);
          var knownMask = new BitSet(4096);
          for (int i = 0; i < 4096; i++) {
            int x = sx * 16 + (i & 15), z = sz * 16 + ((i >> 4) & 15), y = sy * 16 + (i >> 8);
            if (x < bounds.minX()
                || x > bounds.maxX()
                || y < bounds.minY()
                || y > bounds.maxY()
                || z < bounds.minZ()
                || z > bounds.maxZ()) {
              knownMask.set(i);
              continue;
            }
            var p = new Position(bounds.dimension(), x, y, z);
            Truth value = scope.at(facts, p).and(select.at(facts, p));
            if (value != Truth.UNKNOWN) knownMask.set(i);
            if (value == Truth.TRUE) trueMask.set(i);
          }
          results.add(new SectionResult(key, trueMask, knownMask));
        }
    return List.copyOf(results);
  }
}

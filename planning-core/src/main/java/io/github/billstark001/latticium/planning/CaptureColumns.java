package io.github.billstark001.latticium.planning;

import io.github.billstark001.latticium.dsl.FactDependencies;
import io.github.billstark001.latticium.dsl.FactDependencies.Fact;
import io.github.billstark001.latticium.planning.SectionScanner.Bounds;
import java.util.BitSet;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Bounded per-column masks derived from exact reads, with full-window fallback for unknown offsets.
 */
public final class CaptureColumns {
  private static final Set<Fact> WORLD =
      Set.of(Fact.STATE, Fact.BIOME, Fact.FLUID, Fact.LIGHT, Fact.SOLID);
  private final CellWindow window;
  private final Map<Fact, BitSet> columns = new EnumMap<>(Fact.class);
  private final Map<Fact, Bounds> bounds = new EnumMap<>(Fact.class);
  private final BitSet world = new BitSet();

  /** The evaluated positions may lie outside the capture window when all reads are shifted. */
  public CaptureColumns(CellWindow window, Bounds evaluated, FactDependencies requirements) {
    requireDimension(window, evaluated);
    this.window = window;
    requirements
        .columns()
        .forEach(
            (fact, reads) -> {
              var mask = new BitSet(window.size());
              if (reads.broad()) {
                mask.set(0, window.size());
                bounds.put(fact, window.bounds());
              } else
                for (var offset : reads.offsets()) {
                  var clipped = shifted(window.bounds(), evaluated, offset);
                  if (clipped == null) continue;
                  mark(mask, window, clipped);
                  bounds.merge(fact, clipped, CaptureColumns::union);
                }
              columns.put(fact, mask);
              if (WORLD.contains(fact)) world.or(mask);
            });
  }

  /**
   * Shrinks an allowed halo to the bounding box of requested columns; global-only reads need no
   * halo.
   */
  public static CellWindow window(
      CellWindow allowed, Bounds evaluated, FactDependencies requirements) {
    requireDimension(allowed, evaluated);
    Bounds result = null;
    for (var reads : requirements.columns().values()) {
      if (reads.broad()) return allowed;
      for (var offset : reads.offsets()) {
        var clipped = shifted(allowed.bounds(), evaluated, offset);
        if (clipped != null) result = result == null ? clipped : union(result, clipped);
      }
    }
    return result == null ? new CellWindow(evaluated) : new CellWindow(result);
  }

  public Optional<Bounds> bounds(Fact fact) {
    return Optional.ofNullable(bounds.get(fact));
  }

  /** Returns the next cell with at least one requested world column, or -1. */
  public int nextWorldIndex(int from) {
    return world.nextSetBit(from);
  }

  public boolean needs(Fact fact, int index) {
    java.util.Objects.checkIndex(index, window.size());
    var mask = columns.get(fact);
    return mask != null && mask.get(index);
  }

  private static Bounds shifted(Bounds allowed, Bounds evaluated, FactDependencies.Offset offset) {
    long minX = Math.max(allowed.minX(), (long) evaluated.minX() + offset.x());
    long minY = Math.max(allowed.minY(), (long) evaluated.minY() + offset.y());
    long minZ = Math.max(allowed.minZ(), (long) evaluated.minZ() + offset.z());
    long maxX = Math.min(allowed.maxX(), (long) evaluated.maxX() + offset.x());
    long maxY = Math.min(allowed.maxY(), (long) evaluated.maxY() + offset.y());
    long maxZ = Math.min(allowed.maxZ(), (long) evaluated.maxZ() + offset.z());
    return minX > maxX || minY > maxY || minZ > maxZ
        ? null
        : new Bounds(
            allowed.dimension(),
            (int) minX,
            (int) minY,
            (int) minZ,
            (int) maxX,
            (int) maxY,
            (int) maxZ);
  }

  private static void mark(BitSet mask, CellWindow window, Bounds box) {
    var all = window.bounds();
    int width = all.maxX() - all.minX() + 1, height = all.maxY() - all.minY() + 1;
    for (long z = box.minZ(); z <= box.maxZ(); z++)
      for (long y = box.minY(); y <= box.maxY(); y++) {
        int start =
            (int) (z - all.minZ()) * width * height
                + (int) (y - all.minY()) * width
                + (box.minX() - all.minX());
        mask.set(start, start + box.maxX() - box.minX() + 1);
      }
  }

  private static Bounds union(Bounds a, Bounds b) {
    return new Bounds(
        a.dimension(),
        Math.min(a.minX(), b.minX()),
        Math.min(a.minY(), b.minY()),
        Math.min(a.minZ(), b.minZ()),
        Math.max(a.maxX(), b.maxX()),
        Math.max(a.maxY(), b.maxY()),
        Math.max(a.maxZ(), b.maxZ()));
  }

  private static void requireDimension(CellWindow window, Bounds evaluated) {
    if (!window.bounds().dimension().equals(evaluated.dimension()))
      throw new IllegalArgumentException("Capture/evaluation dimensions differ");
  }
}

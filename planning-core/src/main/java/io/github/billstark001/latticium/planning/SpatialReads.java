package io.github.billstark001.latticium.planning;

import io.github.billstark001.latticium.dsl.FactDependencies;
import io.github.billstark001.latticium.dsl.FactDependencies.Fact;
import io.github.billstark001.latticium.dsl.Model.Position;
import io.github.billstark001.latticium.planning.Revisions.Key;
import io.github.billstark001.latticium.planning.SectionScanner.Bounds;
import io.github.billstark001.latticium.planning.SectionScanner.SectionKey;
import java.util.Objects;

/** Compact spatial version dependencies: one descriptor rather than a stamp for every cell. */
public record SpatialReads(
    Bounds evaluated,
    FactDependencies dependencies,
    int radius,
    Execution.Granularity granularity,
    Revisions.Reliability reliability) {
  public SpatialReads {
    Objects.requireNonNull(evaluated);
    Objects.requireNonNull(dependencies);
    Objects.requireNonNull(granularity);
    Objects.requireNonNull(reliability);
    if (radius < 0 || radius > 16) throw new IllegalArgumentException("Invalid spatial radius");
  }

  public SpatialReads at(Position position) {
    return new SpatialReads(
        new Bounds(
            position.dimension(),
            position.x(),
            position.y(),
            position.z(),
            position.x(),
            position.y(),
            position.z()),
        dependencies,
        radius,
        granularity,
        reliability);
  }

  public boolean includes(Key key) {
    if (key.resource() instanceof Revisions.SourceCell cell)
      return includes(new Key(key.domain(), cell.position()));
    if (key.domain() == Revisions.Domain.RESIDENCY && key.resource() instanceof Position chunk) {
      if (!chunk.dimension().equals(evaluated.dimension()) || !dependencies.usesWorld())
        return false;
      long x = (long) chunk.x() << 4, z = (long) chunk.z() << 4;
      for (var reads : dependencies.columns().values()) {
        if (reads.broad()
            && intersects(x, Long.MIN_VALUE, z, x + 15, Long.MAX_VALUE, z + 15, 0, 0, 0, radius))
          return true;
        for (var offset : reads.offsets())
          if (intersects(
              x,
              Long.MIN_VALUE,
              z,
              x + 15,
              Long.MAX_VALUE,
              z + 15,
              offset.x(),
              offset.y(),
              offset.z(),
              0)) return true;
      }
      return false;
    }
    final Fact fact;
    try {
      fact = Fact.valueOf(key.domain().name());
    } catch (IllegalArgumentException globalDomain) {
      return false;
    }
    var reads = dependencies.columns().get(fact);
    if (reads == null) return false;
    if (key.resource() instanceof Position position) {
      if (!position.dimension().equals(evaluated.dimension())) return false;
      if (reads.broad())
        return intersects(
            position.x(),
            position.y(),
            position.z(),
            position.x(),
            position.y(),
            position.z(),
            0,
            0,
            0,
            radius);
      for (var offset : reads.offsets())
        if (intersects(
            position.x(),
            position.y(),
            position.z(),
            position.x(),
            position.y(),
            position.z(),
            offset.x(),
            offset.y(),
            offset.z(),
            0)) return true;
    } else if (granularity == Execution.Granularity.SECTION
        && key.resource() instanceof SectionKey section) {
      if (!section.dimension().equals(evaluated.dimension())) return false;
      long x = (long) section.x() << 4, y = (long) section.y() << 4, z = (long) section.z() << 4;
      if (reads.broad()) return intersects(x, y, z, x + 15, y + 15, z + 15, 0, 0, 0, radius);
      for (var offset : reads.offsets())
        if (intersects(x, y, z, x + 15, y + 15, z + 15, offset.x(), offset.y(), offset.z(), 0))
          return true;
    }
    return false;
  }

  private boolean intersects(
      long x0, long y0, long z0, long x1, long y1, long z1, int dx, int dy, int dz, int halo) {
    return x1 >= (long) evaluated.minX() + dx - halo
        && x0 <= (long) evaluated.maxX() + dx + halo
        && y1 >= (long) evaluated.minY() + dy - halo
        && y0 <= (long) evaluated.maxY() + dy + halo
        && z1 >= (long) evaluated.minZ() + dz - halo
        && z0 <= (long) evaluated.maxZ() + dz + halo;
  }
}

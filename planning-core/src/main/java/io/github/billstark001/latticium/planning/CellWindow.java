package io.github.billstark001.latticium.planning;

import io.github.billstark001.latticium.dsl.Model.Position;
import io.github.billstark001.latticium.planning.SectionScanner.Bounds;
import java.util.Objects;

/** Inclusive dense capture window, indexed x-fastest, then y, then z. */
public record CellWindow(Bounds bounds) {
  /** A 16-cube section and the largest supported 16-block halo. */
  public static final int MAX_CELLS = 48 * 48 * 48;

  public CellWindow {
    Objects.requireNonNull(bounds, "bounds");
    long width = (long) bounds.maxX() - bounds.minX() + 1;
    long height = (long) bounds.maxY() - bounds.minY() + 1;
    long depth = (long) bounds.maxZ() - bounds.minZ() + 1;
    if (width > MAX_CELLS || height > MAX_CELLS / width || depth > MAX_CELLS / (width * height))
      throw new IllegalArgumentException("Capture window exceeds " + MAX_CELLS + " cells");
  }

  public int size() {
    return (bounds.maxX() - bounds.minX() + 1)
        * (bounds.maxY() - bounds.minY() + 1)
        * (bounds.maxZ() - bounds.minZ() + 1);
  }

  public boolean contains(CellWindow other) {
    var b = other.bounds();
    return bounds.dimension().equals(b.dimension())
        && bounds.minX() <= b.minX()
        && bounds.minY() <= b.minY()
        && bounds.minZ() <= b.minZ()
        && bounds.maxX() >= b.maxX()
        && bounds.maxY() >= b.maxY()
        && bounds.maxZ() >= b.maxZ();
  }

  /** Returns -1 outside the window or in another dimension. */
  public int index(Position position) {
    if (!bounds.contains(position)) return -1;
    int width = bounds.maxX() - bounds.minX() + 1;
    int height = bounds.maxY() - bounds.minY() + 1;
    return (position.z() - bounds.minZ()) * width * height
        + (position.y() - bounds.minY()) * width
        + (position.x() - bounds.minX());
  }

  /** Inverse of {@link #index}; accepts only valid dense indices. */
  public Position position(int index) {
    Objects.checkIndex(index, size());
    int width = bounds.maxX() - bounds.minX() + 1;
    int height = bounds.maxY() - bounds.minY() + 1;
    return new Position(
        bounds.dimension(),
        bounds.minX() + index % width,
        bounds.minY() + index / width % height,
        bounds.minZ() + index / (width * height));
  }
}

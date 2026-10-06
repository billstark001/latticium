package io.github.billstark001.latticium.planning;

import io.github.billstark001.latticium.dsl.Model.Position;
import io.github.billstark001.latticium.dsl.Model.TargetCell;
import io.github.billstark001.latticium.planning.SectionScanner.Bounds;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Immutable x-fastest, then y, then z target columns for one inclusive, session-qualified window.
 */
public record TargetSlice(Host.SessionId session, CellWindow window, List<TargetCell> cells) {
  /** Matches the largest 16-cube section with a 16-block halo supported by the game adapter. */
  public static final int MAX_CELLS = CellWindow.MAX_CELLS;

  public TargetSlice {
    Objects.requireNonNull(session, "session");
    Objects.requireNonNull(window, "window");
    int size = window.size();
    if (cells.size() != size)
      throw new IllegalArgumentException("Target slice size differs from bounds");
    cells = List.copyOf(cells);
  }

  public TargetSlice(Host.SessionId session, Bounds bounds, List<TargetCell> cells) {
    this(session, new CellWindow(bounds), cells);
  }

  public Bounds bounds() {
    return window.bounds();
  }

  /** Captures fresh point values without retaining a live provider or game object in the slice. */
  public static TargetSlice capture(
      Host.SessionId session, Bounds bounds, Function<Position, TargetCell> reader) {
    Objects.requireNonNull(reader, "reader");
    var window = new CellWindow(bounds);
    var cells = new ArrayList<TargetCell>(window.size());
    for (long z = bounds.minZ(); z <= bounds.maxZ(); z++)
      for (long y = bounds.minY(); y <= bounds.maxY(); y++)
        for (long x = bounds.minX(); x <= bounds.maxX(); x++)
          cells.add(reader.apply(new Position(bounds.dimension(), (int) x, (int) y, (int) z)));
    return new TargetSlice(session, window, cells);
  }

  /** Reads only this window; an out-of-window position is unavailable rather than DontCare. */
  public TargetCell target(Position position) {
    int index = window.index(position);
    return index < 0 ? new TargetCell.Unknown("Target outside captured window") : cells.get(index);
  }

  public TargetSlice map(UnaryOperator<TargetCell> transform) {
    return new TargetSlice(session, window, cells.stream().map(transform).toList());
  }
}

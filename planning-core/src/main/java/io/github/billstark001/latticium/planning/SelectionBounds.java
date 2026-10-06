package io.github.billstark001.latticium.planning;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Immutable named selection bounds shared by jobs, captures and action rechecks. */
public final class SelectionBounds {
  private SelectionBounds() {}

  /** Copies both the map and every bounds list; caller edits cannot alter an existing snapshot. */
  public static Map<String, List<SectionScanner.Bounds>> copy(
      Map<String, List<SectionScanner.Bounds>> source) {
    var copied = new HashMap<String, List<SectionScanner.Bounds>>();
    source.forEach((name, bounds) -> copied.put(name, List.copyOf(bounds)));
    return Map.copyOf(copied);
  }
}

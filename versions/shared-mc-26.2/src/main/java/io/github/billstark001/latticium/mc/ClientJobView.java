package io.github.billstark001.latticium.mc;

import io.github.billstark001.latticium.dsl.Model.BlockState;
import io.github.billstark001.latticium.dsl.Model.Position;
import io.github.billstark001.latticium.dsl.Model.TargetCell;
import io.github.billstark001.latticium.planning.JobController;
import io.github.billstark001.latticium.planning.PositionDistances;
import io.github.billstark001.latticium.planning.Profile;
import java.util.Comparator;
import java.util.Map;

/** Ordering and status presentation for a client job. */
final class ClientJobView {
  private ClientJobView() {}

  static Comparator<Position> order(Profile.Select.Choose choice, Position player) {
    Comparator<Position> yzx =
        Comparator.comparingInt(Position::y)
            .thenComparingInt(Position::z)
            .thenComparingInt(Position::x);
    Comparator<Position> distance = (a, b) -> PositionDistances.compare(a, b, player);
    return switch (choice) {
      case NEAREST -> distance.thenComparing(yzx);
      case FARTHEST -> distance.reversed().thenComparing(yzx);
      case Y_ASC -> yzx;
      case Y_DESC -> yzx.reversed();
      case SCAN -> (a, b) -> 0;
    };
  }

  static boolean isAir(BlockState state) {
    return io.github.billstark001.latticium.dsl.Model.isVanillaAir(state);
  }

  static boolean matches(BlockState current, TargetCell target) {
    return target instanceof TargetCell.Exact exact && current.equals(exact.state())
        || target instanceof TargetCell.Clear && isAir(current);
  }

  static boolean isFinished(
      boolean cancelled,
      JobController active,
      boolean budgetExhausted,
      RefreshCoordinator refresh,
      boolean noCandidates) {
    return !cancelled
        && active == null
        && (budgetExhausted || !refresh.continuous() && noCandidates && !refresh.hasScanWork());
  }

  static String status(
      RefreshCoordinator refresh,
      int pending,
      int completed,
      int actions,
      int actionLimit,
      Map<Position, String> blocked,
      boolean budgetExhausted,
      JobController active) {
    var example =
        blocked.entrySet().stream()
            .min(Comparator.comparing(entry -> entry.getKey().toString()))
            .orElse(null);
    return "scanned="
        + refresh.scannedSections()
        + "/"
        + refresh.sectionCount()
        + " pending="
        + pending
        + " completed="
        + completed
        + " actions="
        + actions
        + "/"
        + actionLimit
        + " deferredSections="
        + refresh.deferredCount()
        + " unsupportedSections="
        + refresh.unsupportedCount()
        + " refresh="
        + (refresh.continuous() ? "continuous" : "manual")
        + " dirty="
        + refresh.dirtyCount()
        + " rescan="
        + refresh.refreshRemaining()
        + " blocked="
        + blocked.size()
        + (example == null ? "" : " firstBlocked=" + example.getKey() + ": " + example.getValue())
        + (budgetExhausted ? " budgetExhausted=true" : "")
        + (active == null ? "" : " active=" + active.status());
  }
}

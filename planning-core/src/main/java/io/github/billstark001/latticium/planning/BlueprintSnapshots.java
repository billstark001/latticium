package io.github.billstark001.latticium.planning;

import io.github.billstark001.latticium.dsl.Model.Position;
import io.github.billstark001.latticium.dsl.Model.ResourceId;
import io.github.billstark001.latticium.dsl.Model.TargetCell;
import io.github.billstark001.latticium.planning.SectionScanner.Bounds;
import java.lang.ref.WeakReference;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Shared session, bounds and overlap semantics for read-only schematic bridges. Client thread only.
 */
public final class BlueprintSnapshots implements Host.TargetSource, Host.SelectionSource {
  private static final Comparator<Bounds> ORDER =
      Comparator.comparing(Bounds::dimension)
          .thenComparingInt(Bounds::minX)
          .thenComparingInt(Bounds::minY)
          .thenComparingInt(Bounds::minZ)
          .thenComparingInt(Bounds::maxX)
          .thenComparingInt(Bounds::maxY)
          .thenComparingInt(Bounds::maxZ);

  /**
   * One fresh placement view. World and placement identities are compared by reference; an optional
   * revision is compared by value. Bounds keep duplicate subregions because overlaps are Unknown.
   * The cell reader must distinguish unloaded cells from exact air and run on the client thread.
   */
  public record View(
      Object world,
      Object placement,
      Object revision,
      ResourceId dimension,
      List<Bounds> bounds,
      List<Bounds> conflicts,
      Function<Position, TargetCell> cells) {
    public View {
      Objects.requireNonNull(world, "world");
      Objects.requireNonNull(placement, "placement");
      Objects.requireNonNull(dimension, "dimension");
      Objects.requireNonNull(cells, "cells");
      bounds = bounds.stream().sorted(ORDER).toList();
      conflicts = List.copyOf(conflicts);
      if (bounds.stream().anyMatch(b -> !b.dimension().equals(dimension))
          || conflicts.stream().anyMatch(b -> !b.dimension().equals(dimension)))
        throw new IllegalArgumentException("Blueprint bounds have another dimension");
    }
  }

  private record Identity(
      WeakReference<Object> world,
      WeakReference<Object> placement,
      Object revision,
      ResourceId dimension,
      List<Bounds> bounds) {}

  private record Validated(View view, String reason) {}

  private final String selection;
  private final Supplier<View> source;
  private final Map<Host.SessionId, Identity> sessions = new WeakHashMap<>();

  /** The supplier returns a fresh view, or null when no active placement/world is available. */
  public BlueprintSnapshots(String selection, Supplier<View> source) {
    this.selection = Objects.requireNonNull(selection, "selection");
    this.source = Objects.requireNonNull(source, "source");
  }

  @Override
  public List<Bounds> finiteBounds(String name, Host.SessionId session) {
    if (!selection.equals(name)) return List.of();
    Objects.requireNonNull(session, "session");
    var view = source.get();
    if (view == null || view.bounds().isEmpty()) return List.of();
    var identity =
        new Identity(
            new WeakReference<>(view.world()),
            new WeakReference<>(view.placement()),
            view.revision(),
            view.dimension(),
            view.bounds());
    var previous = sessions.putIfAbsent(session, identity);
    // A session is established once; repeated enumeration must not authorize a different placement.
    return previous == null || same(previous, view) ? view.bounds() : List.of();
  }

  @Override
  public TargetCell target(Position position, Host.SessionId session) {
    var checked = validate(session);
    return checked.view() == null
        ? new TargetCell.Unknown(checked.reason())
        : read(checked.view(), position);
  }

  @Override
  public Optional<TargetSlice> slice(Bounds bounds, Host.SessionId session) {
    var checked = validate(session);
    if (checked.view() == null) return Optional.empty();
    return Optional.of(TargetSlice.capture(session, bounds, p -> read(checked.view(), p)));
  }

  private Validated validate(Host.SessionId session) {
    var identity = sessions.get(session);
    if (identity == null) return new Validated(null, "Blueprint session changed");
    var current = source.get();
    if (current == null) return new Validated(null, "Active blueprint unavailable");
    if (!same(identity, current))
      return new Validated(null, "Active placement, world, revision or bounds changed");
    return new Validated(current, "");
  }

  private static boolean same(Identity previous, View current) {
    return previous.world().get() == current.world()
        && previous.placement().get() == current.placement()
        && Objects.equals(previous.revision(), current.revision())
        && previous.dimension().equals(current.dimension())
        && previous.bounds().equals(current.bounds());
  }

  private static TargetCell read(View view, Position position) {
    if (!view.dimension().equals(position.dimension()))
      return new TargetCell.Unknown("Blueprint dimension changed");
    int inside = 0;
    for (var box : view.bounds()) if (box.contains(position)) inside++;
    if (inside == 0) return new TargetCell.DontCare();
    if (inside > 1) return new TargetCell.Unknown("Overlapping blueprint subregions");
    for (var box : view.conflicts())
      if (box.contains(position)) return new TargetCell.Unknown("Overlapping active placements");
    var cell = view.cells().apply(position);
    return cell == null ? new TargetCell.Unknown("Blueprint reader returned null") : cell;
  }
}

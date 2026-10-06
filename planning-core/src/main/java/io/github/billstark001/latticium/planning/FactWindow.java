package io.github.billstark001.latticium.planning;

import io.github.billstark001.latticium.dsl.Model.*;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable dense world/target capture with optional global facts. Contains no live game objects.
 */
public final class FactWindow implements Facts {
  private static final TargetCell.Unknown TARGET_UNAVAILABLE =
      new TargetCell.Unknown("Target not captured");
  private final CellWindow window;
  private final WorldCell[] world;
  private final TargetSlice targets;
  private final Map<String, List<SectionScanner.Bounds>> selections;
  private final Position player;
  private final Set<ResourceId> inventory;

  /**
   * Copies all mutable inputs. Null world columns or cells mean unavailable; a supplied target
   * slice must have this session and lie wholly within this window. Null player/inventory mean
   * unavailable too.
   */
  public FactWindow(
      Host.SessionId session,
      CellWindow window,
      WorldCell[] world,
      TargetSlice targets,
      Map<String, List<SectionScanner.Bounds>> selections,
      Position player,
      Set<ResourceId> inventory) {
    Objects.requireNonNull(session, "session");
    this.window = Objects.requireNonNull(window, "window");
    if (world != null && world.length != window.size())
      throw new IllegalArgumentException("World cells differ from capture window");
    if (targets != null
        && (!targets.session().equals(session) || !window.contains(targets.window())))
      throw new IllegalArgumentException("Target slice differs from capture session/window");
    this.world = world == null ? null : world.clone();
    this.targets = targets;
    this.selections = SelectionBounds.copy(selections);
    this.player = player;
    this.inventory = inventory == null ? null : Set.copyOf(inventory);
  }

  @Override
  public Optional<WorldCell> world(Position position) {
    if (world == null) return Optional.empty();
    int index = window.index(position);
    return index < 0 ? Optional.empty() : Optional.ofNullable(world[index]);
  }

  @Override
  public TargetCell target(Position position) {
    return targets == null ? TARGET_UNAVAILABLE : targets.target(position);
  }

  @Override
  public Optional<Position> player() {
    return Optional.ofNullable(player);
  }

  @Override
  public Optional<Set<ResourceId>> inventory() {
    return Optional.ofNullable(inventory);
  }

  @Override
  public Truth selection(String name, Position position) {
    var bounds = selections.get(name);
    if (bounds == null) return Truth.UNKNOWN;
    for (var box : bounds) if (box.contains(position)) return Truth.TRUE;
    return Truth.FALSE;
  }
}

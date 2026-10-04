package io.github.billstark001.latticium.planning;

import io.github.billstark001.latticium.dsl.Syntax.*;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Spatial inputs that can change the truth of a position expression. */
public final class RefreshReads {
  private static final int MAX_EXACT_OFFSETS = 512;
  private static final int MAX_ANALYSIS_VISITS = 4096;
  private static final Offset ORIGIN = new Offset(0, 0, 0);
  private static final List<Offset> NEIGHBORS =
      List.of(
          new Offset(1, 0, 0),
          new Offset(-1, 0, 0),
          new Offset(0, 1, 0),
          new Offset(0, -1, 0),
          new Offset(0, 0, 1),
          new Offset(0, 0, -1));

  private RefreshReads() {}

  /** The expression at candidate {@code p} reads world facts at {@code p + offset}. */
  public record Offset(int x, int y, int z) {
    Offset plus(Offset other) {
      return new Offset(
          Math.addExact(x, other.x), Math.addExact(y, other.y), Math.addExact(z, other.z));
    }
  }

  /** A player sphere is evaluated at the candidate plus {@code offset}. */
  public record PlayerSphere(Offset offset, int radius) {}

  public record Reads(
      Set<Offset> worldOffsets,
      List<PlayerSphere> playerSpheres,
      boolean broadWorld,
      boolean broadPlayer,
      boolean periodic) {
    public Reads {
      worldOffsets = Set.copyOf(worldOffsets);
      playerSpheres = List.copyOf(playerSpheres);
    }

    public boolean usesPlayer() {
      return broadPlayer || !playerSpheres.isEmpty();
    }
  }

  /** Unknown primitives use the compiler's conservative read radius at the call site. */
  public static Reads in(Expr expression) {
    var collector = new Collector();
    collector.visit(expression, ORIGIN);
    return new Reads(
        collector.worldOffsets,
        collector.playerSpheres,
        collector.broadWorld,
        collector.broadPlayer,
        collector.periodic);
  }

  private static final class Collector {
    private final Set<Offset> worldOffsets = new LinkedHashSet<>();
    private final List<PlayerSphere> playerSpheres = new ArrayList<>();
    private boolean broadWorld;
    private boolean broadPlayer;
    private boolean periodic;
    private boolean truncated;
    private int visits;

    private void world(Offset offset) {
      if (broadWorld) return;
      worldOffsets.add(offset);
      if (worldOffsets.size() > MAX_EXACT_OFFSETS) {
        broadWorld = true;
        worldOffsets.clear();
      }
    }

    private void visit(Expr expression, Offset shift) {
      if (truncated) return;
      if (++visits > MAX_ANALYSIS_VISITS) {
        truncated = true;
        broadWorld = true;
        broadPlayer = true;
        worldOffsets.clear();
        playerSpheres.clear();
        return;
      }
      if (expression instanceof Atom) {
        world(shift);
      } else if (expression instanceof Binary binary) {
        visit(binary.left(), shift);
        visit(binary.right(), shift);
      } else if (expression instanceof Negate negate) {
        visit(negate.inner(), shift);
      } else if (expression instanceof Call call) {
        switch (call.name()) {
          case "offset" ->
              visit(
                  call.args().get(3),
                  shift.plus(
                      new Offset(
                          integer(call.args().get(0)),
                          integer(call.args().get(1)),
                          integer(call.args().get(2)))));
          case "adjacent" -> {
            for (var neighbor : NEIGHBORS) visit(call.args().getFirst(), shift.plus(neighbor));
          }
          case "surface" -> {
            world(shift);
            for (var neighbor : NEIGHBORS) world(shift.plus(neighbor));
          }
          case "sphere" -> {
            if (call.args().getFirst() instanceof Name anchor && anchor.value().equals("player"))
              playerSpheres.add(new PlayerSphere(shift, integer(call.args().get(1))));
          }
          case "current", "state", "biome", "fluid", "solid" -> world(shift);
          case "light" -> {
            world(shift);
            periodic = true; // Light packets need not carry a block-state update.
          }
          case "target", "has_target", "matches_target", "changed", "same", "compare" -> {
            world(shift);
            periodic = true; // A bridge can change its target without a world packet.
          }
          case "selection" -> {
            if (call.args().getFirst() instanceof Text name
                && name.value().equals("active_blueprint")) periodic = true;
          }
          case "all",
              "none",
              "box",
              "dimension",
              "inventory",
              "property",
              "property_range",
              "states_of",
              "blocks_of" -> {}
          default -> {
            broadWorld = true;
            broadPlayer = true;
            periodic = true;
          }
        }
      }
    }

    private static int integer(Expr expression) {
      if (expression instanceof Name name) return Integer.parseInt(name.value());
      if (expression instanceof Range range
          && range.axis() == '\0'
          && range.range().min() != null
          && range.range().min().equals(range.range().max())) return range.range().min();
      throw new IllegalArgumentException("Expected integer in bound refresh expression");
    }
  }
}

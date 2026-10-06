package io.github.billstark001.latticium.planning;

import static org.junit.jupiter.api.Assertions.*;

import io.github.billstark001.latticium.dsl.Model.*;
import io.github.billstark001.latticium.planning.SectionScanner.Bounds;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class FactWindowTest {
  private static final ResourceId DIM = ResourceId.parse("minecraft:overworld");
  private static final ResourceId STONE = ResourceId.parse("minecraft:stone");
  private static final WorldCell CELL =
      new WorldCell(new BlockState(STONE, Map.of()), null, null, null, null);

  @Test
  void denseIndicesAndWorldCellsRoundTripAcrossDimensionsAndCoordinateEdges() {
    var random = new Random(10062026);
    for (int sample = 0; sample < 100; sample++) {
      int x =
          switch (sample % 3) {
            case 0 -> Integer.MIN_VALUE;
            case 1 -> Integer.MAX_VALUE - 10;
            default -> -30;
          };
      int y = -2,
          z = -16,
          width = 1 + random.nextInt(10),
          height = 1 + random.nextInt(10),
          depth = 1 + random.nextInt(10);
      var window =
          new CellWindow(new Bounds(DIM, x, y, z, x + width - 1, y + height - 1, z + depth - 1));
      var cells = new WorldCell[window.size()];
      for (int i = 0; i < cells.length; i++)
        if (i % 3 != 0)
          cells[i] =
              new WorldCell(
                  new BlockState(STONE, Map.of("index", Integer.toString(i))),
                  null,
                  null,
                  null,
                  null);
      var facts = new FactWindow(new Host.SessionId(), window, cells, null, Map.of(), null, null);
      for (int i = 0; i < window.size(); i++) {
        var position = window.position(i);
        assertEquals(i, window.index(position));
        assertEquals(java.util.Optional.ofNullable(cells[i]), facts.world(position));
      }
      assertEquals(-1, window.index(new Position(ResourceId.parse("test:other"), x, y, z)));
      assertTrue(facts.world(new Position(DIM, x, y - 1, z)).isEmpty());
      assertThrows(IndexOutOfBoundsException.class, () -> window.position(-1));
      assertThrows(IndexOutOfBoundsException.class, () -> window.position(window.size()));
    }
  }

  @Test
  void captureFreezesWorldSelectionAndInventoryInputs() {
    var session = new Host.SessionId();
    var window = new CellWindow(new Bounds(DIM, 0, 64, 0, 0, 64, 0));
    var cells = new WorldCell[] {CELL};
    var bounds = new ArrayList<Bounds>();
    bounds.add(window.bounds());
    var selections = new HashMap<String, java.util.List<Bounds>>();
    selections.put("build", bounds);
    var inventory = new HashSet<ResourceId>();
    inventory.add(STONE);
    var target = new TargetSlice(session, window, java.util.List.of(new TargetCell.Clear()));
    var player = window.position(0);
    var facts = new FactWindow(session, window, cells, target, selections, player, inventory);
    cells[0] = null;
    bounds.clear();
    selections.clear();
    inventory.clear();
    assertEquals(CELL, facts.world(player).orElseThrow());
    assertEquals(Truth.TRUE, facts.selection("build", player));
    assertEquals(Truth.FALSE, facts.selection("build", new Position(DIM, 1, 64, 0)));
    assertEquals(Truth.UNKNOWN, facts.selection("missing", player));
    assertEquals(java.util.Set.of(STONE), facts.inventory().orElseThrow());
    assertThrows(
        UnsupportedOperationException.class, () -> facts.inventory().orElseThrow().clear());
    assertEquals(player, facts.player().orElseThrow());
    assertInstanceOf(TargetCell.Clear.class, facts.target(player));
    assertInstanceOf(TargetCell.Unknown.class, facts.target(new Position(DIM, 1, 64, 0)));
  }

  @Test
  void missingColumnsStayUnavailableAndSlicesMustMatchTheirSessionAndWindow() {
    var session = new Host.SessionId();
    var window = new CellWindow(new Bounds(DIM, 0, 0, 0, 0, 0, 0));
    var facts = new FactWindow(session, window, null, null, Map.of(), null, null);
    assertTrue(facts.world(window.position(0)).isEmpty());
    assertTrue(facts.player().isEmpty());
    assertTrue(facts.inventory().isEmpty());
    assertInstanceOf(TargetCell.Unknown.class, facts.target(window.position(0)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new FactWindow(session, window, new WorldCell[2], null, Map.of(), null, null));
    var wrongSession =
        new TargetSlice(new Host.SessionId(), window, java.util.List.of(new TargetCell.Clear()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new FactWindow(session, window, null, wrongSession, Map.of(), null, null));
    var wrongWindow =
        new TargetSlice(
            session, new Bounds(DIM, 1, 0, 0, 1, 0, 0), java.util.List.of(new TargetCell.Clear()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new FactWindow(session, window, null, wrongWindow, Map.of(), null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CellWindow(new Bounds(DIM, Integer.MIN_VALUE, 0, 0, Integer.MAX_VALUE, 0, 0)));
  }
}

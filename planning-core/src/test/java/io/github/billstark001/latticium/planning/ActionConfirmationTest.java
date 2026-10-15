package io.github.billstark001.latticium.planning;

import static org.junit.jupiter.api.Assertions.*;

import io.github.billstark001.latticium.dsl.Model.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ActionConfirmationTest {
  private static final BlockState STONE = state("stone");
  private static final BlockState AIR = state("air");
  private static final BlockState DIRT = state("dirt");
  private static final ActionConfirmation BREAK = new ActionConfirmation(STONE, AIR, 10, 7);

  private static BlockState state(String id) {
    return new BlockState(ResourceId.parse(id), Map.of());
  }

  private static ServerUpdates.Update update(long revision, BlockState state) {
    return new ServerUpdates.Update(revision, state);
  }

  @Test
  void localPredictionAndUnrelatedUpdatesCannotConfirm() {
    assertEquals(Host.Observation.STILL_PENDING, BREAK.observe(AIR, null, 11, 100));
    assertEquals(Host.Observation.STILL_PENDING, BREAK.observe(AIR, update(7, AIR), 11, 100));
    assertEquals(Host.Observation.STILL_PENDING, BREAK.observe(AIR, update(6, AIR), 11, 100));
    assertEquals(Host.Observation.STILL_PENDING, BREAK.observe(STONE, update(8, STONE), 11, 100));
    assertEquals(Host.Observation.CONFIRMED, BREAK.observe(AIR, update(8, AIR), 11, 100));
  }

  @Test
  void authoritativeReplacementStopsAnInFlightBreak() {
    assertEquals(Host.Observation.CONTRADICTED, BREAK.observe(DIRT, update(8, DIRT), 11, 100));
    assertEquals(Host.Observation.CONTRADICTED, BREAK.observe(null, update(8, DIRT), 11, 100));
    assertEquals(Host.Observation.STILL_PENDING, BREAK.observe(DIRT, update(7, DIRT), 11, 100));
  }

  @Test
  void packetPayloadMustAgreeWithThePredictionBeforeConfirmation() {
    assertEquals(Host.Observation.CONTRADICTED, BREAK.observe(AIR, update(8, DIRT), 11, 100));
    assertEquals(Host.Observation.STILL_PENDING, BREAK.observe(AIR, update(8, STONE), 11, 100));
    assertEquals(Host.Observation.STILL_PENDING, BREAK.observe(STONE, update(8, AIR), 11, 100));
  }

  @Test
  void unavailableChunkTimesOutWithoutInventingAir() {
    assertEquals(Host.Observation.STILL_PENDING, BREAK.observe(null, update(8, AIR), 110, 100));
    assertEquals(Host.Observation.TIMED_OUT, BREAK.observe(null, update(8, AIR), 111, 100));
    assertEquals(Host.Observation.TIMED_OUT, BREAK.observe(STONE, update(8, STONE), 111, 100));
    assertEquals(Host.Observation.CONFIRMED, BREAK.observe(AIR, update(8, AIR), 111, 100));
  }
}

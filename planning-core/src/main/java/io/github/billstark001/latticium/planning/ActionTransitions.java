package io.github.billstark001.latticium.planning;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Ordered, executable receipt transition table; confirmation evidence stays in the observer. */
public final class ActionTransitions {
  public enum Condition {
    CANCEL_REQUESTED,
    PARTIAL_FINISHED,
    FINISHED,
    PAUSE_REQUESTED,
    QUALIFIED_REMAINDER,
    ALWAYS
  }

  public record Row(
      Host.Observation observation,
      Condition condition,
      JobController.Status next,
      boolean advance) {}

  private static final List<Row> ROWS = load();

  private ActionTransitions() {}

  public static List<Row> rows() {
    return ROWS;
  }

  public static Row resolve(
      Host.Observation observation,
      boolean cancelled,
      boolean finished,
      boolean partial,
      boolean paused) {
    return resolve(observation, cancelled, finished, partial, paused, false);
  }

  public static Row resolve(
      Host.Observation observation,
      boolean cancelled,
      boolean finished,
      boolean partial,
      boolean paused,
      boolean qualifiedRemainder) {
    for (var row : ROWS) {
      if (row.observation() != observation) continue;
      boolean matches =
          switch (row.condition()) {
            case CANCEL_REQUESTED -> cancelled;
            case PARTIAL_FINISHED -> finished && partial;
            case FINISHED -> finished;
            case PAUSE_REQUESTED -> paused;
            case QUALIFIED_REMAINDER -> qualifiedRemainder;
            case ALWAYS -> true;
          };
      if (matches) return row;
    }
    throw new IllegalStateException("Missing action transition: " + observation);
  }

  private static List<Row> load() {
    try (var input =
        ActionTransitions.class.getResourceAsStream("/latticium/action-transitions.json")) {
      if (input == null) throw new IllegalStateException("Missing action transition catalog");
      var root = StrictJson.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
      if (!root.isArray()) throw new IllegalStateException("Invalid action transitions");
      var rows = new ArrayList<Row>();
      for (var row : root) {
        if (!row.isObject()
            || row.size() != 4
            || !row.has("observation")
            || !row.has("when")
            || !row.has("next")
            || !row.has("advance")
            || !row.get("advance").isBoolean())
          throw new IllegalStateException("Invalid action transition row");
        var entry =
            new Row(
                Host.Observation.valueOf(row.get("observation").asText()),
                Condition.valueOf(row.get("when").asText()),
                JobController.Status.valueOf(row.get("next").asText()),
                row.get("advance").booleanValue());
        if (entry.advance() != (entry.observation() == Host.Observation.CONFIRMED))
          throw new IllegalStateException("Only confirmed actions advance progress");
        rows.add(entry);
      }
      for (var observation : Host.Observation.values()) {
        var matching = rows.stream().filter(row -> row.observation() == observation).toList();
        if (matching.isEmpty()
            || matching.getLast().condition() != Condition.ALWAYS
            || matching.stream().map(Row::condition).distinct().count() != matching.size())
          throw new IllegalStateException("Missing or ambiguous fallback: " + observation);
      }
      return List.copyOf(rows);
    } catch (IOException | IllegalArgumentException error) {
      throw new IllegalStateException("Invalid action transition catalog", error);
    }
  }
}

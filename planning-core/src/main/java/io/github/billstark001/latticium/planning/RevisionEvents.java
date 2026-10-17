package io.github.billstark001.latticium.planning;

import io.github.billstark001.latticium.planning.Revisions.Domain;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Executable event-to-domain contract, shared by adapters and mutation-matrix tests. */
public final class RevisionEvents {
  public enum Scope {
    GLOBAL,
    CELL,
    SECTION,
    NAMED,
    SLOT
  }

  public enum Effect {
    END_SESSION,
    REBIND,
    REPLAN,
    RECHECK_INTENT
  }

  public record Event(String id, Scope scope, Set<Domain> domains, Effect effect) {
    public Event {
      domains = Set.copyOf(domains);
    }
  }

  private static final Map<String, Event> EVENTS = load();

  private RevisionEvents() {}

  public static List<Event> all() {
    return List.copyOf(EVENTS.values());
  }

  public static Event get(String id) {
    var event = EVENTS.get(id);
    if (event == null) throw new IllegalArgumentException("Unknown revision event: " + id);
    return event;
  }

  private static Map<String, Event> load() {
    try (var input = RevisionEvents.class.getResourceAsStream("/latticium/revision-events.json")) {
      if (input == null) throw new IllegalStateException("Missing revision event catalog");
      var root = StrictJson.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
      if (!root.isArray() || root.isEmpty())
        throw new IllegalStateException("Invalid revision catalog");
      var result = new LinkedHashMap<String, Event>();
      for (var row : root) {
        if (!row.isObject()
            || row.size() != 4
            || !row.has("id")
            || !row.has("scope")
            || !row.has("domains")
            || !row.has("effect")
            || !row.get("domains").isArray())
          throw new IllegalStateException("Invalid revision event row");
        var domains = new ArrayList<Domain>();
        for (var value : row.get("domains")) domains.add(Domain.valueOf(value.asText()));
        if (domains.isEmpty() || Set.copyOf(domains).size() != domains.size())
          throw new IllegalStateException("Empty or duplicate revision domains");
        var event =
            new Event(
                row.get("id").asText(),
                Scope.valueOf(row.get("scope").asText()),
                Set.copyOf(domains),
                Effect.valueOf(row.get("effect").asText()));
        if (result.putIfAbsent(event.id(), event) != null)
          throw new IllegalStateException("Duplicate revision event: " + event.id());
      }
      return java.util.Collections.unmodifiableMap(result);
    } catch (IOException | IllegalArgumentException error) {
      throw new IllegalStateException("Invalid revision event catalog", error);
    }
  }
}

package io.github.billstark001.latticium.mc;

import io.github.billstark001.latticium.dsl.Model.Position;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Bounded diagnostic rows with exact counts and one deterministic sample per engine reason. */
public record ClientDiagnostics(int totalPositions, int totalGroups, List<Group> groups) {
  public ClientDiagnostics {
    groups = List.copyOf(groups);
  }

  public record Group(Kind kind, String detail, int count, Position example) {}

  public enum Kind {
    REACH,
    MATERIAL,
    DATA,
    BUDGET,
    POLICY,
    CONFIRMATION,
    CHANGED,
    UNSUPPORTED,
    OTHER;

    static Kind classify(String reason) {
      String text = reason.toLowerCase(Locale.ROOT);
      if (text.contains("outside interaction reach")) return REACH;
      if (text.contains("budget") || text.contains("remaining actions")) return BUDGET;
      if (text.contains("breaking denied")) return POLICY;
      if (text.equals("timed_out") || text.equals("contradicted")) return CONFIRMATION;
      if (text.contains("changed") || text.contains("session mismatch")) return CHANGED;
      if (text.contains("unavailable") || text.contains("unknown") || text.contains("unloaded"))
        return DATA;
      if (text.contains("hotbar") || text.contains("available item")) return MATERIAL;
      if (text.contains("no predictable")
          || text.contains("no reachable")
          || text.contains("no allowed")
          || text.contains("no native executor")
          || text.contains("effects")) return UNSUPPORTED;
      return OTHER;
    }
  }

  public static ClientDiagnostics capture(Map<Position, String> blocked, int limit) {
    if (limit <= 0) throw new IllegalArgumentException("Positive diagnostic limit required");
    var grouped = new HashMap<String, Group>();
    blocked.forEach(
        (position, reason) ->
            grouped.compute(
                reason,
                (key, previous) -> {
                  if (previous == null)
                    return new Group(Kind.classify(reason), reason, 1, position);
                  var first =
                      compare(position, previous.example()) < 0 ? position : previous.example();
                  return new Group(previous.kind(), reason, previous.count() + 1, first);
                }));
    var rows =
        grouped.values().stream()
            .sorted(Comparator.comparingInt(Group::count).reversed().thenComparing(Group::detail))
            .limit(limit)
            .toList();
    return new ClientDiagnostics(blocked.size(), grouped.size(), rows);
  }

  private static int compare(Position a, Position b) {
    int dimension = a.dimension().toString().compareTo(b.dimension().toString());
    if (dimension != 0) return dimension;
    int x = Integer.compare(a.x(), b.x());
    if (x != 0) return x;
    int y = Integer.compare(a.y(), b.y());
    return y != 0 ? y : Integer.compare(a.z(), b.z());
  }
}

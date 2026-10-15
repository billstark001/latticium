package io.github.billstark001.latticium.planning;

import io.github.billstark001.latticium.dsl.Model.BlockState;
import io.github.billstark001.latticium.dsl.Model.Position;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Bounded, client-thread record of authoritative packet states, separate from local prediction. */
public final class ServerUpdates {
  public record Update(long revision, BlockState state) {
    public Update {
      Objects.requireNonNull(state, "state");
    }
  }

  private final int capacity;
  private final Map<Position, Update> updates = new LinkedHashMap<>(16, 0.75f, true);
  private final Map<Position, java.util.ArrayDeque<Update>> history = new LinkedHashMap<>();
  private long sequence;

  public ServerUpdates(int capacity) {
    if (capacity <= 0) throw new IllegalArgumentException("Positive update capacity required");
    this.capacity = capacity;
  }

  /** Records the packet payload after vanilla applies it, even if prediction hides that state. */
  public void record(Position position, BlockState state) {
    Objects.requireNonNull(position, "position");
    Objects.requireNonNull(state, "state");
    var update = new Update(++sequence, state);
    updates.put(position, update);
    var recent = history.computeIfAbsent(position, ignored -> new java.util.ArrayDeque<>());
    recent.addLast(update);
    if (recent.size() > 8) recent.removeFirst();
    if (updates.size() > capacity) {
      var oldest = updates.keySet().iterator().next();
      updates.remove(oldest);
      history.remove(oldest);
    }
  }

  /** Null means forgotten or never observed; it cannot be used as server confirmation. */
  public Update get(Position position) {
    return updates.get(position);
  }

  public long revision(Position position) {
    var update = get(position);
    return update == null ? 0 : update.revision();
  }

  /** Explicit transient-operation mode still needs a retained authoritative expected payload. */
  public Update expectedAfter(Position position, BlockState expected, long revision) {
    var recent = history.get(position);
    if (recent == null) return null;
    var iterator = recent.descendingIterator();
    while (iterator.hasNext()) {
      var update = iterator.next();
      if (update.revision() <= revision) return null;
      if (update.state().equals(expected)) return update;
    }
    return null;
  }

  /** Clears the history at a world/session boundary; old receipts must already be discarded. */
  public void clear() {
    updates.clear();
    history.clear();
    sequence = 0;
  }
}

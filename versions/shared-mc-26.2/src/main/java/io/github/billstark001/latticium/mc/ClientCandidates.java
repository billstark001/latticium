package io.github.billstark001.latticium.mc;

import io.github.billstark001.latticium.dsl.Model.*;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/** One insertion-ordered owner for pending candidates, frozen targets and retry state. */
final class ClientCandidates {
  private static final class Candidate {
    private TargetCell target;
    private BlockState expected;
    private long retryAfter;
  }

  private final Map<Position, Candidate> pending = new LinkedHashMap<>();

  /** A rescan can reconsider a target until a confirmed partial action owns it. */
  void claim(Position position) {
    var candidate = pending.computeIfAbsent(position, ignored -> new Candidate());
    if (candidate.expected == null) candidate.target = null;
  }

  Position next(Comparator<Position> order, long tick) {
    Position next = null;
    for (var entry : pending.entrySet())
      if (entry.getValue().retryAfter <= tick
          && (next == null || order.compare(entry.getKey(), next) < 0)) next = entry.getKey();
    return next;
  }

  BlockState expected(Position position) {
    var candidate = pending.get(position);
    return candidate == null ? null : candidate.expected;
  }

  void confirmed(Position position, BlockState expected) {
    pending.get(position).expected = java.util.Objects.requireNonNull(expected);
  }

  TargetCell target(Position position) {
    return pending.get(position).target;
  }

  void freeze(Position position, TargetCell target) {
    pending.get(position).target = java.util.Objects.requireNonNull(target);
  }

  void retry(Position position, long tick) {
    pending.get(position).retryAfter = tick;
  }

  void remove(Position position) {
    pending.remove(position);
  }

  /** Explicit refresh resets retries while preserving a confirmed partial action's target. */
  void refresh() {
    for (var candidate : pending.values()) {
      if (candidate.expected == null) candidate.target = null;
      candidate.retryAfter = 0;
    }
  }

  int size() {
    return pending.size();
  }

  boolean isEmpty() {
    return pending.isEmpty();
  }
}

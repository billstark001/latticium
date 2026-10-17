package io.github.billstark001.latticium.mc;

import io.github.billstark001.latticium.dsl.Model.*;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/** One insertion-ordered owner for pending candidates, frozen targets and retry state. */
final class ClientCandidates {
  record Admission(boolean admitted, Position displaced) {}

  private static final class Candidate {
    private TargetCell target;
    private BlockState expected;
    private long retryAfter;
    private long lastAttempt = Long.MIN_VALUE;
    private io.github.billstark001.latticium.planning.Revisions.Stamp inputs;
  }

  private final Map<Position, Candidate> pending = new LinkedHashMap<>();
  private final int capacity;
  private final boolean protectPinned;
  private final Map<
          io.github.billstark001.latticium.planning.SectionScanner.SectionKey, java.util.BitSet>
      satisfied = new LinkedHashMap<>();
  private java.util.PriorityQueue<Position> admissionQueue;
  private Comparator<Position> admissionOrder;
  private long admissionTick;

  ClientCandidates() {
    this(Integer.MAX_VALUE, false);
  }

  ClientCandidates(int capacity, boolean protectPinned) {
    if (capacity < 1) throw new IllegalArgumentException("Positive candidate capacity required");
    this.capacity = capacity;
    this.protectPinned = protectPinned;
  }

  /** Nearest-work admission with deferred-work rotation and explicit rediscovery responsibility. */
  Admission admit(Position position, Comparator<Position> order, long tick) {
    if (satisfied(position)) return new Admission(true, null);
    if (pending.containsKey(position) || pending.size() < capacity) {
      claim(position);
      return new Admission(true, null);
    }
    if (admissionQueue == null || admissionOrder != order || admissionTick != tick) {
      admissionOrder = order;
      admissionTick = tick;
      admissionQueue =
          new java.util.PriorityQueue<>(
              (a, b) -> {
                boolean ad = pending.get(a).retryAfter > tick,
                    bd = pending.get(b).retryAfter > tick;
                return ad != bd ? ad ? -1 : 1 : order.compare(b, a);
              });
      pending.forEach(
          (pos, candidate) -> {
            if (candidate.expected == null && !(protectPinned && candidate.target != null))
              admissionQueue.add(pos);
          });
    }
    Position replace = admissionQueue.peek();
    if (replace == null
        || pending.get(replace).retryAfter <= tick && order.compare(position, replace) >= 0)
      return new Admission(false, null);
    admissionQueue.remove();
    pending.remove(replace);
    claim(position);
    admissionQueue.add(position);
    return new Admission(true, replace);
  }

  /** A rescan can reconsider a target until a confirmed partial action owns it. */
  void claim(Position position) {
    if (!pending.containsKey(position) && pending.size() >= capacity) return;
    var candidate = pending.computeIfAbsent(position, ignored -> new Candidate());
    if (candidate.expected == null && !protectPinned) candidate.target = null;
  }

  Position next(Comparator<Position> order, long tick) {
    Position next = null;
    for (var entry : pending.entrySet())
      if (entry.getValue().retryAfter <= tick
          && (next == null
              || entry.getValue().lastAttempt < pending.get(next).lastAttempt
              || entry.getValue().lastAttempt == pending.get(next).lastAttempt
                  && order.compare(entry.getKey(), next) < 0)) next = entry.getKey();
    if (next != null) pending.get(next).lastAttempt = tick;
    return next;
  }

  BlockState expected(Position position) {
    var candidate = pending.get(position);
    return candidate == null ? null : candidate.expected;
  }

  void inputs(Position position, io.github.billstark001.latticium.planning.Revisions.Stamp stamp) {
    var candidate = pending.get(position);
    if (candidate != null) candidate.inputs = stamp;
  }

  io.github.billstark001.latticium.planning.Revisions.Stamp inputs(Position position) {
    var candidate = pending.get(position);
    return candidate == null ? null : candidate.inputs;
  }

  void confirmed(Position position, BlockState expected) {
    pending.get(position).expected = java.util.Objects.requireNonNull(expected);
    admissionQueue = null;
  }

  void unconfirm(Position position) {
    pending.get(position).expected = null;
    admissionQueue = null;
  }

  void reconsider(Position position) {
    var candidate = pending.get(position);
    if (candidate != null && candidate.expected == null && !protectPinned) candidate.target = null;
  }

  TargetCell target(Position position) {
    return pending.get(position).target;
  }

  void freeze(Position position, TargetCell target) {
    pending.get(position).target = java.util.Objects.requireNonNull(target);
    admissionQueue = null;
  }

  void retry(Position position, long tick) {
    pending.get(position).retryAfter = tick;
    admissionQueue = null;
  }

  void remove(Position position) {
    pending.remove(position);
    admissionQueue = null;
  }

  boolean satisfied(Position position) {
    var mask = satisfied.get(ClientRevisions.section(position));
    return mask != null && mask.get(index(position));
  }

  void satisfy(Position position) {
    var key = ClientRevisions.section(position);
    if (!satisfied.containsKey(key) && satisfied.size() >= 65_536)
      throw new IllegalStateException("Finite progress section limit exceeded");
    satisfied.computeIfAbsent(key, ignored -> new java.util.BitSet(4096)).set(index(position));
  }

  void changed(Position position) {
    var key = ClientRevisions.section(position);
    var mask = satisfied.get(key);
    if (mask != null) {
      mask.clear(index(position));
      if (mask.isEmpty()) satisfied.remove(key);
    }
  }

  void changedAround(Position position, int radius) {
    for (long x = ((long) position.x() - radius) >> 4;
        x <= ((long) position.x() + radius) >> 4;
        x++)
      for (long y = ((long) position.y() - radius) >> 4;
          y <= ((long) position.y() + radius) >> 4;
          y++)
        for (long z = ((long) position.z() - radius) >> 4;
            z <= ((long) position.z() + radius) >> 4;
            z++)
          satisfied.remove(
              new io.github.billstark001.latticium.planning.SectionScanner.SectionKey(
                  position.dimension(), (int) x, (int) y, (int) z));
  }

  private static int index(Position p) {
    return (p.x() & 15) | (p.z() & 15) << 4 | (p.y() & 15) << 8;
  }

  /** Explicit refresh resets retries while preserving a confirmed partial action's target. */
  void refresh() {
    satisfied.clear();
    admissionQueue = null;
    for (var candidate : pending.values()) {
      if (candidate.expected == null) candidate.target = null;
      candidate.retryAfter = 0;
      candidate.lastAttempt = Long.MIN_VALUE;
    }
  }

  int size() {
    return pending.size();
  }

  boolean isEmpty() {
    return pending.isEmpty();
  }
}

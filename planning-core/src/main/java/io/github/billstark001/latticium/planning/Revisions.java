package io.github.billstark001.latticium.planning;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Bounded, dependency-qualified revisions. Missing/evicted evidence never means unchanged. */
public final class Revisions {
  private Revisions() {}

  public enum Domain {
    REGISTRY,
    WORLD,
    STATE,
    FLUID,
    SOLID,
    LIGHT,
    BIOME,
    RESIDENCY,
    TARGET,
    SELECTION,
    INVENTORY,
    PLAYER,
    RULES
  }

  public enum Reliability {
    VERSIONED,
    SHORT_LIVED
  }

  /** The immutable resource is a position, section, selection name, slot or source identifier. */
  public record Key(Domain domain, Object resource) {
    public Key {
      Objects.requireNonNull(domain);
      Objects.requireNonNull(resource);
    }
  }

  public record Version(long sequence, Reliability reliability) {
    public Version {
      Objects.requireNonNull(reliability);
    }
  }

  public record SourceCell(
      Host.SessionId session, io.github.billstark001.latticium.dsl.Model.Position position) {
    public SourceCell {
      Objects.requireNonNull(session);
      Objects.requireNonNull(position);
    }
  }

  /** Captured read set, not a copy of every revision in the host. */
  public record Stamp(
      Host.SessionId session,
      long tick,
      Map<Key, Version> reads,
      long fence,
      java.util.List<SpatialReads> spatial) {
    public Stamp(Host.SessionId session, long tick, Map<Key, Version> reads) {
      this(session, tick, reads, 0, java.util.List.of());
    }

    public Stamp {
      Objects.requireNonNull(session);
      reads = Map.copyOf(reads);
      spatial = java.util.List.copyOf(spatial);
    }

    public boolean reusable() {
      return (!reads.isEmpty() || !spatial.isEmpty())
          && spatial.stream().allMatch(r -> r.reliability() == Reliability.VERSIONED)
          && reads.values().stream()
              .allMatch(v -> v.sequence() > 0 && v.reliability() == Reliability.VERSIONED);
    }

    public Stamp select(java.util.function.Predicate<Key> include) {
      var selected = new LinkedHashMap<Key, Version>();
      reads.forEach(
          (key, version) -> {
            if (include.test(key)) selected.put(key, version);
          });
      return new Stamp(session, tick, selected);
    }

    public Stamp withSpatial(SpatialReads region) {
      var regions = new java.util.ArrayList<>(spatial);
      regions.add(region);
      return new Stamp(session, tick, reads, fence, regions);
    }

    public Stamp at(io.github.billstark001.latticium.dsl.Model.Position position) {
      return new Stamp(
          session, tick, reads, fence, spatial.stream().map(r -> r.at(position)).toList());
    }
  }

  private record Entry(Object value, Version version) {}

  /** Owned by one adapter thread. Sampling requires immutable values with meaningful equality. */
  public static final class Ledger {
    private final int capacity;
    private final Map<Key, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private long sequence;

    private record Change(long sequence, Key key, boolean event) {}

    private final java.util.ArrayDeque<Change> changes = new java.util.ArrayDeque<>();
    private long forgottenThrough;

    public Ledger(int capacity) {
      if (capacity < 1) throw new IllegalArgumentException("Positive revision capacity required");
      this.capacity = capacity;
    }

    public long sample(Key key, Object value, Reliability reliability) {
      Objects.requireNonNull(key);
      Objects.requireNonNull(reliability);
      if (value == null) {
        forget(key);
        return -1;
      }
      var previous = entries.get(key);
      if (previous != null
          && Objects.equals(previous.value(), value)
          && previous.version().reliability() == reliability) return previous.version().sequence();
      long next = nextSequence();
      if (previous != null) recordChange(next, key, false);
      entries.put(key, new Entry(value, new Version(next, reliability)));
      trim();
      return next;
    }

    /** A source event advances evidence even when the value has returned to its prior state. */
    public void changed(Key key) {
      var previous = entries.get(key);
      long next = nextSequence();
      recordChange(next, key, true);
      if (previous != null)
        entries.put(
            key, new Entry(previous.value(), new Version(next, previous.version().reliability())));
    }

    /** Data-driven event expansion; resource identity is supplied by the native event hook. */
    public void event(String event, Object resource) {
      for (var domain : RevisionEvents.get(event).domains()) changed(new Key(domain, resource));
    }

    public Stamp capture(Host.SessionId session, long tick, Collection<Key> keys) {
      var reads = new LinkedHashMap<Key, Version>();
      for (var key : keys) {
        var entry = entries.get(key);
        reads.put(key, entry == null ? new Version(-1, Reliability.SHORT_LIVED) : entry.version());
      }
      return new Stamp(session, tick, reads, sequence, java.util.List.of());
    }

    public boolean valid(Stamp stamp, Host.SessionId session, long tick) {
      if (!stamp.session().equals(session) || stamp.reads().isEmpty() && stamp.spatial().isEmpty())
        return false;
      if (!stamp.spatial().isEmpty()) {
        if (stamp.fence() < forgottenThrough) return false;
        for (var region : stamp.spatial()) {
          if (region.reliability() == Reliability.SHORT_LIVED && stamp.tick() != tick) return false;
          var recent = changes.descendingIterator();
          while (recent.hasNext()) {
            var change = recent.next();
            if (change.sequence() <= stamp.fence()) break;
            if ((!(change.key().resource() instanceof SourceCell source)
                    || source.session().equals(session))
                && region.includes(change.key())) return false;
          }
        }
      }
      for (var read : stamp.reads().entrySet()) {
        var expected = read.getValue();
        var actual = entries.get(read.getKey());
        if (expected.sequence() < 1 || actual == null || !expected.equals(actual.version()))
          return false;
        if (expected.reliability() == Reliability.SHORT_LIVED && stamp.tick() != tick) return false;
      }
      return true;
    }

    public void forget(Key key) {
      entries.remove(key);
    }

    public void clear() {
      entries.clear();
      changes.clear();
      forgottenThrough = nextSequence();
    }

    public int size() {
      return entries.size();
    }

    public long fence() {
      return sequence;
    }

    public Version version(Key key) {
      var entry = entries.get(key);
      return entry == null ? null : entry.version();
    }

    /** Source events during capture differ from merely sampling a previously changed value. */
    public boolean eventsUnchanged(long fence, Host.SessionId session, SpatialReads region) {
      if (fence < forgottenThrough) return false;
      var recent = changes.descendingIterator();
      while (recent.hasNext()) {
        var change = recent.next();
        if (change.sequence() <= fence) break;
        if (change.event()
            && (!(change.key().resource() instanceof SourceCell source)
                || source.session().equals(session))
            && region.includes(change.key())) return false;
      }
      return true;
    }

    private long nextSequence() {
      if (sequence == Long.MAX_VALUE)
        throw new IllegalStateException("Revision sequence exhausted");
      return ++sequence;
    }

    private void trim() {
      while (entries.size() > capacity) entries.remove(entries.keySet().iterator().next());
    }

    private void recordChange(long revision, Key key, boolean event) {
      changes.addLast(new Change(revision, key, event));
      while (changes.size() > capacity) forgottenThrough = changes.removeFirst().sequence();
    }
  }
}

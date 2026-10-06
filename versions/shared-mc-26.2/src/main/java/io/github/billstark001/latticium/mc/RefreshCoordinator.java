package io.github.billstark001.latticium.mc;

import io.github.billstark001.latticium.dsl.Model.Position;
import io.github.billstark001.latticium.dsl.Parser;
import io.github.billstark001.latticium.planning.FiniteScope;
import io.github.billstark001.latticium.planning.Host;
import io.github.billstark001.latticium.planning.Profile;
import io.github.billstark001.latticium.planning.RefreshReads;
import io.github.billstark001.latticium.planning.SectionScanner;
import io.github.billstark001.latticium.planning.SectionScanner.Bounds;
import io.github.billstark001.latticium.planning.SectionScanner.SectionGroup;
import io.github.billstark001.latticium.planning.SectionScanner.SectionKey;
import io.github.billstark001.latticium.planning.SelectionBounds;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Event-driven refresh queue for one finite job. All methods run on the client thread. */
final class RefreshCoordinator {
  private static final int MAX_SECTIONS = 65_536;
  private static final int MAX_DIRTY_POSITIONS = 8192;
  private static final int EXACT_SPHERE_RADIUS = 6;
  private static final long RETRY_TICKS = 20;
  private static final long PERIODIC_TICKS = 200;
  private static final SectionScanner SCANNER = new SectionScanner();
  private static final RefreshReads.Offset ORIGIN = new RefreshReads.Offset(0, 0, 0);

  private final Profile.Bound profile;
  private final Host.SessionId session;
  private final Map<String, List<Bounds>> selections;
  private final RefreshReads.Reads scopeReads;
  private final RefreshReads.Reads selectReads;
  private final Set<RefreshReads.Offset> worldOffsets;
  private final boolean broadWorld;
  private final boolean periodic;
  private final int readRadius;
  private List<SectionGroup> sections;
  private Map<SectionKey, SectionGroup> sectionIndex;
  private final Set<SectionGroup> deferredSections = new LinkedHashSet<>();
  private final Set<SectionKey> dirtySections = new LinkedHashSet<>();
  private final Set<Position> dirtyPositions = new LinkedHashSet<>();
  private final Map<Position, Long> deferredPositions = new HashMap<>();
  private Position lastPlayer;
  private int nextSection;
  private int refreshSection = -1;
  private int unsupportedSections;

  RefreshCoordinator(
      Profile.Bound profile,
      Host.SessionId session,
      Map<String, List<Bounds>> selections,
      Position player) {
    this.profile = profile;
    this.session = session;
    this.selections = SelectionBounds.copy(selections);
    scopeReads = RefreshReads.in(Parser.expression(profile.profile().scope()), profile.scope());
    selectReads =
        RefreshReads.in(Parser.expression(profile.profile().select().where()), profile.select());
    var offsets = new LinkedHashSet<RefreshReads.Offset>();
    offsets.add(ORIGIN); // The action target also depends on the current block.
    offsets.addAll(scopeReads.worldOffsets());
    offsets.addAll(selectReads.worldOffsets());
    worldOffsets = Set.copyOf(offsets);
    broadWorld = scopeReads.broadWorld() || selectReads.broadWorld();
    periodic =
        scopeReads.periodic()
            || selectReads.periodic()
            || profile.profile().target() instanceof Profile.Source;
    readRadius = Math.max(profile.scope().radius(), profile.select().radius());
    lastPlayer = player;
    rebuild(player);
  }

  boolean continuous() {
    return profile.profile().policy().refreshMode() == Profile.Policy.RefreshMode.CONTINUOUS;
  }

  int readRadius() {
    return readRadius;
  }

  List<SectionGroup> sections() {
    return sections;
  }

  int scannedSections() {
    return nextSection;
  }

  int sectionCount() {
    return sections.size();
  }

  int deferredCount() {
    return deferredSections.size() + deferredPositions.size();
  }

  int dirtyCount() {
    return dirtySections.size() + dirtyPositions.size();
  }

  int refreshRemaining() {
    return refreshSection < 0 ? 0 : sections.size() - refreshSection;
  }

  int unsupportedCount() {
    return unsupportedSections;
  }

  boolean hasScanWork() {
    return nextSection < sections.size()
        || refreshSection >= 0
        || !dirtySections.isEmpty()
        || !dirtyPositions.isEmpty()
        || !deferredSections.isEmpty()
        || !deferredPositions.isEmpty();
  }

  void requestFullRefresh(Position player) {
    if (scopeReads.usesPlayer() && !player.equals(lastPlayer)) rebuild(player);
    lastPlayer = player;
    nextSection = sections.size();
    refreshSection = 0;
    deferredSections.clear();
    deferredPositions.clear();
    dirtySections.clear();
    dirtyPositions.clear();
  }

  void blockChanged(Position changed) {
    if (!continuous() || !changed.dimension().equals(lastPlayer.dimension())) return;
    if (broadWorld) {
      queueSectionsAround(changed, readRadius);
      return;
    }
    for (var offset : worldOffsets)
      try {
        var candidate = changed.offset(-offset.x(), -offset.y(), -offset.z());
        queuePosition(candidate);
      } catch (ArithmeticException ignored) {
        // No position in this dimension can satisfy an overflowing offset.
      }
  }

  void playerMoved(Position player) {
    if (!continuous() || player.equals(lastPlayer)) return;
    var previous = lastPlayer;
    lastPlayer = player;
    if (!scopeReads.usesPlayer() && !selectReads.usesPlayer()) return;
    if (scopeReads.usesPlayer()) {
      var oldIndex = sectionIndex;
      var oldDirty = Set.copyOf(dirtySections);
      var oldDeferred = Set.copyOf(deferredSections);
      var unscanned =
          sections.subList(nextSection, sections.size()).stream().map(SectionGroup::key).toList();
      var unrefreshed =
          refreshSection < 0
              ? List.<SectionKey>of()
              : sections.subList(refreshSection, sections.size()).stream()
                  .map(SectionGroup::key)
                  .toList();
      rebuild(player);
      nextSection = sections.size();
      for (var key : oldDirty) if (sectionIndex.containsKey(key)) dirtySections.add(key);
      for (var section : oldDeferred)
        if (sectionIndex.containsKey(section.key())) dirtySections.add(section.key());
      for (var key : unscanned) if (sectionIndex.containsKey(key)) dirtySections.add(key);
      for (var key : unrefreshed) if (sectionIndex.containsKey(key)) dirtySections.add(key);
      for (var key : sectionIndex.keySet()) if (!oldIndex.containsKey(key)) dirtySections.add(key);
      queuePlayerChanges(previous, player, scopeReads.playerSpheres());
      queuePlayerChanges(previous, player, selectReads.playerSpheres());
      if (scopeReads.broadPlayer() || selectReads.broadPlayer())
        dirtySections.addAll(sectionIndex.keySet());
      return;
    }
    queuePlayerChanges(previous, player, selectReads.playerSpheres());
    if (selectReads.broadPlayer()) dirtySections.addAll(sectionIndex.keySet());
  }

  void periodicRefresh(long ticks) {
    if (continuous()
        && periodic
        && ticks % PERIODIC_TICKS == 0
        && nextSection == sections.size()
        && refreshSection < 0) refreshSection = 0;
  }

  List<Position> nextDirtyPositions(long ticks, int limit) {
    if (ticks % RETRY_TICKS == 0) {
      var retry = deferredPositions.entrySet().iterator();
      while (retry.hasNext()) {
        var entry = retry.next();
        if (entry.getValue() <= ticks) {
          dirtyPositions.add(entry.getKey());
          retry.remove();
        }
      }
    }
    var result = new ArrayList<Position>(limit);
    var iterator = dirtyPositions.iterator();
    while (iterator.hasNext() && result.size() < limit) {
      result.add(iterator.next());
      iterator.remove();
    }
    return result;
  }

  void deferPosition(Position position, long ticks) {
    if (deferredPositions.size() < MAX_DIRTY_POSITIONS || deferredPositions.containsKey(position))
      deferredPositions.put(position, ticks + RETRY_TICKS);
    else {
      var section = sectionIndex.get(key(position));
      if (section != null) deferredSections.add(section);
    }
  }

  SectionGroup nextSection(long ticks) {
    if (ticks % RETRY_TICKS == 0 && !deferredSections.isEmpty()) {
      var iterator = deferredSections.iterator();
      var section = iterator.next();
      iterator.remove();
      return section;
    }
    while (!dirtySections.isEmpty()) {
      var iterator = dirtySections.iterator();
      var key = iterator.next();
      iterator.remove();
      var section = sectionIndex.get(key);
      if (section != null) return section;
    }
    if (nextSection < sections.size()) return sections.get(nextSection++);
    if (refreshSection >= 0) {
      if (refreshSection < sections.size()) return sections.get(refreshSection++);
      refreshSection = -1;
    }
    return null;
  }

  void deferSection(SectionGroup section) {
    deferredSections.add(section);
  }

  void unsupportedSection() {
    unsupportedSections++;
  }

  private void rebuild(Position player) {
    var source =
        (Host.SelectionSource)
            (name, requestedSession) ->
                session.equals(requestedSession)
                    ? selections.getOrDefault(name, List.of())
                    : List.of();
    var bounds = FiniteScope.bounds(profile.profile().scope(), player, source, session);
    if (bounds.stream().anyMatch(box -> !box.dimension().equals(player.dimension())))
      throw new IllegalArgumentException("Scope includes another dimension");
    sections = SCANNER.group(bounds, MAX_SECTIONS);
    var index = new HashMap<SectionKey, SectionGroup>();
    for (var section : sections) index.put(section.key(), section);
    sectionIndex = Map.copyOf(index);
    dirtyPositions.removeIf(candidate -> !containsPosition(candidate));
    deferredPositions.keySet().removeIf(candidate -> !containsPosition(candidate));
    nextSection = 0;
    refreshSection = -1;
    deferredSections.clear();
    dirtySections.clear();
  }

  private void queuePlayerInfluence(Position player, List<RefreshReads.PlayerSphere> spheres) {
    for (var sphere : spheres) {
      var shift = sphere.offset();
      long x = (long) player.x() - shift.x();
      long y = (long) player.y() - shift.y();
      long z = (long) player.z() - shift.z();
      queueSections(
          x - sphere.radius(),
          y - sphere.radius(),
          z - sphere.radius(),
          x + sphere.radius(),
          y + sphere.radius(),
          z + sphere.radius());
    }
  }

  private void queuePlayerChanges(
      Position previous, Position player, List<RefreshReads.PlayerSphere> spheres) {
    for (var sphere : spheres) {
      if (sphere.radius() > EXACT_SPHERE_RADIUS
          || !previous.dimension().equals(player.dimension())) {
        queuePlayerInfluence(previous, List.of(sphere));
        queuePlayerInfluence(player, List.of(sphere));
        continue;
      }
      long oldX = (long) previous.x() - sphere.offset().x();
      long oldY = (long) previous.y() - sphere.offset().y();
      long oldZ = (long) previous.z() - sphere.offset().z();
      long newX = (long) player.x() - sphere.offset().x();
      long newY = (long) player.y() - sphere.offset().y();
      long newZ = (long) player.z() - sphere.offset().z();
      queueSphereDifference(
          previous.dimension(), oldX, oldY, oldZ, newX, newY, newZ, sphere.radius());
      queueSphereDifference(
          previous.dimension(), newX, newY, newZ, oldX, oldY, oldZ, sphere.radius());
    }
  }

  private void queueSphereDifference(
      io.github.billstark001.latticium.dsl.Model.ResourceId dimension,
      long x,
      long y,
      long z,
      long otherX,
      long otherY,
      long otherZ,
      int radius) {
    for (int dy = -radius; dy <= radius; dy++)
      for (int dz = -radius; dz <= radius; dz++)
        for (int dx = -radius; dx <= radius; dx++) {
          long distance = (long) dx * dx + (long) dy * dy + (long) dz * dz;
          if (distance > (long) radius * radius) continue;
          long px = x + dx;
          long py = y + dy;
          long pz = z + dz;
          if (px < Integer.MIN_VALUE
              || px > Integer.MAX_VALUE
              || py < Integer.MIN_VALUE
              || py > Integer.MAX_VALUE
              || pz < Integer.MIN_VALUE
              || pz > Integer.MAX_VALUE) continue;
          long ox = px - otherX;
          long oy = py - otherY;
          long oz = pz - otherZ;
          if (Math.abs(ox) <= radius
              && Math.abs(oy) <= radius
              && Math.abs(oz) <= radius
              && ox * ox + oy * oy + oz * oz <= (long) radius * radius) continue;
          queuePosition(new Position(dimension, (int) px, (int) py, (int) pz));
        }
  }

  private void queuePosition(Position candidate) {
    if (!containsPosition(candidate)) return;
    var section = sectionIndex.get(key(candidate));
    if (dirtyPositions.size() < MAX_DIRTY_POSITIONS) dirtyPositions.add(candidate);
    else dirtySections.add(section.key());
  }

  private boolean containsPosition(Position candidate) {
    var section = sectionIndex.get(key(candidate));
    return section != null && section.bounds().stream().anyMatch(box -> box.contains(candidate));
  }

  private void queueSectionsAround(Position center, int radius) {
    queueSections(
        (long) center.x() - radius,
        (long) center.y() - radius,
        (long) center.z() - radius,
        (long) center.x() + radius,
        (long) center.y() + radius,
        (long) center.z() + radius);
  }

  private void queueSections(long x0, long y0, long z0, long x1, long y1, long z1) {
    if (x0 > Integer.MAX_VALUE
        || y0 > Integer.MAX_VALUE
        || z0 > Integer.MAX_VALUE
        || x1 < Integer.MIN_VALUE
        || y1 < Integer.MIN_VALUE
        || z1 < Integer.MIN_VALUE) return;
    int minX = Math.floorDiv((int) Math.max(x0, Integer.MIN_VALUE), 16);
    int minY = Math.floorDiv((int) Math.max(y0, Integer.MIN_VALUE), 16);
    int minZ = Math.floorDiv((int) Math.max(z0, Integer.MIN_VALUE), 16);
    int maxX = Math.floorDiv((int) Math.min(x1, Integer.MAX_VALUE), 16);
    int maxY = Math.floorDiv((int) Math.min(y1, Integer.MAX_VALUE), 16);
    int maxZ = Math.floorDiv((int) Math.min(z1, Integer.MAX_VALUE), 16);
    for (int y = minY; y <= maxY; y++)
      for (int z = minZ; z <= maxZ; z++)
        for (int x = minX; x <= maxX; x++) {
          var key = new SectionKey(lastPlayer.dimension(), x, y, z);
          if (sectionIndex.containsKey(key)) dirtySections.add(key);
        }
  }

  private static SectionKey key(Position position) {
    return new SectionKey(
        position.dimension(), position.x() >> 4, position.y() >> 4, position.z() >> 4);
  }
}

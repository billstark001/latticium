package io.github.billstark001.latticium.dsl;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Minecraft-independent values and read-only facts shared by the DSL and planner. */
public final class Model {
  private static final Set<ResourceId> VANILLA_AIR =
      Set.of(
          ResourceId.parse("minecraft:air"),
          ResourceId.parse("minecraft:cave_air"),
          ResourceId.parse("minecraft:void_air"));

  private Model() {}

  /** Recognizes the property-free states of the three vanilla air blocks. */
  public static boolean isVanillaAir(BlockState state) {
    return VANILLA_AIR.contains(state.block()) && state.properties().isEmpty();
  }

  public enum SetType {
    POS,
    BLOCK,
    STATE,
    ITEM,
    BIOME,
    FLUID
  }

  public enum Truth {
    TRUE,
    FALSE,
    UNKNOWN;

    public Truth not() {
      return this == UNKNOWN ? UNKNOWN : this == TRUE ? FALSE : TRUE;
    }

    public Truth and(Truth other) {
      Objects.requireNonNull(other, "other");
      return this == FALSE || other == FALSE
          ? FALSE
          : this == UNKNOWN || other == UNKNOWN ? UNKNOWN : TRUE;
    }

    public Truth or(Truth other) {
      Objects.requireNonNull(other, "other");
      return this == TRUE || other == TRUE
          ? TRUE
          : this == UNKNOWN || other == UNKNOWN ? UNKNOWN : FALSE;
    }
  }

  public record ResourceId(String namespace, String path) implements Comparable<ResourceId> {
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern PATH = Pattern.compile("[a-z0-9_./-]+");

    public ResourceId {
      Objects.requireNonNull(namespace, "namespace");
      Objects.requireNonNull(path, "path");
      if (!NAMESPACE.matcher(namespace).matches() || !PATH.matcher(path).matches())
        throw new IllegalArgumentException("Invalid resource ID: " + namespace + ":" + path);
    }

    /** Parses a full ID, or uses the {@code minecraft} namespace for a short ID. */
    public static ResourceId parse(String text) {
      Objects.requireNonNull(text, "text");
      int separator = text.indexOf(':');
      if (separator < 0) return new ResourceId("minecraft", text);
      if (text.indexOf(':', separator + 1) >= 0)
        throw new IllegalArgumentException("Invalid resource ID: " + text);
      return new ResourceId(text.substring(0, separator), text.substring(separator + 1));
    }

    @Override
    public String toString() {
      return namespace + ":" + path;
    }

    @Override
    public int compareTo(ResourceId other) {
      return toString().compareTo(other.toString());
    }
  }

  public record BlockState(ResourceId block, Map<String, String> properties) {
    public BlockState {
      Objects.requireNonNull(block, "block");
      properties = Map.copyOf(properties);
    }

    public Optional<String> property(String key) {
      return Optional.ofNullable(properties.get(key));
    }

    /** Stable registry ID and sorted properties for ordering or diagnostics. */
    public String canonicalId() {
      var joined = new StringJoiner(",", block + "[", "]");
      new TreeMap<>(properties).forEach((key, value) -> joined.add(key + "=" + value));
      return joined.toString();
    }
  }

  public record Position(ResourceId dimension, int x, int y, int z) {
    public Position {
      Objects.requireNonNull(dimension, "dimension");
    }

    /** Returns a translated position, throwing if an integer coordinate overflows. */
    public Position offset(int dx, int dy, int dz) {
      return new Position(
          dimension, Math.addExact(x, dx), Math.addExact(y, dy), Math.addExact(z, dz));
    }
  }

  /** Inclusive integer interval; a null endpoint is open, but both cannot be null. */
  public record IntRange(Integer min, Integer max) {
    public IntRange {
      if (min == null && max == null || min != null && max != null && min > max)
        throw new IllegalArgumentException("Empty range");
    }

    public boolean contains(int value) {
      return (min == null || min <= value) && (max == null || value <= max);
    }
  }

  /** Exact state, requested air, intentional omission or unavailable target data. */
  public sealed interface TargetCell
      permits TargetCell.Exact, TargetCell.Clear, TargetCell.DontCare, TargetCell.Unknown {
    record Exact(BlockState state) implements TargetCell {
      public Exact {
        Objects.requireNonNull(state);
      }
    }

    record Clear() implements TargetCell {}

    record DontCare() implements TargetCell {}

    record Unknown(String reason) implements TargetCell {}
  }

  /** Null fields mean that the corresponding world fact was not captured. */
  public record WorldCell(
      BlockState state, ResourceId biome, ResourceId fluid, Integer light, Boolean solid) {}

  /**
   * All methods read one immutable capture; unavailable facts must remain unknown to evaluators.
   */
  public interface Facts {
    /** Empty means the cell is unavailable; individual null fields mean partial capture. */
    Optional<WorldCell> world(Position pos);

    /** Returns {@link TargetCell.Unknown} when target data cannot be read. */
    TargetCell target(Position pos);

    /** Empty means the player anchor is unavailable in this capture. */
    Optional<Position> player();

    /** Empty means inventory data is unavailable, not an empty inventory. */
    Optional<Set<ResourceId>> inventory();

    /** Returns {@link Truth#UNKNOWN} when selection membership is unavailable. */
    Truth selection(String name, Position pos);
  }

  /**
   * Version-bound registry domain used to validate and enumerate symbolic set members. POS has no
   * registry domain; its predicates are evaluated against finite world bounds instead.
   */
  public interface Registry {
    enum Resolution {
      FOUND,
      MISSING,
      UNAVAILABLE
    }

    /** Resolves an ID without treating an unavailable registry as a missing ID. */
    Resolution resolve(SetType kind, ResourceId id);

    /** Resolves a tag name within one registry kind. */
    Resolution resolveTag(SetType kind, ResourceId id);

    /** Returns the members of a resolved tag. Rebind expressions after a registry reload. */
    Set<ResourceId> tag(SetType kind, ResourceId id);

    /**
     * Returns the finite ID domain. For STATE this contains block IDs; {@link #states(ResourceId)}
     * enumerates each block's legal states.
     */
    Set<ResourceId> universe(SetType kind);

    /** Returns all legal states of a block in this registry version. */
    Set<BlockState> states(ResourceId block);
  }
}

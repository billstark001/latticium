package io.github.billstark001.latticium.dsl;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class Model {
    private Model() {}

    public enum SetType { POS, BLOCK, STATE, ITEM, BIOME, FLUID }
    public enum Truth {
        TRUE, FALSE, UNKNOWN;
        public Truth not() { return this == UNKNOWN ? UNKNOWN : this == TRUE ? FALSE : TRUE; }
        public Truth and(Truth other) { return this == FALSE || other == FALSE ? FALSE : this == UNKNOWN || other == UNKNOWN ? UNKNOWN : TRUE; }
        public Truth or(Truth other) { return this == TRUE || other == TRUE ? TRUE : this == UNKNOWN || other == UNKNOWN ? UNKNOWN : FALSE; }
    }

    public record ResourceId(String namespace, String path) implements Comparable<ResourceId> {
        public ResourceId {
            if (!namespace.matches("[a-z0-9_.-]+") || !path.matches("[a-z0-9_./-]+"))
                throw new IllegalArgumentException("Invalid resource ID: " + namespace + ":" + path);
        }
        public static ResourceId parse(String text) {
            var parts = text.split(":", -1);
            if (parts.length == 1) return new ResourceId("minecraft", parts[0]);
            if (parts.length == 2) return new ResourceId(parts[0], parts[1]);
            throw new IllegalArgumentException("Invalid resource ID: " + text);
        }
        @Override public String toString() { return namespace + ":" + path; }
        @Override public int compareTo(ResourceId other) { return toString().compareTo(other.toString()); }
    }

    public record BlockState(ResourceId block, Map<String, String> properties) {
        public BlockState { properties = Map.copyOf(properties); }
        public Optional<String> property(String key) { return Optional.ofNullable(properties.get(key)); }
    }
    public record Position(ResourceId dimension, int x, int y, int z) {
        public Position offset(int dx, int dy, int dz) { return new Position(dimension, Math.addExact(x, dx), Math.addExact(y, dy), Math.addExact(z, dz)); }
    }
    public record IntRange(Integer min, Integer max) {
        public IntRange { if (min == null && max == null || min != null && max != null && min > max) throw new IllegalArgumentException("Empty range"); }
        public boolean contains(int value) { return (min == null || min <= value) && (max == null || value <= max); }
    }

    public sealed interface TargetCell permits TargetCell.Exact, TargetCell.Clear, TargetCell.DontCare, TargetCell.Unknown {
        record Exact(BlockState state) implements TargetCell { public Exact { Objects.requireNonNull(state); } }
        record Clear() implements TargetCell {}
        record DontCare() implements TargetCell {}
        record Unknown(String reason) implements TargetCell {}
    }
    public record WorldCell(BlockState state, ResourceId biome, ResourceId fluid, Integer light, Boolean solid) {}
    public interface Facts {
        Optional<WorldCell> world(Position pos);
        TargetCell target(Position pos);
        Optional<Position> player();
        Optional<Set<ResourceId>> inventory();
        Truth selection(String name, Position pos);
    }
    public interface Registry {
        enum Resolution { FOUND, MISSING, UNAVAILABLE }
        Resolution resolve(SetType kind, ResourceId id);
        Resolution resolveTag(SetType kind, ResourceId id);
        Set<ResourceId> tag(SetType kind, ResourceId id);
        Set<ResourceId> universe(SetType kind);
        Set<BlockState> states(ResourceId block);
    }
}

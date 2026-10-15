package io.github.billstark001.latticium.planning;

import io.github.billstark001.latticium.dsl.FactDependencies;
import io.github.billstark001.latticium.dsl.FactDependencies.Fact;
import io.github.billstark001.latticium.dsl.Model.Position;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Derives placement/support reads from executable action contracts and concrete proposal data. */
public final class PredictionReads {
  public record Contract(Set<Fact> columns, boolean clicked) {
    public Contract {
      columns = Set.copyOf(columns);
    }
  }

  public record Required(FactDependencies dependencies, int radius) {}

  private static final Map<Planner.Action, Contract> CONTRACTS = load();

  private PredictionReads() {}

  public static Map<Planner.Action, Contract> contracts() {
    return CONTRACTS;
  }

  public static Optional<Required> in(Position center, List<Planner.Proposal> proposals) {
    var dependencies = FactDependencies.of(Fact.STATE);
    int radius = 0;
    try {
      for (var proposal : proposals) {
        var contract = CONTRACTS.get(proposal.action());
        var columns = FactDependencies.of(contract.columns().toArray(Fact[]::new));
        dependencies = dependencies.union(columns);
        var positions = new java.util.HashSet<>(proposal.affected());
        var interaction = proposal.interaction();
        if (contract.clicked() && interaction != null) {
          if (interaction.clicked() != null) positions.add(interaction.clicked());
          else if (proposal.action() == Planner.Action.INTERACT) positions.add(center);
          else positions.add(support(center, interaction.face()));
        }
        for (var position : positions) {
          if (!position.dimension().equals(center.dimension())) return Optional.empty();
          long dx = (long) position.x() - center.x(),
              dy = (long) position.y() - center.y(),
              dz = (long) position.z() - center.z();
          if (Math.abs(dx) > 16 || Math.abs(dy) > 16 || Math.abs(dz) > 16) return Optional.empty();
          radius =
              Math.max(radius, (int) Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))));
          dependencies = dependencies.union(columns.shift((int) dx, (int) dy, (int) dz));
        }
      }
    } catch (ArithmeticException outsideWorld) {
      return Optional.empty();
    }
    return Optional.of(new Required(dependencies, radius));
  }

  private static Position support(Position position, Planner.Interaction.Face face) {
    return switch (face) {
      case UP -> position.offset(0, -1, 0);
      case DOWN -> position.offset(0, 1, 0);
      case EAST -> position.offset(-1, 0, 0);
      case WEST -> position.offset(1, 0, 0);
      case SOUTH -> position.offset(0, 0, -1);
      case NORTH -> position.offset(0, 0, 1);
    };
  }

  private static Map<Planner.Action, Contract> load() {
    try (var input =
        PredictionReads.class.getResourceAsStream("/latticium/prediction-reads.json")) {
      if (input == null) throw new IllegalStateException("Missing prediction read contracts");
      var root = StrictJson.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
      if (!root.isArray()) throw new IllegalStateException("Invalid prediction read contracts");
      var result = new EnumMap<Planner.Action, Contract>(Planner.Action.class);
      for (var row : root) {
        if (!row.isObject()
            || row.size() != 3
            || !row.has("action")
            || !row.has("columns")
            || !row.has("clicked")
            || !row.get("clicked").isBoolean()
            || !row.get("columns").isArray())
          throw new IllegalStateException("Invalid prediction read contract");
        var columns = java.util.EnumSet.noneOf(Fact.class);
        for (var value : row.get("columns")) columns.add(Fact.valueOf(value.asText()));
        if (!columns.contains(Fact.STATE)
            || !Set.of(Fact.STATE, Fact.FLUID, Fact.SOLID).containsAll(columns))
          throw new IllegalStateException("Invalid prediction world columns");
        var action = Planner.Action.valueOf(row.get("action").asText());
        if (result.putIfAbsent(action, new Contract(columns, row.get("clicked").booleanValue()))
            != null) throw new IllegalStateException("Duplicate prediction action: " + action);
      }
      if (result.size() != Planner.Action.values().length)
        throw new IllegalStateException("Missing prediction actions");
      return Map.copyOf(result);
    } catch (IOException | IllegalArgumentException error) {
      throw new IllegalStateException("Invalid prediction read catalog", error);
    }
  }
}

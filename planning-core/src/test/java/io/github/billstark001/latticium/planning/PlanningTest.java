package io.github.billstark001.latticium.planning;

import static org.junit.jupiter.api.Assertions.*;

import io.github.billstark001.latticium.dsl.Compiler;
import io.github.billstark001.latticium.dsl.Model.*;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlanningTest {
  static final ResourceId DIM = ResourceId.parse("minecraft:overworld"),
      AIR = ResourceId.parse("minecraft:air"),
      STONE = ResourceId.parse("minecraft:stone");
  static final BlockState air = new BlockState(AIR, Map.of()),
      stone = new BlockState(STONE, Map.of());

  @Test
  void strictProfileAndFiniteScope() {
    var reader = new ProfileReader();
    String json =
        """
          {"schema":1,"id":"user:test","scope":"box(0,0,0,1,1,1)","select":{"where":"all()"},"target":{"items":"{minecraft:stone}"}}
          """;
    var p = reader.read(json);
    assertEquals(Profile.Select.Choose.NEAREST, p.select().choose());
    assertEquals(SetType.POS, reader.bind(p, Compiler.symbolic()).scope().type());
    assertThrows(
        ProfileReader.Error.class,
        () -> reader.read(json.replace("\"schema\":1", "\"schema\":1,\"schema\":1")));
    assertThrows(
        ProfileReader.Error.class,
        () ->
            reader.bind(
                reader.read(json.replace("box(0,0,0,1,1,1)", "all()")), Compiler.symbolic()));
  }

  @Test
  void designExampleCompilesOffline() {
    String json =
        """
        {"schema":1,"id":"user:basalt_lava",
         "activation":{"where":"$minecraft:basalt_deltas & dimension(minecraft:the_nether)","mode":"while"},
         "scope":"sphere(player, 5)",
         "select":{"where":"fluid({minecraft:lava}) & $minecraft:basalt_deltas","choose":"nearest"},
         "target":{"items":"{minecraft:stone, minecraft:dirt, minecraft:netherrack}",
                   "choose":{"prefer":["minecraft:stone","minecraft:dirt","minecraft:netherrack"]}},
         "policy":{"break":"deny","max_actions_per_tick":1,"max_actions_per_activation":256}}
        """;
    var reader = new ProfileReader();
    var bound = reader.bind(reader.read(json), Compiler.symbolic());
    assertEquals(SetType.POS, bound.select().type());
    assertEquals(SetType.ITEM, bound.items().type());
  }

  @Test
  void explicitModuleImportProvidesDeclarationsAndRejectsCycle() {
    var reader = new ProfileReader();
    var profile =
        reader.read(
            """
          {"schema":1,"id":"user:modular","use":["user:common"],"scope":"box(0,0,0,1,0,0)",
           "select":{"where":"rocks"},"target":{"items":"{minecraft:stone}"}}
          """);
    assertThrows(ProfileReader.Error.class, () -> reader.bind(profile, Compiler.symbolic()));
    ModuleLoader.Resolver good =
        id ->
            Optional.of(
                new ModuleLoader.Module("rocks: PosSet = current(b{minecraft:stone});", List.of()));
    assertEquals(SetType.POS, reader.bind(profile, Compiler.symbolic(), good).select().type());
    ModuleLoader.Resolver cycle =
        id -> Optional.of(new ModuleLoader.Module("rocks: PosSet = all();", List.of(id)));
    assertThrows(ProfileReader.Error.class, () -> reader.bind(profile, Compiler.symbolic(), cycle));
  }

  @Test
  void scannerKeepsUnknownSeparate() {
    var facts =
        new Facts() {
          public Optional<WorldCell> world(Position p) {
            return p.x() == 0
                ? Optional.of(new WorldCell(stone, DIM, AIR, 0, true))
                : Optional.empty();
          }

          public TargetCell target(Position p) {
            return new TargetCell.DontCare();
          }

          public Optional<Position> player() {
            return Optional.empty();
          }

          public Optional<Set<ResourceId>> inventory() {
            return Optional.empty();
          }

          public Truth selection(String n, Position p) {
            return Truth.FALSE;
          }
        };
    var c = Compiler.symbolic();
    var scan = new SectionScanner();
    var result =
        scan.scan(
                new SectionScanner.Bounds(DIM, 0, 0, 0, 1, 0, 0),
                c.compile("box(0,0,0,1,0,0)", SetType.POS),
                c.compile("!current(b{stone})", SetType.POS),
                facts,
                1)
            .getFirst();
    assertEquals(0, result.trueCount());
    assertEquals(1, result.unknownCount());
  }

  @Test
  void plannerEnforcesBreakPolicy() {
    var pos = new Position(DIM, 0, 0, 0);
    var planner = new Planner();
    Planner.Oracle oracle =
        (p, current, goal) ->
            new Planner.Prediction.Proposals(
                List.of(
                    new Planner.Proposal(
                        Planner.Action.BREAK,
                        air,
                        Set.of(pos),
                        new Planner.Cost(1, 0, 0),
                        "clear")));
    var target = new TargetCell.Clear();
    assertInstanceOf(
        Planner.Result.NoPlan.class,
        planner.plan(
            pos,
            stone,
            target,
            new Profile.Policy(Profile.Policy.BreakMode.DENY, 1, 8),
            oracle,
            16));
    assertInstanceOf(
        Planner.Result.Ready.class,
        planner.plan(
            pos,
            stone,
            target,
            new Profile.Policy(Profile.Policy.BreakMode.SELECTED, 1, 8),
            oracle,
            16));
  }

  @Test
  void materialChoiceUsesAvailabilityAndVerifiableStates() {
    var pos = new Position(DIM, 0, 0, 0);
    var c = Compiler.symbolic();
    var items = c.compile("i{minecraft:stone,minecraft:dirt}", SetType.ITEM);
    var stoneItem = STONE;
    var dirt = ResourceId.parse("minecraft:dirt");
    var facts =
        new Facts() {
          public Optional<WorldCell> world(Position p) {
            return Optional.empty();
          }

          public TargetCell target(Position p) {
            return new TargetCell.DontCare();
          }

          public Optional<Position> player() {
            return Optional.empty();
          }

          public Optional<Set<ResourceId>> inventory() {
            return Optional.of(Set.of(stoneItem, dirt));
          }

          public Truth selection(String n, Position p) {
            return Truth.FALSE;
          }
        };
    MaterialSelector.Oracle oracle =
        (item, p) -> new MaterialSelector.Outcome.States(Set.of(new BlockState(item, Map.of())));
    var result =
        new MaterialSelector()
            .choose(
                pos,
                air,
                items,
                null,
                facts,
                Map.of(stoneItem, 4, dirt, 9),
                List.of(stoneItem),
                oracle);
    assertEquals(stoneItem, ((MaterialSelector.Choice.Frozen) result).item());
  }

  @Test
  void readOnlyTerminalCountsKnownAndUnknown() {
    var doc =
        io.github.billstark001.latticium.dsl.Parser.document(
            "query !current(b{minecraft:stone}) order by x desc limit 1;");
    var bound = Compiler.symbolic().compile(doc).getFirst();
    var facts =
        new Facts() {
          public Optional<WorldCell> world(Position p) {
            return p.x() == 0
                ? Optional.of(new WorldCell(stone, DIM, AIR, 0, true))
                : Optional.empty();
          }

          public TargetCell target(Position p) {
            return new TargetCell.DontCare();
          }

          public Optional<Position> player() {
            return Optional.empty();
          }

          public Optional<Set<ResourceId>> inventory() {
            return Optional.empty();
          }

          public Truth selection(String n, Position p) {
            return Truth.FALSE;
          }
        };
    var registry =
        new Registry() {
          public Resolution resolve(SetType k, ResourceId id) {
            return Resolution.FOUND;
          }

          public Resolution resolveTag(SetType k, ResourceId id) {
            return Resolution.FOUND;
          }

          public Set<ResourceId> tag(SetType k, ResourceId id) {
            return Set.of();
          }

          public Set<ResourceId> universe(SetType k) {
            return Set.of();
          }

          public Set<BlockState> states(ResourceId id) {
            return Set.of();
          }
        };
    var result =
        new QueryRunner()
            .run(
                doc.terminals().getFirst(),
                bound,
                List.of(new SectionScanner.Bounds(DIM, 0, 0, 0, 1, 0, 0)),
                registry,
                facts,
                16);
    assertEquals(0, result.count());
    assertEquals(1, result.unknownCount());
    assertEquals(Truth.UNKNOWN, result.exists());
  }

  @Test
  void activationUnknownDoesNotCreateEnterEdge() {
    var tracker = new ActivationTracker();
    var activation = new Profile.Activation("all()", Profile.Activation.Mode.ENTER, false);
    var where = Compiler.symbolic().compile("all()", SetType.POS);
    var known =
        new Facts() {
          public Optional<WorldCell> world(Position p) {
            return Optional.empty();
          }

          public TargetCell target(Position p) {
            return new TargetCell.DontCare();
          }

          public Optional<Position> player() {
            return Optional.of(new Position(DIM, 0, 0, 0));
          }

          public Optional<Set<ResourceId>> inventory() {
            return Optional.empty();
          }

          public Truth selection(String n, Position p) {
            return Truth.FALSE;
          }
        };
    var missing =
        new Facts() {
          public Optional<WorldCell> world(Position p) {
            return Optional.empty();
          }

          public TargetCell target(Position p) {
            return new TargetCell.DontCare();
          }

          public Optional<Position> player() {
            return Optional.empty();
          }

          public Optional<Set<ResourceId>> inventory() {
            return Optional.empty();
          }

          public Truth selection(String n, Position p) {
            return Truth.FALSE;
          }
        };
    assertEquals(ActivationTracker.Decision.IDLE, tracker.sample(activation, where, known));
    assertEquals(ActivationTracker.Decision.DEFER, tracker.sample(activation, where, missing));
    assertEquals(ActivationTracker.Decision.IDLE, tracker.sample(activation, where, known));
  }
}

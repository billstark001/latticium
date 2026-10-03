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
  void profileBindingDoesNotLeakTargetViewToLaterQueries() {
    var compiler = Compiler.symbolic();
    var profile =
        new ProfileReader()
            .read(
                """
                {"schema":1,"id":"user:source","scope":"box(0,0,0,1,0,0)",
                 "select":{"where":"matches_target()"},"target":{"source":"user:blueprint"}}
                """);
    new ProfileReader().bind(profile, compiler);
    assertThrows(
        io.github.billstark001.latticium.dsl.Syntax.Failure.class,
        () -> compiler.compile("matches_target()", SetType.POS));
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
  void scannerSkipsFactReadsOutsideKnownFalseScope() {
    int[] worldReads = {0};
    Facts facts =
        new Facts() {
          public Optional<WorldCell> world(Position p) {
            worldReads[0]++;
            return Optional.of(new WorldCell(stone, DIM, AIR, 0, true));
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
    var compiler = Compiler.symbolic();
    var result =
        new SectionScanner()
            .scan(
                new SectionScanner.Bounds(DIM, 0, 0, 0, 1, 0, 0),
                compiler.compile("x=0", SetType.POS),
                compiler.compile("current(b{minecraft:stone})", SetType.POS),
                facts,
                1)
            .getFirst();
    assertEquals(1, result.trueCount());
    assertEquals(1, worldReads[0]);
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
  void plannerReturnsLowestCostGoalRatherThanFirstDiscoveredGoal() {
    var pos = new Position(DIM, 0, 0, 0);
    var intermediate = new BlockState(ResourceId.parse("minecraft:dirt"), Map.of());
    Planner.Oracle oracle =
        (p, current, goal) -> {
          if (current.equals(stone)) {
            return new Planner.Prediction.Proposals(
                List.of(
                    new Planner.Proposal(
                        Planner.Action.PLACE,
                        air,
                        Set.of(pos),
                        new Planner.Cost(5, 0, 0),
                        "direct"),
                    new Planner.Proposal(
                        Planner.Action.INTERACT,
                        intermediate,
                        Set.of(pos),
                        new Planner.Cost(1, 0, 0),
                        "first")));
          }
          return new Planner.Prediction.Proposals(
              List.of(
                  new Planner.Proposal(
                      Planner.Action.INTERACT,
                      air,
                      Set.of(pos),
                      new Planner.Cost(1, 0, 0),
                      "second")));
        };
    var result =
        assertInstanceOf(
            Planner.Result.Ready.class,
            new Planner()
                .plan(
                    pos,
                    stone,
                    new TargetCell.Clear(),
                    new Profile.Policy(Profile.Policy.BreakMode.DENY, 1, 8),
                    oracle,
                    3));
    assertEquals(2, result.cost().actions());
    assertEquals(
        List.of("first", "second"), result.steps().stream().map(Planner.Proposal::ruleId).toList());
  }

  @Test
  void plannerContinuesPastUnknownBranchWhenAnotherBranchCanReachGoal() {
    var pos = new Position(DIM, 0, 0, 0);
    var unknown = new BlockState(ResourceId.parse("minecraft:dirt"), Map.of());
    var known = new BlockState(ResourceId.parse("minecraft:granite"), Map.of());
    Planner.Oracle oracle =
        (p, current, goal) -> {
          if (current.equals(unknown)) return new Planner.Prediction.Unknown("Missing facts");
          if (current.equals(known))
            return new Planner.Prediction.Proposals(
                List.of(
                    new Planner.Proposal(
                        Planner.Action.INTERACT,
                        air,
                        Set.of(pos),
                        new Planner.Cost(1, 0, 0),
                        "finish")));
          return new Planner.Prediction.Proposals(
              List.of(
                  new Planner.Proposal(
                      Planner.Action.INTERACT,
                      unknown,
                      Set.of(pos),
                      new Planner.Cost(1, 0, 0),
                      "unknown"),
                  new Planner.Proposal(
                      Planner.Action.INTERACT,
                      known,
                      Set.of(pos),
                      new Planner.Cost(1, 0, 0),
                      "known")));
        };
    var policy = new Profile.Policy(Profile.Policy.BreakMode.DENY, 1, 8);
    var planner = new Planner();
    assertInstanceOf(
        Planner.Result.Ready.class,
        planner.plan(pos, stone, new TargetCell.Clear(), policy, oracle, 4));
    assertInstanceOf(
        Planner.Result.Deferred.class,
        planner.plan(
            pos,
            stone,
            new TargetCell.Clear(),
            policy,
            (p, current, goal) -> new Planner.Prediction.Unknown("Missing facts"),
            4));
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

    var terminals =
        io.github.billstark001.latticium.dsl.Parser.document(
            "query all() order by y asc limit 2; count all(); exists all();");
    var expressions = Compiler.symbolic().compile(terminals);
    var domain = List.of(new SectionScanner.Bounds(DIM, 0, 0, 0, 4, 0, 0));
    var runner = new QueryRunner();
    var limited =
        runner.run(terminals.terminals().get(0), expressions.get(0), domain, registry, facts, 5);
    assertEquals(
        List.of(new Position(DIM, 0, 0, 0), new Position(DIM, 1, 0, 0)), limited.matches());
    assertEquals(5, limited.count());
    var counted =
        runner.run(terminals.terminals().get(1), expressions.get(1), domain, registry, facts, 5);
    assertEquals(List.of(), counted.matches());
    assertEquals(5, counted.count());
    assertEquals(Truth.TRUE, counted.exists());
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

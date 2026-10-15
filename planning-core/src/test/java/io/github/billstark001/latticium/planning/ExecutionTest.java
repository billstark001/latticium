package io.github.billstark001.latticium.planning;

import static org.junit.jupiter.api.Assertions.*;

import io.github.billstark001.latticium.dsl.Compiler;
import io.github.billstark001.latticium.dsl.FactDependencies.Fact;
import io.github.billstark001.latticium.dsl.FactDependencies.Offset;
import org.junit.jupiter.api.Test;

class ExecutionTest {
  private static final String PROFILE =
      """
      {"schema":1,"id":"test:execution","scope":"box(0,0,0,1,1,1)",
       "select":{"where":"all()"},"target":{"clear":true}%s}
      """;

  private static Profile read(String options) {
    return new ProfileReader().read(PROFILE.formatted(options));
  }

  @Test
  void selectionIntentIncludesAutomaticActivationInputs() {
    var profile =
        new ProfileReader()
            .read(
                """
        {"schema":1,"id":"test:selection_reads",
         "activation":{"where":"selection(trigger)","mode":"while"},
         "scope":"selection(scope)","select":{"where":"selection(\\\"quoted\\\")"},
         "target":{"clear":true}}
        """);
    assertEquals(java.util.Set.of("trigger", "scope", "quoted"), SelectionReads.in(profile));
  }

  @Test
  void legacyProfilesKeepConservativeDefaults() {
    assertEquals(Execution.DEFAULT, read("").execution());
    var empty = read(",\"execution\":{}");
    assertEquals(Execution.DEFAULT, empty.execution());
    assertEquals(Execution.DEFAULT.planning().maxNodesPerTick(), 1024);
  }

  @Test
  void optInFixedPositionFlowAndIndependentConsistencyOptionsArePreserved() {
    var profile =
        read(
            """
        ,"execution":{"consistency":{"granularity":"section","selections":"freeze",
        "rules":"freeze","targets":"pinned"},"dynamics":{"motion":"settling",
        "repeat":"while_selected","settle_ticks":40},"planning":{"initial_nodes":32,
        "max_nodes_per_tick":512,"max_retry_entries":12,"max_candidates":100,"retry_ticks":5}}
        """);
    assertEquals(Execution.Edits.FREEZE, profile.execution().consistency().selections());
    assertEquals(Execution.Targets.PINNED, profile.execution().consistency().targets());
    assertEquals(Execution.Repeat.WHILE_SELECTED, profile.execution().dynamics().repeat());
    assertEquals(100, profile.execution().planning().maxCandidates());
    assertEquals(40, profile.execution().dynamics().settleTicks());
  }

  @Test
  void dependencyExtensionsWidenDerivedReadsWithoutReplacingThem() {
    var profile =
        read(
            """
        ,"execution":{"dependencies":{"reads":"faces","extra_offsets":[[3,0,0]]}}
        """);
    var bound = new ProfileReader().bind(profile, Compiler.symbolic());
    var dependencies = bound.planningDependencies();
    assertTrue(dependencies.needs(Fact.STATE));
    assertTrue(dependencies.needs(Fact.FLUID));
    var offsets = dependencies.columns().get(Fact.STATE).offsets();
    assertTrue(offsets.contains(new Offset(0, 0, 0)));
    assertTrue(offsets.contains(new Offset(0, -1, 0)));
    assertTrue(offsets.contains(new Offset(3, 0, 0)));
    assertEquals(3, bound.planningRadius());
  }

  @Test
  void invalidConfigurationFailsAtItsJsonPointer() {
    String[][] invalid = {
      {"null", "/execution"},
      {"{\"invented\":true}", "/execution/invented"},
      {"{\"consistency\":{\"targets\":\"ignore_checks\"}}", "/execution/consistency/targets"},
      {"{\"planning\":{\"max_nodes_per_tick\":1}}", "/execution/planning/max_nodes_per_tick"},
      {"{\"planning\":{\"initial_nodes\":2048}}", "/execution/planning/max_nodes_per_tick"},
      {"{\"planning\":{\"max_candidates\":0}}", "/execution/planning/max_candidates"},
      {"{\"dynamics\":{\"settle_ticks\":1201}}", "/execution/dynamics/settle_ticks"},
      {
        "{\"dependencies\":{\"extra_offsets\":[[0,17,0]]}}",
        "/execution/dependencies/extra_offsets/0/1"
      },
      {"{\"dependencies\":{\"radius\":1.5}}", "/execution/dependencies/radius"}
    };
    for (var entry : invalid) {
      var error = assertThrows(ProfileReader.Error.class, () -> read(",\"execution\":" + entry[0]));
      assertTrue(error.getMessage().startsWith(entry[1] + ":"), error.getMessage());
    }
  }
}

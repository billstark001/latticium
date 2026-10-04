package io.github.billstark001.latticium.planning;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ProfileRefreshTest {
  private static final String PROFILE =
      """
      {"schema":1,"id":"user:refresh","scope":"box(0,0,0,1,0,0)",
       "select":{"where":"current(b{minecraft:lava})"},
       "target":{"items":"i{minecraft:stone}"},"policy":%s}
      """;

  @Test
  void continuousIsTheDefaultAndSurvivesActionBudgetReduction() {
    var policy = new ProfileReader().read(PROFILE.formatted("{}")).policy();
    assertEquals(Profile.Policy.RefreshMode.CONTINUOUS, policy.refreshMode());
    assertEquals(Profile.Policy.RefreshMode.CONTINUOUS, policy.afterActions(1).refreshMode());
  }

  @Test
  void manualModeIsExplicitAndInvalidModesAreRejected() {
    var reader = new ProfileReader();
    assertEquals(
        Profile.Policy.RefreshMode.MANUAL,
        reader.read(PROFILE.formatted("{\"refresh\":\"manual\"}")).policy().refreshMode());
    assertThrows(
        ProfileReader.Error.class,
        () -> reader.read(PROFILE.formatted("{\"refresh\":\"always\"}")));
  }
}

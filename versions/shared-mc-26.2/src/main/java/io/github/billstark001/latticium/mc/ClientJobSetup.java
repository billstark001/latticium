package io.github.billstark001.latticium.mc;

import io.github.billstark001.latticium.dsl.Compiler;
import io.github.billstark001.latticium.dsl.Model.Position;
import io.github.billstark001.latticium.planning.*;
import io.github.billstark001.latticium.planning.SectionScanner.Bounds;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;

/** Main-thread adapter construction; these native objects are never worker inputs. */
record ClientJobSetup(
    Host.Epochs epochs,
    Profile.Bound profile,
    Map<String, List<Bounds>> selections,
    RefreshCoordinator refresh,
    ClientPlanning planning,
    ClientTargetResolver targets,
    MinecraftSectionSource scans,
    MinecraftSectionSource plans) {
  static ClientJobSetup create(
      Minecraft minecraft,
      Host.SessionId session,
      String json,
      Host.TargetSource targets,
      Map<String, List<Bounds>> selections,
      MinecraftPlacementOracle oracle) {
    var epochs = ClientRegistryState.epochs();
    var frozen = SelectionBounds.copy(selections);
    var reader = new ProfileReader();
    var parsed = reader.read(json);
    var profile = reader.bind(parsed, new Compiler(new MinecraftRegistry(minecraft.level), 16));
    var feet = minecraft.player.blockPosition();
    var anchor =
        new Position(
            MinecraftStateCodec.id(minecraft.level.dimension().identifier()),
            feet.getX(),
            feet.getY(),
            feet.getZ());
    var refresh = new RefreshCoordinator(profile, session, frozen, anchor);
    var planning =
        new ClientPlanning(
            oracle,
            new ClientMaterialGate(minecraft, oracle, profile.items()),
            parsed.policy(),
            parsed.execution().planning());
    var resolver = new ClientTargetResolver(minecraft, session, profile, targets, oracle);
    var source = resolver.targetSource();
    var scans =
        new MinecraftSectionSource(
            minecraft, session, epochs, source, frozen, profile.scanDependencies());
    var plans =
        new MinecraftSectionSource(
            minecraft, session, epochs, source, frozen, profile.planningDependencies());
    scans.granularity(parsed.execution().consistency().granularity());
    plans.granularity(parsed.execution().consistency().granularity());
    return new ClientJobSetup(epochs, profile, frozen, refresh, planning, resolver, scans, plans);
  }
}

package io.github.billstark001.latticium.mc;

import io.github.billstark001.latticium.dsl.Model.Position;
import io.github.billstark001.latticium.planning.Profile;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;

/** Finite admission, rediscovery and bounded diagnostic samples for one job. */
final class ClientScheduling {
  private final Minecraft minecraft;
  private final Profile profile;
  private final ClientCandidates candidates;
  private final RefreshCoordinator refresh;
  private final ClientPlanning planning;
  private final ClientTargetResolver targets;
  private final Map<Position, String> blocked;

  ClientScheduling(
      Minecraft minecraft,
      Profile profile,
      ClientCandidates candidates,
      RefreshCoordinator refresh,
      ClientPlanning planning,
      ClientTargetResolver targets,
      Map<Position, String> blocked) {
    this.minecraft = minecraft;
    this.profile = profile;
    this.candidates = candidates;
    this.refresh = refresh;
    this.planning = planning;
    this.targets = targets;
    this.blocked = blocked;
  }

  static Map<Position, String> diagnostics(int capacity) {
    return new LinkedHashMap<>() {
      @Override
      protected boolean removeEldestEntry(Map.Entry<Position, String> entry) {
        return size() > capacity;
      }
    };
  }

  Comparator<Position> order() {
    var feet = minecraft.player.blockPosition();
    var player =
        new Position(
            MinecraftStateCodec.id(minecraft.level.dimension().identifier()),
            feet.getX(),
            feet.getY(),
            feet.getZ());
    return ClientJobView.order(profile.select().choose(), player);
  }

  void admit(Position position, Comparator<Position> order, long tick) {
    admit(position, order, tick, null);
  }

  void admit(
      Position position,
      Comparator<Position> order,
      long tick,
      io.github.billstark001.latticium.planning.Revisions.Stamp stamp) {
    var admission = candidates.admit(position, order, tick);
    if (admission.admitted() && stamp != null) candidates.inputs(position, stamp.at(position));
    if (!admission.admitted()) {
      refresh.park(position);
      blocked.put(position, "Candidate capacity reached; section queued for rediscovery");
    }
    if (admission.displaced() != null) {
      var displaced = admission.displaced();
      planning.forget(displaced);
      targets.forget(displaced);
      blocked.remove(displaced);
      refresh.park(displaced);
    }
  }
}

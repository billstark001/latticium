import io.github.billstark001.latticium.dsl.Model.*;
import io.github.billstark001.latticium.mc.*;
import io.github.billstark001.latticium.planning.Host;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;

/** Applies a real vanilla tag-update packet and checks stale binding lifetimes. */
final class LifecycleAudit {
  static void run(AuditProbe probe, Minecraft minecraft, Position position) throws Exception {
    String json =
        """
        {"schema":1,"id":"test:registry_reload","scope":"box(%d,%d,%d,%d,%d,%d)",
         "select":{"where":"all()"},"target":{"clear":true},"policy":{"break":"deny"}}
        """
            .formatted(
                position.x(), position.y(), position.z(), position.x(), position.y(), position.z());
    var stale = new ClientJob(minecraft, new Host.SessionId(), json, null, Map.of());
    stale.pause();
    var controller = LatticiumClient.get();
    controller.startInline(minecraft, json);
    controller.pause();
    probe.check(controller.uiState().job() != null, "Registry fixture did not start");
    var before = ClientRegistryState.epochs();
    minecraft.player.connection.handleUpdateTags(new ClientboundUpdateTagsPacket(Map.of()));
    probe.check(
        ClientRegistryState.epochs().registry() == before.registry() + 1,
        "Tag packet did not advance registry revision");
    probe.check(
        controller.uiState().job() == null,
        "Old registry-bound controller job survived tag update");
    stale.resume();
    stale.tick();
    probe.check(
        stale.snapshot().submittedActions() == 0 && stale.isSettled(),
        "Old public ClientJob submitted or retained work after tag update");
    stale.leaveWorld();
    var exiting = new ClientJob(minecraft, new Host.SessionId(), json, null, Map.of());
    var level = minecraft.level;
    try {
      minecraft.level = null;
      exiting.tick();
    } finally {
      minecraft.level = level;
    }
    probe.check(
        exiting.isSettled() && exiting.snapshot().submittedActions() == 0,
        "Independently held job did not terminate on world exit");
    var session = new Host.SessionId();
    var gateway =
        new MinecraftActionGateway(
            minecraft, session, ClientRegistryState::epochs, null, null, Map.of());
    var current =
        MinecraftStateCodec.state(
            level.getBlockState(
                new net.minecraft.core.BlockPos(position.x(), position.y(), position.z())));
    var proposal =
        new io.github.billstark001.latticium.planning.Planner.Proposal(
            io.github.billstark001.latticium.planning.Planner.Action.BREAK,
            new BlockState(ResourceId.parse("air"), Map.of()),
            java.util.Set.of(position),
            new io.github.billstark001.latticium.planning.Planner.Cost(1, 0, 1),
            "test:closed");
    var beforeSubmit =
        new Host.Preconditions(
            session, ClientRegistryState.epochs(), position, current, new TargetCell.Clear());
    var policy = new io.github.billstark001.latticium.planning.ProfileReader().read(json).policy();
    gateway.cancelFurtherWork();
    var rejected = gateway.submit(proposal, beforeSubmit, policy);
    probe.check(
        rejected instanceof Host.Submission.Rejected r && r.reason().contains("cancelled"),
        "Cancelled gateway did not reject submission at the capability boundary");
    var closed =
        new MinecraftActionGateway(
            minecraft, session, ClientRegistryState::epochs, null, null, Map.of());
    closed.close();
    rejected = closed.submit(proposal, beforeSubmit, policy);
    probe.check(
        rejected instanceof Host.Submission.Rejected r && r.reason().contains("closed"),
        "Closed gateway accepted another submission");
    probe.pass(
        "independently held job terminates on world exit; cancelled and closed gateways reject further submissions");
    probe.pass(
        "vanilla tag-update packet advances registry revision, removes controller job and cancels an independently held stale job before submission");
  }
}

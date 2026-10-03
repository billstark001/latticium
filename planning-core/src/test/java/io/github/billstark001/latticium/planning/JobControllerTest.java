package io.github.billstark001.latticium.planning;

import io.github.billstark001.latticium.dsl.Model.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class JobControllerTest {
    @Test void acceptedActionNeedsObservationAndCancellationSettlesReceipt() {
        var session=new Host.SessionId();var pos=new Position(ResourceId.parse("minecraft:overworld"),0,0,0);
        var stone=new BlockState(ResourceId.parse("minecraft:stone"),Map.of());
        var step=new Planner.Proposal(Planner.Action.PLACE,stone,Set.of(pos),new Planner.Cost(1,1,0),"test");
        var controller=new JobController(session,new Profile.Policy(Profile.Policy.BreakMode.DENY,1,8),pos,new TargetCell.Exact(stone),new Planner.Result.Ready(List.of(step),step.cost()));
        var snapshot=new Host.Snapshot(session,new Host.Epochs(1,1,1,1,1,1),null);
        controller.submit(snapshot,new BlockState(ResourceId.parse("minecraft:air"),Map.of()),(s,p,policy)->new Host.Submission.Accepted(new Host.Receipt(UUID.randomUUID(),session)));
        assertEquals(JobController.Status.WAITING,controller.status());
        assertEquals(0,controller.confirmedSteps());
        controller.cancel();assertEquals(JobController.Status.WAITING,controller.status());
        controller.observe((receipt,expected)->Host.Observation.CONFIRMED);
        assertEquals(1,controller.confirmedSteps());
        assertEquals(JobController.Status.CANCELLED,controller.status());
    }
    @Test void staleSessionNeverSubmits() {
        var session=new Host.SessionId();var pos=new Position(ResourceId.parse("minecraft:overworld"),0,0,0);
        var stone=new BlockState(ResourceId.parse("minecraft:stone"),Map.of());
        var step=new Planner.Proposal(Planner.Action.PLACE,stone,Set.of(pos),new Planner.Cost(1,1,0),"test");
        var controller=new JobController(session,new Profile.Policy(Profile.Policy.BreakMode.DENY,1,8),pos,new TargetCell.Exact(stone),new Planner.Result.Ready(List.of(step),step.cost()));
        controller.submit(new Host.Snapshot(new Host.SessionId(),new Host.Epochs(1,1,1,1,1,1),null),stone,(s,p,policy)->fail("Gateway must not run"));
        assertEquals(JobController.Status.BLOCKED,controller.status());
    }
}

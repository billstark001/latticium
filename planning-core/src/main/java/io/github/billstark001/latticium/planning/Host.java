package io.github.billstark001.latticium.planning;

import io.github.billstark001.latticium.dsl.Model.*;
import java.util.List;
import java.util.UUID;

/** Neutral contracts implemented by a future client adapter or a fake offline host. */
public final class Host {
    private Host() {}
    public record SessionId(UUID value) {public SessionId(){this(UUID.randomUUID());}}
    public record Epochs(long registry,long world,long target,long selection,long inventory,long rules) {}
    public record Snapshot(SessionId session,Epochs epochs,Facts facts) {}
    public sealed interface Capture permits Capture.Ready,Capture.Unloaded,Capture.Deferred,Capture.Unsupported {
        record Ready(Snapshot snapshot) implements Capture {}
        record Unloaded() implements Capture {}
        record Deferred(String reason) implements Capture {}
        record Unsupported(String fact) implements Capture {}
    }
    public interface SectionSnapshotSource {Capture capture(SectionScanner.SectionKey key,int halo);}
    public interface SelectionSource {List<SectionScanner.Bounds> finiteBounds(String selectionId,SessionId session);}
    public interface TargetSource {TargetCell target(Position pos,SessionId session);}
    public record Preconditions(SessionId session,Epochs epochs,Position position,BlockState expectedCurrent,TargetCell expectedTarget) {}
    public record Receipt(UUID id,SessionId session) {}
    public sealed interface Submission permits Submission.Accepted,Submission.Stale,Submission.Deferred,Submission.Rejected {
        record Accepted(Receipt receipt) implements Submission {}
        record Stale(String reason) implements Submission {}
        record Deferred(String reason) implements Submission {}
        record Rejected(String reason) implements Submission {}
    }
    public interface ActionGateway {Submission submit(Planner.Proposal step,Preconditions preconditions,Profile.Policy policy);}
    public enum Observation { CONFIRMED, STILL_PENDING, CONTRADICTED, TIMED_OUT }
    public interface ObservationSource {Observation observe(Receipt receipt,BlockState expected);}
}

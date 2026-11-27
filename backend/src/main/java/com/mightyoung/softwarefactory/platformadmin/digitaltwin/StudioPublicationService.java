package com.mightyoung.softwarefactory.platformadmin.digitaltwin;

import java.util.Objects;
import java.util.function.Function;

import static com.mightyoung.softwarefactory.platformadmin.digitaltwin.StudioPublicationPolicy.*;

/** Dormant publication command; only a trusted caller and atomic port may invoke it. */
public final class StudioPublicationService {
    private final Transaction transaction;

    public StudioPublicationService(Transaction transaction) {
        this.transaction = Objects.requireNonNull(transaction);
    }

    /**
     * Run the callback once atomically; do not return its decision before commit.
     * The adapter must roll back all effects on error and must serialize the target's
     * first publication even when it has no saved head.
     */
    @FunctionalInterface
    public interface Transaction {
        Decision execute(Function<PublicationUnit, Decision> command);
    }

    public interface PublicationUnit {
        Head lockHead(Target target);
        void append(Snapshot snapshot);
        void audit(Caller caller, Snapshot snapshot);
    }

    /** A version captures the validated bytes, digest and metrics. */
    public static final class Snapshot {
        private final Target target;
        private final long version;
        private final String canonicalSha256;
        private final byte[] canonicalBytes;
        private final Metrics metrics;

        private Snapshot(Target target, Decision decision, StudioDraftDecoder.Decoded draft) {
            this.target = target;
            this.version = decision.version();
            this.canonicalSha256 = decision.canonicalSha256();
            this.canonicalBytes = draft.canonicalBytes();
            this.metrics = decision.metrics();
        }

        public Target target() { return target; }
        public long version() { return version; }
        public String canonicalSha256() { return canonicalSha256; }
        public byte[] canonicalBytes() { return canonicalBytes.clone(); }
        public Metrics metrics() { return metrics; }
    }

    public Decision publish(Caller caller, Target target, long expectedVersion, byte[] source) {
        var draft = prepare(caller, target, expectedVersion, source);
        return transaction.execute(unit -> apply(caller, target, expectedVersion, draft, unit));
    }

    static Decision stage(Caller caller, Target target, long expectedVersion, byte[] source, PublicationUnit unit) {
        return apply(caller, target, expectedVersion, prepare(caller, target, expectedVersion, source), unit);
    }

    private static StudioDraftDecoder.Decoded prepare(Caller caller, Target target, long expectedVersion, byte[] source) {
        authorizePublish(caller, target);
        if (expectedVersion < 0) throw new IllegalArgumentException("Negative expected version");
        var draft = StudioDraftDecoder.decode(source);
        if (!target.screenKey().equals(draft.facts().screenKey()))
            throw new IllegalArgumentException("Draft screen does not match publication target");
        return draft;
    }

    private static Decision apply(Caller caller, Target target, long expectedVersion,
                                  StudioDraftDecoder.Decoded draft, PublicationUnit unit) {
        var decision = decide(caller, target, expectedVersion, unit.lockHead(target), draft.facts());
        if (decision.outcome() == Outcome.APPEND) {
            var snapshot = new Snapshot(target, decision, draft);
            unit.append(snapshot);
            unit.audit(caller, snapshot);
        }
        return decision;
    }
}

package com.mightyoung.softwarefactory.files;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.mightyoung.softwarefactory.files.FileIngestionContracts.*;

/** Opaque live native slot ownership. Public values cannot construct or reactivate a lease. */
public final class PreparedSlotLease implements AutoCloseable {
    interface Session {
        /** Memory only: no process poll, filesystem access, waits, or SQL. */
        boolean locallyLive();
        /** Bounded outside-transaction STOP/quiescence; unknown ownership stays native-owned. */
        void stop();
    }
    private final Object owner;
    private final ScanPlan plan;
    private final Session session;
    private final AtomicBoolean closed=new AtomicBoolean();
    PreparedSlotLease(Object owner,ScanPlan plan,Session session) {
        this.owner=Objects.requireNonNull(owner);this.plan=Objects.requireNonNull(plan);this.session=Objects.requireNonNull(session);
    }
    public boolean isValidFor(ScanPlan candidate) {
        return !closed.get()&&plan.equals(candidate)&&session.locallyLive();
    }
    boolean ownedBy(Object expected,RecordedExecution execution) {
        var d=execution.descriptor();
        return owner==expected&&isValidFor(plan)&&d.executionId().equals(plan.plannedExecutionId())
            &&d.tenantId().equals(plan.tenantId())&&d.intentId().equals(plan.intentId())&&d.fileId().equals(plan.fileId())
            &&d.sourceAttemptId().equals(plan.sourceAttemptId())&&d.scanAttemptId().equals(plan.scanAttemptId())
            &&d.quarantineAllocationId().equals(plan.quarantineAllocationId())&&d.scratchAllocationId().equals(plan.scratchAllocationId())
            &&d.physicalResourceRealmId().equals(plan.physicalResourceRealmId())&&d.slotId().equals(plan.slotId())
            &&d.profileSha256().equals(plan.profileSha256())&&d.claimEpoch()==plan.plannedClaimEpoch()
            &&d.claimTechnicalVersion()==Math.addExact(plan.expectedOldTechnicalVersion(),1);
    }
    Session session(Object expected) {if(owner!=expected||closed.get())throw new FileEvidenceTrustUnavailableException();return session;}
    @Override public void close() {
        LocalPrivateUploadProducer.noTransaction();
        if(closed.compareAndSet(false,true))session.stop();
    }
    @Override public String toString(){return "PreparedSlotLease[executionId="+plan.plannedExecutionId()+",closed="+closed.get()+"]";}
}

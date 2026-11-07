package com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload;

import com.mightyoung.softwarefactory.dualprevention.FindingNotVisibleException;
import com.mightyoung.softwarefactory.files.FileEvidenceTrustUnavailableException;
import com.mightyoung.softwarefactory.files.UploadProducerReadiness;
import com.mightyoung.softwarefactory.identity.SessionContextResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.UUID;
import static com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceUploadContracts.*;
import static com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceUploadException.Reason.*;

/** Durable reservations only: no bytes, scan claims, files metadata or workflow transitions. */
@Service
public final class FindingEvidenceUploadService {
    private final SessionContextResolver sessions;
    private final UploadProducerReadiness producer;
    private final FindingEvidenceUploadRepository repository;
    private final FindingEvidenceUploadReadRepository reads;
    private final FindingEvidenceUploadAccessPolicy access;
    public FindingEvidenceUploadService(SessionContextResolver sessions, UploadProducerReadiness producer,
            FindingEvidenceUploadRepository repository, FindingEvidenceUploadReadRepository reads,
            FindingEvidenceUploadAccessPolicy access) {
        this.sessions=sessions; this.producer=producer; this.repository=repository; this.reads=reads; this.access=access;
    }
    public Reservation reserve(ReserveCommand command) {
        requireIndependentTransaction();
        var owner = sessions.currentTenant();
        var capability = producer.requireReady(); // Actual resource probing is outside the guarded transaction.
        if (command.declaredSizeBytes() > capability.maxBytes()) {
            throw new FindingEvidenceUploadException(DECLARED_SIZE_EXCEEDED);
        }
        if (!capability.allowedMediaTypes().contains(command.declaredMediaType())) {
            throw new FindingEvidenceUploadException(MEDIA_TYPE_NOT_ALLOWED);
        }
        return repository.inTenantTransaction(owner.tenantId(), tx -> {
            var finding = tx.finding(command.findingId()).orElseThrow(FindingNotVisibleException::new);
            var notice = tx.notice(command.findingId(), command.roundNo()).orElseThrow(FindingNotVisibleException::new);
            var set = tx.evidenceSet(command.evidenceSetId()).orElseThrow(FindingNotVisibleException::new);
            access.requireCurrent(owner, finding, command.roundNo(), notice, set);
            String hash = command.fingerprint(owner, notice.id());
            var existing = tx.existing(owner.actorId(), command.idempotencyKey());
            Reservation result;
            if (existing.isPresent()) {
                var intent=existing.get();
                if (!hash.equals(intent.payloadSha256())) throw new FindingEvidenceUploadException(IDEMPOTENCY_CONFLICT);
                result=new Reservation(intent.status(tx.now()), true);
            } else {
                if (finding.version()!=command.expectedFindingVersion()) {
                    throw new FindingEvidenceUploadException(VERSION_CONFLICT);
                }
                if (set.revision()!=command.expectedEvidenceRevision()) {
                    throw new FindingEvidenceUploadException(EVIDENCE_REVISION_CONFLICT);
                }
                var quota=tx.quota(owner.memberId(),set.id());
                if (quota.ownerCount()>=capability.ownerPendingCount() || quota.tenantCount()>=capability.tenantPendingCount()
                        || quota.setCount()>=capability.maxItemsPerSet()
                        || quota.ownerBytes()>capability.ownerPendingBytes()-command.declaredSizeBytes()
                        || quota.tenantBytes()>capability.tenantPendingBytes()-command.declaredSizeBytes()) {
                    throw new FindingEvidenceUploadException(QUOTA_EXCEEDED);
                }
                var intent=tx.insert(owner,command,notice,hash,capability);
                result=new Reservation(intent.intent().status(tx.now()),!intent.inserted());
            }
            if (!producer.isCurrent(capability)) throw new FileEvidenceTrustUnavailableException();
            return result;
        }); // TransactionTemplate commits before this method returns, including replay.
    }
    public Status status(UUID findingId,int roundNo,UUID intentId) {
        requireIndependentTransaction();
        var owner=sessions.currentTenant();
        try {
            return reads.inTenantReadSnapshot(owner.tenantId(), snapshot -> {
                var finding=snapshot.finding(findingId).orElseThrow(FindingNotVisibleException::new);
                access.requireAction(owner, finding);
                var notice=snapshot.notice(findingId,roundNo).orElseThrow(FindingNotVisibleException::new);
                var intent=snapshot.intent(intentId).orElseThrow(FindingNotVisibleException::new);
                if (!owner.accountId().equals(intent.ownerAccountId()) || !owner.actorId().equals(intent.ownerActorId())
                        || !owner.memberId().equals(intent.ownerMemberId()) || !findingId.equals(intent.findingId())
                        || roundNo!=intent.roundNo() || !notice.id().equals(intent.noticeId())) throw new FindingNotVisibleException();
                var set=snapshot.evidenceSet(intent.evidenceSetId()).orElseThrow(FindingNotVisibleException::new);
                access.requireOwnedContext(owner,finding,roundNo,notice,set);
                return intent.status(snapshot.now());
            });
        } catch (org.springframework.security.access.AccessDeniedException denied) {
            throw new FindingNotVisibleException();
        }
    }
    static void requireIndependentTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.getResourceMap().isEmpty()) {
            throw new IllegalStateException("Evidence uploads require their own managed transaction");
        }
    }
}

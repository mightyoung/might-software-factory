package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.dualprevention.finding.FindingReadScope;
import com.mightyoung.softwarefactory.files.FindingEvidenceContentReadRepository;
import com.mightyoung.softwarefactory.files.FindingEvidenceFileAccess;
import com.mightyoung.softwarefactory.files.FindingEvidenceReadScope;
import com.mightyoung.softwarefactory.files.FileEvidenceTrustUnavailableException;
import com.mightyoung.softwarefactory.files.VerifiedFileContent;
import com.mightyoung.softwarefactory.files.VerifiedStorageVersionReadPort;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import com.mightyoung.softwarefactory.identity.SessionContextResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Authorizes and opens the exact file version fixed by a sealed remediation-submission snapshot. */
@Service
public final class FindingEvidenceContentReadService implements FindingEvidenceContentReader {

    private final SessionContextResolver sessionContextResolver;
    private final FindingReadAuthorizer readAuthorizer;
    private final FindingEvidenceContentReadRepository snapshotRepository;
    private final FindingEvidenceFileAccess fileAccess;
    private final VerifiedStorageVersionReadPort contentReader;
    private final TransactionTemplate readTransaction;

    public FindingEvidenceContentReadService(
            SessionContextResolver sessionContextResolver,
            FindingReadAuthorizer readAuthorizer,
            FindingEvidenceContentReadRepository snapshotRepository,
            FindingEvidenceFileAccess fileAccess,
            VerifiedStorageVersionReadPort contentReader,
            ObjectProvider<PlatformTransactionManager> transactionManagerProvider) {
        this.sessionContextResolver = sessionContextResolver;
        this.readAuthorizer = readAuthorizer;
        this.snapshotRepository = snapshotRepository;
        this.fileAccess = fileAccess;
        this.contentReader = contentReader;
        PlatformTransactionManager transactionManager = transactionManagerProvider.getIfAvailable();
        if (transactionManager == null) {
            this.readTransaction = null;
        } else {
            this.readTransaction = new TransactionTemplate(transactionManager);
            // The repository takes short-lived shared row locks so a concurrent link revocation or
            // file-state change cannot slip between authorization and opening the pinned bytes.
            this.readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        }
    }

    @Override
    public VerifiedFileContent openSubmittedEvidence(UUID findingId, int roundNo, UUID evidenceItemId) {
        if (findingId == null || roundNo < 1 || evidenceItemId == null) {
            throw new FindingNotVisibleException();
        }
        return withinReadTransaction(() -> {
            CurrentTenantContext context = sessionContextResolver.currentTenant();
            FindingReadScope findingScope = readAuthorizer.requireReadScope(context);
            FindingEvidenceReadScope fileScope = new FindingEvidenceReadScope(
                    findingScope.tenantId(), findingScope.organizationIds());
            var snapshot = snapshotRepository.findReadableSealedSnapshot(
                            fileScope, findingId, roundNo, evidenceItemId)
                    .orElseThrow(FindingNotVisibleException::new);
            fileAccess.requirePreviouslySubmittedEvidence(snapshot);
            return contentReader.openVerifiedVersion(snapshot);
        });
    }

    private <T> T withinReadTransaction(Supplier<T> operation) {
        if (readTransaction == null) throw new FileEvidenceTrustUnavailableException();
        return Objects.requireNonNull(readTransaction.execute(status -> operation.get()));
    }
}

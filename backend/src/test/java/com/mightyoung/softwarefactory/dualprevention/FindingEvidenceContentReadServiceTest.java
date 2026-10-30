package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.authorization.EntityReadAccessPolicy;
import com.mightyoung.softwarefactory.authorization.EntityReadGrant;
import com.mightyoung.softwarefactory.files.ContentVerificationReceipt;
import com.mightyoung.softwarefactory.files.FileEvidenceTrustUnavailableException;
import com.mightyoung.softwarefactory.files.FindingEvidenceContentReadRepository;
import com.mightyoung.softwarefactory.files.FindingEvidenceFileAccess;
import com.mightyoung.softwarefactory.files.FindingEvidenceReadScope;
import com.mightyoung.softwarefactory.files.FileEvidenceVerificationLevel;
import com.mightyoung.softwarefactory.files.VerifiedFileContent;
import com.mightyoung.softwarefactory.files.VerifiedFileEvidenceSnapshot;
import com.mightyoung.softwarefactory.files.VerifiedStorageVersionReadPort;
import com.mightyoung.softwarefactory.identity.SessionContextResolver;
import com.mightyoung.softwarefactory.identity.UnauthenticatedSessionException;
import com.mightyoung.softwarefactory.identity.VerifiedSessionPrincipalTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FindingEvidenceContentReadServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_TENANT_ID = UUID.fromString("30000000-0000-0000-0000-000000000002");
    private static final UUID ACTOR_ID = UUID.fromString("10000000-0000-0000-0000-000000000004");
    private static final UUID MEMBER_ID = UUID.fromString("20000000-0000-0000-0000-000000000004");
    private static final UUID FINDING_ID = UUID.fromString("40000000-0000-0000-0000-000000000002");
    private static final UUID ITEM_ID = UUID.fromString("51000000-0000-0000-0000-000000000002");
    private static final UUID FILE_ID = UUID.fromString("52000000-0000-0000-0000-000000000002");
    private static final UUID LINK_ID = UUID.fromString("53000000-0000-0000-0000-000000000002");

    private final TestAccessPolicy accessPolicy = new TestAccessPolicy();
    private final TestSnapshotRepository snapshotRepository = new TestSnapshotRepository();
    private final TestFileAccess fileAccess = new TestFileAccess();
    private final TestContentReader contentReader = new TestContentReader();
    private FindingEvidenceContentReadService service;

    @BeforeEach
    void setUp() {
        accessPolicy.grant = Optional.of(new EntityReadGrant(TENANT_ID, Set.of(UUID.randomUUID())));
        snapshotRepository.snapshot = Optional.of(snapshot());
        fileAccess.failure = null;
        contentReader.calls.set(0);
        service = new FindingEvidenceContentReadService(new SessionContextResolver(),
                new FindingReadAuthorizer(accessPolicy), snapshotRepository, fileAccess, contentReader,
                new StaticListableBeanFactory(java.util.Map.of(
                        "testTransactionManager", new TestTransactionManager()))
                        .getBeanProvider(PlatformTransactionManager.class));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                VerifiedSessionPrincipalTestFixture.create(ACTOR_ID, TENANT_ID, MEMBER_ID), "",
                List.of(new SimpleGrantedAuthority("AUTHENTICATED"))));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void currentFindingPermissionAndVisibleSealedSnapshotAreRequiredBeforePinnedStorageRead() throws Exception {
        VerifiedFileContent content = service.openSubmittedEvidence(FINDING_ID, 2, ITEM_ID);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (content) {
            content.transferTo(output);
        }

        assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo("synthetic pinned bytes");
        assertThat(snapshotRepository.scope.tenantId()).isEqualTo(TENANT_ID);
        assertThat(snapshotRepository.scope.organizationIds()).isEqualTo(accessPolicy.grant.orElseThrow()
                .organizationIds());
        assertThat(fileAccess.checked).isSameAs(snapshotRepository.snapshot.orElseThrow());
        assertThat(contentReader.snapshot).isSameAs(snapshotRepository.snapshot.orElseThrow());
    }

    @Test
    void revokedPermissionIsRecheckedForEveryReadAndPreventsSnapshotLookup() {
        accessPolicy.grant = Optional.empty();

        assertThatThrownBy(() -> service.openSubmittedEvidence(FINDING_ID, 2, ITEM_ID))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(snapshotRepository.calls).isZero();
        assertThat(contentReader.calls).hasValue(0);
    }

    @Test
    void aMissingVerifiedSessionCannotResolveAnyEvidenceSnapshot() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> service.openSubmittedEvidence(FINDING_ID, 2, ITEM_ID))
                .isInstanceOf(UnauthenticatedSessionException.class);

        assertThat(snapshotRepository.calls).isZero();
        assertThat(contentReader.calls).hasValue(0);
    }

    @Test
    void missingTransactionManagerFailsClosedBeforeQueryingTheSnapshot() {
        service = new FindingEvidenceContentReadService(new SessionContextResolver(),
                new FindingReadAuthorizer(accessPolicy), snapshotRepository, fileAccess, contentReader,
                new StaticListableBeanFactory().getBeanProvider(PlatformTransactionManager.class));

        assertThatThrownBy(() -> service.openSubmittedEvidence(FINDING_ID, 2, ITEM_ID))
                .isInstanceOf(FileEvidenceTrustUnavailableException.class);

        assertThat(snapshotRepository.calls).isZero();
        assertThat(contentReader.calls).hasValue(0);
    }

    @Test
    void anotherTenantOrRevokedLinkCannotOpenTheSubmittedFile() {
        accessPolicy.grant = Optional.of(new EntityReadGrant(OTHER_TENANT_ID, Set.of(UUID.randomUUID())));
        assertThatThrownBy(() -> service.openSubmittedEvidence(FINDING_ID, 2, ITEM_ID))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(snapshotRepository.calls).isZero();

        accessPolicy.grant = Optional.of(new EntityReadGrant(TENANT_ID, Set.of(UUID.randomUUID())));
        snapshotRepository.snapshot = Optional.empty(); // The current link is revoked or the snapshot is not sealed.
        assertThatThrownBy(() -> service.openSubmittedEvidence(FINDING_ID, 2, ITEM_ID))
                .isInstanceOf(FindingNotVisibleException.class);
        assertThat(contentReader.calls).hasValue(0);
    }

    @Test
    void receiptMismatchFailsBeforeOpeningStorageContent() {
        fileAccess.failure = new FileEvidenceTrustUnavailableException();

        assertThatThrownBy(() -> service.openSubmittedEvidence(FINDING_ID, 2, ITEM_ID))
                .isInstanceOf(FileEvidenceTrustUnavailableException.class);

        assertThat(contentReader.calls).hasValue(0);
    }

    private static VerifiedFileEvidenceSnapshot snapshot() {
        return new VerifiedFileEvidenceSnapshot(TENANT_ID, ITEM_ID, FILE_ID, LINK_ID, MEMBER_ID, ACTOR_ID,
                "a".repeat(64), "disk-v1", UUID.randomUUID(), "server-verifier", "key-1", "content-v1",
                FileEvidenceVerificationLevel.CONTENT, OffsetDateTime.parse("2026-10-30T10:00:00Z"));
    }

    private static final class TestAccessPolicy implements EntityReadAccessPolicy {
        private Optional<EntityReadGrant> grant = Optional.empty();

        @Override
        public Optional<EntityReadGrant> resolveEntityReadGrant(
                com.mightyoung.softwarefactory.identity.CurrentTenantContext context, String entityKey) {
            return grant;
        }
    }

    private static final class TestSnapshotRepository implements FindingEvidenceContentReadRepository {
        private Optional<VerifiedFileEvidenceSnapshot> snapshot = Optional.empty();
        private int calls;
        private FindingEvidenceReadScope scope;

        @Override
        public Optional<VerifiedFileEvidenceSnapshot> findReadableSealedSnapshot(
                FindingEvidenceReadScope scope, UUID findingId, int roundNo, UUID evidenceItemId) {
            calls++;
            this.scope = scope;
            return snapshot;
        }
    }

    private static final class TestFileAccess implements FindingEvidenceFileAccess {
        private VerifiedFileEvidenceSnapshot checked;
        private RuntimeException failure;

        @Override
        public VerifiedFileEvidenceSnapshot requireVerifiedContentEvidence(
                com.mightyoung.softwarefactory.files.EvidenceFileUse use) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void requirePreviouslySubmittedEvidence(VerifiedFileEvidenceSnapshot snapshot) {
            checked = snapshot;
            if (failure != null) throw failure;
        }
    }

    private static final class TestContentReader implements VerifiedStorageVersionReadPort {
        private final AtomicInteger calls = new AtomicInteger();
        private VerifiedFileEvidenceSnapshot snapshot;

        @Override
        public VerifiedFileContent openVerifiedVersion(VerifiedFileEvidenceSnapshot snapshot) {
            calls.incrementAndGet();
            this.snapshot = snapshot;
            return new VerifiedFileContent() {
                private boolean closed;

                @Override public long sizeBytes() { return "synthetic pinned bytes".length(); }
                @Override public void transferTo(java.io.OutputStream output) throws IOException {
                    output.write("synthetic pinned bytes".getBytes(StandardCharsets.UTF_8));
                }
                @Override public void close() { closed = true; }
            };
        }
    }

    private static final class TestTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) { }

        @Override
        public void rollback(TransactionStatus status) { }
    }
}

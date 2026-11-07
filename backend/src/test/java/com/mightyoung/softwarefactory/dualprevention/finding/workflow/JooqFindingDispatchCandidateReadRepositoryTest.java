package com.mightyoung.softwarefactory.dualprevention.finding.workflow;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.using;

class JooqFindingDispatchCandidateReadRepositoryTest {
    private static final UUID TENANT = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID FINDING = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID ORGANIZATION = UUID.fromString("31000000-0000-0000-0000-000000000001");

    @AfterEach
    void clearThreadTransactionState() {
        TransactionSynchronizationManager.getResourceMap().keySet().forEach(
                TransactionSynchronizationManager::unbindResource);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.clear();
    }

    @Test
    void tenantAndDeadlineAreLocalBeforeReadingFindingAndNoticeInReadOnlyRepeatableRead() {
        Fixture fixture = new Fixture(Duration.ofSeconds(2));
        var finding = fixture.repository.inTenantReadSnapshot(TENANT, snapshot -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                    .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            assertThat(snapshot.hasNotice(FINDING, 3)).isTrue();
            return snapshot.find(FINDING).orElseThrow();
        });
        assertThat(finding).isEqualTo(new FindingDispatchCandidateReadRepository.Finding(
                FINDING, ORGANIZATION, FindingWorkflowState.RECTIFYING, 3, 19));
        assertThat(fixture.manager.readOnly).isTrue();
        assertThat(fixture.manager.isolation).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThat(fixture.manager.timeout).isEqualTo(5);
        assertThat(fixture.manager.commits).isEqualTo(1);
        assertThat(fixture.manager.rollbacks).isZero();
        assertThat(fixture.statements).hasSize(4);
        assertThat(fixture.statements.get(0)).isEqualTo("select set_config('app.tenant_id', ?, true)");
        assertThat(fixture.bindings.get(0)).containsExactly(TENANT.toString());
        assertThat(fixture.statements.get(1))
                .isEqualTo("select set_config('statement_timeout', ?, true) is not null");
        assertThat(fixture.bindings.get(1)).containsExactly("2000");
        assertThat(fixture.statements.get(2)).contains("public.remediation_notice",
                "tenant_id=cast(? as uuid)", "finding_id=cast(? as uuid)", "round_no=?");
        assertThat(fixture.bindings.get(2)).containsExactly(TENANT, FINDING, 3);
        assertThat(fixture.statements.get(3)).contains("id, organization_id, state, round_no, version",
                "public.finding", "tenant_id=cast(? as uuid)", "id=cast(? as uuid)");
        assertThat(fixture.bindings.get(3)).containsExactly(TENANT, FINDING);
        assertThat(String.join("\n", fixture.statements).toLowerCase(java.util.Locale.ROOT))
                .doesNotContain("for update", "advisory", "lock table");
        assertCleanTransactionState();
    }

    @Test
    void snapshotCannotBeUsedAfterCallbackReturns() {
        Fixture fixture = new Fixture(Duration.ofMillis(1));
        var borrowed = fixture.repository.inTenantReadSnapshot(TENANT, snapshot -> snapshot);
        assertThatIllegalStateException().isThrownBy(() -> borrowed.find(FINDING));
        assertThatIllegalStateException().isThrownBy(() -> borrowed.hasNotice(FINDING, 3));
        assertThat(fixture.statements).hasSize(2);
        assertThat(fixture.bindings.get(1)).containsExactly("1");
    }

    @Test
    void callbackFailureClosesSnapshotRollsBackAndCleansTransactionState() {
        Fixture fixture = new Fixture(Duration.ofSeconds(5));
        AtomicReference<FindingDispatchCandidateReadRepository.Snapshot> borrowed = new AtomicReference<>();
        IllegalArgumentException failure = new IllegalArgumentException("callback failed");
        assertThatThrownBy(() -> fixture.repository.inTenantReadSnapshot(TENANT, snapshot -> {
            borrowed.set(snapshot);
            throw failure;
        })).isSameAs(failure);
        assertThatIllegalStateException().isThrownBy(() -> borrowed.get().find(FINDING));
        assertThat(fixture.manager.commits).isZero();
        assertThat(fixture.manager.rollbacks).isEqualTo(1);
        assertThat(fixture.bindings.get(1)).containsExactly("5000");
        assertCleanTransactionState();
    }

    @Test
    void rejectsEachAmbientTransactionSurfaceBeforeBeginningOrExecutingSql() {
        Fixture fixture = new Fixture(Duration.ofSeconds(2));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertAmbientRejected(fixture);
        TransactionSynchronizationManager.setActualTransactionActive(false);
        TransactionSynchronizationManager.initSynchronization();
        assertAmbientRejected(fixture);
        TransactionSynchronizationManager.clearSynchronization();
        Object resourceKey = new Object();
        TransactionSynchronizationManager.bindResource(resourceKey, new Object());
        assertAmbientRejected(fixture);
        TransactionSynchronizationManager.unbindResource(resourceKey);
        assertThat(fixture.manager.begins).isZero();
        assertThat(fixture.statements).isEmpty();
    }

    @Test
    void rejectsWrongThreadAndLostReadOnlyOrIsolationBeforeReading() {
        Fixture fixture = new Fixture(Duration.ofSeconds(2));
        fixture.repository.inTenantReadSnapshot(TENANT, snapshot -> {
            AtomicReference<Throwable> wrongThreadFailure = new AtomicReference<>();
            Thread other = new Thread(() -> {
                try {
                    snapshot.find(FINDING);
                } catch (Throwable failure) {
                    wrongThreadFailure.set(failure);
                }
            });
            other.start();
            try {
                other.join(5000);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            }
            assertThat(other.isAlive()).isFalse();
            assertThat(wrongThreadFailure.get()).isInstanceOf(IllegalStateException.class);
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
            assertThatIllegalStateException().isThrownBy(() -> snapshot.find(FINDING));
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
            TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
                    TransactionDefinition.ISOLATION_READ_COMMITTED);
            assertThatIllegalStateException().isThrownBy(() -> snapshot.hasNotice(FINDING, 3));
            TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
                    TransactionDefinition.ISOLATION_REPEATABLE_READ);
            TransactionSynchronizationManager.setActualTransactionActive(false);
            assertThatIllegalStateException().isThrownBy(() -> snapshot.find(FINDING));
            TransactionSynchronizationManager.setActualTransactionActive(true);
            return null;
        });
        assertThat(fixture.statements).hasSize(2);
        assertCleanTransactionState();
    }

    @Test
    void failedTenantOrDeadlineSetupRollsBackWithoutInvokingCallback() {
        for (boolean tenantFailure : List.of(true, false)) {
            Fixture fixture = new Fixture(Duration.ofSeconds(2));
            fixture.acceptTenant = !tenantFailure;
            fixture.acceptDeadline = tenantFailure;
            assertThatIllegalStateException().isThrownBy(() -> fixture.repository.inTenantReadSnapshot(
                    TENANT, snapshot -> { throw new AssertionError("callback must not run"); }));
            assertThat(fixture.statements).hasSize(tenantFailure ? 1 : 2);
            assertThat(fixture.manager.rollbacks).isEqualTo(1);
            assertThat(fixture.manager.commits).isZero();
            assertCleanTransactionState();
        }
    }

    @Test
    void rejectsDeadlineOutsideOneMillisecondToFiveSeconds() {
        for (Duration timeout : List.of(Duration.ZERO, Duration.ofMillis(-1),
                Duration.ofNanos(999999), Duration.ofMillis(5001))) {
            assertThatIllegalArgumentException().isThrownBy(() -> new Fixture(timeout));
        }
    }

    private static void assertAmbientRejected(Fixture fixture) {
        assertThatIllegalStateException().isThrownBy(() -> fixture.repository.inTenantReadSnapshot(
                TENANT, snapshot -> { throw new AssertionError("callback must not run"); }));
    }

    private static void assertCleanTransactionState() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
        assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()).isNull();
        assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
    }

    private static final class Fixture {
        private final List<String> statements = new ArrayList<>();
        private final List<List<Object>> bindings = new ArrayList<>();
        private final RecordingTransactionManager manager = new RecordingTransactionManager();
        private boolean acceptTenant = true;
        private boolean acceptDeadline = true;
        private final JooqFindingDispatchCandidateReadRepository repository;

        private Fixture(Duration timeout) {
            DSLContext values = using(SQLDialect.POSTGRES);
            DSLContext dsl = using(new MockConnection(context -> {
                statements.add(context.sql());
                bindings.add(Arrays.asList(context.bindings()));
                if (context.sql().contains("'app.tenant_id'")) {
                    var column = field("set_config", String.class);
                    var result = values.newResult(column);
                    result.add(values.newRecord(column).value1(acceptTenant ? TENANT.toString() : ""));
                    return new MockResult[] {new MockResult(1, result)};
                }
                if (context.sql().contains("'statement_timeout'")
                        || context.sql().contains("public.remediation_notice")) {
                    var column = field("exists", Boolean.class);
                    var result = values.newResult(column);
                    result.add(values.newRecord(column).value1(acceptDeadline));
                    return new MockResult[] {new MockResult(1, result)};
                }
                var id = field("id", UUID.class);
                var organization = field("organization_id", UUID.class);
                var state = field("state", String.class);
                var round = field("round_no", Integer.class);
                var version = field("version", Long.class);
                var result = values.newResult(id, organization, state, round, version);
                result.add(values.newRecord(id, organization, state, round, version)
                        .values(FINDING, ORGANIZATION, "RECTIFYING", 3, 19L));
                return new MockResult[] {new MockResult(1, result)};
            }), SQLDialect.POSTGRES);
            repository = new JooqFindingDispatchCandidateReadRepository(dsl, manager, timeout);
        }
    }

    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        private int begins;
        private int commits;
        private int rollbacks;
        private boolean readOnly;
        private int isolation;
        private int timeout;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            begins++;
            readOnly = definition.isReadOnly();
            isolation = definition.getIsolationLevel();
            timeout = definition.getTimeout();
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commits++;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbacks++;
        }
    }
}

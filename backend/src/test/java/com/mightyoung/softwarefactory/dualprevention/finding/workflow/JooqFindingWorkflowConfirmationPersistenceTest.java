package com.mightyoung.softwarefactory.dualprevention.finding.workflow;

import com.mightyoung.softwarefactory.authorization.AuthorizationWriteGuard;

import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.Confirmation;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.ConfirmationDecision;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.Transition;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowState;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockExecuteContext;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JooqFindingWorkflowConfirmationPersistenceTest {

    private static final UUID TENANT = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID FINDING = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID MEMBER = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID ACTOR = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID COMMAND = UUID.fromString("60000000-0000-0000-0000-000000000001");
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-31T10:00:00Z");
    private static final String REASON = "Safety review complete";

    @Test
    void writesTheSameOpinionToConfirmationTransitionAndAuditAndCapturesGradeChange() {
        List<CapturedStatement> statements = new ArrayList<>();
        DSLContext values = DSL.using(SQLDialect.POSTGRES);
        MockDataProvider provider = context -> {
            statements.add(new CapturedStatement(context.sql(), new ArrayList<>(Arrays.asList(context.bindings()))));
            if (context.sql().toLowerCase(Locale.ROOT).contains("set_config")) {
                return new MockResult[] {singleValue(values, "set_config", String.class, TENANT.toString())};
            }
            return new MockResult[] {new MockResult(1)};
        };
        DSLContext dsl = DSL.using(new MockConnection(provider), SQLDialect.POSTGRES);
        JooqFindingWorkflowRepository repository = new JooqFindingWorkflowRepository(
                dsl, new NoOpTransactionManager(), org.mockito.Mockito.mock(AuthorizationWriteGuard.class));

        repository.inTenantTransaction(TENANT, tx -> {
            FindingWorkflowCase safetyPending = new FindingWorkflowCase(TENANT, FINDING,
                    UUID.fromString("31000000-0000-0000-0000-000000000001"), "YH-20261031-0001",
                    "Synthetic finding", NOW, null, null, "UNKNOWN",
                    FindingWorkflowState.SAFETY_CONFIRM_PENDING, 1, 2,
                    UUID.fromString("20000000-0000-0000-0000-000000000001"), MEMBER,
                    null, null, null, null, null);
            FindingWorkflowCase safetyConfirmed = safetyPending.confirmBySafety(MEMBER, 2L, "MAJOR");
            tx.updateFinding(safetyConfirmed, 2L);
            tx.appendConfirmation(new Confirmation(FINDING, "SAFETY", MEMBER, ConfirmationDecision.APPROVE,
                    REASON, NOW, 2L, COMMAND));
            tx.appendTransition(new Transition(FINDING, FindingWorkflowState.SAFETY_CONFIRM_PENDING,
                    FindingWorkflowState.SAFETY_DISPATCH_PENDING, COMMAND, ACTOR, NOW, REASON,
                    2L, 3L, "UNKNOWN", "MAJOR"));
            return null;
        });

        CapturedStatement confirmation = statements.stream()
                .filter(statement -> statement.sql().toLowerCase(Locale.ROOT)
                        .contains("insert into public.finding_confirmation"))
                .findFirst().orElseThrow();
        CapturedStatement transition = statements.stream()
                .filter(statement -> statement.sql().toLowerCase(Locale.ROOT)
                        .contains("insert into public.finding_transition"))
                .findFirst().orElseThrow();
        CapturedStatement audit = statements.stream()
                .filter(statement -> statement.sql().toLowerCase(Locale.ROOT)
                        .contains("insert into public.audit_event"))
                .findFirst().orElseThrow();
        CapturedStatement findingUpdate = statements.stream()
                .filter(statement -> statement.sql().toLowerCase(Locale.ROOT).contains("update public.finding"))
                .findFirst().orElseThrow();

        assertThat(findingUpdate.sql().toLowerCase(Locale.ROOT)).contains("set grade = ?");
        assertThat(findingUpdate.bindings()).contains("MAJOR");
        assertThat(confirmation.sql().toLowerCase(Locale.ROOT)).contains("decision, reason, decided_at");
        assertThat(confirmation.bindings()).contains(ConfirmationDecision.APPROVE.name(), REASON);
        assertThat(transition.bindings()).contains(REASON);
        assertThat(audit.sql()).contains("'fromGrade'", "'toGrade'");
        assertThat(audit.bindings()).contains(REASON, "UNKNOWN", "MAJOR");
    }

    @Test
    void legacySuccessfulReceiptConflictsWithTheNewFingerprintWithoutUpdatingReceipt() {
        List<CapturedStatement> statements = new ArrayList<>();
        DSLContext values = DSL.using(SQLDialect.POSTGRES);
        MockDataProvider provider = context -> {
            String sql = context.sql().toLowerCase(Locale.ROOT);
            statements.add(new CapturedStatement(context.sql(), new ArrayList<>(Arrays.asList(context.bindings()))));
            if (sql.contains("set_config")) {
                return new MockResult[] {singleValue(values, "set_config", String.class, TENANT.toString())};
            }
            if (sql.contains("insert into public.finding_command_receipt")) {
                return new MockResult[] {new MockResult(0)};
            }
            if (sql.contains("from public.finding_command_receipt receipt")) {
                Field<UUID> actorId = DSL.field("actor_id", UUID.class);
                Field<String> action = DSL.field("action", String.class);
                Field<String> requestHash = DSL.field("request_hash", String.class);
                Field<UUID> resultFindingId = DSL.field("result_finding_id", UUID.class);
                Field<String> resultCode = DSL.field("result_code", String.class);
                Field<String> resultState = DSL.field("result_state", String.class);
                Field<Integer> resultRoundNo = DSL.field("result_round_no", Integer.class);
                Field<Long> resultVersion = DSL.field("result_version", Long.class);
                var record = values.newRecord(actorId, action, requestHash, resultFindingId,
                        resultCode, resultState, resultRoundNo, resultVersion);
                record.set(actorId, ACTOR);
                record.set(action, "finding.confirm.person");
                record.set(requestHash, "a".repeat(64));
                record.set(resultFindingId, FINDING);
                record.set(resultCode, "YH-20261031-0001");
                record.set(resultState, FindingWorkflowState.SAFETY_CONFIRM_PENDING.name());
                record.set(resultRoundNo, 1);
                record.set(resultVersion, 2L);
                var result = values.newResult(actorId, action, requestHash, resultFindingId,
                        resultCode, resultState, resultRoundNo, resultVersion);
                result.add(record);
                return new MockResult[] {new MockResult(1, result)};
            }
            return new MockResult[] {new MockResult(1)};
        };
        DSLContext dsl = DSL.using(new MockConnection(provider), SQLDialect.POSTGRES);
        JooqFindingWorkflowRepository repository = new JooqFindingWorkflowRepository(
                dsl, new NoOpTransactionManager(), org.mockito.Mockito.mock(AuthorizationWriteGuard.class));

        assertThatThrownBy(() -> repository.inTenantTransaction(TENANT, tx -> tx.claimCommand(
                new FindingWorkflowContracts.WorkflowCommand(COMMAND, ACTOR,
                        "finding.confirm.person", "b".repeat(64)))))
                .isInstanceOf(FindingWorkflowIdempotencyConflictException.class);

        assertThat(statements).noneSatisfy(statement ->
                assertThat(statement.sql().toLowerCase(Locale.ROOT)).contains("update public.finding_command_receipt"));
        assertThat(statements).anySatisfy(statement ->
                assertThat(statement.sql().toLowerCase(Locale.ROOT)).contains("on conflict (tenant_id, command_id)"));
        assertThat(statements).anySatisfy(statement ->
                assertThat(statement.sql().toLowerCase(Locale.ROOT)).contains("for update of receipt"));
    }

    private static <T> MockResult singleValue(DSLContext dsl, String key, Class<T> type, T value) {
        Field<T> field = DSL.field(key, type);
        var result = dsl.newResult(field);
        var row = dsl.newRecord(field);
        row.set(field, value);
        result.add(row);
        return new MockResult(result.size(), result);
    }

    private record CapturedStatement(String sql, List<Object> bindings) {}

    private static final class NoOpTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}

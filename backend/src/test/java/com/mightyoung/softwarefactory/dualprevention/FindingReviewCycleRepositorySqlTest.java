package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.authorization.AuthorizationWriteGuard;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.*;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingReviewCycleContracts.*;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.*;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.OffsetDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Captures the real jOOQ SQL/typed decoding; PostgreSQL execution remains a separate acceptance gate. */
class FindingReviewCycleRepositorySqlTest {
    private static final UUID TENANT = UUID.randomUUID(), FINDING = UUID.randomUUID(), MEMBER = UUID.randomUUID(), COMMAND = UUID.randomUUID();
    private static final OffsetDateTime TIME = OffsetDateTime.parse("2026-11-03T00:00:00Z");

    @Test void persistsEveryCycleHistoryWithTenantBoundIdentifiersAndTrueResultCycle() {
        List<Captured> statements = new ArrayList<>();
        var repository = repository(statements, null);
        repository.inTenantTransaction(TENANT, tx -> {
            tx.appendReviewCycle(new ReviewCycle(FINDING, 3, COMMAND, TIME));
            tx.appendReviewDecision(new Decision(FINDING, 3, ConfirmationStage.SAFETY, MEMBER, ReviewDecision.APPROVE,
                    "Approved", "MAJOR", 7, COMMAND, TIME));
            tx.appendSupplement(new Supplement(UUID.randomUUID(), FINDING, 2, 3, UUID.randomUUID(), MEMBER, "Statement", 6, COMMAND, TIME));
            tx.appendParticipant(new CommandParticipant(COMMAND, FINDING, 3, MEMBER, "SAFETY", "definition", "process", "task", "safetyConfirm", TIME));
            tx.completeCommand(COMMAND, new CommandResult(FINDING, "F1", FindingWorkflowState.CLOSED, 2, 14, 3));
            return null;
        });
        for (String table : List.of("finding_review_cycle", "finding_review_decision", "finding_report_supplement", "finding_command_participant")) {
            Captured write = statements.stream().filter(c -> c.sql().contains("insert into public." + table)).findFirst().orElseThrow();
            assertThat(write.sql()).contains("tenant_id", "cast(? as uuid)", "cast(? as timestamptz)");
            assertThat(write.bindings()).contains(TENANT, FINDING, COMMAND);
        }
        Captured completion = statements.stream().filter(c -> c.sql().contains("update public.finding_command_receipt")).findFirst().orElseThrow();
        assertThat(completion.sql()).contains("result_review_cycle = ?");
        assertThat(completion.bindings()).containsExactly(FINDING, "CLOSED", 2, 14L, 3, TENANT, COMMAND);
    }

    @Test void typedReceiptReplayReturnsItsOriginalCycleWithoutConsultingCurrentFindingCycle() {
        List<Captured> statements = new ArrayList<>();
        var repository = repository(statements, receipt("finding.review.decision", 2));
        var result = repository.inTenantTransaction(TENANT, tx -> tx.readCommand(command("finding.review.decision")).orElseThrow());
        assertThat(result.reviewCycle()).isEqualTo(2);
        assertThat(result.version()).isEqualTo(5);
        Captured read = statements.stream().filter(c -> c.sql().contains("from public.finding_command_receipt receipt")).findFirst().orElseThrow();
        assertThat(read.sql()).contains("receipt.result_review_cycle").doesNotContain("finding.review_cycle", "finding.review_protocol");
        assertThat(statements).noneMatch(c -> c.sql().contains("insert into public.finding_command_receipt"));
    }

    @Test void historicalNullCycleUsesOnlyLegacyNamespaceAndNewCycleNamespacesFailClosed() {
        var legacy = repository(new ArrayList<>(), receipt("finding.create", null));
        assertThat(legacy.inTenantTransaction(TENANT, tx -> tx.readCommand(command("finding.create")).orElseThrow()).reviewCycle()).isEqualTo(1);
        for (String action : List.of("finding.review.decision", "finding.report.supplement")) {
            var modern = repository(new ArrayList<>(), receipt(action, null));
            assertThatThrownBy(() -> modern.inTenantTransaction(TENANT, tx -> tx.readCommand(command(action))))
                    .isInstanceOf(com.mightyoung.softwarefactory.workflow.WorkflowTaskStateConflictException.class);
        }
    }

    @Test void receiptMetadataMismatchIsRejectedBeforeHistoricalResultIsReturned() {
        var repository = repository(new ArrayList<>(), receipt("finding.review.decision", 2));
        assertThatThrownBy(() -> repository.inTenantTransaction(TENANT, tx -> tx.readCommand(new WorkflowCommand(
                COMMAND, MEMBER, "finding.review.decision", "b".repeat(64)))))
                .isInstanceOf(FindingWorkflowIdempotencyConflictException.class);
    }

    private static WorkflowCommand command(String action) { return new WorkflowCommand(COMMAND, MEMBER, action, "a".repeat(64)); }

    private static Map<String, Object> receipt(String action, Integer cycle) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("actor_id", MEMBER); row.put("action", action); row.put("request_hash", "a".repeat(64));
        row.put("result_finding_id", FINDING); row.put("result_code", "F1");
        row.put("result_state", "REPORTER_SUPPLEMENT_PENDING"); row.put("result_round_no", 1);
        row.put("result_version", 5L); row.put("result_review_cycle", cycle);
        return row;
    }

    private static JooqFindingWorkflowRepository repository(List<Captured> statements, Map<String, Object> receipt) {
        DSLContext values = DSL.using(SQLDialect.POSTGRES);
        DSLContext dsl = DSL.using(new MockConnection(context -> {
            statements.add(new Captured(context.sql(), new ArrayList<>(Arrays.asList(context.bindings()))));
            if (context.sql().contains("set_config")) return result(values, Map.of("set_config", TENANT.toString()));
            if (context.sql().contains("from public.finding_command_receipt receipt")) return result(values, receipt);
            return new MockResult[]{new MockResult(1)};
        }), SQLDialect.POSTGRES);
        return new JooqFindingWorkflowRepository(dsl, new NoOpTransactionManager(), mock(AuthorizationWriteGuard.class));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static MockResult[] result(DSLContext values, Map<String, Object> source) {
        if (source == null) return new MockResult[]{new MockResult(0)};
        Field<?>[] fields = source.entrySet().stream().map(e -> DSL.field(e.getKey(),
                e.getValue() == null ? Integer.class : (Class) e.getValue().getClass())).toArray(Field<?>[]::new);
        var rows = values.newResult(fields);
        Record row = values.newRecord(fields);
        row.fromMap(source);
        rows.add(row);
        return new MockResult[]{new MockResult(1, rows)};
    }

    private record Captured(String sql, List<Object> bindings) { }
    private static final class NoOpTransactionManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}

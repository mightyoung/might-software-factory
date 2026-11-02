package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.authorization.AuthorizationWriteGuard;
import com.mightyoung.softwarefactory.authorization.AuthorizationWriteGuardException;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingSourceCategory;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingUrgency;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowCase;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowRepository;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowState;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.JooqFindingWorkflowRepository;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class FindingWorkflowRepositorySqlTest {

    private static final UUID TENANT = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID FINDING = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID ORGANIZATION = UUID.fromString("31000000-0000-0000-0000-000000000001");
    private static final UUID MEMBER = UUID.fromString("20000000-0000-0000-0000-000000000001");

    @Test
    void writesNewReportMetadataAsPersistedColumnsWithoutClientBackfillInput() {
        List<String> statements = new ArrayList<>();
        List<List<Object>> bindings = new ArrayList<>();
        DSLContext values = DSL.using(SQLDialect.POSTGRES);
        MockDataProvider provider = context -> {
            statements.add(context.sql());
            bindings.add(Arrays.asList(context.bindings()));
            if (context.sql().toLowerCase(java.util.Locale.ROOT).contains("set_config")) {
                return new MockResult[] {singleValue(values, "set_config", String.class,
                        (String) context.bindings()[1])};
            }
            return new MockResult[] {new MockResult(1)};
        };
        DSLContext dsl = DSL.using(new MockConnection(provider), SQLDialect.POSTGRES);
        AuthorizationWriteGuard guard = mock(AuthorizationWriteGuard.class);
        doAnswer(call -> { statements.add("authorization guard"); return null; })
                .when(guard).forFindingCommand(TENANT);
        FindingWorkflowRepository repository = new JooqFindingWorkflowRepository(dsl, new NoOpTransactionManager(), guard);
        OffsetDateTime discoveredAt = OffsetDateTime.parse("2026-10-30T10:00:00Z");
        FindingWorkflowCase finding = FindingWorkflowCase.create(
                TENANT, FINDING, ORGANIZATION, "YH-20261031-0001", "Synthetic finding", discoveredAt,
                null, null, "UNKNOWN", MEMBER, FindingSourceCategory.PERSON_REPORT, FindingUrgency.URGENT,
                "West entrance", "Area closed", true);

        repository.inTenantTransaction(TENANT, tx -> {
            tx.insertFinding(finding);
            return null;
        });

        assertThat(statements.get(0)).contains("set_config");
        assertThat(statements.get(1)).isEqualTo("authorization guard");
        verify(guard).forFindingCommand(TENANT);
        String insert = statements.stream().filter(sql -> sql.toLowerCase(java.util.Locale.ROOT)
                .contains("insert into public.finding")).findFirst().orElseThrow();
        assertThat(insert.toLowerCase(java.util.Locale.ROOT)).contains(
                "source_category", "urgency", "location", "interim_measure", "backfill");
        assertThat(bindings.stream().flatMap(List::stream)).contains(
                "PERSON_REPORT", "URGENT", "West entrance", "Area closed", true);
        assertThat(finding.state()).isEqualTo(FindingWorkflowState.PERSON_CONFIRM_PENDING);
    }

    @Test
    void guardFailureRollsBackWithoutInvokingCommandOrReplayCallback() {
        DSLContext values = DSL.using(SQLDialect.POSTGRES);
        DSLContext dsl = DSL.using(new MockConnection(context -> new MockResult[] {
                singleValue(values, "set_config", String.class, TENANT.toString())
        }), SQLDialect.POSTGRES);
        AuthorizationWriteGuard guard = mock(AuthorizationWriteGuard.class);
        doThrow(new AuthorizationWriteGuardException("timeout")).when(guard).forFindingCommand(TENANT);
        var manager = mock(org.springframework.transaction.PlatformTransactionManager.class);
        var status = new org.springframework.transaction.support.SimpleTransactionStatus();
        when(manager.getTransaction(any())).thenReturn(status);
        var repository = new JooqFindingWorkflowRepository(dsl, manager, guard);
        java.util.concurrent.atomic.AtomicBoolean invoked = new java.util.concurrent.atomic.AtomicBoolean();

        assertThatThrownBy(() -> repository.inTenantTransaction(TENANT, tx -> {
            invoked.set(true);
            return null;
        })).isInstanceOf(AuthorizationWriteGuardException.class);

        assertThat(invoked).isFalse();
        verify(manager).rollback(status);
        verify(manager, never()).commit(any());
    }

    private static <T> MockResult singleValue(DSLContext dsl, String key, Class<T> type, T value) {
        Field<T> field = DSL.field(key, type);
        var result = dsl.newResult(field);
        var row = dsl.newRecord(field);
        row.set(field, value);
        result.add(row);
        return new MockResult(result.size(), result);
    }

    private static final class NoOpTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() { return new Object(); }

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

package com.mightyoung.softwarefactory.auditops;

import com.mightyoung.softwarefactory.identity.*;
import org.jooq.*;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class JooqIdentitySecurityRecordsTest {
    private final CurrentTenantContext context = new CurrentTenantContext(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),2,3,4);
    private final UUID trace = UUID.randomUUID();
    private final Instant occurredAt = Instant.parse("2026-11-02T12:00:00Z");
    private final IdentitySecurityTransaction security = mock(IdentitySecurityTransaction.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final List<String> statements = new ArrayList<>();
    private final List<List<Object>> bindings = new ArrayList<>();
    private final List<String> order = new ArrayList<>();
    private boolean failEvent;
    private int eventRows = 1;
    private final DSLContext values = DSL.using(SQLDialect.POSTGRES);

    private JooqIdentitySecurityRecords records() {
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        DSLContext dsl = DSL.using(new MockConnection(call -> {
            statements.add(call.sql()); bindings.add(Arrays.asList(call.bindings())); order.add("sql");
            if (call.sql().contains("set_config")) {
                Field<String> field = DSL.field("set_config",String.class);
                var rows = values.newResult(field); var row = values.newRecord(field); row.value1(context.tenantId().toString()); rows.add(row);
                return new MockResult[] {new MockResult(1,rows)};
            }
            if (call.sql().contains("public.security_event")) {
                if (failEvent) throw new SQLException("database unavailable","08006");
                return new MockResult[] {new MockResult(eventRows)};
            }
            return new MockResult[] {new MockResult(1)};
        }),SQLDialect.POSTGRES);
        return new JooqIdentitySecurityRecords(dsl,security,manager);
    }
    @AfterEach void resetThread() { TransactionSynchronizationManager.clear(); }

    @Test void successfulAuditRequiresBridgeThenAppendsOnlyVersionDiffAndSuccessEvent() {
        var records = records();
        doAnswer(call -> { order.add("marker"); return null; }).when(security).requireActive(context);
        records.passwordChanged(context,4,5,trace,occurredAt);
        assertThat(order).containsExactly("marker","sql","sql");
        verifyNoInteractions(manager);
        assertThat(statements).hasSize(2);
        assertThat(statements.getFirst()).contains("public.audit_event","'account.password.change'","'account'", "'oldCredentialVersion'", "'newCredentialVersion'", "cast(? as timestamptz)");
        assertThat(statements.get(1)).contains("public.security_event","'PASSWORD_CHANGE'","cast(? as uuid)","cast(? as timestamptz)");
        assertThat(bindings.getFirst()).contains(context.tenantId(),context.actorId(),context.accountId(),4L,5L,trace.toString());
        assertThat(bindings.get(1)).contains(context.tenantId(),context.accountId(),context.actorId(),"SUCCESS",null,trace);
        assertThat(String.join(" ",statements)).doesNotContain("secret_hash","session_id","currentPassword","newPassword");
    }

    @Test void missingBridgeMarkerPreventsEitherInsert() {
        var records = records();
        doThrow(new IllegalStateException("required marker")).when(security).requireActive(context);
        assertThatThrownBy(() -> records.passwordChanged(context,4,5,trace,occurredAt)).isInstanceOf(IllegalStateException.class);
        assertThat(statements).isEmpty();
    }

    @Test void failedSuccessEventEscapesTheSharedTransactionAndRollsBackAudit() {
        var records = records(); failEvent = true;
        assertThatThrownBy(() -> new TransactionTemplate(manager).executeWithoutResult(status -> records.passwordChanged(context,4,5,trace,occurredAt)))
                .isInstanceOf(org.jooq.exception.DataAccessException.class);
        assertThat(statements).hasSize(2);
        verify(manager).rollback(any()); verify(manager,never()).commit(any());
    }

    @Test void failureUsesRequiresNewTenantContextAndFixedWhitelistedResultsWithoutGlobalLock() {
        var records = records();
        doAnswer(call -> {
            TransactionDefinition definition = call.getArgument(0);
            assertThat(definition.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            assertThat(definition.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
            assertThat(definition.isReadOnly()).isFalse();
            return new SimpleTransactionStatus();
        }).when(manager).getTransaction(any());
        for (PasswordChangeFailureReason reason : PasswordChangeFailureReason.values()) {
            int index = statements.size(); records.passwordChangeFailed(context,reason,trace,occurredAt);
            assertThat(statements.get(index)).contains("set_config('app.tenant_id', ?, true)");
            assertThat(bindings.get(index)).containsExactly(context.tenantId().toString());
            assertThat(bindings.get(index+1)).contains(reason.name(),reason.eventResult(),trace);
        }
        assertThat(statements).noneMatch(sql -> sql.contains("audit_event") || sql.contains("pg_advisory") || sql.toLowerCase(Locale.ROOT).contains("select * from public.security_event"));
        verifyNoInteractions(security);
        verify(manager,times(PasswordChangeFailureReason.values().length)).commit(any());
    }

    @Test void rolledBackSuccessAuditCanBeFollowedByAnIndependentWhitelistedFailureEvent() {
        var records = records(); failEvent = true;
        assertThatThrownBy(() -> new TransactionTemplate(manager).executeWithoutResult(
                status -> records.passwordChanged(context,4,5,trace,occurredAt)))
                .isInstanceOf(org.jooq.exception.DataAccessException.class);
        verify(manager).rollback(any());
        failEvent = false;
        records.passwordChangeFailed(context,PasswordChangeFailureReason.AUDIT_UNAVAILABLE,trace,occurredAt);
        assertThat(statements).hasSize(4);
        assertThat(statements.get(2)).contains("set_config");
        assertThat(bindings.get(3)).contains("FAILED","AUDIT_UNAVAILABLE");
        verify(manager).commit(any()); verify(security).requireActive(context);
    }

    @Test void failureRecorderRejectsAmbientTransactionBeforeOpeningOrWritingAnything() {
        var records = records();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(() -> records.passwordChangeFailed(context,PasswordChangeFailureReason.WRITE_FAILED,trace,occurredAt)).isInstanceOf(IllegalStateException.class);
        assertThat(statements).isEmpty(); verifyNoInteractions(manager,security);
    }

    @Test void databaseFailureAndZeroInsertedRowsNeverPretendFailureWasRecorded() {
        var records = records(); failEvent = true;
        assertThatThrownBy(() -> records.passwordChangeFailed(context,PasswordChangeFailureReason.COMMIT_OUTCOME_UNKNOWN,trace,occurredAt))
                .isInstanceOf(org.jooq.exception.DataAccessException.class);
        failEvent = false; eventRows = 0;
        assertThatThrownBy(() -> records.passwordChangeFailed(context,PasswordChangeFailureReason.AUDIT_UNAVAILABLE,trace,occurredAt))
                .isInstanceOf(IllegalStateException.class);
        verify(manager,times(2)).rollback(any()); verify(manager,never()).commit(any());
    }

    @Test void auditVersionsMustAdvanceExactlyOnceAndRequiredMetadataCannotBeMissing() {
        var records = records();
        for (long next : List.of(3L,4L,6L)) {
            assertThatThrownBy(() -> records.passwordChanged(context,4,next,trace,occurredAt)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> records.passwordChanged(context,4,5,null,occurredAt)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> records.passwordChangeFailed(context,null,trace,occurredAt)).isInstanceOf(NullPointerException.class);
        assertThat(statements).isEmpty(); verifyNoInteractions(manager,security);
    }
}

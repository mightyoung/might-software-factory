package com.mightyoung.softwarefactory.auditops;

import com.mightyoung.softwarefactory.authorization.*;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.jooq.tools.jdbc.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class JooqBusinessAuditAppendTest {
    @Test void receiptRequiresActualSameConnectionInsertAndExactRecordCorrelation() {
        var source=mock(DataSource.class);var authorization=mock(EntityActionAuthorization.class);
        var scope=mock(EntityActionAuthorization.ActionScope.class);
        var identity=new CurrentTenantContext(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),0,0,1);
        when(scope.context()).thenReturn(identity);when(scope.action()).thenReturn(RegisteredEntityAction.WORKSHOP_CREATE);
        when(scope.observedAt()).thenReturn(Instant.parse("2026-11-04T00:00:00.123456Z"));
        var sql=new ArrayList<String>();var parameters=new ArrayList<Object[]>();
        var connection=new MockConnection(context->{sql.add(context.sql());parameters.add(context.bindings());return new MockResult[]{new MockResult(1)};});
        var holder=new ConnectionHolder(connection);holder.setSynchronizedWithTransaction(true);TransactionSynchronizationManager.bindResource(source,holder);
        try{
            var adapter=new JooqBusinessAuditAppend(source,authorization);
            var record=new BusinessAuditRecord(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),BusinessAuditRecord.ChangeKind.CREATE,
                    Set.of(BusinessAuditRecord.FieldCategory.IDENTITY),UUID.randomUUID());
            var receipt=adapter.appendSuccess(scope,record);
            assertThat(adapter.requireMatches(receipt,scope,record)).isEqualTo(record.auditId());
            assertThat(sql).singleElement().satisfies(statement->assertThat(statement).contains("insert into public.audit_event").doesNotContain("select "));
            assertThat(parameters).singleElement().satisfies(bindings->assertThat(bindings[10])
                    .isInstanceOf(java.time.OffsetDateTime.class).isEqualTo(scope.observedAt().atOffset(java.time.ZoneOffset.UTC)));
            verify(authorization,atLeast(2)).requireSqlConnection(scope,source,connection);verifyNoInteractions(source);
            var changed=new BusinessAuditRecord(record.auditId(),UUID.randomUUID(),record.resourceId(),record.changeKind(),record.changedFields(),record.traceId());
            assertThatThrownBy(()->adapter.requireMatches(receipt,scope,changed)).isInstanceOf(IllegalStateException.class);
            assertThat(holder.isRollbackOnly()).isTrue();
            assertThat(BusinessAuditAppend.AuditAppendReceipt.class.getConstructors()).isEmpty();
        }finally{TransactionSynchronizationManager.unbindResourceIfPossible(source);TransactionSynchronizationManager.clear();}
    }
    @Test void missingCallerHolderCannotCreateAnAuditReceiptOrBorrowAnotherConnection() {
        var source=mock(DataSource.class);var authorization=mock(EntityActionAuthorization.class);
        var adapter=new JooqBusinessAuditAppend(source,authorization);
        var record=new BusinessAuditRecord(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),BusinessAuditRecord.ChangeKind.CREATE,
                Set.of(BusinessAuditRecord.FieldCategory.IDENTITY),UUID.randomUUID());
        assertThatThrownBy(()->adapter.appendSuccess(mock(EntityActionAuthorization.ActionScope.class),record)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(source);
    }
}

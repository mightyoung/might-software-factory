package com.mightyoung.softwarefactory.tenancy;

import java.sql.Connection;
import java.util.*;
import javax.sql.DataSource;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TenantApplicationCallerBoundaryTest {
    @Test void oldImplementationCannotFallBackToNestedLegacyLookup() {
        TenantApplicationReadRepository legacy=(tenant,key)->{throw new AssertionError("Legacy lookup must not run");};
        assertThatThrownBy(()->legacy.findCurrentInCaller(UUID.randomUUID(),"safety")).isInstanceOf(IllegalStateException.class);
    }
    @Test void currentCallerReadUsesExistingConnectionWithoutChangingTenantOrOpeningTransaction()throws Exception {
        var tenant=UUID.randomUUID();var source=mock(DataSource.class);var manager=mock(PlatformTransactionManager.class);
        var sql=new ArrayList<String>();var db=DSL.using(SQLDialect.POSTGRES);
        var connection=spy(new MockConnection(context->{
            sql.add(context.sql());
            if(context.sql().contains("current_setting")){
                var field=DSL.field("tenant",String.class);var rows=db.newResult(field);rows.add(db.newRecord(field).values(tenant.toString()));
                return new MockResult[]{new MockResult(1,rows)};
            }
            var enabled=DSL.field("enabled",Boolean.class);var version=DSL.field("version",Long.class);
            var rows=db.newResult(enabled,version);rows.add(db.newRecord(enabled,version).values(true,7L));
            return new MockResult[]{new MockResult(1,rows)};
        }));
        doReturn(false).when(connection).getAutoCommit();doReturn(false).when(connection).isReadOnly();
        doReturn(Connection.TRANSACTION_READ_COMMITTED).when(connection).getTransactionIsolation();
        var holder=new ConnectionHolder(connection);holder.setSynchronizedWithTransaction(true);
        TransactionSynchronizationManager.bindResource(source,holder);TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(Connection.TRANSACTION_READ_COMMITTED);
        try{
            var repository=new JooqTenantApplicationReadRepository(db,manager,source);
            assertThat(repository.findCurrentInCaller(tenant,"safety")).contains(new TenantApplicationState(true,7));
            assertThat(sql).hasSize(3).allMatch(query->!query.contains("set_config")&&!query.contains("timestamp"));
            verifyNoInteractions(source,manager);verify(connection,never()).commit();verify(connection,never()).close();
        }finally{TransactionSynchronizationManager.unbindResourceIfPossible(source);TransactionSynchronizationManager.clear();}
    }
    @Test void missingOwnedBoundaryDoesNotAcquireAConnection() {
        var source=mock(DataSource.class);var manager=mock(PlatformTransactionManager.class);
        var repository=new JooqTenantApplicationReadRepository(DSL.using(SQLDialect.POSTGRES),manager,source);
        assertThatThrownBy(()->repository.findCurrentInCaller(UUID.randomUUID(),"safety")).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(source,manager);
    }
}

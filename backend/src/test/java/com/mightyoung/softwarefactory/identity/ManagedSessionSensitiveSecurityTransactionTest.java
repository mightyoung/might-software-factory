package com.mightyoung.softwarefactory.identity;

import com.mightyoung.softwarefactory.authorization.ManagedSessionSensitiveSecurityTransaction;
import com.mightyoung.softwarefactory.authorization.PostgresAuthorizationWriteGuard;
import com.mightyoung.softwarefactory.authorization.AuthorizationWriteGuardException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.mightyoung.softwarefactory.identity.SessionSensitiveContracts.Operation;

class ManagedSessionSensitiveSecurityTransactionTest {
    final DataSource source=mock(DataSource.class);
    final Connection connection=mock(Connection.class);
    final DataSourceTransactionManager manager=new DataSourceTransactionManager(source);
    final ManagedSessionSensitiveSecurityTransaction boundaries=new ManagedSessionSensitiveSecurityTransaction(source,manager,new PostgresAuthorizationWriteGuard(source));
    final AtomicBoolean autoCommit=new AtomicBoolean(true),readOnly=new AtomicBoolean(false);
    final AtomicInteger isolation=new AtomicInteger(Connection.TRANSACTION_READ_COMMITTED);
    final AtomicReference<String> tenant=new AtomicReference<>("");
    final List<String> statements=new ArrayList<>();
    final Instant databaseTime=Instant.parse("2026-11-04T00:01:00Z");
    @BeforeEach void jdbc()throws Exception{
        when(source.getConnection()).thenReturn(connection);when(connection.unwrap(Connection.class)).thenReturn(connection);
        when(connection.getAutoCommit()).thenAnswer(call->autoCommit.get());doAnswer(call->{autoCommit.set(call.getArgument(0));return null;}).when(connection).setAutoCommit(anyBoolean());
        when(connection.isReadOnly()).thenAnswer(call->readOnly.get());doAnswer(call->{readOnly.set(call.getArgument(0));return null;}).when(connection).setReadOnly(anyBoolean());
        when(connection.getTransactionIsolation()).thenAnswer(call->isolation.get());doAnswer(call->{isolation.set(call.getArgument(0));return null;}).when(connection).setTransactionIsolation(anyInt());
        when(connection.prepareStatement(anyString())).thenAnswer(call->{
            String sql=call.getArgument(0);statements.add(sql);PreparedStatement statement=mock(PreparedStatement.class);
            AtomicReference<String> parameter=new AtomicReference<>();doAnswer(bind->{parameter.set(bind.getArgument(1));return null;}).when(statement).setString(eq(1),anyString());
            ResultSet rows=mock(ResultSet.class);when(rows.next()).thenReturn(true,false);
            when(rows.getString(1)).thenAnswer(read->{
                if(sql.contains("set_config('app.tenant_id'")){tenant.set(parameter.get());return parameter.get();}
                if(sql.contains("current_setting('app.tenant_id'"))return tenant.get();
                if(sql.contains("transaction_isolation"))return isolation.get()==Connection.TRANSACTION_REPEATABLE_READ?"repeatable read":"read committed";
                if(sql.contains("transaction_read_only"))return readOnly.get()?"on":"off";
                return sql.contains("case when")?"5000ms":"0";
            });
            when(rows.getObject(1,OffsetDateTime.class)).thenReturn(databaseTime.atOffset(ZoneOffset.UTC));
            when(statement.executeQuery()).thenReturn(rows);return statement;
        });
        var metadata=mock(DatabaseMetaData.class);when(metadata.supportsSavepoints()).thenReturn(true);when(connection.getMetaData()).thenReturn(metadata);
        when(connection.setSavepoint(anyString())).thenReturn(mock(Savepoint.class));
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();TransactionSynchronizationManager.clear();}
    CapturedHumanRequest capture(){return CapturedHumanRequestTest.capture(CapturedHumanRequestTest.authenticatedRequest());}
    @Test void writerCapturesDatabaseTimeAfterGuardAndPinsActualScope()throws Exception{
        var request=capture();var scope=SessionSensitiveSecurityTransaction.Scope.human(Operation.REGISTER_SESSION,request);
        AtomicReference<SessionSensitiveSecurityTransaction.GuardedBoundary> retained=new AtomicReference<>();
        boundaries.execute(scope,proof->{retained.set(proof);boundaries.requireGuarded(scope,proof,source,connection);
            assertThat(proof.observedAt()).isEqualTo(databaseTime);return null;});
        assertThat(statements.indexOf("select pg_advisory_xact_lock(?, ?)")).isLessThan(statements.indexOf("select statement_timestamp()"));
        assertThat(statements.stream().filter(sql->sql.equals("select statement_timestamp()"))).hasSize(1);
        verify(connection).commit();assertThatThrownBy(()->retained.get().observedAt()).isInstanceOf(AuthorizationWriteGuardException.class);
    }
    @Test void readSnapshotIsReadonlyRepeatableReadAndCannotBecomeGuardedWrite()throws Exception{
        var request=capture();
        boundaries.read(request,proof->{boundaries.requireSnapshot(request,proof,source,connection);
            assertThat(readOnly).isTrue();assertThat(proof.observedAt()).isEqualTo(databaseTime);return null;});
        assertThat(statements).contains("select transaction_timestamp()").noneMatch(sql->sql.contains("pg_advisory_xact_lock"));
        verify(connection).commit();
    }
    @Test void wrongDatasourceAndFabricatedBoundaryPoisonActualTransaction()throws Exception{
        var request=capture();var scope=SessionSensitiveSecurityTransaction.Scope.human(Operation.REGISTER_SESSION,request);
        assertThatThrownBy(()->boundaries.execute(scope,proof->{
            assertThatThrownBy(()->boundaries.requireGuarded(scope,proof,mock(DataSource.class),connection)).isInstanceOf(AuthorizationWriteGuardException.class);
            return null;
        })).isInstanceOf(AuthorizationWriteGuardException.class);
        verify(connection).rollback();verify(connection,never()).commit();
    }
    @Test void wrongTenantCannotBeRestoredAfterFailedAssertion()throws Exception{
        var request=capture();var scope=SessionSensitiveSecurityTransaction.Scope.human(Operation.REGISTER_SESSION,request);
        assertThatThrownBy(()->boundaries.execute(scope,proof->{String original=tenant.get();tenant.set("changed");
            assertThatThrownBy(proof::observedAt).isInstanceOf(AuthorizationWriteGuardException.class);tenant.set(original);return null;
        })).isInstanceOf(AuthorizationWriteGuardException.class);verify(connection).rollback();
    }
    @Test void savepointRollbackInvalidatesEvenWhenSpringResetsRollbackOnly()throws Exception{
        var request=capture();var scope=SessionSensitiveSecurityTransaction.Scope.human(Operation.REGISTER_SESSION,request);
        assertThatThrownBy(()->boundaries.execute(scope,proof->{
            TransactionTemplate nested=new TransactionTemplate(manager);nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
            assertThatThrownBy(()->nested.executeWithoutResult(status->{throw new IllegalStateException();})).isInstanceOf(IllegalStateException.class);
            return null;
        })).isInstanceOf(AuthorizationWriteGuardException.class);verify(connection).rollback(any(Savepoint.class));verify(connection).rollback();
    }
    @Test void noAmbientOrBorrowedTransactionsAreAccepted(){
        var request=capture();var scope=SessionSensitiveSecurityTransaction.Scope.human(Operation.REGISTER_SESSION,request);
        assertThatThrownBy(()->new TransactionTemplate(manager).execute(status->boundaries.execute(scope,proof->null))).isInstanceOf(AuthorizationWriteGuardException.class);
        assertThatThrownBy(()->new TransactionTemplate(manager).execute(status->boundaries.read(request,proof->null))).isInstanceOf(AuthorizationWriteGuardException.class);
    }
    @Test void actualModeDriftPoisonsReadonlySnapshot()throws Exception{
        var request=capture();assertThatThrownBy(()->boundaries.read(request,proof->{readOnly.set(false);return null;})).isInstanceOf(AuthorizationWriteGuardException.class);
        verify(connection).rollback();verify(connection,never()).commit();
    }
}

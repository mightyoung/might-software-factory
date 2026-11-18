package com.mightyoung.softwarefactory.identity;

import com.mightyoung.softwarefactory.authorization.ManagedIdentityLifecycleSecurityTransaction;
import com.mightyoung.softwarefactory.authorization.PostgresAuthorizationWriteGuard;
import com.mightyoung.softwarefactory.authorization.AuthorizationWriteGuardException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.mightyoung.softwarefactory.identity.IdentityLifecycleContracts.*;

class IdentityLifecycleSecurityTransactionTest {
    final DataSource source=mock(DataSource.class);
    final Connection connection=mock(Connection.class);
    final DataSourceTransactionManager manager=new DataSourceTransactionManager(source);
    final ManagedIdentityLifecycleSecurityTransaction writer=new ManagedIdentityLifecycleSecurityTransaction(source,manager,new PostgresAuthorizationWriteGuard(source));
    final AtomicBoolean autoCommit=new AtomicBoolean(true);
    final AtomicInteger isolation=new AtomicInteger(Connection.TRANSACTION_READ_COMMITTED);
    final AtomicReference<String> tenant=new AtomicReference<>("");
    final List<String> statements=new ArrayList<>();
    final CurrentSessionReadRepository sessions=mock(CurrentSessionReadRepository.class);
    final MockHttpServletRequest request=new MockHttpServletRequest();
    final CurrentTenantContext context=new CurrentTenantContext(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),2,3,4);
    final UUID target=UUID.randomUUID();
    @BeforeEach void jdbc()throws Exception {
        when(source.getConnection()).thenReturn(connection);when(connection.unwrap(Connection.class)).thenReturn(connection);
        when(connection.getAutoCommit()).thenAnswer(call->autoCommit.get());doAnswer(call->{autoCommit.set(call.getArgument(0));return null;}).when(connection).setAutoCommit(anyBoolean());
        when(connection.getTransactionIsolation()).thenAnswer(call->isolation.get());doAnswer(call->{isolation.set(call.getArgument(0));return null;}).when(connection).setTransactionIsolation(anyInt());
        when(connection.prepareStatement(anyString())).thenAnswer(call->{
            String sql=call.getArgument(0);statements.add(sql);PreparedStatement statement=mock(PreparedStatement.class);
            AtomicReference<String> parameter=new AtomicReference<>();doAnswer(bind->{parameter.set(bind.getArgument(1));return null;}).when(statement).setString(eq(1),anyString());
            ResultSet rows=mock(ResultSet.class);when(rows.next()).thenReturn(true);
            when(rows.getString(1)).thenAnswer(read->{
                if(sql.contains("set_config('app.tenant_id'")){tenant.set(parameter.get());return parameter.get();}
                if(sql.contains("current_setting('app.tenant_id'"))return tenant.get();
                return sql.contains("transaction_isolation")?"read committed":sql.contains("transaction_read_only")?"off":sql.contains("case when")?"5000ms":"0";
            });when(statement.executeQuery()).thenReturn(rows);return statement;
        });
        var metadata=mock(DatabaseMetaData.class);when(metadata.supportsSavepoints()).thenReturn(true);when(connection.getMetaData()).thenReturn(metadata);when(connection.setSavepoint(anyString())).thenReturn(mock(Savepoint.class));
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();TransactionSynchronizationManager.clear();}
    private IdentityLifecycleSession.Captured capture(){
        var principal=new VerifiedSessionPrincipal(context.actorId(),context.accountId(),context.tenantId(),context.memberId(),2,3,4);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,"",List.of()));
        var lifetime=new SessionLifetimePolicy(Clock.fixed(Instant.parse("2026-11-03T12:00:00Z"),ZoneOffset.UTC),Duration.ofHours(1));lifetime.markAuthenticated(request.getSession());
        @SuppressWarnings("unchecked") ObjectProvider<jakarta.servlet.http.HttpServletRequest> requests=mock(ObjectProvider.class);when(requests.getIfAvailable()).thenReturn(request);
        when(sessions.findCurrent(context)).thenAnswer(call->{assertThat(tenant.get()).isEqualTo(context.tenantId().toString());return Optional.of(new CurrentSessionDetails(context.actorId(),context.accountId(),context.tenantId(),"source","Source",context.memberId(),"Recipient"));});
        return new IdentityLifecycleSession(new SessionContextResolver(),sessions,lifetime,requests).capture();
    }
    @Test void sourceIsVerifiedExactlyOnceBeforeIrreversibleTargetPhase()throws Exception {
        var captured=capture();var scope=IdentityLifecycleSecurityTransaction.Scope.session(Operation.CONSUME_INVITATION,UUID.randomUUID(),target,captured);
        writer.execute(scope,()->{writer.requireActive(scope);writer.requireSqlConnection(scope,source,connection);assertThat(tenant.get()).isEqualTo(target.toString());assertThat(captured.verifiedDisplayName()).isEqualTo("Recipient");return null;});
        verify(sessions).findCurrent(context);verify(connection).commit();
        assertThat(statements.stream().filter(sql->sql.contains("pg_advisory_xact_lock"))).hasSize(3);
        assertThat(statements.indexOf("select pg_advisory_xact_lock(?, ?)" )).isLessThan(statements.indexOf("select set_config('app.tenant_id', ?, true)"));
        assertThatThrownBy(()->writer.execute(scope,()->null)).isInstanceOf(UnauthenticatedSessionException.class);
    }
    @Test void anonymousResetHasNoInventedTenantAndAssertionsNeverAcquireLocks()throws Exception {
        var scope=IdentityLifecycleSecurityTransaction.Scope.capability(Operation.CONSUME_RESET,UUID.randomUUID(),null);
        writer.execute(scope,()->{int before=statements.size();writer.requireActive(scope);assertThat(statements.subList(before,statements.size())).noneMatch(sql->sql.contains("pg_advisory_xact_lock"));assertThat(tenant.get()).isEmpty();return null;});
        verifyNoInteractions(sessions);verify(connection).commit();
    }
    @Test void returningToSourceTenantFailsAndPoisonsTheOriginalTransaction()throws Exception {
        var captured=capture();var scope=IdentityLifecycleSecurityTransaction.Scope.session(Operation.CONSUME_INVITATION,UUID.randomUUID(),target,captured);
        assertThatThrownBy(()->writer.execute(scope,()->{tenant.set(context.tenantId().toString());assertThatThrownBy(()->writer.requireActive(scope)).isInstanceOf(AuthorizationWriteGuardException.class);tenant.set(target.toString());return null;})).isInstanceOf(RuntimeException.class);
        verify(connection).rollback();verify(connection,never()).commit();
    }
    @Test void wrongDataSourceAndConnectionCannotBeLentByTheMarker()throws Exception {
        var scope=IdentityLifecycleSecurityTransaction.Scope.capability(Operation.CONSUME_RESET,UUID.randomUUID(),null);
        assertThatThrownBy(()->writer.execute(scope,()->{writer.requireSqlConnection(scope,mock(DataSource.class),connection);return null;})).isInstanceOf(AuthorizationWriteGuardException.class);
        verify(connection).rollback();
    }
    @Test void unbindingAndRestoringTheHolderCannotRepairARejectedAssertion()throws Exception {
        var scope=IdentityLifecycleSecurityTransaction.Scope.capability(Operation.CONSUME_RESET,UUID.randomUUID(),null);
        assertThatThrownBy(()->writer.execute(scope,()->{
            Object original=TransactionSynchronizationManager.unbindResource(source);
            try {assertThatThrownBy(()->writer.requireActive(scope)).isInstanceOf(AuthorizationWriteGuardException.class);}
            finally {TransactionSynchronizationManager.bindResource(source,original);}
            return null;
        })).isInstanceOf(RuntimeException.class);verify(connection).rollback();
    }
    @Test void nestedRollbackAndSuspendIrreversiblyInvalidateTheMarker()throws Exception {
        var scope=IdentityLifecycleSecurityTransaction.Scope.capability(Operation.CONSUME_RESET,UUID.randomUUID(),null);
        assertThatThrownBy(()->writer.execute(scope,()->{
            TransactionTemplate nested=new TransactionTemplate(manager);nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
            assertThatThrownBy(()->nested.executeWithoutResult(status->{throw new IllegalStateException();})).isInstanceOf(IllegalStateException.class);
            return null;
        })).isInstanceOf(AuthorizationWriteGuardException.class);verify(connection).rollback(any(Savepoint.class));verify(connection).rollback();
    }
    @Test void requestAuthenticationIsRecheckedAtCommitWithoutResettingTargetRls()throws Exception {
        var captured=capture();var scope=IdentityLifecycleSecurityTransaction.Scope.session(Operation.CONSUME_INVITATION,UUID.randomUUID(),target,captured);
        assertThatThrownBy(()->writer.execute(scope,()->{SecurityContextHolder.clearContext();return null;})).isInstanceOf(AuthorizationWriteGuardException.class);
        verify(sessions).findCurrent(context);verify(connection).rollback();verify(connection,never()).commit();
    }
    @Test void ambientTransactionsAndUndeclaredScopesAreRejected() {
        var scope=IdentityLifecycleSecurityTransaction.Scope.capability(Operation.CONSUME_RESET,UUID.randomUUID(),null);
        assertThatThrownBy(()->new TransactionTemplate(manager).execute(status->writer.execute(scope,()->null))).isInstanceOf(AuthorizationWriteGuardException.class);
        assertThatThrownBy(()->writer.requireActive(scope)).isInstanceOf(RuntimeException.class);
    }
}

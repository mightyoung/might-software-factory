package com.mightyoung.softwarefactory.authorization;

import com.mightyoung.softwarefactory.identity.*;
import com.mightyoung.softwarefactory.tenancy.*;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.jooq.*;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Managed + real checked HUMAN adapter with controlled JDBC rows; not PostgreSQL/RLS/grant acceptance. */
class PersistentTenantAuditReadAuthorizationTest {
    @AfterEach void clear() { SecurityContextHolder.clearContext();TransactionSynchronizationManager.clear(); }

    @Test void realEnforcingAuthorityUsesOneBorrowedConnectionAndOneCheckedTimeForEveryTemporalPredicate() throws Exception {
        var f=new Fixture();var retained=new AtomicReference<TenantAuditReadAuthorization.Scope>();
        f.read(boundary->{
            var scope=f.authorization.requireRead(f.request,boundary);retained.set(scope);
            assertThat(scope.tenantId()).isEqualTo(f.context.tenantId());assertThat(scope.observedAt()).isEqualTo(f.cut);
            f.authorization.requireSqlConnection(scope,f.source,f.connection);return null;
        });
        verify(f.source,times(1)).getConnection();verify(f.connection).commit();verify(f.connection,never()).rollback();
        assertThat(f.capabilityBindings).hasSize(1);Object[] bindings=f.capabilityBindings.getFirst();assertThat(bindings).hasSize(13);
        assertThat(bindings[0]).isEqualTo(f.context.tenantId());
        for(int index:List.of(1,2,5,6,8,9,11,12))assertThat(bindings[index]).isEqualTo(f.cut.atOffset(ZoneOffset.UTC));
        for(int index:List.of(3,4,7,10))assertThat(bindings[index]).isEqualTo(f.context.memberId());
        assertThat(f.statements.stream().filter(sql->sql.equals("select transaction_timestamp()"))).hasSize(1);
        assertThat(f.statements.stream().filter(sql->sql.contains("from public.spring_session stored"))).hasSize(1);
        verify(f.applications).findCurrentInCaller(f.context.tenantId(),"system");
        assertUnavailable(()->retained.get().tenantId());
    }

    @Test void theStaticCapabilitySqlContainsOnlyItsExactTenantTupleAndTemporalExistsPaths() {
        String sql=PersistentTenantAuditReadAuthorization.CAPABILITY_SQL;
        assertThat(sql).contains("p.code='audit_event.read'","p.resource_type='audit_event'","p.action='read'",
                "sr.kind='AUDIT_TENANT'","sr.definition='{}'::jsonb","so.tenant_id=sr.tenant_id and so.scope_rule_id=sr.id",
                "rg.subject_kind='MEMBER'","ou.active=true","pos.active=true","g.active=true");
        assertThat(sql).doesNotContain("statement_timestamp", "transaction_timestamp", "current_timestamp", "IDENTITY_TENANT", "DEPT", "select *");
        for(String alias:List.of("rg","mo","mp","gm"))assertThat(sql).contains(alias+".valid_from<=cast(? as timestamptz)",alias+".valid_to>cast(? as timestamptz)");
    }

    @Test void aValidFalseIsDeniedButNeverTreatedAsAnUnavailableOrGrantedResult() {
        var f=new Fixture();f.grant=false;
        assertThatThrownBy(()->f.read(boundary->f.authorization.requireRead(f.request,boundary))).isExactlyInstanceOf(AccessDeniedException.class)
                .hasMessage("Tenant audit query is not authorized");
        assertThat(f.capabilityBindings).hasSize(1);
    }

    @ParameterizedTest @ValueSource(strings={"NULL","STRING","ZERO_ROWS","TWO_ROWS","TWO_COLUMNS","SQL_FAILURE"})
    void malformedResultShapesAndSqlFailuresAreUnavailable(String fault) {
        var f=new Fixture();f.resultFault=fault;
        assertUnavailable(()->f.read(boundary->f.authorization.requireRead(f.request,boundary)));
        assertThat(f.capabilityBindings).hasSize(1);
    }

    @Test void persistedOffAndUnavailableControlCannotAuthorizeEvenWhenOrdinaryPermissionWouldGrant() {
        for(String fault:List.of("OFF","MISSING_CONTROL")){
            var f=new Fixture();f.control=fault;
            assertUnavailable(()->f.read(boundary->f.authorization.requireRead(f.request,boundary)));
            assertThat(f.capabilityBindings).isEmpty();verifyNoInteractions(f.accounts,f.principals,f.members,f.applications);
        }
    }

    @ParameterizedTest @ValueSource(strings={"ACCOUNT_MISSING","ACCOUNT_DISABLED","CREDENTIAL","PRINCIPAL","MEMBER_DISABLED","TENANT_EPOCH","MEMBER_EPOCH","SYSTEM_MISSING","SYSTEM_DISABLED"})
    void currentIdentityAndSystemEntitlementMustExistBeforeTheCapabilityQuery(String fault) {
        var f=new Fixture();
        switch(fault){
            case "ACCOUNT_MISSING"->when(f.accounts.findState(f.context.accountId())).thenReturn(Optional.empty());
            case "ACCOUNT_DISABLED"->when(f.accounts.findState(f.context.accountId())).thenReturn(Optional.of(new AccountSecurityState("DISABLED",4)));
            case "CREDENTIAL"->when(f.accounts.findState(f.context.accountId())).thenReturn(Optional.of(new AccountSecurityState("ACTIVE",5)));
            case "PRINCIPAL"->when(f.principals.findByActorId(f.context.actorId())).thenReturn(Optional.of(new PrincipalStatus(f.context.actorId(),f.context.accountId(),"AGENT","ACTIVE")));
            case "MEMBER_DISABLED"->when(f.members.findCurrentMembership(any(),any(),any())).thenReturn(Optional.of(new TenantMembershipState("ACTIVE","DISABLED",2,3)));
            case "TENANT_EPOCH"->when(f.members.findCurrentMembership(any(),any(),any())).thenReturn(Optional.of(new TenantMembershipState("ACTIVE","ACTIVE",9,3)));
            case "MEMBER_EPOCH"->when(f.members.findCurrentMembership(any(),any(),any())).thenReturn(Optional.of(new TenantMembershipState("ACTIVE","ACTIVE",2,9)));
            case "SYSTEM_MISSING"->when(f.applications.findCurrentInCaller(f.context.tenantId(),"system")).thenReturn(Optional.empty());
            case "SYSTEM_DISABLED"->when(f.applications.findCurrentInCaller(f.context.tenantId(),"system")).thenReturn(Optional.of(new TenantApplicationState(false,2)));
            default->throw new AssertionError(fault);
        }
        assertThatThrownBy(()->f.read(boundary->f.authorization.requireRead(f.request,boundary))).isExactlyInstanceOf(AccessDeniedException.class);
        assertThat(f.capabilityBindings).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings={"ISSUER","SOURCE","CONNECTION"})
    void wrongOwnershipPermanentlyPoisonsTheOriginalScopeDespiteRollbackReset(String fault) throws Exception {
        var f=new Fixture();
        assertUnavailable(()->f.read(boundary->{
            var scope=f.authorization.requireRead(f.request,boundary);
            assertUnavailable(()->{
                if(fault.equals("ISSUER"))f.newAuthorization().requireCurrent(scope);
                else f.authorization.requireSqlConnection(scope,fault.equals("SOURCE")?mock(DataSource.class):f.source,
                        fault.equals("CONNECTION")?mock(Connection.class):f.connection);
            });
            f.holder().resetRollbackOnly();assertUnavailable(()->f.authorization.requireCurrent(scope));
            f.holder().resetRollbackOnly();return null;
        }));
        verify(f.connection).rollback();verify(f.connection,never()).commit();
    }

    @Test void crossThreadMisuseInvalidatesTheOriginalThreadAndCannotBeResetForCommit() throws Exception {
        var f=new Fixture();var failure=new AtomicReference<Throwable>();
        assertUnavailable(()->f.read(boundary->{
            var scope=f.authorization.requireRead(f.request,boundary);
            var thread=new Thread(()->{try{scope.observedAt();}catch(Throwable rejected){failure.set(rejected);}});thread.start();
            try{thread.join();}catch(InterruptedException interrupted){throw new IllegalStateException(interrupted);}
            assertThat(failure.get()).isInstanceOf(SessionSensitiveException.class);f.holder().resetRollbackOnly();
            assertUnavailable(scope::tenantId);f.holder().resetRollbackOnly();return null;
        }));
        verify(f.connection).rollback();verify(f.connection,never()).commit();
    }

    @Test void aChangedPhysicalConnectionCannotBeRestoredToReviveTheScope() {
        var f=new Fixture();
        assertUnavailable(()->f.read(boundary->{
            var scope=f.authorization.requireRead(f.request,boundary);f.physical.set(mock(Connection.class));
            assertUnavailable(()->f.authorization.requireCurrent(scope));f.physical.set(f.connection);f.holder().resetRollbackOnly();
            assertUnavailable(()->f.authorization.requireCurrent(scope));f.holder().resetRollbackOnly();return null;
        }));
    }

    @Test void aRealCapturedRequestFailureRetainsItsExistingRejectedClassification() {
        var f=new Fixture();
        assertThatThrownBy(()->f.read(boundary->{var scope=f.authorization.requireRead(f.request,boundary);f.request.close();scope.tenantId();return null;}))
                .isInstanceOf(SessionSensitiveException.class).satisfies(error->assertThat(((SessionSensitiveException)error).reason()).isEqualTo(SessionSensitiveException.Reason.REJECTED));
    }

    @Test void anUnknownOrNonReadonlyCallerCannotUseTheAuthorityToOpenAnotherTransaction() throws Exception {
        var outside=new Fixture();
        assertUnavailable(()->outside.authorization.requireRead(outside.request,mock(HumanSessionAuthorityTransaction.ReadBoundary.class)));
        assertThat(outside.statements).isEmpty();verify(outside.source,never()).getConnection();
        for(boolean readOnly:List.of(false,true)){
            var f=new Fixture();var transaction=new TransactionTemplate(f.manager);transaction.setReadOnly(readOnly);
            transaction.setIsolationLevel(readOnly?TransactionDefinition.ISOLATION_READ_COMMITTED:TransactionDefinition.ISOLATION_REPEATABLE_READ);
            assertUnavailable(()->transaction.execute(status->f.authorization.requireRead(f.request,mock(HumanSessionAuthorityTransaction.ReadBoundary.class))));
            assertThat(f.capabilityBindings).isEmpty();verify(f.source,times(1)).getConnection();
            verify(f.connection).rollback();verify(f.connection,never()).commit();
        }
    }

    @Test void callbackClosureRejectsScopeBeforeTheOuterHolderClosesAndSticksAfterReset() throws Exception {
        var f=new Fixture();var reached=new AtomicBoolean();
        assertThatThrownBy(()->f.inCaller(()->{
            var scope=f.callers.withRead(f.request,boundary->f.authorization.requireRead(f.request,boundary));
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();assertThat(f.holder()).isNotNull();
            reached.set(true);assertUnavailable(scope::tenantId);f.holder().resetRollbackOnly();
            assertUnavailable(()->f.authorization.requireCurrent(scope));f.holder().resetRollbackOnly();return null;
        })).isInstanceOf(RuntimeException.class);
        assertThat(reached.get()).isTrue();verify(f.connection).rollback();verify(f.connection,never()).commit();
    }

    @Test void replacingTheHolderPoisonsOnlyTheOriginalScopeAndCannotBeRestoredForCommit() {
        var f=new Fixture();
        assertUnavailable(()->f.read(boundary->{
            var scope=f.authorization.requireRead(f.request,boundary);var original=f.holder();var replacement=new ConnectionHolder(f.connection);
            TransactionSynchronizationManager.unbindResource(f.source);TransactionSynchronizationManager.bindResource(f.source,replacement);
            try { assertUnavailable(()->f.authorization.requireCurrent(scope));assertThat(original.isRollbackOnly()).isTrue();assertThat(replacement.isRollbackOnly()).isFalse(); }
            finally { TransactionSynchronizationManager.unbindResource(f.source);TransactionSynchronizationManager.bindResource(f.source,original); }
            original.resetRollbackOnly();assertUnavailable(scope::tenantId);original.resetRollbackOnly();return null;
        }));
    }

    @Test void aForgedBoundaryIsRejectedBeforeCheckedHumanAndCapabilitySql() {
        var f=new Fixture();
        assertUnavailable(()->f.read(boundary->f.authorization.requireRead(f.request,mock(HumanSessionAuthorityTransaction.ReadBoundary.class))));
        assertThat(f.capabilityBindings).isEmpty();verifyNoInteractions(f.keyProvider);
        assertThat(f.statements).noneMatch(sql->sql.startsWith("select state,version,")||sql.contains("from public.spring_session stored"));
    }

    @Test void anIncorrectStoredDigestIsVerifiedByTheRealKeyAdapterBeforeCapabilityReads() {
        var f=new Fixture();f.storedDigest="0".repeat(64);
        assertUnavailable(()->f.read(boundary->f.authorization.requireRead(f.request,boundary)));
        assertThat(f.statements.stream().filter(sql->sql.contains("from public.spring_session stored"))).hasSize(1);
        verify(f.keyProvider).requireRetainedKeys();assertThat(f.capabilityBindings).isEmpty();
        verifyNoInteractions(f.accounts,f.principals,f.members,f.applications);
    }

    private static void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable action){
        assertThatThrownBy(action).isInstanceOf(SessionSensitiveException.class)
                .satisfies(error->assertThat(((SessionSensitiveException)error).reason()).isEqualTo(SessionSensitiveException.Reason.UNAVAILABLE));
    }

    private static final class Fixture {
        final UUID actor=UUID.randomUUID(),account=UUID.randomUUID(),tenant=UUID.randomUUID(),member=UUID.randomUUID();
        final Instant authenticatedAt=Instant.parse("2026-11-07T00:00:00.123456Z"),cut=authenticatedAt.plusSeconds(60);
        final CurrentTenantContext context=new CurrentTenantContext(actor,account,tenant,member,2,3,4);
        final DataSource source=mock(DataSource.class);final Connection connection;
        final AtomicReference<Connection> physical=new AtomicReference<>();
        final AtomicBoolean autoCommit=new AtomicBoolean(true),readOnly=new AtomicBoolean(false);
        final AtomicInteger isolation=new AtomicInteger(Connection.TRANSACTION_READ_COMMITTED);
        final AtomicReference<String> appliedTenant=new AtomicReference<>("");
        final List<String> statements=new ArrayList<>();final List<Object[]> capabilityBindings=new ArrayList<>();
        final MockHttpServletRequest http=new MockHttpServletRequest();final CapturedHumanRequest request;
        final SessionSensitiveKeys keys=new SessionSensitiveKeys("test-key",Map.of("test-key",new byte[32]));
        final SessionSensitiveKeyProvider keyProvider=mock(SessionSensitiveKeyProvider.class);
        final AccountStatusReadRepository accounts=mock(AccountStatusReadRepository.class);
        final PrincipalStatusReadRepository principals=mock(PrincipalStatusReadRepository.class);
        final TenantMembershipReadRepository members=mock(TenantMembershipReadRepository.class);
        final TenantApplicationReadRepository applications=mock(TenantApplicationReadRepository.class);
        final DataSourceTransactionManager manager;final ManagedSessionSensitiveSecurityTransaction callers;
        final JooqCurrentHumanSessionAuthority human;final PersistentTenantAuditReadAuthorization authorization;
        // Independently precomputed synthetic HMAC-SHA256: zero32 test-key, session-binding domain, fixed session id.
        String storedDigest="12c447a7b9a9a51e1b9a3e8fa99df9bbeff57098fc44399ba936fbc00c531393";
        Object grant=true;String resultFault="",control="ENFORCING";
        Fixture(){
            var principal=VerifiedSessionPrincipalTestFixture.create(actor,account,tenant,member,2,3,4);
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,"",List.of()));
            http.setSession(new MockHttpSession(http.getServletContext(),"audit-fixture-session"));
            VerifiedSessionPrincipalTestFixture.markAuthenticatedSession(http.getSession(),authenticatedAt);
            request=new SessionSensitiveRequests(new SessionContextResolver(),Duration.ofHours(12)).capture(http);
            connection=spy(new MockConnection(this::execute));physical.set(connection);
            try{
                when(source.getConnection()).thenReturn(connection);doAnswer(call->physical.get()).when(connection).unwrap(Connection.class);
                doAnswer(call->autoCommit.get()).when(connection).getAutoCommit();doAnswer(call->{autoCommit.set(call.getArgument(0));return null;}).when(connection).setAutoCommit(anyBoolean());
                doAnswer(call->readOnly.get()).when(connection).isReadOnly();doAnswer(call->{readOnly.set(call.getArgument(0));return null;}).when(connection).setReadOnly(anyBoolean());
                doAnswer(call->isolation.get()).when(connection).getTransactionIsolation();doAnswer(call->{isolation.set(call.getArgument(0));return null;}).when(connection).setTransactionIsolation(anyInt());
            }catch(SQLException failure){throw new AssertionError(failure);}
            when(keyProvider.requireRetainedKeys()).thenReturn(keys);
            when(accounts.findState(account)).thenReturn(Optional.of(new AccountSecurityState("ACTIVE",4)));
            when(principals.findByActorId(actor)).thenReturn(Optional.of(new PrincipalStatus(actor,account,"HUMAN","ACTIVE")));
            when(members.findCurrentMembership(tenant,account,member)).thenReturn(Optional.of(new TenantMembershipState("ACTIVE","ACTIVE",2,3)));
            when(applications.findCurrentInCaller(tenant,"system")).thenReturn(Optional.of(new TenantApplicationState(true,1)));
            manager=new DataSourceTransactionManager(source);callers=new ManagedSessionSensitiveSecurityTransaction(source,manager,new PostgresAuthorizationWriteGuard(source));
            human=new JooqCurrentHumanSessionAuthority(source,callers,keyProvider,true);authorization=newAuthorization();
        }
        PersistentTenantAuditReadAuthorization newAuthorization(){return new PersistentTenantAuditReadAuthorization(source,callers,human,accounts,principals,members,applications);}
        ConnectionHolder holder(){return (ConnectionHolder)TransactionSynchronizationManager.getResource(source);}
        <T>T read(Function<HumanSessionAuthorityTransaction.ReadBoundary,T> work){ return inCaller(()->callers.withRead(request,work)); }
        <T>T inCaller(Supplier<T> work){
            var transaction=new TransactionTemplate(manager);transaction.setReadOnly(true);transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);transaction.setTimeout(10);
            return transaction.execute(status->{
                try(var statement=DataSourceUtils.getConnection(source).prepareStatement("select set_config('app.tenant_id', ?, true)")){
                    statement.setString(1,tenant.toString());try(var rows=statement.executeQuery()){assertThat(rows.next()).isTrue();}
                }catch(SQLException failure){throw new IllegalStateException(failure);}
                return work.get();
            });
        }
        MockResult[] execute(MockExecuteContext execution)throws SQLException{
            String sql=execution.sql().stripLeading();statements.add(sql);
            if(sql.contains("set_config('app.tenant_id'")){appliedTenant.set(execution.bindings()[0].toString());return row(Map.of("set_config",appliedTenant.get()),1);}
            if(sql.equals("select current_setting('transaction_isolation')"))return row(Map.of("value",isolation.get()==Connection.TRANSACTION_REPEATABLE_READ?"repeatable read":"read committed"),1);
            if(sql.equals("select current_setting('transaction_read_only')"))return row(Map.of("value",readOnly.get()?"on":"off"),1);
            if(sql.equals("select current_setting('app.tenant_id',true)"))return row(Map.of("value",appliedTenant.get()),1);
            if(sql.equals("select transaction_timestamp()"))return row(Map.of("value",cut.atOffset(ZoneOffset.UTC)),1);
            if(sql.startsWith("select state,version,")){
                Map<String,Object> values=new LinkedHashMap<>();boolean enforcing=control.equals("ENFORCING");
                values.put("state",enforcing?"ENFORCING":"OFF");values.put("version",enforcing?1L:0L);
                values.put("activated_at",enforcing?cut.minusSeconds(1).atOffset(ZoneOffset.UTC):null);values.put("activated_by_database_role",enforcing?"test-migrator":null);
                values.put("activation_event_id",enforcing?new UUID(1,1):null);values.put("activation_evidence_sha256",enforcing?"a".repeat(64):null);
                return row(values,control.equals("MISSING_CONTROL")?0:1);
            }
            if(sql.contains("from public.spring_session stored"))return row(Map.of("primary_id",new UUID(9,9).toString(),"session_key_id","test-key",
                    "session_digest",storedDigest,"authenticated_at",authenticatedAt.atOffset(ZoneOffset.UTC),
                    "absolute_expires_at",authenticatedAt.plus(Duration.ofHours(12)).atOffset(ZoneOffset.UTC)),1);
            if(sql.equals(PersistentTenantAuditReadAuthorization.CAPABILITY_SQL.stripLeading())){
                capabilityBindings.add(execution.bindings().clone());if(resultFault.equals("SQL_FAILURE"))throw new SQLException("synthetic grant failure");
                var values=new LinkedHashMap<String,Object>();values.put("authorized",resultFault.equals("NULL")?null:resultFault.equals("STRING")?"true":grant);
                if(resultFault.equals("TWO_COLUMNS"))values.put("extra",true);
                return row(values,resultFault.equals("ZERO_ROWS")?0:resultFault.equals("TWO_ROWS")?2:1);
            }
            throw new SQLException("Unexpected synthetic statement");
        }
        @SuppressWarnings({"rawtypes","unchecked"}) static MockResult[] row(Map<String,Object> values,int count){
            DSLContext db=DSL.using(SQLDialect.POSTGRES);Field<?>[] fields=values.entrySet().stream().map(e->DSL.field(DSL.name(e.getKey()),(Class)(e.getValue()==null?String.class:e.getValue().getClass()))).toArray(Field<?>[]::new);
            Result<Record> result=db.newResult(fields);
            for(int i=0;i<count;i++){Record row=db.newRecord(fields);for(Field field:fields)row.set(field,values.get(field.getName()));result.add(row);}
            return new MockResult[]{new MockResult(count,result)};
        }
    }
}

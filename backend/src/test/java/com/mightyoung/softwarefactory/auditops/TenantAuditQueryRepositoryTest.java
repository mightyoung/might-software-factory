package com.mightyoung.softwarefactory.auditops;

import com.mightyoung.softwarefactory.authorization.*;
import com.mightyoung.softwarefactory.identity.*;
import com.mightyoung.softwarefactory.tenancy.*;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SQLDialect;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.mightyoung.softwarefactory.auditops.TenantAuditQueryContracts.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real identity/Managed/authority adapters with controlled JDBC rows; no real PostgreSQL or grants. */
class TenantAuditQueryRepositoryTest {
    @AfterEach void clear(){SecurityContextHolder.clearContext();TransactionSynchronizationManager.clear();}
    @Test void fixedProjectionBindsEveryFilterAndCursorWithoutReadingAuditBodies() throws Exception {
        var f=new Fixture();var at=f.cut.minusSeconds(2);var id=new UUID(0,4);var resource=new UUID(9,9);
        var row=f.auditRow(id,at);row.put("resource_id",resource);f.auditRows.add(row);
        Window window=new Window(f.authenticatedAt,f.cut);
        Query query=new Query(window.from(),window.to(),f.actor,"finding",resource,"finding.create",Result.SUCCESS,20,new Cursor(f.cut.minusSeconds(1),new UUID(0,5),"a".repeat(64)));
        var items=f.read(boundary->new TenantAuditQueryRepository(f.source,f.authorization).query(f.authorization.requireRead(f.request,boundary),window,query));
        assertThat(items).singleElement().satisfies(item->{assertThat(item.id()).isEqualTo(id);assertThat(item.resourceId()).isEqualTo(resource);});
        assertThatThrownBy(items::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThat(f.queryBindings).hasSize(1);Object[] bindings=f.queryBindings.getFirst();assertThat(bindings).hasSize(17);
        assertThat(bindings).containsExactly(f.tenant,window.from().atOffset(ZoneOffset.UTC),window.to().atOffset(ZoneOffset.UTC),f.actor,f.actor,"finding","finding",resource,resource,
                "finding.create","finding.create","SUCCESS","SUCCESS",query.cursor().occurredAt().atOffset(ZoneOffset.UTC),query.cursor().occurredAt().atOffset(ZoneOffset.UTC),query.cursor().id(),21);
        assertThat(TenantAuditQueryRepository.QUERY_SQL).doesNotContain("reason","diff_redacted","count(","offset","select *").contains("order by occurred_at desc,id desc limit ?");
        verify(f.source,times(1)).getConnection();
    }
    @ParameterizedTest @ValueSource(strings={"legacy-text","FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF","", "not-a-trace-secret"})
    void legacyTraceTextBecomesNullWithoutRejectingTheValidAuditRow(String trace){
        var f=new Fixture();var row=f.auditRow(new UUID(0,1),f.cut.minusSeconds(1));row.put("trace_id",trace);f.auditRows.add(row);
        var items=f.read(boundary->new TenantAuditQueryRepository(f.source,f.authorization).query(f.authorization.requireRead(f.request,boundary),new Window(f.authenticatedAt,f.cut),query(f,20)));
        assertThat(items).singleElement().satisfies(item->assertThat(item.traceId()).isNull());
    }
    @ParameterizedTest @ValueSource(strings={"TENANT","ACTOR","TIME","RESULT","ACTION","DUPLICATE","ORDER","FILTER","COLUMNS","SHUFFLE","SQL"})
    void corruptOrInconsistentStorageFailsAs503WithoutPartialRows(String fault){
        var f=new Fixture();var row=f.auditRow(new UUID(0,2),f.cut.minusSeconds(2));f.auditRows.add(row);
        Query query=query(f,20);
        switch(fault){
            case "TENANT"->row.put("tenant_id",new UUID(99,99));
            case "ACTOR"->row.put("actor_id",null);
            case "TIME"->row.put("occurred_at",f.cut.atOffset(ZoneOffset.UTC));
            case "RESULT"->row.put("result","UNKNOWN");
            case "ACTION"->row.put("action","unsafe body text");
            case "DUPLICATE"->f.auditRows.add(f.auditRow(new UUID(0,2),f.cut.minusSeconds(3)));
            case "ORDER"->f.auditRows.add(f.auditRow(new UUID(0,3),f.cut.minusSeconds(1)));
            case "FILTER"->query=new Query(f.authenticatedAt,f.cut,new UUID(88,88),null,null,null,null,20,null);
            default->f.readFault=fault;
        }
        Query selected=query;
        assertThatThrownBy(()->f.read(boundary->new TenantAuditQueryRepository(f.source,f.authorization).query(f.authorization.requireRead(f.request,boundary),new Window(f.authenticatedAt,f.cut),selected)))
                .isExactlyInstanceOf(TenantAuditQueryService.Unavailable.class).hasMessage("Tenant audit query is unavailable");
        assertThat(f.queryBindings).hasSize(1);
    }
    @Test void maxRowsAndPostgresUnsignedUuidOrderingAreCheckedOnTheActualResult(){
        var f=new Fixture();var at=f.cut.minusSeconds(1);UUID high=UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"),low=UUID.fromString("77777777-7777-7777-7777-777777777777");
        f.auditRows.add(f.auditRow(high,at));f.auditRows.add(f.auditRow(low,at));
        var items=f.read(boundary->new TenantAuditQueryRepository(f.source,f.authorization).query(f.authorization.requireRead(f.request,boundary),new Window(f.authenticatedAt,f.cut),query(f,1)));
        assertThat(items).extracting(Item::id).containsExactly(high,low);
        var overflow=new Fixture();for(int i=3;i>0;i--)overflow.auditRows.add(overflow.auditRow(new UUID(0,i),overflow.cut.minusSeconds(4-i)));
        assertThatThrownBy(()->overflow.read(boundary->new TenantAuditQueryRepository(overflow.source,overflow.authorization).query(overflow.authorization.requireRead(overflow.request,boundary),new Window(overflow.authenticatedAt,overflow.cut),query(overflow,1))))
                .isExactlyInstanceOf(TenantAuditQueryService.Unavailable.class);
    }
    @Test void mismatchedOrFutureInternalWindowsFailBeforeAuditSql(){
        for(boolean future:List.of(false,true)){
            var f=new Fixture();var window=future?new Window(f.authenticatedAt,f.cut.plusSeconds(1)):new Window(f.authenticatedAt.plusSeconds(1),f.cut);
            assertThatThrownBy(()->f.read(boundary->new TenantAuditQueryRepository(f.source,f.authorization).query(f.authorization.requireRead(f.request,boundary),window,query(f,20))))
                    .isExactlyInstanceOf(TenantAuditQueryService.Unavailable.class);assertThat(f.queryBindings).isEmpty();
        }
    }
    static Query query(Fixture fixture,int size){return new Query(fixture.authenticatedAt,fixture.cut,null,null,null,null,null,size,null);}
    static final class Fixture {
        final UUID actor=new UUID(1,1),account=new UUID(2,2),tenant=new UUID(3,3),member=new UUID(4,4);
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
        final List<Map<String,Object>> auditRows=new ArrayList<>();final List<Object[]> queryBindings=new ArrayList<>();
        String readFault="",timeoutFault="",timeoutDisplay="2s";int timeoutChecks;boolean wrongTenantSetting;
        Runnable beforeAuditRead=()->{};
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
            if(sql.equals(TenantAuditQueryService.SET_TENANT_SQL)){appliedTenant.set(execution.bindings()[0].toString());return row(Map.of("set_config",appliedTenant.get()),1);}
            if(sql.equals(TenantAuditQueryService.SET_TIMEOUT_SQL))return row(Map.of("set_config",timeoutDisplay),1);
            if(sql.equals(TenantAuditQueryService.VERIFY_TENANT_SQL))return row(Map.of("value",wrongTenantSetting?"foreign":appliedTenant.get()),1);
            if(sql.equals(TenantAuditQueryService.VERIFY_TIMEOUT_SQL)){
                timeoutChecks++;if(timeoutFault.equals("SQL"))throw new SQLException("synthetic timeout read failure");
                var values=new LinkedHashMap<String,Object>();values.put("timeout_ok",timeoutFault.equals("NULL")?null:timeoutFault.equals("STRING")?"true":
                        timeoutFault.equals("FALSE")||timeoutFault.equals("FINAL_DRIFT")&&timeoutChecks>1?false:true);
                if(timeoutFault.equals("TWO_COLUMNS"))values.put("extra",true);
                return row(values,timeoutFault.equals("ZERO_ROWS")?0:timeoutFault.equals("TWO_ROWS")?2:1);
            }
            if(sql.equals(TenantAuditQueryRepository.QUERY_SQL.stripLeading())){
                queryBindings.add(execution.bindings().clone());beforeAuditRead.run();if(readFault.equals("SQL"))throw new SQLException("synthetic audit failure");return auditResult();
            }
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
            if(sql.contains("from public.spring_session stored")){
                var values=new LinkedHashMap<String,Object>();
                values.put("primary_id",new UUID(9,9).toString());values.put("session_key_id","test-key");values.put("session_digest",storedDigest);
                values.put("authenticated_at",authenticatedAt.atOffset(ZoneOffset.UTC));
                values.put("absolute_expires_at",authenticatedAt.plus(Duration.ofHours(12)).atOffset(ZoneOffset.UTC));
                assertThat(values.keySet()).containsExactly("primary_id","session_key_id","session_digest","authenticated_at","absolute_expires_at");
                return row(values,1);
            }
            if(sql.contains("p.code='audit_event.read'")){
                capabilityBindings.add(execution.bindings().clone());if(resultFault.equals("SQL_FAILURE"))throw new SQLException("synthetic grant failure");
                var values=new LinkedHashMap<String,Object>();values.put("authorized",resultFault.equals("NULL")?null:resultFault.equals("STRING")?"true":grant);
                if(resultFault.equals("TWO_COLUMNS"))values.put("extra",true);
                return row(values,resultFault.equals("ZERO_ROWS")?0:resultFault.equals("TWO_ROWS")?2:1);
            }
            throw new SQLException("Unexpected synthetic statement");
        }
        Map<String,Object> auditRow(UUID id,Instant at){
            var values=new LinkedHashMap<String,Object>();values.put("tenant_id",tenant);values.put("id",id);values.put("actor_id",actor);
            values.put("delegator_id",null);values.put("action","finding.create");values.put("resource_type","finding");values.put("resource_id",null);
            values.put("result","SUCCESS");values.put("trace_id",new UUID(20,20).toString());values.put("policy_version","v1");values.put("occurred_at",at.atOffset(ZoneOffset.UTC));return values;
        }
        @SuppressWarnings({"rawtypes","unchecked"}) MockResult[] auditResult(){
            Map<String,Object> prototype=new LinkedHashMap<>(auditRows.isEmpty()?auditRow(new UUID(0,1),cut.minusSeconds(1)):auditRows.getFirst());
            if(readFault.equals("COLUMNS"))prototype.put("extra",true);
            if(readFault.equals("SHUFFLE")){Object value=prototype.remove("tenant_id");prototype.put("tenant_id",value);}
            DSLContext db=DSL.using(SQLDialect.POSTGRES);Field<?>[] fields=prototype.entrySet().stream().map(e->DSL.field(DSL.name(e.getKey()),(Class)(e.getValue()==null?String.class:e.getValue().getClass()))).toArray(Field<?>[]::new);
            org.jooq.Result<Record> result=db.newResult(fields);
            for(var values:auditRows){Record row=db.newRecord(fields);for(Field field:fields)row.set(field,values.get(field.getName()));result.add(row);}
            return new MockResult[]{new MockResult(result.size(),result)};
        }
        @SuppressWarnings({"rawtypes","unchecked"}) static MockResult[] row(Map<String,Object> values,int count){
            DSLContext db=DSL.using(SQLDialect.POSTGRES);Field<?>[] fields=values.entrySet().stream().map(e->DSL.field(DSL.name(e.getKey()),(Class)(e.getValue()==null?String.class:e.getValue().getClass()))).toArray(Field<?>[]::new);
            org.jooq.Result<Record> result=db.newResult(fields);
            for(int i=0;i<count;i++){Record row=db.newRecord(fields);for(Field field:fields)row.set(field,values.get(field.getName()));result.add(row);}
            return new MockResult[]{new MockResult(count,result)};
        }
    }
}

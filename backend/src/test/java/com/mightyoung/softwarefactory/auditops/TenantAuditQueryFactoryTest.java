package com.mightyoung.softwarefactory.auditops;

import com.mightyoung.softwarefactory.identity.*;
import com.mightyoung.softwarefactory.tenancy.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;
import static com.mightyoung.softwarefactory.auditops.TenantAuditQueryContracts.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real private graph/readers over controlled JDBC. No PostgreSQL/RLS/role/grant or HTTP acceptance. */
class TenantAuditQueryFactoryTest {
    @AfterEach void clear(){SecurityContextHolder.clearContext();TransactionSynchronizationManager.clear();}

    @Test void everyRealReaderUsesOnePhysicalLeaseAndOnlyTheOwnerCommitsAndClosesBeforeOutput() throws Exception {
        var f=new Fixture();var service=TenantAuditQueryFactory.create(f.source,f.keyProvider);
        verify(f.source,never()).getConnection();
        var result=service.query(f.request,query(),new UUID(30,30),new UUID(31,31));
        assertThat(f.readers).containsExactly("account","principal","membership","application");
        assertThat(f.capabilities.get()).isEqualTo(1);assertThat(f.auditReads.get()).isEqualTo(1);
        assertThat(f.checkedSql.get()).isEqualTo(f.statements.size());verify(f.source,times(1)).getConnection();
        verify(f.connection,times(1)).commit();verify(f.connection,never()).rollback();verify(f.connection,times(1)).close();
        assertThat(f.events).containsExactly("commit","close");assertThat(f.appBindings).hasSize(1);
        assertThat(f.appBindings.getFirst()).containsExactly(f.tenant,"system");
        int before=f.statements.size();var output=new ByteArrayOutputStream();result.writeTo(output);
        assertThat(result.size()).isEqualTo(output.size());assertThat(f.statements).hasSize(before);
        var json=JsonMapper.builder().build().readTree(output.toByteArray());
        assertThat(json.get("data").get("items").size()).isEqualTo(1);
        assertThat(json.get("data").get("items").get(0).get("actorId").stringValue()).isEqualTo(f.actor.toString());
    }

    @ParameterizedTest @ValueSource(strings={"ACCOUNT_DISABLED","CREDENTIAL","PRINCIPAL_BINDING","PRINCIPAL_KIND","TENANT_EPOCH","MEMBER_EPOCH","SYSTEM_MISSING","SYSTEM_DISABLED"})
    void realReaderIdentityEpochAndSystemFailuresDenyBeforeCapabilityAndAuditSql(String fault) throws Exception {
        var f=new Fixture();f.fault=fault;var service=TenantAuditQueryFactory.create(f.source,f.keyProvider);
        assertThatThrownBy(()->service.query(f.request,query(),new UUID(30,30),new UUID(31,31))).isExactlyInstanceOf(AccessDeniedException.class);
        String reached=fault.startsWith("ACCOUNT")||fault.equals("CREDENTIAL")?"account":fault.startsWith("PRINCIPAL")?"principal":
                fault.startsWith("SYSTEM")?"application":"membership";
        assertThat(f.readers).contains(reached);assertThat(f.capabilities.get()).isZero();assertThat(f.auditReads.get()).isZero();
        assertThat(f.checkedSql.get()).isEqualTo(f.statements.size());verify(f.source,times(1)).getConnection();
        verify(f.connection).rollback();verify(f.connection,never()).commit();verify(f.connection).close();
        assertThat(f.events).containsExactly("rollback","close");
    }

    @Test void malformedSystemVersionIsUnavailableAfterTheActualApplicationReader() {
        var f=new Fixture();f.fault="SYSTEM_VERSION";
        assertThatThrownBy(()->TenantAuditQueryFactory.create(f.source,f.keyProvider).query(f.request,query(),new UUID(30,30),new UUID(31,31)))
                .isInstanceOf(SessionSensitiveException.class).satisfies(error->assertThat(((SessionSensitiveException)error).reason()).isEqualTo(SessionSensitiveException.Reason.UNAVAILABLE));
        assertThat(f.readers).contains("application");assertThat(f.capabilities.get()).isZero();assertThat(f.auditReads.get()).isZero();
    }

    @Test void missingRetainedKeysCannotBeHiddenByThePrivateGraph() {
        var f=new Fixture();when(f.keyProvider.requireRetainedKeys()).thenThrow(SessionSensitiveException.unavailable());
        assertThatThrownBy(()->TenantAuditQueryFactory.create(f.source,f.keyProvider).query(f.request,query(),new UUID(30,30),new UUID(31,31)))
                .isInstanceOf(SessionSensitiveException.class).satisfies(error->assertThat(((SessionSensitiveException)error).reason()).isEqualTo(SessionSensitiveException.Reason.UNAVAILABLE));
        assertThat(f.statements).anyMatch(sql->sql.startsWith("select state,version,"));
        verify(f.keyProvider).requireRetainedKeys();assertThat(f.readers).isEmpty();assertThat(f.auditReads.get()).isZero();
    }

    @Test void absentConstructionDependenciesAreRejectedWithoutBorrowingAConnection() throws Exception {
        var source=mock(DataSource.class);var keys=mock(SessionSensitiveKeyProvider.class);
        assertThatThrownBy(()->TenantAuditQueryFactory.create(null,keys)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(()->TenantAuditQueryFactory.create(source,null)).isInstanceOf(NullPointerException.class);
        verifyNoInteractions(source,keys);
    }

    @Test void aRealAccountReaderSqlFailureIsUnavailableBeforeCapabilityAndAudit() throws Exception {
        var f=new Fixture();f.fault="ACCOUNT_SQL";
        assertThatThrownBy(()->TenantAuditQueryFactory.create(f.source,f.keyProvider).query(f.request,query(),new UUID(30,30),new UUID(31,31)))
                .isInstanceOf(SessionSensitiveException.class).satisfies(error->assertThat(((SessionSensitiveException)error).reason()).isEqualTo(SessionSensitiveException.Reason.UNAVAILABLE));
        assertThat(f.readers).containsExactly("account");assertThat(f.capabilities.get()).isZero();assertThat(f.auditReads.get()).isZero();
        verify(f.source,times(1)).getConnection();verify(f.connection).rollback();verify(f.connection,never()).commit();verify(f.connection).close();
    }

    @Test void hostileOutputCannotMutateOwnedBytesOrReenterSqlOrBorrowAnotherConnection() throws Exception {
        var f=new Fixture();var result=TenantAuditQueryFactory.create(f.source,f.keyProvider).query(f.request,query(),new UUID(30,30),new UUID(31,31));
        int sqlCount=f.statements.size();var expected=new ByteArrayOutputStream();result.writeTo(expected);
        var captured=new ByteArrayOutputStream();
        result.writeTo(new OutputStream(){
            @Override public void write(int value){captured.write(value);}
            @Override public void write(byte[] values,int offset,int length){captured.write(values,offset,length);Arrays.fill(values,(byte)0);}
        });
        assertThat(captured.toByteArray()).containsExactly(expected.toByteArray());
        IOException failure=new IOException("synthetic hostile sink");
        assertThatThrownBy(()->result.writeTo(new OutputStream(){
            @Override public void write(int value)throws IOException{throw failure;}
            @Override public void write(byte[] values,int offset,int length)throws IOException{throw failure;}
        })).isSameAs(failure);
        var after=new ByteArrayOutputStream();result.writeTo(after);assertThat(after.toByteArray()).containsExactly(expected.toByteArray());
        assertThat(f.statements).hasSize(sqlCount);verify(f.source,times(1)).getConnection();verify(f.connection,times(1)).commit();verify(f.connection,times(1)).close();
    }

    private static Query query(){return new Query(null,null,null,null,null,null,null,20,null);}
    private static final class Fixture {
        final UUID actor=new UUID(1,1),account=new UUID(2,2),tenant=new UUID(3,3),member=new UUID(4,4);
        final Instant authenticatedAt=Instant.parse("2026-11-07T00:00:00.123456Z"),cut=authenticatedAt.plusSeconds(60);
        final DataSource source=mock(DataSource.class);final Connection connection;
        final AtomicBoolean autoCommit=new AtomicBoolean(true),readOnly=new AtomicBoolean(false);
        final AtomicInteger isolation=new AtomicInteger(Connection.TRANSACTION_READ_COMMITTED);
        final AtomicReference<String> appliedTenant=new AtomicReference<>("");
        final AtomicInteger checkedSql=new AtomicInteger(),capabilities=new AtomicInteger(),auditReads=new AtomicInteger();
        final List<String> statements=new ArrayList<>(),readers=new ArrayList<>(),events=new ArrayList<>();
        final List<Object[]> appBindings=new ArrayList<>();
        final MockHttpServletRequest http=new MockHttpServletRequest();final CapturedHumanRequest request;
        final SessionSensitiveKeyProvider keyProvider=mock(SessionSensitiveKeyProvider.class);
        final SessionSensitiveKeys keys=new SessionSensitiveKeys("test-key",Map.of("test-key",new byte[32]));
        final String accountSql,principalSql,memberSql,applicationSql;
        String fault="";
        Fixture(){
            var principal=VerifiedSessionPrincipalTestFixture.create(actor,account,tenant,member,2,3,4);
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,"",List.of()));
            http.setSession(new MockHttpSession(http.getServletContext(),"audit-fixture-session"));
            VerifiedSessionPrincipalTestFixture.markAuthenticatedSession(http.getSession(),authenticatedAt);
            request=new SessionSensitiveRequests(new SessionContextResolver(),Duration.ofHours(12)).capture(http);
            var renderer=DSL.using(SQLDialect.POSTGRES,new Settings().withExecuteLogging(false).withFetchWarnings(false));
            // Exports select the exact fixed SQL only. Returned projections below are explicitly declared,
            // never synthesized by parsing the incoming SQL or its requested field names.
            accountSql=JooqAccountStatusReadRepository.commandReadSql(renderer).keySet().iterator().next().stripLeading();
            principalSql=JooqPrincipalStatusReadRepository.commandReadSql().keySet().iterator().next().stripLeading();
            memberSql=JooqTenantMembershipReadRepository.commandReadSql().keySet().iterator().next().stripLeading();
            applicationSql=JooqTenantApplicationReadRepository.commandReadSql().keySet().stream().filter(sql->sql.contains("tenant_application")).findFirst().orElseThrow().stripLeading();
            connection=spy(new MockConnection(this::execute));
            try{
                when(source.getConnection()).thenReturn(connection);doReturn(connection).when(connection).unwrap(Connection.class);
                doAnswer(call->autoCommit.get()).when(connection).getAutoCommit();doAnswer(call->{autoCommit.set(call.getArgument(0));return null;}).when(connection).setAutoCommit(anyBoolean());
                doAnswer(call->readOnly.get()).when(connection).isReadOnly();doAnswer(call->{readOnly.set(call.getArgument(0));return null;}).when(connection).setReadOnly(anyBoolean());
                doAnswer(call->isolation.get()).when(connection).getTransactionIsolation();doAnswer(call->{isolation.set(call.getArgument(0));return null;}).when(connection).setTransactionIsolation(anyInt());
                doAnswer(call->{events.add("commit");return call.callRealMethod();}).when(connection).commit();
                doAnswer(call->{events.add("rollback");return call.callRealMethod();}).when(connection).rollback();
                doAnswer(call->{assertThat(TransactionSynchronizationManager.hasResource(source)).isFalse();events.add("close");return call.callRealMethod();}).when(connection).close();
            }catch(SQLException failure){throw new AssertionError(failure);}
            when(keyProvider.requireRetainedKeys()).thenReturn(keys);
        }
        MockResult[] execute(MockExecuteContext execution)throws SQLException{
            String sql=execution.sql().stripLeading();statements.add(sql);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            assertThat(TransactionSynchronizationManager.getResource(source)).isInstanceOf(ConnectionHolder.class);
            var holder=(ConnectionHolder)TransactionSynchronizationManager.getResource(source);
            assertThat(holder.getConnection()).isSameAs(connection);assertThat(connection.unwrap(Connection.class)).isSameAs(connection);
            assertThat(autoCommit.get()).isFalse();assertThat(readOnly.get()).isTrue();assertThat(isolation.get()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
            assertThat(events).doesNotContain("close");checkedSql.incrementAndGet();
            if(sql.equals(TenantAuditQueryService.SET_TENANT_SQL)){appliedTenant.set(execution.bindings()[0].toString());return row(values("set_config",appliedTenant.get()),1);}
            if(sql.equals(TenantAuditQueryService.SET_TIMEOUT_SQL))return row(values("set_config","2s"),1);
            if(sql.equals(TenantAuditQueryService.VERIFY_TENANT_SQL)||sql.equals("select current_setting('app.tenant_id',true)"))return row(values("value",appliedTenant.get()),1);
            if(sql.equals(TenantAuditQueryService.VERIFY_TIMEOUT_SQL))return row(values("timeout_ok",true),1);
            if(sql.equals("select current_setting('transaction_isolation')"))return row(values("value","repeatable read"),1);
            if(sql.equals("select current_setting('transaction_read_only')"))return row(values("value","on"),1);
            if(sql.equals("select transaction_timestamp()"))return row(values("value",cut.atOffset(ZoneOffset.UTC)),1);
            if(sql.startsWith("select state,version,"))return row(values("state","ENFORCING","version",1L,"activated_at",cut.minusSeconds(1).atOffset(ZoneOffset.UTC),
                    "activated_by_database_role","test-migrator","activation_event_id",new UUID(1,1),"activation_evidence_sha256","a".repeat(64)),1);
            if(sql.contains("from public.spring_session stored"))return row(values("primary_id",new UUID(9,9).toString(),"session_key_id","test-key",
                    // Independently precomputed synthetic zero32 HMAC for audit-fixture-session.
                    "session_digest","12c447a7b9a9a51e1b9a3e8fa99df9bbeff57098fc44399ba936fbc00c531393",
                    "authenticated_at",authenticatedAt.atOffset(ZoneOffset.UTC),"absolute_expires_at",authenticatedAt.plus(Duration.ofHours(12)).atOffset(ZoneOffset.UTC)),1);
            if(sql.equals(accountSql)){
                readers.add("account");assertThat(execution.bindings()).containsExactly(account);
                if(fault.equals("ACCOUNT_SQL"))throw new SQLException("synthetic account reader SQL failure");
                return row(values("status",fault.equals("ACCOUNT_DISABLED")?"DISABLED":"ACTIVE","credential_version",fault.equals("CREDENTIAL")?5L:4L),1);
            }
            if(sql.equals(principalSql)){
                readers.add("principal");assertThat(execution.bindings()).containsExactly(actor);
                return row(values("principal_id",actor,"principal_account_id",fault.equals("PRINCIPAL_BINDING")?new UUID(99,99):account,
                        "principal_kind",fault.equals("PRINCIPAL_KIND")?"AGENT":"HUMAN","principal_status","ACTIVE"),1);
            }
            if(sql.equals(memberSql)){
                readers.add("membership");assertThat(execution.bindings()).containsExactly(tenant,account,member);
                return row(values("tenant_status","ACTIVE","member_status","ACTIVE","tenant_auth_epoch",fault.equals("TENANT_EPOCH")?9L:2L,
                        "member_auth_epoch",fault.equals("MEMBER_EPOCH")?9L:3L),1);
            }
            if(sql.equals(applicationSql)){
                readers.add("application");appBindings.add(execution.bindings().clone());assertThat(execution.bindings()).containsExactly(tenant,"system");
                return row(values("enabled",!fault.equals("SYSTEM_DISABLED"),"version",fault.equals("SYSTEM_VERSION")?0L:1L),fault.equals("SYSTEM_MISSING")?0:1);
            }
            if(sql.contains("p.code='audit_event.read'")){capabilities.incrementAndGet();return row(values("authorized",true),1);}
            if(sql.equals(TenantAuditQueryRepository.QUERY_SQL.stripLeading())){
                auditReads.incrementAndGet();return row(values("tenant_id",tenant,"id",new UUID(0,1),"actor_id",actor,"delegator_id",null,
                        "action","finding.create","resource_type","finding","resource_id",null,"result","SUCCESS","trace_id",null,
                        "policy_version","v1","occurred_at",cut.minusSeconds(1).atOffset(ZoneOffset.UTC)),1);
            }
            throw new SQLException("Unexpected synthetic private-graph statement");
        }
        static LinkedHashMap<String,Object> values(Object... pairs){
            var values=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)values.put((String)pairs[i],pairs[i+1]);return values;
        }
        @SuppressWarnings({"rawtypes","unchecked"}) static MockResult[] row(LinkedHashMap<String,Object> values,int count){
            DSLContext db=DSL.using(SQLDialect.POSTGRES);Field<?>[] fields=values.entrySet().stream().map(e->DSL.field(DSL.name(e.getKey()),(Class)(e.getValue()==null?String.class:e.getValue().getClass()))).toArray(Field<?>[]::new);
            org.jooq.Result<Record> result=db.newResult(fields);for(int i=0;i<count;i++){Record record=db.newRecord(fields);for(Field field:fields)record.set(field,values.get(field.getName()));result.add(record);}
            return new MockResult[]{new MockResult(count,result)};
        }
    }
}

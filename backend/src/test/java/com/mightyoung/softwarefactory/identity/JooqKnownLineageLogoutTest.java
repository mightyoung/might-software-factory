package com.mightyoung.softwarefactory.identity;

import org.jooq.*;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** SQL/ownership contract through mock JDBC, not PostgreSQL or committed-transaction evidence. */
class JooqKnownLineageLogoutTest {
    final DataSource source=mock(DataSource.class);
    final SessionSensitiveSecurityTransaction boundaries=mock(SessionSensitiveSecurityTransaction.class);
    final SessionSensitiveSecurityTransaction.LogoutBoundary proof=mock(SessionSensitiveSecurityTransaction.LogoutBoundary.class);
    final UUID account=UUID.randomUUID(),event=UUID.randomUUID();
    final SessionHandle handle=new SessionHandle(UUID.randomUUID());
    final SessionSensitiveKeys keys=new SessionSensitiveKeys("k1",Map.of("k1",new byte[32],"k2",new byte[33]));
    final Instant now=Instant.parse("2026-11-04T00:01:00Z");
    final List<String> sqls=new ArrayList<>();
    KnownLineageLogoutCapture capture;SessionSensitiveSecurityTransaction.LogoutScope scope;JooqSessionAuthorityStore store;MockConnection connection;
    boolean canonical=true,retained,unknownKey,ambiguous,failLookup,replay;int writes;
    @BeforeEach void setup()throws Exception{
        SecurityContextHolder.clearContext();MockHttpServletRequest request=new MockHttpServletRequest();
        request.setCookies(new jakarta.servlet.http.Cookie("SESSION","owned-cookie-bearer"));request.setRequestedSessionId("owned-cookie-bearer");request.setRequestedSessionIdFromCookie(true);
        capture=new KnownLogoutRequests(serializer->serializer.setUseBase64Encoding(false)).capture(request);scope=SessionSensitiveSecurityTransaction.LogoutScope.knownLineage(capture);
        when(proof.observedAt()).thenReturn(now);
        connection=new MockConnection(execution->{
            String sql=execution.sql().stripLeading();sqls.add(sql);
            if(sql.startsWith("select state,version,"))return row(Map.of("state","ENFORCING","version",1L,"activated_at",offset(now.minusSeconds(1)),
                    "activated_by_database_role","migrator","activation_event_id",event,"activation_evidence_sha256","a".repeat(64)));
            if(sql.startsWith("select distinct session_key_id"))return row(Map.of("session_key_id",unknownKey?"missing-retained-key":"k1"));
            if(sql.startsWith("select primary_id,session_id,principal_name")){
                if(failLookup)throw new SQLException("Synthetic lookup dependency failure");
                return canonical?row(Map.of("primary_id",handle.toString(),"session_id",capture.bearerId(),"principal_name",account.toString())):empty();
            }
            if(sql.startsWith("select * from public.account_session_authority where session_key_id")){
                if(!retained)return empty();
                if("k1".equals(execution.bindings()[0]))return authority(handle,account);
                return ambiguous?authority(new SessionHandle(UUID.randomUUID()),UUID.randomUUID()):empty();
            }
            if(sql.startsWith("select id from public.account"))return row(Map.of("id",account,"status","DISABLED","credential_version",99L));
            if(sql.startsWith("select session_id,principal_name"))return canonical?row(Map.of("session_id",capture.bearerId(),"principal_name",account.toString())):empty();
            if(sql.startsWith("select * from public.account_session_authority where primary_id"))return retained?authority(handle,account):empty();
            if(sql.contains("from public.session_authority_command_receipt"))return row(Map.of("event_id",event,"completed_at",offset(now)));
            if(sql.startsWith("insert into public.account_session_authority")){
                assertThat(execution.bindings()).contains("KNOWN_LOGOUT");assertThat(sql).contains("'REVOKED',1").doesNotContain("'ACTIVE'");writes++;return changed();
            }
            if(sql.startsWith("update public.account_session_authority")||sql.startsWith("insert into public.session_authority_command_receipt")){
                writes++;return changed();
            }
            throw new SQLException("Unexpected fixed logout statement");
        });
        when(source.getConnection()).thenReturn(connection);TransactionSynchronizationManager.bindResource(source,new ConnectionHolder(connection));
        store=new JooqSessionAuthorityStore(source,boundaries,keys);
    }
    @AfterEach void clear()throws Exception{
        TransactionSynchronizationManager.unbindResource(source);connection.close();capture.close();SecurityContextHolder.clearContext();
    }
    @Test void canonicalOwnedRowWithoutAuthorityCreatesOnlyRevokedTombstone(){
        var result=store.logout(scope,proof,event);store.requireLogoutLookup(scope,proof,result);
        assertThat(result.absent()).isFalse();assertThat(result.fence().created()).isTrue();assertThat(writes).isEqualTo(2);
        assertThat(result.ownership().accountId()).isEqualTo(account);assertThat(result.ownership().actorId()).isNull();
        assertThat(sqls).noneMatch(sql->sql.contains("credential_version")||sql.contains("expiry_time")||sql.contains("authenticated_at")||sql.contains("status='ACTIVE'"));
        assertThat(sqls).anyMatch(sql->sql.contains("from public.spring_session where session_id=? for update"));
    }
    @Test void retainedHmacProvesOwnershipAfterFrameworkRowDisappears(){
        canonical=false;retained=true;var result=store.logout(scope,proof,event);
        assertThat(result.absent()).isFalse();assertThat(result.ownership().accountId()).isEqualTo(account);assertThat(writes).isEqualTo(2);
        assertThat(sqls).noneMatch(sql->sql.contains("spring_session_attributes"));
    }
    @Test void successfulCompleteCanonicalAndRetainedAbsenceProducesNoFence(){
        canonical=false;var result=store.logout(scope,proof,event);store.requireLogoutLookup(scope,proof,result);
        assertThat(result.absent()).isTrue();assertThat(writes).isZero();
        assertThat(sqls).anyMatch(sql->sql.startsWith("select distinct session_key_id"));
        assertThat(sqls.stream().filter(sql->sql.startsWith("select * from public.account_session_authority where session_key_id"))).hasSize(2);
    }
    @Test void unknownRequiredKeyOrLookupFailureCannotBecomeAbsence(){
        canonical=false;unknownKey=true;assertThatThrownBy(()->store.logout(scope,proof,event)).isInstanceOf(SessionSensitiveException.class);
        assertThat(writes).isZero();unknownKey=false;failLookup=true;
        assertThatThrownBy(()->store.logout(scope,proof,event)).isInstanceOf(SessionSensitiveException.class);assertThat(writes).isZero();
    }
    @Test void contradictoryRetainedMatchesDenyBeforeWrites(){
        canonical=false;retained=true;ambiguous=true;
        assertThatThrownBy(()->store.logout(scope,proof,event)).isInstanceOf(SessionSensitiveException.class);assertThat(writes).isZero();
    }
    @Test void existingRevokedLineageRequiresExactOriginalReceiptAndDoesNotWrite(){
        canonical=false;retained=true;replay=true;var result=store.logout(scope,proof,event);
        assertThat(result.fence().created()).isFalse();assertThat(result.fence().eventId()).isEqualTo(event);assertThat(writes).isZero();
    }
    @Test void issuedOwnershipAndAbsenceCannotMoveToAnotherScopeOrConnection(){
        var result=store.logout(scope,proof,event);var other=SessionSensitiveSecurityTransaction.LogoutScope.knownLineage(capture);
        assertThatThrownBy(()->result.requireLookup(other,proof,source,connection)).isInstanceOf(SessionSensitiveException.class);
        assertThatThrownBy(()->result.ownership().requireOwned(scope,proof,source,mock(java.sql.Connection.class))).isInstanceOf(SessionSensitiveException.class);
    }
    private MockResult[] authority(SessionHandle id,UUID owner){
        Map<String,Object> values=new LinkedHashMap<>();values.put("primary_id",id.toString());values.put("account_id",owner);
        values.put("session_key_id","k1");values.put("session_digest",keys.sessionDigest("k1",capture.bearerId()));
        values.put("state",replay?"REVOKED":"ACTIVE");values.put("version",replay?2L:1L);
        if(replay){values.put("revocation_event_id",event);values.put("revoked_at",offset(now));}return row(values);
    }
    private static OffsetDateTime offset(Instant at){return at.atOffset(ZoneOffset.UTC);}
    private static MockResult[] changed(){return new MockResult[]{new MockResult(1)};}
    private static MockResult[] empty(){return new MockResult[]{new MockResult(0,DSL.using(SQLDialect.POSTGRES).newResult(DSL.field("empty",String.class)))};}
    @SuppressWarnings({"rawtypes","unchecked"}) private static MockResult[] row(Map<String,Object> values){
        DSLContext db=DSL.using(SQLDialect.POSTGRES);Field<?>[] fields=values.entrySet().stream().map(e->DSL.field(DSL.name(e.getKey()),(Class)(e.getValue()==null?String.class:e.getValue().getClass()))).toArray(Field<?>[]::new);
        Result<Record> result=db.newResult(fields);Record row=db.newRecord(fields);for(Field field:fields)row.set(field,values.get(field.getName()));result.add(row);return new MockResult[]{new MockResult(1,result)};
    }
}

package com.mightyoung.softwarefactory.identity;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.*;
import org.springframework.security.core.context.SecurityContextHolder;
import javax.sql.DataSource;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.mightyoung.softwarefactory.identity.SessionSensitiveContracts.*;

/** Executes owning DAL through mock JDBC. Does not prove actual PostgreSQL/guard/commit behavior. */
class JooqSessionAuthorityStoreSqlTest {
    final DataSource source=mock(DataSource.class);
    final SessionSensitiveSecurityTransaction boundaries=mock(SessionSensitiveSecurityTransaction.class);
    final SessionSensitiveSecurityTransaction.GuardedBoundary boundary=mock(SessionSensitiveSecurityTransaction.GuardedBoundary.class);
    final SessionSensitiveKeys keys=new SessionSensitiveKeys("k1",Map.of("k1",new byte[32]));
    final SessionHandle sourceHandle=new SessionHandle(UUID.randomUUID()),target=new SessionHandle(UUID.randomUUID());
    final UUID proofId=UUID.randomUUID(),commandKey=UUID.randomUUID(),eventId=UUID.randomUUID();
    final Instant now=Instant.parse("2026-11-04T00:01:00Z");
    final List<String> statements=new ArrayList<>();
    final IdentityLifecycleContracts.OneUseSecret secret=IdentityLifecycleContracts.OneUseSecret.parse("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
    CapturedHumanRequest captured;CurrentTenantContext context;SessionSensitiveSecurityTransaction.Scope scope;
    JooqSessionAuthorityStore store;boolean completed,foreignTarget;int writes;
    @BeforeEach void setup()throws Exception{
        captured=CapturedHumanRequestTest.capture(CapturedHumanRequestTest.authenticatedRequest());context=captured.context();
        scope=SessionSensitiveSecurityTransaction.Scope.human(Operation.REVOKE_SESSION,captured);
        when(boundary.observedAt()).thenReturn(now);
        when(source.getConnection()).thenAnswer(call->new MockConnection(execution->{
            String sql=execution.sql().stripLeading();statements.add(sql);
            if(sql.startsWith("select state,version,"))return row(Map.of("state","ENFORCING","version",1L,"activated_at",offset(now.minusSeconds(1)),"activated_by_database_role","migrator","activation_event_id",eventId,"activation_evidence_sha256","a".repeat(64)));
            if(sql.contains("from public.spring_session stored"))return row(Map.of("primary_id",sourceHandle.toString(),"session_key_id","k1",
                    "session_digest",keys.sessionDigest("k1",captured.bearerId()),"authenticated_at",offset(captured.authenticatedAt()),"absolute_expires_at",offset(captured.absoluteExpiresAt())));
            if(sql.startsWith("select session_id,principal_name"))return row(Map.of("session_id","remote-private-bearer","principal_name",foreignTarget?UUID.randomUUID().toString():context.accountId().toString()));
            if(sql.startsWith("select * from public.account_session_authority"))return completed?row(Map.of("account_id",context.accountId(),"state","REVOKED","version",1L)):empty();
            if(sql.contains("from public.session_authority_command_receipt")){
                if(!completed)return empty();
                return row(Map.of("target_primary_id",target.toString(),"expected_authority_version",0L,"fingerprint_key_id","k1",
                        "fingerprint",keys.commandDigest("k1",canonical()+"|"+proofId+"|"+secret.digest()),"proof_id",proofId,
                        "result_authority_version",1L,"event_id",eventId,"completed_at",offset(now)));
            }
            if(sql.startsWith("select * from public.sensitive_reauth_proof")){
                Map<String,Object> data=new LinkedHashMap<>();data.put("action","SESSION_REVOKE");data.put("state","ISSUED");data.put("version",1L);
                data.put("account_id",context.accountId());data.put("actor_id",context.actorId());data.put("source_primary_id",sourceHandle.toString());
                data.put("issued_credential_version",context.credentialVersion());data.put("target_primary_id",target.toString());data.put("expected_authority_version",0L);
                data.put("expires_at",offset(now.plusSeconds(60)));data.put("secret_digest",secret.digest());data.put("body_key_id","k1");data.put("body_digest",keys.actionDigest("k1",canonical()));return row(data);
            }
            if(sql.startsWith("update public.sensitive_reauth_proof")||sql.startsWith("insert into public.account_session_authority")||sql.startsWith("insert into public.session_authority_command_receipt")){
                writes++;if(sql.startsWith("insert into public.account_session_authority"))assertThat(execution.bindings()).contains("REMOTE_ABSENCE");
                return new MockResult[]{new MockResult(1)};
            }
            throw new java.sql.SQLException("Unexpected synthetic session statement");
        }));
        store=new JooqSessionAuthorityStore(source,boundaries,keys);
    }
    @AfterEach void clear(){secret.close();captured.close();SecurityContextHolder.clearContext();}
    String canonical(){return context.accountId()+"|"+context.actorId()+"|"+sourceHandle+"|"+context.credentialVersion()+"|"+target+"|0";}
    RevokeCommand command(){return new RevokeCommand(commandKey,target,new ExpectedAuthority(0),proofId,secret);}
    @Test void originalAbsentVersionReplayAcknowledgesAfterTargetBecameRevokedOneWithoutRecheckingProof(){
        completed=true;FenceResult result=store.revoke(scope,boundary,command(),eventId);
        assertThat(result.created()).isFalse();assertThat(result.version()).isEqualTo(1);assertThat(writes).isZero();
        assertThat(statements).noneMatch(sql->sql.contains("from public.sensitive_reauth_proof"));
        verify(boundaries,times(2)).requireGuarded(eq(scope),eq(boundary),eq(source),any());
    }
    @Test void absentTargetIsDirectlyFencedWithoutIntermediateActiveRegistration(){
        FenceResult result=store.revoke(scope,boundary,command(),eventId);
        assertThat(result.created()).isTrue();assertThat(result.version()).isEqualTo(1);assertThat(writes).isEqualTo(3);
        String insert=statements.stream().filter(sql->sql.startsWith("insert into public.account_session_authority")).findFirst().orElseThrow();
        assertThat(insert).contains("'REVOKED',1").doesNotContain("'ACTIVE'");
        assertThat(statements.stream().filter(sql->sql.startsWith("select session_id,principal_name")).findFirst().orElseThrow()).doesNotContain("expiry_time");
        int receipt=-1,proof=-1;
        for(int i=0;i<statements.size();i++){if(statements.get(i).contains("from public.session_authority_command_receipt"))receipt=i;if(statements.get(i).startsWith("select * from public.sensitive_reauth_proof"))proof=i;}
        assertThat(receipt).isGreaterThanOrEqualTo(0).isLessThan(proof);
    }
    @Test void foreignCanonicalOwnerCannotBeClaimedByAnOpaqueHandle(){
        foreignTarget=true;assertThatThrownBy(()->store.revoke(scope,boundary,command(),eventId)).isInstanceOf(SessionSensitiveException.class);
        assertThat(writes).isZero();assertThat(statements).noneMatch(sql->sql.contains("from public.session_authority_command_receipt"));
    }
    private static OffsetDateTime offset(Instant value){return value.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);}
    private static MockResult[] empty(){return new MockResult[]{new MockResult(0,DSL.using(SQLDialect.POSTGRES).newResult(DSL.field("empty",String.class)))};}
    @SuppressWarnings({"rawtypes","unchecked"})
    private static MockResult[] row(Map<String,Object> values){
        DSLContext db=DSL.using(SQLDialect.POSTGRES);Field<?>[] fields=values.entrySet().stream().map(entry->DSL.field(DSL.name(entry.getKey()),(Class)entry.getValue().getClass())).toArray(Field<?>[]::new);
        Result<Record> result=db.newResult(fields);Record record=db.newRecord(fields);
        for(Field field:fields)record.set(field,values.get(field.getName()));result.add(record);return new MockResult[]{new MockResult(1,result)};
    }
}

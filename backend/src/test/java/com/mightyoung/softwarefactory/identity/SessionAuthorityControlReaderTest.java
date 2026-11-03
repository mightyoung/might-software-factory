package com.mightyoung.softwarefactory.identity;

import org.jooq.*;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.*;
import org.junit.jupiter.api.*;
import org.springframework.security.core.context.SecurityContextHolder;
import javax.sql.DataSource;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.mightyoung.softwarefactory.identity.SessionSensitiveContracts.Enforcement;

/** Positively observed control semantics; mocked SQL is not an activation or real-DB test. */
class SessionAuthorityControlReaderTest {
    final DataSource source=mock(DataSource.class);
    final SessionAuthorityControlTransaction transactions=mock(SessionAuthorityControlTransaction.class);
    final SessionAuthorityControlTransaction.Boundary controlProof=mock(SessionAuthorityControlTransaction.Boundary.class);
    final HumanSessionAuthorityTransaction callers=mock(HumanSessionAuthorityTransaction.class);
    final HumanSessionAuthorityTransaction.ReadBoundary readProof=mock(HumanSessionAuthorityTransaction.ReadBoundary.class);
    final SessionSensitiveKeyProvider keyProvider=mock(SessionSensitiveKeyProvider.class);
    final SessionSensitiveKeys keys=new SessionSensitiveKeys("k1",Map.of("k1",new byte[32]));
    final List<String> statements=new ArrayList<>();
    final Instant now=Instant.parse("2026-11-04T00:01:00Z");
    Map<String,Object> control;boolean missing,storageFailure,unknownKey;CapturedHumanRequest captured;
    @BeforeEach void setup()throws Exception{
        control=off();captured=CapturedHumanRequestTest.capture(CapturedHumanRequestTest.authenticatedRequest());
        when(readProof.observedAt()).thenReturn(now);when(keyProvider.requireRetainedKeys()).thenReturn(keys);
        when(transactions.readControl(any())).thenAnswer(call->call.<Function<SessionAuthorityControlTransaction.Boundary,Object>>getArgument(0).apply(controlProof));
        when(source.getConnection()).thenAnswer(call->new MockConnection(execution->{
            String sql=execution.sql().stripLeading();statements.add(sql);
            if(storageFailure)throw new java.sql.SQLException("Synthetic control dependency unavailable");
            if(sql.startsWith("select state,version,"))return missing?empty():row(control);
            if(sql.startsWith("select distinct session_key_id"))return row(Map.of("session_key_id",unknownKey?"unavailable-old-key":"k1"));
            if(sql.contains("from public.spring_session stored"))return row(Map.of("primary_id",UUID.randomUUID().toString(),"session_key_id","k1",
                    "session_digest",keys.sessionDigest("k1",captured.bearerId()),"authenticated_at",captured.authenticatedAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS).atOffset(ZoneOffset.UTC),
                    "absolute_expires_at",captured.absoluteExpiresAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS).atOffset(ZoneOffset.UTC)));
            throw new java.sql.SQLException("Unexpected control statement");
        }));
    }
    @AfterEach void clear(){captured.close();SecurityContextHolder.clearContext();}
    JooqSessionAuthorityControlReader reader(){return new JooqSessionAuthorityControlReader(source,transactions,keyProvider);}
    JooqCurrentHumanSessionAuthority human(){return new JooqCurrentHumanSessionAuthority(source,callers,keyProvider);}
    @Test void offRequiresActualCompleteRowButDoesNotRequireUnusedKeys(){
        assertThat(reader().read()).isEqualTo(Enforcement.OFF);verifyNoInteractions(keyProvider);
        assertThat(statements).hasSize(1);verify(transactions,times(2)).requireControl(eq(controlProof),eq(source),any());
    }
    @Test void missingRowAndStorageFailuresCannotFallBackToOff(){
        missing=true;assertThatThrownBy(()->reader().read()).isInstanceOf(SessionSensitiveException.class);
        missing=false;storageFailure=true;assertThatThrownBy(()->reader().read()).isInstanceOf(SessionSensitiveException.class);
        verifyNoInteractions(keyProvider);
    }
    @Test void offWithActivationEvidenceOrWrongVersionIsInvalid(){
        control.put("activation_event_id",UUID.randomUUID());assertThatThrownBy(()->reader().read()).isInstanceOf(SessionSensitiveException.class);
        control=off();control.put("version",1L);assertThatThrownBy(()->reader().read()).isInstanceOf(SessionSensitiveException.class);
    }
    @Test void enforcingRequiresEveryActivationFieldAndRetainedKeyCoverage(){
        control=enforcing();assertThat(reader().read()).isEqualTo(Enforcement.ENFORCING);verify(keyProvider).requireRetainedKeys();
        unknownKey=true;assertThatThrownBy(()->reader().read()).isInstanceOf(SessionSensitiveException.class);
        unknownKey=false;control.put("activated_at",null);assertThatThrownBy(()->reader().read()).isInstanceOf(SessionSensitiveException.class);
    }
    @Test void missingEnforcingKeyRemainsUnavailable(){
        control=enforcing();when(keyProvider.requireRetainedKeys()).thenThrow(SessionSensitiveException.unavailable());
        assertThatThrownBy(()->reader().read()).isInstanceOf(SessionSensitiveException.class);
    }
    @Test void borrowedCurrentAssertionPositivelyReadsOffInTheCallerBoundary(){
        assertThat(human().requireReadSnapshotCurrent(captured,readProof)).isEqualTo(Enforcement.OFF);
        verify(callers,times(2)).requireRead(eq(captured),eq(readProof),eq(source),any());verifyNoInteractions(keyProvider);
    }
    @Test void enforcingCurrentUsesTheOwnerObservationAndRetainedSessionBinding(){
        control=enforcing();assertThat(human().requireReadSnapshotCurrent(captured,readProof)).isEqualTo(Enforcement.ENFORCING);
        assertThat(statements).anyMatch(sql->sql.contains("authority.bound_credential_version")&&sql.contains("stored.expiry_time>"));
        verify(keyProvider).requireRetainedKeys();
    }
    @Test void forgedBorrowedBoundaryDeniesBeforeControlSql(){
        doThrow(SessionSensitiveException.unavailable()).when(callers).requireRead(eq(captured),eq(readProof),eq(source),any());
        assertThatThrownBy(()->human().requireReadSnapshotCurrent(captured,readProof)).isInstanceOf(SessionSensitiveException.class);
        assertThat(statements).isEmpty();verifyNoInteractions(keyProvider);
    }
    private static Map<String,Object> off(){
        Map<String,Object> row=new LinkedHashMap<>();row.put("state","OFF");row.put("version",0L);row.put("activated_at",null);
        row.put("activated_by_database_role",null);row.put("activation_event_id",null);row.put("activation_evidence_sha256",null);return row;
    }
    private Map<String,Object> enforcing(){
        var row=off();row.put("state","ENFORCING");row.put("version",1L);row.put("activated_at",now.minusSeconds(1).atOffset(ZoneOffset.UTC));
        row.put("activated_by_database_role","migrator");row.put("activation_event_id",UUID.randomUUID());row.put("activation_evidence_sha256","a".repeat(64));return row;
    }
    private static MockResult[] empty(){return new MockResult[]{new MockResult(0,DSL.using(SQLDialect.POSTGRES).newResult(DSL.field("empty",String.class)))};}
    @SuppressWarnings({"rawtypes","unchecked"}) private static MockResult[] row(Map<String,Object> values){
        DSLContext db=DSL.using(SQLDialect.POSTGRES);Field<?>[] fields=values.entrySet().stream().map(e->DSL.field(DSL.name(e.getKey()),(Class)(e.getValue()==null?String.class:e.getValue().getClass()))).toArray(Field<?>[]::new);
        Result<Record> result=db.newResult(fields);Record row=db.newRecord(fields);for(Field field:fields)row.set(field,values.get(field.getName()));result.add(row);return new MockResult[]{new MockResult(1,result)};
    }
}

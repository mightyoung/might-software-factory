package com.mightyoung.softwarefactory.auditops;

import com.mightyoung.softwarefactory.identity.SessionSensitiveException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;
import static com.mightyoung.softwarefactory.auditops.TenantAuditQueryContracts.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Source component evidence using real Managed/current HUMAN/capability adapters and controlled JDBC. */
class TenantAuditQueryServiceTest {
    private static final UUID REQUEST_ID=new UUID(30,30),TRACE_ID=new UUID(31,31);
    private static final JsonMapper JSON=JsonMapper.builder().build();
    @AfterEach void clear(){SecurityContextHolder.clearContext();TransactionSynchronizationManager.clear();}

    @Test void defaultWindowUsesTheRealCheckedCutAndProducesFinalOwnedBytesAfterCommit() throws Exception {
        var f=new TenantAuditQueryRepositoryTest.Fixture();f.timeoutDisplay="2s";
        f.auditRows.add(f.auditRow(new UUID(0,1),f.cut.minusSeconds(1)));
        var encoded=service(f).query(f.request,defaults(20),REQUEST_ID,TRACE_ID);byte[] bytes=bytes(encoded);var root=JSON.readTree(bytes);
        assertThat(encoded.size()).isEqualTo(bytes.length);assertThat(root.get("data").get("items").size()).isEqualTo(1);
        assertThat(root.get("data").get("window").get("to").stringValue()).isEqualTo(f.cut.truncatedTo(ChronoUnit.MICROS).toString());
        assertThat(root.get("data").get("window").get("from").stringValue()).isEqualTo(f.cut.truncatedTo(ChronoUnit.MICROS).minus(1,ChronoUnit.DAYS).toString());
        assertThat(root.get("meta").get("requestId").stringValue()).isEqualTo(REQUEST_ID.toString());
        assertThat(root.get("meta").get("traceId").stringValue()).isEqualTo(TRACE_ID.toString());
        assertThat(root.get("meta").get("schemaVersion").stringValue()).isEqualTo("1");assertThat(root.get("data").has("nextCursor")).isFalse();
        assertThat(f.queryBindings).hasSize(1);assertThat(f.queryBindings.getFirst()[16]).isEqualTo(21);assertThat(f.timeoutChecks).isEqualTo(2);
        verify(f.source,times(1)).getConnection();verify(f.connection).commit();verify(f.connection,never()).rollback();
        assertThat(TransactionSynchronizationManager.hasResource(f.source)).isFalse();
    }

    @Test void pageSizeUsesCompleteItemsAndTheLastReturnedCursorForAnAuthorizedNextPage() throws Exception {
        var f=new TenantAuditQueryRepositoryTest.Fixture();
        for(int i=3;i>0;i--)f.auditRows.add(f.auditRow(new UUID(0,i),f.cut.minusSeconds(4-i)));
        var first=JSON.readTree(bytes(service(f).query(f.request,defaults(2),REQUEST_ID,TRACE_ID))).get("data");
        assertThat(first.get("items").size()).isEqualTo(2);assertThat(first.get("hasMore").booleanValue()).isTrue();
        var cursor=first.get("nextCursor");assertThat(cursor.get("id").stringValue()).isEqualTo(new UUID(0,2).toString());
        var next=new TenantAuditQueryRepositoryTest.Fixture();next.auditRows.add(next.auditRow(new UUID(0,1),next.cut.minusSeconds(3)));
        Query query=new Query(Instant.parse(first.get("window").get("from").stringValue()),Instant.parse(first.get("window").get("to").stringValue()),
                null,null,null,null,null,2,new Cursor(Instant.parse(cursor.get("occurredAt").stringValue()),UUID.fromString(cursor.get("id").stringValue()),cursor.get("queryFingerprint").stringValue()));
        var page=JSON.readTree(bytes(service(next).query(next.request,query,REQUEST_ID,TRACE_ID))).get("data");
        assertThat(page.get("items").size()).isEqualTo(1);assertThat(page.get("items").get(0).get("id").stringValue()).isEqualTo(new UUID(0,1).toString());
        assertThat(page.get("hasMore").booleanValue()).isFalse();assertThat(page.has("nextCursor")).isFalse();
    }

    @Test void currentPermissionAndEnforcingControlAreRequiredBeforeAnyAuditRead() {
        var denied=new TenantAuditQueryRepositoryTest.Fixture();denied.grant=false;
        assertThatThrownBy(()->service(denied).query(denied.request,defaults(20),REQUEST_ID,TRACE_ID)).isExactlyInstanceOf(AccessDeniedException.class);
        assertThat(denied.capabilityBindings).hasSize(1);assertThat(denied.queryBindings).isEmpty();
        var off=new TenantAuditQueryRepositoryTest.Fixture();off.control="OFF";
        assertThatThrownBy(()->service(off).query(off.request,defaults(20),REQUEST_ID,TRACE_ID)).isInstanceOf(SessionSensitiveException.class)
                .satisfies(error->assertThat(((SessionSensitiveException)error).reason()).isEqualTo(SessionSensitiveException.Reason.UNAVAILABLE));
        assertThat(off.queryBindings).isEmpty();assertThat(off.capabilityBindings).isEmpty();
    }

    @Test void clientFutureWindowAndWrongFingerprintStay400AfterAuthorizationWithoutDataSql() {
        for(boolean future:List.of(false,true)){
            var f=new TenantAuditQueryRepositoryTest.Fixture();
            Query query=future?new Query(f.authenticatedAt,f.cut.plusNanos(1000),null,null,null,null,null,20,null):
                    new Query(f.authenticatedAt,f.cut,null,null,null,null,null,20,new Cursor(f.cut.minusSeconds(1),new UUID(0,1),"0".repeat(64)));
            assertThatThrownBy(()->service(f).query(f.request,query,REQUEST_ID,TRACE_ID)).isExactlyInstanceOf(Failure.class)
                    .satisfies(error->assertThat(((Failure)error).code()).isEqualTo(Code.BAD_REQUEST));
            assertThat(f.capabilityBindings).hasSize(1);assertThat(f.queryBindings).isEmpty();
        }
    }

    @Test void corruptPersistedFieldsAre503RatherThanParser400() {
        var f=new TenantAuditQueryRepositoryTest.Fixture();var row=f.auditRow(new UUID(0,1),f.cut.minusSeconds(1));row.put("policy_version","unsafe body text");f.auditRows.add(row);
        assertThatThrownBy(()->service(f).query(f.request,defaults(20),REQUEST_ID,TRACE_ID)).isExactlyInstanceOf(TenantAuditQueryService.Unavailable.class)
                .isNotInstanceOf(Failure.class).hasMessage("Tenant audit query is unavailable");assertThat(f.queryBindings).hasSize(1);
    }

    @ParameterizedTest @ValueSource(strings={"FALSE","NULL","STRING","ZERO_ROWS","TWO_ROWS","TWO_COLUMNS","SQL"})
    void timeoutReadbackMustBeExactlyOneTrueBooleanBeforeCurrentAuthority(String fault) {
        var f=new TenantAuditQueryRepositoryTest.Fixture();f.timeoutFault=fault;
        assertThatThrownBy(()->service(f).query(f.request,defaults(20),REQUEST_ID,TRACE_ID)).isExactlyInstanceOf(TenantAuditQueryService.Unavailable.class);
        assertThat(f.capabilityBindings).isEmpty();assertThat(f.queryBindings).isEmpty();
        assertThat(f.statements).contains(TenantAuditQueryService.SET_TIMEOUT_SQL,TenantAuditQueryService.VERIFY_TIMEOUT_SQL);
    }

    @Test void finalTimeoutDriftRefusesTheAlreadyEncodedResultAndRollsBack() throws Exception {
        var f=new TenantAuditQueryRepositoryTest.Fixture();f.timeoutFault="FINAL_DRIFT";f.auditRows.add(f.auditRow(new UUID(0,1),f.cut.minusSeconds(1)));
        assertThatThrownBy(()->service(f).query(f.request,defaults(20),REQUEST_ID,TRACE_ID)).isExactlyInstanceOf(TenantAuditQueryService.Unavailable.class);
        assertThat(f.queryBindings).hasSize(1);assertThat(f.timeoutChecks).isEqualTo(2);verify(f.connection).rollback();verify(f.connection,never()).commit();
    }

    @Test void existingTransactionOrHolderIsRejectedWithoutAnExtraConnection() throws Exception {
        var f=new TenantAuditQueryRepositoryTest.Fixture();
        assertThatThrownBy(()->f.inCaller(()->service(f).query(f.request,defaults(20),REQUEST_ID,TRACE_ID))).isExactlyInstanceOf(TenantAuditQueryService.Unavailable.class);
        assertThat(f.queryBindings).isEmpty();verify(f.source,times(1)).getConnection();
        var held=new TenantAuditQueryRepositoryTest.Fixture();TransactionSynchronizationManager.bindResource(held.source,new ConnectionHolder(held.connection));
        try{assertThatThrownBy(()->service(held).query(held.request,defaults(20),REQUEST_ID,TRACE_ID)).isExactlyInstanceOf(TenantAuditQueryService.Unavailable.class);verify(held.source,never()).getConnection();}
        finally{TransactionSynchronizationManager.unbindResource(held.source);}
    }

    @Test void aManagerForAnotherSourceCannotCauseTheRepositoryToBorrowItsOwnConnection() throws Exception {
        var f=new TenantAuditQueryRepositoryTest.Fixture();DataSource other=mock(DataSource.class);
        var service=new TenantAuditQueryService(other,f.manager,f.callers,f.authorization);
        assertThatThrownBy(()->service.query(f.request,defaults(20),REQUEST_ID,TRACE_ID)).isExactlyInstanceOf(TenantAuditQueryService.Unavailable.class);
        verify(other,never()).getConnection();verify(f.source,times(1)).getConnection();assertThat(f.queryBindings).isEmpty();
    }

    @Test void writerIOExceptionPropagatesWithoutRequeryOrChangingCommittedOutcome() throws Exception {
        var f=new TenantAuditQueryRepositoryTest.Fixture();f.auditRows.add(f.auditRow(new UUID(0,1),f.cut.minusSeconds(1)));
        var encoded=service(f).query(f.request,defaults(20),REQUEST_ID,TRACE_ID);IOException failure=new IOException("synthetic write failure");var attempts=new AtomicInteger();
        assertThatThrownBy(()->encoded.writeTo(new OutputStream(){
            @Override public void write(int value)throws IOException{attempts.incrementAndGet();throw failure;}
            @Override public void write(byte[] values,int offset,int length)throws IOException{attempts.incrementAndGet();throw failure;}
        })).isSameAs(failure);
        assertThat(attempts.get()).isEqualTo(1);assertThat(f.queryBindings).hasSize(1);verify(f.connection).commit();verify(f.connection,never()).rollback();
    }

    @Test void aDifferentLocalTenantReadbackRefusesBeforeAnyCurrentAuthorityOrAuditQuery() {
        var f=new TenantAuditQueryRepositoryTest.Fixture();f.wrongTenantSetting=true;
        assertThatThrownBy(()->service(f).query(f.request,defaults(20),REQUEST_ID,TRACE_ID)).isExactlyInstanceOf(TenantAuditQueryService.Unavailable.class);
        assertThat(f.queryBindings).isEmpty();assertThat(f.capabilityBindings).isEmpty();
        assertThat(f.statements).noneMatch(sql->sql.startsWith("select state,version,")||sql.contains("from public.spring_session stored"));
    }

    private static Query defaults(int size){return new Query(null,null,null,null,null,null,null,size,null);}
    private static TenantAuditQueryService service(TenantAuditQueryRepositoryTest.Fixture f){return new TenantAuditQueryService(f.source,f.manager,f.callers,f.authorization);}
    private static byte[] bytes(TenantAuditQueryService.EncodedResult result)throws IOException{var output=new ByteArrayOutputStream();result.writeTo(output);return output.toByteArray();}
}

package com.mightyoung.softwarefactory.identity;

import com.mightyoung.softwarefactory.auditops.JooqSessionSensitiveAudit;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.mock.web.MockHttpServletRequest;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.mightyoung.softwarefactory.identity.SessionSensitiveContracts.*;

class JooqKnownLineageLogoutAuditTest {
    final DataSource source=mock(DataSource.class);
    final SessionSensitiveSecurityTransaction boundaries=mock(SessionSensitiveSecurityTransaction.class);
    final SessionSensitiveSecurityTransaction.LogoutBoundary proof=mock(SessionSensitiveSecurityTransaction.LogoutBoundary.class);
    final UUID account=UUID.randomUUID(),eventId=UUID.randomUUID(),trace=UUID.randomUUID();
    final SessionHandle handle=new SessionHandle(UUID.randomUUID());
    final Instant now=Instant.parse("2026-11-04T00:01:00Z");
    final KnownLineageLogoutCapture captured=new KnownLogoutRequests(serializer->serializer.setUseBase64Encoding(false)).capture(new MockHttpServletRequest());
    final SessionSensitiveSecurityTransaction.LogoutScope scope=SessionSensitiveSecurityTransaction.LogoutScope.knownLineage(captured);
    MockConnection connection;ConnectionHolder holder;JooqSessionSensitiveAudit audit;int writes;boolean failInsert;
    @BeforeEach void setup()throws Exception{
        when(proof.observedAt()).thenReturn(now);
        connection=new MockConnection(execution->{
            if(!execution.sql().stripLeading().startsWith("insert into public.identity_sensitive_event"))throw new java.sql.SQLException("Unexpected audit SQL");
            if(failInsert)throw new java.sql.SQLException("Synthetic audit dependency failure");
            assertThat(java.util.Arrays.stream(execution.bindings()).map(String::valueOf).toList()).contains("LOGOUT","KNOWN_LINEAGE",account.toString(),handle.toString());writes++;return new MockResult[]{new MockResult(1)};
        });
        holder=new ConnectionHolder(connection);TransactionSynchronizationManager.bindResource(source,holder);when(source.getConnection()).thenReturn(connection);
        audit=new JooqSessionSensitiveAudit(source,boundaries,mock(PlatformTransactionManager.class));
    }
    @AfterEach void clear()throws Exception{TransactionSynchronizationManager.unbindResource(source);connection.close();captured.close();}
    LogoutOwnership owner(){return new LogoutOwnership(scope,proof,source,connection,account,null,handle);}
    SensitiveEvent event(UUID subject){return new SensitiveEvent(eventId,Operation.LOGOUT,"SUCCESS",null,Provenance.KNOWN_LINEAGE,subject,null,handle,handle,null,null,trace,now);}
    @Test void knownLineageAuditCanHaveNoFreshActor(){
        audit.logoutSuccess(scope,proof,owner(),event(account));assertThat(writes).isEqualTo(1);assertThat(holder.isRollbackOnly()).isFalse();
    }
    @Test void mismatchedSubjectOrOwnershipCannotWrite(){
        assertThatThrownBy(()->audit.logoutSuccess(scope,proof,owner(),event(UUID.randomUUID()))).isInstanceOf(SessionSensitiveException.class);
        assertThat(writes).isZero();assertThat(holder.isRollbackOnly()).isTrue();
    }
    @Test void auditInsertFailurePoisonsOwningTransaction(){
        failInsert=true;assertThatThrownBy(()->audit.logoutSuccess(scope,proof,owner(),event(account))).isInstanceOf(SessionSensitiveException.class);
        assertThat(writes).isZero();assertThat(holder.isRollbackOnly()).isTrue();
    }
}

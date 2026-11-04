package com.mightyoung.softwarefactory.identity;

import java.time.Instant;
import javax.sql.DataSource;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Fixture evidence only: exercises the real checked adapter, not persisted control acceptance. */
class JooqCurrentHumanSessionAuthorityCheckedTest {
    private final DataSource source=mock(DataSource.class);
    private final HumanSessionAuthorityTransaction callers=mock(HumanSessionAuthorityTransaction.class);
    private final SessionSensitiveKeyProvider keys=mock(SessionSensitiveKeyProvider.class);
    @AfterEach void clear() {
        TransactionSynchronizationManager.unbindResourceIfPossible(source);
        TransactionSynchronizationManager.clear();
    }
    @Test void readsActualBoundaryClockOnceAndKeepsReturnedCutWithoutReopeningGetter() { checkedTime(false); }
    @Test void dedicatedTypedMappingKeepsTheSameReadOnceBoundaryContract() { checkedTime(true); }
    private void checkedTime(boolean typed) {
        var db=DSL.using(SQLDialect.POSTGRES);
        var state=DSL.field("state",String.class);
        var version=DSL.field("version",Long.class);
        var at=DSL.field("activated_at",Object.class);
        var role=DSL.field("activated_by_database_role",Object.class);
        var event=DSL.field("activation_event_id",Object.class);
        var evidence=DSL.field("activation_evidence_sha256",String.class);
        var rows=db.newResult(state,version,at,role,event,evidence);
        rows.add(db.newRecord(state,version,at,role,event,evidence).values("OFF",0L,null,null,null,null));
        var connection=new MockConnection(context -> new MockResult[]{new MockResult(1,rows)});
        var holder=new ConnectionHolder(connection);
        holder.setSynchronizedWithTransaction(true);
        TransactionSynchronizationManager.bindResource(source,holder);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        var request=mock(CapturedHumanRequest.class);
        var boundary=mock(HumanSessionAuthorityTransaction.ReadBoundary.class);
        Instant cut=Instant.parse("2026-11-04T01:02:03Z");
        when(boundary.observedAt()).thenReturn(cut).thenThrow(new AssertionError("Clock getter read twice"));
        var adapter=typed?new JooqCurrentHumanSessionAuthority(source,callers,keys,true):new JooqCurrentHumanSessionAuthority(source,callers,keys);
        var checked=adapter.requireReadCheckedCurrent(request,boundary);
        assertThat(checked.observedAt(request,boundary)).isEqualTo(cut);
        assertThat(checked.observedAt(request,boundary)).isEqualTo(cut);
        assertThat(checked.enforcement()).isEqualTo(SessionSensitiveContracts.Enforcement.OFF);
        verify(boundary,times(1)).observedAt();
        verifyNoInteractions(keys);
        holder.setRollbackOnly();
        assertThatThrownBy(()->checked.observedAt(request,boundary)).isInstanceOf(SessionSensitiveException.class);
    }
    @Test void legacyOnlyImplementationCannotFabricateCheckedOffAuthority() {
        CurrentHumanSessionAuthority legacy=new CurrentHumanSessionAuthority() {
            public SessionSensitiveContracts.Enforcement requireGuardedCurrent(HumanSessionAuthorityTransaction.WriteScope scope,
                    HumanSessionAuthorityTransaction.GuardedBoundary boundary) { return SessionSensitiveContracts.Enforcement.OFF; }
            public SessionSensitiveContracts.Enforcement requireReadSnapshotCurrent(CapturedHumanRequest request,
                    HumanSessionAuthorityTransaction.ReadBoundary boundary) { return SessionSensitiveContracts.Enforcement.OFF; }
        };
        assertThatThrownBy(()->legacy.requireReadCheckedCurrent(mock(CapturedHumanRequest.class),
                mock(HumanSessionAuthorityTransaction.ReadBoundary.class))).isInstanceOf(SessionSensitiveException.class);
    }
}

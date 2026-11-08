package com.mightyoung.softwarefactory.identity;

import org.jooq.*;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.*;
import org.junit.jupiter.api.*;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Executes the actual DAL SQL through jOOQ's mock JDBC adapter; no PostgreSQL acceptance claim. */
class JooqIdentityLifecycleStoreRateLimitTest {
    final IdentityLifecycleSecurityTransaction writer=mock(IdentityLifecycleSecurityTransaction.class);
    final DataSource source=mock(DataSource.class);
    final IdentityLifecycleSecurityTransaction.Scope scope=IdentityLifecycleSecurityTransaction.Scope.resetRequest(UUID.randomUUID());
    final Instant window=Instant.parse("2026-11-03T12:00:00Z");
    final List<String> statements=new ArrayList<>();
    long size;
    Integer attempts;
    int writes;
    JooqIdentityLifecycleStore store;
    @BeforeEach void setup()throws Exception {
        when(source.getConnection()).thenAnswer(call->new MockConnection(execution->{
            String sql=execution.sql();statements.add(sql);DSLContext db=DSL.using(SQLDialect.POSTGRES);
            if(sql.startsWith("select count(*)")) {
                Field<Long> count=DSL.field("count",Long.class);Result<Record1<Long>> result=db.newResult(count);result.add(db.newRecord(count).value1(size));return new MockResult[]{new MockResult(1,result)};
            }
            if(sql.startsWith("select attempts")) {
                assertThat(execution.bindings()).contains("a".repeat(64));
                Field<Integer> value=DSL.field("attempts",Integer.class);Result<Record1<Integer>> result=db.newResult(value);
                if(attempts!=null)result.add(db.newRecord(value).value1(attempts));return new MockResult[]{new MockResult(result.size(),result)};
            }
            if(sql.startsWith("update public.identity_lifecycle_rate_bucket")){writes++;attempts++;return new MockResult[]{new MockResult(1)};}
            if(sql.startsWith("insert into public.identity_lifecycle_rate_bucket")){writes++;size++;attempts=1;return new MockResult[]{new MockResult(1)};}
            throw new java.sql.SQLException("Unexpected synthetic rate-limit statement");
        }));
        store=new JooqIdentityLifecycleStore(DSL.using(SQLDialect.POSTGRES),source,writer);
    }
    private IdentityLifecycleStore.RateDecision reserve(){return store.reserveRate(scope,"a".repeat(64),window,5,10);}
    @Test void fullTableDeniesExistingUnderLimitExistingAtLimitAndMissingSelectorsWithoutLookupOrWrite(){
        for(Integer existing:Arrays.asList(1,5,null)) {
            size=10;attempts=existing;writes=0;statements.clear();
            assertThat(reserve()).isEqualTo(IdentityLifecycleStore.RateDecision.CAPACITY);
            assertThat(statements).containsExactly("select count(*) from public.identity_lifecycle_rate_bucket");
            assertThat(attempts).isEqualTo(existing);assertThat(writes).isZero();assertThat(size).isEqualTo(10);
        }
    }
    @Test void belowCapacityExistingBucketMayAdvanceOnlyAfterGlobalCapacityCheck(){
        size=9;attempts=4;assertThat(reserve()).isEqualTo(IdentityLifecycleStore.RateDecision.ADMITTED);
        assertThat(statements).hasSize(3);assertThat(statements.getFirst()).startsWith("select count(*)");assertThat(statements.get(1)).startsWith("select attempts").endsWith("for update");assertThat(statements.getLast()).startsWith("update public.identity_lifecycle_rate_bucket");
        assertThat(attempts).isEqualTo(5);assertThat(writes).isEqualTo(1);
        verify(writer,times(2)).requireSqlConnection(eq(scope),eq(source),any());
    }
    @Test void belowCapacityExistingAtLimitRetainsSelectorLimitWithoutMutation(){
        size=9;attempts=5;assertThat(reserve()).isEqualTo(IdentityLifecycleStore.RateDecision.SELECTOR_LIMIT);
        assertThat(statements).hasSize(2);assertThat(statements.getFirst()).startsWith("select count(*)");assertThat(writes).isZero();assertThat(attempts).isEqualTo(5);
    }
    @Test void insertAtLastFreeSlotMakesNextExistingSelectorRequestUniformCapacity(){
        size=9;attempts=null;assertThat(reserve()).isEqualTo(IdentityLifecycleStore.RateDecision.ADMITTED);
        assertThat(statements).hasSize(3);assertThat(statements.getFirst()).startsWith("select count(*)");assertThat(statements.getLast()).startsWith("insert into public.identity_lifecycle_rate_bucket");assertThat(size).isEqualTo(10);assertThat(attempts).isEqualTo(1);
        statements.clear();assertThat(reserve()).isEqualTo(IdentityLifecycleStore.RateDecision.CAPACITY);assertThat(statements).containsExactly("select count(*) from public.identity_lifecycle_rate_bucket");assertThat(writes).isEqualTo(1);assertThat(attempts).isEqualTo(1);
    }
}

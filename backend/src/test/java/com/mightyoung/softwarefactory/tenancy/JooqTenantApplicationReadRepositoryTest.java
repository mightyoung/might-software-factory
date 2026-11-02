package com.mightyoung.softwarefactory.tenancy;

import org.jooq.*;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class JooqTenantApplicationReadRepositoryTest {
    @Test void usesReadOnlyTransactionLocalTenantAndBoundEntitlementLookup() {
        UUID tenant = UUID.randomUUID();
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(invocation -> {
            TransactionDefinition definition = invocation.getArgument(0);
            assertThat(definition.isReadOnly()).isTrue();
            assertThat(definition.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            return new SimpleTransactionStatus();
        });
        List<String> sql = new ArrayList<>();
        List<List<Object>> bindings = new ArrayList<>();
        DSLContext values = DSL.using(SQLDialect.POSTGRES);
        DSLContext dsl = DSL.using(new MockConnection(context -> {
            sql.add(context.sql()); bindings.add(Arrays.asList(context.bindings()));
            if (context.sql().contains("set_config")) {
                var field = DSL.field("set_config",String.class);
                var rows = values.newResult(field); var row = values.newRecord(field); row.value1(tenant.toString()); rows.add(row);
                return new MockResult[]{new MockResult(1,rows)};
            }
            var enabled = DSL.field("enabled",Boolean.class); var version = DSL.field("version",Long.class);
            var rows = values.newResult(enabled,version); var row = values.newRecord(enabled,version);
            row.values(true,7L); rows.add(row); return new MockResult[]{new MockResult(1,rows)};
        }),SQLDialect.POSTGRES);
        var repository = new JooqTenantApplicationReadRepository(dsl,manager);
        assertThat(repository.findCurrent(tenant,"safety")).contains(new TenantApplicationState(true,7));
        assertThat(sql.getFirst()).contains("set_config(?, ?, true)");
        assertThat(bindings.getFirst()).containsExactly("app.tenant_id",tenant.toString());
        assertThat(sql.get(1)).contains("public.tenant_application","tenant_id = cast(? as uuid)","application_key = ?");
        assertThat(bindings.get(1)).containsExactly(tenant,"safety");
        assertThat(repository.findCurrent(null,"safety")).isEmpty();
        assertThat(repository.findCurrent(tenant,null)).isEmpty();
        verify(manager).commit(any());
    }
    @Test void missingRowsAndMalformedPersistedEntitlementsFailClosed() {
        UUID tenant = UUID.randomUUID();
        for (int scenario = 0; scenario < 5; scenario++) {
            final int current = scenario;
            var manager = mock(PlatformTransactionManager.class);
            when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
            DSLContext values = DSL.using(SQLDialect.POSTGRES);
            DSLContext dsl = DSL.using(new MockConnection(context -> {
                if (context.sql().contains("set_config")) {
                    var field = DSL.field("set_config",String.class);
                    var rows = values.newResult(field); var row = values.newRecord(field);
                    row.value1(tenant.toString()); rows.add(row);
                    return new MockResult[]{new MockResult(1,rows)};
                }
                var enabled = DSL.field("enabled",Boolean.class); var version = DSL.field("version",Long.class);
                var rows = values.newResult(enabled,version);
                if (current != 0) {
                    var row = values.newRecord(enabled,version);
                    row.values(current == 1 ? null : current != 4,
                            current == 2 ? null : current == 3 ? 0L : 1L);
                    rows.add(row);
                }
                return new MockResult[]{new MockResult(rows.size(),rows)};
            }),SQLDialect.POSTGRES);
            var state = new JooqTenantApplicationReadRepository(dsl,manager).findCurrent(tenant,"safety");
            if (current == 4) assertThat(state).contains(new TenantApplicationState(false,1));
            else assertThat(state).isEmpty();
        }
    }

}

package com.mightyoung.softwarefactory.identity;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JooqCurrentSessionReadRepositoryTest {

    @Test
    void currentIdentityQueryRequiresTheSessionCredentialGenerationInsideTheTenantReadTransaction() {
        UUID actor = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID account = UUID.fromString("11000000-0000-0000-0000-000000000001");
        UUID tenant = UUID.fromString("30000000-0000-0000-0000-000000000001");
        UUID member = UUID.fromString("20000000-0000-0000-0000-000000000001");
        var context = new CurrentTenantContext(actor, account, tenant, member, 17L, 23L, 7L);
        DSLContext values = DSL.using(SQLDialect.POSTGRES);
        List<String> statements = new ArrayList<>();
        List<List<Object>> bindings = new ArrayList<>();
        DSLContext dsl = DSL.using(new MockConnection(query -> {
            statements.add(query.sql());
            bindings.add(Arrays.asList(query.bindings()));
            if (query.sql().contains("set_config")) {
                var applied = DSL.field("set_config", String.class);
                var result = values.newResult(applied);
                result.add(values.newRecord(applied).value1(tenant.toString()));
                return new MockResult[] {new MockResult(1, result)};
            }
            var actorField = DSL.field("actor_id", UUID.class);
            var accountField = DSL.field("account_id", UUID.class);
            var tenantField = DSL.field("tenant_id", UUID.class);
            var tenantCode = DSL.field("tenant_code", String.class);
            var tenantName = DSL.field("tenant_name", String.class);
            var memberField = DSL.field("member_id", UUID.class);
            var displayName = DSL.field("display_name", String.class);
            var result = values.newResult(actorField, accountField, tenantField,
                    tenantCode, tenantName, memberField, displayName);
            result.add(values.newRecord(actorField, accountField, tenantField,
                    tenantCode, tenantName, memberField, displayName)
                    .values(actor, account, tenant, "north-plant", "North plant", member, "Synthetic Operator"));
            return new MockResult[] {new MockResult(1, result)};
        }), SQLDialect.POSTGRES);
        var transactions = new CapturingTransactionManager();

        var current = new JooqCurrentSessionReadRepository(dsl, transactions).findCurrent(context);

        assertThat(current).contains(new CurrentSessionDetails(
                actor, account, tenant, "north-plant", "North plant", member, "Synthetic Operator"));
        assertThat(statements).hasSize(2);
        assertThat(statements.get(1)).contains("a.credential_version = ?", "a.status = 'ACTIVE'",
                "t.auth_epoch = ?", "tm.auth_epoch = ?");
        assertThat(bindings.get(0)).containsExactly("app.tenant_id", tenant.toString());
        assertThat(bindings.get(1)).containsExactly(tenant, member, actor, account, 7L, 17L, 23L);
        assertThat(transactions.readOnly).isTrue();
        assertThat(transactions.isolation).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    private static final class CapturingTransactionManager extends AbstractPlatformTransactionManager {
        private boolean readOnly;
        private int isolation;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            readOnly = definition.isReadOnly();
            isolation = definition.getIsolationLevel();
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) { }

        @Override
        protected void doRollback(DefaultTransactionStatus status) { }
    }
}

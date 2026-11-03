package com.mightyoung.softwarefactory.authorization;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record3;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class JooqRoleScopeReadRepositoryTest {

    private static final UUID TENANT_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID MEMBER_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID ORG_ID = UUID.fromString("31000000-0000-0000-0000-000000000001");
    private static final UUID POSITION_ID = UUID.fromString("32000000-0000-0000-0000-000000000001");
    private static final UUID GROUP_ID = UUID.fromString("33000000-0000-0000-0000-000000000001");
    private static final UUID SCOPE_ID = UUID.fromString("34000000-0000-0000-0000-000000000001");

    @Test
    void scopesRequireAnActiveTenantRoleGrantAndTheRequestedPermission() {
        List<String> statements = new ArrayList<>();
        List<List<Object>> bindings = new ArrayList<>();
        DSLContext values = DSL.using(SQLDialect.POSTGRES);
        MockDataProvider provider = context -> {
            statements.add(context.sql());
            bindings.add(Arrays.stream(context.bindings()).map(JooqRoleScopeReadRepositoryTest::temporalBinding).toList());
            return new MockResult[] {scopeRow(values)};
        };
        DSLContext dsl = DSL.using(new MockConnection(provider), SQLDialect.POSTGRES);
        var repository = new JooqRoleScopeReadRepository(dsl);

        List<RoleScopeDescriptor> scopes = repository.findActiveScopes(
                TENANT_ID, MEMBER_ID, Set.of(ORG_ID), Set.of(POSITION_ID), Set.of(GROUP_ID), "finding.read");

        assertThat(scopes).containsExactly(new RoleScopeDescriptor(SCOPE_ID, "EXPLICIT", ORG_ID));
        String sql = statements.getFirst().toLowerCase(java.util.Locale.ROOT);
        assertThat(sql).contains("role_grant", "role_permission", "permission", "scope_rule", "scope_org")
                .contains("active", "valid_from", "valid_to", "subject_kind");
        assertThat(bindings.getFirst()).contains(TENANT_ID, MEMBER_ID, ORG_ID, POSITION_ID, GROUP_ID,
                "finding.read", "finding", "read", "MEMBER", "ORG", "POSITION", "GROUP");
    }

    @Test
    void workflowActionScopesUseTheirOwnActionPermissionInsteadOfFindingRead() {
        List<String> statements = new ArrayList<>();
        List<List<Object>> bindings = new ArrayList<>();
        DSLContext values = DSL.using(SQLDialect.POSTGRES);
        MockDataProvider provider = context -> {
            statements.add(context.sql());
            bindings.add(Arrays.stream(context.bindings()).map(JooqRoleScopeReadRepositoryTest::temporalBinding).toList());
            return new MockResult[] {scopeRow(values)};
        };
        DSLContext dsl = DSL.using(new MockConnection(provider), SQLDialect.POSTGRES);
        var repository = new JooqRoleScopeReadRepository(dsl);

        List<RoleScopeDescriptor> scopes = repository.findActiveActionScopes(
                TENANT_ID, MEMBER_ID, Set.of(ORG_ID), Set.of(POSITION_ID), Set.of(GROUP_ID),
                "finding", "verify", "finding.verify");

        assertThat(scopes).containsExactly(new RoleScopeDescriptor(SCOPE_ID, "EXPLICIT", ORG_ID));
        assertThat(statements.getFirst().toLowerCase(java.util.Locale.ROOT))
                .contains("permission", "\"p\".\"code\"", "\"p\".\"resource_type\"", "\"p\".\"action\"");
        assertThat(bindings.getFirst()).contains("finding.verify", "finding", "verify");
        assertThat(bindings.getFirst()).doesNotContain("finding.read", "read");
    }

    @Test
    void explicitTimeBindsBothHalfOpenGrantBoundsAndNeverUsesTheTransactionClock() {
        var at = OffsetDateTime.parse("2026-11-03T10:15:30.123456Z");
        var values = DSL.using(SQLDialect.POSTGRES);
        List<List<Object>> bindings = new ArrayList<>();
        var repository = new JooqRoleScopeReadRepository(DSL.using(new MockConnection(context -> {
            assertThat(context.sql()).doesNotContain("current_timestamp", "2026-11");
            bindings.add(Arrays.stream(context.bindings()).map(JooqRoleScopeReadRepositoryTest::temporalBinding).toList());
            return new MockResult[] {scopeRow(values)};
        }), SQLDialect.POSTGRES));
        repository.findActiveActionScopesAt(TENANT_ID, MEMBER_ID, Set.of(ORG_ID), Set.of(POSITION_ID),
                Set.of(GROUP_ID), "finding", "dispatch", "finding.dispatch", at);
        assertThat(bindings).singleElement().satisfies(bound ->
                assertThat(bound.stream().filter(at::equals).count()).isEqualTo(2));
        assertThatNullPointerException().isThrownBy(() -> repository.findActiveActionScopesAt(
                TENANT_ID, MEMBER_ID, Set.of(), Set.of(), Set.of(), "finding", "read", "finding.read", null));
        assertThat(bindings).hasSize(1);
    }

    @Test
    void legacyOnlyRoleAdapterFailsExplicitTimeInsteadOfFallingBack() {
        RoleScopeReadRepository legacy = (tenant, member, organizations, positions, groups, permission) -> {
            throw new AssertionError("Legacy fallback");
        };
        assertThatThrownBy(() -> legacy.findActiveActionScopesAt(TENANT_ID, MEMBER_ID, Set.of(), Set.of(),
                Set.of(), "finding", "read", "finding.read", OffsetDateTime.parse("2026-11-03T10:15:30Z")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static MockResult scopeRow(DSLContext dsl) {
        Field<UUID> ruleId = DSL.field("scope_rule_id", UUID.class);
        Field<String> kind = DSL.field("kind", String.class);
        Field<UUID> organizationId = DSL.field("org_id", UUID.class);
        var result = dsl.newResult(ruleId, kind, organizationId);
        Record3<UUID, String, UUID> row = dsl.newRecord(ruleId, kind, organizationId);
        row.set(ruleId, SCOPE_ID);
        row.set(kind, "EXPLICIT");
        row.set(organizationId, ORG_ID);
        result.add(row);
        return new MockResult(result.size(), result);
    }
    // PostgreSQL jOOQ may encode timestamptz as a bound JDBC String; compare its exact instant.
    private static Object temporalBinding(Object value) {
        if (value instanceof String encoded && encoded.matches("\\d{4}-\\d{2}-\\d{2} .*")) {
            return OffsetDateTime.parse(encoded.replace(' ', 'T')).withOffsetSameInstant(java.time.ZoneOffset.UTC);
        }
        return value;
    }


}

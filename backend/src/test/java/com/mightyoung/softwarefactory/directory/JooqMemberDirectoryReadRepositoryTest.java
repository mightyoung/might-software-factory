package com.mightyoung.softwarefactory.directory;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record1;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JooqMemberDirectoryReadRepositoryTest {

    private static final UUID TENANT_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID MEMBER_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID ORG_ID = UUID.fromString("31000000-0000-0000-0000-000000000001");
    private static final UUID CHILD_ORG_ID = UUID.fromString("31000000-0000-0000-0000-000000000002");
    private static final UUID POSITION_ID = UUID.fromString("32000000-0000-0000-0000-000000000001");
    private static final UUID GROUP_ID = UUID.fromString("33000000-0000-0000-0000-000000000001");
    private static final UUID EXPLICIT_ORG_ID = UUID.fromString("31000000-0000-0000-0000-000000000003");

    @Test
    void readsCurrentMembershipsAndExpandsOnlyActiveOrganizationsInsideTheTenant() {
        List<String> statements = new ArrayList<>();
        List<List<Object>> bindings = new ArrayList<>();
        DSLContext values = DSL.using(SQLDialect.POSTGRES);
        MockDataProvider provider = context -> {
            statements.add(context.sql());
            bindings.add(Arrays.asList(context.bindings()));
            String sql = context.sql().toLowerCase(java.util.Locale.ROOT);
            if (sql.contains("with recursive")) {
                return new MockResult[] {ids(values, "id", ORG_ID, CHILD_ORG_ID)};
            }
            if (sql.contains("from \"public\".\"org_unit\"")) {
                return new MockResult[] {ids(values, "id", EXPLICIT_ORG_ID)};
            }
            if (sql.contains("member_org")) {
                return new MockResult[] {ids(values, "org_id", ORG_ID)};
            }
            if (sql.contains("member_position")) {
                return new MockResult[] {ids(values, "position_id", POSITION_ID)};
            }
            if (sql.contains("group_member")) {
                return new MockResult[] {ids(values, "group_id", GROUP_ID)};
            }
            throw new AssertionError("Unexpected directory query: " + context.sql());
        };
        DSLContext dsl = DSL.using(new MockConnection(provider), SQLDialect.POSTGRES);
        var repository = new JooqMemberDirectoryReadRepository(dsl);

        MemberDirectorySnapshot memberships = repository.findCurrentMemberships(TENANT_ID, MEMBER_ID);
        Set<UUID> departmentTree = repository.expandDepartmentTree(TENANT_ID, MEMBER_ID);
        Set<UUID> explicitOrganizations = repository.findActiveOrganizations(
                TENANT_ID, Set.of(EXPLICIT_ORG_ID));

        assertThat(memberships).isEqualTo(new MemberDirectorySnapshot(
                Set.of(ORG_ID), Set.of(POSITION_ID), Set.of(GROUP_ID)));
        assertThat(departmentTree).containsExactlyInAnyOrder(ORG_ID, CHILD_ORG_ID);
        assertThat(explicitOrganizations).containsExactly(EXPLICIT_ORG_ID);
        assertThat(statements).hasSize(5);
        assertThat(statements.subList(0, 3)).allSatisfy(sql -> {
            assertThat(sql.toLowerCase(java.util.Locale.ROOT))
                    .contains("valid_from <= current_timestamp", "valid_to is null", "active = true");
        });
        assertThat(statements.get(0).toLowerCase(java.util.Locale.ROOT)).contains("member_org", "tenant_id");
        assertThat(statements.get(1).toLowerCase(java.util.Locale.ROOT)).contains("member_position", "tenant_id");
        assertThat(statements.get(2).toLowerCase(java.util.Locale.ROOT)).contains("group_member", "tenant_id");
        assertThat(statements.get(3).toLowerCase(java.util.Locale.ROOT))
                .contains("with recursive department_tree", "child.parent_id = parent.id");
        assertThat(statements.get(4).toLowerCase(java.util.Locale.ROOT))
                .contains("from \"public\".\"org_unit\"", "\"active\" = true", "\"tenant_id\"");
        assertThat(bindings.subList(0, 4)).allSatisfy(queryBindings ->
                assertThat(queryBindings).containsExactly(TENANT_ID, MEMBER_ID));
        assertThat(bindings.get(4)).contains(TENANT_ID, EXPLICIT_ORG_ID);
    }

    private static MockResult ids(DSLContext dsl, String column, UUID... values) {
        Field<UUID> field = DSL.field(column, UUID.class);
        var result = dsl.newResult(field);
        for (UUID value : values) {
            Record1<UUID> row = dsl.newRecord(field);
            row.set(field, value);
            result.add(row);
        }
        return new MockResult(result.size(), result);
    }
}

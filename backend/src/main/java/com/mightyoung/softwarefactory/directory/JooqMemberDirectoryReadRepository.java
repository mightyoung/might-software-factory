package com.mightyoung.softwarefactory.directory;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

/** Directory-owned SQL for effective memberships and active organization hierarchy. */
@Repository
public final class JooqMemberDirectoryReadRepository implements MemberDirectoryReadRepository {

    private static final String ORGANIZATIONS_SQL = """
            select distinct mo.org_id
              from public.member_org mo
              join public.org_unit ou
                on ou.tenant_id = mo.tenant_id and ou.id = mo.org_id and ou.active = true
             where mo.tenant_id = cast(? as uuid)
               and mo.member_id = cast(? as uuid)
               and mo.valid_from <= current_timestamp
               and (mo.valid_to is null or mo.valid_to > current_timestamp)
            """;

    private static final String POSITIONS_SQL = """
            select distinct mp.position_id
              from public.member_position mp
              join public."position" pos
                on pos.tenant_id = mp.tenant_id and pos.id = mp.position_id and pos.active = true
             where mp.tenant_id = cast(? as uuid)
               and mp.member_id = cast(? as uuid)
               and mp.valid_from <= current_timestamp
               and (mp.valid_to is null or mp.valid_to > current_timestamp)
            """;

    private static final String GROUPS_SQL = """
            select distinct gm.group_id
              from public.group_member gm
              join public.user_group g
                on g.tenant_id = gm.tenant_id and g.id = gm.group_id and g.active = true
             where gm.tenant_id = cast(? as uuid)
               and gm.member_id = cast(? as uuid)
               and gm.valid_from <= current_timestamp
               and (gm.valid_to is null or gm.valid_to > current_timestamp)
            """;

    private static final String DEPARTMENT_TREE_SQL = """
            with recursive department_tree (tenant_id, id) as (
                select ou.tenant_id, ou.id
                  from public.member_org mo
                  join public.org_unit ou
                    on ou.tenant_id = mo.tenant_id and ou.id = mo.org_id and ou.active = true
                 where mo.tenant_id = cast(? as uuid)
                   and mo.member_id = cast(? as uuid)
                   and mo.valid_from <= current_timestamp
                   and (mo.valid_to is null or mo.valid_to > current_timestamp)
                union
                select child.tenant_id, child.id
                  from public.org_unit child
                  join department_tree parent
                    on child.tenant_id = parent.tenant_id and child.parent_id = parent.id
                 where child.active = true
            )
            select id from department_tree
            """;

    private static final Table<?> ORG_UNIT = table(name("public", "org_unit"));
    private static final Field<UUID> ORG_ID = field(name("id"), UUID.class);
    private static final Field<UUID> TENANT_ID = field(name("tenant_id"), UUID.class);
    private static final Field<Boolean> ACTIVE = field(name("active"), Boolean.class);

    private final DSLContext dsl;

    public JooqMemberDirectoryReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public MemberDirectorySnapshot findCurrentMemberships(UUID tenantId, UUID memberId) {
        return new MemberDirectorySnapshot(
                fetchIds(ORGANIZATIONS_SQL, tenantId, memberId),
                fetchIds(POSITIONS_SQL, tenantId, memberId),
                fetchIds(GROUPS_SQL, tenantId, memberId));
    }

    @Override
    public Set<UUID> expandDepartmentTree(UUID tenantId, UUID memberId) {
        return fetchIds(DEPARTMENT_TREE_SQL, tenantId, memberId);
    }

    @Override
    public Set<UUID> findActiveOrganizations(UUID tenantId, Set<UUID> organizationIds) {
        if (organizationIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(dsl.select(ORG_ID)
                .from(ORG_UNIT)
                .where(TENANT_ID.eq(tenantId).and(ACTIVE.isTrue()).and(ORG_ID.in(organizationIds)))
                .fetch(ORG_ID));
    }

    private Set<UUID> fetchIds(String sql, Object... bindings) {
        Set<UUID> ids = new LinkedHashSet<>();
        dsl.fetch(sql, bindings).forEach(row -> ids.add(row.get(0, UUID.class)));
        return Set.copyOf(ids);
    }
}

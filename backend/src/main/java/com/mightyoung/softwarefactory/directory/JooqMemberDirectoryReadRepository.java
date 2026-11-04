package com.mightyoung.softwarefactory.directory;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

/** Directory-owned SQL for effective memberships and active organization hierarchy. */
@Repository
public final class JooqMemberDirectoryReadRepository implements MemberDirectoryReadRepository {

    // A null value is used only by the retained legacy methods, which keep transaction time.
    private static final String EVALUATION_SQL = "with evaluation_time as (select coalesce(cast(? as timestamptz), current_timestamp) as evaluated_at)\n";
    private static final String EVALUATION_AT_SQL = "with evaluation_time as (select cast(? as timestamptz) as evaluated_at)\n";

    private static final String ORGANIZATIONS_SQL = """
            select distinct mo.org_id
              from public.member_org mo
              cross join evaluation_time e
              join public.org_unit ou
                on ou.tenant_id = mo.tenant_id and ou.id = mo.org_id and ou.active = true
             where mo.tenant_id = cast(? as uuid)
               and mo.member_id = cast(? as uuid)
               and mo.valid_from <= e.evaluated_at
               and (mo.valid_to is null or mo.valid_to > e.evaluated_at)
            """;

    private static final String POSITIONS_SQL = """
            select distinct mp.position_id
              from public.member_position mp
              cross join evaluation_time e
              join public."position" pos
                on pos.tenant_id = mp.tenant_id and pos.id = mp.position_id and pos.active = true
             where mp.tenant_id = cast(? as uuid)
               and mp.member_id = cast(? as uuid)
               and mp.valid_from <= e.evaluated_at
               and (mp.valid_to is null or mp.valid_to > e.evaluated_at)
            """;

    private static final String GROUPS_SQL = """
            select distinct gm.group_id
              from public.group_member gm
              cross join evaluation_time e
              join public.user_group g
                on g.tenant_id = gm.tenant_id and g.id = gm.group_id and g.active = true
             where gm.tenant_id = cast(? as uuid)
               and gm.member_id = cast(? as uuid)
               and gm.valid_from <= e.evaluated_at
               and (gm.valid_to is null or gm.valid_to > e.evaluated_at)
            """;

    private static final String DEPARTMENT_TREE_SQL = """
            with recursive evaluation_time as (select coalesce(cast(? as timestamptz), current_timestamp) as evaluated_at),
            department_tree (tenant_id, id) as (
                select ou.tenant_id, ou.id
                  from public.member_org mo
                  cross join evaluation_time e
                  join public.org_unit ou
                    on ou.tenant_id = mo.tenant_id and ou.id = mo.org_id and ou.active = true
                 where mo.tenant_id = cast(? as uuid)
                   and mo.member_id = cast(? as uuid)
                   and mo.valid_from <= e.evaluated_at
                   and (mo.valid_to is null or mo.valid_to > e.evaluated_at)
                union
                select child.tenant_id, child.id
                  from public.org_unit child
                  join department_tree parent
                    on child.tenant_id = parent.tenant_id and child.parent_id = parent.id
                 where child.active = true
            )
            select id from department_tree
            """;

    private static final Field<UUID> ORG_ID = field(name("id"), UUID.class);
    private static final String ACTIVE_ORGANIZATIONS_SQL = "select id from public.org_unit where tenant_id=cast(? as uuid) and active=true and id=any(cast(? as uuid[]))";
    private static final String DEPARTMENT_TREE_AT_SQL = DEPARTMENT_TREE_SQL.replace(
            "coalesce(cast(? as timestamptz), current_timestamp)","cast(? as timestamptz)");

    /** Only explicit common-clock and fixed-array caller queries; legacy variable IN forms stay outside. */
    public static java.util.Map<String,Integer> commandReadSql() {
        return java.util.Map.of(EVALUATION_AT_SQL+ORGANIZATIONS_SQL,3,EVALUATION_AT_SQL+POSITIONS_SQL,3,
                EVALUATION_AT_SQL+GROUPS_SQL,3,DEPARTMENT_TREE_AT_SQL,3,ACTIVE_ORGANIZATIONS_SQL,2);
    }

    private final DSLContext dsl;

    public JooqMemberDirectoryReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public MemberDirectorySnapshot findCurrentMemberships(UUID tenantId, UUID memberId) {
        return memberships(tenantId, memberId, null);
    }

    @Override
    public MemberDirectorySnapshot findCurrentMembershipsAt(UUID tenantId, UUID memberId, OffsetDateTime evaluatedAt) {
        return memberships(tenantId, memberId, Objects.requireNonNull(evaluatedAt, "Evaluation time is required"));
    }

    private MemberDirectorySnapshot memberships(UUID tenantId, UUID memberId, OffsetDateTime evaluatedAt) {
        String evaluation=evaluatedAt==null?EVALUATION_SQL:EVALUATION_AT_SQL;
        return new MemberDirectorySnapshot(
                fetchIds(evaluation + ORGANIZATIONS_SQL, evaluatedAt, tenantId, memberId),
                fetchIds(evaluation + POSITIONS_SQL, evaluatedAt, tenantId, memberId),
                fetchIds(evaluation + GROUPS_SQL, evaluatedAt, tenantId, memberId));
    }

    @Override
    public Set<UUID> expandDepartmentTree(UUID tenantId, UUID memberId) {
        return fetchIds(DEPARTMENT_TREE_SQL, null, tenantId, memberId);
    }

    @Override
    public Set<UUID> expandDepartmentTreeAt(UUID tenantId, UUID memberId, OffsetDateTime evaluatedAt) {
        return fetchIds(DEPARTMENT_TREE_AT_SQL,
                Objects.requireNonNull(evaluatedAt, "Evaluation time is required"), tenantId, memberId);
    }

    @Override
    public Set<UUID> findActiveOrganizations(UUID tenantId, Set<UUID> organizationIds) {
        if (organizationIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(dsl.select(ORG_ID).from(table(name("public","org_unit")))
                .where(field(name("tenant_id"),UUID.class).eq(tenantId)
                        .and(field(name("active"),Boolean.class).isTrue()).and(ORG_ID.in(organizationIds))).fetch(ORG_ID));
    }

    @Override public Set<UUID> findActiveOrganizationsInCaller(UUID tenantId, Set<UUID> organizationIds) {
        if(organizationIds.isEmpty())return Set.of();
        // A single fixed parameter shape; bind canonical UUID text, never create a driver Array.
        String ids=organizationIds.stream().map(UUID::toString).sorted().collect(java.util.stream.Collectors.joining(",","{","}"));
        return Set.copyOf(dsl.resultQuery(ACTIVE_ORGANIZATIONS_SQL,
                tenantId,ids).coerce(ORG_ID).fetch(ORG_ID));
    }

    private Set<UUID> fetchIds(String sql, Object... bindings) {
        Set<UUID> ids = new LinkedHashSet<>();
        dsl.resultQuery(sql, bindings).coerce(field("id",UUID.class)).fetch().forEach(row -> ids.add(row.get(0, UUID.class)));
        return Set.copyOf(ids);
    }
}

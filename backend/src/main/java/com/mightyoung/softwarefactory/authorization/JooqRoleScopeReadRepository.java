package com.mightyoung.softwarefactory.authorization;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

/** Authorization-owned SQL for current role grants and their action-specific scope rules. */
@Repository
public final class JooqRoleScopeReadRepository implements RoleScopeReadRepository {

    private static final Table<?> ROLE_GRANT = table(name("public", "role_grant")).as("rg");
    private static final Table<?> ROLE = table(name("public", "role")).as("r");
    private static final Table<?> ROLE_PERMISSION = table(name("public", "role_permission")).as("rp");
    private static final Table<?> PERMISSION = table(name("public", "permission")).as("p");
    private static final Table<?> SCOPE_RULE = table(name("public", "scope_rule")).as("sr");
    private static final Table<?> SCOPE_ORG = table(name("public", "scope_org")).as("so");

    private static final Field<UUID> GRANT_TENANT_ID = field(name("rg", "tenant_id"), UUID.class);
    private static final Field<UUID> GRANT_ROLE_ID = field(name("rg", "role_id"), UUID.class);
    private static final Field<String> SUBJECT_KIND = field(name("rg", "subject_kind"), String.class);
    private static final Field<UUID> GRANT_MEMBER_ID = field(name("rg", "member_id"), UUID.class);
    private static final Field<UUID> GRANT_ORG_ID = field(name("rg", "org_id"), UUID.class);
    private static final Field<UUID> GRANT_POSITION_ID = field(name("rg", "position_id"), UUID.class);
    private static final Field<UUID> GRANT_GROUP_ID = field(name("rg", "group_id"), UUID.class);
    private static final Field<OffsetDateTime> GRANT_VALID_FROM =
            field(name("rg", "valid_from"), OffsetDateTime.class);
    private static final Field<OffsetDateTime> GRANT_VALID_TO =
            field(name("rg", "valid_to"), OffsetDateTime.class);

    private static final Field<UUID> ROLE_TENANT_ID = field(name("r", "tenant_id"), UUID.class);
    private static final Field<UUID> ROLE_ID = field(name("r", "id"), UUID.class);
    private static final Field<Boolean> ROLE_ACTIVE = field(name("r", "active"), Boolean.class);

    private static final Field<UUID> ROLE_PERMISSION_TENANT_ID = field(name("rp", "tenant_id"), UUID.class);
    private static final Field<UUID> ROLE_PERMISSION_ROLE_ID = field(name("rp", "role_id"), UUID.class);
    private static final Field<UUID> ROLE_PERMISSION_PERMISSION_ID = field(name("rp", "permission_id"), UUID.class);
    private static final Field<UUID> ROLE_PERMISSION_SCOPE_ID = field(name("rp", "scope_rule_id"), UUID.class);

    private static final Field<UUID> PERMISSION_ID = field(name("p", "id"), UUID.class);
    private static final Field<String> PERMISSION_CODE = field(name("p", "code"), String.class);
    private static final Field<String> PERMISSION_RESOURCE_TYPE = field(name("p", "resource_type"), String.class);
    private static final Field<String> PERMISSION_ACTION = field(name("p", "action"), String.class);

    private static final Field<UUID> SCOPE_TENANT_ID = field(name("sr", "tenant_id"), UUID.class);
    private static final Field<UUID> SCOPE_ID = field(name("sr", "id"), UUID.class);
    private static final Field<String> SCOPE_KIND = field(name("sr", "kind"), String.class);
    private static final Field<UUID> SCOPE_ORG_TENANT_ID = field(name("so", "tenant_id"), UUID.class);
    private static final Field<UUID> SCOPE_ORG_RULE_ID = field(name("so", "scope_rule_id"), UUID.class);
    private static final Field<UUID> SCOPE_ORG_ID = field(name("so", "org_id"), UUID.class);

    private static final Field<OffsetDateTime> CURRENT_TIMESTAMP =
            DSL.field("current_timestamp", OffsetDateTime.class);

    private final DSLContext dsl;

    public JooqRoleScopeReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<RoleScopeDescriptor> findActiveScopes(
            UUID tenantId,
            UUID memberId,
            Set<UUID> organizationIds,
            Set<UUID> positionIds,
            Set<UUID> groupIds,
            String permissionCode) {
        return findActiveActionScopes(tenantId, memberId, organizationIds, positionIds, groupIds,
                "finding", "read", permissionCode);
    }

    @Override
    public List<RoleScopeDescriptor> findActiveActionScopes(
            UUID tenantId,
            UUID memberId,
            Set<UUID> organizationIds,
            Set<UUID> positionIds,
            Set<UUID> groupIds,
            String resourceType,
            String action,
            String permissionCode) {
        return actionScopes(tenantId, memberId, organizationIds, positionIds, groupIds,
                resourceType, action, permissionCode, CURRENT_TIMESTAMP);
    }

    @Override
    public List<RoleScopeDescriptor> findActiveActionScopesAt(
            UUID tenantId, UUID memberId, Set<UUID> organizationIds, Set<UUID> positionIds,
            Set<UUID> groupIds, String resourceType, String action, String permissionCode,
            OffsetDateTime evaluatedAt) {
        return actionScopes(tenantId, memberId, organizationIds, positionIds, groupIds,
                resourceType, action, permissionCode,
                DSL.val(Objects.requireNonNull(evaluatedAt, "Evaluation time is required"), OffsetDateTime.class));
    }

    private List<RoleScopeDescriptor> actionScopes(
            UUID tenantId, UUID memberId, Set<UUID> organizationIds, Set<UUID> positionIds,
            Set<UUID> groupIds, String resourceType, String action, String permissionCode,
            Field<OffsetDateTime> evaluatedAt) {
        Condition currentGrant = GRANT_VALID_FROM.le(evaluatedAt)
                .and(GRANT_VALID_TO.isNull().or(GRANT_VALID_TO.gt(evaluatedAt)));
        Condition subject = SUBJECT_KIND.eq("MEMBER").and(GRANT_MEMBER_ID.eq(memberId))
                .or(SUBJECT_KIND.eq("ORG").and(GRANT_ORG_ID.in(organizationIds)))
                .or(SUBJECT_KIND.eq("POSITION").and(GRANT_POSITION_ID.in(positionIds)))
                .or(SUBJECT_KIND.eq("GROUP").and(GRANT_GROUP_ID.in(groupIds)));

        return dsl.selectDistinct(SCOPE_ID, SCOPE_KIND, SCOPE_ORG_ID)
                .from(ROLE_GRANT)
                .join(ROLE).on(ROLE_TENANT_ID.eq(GRANT_TENANT_ID)
                        .and(ROLE_ID.eq(GRANT_ROLE_ID)).and(ROLE_ACTIVE.isTrue()))
                .join(ROLE_PERMISSION).on(ROLE_PERMISSION_TENANT_ID.eq(GRANT_TENANT_ID)
                        .and(ROLE_PERMISSION_ROLE_ID.eq(GRANT_ROLE_ID)))
                .join(PERMISSION).on(PERMISSION_ID.eq(ROLE_PERMISSION_PERMISSION_ID)
                        .and(PERMISSION_CODE.eq(permissionCode))
                        .and(PERMISSION_RESOURCE_TYPE.eq(resourceType))
                        .and(PERMISSION_ACTION.eq(action)))
                .join(SCOPE_RULE).on(SCOPE_TENANT_ID.eq(ROLE_PERMISSION_TENANT_ID)
                        .and(SCOPE_ID.eq(ROLE_PERMISSION_SCOPE_ID)))
                .leftJoin(SCOPE_ORG).on(SCOPE_ORG_TENANT_ID.eq(ROLE_PERMISSION_TENANT_ID)
                        .and(SCOPE_ORG_RULE_ID.eq(SCOPE_ID)))
                .where(GRANT_TENANT_ID.eq(tenantId).and(currentGrant).and(subject))
                .fetch(row -> new RoleScopeDescriptor(
                        row.get(0, UUID.class), row.get(1, String.class), row.get(2, UUID.class)));
    }
}

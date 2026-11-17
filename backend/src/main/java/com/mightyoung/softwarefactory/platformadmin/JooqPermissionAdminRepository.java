package com.mightyoung.softwarefactory.platformadmin;

import com.mightyoung.softwarefactory.platformadmin.PermissionAdminException.Reason;
import com.mightyoung.softwarefactory.platformadmin.PermissionAdminModels.CatalogItem;
import com.mightyoung.softwarefactory.platformadmin.PermissionAdminModels.RoleView;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

@Repository
public final class JooqPermissionAdminRepository implements PermissionAdminRepository {
    private final DSLContext dsl;
    private final TransactionTemplate read;

    public JooqPermissionAdminRepository(DSLContext dsl, PlatformTransactionManager manager) {
        this.dsl = dsl;
        this.read = new TransactionTemplate(manager);
        this.read.setReadOnly(true);
        this.read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    public List<CatalogItem> catalog(UUID tenantId) {
        return read.execute(status -> {
            scope(tenantId);
            return dsl.fetch("select code, application_key, label from public.permission "
                            + "where application_key is not null order by sort_order, code")
                    .map(r -> new CatalogItem(r.get(0, String.class), r.get(1, String.class), r.get(2, String.class)));
        });
    }

    @Override
    public List<RoleView> roles(UUID tenantId) {
        return read.execute(status -> {
            scope(tenantId);
            return loadRoles(tenantId, null);
        });
    }

    @Override
    public RoleView replaceRolePermissions(UUID tenantId, UUID roleId, Set<String> desiredCodes,
                                           String expectedVersion, UUID actorMemberId, UUID traceId) {
        scope(tenantId);
        // The caller's identity security transaction already holds the global authorization write lock, which
        // serializes every security writer; no row lock (and no UPDATE privilege on role) is needed.
        if (dsl.fetchOptional("select 1 from public.\"role\" where tenant_id = ? and id = ?",
                tenantId, roleId).isEmpty()) {
            throw new PermissionAdminException(Reason.ROLE_NOT_FOUND, "Role not found");
        }
        Map<String, UUID> rows = new LinkedHashMap<>(); // current code -> scope rule
        for (Record r : dsl.fetch("select p.code, rp.scope_rule_id from public.role_permission rp "
                + "join public.permission p on p.id = rp.permission_id where rp.tenant_id = ? and rp.role_id = ?",
                tenantId, roleId)) {
            rows.put(r.get(0, String.class), r.get(1, UUID.class));
        }
        if (!version(rows.keySet()).equals(expectedVersion)) {
            throw new PermissionAdminException(Reason.VERSION_CONFLICT, "Stale role permissions");
        }
        Map<String, UUID> catalog = new LinkedHashMap<>();
        Map<String, UUID> allIds = new LinkedHashMap<>();
        for (Record r : dsl.fetch("select code, id, application_key from public.permission")) {
            allIds.put(r.get(0, String.class), r.get(1, UUID.class));
            if (r.get(2, String.class) != null) catalog.put(r.get(0, String.class), r.get(1, UUID.class));
        }
        for (String code : desiredCodes) {
            if (!catalog.containsKey(code) && !rows.containsKey(code)) {
                throw new PermissionAdminException(Reason.UNKNOWN_PERMISSION, "Unknown permission " + code);
            }
        }
        // The full-set API exposes these codes, but this catalog editor cannot grant or revoke them.
        for (String code : rows.keySet()) {
            if (!catalog.containsKey(code) && !desiredCodes.contains(code)) {
                throw new PermissionAdminException(Reason.UNKNOWN_PERMISSION, "Permission is not configurable " + code);
            }
        }
        Set<String> added = new TreeSet<>(desiredCodes);
        added.removeAll(rows.keySet());
        Set<String> removed = new TreeSet<>(rows.keySet());
        removed.removeAll(desiredCodes);
        if (!added.isEmpty() || !removed.isEmpty()) {
            if (removed.contains(PermissionAdminService.MANAGE)
                    && !otherManagerExists(tenantId, roleId)) {
                throw new PermissionAdminException(Reason.LAST_MANAGER, "Last permission manager");
            }
            UUID scopeRule = null;
            if (!added.isEmpty()) {
                Set<UUID> scopes = new java.util.HashSet<>();
                rows.forEach((code, existingScope) -> {
                    if (catalog.containsKey(code)) scopes.add(existingScope);
                });
                if (scopes.size() != 1 || scopes.contains(null)) {
                    throw new PermissionAdminException(Reason.ROLE_HAS_NO_SCOPE, "No single scope rule to inherit");
                }
                scopeRule = scopes.iterator().next();
            }
            for (String code : removed) {
                dsl.execute("delete from public.role_permission where tenant_id = ? and role_id = ? and permission_id = ?",
                        tenantId, roleId, allIds.get(code));
            }
            for (String code : added) {
                dsl.execute("insert into public.role_permission (tenant_id, role_id, permission_id, scope_rule_id) "
                        + "values (?, ?, ?, ?)", tenantId, roleId, catalog.get(code), scopeRule);
            }
            dsl.execute("insert into public.permission_change_event (tenant_id, id, role_id, actor_member_id, "
                    + "added_permission_codes, removed_permission_codes, trace_id, occurred_at) "
                    + "values (?, ?, ?, ?, cast(? as text[]), cast(? as text[]), ?, now())",
                    tenantId, UUID.randomUUID(), roleId, actorMemberId, pgArray(added), pgArray(removed), traceId);
        }
        return loadRoles(tenantId, roleId).get(0);
    }

    private boolean otherManagerExists(UUID tenantId, UUID excludedRoleId) {
        // DEPT_TREE includes its active department roots, so its scope is nonempty exactly when DEPT is.
        // Subject and scope checks mirror the directory/role resolver used by EffectivePermissions.
        return !dsl.fetch("""
                with active_members as (
                    select m.tenant_id,m.id
                      from public.tenant_member m
                      join public.tenant t on t.id=m.tenant_id and t.status='ACTIVE'
                      join public.account a on a.id=m.account_id and a.status='ACTIVE'
                      join public.principal actor on actor.account_id=a.id and actor.kind='HUMAN' and actor.status='ACTIVE'
                     where m.tenant_id=? and m.status='ACTIVE'
                ), member_organizations as (
                    select mo.tenant_id,mo.member_id,mo.org_id
                      from public.member_org mo
                      join active_members m on m.tenant_id=mo.tenant_id and m.id=mo.member_id
                      join public.org_unit o on o.tenant_id=mo.tenant_id and o.id=mo.org_id and o.active
                     where mo.valid_from<=current_timestamp and (mo.valid_to is null or mo.valid_to>current_timestamp)
                ), member_positions as (
                    select mp.tenant_id,mp.member_id,mp.position_id
                      from public.member_position mp
                      join active_members m on m.tenant_id=mp.tenant_id and m.id=mp.member_id
                      join public.position pos on pos.tenant_id=mp.tenant_id and pos.id=mp.position_id and pos.active
                     where mp.valid_from<=current_timestamp and (mp.valid_to is null or mp.valid_to>current_timestamp)
                ), member_groups as (
                    select gm.tenant_id,gm.member_id,gm.group_id
                      from public.group_member gm
                      join active_members m on m.tenant_id=gm.tenant_id and m.id=gm.member_id
                      join public.user_group grp on grp.tenant_id=gm.tenant_id and grp.id=gm.group_id and grp.active
                     where gm.valid_from<=current_timestamp and (gm.valid_to is null or gm.valid_to>current_timestamp)
                )
                select 1 from public.role_permission rp
                  join public.permission p on p.id=rp.permission_id and p.code=?
                       and p.resource_type='platform' and p.action='permission.manage'
                  join public."role" r on r.tenant_id=rp.tenant_id and r.id=rp.role_id and r.active
                  join public.scope_rule sr on sr.tenant_id=rp.tenant_id and sr.id=rp.scope_rule_id
                  join public.role_grant g on g.tenant_id=rp.tenant_id and g.role_id=rp.role_id
                       and g.valid_from<=current_timestamp and (g.valid_to is null or g.valid_to>current_timestamp)
                  join active_members m on m.tenant_id=g.tenant_id and (
                       (g.subject_kind='MEMBER' and g.member_id=m.id)
                    or (g.subject_kind='ORG' and exists(select 1 from member_organizations mo
                         where mo.tenant_id=m.tenant_id and mo.member_id=m.id and mo.org_id=g.org_id))
                    or (g.subject_kind='POSITION' and exists(select 1 from member_positions mp
                         where mp.tenant_id=m.tenant_id and mp.member_id=m.id and mp.position_id=g.position_id))
                    or (g.subject_kind='GROUP' and exists(select 1 from member_groups gm
                         where gm.tenant_id=m.tenant_id and gm.member_id=m.id and gm.group_id=g.group_id)))
                 where rp.tenant_id=? and rp.role_id<>? and (
                       (sr.kind in ('DEPT','DEPT_TREE') and exists(select 1 from member_organizations mo
                         where mo.tenant_id=m.tenant_id and mo.member_id=m.id))
                    or (sr.kind='EXPLICIT' and exists(select 1 from public.scope_org so
                         join public.org_unit o on o.tenant_id=so.tenant_id and o.id=so.org_id and o.active
                         where so.tenant_id=sr.tenant_id and so.scope_rule_id=sr.id)))
                 limit 1
                """, tenantId, PermissionAdminService.MANAGE, tenantId, excludedRoleId).isEmpty();
    }

    private List<RoleView> loadRoles(UUID tenantId, UUID onlyRole) {
        Map<UUID, Set<String>> codes = new LinkedHashMap<>();
        for (Record r : dsl.fetch("select rp.role_id, p.code from public.role_permission rp "
                + "join public.permission p on p.id = rp.permission_id where rp.tenant_id = ?", tenantId)) {
            codes.computeIfAbsent(r.get(0, UUID.class), k -> new TreeSet<>()).add(r.get(1, String.class));
        }
        Map<UUID, Integer> members = new LinkedHashMap<>();
        for (Record r : dsl.fetch("select role_id, count(*) from public.role_grant where tenant_id = ? "
                + "and valid_from <= current_timestamp and (valid_to is null or valid_to > current_timestamp) "
                + "group by role_id", tenantId)) {
            members.put(r.get(0, UUID.class), r.get(1, Long.class).intValue());
        }
        List<RoleView> roles = new ArrayList<>();
        for (Record r : dsl.fetch("select id, code, name, active from public.\"role\" where tenant_id = ? order by code",
                tenantId)) {
            UUID id = r.get(0, UUID.class);
            if (onlyRole != null && !onlyRole.equals(id)) continue;
            Set<String> held = codes.getOrDefault(id, Set.of());
            roles.add(new RoleView(id.toString(), r.get(1, String.class), r.get(2, String.class),
                    r.get(3, Boolean.class), List.copyOf(held), members.getOrDefault(id, 0), version(held)));
        }
        return roles;
    }

    /** Stable fingerprint of a role's permission set, used for optimistic concurrency. */
    static String version(Set<String> codes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(String.join("\n", new TreeSet<>(codes)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String pgArray(Set<String> values) {
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (String v : values) {
            if (!first) out.append(',');
            out.append('"').append(v.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            first = false;
        }
        return out.append('}').toString();
    }

    private void scope(UUID tenantId) {
        String applied = (String) dsl.fetchValue("select set_config(?, ?, true)", "app.tenant_id", tenantId.toString());
        if (!tenantId.toString().equals(applied)) throw new IllegalStateException("Unable to apply tenant context");
    }
}

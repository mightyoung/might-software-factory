package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.authorization.PostgresAuthorizationWriteGuard;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/** Extra random-ID reference rows only; removes them before the existing identity fixture closes. */
final class G013DefinitionPostgresFixture implements AutoCloseable {
    private final IdentityPostgresFixture identity;
    private final HikariDataSource source;
    private final JdbcTemplate sql;
    private final TransactionTemplate writes;
    private final PostgresAuthorizationWriteGuard guard;
    private final Map<UUID, String> scopedActive = new LinkedHashMap<>();
    private final List<UUID> addedOrganizations = new ArrayList<>();
    private final List<UUID> addedScopeOrganizations = new ArrayList<>();
    private UUID roleId;
    private UUID scopeId;
    private UUID grantId;
    private boolean closed;

    static G013DefinitionPostgresFixture create(PasswordEncoder passwords) {
        IdentityPostgresFixture identity = IdentityPostgresFixture.create(passwords);
        try {
            var fixture = new G013DefinitionPostgresFixture(identity);
            try { fixture.seed(); return fixture; }
            catch (RuntimeException | Error failure) {
                try { fixture.close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        } catch (RuntimeException | Error failure) {
            try { identity.close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private G013DefinitionPostgresFixture(IdentityPostgresFixture identity) {
        this.identity = identity;
        var target = IdentityAcceptanceTarget.preflightFromEnvironment(System.getenv());
        var config = new HikariConfig();
        config.setJdbcUrl(target.jdbcUrl());
        config.setUsername(required("LAN_PG_USERNAME"));
        config.setPassword(required("LAN_PG_PASSWORD"));
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(0);
        config.setPoolName("g013-owned-fixture");
        source = new HikariDataSource(config);
        sql = new JdbcTemplate(source);
        writes = new TransactionTemplate(new DataSourceTransactionManager(source));
        guard = new PostgresAuthorizationWriteGuard(source);
    }

    private void seed() {
        write(db -> {
            var bindings = db.queryForList("""
                    select rg.id grant_id, rg.role_id, rp.scope_rule_id
                      from public.role_grant rg
                      join public.role_permission rp on rp.tenant_id=rg.tenant_id and rp.role_id=rg.role_id
                      join public.permission p on p.id=rp.permission_id
                     where rg.tenant_id=? and rg.member_id=? and p.code='finding.create'
                    """, identity.tenantAId(), identity.memberAId());
            if (bindings.size() != 1) throw new IllegalStateException("Expected one owned create binding");
            roleId = (UUID) bindings.getFirst().get("role_id");
            scopeId = (UUID) bindings.getFirst().get("scope_rule_id");
            grantId = (UUID) bindings.getFirst().get("grant_id");
            addOrganization(db, "Line % literal", true, true);
            addOrganization(db, "Line _ literal", true, true);
            addOrganization(db, "Line \\ literal", true, true);
            addOrganization(db, "设备🧪线", true, true);
            addOrganization(db, "Plain reference line", true, true);
            addOrganization(db, "Out of scope reference", true, false);
            addOrganization(db, "Inactive reference", false, true);
        });
    }

    private void addOrganization(JdbcTemplate db, String name, boolean active, boolean scoped) {
        UUID id = UUID.randomUUID();
        addedOrganizations.add(id);
        requireOne(db.update("insert into public.org_unit(tenant_id,id,code,name,active) values(?,?,?,?,?)",
                identity.tenantAId(), id, "g013-" + id, name, active));
        if (scoped) {
            addedScopeOrganizations.add(id);
            requireOne(db.update("insert into public.scope_org(tenant_id,scope_rule_id,org_id) values(?,?,?)",
                    identity.tenantAId(), scopeId, id));
            if (active) scopedActive.put(id, name);
        }
    }

    IdentityPostgresFixture identity() { return identity; }
    void enableReads() { identity.enableNavigationReads(); }
    void revokeCreate() { identity.revokeCreatePermissionA(); }
    void revokeRead() {
        write(db -> requireOne(db.update("""
                delete from public.role_permission rp using public.permission p
                 where rp.permission_id=p.id and rp.tenant_id=? and rp.role_id=? and p.code='finding.read'
                """, identity.tenantAId(), roleId)));
    }
    List<UUID> expectedOrganizationIds() {
        var result = new ArrayList<>(scopedActive.keySet());
        result.add(identity.organizationAId());
        result.sort(Comparator.comparing(UUID::toString));
        return List.copyOf(result);
    }
    UUID organizationNamed(String name) {
        return scopedActive.entrySet().stream().filter(entry -> entry.getValue().equals(name))
                .map(Map.Entry::getKey).findFirst().orElseThrow();
    }
    void revokeAndDeactivate(UUID orgId) {
        if (!scopedActive.containsKey(orgId)) throw new IllegalArgumentException("Not an owned scoped organization");
        write(db -> {
            requireOne(db.update("update public.org_unit set active=false where tenant_id=? and id=?",
                    identity.tenantAId(), orgId));
            requireOne(db.update("update public.role_grant set valid_to=current_timestamp where tenant_id=? and id=?",
                    identity.tenantAId(), grantId));
        });
    }
    void write(Consumer<JdbcTemplate> work) {
        writes.executeWithoutResult(status -> {
            guard.forGlobalSecurityChange();
            String tenant = sql.queryForObject("select set_config('app.tenant_id',?,true)", String.class,
                    identity.tenantAId().toString());
            if (!identity.tenantAId().toString().equals(tenant)) throw new IllegalStateException("Owned fixture tenant was not applied");
            work.accept(sql);
        });
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        RuntimeException failure = null;
        try {
            try {
                write(db -> {
                    for (UUID id : addedScopeOrganizations) {
                        db.update("delete from public.scope_org where tenant_id=? and scope_rule_id=? and org_id=?",
                                identity.tenantAId(), scopeId, id);
                    }
                    for (UUID id : addedOrganizations) {
                        db.update("delete from public.org_unit where tenant_id=? and id=?", identity.tenantAId(), id);
                        Integer remaining = db.queryForObject("select count(*) from public.org_unit where tenant_id=? and id=?",
                                Integer.class, identity.tenantAId(), id);
                        if (remaining == null || remaining != 0) throw new IllegalStateException("Owned reference cleanup incomplete");
                    }
                });
            } catch (RuntimeException cleanup) { failure = cleanup; }
            try { identity.close(); }
            catch (RuntimeException cleanup) { if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup); }
        } finally { source.close(); }
        if (failure != null) throw failure;
    }

    private static void requireOne(int changed) {
        if (changed != 1) throw new IllegalStateException("Expected one owned reference row");
    }
    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing owned fixture input");
        return value;
    }
}

package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.identity.VerifiedSessionPrincipal;
import com.mightyoung.softwarefactory.identity.SessionLifetimePolicy;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.core.serializer.support.DeserializingConverter;
import org.springframework.core.serializer.support.SerializingConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.time.Instant;

/** Synthetic identity rows only. Schema and temporary runtime grants are provisioned separately. */
public final class IdentityPostgresFixture implements AutoCloseable {

    private static final String CREATE_PERMISSION = "finding.create";
    private static final String SYNTHETIC_PASSWORD = "synthetic-identity-login-password";
    private static final String SYNTHETIC_NEW_PASSWORD = "synthetic-identity-rotated-password";

    private final UUID accountId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final String suffix = UUID.randomUUID().toString();
    private final String loginKey = "identity-acceptance-" + suffix;
    private final TenantRows tenantA = new TenantRows("a", suffix);
    private final TenantRows tenantB = new TenantRows("b", suffix);
    private final UUID tenantCId = UUID.randomUUID();
    private final String tenantCCode = "identity-c-" + suffix;
    private final HikariDataSource pool;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final com.mightyoung.softwarefactory.authorization.AuthorizationWriteGuard securityGuard;
    private final PasswordEncoder passwordEncoder;
    private UUID permissionId = UUID.randomUUID();
    private boolean ownsPermission;
    private UUID readPermissionId = UUID.randomUUID();
    private boolean ownsReadPermission;
    private boolean navigationReadsEnabled;
    private boolean closed;

    public static IdentityPostgresFixture create(PasswordEncoder passwordEncoder) {
        var fixture = new IdentityPostgresFixture(passwordEncoder);
        try {
            fixture.seed();
            return fixture;
        } catch (RuntimeException | Error failure) {
            try {
                fixture.close();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private IdentityPostgresFixture(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder");
        var target = IdentityAcceptanceTarget.preflightFromEnvironment(System.getenv());
        HikariConfig configuration = new HikariConfig();
        configuration.setJdbcUrl(target.jdbcUrl());
        configuration.setUsername(required("LAN_PG_USERNAME"));
        configuration.setPassword(required("LAN_PG_PASSWORD"));
        configuration.setMaximumPoolSize(2);
        configuration.setMinimumIdle(1);
        configuration.setPoolName("identity-fixture-" + suffix);
        pool = new HikariDataSource(configuration);
        jdbc = new JdbcTemplate(pool);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(pool));
        securityGuard = new com.mightyoung.softwarefactory.authorization.PostgresAuthorizationWriteGuard(pool);
    }

    private void securityWrite(java.util.function.Consumer<org.springframework.transaction.TransactionStatus> work) {
        transaction.executeWithoutResult(status -> {
            securityGuard.forGlobalSecurityChange();
            work.accept(status);
        });
    }

    private void seed() {
        int expectedMajor = Integer.parseInt(System.getenv().getOrDefault("LAN_PG_EXPECTED_MAJOR_VERSION", "18"));
        int actualVersion = jdbc.queryForObject("select current_setting('server_version_num')::int", Integer.class);
        if (actualVersion / 10_000 != expectedMajor) {
            throw new IllegalStateException("Identity fixture PostgreSQL major version does not match the test target");
        }
        String encoded = passwordEncoder.encode(SYNTHETIC_PASSWORD);
        securityWrite(status -> {
            jdbc.update("insert into public.account (id, login_key, status, credential_version) values (?, ?, 'ACTIVE', 1)",
                    accountId, loginKey);
            jdbc.update("insert into public.principal (id, kind, account_id, status) values (?, 'HUMAN', ?, 'ACTIVE')",
                    actorId, accountId);
            jdbc.update("""
                    insert into public.credential (account_id, kind, secret_hash, changed_at)
                    values (?, 'PASSWORD', ?, current_timestamp)
                    """, accountId, encoded);
            ownsPermission = jdbc.update("""
                    insert into public.permission (id, code, resource_type, action)
                    values (?, ?, 'finding', 'create') on conflict (code) do nothing
                    """, permissionId, CREATE_PERMISSION) == 1;
            if (!ownsPermission) {
                var existing = jdbc.queryForMap("select id, resource_type, action from public.permission where code = ?",
                        CREATE_PERMISSION);
                if (!"finding".equals(existing.get("resource_type")) || !"create".equals(existing.get("action"))) {
                    throw new IllegalStateException("Existing finding.create permission has an incompatible definition");
                }
                permissionId = (UUID) existing.get("id");
            }
            seedTenant(tenantA);
            seedTenant(tenantB);
            setTenant(tenantCId);
            jdbc.update("insert into public.tenant (id, code, name, status, auth_epoch) values (?, ?, ?, 'ACTIVE', 1)",
                    tenantCId, tenantCCode, "Synthetic unjoined tenant " + suffix);
        });
    }

    private void seedTenant(TenantRows tenant) {
        setTenant(tenant.id);
        jdbc.update("insert into public.tenant (id, code, name, status, auth_epoch) values (?, ?, ?, 'ACTIVE', 1)",
                tenant.id, tenant.code, "Synthetic identity tenant " + tenant.label + " " + suffix);
        jdbc.update("insert into public.tenant_application (tenant_id, application_key, enabled, version) values (?, 'safety', true, 1)", tenant.id);
        jdbc.update("""
                insert into public.tenant_member (tenant_id, id, account_id, status, auth_epoch, display_name)
                values (?, ?, ?, 'ACTIVE', 1, ?)
                """, tenant.id, tenant.memberId, accountId, "Synthetic identity member " + tenant.label);
        jdbc.update("insert into public.org_unit (tenant_id, id, code, name, active) values (?, ?, ?, ?, true)",
                tenant.id, tenant.organizationId, tenant.organizationCode, "Synthetic organization " + tenant.label);
        jdbc.update("""
                insert into public.member_org (tenant_id, member_id, org_id, kind, valid_from)
                values (?, ?, ?, 'PRIMARY', current_timestamp - interval '1 day')
                """, tenant.id, tenant.memberId, tenant.organizationId);
        jdbc.update("insert into public.\"role\" (tenant_id, id, code, name, active) values (?, ?, ?, ?, true)",
                tenant.id, tenant.roleId, "identity-acceptance", "Synthetic identity reporting role");
        jdbc.update("""
                insert into public.scope_rule (tenant_id, id, kind, version_no, definition)
                values (?, ?, 'EXPLICIT', 1, '{}'::jsonb)
                """, tenant.id, tenant.scopeId);
        jdbc.update("insert into public.scope_org (tenant_id, scope_rule_id, org_id) values (?, ?, ?)",
                tenant.id, tenant.scopeId, tenant.organizationId);
        jdbc.update("""
                insert into public.role_permission (tenant_id, role_id, permission_id, scope_rule_id)
                values (?, ?, ?, ?)
                """, tenant.id, tenant.roleId, permissionId, tenant.scopeId);
        jdbc.update("""
                insert into public.role_grant (tenant_id, id, role_id, subject_kind, member_id,
                                              source_type, source_id, granted_by_member_id, valid_from)
                values (?, ?, ?, 'MEMBER', ?, 'P0_IDENTITY_ACCEPTANCE', ?, ?, current_timestamp - interval '1 day')
                """, tenant.id, tenant.grantId, tenant.roleId, tenant.memberId, tenant.sourceId, tenant.memberId);
    }

    /** Canonical reserved catalog rows for D0 metadata rejection probes; grants remain unchanged.
     * These definitions are intentionally retained because V19/V23 prohibit their deletion. */
    void ensureReservedIdentityPermissionsForMetadataProbe() {
        securityWrite(status -> {
            for (String action : List.of("create", "revoke")) {
                String code = "identity.invitation." + action;
                jdbc.update("""
                        insert into public.permission(id,code,resource_type,action)
                        values (?,?,'account-invitation',?) on conflict(code) do nothing
                        """, UUID.randomUUID(), code, action);
                var existing = jdbc.queryForMap(
                        "select resource_type,action from public.permission where code=?", code);
                if (!"account-invitation".equals(existing.get("resource_type"))
                        || !action.equals(existing.get("action"))) {
                    throw new IllegalStateException("Existing reserved identity permission has an incompatible definition");
                }
            }
        });
    }

    /** Opt-in navigation fixture only: do not broaden the existing create-only identity cases. */
    public void enableNavigationReads() {
        if (navigationReadsEnabled) throw new IllegalStateException("Navigation reads already seeded");
        securityWrite(status -> {
            ownsReadPermission = jdbc.update("""
                    insert into public.permission (id, code, resource_type, action)
                    values (?, 'finding.read', 'finding', 'read') on conflict (code) do nothing
                    """, readPermissionId) == 1;
            if (!ownsReadPermission) {
                var existing = jdbc.queryForMap("select id, resource_type, action from public.permission where code = 'finding.read'");
                if (!"finding".equals(existing.get("resource_type")) || !"read".equals(existing.get("action"))) {
                    throw new IllegalStateException("Existing finding.read permission has an incompatible definition");
                }
                readPermissionId = (UUID) existing.get("id");
            }
            for (TenantRows tenant : List.of(tenantA,tenantB)) {
                setTenant(tenant.id);
                requireOne(jdbc.update("""
                        insert into public.role_permission (tenant_id, role_id, permission_id, scope_rule_id)
                        values (?, ?, ?, ?)
                        """, tenant.id,tenant.roleId,readPermissionId,tenant.scopeId));
            }
        });
        navigationReadsEnabled = true;
    }

    public void setSafetyApplicationA(boolean enabled) { setSafetyApplication(tenantA, enabled); }
    public void setSafetyApplicationB(boolean enabled) { setSafetyApplication(tenantB, enabled); }

    private void setSafetyApplication(TenantRows tenant, boolean enabled) {
        securityWrite(status -> {
            setTenant(tenant.id);
            requireOne(jdbc.update("""
                    insert into public.tenant_application (tenant_id, application_key, enabled, version)
                    values (?, 'safety', ?, 1)
                    on conflict (tenant_id, application_key) do update
                    set enabled = excluded.enabled, version = tenant_application.version + 1
                    """, tenant.id, enabled));
        });
    }

    public void removeSafetyApplicationA() {
        securityWrite(status -> {
            setTenant(tenantA.id);
            requireOne(jdbc.update("delete from public.tenant_application where tenant_id = ? and application_key = 'safety'",tenantA.id));
        });
    }

    public void revokeCreatePermissionA() {
        securityWrite(status -> {
            setTenant(tenantA.id);
            requireOne(jdbc.update("""
                    delete from public.role_permission where tenant_id = ? and role_id = ? and permission_id = ?
                    """,tenantA.id,tenantA.roleId,permissionId));
        });
    }

    public void rotatePasswordAndCredentialVersion() {
        String encoded = passwordEncoder.encode(SYNTHETIC_NEW_PASSWORD);
        securityWrite(status -> {
            requireOne(jdbc.update("""
                    update public.credential set secret_hash = ?, changed_at = current_timestamp
                    where account_id = ? and kind = 'PASSWORD'
                    """, encoded, accountId));
            requireOne(jdbc.update("update public.account set credential_version = credential_version + 1 where id = ?",
                    accountId));
        });
    }

    public void lockAccount() {
        setAccountStatus("LOCKED");
    }

    public void disableAccount() {
        setAccountStatus("DISABLED");
    }

    private void setAccountStatus(String status) {
        securityWrite(ignored -> requireOne(jdbc.update(
                "update public.account set status = ? where id = ?", status, accountId)));
    }

    public void disablePrincipal() {
        securityWrite(status -> requireOne(jdbc.update(
                "update public.principal set status = 'DISABLED' where id = ? and account_id = ?",
                actorId, accountId)));
    }

    public void suspendTenantA() {
        securityWrite(status -> {
            setTenant(tenantA.id);
            requireOne(jdbc.update("""
                    update public.tenant set status = 'SUSPENDED', auth_epoch = auth_epoch + 1
                    where id = ?
                    """, tenantA.id));
        });
    }

    public void revokeMembershipA() {
        setMembershipA("DISABLED");
    }

    public void restoreMembershipA() {
        setMembershipA("ACTIVE");
    }

    private void setMembershipA(String status) {
        securityWrite(ignored -> {
            setTenant(tenantA.id);
            requireOne(jdbc.update("""
                    update public.tenant_member set status = ?, auth_epoch = auth_epoch + 1
                    where tenant_id = ? and id = ? and account_id = ?
                    """, status, tenantA.id, tenantA.memberId, accountId));
        });
    }

    public void revokeGrantA() {
        securityWrite(status -> {
            setTenant(tenantA.id);
            requireOne(jdbc.update("update public.role_grant set valid_to = current_timestamp where tenant_id = ? and id = ?",
                    tenantA.id, tenantA.grantId));
        });
    }

    public boolean sessionExists(String sessionId) {
        return jdbc.queryForObject("select count(*) from public.spring_session where session_id = ?",
                Integer.class, Objects.requireNonNull(sessionId, "sessionId")) == 1;
    }

    public long storedCredentialVersion(String sessionId) {
        List<byte[]> attributes = jdbc.query("""
                select a.attribute_bytes from public.spring_session s
                join public.spring_session_attributes a on a.session_primary_id = s.primary_id
                where s.session_id = ? and a.attribute_name = ?
                """, (result, row) -> result.getBytes(1), Objects.requireNonNull(sessionId, "sessionId"),
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        if (attributes.size() != 1) {
            throw new IllegalStateException("Expected one owned JDBC security-context attribute");
        }
        Object stored = new DeserializingConverter().convert(attributes.get(0));
        if (!(stored instanceof SecurityContext context)
                || context.getAuthentication() == null
                || !(context.getAuthentication().getPrincipal() instanceof VerifiedSessionPrincipal principal)
                || !accountId.equals(principal.accountId())) {
            throw new IllegalStateException("Stored session is not bound to this fixture account");
        }
        return principal.credentialVersion();
    }

    public Instant storedAuthenticatedAt(String sessionId) {
        storedCredentialVersion(sessionId); // Refuse inspection of a session outside this fixture account.
        List<byte[]> attributes = jdbc.query("""
                select a.attribute_bytes from public.spring_session s
                join public.spring_session_attributes a on a.session_primary_id = s.primary_id
                where s.session_id = ? and a.attribute_name = ?
                """, (result, row) -> result.getBytes(1), sessionId,
                SessionLifetimePolicy.AUTHENTICATED_AT_ATTRIBUTE);
        if (attributes.size() != 1
                || !(new DeserializingConverter().convert(attributes.getFirst()) instanceof Instant authenticatedAt)) {
            throw new IllegalStateException("Expected one owned JDBC authentication-time attribute");
        }
        return authenticatedAt;
    }

    public String storedPrincipalName(String sessionId) {
        storedCredentialVersion(sessionId);
        return jdbc.queryForObject("select principal_name from public.spring_session where session_id = ?",
                String.class, sessionId);
    }

    public void replaceStoredPrincipalName(String sessionId, String principalName) {
        storedCredentialVersion(sessionId);
        requireOne(jdbc.update("update public.spring_session set principal_name = ? where session_id = ?",
                Objects.requireNonNull(principalName, "principalName"), sessionId));
    }

    public void expireStoredSession(String sessionId) {
        storedCredentialVersion(sessionId);
        requireOne(jdbc.update("""
                update public.spring_session set last_access_time = 0, max_inactive_interval = 0, expiry_time = 0
                where session_id = ?
                """, sessionId));
    }

    public void replaceStoredAuthenticationTime(String sessionId, Object value) {
        storedCredentialVersion(sessionId);
        requireOne(jdbc.update("""
                update public.spring_session_attributes a set attribute_bytes = ?
                from public.spring_session s
                where s.primary_id = a.session_primary_id and s.session_id = ? and a.attribute_name = ?
                """, new SerializingConverter().convert(Objects.requireNonNull(value, "value")), sessionId,
                SessionLifetimePolicy.AUTHENTICATED_AT_ATTRIBUTE));
    }

    public void removeStoredAuthenticationTime(String sessionId) {
        storedCredentialVersion(sessionId);
        requireOne(jdbc.update("""
                delete from public.spring_session_attributes a using public.spring_session s
                where s.primary_id = a.session_primary_id and s.session_id = ? and a.attribute_name = ?
                """, sessionId, SessionLifetimePolicy.AUTHENTICATED_AT_ATTRIBUTE));
    }

    public void cleanupSessions(Collection<String> sessionIds) {
        for (String id : List.copyOf(sessionIds)) {
            jdbc.update("delete from public.spring_session where session_id = ?", Objects.requireNonNull(id, "sessionId"));
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        RuntimeException failure = null;
        try {
            for (TenantRows tenant : List.of(tenantA, tenantB)) {
                try {
                    securityWrite(status -> cleanupTenant(tenant));
                } catch (RuntimeException exception) {
                    failure = combine(failure, exception);
                }
            }
            try {
                securityWrite(status -> {
                    setTenant(tenantCId);
                    jdbc.update("delete from public.tenant where id = ?", tenantCId);
                    requireNoRows("unjoined tenant", "select count(*) from public.tenant where id = ?", tenantCId);
                });
            } catch (RuntimeException exception) {
                failure = combine(failure, exception);
            }
            try {
                securityWrite(status -> {
                    jdbc.update("delete from public.credential where account_id = ? and kind = 'PASSWORD'", accountId);
                    jdbc.update("delete from public.principal where id = ? and account_id = ?", actorId, accountId);
                    jdbc.update("delete from public.account where id = ?", accountId);
                    if (ownsReadPermission) {
                        jdbc.update("delete from public.permission where id = ? and code = 'finding.read'", readPermissionId);
                        requireNoRows("read permission", "select count(*) from public.permission where id = ?", readPermissionId);
                    }
                    if (ownsPermission) {
                        jdbc.update("delete from public.permission where id = ? and code = ?", permissionId, CREATE_PERMISSION);
                        requireNoRows("permission", "select count(*) from public.permission where id = ?", permissionId);
                    }
                    requireNoRows("credential", "select count(*) from public.credential where account_id = ?", accountId);
                    requireNoRows("principal", "select count(*) from public.principal where id = ?", actorId);
                    requireNoRows("account", "select count(*) from public.account where id = ?", accountId);
                });
            } catch (RuntimeException exception) {
                failure = combine(failure, exception);
            }
        } finally {
            pool.close();
        }
        if (failure != null) throw failure;
    }

    private void cleanupTenant(TenantRows tenant) {
        setTenant(tenant.id);
        jdbc.update("delete from public.role_grant where tenant_id = ? and id = ?", tenant.id, tenant.grantId);
        jdbc.update("delete from public.role_permission where tenant_id = ? and role_id = ? and permission_id in (?, ?)",
                tenant.id, tenant.roleId, permissionId, readPermissionId);
        jdbc.update("delete from public.scope_org where tenant_id = ? and scope_rule_id = ? and org_id = ?",
                tenant.id, tenant.scopeId, tenant.organizationId);
        jdbc.update("delete from public.scope_rule where tenant_id = ? and id = ?", tenant.id, tenant.scopeId);
        jdbc.update("delete from public.\"role\" where tenant_id = ? and id = ?", tenant.id, tenant.roleId);
        jdbc.update("delete from public.member_org where tenant_id = ? and member_id = ? and org_id = ?",
                tenant.id, tenant.memberId, tenant.organizationId);
        jdbc.update("delete from public.org_unit where tenant_id = ? and id = ?", tenant.id, tenant.organizationId);
        jdbc.update("delete from public.tenant_member where tenant_id = ? and id = ? and account_id = ?",
                tenant.id, tenant.memberId, accountId);
        jdbc.update("delete from public.tenant_application where tenant_id = ? and application_key = 'safety'", tenant.id);
        requireNoRows("application entitlement", "select count(*) from public.tenant_application where tenant_id = ?", tenant.id);
        jdbc.update("delete from public.tenant where id = ?", tenant.id);
        // Keep the same tenant setting: FORCE RLS must not hide an owned leftover from these checks.
        requireNoRows("role grant", "select count(*) from public.role_grant where tenant_id = ? and id = ?",
                tenant.id, tenant.grantId);
        requireNoRows("role permission", """
                select count(*) from public.role_permission
                where tenant_id = ? and role_id = ? and permission_id in (?, ?)
                """, tenant.id, tenant.roleId, permissionId, readPermissionId);
        requireNoRows("scope organization", """
                select count(*) from public.scope_org
                where tenant_id = ? and scope_rule_id = ? and org_id = ?
                """, tenant.id, tenant.scopeId, tenant.organizationId);
        requireNoRows("scope rule", "select count(*) from public.scope_rule where tenant_id = ? and id = ?",
                tenant.id, tenant.scopeId);
        requireNoRows("role", "select count(*) from public.\"role\" where tenant_id = ? and id = ?",
                tenant.id, tenant.roleId);
        requireNoRows("member organization", """
                select count(*) from public.member_org where tenant_id = ? and member_id = ? and org_id = ?
                """, tenant.id, tenant.memberId, tenant.organizationId);
        requireNoRows("organization", "select count(*) from public.org_unit where tenant_id = ? and id = ?",
                tenant.id, tenant.organizationId);
        requireNoRows("member", "select count(*) from public.tenant_member where tenant_id = ? and id = ?",
                tenant.id, tenant.memberId);
        requireNoRows("tenant", "select count(*) from public.tenant where id = ?", tenant.id);
    }

    private void setTenant(UUID tenantId) {
        String applied = jdbc.queryForObject("select set_config('app.tenant_id', ?, true)", String.class,
                tenantId.toString());
        if (!tenantId.toString().equals(applied)) throw new IllegalStateException("Unable to scope identity fixture transaction");
    }

    private static void requireOne(int changed) {
        if (changed != 1) throw new IllegalStateException("Expected exactly one owned identity row");
    }

    private void requireNoRows(String resource, String sql, Object... arguments) {
        if (jdbc.queryForObject(sql, Integer.class, arguments) != 0) {
            throw new IllegalStateException("Owned identity fixture cleanup incomplete: " + resource);
        }
    }

    private static RuntimeException combine(RuntimeException first, RuntimeException next) {
        if (first == null) return next;
        first.addSuppressed(next);
        return first;
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Required identity fixture input missing: " + key);
        return value;
    }

    public UUID accountId() { return accountId; }
    public UUID actorId() { return actorId; }
    public String loginKey() { return loginKey; }
    public String password() { return SYNTHETIC_PASSWORD; }
    public String newPassword() { return SYNTHETIC_NEW_PASSWORD; }
    public UUID tenantAId() { return tenantA.id; }
    public String tenantACode() { return tenantA.code; }
    public UUID memberAId() { return tenantA.memberId; }
    public UUID organizationAId() { return tenantA.organizationId; }
    public String organizationACode() { return tenantA.organizationCode; }
    public UUID tenantBId() { return tenantB.id; }
    public String tenantBCode() { return tenantB.code; }
    public UUID memberBId() { return tenantB.memberId; }
    public UUID organizationBId() { return tenantB.organizationId; }
    public String organizationBCode() { return tenantB.organizationCode; }
    public UUID tenantCId() { return tenantCId; }
    public String tenantCCode() { return tenantCCode; }

    @Override
    public String toString() { return "IdentityPostgresFixture"; }

    private static final class TenantRows {
        private final String label;
        private final UUID id = UUID.randomUUID();
        private final UUID memberId = UUID.randomUUID();
        private final UUID organizationId = UUID.randomUUID();
        private final UUID roleId = UUID.randomUUID();
        private final UUID scopeId = UUID.randomUUID();
        private final UUID grantId = UUID.randomUUID();
        private final UUID sourceId = UUID.randomUUID();
        private final String code;
        private final String organizationCode;

        private TenantRows(String label, String suffix) {
            this.label = label;
            code = "identity-" + label + "-" + suffix;
            organizationCode = "ORG-" + label + "-" + suffix;
        }
    }
}

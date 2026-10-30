package com.mightyoung.softwarefactory.authorization;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FindingAuthorizationMigrationContractTest {

    @Test
    void authorizationMigrationDefinesTenantScopedReadSourcesWithoutApplyingDatabaseGrants() throws IOException {
        String sql = Files.readString(Path.of(
                        "src/main/resources/db/migration/V3__identity_directory_authorization_read.sql"))
                .toLowerCase(java.util.Locale.ROOT);
        String executableSql = sql.lines()
                .filter(line -> !line.stripLeading().startsWith("--"))
                .collect(java.util.stream.Collectors.joining("\n"));

        assertThat(sql).contains("create table public.account", "create table public.tenant_member",
                        "create table public.org_unit", "create table public.member_org",
                        "create table public.role_grant", "create table public.role_permission",
                        "create table public.scope_rule", "create table public.scope_org",
                        "tenant_member_tenant_account_key", "role_grant_subject_shape",
                        "scope_rule_definition_empty",
                        "auth_epoch bigint", "enable row level security", "force row level security",
                        "current_setting('app.tenant_id', true)");
        assertThat(executableSql).doesNotMatch("(?m)^\\s*grant\\s+")
                .doesNotContain("insert into", "update public.", "delete from");
    }
}

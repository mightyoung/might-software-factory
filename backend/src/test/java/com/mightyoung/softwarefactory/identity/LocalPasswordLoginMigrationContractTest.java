package com.mightyoung.softwarefactory.identity;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LocalPasswordLoginMigrationContractTest {

    @Test
    void migrationAddsCredentialAndPrincipalStorageWithoutSeedingIdentitiesOrChangingGrants() throws IOException {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V4__identity_password_login.sql"))
                .toLowerCase(java.util.Locale.ROOT);
        String executableSql = sql.lines()
                .filter(line -> !line.stripLeading().startsWith("--"))
                .collect(java.util.stream.Collectors.joining("\n"));

        assertThat(sql).contains("account_login_key_normalized_key", "create table public.principal",
                "kind in ('human', 'service', 'agent')",
                "create unique index principal_human_account_key", "create table public.credential",
                "secret_hash text not null", "tenant_login_lookup", "app.login_tenant_code");
        assertThat(executableSql).doesNotMatch("(?m)^\\s*grant\\s+")
                .doesNotContain("insert into", "update public.", "delete from", "create user", "create role");
    }
}

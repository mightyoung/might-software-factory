package com.mightyoung.softwarefactory.dualprevention;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FindingMigrationContractTest {

    @Test
    void productionFindingMigrationDefinesTenantAndOrganizationGuardsWithoutGrantingTheAppRole()
            throws IOException {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V2__dualprevention_finding_read.sql"))
                .toLowerCase(java.util.Locale.ROOT);
        String executableSql = sql.lines()
                .filter(line -> !line.stripLeading().startsWith("--"))
                .collect(java.util.stream.Collectors.joining("\n"));

        assertThat(sql).contains("create table public.finding")
                .contains("organization_id uuid not null")
                .contains("primary key (tenant_id, id)")
                .contains("unique (tenant_id, code)")
                .contains("enable row level security")
                .contains("force row level security")
                .contains("current_setting('app.tenant_id', true)")
                .contains("organization_id, discovered_at desc, id");
        assertThat(executableSql).doesNotContain("grant ", "insert into", "update ", "delete from");
    }
}

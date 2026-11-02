package com.mightyoung.softwarefactory.dualprevention;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FindingMigrationContractTest {

    @Test
    void notEstablishedMigrationAddsTerminalStateDecisionAndTenantSafeRelatedFindingLink() throws IOException {
        Path migrationPath = Path.of("src/main/resources/db/migration/V13__finding_not_established_close_and_relation.sql");
        assertThat(Files.exists(migrationPath)).isTrue();
        assertThat(Files.exists(migrationPath.resolveSibling("V10__finding_not_established_close_and_relation.sql")))
                .as("unapplied V10 draft must not also be discovered").isFalse();
        String sql = Files.readString(migrationPath).toLowerCase(java.util.Locale.ROOT)
                .replaceAll("\\s+", " ");

        assertThat(sql).contains("'not_established'", "decision in ('approve', 'not_established')",
                "related_finding_id", "foreign key (tenant_id, related_finding_id)",
                "finding_related_finding_not_self", "finding_related_finding_immutable",
                "prevent_not_established_finding_reopen", "related finding must be not established",
                "decision <> 'not_established' or (reason is not null and length(btrim(reason, u&'\\0009\\000a\\000b\\000c\\000d\\0020\\0085\\00a0\\1680\\2000\\2001\\2002\\2003\\2004\\2005\\2006\\2007\\2008\\2009\\200a\\2028\\2029\\202f\\205f\\3000')) > 0)");
    }

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

    @Test
    void reportedMetadataMigrationKeepsAllNewFieldsNullableForLegacyRows() throws IOException {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V8__finding_reported_metadata.sql"))
                .toLowerCase(java.util.Locale.ROOT);
        String executableSql = sql.lines()
                .filter(line -> !line.stripLeading().startsWith("--"))
                .collect(java.util.stream.Collectors.joining("\n"));
        String normalizedSql = executableSql.replaceAll("\\s+", " ");

        assertThat(executableSql).contains(
                "alter table public.finding",
                "add column source_category varchar(32)",
                "add column urgency varchar(16)",
                "add column location text",
                "add column interim_measure text",
                "add column backfill boolean",
                "constraint finding_source_category_valid",
                "constraint finding_urgency_valid",
                "constraint finding_location_length",
                "constraint finding_interim_measure_length",
                "constraint finding_report_metadata_legacy_or_complete");
        assertThat(executableSql).doesNotContain(" update public.finding", "grant ", "create policy");
        String unicodeWhiteSpaceCodePoints = java.util.stream.IntStream.of(
                        9, 10, 11, 12, 13, 32, 133, 160, 5760,
                        8192, 8193, 8194, 8195, 8196, 8197, 8198, 8199, 8200, 8201, 8202,
                        8232, 8233, 8239, 8287, 12288)
                .mapToObj(codePoint -> "chr(" + codePoint + ")")
                .collect(java.util.stream.Collectors.joining(" || "));
        assertThat(normalizedSql).contains("translate(location, " + unicodeWhiteSpaceCodePoints + ", '') <> ''")
                .doesNotContain("[:space:]", "location ~");
        assertThat(executableSql).doesNotContain(
                "add column source_category varchar(32) not null",
                "add column urgency varchar(16) not null",
                "add column location text not null",
                "add column interim_measure text not null",
                "add column backfill boolean not null");
    }
}

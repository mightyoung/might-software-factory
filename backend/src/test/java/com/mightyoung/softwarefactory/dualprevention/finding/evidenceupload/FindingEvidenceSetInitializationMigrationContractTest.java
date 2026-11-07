package com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

class FindingEvidenceSetInitializationMigrationContractTest {
    @Test void independentImmutableReceiptBindsEmptyInitialSetAndServerOwnerWithoutGrantingAuthority() throws Exception {
        String sql=Files.readString(Path.of("src/main/resources/db/migration/V16__finding_evidence_set_initialization_receipt.sql")).toLowerCase(java.util.Locale.ROOT);
        String executable=sql.lines().filter(line -> !line.stripLeading().startsWith("--")).collect(java.util.stream.Collectors.joining("\n"));
        assertThat(sql).contains("primary key (tenant_id, id)","unique (tenant_id, owner_actor_id, operation, idempotency_key)",
                "unique (tenant_id, evidence_set_id)","foreign key (tenant_id, notice_id, finding_id, round_no)",
                "foreign key (tenant_id, evidence_set_id, finding_id, round_no, owner_member_id)",
                "m.account_id = new.owner_account_id","p.kind = 'human' and p.account_id = new.owner_account_id",
                "n.assignee_id = m.id","s.owner_member_id = m.id","s.state = 'uploading' and s.evidence_revision = 0",
                "s.verified_at is null and s.submission_sealed_at is null","s.created_at = new.created_at",
                "and not exists (select 1 from public.evidence_item item","initial_evidence_revision = 0",
                "before update or delete","enable row level security","force row level security",
                "with check (tenant_id = nullif(current_setting('app.tenant_id', true)");
        assertThat(executable).doesNotContain("security definer","cascade","finding_command_receipt","event_publication",
                "insert into","update public.finding","create role","alter role");
        assertThat(executable).doesNotMatch("(?is).*\\bgrant\\s+.*");
    }
}

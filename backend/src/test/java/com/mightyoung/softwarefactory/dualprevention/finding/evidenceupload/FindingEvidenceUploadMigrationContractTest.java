package com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

class FindingEvidenceUploadMigrationContractTest {
    @Test void immutableReservationHasTenantIsolationExactIdentityBindingsAndNoProvisioning() throws Exception {
        String sql=Files.readString(Path.of("src/main/resources/db/migration/V15__finding_evidence_upload_intent.sql")).toLowerCase(java.util.Locale.ROOT);
        String executable=sql.lines().filter(line -> !line.stripLeading().startsWith("--")).collect(java.util.stream.Collectors.joining("\n"));
        assertThat(sql).contains("primary key (tenant_id, id)","unique (tenant_id, owner_actor_id, operation, idempotency_key)",
                "foreign key (tenant_id, notice_id, finding_id, round_no)","foreign key (tenant_id, evidence_set_id, finding_id, round_no, owner_member_id)",
                "m.account_id = new.owner_account_id","p.kind = 'human' and p.account_id = new.owner_account_id",
                "n.assignee_id = m.id","s.owner_member_id = m.id","before update or delete",
                "enable row level security","force row level security","with check (tenant_id = nullif(current_setting('app.tenant_id', true)",
                "state = 'intent_created'","expires_at > created_at");
        assertThat(executable).doesNotContain("security definer","cascade","event_publication","finding_command_receipt",
                "'ready'","'clean'","'receiving'","'scanning'","create role","alter role");
        assertThat(executable).doesNotMatch("(?is).*\\bgrant\\s+.*");
    }
}

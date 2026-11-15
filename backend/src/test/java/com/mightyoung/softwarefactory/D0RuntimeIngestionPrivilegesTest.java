package com.mightyoung.softwarefactory;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Keep worker state persistence usable without granting declaration or whole-table mutation. */
class D0RuntimeIngestionPrivilegesTest {
    @Test void technicalIntentUpdatesMatchTheActualWorkerSql() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/mightyoung/softwarefactory/dualprevention/finding/evidenceupload/JooqFindingEvidenceIngestionRepository.java"));
        var writes = Pattern.compile("update public\\.finding_evidence_upload_intent set (.*?)\\s+where\\b", Pattern.DOTALL).matcher(source);
        Set<String> required = new HashSet<>();
        int statements = 0;
        while (writes.find()) {
            statements++;
            var assignments = Pattern.compile("(?:^|,)\\s*([a-z0-9_]+)\\s*=").matcher(writes.group(1));
            while (assignments.find()) required.add(assignments.group(1));
        }
        assertThat(statements).isEqualTo(1);
        assertThat(required).contains("scanner_generation_sha256", "lease_epoch", "reserved_scan_attempt_id", "current_execution_id");

        String script = Files.readString(Path.of("src/main/resources/db/operator/d0-legacy-runtime-grants.sql"));
        var row = Pattern.compile("\\('finding_evidence_upload_intent',true,true,ARRAY\\[(.*?)\\]\\)", Pattern.DOTALL).matcher(script);
        assertThat(row.find()).isTrue();
        Set<String> granted = new HashSet<>();
        var names = Pattern.compile("'([a-z0-9_]+)'").matcher(row.group(1));
        while (names.find()) granted.add(names.group(1));
        assertThat(granted).containsExactlyInAnyOrderElementsOf(required);
        assertThat(granted).doesNotContain("tenant_id", "owner_member_id", "finding_id", "evidence_set_id", "file_id", "declared_sha256");
    }
}

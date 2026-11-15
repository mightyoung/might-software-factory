package com.mightyoung.softwarefactory;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class D0RuntimeFileLockPrivilegesTest {
    @Test void fileMetadataShareLocksHaveOnlyTheImmutableTenantColumnPrivilege() throws Exception {
        String script = Files.readString(Path.of("src/main/resources/db/operator/d0-legacy-runtime-grants.sql"));
        assertThat(script).contains("update d0_legacy_runtime set update_columns=ARRAY['tenant_id']\n where table_name in ('file_object','file_link');",
                "t.tgenabled in ('O','A')", "D0 metadata locking requires enabled immutable identity guard");

        String migration = Files.readString(Path.of("src/main/resources/db/migration/V6__file_evidence_reference_metadata.sql"));
        for (var guard : Map.of("file_link", "reject_file_link_rebinding", "file_object", "reject_file_object_submission_version_rewrite").entrySet()) {
            int start = migration.indexOf("create function public." + guard.getValue() + "()");
            assertThat(start).isNotNegative();
            String body = migration.substring(start, migration.indexOf("$$;", start));
            assertThat(body).contains("NEW.tenant_id is distinct from OLD.tenant_id", "raise exception");
            assertThat(migration).contains("before update on public." + guard.getKey(), "execute function public." + guard.getValue() + "();");
        }
        for (var source : Map.of("JooqFindingEvidenceContentReadRepository", "for share of fl, fo",
                "JooqEvidenceFileReferenceReadRepository", "for share of fl", "JooqFileEvidenceMetadataReadRepository", "for share").entrySet()) {
            String java = Files.readString(Path.of("src/main/java/com/mightyoung/softwarefactory/files/" + source.getKey() + ".java"));
            assertThat(java).contains(source.getValue());
        }
    }
}

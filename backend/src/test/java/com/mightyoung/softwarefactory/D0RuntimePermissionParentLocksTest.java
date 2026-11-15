package com.mightyoung.softwarefactory;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class D0RuntimePermissionParentLocksTest {
    @Test void rolePermissionWriterUsesV23InvokerParentLocksWithOnlyReviewedMetadataUpdates() throws Exception {
        String trigger = Files.readString(Path.of("src/main/resources/db/migration/V23__tenant_audit_query_capability.sql"));
        int start = trigger.indexOf("create function public.guard_audit_query_role_permission()");
        assertThat(start).isNotNegative();
        String body = trigger.substring(start, trigger.indexOf("end $$;", start));
        assertThat(body).contains("from public.permission p where p.id=new.permission_id for key share",
                "from public.scope_rule s where s.tenant_id=new.tenant_id and s.id=new.scope_rule_id for key share");
        assertThat(body.toLowerCase()).doesNotContain("security definer");
        String producer = Files.readString(Path.of("src/main/java/com/mightyoung/softwarefactory/platformadmin/JooqPermissionAdminRepository.java"));
        assertThat(producer).contains("insert into public.role_permission");

        String script = Files.readString(Path.of("src/main/resources/db/operator/d0-runtime-grants.sql"));
        var declarations = Pattern.compile("grant update\\(([^)]+)\\) on public\\.(permission|scope_rule) to :\\\"runtime_role\\\";").matcher(script);
        Map<String, String> delta = new HashMap<>();
        while (declarations.find()) assertThat(delta.put(declarations.group(2), declarations.group(1))).isNull();
        assertThat(delta).containsExactlyInAnyOrderEntriesOf(Map.of("permission", "label", "scope_rule", "version_no"));
        assertThat(script).contains("('permission','label'),('scope_rule','version_no')",
                "has_table_privilege(role_name,rel,'UPDATE')", "col.attname=spec.lock_column",
                "D0 parent locking UPDATE mismatch");
        assertThat(script.toLowerCase()).doesNotContain("grant update on public.permission", "grant update on public.scope_rule");

        String schema = Files.readString(Path.of("src/main/resources/db/migration/V3__identity_directory_authorization_read.sql"));
        assertThat(schema).contains("version_no integer not null check (version_no >= 1)");
        try (var files = Files.list(Path.of("src/main/java/com/mightyoung/softwarefactory/authorization"))) {
            for (Path path : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                assertThat(Files.readString(path)).as("Current authorization must not use the metadata generation: %s", path)
                        .doesNotContain("version_no");
            }
        }
    }
}

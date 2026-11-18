package com.mightyoung.softwarefactory;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Compares the operator manifest with actual producer SQL and migration function declarations. */
class D0RuntimePrivilegesTest {
    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");
    private static final Path SCRIPT = Path.of("src/main/resources/db/operator/d0-runtime-grants.sql");
    private record Rights(Set<String> select, Set<String> insert, Set<String> update) { }

    @Test void manifestCoversNewTablesAndCurrentProducerColumns() throws Exception {
        var manifest = manifest();
        Set<String> tables = new HashSet<>();
        try (var paths = Files.list(MIGRATIONS)) {
            for (Path path : paths.filter(p -> p.getFileName().toString().matches("V(19|2[0-5])__.*")).toList()) {
                var names = Pattern.compile("create table public\\.(\\w+)").matcher(Files.readString(path));
                while (names.find()) tables.add(names.group(1));
            }
        }
        assertThat(manifest.keySet()).hasSize(42).containsExactlyInAnyOrderElementsOf(tables);
        try (var paths = Files.walk(Path.of("src/main/java"))) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(path);
                var inserts = Pattern.compile("insert into public\\.(\\w+)\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE).matcher(source);
                while (inserts.find()) {
                    var rights = manifest.get(inserts.group(1));
                    if (rights != null) assertThat(rights.insert).as("INSERT in %s", path).containsAll(words(inserts.group(2)));
                }
                var updates = Pattern.compile("update public\\.(\\w+)\\s+(?:\\w+\\s+)?set\\s+(.+?)\\s+where\\b", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(source);
                while (updates.find()) {
                    var rights = manifest.get(updates.group(1));
                    if (rights == null) continue;
                    var columns = Pattern.compile("(?:^|,)\\s*(\\w+)\\s*=").matcher(updates.group(2));
                    while (columns.find()) assertThat(rights.update).as("UPDATE in %s", path).contains(columns.group(1));
                }
            }
        }
    }

    @Test void historyIsAppendOnlyAndInvokerFencesRemainUsable() throws Exception {
        var manifest = manifest();
        for (var entry : manifest.entrySet()) {
            String name = entry.getKey();
            if (name.endsWith("_revision") || name.endsWith("_receipt") || name.endsWith("_event")
                    || name.equals("risk_scenario_revision_hazard") || name.equals("risk_change_cause")) {
                assertThat(entry.getValue().update).as(name).isEmpty();
            }
        }
        for (String name : Set.of("workshop", "production_line", "process_stage", "equipment", "work_unit")) {
            assertThat(manifest.get(name).update).as(name).contains("interval_mutex_revision");
        }
        assertThat(manifest.get("account_recovery_contact").update).containsExactly("version");
        for (String name : Set.of("session_authority_activation", "recovery_contact_change",
                "recovery_contact_command_receipt", "recovery_contact_delivery_attempt", "sensitive_reauth_issuance_receipt")) {
            assertThat(manifest.get(name).insert).as(name).isEmpty();
            assertThat(manifest.get(name).update).as(name).isEmpty();
        }
    }

    @Test void executableHelpersMatchOrdinaryRevokedFunctionsAndExcludeTriggerBodies() throws Exception {
        String script = Files.readString(SCRIPT);
        Set<String> actual = new HashSet<>(), expected = new HashSet<>();
        var entries = Pattern.compile("\\('(public\\.g014[^']*)'\\)").matcher(script);
        while (entries.find()) actual.add(entries.group(1));
        try (var paths = Files.list(MIGRATIONS)) {
            for (Path path : paths.filter(p -> p.getFileName().toString().matches("V2[12]__.*")).toList()) {
                String ddl = Files.readString(path);
                var revoked = Pattern.compile("revoke execute on function (public\\.[^;]+?) from public;").matcher(ddl);
                while (revoked.find()) {
                    String signature = revoked.group(1), name = signature.substring(7, signature.indexOf('('));
                    var header = Pattern.compile("create(?: or replace)? function public\\." + name + "\\([\\s\\S]*?\\$\\$").matcher(ddl);
                    assertThat(header.find()).as(signature).isTrue();
                    if (!Pattern.compile("returns\\s+trigger\\b").matcher(header.group()).find()) expected.add(signature);
                }
            }
        }
        assertThat(actual).hasSize(32).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(script).contains("current_database() <> current_setting('d0.target_database')", "runtime.rolbypassrls", "pg_auth_members", "has_column_privilege");
        assertThat(script.toLowerCase()).doesNotContain("grant all", "all functions", "alter role", "disable trigger");
    }

    private static Map<String, Rights> manifest() throws Exception {
        Map<String, Rights> result = new HashMap<>();
        var rows = Pattern.compile("\\('([a-z_]+)',\\d+,ARRAY\\[(.*?)\\]::text\\[\\],ARRAY\\[(.*?)\\]::text\\[\\],ARRAY\\[(.*?)\\]::text\\[\\]\\)").matcher(Files.readString(SCRIPT));
        while (rows.find()) {
            var rights = new Rights(words(rows.group(2)), words(rows.group(3)), words(rows.group(4)));
            assertThat(result.put(rows.group(1), rights)).isNull();
            assertThat(rights.select).containsAll(rights.insert).containsAll(rights.update);
        }
        return result;
    }
    private static Set<String> words(String input) {
        Set<String> result = new HashSet<>();
        var values = Pattern.compile("[a-z][a-z_0-9]*").matcher(input);
        while (values.find()) result.add(values.group());
        return result;
    }
}

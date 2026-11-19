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
    private static final Path REAUTH_WRITER = Path.of("src/main/java/com/mightyoung/softwarefactory/identity/JooqReauthIssuerStore.java");
    private static final Path REAUTH_GRANTS = Path.of("src/ci-pg18-reauth-test/resources/ci-pg18-reauth/runtime-grants.sql");
    private static final Set<String> REAUTH_ISSUANCE_TABLES = Set.of("sensitive_reauth_proof", "sensitive_reauth_issuance_receipt");
    private record Rights(Set<String> select, Set<String> insert, Set<String> update) { }

    @Test void manifestCoversNewTablesAndCurrentProducerColumns() throws Exception {
        var manifest = manifest();
        var isolatedReauthInserts = isolatedReauthRights("insert");
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
                    if (rights != null) {
                        Set<String> allowed = path.equals(REAUTH_WRITER) && REAUTH_ISSUANCE_TABLES.contains(inserts.group(1))
                                ? isolatedReauthInserts.get(inserts.group(1)) : rights.insert;
                        assertThat(allowed).as("INSERT in %s", path).containsAll(words(inserts.group(2)));
                    }
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

    @Test void isolatedReauthWriterRequiresItsExplicitGrantAndD0CannotIssueProofs() throws Exception {
        var baseline = manifest();
        for (String table : REAUTH_ISSUANCE_TABLES) {
            assertThat(baseline.get(table).insert).as(table).isEmpty();
        }
        var inserts = isolatedReauthRights("insert");
        assertThat(inserts.get("sensitive_reauth_proof")).containsExactlyInAnyOrder(
                "id", "account_id", "actor_id", "source_primary_id", "issued_credential_version", "action",
                "target_primary_id", "expected_authority_version", "body_key_id", "body_digest", "secret_digest",
                "state", "version", "created_at", "expires_at", "issuance_receipt_id");
        assertThat(inserts.get("sensitive_reauth_issuance_receipt")).containsExactlyInAnyOrder(
                "id", "account_id", "actor_id", "source_primary_id", "command_key", "fingerprint_key_id",
                "fingerprint", "proof_id", "expires_at", "event_id", "completed_at");
        var updates = isolatedReauthRights("update");
        assertThat(updates.get("sensitive_reauth_proof")).containsExactlyInAnyOrder(
                "state", "version", "session_consume_receipt_id");
        assertThat(updates).doesNotContainKey("sensitive_reauth_issuance_receipt");
        String writer = Files.readString(REAUTH_WRITER);
        var statements = Pattern.compile("update public\\.(\\w+)\\s+set\\s+(.+?)\\s+where\\b",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(writer);
        int count = 0;
        while (statements.find()) {
            assertThat(statements.group(1)).isEqualTo("sensitive_reauth_proof");
            var columns = Pattern.compile("(?:^|,)\\s*(\\w+)\\s*=").matcher(statements.group(2));
            while (columns.find()) assertThat(updates.get(statements.group(1))).contains(columns.group(1));
            count++;
        }
        assertThat(count).isEqualTo(1);
    }

    private static Map<String, Set<String>> isolatedReauthRights(String operation) throws Exception {
        Map<String, Set<String>> result = new HashMap<>();
        var grants = Pattern.compile("grant " + operation
                + "\\(([a-z0-9_,]+)\\) on public\\.\"([a-z_]+)\" to msf_ci_reauth_runtime;")
                .matcher(Files.readString(REAUTH_GRANTS));
        while (grants.find()) assertThat(result.put(grants.group(2), words(grants.group(1)))).isNull();
        return result;
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

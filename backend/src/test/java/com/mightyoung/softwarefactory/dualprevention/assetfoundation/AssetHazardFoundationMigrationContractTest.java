package com.mightyoung.softwarefactory.dualprevention.assetfoundation;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Finite source-contract guard only; this does not execute or validate PostgreSQL behavior. */
class AssetHazardFoundationMigrationContractTest {
    private static final List<String> ROOTS = List.of(
            "workshop", "production_line", "process_stage", "equipment", "work_unit", "hazard_source");
    private static final List<String> LINKS = List.of(
            "line_workshop", "stage_equipment", "responsibility_binding");
    private static final List<String> ENTITIES = entities();

    @Test
    void retainsExactlyNineteenTenantTablesWithoutAddingRuntimeAuthority() throws IOException {
        String sql = source();
        var tables = new ArrayList<>(ENTITIES);
        ENTITIES.forEach(name -> tables.add(name + "_revision"));
        tables.add("asset_foundation_receipt");
        assertThat(captures(sql, "create table public\\.(\\w+) \\("))
                .containsExactlyInAnyOrderElementsOf(tables);
        for (String table : tables) {
            assertThat(sql).contains("alter table public." + table + " enable row level security;",
                    "alter table public." + table + " force row level security;",
                    "create policy g014_tenant_isolation on public." + table);
        }
        String statements = sql.replaceAll("(?m)--[^\\r\\n]*", "").toLowerCase();
        assertThat(statements).doesNotContain("security definer", "create extension", "create role",
                "grant ", "on delete cascade", "on delete set null", "alter role", "set_config(",
                "on conflict do nothing", "delete from", "set constraints all");
        assertThat(captures(sql, "revoke execute on function public\\.(\\w+)\\("))
                .containsExactlyInAnyOrderElementsOf(captures(sql, "create function public\\.(\\w+)\\("));
        assertThat(statements).doesNotContain("from public.audit_event", "join public.audit_event",
                "update public.permission", "insert into public.role_permission");
    }

    @Test
    void finiteDeferredListMustBeForcedBeforeTheFinalCut() throws IOException {
        String sql = source();
        var expected = new ArrayList<String>();
        for (String entity : ENTITIES) {
            expected.add("g014_" + entity + "_last_command_fk");
            expected.add("g014_" + entity + "_revision_receipt_fk");
            expected.add("g014_receipt_" + entity + "_result_fk");
            expected.add("g014_" + entity + "_history_complete");
        }
        expected.addAll(List.of("g014_line_workshop_line_label_revision_fk",
                "g014_line_workshop_workshop_label_revision_fk",
                "g014_stage_equipment_stage_label_revision_fk",
                "g014_stage_equipment_equipment_label_revision_fk"));
        var actual = captures(sql,
                "add constraint (g014_\\w+) [^;]*?deferrable initially deferred");
        actual.addAll(captures(sql,
                "create constraint trigger (g014_\\w+) [^;]*?deferrable initially deferred"));
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(captures(sql, "(deferrable initially deferred)")).hasSize(40);
    }

    @Test
    void preservesTypedWorkUnitsIndependentEquipmentAndFiniteMutationMapping() throws IOException {
        String sql = source();
        String workUnit = table(sql, "work_unit");
        assertThat(workUnit).contains("code varchar(64) collate \"C\" not null");
        assertThat(workUnit).contains("kind='STANDALONE' and num_nonnulls(workshop_id,line_id,stage_id,equipment_id)=0");
        for (String kind : List.of("WORKSHOP", "LINE", "STAGE", "EQUIPMENT")) {
            assertThat(workUnit).contains("kind='" + kind + "'");
        }
        assertThat(workUnit).doesNotContain("unique (tenant_id,workshop_id)", "unique (tenant_id,line_id)",
                "unique (tenant_id,stage_id)", "unique (tenant_id,equipment_id)");
        assertThat(table(sql, "equipment")).doesNotContain("stage_id", "line_id", "workshop_id");
        assertThat(table(sql, "hazard_source")).contains("factor_code_ref is null and factor_version is null")
                .doesNotContain("lifecycle");
        String receipt = table(sql, "asset_foundation_receipt");
        assertThat(captures(receipt, "action_code='([^']+)'"))
                .containsExactlyInAnyOrderElementsOf(mutations());
        assertThat(receipt).contains("fingerprint_version=1", "num_nonnulls(result_workshop_id",
                "unique (tenant_id,actor_id,action_code,idempotency_key)")
                .doesNotContain(".read", "PENDING");
        for (String action : mutations()) {
            String resource = action.substring(0, action.indexOf('.'));
            String change = action.substring(action.indexOf('.') + 1).toUpperCase(java.util.Locale.ROOT);
            assertThat(receipt).contains("action_code='" + action + "' and result_resource_type='" + resource
                    + "' and result_" + resource + "_id is not null and change_kind='" + change + "'");
        }
        var permissions = new ArrayList<>(mutations());
        ROOTS.forEach(root -> permissions.add(root + ".read"));
        assertThat(captures(sql, "\\('[0-9a-f-]{36}','([^']+)','[^']+','[^']+'\\)"))
                .containsExactlyInAnyOrderElementsOf(permissions);
        assertThat(sql).contains("actual.resource_type is distinct from expected.resource_type",
                "actual.action is distinct from expected.action", "existing permission triple mismatch");
    }

    @Test
    void actualParentWritesPrecedeIntervalChecksAndCannotBeFlagBypassed() throws IOException {
        String sql = source();
        String fence = function(sql, "g014_fence_parent");
        assertThat(fence).contains("tenant is distinct from nullif(current_setting('app.tenant_id',true),'')::uuid",
                "get diagnostics affected = row_count", "affected <> 1");
        for (String parent : ROOTS.subList(0, 5)) {
            assertThat(fence).contains("update public." + parent
                    + " set interval_mutex_revision=interval_mutex_revision+1");
            assertThat(sql).contains("create trigger g014_" + parent + "_archive_check after update on public." + parent);
            assertThat(function(sql, "g014_" + parent + "_archive_check"))
                    .contains("archive has active or unexpired dependents");
            String trigger = function(sql, "g014_" + parent + "_before_write");
            assertThat(trigger).contains("(to_jsonb(new)-'interval_mutex_revision') = (to_jsonb(old)-'interval_mutex_revision')",
                    "new.interval_mutex_revision is distinct from old.interval_mutex_revision+1",
                    "new.interval_mutex_revision := old.interval_mutex_revision+1");
        }
        for (String link : LINKS) {
            String trigger = function(sql, "g014_" + link + "_before_write");
            assertThat(trigger).contains("select distinct p.kind,p.id", "order by p.kind,p.id",
                    "tg_op='INSERT'", "relationship scope anchor mismatch", "END must strictly shorten",
                    "x.valid_from < coalesce(new.valid_to,'infinity'::timestamptz)",
                    "new.valid_from < coalesce(x.valid_to,'infinity'::timestamptz)");
            assertThat(trigger.indexOf("public.g014_fence_parent"))
                    .isLessThan(trigger.indexOf("immutable identity anchor parent type or endpoint"));
            assertThat(trigger.indexOf("public.g014_fence_parent"))
                    .isLessThan(trigger.indexOf("overlapping effective relationship"));
        }
        assertThat(function(sql, "g014_responsibility_binding_before_write"))
                .contains("x.position_id is not distinct from new.position_id");
        assertThat(function(sql, "g014_lock_natural_key")).contains("sha256(tuple_bytes)",
                "pg_advisory_xact_lock(1297303118, key_bits::integer)",
                "public.g014_natural_key_bytes(table_name,tenant_id,code,line_id)");
        assertThat(function(sql, "g014_natural_key_bytes"))
                .contains("(table_name='process_stage') is distinct from (line_id is not null)");
    }

    @Test
    void canonicalLockCodecsUseFiniteTypedNetworkBytesBeforeHashing() throws IOException {
        String sql = source();
        String natural = function(sql, "g014_natural_key_bytes");
        assertThat(natural).contains("int4send(1)", "uuid_send(tenant_id)", "uuid_send(line_id)",
                "convert_to(code,'UTF8')");
        String receipt = function(sql, "g014_receipt_key_bytes");
        assertThat(captures(receipt, "'([a-z_]+\\.(?:create|update|archive|bind|end))'"))
                .containsExactlyInAnyOrderElementsOf(mutations());
        assertThat(receipt).contains("uuid_send(tenant_id)", "uuid_send(actor_id)",
                "convert_to(action_code,'UTF8')", "uuid_send(idempotency_key)");
        String plan = function(sql, "g014_mutation_key_plan");
        for (String entity : ENTITIES) assertThat(plan).contains("'" + entity + "'");
        assertThat(plan).contains("new_version not between 1 and 9007199254740991",
                "uuid_send(entity_id)", "int8send(new_version)", "uuid_send(command_id)",
                "select 20,", "select 30,", "select 40,");
        for (String codec : List.of(natural, receipt, plan)) {
            assertThat(codec).doesNotContain("sha256(", "pg_advisory", "::text", "execute ");
        }
        for (String helper : List.of("g014_lock_mutation_keys", "g014_require_mutation_keys")) {
            String body = function(sql, helper);
            assertThat(body.indexOf("order by family,canonical_bytes"))
                    .isLessThan(body.indexOf("sha256(tuple_bytes)"));
        }
    }

    @Test
    void triggerLockChecksNeverAcquireAnAbsentFreeKeyOrWaitAfterTheCut() throws IOException {
        String sql = source();
        for (String domain : List.of("natural", "receipt", "mutation")) {
            String suffix = domain.equals("mutation") ? "keys" : "key";
            String acquire = function(sql, "g014_lock_" + domain + "_" + suffix);
            String require = function(sql, "g014_require_" + domain + "_" + suffix);
            for (String body : List.of(acquire, require)) {
                assertThat(body).contains("current_setting('transaction_isolation') is distinct from 'read committed'",
                        "current_setting('transaction_read_only') is distinct from 'off'",
                        "tenant_id is distinct from nullif(current_setting('app.tenant_id',true),'')::uuid");
            }
            assertThat(require).contains("from pg_locks", "pid=pg_backend_pid()", "locktype='advisory'",
                    "mode='ExclusiveLock'", "and granted", "objsubid=2",
                    "database=(select oid from pg_database where datname=current_database())",
                    "key_bits & 4294967295", "raise exception 'required command key is not held'",
                    "pg_try_advisory_xact_lock(");
            assertThat(require.indexOf("raise exception 'required command key is not held'"))
                    .isLessThan(require.indexOf("pg_try_advisory_xact_lock("));
            assertThat(require).doesNotContain("perform pg_advisory_xact_lock(", "pg_advisory_lock(",
                    "pg_advisory_unlock", "exception when", "execute ");
        }
    }

    @Test
    void allNineteenWritePathsRequireTheSamePlannedKeysBeforeTheirReadsAndFences() throws IOException {
        String sql = source();
        for (String entity : ENTITIES) {
            String write = function(sql, "g014_" + entity + "_before_write");
            String required = "public.g014_require_mutation_keys('" + entity
                    + "',new.tenant_id,new.id,new.version,new.last_command_id)";
            assertThat(write).contains(required);
            if (ROOTS.contains(entity)) {
                assertThat(write.indexOf("return new;")) // Pure technical fence branch remains exempt.
                        .isLessThan(write.indexOf(required));
                assertThat(write).contains("public.g014_require_natural_key('" + entity + "'")
                        .doesNotContain("public.g014_lock_natural_key(");
            } else {
                assertThat(write.indexOf(required)).isLessThan(write.indexOf("public.g014_fence_parent("));
            }
            String revision = function(sql, "g014_" + entity + "_revision_insert");
            assertThat(revision).contains("public.g014_require_mutation_keys('" + entity
                    + "',new.tenant_id,new.entity_id,new.version,new.command_id)");
            assertThat(revision.indexOf("public.g014_require_mutation_keys("))
                    .isLessThan(revision.indexOf("select * into b"));
        }
        String receipt = function(sql, "g014_receipt_insert");
        assertThat(receipt).contains("public.g014_require_receipt_key(new.tenant_id,new.actor_id,new.action_code,new.idempotency_key)",
                "num_nonnulls(new.result_workshop_id", "result_id is null", "invalid receipt target shape");
        assertThat(receipt.indexOf("invalid receipt target shape"))
                .isLessThan(receipt.indexOf("public.g014_require_mutation_keys("));
        assertThat(receipt.indexOf("public.g014_require_mutation_keys("))
                .isLessThan(receipt.indexOf("select exists"));
    }

    @Test
    void historyAndReceiptsRequireExactTypedMutationBeforeCompletion() throws IOException {
        String sql = source();
        for (String entity : ENTITIES) {
            String revision = function(sql, "g014_" + entity + "_revision_insert");
            assertThat(revision).contains("new.version is distinct from b.version",
                    "new.command_id is distinct from b.last_command_id",
                    "new.actor_id is distinct from b.updated_by", "new.occurred_at is distinct from b.updated_at",
                    "row(new.snapshot_organization_id", "is distinct from row(b.organization_id");
            assertThat(function(sql, "g014_" + entity + "_history_complete"))
                    .contains("r.command_id=new.last_command_id", "c.result_version=new.version",
                            "c.actor_id=r.actor_id", "c.result_occurred_at=r.occurred_at", "c.change_kind=r.change_kind");
            assertThat(sql).contains("before update or delete on public." + entity + "_revision",
                    "before delete on public." + entity);
        }
        assertThat(function(sql, "g014_receipt_insert")).contains("r.command_id=new.command_id",
                "r.actor_id=new.actor_id", "r.occurred_at=new.result_occurred_at", "r.change_kind=new.change_kind",
                "matches is distinct from true");
        assertThat(sql).contains("before update or delete on public.asset_foundation_receipt");
    }

    @Test
    void bindLabelsCaptureBusinessVersionsAfterAllFencesAndRemainExactOnEnd() throws IOException {
        String sql = source();
        for (String link : List.of("line_workshop", "stage_equipment")) {
            var endpoints = link.equals("line_workshop")
                    ? List.of("line", "workshop") : List.of("stage", "equipment");
            String trigger = function(sql, "g014_" + link + "_before_write");
            assertThat(trigger).contains("bind label anchors are database derived");
            assertThat(trigger.indexOf("bind label anchors are database derived"))
                    .isLessThan(trigger.indexOf("public.g014_fence_parent"));
            for (String endpoint : endpoints) {
                String anchor = endpoint + "_version_at_bind";
                assertThat(table(sql, link)).contains(anchor + " bigint not null check (" + anchor
                        + " between 1 and 9007199254740991)");
                assertThat(table(sql, link + "_revision")).contains("snapshot_" + anchor
                        + " bigint not null check (snapshot_" + anchor + " between 1 and 9007199254740991)");
                assertThat(trigger.indexOf("end loop;"))
                        .isLessThan(trigger.indexOf("select version into strict new." + anchor));
                assertThat(trigger).doesNotContain("'" + anchor + "'"); // Never in mutable whitelist.
                assertThat(function(sql, "g014_" + link + "_revision_insert"))
                        .contains("new.snapshot_" + anchor, "b." + anchor);
                assertThat(function(sql, "g014_" + link + "_history_complete"))
                        .contains("r.snapshot_" + anchor, "new." + anchor);
            }
        }
    }

    private static List<String> entities() {
        var result = new ArrayList<>(ROOTS);
        result.addAll(LINKS);
        return List.copyOf(result);
    }

    private static List<String> mutations() {
        var result = new ArrayList<String>();
        for (String root : ROOTS) {
            result.add(root + ".create");
            result.add(root + ".update");
            if (!root.equals("hazard_source")) result.add(root + ".archive");
        }
        for (String link : LINKS) {
            result.add(link + ".bind");
            result.add(link + ".end");
        }
        return result;
    }

    private static ArrayList<String> captures(String source, String expression) {
        var result = new ArrayList<String>();
        var matcher = Pattern.compile(expression).matcher(source);
        while (matcher.find()) result.add(matcher.group(1));
        return result;
    }

    private static String table(String sql, String name) {
        int start = sql.indexOf("create table public." + name + " (");
        assertThat(start).isGreaterThanOrEqualTo(0);
        return sql.substring(start, sql.indexOf("\n);", start) + 3);
    }

    private static String function(String sql, String name) {
        int start = sql.indexOf("create function public." + name + "(");
        assertThat(start).isGreaterThanOrEqualTo(0);
        return sql.substring(start, sql.indexOf("end $$;", start) + 7);
    }

    private static String source() throws IOException {
        try (var input = AssetHazardFoundationMigrationContractTest.class.getResourceAsStream(
                "/db/migration/V21__asset_hazard_foundation.sql")) {
            assertThat(input).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

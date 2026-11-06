package com.mightyoung.softwarefactory.dualprevention.risk;

import com.mightyoung.softwarefactory.support.G014SqlProfile;
import com.mightyoung.softwarefactory.support.TransactionSqlSealGate;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** Source-contract guards only. No SQL parsing, migration application or PostgreSQL behavior proof. */
class RiskScenarioMigrationContractTest {
    private static final List<String> TABLES=List.of("risk_scenario","risk_scenario_revision","risk_hazard",
            "risk_hazard_revision","risk_scenario_revision_hazard","risk_scenario_receipt");
    private static final List<String> GUARDS=List.of("g014r_before_scenario","g014r_before_revision","g014r_before_link",
            "g014r_before_link_revision","g014r_before_snapshot","g014r_before_receipt");

    @Test void exactlySixTenantTablesForceRlsAndRetainCompositeIdentityReferences()throws IOException {
        String sql=source();
        assertThat(captures(sql,"create table public\\.(\\w+) \\(" )).containsExactlyElementsOf(TABLES);
        for(String name:TABLES){
            assertThat(sql).contains("alter table public."+name+" enable row level security;",
                    "alter table public."+name+" force row level security;","create policy g014r_tenant_isolation on public."+name);
            assertThat(table(sql,name)).contains("tenant_id uuid not null references public.tenant(id) on delete restrict");
        }
        assertThat(table(sql,"risk_scenario")).contains("foreign key (tenant_id,organization_id) references public.org_unit(tenant_id,id)",
                "foreign key (tenant_id,created_by_member_id) references public.tenant_member(tenant_id,id)",
                "foreign key (tenant_id,updated_by_member_id) references public.tenant_member(tenant_id,id)");
        assertThat(table(sql,"risk_scenario_receipt")).contains("account_id uuid not null references public.account(id)",
                "foreign key (tenant_id,audit_event_id) references public.audit_event(tenant_id,id)",
                "unique (tenant_id,actor_id,action_code,idempotency_key)");
    }
    @Test void deferredForeignKeysAndClosureNamesMatchTheFixedProfileExactly()throws IOException {
        String sql=source();
        var names=captures(sql,"add constraint (g014r_\\w+) [^;]*?deferrable initially deferred");
        assertThat(names).hasSize(13);
        var closures=captures(sql,"create constraint trigger (g014r_\\w+) [^;]*?deferrable initially deferred");
        assertThat(closures).hasSize(3);names.addAll(closures);
        String fixed=TransactionSqlSealGate.SqlTemplate.deferredConstraintsImmediate(G014SqlProfile.RISK_SCENARIO).sql();
        String body=fixed.substring("set constraints ".length(),fixed.indexOf(" immediate")).strip();
        assertThat(names).containsExactlyElementsOf(List.of(body.split(",\\s*")));
        assertThat(captures(sql,"(deferrable initially deferred)")).hasSize(16);
    }
    @Test void bothHazardAnchorsReferenceTheActualV21ImmutableCompositeKey()throws IOException {
        String sql=source();String previous=resource("V21__asset_hazard_foundation.sql");
        assertThat(table(previous,"hazard_source_revision")).contains("primary key (tenant_id,entity_id,version)",
                "snapshot_code varchar(64)","snapshot_description text","snapshot_existence_state varchar(24)");
        assertThat(sql).contains("foreign key (tenant_id,hazard_id,hazard_version_at_link) references public.hazard_source_revision(tenant_id,entity_id,version)",
                "foreign key (tenant_id,hazard_id,hazard_version_at_scenario_revision) references public.hazard_source_revision(tenant_id,entity_id,version)");
        assertThat(table(sql,"risk_scenario_revision_hazard")).contains("full_description text","description_preview text",
                "check (description_preview=left(full_description,200))","check (description_truncated=(char_length(full_description)>200))");
    }
    @Test void everyTableHasExplicitBeforeInsertUpdateDeleteProtectionAndReceiptClosesAllChildren()throws IOException {
        String sql=source();
        for(int i=0;i<TABLES.size();i++)assertThat(sql).contains("create trigger "+GUARDS.get(i)+" before insert or update or delete on public."+TABLES.get(i));
        for(String guard:GUARDS)assertThat(function(sql,guard)).contains("tg_op");
        for(String immutable:List.of("g014r_before_revision","g014r_before_link_revision","g014r_before_snapshot","g014r_before_receipt"))
            assertThat(function(sql,immutable)).contains("if tg_op<>'INSERT' then","g014r_open_command(");
        String open=function(sql,"g014r_open_command");
        assertThat(open).contains("s.last_command_id,s.updated_by_actor_id,s.updated_by_member_id,s.updated_at",
                "row(p_version,p_command,p_actor,p_member,p_occurred)",
                "exists(select 1 from public.risk_scenario_receipt where tenant_id=p_tenant and command_id=p_command)",
                "g014r_require_scenario_keys(");
        assertThat(function(sql,"g014r_before_receipt")).contains("g014r_validate_receipt(new)","new.command_id,false)");
    }
    @Test void rootAndLinkTransitionsCannotChangeImmutableIdentityOrReopenEndedLinks()throws IOException {
        String sql=source();String root=function(sql,"g014r_before_scenario");String link=function(sql,"g014r_before_link");
        assertThat(root).contains("new.version<>1","new.version is distinct from old.version+1",
                "new.last_command_id is not distinct from old.last_command_id","previous risk revision is not closed",
                "new.accident_code_ref is not null or new.accident_version is not null");
        assertThat(link).contains("old.version<>1 or old.ended_at is not null or new.version<>2",
                "new.ended_at is distinct from s.updated_at","new.ended_at<=old.linked_at",
                "'last_scenario_version','last_command_id','last_actor_id','last_member_id','last_occurred_at'");
        assertThat(sql).contains("where ended_at is null;","risk links only permit one current END transition");
    }
    @Test void closureUsesFullBidirectionalSetsAndPreheldKeysInsteadOfOnlyCounts()throws IOException {
        String closure=function(source(),"g014r_validate_scenario");
        assertThat(captures(closure,"(except select \\* from)")).hasSize(4);
        assertThat(closure).contains("active_count not between 1 and 100","hazard_version_at_link,h.version as hazard_version_at_scenario_revision",
                "hr.snapshot_description as full_description","hr.snapshot_existence_state as existence",
                "s.last_command_id as command_id,s.updated_by_actor_id as actor_id,s.updated_by_member_id as member_id,s.updated_at as occurred_at",
                "last_scenario_version,last_command_id,last_actor_id,last_member_id,last_occurred_at",
                "g014r_require_mutation_key('risk_scenario_revision_hazard'","g014r_validate_receipt(c)");
        assertThat(closure).doesNotContain("insert into","update public.","delete from","for update","pg_advisory_xact_lock(");
    }
    @Test void canonicalPlanIsBoundedAndSortedBeforeAnyPhysicalRowLock()throws IOException {
        String sql=source();String plan=function(sql,"g014r_mutation_key_plan");
        assertThat(plan).contains("cardinality(p_links)>200","cardinality(p_hazards) not between 1 and 100",
                "count(distinct v)","select 20,","select 30,","select 40,");
        assertThat(function(sql,"g014r_lock_mutation_keys")).contains("order by family,canonical_bytes")
                .doesNotContain("order by sha256","for update");
        String require=function(sql,"g014r_require_key");
        assertThat(require.indexOf("if not exists(select 1 from pg_locks")).isLessThan(require.indexOf("pg_try_advisory_xact_lock("));
        assertThat(require).contains("objsubid=2 and mode='ExclusiveLock' and granted").doesNotContain("perform pg_advisory_xact_lock(");
        for(String guard:GUARDS)assertThat(function(sql,guard)).doesNotContain("g014r_lock_row(","g014r_lock_mutation_keys(","for update");
    }
    @Test void rowAcquisitionHasOnlyRiskRelevantKindsWithoutExpandingOldHelpers()throws IOException {
        String sql=source();String rows=function(sql,"g014r_lock_row");
        assertThat(captures(rows,"when (\\d+) then")).containsExactly("60","100","110");
        assertThat(rows).contains("from public.hazard_source","from public.risk_scenario","from public.risk_hazard","for update");
        String context=function(sql,"g014r_require_context");
        assertThat(context).contains("'read committed'","'off'","mode='ShareLock' and granted",
                "pg_try_advisory_xact_lock_shared(1297303111,1)","pg_try_advisory_xact_lock_shared(tenant_bits)");
        assertThat(sql).doesNotContain("create or replace function public.g014_","create function public.g014_fence_parent");
    }
    @Test void invokerFunctionsArePinnedAndRevokedWithoutRuntimeAuthorityExpansion()throws IOException {
        String sql=source();String statements=sql.replaceAll("(?m)--[^\\r\\n]*","").toLowerCase(java.util.Locale.ROOT);
        var functions=captures(sql,"create function public\\.(\\w+)\\(");
        assertThat(functions).hasSize(29);
        assertThat(captures(sql,"revoke execute on function public\\.(\\w+)\\(" )).containsExactlyElementsOf(functions);
        assertThat(captures(sql,"(security invoker set search_path = pg_catalog, public)")).hasSize(functions.size());
        assertThat(statements).doesNotContain("security definer","create extension","create role","alter role","grant ",
                "on delete cascade","on delete set null","set_config(","set constraints all","execute format(",
                "insert into public.role_permission","update public.permission","command_xid","txid_current(");
    }
    @Test void auditTupleChecksOnlyTheNecessaryTypedColumnsAndCapabilitiesAreDefinitionsOnly()throws IOException {
        String sql=source();String audit=function(sql,"g014r_validate_receipt");
        assertThat(audit).contains("from public.audit_event a","a.actor_id=p_receipt.actor_id","a.action=p_receipt.action_code",
                "a.resource_type='risk_scenario'","a.resource_id=p_receipt.result_scenario_id","a.result='SUCCESS'",
                "a.trace_id=p_receipt.trace_id::text","a.occurred_at=p_receipt.occurred_at",
                "jsonb_typeof(a.diff_redacted)='object'","a.diff_redacted->>'schemaVersion'='1'",
                "jsonb_typeof(a.diff_redacted->'commandId')='string'","jsonb_typeof(a.diff_redacted->'changedFieldCategories')='array'",
                "'commandId',p_receipt.command_id::text","'changedFieldCategories',expected_fields",
                "'[\"IDENTITY\",\"DESCRIPTION\",\"RELATIONSHIP\"]'::jsonb");
        assertThat(audit).doesNotContain("a.reason","a.policy_version","select a.*");
        assertThat(captures(sql,"\\('[0-9a-f-]{36}','(risk_scenario\\.\\w+)','risk_scenario','\\w+'\\)"))
                .containsExactly("risk_scenario.read","risk_scenario.create","risk_scenario.update");
        assertThat(sql).contains("d8d4d55d-d453-5ea9-9ab9-4c7ce1f25777","4a8649f7-be73-587a-b7f3-c0fe98a917c4",
                "b9c33d83-7aa4-543b-bfd0-ea2c613e11f8","risk permission identifier triple mismatch","existing risk permission triple mismatch");
        assertThat(table(sql,"risk_scenario")).doesNotContain("score","grade","assessment","LOW");
    }
    private static String source()throws IOException{return resource("V22__risk_scenario_drafts.sql");}
    private static String resource(String name)throws IOException {
        try(var input=RiskScenarioMigrationContractTest.class.getResourceAsStream("/db/migration/"+name)){
            if(input==null)throw new IOException("Missing migration resource");
            return new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    private static List<String> captures(String sql,String regex){
        var result=new ArrayList<String>();var match=Pattern.compile(regex,Pattern.DOTALL).matcher(sql);
        while(match.find())result.add(match.group(1));return result;
    }
    private static String table(String sql,String name){
        var match=Pattern.compile("create table public\\."+name+" \\(([\\s\\S]*?)\\n\\);",Pattern.DOTALL).matcher(sql);
        assertThat(match.find()).as("table %s",name).isTrue();return match.group(1);
    }
    private static String function(String sql,String name){
        var match=Pattern.compile("create function public\\."+name+"\\([^$]*?as \\$\\$([\\s\\S]*?)\\$\\$;",Pattern.DOTALL).matcher(sql);
        assertThat(match.find()).as("function %s",name).isTrue();return match.group(1);
    }
}

package com.mightyoung.softwarefactory.dualprevention.risk;

import com.mightyoung.softwarefactory.support.G014SqlProfile;
import com.mightyoung.softwarefactory.support.TransactionSqlSealGate.SqlTemplate;
import java.time.Instant;
import java.util.*;

/** Source-owned finite write-graph SQL; no request text becomes a table, predicate or constraint. */
enum RiskScenarioSql {
    SET_TENANT("""
        select set_config('app.tenant_id', ?, true)
        """,Phase.SETUP,null,
        List.of(new Bind("tenant_text",Kind.TEXT)),
        List.of(new Column("set_config",Kind.TEXT))),
    RECEIPT_LOCK("""
        select public.g014r_lock_receipt_key(cast(? as uuid),cast(? as uuid),?,cast(? as uuid))
        """,Phase.LOCK,null,
        List.of(new Bind("tenant_id",Kind.UUID),new Bind("actor_id",Kind.UUID),new Bind("action_code",Kind.TEXT),new Bind("idempotency_key",Kind.UUID)),
        List.of()),
    NATURAL_LOCK("""
        select public.g014r_lock_natural_key(cast(? as uuid),?)
        """,Phase.LOCK,null,
        List.of(new Bind("tenant_id",Kind.UUID),new Bind("code",Kind.TEXT)),
        List.of()),
    MUTATION_PLAN("""
        select family,canonical_bytes from public.g014r_mutation_key_plan(cast(? as uuid),cast(? as uuid),cast(? as bigint),cast(? as uuid),cast(? as uuid[]),cast(? as bigint[]),cast(? as uuid[])) order by family,canonical_bytes
        """,Phase.LOCK,null,
        List.of(new Bind("tenant_id",Kind.UUID),new Bind("scenario_id",Kind.UUID),new Bind("version",Kind.LONG),new Bind("command_id",Kind.UUID),new Bind("changed_links",Kind.UUID_ARRAY),new Bind("changed_versions",Kind.LONG_ARRAY),new Bind("hazards",Kind.UUID_ARRAY)),
        List.of(new Column("family",Kind.INTEGER),new Column("canonical_bytes",Kind.BYTES))),
    MUTATION_LOCK("""
        select public.g014r_lock_mutation_keys(cast(? as uuid),cast(? as uuid),cast(? as bigint),cast(? as uuid),cast(? as uuid[]),cast(? as bigint[]),cast(? as uuid[]))
        """,Phase.LOCK,null,
        List.of(new Bind("tenant_id",Kind.UUID),new Bind("scenario_id",Kind.UUID),new Bind("version",Kind.LONG),new Bind("command_id",Kind.UUID),new Bind("changed_links",Kind.UUID_ARRAY),new Bind("changed_versions",Kind.LONG_ARRAY),new Bind("hazards",Kind.UUID_ARRAY)),
        List.of()),
    ROW_LOCK("""
        select public.g014r_lock_row(?,cast(? as uuid),cast(? as uuid)) as held_version
        """,Phase.LOCK,null,
        List.of(new Bind("row_kind",Kind.INTEGER),new Bind("tenant_id",Kind.UUID),new Bind("row_id",Kind.UUID)),
        List.of(new Column("held_version",Kind.LONG))),
    ROOT_CURRENT("""
        select s.tenant_id,s.id,s.organization_id,s.code,s.version,s.description,s.triggering_conditions,s.exposure_description,s.consequence,s.accident_code_ref,s.accident_version,s.created_by_actor_id,s.created_by_member_id,s.created_at,s.updated_by_actor_id,s.updated_by_member_id,s.updated_at,s.last_command_id from public.risk_scenario s where s.tenant_id=? and s.id=? and s.organization_id=any(cast(? as uuid[]))
        """,Phase.FINAL_READ,null,
        List.of(new Bind("tenant_id",Kind.UUID),new Bind("scenario_id",Kind.UUID),new Bind("organization_scope",Kind.UUID_ARRAY)),
        List.of(new Column("tenant_id",Kind.UUID),new Column("id",Kind.UUID),new Column("organization_id",Kind.UUID),new Column("code",Kind.TEXT),new Column("version",Kind.LONG),new Column("description",Kind.TEXT),new Column("triggering_conditions",Kind.TEXT),new Column("exposure_description",Kind.TEXT),new Column("consequence",Kind.TEXT),new Column("accident_code_ref",Kind.TEXT),new Column("accident_version",Kind.TEXT),new Column("created_by_actor_id",Kind.UUID),new Column("created_by_member_id",Kind.UUID),new Column("created_at",Kind.INSTANT),new Column("updated_by_actor_id",Kind.UUID),new Column("updated_by_member_id",Kind.UUID),new Column("updated_at",Kind.INSTANT),new Column("last_command_id",Kind.UUID))),
    ACTIVE_LINKS("""
        select l.tenant_id,l.id,l.scenario_id,l.hazard_id,l.hazard_version_at_link,l.version,l.linked_at,l.created_by_actor_id,l.created_by_member_id,l.last_scenario_version,l.last_command_id,l.last_actor_id,l.last_member_id,l.last_occurred_at from public.risk_hazard l join public.risk_scenario s on s.tenant_id=l.tenant_id and s.id=l.scenario_id where s.tenant_id=? and s.id=? and s.organization_id=any(cast(? as uuid[])) and l.ended_at is null order by l.id limit 101
        """,Phase.FINAL_READ,null,
        List.of(new Bind("tenant_id",Kind.UUID),new Bind("scenario_id",Kind.UUID),new Bind("organization_scope",Kind.UUID_ARRAY)),
        List.of(new Column("tenant_id",Kind.UUID),new Column("id",Kind.UUID),new Column("scenario_id",Kind.UUID),new Column("hazard_id",Kind.UUID),new Column("hazard_version_at_link",Kind.LONG),new Column("version",Kind.LONG),new Column("linked_at",Kind.INSTANT),new Column("created_by_actor_id",Kind.UUID),new Column("created_by_member_id",Kind.UUID),new Column("last_scenario_version",Kind.LONG),new Column("last_command_id",Kind.UUID),new Column("last_actor_id",Kind.UUID),new Column("last_member_id",Kind.UUID),new Column("last_occurred_at",Kind.INSTANT))),
    HAZARD_REFERENCE("""
        select h.id,h.organization_id,h.version,h.code,encode(sha256(convert_to(h.description,'UTF8')),'hex') as description_sha256,h.existence_state
        from public.hazard_source h join public.hazard_source_revision r on r.tenant_id=h.tenant_id and r.entity_id=h.id and r.version=h.version
        where h.tenant_id=? and h.id=? and h.organization_id=any(cast(? as uuid[]))
          and row(h.code,h.description,h.existence_state) is not distinct from row(r.snapshot_code,r.snapshot_description,r.snapshot_existence_state)
        """,Phase.FINAL_READ,null,
        List.of(new Bind("tenant_id",Kind.UUID),new Bind("hazard_id",Kind.UUID),new Bind("organization_scope",Kind.UUID_ARRAY)),
        List.of(new Column("id",Kind.UUID),new Column("organization_id",Kind.UUID),new Column("version",Kind.LONG),new Column("code",Kind.TEXT),new Column("description_sha256",Kind.TEXT),new Column("existence_state",Kind.TEXT))),
    RECEIPT_FIND("""
        select r.command_id,r.account_id,r.member_id,r.actor_id,r.action_code,r.payload_sha256,r.result_scenario_id,r.result_version,r.result_code,r.occurred_at,r.audit_event_id,r.trace_id from public.risk_scenario_receipt r join public.risk_scenario s on s.tenant_id=r.tenant_id and s.id=r.result_scenario_id where r.tenant_id=? and r.actor_id=? and r.action_code=? and r.idempotency_key=? and s.organization_id=any(cast(? as uuid[]))
        """,Phase.FINAL_READ,null,
        List.of(new Bind("tenant_id",Kind.UUID),new Bind("actor_id",Kind.UUID),new Bind("action_code",Kind.TEXT),new Bind("idempotency_key",Kind.UUID),new Bind("organization_scope",Kind.UUID_ARRAY)),
        List.of(new Column("command_id",Kind.UUID),new Column("account_id",Kind.UUID),new Column("member_id",Kind.UUID),new Column("actor_id",Kind.UUID),new Column("action_code",Kind.TEXT),new Column("payload_sha256",Kind.TEXT),new Column("result_scenario_id",Kind.UUID),new Column("result_version",Kind.LONG),new Column("result_code",Kind.TEXT),new Column("occurred_at",Kind.INSTANT),new Column("audit_event_id",Kind.UUID),new Column("trace_id",Kind.UUID))),
    ROOT_INSERT("""
        with i as (select cast(? as uuid) as tenant_id,cast(? as uuid) as id,cast(? as uuid) as organization_id,cast(? as text) as code,
        cast(? as text) as description,cast(? as text) as triggering_conditions,cast(? as text) as exposure_description,cast(? as text) as consequence,
        cast(? as uuid) as actor_id,cast(? as uuid) as member_id,cast(? as timestamptz) as occurred_at,cast(? as uuid) as command_id,cast(? as uuid[]) as organizations)
        insert into public.risk_scenario (tenant_id,id,organization_id,code,version,description,triggering_conditions,exposure_description,consequence,accident_code_ref,accident_version,created_by_actor_id,created_by_member_id,created_at,updated_by_actor_id,updated_by_member_id,updated_at,last_command_id)
        select i.tenant_id,i.id,i.organization_id,i.code,1,i.description,i.triggering_conditions,i.exposure_description,i.consequence,null,null,
        i.actor_id,i.member_id,i.occurred_at,i.actor_id,i.member_id,i.occurred_at,i.command_id from i
        join public.org_unit o on o.tenant_id=i.tenant_id and o.id=i.organization_id and o.active and o.id=any(i.organizations)
        """,Phase.WRITE,"risk_scenario.insert",
        List.of(new Bind("tenant_id",Kind.UUID),new Bind("scenario_id",Kind.UUID),new Bind("organization_id",Kind.UUID),new Bind("code",Kind.TEXT),new Bind("description",Kind.TEXT),new Bind("triggering_conditions",Kind.TEXT),new Bind("exposure_description",Kind.TEXT),new Bind("consequence",Kind.TEXT),new Bind("actor_id",Kind.UUID),new Bind("member_id",Kind.UUID),new Bind("occurred_at",Kind.INSTANT),new Bind("command_id",Kind.UUID),new Bind("organization_scope",Kind.UUID_ARRAY)),
        List.of()),
    ROOT_UPDATE("""
        update public.risk_scenario set description=?,triggering_conditions=?,exposure_description=?,consequence=?,version=?,updated_by_actor_id=?,updated_by_member_id=?,updated_at=?,last_command_id=?
        where tenant_id=? and id=? and version=? and organization_id=any(cast(? as uuid[]))
        """,Phase.WRITE,"risk_scenario.update",
        List.of(new Bind("description",Kind.TEXT),new Bind("triggering_conditions",Kind.TEXT),new Bind("exposure_description",Kind.TEXT),new Bind("consequence",Kind.TEXT),new Bind("version",Kind.LONG),new Bind("actor_id",Kind.UUID),new Bind("member_id",Kind.UUID),new Bind("occurred_at",Kind.INSTANT),new Bind("command_id",Kind.UUID),new Bind("tenant_id",Kind.UUID),new Bind("scenario_id",Kind.UUID),new Bind("expected_version",Kind.LONG),new Bind("organization_scope",Kind.UUID_ARRAY)),
        List.of()),
    ROOT_REVISION("""
        insert into public.risk_scenario_revision (tenant_id,entity_id,organization_id,code,version,description,triggering_conditions,exposure_description,consequence,accident_code_ref,accident_version,created_by_actor_id,created_by_member_id,created_at,updated_by_actor_id,updated_by_member_id,updated_at,command_id,actor_id,member_id,occurred_at,change_kind) select s.tenant_id,s.id,s.organization_id,s.code,s.version,s.description,s.triggering_conditions,s.exposure_description,s.consequence,s.accident_code_ref,s.accident_version,s.created_by_actor_id,s.created_by_member_id,s.created_at,s.updated_by_actor_id,s.updated_by_member_id,s.updated_at,s.last_command_id,s.updated_by_actor_id,s.updated_by_member_id,s.updated_at,case when s.version=1 then 'CREATE' else 'UPDATE' end from public.risk_scenario s where s.tenant_id=? and s.id=? and s.version=? and s.last_command_id=? and s.organization_id=any(cast(? as uuid[]))
        """,Phase.WRITE,"risk_scenario.revision",
        List.of(new Bind("tenant_id",Kind.UUID),new Bind("scenario_id",Kind.UUID),new Bind("version",Kind.LONG),new Bind("command_id",Kind.UUID),new Bind("organization_scope",Kind.UUID_ARRAY)),
        List.of()),
    LINK_NEW("""
        with i as (select cast(? as uuid) as tenant_id,cast(? as uuid) as scenario_id,cast(? as bigint) as scenario_version,
        cast(? as uuid) as command_id,cast(? as uuid) as actor_id,cast(? as uuid) as member_id,cast(? as timestamptz) as occurred_at,cast(? as uuid[]) as organizations) insert into public.risk_hazard (tenant_id,id,scenario_id,hazard_id,hazard_version_at_link,version,linked_at,ended_at,created_by_actor_id,created_by_member_id,last_scenario_version,last_command_id,last_actor_id,last_member_id,last_occurred_at)
        select i.tenant_id,cast(? as uuid),s.id,h.id,h.version,1,i.occurred_at,null,i.actor_id,i.member_id,i.scenario_version,i.command_id,i.actor_id,i.member_id,i.occurred_at
        from i join public.risk_scenario s on s.tenant_id=i.tenant_id and s.id=i.scenario_id and s.organization_id=any(i.organizations) and row(s.version,s.last_command_id,s.updated_by_actor_id,s.updated_by_member_id,s.updated_at) is not distinct from row(i.scenario_version,i.command_id,i.actor_id,i.member_id,i.occurred_at) join public.hazard_source h on h.tenant_id=i.tenant_id and h.id=? and h.version=?
        """,Phase.WRITE,"risk_hazard.new",
        List.of(new Bind("tenant_id",Kind.UUID),new Bind("scenario_id",Kind.UUID),new Bind("version",Kind.LONG),new Bind("command_id",Kind.UUID),new Bind("actor_id",Kind.UUID),new Bind("member_id",Kind.UUID),new Bind("occurred_at",Kind.INSTANT),new Bind("organization_scope",Kind.UUID_ARRAY),new Bind("link_id",Kind.UUID),new Bind("hazard_id",Kind.UUID),new Bind("hazard_version",Kind.LONG)),
        List.of()),
    LINK_END("""
        with i as (select cast(? as uuid) as tenant_id,cast(? as uuid) as scenario_id,cast(? as bigint) as scenario_version,
        cast(? as uuid) as command_id,cast(? as uuid) as actor_id,cast(? as uuid) as member_id,cast(? as timestamptz) as occurred_at,cast(? as uuid[]) as organizations) update public.risk_hazard l set version=2,ended_at=i.occurred_at,last_scenario_version=i.scenario_version,last_command_id=i.command_id,last_actor_id=i.actor_id,last_member_id=i.member_id,last_occurred_at=i.occurred_at
        from i join public.risk_scenario s on s.tenant_id=i.tenant_id and s.id=i.scenario_id and s.organization_id=any(i.organizations) and row(s.version,s.last_command_id,s.updated_by_actor_id,s.updated_by_member_id,s.updated_at) is not distinct from row(i.scenario_version,i.command_id,i.actor_id,i.member_id,i.occurred_at)
        where l.tenant_id=i.tenant_id and l.scenario_id=i.scenario_id and l.id=? and l.hazard_id=? and l.hazard_version_at_link=? and l.version=1 and l.ended_at is null
        """,Phase.WRITE,"risk_hazard.end",
        List.of(new Bind("tenant_id",Kind.UUID),new Bind("scenario_id",Kind.UUID),new Bind("version",Kind.LONG),new Bind("command_id",Kind.UUID),new Bind("actor_id",Kind.UUID),new Bind("member_id",Kind.UUID),new Bind("occurred_at",Kind.INSTANT),new Bind("organization_scope",Kind.UUID_ARRAY),new Bind("link_id",Kind.UUID),new Bind("hazard_id",Kind.UUID),new Bind("hazard_version_at_link",Kind.LONG)),
        List.of()),
    LINK_REVISION("""
        insert into public.risk_hazard_revision (tenant_id,entity_id,scenario_id,hazard_id,hazard_version_at_link,version,linked_at,ended_at,created_by_actor_id,created_by_member_id,scenario_version,command_id,actor_id,member_id,occurred_at) select l.tenant_id,l.id,l.scenario_id,l.hazard_id,l.hazard_version_at_link,l.version,l.linked_at,l.ended_at,l.created_by_actor_id,l.created_by_member_id,l.last_scenario_version,l.last_command_id,l.last_actor_id,l.last_member_id,l.last_occurred_at
        from public.risk_hazard l join public.risk_scenario s on s.tenant_id=l.tenant_id and s.id=l.scenario_id
        where l.tenant_id=? and l.id=? and l.scenario_id=? and l.last_scenario_version=? and l.version=? and l.last_command_id=? and s.organization_id=any(cast(? as uuid[]))
        """,Phase.WRITE,"risk_hazard.revision",
        List.of(new Bind("tenant_id",Kind.UUID),new Bind("link_id",Kind.UUID),new Bind("scenario_id",Kind.UUID),new Bind("version",Kind.LONG),new Bind("link_version",Kind.LONG),new Bind("command_id",Kind.UUID),new Bind("organization_scope",Kind.UUID_ARRAY)),
        List.of()),
    SNAPSHOT_INSERT("""
        insert into public.risk_scenario_revision_hazard (tenant_id,scenario_id,scenario_version,hazard_id,link_id,link_version,hazard_version_at_link,hazard_version_at_scenario_revision,snapshot_code,full_description,description_preview,description_truncated,existence,command_id,actor_id,member_id,occurred_at)
        select s.tenant_id,s.id,s.version,h.id,l.id,l.version,l.hazard_version_at_link,h.version,r.snapshot_code,r.snapshot_description,
        left(r.snapshot_description,200),(char_length(r.snapshot_description)>200),r.snapshot_existence_state,s.last_command_id,s.updated_by_actor_id,s.updated_by_member_id,s.updated_at
        from public.risk_scenario s join public.risk_hazard l on l.tenant_id=s.tenant_id and l.scenario_id=s.id and l.ended_at is null
        join public.hazard_source h on h.tenant_id=l.tenant_id and h.id=l.hazard_id
        join public.hazard_source_revision r on r.tenant_id=h.tenant_id and r.entity_id=h.id and r.version=h.version
        where s.tenant_id=? and s.id=? and s.version=? and s.last_command_id=? and l.id=? and h.id=? and l.hazard_version_at_link=? and h.version=? and s.organization_id=any(cast(? as uuid[]))
        """,Phase.WRITE,"risk_scenario.snapshot",
        List.of(new Bind("tenant_id",Kind.UUID),new Bind("scenario_id",Kind.UUID),new Bind("version",Kind.LONG),new Bind("command_id",Kind.UUID),new Bind("link_id",Kind.UUID),new Bind("hazard_id",Kind.UUID),new Bind("hazard_version_at_link",Kind.LONG),new Bind("hazard_version",Kind.LONG),new Bind("organization_scope",Kind.UUID_ARRAY)),
        List.of()),
    RECEIPT_INSERT("""
        with i as (select cast(? as uuid) as tenant_id,cast(? as uuid) as command_id,cast(? as uuid) as actor_id,cast(? as uuid) as account_id,cast(? as uuid) as member_id,
        cast(? as text) as action_code,cast(? as uuid) as idempotency_key,cast(? as text) as payload_sha256,cast(? as uuid) as scenario_id,cast(? as bigint) as version,
        cast(? as text) as code,cast(? as timestamptz) as occurred_at,cast(? as uuid) as audit_event_id,cast(? as uuid) as trace_id,cast(? as uuid[]) as organizations)
        insert into public.risk_scenario_receipt (tenant_id,command_id,actor_id,account_id,member_id,action_code,idempotency_key,fingerprint_version,payload_sha256,result_scenario_id,result_version,result_code,occurred_at,audit_event_id,trace_id)
        select i.tenant_id,i.command_id,i.actor_id,i.account_id,i.member_id,i.action_code,i.idempotency_key,1,i.payload_sha256,i.scenario_id,i.version,i.code,i.occurred_at,i.audit_event_id,i.trace_id
        from i join public.risk_scenario s on s.tenant_id=i.tenant_id and s.id=i.scenario_id and s.organization_id=any(i.organizations)
        and row(s.version,s.last_command_id,s.code,s.updated_by_actor_id,s.updated_by_member_id,s.updated_at) is not distinct from row(i.version,i.command_id,i.code,i.actor_id,i.member_id,i.occurred_at)
        """,Phase.WRITE,"risk_scenario.receipt",
        List.of(new Bind("tenant_id",Kind.UUID),new Bind("command_id",Kind.UUID),new Bind("actor_id",Kind.UUID),new Bind("account_id",Kind.UUID),new Bind("member_id",Kind.UUID),new Bind("action_code",Kind.TEXT),new Bind("idempotency_key",Kind.UUID),new Bind("payload_sha256",Kind.TEXT),new Bind("scenario_id",Kind.UUID),new Bind("version",Kind.LONG),new Bind("code",Kind.TEXT),new Bind("occurred_at",Kind.INSTANT),new Bind("audit_event_id",Kind.UUID),new Bind("trace_id",Kind.UUID),new Bind("organization_scope",Kind.UUID_ARRAY)),
        List.of()),
    SNAPSHOT_INDEX("""
        select q.hazard_id,q.link_id,q.link_version,q.hazard_version_at_link,q.hazard_version_at_scenario_revision,q.snapshot_code,
        encode(sha256(convert_to(q.full_description,'UTF8')),'hex') as description_sha256,q.existence,q.command_id,q.actor_id,q.member_id,q.occurred_at
        from public.risk_scenario_revision_hazard q join public.risk_scenario s on s.tenant_id=q.tenant_id and s.id=q.scenario_id
        where q.tenant_id=? and q.scenario_id=? and q.scenario_version=? and s.organization_id=any(cast(? as uuid[])) order by q.hazard_id limit 101
        """,Phase.FINAL_READ,null,
        List.of(new Bind("tenant_id",Kind.UUID),new Bind("scenario_id",Kind.UUID),new Bind("version",Kind.LONG),new Bind("organization_scope",Kind.UUID_ARRAY)),
        List.of(new Column("hazard_id",Kind.UUID),new Column("link_id",Kind.UUID),new Column("link_version",Kind.LONG),new Column("hazard_version_at_link",Kind.LONG),new Column("hazard_version_at_scenario_revision",Kind.LONG),new Column("snapshot_code",Kind.TEXT),new Column("description_sha256",Kind.TEXT),new Column("existence",Kind.TEXT),new Column("command_id",Kind.UUID),new Column("actor_id",Kind.UUID),new Column("member_id",Kind.UUID),new Column("occurred_at",Kind.INSTANT)));
    enum Phase { SETUP, LOCK, FINAL_READ, WRITE }
    enum Kind { UUID, LONG, INTEGER, TEXT, INSTANT, UUID_ARRAY, LONG_ARRAY, BYTES }
    record Bind(String name,Kind kind) {}
    record Column(String name,Kind kind) {}
    final String sql;final Phase phase;final String effect;final List<Bind> binds;final List<Column> columns;
    RiskScenarioSql(String sql,Phase phase,String effect,List<Bind> binds,List<Column> columns){
        this.sql=sql;this.phase=phase;this.effect=effect;this.binds=List.copyOf(binds);this.columns=List.copyOf(columns);
        if(sql.chars().filter(c->c=='?').count()!=binds.size())throw new IllegalArgumentException("Risk SQL bind count mismatch");
    }
    SqlTemplate template(){return new SqlTemplate(sql,binds.size(),phase==Phase.FINAL_READ,effect,null,G014SqlProfile.RISK_SCENARIO);}
    /** Business catalog only. Construction also needs the separately reviewed exact security/framework catalog. */
    static List<SqlTemplate> templates(){var result=new ArrayList<SqlTemplate>();for(var value:values())result.add(value.template());
        result.add(SqlTemplate.deferredConstraintsImmediate(G014SqlProfile.RISK_SCENARIO));return List.copyOf(result);}
    void requireBindings(List<?> values){
        if(values.size()!=binds.size())throw new IllegalArgumentException("Risk SQL bind count mismatch");
        for(int i=0;i<values.size();i++){Object value=values.get(i);boolean valid=switch(binds.get(i).kind()){
            case UUID -> value instanceof java.util.UUID;case LONG -> value instanceof Long;case INTEGER -> value instanceof Integer;
            case TEXT,UUID_ARRAY,LONG_ARRAY -> value instanceof String;case INSTANT -> value instanceof Instant;case BYTES -> value instanceof byte[];
        };if(!valid)throw new IllegalArgumentException("Risk SQL bind type mismatch");}
    }
}

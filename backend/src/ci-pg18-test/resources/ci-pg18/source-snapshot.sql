-- Digest covers the source's complete business graph/history/audit/receipts, excludes only cause ledger.
with scenario as (select * from public.risk_scenario where tenant_id=? and id=?),
links as (select l.* from public.risk_hazard l join scenario s on s.tenant_id=l.tenant_id and s.id=l.scenario_id),
hazards as (select h.* from public.hazard_source h join links l on l.tenant_id=h.tenant_id and l.hazard_id=h.id),
units as (select w.* from public.work_unit w join hazards h on h.tenant_id=w.tenant_id and h.work_unit_id=w.id),
commands as (select tenant_id,last_command_id as id from scenario union all select tenant_id,last_command_id from hazards union all select tenant_id,last_command_id from units),
audit_ids as (
 select r.tenant_id,r.audit_event_id as id from public.risk_scenario_receipt r join commands c on c.tenant_id=r.tenant_id and c.id=r.command_id
 union all select r.tenant_id,r.audit_event_id from public.asset_foundation_receipt r join commands c on c.tenant_id=r.tenant_id and c.id=r.command_id),
parts as (
 select 'scenario' as name,to_jsonb(s) as value from scenario s
 union all select 'link',to_jsonb(l) from links l
 union all select 'hazard',to_jsonb(h) from hazards h
 union all select 'unit',to_jsonb(w) from units w
 union all select 'scenario_revision',to_jsonb(r) from public.risk_scenario_revision r join scenario s on s.tenant_id=r.tenant_id and s.id=r.entity_id
 union all select 'link_revision',to_jsonb(r) from public.risk_hazard_revision r join links l on l.tenant_id=r.tenant_id and l.id=r.entity_id
 union all select 'hazard_revision',to_jsonb(r) from public.hazard_source_revision r join hazards h on h.tenant_id=r.tenant_id and h.id=r.entity_id
 union all select 'unit_revision',to_jsonb(r) from public.work_unit_revision r join units w on w.tenant_id=r.tenant_id and w.id=r.entity_id
 union all select 'hazard_snapshot',to_jsonb(r) from public.risk_scenario_revision_hazard r join scenario s on s.tenant_id=r.tenant_id and s.id=r.scenario_id
 union all select 'risk_receipt',to_jsonb(r) from public.risk_scenario_receipt r join commands c on c.tenant_id=r.tenant_id and c.id=r.command_id
 union all select 'asset_receipt',to_jsonb(r) from public.asset_foundation_receipt r join commands c on c.tenant_id=r.tenant_id and c.id=r.command_id
 union all select 'audit',to_jsonb(a) from public.audit_event a join audit_ids i on i.tenant_id=a.tenant_id and i.id=a.id)
select pg_catalog.encode(pg_catalog.sha256(pg_catalog.convert_to(
 (select pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object('table',name,'row',value) order by name,value::pg_catalog.text)::pg_catalog.text from parts),'UTF8')),'hex')

\set ON_ERROR_STOP on
begin;
do $$ begin if current_database()<>'msf_d2_read_scope_test' then raise exception 'wrong read-scope test database'; end if; end $$;
insert into role_permission(tenant_id,role_id,permission_id,scope_rule_id)
select r.tenant_id,r.id,p.id,rp.scope_rule_id
from role r join tenant t on t.id=r.tenant_id and t.code='d1seed'
join role_permission rp on rp.tenant_id=r.tenant_id and rp.role_id=r.id
join permission existing on existing.id=rp.permission_id and existing.code='tool.read'
join permission p on p.code=case when r.code='D1-keeper' then 'tool_loan_custody.read' else 'tool_loan_department.read' end
where r.code in ('D1-keeper','D1-nonkeeper','D1-outside')
on conflict do nothing;
-- Nonkeeper has an explicit custody read grant, which still cannot replace actual custodianship.
insert into role_permission(tenant_id,role_id,permission_id,scope_rule_id)
select r.tenant_id,r.id,p.id,rp.scope_rule_id
from role r join tenant t on t.id=r.tenant_id and t.code='d1seed'
join role_permission rp on rp.tenant_id=r.tenant_id and rp.role_id=r.id
join permission existing on existing.id=rp.permission_id and existing.code='tool.read'
cross join permission p where r.code='D1-nonkeeper' and p.code='tool_loan_custody.read'
on conflict do nothing;
insert into tool(tenant_id,id,version,created_at,updated_at,created_by_actor_id,updated_by_actor_id,code,name,custody_scope_id,organization_id)
select t.id,gen_random_uuid(),1,now(),now(),actor.id,actor.id,'D2-READ-SCOPE-'||substr(gen_random_uuid()::text,1,8),'只读范围验收工装',s.id,s.organization_id
from tenant t join tool_custody_scope s on s.tenant_id=t.id and s.code='D1-SCOPE-A'
join account a on a.login_key='d1seed-reader' join principal actor on actor.account_id=a.id
where t.code='d1seed';
commit;

\set ON_ERROR_STOP on
begin;
do $$ begin if current_database()<>'msf_d2_loans_test' then raise exception 'wrong test database'; end if; end $$;
insert into role_permission(tenant_id,role_id,permission_id,scope_rule_id)
select r.tenant_id,r.id,p.id,rp.scope_rule_id from role r join tenant t on t.id=r.tenant_id join role_permission rp on rp.tenant_id=t.id and rp.role_id=r.id join permission existing on existing.id=rp.permission_id and existing.code='tool.read' cross join permission p where t.code='d1seed' and r.code in ('D1-reader','D1-keeper') and p.code in ('tool_loan.read','tool_loan.create','tool_loan.update') on conflict do nothing;
insert into tool(tenant_id,id,version,created_at,updated_at,created_by_actor_id,updated_by_actor_id,code,name,custody_scope_id,organization_id)
select t.id,gen_random_uuid(),1,now(),now(),actor.id,actor.id,'D2-'||s.code||'-'||i,'借用验收工装 '||i,s.id,s.organization_id from tenant t join tool_custody_scope s on s.tenant_id=t.id join account a on a.login_key='d1seed-reader' join principal actor on actor.account_id=a.id cross join generate_series(1,3) i where t.code='d1seed' on conflict(tenant_id,code) do nothing;
commit;

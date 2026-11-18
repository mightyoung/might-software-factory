-- Configurable functional permissions: catalog metadata for the permission-config UI plus an append-only change log.
-- No runtime grants here; operators apply db/operator/V25-runtime-grants.sql separately.
alter table public.permission
    add column application_key varchar(64),
    add column label varchar(200),
    add column sort_order integer not null default 0;
alter table public.permission
    add constraint permission_application_key_valid check (application_key is null or application_key ~ '^[a-z][a-z0-9._-]{0,63}$');

update public.permission set application_key = 'safety', sort_order = v.ord, label = v.label
from (values
    ('finding.read', 10, '查看隐患'),
    ('finding.create', 20, '上报隐患'),
    ('finding.workflow.initialize', 30, '初始化隐患流程'),
    ('finding.confirm.person', 40, '人员确认'),
    ('finding.confirm.safety', 50, '安全确认'),
    ('finding.dispatch', 60, '派单'),
    ('finding.remediate', 70, '整改提交'),
    ('finding.verify', 80, '整改验收')
) as v(code, ord, label) where permission.code = v.code;

-- Asset and risk permissions created by V21/V22: one label per entity and action.
-- audit_event.read is left out on purpose: V23 makes its definition immutable and guards its grants separately.
update public.permission p
   set application_key = case when e.entity = 'equipment' then 'equipment' else 'safety' end,
       label = a.label || e.label, sort_order = e.ord + a.ord
from (values
    ('workshop', '车间', 100), ('production_line', '产线', 110), ('process_stage', '工序', 120),
    ('work_unit', '作业单元', 130), ('line_workshop', '产线车间关系', 140), ('stage_equipment', '工序设备关系', 150),
    ('responsibility_binding', '责任关系', 160), ('hazard_source', '危险源', 170), ('risk_scenario', '风险场景', 180),
    ('equipment', '设备', 200)
) as e(entity, label, ord), (values
    ('read', '查看', 0), ('create', '新增', 1), ('update', '修改', 2), ('archive', '归档', 3), ('bind', '建立', 4), ('end', '结束', 5)
) as a(action, label, ord)
where p.resource_type = e.entity and p.action = a.action;

insert into public.permission (id, code, resource_type, action, application_key, label, sort_order) values
    (gen_random_uuid(), 'platform.permission.read', 'platform', 'permission.read', 'platform', '查看权限配置', 300),
    (gen_random_uuid(), 'platform.permission.manage', 'platform', 'permission.manage', 'platform', '修改权限配置', 310);

create table public.permission_change_event (
    tenant_id uuid not null references public.tenant(id) on delete restrict,
    id uuid not null,
    role_id uuid not null,
    actor_member_id uuid not null,
    added_permission_codes text[] not null,
    removed_permission_codes text[] not null,
    trace_id uuid not null,
    occurred_at timestamptz not null,
    constraint permission_change_event_pkey primary key (tenant_id, id),
    constraint permission_change_event_role_fkey foreign key (tenant_id, role_id) references public."role"(tenant_id, id),
    constraint permission_change_event_nonempty check (cardinality(added_permission_codes) + cardinality(removed_permission_codes) > 0)
);
create index permission_change_event_role_time_idx on public.permission_change_event (tenant_id, role_id, occurred_at desc);
alter table public.permission_change_event enable row level security;
alter table public.permission_change_event force row level security;
create policy permission_change_event_tenant_isolation on public.permission_change_event
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
create function public.reject_permission_change_event_mutation() returns trigger language plpgsql as $$
begin
    raise exception 'permission_change_event is append-only';
end;
$$;
create trigger permission_change_event_append_only
    before update or delete on public.permission_change_event
    for each row execute function public.reject_permission_change_event_mutation();

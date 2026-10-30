-- Persistence foundation for verified tenant/member context and server-side entity read scopes.
-- This migration defines storage only. Runtime database grants are managed separately.

create table public.account (
    id uuid primary key,
    login_key varchar(200) not null unique,
    status varchar(24) not null check (status in ('ACTIVE', 'LOCKED', 'DISABLED')),
    credential_version bigint not null default 1 check (credential_version >= 1)
);

create table public.tenant (
    id uuid primary key,
    code varchar(64) not null unique,
    name varchar(200) not null,
    status varchar(24) not null check (status in ('ACTIVE', 'SUSPENDED')),
    auth_epoch bigint not null default 0 check (auth_epoch >= 0)
);

create table public.tenant_member (
    tenant_id uuid not null references public.tenant(id) on delete restrict,
    id uuid not null,
    account_id uuid not null references public.account(id) on delete restrict,
    status varchar(24) not null,
    auth_epoch bigint not null default 0 check (auth_epoch >= 0),
    display_name varchar(200) not null,
    constraint tenant_member_pkey primary key (tenant_id, id),
    constraint tenant_member_tenant_account_key unique (tenant_id, account_id)
);

create table public.org_unit (
    tenant_id uuid not null references public.tenant(id) on delete restrict,
    id uuid not null,
    code varchar(64) not null,
    name varchar(200) not null,
    parent_id uuid,
    active boolean not null default true,
    constraint org_unit_pkey primary key (tenant_id, id),
    constraint org_unit_tenant_code_key unique (tenant_id, code),
    constraint org_unit_parent_fkey foreign key (tenant_id, parent_id)
        references public.org_unit(tenant_id, id) on delete restrict
);

create table public.member_org (
    tenant_id uuid not null,
    member_id uuid not null,
    org_id uuid not null,
    kind varchar(24) not null check (kind in ('PRIMARY', 'CONCURRENT')),
    valid_from timestamptz not null,
    valid_to timestamptz,
    constraint member_org_pkey primary key (tenant_id, member_id, org_id, valid_from),
    constraint member_org_period_check check (valid_to is null or valid_from < valid_to),
    constraint member_org_member_fkey foreign key (tenant_id, member_id)
        references public.tenant_member(tenant_id, id) on delete restrict,
    constraint member_org_org_fkey foreign key (tenant_id, org_id)
        references public.org_unit(tenant_id, id) on delete restrict
);

create table public."position" (
    tenant_id uuid not null,
    id uuid not null,
    code varchar(64) not null,
    name varchar(200) not null,
    org_id uuid,
    active boolean not null default true,
    constraint position_pkey primary key (tenant_id, id),
    constraint position_tenant_code_key unique (tenant_id, code),
    constraint position_org_fkey foreign key (tenant_id, org_id)
        references public.org_unit(tenant_id, id) on delete restrict
);

create table public.member_position (
    tenant_id uuid not null,
    member_id uuid not null,
    position_id uuid not null,
    valid_from timestamptz not null,
    valid_to timestamptz,
    constraint member_position_pkey primary key (tenant_id, member_id, position_id, valid_from),
    constraint member_position_period_check check (valid_to is null or valid_from < valid_to),
    constraint member_position_member_fkey foreign key (tenant_id, member_id)
        references public.tenant_member(tenant_id, id) on delete restrict,
    constraint member_position_position_fkey foreign key (tenant_id, position_id)
        references public."position"(tenant_id, id) on delete restrict
);

create table public.user_group (
    tenant_id uuid not null,
    id uuid not null,
    code varchar(64) not null,
    name varchar(200) not null,
    active boolean not null default true,
    constraint user_group_pkey primary key (tenant_id, id),
    constraint user_group_tenant_code_key unique (tenant_id, code)
);

create table public.group_member (
    tenant_id uuid not null,
    member_id uuid not null,
    group_id uuid not null,
    valid_from timestamptz not null,
    valid_to timestamptz,
    constraint group_member_pkey primary key (tenant_id, member_id, group_id, valid_from),
    constraint group_member_period_check check (valid_to is null or valid_from < valid_to),
    constraint group_member_member_fkey foreign key (tenant_id, member_id)
        references public.tenant_member(tenant_id, id) on delete restrict,
    constraint group_member_group_fkey foreign key (tenant_id, group_id)
        references public.user_group(tenant_id, id) on delete restrict
);

create table public.permission (
    id uuid primary key,
    code varchar(128) not null unique,
    resource_type varchar(64) not null,
    action varchar(64) not null
);

create table public."role" (
    tenant_id uuid not null,
    id uuid not null,
    code varchar(64) not null,
    name varchar(200) not null,
    active boolean not null default true,
    constraint role_pkey primary key (tenant_id, id),
    constraint role_tenant_code_key unique (tenant_id, code)
);

create table public.scope_rule (
    tenant_id uuid not null,
    id uuid not null,
    kind varchar(24) not null check (kind in ('SELF', 'DEPT', 'DEPT_TREE', 'EXPLICIT', 'RELATION')),
    version_no integer not null check (version_no >= 1),
    definition jsonb not null,
    constraint scope_rule_definition_empty check (definition = '{}'::jsonb),
    constraint scope_rule_pkey primary key (tenant_id, id)
);

create table public.role_permission (
    tenant_id uuid not null,
    role_id uuid not null,
    permission_id uuid not null references public.permission(id) on delete restrict,
    scope_rule_id uuid,
    constraint role_permission_pkey primary key (tenant_id, role_id, permission_id),
    constraint role_permission_role_fkey foreign key (tenant_id, role_id)
        references public."role"(tenant_id, id) on delete restrict,
    constraint role_permission_scope_fkey foreign key (tenant_id, scope_rule_id)
        references public.scope_rule(tenant_id, id) on delete restrict
);

create table public.role_grant (
    tenant_id uuid not null,
    id uuid not null,
    role_id uuid not null,
    subject_kind varchar(24) not null check (subject_kind in ('MEMBER', 'ORG', 'POSITION', 'GROUP')),
    member_id uuid,
    org_id uuid,
    position_id uuid,
    group_id uuid,
    source_type varchar(64) not null,
    source_id uuid not null,
    granted_by_member_id uuid not null,
    valid_from timestamptz not null,
    valid_to timestamptz,
    constraint role_grant_pkey primary key (tenant_id, id),
    constraint role_grant_period_check check (valid_to is null or valid_from < valid_to),
    constraint role_grant_subject_shape check (
        (subject_kind = 'MEMBER' and member_id is not null and org_id is null and position_id is null and group_id is null)
        or (subject_kind = 'ORG' and member_id is null and org_id is not null and position_id is null and group_id is null)
        or (subject_kind = 'POSITION' and member_id is null and org_id is null and position_id is not null and group_id is null)
        or (subject_kind = 'GROUP' and member_id is null and org_id is null and position_id is null and group_id is not null)
    ),
    constraint role_grant_role_fkey foreign key (tenant_id, role_id)
        references public."role"(tenant_id, id) on delete restrict,
    constraint role_grant_member_fkey foreign key (tenant_id, member_id)
        references public.tenant_member(tenant_id, id) on delete restrict,
    constraint role_grant_org_fkey foreign key (tenant_id, org_id)
        references public.org_unit(tenant_id, id) on delete restrict,
    constraint role_grant_position_fkey foreign key (tenant_id, position_id)
        references public."position"(tenant_id, id) on delete restrict,
    constraint role_grant_group_fkey foreign key (tenant_id, group_id)
        references public.user_group(tenant_id, id) on delete restrict,
    constraint role_grant_grantor_fkey foreign key (tenant_id, granted_by_member_id)
        references public.tenant_member(tenant_id, id) on delete restrict
);

create table public.scope_org (
    tenant_id uuid not null,
    scope_rule_id uuid not null,
    org_id uuid not null,
    constraint scope_org_pkey primary key (tenant_id, scope_rule_id, org_id),
    constraint scope_org_rule_fkey foreign key (tenant_id, scope_rule_id)
        references public.scope_rule(tenant_id, id) on delete restrict,
    constraint scope_org_org_fkey foreign key (tenant_id, org_id)
        references public.org_unit(tenant_id, id) on delete restrict
);

create index member_org_current_lookup_idx on public.member_org (tenant_id, member_id, valid_from, valid_to);
create index member_position_current_lookup_idx on public.member_position (tenant_id, member_id, valid_from, valid_to);
create index group_member_current_lookup_idx on public.group_member (tenant_id, member_id, valid_from, valid_to);
create index role_grant_subject_lookup_idx on public.role_grant (tenant_id, subject_kind, member_id, org_id, position_id, group_id, valid_from, valid_to);
create index role_permission_permission_lookup_idx on public.role_permission (tenant_id, permission_id, role_id);
create index scope_org_rule_lookup_idx on public.scope_org (tenant_id, scope_rule_id, org_id);
create index org_unit_parent_lookup_idx on public.org_unit (tenant_id, parent_id);

alter table public.tenant enable row level security;
alter table public.tenant force row level security;
create policy tenant_isolation on public.tenant
    using (id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table public.tenant_member enable row level security;
alter table public.tenant_member force row level security;
create policy tenant_member_isolation on public.tenant_member
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table public.org_unit enable row level security;
alter table public.org_unit force row level security;
create policy org_unit_isolation on public.org_unit
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table public.member_org enable row level security;
alter table public.member_org force row level security;
create policy member_org_isolation on public.member_org
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table public."position" enable row level security;
alter table public."position" force row level security;
create policy position_isolation on public."position"
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table public.member_position enable row level security;
alter table public.member_position force row level security;
create policy member_position_isolation on public.member_position
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table public.user_group enable row level security;
alter table public.user_group force row level security;
create policy user_group_isolation on public.user_group
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table public.group_member enable row level security;
alter table public.group_member force row level security;
create policy group_member_isolation on public.group_member
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table public."role" enable row level security;
alter table public."role" force row level security;
create policy role_isolation on public."role"
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table public.scope_rule enable row level security;
alter table public.scope_rule force row level security;
create policy scope_rule_isolation on public.scope_rule
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table public.role_permission enable row level security;
alter table public.role_permission force row level security;
create policy role_permission_isolation on public.role_permission
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table public.role_grant enable row level security;
alter table public.role_grant force row level security;
create policy role_grant_isolation on public.role_grant
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table public.scope_org enable row level security;
alter table public.scope_org force row level security;
create policy scope_org_isolation on public.scope_org
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

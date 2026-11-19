-- D2 applications record requested items by immutable revision. Applications do not occupy tools.
create table public.tool_loan (
    tenant_id uuid not null references public.tenant(id) on delete restrict,
    id uuid not null,
    version bigint not null check (version >= 1),
    state varchar(32) not null check (state in ('PENDING_ISSUE', 'WITHDRAWN')),
    applicant_member_id uuid not null,
    organization_id uuid not null,
    custody_scope_id uuid not null,
    definition_version varchar(64) not null check (length(btrim(definition_version)) > 0),
    purpose varchar(4000) not null check (length(btrim(purpose)) > 0),
    created_at timestamptz not null,
    updated_at timestamptz not null,
    last_command_id uuid not null,
    primary key (tenant_id, id),
    foreign key (tenant_id, applicant_member_id) references public.tenant_member(tenant_id, id) on delete restrict,
    foreign key (tenant_id, organization_id) references public.org_unit(tenant_id, id) on delete restrict,
    foreign key (tenant_id, custody_scope_id) references public.tool_custody_scope(tenant_id, id) on delete restrict
);
create index tool_loan_applicant_idx on public.tool_loan(tenant_id, applicant_member_id, created_at, id);
create table public.tool_loan_revision (
    tenant_id uuid not null,
    loan_id uuid not null,
    version bigint not null check (version >= 1),
    state varchar(32) not null check (state in ('PENDING_ISSUE', 'WITHDRAWN')),
    purpose varchar(4000) not null check (length(btrim(purpose)) > 0),
    command_id uuid not null,
    action varchar(16) not null check (action in ('CREATE', 'UPDATE', 'WITHDRAW')),
    occurred_at timestamptz not null,
    actor_id uuid not null references public.principal(id) on delete restrict,
    primary key (tenant_id, loan_id, version),
    unique (tenant_id, command_id),
    foreign key (tenant_id, loan_id) references public.tool_loan(tenant_id, id) on delete restrict
);
create table public.tool_loan_item (
    tenant_id uuid not null,
    loan_id uuid not null,
    version bigint not null,
    line_no integer not null check (line_no >= 1),
    tool_id uuid not null,
    primary key (tenant_id, loan_id, version, line_no),
    unique (tenant_id, loan_id, version, tool_id),
    foreign key (tenant_id, loan_id, version) references public.tool_loan_revision(tenant_id, loan_id, version)
        on delete restrict deferrable initially deferred,
    foreign key (tenant_id, tool_id) references public.tool(tenant_id, id) on delete restrict
);
create table public.tool_loan_command_receipt (
    tenant_id uuid not null,
    actor_id uuid not null references public.principal(id) on delete restrict,
    idempotency_key uuid not null,
    action varchar(16) not null check (action in ('CREATE', 'UPDATE', 'WITHDRAW')),
    request_hash char(64) not null check (request_hash ~ '^[0-9a-f]{64}$'),
    loan_id uuid not null,
    result_version bigint not null,
    command_id uuid not null,
    event_id uuid not null,
    occurred_at timestamptz not null,
    primary key (tenant_id, actor_id, idempotency_key, action),
    unique (tenant_id, command_id),
    unique (tenant_id, event_id),
    foreign key (tenant_id, loan_id, result_version) references public.tool_loan_revision(tenant_id, loan_id, version)
        on delete restrict
);
create function public.guard_tool_loan_application_update() returns trigger language plpgsql as $$
begin
    if row(new.tenant_id, new.id, new.applicant_member_id, new.organization_id, new.custody_scope_id,
           new.definition_version, new.created_at)
       is distinct from row(old.tenant_id, old.id, old.applicant_member_id, old.organization_id, old.custody_scope_id,
           old.definition_version, old.created_at)
       or old.state <> 'PENDING_ISSUE' or new.version <> old.version + 1 then
        raise exception 'loan application identity and completed revisions are protected' using errcode = '23514';
    end if;
    return new;
end $$;
create trigger tool_loan_identity before update on public.tool_loan
    for each row execute function public.guard_tool_loan_application_update();
do $$
declare relation text;
begin
    foreach relation in array array['tool_loan', 'tool_loan_revision', 'tool_loan_item', 'tool_loan_command_receipt'] loop
        execute format('alter table public.%I enable row level security', relation);
        execute format('alter table public.%I force row level security', relation);
        execute format('create policy %I on public.%I using (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid) with check (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid)', relation || '_tenant_isolation', relation);
        if relation <> 'tool_loan' then
            execute format('create trigger %I before update or delete on public.%I for each row execute function public.reject_ledger_history_mutation()', relation || '_append_only', relation);
        end if;
        execute format('create trigger %I before truncate on public.%I for each statement execute function public.reject_ledger_history_mutation()', relation || '_no_truncate', relation);
    end loop;
end $$;
do $$
begin
    if exists(select 1 from public.permission where code in ('tool_loan.read', 'tool_loan.create', 'tool_loan.update')
       and (resource_type is distinct from 'tool_loan' or action is distinct from substring(code from 11)
            or application_key is not null and application_key <> 'tooling')) then
        raise exception 'loan permissions conflict with tooling application' using errcode = '23514';
    end if;
    insert into public.permission(id, code, resource_type, action, application_key, label, sort_order) values
        (gen_random_uuid(), 'tool_loan.read', 'tool_loan', 'read', 'tooling', '查看工装借用申请', 430),
        (gen_random_uuid(), 'tool_loan.create', 'tool_loan', 'create', 'tooling', '提交工装借用申请', 440),
        (gen_random_uuid(), 'tool_loan.update', 'tool_loan', 'update', 'tooling', '修订或撤销工装借用申请', 450)
    on conflict(code) do nothing;
end $$;

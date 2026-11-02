-- Authenticated self-service password-change events only. No runtime grants or automatic retention jobs.
create table public.security_event (
    tenant_id uuid not null references public.tenant(id) on delete restrict,
    id uuid not null,
    account_id uuid not null,
    actor_id uuid not null,
    type varchar(64) not null check (type = 'PASSWORD_CHANGE'),
    result varchar(16) not null check (result in ('SUCCESS', 'DENIED', 'FAILED', 'UNKNOWN')),
    reason_code varchar(64),
    trace_id uuid not null,
    policy_version varchar(64) not null,
    occurred_at timestamptz not null,
    constraint security_event_pkey primary key (tenant_id, id),
    constraint security_event_reason_result_valid check (
        (result = 'SUCCESS' and reason_code is null)
        or (result = 'DENIED' and reason_code is not null and reason_code in ('CURRENT_PASSWORD_REJECTED', 'STALE_IDENTITY',
            'INVALID_NEW_PASSWORD', 'PREPARATION_EXPIRED', 'RATE_LIMITED'))
        or (result = 'FAILED' and reason_code is not null and reason_code in ('WRITE_FAILED', 'AUDIT_UNAVAILABLE'))
        or (result = 'UNKNOWN' and reason_code is not null and reason_code = 'COMMIT_OUTCOME_UNKNOWN')
    )
);
create index security_event_subject_time_idx
    on public.security_event (tenant_id, account_id, occurred_at desc);
alter table public.security_event enable row level security;
alter table public.security_event force row level security;
create policy security_event_tenant_isolation on public.security_event
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
create function public.reject_security_event_mutation() returns trigger language plpgsql as $$
begin
    raise exception 'security_event is append-only';
end;
$$;
create trigger security_event_append_only
    before update or delete on public.security_event
    for each row execute function public.reject_security_event_mutation();

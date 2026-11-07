-- Anonymous admission outcomes are a separate global append-only stream, not tenant-scoped password events.
-- No runtime grants or history-reading endpoint are installed by this migration.
create table public.authentication_event (
    attempt_id uuid primary key,
    outcome varchar(40) not null,
    account_id uuid,
    actor_id uuid,
    tenant_id uuid,
    member_id uuid,
    occurred_at timestamptz not null,
    policy_version varchar(40) not null check (policy_version = 'login-audit-v1'),
    constraint authentication_event_outcome check (outcome in (
        'AUTHENTICATION_DENIED', 'AUTHENTICATION_UNAVAILABLE',
        'SESSION_PERSISTED', 'SESSION_OUTCOME_UNKNOWN'
    )),
    constraint authentication_event_subject check (
        (outcome in ('AUTHENTICATION_DENIED', 'AUTHENTICATION_UNAVAILABLE')
            and account_id is null and actor_id is null and tenant_id is null and member_id is null)
        or (outcome in ('SESSION_PERSISTED', 'SESSION_OUTCOME_UNKNOWN')
            and account_id is not null and actor_id is not null and tenant_id is not null and member_id is not null)
    )
);

create index authentication_event_time on public.authentication_event (occurred_at, attempt_id);

create function public.prevent_authentication_event_mutation() returns trigger
language plpgsql as $$
begin
    raise exception 'Authentication events are append-only' using errcode = '55000';
end;
$$;

create trigger authentication_event_append_only
    before update or delete or truncate on public.authentication_event
    for each statement execute function public.prevent_authentication_event_mutation();

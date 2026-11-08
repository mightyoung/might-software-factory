-- Source-only durable reservation. Actual producer/schema/grants are separate acceptance gates.
-- Only INTENT_CREATED is stored here; expiry is derived, and no bytes exist in this phase.
create table public.finding_evidence_upload_intent (
    tenant_id uuid not null references public.tenant(id) on delete restrict,
    id uuid not null,
    owner_account_id uuid not null references public.account(id) on delete restrict,
    owner_actor_id uuid not null references public.principal(id) on delete restrict,
    owner_member_id uuid not null,
    operation varchar(64) not null check (operation = 'FINDING_REMEDIATION_UPLOAD_INTENT_V1'),
    idempotency_key uuid not null,
    fingerprint_version varchar(32) not null check (fingerprint_version = 'upload-intent-v1'),
    payload_sha256 varchar(64) not null check (payload_sha256 ~ '^[0-9a-f]{64}$'),
    finding_id uuid not null,
    round_no integer not null check (round_no >= 1),
    notice_id uuid not null,
    evidence_set_id uuid not null,
    expected_finding_version bigint not null check (expected_finding_version >= 0),
    expected_evidence_revision bigint not null check (expected_evidence_revision >= 0),
    filename varchar(1020) not null check (char_length(filename) between 1 and 255
        and filename not in ('.', '..') and filename !~ '[[:cntrl:]/\\]'),
    declared_media_type varchar(255) not null
        check (declared_media_type ~ '^[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+$'),
    declared_size_bytes bigint not null check (declared_size_bytes between 1 and 67108864),
    producer_configuration_version varchar(64) not null
        check (producer_configuration_version ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'),
    upload_policy_version varchar(64) not null
        check (upload_policy_version ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'),
    state varchar(24) not null check (state = 'INTENT_CREATED'),
    created_at timestamptz not null,
    expires_at timestamptz not null check (expires_at > created_at),
    constraint finding_evidence_upload_intent_pkey primary key (tenant_id, id),
    constraint finding_evidence_upload_intent_idempotency_key
        unique (tenant_id, owner_actor_id, operation, idempotency_key),
    constraint finding_evidence_upload_intent_owner_fkey foreign key (tenant_id, owner_member_id)
        references public.tenant_member(tenant_id, id) on delete restrict,
    constraint finding_evidence_upload_intent_round_fkey foreign key (tenant_id, finding_id, round_no)
        references public.remediation_round(tenant_id, finding_id, round_no) on delete restrict,
    constraint finding_evidence_upload_intent_notice_fkey foreign key (tenant_id, notice_id, finding_id, round_no)
        references public.remediation_notice(tenant_id, id, finding_id, round_no) on delete restrict,
    constraint finding_evidence_upload_intent_set_fkey
        foreign key (tenant_id, evidence_set_id, finding_id, round_no, owner_member_id)
        references public.evidence_set(tenant_id, id, finding_id, round_no, owner_member_id) on delete restrict
);

-- Historical identity shape only. Current ACTIVE/epochs/credential/action authority is service-owned.
-- Normal invoker privileges and FORCE RLS apply to every joined source.
create function public.check_finding_upload_intent_owner() returns trigger language plpgsql as $$
begin
    if not exists (
        select 1 from public.tenant_member m
        join public.principal p on p.id = new.owner_actor_id
            and p.kind = 'HUMAN' and p.account_id = new.owner_account_id
        join public.remediation_notice n on n.tenant_id = m.tenant_id
            and n.id = new.notice_id and n.finding_id = new.finding_id
            and n.round_no = new.round_no and n.assignee_id = m.id
        join public.evidence_set s on s.tenant_id = m.tenant_id
            and s.id = new.evidence_set_id and s.finding_id = new.finding_id
            and s.round_no = new.round_no and s.owner_member_id = m.id
        where m.tenant_id = new.tenant_id and m.id = new.owner_member_id
            and m.account_id = new.owner_account_id
    ) then
        raise exception 'Upload intent ownership context is invalid' using errcode = '23514';
    end if;
    return new;
end;
$$;
create trigger finding_upload_intent_owner before insert on public.finding_evidence_upload_intent
    for each row execute function public.check_finding_upload_intent_owner();

create function public.reject_finding_upload_intent_mutation() returns trigger language plpgsql as $$
begin
    raise exception 'Upload intent reservations are immutable' using errcode = '23514';
end;
$$;
create trigger finding_upload_intent_immutable before update or delete on public.finding_evidence_upload_intent
    for each row execute function public.reject_finding_upload_intent_mutation();

create index finding_upload_intent_owner_expiry_idx
    on public.finding_evidence_upload_intent(tenant_id, owner_member_id, expires_at);
create index finding_upload_intent_tenant_expiry_idx
    on public.finding_evidence_upload_intent(tenant_id, expires_at, id);
create index finding_upload_intent_finding_set_idx
    on public.finding_evidence_upload_intent(tenant_id, finding_id, evidence_set_id);

alter table public.finding_evidence_upload_intent enable row level security;
alter table public.finding_evidence_upload_intent force row level security;
create policy finding_evidence_upload_intent_tenant_isolation on public.finding_evidence_upload_intent
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

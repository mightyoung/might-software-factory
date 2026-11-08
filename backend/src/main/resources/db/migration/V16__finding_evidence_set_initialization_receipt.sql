-- Source-only G024 receipt. Does not apply runtime privileges or initialize any target rows.
create table public.finding_evidence_set_initialization_receipt (
    tenant_id uuid not null references public.tenant(id) on delete restrict,
    id uuid not null,
    owner_account_id uuid not null references public.account(id) on delete restrict,
    owner_actor_id uuid not null references public.principal(id) on delete restrict,
    owner_member_id uuid not null,
    operation varchar(64) not null check (operation = 'FINDING_REMEDIATION_EVIDENCE_SET_INITIALIZE_V1'),
    idempotency_key uuid not null,
    fingerprint_version varchar(32) not null check (fingerprint_version = 'evidence-set-init-v1'),
    payload_sha256 varchar(64) not null check (payload_sha256 ~ '^[0-9a-f]{64}$'),
    finding_id uuid not null,
    round_no integer not null check (round_no >= 1),
    notice_id uuid not null,
    evidence_set_id uuid not null,
    expected_finding_version bigint not null check (expected_finding_version >= 0),
    initial_evidence_revision bigint not null check (initial_evidence_revision = 0),
    created_at timestamptz not null,
    constraint finding_evidence_set_initialization_receipt_pkey primary key (tenant_id, id),
    constraint finding_evidence_set_initialization_idempotency_key
        unique (tenant_id, owner_actor_id, operation, idempotency_key),
    constraint finding_evidence_set_initialization_set_key unique (tenant_id, evidence_set_id),
    constraint finding_evidence_set_initialization_owner_fkey foreign key (tenant_id, owner_member_id)
        references public.tenant_member(tenant_id, id) on delete restrict,
    constraint finding_evidence_set_initialization_round_fkey foreign key (tenant_id, finding_id, round_no)
        references public.remediation_round(tenant_id, finding_id, round_no) on delete restrict,
    constraint finding_evidence_set_initialization_notice_fkey foreign key (tenant_id, notice_id, finding_id, round_no)
        references public.remediation_notice(tenant_id, id, finding_id, round_no) on delete restrict,
    constraint finding_evidence_set_initialization_set_fkey
        foreign key (tenant_id, evidence_set_id, finding_id, round_no, owner_member_id)
        references public.evidence_set(tenant_id, id, finding_id, round_no, owner_member_id) on delete restrict
);

-- Normal-invoker structural provenance check, not a substitute for current service authorization.
-- No SECURITY DEFINER; joined sources retain their existing RLS and privilege boundaries.
create function public.check_finding_evidence_set_initialization() returns trigger language plpgsql as $$
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
            and s.state = 'UPLOADING' and s.evidence_revision = 0
            and s.verified_at is null and s.submission_sealed_at is null
            and s.created_at = new.created_at
        where m.tenant_id = new.tenant_id and m.id = new.owner_member_id
            and m.account_id = new.owner_account_id
            and not exists (select 1 from public.evidence_item item
                where item.tenant_id = new.tenant_id and item.evidence_set_id = new.evidence_set_id)
    ) then
        raise exception 'Evidence set initialization context is invalid' using errcode = '23514';
    end if;
    return new;
end;
$$;
create trigger finding_evidence_set_initialization_context
    before insert on public.finding_evidence_set_initialization_receipt
    for each row execute function public.check_finding_evidence_set_initialization();

create function public.reject_finding_evidence_set_initialization_mutation() returns trigger language plpgsql as $$
begin
    raise exception 'Evidence set initialization receipts are immutable' using errcode = '23514';
end;
$$;
create trigger finding_evidence_set_initialization_immutable
    before update or delete on public.finding_evidence_set_initialization_receipt
    for each row execute function public.reject_finding_evidence_set_initialization_mutation();

create index finding_evidence_set_initialization_finding_idx
    on public.finding_evidence_set_initialization_receipt(tenant_id, finding_id, round_no);

alter table public.finding_evidence_set_initialization_receipt enable row level security;
alter table public.finding_evidence_set_initialization_receipt force row level security;
create policy finding_evidence_set_initialization_tenant_isolation on public.finding_evidence_set_initialization_receipt
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- Minimal tenant-scoped file metadata and evidence-item link contract.
-- This does not implement upload, filesystem storage, scanning, or content verification.
-- The trusted storage verifier remains a separate required application adapter.

create table public.file_object (
    tenant_id uuid not null references public.tenant(id) on delete restrict,
    id uuid not null,
    owner_member_id uuid not null,
    owner_actor_id uuid not null references public.principal(id) on delete restrict,
    object_key varchar(512) not null,
    size_bytes bigint not null check (size_bytes >= 0),
    mime varchar(255) not null check (length(btrim(mime)) > 0),
    checksum varchar(64),
    state varchar(24) not null check (state in (
        'UPLOADING', 'QUARANTINED', 'READY', 'DELETING', 'FAILED'
    )),
    storage_version varchar(255),
    created_at timestamptz not null,
    verified_at timestamptz,
    constraint file_object_pkey primary key (tenant_id, id),
    constraint file_object_object_key unique (tenant_id, object_key),
    constraint file_object_owner_fkey foreign key (tenant_id, owner_member_id)
        references public.tenant_member(tenant_id, id) on delete restrict,
    constraint file_object_checksum_shape check (checksum is null or checksum ~ '^[0-9a-f]{64}$'),
    constraint file_object_ready_shape check (
        state <> 'READY' or (checksum is not null and storage_version is not null and verified_at is not null)
    )
);

create table public.file_link (
    tenant_id uuid not null,
    id uuid not null,
    file_id uuid not null,
    resource_type varchar(64) not null check (length(btrim(resource_type)) > 0),
    resource_id uuid not null,
    purpose varchar(64) not null check (length(btrim(purpose)) > 0),
    linked_by_actor_id uuid not null references public.principal(id) on delete restrict,
    linked_at timestamptz not null,
    revoked_at timestamptz,
    revoked_by_actor_id uuid references public.principal(id) on delete restrict,
    constraint file_link_pkey primary key (tenant_id, id),
    constraint file_link_file_fkey foreign key (tenant_id, file_id)
        references public.file_object(tenant_id, id) on delete restrict,
    constraint file_link_revocation_shape check (
        (revoked_at is null and revoked_by_actor_id is null)
        or (revoked_at is not null and revoked_by_actor_id is not null)
    )
);

alter table public.evidence_item
    add constraint evidence_item_file_fkey foreign key (tenant_id, file_id)
        references public.file_object(tenant_id, id) on delete restrict;
alter table public.evidence_item
    add constraint evidence_item_snapshot_context_key unique (tenant_id, id, evidence_set_id);
alter table public.remediation_submission
    add constraint remediation_submission_evidence_context_key
        unique (tenant_id, id, evidence_set_id, finding_id, round_no, submitted_by);
alter table public.remediation_submission
    add column evidence_snapshot_count integer,
    add column evidence_snapshot_manifest uuid[],
    add column evidence_snapshots_sealed_at timestamptz,
    add constraint remediation_submission_snapshot_seal_shape check (
        (evidence_snapshots_sealed_at is null
            and evidence_snapshot_count is null
            and evidence_snapshot_manifest is null)
        or (evidence_snapshots_sealed_at is not null
            and evidence_snapshot_count is not null
            and evidence_snapshot_count > 0
            and evidence_snapshot_manifest is not null
            and evidence_snapshot_count = cardinality(evidence_snapshot_manifest)
            and array_position(evidence_snapshot_manifest, null) is null)
    );
alter table public.file_link
    add constraint file_link_submission_snapshot_key
        unique (tenant_id, id, file_id, resource_type, resource_id, purpose);

-- A submission records the exact file bytes and link authorization accepted at submit time.
-- Repeated use of the same file under distinct evidence item IDs remains representable.
create table public.remediation_submission_evidence (
    tenant_id uuid not null,
    submission_id uuid not null,
    finding_id uuid not null,
    round_no integer not null check (round_no >= 1),
    evidence_set_id uuid not null,
    submitted_by uuid not null,
    evidence_item_id uuid not null,
    file_link_id uuid not null,
    file_id uuid not null,
    resource_type varchar(64) not null check (resource_type = 'FINDING_EVIDENCE_ITEM'),
    resource_id uuid not null,
    purpose varchar(64) not null check (purpose = 'REMEDIATION_EVIDENCE'),
    owner_member_id uuid not null,
    owner_actor_id uuid not null,
    sha256 varchar(64) not null check (sha256 ~ '^[0-9a-f]{64}$'),
    storage_version varchar(255) not null check (length(btrim(storage_version)) > 0),
    verification_receipt_id uuid not null,
    verifier_id varchar(128) not null check (length(btrim(verifier_id)) > 0),
    verifier_key_id varchar(128) not null check (length(btrim(verifier_key_id)) > 0),
    verification_policy_version varchar(64) not null check (length(btrim(verification_policy_version)) > 0),
    verification_level varchar(24) not null check (verification_level = 'CONTENT'),
    verified_at timestamptz not null,
    constraint remediation_submission_evidence_pkey
        primary key (tenant_id, submission_id, evidence_item_id),
    constraint remediation_submission_evidence_parent_fkey
        foreign key (tenant_id, submission_id, evidence_set_id, finding_id, round_no, submitted_by)
        references public.remediation_submission
            (tenant_id, id, evidence_set_id, finding_id, round_no, submitted_by) on delete restrict,
    constraint remediation_submission_evidence_item_fkey
        foreign key (tenant_id, evidence_item_id, evidence_set_id)
        references public.evidence_item(tenant_id, id, evidence_set_id) on delete restrict,
    constraint remediation_submission_evidence_file_fkey
        foreign key (tenant_id, file_id)
        references public.file_object(tenant_id, id) on delete restrict,
    constraint remediation_submission_evidence_link_fkey
        foreign key (tenant_id, file_link_id, file_id, resource_type, resource_id, purpose)
        references public.file_link(tenant_id, id, file_id, resource_type, resource_id, purpose)
        on delete restrict,
    constraint remediation_submission_evidence_owner_fkey
        foreign key (tenant_id, owner_member_id)
        references public.tenant_member(tenant_id, id) on delete restrict,
    constraint remediation_submission_evidence_actor_fkey
        foreign key (owner_actor_id) references public.principal(id) on delete restrict,
    constraint remediation_submission_evidence_owner_context_check
        check (submitted_by = owner_member_id and resource_id = evidence_item_id)
);

create index remediation_submission_evidence_file_version_idx
    on public.remediation_submission_evidence (tenant_id, file_id, storage_version);

-- Item creation takes the same evidence-set lock used while sealing a submission.
-- Once a set has a sealed submission, its item list cannot be extended.
create function public.guard_new_evidence_item_for_submission() returns trigger
language plpgsql
as $$
begin
    perform 1
      from public.evidence_set es
     where es.tenant_id = NEW.tenant_id and es.id = NEW.evidence_set_id
     for update;
    if not found then
        raise exception 'evidence set for new item was not found';
    end if;
    if exists (
        select 1 from public.remediation_submission submission
         where submission.tenant_id = NEW.tenant_id
           and submission.evidence_set_id = NEW.evidence_set_id
           and submission.evidence_snapshots_sealed_at is not null
    ) then
        raise exception 'evidence set is sealed by a remediation submission';
    end if;
    return NEW;
end;
$$;

create trigger evidence_item_submission_set_lock
    before insert on public.evidence_item
    for each row execute function public.guard_new_evidence_item_for_submission();

-- Serialize child inserts against the one-way parent seal transition.
create function public.lock_remediation_submission_evidence_parent() returns trigger
language plpgsql
as $$
declare
    submission_row record;
begin
    select submission.evidence_set_id, submission.finding_id, submission.round_no,
           submission.submitted_by, submission.evidence_snapshots_sealed_at
      into submission_row
      from public.remediation_submission submission
     where submission.tenant_id = NEW.tenant_id and submission.id = NEW.submission_id
     for update;

    if not found then
        raise exception 'remediation submission for evidence snapshot was not found';
    end if;
    if submission_row.evidence_snapshots_sealed_at is not null then
        raise exception 'remediation submission evidence snapshots are sealed';
    end if;
    if submission_row.evidence_set_id is distinct from NEW.evidence_set_id
       or submission_row.finding_id is distinct from NEW.finding_id
       or submission_row.round_no is distinct from NEW.round_no
       or submission_row.submitted_by is distinct from NEW.submitted_by then
        raise exception 'remediation submission evidence parent context does not match';
    end if;
    return NEW;
end;
$$;

create trigger remediation_submission_evidence_parent_lock
    before insert on public.remediation_submission_evidence
    for each row execute function public.lock_remediation_submission_evidence_parent();

-- The application requests only the seal transition. The database computes and stores
-- the complete, sorted item manifest and its count while holding the evidence-set lock.
create function public.guard_remediation_submission_snapshot_seal() returns trigger
language plpgsql
as $$
declare
    expected_item_ids uuid[];
    actual_item_ids uuid[];
begin
    if TG_OP = 'INSERT' then
        if NEW.evidence_snapshot_count is not null
           or NEW.evidence_snapshot_manifest is not null
           or NEW.evidence_snapshots_sealed_at is not null then
            raise exception 'submission evidence snapshot seal is database-managed';
        end if;
        return NEW;
    end if;

    if OLD.evidence_snapshots_sealed_at is not null then
        raise exception 'a sealed remediation submission is immutable';
    end if;
    if NEW.evidence_snapshots_sealed_at is null then
        raise exception 'remediation submission update must seal its evidence snapshots';
    end if;
    if ROW(NEW.tenant_id, NEW.id, NEW.finding_id, NEW.notice_id, NEW.round_no,
           NEW.submitted_by, NEW.submitted_at, NEW.evidence_set_id, NEW.statement,
           NEW.expected_version, NEW.command_id)
       IS DISTINCT FROM
       ROW(OLD.tenant_id, OLD.id, OLD.finding_id, OLD.notice_id, OLD.round_no,
           OLD.submitted_by, OLD.submitted_at, OLD.evidence_set_id, OLD.statement,
           OLD.expected_version, OLD.command_id) then
        raise exception 'remediation submission business fields are immutable';
    end if;
    if NEW.evidence_snapshot_count is not null or NEW.evidence_snapshot_manifest is not null then
        raise exception 'submission evidence snapshot count and manifest are database-managed';
    end if;

    perform 1
      from public.evidence_set es
     where es.tenant_id = NEW.tenant_id and es.id = NEW.evidence_set_id
     for update;
    if not found then
        raise exception 'evidence set for remediation submission was not found';
    end if;

    select array_agg(item.id order by item.id)
      into expected_item_ids
      from public.evidence_item item
     where item.tenant_id = NEW.tenant_id
       and item.evidence_set_id = NEW.evidence_set_id;
    select array_agg(snapshot.evidence_item_id order by snapshot.evidence_item_id)
      into actual_item_ids
      from public.remediation_submission_evidence snapshot
     where snapshot.tenant_id = NEW.tenant_id
       and snapshot.submission_id = NEW.id;

    if expected_item_ids is null or actual_item_ids is distinct from expected_item_ids then
        raise exception 'evidence snapshot manifest does not match the complete evidence set';
    end if;

    NEW.evidence_snapshot_count := cardinality(expected_item_ids);
    NEW.evidence_snapshot_manifest := expected_item_ids;
    NEW.evidence_snapshots_sealed_at := transaction_timestamp();
    return NEW;
end;
$$;

create trigger remediation_submission_evidence_seal_guard
    before insert or update on public.remediation_submission
    for each row execute function public.guard_remediation_submission_snapshot_seal();

create function public.require_remediation_submission_evidence_sealed() returns trigger
language plpgsql
as $$
declare
    is_sealed boolean;
begin
    select submission.evidence_snapshots_sealed_at is not null
      into is_sealed
      from public.remediation_submission submission
     where submission.tenant_id = NEW.tenant_id and submission.id = NEW.id;
    if not found or is_sealed is distinct from true then
        raise exception 'remediation submission cannot commit before its evidence snapshots are sealed';
    end if;
    return null;
end;
$$;

create constraint trigger remediation_submission_requires_evidence_seal
    after insert on public.remediation_submission
    deferrable initially deferred
    for each row execute function public.require_remediation_submission_evidence_sealed();

create function public.validate_submission_evidence_snapshot() returns trigger
language plpgsql
as $$
declare
    evidence_row record;
begin
    select ei.file_id, btrim(ei.sha256) as evidence_sha256, ei.source_kind, ei.state as evidence_state,
           ei.verification_level, es.finding_id, es.round_no, es.owner_member_id, es.state as set_state,
           fo.owner_member_id as file_owner_member_id, fo.owner_actor_id as file_owner_actor_id,
           fo.checksum as file_checksum, fo.storage_version as file_storage_version,
           fo.state as file_state, fo.verified_at as file_verified_at, fl.revoked_at
      into evidence_row
      from public.evidence_item ei
      join public.evidence_set es
        on es.tenant_id = ei.tenant_id and es.id = ei.evidence_set_id
      join public.file_object fo
        on fo.tenant_id = ei.tenant_id and fo.id = ei.file_id
      join public.file_link fl
        on fl.tenant_id = NEW.tenant_id and fl.id = NEW.file_link_id
       and fl.file_id = NEW.file_id and fl.resource_type = NEW.resource_type
       and fl.resource_id = NEW.resource_id and fl.purpose = NEW.purpose
     where ei.tenant_id = NEW.tenant_id
       and ei.id = NEW.evidence_item_id
       and ei.evidence_set_id = NEW.evidence_set_id
       and es.finding_id = NEW.finding_id
       and es.round_no = NEW.round_no
       and es.owner_member_id = NEW.submitted_by;

    if not found then
        raise exception 'submission evidence snapshot source rows were not found';
    end if;
    if evidence_row.source_kind <> 'FILE'
       or evidence_row.evidence_state <> 'READY'
       or evidence_row.verification_level <> 'CONTENT'
       or evidence_row.set_state <> 'READY'
       or evidence_row.file_state <> 'READY'
       or evidence_row.file_verified_at is null
       or evidence_row.file_id is distinct from NEW.file_id
       or evidence_row.evidence_sha256 is distinct from NEW.sha256
       or evidence_row.file_checksum is distinct from NEW.sha256
       or evidence_row.file_storage_version is distinct from NEW.storage_version
       or evidence_row.file_owner_member_id is distinct from NEW.owner_member_id
       or evidence_row.file_owner_actor_id is distinct from NEW.owner_actor_id
       or NEW.owner_member_id is distinct from NEW.submitted_by
       or NEW.resource_id is distinct from NEW.evidence_item_id
       or evidence_row.revoked_at is not null then
        raise exception 'submission evidence snapshot does not match verified source rows';
    end if;
    return NEW;
end;
$$;

create trigger remediation_submission_evidence_validate_source
    before insert on public.remediation_submission_evidence
    for each row execute function public.validate_submission_evidence_snapshot();

create function public.reject_submission_evidence_mutation() returns trigger
language plpgsql
as $$
begin
    raise exception 'remediation submission evidence snapshots are immutable';
end;
$$;

create trigger remediation_submission_evidence_append_only
    before update or delete on public.remediation_submission_evidence
    for each row execute function public.reject_submission_evidence_mutation();

create function public.reject_submission_evidence_rebinding() returns trigger
language plpgsql
as $$
begin
    if exists (
        select 1 from public.remediation_submission_evidence snapshot
         where snapshot.tenant_id = OLD.tenant_id and snapshot.evidence_item_id = OLD.id
    ) and (
        NEW.tenant_id is distinct from OLD.tenant_id
        or NEW.id is distinct from OLD.id
        or NEW.evidence_set_id is distinct from OLD.evidence_set_id
        or NEW.source_kind is distinct from OLD.source_kind
        or NEW.file_id is distinct from OLD.file_id
        or NEW.sha256 is distinct from OLD.sha256
    ) then
        raise exception 'submitted evidence item binding is immutable';
    end if;
    return NEW;
end;
$$;

create trigger evidence_item_submission_binding_immutable
    before update on public.evidence_item
    for each row execute function public.reject_submission_evidence_rebinding();

create function public.reject_file_link_rebinding() returns trigger
language plpgsql
as $$
begin
    if NEW.tenant_id is distinct from OLD.tenant_id
       or NEW.id is distinct from OLD.id
       or NEW.file_id is distinct from OLD.file_id
       or NEW.resource_type is distinct from OLD.resource_type
       or NEW.resource_id is distinct from OLD.resource_id
       or NEW.purpose is distinct from OLD.purpose
       or NEW.linked_by_actor_id is distinct from OLD.linked_by_actor_id
       or NEW.linked_at is distinct from OLD.linked_at then
        raise exception 'file link identity is immutable; create a new link instead';
    end if;
    if OLD.revoked_at is not null and (
        NEW.revoked_at is distinct from OLD.revoked_at
        or NEW.revoked_by_actor_id is distinct from OLD.revoked_by_actor_id
    ) then
        raise exception 'a revoked file link cannot be reactivated or rewritten';
    end if;
    if OLD.revoked_at is null and NEW.revoked_at is null
       and NEW.revoked_by_actor_id is distinct from OLD.revoked_by_actor_id then
        raise exception 'file link revocation actor requires a one-way revocation';
    end if;
    return NEW;
end;
$$;

create trigger file_link_identity_immutable
    before update on public.file_link
    for each row execute function public.reject_file_link_rebinding();

create function public.reject_file_object_submission_version_rewrite() returns trigger
language plpgsql
as $$
begin
    if NEW.tenant_id is distinct from OLD.tenant_id
       or NEW.id is distinct from OLD.id
       or NEW.object_key is distinct from OLD.object_key
       or NEW.owner_member_id is distinct from OLD.owner_member_id
       or NEW.owner_actor_id is distinct from OLD.owner_actor_id then
        raise exception 'file object identity is immutable';
    end if;
    if exists (
        select 1 from public.remediation_submission_evidence snapshot
         where snapshot.tenant_id = OLD.tenant_id and snapshot.file_id = OLD.id
    ) and (
        NEW.checksum is distinct from OLD.checksum
        or NEW.storage_version is distinct from OLD.storage_version
    ) then
        raise exception 'a submitted file object version is immutable';
    end if;
    return NEW;
end;
$$;

create trigger file_object_submission_version_immutable
    before update on public.file_object
    for each row execute function public.reject_file_object_submission_version_rewrite();

create index file_object_owner_state_idx
    on public.file_object (tenant_id, owner_member_id, state, id);
create index file_link_resource_lookup_idx
    on public.file_link (tenant_id, resource_type, resource_id, purpose, file_id)
    where revoked_at is null;
create unique index file_link_active_identity_key
    on public.file_link (tenant_id, file_id, resource_type, resource_id, purpose)
    where revoked_at is null;

alter table public.file_object enable row level security;
alter table public.file_object force row level security;
create policy file_object_tenant_isolation on public.file_object
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table public.file_link enable row level security;
alter table public.file_link force row level security;
create policy file_link_tenant_isolation on public.file_link
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table public.remediation_submission_evidence enable row level security;
alter table public.remediation_submission_evidence force row level security;
create policy remediation_submission_evidence_tenant_isolation on public.remediation_submission_evidence
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

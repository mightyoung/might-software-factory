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

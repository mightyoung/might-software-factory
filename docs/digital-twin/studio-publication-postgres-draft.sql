-- Design draft only. Outside Flyway; not applied, not an operator runbook.
-- Existing tenant, tenant_member and audit_event tables are required.
-- This file intentionally grants no privileges and seeds no permissions.

create table public.studio_publication_stream (
    tenant_id uuid not null references public.tenant(id),
    screen_key varchar(64) not null check (screen_key ~ '^[a-z][a-z0-9_-]{0,63}$'),
    stream_id uuid not null,
    current_version bigint check (current_version > 0),
    primary key (tenant_id, screen_key),
    unique (tenant_id, stream_id)
);

create table public.studio_published_version (
    tenant_id uuid not null,
    screen_key varchar(64) not null,
    version bigint not null check (version > 0),
    canonical_bytes bytea not null
        check (octet_length(canonical_bytes) between 1 and 262144),
    canonical_sha256 varchar(64) not null
        check (canonical_sha256 ~ '^[0-9a-f]{64}$'),
    publisher_member_id uuid not null,
    audit_event_id uuid not null,
    published_at timestamptz not null,
    primary key (tenant_id, screen_key, version),
    unique (tenant_id, audit_event_id),
    foreign key (tenant_id, screen_key)
        references public.studio_publication_stream(tenant_id, screen_key),
    foreign key (tenant_id, publisher_member_id)
        references public.tenant_member(tenant_id, id),
    foreign key (tenant_id, audit_event_id)
        references public.audit_event(tenant_id, id) deferrable initially deferred
);

alter table public.studio_publication_stream add constraint studio_current_points_to_own_published_version
    foreign key (tenant_id, screen_key, current_version)
    references public.studio_published_version(tenant_id, screen_key, version);

create function public.reject_studio_published_version_mutation() returns trigger
language plpgsql as $$
begin
    raise exception 'studio publication history is append-only' using errcode = '23514';
end;
$$;

create trigger studio_published_version_append_only
    before update or delete on public.studio_published_version
    for each row execute function public.reject_studio_published_version_mutation();

alter table public.studio_publication_stream enable row level security;
alter table public.studio_publication_stream force row level security;
create policy studio_publication_stream_tenant on public.studio_publication_stream
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table public.studio_published_version enable row level security;
alter table public.studio_published_version force row level security;
create policy studio_published_version_tenant on public.studio_published_version
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

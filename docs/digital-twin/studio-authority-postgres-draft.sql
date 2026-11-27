-- Review-only design. Outside Flyway; no seeds, grants, or database execution.
-- Dedicated positive member assignments, not existing roles, scope_rule, or organization grants.
-- These two assignment stores are the proposed authority model, not a fallback source.

create table public.studio_screen (
    tenant_id uuid not null references public.tenant(id) on delete restrict,
    screen_key varchar(64) not null check (screen_key ~ '^[a-z][a-z0-9_-]{0,63}$'),
    retired_at timestamptz,
    primary key (tenant_id, screen_key)
);

create table public.studio_tenant_scene_admin (
    tenant_id uuid not null,
    member_id uuid not null,
    primary key (tenant_id, member_id),
    foreign key (tenant_id, member_id) references public.tenant_member(tenant_id, id) on delete restrict
);

create table public.studio_screen_player_reader (
    tenant_id uuid not null,
    screen_key varchar(64) not null,
    member_id uuid not null,
    primary key (tenant_id, screen_key, member_id),
    foreign key (tenant_id, screen_key) references public.studio_screen(tenant_id, screen_key) on delete restrict,
    foreign key (tenant_id, member_id) references public.tenant_member(tenant_id, id) on delete restrict
);

alter table public.studio_screen enable row level security;
alter table public.studio_screen force row level security;
create policy studio_screen_same_tenant on public.studio_screen
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table public.studio_tenant_scene_admin enable row level security;
alter table public.studio_tenant_scene_admin force row level security;
create policy studio_scene_admin_same_tenant on public.studio_tenant_scene_admin
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table public.studio_screen_player_reader enable row level security;
alter table public.studio_screen_player_reader force row level security;
create policy studio_player_reader_same_tenant on public.studio_screen_player_reader
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- In the joint future schema: the publication stream references the screen registry,
-- not the other way around. This dependency does not create a stream when granting read.
-- alter table public.studio_publication_stream
--     add foreign key (tenant_id, screen_key) references public.studio_screen(tenant_id, screen_key) on delete restrict;

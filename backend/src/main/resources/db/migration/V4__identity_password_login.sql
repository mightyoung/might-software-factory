-- Local password login storage. No accounts, principals, credentials, passwords, or grants are seeded.
create unique index account_login_key_normalized_key
    on public.account (lower(btrim(login_key)));

create table public.principal (
    id uuid primary key,
    kind varchar(24) not null check (kind in ('HUMAN', 'SERVICE', 'AGENT')),
    account_id uuid references public.account(id) on delete restrict,
    status varchar(24) not null check (status in ('ACTIVE', 'DISABLED')),
    constraint principal_kind_account_shape check (
        (kind = 'HUMAN' and account_id is not null)
        or (kind in ('SERVICE', 'AGENT') and account_id is null)
    )
);

create unique index principal_human_account_key
    on public.principal (account_id)
    where kind = 'HUMAN';

create table public.credential (
    account_id uuid not null references public.account(id) on delete restrict,
    kind varchar(24) not null check (kind = 'PASSWORD'),
    secret_hash text not null check (length(secret_hash) between 20 and 512),
    changed_at timestamptz not null,
    constraint credential_pkey primary key (account_id, kind)
);

-- Login must select one tenant before tenant-scoped member rows can be read under FORCE RLS.
-- This policy permits SELECT of a tenant matching only the transaction-local login selector.
create policy tenant_login_lookup on public.tenant
    for select
    using (code = nullif(current_setting('app.login_tenant_code', true), ''));

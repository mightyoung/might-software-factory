-- Source-only identity lifecycle expansion. No accounts, keys, contacts, definitions or grants are seeded.
-- These global identity/capability tables deliberately do not pretend to have tenant RLS.
alter table public.scope_rule drop constraint scope_rule_kind_check;
alter table public.scope_rule add constraint scope_rule_kind_check
    check (kind in ('SELF','DEPT','DEPT_TREE','EXPLICIT','RELATION','IDENTITY_TENANT'));

create function public.guard_identity_tenant_scope() returns trigger language plpgsql as $$
begin
    if tg_op='UPDATE' and (old.kind='IDENTITY_TENANT' or new.kind='IDENTITY_TENANT')
       and (new.kind is distinct from old.kind or new.tenant_id is distinct from old.tenant_id or new.id is distinct from old.id) then
        raise exception 'identity tenant scope identity is immutable' using errcode='23514';
    end if;
    if new.kind='IDENTITY_TENANT' and new.definition<>'{}'::jsonb then
        raise exception 'identity tenant scope has no organization definition' using errcode='23514';
    end if;
    return new;
end $$;
create trigger guard_identity_tenant_scope before insert or update on public.scope_rule
    for each row execute function public.guard_identity_tenant_scope();

create function public.guard_identity_tenant_scope_org() returns trigger language plpgsql as $$
begin
    if exists(select 1 from public.scope_rule s where s.tenant_id=new.tenant_id and s.id=new.scope_rule_id and s.kind='IDENTITY_TENANT') then
        raise exception 'identity tenant scopes cannot have organization rows' using errcode='23514';
    end if;
    return new;
end $$;
create trigger guard_identity_tenant_scope_org before insert or update on public.scope_org
    for each row execute function public.guard_identity_tenant_scope_org();

create function public.guard_identity_invitation_permission() returns trigger language plpgsql as $$
begin
    if tg_op='DELETE' then
        if old.code in ('identity.invitation.create','identity.invitation.revoke') then
            raise exception 'identity permission definitions are immutable' using errcode='23514';
        end if;
        return old;
    end if;
    if tg_op='UPDATE' and (old.code in ('identity.invitation.create','identity.invitation.revoke')
          or new.code in ('identity.invitation.create','identity.invitation.revoke')) and new is distinct from old then
        raise exception 'reserved identity permission tuples require fresh definitions' using errcode='23514';
    end if;
    if new.code in ('identity.invitation.create','identity.invitation.revoke') and not (
        (new.code='identity.invitation.create' and new.resource_type='account-invitation' and new.action='create')
        or (new.code='identity.invitation.revoke' and new.resource_type='account-invitation' and new.action='revoke')) then
        raise exception 'invalid identity invitation permission tuple' using errcode='23514';
    end if;
    return new;
end $$;
create trigger guard_identity_invitation_permission before insert or update or delete on public.permission
    for each row execute function public.guard_identity_invitation_permission();

create function public.guard_identity_invitation_role_permission() returns trigger language plpgsql as $$
declare scope_kind text; permission_code text; resource_kind text; permission_action text;
begin
    select s.kind into scope_kind from public.scope_rule s where s.tenant_id=new.tenant_id and s.id=new.scope_rule_id;
    select p.code,p.resource_type,p.action into permission_code,resource_kind,permission_action from public.permission p where p.id=new.permission_id;
    if scope_kind='IDENTITY_TENANT' or permission_code in ('identity.invitation.create','identity.invitation.revoke') then
        if scope_kind is distinct from 'IDENTITY_TENANT' or not (
            (permission_code='identity.invitation.create' and resource_kind='account-invitation' and permission_action='create')
            or (permission_code='identity.invitation.revoke' and resource_kind='account-invitation' and permission_action='revoke')) then
            raise exception 'identity invitation permission requires its exact tenant capability scope' using errcode='23514';
        end if;
    end if;
    return new;
end $$;
create trigger guard_identity_invitation_role_permission before insert or update on public.role_permission
    for each row execute function public.guard_identity_invitation_role_permission();

-- Existing definitions cannot acquire new meaning through a later permission rename.
do $$ begin
    if exists(select 1 from public.permission p where p.code in ('identity.invitation.create','identity.invitation.revoke') and not (
        (p.code='identity.invitation.create' and p.resource_type='account-invitation' and p.action='create')
        or (p.code='identity.invitation.revoke' and p.resource_type='account-invitation' and p.action='revoke'))) then
        raise exception 'existing reserved identity permission tuple requires explicit review' using errcode='23514';
    end if;
end $$;

create table public.account_recovery_contact (
    id uuid primary key,
    account_id uuid not null references public.account(id) on delete restrict,
    channel varchar(24) not null check(channel='EMAIL'),
    destination varchar(320) not null check(length(destination) between 1 and 320),
    status varchar(16) not null check(status in ('ACTIVE','REVOKED')),
    version bigint not null check(version>=1),
    verified_at timestamptz not null,
    verified_by_challenge_id uuid not null
);
create unique index account_recovery_contact_active_channel_key on public.account_recovery_contact(account_id,channel) where status='ACTIVE';
create index account_recovery_contact_account_idx on public.account_recovery_contact(account_id,id);

create table public.identity_credential_challenge (
    id uuid primary key,
    purpose varchar(32) not null check(purpose in ('NEW_ACCOUNT','EXISTING_ACCOUNT_MEMBERSHIP','PASSWORD_RESET')),
    target_account_id uuid references public.account(id) on delete restrict,
    target_tenant_id uuid references public.tenant(id) on delete restrict,
    issuer_account_id uuid references public.account(id) on delete restrict,
    issuer_actor_id uuid references public.principal(id) on delete restrict,
    issuer_tenant_id uuid,
    issuer_member_id uuid,
    issuer_credential_version bigint check(issuer_credential_version>=1),
    issuer_tenant_epoch bigint check(issuer_tenant_epoch>=0),
    issuer_member_epoch bigint check(issuer_member_epoch>=0),
    intended_login varchar(200),
    display_name varchar(200),
    channel varchar(24) not null check(channel='EMAIL'),
    destination varchar(320) not null check(length(destination) between 1 and 320),
    destination_digest varchar(64) not null check(destination_digest~'^[0-9a-f]{64}$'),
    recovery_contact_id uuid references public.account_recovery_contact(id) on delete restrict,
    issued_contact_version bigint check(issued_contact_version>=1),
    issued_credential_version bigint check(issued_credential_version>=1),
    secret_digest varchar(64) not null check(secret_digest~'^[0-9a-f]{64}$'),
    state varchar(16) not null check(state in ('ISSUED','CONSUMED','REVOKED','EXPIRED')),
    version bigint not null check(version>=1),
    created_at timestamptz not null,
    expires_at timestamptz not null check(expires_at>created_at),
    consumed_receipt_id uuid,
    constraint identity_challenge_issuer_member_fkey foreign key(issuer_tenant_id,issuer_member_id)
        references public.tenant_member(tenant_id,id) on delete restrict,
    constraint identity_challenge_purpose_shape check(
        (purpose='NEW_ACCOUNT' and target_account_id is null and target_tenant_id is not null
            and num_nonnulls(issuer_account_id,issuer_actor_id,issuer_tenant_id,issuer_member_id,issuer_credential_version,issuer_tenant_epoch,issuer_member_epoch)=7
            and issuer_tenant_id=target_tenant_id and intended_login is not null and length(intended_login)>0
            and display_name is not null and length(btrim(display_name))>0
            and recovery_contact_id is null and issued_contact_version is null and issued_credential_version is null)
        or (purpose='EXISTING_ACCOUNT_MEMBERSHIP' and target_account_id is not null and target_tenant_id is not null
            and num_nonnulls(issuer_account_id,issuer_actor_id,issuer_tenant_id,issuer_member_id,issuer_credential_version,issuer_tenant_epoch,issuer_member_epoch)=7
            and issuer_tenant_id=target_tenant_id and intended_login is null and display_name is null
            and recovery_contact_id is not null and issued_contact_version is not null and issued_credential_version is null)
        or (purpose='PASSWORD_RESET' and target_account_id is not null and target_tenant_id is null
            and num_nonnulls(issuer_account_id,issuer_actor_id,issuer_tenant_id,issuer_member_id,issuer_credential_version,issuer_tenant_epoch,issuer_member_epoch)=0
            and intended_login is null and display_name is null and recovery_contact_id is not null
            and issued_contact_version is not null and issued_credential_version is not null)),
    constraint identity_challenge_consumed_shape check((state='CONSUMED')=(consumed_receipt_id is not null))
);
alter table public.account_recovery_contact add constraint account_recovery_verified_challenge_fkey
    foreign key(verified_by_challenge_id) references public.identity_credential_challenge(id) on delete restrict;
create index account_recovery_verified_challenge_idx on public.account_recovery_contact(verified_by_challenge_id);
create unique index identity_challenge_live_reset_key on public.identity_credential_challenge(target_account_id)
    where purpose='PASSWORD_RESET' and state='ISSUED';
create unique index identity_challenge_live_new_invite_key on public.identity_credential_challenge(target_tenant_id,intended_login,destination_digest)
    where purpose='NEW_ACCOUNT' and state='ISSUED';
create unique index identity_challenge_live_existing_invite_key on public.identity_credential_challenge(target_tenant_id,target_account_id,destination_digest)
    where purpose='EXISTING_ACCOUNT_MEMBERSHIP' and state='ISSUED';
create index identity_challenge_target_account_idx on public.identity_credential_challenge(target_account_id,created_at);
create index identity_challenge_target_tenant_idx on public.identity_credential_challenge(target_tenant_id,created_at);
create index identity_challenge_issuer_account_idx on public.identity_credential_challenge(issuer_account_id,created_at);
create index identity_challenge_issuer_actor_idx on public.identity_credential_challenge(issuer_actor_id);
create index identity_challenge_issuer_member_idx on public.identity_credential_challenge(issuer_tenant_id,issuer_member_id);
create index identity_challenge_recovery_idx on public.identity_credential_challenge(recovery_contact_id);

create table public.identity_lifecycle_command_receipt (
    id uuid primary key,
    operation varchar(32) not null check(operation in ('ISSUE_INVITATION','REISSUE_INVITATION','REVOKE_INVITATION','CONSUME_INVITATION','CONSUME_RESET')),
    owner_kind varchar(16) not null check(owner_kind in ('SESSION','CAPABILITY')),
    owner_id uuid not null,
    command_key uuid not null,
    challenge_id uuid not null references public.identity_credential_challenge(id) on delete restrict,
    fingerprint_key_id varchar(64) not null check(fingerprint_key_id~'^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'),
    fingerprint varchar(64) not null check(fingerprint~'^[0-9a-f]{64}$'),
    result_account_id uuid references public.account(id) on delete restrict,
    result_actor_id uuid references public.principal(id) on delete restrict,
    result_member_id uuid,
    result_contact_id uuid references public.account_recovery_contact(id) on delete restrict,
    result_challenge_id uuid references public.identity_credential_challenge(id) on delete restrict,
    result_version bigint not null check(result_version>=1),
    result_expires_at timestamptz,
    result_delivery_state varchar(24) check(result_delivery_state in ('PREPARED','DISPATCHING','ACCEPTED','DEFINITELY_NOT_SENT','UNKNOWN')),
    completed_at timestamptz not null,
    unique(operation,owner_kind,owner_id,command_key),
    constraint identity_receipt_owner_shape check(
        (operation in ('CONSUME_INVITATION','CONSUME_RESET') and owner_kind='CAPABILITY' and owner_id=challenge_id
            and result_account_id is not null and result_actor_id is not null and result_delivery_state is null)
        or (operation in ('ISSUE_INVITATION','REISSUE_INVITATION','REVOKE_INVITATION') and owner_kind='SESSION'
            and num_nonnulls(result_account_id,result_actor_id,result_member_id,result_contact_id)=0 and result_delivery_state is not null))
);
alter table public.identity_credential_challenge add constraint identity_challenge_consumed_receipt_fkey
    foreign key(consumed_receipt_id) references public.identity_lifecycle_command_receipt(id) on delete restrict deferrable initially deferred;
create index identity_challenge_consumed_receipt_idx on public.identity_credential_challenge(consumed_receipt_id);
create index identity_receipt_challenge_idx on public.identity_lifecycle_command_receipt(challenge_id,completed_at);
create index identity_receipt_result_account_idx on public.identity_lifecycle_command_receipt(result_account_id);
create index identity_receipt_result_actor_idx on public.identity_lifecycle_command_receipt(result_actor_id);
create index identity_receipt_result_contact_idx on public.identity_lifecycle_command_receipt(result_contact_id);
create index identity_receipt_result_challenge_idx on public.identity_lifecycle_command_receipt(result_challenge_id);

-- One attempt per challenge, append-only state history. No recoverable capability is persisted.
create table public.identity_capability_delivery_attempt (
    id uuid not null,
    challenge_id uuid not null references public.identity_credential_challenge(id) on delete restrict,
    provider_generation varchar(64) not null check(provider_generation~'^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'),
    template_version varchar(64) not null check(template_version~'^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'),
    state varchar(24) not null check(state in ('PREPARED','DISPATCHING','ACCEPTED','DEFINITELY_NOT_SENT','UNKNOWN')),
    version bigint not null check(version>=1),
    origin_instance_id uuid not null,
    created_at timestamptz not null,
    delivery_deadline_at timestamptz not null check(delivery_deadline_at>created_at and delivery_deadline_at<=created_at+interval '6 minutes'),
    occurred_at timestamptz not null check(occurred_at>=created_at),
    primary key(id,version),
    unique(challenge_id,version)
);
create index identity_delivery_challenge_idx on public.identity_capability_delivery_attempt(challenge_id,version desc);
create index identity_delivery_deadline_idx on public.identity_capability_delivery_attempt(delivery_deadline_at,id) where state in ('PREPARED','DISPATCHING');

create table public.identity_lifecycle_event (
    id uuid primary key,
    challenge_id uuid references public.identity_credential_challenge(id) on delete restrict,
    operation varchar(32) not null check(operation in ('ISSUE_INVITATION','REISSUE_INVITATION','REVOKE_INVITATION','REQUEST_RESET','CONSUME_INVITATION','CONSUME_RESET','DELIVERY_CHECKPOINT')),
    result varchar(32) not null check(result in ('SUCCESS','REJECTED','UNKNOWN','ACCEPTED','DEFINITELY_NOT_SENT')),
    reason varchar(64) check(reason~'^[A-Z][A-Z0-9_]{0,63}$'),
    initiator_kind varchar(16) not null check(initiator_kind in ('SESSION','CAPABILITY','ANONYMOUS')),
    tenant_id uuid references public.tenant(id) on delete restrict,
    subject_account_id uuid references public.account(id) on delete restrict,
    subject_actor_id uuid references public.principal(id) on delete restrict,
    initiator_account_id uuid references public.account(id) on delete restrict,
    initiator_actor_id uuid references public.principal(id) on delete restrict,
    initiator_member_id uuid,
    selector_digest varchar(64) check(selector_digest~'^[0-9a-f]{64}$'),
    trace_id uuid not null,
    policy_version varchar(64) not null check(policy_version='identity-lifecycle-v1'),
    occurred_at timestamptz not null,
    foreign key(tenant_id,initiator_member_id) references public.tenant_member(tenant_id,id) on delete restrict,
    constraint identity_lifecycle_event_provenance check(
        (initiator_kind='SESSION' and num_nonnulls(tenant_id,initiator_account_id,initiator_actor_id,initiator_member_id)=4)
        or (initiator_kind='CAPABILITY' and challenge_id is not null and num_nonnulls(initiator_account_id,initiator_actor_id,initiator_member_id)=0)
        or (initiator_kind='ANONYMOUS' and num_nonnulls(initiator_account_id,initiator_actor_id,initiator_member_id)=0)),
    constraint identity_lifecycle_success_subject check(result<>'SUCCESS' or challenge_id is not null)
);
create index identity_lifecycle_event_challenge_idx on public.identity_lifecycle_event(challenge_id,occurred_at);
create index identity_lifecycle_event_tenant_idx on public.identity_lifecycle_event(tenant_id,occurred_at);
create index identity_lifecycle_event_subject_account_idx on public.identity_lifecycle_event(subject_account_id,occurred_at);
create index identity_lifecycle_event_subject_actor_idx on public.identity_lifecycle_event(subject_actor_id);
create index identity_lifecycle_event_initiator_account_idx on public.identity_lifecycle_event(initiator_account_id);
create index identity_lifecycle_event_initiator_actor_idx on public.identity_lifecycle_event(initiator_actor_id);
create index identity_lifecycle_event_initiator_member_idx on public.identity_lifecycle_event(tenant_id,initiator_member_id);

create table public.identity_lifecycle_rate_bucket (
    selector_digest varchar(64) not null check(selector_digest~'^[0-9a-f]{64}$'),
    window_start timestamptz not null,
    attempts integer not null check(attempts between 1 and 1000),
    primary key(selector_digest,window_start)
);

create function public.reject_identity_lifecycle_history_mutation() returns trigger language plpgsql as $$
begin raise exception 'identity lifecycle history is append-only' using errcode='23514'; end $$;
create trigger identity_receipt_immutable before update or delete on public.identity_lifecycle_command_receipt
    for each row execute function public.reject_identity_lifecycle_history_mutation();
create trigger identity_delivery_immutable before update or delete on public.identity_capability_delivery_attempt
    for each row execute function public.reject_identity_lifecycle_history_mutation();
create trigger identity_event_immutable before update or delete on public.identity_lifecycle_event
    for each row execute function public.reject_identity_lifecycle_history_mutation();

create function public.guard_identity_challenge() returns trigger language plpgsql as $$
begin
    if tg_op='DELETE' then raise exception 'identity challenges are retained' using errcode='23514'; end if;
    if tg_op='INSERT' then
        if new.state<>'ISSUED' or new.version<>1 or new.consumed_receipt_id is not null then
            raise exception 'identity challenges begin issued' using errcode='23514';
        end if;
        if new.purpose='PASSWORD_RESET' then
            if not exists(select 1 from public.account_recovery_contact c join public.account a on a.id=c.account_id
                where c.id=new.recovery_contact_id and c.account_id=new.target_account_id and c.status='ACTIVE'
                  and c.version=new.issued_contact_version and c.channel=new.channel and c.destination=new.destination
                  and a.status='ACTIVE' and a.credential_version=new.issued_credential_version) then
                raise exception 'reset recovery binding does not match' using errcode='23514';
            end if;
        elsif not exists(select 1 from public.principal p join public.tenant_member m on m.account_id=p.account_id
            where p.id=new.issuer_actor_id and p.kind='HUMAN' and p.account_id=new.issuer_account_id
              and m.tenant_id=new.issuer_tenant_id and m.id=new.issuer_member_id) then
            raise exception 'invitation issuer binding does not match' using errcode='23514';
        end if;
        if new.purpose='EXISTING_ACCOUNT_MEMBERSHIP' and not exists(select 1 from public.account_recovery_contact c
            join public.account a on a.id=c.account_id where c.id=new.recovery_contact_id and c.account_id=new.target_account_id
              and c.status='ACTIVE' and a.status='ACTIVE' and c.version=new.issued_contact_version
              and c.channel=new.channel and c.destination=new.destination) then
            raise exception 'existing recipient verified binding does not match' using errcode='23514';
        end if;
    else
        if (to_jsonb(new)-array['state','version','consumed_receipt_id']) is distinct from
           (to_jsonb(old)-array['state','version','consumed_receipt_id']) or old.state<>'ISSUED'
           or new.state not in ('CONSUMED','REVOKED','EXPIRED') or new.version<>old.version+1 then
            raise exception 'invalid identity challenge transition' using errcode='23514';
        end if;
        if (new.state='EXPIRED' and old.expires_at>statement_timestamp())
            or (new.state='CONSUMED' and old.expires_at<=statement_timestamp()) then
            raise exception 'identity challenge expiry does not match transition' using errcode='23514';
        end if;
    end if;
    return new;
end $$;
create trigger guard_identity_challenge before insert or update or delete on public.identity_credential_challenge
    for each row execute function public.guard_identity_challenge();

create function public.guard_identity_recovery_contact() returns trigger language plpgsql as $$
begin
    if tg_op='DELETE' then raise exception 'verified recovery contacts are retained' using errcode='23514'; end if;
    if tg_op='INSERT' then
        if new.status<>'ACTIVE' or new.version<>1 then raise exception 'invalid new verified contact' using errcode='23514'; end if;
    elsif (to_jsonb(new)-array['status','version']) is distinct from (to_jsonb(old)-array['status','version'])
        or old.status<>'ACTIVE' or new.status<>'REVOKED' or new.version<>old.version+1 then
        raise exception 'verified contact binding is immutable' using errcode='23514';
    end if;
    return new;
end $$;
create trigger guard_identity_recovery_contact before insert or update or delete on public.account_recovery_contact
    for each row execute function public.guard_identity_recovery_contact();

create function public.guard_identity_delivery_transition() returns trigger language plpgsql as $$
declare previous public.identity_capability_delivery_attempt;
begin
    select * into previous from public.identity_capability_delivery_attempt where challenge_id=new.challenge_id order by version desc limit 1;
    if not found then
        if new.version<>1 or new.state<>'PREPARED' or new.occurred_at<>new.created_at then
            raise exception 'invalid first capability delivery state' using errcode='23514';
        end if;
    else
        if new.id<>previous.id or new.provider_generation<>previous.provider_generation or new.template_version<>previous.template_version
           or new.origin_instance_id<>previous.origin_instance_id or new.delivery_deadline_at<>previous.delivery_deadline_at
           or new.created_at<>previous.created_at or new.version<>previous.version+1 or new.occurred_at<previous.occurred_at
           or not ((previous.state='PREPARED' and new.state in ('DISPATCHING','UNKNOWN'))
               or (previous.state='DISPATCHING' and new.state in ('ACCEPTED','DEFINITELY_NOT_SENT','UNKNOWN'))) then
            raise exception 'invalid capability delivery transition' using errcode='23514';
        end if;
    end if;
    if new.state='DISPATCHING' and new.delivery_deadline_at<=statement_timestamp() then
        raise exception 'capability delivery deadline elapsed' using errcode='23514';
    end if;
    return new;
end $$;
create trigger guard_identity_delivery_transition before insert on public.identity_capability_delivery_attempt
    for each row execute function public.guard_identity_delivery_transition();

create function public.check_identity_receipt_binding() returns trigger language plpgsql as $$
declare c public.identity_credential_challenge; next_c public.identity_credential_challenge;
begin
    select * into c from public.identity_credential_challenge where id=new.challenge_id;
    if c.id is null then raise exception 'missing identity challenge' using errcode='23514'; end if;
    if new.owner_kind='SESSION' and new.operation<>'REVOKE_INVITATION' and new.owner_id is distinct from c.issuer_actor_id then
        raise exception 'identity command owner does not match issuer' using errcode='23514';
    end if;
    if new.operation='ISSUE_INVITATION' then
        if c.purpose='PASSWORD_RESET' or new.result_challenge_id is distinct from c.id or new.result_version<>1
           or new.result_expires_at is distinct from c.expires_at then raise exception 'invalid invitation issuance receipt' using errcode='23514'; end if;
    elsif new.operation='REISSUE_INVITATION' then
        select * into next_c from public.identity_credential_challenge where id=new.result_challenge_id;
        if c.state<>'REVOKED' or next_c.id is null or next_c.id=c.id or next_c.state<>'ISSUED'
           or next_c.purpose<>c.purpose or next_c.target_account_id is distinct from c.target_account_id
           or next_c.target_tenant_id is distinct from c.target_tenant_id or next_c.issuer_account_id is distinct from c.issuer_account_id
           or next_c.issuer_actor_id is distinct from c.issuer_actor_id
           or next_c.issuer_member_id is distinct from c.issuer_member_id or next_c.intended_login is distinct from c.intended_login
           or next_c.display_name is distinct from c.display_name or next_c.channel<>c.channel or next_c.destination<>c.destination
           or new.result_version<>1 or new.result_expires_at is distinct from next_c.expires_at then
            raise exception 'invalid invitation reissue receipt' using errcode='23514'; end if;
    elsif new.operation='REVOKE_INVITATION' then
        if c.state<>'REVOKED' or new.result_version<>c.version then raise exception 'invalid invitation revocation receipt' using errcode='23514'; end if;
    else
        if c.state<>'CONSUMED' or c.consumed_receipt_id is distinct from new.id
           or (new.operation='CONSUME_RESET')<>(c.purpose='PASSWORD_RESET')
           or not exists(select 1 from public.principal p where p.id=new.result_actor_id and p.kind='HUMAN' and p.account_id=new.result_account_id)
           or (c.purpose<>'NEW_ACCOUNT' and new.result_account_id is distinct from c.target_account_id) then
            raise exception 'invalid capability consume receipt' using errcode='23514';
        end if;
        if c.purpose='PASSWORD_RESET' and (new.result_member_id is not null or new.result_contact_id is distinct from c.recovery_contact_id
            or new.result_version<>c.issued_credential_version+1) then raise exception 'invalid reset result binding' using errcode='23514'; end if;
        if c.purpose='PASSWORD_RESET' and not exists(select 1 from public.account a join public.credential p on p.account_id=a.id
            where a.id=new.result_account_id and a.status='ACTIVE' and a.credential_version=new.result_version
              and p.kind='PASSWORD' and p.changed_at=new.completed_at) then
            raise exception 'reset receipt requires its credential generation write' using errcode='23514';
        end if;
        if c.purpose in ('NEW_ACCOUNT','EXISTING_ACCOUNT_MEMBERSHIP') and not exists(select 1 from public.tenant_member m
            where m.tenant_id=c.target_tenant_id and m.id=new.result_member_id and m.account_id=new.result_account_id) then
            raise exception 'invalid invitation result member' using errcode='23514';
        end if;
        if c.purpose='NEW_ACCOUNT' and not exists(select 1 from public.account_recovery_contact r where r.id=new.result_contact_id
            and r.account_id=new.result_account_id and r.verified_by_challenge_id=c.id and r.channel=c.channel and r.destination=c.destination) then
            raise exception 'invalid new account recovery result' using errcode='23514';
        end if;
        if c.purpose='NEW_ACCOUNT' and not exists(select 1 from public.account a join public.credential p on p.account_id=a.id
            where a.id=new.result_account_id and a.status='ACTIVE' and a.login_key=c.intended_login and a.credential_version=1
              and p.kind='PASSWORD' and p.changed_at=new.completed_at and new.result_version=1) then
            raise exception 'new account receipt requires its account and credential graph' using errcode='23514';
        end if;
    end if;
    return null;
end $$;
create constraint trigger check_identity_receipt_binding after insert on public.identity_lifecycle_command_receipt
    deferrable initially deferred for each row execute function public.check_identity_receipt_binding();

create function public.check_identity_consumed_challenge() returns trigger language plpgsql as $$
begin
    if new.state='CONSUMED' and not exists(select 1 from public.identity_lifecycle_command_receipt r
        where r.id=new.consumed_receipt_id and r.challenge_id=new.id and r.owner_kind='CAPABILITY' and r.owner_id=new.id
          and r.operation=case when new.purpose='PASSWORD_RESET' then 'CONSUME_RESET' else 'CONSUME_INVITATION' end) then
        raise exception 'consumed challenge requires its exact completed receipt' using errcode='23514';
    end if;
    return null;
end $$;
create constraint trigger check_identity_consumed_challenge after update on public.identity_credential_challenge
    deferrable initially deferred for each row execute function public.check_identity_consumed_challenge();

create function public.check_identity_recovery_provenance() returns trigger language plpgsql as $$
begin
    if not exists(select 1 from public.identity_credential_challenge c join public.identity_lifecycle_command_receipt r on r.id=c.consumed_receipt_id
        where c.id=new.verified_by_challenge_id and c.purpose='NEW_ACCOUNT' and c.state='CONSUMED'
          and r.result_account_id=new.account_id and r.result_contact_id=new.id and c.channel=new.channel and c.destination=new.destination) then
        raise exception 'verified contact requires exact invitation consumption' using errcode='23514';
    end if;
    return null;
end $$;
create constraint trigger check_identity_recovery_provenance after insert on public.account_recovery_contact
    deferrable initially deferred for each row execute function public.check_identity_recovery_provenance();

create function public.guard_identity_rate_bucket() returns trigger language plpgsql as $$
begin
    if tg_op='DELETE' then raise exception 'identity rate observations are retained' using errcode='23514'; end if;
    if tg_op='INSERT' then
        if new.attempts<>1 then raise exception 'identity rate window begins at one' using errcode='23514'; end if;
    elsif new.selector_digest<>old.selector_digest or new.window_start<>old.window_start or new.attempts<>old.attempts+1 then
        raise exception 'identity rate counters only advance' using errcode='23514';
    end if;
    return new;
end $$;
create trigger guard_identity_rate_bucket before insert or update or delete on public.identity_lifecycle_rate_bucket
    for each row execute function public.guard_identity_rate_bucket();

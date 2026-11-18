-- CANDIDATE SOURCE ONLY. Not installed, parsed by PostgreSQL, or approved for execution.
-- Reserved V20; V18 must be integrated/applied before V19/V20. No historical source edits.
-- Global identity tables: no invented tenant RLS; authorization is enforced by owning DAL,
-- current request proof, pinned managed transactions and the global security writer guard.

create table public.session_authority_activation (
    singleton boolean primary key default true check(singleton),
    state varchar(16) not null check(state in ('OFF','ENFORCING')),
    version bigint not null check(version in (0,1)),
    activated_at timestamptz,
    activated_by_database_role name,
    activation_event_id uuid,
    activation_evidence_sha256 varchar(64),
    check ((state='OFF' and version=0 and num_nonnulls(activated_at,activated_by_database_role,activation_event_id,activation_evidence_sha256)=0)
        or (state='ENFORCING' and version=1 and num_nonnulls(activated_at,activated_by_database_role,activation_event_id,activation_evidence_sha256)=4
                and activation_evidence_sha256 ~ '^[0-9a-f]{64}$'))
);
-- Only an OFF bootstrap row. This proposed migration does not activate enforcement.
insert into public.session_authority_activation(singleton,state,version) values(true,'OFF',0);

create table public.account_session_authority (
    primary_id varchar(36) primary key check(primary_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
    account_id uuid not null references public.account(id) on delete restrict,
    binding_kind varchar(24) not null check(binding_kind in ('CURRENT_REQUEST','REMOTE_ABSENCE','KNOWN_LOGOUT')),
    session_key_id varchar(64) not null check(session_key_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'),
    session_digest varchar(64) not null check(session_digest ~ '^[0-9a-f]{64}$'),
    bound_credential_version bigint check(bound_credential_version>=1),
    authenticated_at timestamptz,
    absolute_expires_at timestamptz,
    state varchar(16) not null check(state in ('ACTIVE','REVOKED')),
    version bigint not null check(version>=1),
    created_at timestamptz not null,
    created_event_id uuid not null,
    revoked_at timestamptz,
    revocation_event_id uuid,
    unique(primary_id,account_id),
    check ((binding_kind='CURRENT_REQUEST' and num_nonnulls(bound_credential_version,authenticated_at,absolute_expires_at)=3
                and authenticated_at<=created_at and absolute_expires_at>created_at)
        or (binding_kind in ('REMOTE_ABSENCE','KNOWN_LOGOUT') and state='REVOKED'
                and num_nonnulls(bound_credential_version,authenticated_at,absolute_expires_at)=0)),
    check ((state='ACTIVE' and revoked_at is null and revocation_event_id is null)
        or (state='REVOKED' and revoked_at is not null and revoked_at>=created_at and revocation_event_id is not null))
);
-- No FK to spring_session: framework cleanup must never erase the fence.
create index account_session_authority_owner_idx on public.account_session_authority(account_id,primary_id);
create unique index account_session_authority_digest_key on public.account_session_authority(session_key_id,session_digest);

create table public.sensitive_reauth_proof (
    id uuid primary key,
    account_id uuid not null references public.account(id) on delete restrict,
    actor_id uuid not null references public.principal(id) on delete restrict,
    source_primary_id varchar(36) not null,
    issued_credential_version bigint not null check(issued_credential_version>=1),
    action varchar(32) not null check(action in ('SESSION_REVOKE','RECOVERY_CONTACT_CHANGE')),
    target_primary_id varchar(36),
    expected_authority_version bigint,
    expected_contact_id uuid references public.account_recovery_contact(id) on delete restrict,
    expected_contact_version bigint,
    destination_key_id varchar(64),
    destination_digest varchar(64),
    expected_pending_change_id uuid,
    expected_pending_change_version bigint,
    body_key_id varchar(64) not null check(body_key_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'),
    body_digest varchar(64) not null check(body_digest ~ '^[0-9a-f]{64}$'),
    secret_digest varchar(64) not null unique check(secret_digest ~ '^[0-9a-f]{64}$'),
    state varchar(16) not null check(state in ('ISSUED','CONSUMED','REVOKED','EXPIRED')),
    version bigint not null check(version>=1),
    created_at timestamptz not null,
    expires_at timestamptz not null check(expires_at>created_at and expires_at<=created_at+interval '5 minutes'),
    issuance_receipt_id uuid not null unique,
    session_consume_receipt_id uuid,
    contact_consume_receipt_id uuid,
    foreign key(source_primary_id,account_id) references public.account_session_authority(primary_id,account_id) on delete restrict,
    check ((action='SESSION_REVOKE' and target_primary_id is not null and target_primary_id<>source_primary_id
                and target_primary_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                and expected_authority_version is not null and expected_authority_version>=0
                and num_nonnulls(expected_contact_id,expected_contact_version,destination_key_id,destination_digest,
                                 expected_pending_change_id,expected_pending_change_version)=0)
        or (action='RECOVERY_CONTACT_CHANGE' and target_primary_id is null and expected_authority_version is null
                and expected_contact_version is not null and destination_key_id is not null and destination_digest is not null
                and ((expected_contact_id is null and expected_contact_version=0)
                    or (expected_contact_id is not null and expected_contact_version>=1))
                and destination_key_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'
                and destination_digest ~ '^[0-9a-f]{64}$'
                and ((expected_pending_change_id is null and expected_pending_change_version is null)
                    or (expected_pending_change_id is not null and expected_pending_change_version is not null and expected_pending_change_version>=1)))),
    check ((state='CONSUMED' and ((action='SESSION_REVOKE' and session_consume_receipt_id is not null and contact_consume_receipt_id is null)
            or (action='RECOVERY_CONTACT_CHANGE' and contact_consume_receipt_id is not null and session_consume_receipt_id is null)))
        or (state<>'CONSUMED' and num_nonnulls(session_consume_receipt_id,contact_consume_receipt_id)=0))
);
create index sensitive_reauth_owner_idx on public.sensitive_reauth_proof(account_id,source_primary_id,id);
create index sensitive_reauth_pending_idx on public.sensitive_reauth_proof(account_id,source_primary_id,action,expires_at) where state='ISSUED';
-- One live proof for an exact server-canonical action/target/body; expired rows are terminalized before insertion.
create unique index sensitive_reauth_live_target_key on public.sensitive_reauth_proof(account_id,source_primary_id,action,body_key_id,body_digest) where state='ISSUED';

create table public.sensitive_reauth_issuance_receipt (
    id uuid primary key,
    account_id uuid not null references public.account(id) on delete restrict,
    actor_id uuid not null references public.principal(id) on delete restrict,
    source_primary_id varchar(36) not null,
    command_key uuid not null,
    fingerprint_key_id varchar(64) not null check(fingerprint_key_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'),
    fingerprint varchar(64) not null check(fingerprint ~ '^[0-9a-f]{64}$'),
    proof_id uuid not null unique references public.sensitive_reauth_proof(id) on delete restrict deferrable initially deferred,
    expires_at timestamptz not null,
    event_id uuid not null unique,
    completed_at timestamptz not null,
    foreign key(source_primary_id,account_id) references public.account_session_authority(primary_id,account_id) on delete restrict,
    unique(account_id,source_primary_id,command_key)
);

create table public.session_authority_command_receipt (
    id uuid primary key,
    operation varchar(24) not null check(operation in ('REVOKE_SESSION','LOGOUT')),
    account_id uuid not null references public.account(id) on delete restrict,
    actor_id uuid references public.principal(id) on delete restrict,
    source_primary_id varchar(36) not null,
    target_primary_id varchar(36) not null,
    command_key uuid,
    expected_authority_version bigint not null check(expected_authority_version>=0),
    fingerprint_key_id varchar(64),
    fingerprint varchar(64),
    proof_id uuid unique references public.sensitive_reauth_proof(id) on delete restrict,
    result_authority_version bigint not null check(result_authority_version>=1),
    event_id uuid not null unique,
    completed_at timestamptz not null,
    foreign key(source_primary_id,account_id) references public.account_session_authority(primary_id,account_id) on delete restrict,
    foreign key(target_primary_id,account_id) references public.account_session_authority(primary_id,account_id) on delete restrict,
    check ((operation='REVOKE_SESSION' and source_primary_id<>target_primary_id and actor_id is not null
                and num_nonnulls(command_key,fingerprint_key_id,fingerprint,proof_id)=4
                and fingerprint_key_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$' and fingerprint ~ '^[0-9a-f]{64}$')
        or (operation='LOGOUT' and source_primary_id=target_primary_id
                and num_nonnulls(command_key,fingerprint_key_id,fingerprint,proof_id)=0))
);
create unique index session_revoke_command_key on public.session_authority_command_receipt(account_id,source_primary_id,command_key) where operation='REVOKE_SESSION';
create unique index session_logout_lineage_key on public.session_authority_command_receipt(target_primary_id) where operation='LOGOUT';

create table public.recovery_contact_change (
    id uuid primary key,
    account_id uuid not null references public.account(id) on delete restrict,
    actor_id uuid not null references public.principal(id) on delete restrict,
    source_primary_id varchar(36) not null,
    issued_credential_version bigint not null check(issued_credential_version>=1),
    proof_id uuid not null unique references public.sensitive_reauth_proof(id) on delete restrict,
    expected_contact_id uuid references public.account_recovery_contact(id) on delete restrict,
    expected_contact_version bigint not null check(expected_contact_version>=0),
    previous_pending_id uuid references public.recovery_contact_change(id) on delete restrict,
    previous_pending_version bigint,
    channel varchar(24) not null check(channel='EMAIL'),
    destination varchar(320) not null check(length(destination) between 1 and 320),
    destination_key_id varchar(64) not null check(destination_key_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'),
    destination_digest varchar(64) not null check(destination_digest ~ '^[0-9a-f]{64}$'),
    secret_digest varchar(64) not null unique check(secret_digest ~ '^[0-9a-f]{64}$'),
    state varchar(16) not null check(state in ('ISSUED','CONSUMED','REVOKED','EXPIRED')),
    version bigint not null check(version>=1),
    created_at timestamptz not null,
    expires_at timestamptz not null check(expires_at>created_at and expires_at<=created_at+interval '30 minutes'),
    consumed_receipt_id uuid,
    foreign key(source_primary_id,account_id) references public.account_session_authority(primary_id,account_id) on delete restrict,
    check ((expected_contact_id is null and expected_contact_version=0) or (expected_contact_id is not null and expected_contact_version>=1)),
    check ((previous_pending_id is null and previous_pending_version is null) or (previous_pending_id is not null and previous_pending_version is not null and previous_pending_version>=1)),
    check ((state='CONSUMED')=(consumed_receipt_id is not null))
);
create unique index recovery_contact_change_pending_key on public.recovery_contact_change(account_id) where state='ISSUED';
create index recovery_contact_change_source_idx on public.recovery_contact_change(account_id,source_primary_id,id);

create table public.recovery_contact_command_receipt (
    id uuid primary key,
    operation varchar(32) not null check(operation in ('REQUEST_CONTACT_CHANGE','CONSUME_CONTACT_CHANGE')),
    account_id uuid not null references public.account(id) on delete restrict,
    actor_id uuid not null references public.principal(id) on delete restrict,
    source_primary_id varchar(36) not null,
    command_key uuid not null,
    challenge_id uuid not null references public.recovery_contact_change(id) on delete restrict,
    fingerprint_key_id varchar(64) not null check(fingerprint_key_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'),
    fingerprint varchar(64) not null check(fingerprint ~ '^[0-9a-f]{64}$'),
    proof_id uuid unique references public.sensitive_reauth_proof(id) on delete restrict,
    result_contact_id uuid references public.account_recovery_contact(id) on delete restrict,
    result_challenge_version bigint not null check(result_challenge_version>=1),
    event_id uuid not null unique,
    completed_at timestamptz not null,
    foreign key(source_primary_id,account_id) references public.account_session_authority(primary_id,account_id) on delete restrict,
    unique(account_id,source_primary_id,operation,command_key),
    check ((operation='REQUEST_CONTACT_CHANGE' and proof_id is not null and result_contact_id is null and result_challenge_version=1)
        or (operation='CONSUME_CONTACT_CHANGE' and proof_id is null and result_contact_id is not null and result_challenge_version=2))
);
create unique index recovery_contact_consume_once_key on public.recovery_contact_command_receipt(challenge_id) where operation='CONSUME_CONTACT_CHANGE';

create table public.recovery_contact_delivery_attempt (
    id uuid not null,
    challenge_id uuid not null references public.recovery_contact_change(id) on delete restrict,
    provider_generation varchar(64) not null check(provider_generation ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'),
    template_version varchar(64) not null check(template_version ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'),
    state varchar(24) not null check(state in ('PREPARED','DISPATCHING','ACCEPTED','DEFINITELY_NOT_SENT','UNKNOWN')),
    version bigint not null check(version>=1),
    origin_instance_id uuid not null,
    created_at timestamptz not null,
    delivery_deadline_at timestamptz not null check(delivery_deadline_at>created_at and delivery_deadline_at<=created_at+interval '6 minutes'),
    occurred_at timestamptz not null check(occurred_at>=created_at),
    primary key(id,version), unique(challenge_id,version)
);
create index recovery_contact_delivery_due_idx on public.recovery_contact_delivery_attempt(delivery_deadline_at,id) where state in ('PREPARED','DISPATCHING');

create table public.identity_sensitive_event (
    id uuid primary key,
    operation varchar(40) not null check(operation in ('ACTIVATE_SESSION_AUTHORITY','REGISTER_SESSION','REVOKE_SESSION','LOGOUT',
        'ISSUE_REAUTH','REQUEST_CONTACT_CHANGE','CONSUME_CONTACT_CHANGE','CONTACT_DELIVERY_CHECKPOINT')),
    result varchar(24) not null check(result in ('SUCCESS','REJECTED','UNKNOWN','ACCEPTED','DEFINITELY_NOT_SENT')),
    reason varchar(64) check(reason ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    provenance varchar(24) not null check(provenance in ('FRESH_SESSION','KNOWN_LINEAGE','PROVED_SUBJECT','SYSTEM_OBSERVATION','CONTROLLED_ACTIVATION','ANONYMOUS')),
    subject_account_id uuid references public.account(id) on delete restrict,
    actor_id uuid references public.principal(id) on delete restrict,
    source_primary_id varchar(36),
    target_primary_id varchar(36),
    proof_id uuid references public.sensitive_reauth_proof(id) on delete restrict,
    challenge_id uuid references public.recovery_contact_change(id) on delete restrict,
    database_role name,
    trace_id uuid not null,
    policy_version varchar(64) not null check(policy_version='session-sensitive-v1'),
    occurred_at timestamptz not null,
    check ((provenance='FRESH_SESSION' and num_nonnulls(subject_account_id,actor_id,source_primary_id)=3 and database_role is null)
        or (provenance='KNOWN_LINEAGE' and operation='LOGOUT' and subject_account_id is not null and source_primary_id is not null and database_role is null)
        or (provenance='PROVED_SUBJECT' and result in ('REJECTED','UNKNOWN') and subject_account_id is not null and database_role is null)
        or (provenance='SYSTEM_OBSERVATION' and operation='CONTACT_DELIVERY_CHECKPOINT' and subject_account_id is not null and challenge_id is not null and actor_id is null and database_role is null)
        or (provenance='CONTROLLED_ACTIVATION' and operation='ACTIVATE_SESSION_AUTHORITY' and database_role is not null
                and num_nonnulls(subject_account_id,actor_id,source_primary_id,target_primary_id,proof_id,challenge_id)=0)
        or (provenance='ANONYMOUS' and result in ('REJECTED','UNKNOWN')
                and num_nonnulls(subject_account_id,actor_id,source_primary_id,target_primary_id,proof_id,challenge_id,database_role)=0))
);
create index identity_sensitive_event_account_idx on public.identity_sensitive_event(subject_account_id,occurred_at,id);
create index identity_sensitive_event_challenge_idx on public.identity_sensitive_event(challenge_id,occurred_at,id);

alter table public.session_authority_activation add foreign key(activation_event_id) references public.identity_sensitive_event(id) on delete restrict deferrable initially deferred;
alter table public.account_session_authority add foreign key(created_event_id) references public.identity_sensitive_event(id) on delete restrict deferrable initially deferred;
alter table public.account_session_authority add foreign key(revocation_event_id) references public.identity_sensitive_event(id) on delete restrict deferrable initially deferred;
alter table public.sensitive_reauth_proof add foreign key(issuance_receipt_id) references public.sensitive_reauth_issuance_receipt(id) on delete restrict deferrable initially deferred;
alter table public.sensitive_reauth_proof add foreign key(session_consume_receipt_id) references public.session_authority_command_receipt(id) on delete restrict deferrable initially deferred;
alter table public.sensitive_reauth_proof add foreign key(contact_consume_receipt_id) references public.recovery_contact_command_receipt(id) on delete restrict deferrable initially deferred;
alter table public.sensitive_reauth_proof add foreign key(expected_pending_change_id) references public.recovery_contact_change(id) on delete restrict;
alter table public.recovery_contact_change add foreign key(consumed_receipt_id) references public.recovery_contact_command_receipt(id) on delete restrict deferrable initially deferred;
alter table public.sensitive_reauth_issuance_receipt add foreign key(event_id) references public.identity_sensitive_event(id) on delete restrict deferrable initially deferred;
alter table public.session_authority_command_receipt add foreign key(event_id) references public.identity_sensitive_event(id) on delete restrict deferrable initially deferred;
alter table public.recovery_contact_command_receipt add foreign key(event_id) references public.identity_sensitive_event(id) on delete restrict deferrable initially deferred;

-- This lock-presence assertion is defense in depth, NOT a server request/authentication proof.
-- It does not acquire locks. Pinned application markers still prove transaction ownership,
-- xact-lock scope, physical connection and post-guard current authority.
create function public.require_sensitive_global_guard() returns void language plpgsql as $$
begin
    if current_setting('transaction_isolation')<>'read committed' or current_setting('transaction_read_only')<>'off'
       or not exists(select 1 from pg_locks where locktype='advisory' and pid=pg_backend_pid()
            and classid=1297303111::oid and objid=1::oid and objsubid=2 and mode='ExclusiveLock' and granted) then
        raise exception 'managed global security writer required' using errcode='23514';
    end if;
end $$;

create function public.require_sensitive_enforcement() returns void language plpgsql as $$
begin
    if not exists(select 1 from public.session_authority_activation where singleton and state='ENFORCING' and version=1) then
        raise exception 'session authority enforcement is not active' using errcode='23514';
    end if;
end $$;

create function public.reject_sensitive_history_mutation() returns trigger language plpgsql as $$
begin raise exception 'sensitive identity history is retained' using errcode='23514'; end $$;

create function public.guard_session_authority_activation() returns trigger language plpgsql as $$
begin
    if tg_op<>'UPDATE' then raise exception 'activation singleton cannot be inserted or deleted' using errcode='23514'; end if;
    if current_user is distinct from (select pg_get_userbyid(relowner) from pg_class where oid='public.session_authority_activation'::regclass) then
        raise exception 'activation requires the separately approved migration owner' using errcode='23514';
    end if;
    perform public.require_sensitive_global_guard();
    if (to_jsonb(new)-array['state','version','activated_at','activated_by_database_role','activation_event_id','activation_evidence_sha256']) is distinct from
       (to_jsonb(old)-array['state','version','activated_at','activated_by_database_role','activation_event_id','activation_evidence_sha256'])
       or old.state<>'OFF' or new.state<>'ENFORCING' or old.version<>0 or new.version<>1
       or new.activated_at>statement_timestamp() or new.activated_by_database_role is distinct from current_user then
        raise exception 'activation is one way' using errcode='23514';
    end if;
    return new;
end $$;
create trigger guard_session_authority_activation before insert or update or delete on public.session_authority_activation
    for each row execute function public.guard_session_authority_activation();

create function public.guard_account_session_authority() returns trigger language plpgsql as $$
begin
    if tg_op='DELETE' then raise exception 'session authority is retained' using errcode='23514'; end if;
    perform public.require_sensitive_global_guard();
    perform public.require_sensitive_enforcement();
    if tg_op='INSERT' then
        if new.version<>1 or new.created_at>statement_timestamp()
           or (new.state='ACTIVE' and new.binding_kind<>'CURRENT_REQUEST')
           or (new.state='REVOKED' and (new.binding_kind not in ('REMOTE_ABSENCE','KNOWN_LOGOUT')
               or new.revoked_at<>new.created_at or new.revocation_event_id<>new.created_event_id))
           or not exists(select 1 from public.spring_session owned where owned.primary_id=new.primary_id and owned.principal_name=new.account_id::text)
           or (new.state='ACTIVE' and (new.binding_kind<>'CURRENT_REQUEST'
                or not exists(select 1 from public.spring_session s join public.account a on a.id=new.account_id
                    where s.primary_id=new.primary_id and s.principal_name=new.account_id::text
                      and s.expiry_time>floor(extract(epoch from statement_timestamp())*1000)::bigint
                      and a.status='ACTIVE' and a.credential_version=new.bound_credential_version
                      and exists(select 1 from public.principal p where p.account_id=a.id and p.kind='HUMAN' and p.status='ACTIVE')))) then
            raise exception 'invalid initial session authority' using errcode='23514';
        end if;
    else
        if (to_jsonb(new)-array['state','version','revoked_at','revocation_event_id']) is distinct from
           (to_jsonb(old)-array['state','version','revoked_at','revocation_event_id'])
           or old.state<>'ACTIVE' or new.state<>'REVOKED' or new.version<>old.version+1
           or new.revoked_at<old.created_at or new.revoked_at>statement_timestamp() then
            raise exception 'session authority cannot revive or rebind' using errcode='23514';
        end if;
    end if;
    return new;
end $$;
create trigger guard_account_session_authority before insert or update or delete on public.account_session_authority
    for each row execute function public.guard_account_session_authority();

create function public.require_sensitive_source(source_id varchar, owner_id uuid, human_id uuid, credential_generation bigint) returns void language plpgsql as $$
begin
    if not exists(select 1 from public.account_session_authority authority
        join public.account a on a.id=authority.account_id
        join public.principal p on p.account_id=a.id
        join public.spring_session stored on stored.primary_id=authority.primary_id
        where authority.primary_id=source_id and authority.account_id=owner_id and authority.state='ACTIVE'
          and authority.bound_credential_version=credential_generation and authority.absolute_expires_at>statement_timestamp()
          and a.status='ACTIVE' and a.credential_version=credential_generation
          and p.id=human_id and p.kind='HUMAN' and p.status='ACTIVE'
          and stored.principal_name=owner_id::text
          and stored.expiry_time>floor(extract(epoch from statement_timestamp())*1000)::bigint) then
        raise exception 'current persisted source authority required' using errcode='23514';
    end if;
end $$;

create function public.guard_sensitive_reauth_proof() returns trigger language plpgsql as $$
begin
    if tg_op='DELETE' then raise exception 'reauth proof is retained' using errcode='23514'; end if;
    perform public.require_sensitive_global_guard();
    perform public.require_sensitive_enforcement();
    if tg_op='INSERT' then
        if new.state<>'ISSUED' or new.version<>1 or new.expires_at<=statement_timestamp()
           or not exists(select 1 from public.account_session_authority s join public.account a on a.id=s.account_id
                join public.principal p on p.account_id=a.id
                where s.primary_id=new.source_primary_id and s.account_id=new.account_id and s.state='ACTIVE'
                  and a.status='ACTIVE' and a.credential_version=new.issued_credential_version
                  and p.id=new.actor_id and p.kind='HUMAN' and p.status='ACTIVE') then
            raise exception 'invalid initial reauth proof' using errcode='23514';
        end if;
    elsif (to_jsonb(new)-array['state','version','session_consume_receipt_id','contact_consume_receipt_id']) is distinct from
          (to_jsonb(old)-array['state','version','session_consume_receipt_id','contact_consume_receipt_id'])
          or old.state<>'ISSUED' or new.state not in ('CONSUMED','REVOKED','EXPIRED') or new.version<>old.version+1
          or (new.state='CONSUMED' and old.expires_at<=statement_timestamp())
          or (new.state='EXPIRED' and old.expires_at>statement_timestamp()) then
        raise exception 'invalid reauth transition' using errcode='23514';
    end if;
    if new.state in ('ISSUED','CONSUMED') then
        perform public.require_sensitive_source(new.source_primary_id,new.account_id,new.actor_id,new.issued_credential_version);
    end if;
    if tg_op='INSERT' and new.action='SESSION_REVOKE' then
        if not exists(select 1 from public.spring_session s where s.primary_id=new.target_primary_id and s.principal_name=new.account_id::text)
           and not exists(select 1 from public.account_session_authority a where a.primary_id=new.target_primary_id and a.account_id=new.account_id) then
            raise exception 'target ownership must be proved' using errcode='23514';
        end if;
        if (new.expected_authority_version=0 and exists(select 1 from public.account_session_authority where primary_id=new.target_primary_id))
            or (new.expected_authority_version>0 and not exists(select 1 from public.account_session_authority a
                where a.primary_id=new.target_primary_id and a.account_id=new.account_id and a.state='ACTIVE' and a.version=new.expected_authority_version)) then
            raise exception 'target authority version changed' using errcode='23514';
        end if;
    end if;
    if tg_op='INSERT' and new.action='RECOVERY_CONTACT_CHANGE' then
        if (new.expected_contact_id is null and exists(select 1 from public.account_recovery_contact
                where account_id=new.account_id and channel='EMAIL' and status='ACTIVE'))
           or (new.expected_contact_id is not null and not exists(select 1 from public.account_recovery_contact
                where id=new.expected_contact_id and account_id=new.account_id and channel='EMAIL'
                  and status='ACTIVE' and version=new.expected_contact_version)) then
            raise exception 'contact target changed before reauth' using errcode='23514';
        end if;
        -- Structural predecessor, including naturally expired ISSUED rows: this is NOT token liveness.
        -- A fresh explicit request must revoke exactly this frozen id/version before replacement.
        if (new.expected_pending_change_id is null and exists(select 1 from public.recovery_contact_change
                where account_id=new.account_id and state='ISSUED'))
           or (new.expected_pending_change_id is not null and not exists(select 1 from public.recovery_contact_change
                where id=new.expected_pending_change_id and account_id=new.account_id and state='ISSUED'
                  and version=new.expected_pending_change_version)) then
            raise exception 'pending contact target changed before reauth' using errcode='23514';
        end if;
    end if;
    return new;
end $$;
create trigger guard_sensitive_reauth_proof before insert or update or delete on public.sensitive_reauth_proof
    for each row execute function public.guard_sensitive_reauth_proof();

create function public.guard_recovery_contact_change() returns trigger language plpgsql as $$
declare p public.sensitive_reauth_proof;
begin
    if tg_op='DELETE' then raise exception 'contact challenges are retained' using errcode='23514'; end if;
    perform public.require_sensitive_global_guard();
    perform public.require_sensitive_enforcement();
    if tg_op='INSERT' then
        select * into p from public.sensitive_reauth_proof where id=new.proof_id;
        if new.state<>'ISSUED' or new.version<>1 or new.expires_at<=statement_timestamp()
           or p.id is null or p.state<>'CONSUMED' or p.expires_at<=statement_timestamp() or p.action<>'RECOVERY_CONTACT_CHANGE' or p.account_id<>new.account_id or p.actor_id<>new.actor_id
           or p.source_primary_id<>new.source_primary_id or p.issued_credential_version<>new.issued_credential_version
           or p.expected_contact_id is distinct from new.expected_contact_id or p.expected_contact_version<>new.expected_contact_version
           or p.destination_key_id<>new.destination_key_id or p.destination_digest<>new.destination_digest
           or p.expected_pending_change_id is distinct from new.previous_pending_id
           or p.expected_pending_change_version is distinct from new.previous_pending_version then
            raise exception 'contact proposal does not match bound reauth' using errcode='23514';
        end if;
        perform public.require_sensitive_source(new.source_primary_id,new.account_id,new.actor_id,new.issued_credential_version);
        if new.previous_pending_id is not null and not exists(select 1 from public.recovery_contact_change previous
            where previous.id=new.previous_pending_id and previous.account_id=new.account_id
              and previous.state='REVOKED' and previous.version=new.previous_pending_version+1) then
            raise exception 'pending predecessor changed' using errcode='23514';
        end if;
        if new.expected_contact_id is null then
            if exists(select 1 from public.account_recovery_contact where account_id=new.account_id and channel='EMAIL' and status='ACTIVE') then
                raise exception 'expected contact absence changed' using errcode='23514';
            end if;
        elsif not exists(select 1 from public.account_recovery_contact where id=new.expected_contact_id and account_id=new.account_id
                  and channel='EMAIL' and status='ACTIVE' and version=new.expected_contact_version) then
            raise exception 'expected contact identity changed' using errcode='23514';
        end if;
    elsif (to_jsonb(new)-array['state','version','consumed_receipt_id']) is distinct from (to_jsonb(old)-array['state','version','consumed_receipt_id'])
       or old.state<>'ISSUED' or new.state not in ('CONSUMED','REVOKED','EXPIRED') or new.version<>old.version+1
       or (new.state='CONSUMED' and old.expires_at<=statement_timestamp())
       or (new.state='EXPIRED' and old.expires_at>statement_timestamp()) then
        raise exception 'invalid contact challenge transition' using errcode='23514';
    end if;
    if tg_op='UPDATE' and new.state='CONSUMED' then
        perform public.require_sensitive_source(new.source_primary_id,new.account_id,new.actor_id,new.issued_credential_version);
        if (new.expected_contact_id is null and exists(select 1 from public.account_recovery_contact
                where account_id=new.account_id and channel='EMAIL' and status='ACTIVE'))
           or (new.expected_contact_id is not null and not exists(select 1 from public.account_recovery_contact
                where id=new.expected_contact_id and account_id=new.account_id and channel='EMAIL'
                  and status='ACTIVE' and version=new.expected_contact_version)) then
            raise exception 'contact identity changed before consume' using errcode='23514';
        end if;
    end if;
    return new;
end $$;
create trigger guard_recovery_contact_change before insert or update or delete on public.recovery_contact_change
    for each row execute function public.guard_recovery_contact_change();

create function public.guard_recovery_contact_delivery() returns trigger language plpgsql as $$
declare c public.recovery_contact_change; previous public.recovery_contact_delivery_attempt;
begin
    perform public.require_sensitive_global_guard();
    perform public.require_sensitive_enforcement();
    -- Parent first: serializes append history and challenge terminalization.
    select * into c from public.recovery_contact_change where id=new.challenge_id for update;
    if c.id is null then raise exception 'missing contact delivery challenge' using errcode='23514'; end if;
    select * into previous from public.recovery_contact_delivery_attempt where challenge_id=new.challenge_id order by version desc limit 1;
    if not found then
        if new.version<>1 or new.state<>'PREPARED' or new.occurred_at<>new.created_at or c.state<>'ISSUED' then
            raise exception 'invalid first contact delivery state' using errcode='23514';
        end if;
    elsif (to_jsonb(new)-array['state','version','occurred_at']) is distinct from (to_jsonb(previous)-array['state','version','occurred_at'])
       or new.version<>previous.version+1 or new.occurred_at<previous.occurred_at
       or not ((previous.state='PREPARED' and new.state in ('DISPATCHING','UNKNOWN'))
            or (previous.state='DISPATCHING' and new.state in ('ACCEPTED','DEFINITELY_NOT_SENT','UNKNOWN'))) then
        raise exception 'invalid contact delivery history' using errcode='23514';
    end if;
    if new.state='DISPATCHING' and (new.delivery_deadline_at<=statement_timestamp() or c.state<>'ISSUED') then
        raise exception 'contact dispatch no longer eligible' using errcode='23514';
    end if;
    if new.state in ('ACCEPTED','DEFINITELY_NOT_SENT') and new.delivery_deadline_at<=statement_timestamp() then
        raise exception 'late contact provider result remains unknown' using errcode='23514';
    end if;
    if new.state='UNKNOWN' and new.occurred_at<previous.occurred_at then
        raise exception 'contact delivery observation moved backwards' using errcode='23514';
    end if;
    return new;
end $$;
create trigger guard_recovery_contact_delivery before insert on public.recovery_contact_delivery_attempt
    for each row execute function public.guard_recovery_contact_delivery();

create function public.guard_sensitive_event() returns trigger language plpgsql as $$
begin
    if new.actor_id is not null and not exists(select 1 from public.principal p
        where p.id=new.actor_id and p.account_id=new.subject_account_id and p.kind='HUMAN') then
        raise exception 'sensitive event subject binding invalid' using errcode='23514';
    end if;
    if new.source_primary_id is not null and not exists(select 1 from public.account_session_authority a
        where a.primary_id=new.source_primary_id and a.account_id=new.subject_account_id)
        and not (new.provenance='PROVED_SUBJECT' and exists(select 1 from public.spring_session stored
            where stored.primary_id=new.source_primary_id and stored.principal_name=new.subject_account_id::text)) then
        raise exception 'sensitive event lineage binding invalid' using errcode='23514';
    end if;
    if new.provenance='FRESH_SESSION' and not exists(select 1 from public.principal p join public.account a on a.id=p.account_id
        where p.id=new.actor_id and p.account_id=new.subject_account_id and p.kind='HUMAN' and p.status='ACTIVE' and a.status='ACTIVE') then
        raise exception 'fresh event cannot fabricate active provenance' using errcode='23514';
    end if;
    if new.result='SUCCESS' or new.operation='CONTACT_DELIVERY_CHECKPOINT' then
        perform public.require_sensitive_global_guard();
        if new.operation<>'ACTIVATE_SESSION_AUTHORITY' then perform public.require_sensitive_enforcement(); end if;
    end if;
    if new.provenance='CONTROLLED_ACTIVATION' and (new.database_role is distinct from current_user
        or current_user is distinct from (select pg_get_userbyid(relowner) from pg_class where oid='public.session_authority_activation'::regclass)) then
        raise exception 'activation provenance must identify actual database role' using errcode='23514';
    end if;
    return new;
end $$;
create trigger guard_sensitive_event before insert on public.identity_sensitive_event
    for each row execute function public.guard_sensitive_event();

-- Deferred graphs prove every effect's retained receipt/event references at commit.
create function public.check_sensitive_effect_graph() returns trigger language plpgsql as $$
declare p public.sensitive_reauth_proof; c public.recovery_contact_change; a public.account_session_authority;
begin
    if tg_table_name='session_authority_activation' then
        if new.state='ENFORCING' and not exists(select 1 from public.identity_sensitive_event e where e.id=new.activation_event_id
            and e.operation='ACTIVATE_SESSION_AUTHORITY' and e.result='SUCCESS' and e.provenance='CONTROLLED_ACTIVATION'
            and e.database_role=new.activated_by_database_role and e.occurred_at=new.activated_at) then
            raise exception 'activation event missing' using errcode='23514';
        end if;
    elsif tg_table_name='account_session_authority' then
        if not exists(select 1 from public.identity_sensitive_event e where e.id=new.created_event_id and e.result='SUCCESS'
                and e.subject_account_id=new.account_id and e.target_primary_id=new.primary_id and e.occurred_at=new.created_at
                and ((new.binding_kind='CURRENT_REQUEST' and e.operation='REGISTER_SESSION' and e.provenance='FRESH_SESSION')
                    or (new.binding_kind='REMOTE_ABSENCE' and e.operation='REVOKE_SESSION' and e.provenance='FRESH_SESSION')
                    or (new.binding_kind='KNOWN_LOGOUT' and e.operation='LOGOUT' and e.provenance in ('FRESH_SESSION','KNOWN_LINEAGE')))) then
            raise exception 'session creation provenance missing' using errcode='23514';
        end if;
        if new.state='REVOKED' and not exists(select 1 from public.session_authority_command_receipt r
            where r.target_primary_id=new.primary_id and r.account_id=new.account_id
                and r.result_authority_version=new.version and r.event_id=new.revocation_event_id and r.completed_at=new.revoked_at) then
            raise exception 'session revocation receipt missing' using errcode='23514';
        end if;
    elsif tg_table_name='sensitive_reauth_proof' then
        if not exists(select 1 from public.sensitive_reauth_issuance_receipt r where r.id=new.issuance_receipt_id
                and r.proof_id=new.id and r.account_id=new.account_id and r.actor_id=new.actor_id
                and r.source_primary_id=new.source_primary_id and r.expires_at=new.expires_at) then
            raise exception 'reauth issuance receipt missing' using errcode='23514';
        end if;
        if new.state='CONSUMED' then
            if new.action='SESSION_REVOKE' and not exists(select 1 from public.session_authority_command_receipt r
                where r.id=new.session_consume_receipt_id and r.proof_id=new.id and r.operation='REVOKE_SESSION'
                    and r.account_id=new.account_id and r.actor_id=new.actor_id and r.source_primary_id=new.source_primary_id
                    and r.target_primary_id=new.target_primary_id and r.expected_authority_version=new.expected_authority_version) then
                raise exception 'reauth session consumption missing' using errcode='23514';
            end if;
            if new.action='RECOVERY_CONTACT_CHANGE' and not exists(select 1 from public.recovery_contact_command_receipt r
                join public.recovery_contact_change change on change.id=r.challenge_id
                where r.id=new.contact_consume_receipt_id and r.proof_id=new.id and r.operation='REQUEST_CONTACT_CHANGE'
                    and r.account_id=new.account_id and r.actor_id=new.actor_id and r.source_primary_id=new.source_primary_id
                    and change.proof_id=new.id) then
                raise exception 'reauth contact consumption missing' using errcode='23514';
            end if;
        end if;
    elsif tg_table_name='recovery_contact_change' then
        select * into p from public.sensitive_reauth_proof where id=new.proof_id;
        if p.state<>'CONSUMED' or p.contact_consume_receipt_id is null or not exists(select 1 from public.recovery_contact_command_receipt r
            where r.id=p.contact_consume_receipt_id and r.operation='REQUEST_CONTACT_CHANGE' and r.challenge_id=new.id) then
            raise exception 'contact request must consume exact proof' using errcode='23514';
        end if;
        if not exists(select 1 from public.recovery_contact_delivery_attempt d where d.challenge_id=new.id and d.version=1) then
            raise exception 'contact request has no original delivery attempt' using errcode='23514';
        end if;
        if new.state='CONSUMED' and not exists(select 1 from public.recovery_contact_command_receipt r
            where r.id=new.consumed_receipt_id and r.operation='CONSUME_CONTACT_CHANGE' and r.challenge_id=new.id
                and r.account_id=new.account_id and r.actor_id=new.actor_id and r.source_primary_id=new.source_primary_id
                and exists(select 1 from public.account_recovery_contact verified where verified.id=r.result_contact_id
                    and verified.account_id=new.account_id and verified.verified_by_challenge_id=new.id
                    and verified.channel=new.channel and verified.destination=new.destination
                    and verified.status='ACTIVE' and verified.version=1 and verified.verified_at=r.completed_at)) then
            raise exception 'contact consume receipt missing' using errcode='23514';
        end if;
    end if;
    return null;
end $$;
create constraint trigger check_sensitive_activation after update on public.session_authority_activation
    deferrable initially deferred for each row execute function public.check_sensitive_effect_graph();
create constraint trigger check_sensitive_authority after insert or update on public.account_session_authority
    deferrable initially deferred for each row execute function public.check_sensitive_effect_graph();
create constraint trigger check_sensitive_proof after insert or update on public.sensitive_reauth_proof
    deferrable initially deferred for each row execute function public.check_sensitive_effect_graph();
create constraint trigger check_sensitive_contact_change after insert or update on public.recovery_contact_change
    deferrable initially deferred for each row execute function public.check_sensitive_effect_graph();

create function public.guard_sensitive_receipt_insert() returns trigger language plpgsql as $$
begin
    perform public.require_sensitive_global_guard();
    perform public.require_sensitive_enforcement();
    return new;
end $$;
create trigger guard_sensitive_reauth_receipt before insert on public.sensitive_reauth_issuance_receipt
    for each row execute function public.guard_sensitive_receipt_insert();
create trigger guard_session_authority_receipt before insert on public.session_authority_command_receipt
    for each row execute function public.guard_sensitive_receipt_insert();
create trigger guard_contact_receipt before insert on public.recovery_contact_command_receipt
    for each row execute function public.guard_sensitive_receipt_insert();

create function public.check_sensitive_receipt_event() returns trigger language plpgsql as $$
declare expected_operation text; expected_proof uuid; expected_challenge uuid; expected_target varchar(36);
begin
    if tg_table_name='sensitive_reauth_issuance_receipt' then
        expected_operation='ISSUE_REAUTH'; expected_proof=new.proof_id;
    elsif tg_table_name='session_authority_command_receipt' then
        expected_operation=new.operation; expected_proof=new.proof_id; expected_target=new.target_primary_id;
    else
        expected_operation=new.operation; expected_proof=new.proof_id; expected_challenge=new.challenge_id;
    end if;
    if tg_table_name='sensitive_reauth_issuance_receipt' then
        if not exists(select 1 from public.sensitive_reauth_proof p where p.id=new.proof_id and p.issuance_receipt_id=new.id
            and p.account_id=new.account_id and p.actor_id=new.actor_id and p.source_primary_id=new.source_primary_id
            and p.created_at=new.completed_at and p.expires_at=new.expires_at) then
            raise exception 'issuance receipt does not match proof' using errcode='23514';
        end if;
    elsif tg_table_name='session_authority_command_receipt' then
        if not exists(select 1 from public.account_session_authority a where a.primary_id=new.target_primary_id
            and a.account_id=new.account_id and a.state='REVOKED' and a.version=new.result_authority_version
            and a.revocation_event_id=new.event_id
            and new.result_authority_version=new.expected_authority_version+1) then
            raise exception 'session receipt does not match revocation' using errcode='23514';
        end if;
        if new.operation='REVOKE_SESSION' and not exists(select 1 from public.sensitive_reauth_proof p
            where p.id=new.proof_id and p.action='SESSION_REVOKE' and p.state='CONSUMED'
                and p.session_consume_receipt_id=new.id and p.account_id=new.account_id and p.actor_id=new.actor_id
                and p.source_primary_id=new.source_primary_id and p.target_primary_id=new.target_primary_id
                and p.expected_authority_version=new.expected_authority_version) then
            raise exception 'session receipt does not match consumed proof' using errcode='23514';
        end if;
    elsif tg_table_name='recovery_contact_command_receipt' then
        if not exists(select 1 from public.recovery_contact_change c where c.id=new.challenge_id
            and c.account_id=new.account_id and c.actor_id=new.actor_id and c.source_primary_id=new.source_primary_id
            and ((new.operation='REQUEST_CONTACT_CHANGE' and c.proof_id=new.proof_id and exists(
                select 1 from public.sensitive_reauth_proof p where p.id=new.proof_id and p.state='CONSUMED'
                    and p.action='RECOVERY_CONTACT_CHANGE' and p.contact_consume_receipt_id=new.id))
                or (new.operation='CONSUME_CONTACT_CHANGE' and c.state='CONSUMED' and c.consumed_receipt_id=new.id))) then
            raise exception 'contact receipt does not match challenge' using errcode='23514';
        end if;
    end if;
    if not exists(select 1 from public.identity_sensitive_event e where e.id=new.event_id and e.operation=expected_operation
        and e.result='SUCCESS' and e.subject_account_id=new.account_id and e.source_primary_id=new.source_primary_id
        and e.proof_id is not distinct from expected_proof and e.challenge_id is not distinct from expected_challenge
        and e.target_primary_id is not distinct from expected_target and e.occurred_at=new.completed_at
        and (new.actor_id is null or e.actor_id=new.actor_id)) then
        raise exception 'sensitive command event missing' using errcode='23514';
    end if;
    return null;
end $$;
create constraint trigger check_sensitive_reauth_receipt_event after insert on public.sensitive_reauth_issuance_receipt
    deferrable initially deferred for each row execute function public.check_sensitive_receipt_event();
create constraint trigger check_session_receipt_event after insert on public.session_authority_command_receipt
    deferrable initially deferred for each row execute function public.check_sensitive_receipt_event();
create constraint trigger check_contact_receipt_event after insert on public.recovery_contact_command_receipt
    deferrable initially deferred for each row execute function public.check_sensitive_receipt_event();

-- Preserve the V19 invitation provenance branch condition-for-condition; add only contact consumption.
create or replace function public.check_identity_recovery_provenance() returns trigger language plpgsql as $$
begin
    if exists(select 1 from public.identity_credential_challenge c join public.identity_lifecycle_command_receipt r on r.id=c.consumed_receipt_id
        where c.id=new.verified_by_challenge_id and c.purpose='NEW_ACCOUNT' and c.state='CONSUMED'
          and r.result_account_id=new.account_id and r.result_contact_id=new.id and c.channel=new.channel and c.destination=new.destination) then
        return null;
    end if;
    if not exists(select 1 from public.recovery_contact_change c
        join public.recovery_contact_command_receipt r on r.id=c.consumed_receipt_id
        join public.account a on a.id=c.account_id
        where c.id=new.verified_by_challenge_id and c.state='CONSUMED'
          and r.operation='CONSUME_CONTACT_CHANGE' and r.challenge_id=c.id and r.account_id=c.account_id
          and r.source_primary_id=c.source_primary_id and r.result_contact_id=new.id
          and c.account_id=new.account_id and c.channel=new.channel and c.destination=new.destination
          and new.version=1 and new.status='ACTIVE' and new.verified_at=r.completed_at
          and a.status='ACTIVE' and a.credential_version=c.issued_credential_version
          and ((c.expected_contact_id is null and c.expected_contact_version=0)
            or exists(select 1 from public.account_recovery_contact old_contact where old_contact.id=c.expected_contact_id
                and old_contact.account_id=c.account_id and old_contact.status='REVOKED'
                and old_contact.version=c.expected_contact_version+1))) then
        raise exception 'verified contact requires exact committed possession consumption' using errcode='23514';
    end if;
    return null;
end $$;
-- Existing V19 deferred check_identity_recovery_provenance trigger remains installed unchanged.
-- Existing V19 guard_identity_recovery_contact still permits only new ACTIVE/version1 or
-- immutable ACTIVE -> REVOKED/version+1. No account-wide monotonic version is invented.

create trigger sensitive_reauth_receipt_immutable before update or delete on public.sensitive_reauth_issuance_receipt
    for each row execute function public.reject_sensitive_history_mutation();
create trigger session_authority_receipt_immutable before update or delete on public.session_authority_command_receipt
    for each row execute function public.reject_sensitive_history_mutation();
create trigger contact_receipt_immutable before update or delete on public.recovery_contact_command_receipt
    for each row execute function public.reject_sensitive_history_mutation();
create trigger contact_delivery_immutable before update or delete on public.recovery_contact_delivery_attempt
    for each row execute function public.reject_sensitive_history_mutation();
create trigger sensitive_event_immutable before update or delete on public.identity_sensitive_event
    for each row execute function public.reject_sensitive_history_mutation();

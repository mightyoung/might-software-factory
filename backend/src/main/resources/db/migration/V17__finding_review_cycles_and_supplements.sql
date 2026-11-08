-- Source-only expand migration. Installation and runtime privileges are separately authorized.
alter table public.finding
    add column review_cycle integer not null default 1 check (review_cycle >= 1),
    add column review_protocol varchar(16) not null default 'LEGACY'
        check (review_protocol in ('LEGACY', 'CYCLE_V1')),
    drop constraint finding_state_valid,
    add constraint finding_state_valid check (state in ('DISCOVERED', 'PERSON_CONFIRM_PENDING',
        'SAFETY_CONFIRM_PENDING', 'REPORTER_SUPPLEMENT_PENDING', 'SAFETY_DISPATCH_PENDING',
        'RECTIFYING', 'ACCEPTANCE_PENDING', 'NOT_ESTABLISHED', 'CLOSED')),
    add constraint finding_legacy_review_shape check (review_protocol <> 'LEGACY'
        or (review_cycle = 1 and state <> 'REPORTER_SUPPLEMENT_PENDING'));

create function public.finding_review_utf16_length(value text) returns integer
language sql immutable strict parallel safe
as $$ select coalesce(sum(case when ascii(c) > 65535 then 2 else 1 end), 0)::integer
      from regexp_split_to_table(value, '') as chars(c) $$;

create function public.finding_review_text_valid(value text) returns boolean
language sql immutable strict parallel safe
as $$ select public.finding_review_utf16_length(value) between 1 and 4000
    and length(btrim(value, U&'\0009\000A\000B\000C\000D\0020\0085\00A0\1680\2000\2001\2002\2003\2004\2005\2006\2007\2008\2009\200A\2028\2029\202F\205F\3000')) > 0 $$;

create table public.finding_review_cycle (
    tenant_id uuid not null,
    finding_id uuid not null,
    review_cycle integer not null check (review_cycle >= 1),
    opened_by_command_id uuid not null,
    opened_at timestamptz not null,
    primary key (tenant_id, finding_id, review_cycle),
    foreign key (tenant_id, finding_id) references public.finding(tenant_id, id) on delete restrict,
    foreign key (tenant_id, opened_by_command_id) references public.finding_command_receipt(tenant_id, command_id) on delete restrict
);

create table public.finding_review_decision (
    tenant_id uuid not null,
    finding_id uuid not null,
    review_cycle integer not null check (review_cycle >= 1),
    stage varchar(16) not null check (stage in ('PERSON', 'SAFETY')),
    actor_member_id uuid not null,
    decision varchar(32) not null check (decision in ('APPROVE', 'RETURN_FOR_INFORMATION', 'NOT_ESTABLISHED')),
    reason text not null check (public.finding_review_text_valid(reason)),
    confirmed_grade varchar(16),
    expected_version bigint not null check (expected_version >= 1),
    command_id uuid not null,
    decided_at timestamptz not null,
    primary key (tenant_id, finding_id, review_cycle, stage),
    unique (tenant_id, command_id),
    unique (tenant_id, finding_id, review_cycle, command_id),
    foreign key (tenant_id, finding_id, review_cycle) references public.finding_review_cycle(tenant_id, finding_id, review_cycle) on delete restrict,
    foreign key (tenant_id, actor_member_id) references public.tenant_member(tenant_id, id) on delete restrict,
    foreign key (tenant_id, command_id) references public.finding_command_receipt(tenant_id, command_id) on delete restrict,
    constraint finding_review_grade_shape check (
        (stage = 'SAFETY' and decision = 'APPROVE' and confirmed_grade is not null and confirmed_grade in ('GENERAL', 'MAJOR'))
        or ((stage <> 'SAFETY' or decision <> 'APPROVE') and confirmed_grade is null))
);

create table public.finding_report_supplement (
    tenant_id uuid not null,
    id uuid not null,
    finding_id uuid not null,
    review_cycle integer not null check (review_cycle >= 1),
    next_review_cycle integer not null check (next_review_cycle = review_cycle + 1),
    return_command_id uuid not null,
    reporter_member_id uuid not null,
    statement text not null check (public.finding_review_text_valid(statement)),
    expected_version bigint not null check (expected_version >= 1),
    command_id uuid not null,
    submitted_at timestamptz not null,
    primary key (tenant_id, id),
    unique (tenant_id, finding_id, review_cycle),
    unique (tenant_id, command_id),
    foreign key (tenant_id, finding_id, review_cycle) references public.finding_review_cycle(tenant_id, finding_id, review_cycle) on delete restrict,
    foreign key (tenant_id, finding_id, next_review_cycle) references public.finding_review_cycle(tenant_id, finding_id, review_cycle) on delete restrict,
    foreign key (tenant_id, finding_id, review_cycle, return_command_id) references public.finding_review_decision(tenant_id, finding_id, review_cycle, command_id) on delete restrict,
    foreign key (tenant_id, reporter_member_id) references public.tenant_member(tenant_id, id) on delete restrict,
    foreign key (tenant_id, command_id) references public.finding_command_receipt(tenant_id, command_id) on delete restrict
);

create table public.finding_command_participant (
    tenant_id uuid not null,
    command_id uuid not null,
    finding_id uuid not null,
    review_cycle integer not null check (review_cycle >= 1),
    actor_member_id uuid not null,
    stage varchar(24) not null check (length(btrim(stage)) > 0),
    definition_id varchar(255) not null check (length(btrim(definition_id)) > 0),
    process_instance_id varchar(64) not null check (length(btrim(process_instance_id)) > 0),
    task_id varchar(64) not null check (length(btrim(task_id)) > 0),
    task_definition_key varchar(255) not null check (length(btrim(task_definition_key)) > 0),
    recorded_at timestamptz not null,
    primary key (tenant_id, command_id),
    foreign key (tenant_id, command_id) references public.finding_command_receipt(tenant_id, command_id) on delete restrict,
    foreign key (tenant_id, finding_id) references public.finding(tenant_id, id) on delete restrict,
    foreign key (tenant_id, actor_member_id) references public.tenant_member(tenant_id, id) on delete restrict
);

-- Stable history order, plus reverse FK lookups not covered by an existing key prefix.
create index finding_review_decision_history_idx
    on public.finding_review_decision (tenant_id, finding_id, review_cycle, decided_at, stage);
create index finding_report_supplement_history_idx
    on public.finding_report_supplement (tenant_id, finding_id, review_cycle, submitted_at, id);
create index finding_review_cycle_opened_command_idx
    on public.finding_review_cycle (tenant_id, opened_by_command_id);
create index finding_review_decision_actor_idx
    on public.finding_review_decision (tenant_id, actor_member_id);
create index finding_report_supplement_next_cycle_idx
    on public.finding_report_supplement (tenant_id, finding_id, next_review_cycle);
create index finding_report_supplement_reporter_idx
    on public.finding_report_supplement (tenant_id, reporter_member_id);
create index finding_command_participant_finding_idx
    on public.finding_command_participant (tenant_id, finding_id);
create index finding_command_participant_actor_idx
    on public.finding_command_participant (tenant_id, actor_member_id);
-- Cycle/finding and decision/cycle FKs use PK prefixes; command FKs use unique keys.
-- Supplement source/return lookups share the unique (tenant_id, finding_id, review_cycle):
-- it identifies at most one supplement, including when return_command_id is also checked.

alter table public.finding_command_receipt
    add column result_review_cycle integer check (result_review_cycle >= 1),
    add constraint finding_receipt_incomplete_cycle check (completed_at is not null or result_review_cycle is null);

create function public.guard_finding_review_receipt() returns trigger language plpgsql as $$
begin
    if tg_op = 'UPDATE' and old.completed_at is not null and new is distinct from old then
        raise exception 'completed finding receipts are immutable' using errcode = '23514';
    end if;
    if new.completed_at is not null and (tg_op = 'INSERT' or old.completed_at is null)
        and new.result_review_cycle is null then
        raise exception 'newly completed finding receipts require a result review cycle' using errcode = '23514';
    end if;
    return new;
end $$;
create trigger guard_finding_review_receipt before insert or update on public.finding_command_receipt
    for each row execute function public.guard_finding_review_receipt();

create function public.reject_finding_history_mutation() returns trigger language plpgsql as $$
begin
    raise exception 'finding history is append-only' using errcode = '23514';
end $$;

alter table public.finding_review_cycle enable row level security;
alter table public.finding_review_cycle force row level security;
create policy finding_review_cycle_tenant_isolation on public.finding_review_cycle
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table public.finding_review_decision enable row level security;
alter table public.finding_review_decision force row level security;
create policy finding_review_decision_tenant_isolation on public.finding_review_decision
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table public.finding_report_supplement enable row level security;
alter table public.finding_report_supplement force row level security;
create policy finding_report_supplement_tenant_isolation on public.finding_report_supplement
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table public.finding_command_participant enable row level security;
alter table public.finding_command_participant force row level security;
create policy finding_command_participant_tenant_isolation on public.finding_command_participant
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
create trigger finding_review_cycle_append_only before update or delete on public.finding_review_cycle
    for each row execute function public.reject_finding_history_mutation();
create trigger finding_review_decision_append_only before update or delete on public.finding_review_decision
    for each row execute function public.reject_finding_history_mutation();
create trigger finding_report_supplement_append_only before update or delete on public.finding_report_supplement
    for each row execute function public.reject_finding_history_mutation();
create trigger finding_command_participant_append_only before update or delete on public.finding_command_participant
    for each row execute function public.reject_finding_history_mutation();
create trigger finding_confirmation_append_only before update or delete on public.finding_confirmation
    for each row execute function public.reject_finding_history_mutation();
create trigger finding_transition_append_only before update or delete on public.finding_transition
    for each row execute function public.reject_finding_history_mutation();

create function public.guard_finding_review_projection() returns trigger language plpgsql as $$
begin
    if new.review_protocol is distinct from old.review_protocol then
        if not (old.review_protocol = 'LEGACY' and new.review_protocol = 'CYCLE_V1'
            and old.state = 'DISCOVERED' and new.state = 'PERSON_CONFIRM_PENDING'
            and old.review_cycle = 1 and new.review_cycle = 1
            and not exists (select 1 from public.workflow_binding b where b.tenant_id = old.tenant_id
                and b.business_type = 'FINDING' and b.business_id = old.id)) then
            raise exception 'finding review protocol is immutable after initialization' using errcode = '23514';
        end if;
    end if;
    if old.review_protocol = 'CYCLE_V1' and new.grade is distinct from old.grade
        and not (old.state = 'SAFETY_CONFIRM_PENDING' and new.state = 'SAFETY_DISPATCH_PENDING') then
        raise exception 'only SAFETY approval changes finding grade' using errcode = '23514';
    end if;
    if new.review_protocol = 'CYCLE_V1' and new.created_by_member_id is distinct from old.created_by_member_id then
        raise exception 'original finding reporter is immutable' using errcode = '23514';
    end if;
    if new.review_protocol = 'CYCLE_V1' and new.description is distinct from old.description then
        raise exception 'initial finding description is immutable' using errcode = '23514';
    end if;
    return new;
end $$;
create trigger guard_finding_review_projection before update on public.finding
    for each row execute function public.guard_finding_review_projection();

create function public.validate_finding_supplement() returns trigger language plpgsql as $$
begin
    if not exists (select 1 from public.finding_review_decision d join public.finding f
        on f.tenant_id = d.tenant_id and f.id = d.finding_id
        where d.tenant_id = new.tenant_id and d.finding_id = new.finding_id
        and d.review_cycle = new.review_cycle and d.command_id = new.return_command_id
        and d.decision = 'RETURN_FOR_INFORMATION' and f.created_by_member_id = new.reporter_member_id) then
        raise exception 'supplement requires an exact return decision and original reporter' using errcode = '23514';
    end if;
    return null;
end $$;
create constraint trigger validate_finding_supplement after insert on public.finding_report_supplement
    deferrable initially deferred for each row execute function public.validate_finding_supplement();

create function public.validate_finding_review_transition() returns trigger language plpgsql as $$
declare
    transition_command uuid;
    review_stage varchar(16);
    review public.finding_review_decision%rowtype;
    supplement public.finding_report_supplement%rowtype;
    person_actor uuid;
begin
    if new.review_protocol <> 'CYCLE_V1' then return null; end if;
    if not exists (select 1 from public.finding_review_cycle c where c.tenant_id = new.tenant_id
        and c.finding_id = new.id and c.review_cycle = new.review_cycle) then
        raise exception 'finding current review cycle is missing' using errcode = '23503';
    end if;
    if tg_op = 'INSERT' then
        if new.review_cycle <> 1 or new.state <> 'PERSON_CONFIRM_PENDING' then
            raise exception 'cycle findings start at PERSON cycle 1' using errcode = '23514';
        end if;
        return null;
    end if;
    if old.review_protocol = 'LEGACY' then return null; end if;
    if new is not distinct from old then return null; end if;
    select t.command_id into transition_command from public.finding_transition t
        where t.tenant_id = new.tenant_id and t.finding_id = new.id
        and t.from_version = old.version and t.to_version = new.version
        and t.from_state = old.state and t.to_state = new.state;
    if transition_command is null then
        raise exception 'cycle finding update requires an exact transition' using errcode = '23514';
    end if;
    if old.state = 'REPORTER_SUPPLEMENT_PENDING' then
        select * into supplement from public.finding_report_supplement s
            where s.tenant_id = new.tenant_id and s.finding_id = new.id
            and s.review_cycle = old.review_cycle and s.command_id = transition_command
            and s.expected_version = old.version;
        if not found or new.state <> 'PERSON_CONFIRM_PENDING' or new.review_cycle <> old.review_cycle + 1
            or supplement.next_review_cycle <> new.review_cycle or new.grade <> old.grade
            or new.round_no <> old.round_no or new.version <> old.version + 1 then
            raise exception 'invalid reporter supplement transition' using errcode = '23514';
        end if;
        return null;
    end if;
    if new.review_cycle <> old.review_cycle then
        raise exception 'only reporter supplement advances review cycle' using errcode = '23514';
    end if;
    -- Remediation is independent: dispatch/submission/PASS/FAIL need no review decision.
    if old.state not in ('PERSON_CONFIRM_PENDING', 'SAFETY_CONFIRM_PENDING') then return null; end if;
    review_stage := case old.state when 'PERSON_CONFIRM_PENDING' then 'PERSON' else 'SAFETY' end;
    select * into review from public.finding_review_decision d
        where d.tenant_id = new.tenant_id and d.finding_id = new.id and d.review_cycle = old.review_cycle
        and d.stage = review_stage and d.command_id = transition_command and d.expected_version = old.version;
    if not found or review.actor_member_id = new.created_by_member_id
        or new.round_no <> old.round_no or new.version <> old.version + 1 then
        raise exception 'review transition requires exact nonreporter decision' using errcode = '23514';
    end if;
    if review_stage = 'SAFETY' then
        select d.actor_member_id into person_actor from public.finding_review_decision d
            where d.tenant_id = new.tenant_id and d.finding_id = new.id
            and d.review_cycle = old.review_cycle and d.stage = 'PERSON' and d.decision = 'APPROVE';
        if person_actor is null or person_actor = review.actor_member_id or person_actor = new.created_by_member_id then
            raise exception 'SAFETY requires distinct same-cycle PERSON approval' using errcode = '23514';
        end if;
    end if;
    if review.decision = 'RETURN_FOR_INFORMATION' then
        if new.state <> 'REPORTER_SUPPLEMENT_PENDING' or new.grade <> old.grade then
            raise exception 'invalid return transition' using errcode = '23514';
        end if;
    elsif review.decision = 'NOT_ESTABLISHED' then
        if new.state <> 'NOT_ESTABLISHED' or new.grade <> old.grade then
            raise exception 'invalid not-established transition' using errcode = '23514';
        end if;
    elsif review_stage = 'PERSON' then
        if new.state <> 'SAFETY_CONFIRM_PENDING' or new.grade <> old.grade then
            raise exception 'invalid PERSON approval transition' using errcode = '23514';
        end if;
    elsif new.state <> 'SAFETY_DISPATCH_PENDING' or new.grade is distinct from review.confirmed_grade then
        raise exception 'invalid SAFETY approval transition' using errcode = '23514';
    end if;
    return null;
end $$;
create constraint trigger validate_finding_review_transition after insert or update on public.finding
    deferrable initially deferred for each row execute function public.validate_finding_review_transition();

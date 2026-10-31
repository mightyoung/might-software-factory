-- Add an explicit terminal outcome for findings confirmed as not established.
-- V2-V9 are immutable history; V9 is already applied on the test database and V8 is
-- reserved by PR #12. This migration is intentionally not applied by the local slice.

alter table public.finding
    add column related_finding_id uuid,
    drop constraint finding_state_valid,
    add constraint finding_state_valid check (state in (
        'DISCOVERED',
        'PERSON_CONFIRM_PENDING',
        'SAFETY_CONFIRM_PENDING',
        'SAFETY_DISPATCH_PENDING',
        'RECTIFYING',
        'ACCEPTANCE_PENDING',
        'NOT_ESTABLISHED',
        'CLOSED'
    )),
    add constraint finding_related_finding_not_self
        check (related_finding_id is null or related_finding_id <> id),
    add constraint finding_related_finding_fkey
        foreign key (tenant_id, related_finding_id)
        references public.finding (tenant_id, id) on delete restrict;

alter table public.finding_confirmation
    drop constraint finding_confirmation_decision_check,
    add constraint finding_confirmation_decision_check
        check (decision in ('APPROVE', 'NOT_ESTABLISHED')),
    add constraint finding_confirmation_not_established_reason
        check (decision <> 'NOT_ESTABLISHED'
            or (reason is not null and length(btrim(reason, U&'\0009\000A\000B\000C\000D\0020\0085\00A0\1680\2000\2001\2002\2003\2004\2005\2006\2007\2008\2009\200A\2028\2029\202F\205F\3000')) > 0));

create function public.prevent_not_established_finding_reopen() returns trigger
language plpgsql
as $$
begin
    if old.state = 'NOT_ESTABLISHED' and new.state <> 'NOT_ESTABLISHED' then
        raise exception 'NOT_ESTABLISHED findings are terminal; create a related finding for new evidence'
            using errcode = '23514', constraint = 'prevent_not_established_finding_reopen';
    end if;
    return new;
end;
$$;

create trigger prevent_not_established_finding_reopen
    before update of state on public.finding
    for each row execute function public.prevent_not_established_finding_reopen();

create function public.validate_related_finding_link() returns trigger
language plpgsql
as $$
declare
    related_state varchar(40);
begin
    if tg_op = 'UPDATE' and new.related_finding_id is distinct from old.related_finding_id then
        raise exception 'related finding link is immutable'
            using errcode = '23514', constraint = 'finding_related_finding_immutable';
    end if;

    if new.related_finding_id is not null then
        select state into related_state
          from public.finding
         where tenant_id = new.tenant_id
           and id = new.related_finding_id;

        if related_state is distinct from 'NOT_ESTABLISHED' then
            raise exception 'related finding must be not established'
                using errcode = '23514', constraint = 'finding_related_finding_terminal';
        end if;
    end if;
    return new;
end;
$$;

create trigger validate_related_finding_link
    before insert or update on public.finding
    for each row execute function public.validate_related_finding_link();

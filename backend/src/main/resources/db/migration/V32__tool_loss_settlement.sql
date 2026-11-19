-- Loss settlement is an immutable conclusion. A pending return is superseded without fabricating a receipt.
alter table public.tool_loan_revision drop constraint tool_loan_revision_action_check;
alter table public.tool_loan_revision add constraint tool_loan_revision_action_check
    check (action in ('CREATE','UPDATE','WITHDRAW','ISSUE','RETURN','RECEIVE','CLOSE_LOST'));
alter table public.tool_loan_command_receipt drop constraint tool_loan_command_receipt_action_check;
alter table public.tool_loan_command_receipt add constraint tool_loan_command_receipt_action_check
    check (action in ('CREATE','UPDATE','WITHDRAW','ISSUE','RETURN','RECEIVE','CLOSE_LOST'));
create table public.tool_loan_loss (
    tenant_id uuid not null,
    loan_id uuid not null,
    tool_id uuid not null,
    command_id uuid not null,
    actor_member_id uuid not null,
    occurred_at timestamptz not null,
    reason varchar(4000) not null check (length(btrim(reason)) > 0),
    primary key (tenant_id,loan_id,tool_id),
    foreign key (tenant_id,loan_id) references public.tool_loan(tenant_id,id) on delete restrict,
    foreign key (tenant_id,tool_id) references public.tool(tenant_id,id) on delete restrict,
    foreign key (tenant_id,actor_member_id) references public.tenant_member(tenant_id,id) on delete restrict
);
create index tool_loan_loss_command on public.tool_loan_loss(tenant_id,loan_id,command_id);
alter table public.tool_loan_loss enable row level security;
alter table public.tool_loan_loss force row level security;
create policy tool_loan_loss_tenant_isolation on public.tool_loan_loss
    using (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
    with check (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
create trigger tool_loan_loss_append_only before update or delete on public.tool_loan_loss
    for each row execute function public.reject_ledger_history_mutation();
create trigger tool_loan_loss_no_truncate before truncate on public.tool_loan_loss
    for each statement execute function public.reject_ledger_history_mutation();

alter table public.tool_return_request_item
    add column superseded_at timestamptz,
    add column superseded_by_command_id uuid,
    add constraint tool_return_supersession_complete check (
        (superseded_at is null and superseded_by_command_id is null)
        or (superseded_at is not null and superseded_by_command_id is not null
            and received_at is null and outcome is null and reason is null
            and received_by_member_id is null and command_id is null)
    );
drop index public.tool_return_item_pending;
create unique index tool_return_item_pending on public.tool_return_request_item(tenant_id,loan_id,tool_id)
    where received_at is null and superseded_at is null;
create or replace function public.guard_tool_return_receipt() returns trigger language plpgsql as $$
begin
    if tg_op='DELETE' then
        raise exception 'return history is protected' using errcode='23514';
    end if;
    if old.received_at is not null or old.superseded_at is not null
       or row(new.tenant_id,new.request_id,new.loan_id,new.tool_id)
          is distinct from row(old.tenant_id,old.request_id,old.loan_id,old.tool_id)
       or new.received_at is null and new.superseded_at is null then
        raise exception 'return item can only be concluded once' using errcode='23514';
    end if;
    if new.superseded_at is not null and not exists(
        select from public.tool_loan_loss l where l.tenant_id=new.tenant_id
          and l.loan_id=new.loan_id and l.tool_id=new.tool_id
          and l.command_id=new.superseded_by_command_id and l.occurred_at=new.superseded_at) then
        raise exception 'return supersession requires its loss conclusion' using errcode='23514';
    end if;
    return new;
end $$;
create or replace function public.guard_tool_loan_item_settlement() returns trigger language plpgsql as $$
begin
    if tg_op='DELETE' then
        raise exception 'loan item history is protected' using errcode='23514';
    end if;
    if old.issued_at is null or old.settled_at is not null or new.settled_at is null
       or row(new.tenant_id,new.loan_id,new.version,new.line_no,new.tool_id,new.issued_at)
          is distinct from row(old.tenant_id,old.loan_id,old.version,old.line_no,old.tool_id,old.issued_at)
       or not (
          exists(select from public.tool_return_request_item r where r.tenant_id=new.tenant_id
             and r.loan_id=new.loan_id and r.tool_id=new.tool_id and r.received_at=new.settled_at)
          or exists(select from public.tool_loan_loss l where l.tenant_id=new.tenant_id
             and l.loan_id=new.loan_id and l.tool_id=new.tool_id and l.occurred_at=new.settled_at)
       ) then
        raise exception 'loan item requires a matching receipt or loss conclusion' using errcode='23514';
    end if;
    return new;
end $$;
create or replace function public.guard_tool_identity() returns trigger language plpgsql as $$
begin
    if row(new.tenant_id,new.id,new.code,new.custody_scope_id,new.organization_id,new.created_at,new.created_by_actor_id)
       is distinct from row(old.tenant_id,old.id,old.code,old.custody_scope_id,old.organization_id,old.created_at,old.created_by_actor_id)
       or new.version<>old.version+1 then
        raise exception 'tool identity is protected and versions must advance' using errcode='23514';
    end if;
    if new.item_state is distinct from old.item_state and not (
        (old.item_state='NORMAL' and new.item_state='DAMAGED' and exists(
            select from public.tool_return_request_item r
            join public.tool_loan_item i on i.tenant_id=r.tenant_id and i.loan_id=r.loan_id and i.tool_id=r.tool_id
            where r.tenant_id=new.tenant_id and r.tool_id=new.id and r.outcome='DAMAGED'
              and r.received_at=new.updated_at and i.settled_at=r.received_at and i.issued_at is not null))
        or (old.item_state in ('NORMAL','DAMAGED') and new.item_state='LOST' and exists(
            select from public.tool_loan_loss l
            join public.tool_loan_item i on i.tenant_id=l.tenant_id and i.loan_id=l.loan_id and i.tool_id=l.tool_id
            where l.tenant_id=new.tenant_id and l.tool_id=new.id and l.occurred_at=new.updated_at
              and i.settled_at=l.occurred_at and i.issued_at is not null))
    ) then
        raise exception 'tool state change requires its receipt or loss conclusion' using errcode='23514';
    end if;
    return new;
end $$;
create function public.mark_lost_tool(p_tenant uuid,p_tool uuid,p_at timestamptz,p_actor uuid)
    returns void language plpgsql security invoker as $$
begin
    if not exists(select from public.tool_loan_loss l
        join public.tool_loan_item i on i.tenant_id=l.tenant_id and i.loan_id=l.loan_id and i.tool_id=l.tool_id
        where l.tenant_id=p_tenant and l.tool_id=p_tool and l.occurred_at=p_at
          and i.settled_at=p_at and i.issued_at is not null) then
        raise exception 'lost tool update requires its loss conclusion' using errcode='23514';
    end if;
    perform 1 from public.tool where tenant_id=p_tenant and id=p_tool and item_state in ('NORMAL','DAMAGED','LOST') for update;
    if not found then
        raise exception 'tool is unavailable for loss conclusion' using errcode='23514';
    end if;
    update public.tool set item_state='LOST',version=version+1,updated_at=p_at,updated_by_actor_id=p_actor
      where tenant_id=p_tenant and id=p_tool and item_state in ('NORMAL','DAMAGED');
end $$;
revoke all on function public.mark_lost_tool(uuid,uuid,timestamptz,uuid) from public;

lock table public.permission in share row exclusive mode;
do $$
begin
    if exists(select from public.permission where code='tool_loan.close_lost'
       and (resource_type is distinct from 'tool_loan' or action is distinct from 'close_lost'
            or application_key is not null and application_key<>'tooling')) then
        raise exception 'loan loss permission conflicts with tooling application' using errcode='23514';
    end if;
    insert into public.permission(id,code,resource_type,action,application_key,label,sort_order)
        values(gen_random_uuid(),'tool_loan.close_lost','tool_loan','close_lost','tooling','记录工装遗失结论',480)
        on conflict(code) do nothing;
    update public.permission set application_key='tooling',
        label=case when label is null or btrim(label)='' then '记录工装遗失结论' else label end,
        sort_order=case when sort_order=0 then 480 else sort_order end
      where code='tool_loan.close_lost' and (application_key is null or label is null or btrim(label)='' or sort_order=0);
end $$;

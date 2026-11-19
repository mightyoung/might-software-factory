-- Authorized catalog edits may transfer unoccupied normal tools. The UPDATE row lock also serializes issuance.
create or replace function public.guard_tool_identity() returns trigger language plpgsql as $$
begin
    if row(new.tenant_id,new.id,new.code,new.created_at,new.created_by_actor_id)
       is distinct from row(old.tenant_id,old.id,old.code,old.created_at,old.created_by_actor_id)
       or new.version<>old.version+1 then
        raise exception 'tool identity is protected and versions must advance' using errcode='23514';
    end if;
    if row(new.custody_scope_id,new.organization_id) is distinct from row(old.custody_scope_id,old.organization_id) then
        if old.item_state<>'NORMAL' or new.item_state<>'NORMAL'
           or exists(select from public.tool_loan_item i where i.tenant_id=old.tenant_id
                and i.tool_id=old.id and i.issued_at is not null and i.settled_at is null)
           or not exists(select from public.tool_custody_scope s where s.tenant_id=new.tenant_id
                and s.id=new.custody_scope_id and s.organization_id=new.organization_id and s.active) then
            raise exception 'tool scope transfer requires an available normal tool and active target' using errcode='23514';
        end if;
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

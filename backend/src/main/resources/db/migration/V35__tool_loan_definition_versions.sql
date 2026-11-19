-- V1 history keeps NULL. New fields are bound to each loan's submitted definition.
alter table public.tool_loan add column usage_location varchar(200);
alter table public.tool_loan_revision add column usage_location varchar(200);
alter table public.tool_loan add constraint tool_loan_definition_fields check (
    definition_version='1' and usage_location is null
    or definition_version='2' and usage_location is not null and length(btrim(usage_location))>0
);
create function public.guard_tool_loan_revision_fields() returns trigger language plpgsql as $$
declare bound_version text; bound_location text;
begin
    select definition_version,usage_location into strict bound_version,bound_location from public.tool_loan
        where tenant_id=new.tenant_id and id=new.loan_id;
    if new.usage_location is distinct from bound_location or bound_version='1' and new.usage_location is not null
       or bound_version='2' and (new.usage_location is null or length(btrim(new.usage_location))=0) then
        raise exception 'revision fields must match submitted definition' using errcode='23514';
    end if;
    return new;
end $$;
create trigger tool_loan_revision_fields before insert on public.tool_loan_revision
    for each row execute function public.guard_tool_loan_revision_fields();

create or replace function public.guard_tool_loan_application_update() returns trigger language plpgsql as $$
begin
    if row(new.tenant_id,new.id,new.applicant_member_id,new.organization_id,new.custody_scope_id,
           new.definition_version,new.created_at)
       is distinct from row(old.tenant_id,old.id,old.applicant_member_id,old.organization_id,old.custody_scope_id,
           old.definition_version,old.created_at)
       or new.version <> old.version + 1
       or old.state not in ('PENDING_ISSUE','ISSUED')
       or old.state='ISSUED' and (new.state not in ('ISSUED','SETTLED')
           or new.purpose is distinct from old.purpose or new.usage_location is distinct from old.usage_location) then
        raise exception 'loan identity and completed revisions are protected' using errcode='23514';
    end if;
    return new;
end $$;

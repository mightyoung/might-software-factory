-- Operator-applied (not Flyway). Ownership fix-up after applying V23 as a superuser.
--
-- Why: V23 refuses to run unless the executing identity is exempt from row security on permission,
-- scope_rule, role_permission and scope_org. The migrator role owns those tables under FORCE RLS,
-- so it is not exempt, and it is deliberately not given BYPASSRLS.
--
-- Procedure, per database:
--   1. As the migrator role, migrate up to V22 (Flyway target = 22).
--   2. As a superuser, WITHOUT `set role`, migrate V23 only (Flyway target = 23).
--   3. As the same superuser, run this file. It hands the four V23 functions to the owner of
--      public.permission, which is the migrator role.
--   4. As the migrator role, migrate the rest and run Flyway validate.
-- flyway_schema_history_app will show V23 installed_by the superuser; that is expected.
-- Safe to re-run: functions already owned by the migrator are left alone.
do $$
declare
    target oid := (select relowner from pg_catalog.pg_class where oid = 'public.permission'::regclass);
    fn regprocedure;
begin
    for fn in
        select p.oid::regprocedure from pg_catalog.pg_proc p
         where p.pronamespace = 'public'::regnamespace and p.proowner <> target
           and p.proname in ('guard_audit_query_permission', 'guard_audit_tenant_scope',
                             'guard_audit_tenant_scope_org', 'guard_audit_query_role_permission')
    loop
        execute format('alter function %s owner to %I', fn, pg_catalog.pg_get_userbyid(target));
    end loop;
end $$;

-- Must return zero rows: nothing in public may be left owned by anyone but the table owner.
select 'function' as kind, p.oid::regprocedure::text as name, pg_catalog.pg_get_userbyid(p.proowner) as owner
  from pg_catalog.pg_proc p
 where p.pronamespace = 'public'::regnamespace
   and p.proowner <> (select relowner from pg_catalog.pg_class where oid = 'public.permission'::regclass)
union all
select 'relation', c.oid::regclass::text, pg_catalog.pg_get_userbyid(c.relowner)
  from pg_catalog.pg_class c
 where c.relnamespace = 'public'::regnamespace
   and c.relowner <> (select relowner from pg_catalog.pg_class where oid = 'public.permission'::regclass);

-- Operator-applied (not Flyway). Minimal runtime privileges for the permission-config feature.
grant insert, delete on public.role_permission to msf_dev_runtime_20261107;
grant select, insert on public.permission_change_event to msf_dev_runtime_20261107;

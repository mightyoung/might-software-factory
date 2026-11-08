-- Ephemeral CI runtime only; fixture verifies effective privileges before capture.
-- This exclusively owned disposable database is not a shared NAS target.
revoke connect,temporary,create on database msf_ci_risk_recovery from public;
revoke all privileges on schema public from public;
grant connect on database msf_ci_risk_recovery to msf_ci_recovery_capture_runtime;
grant usage on schema public to msf_ci_recovery_capture_runtime;
grant select(id,account_id,kind,status) on public.principal to msf_ci_recovery_capture_runtime;
grant select(id,status,auth_epoch) on public.tenant to msf_ci_recovery_capture_runtime;
grant select(tenant_id,application_key,enabled,version) on public.tenant_application to msf_ci_recovery_capture_runtime;
grant select(tenant_id,command_id,entity_id,version,occurred_at,change_kind) on public.risk_scenario_revision to msf_ci_recovery_capture_runtime;
grant select(tenant_id,command_id,action_code,result_scenario_id,result_version,occurred_at,trace_id) on public.risk_scenario_receipt to msf_ci_recovery_capture_runtime;
grant select(tenant_id,source_command_id,consumer_version,recorded_at,recorded_by_service_actor_id) on public.risk_change_cause to msf_ci_recovery_capture_runtime;
grant insert(tenant_id,source_command_id,consumer_version,recorded_at,recorded_by_service_actor_id) on public.risk_change_cause to msf_ci_recovery_capture_runtime;
grant execute on function public.g014_instant_valid(timestamptz) to msf_ci_recovery_capture_runtime;

-- Fixed RECOVERY-only metadata reads for SERVICE attachment. No history write or schema rights.
grant select(installed_rank,version,script,checksum,success) on public."flyway_schema_history_app" to msf_ci_recovery_runtime;

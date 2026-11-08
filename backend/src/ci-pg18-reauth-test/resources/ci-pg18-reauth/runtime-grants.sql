-- New owned CI database only; no existing runtime roles or PUBLIC function baseline are widened.
revoke connect,temporary,create on database msf_ci_g008_reauth from public;
revoke all privileges on schema public from public;
grant connect on database msf_ci_g008_reauth to msf_ci_reauth_runtime;
grant usage on schema public to msf_ci_reauth_runtime;
grant select(id,status,credential_version) on public."account" to msf_ci_reauth_runtime;
grant select(id,account_id,kind,status) on public."principal" to msf_ci_reauth_runtime;
grant select(account_id,kind,secret_hash) on public."credential" to msf_ci_reauth_runtime;
grant select(id,status,auth_epoch) on public."tenant" to msf_ci_reauth_runtime;
grant select(tenant_id,id,account_id,status,auth_epoch) on public."tenant_member" to msf_ci_reauth_runtime;
grant select(primary_id,session_id,principal_name,expiry_time) on public."spring_session" to msf_ci_reauth_runtime;
grant update(principal_name) on public."spring_session" to msf_ci_reauth_runtime;
grant select(singleton,state,version,activated_at,activated_by_database_role,activation_event_id,activation_evidence_sha256) on public."session_authority_activation" to msf_ci_reauth_runtime;
grant select(primary_id,account_id,binding_kind,session_key_id,session_digest,bound_credential_version,authenticated_at,absolute_expires_at,state,version,created_at,created_event_id,revoked_at,revocation_event_id) on public."account_session_authority" to msf_ci_reauth_runtime;
grant insert(primary_id,account_id,binding_kind,session_key_id,session_digest,bound_credential_version,authenticated_at,absolute_expires_at,state,version,created_at,created_event_id,revoked_at,revocation_event_id) on public."account_session_authority" to msf_ci_reauth_runtime;
grant update(state,version,revoked_at,revocation_event_id) on public."account_session_authority" to msf_ci_reauth_runtime;
grant select(id,account_id,actor_id,source_primary_id,issued_credential_version,action,target_primary_id,expected_authority_version,expected_contact_id,expected_contact_version,destination_key_id,destination_digest,expected_pending_change_id,expected_pending_change_version,body_key_id,body_digest,secret_digest,state,version,created_at,expires_at,issuance_receipt_id,session_consume_receipt_id,contact_consume_receipt_id) on public."sensitive_reauth_proof" to msf_ci_reauth_runtime;
grant insert(id,account_id,actor_id,source_primary_id,issued_credential_version,action,target_primary_id,expected_authority_version,body_key_id,body_digest,secret_digest,state,version,created_at,expires_at,issuance_receipt_id) on public."sensitive_reauth_proof" to msf_ci_reauth_runtime;
grant update(state,version,session_consume_receipt_id) on public."sensitive_reauth_proof" to msf_ci_reauth_runtime;
grant select(id,account_id,actor_id,source_primary_id,command_key,fingerprint_key_id,fingerprint,proof_id,expires_at,event_id,completed_at) on public."sensitive_reauth_issuance_receipt" to msf_ci_reauth_runtime;
grant insert(id,account_id,actor_id,source_primary_id,command_key,fingerprint_key_id,fingerprint,proof_id,expires_at,event_id,completed_at) on public."sensitive_reauth_issuance_receipt" to msf_ci_reauth_runtime;
grant select(id,operation,account_id,actor_id,source_primary_id,target_primary_id,command_key,expected_authority_version,fingerprint_key_id,fingerprint,proof_id,result_authority_version,event_id,completed_at) on public."session_authority_command_receipt" to msf_ci_reauth_runtime;
grant insert(id,operation,account_id,actor_id,source_primary_id,target_primary_id,command_key,expected_authority_version,fingerprint_key_id,fingerprint,proof_id,result_authority_version,event_id,completed_at) on public."session_authority_command_receipt" to msf_ci_reauth_runtime;
grant select(id,operation,result,provenance,subject_account_id,actor_id,source_primary_id,target_primary_id,proof_id,challenge_id,occurred_at) on public."identity_sensitive_event" to msf_ci_reauth_runtime;
grant insert(id,operation,result,reason,provenance,subject_account_id,actor_id,source_primary_id,target_primary_id,proof_id,challenge_id,trace_id,policy_version,occurred_at) on public."identity_sensitive_event" to msf_ci_reauth_runtime;
-- Required V20 invoker helpers inherit the original PUBLIC EXECUTE baseline.
-- No direct runtime function ACL is introduced; fixture verifies required effective EXECUTE,
-- INVOKER/owner and whole-public-schema runtime effective rights == PUBLIC baseline.

-- This target is a newly created, exclusively owned CI container database.
revoke connect,temporary,create on database msf_ci_risk_registry from public;
revoke all privileges on schema public from public;
grant connect on database msf_ci_risk_registry to msf_ci_registry_runtime;
grant usage on schema public to msf_ci_registry_runtime;
-- Native V1 event-target lookup uses SELECT * against the original nine-column V5 table.
grant select(id,listener_id,event_type,serialized_event,publication_date,completion_date,status,completion_attempts,last_resubmission_date),
      insert(id,event_type,listener_id,publication_date,serialized_event),
      update(completion_date)
      on public.event_publication to msf_ci_registry_runtime;

grant select,insert on public.ordinary_ledger_record,public.ordinary_ledger_command to msf_dev_runtime_20261107;
grant update(values_json,version,deleted,updated_by_member_id,updated_at) on public.ordinary_ledger_record to msf_dev_runtime_20261107;
grant execute on function public.register_ledger_record_permissions(text) to msf_dev_runtime_20261107;

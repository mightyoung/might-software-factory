-- Operator-applied minimal privileges for configuration storage. This grants no business action to members.
grant select, insert on public.ledger_configuration to msf_dev_runtime_20261107;
grant update (latest_version,published_version) on public.ledger_configuration to msf_dev_runtime_20261107;
grant select, insert on public.ledger_configuration_snapshot, public.ledger_configuration_publication to msf_dev_runtime_20261107;

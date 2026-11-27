#!/usr/bin/env bash
set -euo pipefail
[[ ${STUDIO_PG_ACK:-} == studio-synthetic-ci-once &&
   ${GITHUB_ACTIONS:-} == true && ${RUNNER_ENVIRONMENT:-} == github-hosted &&
   ${GITHUB_REPOSITORY:-} == mightyoung/might-software-factory &&
   ${GITHUB_EVENT_NAME:-} == pull_request &&
   ${GITHUB_HEAD_REF:-} == codex/twin-studio-pg18-acceptance-20261127 &&
   ${GITHUB_BASE_REF:-} == codex/twin-studio-scoped-authority-20261127 &&
   ${GITHUB_RUN_ATTEMPT:-} == 1 &&
   ${STUDIO_PG_CONTAINER:-} =~ ^[0-9a-f]{64}$ ]] || { echo 'Studio CI gate refused'; exit 1; }
# No connection variables or credential files may override the fixed inside-container target.
for key in PGHOST PGHOSTADDR PGPORT PGDATABASE PGUSER PGPASSWORD PGSERVICE PGSERVICEFILE PGPASSFILE DATABASE_URL LAN_PG_URL LAN_PG_APP_PASSWORD; do
  [[ -z ${!key+x} ]] || { echo 'Unexpected database environment'; exit 1; }
done
c=$STUDIO_PG_CONTAINER
[[ $(docker inspect --type container --format '{{.State.Running}} {{.Config.Image}} {{len .HostConfig.PortBindings}} {{len .Mounts}}' "$c") == 'true postgres:18-bookworm 0 1' ]] ||
  { echo 'Studio sidecar is not unexposed official PG18'; exit 1; }
[[ $(docker inspect --format '{{range .Mounts}}{{.Type}} {{.Destination}}{{end}}' "$c") == 'volume /var/lib/postgresql' ]] ||
  { echo 'Studio sidecar storage is not the default ephemeral volume'; exit 1; }
[[ $(docker inspect --format '{{.Id}}' "$c") == "$c" ]] || exit 1

psql_ci() {
  local role=$1 password=$2; shift 2
  docker exec -i -e PGPASSWORD="$password" "$c" psql -X -h 127.0.0.1 -U "$role" -d msf_ci_studio_authority -v ON_ERROR_STOP=1 "$@"
}
bootstrap() { psql_ci postgres synthetic_studio_ci_bootstrap_only "$@"; }
runtime()   { psql_ci msf_ci_studio_reader synthetic_studio_ci_runtime_only "$@"; }
publisher() { psql_ci msf_ci_studio_writer synthetic_studio_ci_writer_only "$@"; }
probe=$(bootstrap -Atq -c "select current_database() = 'msf_ci_studio_authority' and current_user = 'postgres' and session_user = current_user and inet_server_addr() = '127.0.0.1'::inet and current_setting('server_version_num')::int / 10000 = 18 and not exists (select from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname='public' and c.relkind in ('r','p','v','m','f')) and not exists (select from pg_roles where rolname in ('msf_ci_studio_reader','msf_ci_studio_writer')) and (select count(*) from pg_stat_activity where datname=current_database() and backend_type='client backend')=1")
[[ $probe == t ]] || { echo 'Studio database identity or freshness invalid'; exit 1; }

# Match the actual server's immutable instance identity to its control file inside this sidecar.
control_id=$(docker exec "$c" pg_controldata /var/lib/postgresql/18/docker | awk '/^Database system identifier:/ {print $NF}')
server_id=$(bootstrap -Atq -c 'select system_identifier from pg_control_system()')
[[ $control_id =~ ^[0-9]+$ && $control_id == "$server_id" ]] || { echo 'Studio sidecar instance mismatch'; exit 1; }

marker=$(cat /proc/sys/kernel/random/uuid)
[[ $marker =~ ^[a-f0-9-]{36}$ ]]
# All files below are branch-owned, fixed; no script input selects paths or targets.
{
  printf "begin; create table public.studio_ci_marker (id uuid primary key); insert into public.studio_ci_marker values ('%s');\n" "$marker"
  cat tools/studio-pg18/setup.sql
  cat docs/digital-twin/studio-authority-postgres-draft.sql docs/digital-twin/studio-publication-postgres-draft.sql
  cat tools/studio-pg18/seed.sql
  echo 'commit;'
} | bootstrap -q
[[ $(runtime -Atq -c 'select id from public.studio_ci_marker') == "$marker" ]] || exit 1
cat tools/studio-pg18/probe.sql docs/digital-twin/studio-authority-pg18-probe-draft.sql | runtime -q
bootstrap -q < tools/studio-pg18/revoke.sql
runtime -q < tools/studio-pg18/after-revoke.sql

log=$(mktemp)
trap 'rm -f "$log"' EXIT
if publisher -q < tools/studio-pg18/failing-release.sql >"$log" 2>&1; then
  echo 'Expected deferred audit FK failure'; exit 1
fi
grep -Fq 'studio fixture reached deferred commit' "$log"
grep -Fq '23503' "$log"
grep -Fq 'studio_published_version_tenant_id_audit_event_id_fkey' "$log"
runtime -q < tools/studio-pg18/after-release.sql
echo 'Studio PG18: same-name scope, committed revocation and failed release rollback passed'

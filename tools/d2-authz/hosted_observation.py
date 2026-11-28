"""Read-only source observations for the private fixed AU-08 runner.

The future allocator must obtain the ID from its private create receipt and
resolve the official repository digest before launch. Neither comes from a
workflow input. This module cannot create, clean up or configure resources.
"""

from contextlib import closing
from dataclasses import dataclass
import json
import re
import subprocess

from hosted_contract import DATABASE, REPOSITORY, canonical_uuid

PREFIX = "com.mightyoung.ci.pg18."
BOOTSTRAP_ROLE = "msf_ci_au08_bootstrap"
FIXTURE_ROLE = "msf_ci_au08_fixture"
RUNTIME_ROLE = "msf_ci_au08_runtime"


@dataclass(frozen=True)
class OwnedContainer:
    container_id: str
    instance: str
    port: int
    volume: str


def identity_inputs(container_id, run, attempt, instance, image):
    return (all(isinstance(x, str) for x in (container_id, run, attempt, instance, image))
            and re.fullmatch(r"[0-9a-f]{64}", container_id)
            and re.fullmatch(r"[1-9][0-9]{0,19}", run)
            and re.fullmatch(r"[1-9][0-9]{0,9}", attempt)
            and canonical_uuid(instance)
            and re.fullmatch(r"docker\.io/library/postgres@sha256:[0-9a-f]{64}", image))


def inspect_container(container_id, run, attempt, instance, image):
    """Inspect exactly the allocator's recorded ID through the local runner daemon."""
    if not identity_inputs(container_id, run, attempt, instance, image):
        raise ValueError("Invalid AU-08 container receipt")
    try:
        output = subprocess.run(
            ["docker", "--host", "unix:///var/run/docker.sock", "inspect", "--type", "container", container_id],
            env={"PATH": "/usr/local/bin:/usr/bin:/bin"}, check=True, timeout=15,
            capture_output=True, text=True,
        )
        rows = json.loads(output.stdout)
        if not isinstance(rows, list) or len(rows) != 1:
            raise ValueError
    except (ValueError, subprocess.SubprocessError, OSError):
        raise ValueError("Cannot inspect the AU-08 container") from None
    return own_container(rows[0], container_id, run, attempt, instance, image)


def own_container(row, container_id, run, attempt, instance, image):
    if not identity_inputs(container_id, run, attempt, instance, image):
        raise ValueError("Invalid AU-08 container receipt")
    labels = {PREFIX + key: value for key, value in {
        "kind": "au08", "repository": REPOSITORY, "run": run, "attempt": attempt, "instance": instance,
    }.items()}
    try:
        ports = row["NetworkSettings"]["Ports"]
        bindings = ports["5432/tcp"]
        binding, = bindings
        mount, = row["Mounts"]
        port = binding["HostPort"]
        volume = mount["Name"]
        if (row["Id"] != container_id or row["Name"] != f"/msf-ci-au08-{run}-{attempt}"
                or row["Config"]["Image"] != image or row["State"]["Running"] is not True
                or {key: value for key, value in row["Config"]["Labels"].items()
                    if key.startswith(PREFIX)} != labels
                or row["HostConfig"]["Privileged"] is not False
                or row["HostConfig"]["NetworkMode"] not in {"bridge", "default"}
                or row["HostConfig"]["Binds"] not in (None, [])
                or row["HostConfig"].get("CapAdd") not in (None, [])
                or row["HostConfig"].get("Devices") not in (None, [])
                or row["HostConfig"].get("PidMode") not in (None, "")
                or row["NetworkSettings"]["Networks"].keys() != {"bridge"}
                or ports.keys() != {"5432/tcp"} or binding.keys() != {"HostIp", "HostPort"}
                or binding["HostIp"] != "127.0.0.1"
                or not re.fullmatch(r"[1-9][0-9]{0,4}", port)
                or not 1024 <= int(port) <= 65535 or int(port) == 5432
                or mount["Type"] != "volume" or mount["Destination"] != "/var/lib/postgresql"
                or not re.fullmatch(r"[0-9a-f]{64}", volume)):
            raise ValueError
    except (AttributeError, KeyError, TypeError, ValueError):
        raise ValueError("Container ownership or loopback isolation did not match AU-08") from None
    return OwnedContainer(container_id, instance, int(port), volume)


def observe_database(owned, runtime_password):
    """Ask the expected non-admin PostgreSQL role for the live database instance."""
    return _observe_role(owned, RUNTIME_ROLE, runtime_password)


def observe_fixture(owned, fixture_password):
    """Independently check the synthetic fixture writer is not the runtime or bootstrap."""
    return _observe_role(owned, FIXTURE_ROLE, fixture_password)


def _observe_role(owned, role, password):
    if not isinstance(owned, OwnedContainer) or not isinstance(password, str) or not password:
        raise ValueError("Missing owned container or generated fixture credential")
    row = _database_identity(owned, role, password)
    if (not isinstance(row, tuple) or len(row) != 13
            or row[:4] != (DATABASE, role, role, owned.instance)
            or type(row[4]) is not int or not 180000 <= row[4] < 190000
            or any(flag is not False for flag in row[5:])):
        raise ValueError("Live AU-08 database or runtime identity did not match")
    return row[3]


def _database_identity(owned, role, password):
    import os
    import psycopg2

    if any(key.startswith("PG") for key in os.environ):
        raise ValueError("Inherited PostgreSQL client options are forbidden")
    try:
        with closing(psycopg2.connect(
            host="127.0.0.1", hostaddr="127.0.0.1", port=owned.port, dbname=DATABASE,
            user=role, password=password, passfile="/dev/null", connect_timeout=5,
            sslmode="disable", gssencmode="disable",
        )) as db:
            db.set_session(readonly=True)
            with db, db.cursor() as query:
                query.execute("""
                    select current_database(), current_user, session_user,
                           current_setting('msf.ci_instance', true),
                           current_setting('server_version_num')::int,
                           rolsuper, rolcreatedb, rolcreaterole, rolreplication, rolbypassrls,
                           rolinherit or not rolcanlogin,
                           exists(select 1 from pg_catalog.pg_auth_members m where m.member = r.oid),
                           has_database_privilege(current_database(), 'CREATE,TEMP')
                             or has_schema_privilege('public', 'CREATE')
                             or exists(select 1 from pg_catalog.pg_database d
                                       where d.datname = current_database() and d.datdba = r.oid)
                    from pg_catalog.pg_roles r where rolname = current_user
                """)
                return query.fetchone()
    except psycopg2.Error:
        raise ValueError("Cannot observe the owned AU-08 database") from None

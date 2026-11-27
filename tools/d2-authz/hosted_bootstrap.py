"""Prepare the fixed, disposable AU-08 database without using existing environments."""

from contextlib import closing
from hashlib import file_digest
import os
import re
import secrets
import shutil
import subprocess
import zipfile

from bootstrap_inventory import review_inputs
from fixture_scope import synthetic_tenant_id
from hosted_contract import DATABASE
from hosted_observation import BOOTSTRAP_ROLE, FIXTURE_ROLE, RUNTIME_ROLE, observe_database, observe_fixture

MIGRATOR_ROLE = "msf_ci_au08_migrator"


def sha256_file(path):
    with path.open("rb") as stream:
        return file_digest(stream, "sha256").hexdigest()


def unpack_dependencies(artifact, destination, expected_sha256):
    if sha256_file(artifact) != expected_sha256:
        raise ValueError("AU-08 artifact does not match the recorded SHA-256")
    with zipfile.ZipFile(artifact) as jar:
        members = [name for name in jar.namelist() if name.startswith("BOOT-INF/lib/") and name.endswith(".jar")]
        names = [name.removeprefix("BOOT-INF/lib/") for name in members]
        if (not names or len(names) != len(set(names))
                or any(not re.fullmatch(r"[A-Za-z0-9_.-]+\.jar", name) for name in names)):
            raise ValueError("AU-08 artifact contains invalid dependency entries")
        destination.mkdir(mode=0o700)
        for entry, name in zip(members, names):
            with jar.open(entry) as source, (destination / name).open("xb") as output:
                shutil.copyfileobj(source, output)
    return destination


def private_role_passwords():
    passwords = {role: secrets.token_hex(32) for role in (MIGRATOR_ROLE, FIXTURE_ROLE, RUNTIME_ROLE)}
    if len(set(passwords.values())) != len(passwords):
        raise ValueError("AU-08 reused database credentials")
    return passwords


def connect(owned, role, password):
    import psycopg2

    if any(key.startswith("PG") for key in os.environ):
        raise ValueError("Inherited PostgreSQL client options are forbidden")
    return psycopg2.connect(host="127.0.0.1", hostaddr="127.0.0.1", port=owned.port, dbname=DATABASE,
                            user=role, password=password, passfile="/dev/null", connect_timeout=5,
                            sslmode="disable", gssencmode="disable")


def provision_roles(owned, bootstrap_password, passwords):
    from psycopg2 import sql

    with closing(connect(owned, BOOTSTRAP_ROLE, bootstrap_password)) as db, db, db.cursor() as q:
        q.execute("""select current_database(), current_user, session_user,
                            current_setting('msf.ci_instance', true),
                            current_setting('server_version_num')::int,
                            (select pg_get_userbyid(datdba) from pg_database where datname=current_database()),
                            (select rolsuper from pg_roles where rolname=current_user),
                            exists(select 1 from pg_roles where rolname like 'msf\\_ci\\_au08\\_%' and rolname<>current_user),
                            exists(select 1 from pg_class where relnamespace='public'::regnamespace)""")
        db_name, role, session, marker, version, owner, elevated, preexisting_roles, preexisting_tables = q.fetchone()
        if ((db_name, role, session, marker, owner) != (DATABASE, BOOTSTRAP_ROLE, BOOTSTRAP_ROLE, owned.instance, BOOTSTRAP_ROLE)
                or not 180000 <= version < 190000 or not elevated or preexisting_roles or preexisting_tables):
            raise ValueError("AU-08 bootstrap requires the fresh owned PostgreSQL 18 database")
        q.execute(sql.SQL("revoke all on database {} from public").format(sql.Identifier(DATABASE)))
        q.execute("revoke all on schema public from public")
        for name, password in passwords.items():
            q.execute(sql.SQL("create role {} login noinherit nosuperuser nocreatedb nocreaterole noreplication nobypassrls password %s")
                      .format(sql.Identifier(name)), (password,))
            q.execute(sql.SQL("grant connect on database {} to {}").format(sql.Identifier(DATABASE), sql.Identifier(name)))
        q.execute(sql.SQL("alter schema public owner to {}").format(sql.Identifier(MIGRATOR_ROLE)))
        for name in (FIXTURE_ROLE, RUNTIME_ROLE):
            q.execute(sql.SQL("grant usage on schema public to {}").format(sql.Identifier(name)))
    observe_database(owned, passwords[RUNTIME_ROLE])
    observe_fixture(owned, passwords[FIXTURE_ROLE])


def run_java(root, classpath, stage, environment):
    try:
        return subprocess.run(["java", "--class-path", classpath, "HostedBootstrap", stage],
                              cwd=root, env=environment, capture_output=True, text=True,
                              timeout=180, check=True)
    except subprocess.CalledProcessError as error:
        # The Java entry emits only a fixed stage or sanitized SQLSTATE.
        status = [line for line in error.stderr.splitlines()
                  if re.fullmatch(r"AU08_BOOTSTRAP_FAILED [A-Za-z.]+ (?:[A-Z0-9]{5}|NA)", line)]
        raise ValueError("AU-08 " + stage + " failed" + (": " + status[0] if len(status) == 1 else "")) from None
    except (OSError, subprocess.TimeoutExpired):
        raise ValueError("AU-08 " + stage + " unavailable") from None


def migrate_and_seed(root, artifact, artifact_sha256, owned, directory, bootstrap_password, passwords, actors):
    review_inputs(root)
    libraries = unpack_dependencies(artifact, directory / "lib", artifact_sha256)
    classes = directory / "classes"
    classes.mkdir(mode=0o700)
    classpath = str(libraries / "*")
    process_environment = {key: os.environ[key] for key in ("PATH", "JAVA_HOME") if key in os.environ}
    try:
        subprocess.run(["javac", "-cp", classpath, "-d", str(classes), "tools/d0/bootstrap.java",
                        "tools/d0/migrate.java", "tools/d2-authz/HostedBootstrap.java",
                        "tools/d2-authz/HostedBootstrapConfigTest.java"],
                       cwd=root, env=process_environment, capture_output=True, timeout=60, check=True)
    except (OSError, subprocess.SubprocessError):
        raise ValueError("AU-08 bootstrap Java compilation failed") from None
    child = {**process_environment, "AU08_BOOT_PORT": str(owned.port), "AU08_BOOT_MARKER": owned.instance,
             "AU08_BOOT_TENANT": synthetic_tenant_id(owned.instance),
             "AU08_BOOT_MIGRATOR_PASSWORD": passwords[MIGRATOR_ROLE]}
    classpath = str(classes) + os.pathsep + classpath
    try:
        subprocess.run(["java", "--class-path", classpath, "HostedBootstrapConfigTest"], cwd=root,
                       env=process_environment, capture_output=True, timeout=20, check=True)
    except (OSError, subprocess.SubprocessError):
        raise ValueError("AU-08 Flowable offline configuration test failed") from None
    run_java(root, classpath, "migrate", {**child, "AU08_BOOT_ADMIN_PASSWORD": bootstrap_password})
    print("AU08_MIGRATIONS_VALIDATED", flush=True)
    run_java(root, classpath, "seed", {**child, "AU08_BOOT_READER": actors["reader"],
                                                  "AU08_BOOT_KEEPER": actors["keeper"]})
    print("AU08_SYNTHETIC_IDENTITIES_SEEDED", flush=True)
    run_java(root, classpath, "deploy", child)

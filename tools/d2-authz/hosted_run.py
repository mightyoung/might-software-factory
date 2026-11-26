"""AU-08 acceptance entry for the approved GitHub-hosted, synthetic-only runner."""

from contextlib import closing, contextmanager
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import time
import traceback

from fixture_scope import fixture_transaction
from hosted_allocation import IMAGE, allocate
from hosted_bootstrap import connect, migrate_and_seed, private_role_passwords, provision_roles, sha256_file
from hosted_contract import DATABASE, phase_transition, result_summary
from hosted_grants import apply_grants
from hosted_observation import FIXTURE_ROLE, RUNTIME_ROLE, observe_database, observe_fixture
from workflow_pin import read_workflow_pin

ROOT = Path(__file__).resolve().parents[2]
ARTIFACT = ROOT / "backend/target/backend-0.1.0-SNAPSHOT.jar"


def observed_port(log):
    ports = re.findall(r"Tomcat started on port ([0-9]{1,5}) \(https\)", log)
    if not ports:
        return None
    if len(ports) != 1 or not 1024 <= int(ports[0]) <= 65535:
        raise ValueError("Cannot identify a single owned HTTPS connector")
    return int(ports[0])


def csrf_ready(status, token):
    return status == 204 and isinstance(token, str) and bool(token)


def app_configuration(keystore, password, version, tenant, pin):
    return {
        "server": {"address": "127.0.0.1", "port": 0, "ssl": {"enabled": True, "key-store": str(keystore),
                   "key-store-type": "PKCS12", "key-store-password": password, "key-alias": "au08"}},
        "spring": {"flyway": {"enabled": False}, "session": {"jdbc": {"initialize-schema": "never"}}},
        "flowable": {"database-schema-update": False, "check-process-definitions": False,
                     "async-executor-activate": False, "async-history-executor-activate": False},
        "software-factory": {"tooling": {"loan-definition-version": version},
                             "workflow": {"tool-loan": {"tenant-pins": {tenant: pin}}}},
    }


def make_certificate(directory):
    ca, keystore = directory / "au08.pem", directory / "au08.p12"
    password = secrets.token_hex(32)
    environment = {key: os.environ[key] for key in ("PATH", "JAVA_HOME") if key in os.environ}
    environment["AU08_KEYSTORE_PASSWORD"] = password
    print("::add-mask::" + password, flush=True)
    try:
        for cmd in [
            ["keytool", "-genkeypair", "-alias", "au08", "-keyalg", "RSA", "-keysize", "2048",
             "-dname", "CN=127.0.0.1", "-ext", "SAN=ip:127.0.0.1", "-validity", "1",
             "-storetype", "PKCS12", "-keystore", str(keystore), "-storepass:env", "AU08_KEYSTORE_PASSWORD"],
            ["keytool", "-exportcert", "-rfc", "-alias", "au08", "-keystore", str(keystore),
             "-file", str(ca), "-storepass:env", "AU08_KEYSTORE_PASSWORD"],
        ]:
            subprocess.run(cmd, env=environment, capture_output=True, check=True, timeout=20)
    except (OSError, subprocess.SubprocessError):
        raise ValueError("AU-08 temporary TLS certificate creation failed") from None
    return ca, keystore, password


@contextmanager
def app(owned, directory, cert, jar_sha, runtime_password, phase, tenant, pin):
    import requests

    ca, keystore, tls_password = cert
    if sha256_file(ARTIFACT) != jar_sha:
        raise ValueError("AU-08 application artifact changed")
    app_name = "au08-" + secrets.token_hex(16)
    environment = {key: os.environ[key] for key in ("PATH", "JAVA_HOME") if key in os.environ}
    environment.update(
        LAN_PG_APP_JDBC_URL=f"jdbc:postgresql://127.0.0.1:{owned.port}/{DATABASE}"
                            f"?sslmode=disable&gssEncMode=disable&ApplicationName={app_name}",
        LAN_PG_APP_USERNAME=RUNTIME_ROLE, LAN_PG_APP_PASSWORD=runtime_password,
        SPRING_APPLICATION_JSON=json.dumps(app_configuration(keystore, tls_password, phase, tenant, pin)),
    )
    log = directory / ("phase-" + phase + ".log")
    with log.open("xb") as stream:
        process = subprocess.Popen(["java", "-jar", str(ARTIFACT)], env=environment, cwd=directory,
                                   stdout=stream, stderr=subprocess.STDOUT)
        try:
            with requests.Session() as session:
                session.trust_env = False
                session.verify = str(ca)
                for _ in range(150):
                    if process.poll() is not None:
                        sql_states = re.findall(r"SQL State\s*:\s*([A-Z0-9]{5})", log.read_text(errors="replace"))
                        suffix = " SQLSTATE=" + sql_states[0] if sql_states else ""
                        raise ValueError("AU-08 HTTPS phase " + phase + " exited at startup" + suffix)
                    port = observed_port(log.read_text(errors="replace"))
                    if port:
                        origin = f"https://127.0.0.1:{port}"
                        try:
                            response = session.get(origin + "/api/v1/auth/csrf", timeout=3, allow_redirects=False)
                            if csrf_ready(response.status_code, session.cookies.get("XSRF-TOKEN")):
                                break
                        except requests.exceptions.SSLError:
                            raise ValueError("AU-08 HTTPS endpoint did not present the owned certificate") from None
                        except requests.RequestException:
                            pass
                    time.sleep(0.5)
                else:
                    raise ValueError("AU-08 owned HTTPS endpoint did not become ready for phase " + phase)
            yield origin, app_name
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=10)


def witness(connection, owned, target, app_name, jar_hash, version, loan_id, expected_loan_state):
    with fixture_transaction(connection, owned) as q:
        # Unrelated PostgreSQL roles see database/user/application name, not backend_type or SQL text.
        q.execute("""select exists(select 1 from pg_stat_activity where datname=current_database()
            and usename=%s and application_name=%s)""", (RUNTIME_ROLE, app_name))
        if q.fetchone() != (True,):
            raise ValueError("No actual application connection observed in the owned AU-08 database")
        q.execute("select definition_version,state from public.tool_loan where id=%s", (loan_id,))
        if q.fetchone() != ("1", expected_loan_state):
            raise ValueError("The observed AU-08 loan did not remain V1 in the same database")
    return {"commit": target.commit, "jar_sha256": jar_hash, "instance": owned.instance,
            "database": DATABASE, "definition_version": int(version)}


def run():
    import acceptance

    if not ARTIFACT.is_file() or ARTIFACT.is_symlink():
        raise ValueError("Build the current AU-08 application artifact first")
    jar_sha = sha256_file(ARTIFACT)
    with allocate(ROOT, os.environ) as (owned, target, directory, admin):
        print("AU08_OWNED_POSTGRES_READY", flush=True)
        roles = private_role_passwords()
        actors = {actor: secrets.token_hex(32) for actor in ("reader", "keeper")}
        if len({admin, *roles.values(), *actors.values()}) != 6:
            raise ValueError("AU-08 generated credentials must be distinct")
        for password in [*roles.values(), *actors.values()]:
            print("::add-mask::" + password, flush=True)
        provision_roles(owned, admin, roles)
        print("AU08_ROLES_VERIFIED", flush=True)
        migrate_and_seed(ROOT, ARTIFACT, jar_sha, owned, directory, admin, roles, actors)
        print("AU08_MIGRATIONS_SEED_AND_BPMN_WRITTEN", flush=True)
        apply_grants(owned, roles)
        print("AU08_EFFECTIVE_ACL_VERIFIED", flush=True)
        cert = make_certificate(directory)
        ca_sha = sha256_file(cert[0])
        with closing(connect(owned, FIXTURE_ROLE, roles[FIXTURE_ROLE])) as fixture:
            tenant, pin = read_workflow_pin(ROOT, fixture, owned)
            observe_database(owned, roles[RUNTIME_ROLE])
            observe_fixture(owned, roles[FIXTURE_ROLE])
            print("AU08_WORKFLOW_PIN_OBSERVED", flush=True)
            with app(owned, directory, cert, jar_sha, roles[RUNTIME_ROLE], "1", tenant, pin) as (origin, name):
                acceptance.configure(origin, cert[0], directory, actors)
                actual = acceptance.prepare()
                loan = json.loads((directory / "scenario.json").read_text())["id"]
                one = witness(fixture, owned, target, name, jar_sha, actual, loan, "PENDING_ISSUE")
            print("AU08_V1_CREATED_THROUGH_HTTPS", flush=True)
            if sha256_file(cert[0]) != ca_sha or read_workflow_pin(ROOT, fixture, owned) != (tenant, pin):
                raise ValueError("The AU-08 pinned certificate or definition changed")
            with app(owned, directory, cert, jar_sha, roles[RUNTIME_ROLE], "2", tenant, pin) as (origin, name):
                acceptance.configure(origin, cert[0], directory, actors)
                actual = acceptance.verify(owned, roles[FIXTURE_ROLE], name)
                two = witness(fixture, owned, target, name, jar_sha, actual, loan, "SETTLED")
            phase_transition(target, one, two)
            report = result_summary(json.loads((directory / "summary.json").read_text()))
    output = ROOT / "artifacts" / "au08" / "result.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    report.update(commit=target.commit, jar_sha256=jar_sha, tls_sha256=ca_sha,
                  workflow_sha256=pin["resource-sha256"], postgres_image=IMAGE,
                  versions=[one["definition_version"], two["definition_version"]],
                  owned_resources_removed=True)
    with output.open("x", encoding="utf-8") as stream:
        json.dump(report, stream, ensure_ascii=False, indent=2)
    print("AU08_VERIFIED_AFTER_CLEANUP checks=" + str(report["passed"]))


def failure_diagnostic(failure):
    """Allow only a local source coordinate, HTTP status and SQLSTATE, never the payload."""
    stage = "AU08_RUN_FAILED " + type(failure).__name__
    for frame in reversed(traceback.extract_tb(failure.__traceback__)):
        basename = Path(frame.filename).name
        if basename in {"acceptance.py", "fixture_scope.py", "hosted_run.py",
                        "hosted_bootstrap.py", "hosted_grants.py", "hosted_observation.py"}:
            stage += f" SITE={basename}:{frame.lineno}"
            break
    sqlstate = getattr(failure, "pgcode", None)
    if isinstance(sqlstate, str) and re.fullmatch(r"[A-Z0-9]{5}", sqlstate):
        stage += " SQLSTATE=" + sqlstate
    response = getattr(failure, "response", None)
    status = getattr(response, "status_code", None)
    if isinstance(failure, AssertionError) and len(failure.args) == 1:
        detail = failure.args[0]
        if isinstance(detail, tuple) and len(detail) == 2 and type(detail[0]) is int:
            status = detail[0]
    if type(status) is int and 100 <= status <= 599:
        stage += " HTTP=" + str(status)
    return stage


if __name__ == "__main__":
    try:
        run()
    except Exception as failure:
        # Print only authored messages and stable diagnostic categories. No raw logs, rows or payloads.
        message = str(failure)
        if type(failure) is ValueError and re.fullmatch(r"[A-Za-z0-9_:. /=-]{1,180}", message) and message.startswith(("AU-08", "Unexpected AU-08", "The AU-08", "No actual application")):
            print(message, file=sys.stderr)
        else:
            print(failure_diagnostic(failure), file=sys.stderr)
        raise SystemExit(1) from None

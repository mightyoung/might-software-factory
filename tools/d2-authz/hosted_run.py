"""Single TL-10 acceptance using the prior isolated bootstrap for infrastructure only."""

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

from uuid import uuid4

from fixture_scope import fixture_transaction
from hosted_allocation import IMAGE, allocate
from hosted_bootstrap import connect, migrate_and_seed, private_role_passwords, provision_roles, sha256_file
from hosted_contract import DATABASE, canonical_uuid
from hosted_grants import apply_grants
from hosted_observation import FIXTURE_ROLE, RUNTIME_ROLE
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


def app_diagnostics(log):
    """Return only bounded diagnostics from the known ledger warning and PostgreSQL errors."""
    findings = []
    warnings = re.findall(
        r"Ledger unavailable trace=[0-9a-f-]{36} type=([A-Za-z0-9_.$]{1,120}) "
        r"causeType=([A-Za-z0-9_.$]{1,120}) sqlState=([A-Z0-9]{5}|none|00000) source=\[([^\]\n]{0,1200})\]",
        log)
    for kind, cause, sqlstate, frames in warnings[-2:]:
        locations = re.findall(r"\(([A-Za-z0-9_]+\.java:[0-9]{1,5})\)", frames)[:4]
        findings.append(f"AU08_APP_DEPENDENCY type={kind} cause={cause} sqlstate={sqlstate} source="
                        + ",".join(locations))
    for kind, name in re.findall(
            r"org\.postgresql\.util\.PSQLException: ERROR: permission denied for (table|function|sequence|schema) "
            r"([a-z][a-z0-9_]{0,62})(?=\s|\(|$)", log)[-4:]:
        findings.append(f"AU08_APP_DB_PERMISSION {kind}={name}")
    return list(dict.fromkeys(findings))


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
        except Exception:
            for diagnostic in app_diagnostics(log.read_text(errors="replace")):
                print(diagnostic, file=sys.stderr)
            raise
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=10)


@contextmanager
def preview(directory, origin, cert):
    ca, key, password = cert
    log = directory / "tl10-preview.log"
    environment = {key: os.environ[key] for key in ("PATH",) if key in os.environ}
    environment.update(TL10_BACKEND=origin, TL10_CA=str(ca), TL10_PFX=str(key), TL10_TLS_PASSWORD=password)
    with log.open("xb") as stream:
        process = subprocess.Popen(["node", "tools/d2-issue/hosted-preview.mjs"],
                                   cwd=ROOT, env=environment, stdout=stream, stderr=subprocess.STDOUT)
        try:
            for _ in range(100):
                ports = re.findall(r"^TL10_PREVIEW_PORT=(\d{4,5})$", log.read_text(), re.M)
                if len(ports) == 1:
                    yield f"https://127.0.0.1:{ports[0]}"
                    return
                if process.poll() is not None:
                    raise ValueError("TL-10 HTTPS preview exited")
                time.sleep(0.2)
            raise ValueError("TL-10 HTTPS preview not ready")
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)


def seed_tool(origin, ca, actors):
    import requests
    def login(session, actor):
        session.trust_env = False
        session.verify = str(ca)
        csrf = origin + "/api/v1/auth/csrf"
        session.get(csrf, timeout=10, allow_redirects=False).raise_for_status()
        response = post(session, "/api/v1/auth/login", {
            "tenantCode": "d1seed", "loginKey": "d1seed-" + actor, "password": actors[actor]})
        if not 200 <= response.status_code < 300:
            raise ValueError("TL-10 fixture login failed")
        session.get(csrf, timeout=10, allow_redirects=False).raise_for_status()

    def post(session, path, body):
        return session.post(origin + path, json=body, timeout=15, allow_redirects=False,
                            headers={"X-XSRF-TOKEN": session.cookies["XSRF-TOKEN"],
                                     "Idempotency-Key": str(uuid4())})

    with requests.Session() as reader, requests.Session() as keeper:
        login(reader, "reader")
        response = reader.get(origin + "/api/v1/tooling/loan-applications/candidates",
                              timeout=15, allow_redirects=False)
        assert response.status_code == 200
        scope, = [item["id"] for item in response.json()["data"]["scopes"] if "D1-SCOPE-A" in item["label"]]
        login(keeper, "keeper")
        created = post(keeper, "/api/v1/apps/tooling/entities/tool/records", {"definitionVersion": "1",
                       "values": {"code": "TL10-" + uuid4().hex, "name": "TL-10 synthetic tool", "custodyScopeId": scope}})
        assert created.status_code == 200
        return scope, created.json()["data"]["id"]


def check_browser(report, tool):
    if (report.keys() != {"marker", "loanId", "toolId", "issueStatus", "withdrawStatus", "rereadState",
                          "rereadVersion", "databaseEvidence"}
            or not canonical_uuid(report["loanId"]) or not canonical_uuid(tool) or report["toolId"] != tool
            or not report["marker"].startswith("TL10-ISSUE-FIRST-")
            or not canonical_uuid(report["marker"].removeprefix("TL10-ISSUE-FIRST-"))
            or [report[key] for key in ("issueStatus", "withdrawStatus", "rereadState",
                                       "rereadVersion", "databaseEvidence")] != [200, 409, "ISSUED", 2, "PENDING"]):
        raise ValueError("TL-10 browser evidence was not the requested scenario")


def run():
    assert ARTIFACT.is_file() and not ARTIFACT.is_symlink()
    assert (ROOT / "frontend/dist/index.html").is_file()
    jar_sha = sha256_file(ARTIFACT)
    with allocate(ROOT, os.environ) as (owned, target, directory, admin):
        print("TL10_ISOLATED_POSTGRES_READY", flush=True)
        roles = private_role_passwords()
        actors = {actor: secrets.token_hex(32) for actor in ("reader", "keeper")}
        assert len({admin, *roles.values(), *actors.values()}) == 6
        for password in [*roles.values(), *actors.values()]:
            print("::add-mask::" + password, flush=True)
        provision_roles(owned, admin, roles)
        migrate_and_seed(ROOT, ARTIFACT, jar_sha, owned, directory, admin, roles, actors)
        apply_grants(owned, roles)
        cert = make_certificate(directory)
        with closing(connect(owned, FIXTURE_ROLE, roles[FIXTURE_ROLE])) as fixture:
            tenant, pin = read_workflow_pin(ROOT, fixture, owned)
            with app(owned, directory, cert, jar_sha, roles[RUNTIME_ROLE], "1", tenant, pin) as (origin, _):
                scope, tool = seed_tool(origin, cert[0], actors)
                print("TL10_HTTP_FIXTURE_READY", flush=True)
                with preview(directory, origin, cert) as frontend:
                    environment = {"PATH": os.environ["PATH"], "HOME": os.environ["HOME"],
                                   "D2_ISSUE_ORIGIN": frontend, "D2_ISSUE_DATABASE": DATABASE,
                                   "D2_ISSUE_ACK": "isolated-issuance-acceptance:" + DATABASE,
                                   "D2_ISSUE_SCOPE_ID": scope, "D2_WITHDRAW_TOOL_ID": tool,
                                   "D2_PASSWORD_READER": actors["reader"], "D2_PASSWORD_KEEPER": actors["keeper"]}
                    child = subprocess.run(["node", "tools/d2-issue/withdraw-after-issue.mjs"],
                                           cwd=ROOT, env=environment, timeout=110, capture_output=True, text=True)
                    if child.returncode != 0:
                        # Only a source line coordinate, not browser selectors, account data or page snapshots.
                        sites = re.findall(r"withdraw-after-issue\.mjs:(\d{1,4}):\d+", child.stderr)
                        print("TL10_BROWSER_FAILED site=" + (sites[0] if sites else "unknown"), file=sys.stderr)
                        raise ValueError("TL-10 browser acceptance failed")
                    report = json.loads(child.stdout)
                    check_browser(report, tool)
                    print("TL10_BROWSER_200_409_ISSUED_V2", flush=True)
                fixture.set_session(readonly=True)
                with fixture_transaction(fixture, owned) as cursor:
                    for key, value in {"loan": report["loanId"], "marker": report["marker"],
                                       "tool": tool, "owned_instance": owned.instance}.items():
                        cursor.execute("select set_config(%s,%s,true)", ("test.tl10_" + key, value))
                    sql = (ROOT / "tools/d2-issue/verify-withdraw-after-issue.sql").read_text()
                    start, stop = sql.index("do $$"), sql.index("\nend $$;")
                    cursor.execute(sql[start:stop + len("\nend $$;")])
                print("TL10_READ_ONLY_DATABASE_VERIFIED", flush=True)
    output = ROOT / "artifacts/tl10/result.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("x") as stream:
        json.dump({"commit": target.commit, "jar_sha256": jar_sha, "postgres_image": IMAGE,
                   "scenario": "issue-before-stale-withdrawal", "issue_http": 200, "withdraw_http": 409,
                   "reread_state": "ISSUED", "reread_version": 2, "database_evidence": "PASS",
                   "owned_resources_removed": True}, stream, indent=2)
    print("TL10_VERIFIED_AFTER_CLEANUP", flush=True)


if __name__ == "__main__":
    try:
        run()
    except Exception as failure:
        # Annotate only our stage and source coordinate; do not emit response bodies or raw subprocess logs.
        coordinate = traceback.extract_tb(failure.__traceback__)[-1]
        print(f"TL10_FAILED {type(failure).__name__} {Path(coordinate.filename).name}:{coordinate.lineno}", file=sys.stderr)
        raise SystemExit(1) from None

#!/usr/bin/env python3
"""Isolated PostgreSQL 18 capture acceptance. All process output stays private."""
from __future__ import annotations

import json
import os
import re
import secrets
import shutil
import signal
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path

REPOSITORY = "mightyoung/might-software-factory"
DATABASE = "msf_ci_risk_capture"
IMAGE_TAG = "docker.io/library/postgres:18-bookworm"
ADMIN = "msf_ci_bootstrap"
VERSIONS = tuple(map(str, (*range(2, 9), *range(11, 25))))
IT_CLASS = "com.mightyoung.softwarefactory.dualprevention.risk.RiskChangeCapturePostgresIT"
UNIT_CLASS = "com.mightyoung.softwarefactory.dualprevention.risk.CiPg18CaptureTargetTest"
SCENARIOS = frozenset((
    "fullMigrationHistoryAndExactCauseSchema",
    "firstReplayAndActorRotationKeepOriginalAttribution",
    "runtimePrivilegesAndTenantRlsAreExact",
    "forgedSourceTupleCreatesNoCause",
    "missingInstantExecuteRefusesAndRestoredGrantCaptures",
    "runtimeMutationPrivilegesDeny",
    "ownerMutationTriggersReject55000",
    "winnerCommitBlocksThenWaiterReplays",
    "winnerRollbackBlocksThenWaiterInserts",
))
FAILURE_PHASES = frozenset((
    "migrate", "open", "admin-connect", "runtime-connect", "target-version",
    "target-marker", "empty-schema", "empty-runtime", "seed-tenant",
    "seed-template", "seed-graph", "history", "schema", "cause-table",
    "cause-fks", "cause-triggers", "cause-key", "runtime-role",
    "runtime-columns", "runtime-required", "runtime-rights", "runtime-login",
    "runtime-table", "instant-function", "cause-count", "source-snapshot", "cause-columns", "cause-checks", "cause-policy",
    "closed", "unknown",
))
LABEL_PREFIX = "com.mightyoung.ci.pg18."
MAX_JSON = 131072
MAX_XML = 4194304


class Rejected(Exception):
    """A fixed stage identifier only. Never wrap raw process or SQL errors."""
    def __init__(self, stage, exit_code=None):
        super().__init__(stage)
        self.exit_code = exit_code


def reject(stage):
    raise Rejected(stage)


def bounded_bytes(path, maximum, stage):
    try:
        if path.is_symlink() or not path.is_file():
            reject(stage)
        with path.open("rb") as handle:
            data = handle.read(maximum + 1)
        if not data or len(data) > maximum:
            reject(stage)
        return data
    except OSError:
        reject(stage)


def load_json(path):
    try:
        def unique_pairs(pairs):
            result = {}
            for key, value in pairs:
                if key in result:
                    reject("EVIDENCE_JSON")
                result[key] = value
            return result
        return json.loads(bounded_bytes(path, MAX_JSON, "EVIDENCE_JSON"),
                          object_pairs_hook=unique_pairs)
    except (ValueError, UnicodeError):
        reject("EVIDENCE_JSON")


def validate_success_evidence(value, digest):
    keys = {"schemaVersion", "postgresMajor", "database", "instanceMarkerVerified",
            "imageDigest", "migrations", "causeSchemaVerified", "runtimeRightsVerified"}
    if not isinstance(value, dict) or set(value) != keys:
        reject("SUCCESS_EVIDENCE")
    if (type(value["schemaVersion"]) is not int or value["schemaVersion"] != 1
            or type(value["postgresMajor"]) is not int or value["postgresMajor"] != 18
            or value["database"] != DATABASE or value["imageDigest"] != digest
            or value["instanceMarkerVerified"] is not True
            or value["causeSchemaVerified"] is not True
            or value["runtimeRightsVerified"] is not True):
        reject("SUCCESS_EVIDENCE")
    migrations = value["migrations"]
    if not isinstance(migrations, list) or len(migrations) != len(VERSIONS):
        reject("MIGRATION_EVIDENCE")
    found = {}
    for row in migrations:
        if not isinstance(row, dict) or set(row) != {"version", "script", "checksum"}:
            reject("MIGRATION_EVIDENCE")
        version, script, checksum = row["version"], row["script"], row["checksum"]
        if (not isinstance(version, str) or version not in VERSIONS or version in found
                or not isinstance(script, str)
                or not re.fullmatch(r"V" + re.escape(version) + r"__[A-Za-z0-9_]+\.sql", script)
                or type(checksum) is not int or not -(2**31) <= checksum < 2**31):
            reject("MIGRATION_EVIDENCE")
        found[version] = {"version": version, "script": script, "checksum": checksum}
    return {"schemaVersion": 1, "postgresMajor": 18, "database": DATABASE,
            "instanceMarkerVerified": True, "imageDigest": digest,
            "migrations": [found[version] for version in VERSIONS],
            "causeSchemaVerified": True, "runtimeRightsVerified": True}


def validate_failure_evidence(value):
    if not isinstance(value, dict) or set(value) != {"schemaVersion", "phase", "migrationVersion", "sqlState"}:
        reject("FAILURE_EVIDENCE")
    if (type(value["schemaVersion"]) is not int or value["schemaVersion"] != 1
            or not isinstance(value["phase"], str) or value["phase"] not in FAILURE_PHASES
            or (value["migrationVersion"] is not None and value["migrationVersion"] not in VERSIONS)
            or (value["sqlState"] is not None and
                (not isinstance(value["sqlState"], str) or not re.fullmatch(r"[0-9A-Z]{5}", value["sqlState"])))):
        reject("FAILURE_EVIDENCE")
    return {key: value[key] for key in ("schemaVersion", "phase", "migrationVersion", "sqlState")}


def validate_report(path, classname, scenarios=None):
    data = bounded_bytes(path, MAX_XML, "REPORT_MISSING_OR_LARGE")
    if b"<!DOCTYPE" in data.upper() or b"<!ENTITY" in data.upper():
        reject("REPORT_XML")
    try:
        root = ET.fromstring(data)
        if root.tag != "testsuite" or root.get("name") != classname:
            reject("REPORT_IDENTITY")
        counts = {key: int(root.attrib[key]) for key in ("tests", "failures", "errors", "skipped")}
        cases = root.findall("testcase")
        if counts["tests"] < 1 or counts["tests"] != len(cases) or any(
                counts[key] != 0 for key in ("failures", "errors", "skipped")):
            reject("REPORT_NOT_PASS")
        names = []
        for case in cases:
            if case.get("classname") != classname or not case.get("name") or any(child.tag not in {"system-out", "system-err"} for child in case):
                reject("REPORT_CASE")
            names.append(case.get("name"))
        if scenarios is not None and (len(names) != len(scenarios) or set(names) != scenarios):
            reject("REPORT_SCENARIOS")
        return counts["tests"]
    except (ET.ParseError, KeyError, TypeError, ValueError):
        reject("REPORT_XML")


def verify_reports(repo):
    base = repo / "backend" / "target"
    integration = validate_report(base / "failsafe-reports" / ("TEST-" + IT_CLASS + ".xml"),
                                  IT_CLASS, SCENARIOS)
    unit = validate_report(base / "surefire-reports" / ("TEST-" + UNIT_CLASS + ".xml"), UNIT_CLASS)
    # No other Failsafe suites may silently enter this dedicated acceptance target.
    if set((base / "failsafe-reports").glob("TEST-*.xml")) != {
            base / "failsafe-reports" / ("TEST-" + IT_CLASS + ".xml")}:
        reject("REPORT_EXTRA_SUITE")
    return {"integrationTests": integration, "targetGuardTests": unit,
            "failures": 0, "errors": 0, "skipped": 0, "scenarios": sorted(SCENARIOS)}


def report_diagnostics(repo):
    """Whitelist counts and known scenario states, never failure text or properties."""
    result = {"scenarios": {name: "missing" for name in sorted(SCENARIOS)}}
    for kind, folder, classname in (("integration", "failsafe-reports", IT_CLASS),
                                    ("targetGuard", "surefire-reports", UNIT_CLASS)):
        path = repo / "backend" / "target" / folder / ("TEST-" + classname + ".xml")
        try:
            data = bounded_bytes(path, MAX_XML, "REPORT_DIAGNOSTIC")
            if b"<!DOCTYPE" in data.upper() or b"<!ENTITY" in data.upper():
                reject("REPORT_DIAGNOSTIC")
            tree = ET.fromstring(data)
            if tree.tag != "testsuite" or tree.get("name") != classname:
                reject("REPORT_DIAGNOSTIC")
            counts = {key: int(tree.attrib[key]) for key in ("tests", "failures", "errors", "skipped")}
            if any(not 0 <= value <= 10000 for value in counts.values()):
                reject("REPORT_DIAGNOSTIC")
            result[kind] = counts
            if kind == "integration":
                seen = set()
                for case in tree.findall("testcase"):
                    if case.get("classname") != classname:
                        continue
                    name = case.get("name")
                    if name not in SCENARIOS:
                        continue
                    if name in seen:
                        result["scenarios"][name] = "duplicate"
                        continue
                    seen.add(name)
                    tags = {child.tag for child in case}
                    state = "error" if "error" in tags else "failed" if "failure" in tags else "skipped" if "skipped" in tags else "passed"
                    result["scenarios"][name] = state
        except (Rejected, ET.ParseError, ValueError, TypeError, KeyError):
            result[kind] = {"available": False}
    return result


def active_process_group(pgid, marker, proc_root=Path("/proc")):
    """Linux session members must carry this private run's marker before signaling."""
    active = []
    try:
        for entry in proc_root.iterdir():
            if not entry.name.isdigit():
                continue
            try:
                text = (entry / "stat").read_text()
                fields = text[text.rfind(")") + 2:].split()
                if len(fields) < 4 or int(fields[2]) != pgid or int(fields[3]) != pgid or fields[0] == "Z":
                    continue
                if entry.stat().st_uid != os.getuid():
                    reject("MAVEN_GROUP_OWNERSHIP")
                with (entry / "environ").open("rb") as handle:
                    environment = handle.read(262145)
                if len(environment) > 262144 or ("MSF_PG18_PROCESS_MARKER=" + marker).encode() not in environment.split(b"\0"):
                    reject("MAVEN_GROUP_OWNERSHIP")
                active.append(int(entry.name))
            except (FileNotFoundError, ProcessLookupError):
                continue
        return active
    except (OSError, ValueError, IndexError):
        reject("MAVEN_GROUP_OBSERVATION")


def terminate_owned_group(pgid, marker):
    for signum, seconds in ((signal.SIGTERM, 3), (signal.SIGKILL, 3)):
        if not active_process_group(pgid, marker):
            return
        try:
            os.killpg(pgid, signum)
        except ProcessLookupError:
            pass
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            if not active_process_group(pgid, marker):
                return
            time.sleep(0.05)
    reject("MAVEN_GROUP_STILL_ACTIVE")



def stop_maven_group(state):
    path = state / "maven-process.json"
    if not path.exists():
        if (state / "maven-starting").exists():
            reject("MAVEN_GROUP_UNKNOWN")
        return
    record = load_json(path)
    if (not isinstance(record, dict) or set(record) != {"pgid", "marker"}
            or type(record["pgid"]) is not int or record["pgid"] <= 1
            or not isinstance(record["marker"], str)
            or not re.fullmatch(r"[0-9a-f]{32}", record["marker"])):
        reject("MAVEN_GROUP_RECEIPT")
    terminate_owned_group(record["pgid"], record["marker"])
    path.unlink()
    (state / "maven-starting").unlink(missing_ok=True)


def run_maven(repo, state, environment):
    marker = secrets.token_hex(16)
    child_environment = dict(environment, MSF_PG18_PROCESS_MARKER=marker)
    private_write(state / "maven-starting", marker)
    with (state / "maven.log").open("wb") as output:
        process = subprocess.Popen(
            [str(repo / "mvnw"), "--batch-mode", "--no-transfer-progress",
             "-Pci-pg18-risk-capture", "clean", "verify"],
            cwd=repo, env=child_environment, stdout=output, stderr=subprocess.STDOUT,
            start_new_session=True)
        try:
            private_write(state / "maven-process.json", json.dumps({"pgid": process.pid, "marker": marker}))
            try:
                return process.wait(timeout=900)
            except subprocess.TimeoutExpired:
                reject("MAVEN_TIMEOUT")
        finally:
            # Terminate any still-running forked JVM in this exact marked session.
            terminate_owned_group(process.pid, marker)
            (state / "maven-process.json").unlink(missing_ok=True)
            (state / "maven-starting").unlink(missing_ok=True)
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                reject("MAVEN_WAIT")


def command(arguments, timeout=30, allow_failure=False, environment=None, cwd=None, failure_stage="PROCESS"):
    try:
        result = subprocess.run(arguments, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                timeout=timeout, check=False, env=environment, cwd=cwd, text=True)
    except (OSError, subprocess.TimeoutExpired):
        reject(failure_stage + "_UNAVAILABLE")
    if result.returncode and not allow_failure:
        raise Rejected(failure_stage + "_EXIT", result.returncode)
    return result


def docker_field(identity, expression):
    result = command(["docker", "inspect", "--type", "container", "--format",
                      "{{json " + expression + "}}", identity])
    try:
        return json.loads(result.stdout)
    except ValueError:
        reject("CONTAINER_INSPECT")


def context(environment):
    if (environment.get("GITHUB_ACTIONS") != "true" or environment.get("CI") != "true"
            or environment.get("GITHUB_REPOSITORY") != REPOSITORY):
        reject("CI_CONTEXT")
    if any((key.startswith("LAN_PG_") or key.startswith("PG18_TUNNEL_")
            or key.startswith("MSF_CI_PG18_")) and value
           for key, value in environment.items()):
        reject("FOREIGN_TARGET")
    run, attempt = environment.get("GITHUB_RUN_ID", ""), environment.get("GITHUB_RUN_ATTEMPT", "")
    if not re.fullmatch(r"[1-9][0-9]{0,19}", run) or not re.fullmatch(r"[1-9][0-9]{0,9}", attempt):
        reject("CI_CONTEXT")
    try:
        temporary = Path(environment["RUNNER_TEMP"]).resolve(strict=True)
    except (KeyError, OSError):
        reject("CI_CONTEXT")
    if not temporary.is_dir():
        reject("CI_CONTEXT")
    state = temporary / ("msf-ci-pg18-" + run + "-" + attempt)
    return run, attempt, state


def expected_labels(run, attempt, marker):
    return {LABEL_PREFIX + "kind": "risk-capture", LABEL_PREFIX + "repository": REPOSITORY,
            LABEL_PREFIX + "run": run, LABEL_PREFIX + "attempt": attempt,
            LABEL_PREFIX + "instance": marker}


def private_write(path, content):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
        handle.write(content)


def validate_intent(value, run, attempt):
    if (not isinstance(value, dict) or set(value) != {"name", "instance"}
            or value["name"] != "msf-ci-pg18-" + run + "-" + attempt):
        reject("CLEANUP_INTENT")
    try:
        if str(uuid.UUID(value["instance"])) != value["instance"]:
            reject("CLEANUP_INTENT")
    except (ValueError, TypeError, AttributeError):
        reject("CLEANUP_INTENT")
    return value


def validate_resources(value, run, attempt, marker):
    if (not isinstance(value, dict) or set(value) != {"container_id", "volumes", "run_id", "run_attempt", "instance", "name"}
            or value.get("run_id") != run or value.get("run_attempt") != attempt
            or value.get("instance") != marker or value.get("name") != "msf-ci-pg18-" + run + "-" + attempt
            or not isinstance(value["container_id"], str)
            or not re.fullmatch(r"[0-9a-f]{64}", value["container_id"])
            or not isinstance(value["volumes"], list) or len(value["volumes"]) != 1
            or not isinstance(value["volumes"][0], str)
            or not re.fullmatch(r"[0-9a-f]{64}", value["volumes"][0])):
        reject("CLEANUP_RESOURCES")
    return value


def verify_volume_absent(name):
    remaining = command(["docker", "volume", "ls", "--format", "{{.Name}}"])
    if name in remaining.stdout.splitlines():
        reject("CLEANUP_VOLUME_REMAINS")


def cleanup_container(state, run, attempt):
    if not state.exists():
        return {"verified": False, "notCreated": True}
    if state.is_symlink() or not state.is_dir():
        reject("CLEANUP_STATE")
    stop_maven_group(state)
    intent_file = state / "intent.json"
    if not intent_file.exists():
        # State was created before Docker was invoked.
        shutil.rmtree(state)
        return {"verified": True, "notCreated": True}
    intent = validate_intent(load_json(intent_file), run, attempt)
    listed = command(["docker", "ps", "-a", "--no-trunc", "--filter",
                      "name=^/" + intent["name"] + "$", "--format", "{{.ID}}"])
    identifiers = listed.stdout.split()
    if not identifiers:
        resources_file = state / "resources.json"
        if not resources_file.exists():
            scrub_private(state)
            return {"verified": False, "containerAbsent": True, "anonymousVolumesUnknown": True}
        resources = validate_resources(load_json(resources_file), run, attempt, intent["instance"])
        for name in resources["volumes"]:
            remaining = command(["docker", "volume", "ls", "--format", "{{.Name}}"])
            if name in remaining.stdout.splitlines():
                command(["docker", "volume", "rm", name])
            verify_volume_absent(name)
        shutil.rmtree(state)
        return {"verified": True, "containerRemoved": True, "anonymousVolumesRemoved": True}
    if len(identifiers) != 1 or not re.fullmatch(r"[0-9a-f]{64}", identifiers[0]):
        reject("CLEANUP_IDENTITY")
    identity = identifiers[0]
    resources = None
    if (state / "resources.json").exists():
        resources = validate_resources(load_json(state / "resources.json"), run, attempt, intent["instance"])
        if resources["container_id"] != identity:
            reject("CLEANUP_IDENTITY")
    labels = docker_field(identity, ".Config.Labels")
    if not isinstance(labels, dict) or any(labels.get(key) != value
            for key, value in expected_labels(run, attempt, intent["instance"]).items()):
        reject("CLEANUP_OWNERSHIP")
    mounts = docker_field(identity, ".Mounts")
    mounts_valid = isinstance(mounts, list) and len(mounts) == 1 and all(isinstance(mount, dict)
            and mount.get("Type") == "volume" and mount.get("Destination") == "/var/lib/postgresql"
            and re.fullmatch(r"[0-9a-f]{64}", mount.get("Name", "")) for mount in mounts)
    if not mounts_valid:
        reject("CLEANUP_MOUNTS")
    volume_names = [mount["Name"] for mount in mounts]
    if resources is not None and set(resources["volumes"]) != set(volume_names):
        reject("CLEANUP_VOLUME_DRIFT")
    command(["docker", "rm", "--force", "--volumes", identity])
    remaining = command(["docker", "ps", "-a", "--no-trunc", "--filter",
                         "id=" + identity, "--format", "{{.ID}}"])
    if remaining.stdout.strip():
        reject("CLEANUP_CONTAINER_REMAINS")
    for name in volume_names:
        verify_volume_absent(name)
    shutil.rmtree(state)
    return {"verified": True, "containerRemoved": True, "anonymousVolumesRemoved": True}


def scrub_private(state):
    if state.is_symlink() or not state.is_dir():
        return
    if (state / "maven-process.json").exists() or (state / "maven-starting").exists():
        reject("MAVEN_GROUP_UNVERIFIED")
    for name in ("admin.password", "runtime.password", "postgres.env", "maven.log"):
        (state / name).unlink(missing_ok=True)


def create_container(state, run, attempt):
    version = command(["docker", "version", "--format", "{{.Server.Version}}"], failure_stage="ENGINE_VERSION").stdout.strip()
    major = version.split(".", 1)[0]
    if not re.fullmatch(r"[1-9][0-9]{0,2}", major) or int(major) < 28:
        reject("ENGINE_VERSION")
    command(["docker", "pull", "--platform", "linux/amd64", IMAGE_TAG], timeout=180, failure_stage="IMAGE_PULL")
    raw = command(["docker", "image", "inspect", "--format", "{{json .RepoDigests}}", IMAGE_TAG], failure_stage="IMAGE_DIGEST").stdout
    try:
        candidates = json.loads(raw)
        if not isinstance(candidates, list):
            reject("IMAGE_DIGEST")
        digests = {match.group(1) for value in candidates if isinstance(value, str)
                   and (match := re.fullmatch(r"(?:docker\.io/library/)?postgres@(sha256:[0-9a-f]{64})", value))}
    except ValueError:
        reject("IMAGE_DIGEST")
    if len(digests) != 1:
        reject("IMAGE_DIGEST")
    digest = digests.pop()
    pinned = "docker.io/library/postgres@" + digest
    marker = str(uuid.uuid4())
    name = "msf-ci-pg18-" + run + "-" + attempt
    private_write(state / "intent.json", json.dumps({"name": name, "instance": marker}))
    admin_password, runtime_password = secrets.token_hex(32), secrets.token_hex(32)
    if admin_password == runtime_password:
        reject("PASSWORD_GENERATION")
    for password in (admin_password, runtime_password):
        print("::add-mask::" + password, flush=True)
    private_write(state / "admin.password", admin_password)
    private_write(state / "runtime.password", runtime_password)
    private_write(state / "postgres.env", "POSTGRES_USER=" + ADMIN + "\nPOSTGRES_DB=" + DATABASE
                  + "\nPOSTGRES_PASSWORD=" + admin_password
                  + "\nPOSTGRES_INITDB_ARGS=--auth-host=scram-sha-256\n")
    arguments = ["docker", "run", "--detach", "--name", name, "--platform", "linux/amd64",
                 "--publish", "127.0.0.1::5432/tcp", "--env-file", str(state / "postgres.env"),
                 "--memory", "1g", "--cpus", "2", "--shm-size", "128m"]
    for key, value in expected_labels(run, attempt, marker).items():
        arguments.extend(["--label", key + "=" + value])
    arguments.extend([pinned, "postgres", "-c", "msf.ci_instance=" + marker,
                      "-c", "log_statement=none", "-c", "log_min_error_statement=panic",
                      "-c", "log_parameter_max_length_on_error=0"])
    identity = command(arguments, timeout=60, failure_stage="CONTAINER_START").stdout.strip()
    if not re.fullmatch(r"[0-9a-f]{64}", identity):
        reject("CONTAINER_ID")
    if docker_field(identity, ".Config.Image") != pinned:
        reject("CONTAINER_IMAGE")
    labels = docker_field(identity, ".Config.Labels")
    if not isinstance(labels, dict) or any(labels.get(key) != value
            for key, value in expected_labels(run, attempt, marker).items()):
        reject("CONTAINER_LABELS")
    ports = docker_field(identity, ".NetworkSettings.Ports")
    if not isinstance(ports, dict) or set(ports) != {"5432/tcp"}:
        reject("CONTAINER_PORT")
    bindings = ports["5432/tcp"]
    if (not isinstance(bindings, list) or len(bindings) != 1 or not isinstance(bindings[0], dict)
            or set(bindings[0]) != {"HostIp", "HostPort"} or bindings[0]["HostIp"] != "127.0.0.1"
            or not isinstance(bindings[0]["HostPort"], str)
            or not re.fullmatch(r"[1-9][0-9]{0,4}", bindings[0]["HostPort"])):
        reject("CONTAINER_PORT")
    port = bindings[0]["HostPort"]
    if not 1024 <= int(port) <= 65535:
        reject("CONTAINER_PORT")
    mounts = docker_field(identity, ".Mounts")
    if (not isinstance(mounts, list) or len(mounts) != 1 or not isinstance(mounts[0], dict) or mounts[0].get("Type") != "volume"
            or mounts[0].get("Destination") != "/var/lib/postgresql"
            or not re.fullmatch(r"[0-9a-f]{64}", mounts[0].get("Name", ""))):
        reject("CONTAINER_MOUNTS")
    private_write(state / "resources.json", json.dumps({"container_id": identity, "volumes": [mounts[0]["Name"]],
                  "run_id": run, "run_attempt": attempt, "instance": marker, "name": name}))
    ownership = {"container_id": identity, "instance_id": marker, "host": "127.0.0.1", "port": port,
                 "database": DATABASE, "image_digest": digest, "repository": REPOSITORY,
                 "run_id": run, "run_attempt": attempt, "engine_major": major}
    private_write(state / "ownership", "".join(key + "=" + value + "\n" for key, value in ownership.items()))
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        readiness = command(["docker", "exec", identity, "pg_isready", "-U", ADMIN, "-d", DATABASE],
                            timeout=5, allow_failure=True)
        if readiness.returncode == 0:
            return ownership
        time.sleep(0.5)
    reject("POSTGRES_READINESS")


def result_path(repo):
    return repo / "artifacts" / "ci-pg18-risk-capture" / "result.json"


def write_result(repo, result):
    path = result_path(repo)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")


def migration_diagnostics(path):
    """Keep only known migration numbers and numeric SQL positions from private logs."""
    try:
        data = bounded_bytes(path, 8 * 1024 * 1024, "MIGRATION_LOG").decode("utf-8", errors="replace")
    except Rejected:
        return {}
    versions = re.findall(r'Migrating schema [^\r\n]*? to version "([0-9]+)(?: -|")', data)
    positions = re.findall(r"(?m)^\s*(?:Position|Line)\s*:\s*([0-9]{1,7})\s*$", data)
    output = {}
    if versions and versions[-1] in VERSIONS:
        output["lastMigrationVersion"] = versions[-1]
    if positions:
        output["errorPositions"] = [int(value) for value in positions[-4:]]
    return output


def public_diagnostics(result):
    """Render only known test outcomes and validated database evidence."""
    output = {"status": "PASS" if result.get("status") == "PASS" else "FAILED",
              "cleanupVerified": result.get("cleanup", {}).get("verified") is True}
    diagnostics = result.get("reportDiagnostics", {})
    if isinstance(diagnostics, dict):
        scenarios = diagnostics.get("scenarios", {})
        if isinstance(scenarios, dict):
            output["scenarios"] = {name: scenarios[name] for name in sorted(SCENARIOS)
                                   if scenarios.get(name) in {"passed", "failed", "error", "skipped", "missing", "duplicate"}}
        for kind in ("integration", "targetGuard"):
            counts = diagnostics.get(kind, {})
            if isinstance(counts, dict):
                output[kind] = {key: counts[key] for key in ("tests", "failures", "errors", "skipped")
                                if type(counts.get(key)) is int and counts[key] >= 0}
    if "databaseFailure" in result:
        output["databaseFailure"] = validate_failure_evidence(result["databaseFailure"])
    if "databaseEvidence" in result:
        digest = result.get("imageDigest")
        if isinstance(digest, str) and re.fullmatch(r"sha256:[0-9a-f]{64}", digest):
            output["databaseEvidence"] = validate_success_evidence(result["databaseEvidence"], digest)
    migration = result.get("migrationDiagnostics", {})
    if isinstance(migration, dict):
        version = migration.get("lastMigrationVersion")
        if version in VERSIONS:
            output["lastMigrationVersion"] = version
        positions = migration.get("errorPositions")
        if isinstance(positions, list) and len(positions) <= 4 and all(type(value) is int and 0 <= value < 10000000 for value in positions):
            output["errorPositions"] = positions
    return output


def run_acceptance(repo, environment):
    run, attempt, state = context(environment)
    if state.exists() or state.is_symlink():
        reject("STATE_ALREADY_EXISTS")
    os.umask(0o077)
    state.mkdir(mode=0o700)
    result = {"schemaVersion": 1, "status": "FAILED", "stage": "INITIALIZE",
              "cleanup": {"verified": False}}
    write_result(repo, result)
    stage = "INITIALIZE"
    try:
        stage = "CONTAINER"
        ownership = create_container(state, run, attempt)
        result["imageDigest"] = ownership["image_digest"]
        result["engineMajor"] = int(ownership["engine_major"])
        child = dict(environment)
        child.update({
            "MSF_CI_PG18_ACK": "ephemeral-risk-capture-only",
            "MSF_CI_PG18_URL": "jdbc:postgresql://127.0.0.1:" + ownership["port"] + "/" + DATABASE,
            "MSF_CI_PG18_CONTAINER_ID": ownership["container_id"],
            "MSF_CI_PG18_INSTANCE_ID": ownership["instance_id"],
            "MSF_CI_PG18_IMAGE_DIGEST": ownership["image_digest"],
            "MSF_CI_PG18_OWNERSHIP_FILE": str(state / "ownership"),
            "MSF_CI_PG18_ADMIN_PASSWORD_FILE": str(state / "admin.password"),
            "MSF_CI_PG18_RUNTIME_PASSWORD_FILE": str(state / "runtime.password"),
        })
        stage = "MAVEN_VERIFY"
        maven_exit = run_maven(repo, state, child)
        result["mavenExit"] = maven_exit
        if maven_exit:
            result["migrationDiagnostics"] = migration_diagnostics(state / "maven.log")
            result["reportDiagnostics"] = report_diagnostics(repo)
            failure = repo / "backend" / "target" / "ci-pg18-safe-failure.json"
            if failure.exists():
                result["databaseFailure"] = validate_failure_evidence(load_json(failure))
            reject("MAVEN_VERIFY")
        stage = "REPORT_VERIFY"
        result["tests"] = verify_reports(repo)
        stage = "DATABASE_EVIDENCE"
        result["databaseEvidence"] = validate_success_evidence(
            load_json(repo / "backend" / "target" / "ci-pg18-safe-evidence.json"), ownership["image_digest"])
        result["status"], result["stage"] = "PASS", "COMPLETE"
    except Rejected as failure:
        result["stage"] = str(failure)
        if failure.exit_code is not None:
            result["processExit"] = failure.exit_code
    except Exception:
        result["stage"] = stage
    finally:
        if stage == "MAVEN_VERIFY" and result["status"] != "PASS":
            result["reportDiagnostics"] = report_diagnostics(repo)
        try:
            result["cleanup"] = cleanup_container(state, run, attempt)
            if not result["cleanup"].get("verified"):
                result["status"], result["stage"] = "FAILED", "CLEANUP_UNVERIFIED"
        except Exception:
            result["status"], result["stage"] = "FAILED", "CLEANUP_FAILED"
        try:
            scrub_private(state)
        except Exception:
            result["status"], result["stage"] = "FAILED", "PRIVATE_CLEANUP_FAILED"
        write_result(repo, result)
    print(json.dumps({"status": result["status"], "stage": result["stage"],
                      "cleanupVerified": result["cleanup"].get("verified", False)}), flush=True)
    print(json.dumps(public_diagnostics(result)), flush=True)
    return 0 if result["status"] == "PASS" else 1


def always_cleanup(repo, environment):
    run, attempt, state = context(environment)
    path = result_path(repo)
    previous = load_json(path) if path.exists() else {
        "schemaVersion": 1, "status": "FAILED", "stage": "RUN_NOT_COMPLETED"}
    if not state.exists():
        if previous.get("cleanup", {}).get("verified") is True:
            return 0
        previous["cleanup"] = {"verified": False, "notCreated": True}
        previous["status"], previous["stage"] = "FAILED", "CLEANUP_UNVERIFIED"
        write_result(repo, previous)
        return 1
    try:
        previous["cleanup"] = cleanup_container(state, run, attempt)
    except Exception:
        previous["status"], previous["stage"] = "FAILED", "CLEANUP_FAILED"
        previous["cleanup"] = {"verified": False}
        try:
            scrub_private(state)
        except Exception:
            previous["stage"] = "PRIVATE_CLEANUP_FAILED"
        write_result(repo, previous)
        return 1
    scrub_private(state)
    if not previous["cleanup"].get("verified"):
        previous["status"], previous["stage"] = "FAILED", "CLEANUP_UNVERIFIED"
    write_result(repo, previous)
    return 0 if previous["cleanup"].get("verified") else 1


def main():
    repo = Path(__file__).resolve().parents[2]
    def interrupted(_signum, _frame):
        reject("INTERRUPTED")
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    try:
        if sys.argv[1:] == ["run"]:
            return run_acceptance(repo, os.environ)
        if sys.argv[1:] == ["cleanup"]:
            return always_cleanup(repo, os.environ)
        reject("COMMAND")
    except Exception:
        print("CI PostgreSQL harness rejected", flush=True)
        return 1


if __name__ == "__main__":
    sys.exit(main())

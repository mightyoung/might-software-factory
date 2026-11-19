#!/usr/bin/env python3
"""Fixed isolated PostgreSQL 18 lanes. Capture is the unchanged default entry."""
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
from dataclasses import dataclass
from pathlib import Path

REPOSITORY = "mightyoung/might-software-factory"
IMAGE_TAG = "docker.io/library/postgres:18-bookworm"
ADMIN = "msf_ci_bootstrap"
VERSIONS = (*map(str, range(2, 9)), *map(str, range(11, 19)), "18.1", "18.2", "18.3", *map(str, range(19, 36)))


@dataclass(frozen=True)
class _LaneSpec:
    kind: str
    database: str
    runtime_role: str
    ack: str
    profile: str
    state_prefix: str
    artifact_directory: str
    it_class: str
    unit_class: str
    scenarios: frozenset[str]
    schema_field: str
    evidence_file: str
    failure_file: str
    failure_phases: frozenset[str]
    guard_count: int | None = None


CAPTURE = _LaneSpec(
    kind="risk-capture", database="msf_ci_risk_capture", runtime_role="msf_ci_capture_runtime",
    ack="ephemeral-risk-capture-only", profile="ci-pg18-risk-capture",
    state_prefix="msf-ci-pg18-", artifact_directory="ci-pg18-risk-capture",
    it_class="com.mightyoung.softwarefactory.dualprevention.risk.RiskChangeCapturePostgresIT",
    unit_class="com.mightyoung.softwarefactory.dualprevention.risk.CiPg18CaptureTargetTest",
    scenarios=frozenset((
        "fullMigrationHistoryAndExactCauseSchema",
        "firstReplayAndActorRotationKeepOriginalAttribution",
        "runtimePrivilegesAndTenantRlsAreExact",
        "forgedSourceTupleCreatesNoCause",
        "missingInstantExecuteRefusesAndRestoredGrantCaptures",
        "runtimeMutationPrivilegesDeny",
        "ownerMutationTriggersReject55000",
        "winnerCommitBlocksThenWaiterReplays",
        "winnerRollbackBlocksThenWaiterInserts",
    )),
    schema_field="causeSchemaVerified", evidence_file="ci-pg18-safe-evidence.json",
    failure_file="ci-pg18-safe-failure.json", failure_phases=frozenset((
        "migrate", "open", "admin-connect", "runtime-connect", "target-version",
        "target-marker", "empty-schema", "empty-runtime", "seed-tenant",
        "seed-template", "seed-graph", "history", "schema", "cause-table",
        "cause-fks", "cause-triggers", "cause-key", "runtime-role",
        "runtime-columns", "runtime-required", "runtime-rights", "runtime-login",
        "runtime-table", "instant-function", "cause-count", "source-snapshot", "cause-columns", "cause-checks", "cause-policy",
        "closed", "unknown",
    )))
REGISTRY = _LaneSpec(
    kind="risk-registry", database="msf_ci_risk_registry", runtime_role="msf_ci_registry_runtime",
    ack="ephemeral-risk-registry-only", profile="ci-pg18-risk-registry",
    state_prefix="msf-ci-pg18-risk-registry-", artifact_directory="ci-pg18-risk-registry",
    it_class="com.mightyoung.softwarefactory.support.RiskNativePublicationPostgresIT",
    unit_class="com.mightyoung.softwarefactory.support.CiPg18RegistryTargetTest",
    scenarios=frozenset((
        "originalMigrationsAndRegistryPrivilegesAreExact",
        "actualJacksonAndSevenNativeSqlPaths",
        "pgBaselineAttributesAreRestored",
        "ackCommitThenThrowKeepsDurableCompletion",
        "registrationCommitThenThrowKeepsOneIncompletePublication",
        "committedResetFailureStopsClose",
        "committedCloseFailureIsNotRetried",
        "statementFailureRollsBackCompletion",
        "disposalSerializationFailureIsSafeAndReadOnly",
    )), schema_field="publicationSchemaVerified", evidence_file="ci-pg18-registry-safe-evidence.json",
    failure_file="ci-pg18-registry-safe-failure.json", failure_phases=frozenset((
        "open", "target-version", "target-marker", "empty-schema", "empty-runtime", "migrate", "history",
        "publication-schema", "runtime-role", "runtime-rights", "native-graph", "closed", "unknown",
    )))
SOURCE = _LaneSpec(
    kind="risk-source", database="msf_ci_risk_source", runtime_role="msf_ci_source_runtime",
    ack="ephemeral-risk-source-only", profile="ci-pg18-risk-source",
    state_prefix="msf-ci-pg18-risk-source-", artifact_directory="ci-pg18-risk-source",
    it_class="com.mightyoung.softwarefactory.CiPg18RiskSourceIT",
    unit_class="com.mightyoung.softwarefactory.CiPg18SourceTargetTest",
    scenarios=frozenset((
        "actualCreateCommitsSourceAndNativePublicationTogether",
        "sameKeyReplayAddsNoEffectsAndChangedPayloadConflicts",
        "latePublisherFailureRollsBackAllSourceEffects",
    )), schema_field="sourceSchemaVerified", evidence_file="ci-pg18-source-safe-evidence.json",
    failure_file="ci-pg18-source-safe-failure.json", failure_phases=frozenset((
        "open", "target-version", "target-marker", "empty-schema", "migrate", "history",
        "framework-schema", "source-schema", "runtime-role", "runtime-rights", "seed-identity",
        "seed-foundation", "native-graph", "closed", "unknown",
    )), guard_count=52)
REAUTH = _LaneSpec(
    kind="g008-reauth", database="msf_ci_g008_reauth", runtime_role="msf_ci_reauth_runtime",
    ack="ephemeral-g008-reauth-only", profile="ci-pg18-g008-reauth",
    state_prefix="msf-ci-pg18-g008-reauth-", artifact_directory="ci-pg18-g008-reauth",
    it_class="com.mightyoung.softwarefactory.identity.CiPg18ReauthPostgresIT",
    unit_class="com.mightyoung.softwarefactory.identity.CiPg18ReauthTargetTest",
    scenarios=frozenset((
        "originalSchemaPrivilegesAndActualGuardAreEnforced",
        "actualIssuerFeedsActualActiveTargetRevoke",
        "absentAuthorityTargetGetsDirectRevocationFence",
        "issuanceAndConsumptionReceiptsReplayWithoutRedeliveringToken",
        "explicitReissueAcrossRetainedKeysReplacesOnlyExactLiveProof",
        "currentCredentialAndSourceChangesMeetBothGuardCommitOrders",
        "lateActualAuditFailureRollsBackIssuanceAndTerminalization",
        "deferredCommitConstraintFailureRollsBackTheWholeGraph",
        "commitThenThrowKeepsOneDurableIssueAndNeverDeliversItsToken",
        "crossAccountSourceTargetAndTenantContextCannotBorrowTheProof",
    )), schema_field="reauthSchemaVerified", evidence_file="ci-pg18-reauth-safe-evidence.json",
    failure_file="ci-pg18-reauth-safe-failure.json", failure_phases=frozenset((
        "open", "target-version", "target-marker", "empty-schema", "migrate", "history",
        "framework-schema", "reauth-schema", "runtime-role", "runtime-rights", "seed-identity",
        "activate-authority", "closed", "unknown",
    )), guard_count=55)
RECOVERY = _LaneSpec(
    kind="risk-recovery", database="msf_ci_risk_recovery", runtime_role="msf_ci_recovery_runtime",
    ack="ephemeral-risk-recovery-only", profile="ci-pg18-risk-recovery",
    state_prefix="msf-ci-pg18-risk-recovery-", artifact_directory="ci-pg18-risk-recovery",
    it_class="com.mightyoung.softwarefactory.CiPg18RecoveryPostgresIT",
    unit_class="com.mightyoung.softwarefactory.CiPg18RecoveryTargetTest",
    scenarios=frozenset((
        "actualSourceNamedListenerCaptureAndAckCommit",
        "causeCommitWithKnownAckRollbackRecoversExactlyOnce",
        "serviceRevocationLeavesIncompleteThenCurrentEligibilityRestoresRecovery",
        "newJvmRecoversPublicationLeftByTerminatedProducer",
        "unknownAckCommitThenThrowIsObservedBeforeAnyFurtherDispatch",
    )), schema_field="recoverySchemaVerified", evidence_file="ci-pg18-recovery-safe-evidence.json",
    failure_file="ci-pg18-recovery-safe-failure.json", failure_phases=frozenset((
        "open", "target-version", "target-marker", "empty-schema", "migrate", "history",
        "framework-schema", "canonical-rights", "capture-rights", "seed-service", "source-producer",
        "source-commit-observed", "service-consumer", "capture-observed", "ack-observed",
        "unknown-ack-observed", "producer-exit", "consumer-start", "child-cleanup", "closed", "unknown",
    )), guard_count=61)
# Legacy capture constants and default call signatures remain the capture lane.
DATABASE, IT_CLASS, UNIT_CLASS = CAPTURE.database, CAPTURE.it_class, CAPTURE.unit_class
SCENARIOS, FAILURE_PHASES = CAPTURE.scenarios, CAPTURE.failure_phases
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


def _spec(lane):
    if lane is not CAPTURE and lane is not REGISTRY and lane is not SOURCE and lane is not REAUTH and lane is not RECOVERY:
        reject("LANE")
    return lane


def _result_lane(result, lane):
    lane = _spec(lane)
    if not isinstance(result, dict):
        reject("RESULT_LANE")
    marker = result.get("acceptanceKind")
    if (lane is not CAPTURE and marker != lane.kind) or (lane is CAPTURE and marker not in (None, lane.kind)):
        reject("RESULT_LANE")
    # A renamed capture artifact is not an authoritative registry acceptance receipt.
    if "databaseEvidence" in result:
        try:
            validate_success_evidence(result["databaseEvidence"], result.get("imageDigest"), lane)
        except Rejected:
            reject("RESULT_LANE")
    if lane is not CAPTURE:
        if type(result.get("schemaVersion")) is not int or result["schemaVersion"] != 1:
            reject("RESULT_LANE")
        if result.get("status") == "PASS":
            tests = result.get("tests")
            if ("databaseEvidence" not in result or result.get("stage") != "COMPLETE"
                    or not isinstance(tests, dict)
                    or set(tests) != {"integrationTests", "targetGuardTests", "failures", "errors", "skipped", "scenarios"}
                    or type(tests.get("integrationTests")) is not int or tests["integrationTests"] != len(lane.scenarios)
                    or type(tests.get("targetGuardTests")) is not int or tests["targetGuardTests"] < 1
                    or lane.guard_count is not None and tests["targetGuardTests"] != lane.guard_count
                    or any(type(tests.get(key)) is not int or tests[key] != 0 for key in ("failures", "errors", "skipped"))
                    or tests.get("scenarios") != sorted(lane.scenarios)):
                reject("RESULT_LANE")
            if lane is SOURCE or lane is REAUTH or lane is RECOVERY:
                revision = result.get("sourceRevision")
                if (not isinstance(revision, dict) or set(revision) != {"commit", "tree"}
                        or any(not isinstance(revision[key], str) or not re.fullmatch(r"[0-9a-f]{40}", revision[key])
                               for key in ("commit", "tree"))):
                    reject("RESULT_LANE")
            if lane is SOURCE:
                try:
                    validate_source_diagnostics(result.get("sourceDiagnostics"), require_complete=True)
                except Rejected:
                    reject("RESULT_LANE")
            if lane is REAUTH:
                try:
                    validate_reauth_commit_diagnostics(result.get("reauthCommitDiagnostics"), require_complete=True)
                except Rejected:
                    reject("RESULT_LANE")
            if lane is RECOVERY:
                try:
                    validate_recovery_process_evidence(result.get("recoveryProcesses"), require_complete=True)
                except Rejected:
                    reject("RESULT_LANE")


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


def validate_success_evidence(value, digest, lane=CAPTURE):
    lane = _spec(lane)
    keys = {"schemaVersion", "postgresMajor", "database", "instanceMarkerVerified",
            "imageDigest", "migrations", lane.schema_field, "runtimeRightsVerified"}
    if lane is RECOVERY:
        keys.add("captureRightsVerified")
    if (not isinstance(value, dict) or set(value) != keys or not isinstance(digest, str)
            or not re.fullmatch(r"sha256:[0-9a-f]{64}", digest)):
        reject("SUCCESS_EVIDENCE")
    if (type(value["schemaVersion"]) is not int or value["schemaVersion"] != 1
            or type(value["postgresMajor"]) is not int or value["postgresMajor"] != 18
            or value["database"] != lane.database or value["imageDigest"] != digest
            or value["instanceMarkerVerified"] is not True
            or value[lane.schema_field] is not True
            or value["runtimeRightsVerified"] is not True):
        reject("SUCCESS_EVIDENCE")
    if lane is RECOVERY and value["captureRightsVerified"] is not True:
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
                or not re.fullmatch(r"V" + re.escape(version).replace(r"\.", "[._]") + r"__[A-Za-z0-9_]+\.sql", script)
                or type(checksum) is not int or not -(2**31) <= checksum < 2**31):
            reject("MIGRATION_EVIDENCE")
        found[version] = {"version": version, "script": script, "checksum": checksum}
    return {"schemaVersion": 1, "postgresMajor": 18, "database": lane.database,
            "instanceMarkerVerified": True, "imageDigest": digest,
            "migrations": [found[version] for version in VERSIONS],
            lane.schema_field: True, "runtimeRightsVerified": True,
            **({"captureRightsVerified": True} if lane is RECOVERY else {})}


def validate_failure_evidence(value, lane=CAPTURE):
    lane = _spec(lane)
    fields = ("schemaVersion", "phase", "migrationVersion", "sqlState")
    if lane is not CAPTURE:
        fields += ("database",)
    if not isinstance(value, dict) or set(value) != set(fields):
        reject("FAILURE_EVIDENCE")
    if (type(value["schemaVersion"]) is not int or value["schemaVersion"] != 1
            or not isinstance(value["phase"], str) or value["phase"] not in lane.failure_phases
            or (value["migrationVersion"] is not None and value["migrationVersion"] not in VERSIONS)
            or (value["sqlState"] is not None and
                (not isinstance(value["sqlState"], str) or not re.fullmatch(r"[0-9A-Z]{5}", value["sqlState"])))
            or (lane is not CAPTURE and value["database"] != lane.database)):
        reject("FAILURE_EVIDENCE")
    return {key: value[key] for key in fields}


def validate_source_diagnostics(value, require_complete=False):
    """Only fixed source phases, numeric outcomes and one-way SQL hashes; never raw failure text."""
    if (not isinstance(value, dict) or set(value) != {"schemaVersion", "database", "cases"}
            or type(value["schemaVersion"]) is not int or value["schemaVersion"] != 1
            or value["database"] != SOURCE.database or not isinstance(value["cases"], list)
            or not 1 <= len(value["cases"]) <= len(SOURCE.scenarios)):
        reject("SOURCE_DIAGNOSTIC")
    operations = {"PREPARE", "EXECUTE_QUERY", "EXECUTE_UPDATE", "EXECUTE", "RESULT_READ", "COMMIT", "ROLLBACK", "CLOSE"}
    phases = {"GRAPH_STARTUP", "GRAPH_READY", "ISSUER_ENTERED", "ISSUER_RETURNED", "ISSUER_FAILED"}
    found = set()
    for row in value["cases"]:
        fields = {"name", "status", "phase", "issuerEntered", "issuerReturned", "lastIssuerStatus",
                  "counts", "failureKind", "lastSuccess", "firstDriverFailure"}
        if (not isinstance(row, dict) or set(row) != fields or not isinstance(row["name"],str) or row["name"] not in SOURCE.scenarios
                or row["name"] in found or not isinstance(row["status"],str) or row["status"] not in {"PASSED", "FAILED"}
                or not isinstance(row["phase"],str) or row["phase"] not in phases
                or type(row["issuerEntered"]) is not int or not 0 <= row["issuerEntered"] <= 16
                or type(row["issuerReturned"]) is not int or not 0 <= row["issuerReturned"] <= row["issuerEntered"]
                or row["failureKind"] is not None and not isinstance(row["failureKind"],str)
                or row["failureKind"] not in {None, "ASSERTION", "RISK_COMMAND", "UNAVAILABLE"}
                or (row["status"] == "PASSED") != (row["failureKind"] is None)
                or row["lastIssuerStatus"] is not None and
                   (type(row["lastIssuerStatus"]) is not int or row["lastIssuerStatus"] not in {400,403,404,409,413,415,503})):
            reject("SOURCE_DIAGNOSTIC")
        found.add(row["name"])
        counts = row["counts"]
        if (not isinstance(counts, dict) or set(counts) != {"borrows", "commits", "rollbacks", "publicationInserts"}
                or any(type(count) is not int or not 0 <= count <= 1000 for count in counts.values())):
            reject("SOURCE_DIAGNOSTIC")
        for field in ("lastSuccess", "firstDriverFailure"):
            operation = row[field]
            if operation is None:
                continue
            fields = {"operation", "sqlSha256"} | ({"sqlState"} if field == "firstDriverFailure" else set())
            if (not isinstance(operation, dict) or set(operation) != fields or not isinstance(operation["operation"],str)
                    or operation["operation"] not in operations
                    or operation["sqlSha256"] is not None and
                       (not isinstance(operation["sqlSha256"], str) or not re.fullmatch(r"[0-9a-f]{64}", operation["sqlSha256"]))
                    or field == "firstDriverFailure" and operation["sqlState"] is not None and
                       (not isinstance(operation["sqlState"], str) or not re.fullmatch(r"[0-9A-Z]{5}", operation["sqlState"]))):
                reject("SOURCE_DIAGNOSTIC")
    if require_complete and (found != SOURCE.scenarios or any(row["status"] != "PASSED" or row["issuerEntered"] == 0
                                                           for row in value["cases"])):
        reject("SOURCE_DIAGNOSTIC_NOT_PASS")
    return value


def validate_reauth_commit_diagnostics(value, require_complete=False):
    """Fixed diagnostic phases only; no messages, identifiers, secrets or arbitrary values."""
    case = "commitThenThrowKeepsOneDurableIssueAndNeverDeliversItsToken"
    if (not isinstance(value, dict) or set(value) != {"schemaVersion", "database", "case", "iterations"}
            or type(value["schemaVersion"]) is not int or value["schemaVersion"] != 1
            or value["database"] != REAUTH.database or value["case"] != case
            or not isinstance(value["iterations"], list) or len(value["iterations"]) != 6):
        reject("REAUTH_COMMIT_DIAGNOSTIC")
    expected = [(level, fault) for level in ("INFO", "DEBUG")
                for fault in ("COMMIT_SQL", "COMMIT_RUNTIME", "COMMIT_ERROR")]
    phases = {"SETUP", "ACTION", "LOG_PASSWORD_DRIVER", "LOG_SECRET_CANARIES",
              "DURABLE_GRAPH", "COMMIT_COUNT", "REPLAY", "REPLAY_GRAPH", "NOT_REACHED"}
    terminal = False
    for row, pair in zip(value["iterations"], expected):
        if (not isinstance(row, dict) or set(row) != {"level", "fault", "phase", "status", "failureKind"}
                or (row["level"], row["fault"]) != pair
                or not isinstance(row["phase"], str) or row["phase"] not in phases
                or not isinstance(row["status"], str) or row["status"] not in {"PASS", "FAIL", "NOT_REACHED"}
                or row["failureKind"] is not None and not isinstance(row["failureKind"], str)
                or row["failureKind"] not in {None, "ASSERTION", "SESSION_SENSITIVE", "OTHER"}):
            reject("REAUTH_COMMIT_DIAGNOSTIC")
        if row["status"] == "NOT_REACHED":
            if not terminal or row["phase"] != "NOT_REACHED" or row["failureKind"] is not None:
                reject("REAUTH_COMMIT_DIAGNOSTIC")
        elif terminal:
            reject("REAUTH_COMMIT_DIAGNOSTIC")
        elif row["status"] == "FAIL":
            if row["phase"] == "NOT_REACHED" or row["failureKind"] is None:
                reject("REAUTH_COMMIT_DIAGNOSTIC")
            terminal = True
        elif row["phase"] != "REPLAY_GRAPH" or row["failureKind"] is not None:
            reject("REAUTH_COMMIT_DIAGNOSTIC")
    if require_complete and any(row["status"] != "PASS" for row in value["iterations"]):
        reject("REAUTH_COMMIT_DIAGNOSTIC_NOT_PASS")
    return value


def validate_recovery_process_evidence(value, require_complete=False):
    """Public bounded process facts; raw process IDs, credentials and error text stay private."""
    expected_workers = {
        "actualSourceNamedListenerCaptureAndAckCommit": 1,
        "causeCommitWithKnownAckRollbackRecoversExactlyOnce": 3,
        "serviceRevocationLeavesIncompleteThenCurrentEligibilityRestoresRecovery": 3,
        "newJvmRecoversPublicationLeftByTerminatedProducer": 2,
        "unknownAckCommitThenThrowIsObservedBeforeAnyFurtherDispatch": 3,
    }
    if (not isinstance(value, dict) or set(value) != {"schemaVersion", "database", "cases"}
            or type(value["schemaVersion"]) is not int or value["schemaVersion"] != 1
            or value["database"] != RECOVERY.database or not isinstance(value["cases"], list)
            or len(value["cases"]) != 5):
        reject("RECOVERY_PROCESS_EVIDENCE")
    flags = {"ownershipVerified", "processCleanupVerified", "restartDistinctProcesses", "unknownObservedBeforeRecovery"}
    fields = {"name", "status", "workersStarted", "workersExited"} | flags
    found = set()
    for row in value["cases"]:
        if (not isinstance(row, dict) or set(row) != fields
                or not isinstance(row["name"], str) or row["name"] not in expected_workers or row["name"] in found
                or not isinstance(row["status"], str) or row["status"] not in {"PASSED", "FAILED", "NOT_REACHED"}
                or any(type(row[key]) is not bool for key in flags)
                or any(type(row[key]) is not int for key in ("workersStarted", "workersExited"))
                or not 0 <= row["workersExited"] <= row["workersStarted"] <= expected_workers[row["name"]]):
            reject("RECOVERY_PROCESS_EVIDENCE")
        found.add(row["name"])
        if row["status"] == "PASSED":
            if (row["workersStarted"] != expected_workers[row["name"]]
                    or row["workersExited"] != row["workersStarted"]
                    or not row["ownershipVerified"] or not row["processCleanupVerified"]
                    or row["restartDistinctProcesses"] != (row["name"] == "newJvmRecoversPublicationLeftByTerminatedProducer")
                    or row["unknownObservedBeforeRecovery"] != (row["name"] == "unknownAckCommitThenThrowIsObservedBeforeAnyFurtherDispatch")):
                reject("RECOVERY_PROCESS_EVIDENCE")
        elif row["status"] == "NOT_REACHED" and (row["workersStarted"] != 0 or any(row[key] for key in flags)):
            reject("RECOVERY_PROCESS_EVIDENCE")
    if found != RECOVERY.scenarios or require_complete and any(row["status"] != "PASSED" for row in value["cases"]):
        reject("RECOVERY_PROCESS_NOT_PASS")
    return value


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


def verify_reports(repo, lane=CAPTURE):
    lane = _spec(lane)
    base = repo / "backend" / "target"
    integration = validate_report(base / "failsafe-reports" / ("TEST-" + lane.it_class + ".xml"),
                                  lane.it_class, lane.scenarios)
    unit = validate_report(base / "surefire-reports" / ("TEST-" + lane.unit_class + ".xml"), lane.unit_class)
    if lane.guard_count is not None and unit != lane.guard_count:
        reject("REPORT_GUARD_COUNT")
    # Both profiles select exactly one IT and one target-guard suite.
    for folder, classname in (("failsafe-reports", lane.it_class), ("surefire-reports", lane.unit_class)):
        if set((base / folder).glob("TEST-*.xml")) != {base / folder / ("TEST-" + classname + ".xml")}:
            reject("REPORT_EXTRA_SUITE")
    return {"integrationTests": integration, "targetGuardTests": unit,
            "failures": 0, "errors": 0, "skipped": 0, "scenarios": sorted(lane.scenarios)}


def report_diagnostics(repo, lane=CAPTURE):
    """Whitelist counts and known scenario states, never failure text or properties."""
    lane = _spec(lane)
    result = {"scenarios": {name: "missing" for name in sorted(lane.scenarios)}}
    for kind, folder, classname in (("integration", "failsafe-reports", lane.it_class),
                                    ("targetGuard", "surefire-reports", lane.unit_class)):
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
                    if name not in lane.scenarios:
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



def stop_maven_group(state, lane=CAPTURE):
    lane = _spec(lane)
    path = state / "maven-process.json"
    if not path.exists():
        if (state / "maven-starting").exists():
            reject("MAVEN_GROUP_UNKNOWN")
        return
    record = load_json(path)
    keys = {"pgid", "marker"} | ({"acceptanceKind"} if lane is not CAPTURE else set())
    if (not isinstance(record, dict) or set(record) != keys
            or (lane is not CAPTURE and record.get("acceptanceKind") != lane.kind)
            or type(record["pgid"]) is not int or record["pgid"] <= 1
            or not isinstance(record["marker"], str)
            or not re.fullmatch(r"[0-9a-f]{32}", record["marker"])):
        reject("MAVEN_GROUP_RECEIPT")
    terminate_owned_group(record["pgid"], record["marker"])
    path.unlink()
    (state / "maven-starting").unlink(missing_ok=True)


def run_maven(repo, state, environment, lane=CAPTURE):
    lane = _spec(lane)
    marker = secrets.token_hex(16)
    child_environment = dict(environment, MSF_PG18_PROCESS_MARKER=marker)
    private_write(state / "maven-starting", marker)
    with (state / "maven.log").open("wb") as output:
        process = subprocess.Popen(
            [str(repo / "mvnw"), "--batch-mode", "--no-transfer-progress",
             "-P" + lane.profile, "clean", "verify"],
            cwd=repo, env=child_environment, stdout=output, stderr=subprocess.STDOUT,
            start_new_session=True)
        try:
            record = {"pgid": process.pid, "marker": marker}
            if lane is not CAPTURE:
                record["acceptanceKind"] = lane.kind
            private_write(state / "maven-process.json", json.dumps(record))
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


def context(environment, lane=CAPTURE):
    lane = _spec(lane)
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
    state = temporary / (lane.state_prefix + run + "-" + attempt)
    return run, attempt, state


def expected_labels(run, attempt, marker, lane=CAPTURE):
    lane = _spec(lane)
    return {LABEL_PREFIX + "kind": lane.kind, LABEL_PREFIX + "repository": REPOSITORY,
            LABEL_PREFIX + "run": run, LABEL_PREFIX + "attempt": attempt,
            LABEL_PREFIX + "instance": marker}


def private_write(path, content):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
        handle.write(content)


def validate_intent(value, run, attempt, lane=CAPTURE):
    lane = _spec(lane)
    if (not isinstance(value, dict) or set(value) != {"name", "instance"}
            or value["name"] != lane.state_prefix + run + "-" + attempt):
        reject("CLEANUP_INTENT")
    try:
        if str(uuid.UUID(value["instance"])) != value["instance"]:
            reject("CLEANUP_INTENT")
    except (ValueError, TypeError, AttributeError):
        reject("CLEANUP_INTENT")
    return value


def validate_resources(value, run, attempt, marker, lane=CAPTURE):
    lane = _spec(lane)
    if (not isinstance(value, dict) or set(value) != {"container_id", "volumes", "run_id", "run_attempt", "instance", "name"}
            or value.get("run_id") != run or value.get("run_attempt") != attempt
            or value.get("instance") != marker or value.get("name") != lane.state_prefix + run + "-" + attempt
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


def cleanup_container(state, run, attempt, lane=CAPTURE):
    lane = _spec(lane)
    if not state.exists():
        return {"verified": False, "notCreated": True}
    if state.is_symlink() or not state.is_dir():
        reject("CLEANUP_STATE")
    intent_file = state / "intent.json"
    # Check a present lane identity before any process signal, as well as before Docker removal.
    intent = validate_intent(load_json(intent_file), run, attempt, lane) if intent_file.exists() else None
    if intent is None and ((state / "maven-process.json").exists() or (state / "maven-starting").exists()):
        reject("MAVEN_GROUP_UNKNOWN")
    stop_maven_group(state, lane)
    if not intent_file.exists():
        # Only an empty state proves interruption before the pre-Docker intent was written.
        if any(state.iterdir()):
            reject("CLEANUP_INTENT")
        shutil.rmtree(state)
        return {"verified": True, "notCreated": True}
    listed = command(["docker", "ps", "-a", "--no-trunc", "--filter",
                      "name=^/" + intent["name"] + "$", "--format", "{{.ID}}"])
    identifiers = listed.stdout.split()
    if not identifiers:
        resources_file = state / "resources.json"
        if not resources_file.exists():
            scrub_private(state, lane)
            return {"verified": False, "containerAbsent": True, "anonymousVolumesUnknown": True}
        resources = validate_resources(load_json(resources_file), run, attempt, intent["instance"], lane)
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
        resources = validate_resources(load_json(state / "resources.json"), run, attempt, intent["instance"], lane)
        if resources["container_id"] != identity:
            reject("CLEANUP_IDENTITY")
    labels = docker_field(identity, ".Config.Labels")
    if not isinstance(labels, dict) or any(labels.get(key) != value
            for key, value in expected_labels(run, attempt, intent["instance"], lane).items()):
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


def scrub_private(state, lane=CAPTURE):
    lane = _spec(lane)
    if state.is_symlink() or not state.is_dir():
        return
    if (state / "maven-process.json").exists() or (state / "maven-starting").exists():
        reject("MAVEN_GROUP_UNVERIFIED")
    workers = state / "recovery-workers"
    if lane is RECOVERY and (workers.exists() or workers.is_symlink()):
        if workers.is_symlink() or not workers.is_dir() or workers.stat().st_uid != os.getuid():
            reject("WORKER_PRIVATE_CLEANUP")
        # The verified Maven group includes all marked workers. No private files are
        # removed until that group is gone; rmtree does not follow child symlinks.
        shutil.rmtree(workers)
    for name in ("admin.password", "runtime.password", "capture.password", "postgres.env", "maven.log"):
        (state / name).unlink(missing_ok=True)


def create_container(state, run, attempt, lane=CAPTURE):
    lane = _spec(lane)
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
    name = lane.state_prefix + run + "-" + attempt
    private_write(state / "intent.json", json.dumps({"name": name, "instance": marker}))
    admin_password, runtime_password = secrets.token_hex(32), secrets.token_hex(32)
    if admin_password == runtime_password:
        reject("PASSWORD_GENERATION")
    for password in (admin_password, runtime_password):
        print("::add-mask::" + password, flush=True)
    private_write(state / "admin.password", admin_password)
    private_write(state / "runtime.password", runtime_password)
    if lane is RECOVERY:
        capture_password = secrets.token_hex(32)
        if capture_password in (admin_password, runtime_password):
            reject("PASSWORD_GENERATION")
        print("::add-mask::" + capture_password, flush=True)
        private_write(state / "capture.password", capture_password)
    private_write(state / "postgres.env", "POSTGRES_USER=" + ADMIN + "\nPOSTGRES_DB=" + lane.database
                  + "\nPOSTGRES_PASSWORD=" + admin_password
                  + "\nPOSTGRES_INITDB_ARGS=--auth-host=scram-sha-256\n")
    arguments = ["docker", "run", "--detach", "--name", name, "--platform", "linux/amd64",
                 "--publish", "127.0.0.1::5432/tcp", "--env-file", str(state / "postgres.env"),
                 "--memory", "1g", "--cpus", "2", "--shm-size", "128m"]
    for key, value in expected_labels(run, attempt, marker, lane).items():
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
            for key, value in expected_labels(run, attempt, marker, lane).items()):
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
                 "database": lane.database, "image_digest": digest, "repository": REPOSITORY,
                 "run_id": run, "run_attempt": attempt, "engine_major": major}
    private_write(state / "ownership", "".join(key + "=" + value + "\n" for key, value in ownership.items()))
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        readiness = command(["docker", "exec", identity, "pg_isready", "-U", ADMIN, "-d", lane.database],
                            timeout=5, allow_failure=True)
        if readiness.returncode == 0:
            return ownership
        time.sleep(0.5)
    reject("POSTGRES_READINESS")


def result_path(repo, lane=CAPTURE):
    lane = _spec(lane)
    return repo / "artifacts" / lane.artifact_directory / "result.json"


def write_result(repo, result, lane=CAPTURE):
    _result_lane(result, lane)
    path = result_path(repo, lane)
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


def public_diagnostics(result, lane=CAPTURE):
    """Render only known test outcomes and validated database evidence."""
    lane = _spec(lane)
    output = {"status": "PASS" if result.get("status") == "PASS" else "FAILED",
              "cleanupVerified": result.get("cleanup", {}).get("verified") is True}
    diagnostics = result.get("reportDiagnostics", {})
    if isinstance(diagnostics, dict):
        scenarios = diagnostics.get("scenarios", {})
        if isinstance(scenarios, dict):
            output["scenarios"] = {name: scenarios[name] for name in sorted(lane.scenarios)
                                   if scenarios.get(name) in {"passed", "failed", "error", "skipped", "missing", "duplicate"}}
        for kind in ("integration", "targetGuard"):
            counts = diagnostics.get(kind, {})
            if isinstance(counts, dict):
                output[kind] = {key: counts[key] for key in ("tests", "failures", "errors", "skipped")
                                if type(counts.get(key)) is int and counts[key] >= 0}
    if "databaseFailure" in result:
        output["databaseFailure"] = validate_failure_evidence(result["databaseFailure"], lane)
    if "databaseEvidence" in result:
        digest = result.get("imageDigest")
        if isinstance(digest, str) and re.fullmatch(r"sha256:[0-9a-f]{64}", digest):
            output["databaseEvidence"] = validate_success_evidence(result["databaseEvidence"], digest, lane)
    if lane is SOURCE and "sourceDiagnostics" in result:
        output["sourceDiagnostics"] = validate_source_diagnostics(result["sourceDiagnostics"])
    if lane is REAUTH and "reauthCommitDiagnostics" in result:
        output["reauthCommitDiagnostics"] = validate_reauth_commit_diagnostics(result["reauthCommitDiagnostics"])
    if lane is RECOVERY and "recoveryProcesses" in result:
        output["recoveryProcesses"] = validate_recovery_process_evidence(result["recoveryProcesses"])
    migration = result.get("migrationDiagnostics", {})
    if isinstance(migration, dict):
        version = migration.get("lastMigrationVersion")
        if version in VERSIONS:
            output["lastMigrationVersion"] = version
        positions = migration.get("errorPositions")
        if isinstance(positions, list) and len(positions) <= 4 and all(type(value) is int and 0 <= value < 10000000 for value in positions):
            output["errorPositions"] = positions
    return output


def source_revision(repo, environment):
    """Bind source acceptance to the actual clean hosted checkout, before creating any database."""
    expected = environment.get("GITHUB_SHA")
    if not isinstance(expected, str) or not re.fullmatch(r"[0-9a-f]{40}", expected):
        reject("SOURCE_REVISION")
    actual = command(["git", "rev-parse", "HEAD"], cwd=repo, failure_stage="SOURCE_REVISION").stdout.strip()
    tree = command(["git", "rev-parse", "HEAD^{tree}"], cwd=repo, failure_stage="SOURCE_REVISION").stdout.strip()
    if actual != expected or not re.fullmatch(r"[0-9a-f]{40}", tree):
        reject("SOURCE_REVISION")
    command(["git", "diff", "--quiet", "HEAD", "--"], cwd=repo, failure_stage="SOURCE_DIRTY")
    untracked = command(["git", "ls-files", "--others", "--exclude-standard", "-z"],
                        cwd=repo, failure_stage="SOURCE_UNTRACKED").stdout
    if untracked:
        reject("SOURCE_UNTRACKED")
    return {"commit": actual, "tree": tree}


def run_acceptance(repo, environment, lane=CAPTURE):
    lane = _spec(lane)
    run, attempt, state = context(environment, lane)
    if state.exists() or state.is_symlink():
        reject("STATE_ALREADY_EXISTS")
    os.umask(0o077)
    state.mkdir(mode=0o700)
    result = {"schemaVersion": 1, "status": "FAILED", "stage": "INITIALIZE",
              "cleanup": {"verified": False}}
    if lane is not CAPTURE:
        result["acceptanceKind"] = lane.kind
    stage = "INITIALIZE"
    try:
        if lane is SOURCE or lane is REAUTH or lane is RECOVERY:
            stage = "SOURCE_REVISION"
            result["sourceRevision"] = source_revision(repo, environment)
        # The source revision must be checked before this untracked acceptance artifact exists.
        write_result(repo, result, lane)
        stage = "CONTAINER"
        ownership = create_container(state, run, attempt, lane)
        result["imageDigest"] = ownership["image_digest"]
        result["engineMajor"] = int(ownership["engine_major"])
        child = dict(environment)
        child.update({
            "MSF_CI_PG18_ACK": lane.ack,
            "MSF_CI_PG18_URL": "jdbc:postgresql://127.0.0.1:" + ownership["port"] + "/" + lane.database,
            "MSF_CI_PG18_CONTAINER_ID": ownership["container_id"],
            "MSF_CI_PG18_INSTANCE_ID": ownership["instance_id"],
            "MSF_CI_PG18_IMAGE_DIGEST": ownership["image_digest"],
            "MSF_CI_PG18_OWNERSHIP_FILE": str(state / "ownership"),
            "MSF_CI_PG18_ADMIN_PASSWORD_FILE": str(state / "admin.password"),
            "MSF_CI_PG18_RUNTIME_PASSWORD_FILE": str(state / "runtime.password"),
        })
        if lane is RECOVERY:
            child["MSF_CI_PG18_CAPTURE_PASSWORD_FILE"] = str(state / "capture.password")
        stage = "MAVEN_VERIFY"
        maven_exit = run_maven(repo, state, child, lane)
        result["mavenExit"] = maven_exit
        if lane is REAUTH:
            stage = "REAUTH_COMMIT_DIAGNOSTICS"
            diagnostics = repo / "backend" / "target" / "ci-pg18-reauth-commit-diagnostics.json"
            if diagnostics.exists():
                result["reauthCommitDiagnostics"] = validate_reauth_commit_diagnostics(load_json(diagnostics), require_complete=not maven_exit)
            elif not maven_exit:
                reject("REAUTH_COMMIT_DIAGNOSTIC_MISSING")
            evidence = repo / "backend" / "target" / lane.evidence_file
            if evidence.exists():
                result["databaseEvidence"] = validate_success_evidence(load_json(evidence), ownership["image_digest"], lane)
            stage = "MAVEN_VERIFY"
        if lane is RECOVERY:
            stage = "RECOVERY_PROCESSES"
            processes = repo / "backend" / "target" / "ci-pg18-recovery-process-evidence.json"
            if processes.exists():
                result["recoveryProcesses"] = validate_recovery_process_evidence(load_json(processes), require_complete=not maven_exit)
            elif not maven_exit:
                reject("RECOVERY_PROCESS_MISSING")
            evidence = repo / "backend" / "target" / lane.evidence_file
            if evidence.exists():
                result["databaseEvidence"] = validate_success_evidence(load_json(evidence), ownership["image_digest"], lane)
            stage = "MAVEN_VERIFY"
        if lane is SOURCE:
            stage = "SOURCE_DIAGNOSTICS"
            diagnostics = repo / "backend" / "target" / "ci-pg18-source-case-diagnostics.json"
            if diagnostics.exists():
                result["sourceDiagnostics"] = validate_source_diagnostics(load_json(diagnostics), require_complete=not maven_exit)
            elif not maven_exit:
                reject("SOURCE_DIAGNOSTIC_MISSING")
            # Preserve genuine completed fixture checks even if a later source command failed.
            evidence = repo / "backend" / "target" / lane.evidence_file
            if evidence.exists():
                result["databaseEvidence"] = validate_success_evidence(load_json(evidence), ownership["image_digest"], lane)
            stage = "MAVEN_VERIFY"
        if maven_exit:
            result["migrationDiagnostics"] = migration_diagnostics(state / "maven.log")
            result["reportDiagnostics"] = report_diagnostics(repo, lane)
            failure = repo / "backend" / "target" / lane.failure_file
            if failure.exists():
                result["databaseFailure"] = validate_failure_evidence(load_json(failure), lane)
            reject("MAVEN_VERIFY")
        stage = "REPORT_VERIFY"
        result["tests"] = verify_reports(repo, lane)
        stage = "DATABASE_EVIDENCE"
        result["databaseEvidence"] = validate_success_evidence(
            load_json(repo / "backend" / "target" / lane.evidence_file), ownership["image_digest"], lane)
        result["status"], result["stage"] = "PASS", "COMPLETE"
    except Rejected as failure:
        result["stage"] = str(failure)
        if failure.exit_code is not None:
            result["processExit"] = failure.exit_code
    except Exception:
        result["stage"] = stage
    finally:
        if stage == "MAVEN_VERIFY" and result["status"] != "PASS":
            result["reportDiagnostics"] = report_diagnostics(repo, lane)
        try:
            result["cleanup"] = cleanup_container(state, run, attempt, lane)
            if not result["cleanup"].get("verified"):
                result["status"], result["stage"] = "FAILED", "CLEANUP_UNVERIFIED"
        except Exception:
            result["status"], result["stage"] = "FAILED", "CLEANUP_FAILED"
        try:
            scrub_private(state, lane)
        except Exception:
            result["status"], result["stage"] = "FAILED", "PRIVATE_CLEANUP_FAILED"
        write_result(repo, result, lane)
    print(json.dumps({"status": result["status"], "stage": result["stage"],
                      "cleanupVerified": result["cleanup"].get("verified", False)}), flush=True)
    print(json.dumps(public_diagnostics(result, lane)), flush=True)
    return 0 if result["status"] == "PASS" else 1


def always_cleanup(repo, environment, lane=CAPTURE):
    lane = _spec(lane)
    run, attempt, state = context(environment, lane)
    path = result_path(repo, lane)
    previous = load_json(path) if path.exists() else {
        "schemaVersion": 1, "status": "FAILED", "stage": "RUN_NOT_COMPLETED",
        **({"acceptanceKind": lane.kind} if lane is not CAPTURE else {})}
    _result_lane(previous, lane)
    if not state.exists():
        if previous.get("cleanup", {}).get("verified") is True:
            return 0
        previous["cleanup"] = {"verified": False, "notCreated": True}
        previous["status"], previous["stage"] = "FAILED", "CLEANUP_UNVERIFIED"
        write_result(repo, previous, lane)
        return 1
    try:
        previous["cleanup"] = cleanup_container(state, run, attempt, lane)
    except Exception:
        previous["status"], previous["stage"] = "FAILED", "CLEANUP_FAILED"
        previous["cleanup"] = {"verified": False}
        try:
            scrub_private(state, lane)
        except Exception:
            previous["stage"] = "PRIVATE_CLEANUP_FAILED"
        write_result(repo, previous, lane)
        return 1
    scrub_private(state, lane)
    if not previous["cleanup"].get("verified"):
        previous["status"], previous["stage"] = "FAILED", "CLEANUP_UNVERIFIED"
    write_result(repo, previous, lane)
    return 0 if previous["cleanup"].get("verified") else 1


def main(lane=CAPTURE):
    repo = Path(__file__).resolve().parents[2]
    def interrupted(_signum, _frame):
        reject("INTERRUPTED")
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    try:
        if sys.argv[1:] == ["run"]:
            return run_acceptance(repo, os.environ, lane)
        if sys.argv[1:] == ["cleanup"]:
            return always_cleanup(repo, os.environ, lane)
        reject("COMMAND")
    except Exception:
        print("CI PostgreSQL harness rejected", flush=True)
        return 1


if __name__ == "__main__":
    sys.exit(main())

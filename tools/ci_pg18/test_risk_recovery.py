import copy
import dataclasses
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from xml.etree.ElementTree import Element, SubElement, tostring

import risk_capture as subject
import risk_recovery as entry


def processes():
    rows = []
    for name in sorted(subject.RECOVERY.scenarios):
        restart = name == "newJvmRecoversPublicationLeftByTerminatedProducer"
        count = 1 if name == "actualSourceNamedListenerCaptureAndAckCommit" else 2 if restart else 3
        rows.append(dict(name=name, status="PASSED", workersStarted=count, workersExited=count,
                         ownershipVerified=True, processCleanupVerified=True, restartDistinctProcesses=restart,
                         unknownObservedBeforeRecovery=name == "unknownAckCommitThenThrowIsObservedBeforeAnyFurtherDispatch"))
    return dict(schemaVersion=1, database=subject.RECOVERY.database, cases=rows)


class RecoveryHarnessTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.lane = subject.RECOVERY
        self.digest = "sha256:" + "b" * 64
        self.env = dict(GITHUB_ACTIONS="true", CI="true", GITHUB_REPOSITORY=subject.REPOSITORY,
                        GITHUB_RUN_ID="12345", GITHUB_RUN_ATTEMPT="1", RUNNER_TEMP=str(self.root))

    def evidence(self, lane):
        return dict(schemaVersion=1, postgresMajor=18, database=lane.database, instanceMarkerVerified=True,
                    imageDigest=self.digest, migrations=[dict(version=v, script="V" + v + "__fixture.sql", checksum=1)
                    for v in subject.VERSIONS], **{lane.schema_field: True, "runtimeRightsVerified": True},
                    **({"captureRightsVerified": True} if lane is subject.RECOVERY else {}))

    def report(self, classname, names, directory):
        path = self.root / "backend/target" / directory
        path.mkdir(parents=True, exist_ok=True)
        root = Element("testsuite", dict(name=classname, tests=str(len(names)), failures="0", errors="0", skipped="0"))
        for name in names:
            SubElement(root, "testcase", dict(classname=classname, name=name))
        (path / ("TEST-" + classname + ".xml")).write_bytes(tostring(root))

    def reports(self, guards=61):
        self.report(self.lane.it_class, sorted(self.lane.scenarios), "failsafe-reports")
        self.report(self.lane.unit_class, ["guard" + str(n) for n in range(guards)], "surefire-reports")

    def test_literal_immutable_lane_and_foreign_targets_are_rejected(self):
        with patch.object(subject, "main", return_value=8) as main:
            self.assertEqual(entry.main(), 8)
        main.assert_called_once_with(self.lane)
        with self.assertRaises(dataclasses.FrozenInstanceError):
            self.lane.database = "other"
        with self.assertRaises(subject.Rejected):
            subject.context(self.env, copy.copy(self.lane))
        for other in (subject.CAPTURE, subject.REGISTRY, subject.SOURCE):
            self.assertNotEqual(subject.context(self.env, other)[2], subject.context(self.env, self.lane)[2])
            with self.assertRaises(subject.Rejected):
                subject.validate_success_evidence(self.evidence(other), self.digest, self.lane)
        # PR50 deliberately has no G008 lane implementation. Its fixed raw tuple
        # is still foreign evidence; do not introduce REAUTH to test rejection.
        foreign = self.evidence(subject.SOURCE)
        foreign["database"] = "msf_ci_g008_reauth"
        foreign["reauthSchemaVerified"] = foreign.pop("sourceSchemaVerified")
        with self.assertRaises(subject.Rejected):
            subject.validate_success_evidence(foreign, self.digest, self.lane)
        for key in ("LAN_PG_URL", "PG18_TUNNEL_LOCAL_PORT", "MSF_CI_PG18_CAPTURE_PASSWORD_FILE"):
            with self.assertRaises(subject.Rejected):
                subject.context(dict(self.env, **{key: "operator"}), self.lane)

    def test_exact_five_cases_and_sixty_one_guards(self):
        self.reports()
        result = subject.verify_reports(self.root, self.lane)
        self.assertEqual((result["integrationTests"], result["targetGuardTests"]), (5, 61))
        self.reports(60)
        with self.assertRaises(subject.Rejected):
            subject.verify_reports(self.root, self.lane)
        self.reports()
        self.report(subject.SOURCE.it_class, sorted(subject.SOURCE.scenarios), "failsafe-reports")
        with self.assertRaises(subject.Rejected):
            subject.verify_reports(self.root, self.lane)

    def test_complete_and_partial_process_evidence_cannot_confuse_pass(self):
        value = processes()
        self.assertEqual(subject.validate_recovery_process_evidence(value, True), value)
        value["cases"][0].update(status="FAILED", workersExited=0, processCleanupVerified=False)
        subject.validate_recovery_process_evidence(value)
        with self.assertRaises(subject.Rejected):
            subject.validate_recovery_process_evidence(value, True)

    def test_process_schema_refuses_arbitrary_values_duplicates_and_false_cleanup(self):
        mutations = (lambda x: x.update(password="secret"), lambda x: x["cases"].pop(),
                     lambda x: x["cases"].__setitem__(1, copy.deepcopy(x["cases"][0])),
                     lambda x: x["cases"][0].update(pid=123),
                     lambda x: x["cases"][0].update(workersStarted=True),
                     lambda x: x["cases"][0].update(workersExited=0),
                     lambda x: x["cases"][0].update(ownershipVerified=False),
                     lambda x: x["cases"][0].update(processCleanupVerified=False),
                     lambda x: x["cases"][0].update(status="PASSED_SECRET"))
        for mutate in mutations:
            value = processes()
            mutate(value)
            with self.assertRaises(subject.Rejected):
                subject.validate_recovery_process_evidence(value)

    def test_restart_and_unknown_observation_are_required_for_their_exact_cases(self):
        for name, flag in (("newJvmRecoversPublicationLeftByTerminatedProducer", "restartDistinctProcesses"),
                           ("unknownAckCommitThenThrowIsObservedBeforeAnyFurtherDispatch", "unknownObservedBeforeRecovery")):
            value = processes()
            next(row for row in value["cases"] if row["name"] == name)[flag] = False
            with self.assertRaises(subject.Rejected):
                subject.validate_recovery_process_evidence(value)

    def test_pass_requires_both_role_evidence_revision_and_process_completion(self):
        self.reports()
        result = dict(schemaVersion=1, status="PASS", stage="COMPLETE", acceptanceKind=self.lane.kind,
                      imageDigest=self.digest, databaseEvidence=self.evidence(self.lane),
                      tests=subject.verify_reports(self.root, self.lane), recoveryProcesses=processes(),
                      sourceRevision=dict(commit="a" * 40, tree="b" * 40))
        subject._result_lane(result, self.lane)
        for field in ("sourceRevision", "recoveryProcesses"):
            with self.assertRaises(subject.Rejected):
                subject._result_lane(dict(result, **{field: None}), self.lane)
        result["databaseEvidence"]["captureRightsVerified"] = False
        with self.assertRaises(subject.Rejected):
            subject._result_lane(result, self.lane)

    def test_worker_private_cleanup_requires_verified_process_group(self):
        state = self.root / "state"
        state.mkdir()
        workers = state / "recovery-workers"
        workers.mkdir()
        (workers / "private-output").write_text("secret")
        (state / "capture.password").write_text("secret")
        marker = state / "maven-process.json"
        marker.write_text("{}")
        with self.assertRaises(subject.Rejected):
            subject.scrub_private(state, self.lane)
        self.assertTrue((state / "capture.password").exists())
        marker.unlink()
        subject.scrub_private(state, self.lane)
        self.assertFalse(workers.exists())
        self.assertFalse((state / "capture.password").exists())

    def test_worker_private_cleanup_never_follows_a_directory_symlink(self):
        state = self.root / "state"
        state.mkdir()
        outside = self.root / "unowned"
        outside.mkdir()
        (outside / "retain").write_text("keep")
        (state / "recovery-workers").symlink_to(outside, target_is_directory=True)
        with self.assertRaises(subject.Rejected):
            subject.scrub_private(state, self.lane)
        self.assertEqual((outside / "retain").read_text(), "keep")


if __name__ == "__main__":
    unittest.main()

import contextlib
import copy
import dataclasses
import io
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from xml.etree.ElementTree import Element, SubElement, tostring

import risk_capture as subject
import risk_source as entry


class SourceLaneTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.lane = subject.SOURCE
        self.digest = "sha256:" + "b" * 64
        self.marker = "11111111-2222-4333-8444-555555555555"
        self.environment = {"GITHUB_ACTIONS": "true", "CI": "true",
                            "GITHUB_REPOSITORY": subject.REPOSITORY, "GITHUB_RUN_ID": "12345",
                            "GITHUB_RUN_ATTEMPT": "1", "RUNNER_TEMP": str(self.root)}

    def evidence(self, lane=None):
        lane = lane or self.lane
        return {"schemaVersion": 1, "postgresMajor": 18, "database": lane.database,
                "instanceMarkerVerified": True, "imageDigest": self.digest,
                "migrations": [{"version": version, "script": "V" + version + "__fixture.sql", "checksum": 123}
                               for version in subject.VERSIONS],
                lane.schema_field: True, "runtimeRightsVerified": True}

    def write_report(self, classname, names, folder="failsafe-reports", **changes):
        directory = self.root / "backend/target" / folder
        directory.mkdir(parents=True, exist_ok=True)
        attributes = {"name": classname, "tests": str(len(names)), "failures": "0", "errors": "0", "skipped": "0"}
        attributes.update({key: str(value) for key, value in changes.items()})
        tree = Element("testsuite", attributes)
        for name in names:
            SubElement(tree, "testcase", {"classname": classname, "name": name})
        path = directory / ("TEST-" + classname + ".xml")
        path.write_bytes(tostring(tree))
        return path

    def reports(self, lane=None):
        lane = lane or self.lane
        self.write_report(lane.it_class, sorted(lane.scenarios))
        self.write_report(lane.unit_class, ["strictTarget_" + str(index) for index in range(52)], "surefire-reports")

    def state(self, lane=None):
        lane = lane or self.lane
        state = self.root / "state"
        state.mkdir()
        subject.private_write(state / "intent.json", json.dumps({
            "name": lane.state_prefix + "12345-1", "instance": self.marker}))
        return state

    def test_source_entry_selects_only_the_literal_fixed_lane(self):
        with patch.object(subject, "main", return_value=7) as main:
            self.assertEqual(entry.main(), 7)
        main.assert_called_once_with(self.lane)
        with self.assertRaises(dataclasses.FrozenInstanceError):
            self.lane.database = "operator"
        with self.assertRaisesRegex(subject.Rejected, "LANE"):
            subject.context(self.environment, copy.copy(self.lane))
        with self.assertRaisesRegex(subject.Rejected, "LANE"):
            subject.result_path(self.root, "source")

    def test_all_three_lane_identities_results_and_failure_records_are_disjoint(self):
        lanes = (subject.CAPTURE, subject.REGISTRY, self.lane)
        for actual in lanes:
            for requested in lanes:
                if actual is requested:
                    continue
                with self.subTest(actual=actual.kind, requested=requested.kind):
                    self.assertNotEqual(actual.database, requested.database)
                    self.assertNotEqual(actual.runtime_role, requested.runtime_role)
                    self.assertNotEqual(actual.state_prefix, requested.state_prefix)
                    self.assertNotEqual(subject.result_path(self.root, actual), subject.result_path(self.root, requested))
                    with self.assertRaises(subject.Rejected):
                        subject.validate_success_evidence(self.evidence(actual), self.digest, requested)
                    failure = {"schemaVersion": 1, "phase": "migrate", "migrationVersion": None, "sqlState": "42501"}
                    if actual is not subject.CAPTURE:
                        failure["database"] = actual.database
                    with self.assertRaises(subject.Rejected):
                        subject.validate_failure_evidence(failure, requested)

    def test_source_requires_exactly_fifty_two_target_guards(self):
        self.reports()
        for count in (1, 51, 53):
            self.write_report(self.lane.unit_class, ["strictTarget_" + str(index) for index in range(count)], "surefire-reports")
            with self.subTest(count=count), self.assertRaisesRegex(subject.Rejected, "REPORT_GUARD_COUNT"):
                subject.verify_reports(self.root, self.lane)
        self.reports()
        self.assertEqual(subject.verify_reports(self.root, self.lane)["targetGuardTests"], 52)

    @patch.object(subject, "command")
    def test_source_revision_requires_matching_hosted_head_tree_and_clean_tracked_files(self, command):
        head, tree = "1" * 40, "2" * 40
        command.side_effect = [subprocess.CompletedProcess([], 0, head + "\n", ""),
                               subprocess.CompletedProcess([], 0, tree + "\n", ""),
                               subprocess.CompletedProcess([], 0, "", ""),
                               subprocess.CompletedProcess([], 0, "", "")]
        self.assertEqual(subject.source_revision(self.root, dict(self.environment, GITHUB_SHA=head)),
                         {"commit": head, "tree": tree})
        self.assertEqual([call.args[0] for call in command.call_args_list],
                         [["git", "rev-parse", "HEAD"], ["git", "rev-parse", "HEAD^{tree}"],
                          ["git", "diff", "--quiet", "HEAD", "--"],
                          ["git", "ls-files", "--others", "--exclude-standard", "-z"]])
        self.assertTrue(all(call.kwargs["cwd"] == self.root for call in command.call_args_list))

    @patch.object(subject, "command")
    def test_source_revision_rejects_missing_malformed_mismatched_or_dirty_source(self, command):
        for value in (None, "PRIVATE", "1" * 39, "A" * 40):
            with self.subTest(value=value), self.assertRaisesRegex(subject.Rejected, "SOURCE_REVISION"):
                subject.source_revision(self.root, dict(self.environment, GITHUB_SHA=value))
        command.assert_not_called()
        for actual, tree in (("2" * 40, "3" * 40), ("1" * 40, "PRIVATE")):
            command.side_effect = [subprocess.CompletedProcess([], 0, actual, ""), subprocess.CompletedProcess([], 0, tree, "")]
            with self.subTest(actual=actual), self.assertRaisesRegex(subject.Rejected, "SOURCE_REVISION"):
                subject.source_revision(self.root, dict(self.environment, GITHUB_SHA="1" * 40))
        command.side_effect = [subprocess.CompletedProcess([], 0, "1" * 40, ""),
                               subprocess.CompletedProcess([], 0, "2" * 40, ""), subject.Rejected("SOURCE_DIRTY_EXIT")]
        with self.assertRaisesRegex(subject.Rejected, "SOURCE_DIRTY_EXIT"):
            subject.source_revision(self.root, dict(self.environment, GITHUB_SHA="1" * 40))

    def test_actual_git_source_binding_rejects_untracked_java_and_migration_but_allows_ignored_builds(self):
        repo = self.root / "checkout"; repo.mkdir()
        def git(*arguments):
            return subprocess.run(["git", "-C", str(repo), *arguments], check=True,
                                  stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True).stdout.strip()
        git("init", "--quiet")
        (repo / ".gitignore").write_text("target/\n")
        (repo / "pom.xml").write_text("<project/>\n")
        git("add", ".")
        git("-c", "user.name=CI source verifier", "-c", "user.email=ci-source@example.invalid",
            "-c", "commit.gpgsign=false", "commit", "--quiet", "-m", "owned source binding fixture")
        head, tree = git("rev-parse", "HEAD"), git("rev-parse", "HEAD^{tree}")
        environment = dict(self.environment, GITHUB_SHA=head)
        expected = {"commit": head, "tree": tree}
        self.assertEqual(subject.source_revision(repo, environment), expected)
        build = repo / "backend/target/generated"; build.mkdir(parents=True)
        (build / "compiler-output").write_text("ignored build output")
        self.assertEqual(subject.source_revision(repo, environment), expected)
        for name in ("backend/src/main/java/Injected.java", "backend/src/main/resources/db/migration/V999__untracked.sql",
                     "artifacts/ci-pg18-risk-source/result.json"):
            path = repo / name; path.parent.mkdir(parents=True, exist_ok=True); path.write_text("untracked executable input")
            with self.subTest(name=name), self.assertRaisesRegex(subject.Rejected, "SOURCE_UNTRACKED"):
                subject.source_revision(repo, environment)
            path.unlink()
        (repo / "pom.xml").write_text("<modified/>\n")
        with self.assertRaisesRegex(subject.Rejected, "SOURCE_DIRTY_EXIT"):
            subject.source_revision(repo, environment)

    @patch.object(subject, "create_container")
    @patch.object(subject, "run_maven")
    def test_missing_source_revision_fails_before_credentials_or_container_creation(self, maven, create):
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(subject.run_acceptance(self.root, self.environment, self.lane), 1)
        create.assert_not_called(); maven.assert_not_called()
        result = subject.load_json(subject.result_path(self.root, self.lane))
        self.assertEqual(result["acceptanceKind"], self.lane.kind)
        self.assertEqual(result["stage"], "SOURCE_REVISION")
        self.assertEqual(result["status"], "FAILED")
        self.assertTrue(result["cleanup"]["verified"])
        self.assertTrue(result["cleanup"]["notCreated"])

    def test_source_pass_requires_revision_three_cases_and_exact_guard_count(self):
        valid = {"schemaVersion": 1, "status": "PASS", "stage": "COMPLETE", "acceptanceKind": self.lane.kind,
                 "cleanup": {"verified": True}, "imageDigest": self.digest, "databaseEvidence": self.evidence(),
                 "sourceRevision": {"commit": "1" * 40, "tree": "2" * 40},
                 "tests": {"integrationTests": 3, "targetGuardTests": 52, "failures": 0, "errors": 0,
                           "skipped": 0, "scenarios": sorted(self.lane.scenarios)}}
        subject.write_result(self.root, valid, self.lane)
        self.assertEqual(subject.load_json(subject.result_path(self.root, self.lane)), valid)
        for change in ({"sourceRevision": None}, {"sourceRevision": {"commit": "PRIVATE", "tree": "2" * 40}},
                       {"sourceRevision": {"commit": "1" * 40, "tree": "2" * 40, "raw": "PRIVATE"}},
                       {"tests": dict(valid["tests"], targetGuardTests=1)},
                       {"tests": dict(valid["tests"], integrationTests=9)},
                       {"databaseEvidence": self.evidence(subject.REGISTRY)}):
            with self.subTest(change=change), self.assertRaisesRegex(subject.Rejected, "RESULT_LANE"):
                subject.write_result(self.root, dict(valid, **change), self.lane)

    @patch.object(subject, "terminate_owned_group")
    @patch.object(subject, "command")
    def test_registry_process_or_intent_cannot_authorize_source_cleanup(self, command, terminate):
        state = self.state(subject.REGISTRY)
        subject.private_write(state / "maven-process.json", json.dumps({
            "pgid": 27123, "marker": "f" * 32, "acceptanceKind": subject.REGISTRY.kind}))
        with self.assertRaisesRegex(subject.Rejected, "CLEANUP_INTENT"):
            subject.cleanup_container(state, "12345", "1", self.lane)
        command.assert_not_called(); terminate.assert_not_called()
        with self.assertRaisesRegex(subject.Rejected, "MAVEN_GROUP_RECEIPT"):
            subject.stop_maven_group(state, self.lane)
        terminate.assert_not_called()

    def test_capture_defaults_and_source_identities_are_disjoint_and_fixed(self):
        self.assertEqual(subject.DATABASE, "msf_ci_risk_capture")
        self.assertEqual(subject.CAPTURE.runtime_role, "msf_ci_capture_runtime")
        self.assertEqual(len(subject.SCENARIOS), 9)
        self.assertEqual(self.lane.database, "msf_ci_risk_source")
        self.assertEqual(self.lane.runtime_role, "msf_ci_source_runtime")
        self.assertEqual(self.lane.ack, "ephemeral-risk-source-only")
        self.assertEqual(self.lane.profile, "ci-pg18-risk-source")
        self.assertEqual(self.lane.it_class, "com.mightyoung.softwarefactory.CiPg18RiskSourceIT")
        self.assertEqual(self.lane.unit_class, "com.mightyoung.softwarefactory.CiPg18SourceTargetTest")
        self.assertEqual(len(self.lane.scenarios), 3)
        self.assertTrue(self.lane.scenarios.isdisjoint(subject.SCENARIOS))
        capture_state = subject.context(self.environment)[2]
        source_state = subject.context(self.environment, self.lane)[2]
        self.assertNotEqual(capture_state, source_state)
        self.assertNotEqual(subject.result_path(self.root), subject.result_path(self.root, self.lane))
        self.assertEqual(subject.result_path(self.root, self.lane), self.root / "artifacts/ci-pg18-risk-source/result.json")
        self.assertNotEqual(subject.expected_labels("12345", "1", self.marker),
                            subject.expected_labels("12345", "1", self.marker, self.lane))

    def test_source_context_retains_lan_tunnel_and_operator_override_refusal(self):
        for key in ("LAN_PG_URL", "PG18_TUNNEL_LOCAL_PORT", "MSF_CI_PG18_URL", "MSF_CI_PG18_LANE"):
            with self.subTest(key=key), self.assertRaisesRegex(subject.Rejected, "FOREIGN_TARGET"):
                subject.context(dict(self.environment, **{key: "PRIVATE"}), self.lane)

    def test_success_evidence_cannot_cross_lanes_or_relabel_capture_schema(self):
        self.assertEqual(subject.validate_success_evidence(self.evidence(), self.digest, self.lane), self.evidence())
        for value, lane in ((self.evidence(subject.CAPTURE), self.lane), (self.evidence(), subject.CAPTURE),
                            (dict(self.evidence(), database=subject.DATABASE), self.lane),
                            (dict(self.evidence(), raw="PRIVATE"), self.lane)):
            with self.subTest(lane=lane.kind), self.assertRaises(subject.Rejected) as caught:
                subject.validate_success_evidence(value, self.digest, lane)
            self.assertNotIn("PRIVATE", str(caught.exception))
        for digest in ("postgres:18-bookworm", "sha256:PRIVATE", None):
            with self.subTest(digest=digest), self.assertRaises(subject.Rejected):
                subject.validate_success_evidence(dict(self.evidence(), imageDigest=digest), digest, self.lane)

    def test_source_failure_evidence_requires_its_fixed_database_and_phase_inventory(self):
        capture = {"schemaVersion": 1, "phase": "migrate", "migrationVersion": None, "sqlState": "42501"}
        source = dict(capture, database=self.lane.database)
        self.assertEqual(subject.validate_failure_evidence(source, self.lane), source)
        for value, lane in ((capture, self.lane), (source, subject.CAPTURE),
                            (dict(source, database=subject.DATABASE), self.lane),
                            (dict(source, phase="seed-tenant"), self.lane),
                            (dict(source, sqlState="PRIVATE"), self.lane),
                            (dict(source, message="PRIVATE"), self.lane)):
            with self.subTest(value=value), self.assertRaises(subject.Rejected) as caught:
                subject.validate_failure_evidence(value, lane)
            self.assertNotIn("PRIVATE", str(caught.exception))

    def test_source_reports_require_exact_three_cases_and_its_target_class(self):
        self.reports()
        self.assertEqual(subject.verify_reports(self.root, self.lane)["integrationTests"], 3)
        for names in (sorted(self.lane.scenarios)[:-1], sorted(self.lane.scenarios) + ["unknown"],
                      sorted(self.lane.scenarios)[:-1] + [sorted(self.lane.scenarios)[0]], sorted(subject.SCENARIOS)):
            self.write_report(self.lane.it_class, names)
            with self.subTest(names=names), self.assertRaises(subject.Rejected):
                subject.verify_reports(self.root, self.lane)
        self.reports()
        with self.assertRaises(subject.Rejected):
            subject.verify_reports(self.root)

    def test_both_lanes_reject_extra_surefire_and_failsafe_xml(self):
        for lane in (subject.CAPTURE, self.lane):
            self.reports(lane)
            for folder in ("surefire-reports", "failsafe-reports"):
                path = self.write_report("other.ForeignSuite", ["extra"], folder)
                with self.subTest(lane=lane.kind, folder=folder), self.assertRaisesRegex(subject.Rejected, "REPORT_EXTRA_SUITE"):
                    subject.verify_reports(self.root, lane)
                path.unlink()
            for folder in ("surefire-reports", "failsafe-reports"):
                for path in (self.root / "backend/target" / folder).glob("TEST-*.xml"):
                    path.unlink()

    def test_source_zero_failed_error_skipped_or_mismatched_guard_never_passes(self):
        self.reports()
        for changes in ({"tests": 0}, {"failures": 1}, {"errors": 1}, {"skipped": 1}):
            self.write_report(self.lane.it_class, sorted(self.lane.scenarios), **changes)
            with self.subTest(changes=changes), self.assertRaises(subject.Rejected):
                subject.verify_reports(self.root, self.lane)
        self.reports()
        self.write_report(self.lane.unit_class, [], "surefire-reports")
        with self.assertRaises(subject.Rejected):
            subject.verify_reports(self.root, self.lane)

    def test_source_diagnostics_never_include_unknown_case_or_failure_text(self):
        self.reports()
        value = {"status": "FAILED", "reportDiagnostics": {"scenarios": {
            next(iter(self.lane.scenarios)): "failed", next(iter(subject.SCENARIOS)): "passed", "PRIVATE": "passed"}},
            "databaseFailure": {"schemaVersion": 1, "database": self.lane.database, "phase": "native-graph",
                                "migrationVersion": None, "sqlState": "XX000"}}
        safe = subject.public_diagnostics(value, self.lane)
        self.assertNotIn("PRIVATE", json.dumps(safe))
        self.assertTrue(set(safe["scenarios"]).issubset(self.lane.scenarios))
        self.assertEqual(safe["databaseFailure"]["database"], self.lane.database)

    @patch.object(subject, "terminate_owned_group")
    @patch.object(subject, "command")
    def test_capture_intent_and_resources_never_authorize_source_deletion(self, command, terminate):
        state = self.state(subject.CAPTURE)
        subject.private_write(state / "maven-process.json", json.dumps({"pgid": 27123, "marker": "f" * 32}))
        with self.assertRaisesRegex(subject.Rejected, "CLEANUP_INTENT"):
            subject.cleanup_container(state, "12345", "1", self.lane)
        command.assert_not_called()
        terminate.assert_not_called()
        value = {"container_id": "a" * 64, "volumes": ["b" * 64], "run_id": "12345", "run_attempt": "1",
                 "instance": self.marker, "name": subject.CAPTURE.state_prefix + "12345-1"}
        with self.assertRaisesRegex(subject.Rejected, "CLEANUP_RESOURCES"):
            subject.validate_resources(value, "12345", "1", self.marker, self.lane)

    @patch.object(subject, "docker_field")
    @patch.object(subject, "command")
    def test_capture_labels_cannot_authorize_source_container_deletion(self, command, inspect):
        state = self.state()
        command.return_value = subprocess.CompletedProcess([], 0, "a" * 64 + "\n", "")
        inspect.return_value = subject.expected_labels("12345", "1", self.marker)
        with self.assertRaisesRegex(subject.Rejected, "CLEANUP_OWNERSHIP"):
            subject.cleanup_container(state, "12345", "1", self.lane)
        self.assertFalse(any(call.args[0][:2] == ["docker", "rm"] for call in command.call_args_list))

    @patch.object(subject, "docker_field")
    @patch.object(subject, "command")
    def test_source_cleanup_requires_exact_kind_run_attempt_and_instance_labels(self, command, inspect):
        state = self.state()
        command.return_value = subprocess.CompletedProcess([], 0, "a" * 64 + "\n", "")
        for key in ("kind", "run", "attempt", "instance", "repository"):
            labels = subject.expected_labels("12345", "1", self.marker, self.lane)
            labels[subject.LABEL_PREFIX + key] = "foreign"
            inspect.return_value = labels; command.reset_mock()
            with self.subTest(label=key), self.assertRaisesRegex(subject.Rejected, "CLEANUP_OWNERSHIP"):
                subject.cleanup_container(state, "12345", "1", self.lane)
            self.assertFalse(any(call.args[0][:2] == ["docker", "rm"] for call in command.call_args_list))
        self.assertTrue(state.exists())

    @patch.object(subject, "docker_field")
    @patch.object(subject, "command")
    def test_exact_source_container_and_recorded_volume_use_shared_verified_cleanup(self, command, inspect):
        state = self.state(); identity, volume = "a" * 64, "c" * 64
        subject.private_write(state / "resources.json", json.dumps({
            "container_id": identity, "volumes": [volume], "run_id": "12345", "run_attempt": "1",
            "instance": self.marker, "name": self.lane.state_prefix + "12345-1"}))
        command.side_effect = [subprocess.CompletedProcess([], 0, value, "")
                               for value in (identity + "\n", "", "", "")]
        inspect.side_effect = [subject.expected_labels("12345", "1", self.marker, self.lane),
                               [{"Type": "volume", "Destination": "/var/lib/postgresql", "Name": volume}]]
        result = subject.cleanup_container(state, "12345", "1", self.lane)
        self.assertTrue(result["verified"]); self.assertTrue(result["anonymousVolumesRemoved"])
        self.assertFalse(state.exists())
        self.assertTrue(any(call.args[0] == ["docker", "rm", "--force", "--volumes", identity]
                            for call in command.call_args_list))

    @patch.object(subject, "terminate_owned_group")
    @patch.object(subject, "command")
    def test_missing_intent_with_even_well_formed_process_record_is_not_signaled_or_deleted(self, command, terminate):
        state = self.root / "unknown-state"; state.mkdir()
        subject.private_write(state / "maven-process.json", json.dumps({
            "pgid": 27123, "marker": "f" * 32, "acceptanceKind": self.lane.kind}))
        with self.assertRaisesRegex(subject.Rejected, "MAVEN_GROUP_UNKNOWN"):
            subject.cleanup_container(state, "12345", "1", self.lane)
        terminate.assert_not_called(); command.assert_not_called()
        self.assertTrue((state / "maven-process.json").exists())

    @patch.object(subject, "terminate_owned_group")
    @patch.object(subject, "command")
    def test_only_empty_missing_intent_state_is_proven_not_created(self, command, terminate):
        empty = self.root / "empty-state"; empty.mkdir()
        self.assertEqual(subject.cleanup_container(empty, "12345", "1", self.lane),
                         {"verified": True, "notCreated": True})
        unknown = self.root / "unknown-resources"; unknown.mkdir()
        subject.private_write(unknown / "resources.json", json.dumps({"container_id": "a" * 64}))
        with self.assertRaisesRegex(subject.Rejected, "CLEANUP_INTENT"):
            subject.cleanup_container(unknown, "12345", "1", self.lane)
        terminate.assert_not_called(); command.assert_not_called()
        self.assertTrue((unknown / "resources.json").exists())

    @patch.object(subject, "terminate_owned_group")
    @patch.object(subject, "command")
    def test_source_always_cleanup_does_not_signal_or_delete_copied_capture_state(self, command, terminate):
        state = subject.context(self.environment, self.lane)[2]; state.mkdir()
        subject.private_write(state / "intent.json", json.dumps({
            "name": subject.CAPTURE.state_prefix + "12345-1", "instance": self.marker}))
        subject.private_write(state / "maven-process.json", json.dumps({"pgid": 27123, "marker": "f" * 32}))
        subject.write_result(self.root, {"schemaVersion": 1, "acceptanceKind": self.lane.kind,
                                        "status": "FAILED", "stage": "INITIALIZE"}, self.lane)
        self.assertEqual(subject.always_cleanup(self.root, self.environment, self.lane), 1)
        terminate.assert_not_called(); command.assert_not_called()
        self.assertTrue((state / "maven-process.json").exists())
        result = json.loads(subject.result_path(self.root, self.lane).read_text())
        self.assertEqual(result["status"], "FAILED")
        self.assertFalse(result["cleanup"]["verified"])

    @patch.object(subject, "terminate_owned_group")
    def test_process_receipts_cannot_cross_lane_cleanup(self, terminate):
        state = self.root / "process"; state.mkdir()
        capture = {"pgid": 27123, "marker": "f" * 32}
        path = state / "maven-process.json"; path.write_text(json.dumps(capture))
        with self.assertRaisesRegex(subject.Rejected, "MAVEN_GROUP_RECEIPT"):
            subject.stop_maven_group(state, self.lane)
        path.write_text(json.dumps(dict(capture, acceptanceKind=self.lane.kind)))
        with self.assertRaisesRegex(subject.Rejected, "MAVEN_GROUP_RECEIPT"):
            subject.stop_maven_group(state)
        terminate.assert_not_called()

    @patch.object(subject, "command")
    def test_opposite_lane_pass_cannot_bypass_cleanup_with_missing_state(self, command):
        for lane, previous in ((self.lane, {"status": "PASS", "cleanup": {"verified": True}}),
                               (subject.CAPTURE, {"status": "PASS", "acceptanceKind": self.lane.kind,
                                                  "cleanup": {"verified": True}})):
            path = subject.result_path(self.root, lane); path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps(previous))
            with self.subTest(lane=lane.kind), self.assertRaisesRegex(subject.Rejected, "RESULT_LANE"):
                subject.always_cleanup(self.root, self.environment, lane)
        command.assert_not_called()

    @patch.object(subject, "command")
    def test_relabeling_capture_acceptance_kind_does_not_create_a_source_pass(self, command):
        previous = {"schemaVersion": 1, "status": "PASS", "stage": "COMPLETE", "acceptanceKind": self.lane.kind,
                    "cleanup": {"verified": True}, "imageDigest": self.digest,
                    "databaseEvidence": self.evidence(subject.CAPTURE),
                    "sourceRevision": {"commit": "1" * 40, "tree": "2" * 40},
                    "tests": {"integrationTests": 3, "targetGuardTests": 52, "failures": 0, "errors": 0,
                              "skipped": 0, "scenarios": sorted(self.lane.scenarios)}}
        path = subject.result_path(self.root, self.lane); path.parent.mkdir(parents=True)
        for mutation in (previous, dict(previous, databaseEvidence=self.evidence(), tests=dict(previous["tests"], scenarios=sorted(subject.SCENARIOS))),
                         {key: value for key, value in previous.items() if key != "databaseEvidence"}):
            path.write_text(json.dumps(mutation))
            with self.assertRaisesRegex(subject.Rejected, "RESULT_LANE"):
                subject.always_cleanup(self.root, self.environment, self.lane)
        command.assert_not_called()

    @patch.object(subject, "docker_field")
    @patch.object(subject, "command")
    def test_source_container_creation_uses_fixed_database_name_labels_and_immutable_image(self, command, inspect):
        state = self.root / "new-container"; state.mkdir()
        identity, volume = "a" * 64, "c" * 64
        pinned = "docker.io/library/postgres@" + self.digest
        command.side_effect = [subprocess.CompletedProcess([], 0, value, "") for value in (
            "28.0.1\n", "", json.dumps(["postgres@" + self.digest]), identity + "\n", "")]
        inspect.side_effect = [pinned, subject.expected_labels("12345", "1", self.marker, self.lane),
                               {"5432/tcp": [{"HostIp": "127.0.0.1", "HostPort": "35432"}]},
                               [{"Type": "volume", "Destination": "/var/lib/postgresql", "Name": volume}]]
        with patch.object(subject.uuid, "uuid4", return_value=subject.uuid.UUID(self.marker)), \
             patch.object(subject.secrets, "token_hex", side_effect=["d" * 64, "e" * 64]), \
             contextlib.redirect_stdout(io.StringIO()):
            ownership = subject.create_container(state, "12345", "1", self.lane)
        arguments = command.call_args_list[3].args[0]
        self.assertEqual(arguments[arguments.index("--name") + 1], self.lane.state_prefix + "12345-1")
        self.assertIn(pinned, arguments)
        self.assertNotIn(subject.IMAGE_TAG, arguments)
        self.assertEqual(ownership["database"], self.lane.database)
        self.assertEqual(ownership["instance_id"], self.marker)
        self.assertIn("POSTGRES_DB=msf_ci_risk_source\n", (state / "postgres.env").read_text())
        self.assertEqual(subject.load_json(state / "resources.json")["name"], self.lane.state_prefix + "12345-1")

    @patch.object(subject, "terminate_owned_group")
    @patch.object(subject.subprocess, "Popen")
    def test_source_maven_uses_only_its_profile_and_shared_owned_session_cleanup(self, popen, stop):
        state = self.root / "maven-state"; state.mkdir()
        process = popen.return_value; process.pid = 27123
        receipts = []
        def completed(**_arguments):
            if not receipts:
                receipts.append(subject.load_json(state / "maven-process.json"))
            return 0
        process.wait.side_effect = completed
        self.assertEqual(subject.run_maven(self.root, state, {}, self.lane), 0)
        args = popen.call_args.args[0]
        self.assertIn("-Pci-pg18-risk-source", args)
        self.assertNotIn("-Pci-pg18-risk-capture", args)
        self.assertTrue(popen.call_args.kwargs["start_new_session"])
        marker = popen.call_args.kwargs["env"]["MSF_PG18_PROCESS_MARKER"]
        self.assertEqual(receipts, [{"pgid": 27123, "marker": marker, "acceptanceKind": self.lane.kind}])
        stop.assert_called_once_with(27123, marker)
        self.assertFalse((state / "maven-starting").exists())
        self.assertFalse((state / "maven-process.json").exists())

    @patch.object(subject, "source_revision", return_value={"commit": "1" * 40, "tree": "2" * 40})
    @patch.object(subject, "cleanup_container", return_value={"verified": True})
    @patch.object(subject, "create_container")
    @patch.object(subject, "run_maven", return_value=0)
    def test_source_run_uses_fixed_child_target_and_its_evidence_without_changing_capture_artifact(self, maven, create, cleanup, revision):
        self.reports()
        evidence = self.root / "backend/target" / self.lane.evidence_file
        evidence.write_text(json.dumps(self.evidence()))
        capture = subject.result_path(self.root); capture.parent.mkdir(parents=True)
        capture.write_text("capture artifact untouched")
        def bind_before_artifact(_repo, _environment):
            self.assertFalse(subject.result_path(self.root, self.lane).exists())
            return {"commit": "1" * 40, "tree": "2" * 40}
        revision.side_effect = bind_before_artifact
        create.return_value = {"image_digest": self.digest, "engine_major": "28", "port": "35432",
                               "container_id": "a" * 64, "instance_id": self.marker}
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(subject.run_acceptance(self.root, self.environment, self.lane), 0)
        child = maven.call_args.args[2]
        self.assertEqual(maven.call_args.args[3], self.lane)
        self.assertEqual(child["MSF_CI_PG18_ACK"], self.lane.ack)
        self.assertEqual(child["MSF_CI_PG18_URL"], "jdbc:postgresql://127.0.0.1:35432/msf_ci_risk_source")
        result = json.loads(subject.result_path(self.root, self.lane).read_text())
        self.assertEqual(result["status"], "PASS")
        self.assertEqual(result["acceptanceKind"], self.lane.kind)
        self.assertEqual(result["tests"]["integrationTests"], 3)
        self.assertEqual(result["databaseEvidence"], self.evidence())
        self.assertEqual(capture.read_text(), "capture artifact untouched")
        self.assertEqual(cleanup.call_args.args[3], self.lane)


if __name__ == "__main__":
    unittest.main()

import copy
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from xml.etree.ElementTree import Element, SubElement, tostring

import risk_capture as subject


class HarnessTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.digest = "sha256:" + "b" * 64

    def evidence(self):
        return {"schemaVersion": 1, "postgresMajor": 18, "database": subject.DATABASE,
                "instanceMarkerVerified": True, "imageDigest": self.digest,
                "migrations": [{"version": version, "script": "V" + version + "__fixture.sql", "checksum": 123}
                               for version in subject.VERSIONS],
                "causeSchemaVerified": True, "runtimeRightsVerified": True}

    def report(self, classname, names, **changes):
        values = {"name": classname, "tests": str(len(names)), "failures": "0", "errors": "0", "skipped": "0"}
        values.update({key: str(value) for key, value in changes.items()})
        tree = Element("testsuite", values)
        for name in names:
            SubElement(tree, "testcase", {"classname": classname, "name": name})
        return tree

    def write_report(self, classname, names, failsafe=True, **changes):
        directory = self.root / "backend" / "target" / ("failsafe-reports" if failsafe else "surefire-reports")
        directory.mkdir(parents=True, exist_ok=True)
        path = directory / ("TEST-" + classname + ".xml")
        tree = self.report(classname, names, **changes)
        path.write_bytes(tostring(tree))
        return path

    def reports(self):
        integration = self.write_report(subject.IT_CLASS, sorted(subject.SCENARIOS))
        unit = self.write_report(subject.UNIT_CLASS, ["strictTarget"], failsafe=False)
        return integration, unit

    def state(self):
        path = self.root / "state"
        path.mkdir()
        (path / "intent.json").write_text(json.dumps({
            "name": "msf-ci-pg18-12345-1", "instance": "11111111-2222-4333-8444-555555555555"}))
        return path

    def resources(self, identity, volume):
        return {"container_id": identity, "volumes": [volume], "run_id": "12345", "run_attempt": "1",
                "instance": "11111111-2222-4333-8444-555555555555", "name": "msf-ci-pg18-12345-1"}

    def test_report_requires_every_named_real_scenario_once(self):
        self.reports()
        result = subject.verify_reports(self.root)
        self.assertEqual(result["integrationTests"], 9)
        self.assertEqual(result["targetGuardTests"], 1)
        self.assertEqual(set(result["scenarios"]), subject.SCENARIOS)

    def test_missing_zero_skipped_failed_or_error_reports_never_pass(self):
        self.reports()
        cases = sorted(subject.SCENARIOS)
        for changes in ({"tests": 0}, {"failures": 1}, {"errors": 1}, {"skipped": 1}, {"tests": 10}):
            with self.subTest(changes=changes):
                self.write_report(subject.IT_CLASS, cases, **changes)
                with self.assertRaises(subject.Rejected):
                    subject.verify_reports(self.root)
        path = self.write_report(subject.IT_CLASS, cases)
        path.unlink()
        with self.assertRaises(subject.Rejected):
            subject.verify_reports(self.root)

    def test_missing_duplicate_and_unknown_cases_fail(self):
        self.reports()
        original = sorted(subject.SCENARIOS)
        for names in (original[:-1], original[:-1] + [original[0]], original + ["unreviewedExtraCase"]):
            with self.subTest(names=names):
                self.write_report(subject.IT_CLASS, names)
                with self.assertRaises(subject.Rejected):
                    subject.verify_reports(self.root)

    def test_success_logs_are_never_returned_and_failure_nodes_are_rejected(self):
        path = self.write_report(subject.IT_CLASS, sorted(subject.SCENARIOS))
        root = self.report(subject.IT_CLASS, sorted(subject.SCENARIOS))
        SubElement(root.find("testcase"), "system-out").text = "PRIVATE SQL BODY"
        path.write_bytes(tostring(root))
        self.assertEqual(subject.validate_report(path, subject.IT_CLASS, subject.SCENARIOS), 9)
        SubElement(root.find("testcase"), "failure").text = "PRIVATE FAILURE"
        path.write_bytes(tostring(root))
        with self.assertRaises(subject.Rejected) as caught:
            subject.validate_report(path, subject.IT_CLASS, subject.SCENARIOS)
        self.assertNotIn("PRIVATE", str(caught.exception))

    def test_extra_suite_or_zero_target_guard_fails(self):
        self.reports()
        self.write_report("other.UnselectedIT", ["ignored"])
        with self.assertRaises(subject.Rejected):
            subject.verify_reports(self.root)
        (self.root / "backend/target/failsafe-reports/TEST-other.UnselectedIT.xml").unlink()
        self.write_report(subject.UNIT_CLASS, [], failsafe=False)
        with self.assertRaises(subject.Rejected):
            subject.verify_reports(self.root)

    def test_identity_xml_entity_and_classname_mismatch_fail(self):
        path = self.write_report(subject.IT_CLASS, sorted(subject.SCENARIOS))
        path.write_text('<!DOCTYPE x [<!ENTITY secret "private">]><testsuite/>')
        with self.assertRaises(subject.Rejected):
            subject.validate_report(path, subject.IT_CLASS, subject.SCENARIOS)
        root = self.report(subject.IT_CLASS, sorted(subject.SCENARIOS))
        root.find("testcase").set("classname", "other.ForeignIT")
        path.write_bytes(tostring(root))
        with self.assertRaises(subject.Rejected):
            subject.validate_report(path, subject.IT_CLASS, subject.SCENARIOS)

    def test_only_exact_complete_original_migration_evidence_passes(self):
        data = self.evidence()
        safe = subject.validate_success_evidence(data, self.digest)
        self.assertEqual([entry["version"] for entry in safe["migrations"]], list(subject.VERSIONS))
        for mutation in ("extra", "missing", "duplicate", "script", "checksum", "wrong-image", "unchecked-rights"):
            broken = copy.deepcopy(data)
            if mutation == "extra":
                broken["rawSql"] = "PRIVATE SQL"
            elif mutation == "missing":
                broken["migrations"].pop()
            elif mutation == "duplicate":
                broken["migrations"][-1] = broken["migrations"][0]
            elif mutation == "script":
                broken["migrations"][0]["script"] = "V2__../../private.sql"
            elif mutation == "checksum":
                broken["migrations"][0]["checksum"] = True
            elif mutation == "wrong-image":
                broken["imageDigest"] = "postgres:18-bookworm"
            else:
                broken["runtimeRightsVerified"] = False
            with self.subTest(mutation=mutation), self.assertRaises(subject.Rejected) as caught:
                subject.validate_success_evidence(broken, self.digest)
            self.assertNotIn("PRIVATE", str(caught.exception))

    def test_failure_diagnostics_are_fixed_whitelist_fields_only(self):
        safe = {"schemaVersion": 1, "phase": "migrate", "migrationVersion": None, "sqlState": "42601"}
        self.assertEqual(subject.validate_failure_evidence(safe), safe)
        for changed in (dict(safe, message="PRIVATE"), dict(safe, phase="SELECT PRIVATE"),
                        dict(safe, sqlState="PRIVATE"), dict(safe, migrationVersion="9")):
            with self.assertRaises(subject.Rejected) as caught:
                subject.validate_failure_evidence(changed)
            self.assertNotIn("PRIVATE", str(caught.exception))

    def test_duplicate_json_keys_are_rejected(self):
        path = self.root / "evidence.json"
        path.write_text('{"schemaVersion":1,"schemaVersion":2}')
        with self.assertRaises(subject.Rejected):
            subject.load_json(path)

    def test_context_refuses_operator_target_lan_and_tunnel(self):
        environment = {"GITHUB_ACTIONS": "true", "CI": "true", "GITHUB_REPOSITORY": subject.REPOSITORY,
                       "GITHUB_RUN_ID": "12345", "GITHUB_RUN_ATTEMPT": "1", "RUNNER_TEMP": str(self.root)}
        self.assertEqual(subject.context(environment)[:2], ("12345", "1"))
        for key in ("LAN_PG_URL", "PG18_TUNNEL_LOCAL_PORT", "MSF_CI_PG18_URL"):
            with self.subTest(key=key), self.assertRaises(subject.Rejected):
                subject.context(dict(environment, **{key: "operator-supplied"}))

    @patch.object(subject, "docker_field")
    @patch.object(subject, "command")
    def test_foreign_container_is_never_deleted(self, command, inspect):
        state = self.state()
        command.return_value = subprocess.CompletedProcess([], 0, stdout="a" * 64 + "\n", stderr="")
        inspect.return_value = {subject.LABEL_PREFIX + "kind": "unrelated"}
        with self.assertRaisesRegex(subject.Rejected, "CLEANUP_OWNERSHIP"):
            subject.cleanup_container(state, "12345", "1")
        self.assertFalse(any(call.args[0][:2] == ["docker", "rm"] for call in command.call_args_list))
        self.assertTrue(state.exists())

    @patch.object(subject, "docker_field")
    @patch.object(subject, "command")
    def test_exact_owned_container_and_anonymous_volume_are_verified_absent(self, command, inspect):
        state = self.state()
        (state / "admin.password").write_text("PRIVATE")
        (state / "maven.log").write_text("PRIVATE")
        identity, volume = "a" * 64, "b" * 64
        command.side_effect = [
            subprocess.CompletedProcess([], 0, identity + "\n", ""),
            subprocess.CompletedProcess([], 0, identity + "\n", ""),
            subprocess.CompletedProcess([], 0, "", ""),
            subprocess.CompletedProcess([], 0, "", ""),
        ]
        inspect.side_effect = [
            subject.expected_labels("12345", "1", "11111111-2222-4333-8444-555555555555"),
            [{"Type": "volume", "Destination": "/var/lib/postgresql", "Name": volume}],
        ]
        result = subject.cleanup_container(state, "12345", "1")
        self.assertTrue(result["verified"])
        self.assertTrue(result["anonymousVolumesRemoved"])
        self.assertFalse(state.exists())
        self.assertIn(unittest.mock.call(["docker", "rm", "--force", "--volumes", identity]),
                      command.call_args_list)

    @patch.object(subject, "command")
    def test_disappeared_container_without_volume_receipt_is_not_claimed_clean(self, command):
        state = self.state()
        (state / "admin.password").write_text("PRIVATE")
        command.return_value = subprocess.CompletedProcess([], 0, "", "")
        result = subject.cleanup_container(state, "12345", "1")
        self.assertFalse(result["verified"])
        self.assertTrue(result["anonymousVolumesUnknown"])
        self.assertFalse((state / "admin.password").exists())

    @patch.object(subject, "command")
    def test_recorded_orphan_anonymous_volume_is_removed_and_checked(self, command):
        state = self.state()
        identity, volume = "a" * 64, "b" * 64
        (state / "resources.json").write_text(json.dumps(self.resources(identity, volume)))
        command.side_effect = [
            subprocess.CompletedProcess([], 0, "", ""),
            subprocess.CompletedProcess([], 0, volume + "\n", ""),
            subprocess.CompletedProcess([], 0, volume + "\n", ""),
            subprocess.CompletedProcess([], 0, "", ""),
        ]
        result = subject.cleanup_container(state, "12345", "1")
        self.assertTrue(result["verified"])
        self.assertIn(unittest.mock.call(["docker", "volume", "rm", volume]), command.call_args_list)
        self.assertFalse(state.exists())

    @patch.object(subject, "command")
    def test_remaining_anonymous_volume_fails_cleanup(self, command):
        state = self.state()
        (state / "resources.json").write_text(json.dumps(self.resources("a" * 64, "b" * 64)))
        command.side_effect = [
            subprocess.CompletedProcess([], 0, "", ""),
            subprocess.CompletedProcess([], 0, "", ""),
            subprocess.CompletedProcess([], 0, "b" * 64 + "\n", ""),
        ]
        with self.assertRaisesRegex(subject.Rejected, "CLEANUP_VOLUME_REMAINS"):
            subject.cleanup_container(state, "12345", "1")


    def test_failed_maven_diagnostics_never_copy_failure_body(self):
        path, _ = self.reports()
        tree = self.report(subject.IT_CLASS, sorted(subject.SCENARIOS), failures=1)
        first = tree.find("testcase")
        SubElement(first, "failure", {"message": "PRIVATE SQL"}).text = "PRIVATE PASSWORD"
        SubElement(first, "system-out").text = "PRIVATE LOG"
        path.write_bytes(tostring(tree))
        diagnostic = subject.report_diagnostics(self.root)
        self.assertEqual(diagnostic["scenarios"][first.get("name")], "failed")
        self.assertEqual(diagnostic["integration"]["failures"], 1)
        self.assertNotIn("PRIVATE", json.dumps(diagnostic))

    @patch.object(subject, "command")
    def test_orphan_volume_receipt_from_another_instance_is_never_used(self, command):
        state = self.state()
        resources = self.resources("a" * 64, "b" * 64)
        resources["instance"] = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
        (state / "resources.json").write_text(json.dumps(resources))
        command.return_value = subprocess.CompletedProcess([], 0, "", "")
        with self.assertRaisesRegex(subject.Rejected, "CLEANUP_RESOURCES"):
            subject.cleanup_container(state, "12345", "1")
        self.assertFalse(any(call.args[0][:3] == ["docker", "volume", "rm"] for call in command.call_args_list))

    @patch.object(subject, "cleanup_container", return_value={"verified": False})
    @patch.object(subject, "context")
    def test_always_cleanup_demotes_previous_pass_if_cleanup_is_unverified(self, context, cleanup):
        state = self.state()
        context.return_value = ("12345", "1", state)
        subject.write_result(self.root, {"schemaVersion": 1, "status": "PASS", "stage": "COMPLETE"})
        self.assertEqual(subject.always_cleanup(self.root, {}), 1)
        result = json.loads(subject.result_path(self.root).read_text())
        self.assertEqual(result["status"], "FAILED")
        self.assertEqual(result["stage"], "CLEANUP_UNVERIFIED")

    @patch.object(subject, "context")
    def test_missing_state_cannot_preserve_an_unverified_pass(self, context):
        context.return_value = ("12345", "1", self.root / "missing")
        subject.write_result(self.root, {"schemaVersion": 1, "status": "PASS", "stage": "COMPLETE",
                                        "cleanup": {"verified": False}})
        self.assertEqual(subject.always_cleanup(self.root, {}), 1)
        self.assertEqual(json.loads(subject.result_path(self.root).read_text())["status"], "FAILED")

    def test_process_observation_requires_exact_session_and_private_marker(self):
        proc = self.root / "proc"
        entry = proc / "27123"
        entry.mkdir(parents=True)
        (entry / "stat").write_text("27123 (java with spaces) S 1 27123 27123 0 0 0\n")
        (entry / "environ").write_bytes(b"OTHER=private\0MSF_PG18_PROCESS_MARKER=" + b"e" * 32 + b"\0")
        self.assertEqual(subject.active_process_group(27123, "e" * 32, proc), [27123])
        with self.assertRaisesRegex(subject.Rejected, "MAVEN_GROUP_OWNERSHIP"):
            subject.active_process_group(27123, "f" * 32, proc)
        (entry / "stat").write_text("27123 (java) Z 1 27123 27123 0 0 0\n")
        self.assertEqual(subject.active_process_group(27123, "f" * 32, proc), [])

    @patch.object(subject.os, "killpg")
    @patch.object(subject, "active_process_group", side_effect=subject.Rejected("MAVEN_GROUP_OWNERSHIP"))
    def test_group_with_unproven_ownership_is_never_signaled(self, observe, kill):
        with self.assertRaisesRegex(subject.Rejected, "MAVEN_GROUP_OWNERSHIP"):
            subject.terminate_owned_group(27123, "e" * 32)
        kill.assert_not_called()

    @patch.object(subject, "terminate_owned_group")
    @patch.object(subject.subprocess, "Popen")
    @patch.object(subject.secrets, "token_hex", return_value="e" * 32)
    def test_maven_timeout_stops_its_exact_session_and_waits_before_returning(self, random, popen, stop):
        state = self.root / "process-state"
        state.mkdir()
        process = popen.return_value
        process.pid = 27123
        process.wait.side_effect = [subprocess.TimeoutExpired("private-command", 900), 0]
        with self.assertRaisesRegex(subject.Rejected, "MAVEN_TIMEOUT"):
            subject.run_maven(self.root, state, {})
        self.assertTrue(popen.call_args.kwargs["start_new_session"])
        self.assertEqual(popen.call_args.kwargs["env"]["MSF_PG18_PROCESS_MARKER"], "e" * 32)
        stop.assert_called_once_with(27123, "e" * 32)
        self.assertEqual(process.wait.call_count, 2)
        self.assertFalse((state / "maven-process.json").exists())
        self.assertFalse((state / "maven-starting").exists())

    @patch.object(subject, "command")
    def test_unknown_maven_launch_state_prevents_database_deletion(self, command):
        state = self.state()
        (state / "maven-starting").write_text("e" * 32)
        with self.assertRaisesRegex(subject.Rejected, "MAVEN_GROUP_UNKNOWN"):
            subject.cleanup_container(state, "12345", "1")
        command.assert_not_called()


    def test_public_diagnostics_drops_unknown_fields_and_failure_text(self):
        value = {"status": "FAILED", "cleanup": {"verified": True}, "raw": "PRIVATE",
                 "reportDiagnostics": {"scenarios": {next(iter(subject.SCENARIOS)): "failed",
                                                       "PRIVATE": "passed"},
                                       "integration": {"tests": 9, "failures": 1, "message": "PRIVATE"}},
                 "databaseFailure": {"schemaVersion": 1, "phase": "migrate",
                                     "migrationVersion": None, "sqlState": "42501"}}
        rendered = subject.public_diagnostics(value)
        self.assertNotIn("PRIVATE", json.dumps(rendered))
        self.assertEqual(rendered["integration"], {"tests": 9, "failures": 1})
        self.assertEqual(rendered["databaseFailure"]["sqlState"], "42501")

    @patch.object(subject, "docker_field")
    @patch.object(subject, "command")
    def test_mount_drift_or_unknown_mount_is_rejected_before_removal(self, command, inspect):
        state = self.state()
        identity, recorded = "a" * 64, "b" * 64
        (state / "resources.json").write_text(json.dumps(self.resources(identity, recorded)))
        command.return_value = subprocess.CompletedProcess([], 0, identity + "\n", "")
        labels = subject.expected_labels("12345", "1", "11111111-2222-4333-8444-555555555555")
        for mounts, expected in (
            ([{"Type": "volume", "Destination": "/var/lib/postgresql", "Name": "c" * 64}], "CLEANUP_VOLUME_DRIFT"),
            ([{"Type": "bind", "Destination": "/var/lib/postgresql", "Name": recorded}], "CLEANUP_MOUNTS")):
            with self.subTest(expected=expected):
                command.reset_mock()
                inspect.side_effect = [labels, mounts]
                with self.assertRaisesRegex(subject.Rejected, expected):
                    subject.cleanup_container(state, "12345", "1")
                self.assertEqual(command.call_count, 1)
                self.assertTrue((state / "resources.json").exists())

if __name__ == "__main__":
    unittest.main()

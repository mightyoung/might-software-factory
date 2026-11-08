import copy
import dataclasses
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from xml.etree.ElementTree import Element, SubElement, tostring

import risk_capture as subject
import risk_reauth as entry


class ReauthLaneTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.lane = subject.REAUTH
        self.digest = "sha256:" + "b" * 64
        self.environment = {"GITHUB_ACTIONS": "true", "CI": "true",
                            "GITHUB_REPOSITORY": subject.REPOSITORY, "GITHUB_RUN_ID": "12345",
                            "GITHUB_RUN_ATTEMPT": "1", "RUNNER_TEMP": str(self.root)}

    def evidence(self, lane):
        return {"schemaVersion": 1, "postgresMajor": 18, "database": lane.database,
                "instanceMarkerVerified": True, "imageDigest": self.digest,
                "migrations": [{"version": version, "script": "V" + version + "__fixture.sql", "checksum": 123}
                               for version in subject.VERSIONS],
                lane.schema_field: True, "runtimeRightsVerified": True}

    def report(self, classname, names, folder):
        directory = self.root / "backend/target" / folder
        directory.mkdir(parents=True, exist_ok=True)
        tree = Element("testsuite", {"name": classname, "tests": str(len(names)),
                                    "failures": "0", "errors": "0", "skipped": "0"})
        for name in names:
            SubElement(tree, "testcase", {"classname": classname, "name": name})
        path = directory / ("TEST-" + classname + ".xml")
        path.write_bytes(tostring(tree))
        return path

    def reports(self, guards=55):
        self.report(self.lane.it_class, sorted(self.lane.scenarios), "failsafe-reports")
        self.report(self.lane.unit_class, ["guard" + str(n) for n in range(guards)], "surefire-reports")

    def test_entry_is_literal_and_copied_or_mutable_specs_are_rejected(self):
        with patch.object(subject, "main", return_value=7) as main:
            self.assertEqual(entry.main(), 7)
        main.assert_called_once_with(self.lane)
        with self.assertRaises(dataclasses.FrozenInstanceError):
            self.lane.database = "operator"
        with self.assertRaisesRegex(subject.Rejected, "LANE"):
            subject.context(self.environment, copy.copy(self.lane))

    def test_state_receipt_artifact_and_target_are_disjoint_from_other_lanes(self):
        self.assertEqual(self.lane.database, "msf_ci_g008_reauth")
        self.assertEqual(self.lane.runtime_role, "msf_ci_reauth_runtime")
        self.assertEqual(self.lane.ack, "ephemeral-g008-reauth-only")
        self.assertEqual(self.lane.profile, "ci-pg18-g008-reauth")
        for other in (subject.CAPTURE, subject.REGISTRY, subject.SOURCE):
            with self.subTest(other=other.kind):
                self.assertNotEqual(subject.context(self.environment, self.lane)[2],
                                    subject.context(self.environment, other)[2])
                self.assertNotEqual(subject.result_path(self.root, self.lane), subject.result_path(self.root, other))
                self.assertNotEqual(subject.expected_labels("12345", "1", "marker", self.lane),
                                    subject.expected_labels("12345", "1", "marker", other))
                with self.assertRaises(subject.Rejected):
                    subject.validate_success_evidence(self.evidence(other), self.digest, self.lane)
                with self.assertRaises(subject.Rejected):
                    subject.validate_success_evidence(self.evidence(self.lane), self.digest, other)

    def test_operator_and_lan_overrides_are_rejected(self):
        for key in ("LAN_PG_URL", "PG18_TUNNEL_LOCAL_PORT", "MSF_CI_PG18_URL", "MSF_CI_PG18_LANE"):
            with self.subTest(key=key), self.assertRaisesRegex(subject.Rejected, "FOREIGN_TARGET"):
                subject.context(dict(self.environment, **{key: "operator"}), self.lane)

    def test_exact_ten_scenarios_and_fifty_five_guards_are_required(self):
        self.reports()
        result = subject.verify_reports(self.root, self.lane)
        self.assertEqual(result["integrationTests"], 10)
        self.assertEqual(result["targetGuardTests"], 55)
        self.reports(54)
        with self.assertRaisesRegex(subject.Rejected, "REPORT_GUARD_COUNT"):
            subject.verify_reports(self.root, self.lane)

    def test_missing_scenario_and_extra_suite_are_not_acceptance(self):
        self.reports()
        self.report(self.lane.it_class, sorted(self.lane.scenarios)[1:], "failsafe-reports")
        with self.assertRaises(subject.Rejected):
            subject.verify_reports(self.root, self.lane)
        self.reports()
        self.report(subject.SOURCE.it_class, sorted(subject.SOURCE.scenarios), "failsafe-reports")
        with self.assertRaisesRegex(subject.Rejected, "REPORT_EXTRA_SUITE"):
            subject.verify_reports(self.root, self.lane)

    def test_pass_requires_matching_schema_tests_and_source_revision(self):
        self.reports()
        result = {"schemaVersion": 1, "status": "PASS", "stage": "COMPLETE",
                  "acceptanceKind": self.lane.kind, "imageDigest": self.digest,
                  "databaseEvidence": self.evidence(self.lane),
                  "tests": subject.verify_reports(self.root, self.lane),
                  "sourceRevision": {"commit": "a" * 40, "tree": "b" * 40}}
        subject._result_lane(result, self.lane)
        for change in ({"sourceRevision": None}, {"sourceRevision": {"commit": "a" * 40}},
                       {"acceptanceKind": subject.SOURCE.kind},
                       {"databaseEvidence": self.evidence(subject.SOURCE)}):
            with self.subTest(change=change), self.assertRaises(subject.Rejected):
                subject._result_lane(dict(result, **change), self.lane)


if __name__ == "__main__":
    unittest.main()

"""Keep fixed CI inventories aligned with the migrations shipped by this checkout."""
import copy
import re
import unittest
from pathlib import Path

import risk_capture as subject


ROOT = Path(__file__).resolve().parents[2]
MIGRATIONS = sorted(
    ROOT.joinpath("backend/src/main/resources/db/migration").glob("V*__*.sql"),
    key=lambda path: tuple(int(part) for part in path.name[1:].split("__")[0].split("_")),
)
VERSIONS = tuple(path.name[1:].split("__")[0].replace("_", ".") for path in MIGRATIONS)


class MigrationInventoryTests(unittest.TestCase):
    def test_every_fixed_inventory_matches_the_shipped_migrations(self):
        self.assertEqual(subject.VERSIONS, VERSIONS)
        fixtures = list(ROOT.glob("backend/src/ci-pg18*-test/java/**/CiPg18*Fixture.java"))
        inventories = 0
        for path in fixtures:
            match = re.search(r"\bVERSIONS\s*=\s*List\.of\(([^;]+)\);", path.read_text())
            if match:
                inventories += 1
                with self.subTest(fixture=path.name):
                    self.assertEqual(tuple(re.findall(r'"([0-9.]+)"', match[1])), VERSIONS)
        self.assertEqual(inventories, 4)

    def test_all_lanes_accept_real_segmented_filenames_and_reject_wrong_version(self):
        digest = "sha256:" + "a" * 64
        migrations = [dict(version=version, script=path.name, checksum=123)
                      for version, path in zip(VERSIONS, MIGRATIONS)]
        for lane in (subject.CAPTURE, subject.REGISTRY, subject.SOURCE, subject.RECOVERY):
            with self.subTest(lane=lane.kind):
                evidence = dict(schemaVersion=1, postgresMajor=18, database=lane.database,
                                instanceMarkerVerified=True, imageDigest=digest,
                                migrations=migrations, runtimeRightsVerified=True,
                                **{lane.schema_field: True})
                if lane is subject.RECOVERY:
                    evidence["captureRightsVerified"] = True
                self.assertEqual(subject.validate_success_evidence(evidence, digest, lane), evidence)
                broken = copy.deepcopy(evidence)
                segmented = next(row for row in broken["migrations"] if row["version"] == "18.1")
                segmented["script"] = "V18_2__trusted_ingestion_publication.sql"
                with self.assertRaises(subject.Rejected):
                    subject.validate_success_evidence(broken, digest, lane)


if __name__ == "__main__":
    unittest.main()

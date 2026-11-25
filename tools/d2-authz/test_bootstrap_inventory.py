from hashlib import sha256
from pathlib import Path
import tempfile
import unittest

from bootstrap_inventory import app_migrations, review_inputs

ROOT = Path(__file__).resolve().parents[2]


class BootstrapInventoryTest(unittest.TestCase):
    def test_current_checkout_uses_original_discontinuous_flyway_history(self):
        migrations = app_migrations(ROOT)
        versions = list(migrations)
        self.assertEqual(versions[:8], ["2", "3", "4", "5", "6", "7", "8", "11"])
        self.assertEqual(versions[versions.index("18"):versions.index("19")], ["18", "18.1", "18.2", "18.3"])
        self.assertEqual(versions[-4:], ["34", "35", "36", "37"])
        self.assertEqual(len(versions), 37)

    def test_unexpected_directory_is_never_accepted(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / "backend/src/main/resources/db/migration"
            path.mkdir(parents=True)
            (path / "V9__generated.sql").write_text("not an application migration")
            with self.assertRaises(ValueError):
                app_migrations(Path(root))

    def test_review_manifest_does_not_claim_existing_grants_are_runnable(self):
        result = review_inputs(ROOT)
        self.assertEqual(result["target"], "msf_au08_test")
        self.assertFalse(result["ready"])
        for name in ("framework", "d0_grants", "d1_grants", "d2_grants",
                     "d1_fixture", "deploy_bpmn", "bpmn", "acceptance"):
            self.assertIn(name, result["sources"])
            self.assertTrue(all(len(digest) == 64 for digest in result["sources"][name].values()))

    def test_captures_fixed_role_grant_deltas_as_review_inputs_not_runnable_bootstrap(self):
        result = review_inputs(ROOT)
        self.assertFalse(result["ready"])
        fixed = result["sources"]["fixed_role_grant_deltas"]
        self.assertEqual(list(fixed), [
            "backend/src/main/resources/db/operator/V25-runtime-grants.sql",
            "backend/src/main/resources/db/operator/V36-runtime-grants.sql",
            "backend/src/main/resources/db/operator/V37-runtime-grants.sql",
        ])
        for name, digest in fixed.items():
            self.assertEqual(digest, sha256((ROOT / name).read_bytes()).hexdigest())
        self.assertIn("backend/src/main/resources/application.yml", result["sources"]["framework"])

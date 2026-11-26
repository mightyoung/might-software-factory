from hashlib import sha256
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
import zipfile

from hosted_bootstrap import MIGRATOR_ROLE, private_role_passwords, unpack_dependencies
from hosted_observation import BOOTSTRAP_ROLE, FIXTURE_ROLE, RUNTIME_ROLE


class HostedBootstrapTest(unittest.TestCase):
    def test_generated_credentials_are_distinct_for_distinct_fixed_roles(self):
        credentials = private_role_passwords()
        self.assertEqual(set(credentials), {MIGRATOR_ROLE, FIXTURE_ROLE, RUNTIME_ROLE})
        self.assertNotIn(BOOTSTRAP_ROLE, credentials)
        self.assertEqual(len(set(credentials.values())), 3)
        for value in credentials.values():
            self.assertRegex(value, r"^[0-9a-f]{64}$")

    def test_framework_libraries_are_copied_from_exact_acceptance_jar(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            jar = root / "app.jar"
            with zipfile.ZipFile(jar, "w") as zf:
                zf.writestr("BOOT-INF/lib/flowable-engine-8.0.0.jar", b"flowable")
                zf.writestr("BOOT-INF/lib/postgresql-42.7.jar", b"pg")
                zf.writestr("BOOT-INF/classes/application.yml", b"not extracted")
            libs = unpack_dependencies(jar, root / "lib", sha256(jar.read_bytes()).hexdigest())
            self.assertEqual({p.name: p.read_bytes() for p in libs.iterdir()},
                             {"flowable-engine-8.0.0.jar": b"flowable", "postgresql-42.7.jar": b"pg"})
            with self.assertRaisesRegex(ValueError, "artifact"):
                unpack_dependencies(jar, root / "wrong", "0" * 64)
            self.assertFalse((root / "wrong").exists())

    def test_archive_cannot_copy_a_nested_or_duplicate_dependency(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            jar = root / "app.jar"
            with zipfile.ZipFile(jar, "w") as zf:
                zf.writestr("BOOT-INF/lib/../../elsewhere.jar", b"bad")
            with self.assertRaises(ValueError):
                unpack_dependencies(jar, root / "lib", sha256(jar.read_bytes()).hexdigest())
            self.assertFalse((root / "elsewhere.jar").exists())

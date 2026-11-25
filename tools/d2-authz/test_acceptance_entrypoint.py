"""Exercise the acceptance entry point without dependencies or an instance."""
from pathlib import Path
import subprocess
import sys
import unittest


class AcceptanceEntrypointTest(unittest.TestCase):
    def test_optimized_python_refuses_before_loading_dependencies_or_fixture(self):
        script = Path(__file__).with_name("acceptance.py")
        for option in ("-O", "-OO"):
            with self.subTest(option=option):
                result = subprocess.run(
                    [sys.executable, "-I", "-S", option, str(script), "verify"],
                    env={}, capture_output=True, text=True, timeout=5, check=False,
                )
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(result.stdout, "")
                self.assertEqual(
                    result.stderr.strip(),
                    "AU-08 requires Python assertions; do not use optimized mode.",
                )

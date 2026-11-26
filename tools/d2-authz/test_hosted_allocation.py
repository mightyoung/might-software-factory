import os
from pathlib import Path
import unittest

from hosted_allocation import IMAGE, create_command, cleanup_volume, runner_context

RUN = "312"
ATTEMPT = "4"
MARKER = "12345678-1234-4234-8234-123456789abc"
ID = "1" * 64
VOL = "2" * 64
HEAD = "b" * 40


class HostedAllocationTest(unittest.TestCase):
    def environment(self):
        return {"GITHUB_ACTIONS": "true", "RUNNER_ENVIRONMENT": "github-hosted",
                "RUNNER_OS": "Linux", "GITHUB_REPOSITORY": "mightyoung/might-software-factory",
                "GITHUB_SHA": HEAD, "GITHUB_RUN_ID": RUN, "GITHUB_RUN_ATTEMPT": ATTEMPT,
                "RUNNER_TEMP": "/tmp"}

    def test_rejects_foreign_checkout_and_endpoint_overrides_before_docker(self):
        env = self.environment()
        self.assertEqual(runner_context(env, HEAD, False)[:2], (RUN, ATTEMPT))
        for altered, head, dirty in [
            ({**env, "DOCKER_HOST": ""}, HEAD, False),
            ({**env, "PGOPTIONS": "-c role=postgres"}, HEAD, False),
            ({**env, "RUNNER_ENVIRONMENT": "self-hosted"}, HEAD, False),
            ({**env, "GITHUB_RUN_ID": "312/x"}, HEAD, False),
            (env, "a" * 40, False), (env, HEAD, True),
        ]:
            with self.subTest(env=altered, head=head, dirty=dirty), self.assertRaises(ValueError):
                runner_context(altered, head, dirty)

    def test_container_only_accepts_pinned_official_image_private_env_and_loopback(self):
        cmd = create_command(RUN, ATTEMPT, MARKER, Path("/tmp/postgres.env"))
        self.assertEqual(cmd[cmd.index("--publish") + 1], "127.0.0.1::5432/tcp")
        self.assertEqual(cmd[cmd.index("--name") + 1], "msf-ci-au08-312-4")
        self.assertEqual(cmd[cmd.index("--env-file") + 1], "/tmp/postgres.env")
        self.assertIn("com.mightyoung.ci.pg18.instance=" + MARKER, cmd)
        self.assertRegex(IMAGE, r"^docker\.io/library/postgres@sha256:[0-9a-f]{64}$")
        self.assertIn(IMAGE, cmd)
        self.assertIn("msf.ci_instance=" + MARKER, cmd)
        self.assertFalse({"--privileged", "--volume", "--mount"} & set(cmd))

    def test_cleanup_only_accepts_exact_owned_container_and_anonymous_volume(self):
        cmd = create_command(RUN, ATTEMPT, MARKER, Path("/tmp/postgres.env"))
        labels = dict(arg.split("=", 1) for arg in cmd if arg.startswith("com.mightyoung."))
        row = {"Id": ID, "Name": "/msf-ci-au08-312-4", "Config": {"Labels": labels, "Image": IMAGE},
               "Mounts": [{"Type": "volume", "Destination": "/var/lib/postgresql", "Name": VOL}]}
        self.assertEqual(cleanup_volume(row, ID, RUN, ATTEMPT, MARKER), VOL)
        for altered in [{**row, "Id": "f" * 64}, {**row, "Name": "/anything"},
                        {**row, "Mounts": [{"Type": "bind", "Name": VOL}]},
                        {**row, "Config": {"Labels": {}, "Image": IMAGE}},
                        {**row, "Mounts": [{**row["Mounts"][0], "Name": "shared-postgres"}]}]:
            with self.subTest(altered=altered), self.assertRaises(ValueError):
                cleanup_volume(altered, ID, RUN, ATTEMPT, MARKER)

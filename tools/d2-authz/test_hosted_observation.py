from copy import deepcopy
import json
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from hosted_observation import inspect_container, own_container, observe_database, observe_fixture

INSTANCE = "a6f8fa03-8d8c-4b78-9422-51713ac62e41"
IDENTITY = "a" * 64
DIGEST = "sha256:" + "c" * 64
IMAGE = "docker.io/library/postgres@" + DIGEST
PREFIX = "com.mightyoung.ci.pg18."
ROLE = "msf_ci_au08_runtime"


def actual_container():
    return {
        "Id": IDENTITY, "Name": "/msf-ci-au08-42-2",
        "Config": {"Image": IMAGE, "Labels": {
            PREFIX + "kind": "au08", PREFIX + "repository": "mightyoung/might-software-factory",
            PREFIX + "run": "42", PREFIX + "attempt": "2", PREFIX + "instance": INSTANCE,
        }},
        "State": {"Running": True},
        "HostConfig": {"Privileged": False, "NetworkMode": "bridge", "Binds": None},
        "NetworkSettings": {
            "Ports": {"5432/tcp": [{"HostIp": "127.0.0.1", "HostPort": "18432"}]},
            "Networks": {"bridge": {}},
        },
        "Mounts": [{"Type": "volume", "Destination": "/var/lib/postgresql", "Name": "b" * 64}],
    }


class HostedObservationTest(unittest.TestCase):
    def test_binds_evidence_to_exact_created_container_and_image(self):
        result = own_container(actual_container(), IDENTITY, "42", "2", INSTANCE, IMAGE)
        self.assertEqual((result.container_id, result.port, result.instance), (IDENTITY, 18432, INSTANCE))
        for bad_id, bad_image in [("named-elsewhere", IMAGE), (IDENTITY, "postgres:18-bookworm"),
                                  (IDENTITY, "example.test/postgres@" + DIGEST)]:
            with self.subTest(bad_id=bad_id, bad_image=bad_image), self.assertRaises(ValueError):
                own_container(actual_container(), bad_id, "42", "2", INSTANCE, bad_image)

    def test_refuses_broad_ports_networks_mounts_and_wrong_run(self):
        changes = [
            ("Mounts", [{"Type": "bind", "Destination": "/var/lib/postgresql", "Source": "/home"}]),
            ("NetworkSettings", {"Ports": {"5432/tcp": [{"HostIp": "0.0.0.0", "HostPort": "18432"}]},
                                 "Networks": {"bridge": {}}}),
            ("HostConfig", {"Privileged": True, "Binds": None, "NetworkMode": "bridge"}),
        ]
        for field, value in changes:
            changed = dict(actual_container(), **{field: value})
            with self.subTest(field=field), self.assertRaises(ValueError):
                own_container(changed, IDENTITY, "42", "2", INSTANCE, IMAGE)
        changed = deepcopy(actual_container())
        changed["Config"]["Labels"][PREFIX + "run"] = "43"
        with self.assertRaises(ValueError):
            own_container(changed, IDENTITY, "42", "2", INSTANCE, IMAGE)

    @patch("hosted_observation.subprocess.run")
    def test_reads_exact_id_from_fixed_daemon_not_environment(self, command):
        command.return_value = SimpleNamespace(stdout=json.dumps([actual_container()]))
        result = inspect_container(IDENTITY, "42", "2", INSTANCE, IMAGE)
        self.assertEqual(result.port, 18432)
        self.assertEqual(command.call_args.args[0], [
            "docker", "--host", "unix:///var/run/docker.sock", "inspect", "--type", "container", IDENTITY,
        ])
        self.assertNotIn("DOCKER_HOST", command.call_args.kwargs["env"])
        command.return_value = SimpleNamespace(stdout=json.dumps([]))
        with self.assertRaises(ValueError):
            inspect_container(IDENTITY, "42", "2", INSTANCE, IMAGE)

    @patch("hosted_observation._database_identity")
    def test_fixture_role_must_not_resolve_to_runtime(self, observed):
        owned = own_container(actual_container(), IDENTITY, "42", "2", INSTANCE, IMAGE)
        observed.return_value = ("msf_au08_test", ROLE, ROLE, INSTANCE, 180006, *([False] * 8))
        with self.assertRaises(ValueError):
            observe_fixture(owned, "generated-fixture-password")
        fixture = "msf_ci_au08_fixture"
        observed.return_value = ("msf_au08_test", fixture, fixture, INSTANCE, 180006, *([False] * 8))
        self.assertEqual(observe_fixture(owned, "generated-fixture-password"), INSTANCE)

    @patch("hosted_observation._database_identity")
    def test_database_identity_is_observed_under_fixed_non_admin_role(self, observed):
        owned = own_container(actual_container(), IDENTITY, "42", "2", INSTANCE, IMAGE)
        observed.return_value = ("msf_au08_test", ROLE, ROLE, INSTANCE, 180006, False, False, False, False, False, False, False, False)
        self.assertEqual(observe_database(owned, "generated-fixture-password"), INSTANCE)
        observed.assert_called_once_with(owned, ROLE, "generated-fixture-password")
        for changed in [
            ("prod", ROLE, ROLE, INSTANCE, 180006, False, False, False, False, False, False, False, False),
            ("msf_au08_test", "postgres", ROLE, INSTANCE, 180006, False, False, False, False, False, False, False, False),
            ("msf_au08_test", ROLE, ROLE, INSTANCE, 170006, False, False, False, False, False, False, False, False),
            ("msf_au08_test", ROLE, ROLE, INSTANCE, 180006, True, False, False, False, False, False, False, False),
            ("msf_au08_test", ROLE, ROLE, None, 180006, False, False, False, False, False, False, False, False),
        ]:
            observed.return_value = changed
            with self.assertRaises(ValueError):
                observe_database(owned, "generated-fixture-password")

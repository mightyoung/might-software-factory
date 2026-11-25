import json
import unittest

from hosted_contract import checked_target, phase_transition, result_summary

REV = "a" * 40
JAR = "c" * 64
INSTANCE = "a6f8fa03-8d8c-4b78-9422-51713ac62e41"
LOAN = "b40d3734-5e52-48ea-8391-dce8ab1dbac4"
CASES = [
    "issue/revocation-commits-before-blocked-command",
    "issue/permission", "issue/member",
    "issue/current-tool-unavailable/no-partial-commit",
    "return-requests/permission", "return-requests/member",
    "receive/permission", "receive/member",
    "damaged-issued-tool/return-and-receive/settled",
]


class HostedContractTest(unittest.TestCase):
    def context(self):
        return {
            "GITHUB_ACTIONS": "true",
            "GITHUB_REPOSITORY": "mightyoung/might-software-factory",
            "RUNNER_ENVIRONMENT": "github-hosted",
            "RUNNER_OS": "Linux",
            "GITHUB_SHA": REV,
        }

    def target(self, **updates):
        env = self.context()
        env.update(updates)
        return checked_target(env, REV, False, 18432)

    def test_fixed_disposable_target(self):
        target = self.target()
        self.assertEqual(target.database_host, "127.0.0.1")
        self.assertEqual(target.database_name, "msf_au08_test")
        self.assertEqual(target.database_port, 18432)
        self.assertEqual(target.commit, REV)

    def test_non_hosted_context_mismatched_checkout_and_invalid_ports_fail(self):
        for change in [
            {"GITHUB_ACTIONS": "false"}, {"RUNNER_ENVIRONMENT": "self-hosted"},
            {"GITHUB_REPOSITORY": "example/fork"}, {"RUNNER_OS": "macOS"},
            {"GITHUB_SHA": "b" * 40},
        ]:
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.target(**change)
        with self.assertRaises(ValueError):
            checked_target(self.context(), REV, True, 18432)
        for port in [5432, 65536, -1, True, "18432"]:
            with self.subTest(port=port), self.assertRaises(ValueError):
                checked_target(self.context(), REV, False, port)

    def test_inherited_connection_overrides_are_refused(self):
        for name in ["PGHOST", "PGSERVICE", "LAN_PG_APP_PASSWORD", "AU08_ORIGIN",
                     "SPRING_DATASOURCE_URL", "DOCKER_CONTEXT", "DOCKER_HOST",
                     "DOCKER_TLS_VERIFY", "D2_PASSWORD_KEEPER", "CI_PG18_JDBC_URL"]:
            with self.subTest(name=name), self.assertRaises(ValueError):
                self.target(**{name: "configured"})

    def witness(self, version, **updates):
        base = dict(commit=REV, jar_sha256=JAR, instance=INSTANCE,
                    database="msf_au08_test", definition_version=version)
        return dict(base, **updates)

    def test_two_phases_require_same_commit_artifact_and_owned_instance(self):
        phase_transition(self.target(), self.witness(1), self.witness(2))
        for mismatch in [
            {"definition_version": 1}, {"definition_version": True},
            {"commit": "b" * 40}, {"jar_sha256": "d" * 64},
            {"instance": LOAN}, {"database": "postgres"}, {"extra": "data"},
        ]:
            with self.subTest(mismatch=mismatch), self.assertRaises(ValueError):
                phase_transition(self.target(), self.witness(1), self.witness(2, **mismatch))
        with self.assertRaises(ValueError):
            phase_transition(self.target(), self.witness(1, commit="b" * 40),
                             self.witness(2, commit="b" * 40))

    def test_summary_requires_exact_cases_no_raw_operand_or_unknown_fields(self):
        raw = {"loan": LOAN, "checks": CASES}
        output = result_summary(raw)
        self.assertEqual(output["passed"], 9)
        self.assertNotIn(LOAN, json.dumps(output))
        for invalid in [
            {}, dict(raw, checks=CASES[:-1]), dict(raw, checks=CASES + [CASES[0]]),
            dict(raw, checks=CASES[:-1] + ["unexpected"]), dict(raw, connection="secret"),
            dict(raw, checks=[{}] * 9), dict(raw, loan="not-a-loan"),
        ]:
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                result_summary(invalid)

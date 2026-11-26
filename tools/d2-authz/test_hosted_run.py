from pathlib import Path
import unittest
from hosted_run import app_configuration, observed_port


class HostedRunTest(unittest.TestCase):
    def test_observes_only_an_actual_https_connector_port(self):
        self.assertIsNone(observed_port("Tomcat initialized with port 0 (https)"))
        self.assertIsNone(observed_port("Tomcat started on port 8080 (http) with context path '/'"))
        self.assertEqual(observed_port("INFO Tomcat started on port 31456 (https) with context path '/'"), 31456)
        with self.assertRaises(ValueError):
            observed_port("Tomcat started on port 31456 (https)\nTomcat started on port 31457 (https)")

    def test_app_config_enforces_loopback_tls_and_no_schema_writes(self):
        cfg = app_configuration(Path("/tmp/fixture.p12"), "random-private", "1", "tenant",
                                {"definition-id": "observed-id", "resource-sha256": "hash"})
        self.assertEqual(cfg["server"]["address"], "127.0.0.1")
        self.assertEqual(cfg["server"]["port"], 0)
        self.assertTrue(cfg["server"]["ssl"]["enabled"])
        self.assertFalse(cfg["spring"]["flyway"]["enabled"])
        self.assertFalse(cfg["flowable"]["database-schema-update"])
        self.assertEqual(cfg["software-factory"]["workflow"]["tool-loan"]["tenant-pins"]["tenant"]["definition-id"], "observed-id")

    def test_readiness_uses_the_actual_no_content_csrf_route(self):
        from hosted_run import csrf_ready
        self.assertTrue(csrf_ready(204, "from-owned-https"))
        self.assertFalse(csrf_ready(200, "not-the-expected-response"))
        self.assertFalse(csrf_ready(204, None))
        self.assertFalse(csrf_ready(302, "unexpected-redirect"))

    def test_connection_witness_requires_runtime_and_run_name_without_elevated_stats(self):
        from contextlib import contextmanager
        from types import SimpleNamespace
        from unittest.mock import patch
        from hosted_run import witness
        from hosted_observation import RUNTIME_ROLE

        class ScopedCursor:
            def execute(self, sql, arguments):
                self.sql, self.arguments = sql, arguments

            def fetchone(self):
                if "pg_stat_activity" in self.sql:
                    self_test.assertEqual(self.arguments, (RUNTIME_ROLE, "owned-app-name"))
                    self_test.assertIn("datname=current_database()", self.sql)
                    self_test.assertIn("application_name=", self.sql)
                    # PostgreSQL hides backend_type for an unrelated unprivileged role.
                    return ("backend_type" not in self.sql,)
                self_test.assertEqual(self.arguments, ("actual-loan-id",))
                return ("1", "PENDING_ISSUE")

        self_test = self
        @contextmanager
        def scope(connection, owned):
            yield ScopedCursor()

        with patch("hosted_run.fixture_transaction", scope):
            result = witness(None, SimpleNamespace(instance="owned-instance"), SimpleNamespace(commit="commit"),
                             "owned-app-name", "sha", "1", "actual-loan-id", "PENDING_ISSUE")
            self.assertEqual(result["definition_version"], 1)

    def test_failure_diagnostic_exposes_only_local_site_and_http_status(self):
        from hosted_run import failure_diagnostic
        namespace = {}
        exec(compile("def reject():\n    assert False, (403, 'private payload')", "acceptance.py", "exec"),
             namespace)
        try:
            namespace["reject"]()
        except AssertionError as failure:
            diagnosis = failure_diagnostic(failure)
        self.assertEqual(diagnosis, "AU08_RUN_FAILED AssertionError SITE=acceptance.py:2 HTTP=403")
        self.assertNotIn("private", diagnosis)

    def test_app_diagnostics_only_extract_known_code_coordinates_and_db_object(self):
        from hosted_run import app_diagnostics
        log = ("debug password synthetic-private\n"
               "Ledger unavailable trace=12345678-1234-4234-8234-123456789abc "
               "type=org.example.Database causeType=org.example.Cause sqlState=42501 "
               "source=[com.mightyoung.softwarefactory.tooling.ToolLoanService.issue(ToolLoanService.java:121)]\n"
               "Caused by: org.postgresql.util.PSQLException: ERROR: permission denied for table act_ge_bytearray\n"
               "Request fake raw row\n")
        self.assertEqual(app_diagnostics(log),
                         ["AU08_APP_DEPENDENCY type=org.example.Database cause=org.example.Cause "
                          "sqlstate=42501 source=ToolLoanService.java:121",
                          "AU08_APP_DB_PERMISSION table=act_ge_bytearray"])

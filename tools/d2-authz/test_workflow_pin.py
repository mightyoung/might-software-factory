from hashlib import sha256
from pathlib import Path
import unittest

from fixture_scope import synthetic_tenant_id
from workflow_pin import check_workflow_pin

INSTANCE = "a6f8fa03-8d8c-4b78-9422-51713ac62e41"
ROOT = Path(__file__).resolve().parents[2]
BPMN = (ROOT / "backend/src/main/resources/processes/tool-loan-v1.bpmn20.xml").read_bytes()


def installed(**changes):
    return dict(dict(tenant=synthetic_tenant_id(INSTANCE), definition_id="toolLoanV1:1:fixture",
                     key="toolLoanV1", version=1, active=True, resource_name="processes/tool-loan-v1.bpmn20.xml",
                     resource=BPMN), **changes)


class WorkflowPinTest(unittest.TestCase):
    def test_pin_uses_real_source_bytes_and_observed_definition_id(self):
        tenant, pin = check_workflow_pin(ROOT, INSTANCE, [installed()])
        self.assertEqual(tenant, synthetic_tenant_id(INSTANCE))
        self.assertEqual(pin, {"definition-id": "toolLoanV1:1:fixture",
                               "resource-sha256": sha256(BPMN).hexdigest()})

    def test_missing_duplicate_or_wrong_deployment_refuses(self):
        for rows in [[], [installed(), installed()], [installed(active=False)], [installed(active=1)],
                     [installed(tenant="other")], [installed(definition_id=" ")], [installed(version=2)],
                     [installed(key="other")], [installed(resource_name="different.xml")],
                     [installed(resource=BPMN + b"\n")], [installed(resource=BPMN.decode())]]:
            with self.subTest(rows=len(rows)), self.assertRaises(ValueError):
                check_workflow_pin(ROOT, INSTANCE, rows)

    def test_reads_persisted_bytes_inside_verified_fixture_scope(self):
        from unittest.mock import MagicMock, patch
        from hosted_observation import OwnedContainer
        from workflow_pin import read_workflow_pin

        owned = OwnedContainer("a" * 64, INSTANCE, 18432, "b" * 64)
        connection = MagicMock()
        cursor = MagicMock()
        record = installed()
        cursor.fetchall.return_value = [(
            record["tenant"], record["definition_id"], record["key"], 1, 1,
            record["resource_name"], memoryview(BPMN),
        )]
        with patch("workflow_pin.fixture_transaction") as scope:
            scope.return_value.__enter__.return_value = cursor
            self.assertEqual(read_workflow_pin(ROOT, connection, owned),
                             check_workflow_pin(ROOT, INSTANCE, [record]))
            scope.assert_called_once_with(connection, owned)
            sql, bindings = cursor.execute.call_args.args
            self.assertEqual(bindings, (record["tenant"], "toolLoanV1"))
            self.assertIn("left join public.act_ge_bytearray", sql)
            self.assertNotIn("suspension_state_ =", sql)
            cursor.fetchall.return_value *= 2
            with self.assertRaises(ValueError):
                read_workflow_pin(ROOT, connection, owned)

    def test_does_not_read_flowable_if_scope_is_refused(self):
        from unittest.mock import MagicMock, patch
        from hosted_observation import OwnedContainer
        from workflow_pin import read_workflow_pin

        connection = MagicMock()
        owned = OwnedContainer("a" * 64, INSTANCE, 18432, "b" * 64)
        with patch("workflow_pin.fixture_transaction", side_effect=ValueError("invalid scope")):
            with self.assertRaisesRegex(ValueError, "invalid scope"):
                read_workflow_pin(ROOT, connection, owned)
        connection.cursor.assert_not_called()

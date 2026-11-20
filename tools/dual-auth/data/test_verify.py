import unittest
from unittest.mock import patch

from verify import compare, matches, valid_records


class VerifyTest(unittest.TestCase):
    def test_missing_and_explicit_null_are_distinct_inputs_and_deny_string_equality(self):
        plan = {"kind": "KIND_CONDITIONAL", "condition": {"expression": {
            "operator": "eq", "operands": [
                {"variable": "request.resource.attr.department"}, {"value": "quality"},
            ],
        }}}
        fields = {"id": "sample", "tenant": "factory", "owner": "someone", "quantity": 1, "price": 2}
        self.assertFalse(matches(plan, fields))
        self.assertFalse(matches(plan, {**fields, "department": None}))
        self.assertTrue(matches(plan, {**fields, "department": "quality"}))

    def test_missing_or_malformed_required_attributes_refuse_before_request(self):
        fixture = {"id": "sample", "tenant": "factory", "owner": "alice", "quantity": 1, "price": 2}
        valid_records([fixture, {**fixture, "id": "other", "owner": None}])
        for row in [{**fixture, "tenant": None}, {**fixture, "department": ["quality"]},
                    {**fixture, "quantity": True}, {**fixture, "owner": 1},
                    {k: v for k, v in fixture.items() if k != "tenant"}]:
            with self.subTest(row=row), patch("verify.request") as request, self.assertRaises(ValueError):
                compare("http://127.0.0.1:1", [row], [])
            request.assert_not_called()

    def test_unknown_field_or_expression_cannot_become_an_allow(self):
        for condition in [
            {"variable": "request.resource.attr.invalid"},
            {"expression": {"operator": "unsupported", "operands": [{"value": "x"}]}},
        ]:
            with self.assertRaises((AssertionError, ValueError)):
                matches({"kind": "KIND_CONDITIONAL", "condition": condition}, {"id": "sample"})

    @patch("verify.request")
    def test_declared_denial_requires_real_decision_for_each_nullable_record(self, request):
        rows = [
            {"id": "missing", "tenant": "factory", "quantity": 1, "price": 2},
            {"id": "null", "tenant": "factory", "quantity": 1, "price": 2, "department": None},
        ]
        request.return_value = {"results": [
            {"resource": {"id": "missing"}, "actions": {"view": "EFFECT_DENY"}},
        ]}
        with self.assertRaises(ValueError):
            compare("http://127.0.0.1:1", rows, [("nullable", {}, "view", [])])
        self.assertEqual(request.call_count, 1)
        attrs = [row["resource"]["attr"] for row in request.call_args.args[2]["resources"]]
        self.assertNotIn("department", attrs[0])
        self.assertIsNone(attrs[1]["department"])

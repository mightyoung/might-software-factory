import unittest
from hosted_grants import FIXTURE, RUNTIME, effective_column, effective_table


class HostedGrantTest(unittest.TestCase):
    def test_runtime_cannot_write_entitlements_or_ordinary_ledger_data(self):
        self.assertTrue(effective_column(RUNTIME, "tool", "item_state", "UPDATE"))
        self.assertFalse(effective_table(RUNTIME, "tool", "UPDATE"))
        self.assertFalse(effective_column(RUNTIME, "tool", "organization_id", "UPDATE"))
        self.assertFalse(effective_table(RUNTIME, "role_permission", "DELETE"))
        self.assertFalse(effective_column(RUNTIME, "tenant_member", "status", "UPDATE"))
        self.assertFalse(effective_table(RUNTIME, "ordinary_record", "SELECT"))

    def test_fixture_has_only_observation_narrow_mutations_and_v23_parent_locks(self):
        self.assertTrue(effective_column(FIXTURE, "tenant_member", "status", "UPDATE"))
        self.assertFalse(effective_column(FIXTURE, "tenant_member", "auth_epoch", "UPDATE"))
        self.assertTrue(effective_table(FIXTURE, "role_permission", "DELETE"))
        self.assertFalse(effective_table(FIXTURE, "role_permission", "INSERT"))
        self.assertTrue(effective_column(FIXTURE, "role_permission", "scope_rule_id", "INSERT"))
        self.assertFalse(effective_column(FIXTURE, "audit_event", "payload", "SELECT"))
        self.assertFalse(effective_column(FIXTURE, "credential", "secret_hash", "SELECT"))
        self.assertFalse(effective_table(FIXTURE, "act_ru_task", "DELETE"))
        self.assertTrue(effective_column(FIXTURE, "permission", "label", "UPDATE"))
        self.assertFalse(effective_column(FIXTURE, "permission", "code", "UPDATE"))

    def test_all_loan_responses_can_render_loss_projection_without_loss_writes(self):
        for column in ("tenant_id", "loan_id", "tool_id", "command_id", "reason"):
            self.assertTrue(effective_column(RUNTIME, "tool_loan_loss", column, "SELECT"))
        self.assertFalse(effective_table(RUNTIME, "tool_loan_loss", "INSERT"))
        self.assertFalse(effective_column(RUNTIME, "tool_loan_loss", "actor_member_id", "SELECT"))

    def test_settlement_guard_and_damaged_helper_have_only_the_required_column_access(self):
        self.assertTrue(effective_column(RUNTIME, "tool_loan_loss", "occurred_at", "SELECT"))
        self.assertFalse(effective_table(RUNTIME, "tool_loan_loss", "SELECT"))
        self.assertFalse(effective_table(RUNTIME, "tool_loan_loss", "INSERT"))
        for column in ("item_state", "version", "updated_at", "updated_by_actor_id"):
            self.assertTrue(effective_column(RUNTIME, "tool", column, "UPDATE"))
        for column in ("code", "organization_id", "custody_scope_id", "name"):
            self.assertFalse(effective_column(RUNTIME, "tool", column, "UPDATE"))

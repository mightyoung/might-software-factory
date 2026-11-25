import unittest
from unittest.mock import MagicMock

from fixture_scope import fixture_transaction, synthetic_tenant_id
from hosted_observation import OwnedContainer

MARKER = "a6f8fa03-8d8c-4b78-9422-51713ac62e41"
OWNED = OwnedContainer("a" * 64, MARKER, 18432, "b" * 64)


class FixtureScopeTest(unittest.TestCase):
    def connection(self, *, inherited=None, tenant=None):
        tx = MagicMock()
        tx.autocommit = False
        tx.info.transaction_status = 0
        cursor = tx.cursor.return_value.__enter__.return_value
        scoped = synthetic_tenant_id(MARKER)
        cursor.fetchone.side_effect = [
            ("msf_au08_test", "msf_ci_au08_fixture", "msf_ci_au08_fixture", MARKER, inherited),
            (scoped,),
            tenant if tenant is not None else (scoped, "d1seed", "D1 isolated tooling seed v1", "ACTIVE"),
        ]
        return tx, cursor

    def test_stable_tenant_is_unique_per_owned_instance_not_input(self):
        self.assertEqual(synthetic_tenant_id(MARKER), synthetic_tenant_id(MARKER))
        self.assertNotEqual(synthetic_tenant_id(MARKER), synthetic_tenant_id("b40d3734-5e52-48ea-8391-dce8ab1dbac4"))
        with self.assertRaises(ValueError):
            synthetic_tenant_id("invalid")

    def test_context_is_transaction_local_and_synthetic_row_is_verified_before_fixture_use(self):
        tx, cursor = self.connection()
        with fixture_transaction(tx, OWNED) as guarded:
            self.assertIs(guarded, cursor)
            self.assertEqual(len(cursor.execute.call_args_list), 3)
        bind = cursor.execute.call_args_list[1]
        self.assertIn("set_config('app.tenant_id', %s, true)", bind.args[0])
        self.assertEqual(bind.args[1], (synthetic_tenant_id(MARKER),))
        self.assertIsNone(tx.__exit__.call_args.args[0])
        tx.commit.assert_not_called()

    def test_wrong_marker_role_or_inherited_scope_refuses_before_set_config(self):
        for row in [
            ("msf_au08_test", "postgres", "postgres", MARKER, None),
            ("msf_au08_test", "msf_ci_au08_fixture", "msf_ci_au08_fixture", "foreign", None),
            ("msf_au08_test", "msf_ci_au08_fixture", "msf_ci_au08_fixture", MARKER, "pre-existing"),
        ]:
            tx, cursor = self.connection()
            cursor.fetchone.side_effect = [row]
            with self.assertRaises(ValueError), fixture_transaction(tx, OWNED):
                self.fail("A foreign or pre-scoped fixture must not be entered")
            cursor.execute.assert_called_once()
            self.assertIs(tx.__exit__.call_args.args[0], ValueError)

    def test_missing_or_different_synthetic_tenant_refuses_and_rolls_back(self):
        for row in [None, (synthetic_tenant_id(MARKER), "d1seed", "not fixture-owned", "ACTIVE")]:
            tx, cursor = self.connection()
            cursor.fetchone.side_effect = [
                ("msf_au08_test", "msf_ci_au08_fixture", "msf_ci_au08_fixture", MARKER, None),
                (synthetic_tenant_id(MARKER),),
                row,
            ]
            with self.assertRaises(ValueError), fixture_transaction(tx, OWNED):
                self.fail("Unverified tenant must never be exposed")
            self.assertIs(tx.__exit__.call_args.args[0], ValueError)

    def test_dirty_or_autocommit_connection_cannot_join_fixture_scope(self):
        for autocommit, status in [(True, 0), (False, 2)]:
            tx, cursor = self.connection()
            tx.autocommit, tx.info.transaction_status = autocommit, status
            with self.assertRaises(ValueError), fixture_transaction(tx, OWNED):
                self.fail("May not join a previous transaction")
            cursor.execute.assert_not_called()

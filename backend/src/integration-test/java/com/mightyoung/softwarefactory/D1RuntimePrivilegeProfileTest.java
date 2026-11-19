package com.mightyoung.softwarefactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class D1RuntimePrivilegeProfileTest {
    private static final String DATABASE = "msf_d0_d1_core_test";

    @Test void requiresEveryPrecedingAcknowledgementAndD1Target() {
        var accepted = environment(DATABASE);
        assertThat(IdentityLoginPostgresIT.validateRuntimePrivilegeProfile(accepted,DATABASE))
                .isEqualTo(IdentityLoginPostgresIT.RuntimePrivilegeProfile.D1_APPLICATION);
        for (String key : List.of("LAN_PG_BUSINESS_ACCEPTANCE_ACK","LAN_PG_PASSWORD_CHANGE_ACCEPTANCE_ACK",
                "LAN_PG_AUTHENTICATION_EVENT_ACCEPTANCE_ACK","LAN_PG_D0_APPLICATION_ACCEPTANCE_ACK",
                "LAN_PG_D1_APPLICATION_ACCEPTANCE_ACK")) {
            var missing = new HashMap<>(accepted);
            missing.remove(key);
            assertThatThrownBy(() -> IdentityLoginPostgresIT.validateRuntimePrivilegeProfile(missing,DATABASE))
                    .as("Explicit prerequisite %s",key).isInstanceOf(IllegalStateException.class);
            missing.put(key,accepted.get(key).replace(DATABASE,"another_test"));
            assertThatThrownBy(() -> IdentityLoginPostgresIT.validateRuntimePrivilegeProfile(missing,DATABASE))
                    .as("Exact target %s",key).isInstanceOf(IllegalStateException.class);
        }
        for (String target : List.of("msf_d0_core_test","production","msf_d0_d1_test")) {
            assertThatThrownBy(() -> IdentityLoginPostgresIT.validateRuntimePrivilegeProfile(environment(target),target))
                    .hasMessageContaining("D1 acknowledgement");
        }
    }

    @Test void d1PreservesD0IdentityRightsAndOnlyAddsReviewedLedgerTables() {
        var d1 = IdentityLoginPostgresIT.RuntimePrivilegeProfile.D1_APPLICATION;
        var d0 = IdentityLoginPostgresIT.RuntimePrivilegeProfile.D0_APPLICATION;
        for (String table : List.of("account","principal","credential","tenant_member","permission","role_permission","scope_rule")) {
            for (String column : List.of("id","status","code","auth_epoch","login_key","credential_version",
                    "secret_hash","changed_at","label","version_no","tenant_id","role_id","permission_id","scope_rule_id")) {
                assertThat(d1.permitsInsert(table,column)).isEqualTo(d0.permitsInsert(table,column));
                assertThat(d1.permitsUpdate(table,column)).isEqualTo(d0.permitsUpdate(table,column));
            }
        }
        var additions = D1RuntimePrivilegeAssertions.additionalTables();
        assertThat(additions.keySet()).containsExactlyInAnyOrder("tool","tool_custody_scope","tool_custodian",
                "ledger_record_revision","ledger_command_receipt");
        for (String table : List.of("tool_custody_scope","tool_custodian")) {
            assertThat(additions.get(table).table()).containsExactly("SELECT");
            assertThat(additions.get(table).columns().values()).allSatisfy(columns -> assertThat(columns).isEmpty());
        }
        for (String table : List.of("ledger_record_revision","ledger_command_receipt")) {
            assertThat(additions.get(table).table()).containsExactlyInAnyOrder("SELECT","INSERT");
            assertThat(additions.get(table).columns().values()).allSatisfy(columns -> assertThat(columns).isEmpty());
        }
        assertThat(additions.get("tool").table()).containsExactly("SELECT");
        assertThat(additions.get("tool").columns().get("INSERT")).containsExactlyInAnyOrder(
                "tenant_id","id","version","created_at","updated_at","created_by_actor_id","updated_by_actor_id",
                "code","name","model","description","custody_scope_id","organization_id","item_state");
        assertThat(additions.get("tool").columns().get("UPDATE")).containsExactlyInAnyOrder(
                "name","model","description","version","updated_at","updated_by_actor_id");
        var legacy = D0RuntimePrivilegeAssertions.loadApplication(
                D0RuntimePrivilegeAssertions.resource("/db/operator/d0-runtime-grants.sql"),
                D0RuntimePrivilegeAssertions.resource("/db/operator/d0-legacy-runtime-grants.sql"));
        assertThat(legacy.keySet()).doesNotContainAnyElementsOf(additions.keySet());
        assertThat(D1RuntimePrivilegeAssertions.additionalReadColumns()).containsOnlyKeys("session_authority_activation");
        assertThat(D1RuntimePrivilegeAssertions.additionalReadColumns().get("session_authority_activation"))
                .containsExactly("activated_by_database_role");
        assertThat(legacy.get("session_authority_activation").columns().get("SELECT"))
                .doesNotContain("activated_by_database_role");
    }

    private static Map<String,String> environment(String database) {
        return Map.of("LAN_PG_RUNTIME_PRIVILEGE_PROFILE","d1-application",
                "LAN_PG_BUSINESS_ACCEPTANCE_ACK","I_CONFIRM_BUSINESS_WORKFLOW_FIXTURE_WRITES:"+database,
                "LAN_PG_PASSWORD_CHANGE_ACCEPTANCE_ACK","I_CONFIRM_PASSWORD_CHANGE_FIXTURE_WRITES:"+database,
                "LAN_PG_AUTHENTICATION_EVENT_ACCEPTANCE_ACK","I_CONFIRM_AUTHENTICATION_EVENT_FIXTURE_WRITES:"+database,
                "LAN_PG_D0_APPLICATION_ACCEPTANCE_ACK","I_CONFIRM_D0_APPLICATION_PRIVILEGES:"+database,
                "LAN_PG_D1_APPLICATION_ACCEPTANCE_ACK","I_CONFIRM_D1_APPLICATION_PRIVILEGES:"+database);
    }
}

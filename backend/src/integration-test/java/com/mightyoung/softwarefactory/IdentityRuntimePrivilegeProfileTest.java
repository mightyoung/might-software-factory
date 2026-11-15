package com.mightyoung.softwarefactory;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class IdentityRuntimePrivilegeProfileTest {
    private static final String DATABASE="synthetic_acceptance_test";
    @Test void defaultIdentityAndExplicitBusinessRemainReadOnlyEvenWithPasswordAck() {
        assertThat(IdentityLoginPostgresIT.validateRuntimePrivilegeProfile(Map.of(),DATABASE).passwordChange).isFalse();
        var environment=business("business-workflow");
        environment.put("LAN_PG_PASSWORD_CHANGE_ACCEPTANCE_ACK","I_CONFIRM_PASSWORD_CHANGE_FIXTURE_WRITES:"+DATABASE);
        var profile=IdentityLoginPostgresIT.validateRuntimePrivilegeProfile(environment,DATABASE);
        assertThat(profile.business).isTrue();assertThat(profile.passwordChange).isFalse();
        assertThat(profile.permitsUpdate("account","credential_version")).isFalse();
    }
    @Test void passwordProfileRequiresBothDatabaseSpecificAcknowledgements() {
        var environment=business("business-password-change");
        assertThatThrownBy(()->IdentityLoginPostgresIT.validateRuntimePrivilegeProfile(environment,DATABASE)).hasMessageContaining("password-change acknowledgement");
        environment.put("LAN_PG_PASSWORD_CHANGE_ACCEPTANCE_ACK","I_CONFIRM_PASSWORD_CHANGE_FIXTURE_WRITES:another_test");
        assertThatThrownBy(()->IdentityLoginPostgresIT.validateRuntimePrivilegeProfile(environment,DATABASE)).hasMessageContaining("password-change acknowledgement");
        environment.put("LAN_PG_PASSWORD_CHANGE_ACCEPTANCE_ACK","I_CONFIRM_PASSWORD_CHANGE_FIXTURE_WRITES:"+DATABASE);
        environment.put("LAN_PG_BUSINESS_ACCEPTANCE_ACK","I_CONFIRM_BUSINESS_WORKFLOW_FIXTURE_WRITES:another_test");
        assertThatThrownBy(()->IdentityLoginPostgresIT.validateRuntimePrivilegeProfile(environment,DATABASE)).hasMessageContaining("business acknowledgement");
        environment.put("LAN_PG_BUSINESS_ACCEPTANCE_ACK","I_CONFIRM_BUSINESS_WORKFLOW_FIXTURE_WRITES:"+DATABASE);
        assertThat(IdentityLoginPostgresIT.validateRuntimePrivilegeProfile(environment,DATABASE).passwordChange).isTrue();
    }
    @Test void passwordProfilePermitsOnlyTheExactThreeColumnsAndUnknownProfilesReject() {
        var environment=business("business-password-change");
        environment.put("LAN_PG_PASSWORD_CHANGE_ACCEPTANCE_ACK","I_CONFIRM_PASSWORD_CHANGE_FIXTURE_WRITES:"+DATABASE);
        var profile=IdentityLoginPostgresIT.validateRuntimePrivilegeProfile(environment,DATABASE);
        assertThat(profile.permitsUpdate("account","credential_version")).isTrue();
        assertThat(profile.permitsUpdate("credential","secret_hash")).isTrue();
        assertThat(profile.permitsUpdate("credential","changed_at")).isTrue();
        for(String column:new String[]{"status","login_key","id","kind","account_id"}) {
            assertThat(profile.permitsUpdate("account",column)).isFalse();
            assertThat(profile.permitsUpdate("credential",column)).isFalse();
        }
        assertThat(profile.permitsUpdate("principal","status")).isFalse();
        environment.put("LAN_PG_RUNTIME_PRIVILEGE_PROFILE","unknown");
        assertThatThrownBy(()->IdentityLoginPostgresIT.validateRuntimePrivilegeProfile(environment,DATABASE)).hasMessageContaining("business acknowledgement");
    }
    @Test void authenticationAuditProfileRequiresItsOwnExactDatabaseAcknowledgement() {
        var environment=business("business-authentication-audit");
        environment.put("LAN_PG_PASSWORD_CHANGE_ACCEPTANCE_ACK","I_CONFIRM_PASSWORD_CHANGE_FIXTURE_WRITES:"+DATABASE);
        assertThatThrownBy(()->IdentityLoginPostgresIT.validateRuntimePrivilegeProfile(environment,DATABASE))
                .hasMessageContaining("authentication-event acknowledgement");
        environment.put("LAN_PG_AUTHENTICATION_EVENT_ACCEPTANCE_ACK","I_CONFIRM_AUTHENTICATION_EVENT_FIXTURE_WRITES:another_test");
        assertThatThrownBy(()->IdentityLoginPostgresIT.validateRuntimePrivilegeProfile(environment,DATABASE))
                .hasMessageContaining("authentication-event acknowledgement");
        environment.put("LAN_PG_AUTHENTICATION_EVENT_ACCEPTANCE_ACK","I_CONFIRM_AUTHENTICATION_EVENT_FIXTURE_WRITES:"+DATABASE);
        var profile=IdentityLoginPostgresIT.validateRuntimePrivilegeProfile(environment,DATABASE);
        assertThat(profile.authenticationAudit).isTrue();assertThat(profile.passwordChange).isTrue();assertThat(profile.business).isTrue();
        assertThat(profile.permitsUpdate("principal","status")).isFalse();
    }
    @Test void d0RequiresAllPriorAcksAndItsOwnIsolatedTargetAck() {
        var env=business("d0-application");
        env.put("LAN_PG_PASSWORD_CHANGE_ACCEPTANCE_ACK","I_CONFIRM_PASSWORD_CHANGE_FIXTURE_WRITES:"+DATABASE);
        env.put("LAN_PG_AUTHENTICATION_EVENT_ACCEPTANCE_ACK","I_CONFIRM_AUTHENTICATION_EVENT_FIXTURE_WRITES:"+DATABASE);
        assertThatThrownBy(()->IdentityLoginPostgresIT.validateRuntimePrivilegeProfile(new HashMap<>(env),DATABASE)).hasMessageContaining("D0 acknowledgement");
        env.put("LAN_PG_D0_APPLICATION_ACCEPTANCE_ACK","I_CONFIRM_D0_APPLICATION_PRIVILEGES:"+DATABASE);
        assertThatThrownBy(()->IdentityLoginPostgresIT.validateRuntimePrivilegeProfile(new HashMap<>(env),DATABASE)).hasMessageContaining("D0 acknowledgement");
        String isolated="msf_d0_core_test";
        var isolatedEnv=business("d0-application");
        isolatedEnv.put("LAN_PG_BUSINESS_ACCEPTANCE_ACK","I_CONFIRM_BUSINESS_WORKFLOW_FIXTURE_WRITES:"+isolated);
        isolatedEnv.put("LAN_PG_PASSWORD_CHANGE_ACCEPTANCE_ACK","I_CONFIRM_PASSWORD_CHANGE_FIXTURE_WRITES:"+isolated);
        isolatedEnv.put("LAN_PG_AUTHENTICATION_EVENT_ACCEPTANCE_ACK","I_CONFIRM_AUTHENTICATION_EVENT_FIXTURE_WRITES:"+isolated);
        var finalEnv=isolatedEnv;
        assertThatThrownBy(()->IdentityLoginPostgresIT.validateRuntimePrivilegeProfile(finalEnv,isolated)).hasMessageContaining("D0 acknowledgement");
        isolatedEnv.put("LAN_PG_D0_APPLICATION_ACCEPTANCE_ACK","I_CONFIRM_D0_APPLICATION_PRIVILEGES:"+isolated);
        var profile=IdentityLoginPostgresIT.validateRuntimePrivilegeProfile(isolatedEnv,isolated);
        assertThat(profile).isEqualTo(IdentityLoginPostgresIT.RuntimePrivilegeProfile.D0_APPLICATION);
        assertThat(profile.permitsInsert("account","login_key")).isTrue();
        assertThat(profile.permitsUpdate("tenant_member","auth_epoch")).isTrue();
        assertThat(profile.permitsUpdate("permission","label")).isTrue();
        assertThat(profile.permitsUpdate("scope_rule","version_no")).isTrue();
        for(String column:List.of("id","code","resource_type","action")) assertThat(profile.permitsUpdate("permission",column)).isFalse();
        for(String column:List.of("id","tenant_id","kind","definition")) assertThat(profile.permitsUpdate("scope_rule",column)).isFalse();
        for(var old:List.of(IdentityLoginPostgresIT.RuntimePrivilegeProfile.IDENTITY_ONLY,
                IdentityLoginPostgresIT.RuntimePrivilegeProfile.BUSINESS_WORKFLOW,
                IdentityLoginPostgresIT.RuntimePrivilegeProfile.BUSINESS_AUTHENTICATION_AUDIT)) {
            assertThat(old.permitsUpdate("permission","label")).isFalse();
            assertThat(old.permitsUpdate("scope_rule","version_no")).isFalse();
        }
        assertThat(profile.permitsInsert("role_grant","member_id")).isFalse();
        assertThat(profile.permitsUpdate("principal","status")).isFalse();
        assertThat(profile.permitsUpdate("account","status")).isFalse();
        assertThat(IdentityLoginPostgresIT.RuntimePrivilegeProfile.BUSINESS_AUTHENTICATION_AUDIT.permitsInsert("account","login_key")).isFalse();
    }
    @Test void reviewedD0AclCatalogIsCompleteAndKeepsHistoryAppendOnly() {
        var delta=D0RuntimePrivilegeAssertions.loadDelta(D0RuntimePrivilegeAssertions.resource("/db/operator/d0-runtime-grants.sql"));
        var legacy=D0RuntimePrivilegeAssertions.loadLegacy(D0RuntimePrivilegeAssertions.resource("/db/operator/d0-legacy-runtime-grants.sql"));
        var application=D0RuntimePrivilegeAssertions.loadApplication(
                D0RuntimePrivilegeAssertions.resource("/db/operator/d0-runtime-grants.sql"),
                D0RuntimePrivilegeAssertions.resource("/db/operator/d0-legacy-runtime-grants.sql"));
        assertThat(application.get("permission").columns().get("UPDATE")).containsExactly("label");
        assertThat(application.get("scope_rule").columns().get("UPDATE")).containsExactly("version_no");
        assertThat(application.get("permission").table()).doesNotContain("UPDATE");
        assertThat(application.get("scope_rule").table()).doesNotContain("UPDATE");
        assertThat(delta).hasSize(42);
        assertThat(delta.get("risk_scenario_receipt").columns().get("INSERT")).contains("payload_sha256");
        assertThat(D0RuntimePrivilegeAssertions.FLOWABLE).hasSize(32);
        assertThat(legacy).containsKeys("security_event","authentication_event","file_ingestion_publication","finding_evidence_upload_intent","spring_session");
        assertThat(legacy.get("file_ingestion_publication").table()).containsExactlyInAnyOrder("SELECT","INSERT");
        assertThat(legacy.get("file_ingestion_publication").columns().get("UPDATE")).isEmpty();
        for(String table:List.of("file_object","file_link")) {
            assertThat(legacy.get(table).table()).doesNotContain("UPDATE","DELETE");
            assertThat(legacy.get(table).columns().get("UPDATE")).containsExactly("tenant_id");
        }
        assertThat(legacy.get("security_event").table()).containsExactly("INSERT");
        assertThat(legacy.get("remediation_submission").columns().get("UPDATE")).containsExactly("evidence_snapshots_sealed_at");
        assertThat(legacy.get("finding_evidence_upload_intent").columns().get("UPDATE")).contains("technical_version","lease_epoch").doesNotContain("owner_account_id","tenant_id");
    }
    private static HashMap<String,String> business(String name) {
        return new HashMap<>(Map.of("LAN_PG_RUNTIME_PRIVILEGE_PROFILE",name,
                "LAN_PG_BUSINESS_ACCEPTANCE_ACK","I_CONFIRM_BUSINESS_WORKFLOW_FIXTURE_WRITES:"+DATABASE));
    }
}

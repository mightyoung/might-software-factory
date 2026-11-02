package com.mightyoung.softwarefactory;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
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
    private static HashMap<String,String> business(String name) {
        return new HashMap<>(Map.of("LAN_PG_RUNTIME_PRIVILEGE_PROFILE",name,
                "LAN_PG_BUSINESS_ACCEPTANCE_ACK","I_CONFIRM_BUSINESS_WORKFLOW_FIXTURE_WRITES:"+DATABASE));
    }
}

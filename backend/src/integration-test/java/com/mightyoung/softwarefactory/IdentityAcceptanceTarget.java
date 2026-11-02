package com.mightyoung.softwarefactory;

import java.util.Map;

/** Additional opt-in boundary for synthetic identity fixture writes, before any connection. */
final class IdentityAcceptanceTarget {

    private IdentityAcceptanceTarget() { }

    static LanPostgresCompatibilityIT.DatabaseTarget validateInputs(Map<String, String> environment) {
        var target = LanPostgresCompatibilityIT.DatabaseTarget.forAppRole(environment);
        String expected = "I_CONFIRM_IDENTITY_FIXTURE_WRITES:" + target.databaseName();
        if (!expected.equals(environment.get("LAN_PG_IDENTITY_ACCEPTANCE_ACK"))) {
            throw new IllegalStateException(
                    "Identity acceptance requires database-specific fixture-write confirmation");
        }
        return target;
    }

    static LanPostgresCompatibilityIT.DatabaseTarget preflightFromEnvironment(Map<String, String> environment) {
        validateInputs(environment);
        return LanPostgresCompatibilityIT.DatabaseTarget.preflightFromEnvironment(environment);
    }
}

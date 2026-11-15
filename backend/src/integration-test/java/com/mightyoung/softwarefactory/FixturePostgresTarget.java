package com.mightyoung.softwarefactory;

import java.util.Map;

/** Narrow cross-package entry to the existing disposable-target and non-elevated-role preflight. */
public final class FixturePostgresTarget {
    private FixturePostgresTarget() { }

    public record Target(String jdbcUrl, String databaseName) { }

    public static Target preflightRepository(Map<String, String> environment) {
        validateRepositoryInputs(environment);
        return preflight(environment);
    }

    public static Target preflightFinalization(Map<String, String> environment) {
        validateFinalizationInputs(environment);
        return preflight(environment);
    }

    static Target validateRepositoryInputs(Map<String, String> environment) {
        var target = LanPostgresCompatibilityIT.DatabaseTarget.forAppRole(environment);
        LanPostgresCompatibilityIT.DatabaseTarget.requireExclusiveAsyncDatabase(environment);
        return new Target(target.jdbcUrl(), target.databaseName());
    }

    static Target validateFinalizationInputs(Map<String, String> environment) {
        Target target = validateRepositoryInputs(environment);
        if (!target.jdbcUrl().equals(environment.get("LAN_PG_FINALIZE_TEST_JDBC_URL"))
                || !("I_CONFIRM_FINALIZE_FIXTURE_WRITES:" + target.databaseName())
                        .equals(environment.get("LAN_PG_FINALIZE_TEST_ACK"))) {
            throw new IllegalStateException(
                    "Finalization fixtures require the exact disposable database and acknowledgement");
        }
        return target;
    }

    private static Target preflight(Map<String, String> environment) {
        var target = LanPostgresCompatibilityIT.DatabaseTarget.preflightFromEnvironment(environment);
        return new Target(target.jdbcUrl(), target.databaseName());
    }
}

package com.mightyoung.softwarefactory.support;

import java.util.Map;
import java.util.UUID;
import com.mightyoung.softwarefactory.testing.CiPg18EphemeralTarget;

/** Fixed lane wrapper; shared validation cannot select an externally supplied target. */
final class CiPg18RegistryTarget {
    private final CiPg18EphemeralTarget target;
    private CiPg18RegistryTarget(CiPg18EphemeralTarget target) { this.target = target; }
    static CiPg18RegistryTarget fromEnvironment() { return from(System.getenv()); }
    static CiPg18RegistryTarget from(Map<String, String> environment) {
        return new CiPg18RegistryTarget(CiPg18EphemeralTarget.from(environment, CiPg18EphemeralTarget.Lane.REGISTRY));
    }
    String jdbcUrl() { return target.jdbcUrl(); }
    String databaseName() { return target.databaseName(); }
    UUID instanceId() { return target.instanceId(); }
    String adminUser() { return target.adminUser(); }
    String runtimeUser() { return target.runtimeUser(); }
    String adminPassword() { return target.adminPassword(); }
    String runtimePassword() { return target.runtimePassword(); }
    String imageDigest() { return target.imageDigest(); }
    String containerId() { return target.containerId(); }
    @Override public String toString() { return "CiPg18RegistryTarget[private ephemeral target]"; }
}

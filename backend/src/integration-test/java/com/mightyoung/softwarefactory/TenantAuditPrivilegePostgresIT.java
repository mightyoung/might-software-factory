package com.mightyoung.softwarefactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Explicit catalog-only necessary-privilege check. A skipped test is not acceptance.
 * Successful execution proves neither business authorization/RLS isolation nor a complete privilege/escalation audit.
 * Existing nonmembership policy deliberately rejects even otherwise legitimate inherited-role configurations.
 * No configuration is repaired and no privileges are granted or revoked.
 */
@EnabledIfEnvironmentVariable(named="LAN_PG_AUDIT_PRIVILEGE_PREFLIGHT",matches="true")
class TenantAuditPrivilegePostgresIT {
    @Test void explicitRuntimeTargetHasOnlyTheRequiredAuditMetadataReadPrivileges(){
        TenantAuditPrivilegePreflight.inspect(System.getenv());
    }
}

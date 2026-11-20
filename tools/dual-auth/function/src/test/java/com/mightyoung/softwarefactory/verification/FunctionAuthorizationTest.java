package com.mightyoung.softwarefactory.verification;

import org.casbin.jcasbin.main.Enforcer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FunctionAuthorizationTest {
    private Enforcer enforcer;

    @BeforeEach
    void loadTenantRoles() {
        enforcer = new Enforcer("src/test/resources/function-model.conf");
        enforcer.addPolicy("supervisor", "factory-a", "ledger:purchase", "read");
        enforcer.addPolicy("registrar", "factory-a", "ledger:purchase", "update");
        enforcer.addPolicy("supervisor", "factory-b", "ledger:purchase", "approve");
        enforcer.addGroupingPolicy("member-1", "supervisor", "factory-a");
        enforcer.addGroupingPolicy("member-1", "registrar", "factory-a");
    }

    @Test
    void combinesRolesForSpecificActionsAndDeniesUnregisteredActions() {
        assertTrue(allowed("factory-a", "ledger:purchase", "read"));
        assertTrue(allowed("factory-a", "ledger:purchase", "update"));
        assertFalse(allowed("factory-a", "ledger:purchase", "approve"));
        assertFalse(allowed("factory-a", "ledger:purchase", "publish"));
        assertFalse(allowed("factory-a", "ledger:inspection", "read"));
    }

    @Test
    void sameRoleAndMemberNamesRemainIsolatedByTenant() {
        assertFalse(allowed("factory-b", "ledger:purchase", "approve"));
        enforcer.addGroupingPolicy("member-1", "supervisor", "factory-b");
        assertTrue(allowed("factory-b", "ledger:purchase", "approve"));
        assertFalse(allowed("factory-b", "ledger:purchase", "read"));
        assertTrue(allowed("factory-a", "ledger:purchase", "read"));
        assertFalse(allowed("factory-a", "ledger:purchase", "approve"));
    }

    @Test
    void registersNewLedgerActionsAtRuntimeAndRevokesOnlyTargetPolicy() {
        assertFalse(allowed("factory-a", "ledger:inspection", "approve"));
        assertTrue(enforcer.addPolicy("supervisor", "factory-a", "ledger:inspection", "approve"));
        assertTrue(allowed("factory-a", "ledger:inspection", "approve"));
        assertFalse(allowed("factory-b", "ledger:inspection", "approve"));
        assertTrue(enforcer.removePolicy("supervisor", "factory-a", "ledger:inspection", "approve"));
        assertFalse(allowed("factory-a", "ledger:inspection", "approve"));
        assertTrue(allowed("factory-a", "ledger:purchase", "read"));
    }

    @Test
    void revokingRoleMembershipTakesEffectOnNextDecision() {
        assertTrue(allowed("factory-a", "ledger:purchase", "update"));
        assertTrue(enforcer.removeGroupingPolicy("member-1", "registrar", "factory-a"));
        assertFalse(allowed("factory-a", "ledger:purchase", "update"));
        assertTrue(allowed("factory-a", "ledger:purchase", "read"));
    }

    @Test
    void resolvesRoleInheritanceWithinItsTenant() {
        enforcer.addGroupingPolicy("senior-supervisor", "supervisor", "factory-a");
        enforcer.addGroupingPolicy("member-2", "senior-supervisor", "factory-a");
        assertTrue(enforcer.enforce("member-2", "factory-a", "ledger:purchase", "read"));
        assertFalse(enforcer.enforce("member-2", "factory-b", "ledger:purchase", "approve"));
        enforcer.removeGroupingPolicy("senior-supervisor", "supervisor", "factory-a");
        assertFalse(enforcer.enforce("member-2", "factory-a", "ledger:purchase", "read"));
    }

    private boolean allowed(String tenant, String resource, String action) {
        return enforcer.enforce("member-1", tenant, resource, action);
    }
}

package com.mightyoung.softwarefactory;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Policy-contract regression tests; they validate metadata rules without opening a database connection. */
class FindingTenantPolicyDefinitionTest {

    private static final String TENANT_CONTEXT =
            "(id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid)";
    private static final String LOGIN_TENANT_CODE =
            "((code)::text = NULLIF(current_setting('app.login_tenant_code'::text, true), ''::text))";
    private static final String V3_TENANT_CONTEXT_SOURCE_FORM =
            "(id = nullif(current_setting('app.tenant_id', true), '')::uuid)";
    private static final String V4_LOGIN_LOOKUP_SOURCE_FORM =
            "(code = nullif(current_setting('app.login_tenant_code', true), ''))";

    @Test
    void acceptsTheExactPg18CatalogExpressionsFromV3AndV4() {
        assertThat(FindingNumberSequencePostgresIT.hasExpectedTenantPolicies(approvedPolicies())).isTrue();
    }

    @Test
    void acceptsTheEquivalentV3AndV4MigrationSourceExpressions() {
        List<FindingNumberSequencePostgresIT.TenantPolicy> policies = List.of(
                policy("tenant_isolation", "PERMISSIVE", "ALL", "{public}",
                        V3_TENANT_CONTEXT_SOURCE_FORM, V3_TENANT_CONTEXT_SOURCE_FORM),
                policy("tenant_login_lookup", "PERMISSIVE", "SELECT", "{public}",
                        V4_LOGIN_LOOKUP_SOURCE_FORM, null));

        assertThat(FindingNumberSequencePostgresIT.hasExpectedTenantPolicies(policies)).isTrue();
    }

    @Test
    void rejectsAnyAdditionalTenantWritePolicy() {
        List<FindingNumberSequencePostgresIT.TenantPolicy> policies = new ArrayList<>(approvedPolicies());
        policies.add(policy("unexpected_tenant_insert", "PERMISSIVE", "INSERT", "{public}", null, "true"));

        assertThat(FindingNumberSequencePostgresIT.hasExpectedTenantPolicies(policies)).isFalse();
    }

    @Test
    void rejectsAnExpandedTenantIsolationPredicate() {
        List<FindingNumberSequencePostgresIT.TenantPolicy> policies = new ArrayList<>(approvedPolicies());
        policies.set(0, policy("tenant_isolation", "PERMISSIVE", "ALL", "{public}",
                "(" + TENANT_CONTEXT.substring(1, TENANT_CONTEXT.length() - 1) + " OR true)", TENANT_CONTEXT));

        assertThat(FindingNumberSequencePostgresIT.hasExpectedTenantPolicies(policies)).isFalse();
    }

    @Test
    void rejectsAnExpandedLoginLookupPredicate() {
        List<FindingNumberSequencePostgresIT.TenantPolicy> policies = new ArrayList<>(approvedPolicies());
        policies.set(1, policy("tenant_login_lookup", "PERMISSIVE", "SELECT", "{public}",
                "(" + LOGIN_TENANT_CODE.substring(1, LOGIN_TENANT_CODE.length() - 1) + " OR true)", null));

        assertThat(FindingNumberSequencePostgresIT.hasExpectedTenantPolicies(policies)).isFalse();
    }

    @Test
    void rejectsUnexpectedPolicyCommandsOrRoles() {
        List<FindingNumberSequencePostgresIT.TenantPolicy> wrongCommand = new ArrayList<>(approvedPolicies());
        wrongCommand.set(1, policy("tenant_login_lookup", "PERMISSIVE", "ALL", "{public}", LOGIN_TENANT_CODE, null));
        assertThat(FindingNumberSequencePostgresIT.hasExpectedTenantPolicies(wrongCommand)).isFalse();

        List<FindingNumberSequencePostgresIT.TenantPolicy> widenedRoles = new ArrayList<>(approvedPolicies());
        widenedRoles.set(0, policy("tenant_isolation", "PERMISSIVE", "ALL", "{public,might_software_factory_app}",
                TENANT_CONTEXT, TENANT_CONTEXT));
        assertThat(FindingNumberSequencePostgresIT.hasExpectedTenantPolicies(widenedRoles)).isFalse();
    }

    private static List<FindingNumberSequencePostgresIT.TenantPolicy> approvedPolicies() {
        return List.of(
                policy("tenant_isolation", "PERMISSIVE", "ALL", "{public}", TENANT_CONTEXT, TENANT_CONTEXT),
                policy("tenant_login_lookup", "PERMISSIVE", "SELECT", "{public}", LOGIN_TENANT_CODE, null));
    }

    private static FindingNumberSequencePostgresIT.TenantPolicy policy(
            String name, String permissive, String command, String roles, String usingExpression, String checkExpression) {
        return new FindingNumberSequencePostgresIT.TenantPolicy(
                name, permissive, command, roles, usingExpression, checkExpression);
    }
}

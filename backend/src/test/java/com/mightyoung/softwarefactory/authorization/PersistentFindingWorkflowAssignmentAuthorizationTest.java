package com.mightyoung.softwarefactory.authorization;

import com.mightyoung.softwarefactory.directory.MemberDirectoryReadRepository;
import com.mightyoung.softwarefactory.directory.MemberDirectorySnapshot;
import com.mightyoung.softwarefactory.identity.AccountStatusReadRepository;
import com.mightyoung.softwarefactory.identity.PrincipalStatusReadRepository;
import com.mightyoung.softwarefactory.tenancy.TenantMembershipReadRepository;
import org.jooq.DSLContext;
import org.jooq.Record1;
import org.jooq.SQLDialect;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.using;

class PersistentFindingWorkflowAssignmentAuthorizationTest {

    private static final UUID TENANT_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID MEMBER_ID = UUID.fromString("20000000-0000-0000-0000-000000000004");
    private static final UUID ORG_A = UUID.fromString("31000000-0000-0000-0000-000000000001");
    private static final UUID ORG_B = UUID.fromString("31000000-0000-0000-0000-000000000002");

    @Test
    void requiresActiveMemberCurrentOrganizationAndTheSpecificAssignmentActionScope() {
        AtomicBoolean activeMember = new AtomicBoolean(true);
        AtomicReference<Set<UUID>> memberOrganizations = new AtomicReference<>(Set.of(ORG_A));
        AtomicReference<List<RoleScopeDescriptor>> scopes = new AtomicReference<>(
                List.of(new RoleScopeDescriptor(UUID.randomUUID(), "DEPT", null)));
        AtomicReference<String> grantedPermission = new AtomicReference<>();
        MockDataProvider provider = context -> {
            String sql = context.sql().toLowerCase(java.util.Locale.ROOT);
            if (sql.contains("set_config")) {
                var column = field("set_config", String.class);
                var result = using(SQLDialect.POSTGRES).newResult(column);
                Record1<String> row = using(SQLDialect.POSTGRES).newRecord(column);
                row.set(column, TENANT_ID.toString());
                result.add(row);
                return new MockResult[] {new MockResult(1, result)};
            }
            var column = field("exists", Boolean.class);
            var result = using(SQLDialect.POSTGRES).newResult(column);
            Record1<Boolean> row = using(SQLDialect.POSTGRES).newRecord(column);
            row.set(column, activeMember.get());
            result.add(row);
            return new MockResult[] {new MockResult(1, result)};
        };
        DSLContext dsl = using(new MockConnection(provider), SQLDialect.POSTGRES);
        MemberDirectoryReadRepository directory = new MemberDirectoryReadRepository() {
            @Override
            public MemberDirectorySnapshot findCurrentMemberships(UUID tenantId, UUID memberId) {
                return new MemberDirectorySnapshot(memberOrganizations.get(), Set.of(), Set.of());
            }

            @Override
            public Set<UUID> expandDepartmentTree(UUID tenantId, UUID memberId) {
                return Set.of(ORG_A, ORG_B);
            }

            @Override
            public Set<UUID> findActiveOrganizations(UUID tenantId, Set<UUID> organizationIds) {
                return organizationIds;
            }
        };
        RoleScopeReadRepository roleScopes = new RoleScopeReadRepository() {
            @Override
            public List<RoleScopeDescriptor> findActiveScopes(
                    UUID tenantId, UUID memberId, Set<UUID> organizationIds,
                    Set<UUID> positionIds, Set<UUID> groupIds, String permissionCode) {
                return List.of();
            }

            @Override
            public List<RoleScopeDescriptor> findActiveActionScopes(
                    UUID tenantId, UUID memberId, Set<UUID> organizationIds,
                    Set<UUID> positionIds, Set<UUID> groupIds,
                    String resourceType, String action, String permissionCode) {
                grantedPermission.set(permissionCode);
                return scopes.get();
            }
        };
        PlatformTransactionManager transactionManager = new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        };
        var repository = new PersistentFindingWorkflowAuthorizationRepository(
                dsl,
                (AccountStatusReadRepository) accountId -> java.util.Optional.empty(),
                (PrincipalStatusReadRepository) actorId -> java.util.Optional.empty(),
                (TenantMembershipReadRepository) (tenantId, accountId, memberId) -> java.util.Optional.empty(),
                directory, roleScopes, transactionManager);

        assertThat(repository.isAssignableMemberAuthorizedForAction(
                TENANT_ID, MEMBER_ID, ORG_A, "finding.dispatch")).isTrue();
        assertThat(grantedPermission).hasValue("finding.dispatch");

        scopes.set(List.of());
        assertThat(repository.isAssignableMemberAuthorizedForAction(
                TENANT_ID, MEMBER_ID, ORG_A, "finding.remediate")).isFalse();
        assertThat(grantedPermission).hasValue("finding.remediate");

        scopes.set(List.of(new RoleScopeDescriptor(UUID.randomUUID(), "DEPT_TREE", null)));
        assertThat(repository.isAssignableMemberAuthorizedForAction(
                TENANT_ID, MEMBER_ID, ORG_B, "finding.dispatch")).isFalse();
        memberOrganizations.set(Set.of(ORG_A, ORG_B));
        assertThat(repository.isAssignableMemberAuthorizedForAction(
                TENANT_ID, MEMBER_ID, ORG_B, "finding.dispatch")).isTrue();

        activeMember.set(false);
        assertThat(repository.isAssignableMemberAuthorizedForAction(
                TENANT_ID, MEMBER_ID, ORG_A, "finding.dispatch")).isFalse();
    }
}

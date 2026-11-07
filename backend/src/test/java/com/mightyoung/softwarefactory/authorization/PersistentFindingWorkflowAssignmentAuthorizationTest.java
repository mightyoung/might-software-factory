package com.mightyoung.softwarefactory.authorization;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.using;

class PersistentFindingWorkflowAssignmentAuthorizationTest {

    private static final UUID TENANT_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID MEMBER_ID = UUID.fromString("20000000-0000-0000-0000-000000000004");
    private static final UUID ORG_ID = UUID.fromString("31000000-0000-0000-0000-000000000001");

    @Test
    void searchIsOneBoundedEligibilityQueryWithLiteralPrefixAndUuidCursor() {
        Fixture fixture = new Fixture();
        var members = fixture.repository.findAssignableMembers(
                TENANT_ID, ORG_ID, "finding.remediate", "A%_\\'", MEMBER_ID, 51);

        assertThat(members).containsExactly(new AssignableFindingMember(MEMBER_ID, "Alice"));
        assertThat(fixture.statements).hasSize(2);
        assertThat(fixture.bindings.getFirst()).containsExactly("app.tenant_id", TENANT_ID.toString());
        assertThat(fixture.bindings.getLast()).containsExactly(
                TENANT_ID, ORG_ID, "finding.remediate", "remediate", "A\\%\\_\\\\'%", MEMBER_ID, 51);
        String sql = fixture.statements.getLast();
        assertThat(sql).contains("m.display_name like ? escape '\\'", "m.id > cast(? as uuid)")
                .endsWith("order by m.id asc limit ?")
                .doesNotContain("A%", "count(", "offset");
        assertThat(fixture.isolation).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThat(fixture.propagation).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
        assertThat(fixture.readOnly).isTrue();
    }

    @Test
    void authoritativeWriteAndCandidateSearchUseTheSameCompleteEligibilityPredicate() {
        Fixture fixture = new Fixture();
        fixture.repository.findAssignableMembers(TENANT_ID, ORG_ID, "finding.dispatch", null, null, 1);
        String candidate = fixture.statements.getLast();
        assertThat(fixture.repository.isAssignableMemberAuthorizedForAction(
                TENANT_ID, MEMBER_ID, ORG_ID, "finding.dispatch")).isTrue();
        String authority = fixture.statements.getLast();
        String predicate = candidate.substring(candidate.indexOf("  from public.tenant_member"),
                candidate.indexOf(" order by"));
        assertThat(authority).isEqualTo("select exists (select 1\n" + predicate
                + " and m.id = cast(? as uuid))");
        assertThat(fixture.bindings.getLast()).containsExactly(
                TENANT_ID, ORG_ID, "finding.dispatch", "dispatch", MEMBER_ID);
        assertThat(predicate).contains("a.status = 'ACTIVE'", "t.status = 'ACTIVE'", "m.status = 'ACTIVE'",
                "p.kind = 'HUMAN'", "p.status = 'ACTIVE'", "target_org.active = true",
                "target_membership.org_id = cast(? as uuid)", "r.active = true",
                "permission.resource_type = 'finding'", "permission.code = ?", "permission.action = ?",
                "rg.subject_kind = 'MEMBER'", "rg.subject_kind = 'ORG'", "rg.subject_kind = 'POSITION'",
                "rg.subject_kind = 'GROUP'", "ou.active = true", "pos.active = true", "g.active = true",
                "sr.kind in ('DEPT', 'DEPT_TREE')", "sr.kind = 'EXPLICIT'",
                "so.org_id = target_membership.org_id");
        for (String membership : List.of("target_membership", "rg", "mo", "mp", "gm")) {
            assertThat(predicate).contains(membership + ".valid_from <= current_timestamp",
                    membership + ".valid_to > current_timestamp");
        }
        // Subject membership uses the grant's organization rather than restricting to the target.
        assertThat(predicate).contains("mo.org_id = rg.org_id")
                .doesNotContain("mo.org_id = target_membership.org_id", "'SELF'", "'RELATION'", "r.name");
    }

    @Test
    void validatesBoundsAndUnicodePrefixBeforeOpeningATransaction() {
        Fixture fixture = new Fixture();
        for (int limit : List.of(0, -1, 52, Integer.MAX_VALUE)) {
            assertThatIllegalArgumentException().isThrownBy(() -> fixture.repository.findAssignableMembers(
                    TENANT_ID, ORG_ID, "finding.dispatch", null, null, limit));
        }
        String supplementary = "\uD83D\uDE00";
        assertThatIllegalArgumentException().isThrownBy(() -> fixture.repository.findAssignableMembers(
                TENANT_ID, ORG_ID, "finding.dispatch", supplementary.repeat(101), null, 1));
        assertThat(fixture.statements).isEmpty();
        fixture.repository.findAssignableMembers(
                TENANT_ID, ORG_ID, "finding.dispatch", supplementary.repeat(100), null, 1);
        assertThat(fixture.bindings.getLast()).contains(supplementary.repeat(100) + "%");
    }

    @Test
    void invalidIdentityOrPermissionFailsClosedWithoutQuerying() {
        Fixture fixture = new Fixture();
        assertThat(fixture.repository.findAssignableMembers(null, ORG_ID, "finding.dispatch", null, null, 1))
                .isEmpty();
        for (String permission : Arrays.asList(null, "account.dispatch", "finding.")) {
            assertThat(fixture.repository.findAssignableMembers(
                    TENANT_ID, ORG_ID, permission, null, null, 1)).isEmpty();
            assertThat(fixture.repository.isAssignableMemberAuthorizedForAction(
                    TENANT_ID, MEMBER_ID, ORG_ID, permission)).isFalse();
        }
        assertThat(fixture.statements).isEmpty();
        fixture.authorized = false;
        assertThat(fixture.repository.isAssignableMemberAuthorizedForAction(
                TENANT_ID, MEMBER_ID, ORG_ID, "finding.dispatch")).isFalse();
    }

    private static final class Fixture {
        private final List<String> statements = new ArrayList<>();
        private final List<List<Object>> bindings = new ArrayList<>();
        private boolean authorized = true;
        private int isolation;
        private int propagation;
        private boolean readOnly;
        private final PersistentFindingWorkflowAuthorizationRepository repository;

        private Fixture() {
            DSLContext values = using(SQLDialect.POSTGRES);
            DSLContext dsl = using(new MockConnection(context -> {
                statements.add(context.sql());
                bindings.add(Arrays.asList(context.bindings()));
                if (context.sql().contains("set_config")) {
                    var column = field("set_config", String.class);
                    var result = values.newResult(column);
                    result.add(values.newRecord(column).value1(TENANT_ID.toString()));
                    return new MockResult[] {new MockResult(1, result)};
                }
                if (context.sql().startsWith("select exists")) {
                    var column = field("exists", Boolean.class);
                    var result = values.newResult(column);
                    result.add(values.newRecord(column).value1(authorized));
                    return new MockResult[] {new MockResult(1, result)};
                }
                var id = field("id", UUID.class);
                var name = field("display_name", String.class);
                var result = values.newResult(id, name);
                result.add(values.newRecord(id, name).values(MEMBER_ID, "Alice"));
                return new MockResult[] {new MockResult(1, result)};
            }), SQLDialect.POSTGRES);
            PlatformTransactionManager manager = new PlatformTransactionManager() {
                @Override
                public TransactionStatus getTransaction(TransactionDefinition definition) {
                    isolation = definition.getIsolationLevel();
                    propagation = definition.getPropagationBehavior();
                    readOnly = definition.isReadOnly();
                    return new SimpleTransactionStatus();
                }

                @Override
                public void commit(TransactionStatus status) {
                }

                @Override
                public void rollback(TransactionStatus status) {
                }
            };
            // Search and singular checks are SQL-owned and do not call the snapshot collaborators.
            repository = new PersistentFindingWorkflowAuthorizationRepository(
                    dsl, null, null, null, null, null, manager);
        }
    }
}

package com.mightyoung.softwarefactory.authorization;

import com.mightyoung.softwarefactory.directory.JooqMemberDirectoryReadRepository;
import com.mightyoung.softwarefactory.identity.AccountSecurityState;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import com.mightyoung.softwarefactory.identity.PrincipalStatus;
import com.mightyoung.softwarefactory.tenancy.TenantMembershipState;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/** Real owning Jooq adapters, controlled JDBC rows: proves composition, not PG engine behavior. */
class PersistentFindingWorkflowClockTest {
    private static final UUID TENANT = UUID.randomUUID(), MEMBER = UUID.randomUUID(), ACCOUNT = UUID.randomUUID(),
            ACTOR = UUID.randomUUID(), ORG = UUID.randomUUID();
    private static final OffsetDateTime TX = OffsetDateTime.parse("2026-11-03T10:15:30.123456Z");
    private static final CurrentTenantContext CONTEXT = new CurrentTenantContext(ACTOR, ACCOUNT, TENANT, MEMBER, 1, 1, 1);

    @ParameterizedTest @ValueSource(strings = {"read committed", "repeatable read", "serializable"})
    void oneDatabaseObservationFeedsAllOwningMembershipGrantAndTreeQueries(String isolation) {
        var fixture = new Fixture(isolation);
        assertThat(fixture.repository.loadActionSnapshot(CONTEXT, "finding.dispatch", "dispatch"))
                .isPresent().get().extracting(FindingReadAuthorizationSnapshot::organizationIds).isEqualTo(Set.of(ORG));
        var at = isolation.equals("read committed") ? fixture.statementAt : TX;
        assertThat(fixture.statements.stream().filter(sql -> sql.contains("transaction_isolation"))).hasSize(1);
        assertThat(fixture.temporalBindings).hasSize(5);
        assertThat(fixture.temporalBindings.subList(0, 3)).allSatisfy(bound ->
                assertThat(bound).containsExactly(at, TENANT, MEMBER));
        assertThat(fixture.temporalBindings.get(3).stream().filter(at::equals).count()).isEqualTo(2);
        assertThat(fixture.temporalBindings.get(4)).containsExactly(at, TENANT, MEMBER);
    }

    @Test void anotherRcInvocationRecapturesInsteadOfKeepingThePreGuardHintTime() {
        var fixture = new Fixture("read committed");
        fixture.repository.loadActionSnapshot(CONTEXT, "finding.dispatch", "dispatch");
        var first = fixture.statementAt;
        fixture.statementAt = first.plusMinutes(5);
        fixture.repository.loadActionSnapshot(CONTEXT, "finding.dispatch", "dispatch");
        assertThat(fixture.statements.stream().filter(sql -> sql.contains("transaction_isolation"))).hasSize(2);
        assertThat(fixture.temporalBindings.get(0)).contains(first);
        assertThat(fixture.temporalBindings.get(5)).contains(fixture.statementAt).doesNotContain(first);
    }

    @Test void bothAssignmentEntryPointsBindOneCapturedInstantToTheEntireSharedPredicate() {
        var fixture = new Fixture("read committed");
        fixture.repository.findAssignableMembers(TENANT, ORG, "finding.dispatch", null, null, 1);
        fixture.statementAt = fixture.statementAt.plusMinutes(1);
        assertThat(fixture.repository.isAssignableMemberAuthorizedForAction(TENANT, MEMBER, ORG, "finding.dispatch")).isTrue();
        assertThat(fixture.temporalBindings).hasSize(2);
        assertThat(fixture.temporalBindings.get(0).getFirst()).isEqualTo(TX.plusHours(1));
        assertThat(fixture.temporalBindings.get(1).getFirst()).isEqualTo(fixture.statementAt);
        assertThat(fixture.statements.stream().filter(sql -> sql.contains("target_membership")))
                .allSatisfy(sql -> assertThat(sql).doesNotContain("current_timestamp")
                        .contains("cast(? as timestamptz)", "evaluation.evaluated_at"));
    }

    @Test void missingClockOrUnsupportedIsolationNeverReachesTemporalPolicyQueries() {
        for (String mode : List.of("missing", "read uncommitted", "unknown", "null-time")) {
            var fixture = new Fixture(mode);
            assertThatThrownBy(() -> fixture.repository.loadActionSnapshot(CONTEXT, "finding.dispatch", "dispatch"))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(fixture.temporalBindings).isEmpty();
        }
    }

    private static final class Fixture {
        private final List<String> statements = new ArrayList<>();
        private final List<List<Object>> temporalBindings = new ArrayList<>();
        private OffsetDateTime statementAt = TX.plusHours(1);
        private final PersistentFindingWorkflowAuthorizationRepository repository;
        private Fixture(String mode) {
            DSLContext values = DSL.using(SQLDialect.POSTGRES);
            DSLContext dsl = DSL.using(new MockConnection(context -> {
                String sql = context.sql(); statements.add(sql);
                if (sql.contains("set_config")) {
                    var field = DSL.field("set_config", String.class); var result = values.newResult(field);
                    result.add(values.newRecord(field).value1(TENANT.toString()));
                    return new MockResult[]{new MockResult(1, result)};
                }
                if (sql.contains("transaction_isolation")) {
                    var isolation = DSL.field("isolation", String.class);
                    var tx = DSL.field("transaction_at", OffsetDateTime.class);
                    var statement = DSL.field("statement_at", OffsetDateTime.class);
                    var result = values.newResult(isolation, tx, statement);
                    if (!mode.equals("missing")) result.add(values.newRecord(isolation, tx, statement).values(
                            mode.equals("null-time") ? "read committed" : mode, TX,
                            mode.equals("null-time") ? null : statementAt));
                    return new MockResult[]{new MockResult(result.size(), result)};
                }
                temporalBindings.add(Arrays.stream(context.bindings()).map(PersistentFindingWorkflowClockTest::temporalBinding).toList());
                if (sql.contains("select exists")) {
                    var field = DSL.field("exists", Boolean.class); var result = values.newResult(field);
                    result.add(values.newRecord(field).value1(true)); return new MockResult[]{new MockResult(1, result)};
                }
                if (sql.contains("select m.id")) {
                    var id=DSL.field("id",UUID.class); var name=DSL.field("display_name",String.class);
                    var result=values.newResult(id,name);result.add(values.newRecord(id,name).values(MEMBER,"Synthetic"));
                    return new MockResult[]{new MockResult(1,result)};
                }
                if (JooqRoleScopeReadRepository.commandReadSql().containsKey(sql)) {
                    var id=DSL.field("id",UUID.class); var kind=DSL.field("kind",String.class); var org=DSL.field("org_id",UUID.class);
                    var result=values.newResult(id,kind,org); result.add(values.newRecord(id,kind,org).values(UUID.randomUUID(),"DEPT_TREE",null));
                    return new MockResult[]{new MockResult(1,result)};
                }
                var id=DSL.field("id",UUID.class); var result=values.newResult(id);
                result.add(values.newRecord(id).value1(ORG)); return new MockResult[]{new MockResult(1,result)};
            }), SQLDialect.POSTGRES);
            PlatformTransactionManager manager = new PlatformTransactionManager() {
                public TransactionStatus getTransaction(TransactionDefinition definition) { return new SimpleTransactionStatus(); }
                public void commit(TransactionStatus status) { }
                public void rollback(TransactionStatus status) { }
            };
            repository=new PersistentFindingWorkflowAuthorizationRepository(dsl,
                    id->java.util.Optional.of(new AccountSecurityState("ACTIVE",1)),
                    id->java.util.Optional.of(new PrincipalStatus(ACTOR,ACCOUNT,"HUMAN","ACTIVE")),
                    (tenant,account,member)->java.util.Optional.of(new TenantMembershipState("ACTIVE","ACTIVE",1,1)),
                    new JooqMemberDirectoryReadRepository(dsl),new JooqRoleScopeReadRepository(dsl),manager);
        }
    }
    // PostgreSQL jOOQ may encode timestamptz as a bound JDBC String; compare its exact instant.
    private static Object temporalBinding(Object value) {
        if (value instanceof String encoded && encoded.matches("\\d{4}-\\d{2}-\\d{2} .*")) {
            return OffsetDateTime.parse(encoded.replace(' ', 'T')).withOffsetSameInstant(java.time.ZoneOffset.UTC);
        }
        return value;
    }


}

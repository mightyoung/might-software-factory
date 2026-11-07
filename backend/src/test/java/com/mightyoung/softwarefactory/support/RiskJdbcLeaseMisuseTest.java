package com.mightyoung.softwarefactory.support;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Connection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.jdbc.datasource.ConnectionHolder;
import static com.mightyoung.softwarefactory.support.RiskJdbcTransactionOwnerTest.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Adversarial lease tests only; the native repository and durable PG gates are separate. */
class RiskJdbcLeaseMisuseTest {
    private static final String SQL = "SELECT pinned_test_value";
    private static final RiskPublicationSqlCatalog.Operation OPERATION =
            new RiskPublicationSqlCatalog.Operation("Pinned test query", SQL,
                    RiskPublicationSqlCatalog.Kind.QUERY, List.of());
    enum Attack { UPDATE_SQL, QUERY_SQL, EXECUTE_SQL, UNWRAP }
    enum TemplateRefusal { UNLISTED_SQL, FORBIDDEN_OVERLOAD }

    @ParameterizedTest @EnumSource(TemplateRefusal.class)
    void swallowedTemplateRefusalBeforeOperationCannotResumeOrCommit(TemplateRefusal refusal) throws Exception {
        Fixture fixture = fixture(Fault.NONE);
        var template = new RiskPublicationJdbcTemplate(fixture.source(), RiskPublicationSqlCatalogTest.catalog(),
                Set.of(String.class), getClass().getClassLoader(), fixture.owner());
        var failure = failure(() -> fixture.owner().executeOwned(() -> {
            assertThatThrownBy(() -> {
                if (refusal == TemplateRefusal.UNLISTED_SQL)
                    template.update("DELETE FROM unrelated", new Object[0]);
                else template.execute("DELETE FROM unrelated");
            }).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThatThrownBy(() -> fixture.owner().operation(OPERATION, () -> prepare(fixture)))
                    .isInstanceOf(RiskJdbcTransactionOwner.Failure.class);
            return "caller swallowed pre-operation refusal";
        }));
        assertThat(failure.outcome()).isEqualTo(RiskJdbcTransactionOwner.Outcome.ROLLED_BACK);
        verify(fixture.connection(), never()).prepareStatement(anyString());
        verify(fixture.connection(), never()).commit();
        verify(fixture.connection()).rollback();
        assertUnbound(fixture);
    }

    @ParameterizedTest @EnumSource(Attack.class)
    void swallowedChildRefusalCannotCommitAndDoesNotReachDriver(Attack attack) throws Exception {
        Fixture fixture = fixture(Fault.NONE);
        PreparedStatement driver = mock(PreparedStatement.class);
        doReturn(driver).when(fixture.connection()).prepareStatement(SQL);
        var failure = failure(() -> fixture.owner().executeOwned(() -> fixture.owner().operation(OPERATION, () -> {
            PreparedStatement child = prepare(fixture);
            assertThatThrownBy(() -> {
                switch (attack) {
                    case UPDATE_SQL -> child.executeUpdate("DELETE FROM unrelated");
                    case QUERY_SQL -> child.executeQuery("SELECT private_value FROM unrelated");
                    case EXECUTE_SQL -> child.execute("DELETE FROM unrelated");
                    case UNWRAP -> child.unwrap(PreparedStatement.class);
                }
            }).isInstanceOf(SQLException.class);
            return "caller swallowed refusal";
        })));
        assertThat(failure.outcome()).isEqualTo(RiskJdbcTransactionOwner.Outcome.ROLLED_BACK);
        assertSafe(failure);
        verifyNoInteractions(driver);
        verify(fixture.connection(), never()).commit();
        verify(fixture.connection()).rollback();
        assertUnbound(fixture);
    }

    @Test void swallowedDriverFailureStillMakesTheTransactionUncommittable() throws Exception {
        Fixture fixture = fixture(Fault.NONE);
        PreparedStatement driver = mock(PreparedStatement.class);
        when(driver.executeQuery()).thenThrow(secretSql());
        doReturn(driver).when(fixture.connection()).prepareStatement(SQL);
        var failure = failure(() -> fixture.owner().executeOwned(() -> fixture.owner().operation(OPERATION, () -> {
            assertThatThrownBy(prepare(fixture)::executeQuery).isInstanceOf(SQLException.class)
                    .satisfies(error -> assertSafe((Throwable) error));
            return "caller swallowed driver failure";
        })));
        assertThat(failure.outcome()).isEqualTo(RiskJdbcTransactionOwner.Outcome.ROLLED_BACK);
        verify(driver).executeQuery();
        verify(fixture.connection(), never()).commit();
        verify(fixture.connection()).rollback();
        assertUnbound(fixture);
    }

    @Test void childCannotBeUsedOnAnotherThreadEvenWhileTheOperationIsOpen() throws Exception {
        Fixture fixture = fixture(Fault.NONE);
        PreparedStatement driver = mock(PreparedStatement.class);
        doReturn(driver).when(fixture.connection()).prepareStatement(SQL);
        AtomicReference<Throwable> rejected = new AtomicReference<>();
        var failure = failure(() -> fixture.owner().executeOwned(() -> fixture.owner().operation(OPERATION, () -> {
            PreparedStatement child = prepare(fixture);
            Thread thread = new Thread(() -> {
                try { child.executeQuery(); }
                catch (Throwable error) { rejected.set(error); }
            });
            thread.start();
            try { thread.join(5000); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
            assertThat(thread.isAlive()).isFalse();
            assertThat(rejected.get()).isNotNull();
            assertSafe(rejected.get());
            return null;
        })));
        assertThat(failure.outcome()).isEqualTo(RiskJdbcTransactionOwner.Outcome.ROLLED_BACK);
        verifyNoInteractions(driver);
        verify(fixture.connection(), never()).commit();
        assertUnbound(fixture);
    }

    @Test void childExpiresAtOperationEndBeforeTheTransactionEnds() throws Exception {
        Fixture fixture = fixture(Fault.NONE);
        PreparedStatement driver = mock(PreparedStatement.class);
        doReturn(driver).when(fixture.connection()).prepareStatement(SQL);
        var failure = failure(() -> fixture.owner().executeOwned(() -> {
            PreparedStatement retained = fixture.owner().operation(OPERATION, () -> prepare(fixture));
            assertThatThrownBy(retained::executeQuery).isInstanceOfAny(SQLException.class, RiskJdbcTransactionOwner.Failure.class);
            return "caller swallowed expired permit";
        }));
        assertThat(failure.outcome()).isEqualTo(RiskJdbcTransactionOwner.Outcome.ROLLED_BACK);
        verifyNoInteractions(driver);
        verify(fixture.connection(), never()).commit();
        assertUnbound(fixture);
    }

    @Test void repeatingTheSameSqlDoesNotReviveAChildFromThePreviousOperation() throws Exception {
        Fixture fixture = fixture(Fault.NONE);
        PreparedStatement driver = mock(PreparedStatement.class);
        doReturn(driver).when(fixture.connection()).prepareStatement(SQL);
        var failure = failure(() -> fixture.owner().executeOwned(() -> {
            PreparedStatement retained = fixture.owner().operation(OPERATION, () -> prepare(fixture));
            return fixture.owner().operation(OPERATION, () -> {
                assertThatThrownBy(retained::executeQuery)
                        .isInstanceOfAny(SQLException.class, RiskJdbcTransactionOwner.Failure.class);
                return "caller swallowed previous-operation refusal";
            });
        }));
        assertThat(failure.outcome()).isEqualTo(RiskJdbcTransactionOwner.Outcome.ROLLED_BACK);
        verifyNoInteractions(driver);
        verify(fixture.connection(), times(1)).prepareStatement(SQL);
        verify(fixture.connection(), never()).commit();
        assertUnbound(fixture);
    }

    @Test void completedParticipantCannotChangeTheStillLiveOwnerTransaction() throws Exception {
        Fixture fixture = fixture(Fault.NONE);
        var definition = new DefaultTransactionDefinition();
        var owner = fixture.owner().beginNative(definition);
        var participant = fixture.owner().beginNative(definition);
        fixture.owner().finishNative(participant, true);
        verify(fixture.connection(), never()).commit();
        assertThatThrownBy(participant::setRollbackOnly).isInstanceOf(RiskJdbcTransactionOwner.Failure.class);
        var failure = failure(() -> fixture.owner().finishNative(owner, true));
        assertThat(failure.outcome()).isEqualTo(RiskJdbcTransactionOwner.Outcome.ROLLED_BACK);
        verify(fixture.connection()).rollback();
        verify(fixture.source(), times(1)).getConnection();
        assertUnbound(fixture);
    }

    @Test void foreignTemplateAliasIsPreservedAndPreventsTransactionControlOrCleanup() throws Exception {
        Fixture fixture = fixture(Fault.NONE);
        var alias = fixture.owner().template(RiskPublicationSqlCatalogTest.catalog(), Set.of(String.class),
                getClass().getClassLoader()).getDataSource();
        Connection foreignConnection = mock(Connection.class);
        ConnectionHolder foreign = new ConnectionHolder(foreignConnection);
        try {
            var failure = failure(() -> fixture.owner().executeOwned(() -> {
                Object canonicalHolder = TransactionSynchronizationManager.getResource(fixture.source());
                assertThat(TransactionSynchronizationManager.unbindResource(alias)).isSameAs(canonicalHolder);
                TransactionSynchronizationManager.bindResource(alias, foreign);
                return "alias ownership lost";
            }));
            assertThat(failure.outcome()).isEqualTo(RiskJdbcTransactionOwner.Outcome.ACTIVE);
            assertThat(failure.cleanup()).isEqualTo(RiskJdbcTransactionOwner.Cleanup.FAILED);
            assertThat(TransactionSynchronizationManager.getResource(alias)).isSameAs(foreign);
            assertUnbound(fixture);
            verify(fixture.connection(), never()).commit();
            verify(fixture.connection(), never()).rollback();
            verify(fixture.connection(), never()).setAutoCommit(true);
            verify(fixture.connection(), never()).setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            verify(fixture.connection(), never()).close();
            verifyNoInteractions(foreignConnection);
        } finally {
            assertThat(TransactionSynchronizationManager.unbindResource(alias)).isSameAs(foreign);
        }
    }

    private static PreparedStatement prepare(Fixture fixture) {
        try { return bound(fixture).prepareStatement(SQL); }
        catch (SQLException failure) { throw new AssertionError("Expected permitted statement preparation", failure); }
    }
}

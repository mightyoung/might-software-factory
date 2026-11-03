package com.mightyoung.softwarefactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.ServletException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.ConnectionProxy;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Pure JDBC delegation/observation contracts; no target, credentials, socket or PostgreSQL. */
class G013ObservedDataSourceContractTest {
    private static final String ACCOUNT = "select status,credential_version from public.account where id=?";
    private static final String CANDIDATE = "select id,code,name from public.org_unit where tenant_id=? order by id asc fetch next ? rows only";
    private static final String OWNER = "select current_setting('app.tenant_id',true), true statement_timeout_valid, true lock_timeout_valid";
    private static final UUID TENANT = UUID.fromString("a1000000-0000-0000-0000-000000000001");
    private static final OffsetDateTime TIME = OffsetDateTime.parse("2026-11-04T00:00:00Z");

    @Test void preparationIsUnobservedAndExecutionPreservesBindingsRowsAndConnectionContracts() throws Exception {
        var fixture = new JdbcFixture();
        fixture.scope(() -> {
            Connection wrapped = fixture.source.getConnection();
            assertThat(wrapped).isInstanceOf(ConnectionProxy.class);
            assertThat(((ConnectionProxy) wrapped).getTargetConnection()).isSameAs(fixture.real);
            assertThat(DataSourceUtils.getTargetConnection(wrapped)).isSameAs(fixture.real);
            assertThat(wrapped.unwrap(Connection.class)).isSameAs(fixture.real);
            try (var statement = wrapped.prepareStatement(CANDIDATE)) {
                PreparedStatement actual = fixture.prepared.getLast();
                Object binding = new Object(); statement.setObject(1, binding);
                verify(actual).setObject(1, binding);
                assertThat(statement.getConnection()).isSameAs(wrapped);
                assertThat(fixture.events()).isEmpty();
                when(actual.executeQuery()).thenAnswer(call -> {
                    assertThat(fixture.events()).hasSize(1);
                    assertThat(fixture.events().getFirst().stage()).isEqualTo(G013ObservedDataSource.Stage.ATTEMPT);
                    return fixture.rows;
                });
                try (var rows = statement.executeQuery()) { assertThat(rows).isSameAs(fixture.rows); }
                verify(fixture.rows).close();
            }
            wrapped.close(); verify(fixture.real).close();
        });
        assertThat(fixture.events().stream().map(G013ObservedDataSource.Event::stage).toList())
                .containsExactly(G013ObservedDataSource.Stage.ATTEMPT, G013ObservedDataSource.Stage.SUCCESS);
    }

    @Test void failedExecutionKeepsTheActualExceptionAndDoesNotFabricateSuccess() throws Exception {
        var fixture = new JdbcFixture();
        SQLException failure = new SQLException("Owned synthetic statement failure", "57014");
        fixture.scope(() -> {
            var statement = fixture.source.getConnection().prepareStatement(CANDIDATE);
            when(fixture.prepared.getLast().executeQuery()).thenThrow(failure);
            assertThatThrownBy(statement::executeQuery).isSameAs(failure);
        });
        assertThat(fixture.events()).singleElement().satisfies(event -> {
            assertThat(event.label()).isEqualTo(G013ObservedDataSource.Label.CANDIDATE);
            assertThat(event.stage()).isEqualTo(G013ObservedDataSource.Stage.ATTEMPT);
        });
    }

    @Test void ownerPhaseStartsAtItsFiniteBoundaryAndEndsAtTheActualCommit() throws Exception {
        var fixture = new JdbcFixture();
        fixture.state.set(new Metadata(110, "read committed", "off", null));
        fixture.scope(() -> {
            var connection = fixture.source.getConnection();
            connection.prepareStatement(ACCOUNT).executeQuery();
            fixture.state.set(new Metadata(110, "repeatable read", "on", TENANT.toString()));
            connection.prepareStatement(OWNER).executeQuery();
            connection.prepareStatement(ACCOUNT).executeQuery();
            connection.commit(); verify(fixture.real).commit();
            fixture.state.set(new Metadata(110, "read committed", "off", null));
            connection.prepareStatement(ACCOUNT).executeQuery();
        });
        assertThat(fixture.events().stream().filter(event -> event.label() == G013ObservedDataSource.Label.ACCOUNT
                && event.stage() == G013ObservedDataSource.Stage.SUCCESS).map(G013ObservedDataSource.Event::phase).toList())
                .containsExactly(G013ObservedDataSource.Phase.PREFILTER_OR_CONTEXT,
                        G013ObservedDataSource.Phase.OWNER_RO_RR, G013ObservedDataSource.Phase.PREFILTER_OR_CONTEXT);
    }

    @Test void observationsKeepRealDifferentConnectionMetadataInsteadOfCopyingTheOwnerPid() throws Exception {
        var fixture = new JdbcFixture();
        fixture.scope(() -> {
            var connection = fixture.source.getConnection();
            connection.prepareStatement(OWNER).executeQuery();
            fixture.state.set(new Metadata(222, "read committed", "off", TENANT.toString()));
            connection.prepareStatement(ACCOUNT).executeQuery();
        });
        assertThat(fixture.events().stream().filter(event -> event.label() == G013ObservedDataSource.Label.ACCOUNT
                && event.stage() == G013ObservedDataSource.Stage.SUCCESS)).singleElement().satisfies(event -> {
                    assertThat(event.state().pid()).isEqualTo(222);
                    assertThat(event.state().ownerRead()).isFalse();
                    assertThat(event.phase()).isEqualTo(G013ObservedDataSource.Phase.OWNER_RO_RR);
                });
    }

    @Test void anArmedBarrierRejectsAnotherTenantBeforeDelegatingAndCanAlwaysBeReleased() throws Exception {
        var fixture = new JdbcFixture();
        try (var barrier = fixture.source.armCandidate(UUID.randomUUID())) {
            fixture.scope(() -> {
                var connection = fixture.source.getConnection();
                connection.prepareStatement(OWNER).executeQuery();
                var statement = connection.prepareStatement(CANDIDATE);
                PreparedStatement actual = fixture.prepared.getLast();
                assertThatThrownBy(statement::executeQuery).isInstanceOf(IllegalStateException.class);
                verify(actual, never()).executeQuery();
            });
        }
        fixture.source.clear();
        assertThat(fixture.events()).isEmpty();
    }

    @Test void theSingleOwnedCandidateWaitsForExplicitReleaseAndThenDelegatesExactlyOnce() throws Exception {
        var fixture = new JdbcFixture();
        try (var executor = Executors.newSingleThreadExecutor(); var barrier = fixture.source.armCandidate(TENANT)) {
            var pending = executor.submit(() -> {
                fixture.scope(() -> {
                    var connection = fixture.source.getConnection();
                    connection.prepareStatement(OWNER).executeQuery();
                    connection.prepareStatement(CANDIDATE).executeQuery();
                });
                return true;
            });
            try {
                assertThat(barrier.awaitReached()).isTrue();
                assertThat(fixture.events().stream().filter(event -> event.label() == G013ObservedDataSource.Label.CANDIDATE))
                        .singleElement().satisfies(event -> assertThat(event.stage()).isEqualTo(G013ObservedDataSource.Stage.ATTEMPT));
            } finally { barrier.close(); }
            assertThat(pending.get(2, TimeUnit.SECONDS)).isTrue();
            verify(fixture.prepared.getLast()).executeQuery();
        }
    }

    @FunctionalInterface private interface CheckedAction { void run() throws Exception; }
    private record Metadata(int pid, String isolation, String readOnly, String tenant) { }
    private static final class JdbcFixture {
        final DataSource dataSource = mock(DataSource.class);
        final Connection real = mock(Connection.class);
        final ResultSet rows = mock(ResultSet.class);
        final List<PreparedStatement> prepared = new ArrayList<>();
        final AtomicReference<Metadata> state = new AtomicReference<>(new Metadata(110, "repeatable read", "on", TENANT.toString()));
        final UUID trace = UUID.randomUUID();
        final G013ObservedDataSource source;
        JdbcFixture() throws SQLException {
            when(dataSource.getConnection()).thenReturn(real);
            when(real.unwrap(Connection.class)).thenReturn(real);
            when(real.prepareStatement(anyString())).thenAnswer(call -> {
                var statement = mock(PreparedStatement.class);
                when(statement.getConnection()).thenReturn(real);
                when(statement.executeQuery()).thenReturn(rows);
                prepared.add(statement); return statement;
            });
            when(real.createStatement()).thenAnswer(call -> {
                var statement = mock(Statement.class);
                when(statement.executeQuery(anyString())).thenAnswer(query -> {
                    var result = mock(ResultSet.class);
                    Metadata current = state.get();
                    when(result.next()).thenReturn(true, false);
                    when(result.getInt("pid")).thenReturn(current.pid());
                    when(result.getObject("transaction_at", OffsetDateTime.class)).thenReturn(TIME);
                    when(result.getString("isolation")).thenReturn(current.isolation());
                    when(result.getString("readonly")).thenReturn(current.readOnly());
                    when(result.getString("tenant")).thenReturn(current.tenant());
                    return result;
                });
                return statement;
            });
            source = new G013ObservedDataSource(dataSource);
        }
        List<G013ObservedDataSource.Event> events() { return source.events(trace.toString()); }
        void scope(CheckedAction action) throws Exception {
            var request = new MockHttpServletRequest("POST", G013ObservedDataSource.REFERENCE_PATH);
            request.addHeader(IdentityAcceptanceHttps.TRACE_HEADER, trace.toString());
            source.requestScope().doFilter(request, new MockHttpServletResponse(), (ignored, response) -> {
                try { action.run(); }
                catch (Exception failure) { throw new ServletException(failure); }
            });
        }
    }
}

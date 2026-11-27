package com.mightyoung.softwarefactory.platformadmin.digitaltwin;

import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class StudioAuthorityQueriesTest {
    private final UUID tenant = UUID.randomUUID();
    private final UUID member = UUID.randomUUID();
    private final CurrentTenantContext current =
            new CurrentTenantContext(UUID.randomUUID(), UUID.randomUUID(), tenant, member, 0, 0, 1);

    @Test void foreignScreenIsRejectedBeforeSql() throws SQLException {
        var recorder = new Recorder();
        assertFalse(StudioAuthorityQueries.mayRead(recorder.connection, current,
                new StudioPublicationPolicy.Target(UUID.randomUUID(), "floor")));
        assertTrue(recorder.calls.isEmpty());
    }

    @Test void readBindsExactTenantMemberAndScreenWithoutActorOrOrganization() throws SQLException {
        var recorder = new Recorder();
        recorder.granted = true;
        assertTrue(StudioAuthorityQueries.mayRead(recorder.connection, current,
                new StudioPublicationPolicy.Target(tenant, "floor")));
        assertEquals(List.of(StudioAuthorityQueries.SCREEN_SQL, List.of(1, tenant), List.of(2, member),
                List.of(3, "floor"), "query", "close rows", "close statement"), recorder.calls);
    }

    @Test void publishUsesSeparateTenantCapabilityAndErrorsAreNotDenials() throws SQLException {
        var recorder = new Recorder();
        assertFalse(StudioAuthorityQueries.mayPublish(recorder.connection, current));
        assertEquals(List.of(StudioAuthorityQueries.PUBLISH_SQL, List.of(1, tenant), List.of(2, member),
                "query", "close rows", "close statement"), recorder.calls);
        recorder.failure = true;
        assertThrows(SQLException.class, () -> StudioAuthorityQueries.mayPublish(recorder.connection, current));
        assertEquals("close statement", recorder.calls.getLast());
    }

    private static final class Recorder {
        final List<Object> calls = new ArrayList<>();
        boolean granted;
        boolean failure;
        final Connection connection = proxy(Connection.class, (p, method, args) -> {
            if (!method.getName().equals("prepareStatement")) throw new AssertionError(method);
            calls.add(args[0]);
            return proxy(PreparedStatement.class, (s, action, values) -> switch (action.getName()) {
                case "setObject", "setString" -> { calls.add(List.of(values)); yield null; }
                case "executeQuery" -> {
                    calls.add("query");
                    if (failure) throw new SQLException("unavailable");
                    yield rows();
                }
                case "close" -> { calls.add("close statement"); yield null; }
                default -> throw new AssertionError(action);
            });
        });

        private ResultSet rows() {
            var cursor = new boolean[]{false};
            return proxy(ResultSet.class, (p, method, args) -> switch (method.getName()) {
                case "next" -> { boolean first = !cursor[0]; cursor[0] = true; yield first; }
                case "getBoolean" -> granted;
                case "wasNull" -> false;
                case "close" -> { calls.add("close rows"); yield null; }
                default -> throw new AssertionError(method);
            });
        }
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}

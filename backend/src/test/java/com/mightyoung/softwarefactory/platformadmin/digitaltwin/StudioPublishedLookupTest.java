package com.mightyoung.softwarefactory.platformadmin.digitaltwin;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static com.mightyoung.softwarefactory.platformadmin.digitaltwin.StudioPublicationPolicy.*;

class StudioPublishedLookupTest {
    private final Target target = new Target(UUID.randomUUID(), "floor");
    private final Caller reader = new Caller(target.tenantId(), UUID.randomUUID(), UUID.randomUUID(), false, Set.of("floor"));

    @Test void missingPinUsesOneCompositeQueryAndNeverFallsBack() {
        var jdbc = new Recorder();
        assertTrue(new StudioPublishedLookup(jdbc.connection).find(target, 8L).isEmpty());
        assertEquals(List.of(StudioPublishedLookup.PIN_SQL, List.of(1, target.tenantId()), List.of(2, "floor"),
                List.of(3, 8L), "close rows", "close statement"), jdbc.calls);
    }

    @Test void authorizedAliasReturnsVerifiedImmutableBytesAndNoExtraRead() {
        var jdbc = new Recorder();
        var expected = jdbc.snapshot.canonicalBytes();
        jdbc.size = 1;
        var player = new StudioPlayerPublication(new StudioPublishedLookup(jdbc.connection));
        var result = player.read(reader, target, null).orElseThrow();
        assertEquals(target, result.target());
        assertEquals(3L, result.version());
        assertArrayEquals(expected, result.canonicalBytes());
        assertEquals(List.of(StudioPublishedLookup.CURRENT_SQL, List.of(1, target.tenantId()), List.of(2, "floor"),
                "close rows", "close statement"), jdbc.calls);
    }

    @Test void deniedScreenNeverTouchesPublicationConnection() {
        var jdbc = new Recorder();
        var player = new StudioPlayerPublication(new StudioPublishedLookup(jdbc.connection));
        assertThrows(Forbidden.class, () -> player.read(reader, new Target(target.tenantId(), "other"), null));
        assertTrue(jdbc.calls.isEmpty());
    }

    @Test void aForeignRowOrDuplicateIsNotReturnedAndSqlFailureIsNotAbsence() {
        var jdbc = new Recorder();
        jdbc.size = 1;
        jdbc.storedTenant = UUID.randomUUID();
        var player = new StudioPlayerPublication(new StudioPublishedLookup(jdbc.connection));
        assertThrows(Forbidden.class, () -> player.read(reader, target, null));
        jdbc.storedTenant = target.tenantId();
        jdbc.size = 2;
        assertThrows(IllegalStateException.class, () -> player.read(reader, target, null));
        jdbc.broken = true;
        var failure = assertThrows(IllegalStateException.class, () -> player.read(reader, target, null));
        assertFalse(failure.getMessage().contains("raw storage failure"));
    }

    private final class Recorder {
        final List<Object> calls = new ArrayList<>();
        final StudioDraftDecoder.Decoded snapshot = StudioDraftDecoder.decode("""
                {"snapshotVersion":1,"scene":{"schemaVersion":1,"screenId":"floor","devices":[]},
                 "floorplan":{"walls":[],"zones":[]}}
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        UUID storedTenant = target.tenantId();
        int size;
        boolean broken;
        final Connection connection = proxy(Connection.class, (p, method, args) -> {
            if (!method.getName().equals("prepareStatement")) throw new AssertionError(method);
            calls.add(args[0]);
            return proxy(PreparedStatement.class, (s, action, values) -> switch (action.getName()) {
                case "setObject", "setString", "setLong" -> { calls.add(List.of(values)); yield null; }
                case "executeQuery" -> {
                    if (broken) throw new SQLException("raw storage failure");
                    yield rows();
                }
                case "close" -> { calls.add("close statement"); yield null; }
                default -> throw new AssertionError(action);
            });
        });

        private ResultSet rows() {
            var index = new int[]{0};
            return proxy(ResultSet.class, (p, method, args) -> switch (method.getName()) {
                case "next" -> ++index[0] <= size;
                case "getObject" -> storedTenant;
                case "getString" -> Integer.valueOf(2).equals(args[0]) ? "floor" : snapshot.facts().canonicalSha256();
                case "getLong" -> 3L;
                case "getBytes" -> snapshot.canonicalBytes();
                case "close" -> { calls.add("close rows"); yield null; }
                default -> throw new AssertionError(method);
            });
        }
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}

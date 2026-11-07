package com.mightyoung.softwarefactory;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.modulith.events.jdbc.JdbcRepositorySettings;

/** Frozen native PostgreSQL/V1/UPDATE SQL. The composition root must also witness the actual V1 repository. */
public final class RiskPublicationSqlCatalog {
    enum Kind { UPDATE, QUERY, QUERY_ARGS }
    record Operation(String label, String sql, Kind kind, List<Class<?>> arguments) {}
    static final String SQL_STATEMENT_INSERT = "INSERT INTO %s (ID, EVENT_TYPE, LISTENER_ID, PUBLICATION_DATE, SERIALIZED_EVENT)\nVALUES (?, ?, ?, ?, ?)\n";
    static final String SQL_STATEMENT_FIND_COMPLETED = "SELECT ID, COMPLETION_DATE, EVENT_TYPE, LISTENER_ID, PUBLICATION_DATE, SERIALIZED_EVENT\nFROM %s\nWHERE COMPLETION_DATE IS NOT NULL\nORDER BY PUBLICATION_DATE ASC\n";
    static final String SQL_STATEMENT_FIND_INCOMPLETE = "SELECT ID, COMPLETION_DATE, EVENT_TYPE, LISTENER_ID, PUBLICATION_DATE, SERIALIZED_EVENT\nFROM %s\nWHERE COMPLETION_DATE IS NULL\nORDER BY PUBLICATION_DATE ASC\n";
    static final String SQL_STATEMENT_FIND_INCOMPLETE_PUBLISHED_BEFORE = "SELECT ID, COMPLETION_DATE, EVENT_TYPE, LISTENER_ID, PUBLICATION_DATE, SERIALIZED_EVENT\nFROM %s\nWHERE\n\t\tCOMPLETION_DATE IS NULL\n\t\tAND PUBLICATION_DATE < ?\nORDER BY PUBLICATION_DATE ASC\n";
    static final String SQL_STATEMENT_FIND_BY_EVENT_AND_LISTENER_ID = "SELECT *\nFROM %s\nWHERE\n\t\tSERIALIZED_EVENT = ?\n\t\tAND LISTENER_ID = ?\n\t\tAND COMPLETION_DATE IS NULL\nORDER BY PUBLICATION_DATE\n";
    static final String SQL_STATEMENT_UPDATE_BY_EVENT_AND_LISTENER_ID = "UPDATE %s\nSET COMPLETION_DATE = ?\nWHERE\n\t\tLISTENER_ID = ?\n\t\tAND COMPLETION_DATE IS NULL\n\t\tAND SERIALIZED_EVENT = ?\n";
    static final String SQL_STATEMENT_UPDATE_BY_ID = "UPDATE %s\nSET COMPLETION_DATE = ?\nWHERE\n\t\tID = ?\n";
    private final Map<String, Operation> operations;

    public RiskPublicationSqlCatalog(JdbcRepositorySettings settings, Environment environment) {
        try {
            Objects.requireNonNull(settings); Objects.requireNonNull(environment);
            Object databaseType = settings.getDatabaseType();
            if (!(databaseType instanceof Enum<?> database) || !database.name().equals("POSTGRES")
                    || !settings.isUpdateCompletion() || !Boolean.TRUE.equals(environment.getProperty("spring.modulith.events.jdbc.use-legacy-structure", Boolean.class))
                    || !Objects.equals(settings.getSchema(), environment.getProperty("spring.modulith.events.jdbc.schema"))) throw unavailable();
            String schema = settings.getSchema();
            if (schema != null && !schema.isEmpty() && !schema.equals("public")) throw unavailable();
            String table = schema == null || schema.isEmpty() ? "EVENT_PUBLICATION" : "public.EVENT_PUBLICATION";
            var values = new LinkedHashMap<String, Operation>();
            add(values, table, "insert", SQL_STATEMENT_INSERT, Kind.UPDATE,
                    UUID.class, String.class, String.class, Timestamp.class, String.class);
            add(values, table, "completed", SQL_STATEMENT_FIND_COMPLETED, Kind.QUERY);
            add(values, table, "incomplete", SQL_STATEMENT_FIND_INCOMPLETE, Kind.QUERY);
            add(values, table, "incomplete-before", SQL_STATEMENT_FIND_INCOMPLETE_PUBLISHED_BEFORE, Kind.QUERY_ARGS, Timestamp.class);
            add(values, table, "event-target", SQL_STATEMENT_FIND_BY_EVENT_AND_LISTENER_ID, Kind.QUERY_ARGS, String.class, String.class);
            add(values, table, "complete-event-target", SQL_STATEMENT_UPDATE_BY_EVENT_AND_LISTENER_ID, Kind.UPDATE,
                    Timestamp.class, String.class, String.class);
            add(values, table, "complete-id", SQL_STATEMENT_UPDATE_BY_ID, Kind.UPDATE, Timestamp.class, UUID.class);
            operations = Map.copyOf(values);
        } catch (RuntimeException | Error failure) { throw unavailable(); }
    }

    Operation require(String sql, Kind kind, Object[] arguments) {
        if (sql == null || kind == null) throw unavailable();
        Operation operation = operations.get(sql);
        if (operation == null || operation.kind() != kind || arguments == null
                || arguments.length != operation.arguments().size()) throw unavailable();
        for (int index = 0; index < arguments.length; index++) {
            if (arguments[index] == null || arguments[index].getClass() != operation.arguments().get(index)) throw unavailable();
        }
        return operation;
    }

    private static void add(Map<String, Operation> values, String table, String label, String sql, Kind kind, Class<?>... types) {
        String actual = sql.formatted(table);
        if (values.put(actual, new Operation("Risk publication " + label, actual, kind, List.of(types))) != null) throw unavailable();
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("Risk publication SQL catalog unavailable");
    }
}

package com.mightyoung.softwarefactory;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.modulith.events.jdbc.JdbcRepositorySettings;

/** Validates each new lease before a query transaction can use it; owns neither the pool nor a bean. */
final class TenantAuditPostgresDataSource extends AbstractDataSource {
    private final DataSource original;
    private final JdbcRepositorySettings settings;
    TenantAuditPostgresDataSource(DataSource source,JdbcRepositorySettings settings) {
        this.original=Objects.requireNonNull(source);this.settings=Objects.requireNonNull(settings);
    }
    @Override public Connection getConnection() throws SQLException {
        requireSettings();return requirePostgres(original.getConnection());
    }
    @Override public Connection getConnection(String username,String password) throws SQLException {
        requireSettings();return requirePostgres(original.getConnection(username,password));
    }
    private void requireSettings() throws SQLException {
        Object database=settings.getDatabaseType();
        if(!(database instanceof Enum<?> type)||!"POSTGRES".equals(type.name()))throw rejected();
    }
    private Connection requirePostgres(Connection connection) throws SQLException {
        if(connection==null)throw rejected();
        try {
            DatabaseMetaData metadata=connection.getMetaData();
            if(metadata==null||!"PostgreSQL".equals(metadata.getDatabaseProductName()))throw rejected();
            return connection;
        } catch(SQLException | RuntimeException | Error failure) {
            try { connection.close(); }
            catch(SQLException | RuntimeException | Error closeFailure) {
                if(closeFailure!=failure)failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }
    private static SQLException rejected(){return new SQLException("Tenant audit query requires PostgreSQL");}
}

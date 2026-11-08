package com.mightyoung.softwarefactory.authorization;

import com.mightyoung.softwarefactory.identity.CapturedHumanRequest;
import com.mightyoung.softwarefactory.identity.HumanSessionAuthorityTransaction;
import com.mightyoung.softwarefactory.identity.SessionSensitiveException;
import java.sql.Connection;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import javax.sql.DataSource;

/** One fixed tenant-audit read capability, never an arbitrary permission selector. */
public interface TenantAuditReadAuthorization {
    Scope requireRead(CapturedHumanRequest request, HumanSessionAuthorityTransaction.ReadBoundary boundary);
    void requireCurrent(Scope scope);
    void requireSqlConnection(Scope scope, DataSource source, Connection connection);

    final class Scope {
        final Object issuer;
        private final UUID tenantId;
        private final Instant observedAt;
        private final Runnable validation;
        private final BiConsumer<DataSource, Connection> connectionValidation;
        private final Runnable invalidation;
        private volatile boolean invalid;
        Scope(Object issuer, UUID tenantId, Instant observedAt, Runnable validation,
                BiConsumer<DataSource, Connection> connectionValidation, Runnable invalidation) {
            this.issuer=Objects.requireNonNull(issuer); this.tenantId=Objects.requireNonNull(tenantId);
            this.observedAt=Objects.requireNonNull(observedAt); this.validation=Objects.requireNonNull(validation);
            this.connectionValidation=Objects.requireNonNull(connectionValidation); this.invalidation=Objects.requireNonNull(invalidation);
        }
        public UUID tenantId() { require(); return tenantId; }
        public Instant observedAt() { require(); return observedAt; }
        void require() {
            if(invalid) { invalidation.run(); throw SessionSensitiveException.unavailable(); }
            try { validation.run(); }
            catch(RuntimeException|Error failure) { invalidate(); throw failure; }
        }
        void requireConnection(DataSource source, Connection connection) {
            require();
            try { connectionValidation.accept(source,connection); require(); }
            catch(RuntimeException|Error failure) { invalidate(); throw failure; }
        }
        boolean invalid() { return invalid; }
        void invalidate() { invalid=true; invalidation.run(); }
        @Override public String toString() { return "TenantAuditReadScope[REDACTED]"; }
    }
}

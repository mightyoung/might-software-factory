package com.mightyoung.softwarefactory.identity;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Function;
import static com.mightyoung.softwarefactory.identity.SessionSensitiveContracts.Operation;

/** Separate writer and read proofs. Neither port lends connections or accepts caller time. */
public interface SessionSensitiveSecurityTransaction {
    <T> T execute(Scope scope,Function<GuardedBoundary,T> work);
    <T> T executeLogout(LogoutScope scope,Function<LogoutBoundary,T> work);
    void requireLogout(LogoutScope scope,LogoutBoundary boundary,DataSource source,Connection connection);
    <T> T read(CapturedHumanRequest request,Function<ReadSnapshotBoundary,T> work);
    void requireGuarded(Scope scope,GuardedBoundary boundary,DataSource source,Connection connection);
    void requireSnapshot(CapturedHumanRequest request,ReadSnapshotBoundary boundary,DataSource source,Connection connection);
    interface LogoutBoundary { Instant observedAt(); }
    final class LogoutScope {
        private final KnownLineageLogoutCapture request;
        private LogoutScope(KnownLineageLogoutCapture request){this.request=Objects.requireNonNull(request);request.requireUnchanged();}
        public static LogoutScope knownLineage(KnownLineageLogoutCapture request){return new LogoutScope(request);}
        public KnownLineageLogoutCapture request(){return request;}
        @Override public String toString(){return "KnownLineageLogoutScope[REDACTED]";}
    }
    interface GuardedBoundary { Instant observedAt(); }
    interface ReadSnapshotBoundary { Instant observedAt(); }
    final class Scope {
        private final Operation operation;
        private final CapturedHumanRequest request;
        private Scope(Operation operation,CapturedHumanRequest request){
            this.operation=Objects.requireNonNull(operation);this.request=Objects.requireNonNull(request);
            if(operation==Operation.CONTACT_DELIVERY_CHECKPOINT||operation==Operation.LOGOUT)throw new IllegalArgumentException("Operation has a distinct provenance boundary");
            request.requireCurrent();
        }
        public static Scope human(Operation operation,CapturedHumanRequest request){return new Scope(operation,request);}
        public Operation operation(){return operation;}
        public CapturedHumanRequest request(){return request;}
        @Override public String toString(){return "SensitiveSecurityScope[operation="+operation+"]";}
    }
}

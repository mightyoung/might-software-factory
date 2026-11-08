package com.mightyoung.softwarefactory.identity;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/** Verifies an existing caller transaction; never opens a transaction or acquires a guard. */
public interface HumanSessionAuthorityTransaction {
    enum Guard { FINDING_COMMAND, GLOBAL_SECURITY }
    record WriteScope(CapturedHumanRequest request,Guard guard,List<UUID> declaredTenants) {
        public WriteScope {
            Objects.requireNonNull(request);Objects.requireNonNull(guard);
            declaredTenants=List.copyOf(declaredTenants);request.requireCurrent();
            if(declaredTenants.isEmpty()||declaredTenants.size()>32||declaredTenants.stream().distinct().count()!=declaredTenants.size()
                    ||!declaredTenants.contains(request.context().tenantId())
                    ||(guard==Guard.FINDING_COMMAND&&declaredTenants.size()!=1))throw SessionSensitiveException.unavailable();
        }
        @Override public String toString(){return "HumanCallerWriteScope[guard="+guard+"]";}
    }
    interface GuardedBoundary { Instant observedAt(); }
    interface ReadBoundary { Instant observedAt(); }
    <T>T withGuarded(WriteScope scope,Function<GuardedBoundary,T> work);
    <T>T withRead(CapturedHumanRequest request,Function<ReadBoundary,T> work);
    void requireGuarded(WriteScope scope,GuardedBoundary boundary,DataSource source,Connection connection);
    void requireRead(CapturedHumanRequest request,ReadBoundary boundary,DataSource source,Connection connection);
}

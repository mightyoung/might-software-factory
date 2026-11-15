package com.mightyoung.softwarefactory.authorization;

import com.mightyoung.softwarefactory.identity.CapturedHumanRequest;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import com.mightyoung.softwarefactory.identity.HumanSessionAuthorityTransaction;
import java.sql.Connection;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/** Neutral application authority uses the actual identity-owned borrowed transaction and existing RBAC. */
public interface ApplicationActionAuthorization {
    Decision requireRead(CapturedHumanRequest request, HumanSessionAuthorityTransaction.ReadBoundary boundary, ApplicationEntityAction action);
    Decision requireCommand(HumanSessionAuthorityTransaction.WriteScope scope, HumanSessionAuthorityTransaction.GuardedBoundary boundary, ApplicationEntityAction action);
    void requireSqlConnection(Decision decision, DataSource source, Connection connection);
    final class Decision {
        final Object issuer;
        final Runnable validation;
        private final CurrentTenantContext context;
        private final ApplicationEntityAction action;
        private final Instant observedAt;
        private final Set<UUID> organizationIds;
        Decision(Object issuer, Runnable validation, CurrentTenantContext context, ApplicationEntityAction action, Instant observedAt, Set<UUID> organizationIds) {
            this.issuer=issuer; this.validation=validation; this.context=context; this.action=action;
            this.observedAt=observedAt; this.organizationIds=Set.copyOf(organizationIds);
        }
        public CurrentTenantContext context() { validation.run(); return context; }
        public ApplicationEntityAction action() { validation.run(); return action; }
        public Instant observedAt() { validation.run(); return observedAt; }
        public Set<UUID> organizationIds() { validation.run(); return organizationIds; }
        @Override public String toString() { return "ApplicationActionDecision[REDACTED]"; }
    }
}

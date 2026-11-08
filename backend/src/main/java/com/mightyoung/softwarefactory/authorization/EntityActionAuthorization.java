package com.mightyoung.softwarefactory.authorization;

import com.mightyoung.softwarefactory.identity.CapturedHumanRequest;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import com.mightyoung.softwarefactory.identity.HumanSessionAuthorityTransaction;
import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Action authority joins an actual identity-owned borrowed callback; context IDs alone are insufficient. */
public interface EntityActionAuthorization {
    ActionScope requireRead(CapturedHumanRequest request, HumanSessionAuthorityTransaction.ReadBoundary boundary,
                            RegisteredEntityAction action);
    ActionScope requireCommand(HumanSessionAuthorityTransaction.WriteScope source,
                              HumanSessionAuthorityTransaction.GuardedBoundary boundary, RegisteredEntityAction action);
    FinalActionScopes requireFinalCommand(HumanSessionAuthorityTransaction.WriteScope source,
                                         HumanSessionAuthorityTransaction.GuardedBoundary boundary,
                                         Set<RegisteredEntityAction> actions);
    void requireCurrent(ActionScope scope);
    void requireSqlConnection(ActionScope scope, DataSource source, Connection connection);

    final class ActionScope {
        final Object issuer;
        final Runnable validation;
        private final CurrentTenantContext context;
        private final RegisteredEntityAction action;
        private final Instant observedAt;
        private final Set<UUID> organizations;
        ActionScope(Object issuer, Runnable validation, CurrentTenantContext context, RegisteredEntityAction action,
                    Instant observedAt, Set<UUID> organizations) {
            this.issuer=issuer; this.validation=validation; this.context=context; this.action=action;
            this.observedAt=observedAt; this.organizations=Set.copyOf(organizations);
        }
        public CurrentTenantContext context() { validation.run(); return context; }
        public RegisteredEntityAction action() { validation.run(); return action; }
        public Instant observedAt() { validation.run(); return observedAt; }
        public Set<UUID> organizationIds() { validation.run(); return organizations; }
        @Override public String toString() { return "EntityActionScope[REDACTED]"; }
    }
    final class FinalActionScopes {
        private final Map<RegisteredEntityAction,ActionScope> actions;
        private final Instant observedAt;
        FinalActionScopes(Map<RegisteredEntityAction,ActionScope> actions, Instant observedAt) {
            this.actions=Map.copyOf(actions); this.observedAt=observedAt;
        }
        public ActionScope require(RegisteredEntityAction action) {
            ActionScope scope=actions.get(action);
            if(scope==null) throw new org.springframework.security.access.AccessDeniedException("Action was not validated");
            scope.validation.run(); return scope;
        }
        public Instant observedAt() { actions.values().forEach(s -> s.validation.run()); return observedAt; }
        @Override public String toString() { return "FinalEntityActionScopes[REDACTED]"; }
    }
}

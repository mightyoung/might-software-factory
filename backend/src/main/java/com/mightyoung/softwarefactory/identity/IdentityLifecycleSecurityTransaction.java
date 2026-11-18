package com.mightyoung.softwarefactory.identity;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import static com.mightyoung.softwarefactory.identity.IdentityLifecycleContracts.*;

/** Global security writer; the source-session phase is closed before target membership SQL. */
public interface IdentityLifecycleSecurityTransaction {
    <T> T execute(Scope scope,Supplier<T> work);
    void requireActive(Scope scope);
    /** Assertion only: owning DAL code obtains its own bound connection; this port lends none. */
    void requireSqlConnection(Scope scope,javax.sql.DataSource source,java.sql.Connection connection);

    final class Scope {
        private final Operation operation;
        private final UUID challengeId,targetTenantId;
        private final IdentityLifecycleSession.Captured source;
        private Scope(Operation operation,UUID challengeId,UUID targetTenantId,IdentityLifecycleSession.Captured source) {
            required(operation,challengeId);this.operation=operation;this.challengeId=challengeId;this.targetTenantId=targetTenantId;this.source=source;
            boolean global=operation==Operation.CONSUME_RESET||operation==Operation.REQUEST_RESET;
            if(global && (targetTenantId!=null||source!=null))throw invalid();
            if(!global&&operation!=Operation.DELIVERY_CHECKPOINT&&targetTenantId==null)throw invalid();
            if(!global&&operation!=Operation.CONSUME_INVITATION&&operation!=Operation.DELIVERY_CHECKPOINT&&source==null)throw invalid();
            if(source!=null&&operation!=Operation.CONSUME_INVITATION&&!source.context().tenantId().equals(targetTenantId))throw invalid();
        }
        public static Scope session(Operation operation,UUID challengeId,UUID targetTenantId,IdentityLifecycleSession.Captured source) {
            required(source);return new Scope(operation,challengeId,targetTenantId,source);
        }
        public static Scope capability(Operation operation,UUID challengeId,UUID targetTenantId) {
            if(operation!=Operation.CONSUME_RESET&&operation!=Operation.CONSUME_INVITATION)throw invalid();
            return new Scope(operation,challengeId,targetTenantId,null);
        }
        /** A server-generated operation ID; no account, tenant or authenticated subject is invented. */
        public static Scope resetRequest(UUID operationId){return new Scope(Operation.REQUEST_RESET,operationId,null,null);}
        public static Scope deliveryObservation(UUID challengeId,UUID targetTenantId){return new Scope(Operation.DELIVERY_CHECKPOINT,challengeId,targetTenantId,null);}
        public Operation operation(){return operation;}
        public UUID challengeId(){return challengeId;}
        public UUID targetTenantId(){return targetTenantId;}
        public IdentityLifecycleSession.Captured sourceSession(){return source;}
        public Collection<UUID> affectedTenants(){
            var ids=new LinkedHashSet<UUID>();if(source!=null)ids.add(source.context().tenantId());if(targetTenantId!=null)ids.add(targetTenantId);
            return List.copyOf(ids);
        }
        @Override public String toString(){return "LifecycleSecurityScope[operation="+operation+"]";}
    }
}

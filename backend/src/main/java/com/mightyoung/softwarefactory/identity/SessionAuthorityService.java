package com.mightyoung.softwarefactory.identity;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import static com.mightyoung.softwarefactory.identity.SessionSensitiveContracts.*;

/** Not a web endpoint or activation switch. Every public operation owns a top-level boundary. */
public final class SessionAuthorityService {
    private final SessionSensitiveRequests requests;
    private final KnownLogoutRequests logoutRequests;
    private final SessionSensitiveSecurityTransaction boundaries;
    private final SessionAuthorityStore store;
    private final SessionSensitiveAudit audit;
    public SessionAuthorityService(SessionSensitiveRequests requests,SessionSensitiveSecurityTransaction boundaries,SessionAuthorityStore store,SessionSensitiveAudit audit,KnownLogoutRequests logoutRequests){
        this.requests=Objects.requireNonNull(requests);this.logoutRequests=Objects.requireNonNull(logoutRequests);this.boundaries=Objects.requireNonNull(boundaries);this.store=Objects.requireNonNull(store);this.audit=Objects.requireNonNull(audit);
    }
    public SessionPage list(HttpServletRequest request,SessionHandle after,int limit){
        CapturedHumanRequest captured=requests.capture(request);
        try{return boundaries.read(captured,boundary->store.readOwnPage(captured,boundary,after,limit));}
        finally{captured.close();}
    }
    public SessionHandle register(HttpServletRequest request,UUID traceId){
        Objects.requireNonNull(traceId);
        return write(request,Operation.REGISTER_SESSION,traceId,(scope,boundary)->{
            CapturedHumanRequest captured=scope.request();
            UUID eventId=UUID.randomUUID();Registration result=store.register(scope,boundary,eventId);
            if(result.created())audit.success(scope,boundary,new SensitiveEvent(eventId,Operation.REGISTER_SESSION,"SUCCESS",null,Provenance.FRESH_SESSION,
                    captured.context().accountId(),captured.context().actorId(),result.handle(),result.handle(),null,null,traceId,boundary.observedAt()));
            return result.handle();
        });
    }
    public void revoke(HttpServletRequest request,RevokeCommand command,UUID traceId){
        Objects.requireNonNull(command);Objects.requireNonNull(traceId);
        write(request,Operation.REVOKE_SESSION,traceId,(scope,boundary)->{
            CapturedHumanRequest captured=scope.request();
            FenceResult result=store.revoke(scope,boundary,command,UUID.randomUUID());
            if(result.created())audit.success(scope,boundary,new SensitiveEvent(result.eventId(),Operation.REVOKE_SESSION,"SUCCESS",null,Provenance.FRESH_SESSION,
                    captured.context().accountId(),captured.context().actorId(),result.source(),result.target(),command.proofId(),null,traceId,result.occurredAt()));
            return null;
        });
    }
    /** Exact CSRF-protected POST coordinator calls this before framework cleanup. */
    public LogoutOutcome logout(HttpServletRequest request,UUID traceId,Runnable localCleanup){
        Objects.requireNonNull(traceId);Objects.requireNonNull(localCleanup);
        RevocationState state=RevocationState.UNKNOWN;boolean failureAttempted=false;
        try(var captured=logoutRequests.capture(request)){
            var scope=SessionSensitiveSecurityTransaction.LogoutScope.knownLineage(captured);
            state=boundaries.executeLogout(scope,boundary->{
                SessionLogoutResult result=store.logout(scope,boundary,UUID.randomUUID());
                store.requireLogoutLookup(scope,boundary,result);
                if(result.absent())return RevocationState.LOCAL_ONLY;
                FenceResult fence=result.fence();LogoutOwnership owner=result.ownership();
                if(fence.created())audit.logoutSuccess(scope,boundary,owner,new SensitiveEvent(fence.eventId(),Operation.LOGOUT,"SUCCESS",null,Provenance.KNOWN_LINEAGE,
                        owner.accountId(),owner.actorId(),fence.source(),fence.target(),null,null,traceId,fence.occurredAt()));
                return RevocationState.CONFIRMED;
            });
            // An absence decision is usable only after the owning transaction returned normally.
            if(state==RevocationState.LOCAL_ONLY){failureAttempted=true;failureAudit(Operation.LOGOUT,traceId,"REJECTED","NO_TRUSTED_LINEAGE");}
        }catch(RuntimeException uncertain){
            state=RevocationState.UNKNOWN;
            if(!failureAttempted)try{failureAudit(Operation.LOGOUT,traceId,"UNKNOWN","UNAVAILABLE");}catch(RuntimeException unavailable){/* remains UNKNOWN */}
        }
        if(state==RevocationState.UNKNOWN)return new LogoutOutcome(state,false);
        boolean cleaned;
        try{localCleanup.run();cleaned=true;}catch(RuntimeException cleanupFailure){cleaned=false;}
        return new LogoutOutcome(state,cleaned);
    }
    private void failureAudit(Operation operation,UUID traceId,String result,String reason){
        audit.failure(new SensitiveEvent(UUID.randomUUID(),operation,result,reason,Provenance.ANONYMOUS,
                null,null,null,null,null,null,traceId,Instant.now()));
    }
    private <T>T write(HttpServletRequest request,Operation operation,UUID traceId,
                      BiFunction<SessionSensitiveSecurityTransaction.Scope,SessionSensitiveSecurityTransaction.GuardedBoundary,T> work){
        CapturedHumanRequest captured=null;AtomicBoolean completed=new AtomicBoolean();
        try {
            try{captured=requests.capture(request);}catch(UnauthenticatedSessionException unauthenticated){throw SessionSensitiveException.rejected();}
            var scope=SessionSensitiveSecurityTransaction.Scope.human(operation,captured);
            return boundaries.execute(scope,boundary->{
                T result=work.apply(scope,boundary);completed.set(true);return result;
            });
        }catch(RuntimeException failure){
            SessionSensitiveException safe=completed.get()?SessionSensitiveException.unavailable():
                    failure instanceof SessionSensitiveException known?known:SessionSensitiveException.unavailable();
            boolean unknown=completed.get()||safe.reason()==SessionSensitiveException.Reason.UNAVAILABLE;
            try {
                // Capture alone does not prove current SQL authority or a durable lineage. Keep
                // nullable identity fields empty rather than upgrading an old principal to fresh.
                audit.failure(new SensitiveEvent(UUID.randomUUID(),operation,unknown?"UNKNOWN":"REJECTED",safe.reason().name(),
                        Provenance.ANONYMOUS,null,null,null,null,null,null,traceId,Instant.now()));
            }catch(RuntimeException auditUnavailable){throw SessionSensitiveException.unavailable();}
            throw safe;
        }finally{if(captured!=null)captured.close();}
    }
    public record LogoutOutcome(RevocationState revocationState,boolean localCleanupComplete){
        public LogoutOutcome{Objects.requireNonNull(revocationState);}
    }
}

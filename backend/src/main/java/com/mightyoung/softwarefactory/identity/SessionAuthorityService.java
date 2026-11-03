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
    private final SessionSensitiveSecurityTransaction boundaries;
    private final SessionAuthorityStore store;
    private final SessionSensitiveAudit audit;
    public SessionAuthorityService(SessionSensitiveRequests requests,SessionSensitiveSecurityTransaction boundaries,SessionAuthorityStore store,SessionSensitiveAudit audit){
        this.requests=Objects.requireNonNull(requests);this.boundaries=Objects.requireNonNull(boundaries);this.store=Objects.requireNonNull(store);this.audit=Objects.requireNonNull(audit);
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
    /** Must be called by the exact POST logout handler AFTER actual CSRF and BEFORE local cleanup. */
    public LogoutOutcome logout(HttpServletRequest request,UUID traceId,Runnable localCleanup){
        Objects.requireNonNull(traceId);Objects.requireNonNull(localCleanup);
        RevocationState state;
        try {
            write(request,Operation.LOGOUT,traceId,(scope,boundary)->{
                CapturedHumanRequest source=scope.request();
                FenceResult result=store.logout(scope,boundary,UUID.randomUUID());
                if(result.created())audit.success(scope,boundary,new SensitiveEvent(result.eventId(),Operation.LOGOUT,"SUCCESS",null,Provenance.KNOWN_LINEAGE,
                        source.context().accountId(),source.context().actorId(),result.source(),result.target(),null,null,traceId,result.occurredAt()));
                return null;
            });
            // Only a normal return from the transaction establishes known committed fencing.
            state=RevocationState.CONFIRMED;
        }catch(SessionSensitiveException known){
            state=known.reason()==SessionSensitiveException.Reason.NO_TRUSTED_LINEAGE?RevocationState.LOCAL_ONLY:RevocationState.UNKNOWN;
        }
        if(state==RevocationState.UNKNOWN)return new LogoutOutcome(state,false);
        boolean cleaned;
        try{localCleanup.run();cleaned=true;}catch(RuntimeException cleanupFailure){cleaned=false;}
        return new LogoutOutcome(state,cleaned);
    }
    private <T>T write(HttpServletRequest request,Operation operation,UUID traceId,
                      BiFunction<SessionSensitiveSecurityTransaction.Scope,SessionSensitiveSecurityTransaction.GuardedBoundary,T> work){
        CapturedHumanRequest captured=null;AtomicBoolean completed=new AtomicBoolean();
        try {
            try{captured=requests.capture(request);}
            catch(UnauthenticatedSessionException noTrustedCapture){
                throw new SessionSensitiveException(operation==Operation.LOGOUT?SessionSensitiveException.Reason.NO_TRUSTED_LINEAGE:SessionSensitiveException.Reason.REJECTED);
            }catch(SessionSensitiveException captureFailure){
                if(operation==Operation.LOGOUT&&(captureFailure.reason()==SessionSensitiveException.Reason.REJECTED
                        ||captureFailure.reason()==SessionSensitiveException.Reason.NO_TRUSTED_LINEAGE))
                    throw new SessionSensitiveException(SessionSensitiveException.Reason.NO_TRUSTED_LINEAGE);
                throw captureFailure;
            }
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

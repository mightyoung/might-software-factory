package com.mightyoung.softwarefactory.identity;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Expensive password work precedes the top-level global security transaction. */
@Service
public final class PasswordChangeService {
    private final SessionContextResolver contexts;
    private final CurrentSessionService sessions;
    private final PasswordCredentialStore store;
    private final PasswordEncoder encoder;
    private final IdentitySecurityTransaction transaction;
    private final IdentitySecurityAudit audit;
    private final IdentitySecurityEvents events;
    private final LoginAttemptLimiter attempts;
    private final Clock clock;
    private final java.util.concurrent.Semaphore expensiveWork = new java.util.concurrent.Semaphore(8);
    public PasswordChangeService(SessionContextResolver contexts,CurrentSessionService sessions,
            PasswordCredentialStore store,PasswordEncoder encoder,IdentitySecurityTransaction transaction,
            IdentitySecurityAudit audit,IdentitySecurityEvents events,
            @Qualifier("loginAttemptLimiter") LoginAttemptLimiter attempts,ObjectProvider<Clock> clocks) {
        this.contexts=contexts;this.sessions=sessions;this.store=store;this.encoder=encoder;
        this.transaction=transaction;this.audit=audit;this.events=events;this.attempts=attempts;
        this.clock=clocks.getIfAvailable(Clock::systemUTC);
    }
    public void change(PasswordChangeRequest request,UUID traceId) {
        try(request) { change(()->request,traceId); }
    }

    /** Admission precedes parsing and all persistence; rejected traffic never appends an event. */
    public void change(java.util.function.Supplier<PasswordChangeRequest> lazyParser,UUID traceId) {
        CurrentTenantContext failureContext=null;
        boolean admitted=false;
        AtomicBoolean callbackCompleted=new AtomicBoolean();
        try {
            final CurrentTenantContext context=contexts.currentTenant();
            failureContext=context;
            String accountBudget="password-change:"+context.accountId();
            attempts.admitSource(accountBudget); // stable account-wide budget cannot be split by changing IP
            try(var attempt=attempts.reserve(accountBudget,context.accountId().toString())) {
                admitted=true;
                try(var request=lazyParser.get()) {
                    validate(request);
                    revalidate(context);
                    PasswordCredentialState previous=store.findOwn(context).orElseThrow(PasswordChangeService::stale);
                    if(previous.credentialVersion()!=context.credentialVersion())throw stale();
                    Instant preparedAt=clock.instant();
                    if(!expensiveWork.tryAcquire())throw new PasswordChangeException(PasswordChangeFailureReason.RATE_LIMITED,429,1);
                    String newHash;
                    try {
                        if(!encoder.matches(request.currentPassword(),previous.encodedPassword())) {
                            attempt.badCredentials();
                            throw new PasswordChangeException(PasswordChangeFailureReason.CURRENT_PASSWORD_REJECTED,403);
                        }
                        newHash=encoder.encode(request.newPassword()); // never under the global lock
                    } finally { expensiveWork.release(); }
                    Prepared prepared=new Prepared(context,previous,newHash,preparedAt);
                    transaction.execute(context,()->{
                        Instant now=clock.instant();
                        if(prepared.createdAt.isAfter(now)||Duration.between(prepared.createdAt,now).compareTo(Duration.ofSeconds(60))>0)
                            throw new PasswordChangeException(PasswordChangeFailureReason.PREPARATION_EXPIRED,409);
                        if(!prepared.context.equals(contexts.currentTenant()))throw stale();
                        revalidate(context);
                        PasswordCredentialState actual=store.findOwn(context).orElseThrow(PasswordChangeService::stale);
                        if(!actual.equals(prepared.previous))throw stale();
                        store.replace(context,prepared.previous,prepared.encodedPassword,now);
                        try { audit.passwordChanged(context,previous.credentialVersion(),Math.addExact(previous.credentialVersion(),1),traceId,now); }
                        catch(RuntimeException failure){throw new PasswordChangeException(PasswordChangeFailureReason.AUDIT_UNAVAILABLE,503);}
                        callbackCompleted.set(true);
                        return null;
                    });
                }
            }
        } catch(RuntimeException failure) {
            if(failureContext==null)throw failure; // no trusted identity: erase secrets, never invent an event context
            PasswordChangeException safe;
            if(callbackCompleted.get())safe=new PasswordChangeException(PasswordChangeFailureReason.COMMIT_OUTCOME_UNKNOWN,503);
            else if(failure instanceof PasswordChangeException known)safe=known;
            else if(failure instanceof LoginRateLimitedException limited)safe=new PasswordChangeException(PasswordChangeFailureReason.RATE_LIMITED,429,limited.retryAfterSeconds());
            else if(failure instanceof AccessDeniedException||failure instanceof UnauthenticatedSessionException)safe=stale();
            else safe=new PasswordChangeException(PasswordChangeFailureReason.WRITE_FAILED,503);
            if(admitted)recordFailure(failureContext,safe.reason(),traceId);
            throw safe;
        }
    }
    private void recordFailure(CurrentTenantContext context,PasswordChangeFailureReason reason,UUID traceId) {
        try { events.passwordChangeFailed(context,reason,traceId,clock.instant()); }
        catch(RuntimeException unavailable){throw new PasswordChangeException(PasswordChangeFailureReason.WRITE_FAILED,503);}
    }
    private void revalidate(CurrentTenantContext context) {
        if(!context.equals(contexts.currentTenant()))throw stale();
        sessions.current();
    }
    private static PasswordChangeException stale(){return new PasswordChangeException(PasswordChangeFailureReason.STALE_IDENTITY,401);}
    private static void validate(PasswordChangeRequest request) {
        if(!validSecret(request.currentPassword(),1)||!validSecret(request.newPassword(),15)
                ||CharBuffer.wrap(request.currentPassword()).equals(CharBuffer.wrap(request.newPassword())))throw PasswordChangeRequestParser.invalid();
    }
    private static boolean validSecret(CharSequence secret,int minimum) {
        try {
            var bytes=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(secret));
            try { return bytes.remaining()>=1&&bytes.remaining()<=72&&Character.codePointCount(secret,0,secret.length())>=minimum; }
            finally { if(bytes.hasArray())Arrays.fill(bytes.array(),(byte)0); }
        } catch(java.nio.charset.CharacterCodingException failure){return false;}
    }
    private record Prepared(CurrentTenantContext context,PasswordCredentialState previous,String encodedPassword,Instant createdAt) {
        @Override public String toString(){return "PreparedPasswordChange[REDACTED]";}
    }
}

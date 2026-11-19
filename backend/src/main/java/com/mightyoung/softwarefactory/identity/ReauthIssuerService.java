package com.mightyoung.softwarefactory.identity;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.security.crypto.password.PasswordEncoder;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static com.mightyoung.softwarefactory.identity.ReauthIssuerContracts.*;
import static com.mightyoung.softwarefactory.identity.SessionSensitiveContracts.*;

/** Explicit source core only: no HTTP route, default bean or session lifetime change. */
public final class ReauthIssuerService {
    private final SessionSensitiveRequests requests;
    private final SessionSensitiveSecurityTransaction boundaries;
    private final ReauthIssuerStore store;
    private final DataSource source;
    private final SessionSensitiveAudit audit;
    private final PasswordEncoder encoder;
    private final LoginAttemptLimiter account,network;
    private final Clock clock;
    private final SecureRandom random;
    private final Semaphore work=new Semaphore(8);
    private final ThreadLocal<VerifiedPreparation> active=new ThreadLocal<>();
    /**
     * Port-only seam for trusted application assembly. This is not a caller capability;
     * production composition must supply the canonical managed transaction and audit ports.
     */
    public static ReauthIssuerService assembleTrusted(SessionSensitiveRequests requests,DataSource source,SessionSensitiveSecurityTransaction boundaries,
            SessionSensitiveAudit audit,PasswordEncoder encoder,SessionSensitiveKeys keys,LoginAttemptLimiter account,LoginAttemptLimiter network,Clock clock,SecureRandom random){
        return new ReauthIssuerService(requests,source,boundaries,new JooqReauthIssuerStore(source,boundaries,keys),audit,encoder,account,network,clock,random);
    }
    ReauthIssuerService(SessionSensitiveRequests requests,DataSource source,SessionSensitiveSecurityTransaction boundaries,ReauthIssuerStore store,
            SessionSensitiveAudit audit,PasswordEncoder encoder,LoginAttemptLimiter account,LoginAttemptLimiter network,Clock clock,SecureRandom random){
        this.requests=Objects.requireNonNull(requests);this.boundaries=Objects.requireNonNull(boundaries);this.store=Objects.requireNonNull(store);
        this.source=Objects.requireNonNull(source);
        this.audit=Objects.requireNonNull(audit);this.encoder=Objects.requireNonNull(encoder);this.account=Objects.requireNonNull(account);
        this.network=Objects.requireNonNull(network);this.clock=Objects.requireNonNull(clock);this.random=Objects.requireNonNull(random);
        if(account==network)throw new IllegalArgumentException("Reauthentication budgets must be independent");
        store.bindIssuer(this);
    }
    public Outcome issueSessionRevoke(HttpServletRequest request,UUID commandKey,UUID traceId,Request input){
        Objects.requireNonNull(input);
        CapturedHumanRequest captured=null;IdentityLifecycleContracts.OneUseSecret secret=null;
        boolean transferred=false,admitted=false;AtomicBoolean bodyReturned=new AtomicBoolean();
        try(input){
            if(commandKey==null||traceId==null)throw SessionSensitiveException.rejected();
            if(TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isSynchronizationActive()
                    ||!TransactionSynchronizationManager.getResourceMap().isEmpty()){
                rejectActivePreparation();throw SessionSensitiveException.rejected();
            }
            captured=requests.capture(request);var context=captured.context();String budget="reauth:"+context.accountId();String peer=request.getRemoteAddr();
            account.admitSource(budget);network.admitSource(peer);
            ReauthIssuerStore.Stored stored;
            VerifiedPreparation issuedPreparation;
            try(var accountAttempt=account.reserve(budget,context.accountId().toString());var networkAttempt=network.reserve(peer,context.accountId().toString())){
                admitted=true;validatePassword(input.password());captured.requireCurrent();
                var prepared=store.readCredential(context).orElseThrow(SessionSensitiveException::rejected);
                if(prepared.credentialVersion()!=context.credentialVersion())throw SessionSensitiveException.rejected();
                if(!prepared.encodedPassword().matches("\\{bcrypt\\}\\$2[aby]\\$1[2-6]\\$[./A-Za-z0-9]{53}"))throw SessionSensitiveException.rejected();
                var preparedAt=clock.instant();
                if(!work.tryAcquire())throw SessionSensitiveException.unavailable();
                try{
                    if(!encoder.matches(CharBuffer.wrap(input.password()),prepared.encodedPassword())){
                        accountAttempt.badCredentials();networkAttempt.badCredentials();throw SessionSensitiveException.rejected();
                    }
                }finally{work.release();}
                input.close();captured.requireCurrent();
                secret=IdentityLifecycleContracts.OneUseSecret.generate(random);
                var scope=SessionSensitiveSecurityTransaction.Scope.human(Operation.ISSUE_REAUTH,captured);
                var preparation=new VerifiedPreparation(this,scope,input.target(),input.expected(),commandKey,prepared,preparedAt,secret.digest());
                issuedPreparation=preparation;
                stored=boundaries.execute(scope,boundary->{
                    try{
                        preparation.open(boundary);
                        var result=store.issue(scope,boundary,preparation);
                        preparation.requireComplete(result);
                        if(result.created())audit.success(scope,boundary,new SensitiveEvent(result.eventId(),Operation.ISSUE_REAUTH,"SUCCESS",null,
                                Provenance.FRESH_SESSION,context.accountId(),context.actorId(),result.source(),null,result.metadata().proofId(),null,traceId,result.issuedAt()));
                        preparation.requireComplete(result);bodyReturned.set(true);return result;
                    }catch(Throwable failure){
                        preparation.fail();throw failure instanceof SessionSensitiveException known?known:SessionSensitiveException.unavailable();
                    }finally{preparation.closeWindow();}
                });
            }
            if(stored==null)throw SessionSensitiveException.unavailable();
            issuedPreparation.requireReturnedResult(stored);
            captured.requireCurrent();
            captured.close();captured=null;
            if(!stored.created())return new Replay(stored.metadata());
            Outcome result=new Issued(stored.metadata(),secret);transferred=true;return result;
        }catch(Throwable failure){
            SessionSensitiveException safe=bodyReturned.get()?SessionSensitiveException.unavailable():
                    failure instanceof SessionSensitiveException known?known:failure instanceof UnauthenticatedSessionException?
                            SessionSensitiveException.rejected():SessionSensitiveException.unavailable();
            if(admitted){
                try{audit.failure(new SensitiveEvent(UUID.randomUUID(),Operation.ISSUE_REAUTH,
                        bodyReturned.get()||safe.reason()==SessionSensitiveException.Reason.UNAVAILABLE?"UNKNOWN":"REJECTED",safe.reason().name(),
                        Provenance.ANONYMOUS,null,null,null,null,null,null,traceId,clock.instant()));}
                catch(Throwable ignored){throw SessionSensitiveException.unavailable();}
            }
            throw safe;
        }finally{
            if(!transferred&&secret!=null)secret.close();
            if(captured!=null)try{captured.close();}catch(Throwable failure){throw SessionSensitiveException.unavailable();}
        }
    }
    void rejectActivePreparation(){VerifiedPreparation value=active.get();if(value!=null)value.fail();}
    /** Password verification evidence. Only the successful matches branch above can construct it. */
    static final class VerifiedPreparation {
        private final ReauthIssuerService issuer;
        private final SessionSensitiveSecurityTransaction.Scope scope;
        private final CapturedHumanRequest captured;
        private final CurrentTenantContext context;
        private final Thread thread=Thread.currentThread();
        private final SessionHandle target;
        private final ExpectedAuthority expected;
        private final UUID commandKey,proofId=UUID.randomUUID(),receiptId=UUID.randomUUID(),eventId=UUID.randomUUID();
        private final PasswordCredentialState credential;
        private final Instant preparedAt;
        private final String secretDigest;
        private SessionSensitiveSecurityTransaction.GuardedBoundary boundary;
        private ConnectionHolder holder;
        private Connection connection;
        private volatile boolean rejected,closed;
        private boolean open,claimed;
        private ReauthIssuerStore.Stored completed;
        private VerifiedPreparation(ReauthIssuerService issuer,SessionSensitiveSecurityTransaction.Scope scope,SessionHandle target,
                ExpectedAuthority expected,UUID commandKey,PasswordCredentialState credential,Instant preparedAt,String secretDigest){
            this.issuer=issuer;this.scope=scope;captured=scope.request();context=captured.context();this.target=target;this.expected=expected;
            this.commandKey=commandKey;this.credential=credential;this.preparedAt=preparedAt;this.secretDigest=secretDigest;
        }
        private synchronized void open(SessionSensitiveSecurityTransaction.GuardedBoundary boundary){
            if(closed||open||rejected||thread!=Thread.currentThread()||issuer.active.get()!=null)throw reject();
            if(!(TransactionSynchronizationManager.getResource(issuer.source) instanceof ConnectionHolder current))throw reject();
            holder=current;connection=current.getConnection();this.boundary=boundary;
            issuer.boundaries.requireGuarded(scope,boundary,issuer.source,connection);
            issuer.active.set(this);open=true;requireLive(issuer,scope,boundary);
        }
        synchronized void claimFor(ReauthIssuerService owner,SessionSensitiveSecurityTransaction.Scope candidate,
                SessionSensitiveSecurityTransaction.GuardedBoundary boundary){
            requireLive(owner,candidate,boundary);
            if(claimed||completed!=null)throw reject();claimed=true;
        }
        synchronized void completeFor(ReauthIssuerService owner,SessionSensitiveSecurityTransaction.Scope candidate,
                SessionSensitiveSecurityTransaction.GuardedBoundary boundary,ReauthIssuerStore.Stored result){
            requireLive(owner,candidate,boundary);
            if(!claimed||completed!=null||result==null)throw reject();completed=result;
        }
        private synchronized void requireComplete(ReauthIssuerStore.Stored result){
            requireLive(issuer,scope,boundary);
            if(!claimed||completed!=result)throw reject();
        }
        private synchronized void requireReturnedResult(ReauthIssuerStore.Stored result){
            if(thread!=Thread.currentThread()||!closed||open||rejected||!claimed||completed!=result)throw SessionSensitiveException.unavailable();
        }
        private void requireLive(ReauthIssuerService owner,SessionSensitiveSecurityTransaction.Scope candidate,
                SessionSensitiveSecurityTransaction.GuardedBoundary proof){
            if(closed)throw SessionSensitiveException.rejected();
            try{
            if(owner!=issuer||candidate!=scope||proof!=boundary||thread!=Thread.currentThread()||!open||rejected
                    ||issuer.active.get()!=this||scope.request()!=captured||captured.context()!=context
                    ||TransactionSynchronizationManager.getResource(issuer.source)!=holder||holder==null||holder.getConnection()!=connection||holder.isRollbackOnly())throw reject();
            captured.requireCurrent();issuer.boundaries.requireGuarded(scope,boundary,issuer.source,connection);
            }
            catch(Throwable failure){throw reject();}
        }
        private synchronized void requireClaimed(){requireLive(issuer,scope,boundary);if(!claimed||completed!=null)throw reject();}
        synchronized void fail(){
            if(closed)return;
            rejected=true;
            if(thread==Thread.currentThread()&&holder!=null)holder.setRollbackOnly();
        }
        private SessionSensitiveException reject(){fail();return SessionSensitiveException.rejected();}
        private synchronized void closeWindow(){
            closed=true;open=false;if(issuer.active.get()==this)issuer.active.remove();
        }
        SessionHandle target(){requireClaimed();return target;}
        ExpectedAuthority expected(){requireClaimed();return expected;}
        UUID commandKey(){requireClaimed();return commandKey;}
        PasswordCredentialState credential(){requireClaimed();return credential;}
        Instant preparedAt(){requireClaimed();return preparedAt;}
        String secretDigest(){requireClaimed();return secretDigest;}
        UUID proofId(){requireClaimed();return proofId;}
        UUID receiptId(){requireClaimed();return receiptId;}
        UUID eventId(){requireClaimed();return eventId;}
        @Override public String toString(){return "VerifiedReauthPreparation[REDACTED]";}
    }
    private static void validatePassword(char[] password){
        java.nio.ByteBuffer bytes=null;
        try{
            bytes=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(password));
            if(password.length<1||bytes.remaining()>72)throw SessionSensitiveException.rejected();
        }catch(java.nio.charset.CharacterCodingException failure){throw SessionSensitiveException.rejected();}
        finally{if(bytes!=null&&bytes.hasArray())java.util.Arrays.fill(bytes.array(),(byte)0);}
    }
}

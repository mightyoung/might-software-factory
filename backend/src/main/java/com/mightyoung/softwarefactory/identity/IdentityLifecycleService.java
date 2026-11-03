package com.mightyoung.softwarefactory.identity;

import org.springframework.security.crypto.password.PasswordEncoder;
import java.nio.CharBuffer;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import static com.mightyoung.softwarefactory.identity.IdentityLifecycleContracts.*;
import static com.mightyoung.softwarefactory.identity.IdentityLifecycleAuthorization.Action;

/** Domain orchestration only. HTTP/session cleanup and actual delivery acceptance are separate gates. */
public final class IdentityLifecycleService implements AutoCloseable {
    private final IdentityLifecycleStore store;
    private final IdentityLifecycleSecurityTransaction writer;
    private final IdentityLifecycleAuthorization authorization;
    private final InvitationMembershipPort memberships;
    private final IdentityLifecycleAudit audit;
    private final IdentityLifecycleSession sessions;
    private final CredentialCapabilityDeliveryPort delivery;
    private final IdentityLifecycleProperties policy;
    private final PasswordEncoder passwords;
    private final Clock clock;
    private final IdentityLifecycleAdmission admission;
    private final IdentityCapabilityMemoryDispatcher dispatcher=new IdentityCapabilityMemoryDispatcher();
    private final Semaphore expensive=new Semaphore(4);
    private final SecureRandom random=new SecureRandom();
    private final UUID instanceId=UUID.randomUUID();
    private final IdentityLifecycleDiagnostics diagnostics=new IdentityLifecycleDiagnostics();

    public IdentityLifecycleService(IdentityLifecycleStore store,IdentityLifecycleSecurityTransaction writer,
            IdentityLifecycleAuthorization authorization,InvitationMembershipPort memberships,IdentityLifecycleAudit audit,
            IdentityLifecycleSession sessions,CredentialCapabilityDeliveryPort delivery,IdentityLifecycleProperties policy,
            PasswordEncoder passwords,Clock clock) {
        this.store=store;this.writer=writer;this.authorization=authorization;this.memberships=memberships;
        this.audit=audit;this.sessions=sessions;this.delivery=delivery;this.policy=policy;this.passwords=passwords;this.clock=clock;
        policy.validatePolicy();admission=new IdentityLifecycleAdmission(clock,policy.getRequestsPerSourceMinute(),policy.getRequestsGlobalMinute(),policy.getSourceCapacity());
    }

    /** Source is supplied by a trusted transport adapter, before its lazy parser is invoked. */
    public InvitationResult invite(String source,Supplier<InvitationCommand> parser,UUID traceId) {
        return safe(traceId,()->inviteInternal(source,parser,traceId));
    }
    private InvitationResult inviteInternal(String source,Supplier<InvitationCommand> parser,UUID traceId) {
        try(var admitted=admit(source)) {
            InvitationCommand command=parser.get();required(traceId,command);
            var session=sessions.capture();IssuerBinding issuer=IssuerBinding.from(session.context());
            var fingerprint=policy.crypto().prepare(Operation.ISSUE_INVITATION,issuer.tenantId(),issuer.accountId(),issuer.actorId(),issuer.memberId(),
                    command.purpose(),command.targetAccountId(),command.loginKey(),command.displayName(),command.destination().channel(),command.destination().value());
            var known=store.readReceipt(Operation.ISSUE_INVITATION,OwnerKind.SESSION,issuer.actorId(),command.commandKey());
            UUID id=known.map(Receipt::challengeId).orElseGet(UUID::randomUUID);
            var scope=IdentityLifecycleSecurityTransaction.Scope.session(Operation.ISSUE_INVITATION,id,issuer.tenantId(),session);
            reserveBudget("ACCOUNT",issuer.accountId().toString(),20);
            if(known.isPresent())return write(scope,traceId,known.get().challengeId(),()->{
                authorize(scope,issuer,Action.CREATE);return invitationReplay(scope,command.commandKey(),issuer.actorId(),fingerprint);
            });
            var ready=readiness();
            try(var ticket=dispatcher.reserve();var secret=OneUseSecret.generate(random)) {
                Pending pending=write(scope,traceId,null,()->{
                    authorize(scope,issuer,Action.CREATE);
                    var replay=store.receipt(scope,scope.operation(),OwnerKind.SESSION,issuer.actorId(),command.commandKey());
                    if(replay.isPresent())return new Pending(result(replay.get(),fingerprint,true),null);
                    Instant now=store.databaseNow(scope);
                    RecoveryBinding contact=null;
                    if(command.purpose()==Purpose.NEW_ACCOUNT) {
                        if(store.loginExists(scope,command.loginKey()))throw rejected();
                    } else {
                        AccountSnapshot account=activeAccount(scope,command.targetAccountId());
                        contact=store.recovery(scope,account.accountId()).orElseThrow(IdentityLifecycleService::rejected);
                        if(!contact.active()||!contact.destination().equals(command.destination())||memberships.find(scope,account.accountId()).isPresent())throw rejected();
                    }
                    ChallengeSnapshot c=new ChallengeSnapshot(id,command.purpose(),command.targetAccountId(),issuer.tenantId(),issuer,
                            command.loginKey(),command.displayName(),command.destination(),contact==null?null:contact.id(),contact==null?null:contact.version(),null,
                            secret.digest(),State.ISSUED,1,now,now.plus(policy.getInvitationTtl()),null);
                    String destinationDigest=policy.crypto().selector("DESTINATION",c.destination().value());
                    store.expireInvitations(scope,c,destinationDigest,now);
                    DeliveryAttempt attempt=attempt(c,ready,now);
                    authorize(scope,issuer,Action.CREATE);
                    store.issue(scope,c,destinationDigest,attempt);
                    Receipt receipt=receipt(scope,OwnerKind.SESSION,issuer.actorId(),command.commandKey(),fingerprint,null,null,null,null,id,1,c.expiresAt(),now);
                    store.receipt(scope,receipt);success(scope,c,null,null,issuer,traceId,now);
                    return new Pending(new InvitationResult(id,1,c.expiresAt(),DeliveryState.PREPARED,false),prepared(c,attempt,secret,ready));
                });
                dispatch(ticket,pending.delivery());return pending.result();
            }
        }
    }

    public InvitationResult revoke(String source,Supplier<MutationCommand> parser,UUID traceId) {
        return safe(traceId,()->mutate(source,parser,traceId,false));
    }
    public InvitationResult reissue(String source,Supplier<MutationCommand> parser,UUID traceId) {
        return safe(traceId,()->mutate(source,parser,traceId,true));
    }
    private InvitationResult mutate(String source,Supplier<MutationCommand> parser,UUID traceId,boolean reissue) {
        try(var admitted=admit(source)) {
            MutationCommand command=parser.get();required(command,traceId);
            var session=sessions.capture();IssuerBinding issuer=IssuerBinding.from(session.context());
            Operation operation=reissue?Operation.REISSUE_INVITATION:Operation.REVOKE_INVITATION;
            var scope=IdentityLifecycleSecurityTransaction.Scope.session(operation,command.challengeId(),issuer.tenantId(),session);
            var fingerprint=policy.crypto().prepare(operation,issuer.tenantId(),issuer.accountId(),issuer.actorId(),issuer.memberId(),command.challengeId(),command.expectedVersion());
            var known=store.readReceipt(operation,OwnerKind.SESSION,issuer.actorId(),command.commandKey());
            reserveBudget("ACCOUNT",issuer.accountId().toString(),20);
            if(known.isPresent())return write(scope,traceId,command.challengeId(),()->{
                ChallengeSnapshot c=store.lockChallenge(scope,command.challengeId());managementAuthority(scope,c,issuer,reissue);
                return invitationReplay(scope,command.commandKey(),issuer.actorId(),fingerprint);
            });
            var ready=reissue?readiness():null;
            try(var ticket=reissue?dispatcher.reserve():null;var secret=reissue?OneUseSecret.generate(random):null) {
                Pending pending=write(scope,traceId,command.challengeId(),()->{
                    ChallengeSnapshot c=store.lockChallenge(scope,command.challengeId());managementAuthority(scope,c,issuer,reissue);
                    var replay=store.receipt(scope,operation,OwnerKind.SESSION,issuer.actorId(),command.commandKey());
                    if(replay.isPresent())return new Pending(result(replay.get(),fingerprint,true),null);
                    Instant now=store.databaseNow(scope);unused(c,now);
                    if(c.version()!=command.expectedVersion())throw conflict();
                    if(!reissue) {
                        authorize(scope,issuer,Action.REVOKE);store.terminal(scope,c,State.REVOKED,null);
                        Receipt receipt=receipt(scope,OwnerKind.SESSION,issuer.actorId(),command.commandKey(),fingerprint,null,null,null,null,c.id(),c.version()+1,c.expiresAt(),now);
                        store.receipt(scope,receipt);success(scope,c,null,null,issuer,traceId,now);
                        return new Pending(result(receipt,fingerprint,false),null);
                    }
                    RecoveryBinding contact=c.purpose()==Purpose.EXISTING_ACCOUNT_MEMBERSHIP?exactContact(scope,c,c.accountId()):null;
                    if(c.purpose()==Purpose.NEW_ACCOUNT&&store.loginExists(scope,c.intendedLogin()))throw rejected();
                    if(contact!=null&&(memberships.find(scope,c.accountId()).isPresent()||!store.activeHuman(scope,c.accountId(),activeAccount(scope,c.accountId()).actorId())))throw rejected();
                    UUID nextId=UUID.randomUUID();
                    ChallengeSnapshot next=new ChallengeSnapshot(nextId,c.purpose(),c.accountId(),c.tenantId(),c.issuer(),c.intendedLogin(),c.displayName(),c.destination(),
                            c.recoveryContactId(),c.contactVersion(),null,secret.digest(),State.ISSUED,1,now,now.plus(policy.getInvitationTtl()),null);
                    DeliveryAttempt attempt=attempt(next,ready,now);
                    managementAuthority(scope,c,issuer,true);store.terminal(scope,c,State.REVOKED,null);
                    store.issue(scope,next,policy.crypto().selector("DESTINATION",next.destination().value()),attempt);
                    Receipt receipt=receipt(scope,OwnerKind.SESSION,issuer.actorId(),command.commandKey(),fingerprint,null,null,null,null,nextId,1,next.expiresAt(),now);
                    store.receipt(scope,receipt);success(scope,c,null,null,issuer,traceId,now);
                    return new Pending(new InvitationResult(nextId,1,next.expiresAt(),DeliveryState.PREPARED,false),prepared(next,attempt,secret,ready));
                });
                dispatch(ticket,pending.delivery());return pending.result();
            }
        }
    }

    /** Uniform acceptance: no existence, destination, reset ID, or delivery state is disclosed. */
    public void requestReset(String source,Supplier<String> parser,UUID traceId) {
        safe(traceId,()->{requestResetInternal(source,parser,traceId);return null;});
    }
    private void requestResetInternal(String source,Supplier<String> parser,UUID traceId) {
        try(var admitted=admit(source)) {
            String login=LocalLoginKey.normalize(parser.get());text(login,200);required(traceId);
            var ready=readiness(); // unavailable delivery denies all selectors before any account lookup
            // Global queue admission precedes every selector-specific lookup or return.
            try(var ticket=dispatcher.reserve()) {
                var hint=store.readLocalAccount(login);
                var decision=budgetDecision(hint.isPresent()?"ACCOUNT":"RESET_SELECTOR",hint.map(a->a.accountId().toString()).orElse(login),5);
                if(decision==IdentityLifecycleStore.RateDecision.CAPACITY)throw IdentityLifecycleException.unavailable();
                if(decision==IdentityLifecycleStore.RateDecision.SELECTOR_LIMIT)return;
                if(hint.isEmpty())return;
                UUID id=UUID.randomUUID();var scope=IdentityLifecycleSecurityTransaction.Scope.resetRequest(id);
                try(var secret=OneUseSecret.generate(random)) {
                    var pending=write(scope,traceId,null,()->{
                        var actual=store.localAccount(scope,hint.get().accountId());
                        if(actual.isEmpty()||!"ACTIVE".equals(actual.get().status())||!actual.get().loginKey().equals(hint.get().loginKey()))return null;
                        var contact=store.recovery(scope,actual.get().accountId());
                        if(contact.isEmpty()||!contact.get().active())return null; // G008 enrollment is a real prerequisite
                        Instant now=store.databaseNow(scope);
                        if(store.liveReset(scope,actual.get().accountId(),now).isPresent())return null;
                        store.expireResets(scope,actual.get().accountId(),now);
                        var c=new ChallengeSnapshot(id,Purpose.PASSWORD_RESET,actual.get().accountId(),null,null,null,null,contact.get().destination(),
                                contact.get().id(),contact.get().version(),actual.get().credentialVersion(),secret.digest(),State.ISSUED,1,now,now.plus(policy.getResetTtl()),null);
                        var attempt=attempt(c,ready,now);
                        store.issue(scope,c,policy.crypto().selector("DESTINATION",c.destination().value()),attempt);
                        success(scope,c,actual.get().accountId(),actual.get().actorId(),null,traceId,now);
                        return prepared(c,attempt,secret,ready);
                    });
                    dispatch(ticket,pending);
                }
            }
        }
    }

    public void consume(String source,Supplier<ConsumeCommand> parser,UUID traceId) {
        consumeForOperation(source,parser,traceId,null);
    }
    public void consumeReset(String source,Supplier<ConsumeCommand> parser,UUID traceId) {
        consumeForOperation(source,parser,traceId,Operation.CONSUME_RESET);
    }
    public void consumeInvitation(String source,Supplier<ConsumeCommand> parser,UUID traceId) {
        consumeForOperation(source,parser,traceId,Operation.CONSUME_INVITATION);
    }
    private void consumeForOperation(String source,Supplier<ConsumeCommand> parser,UUID traceId,Operation expected) {
        safe(traceId,()->{consumeInternal(source,parser,traceId,expected);return null;});
    }
    private void consumeInternal(String source,Supplier<ConsumeCommand> parser,UUID traceId,Operation expected) {
        try(var admitted=admit(source);var command=parser.get()) {
            required(command,traceId);
            ChallengeSnapshot hint=store.readChallenge(command.challengeId()).orElseThrow(IdentityLifecycleService::rejected);
            if(!command.token().matches(hint.secretDigest()))throw rejected();
            Operation operation=hint.purpose()==Purpose.PASSWORD_RESET?Operation.CONSUME_RESET:Operation.CONSUME_INVITATION;
            if(expected!=null&&operation!=expected)throw rejected();
            if((hint.purpose()==Purpose.EXISTING_ACCOUNT_MEMBERSHIP)!=(command.password()==null))throw rejected();
            var session=hint.purpose()==Purpose.EXISTING_ACCOUNT_MEMBERSHIP?sessions.capture():null;
            if(session!=null&&!session.context().accountId().equals(hint.accountId()))throw rejected();
            var scope=session==null?IdentityLifecycleSecurityTransaction.Scope.capability(operation,hint.id(),hint.tenantId())
                    :IdentityLifecycleSecurityTransaction.Scope.session(operation,hint.id(),hint.tenantId(),session);
            var fingerprint=policy.crypto().prepare(operation,hint.id(),hint.purpose(),hint.accountId(),hint.tenantId(),command.password());
            reserveBudget(hint.accountId()==null?"CAPABILITY":"ACCOUNT",hint.accountId()==null?hint.id().toString():hint.accountId().toString(),10);
            // A completed hint skips bcrypt, but only the guarded branch can acknowledge it.
            boolean completed=store.readReceipt(operation,OwnerKind.CAPABILITY,hint.id(),command.commandKey()).isPresent();
            PreparedPassword password=!completed&&command.password()!=null&&hint.state()==State.ISSUED?preparePassword(command.password()):null;
            write(scope,traceId,hint.id(),()->{
                ChallengeSnapshot c=store.lockChallenge(scope,hint.id());
                if(!immutableSame(hint,c)||!command.token().matches(c.secretDigest()))throw rejected();
                currentInvitationAuthority(scope,c);
                var replay=store.receipt(scope,operation,OwnerKind.CAPABILITY,c.id(),command.commandKey());
                if(replay.isPresent()) {
                    Receipt r=replay.get();match(r,fingerprint);
                    if(c.state()!=State.CONSUMED||!r.id().equals(c.consumedReceiptId())||!r.challengeId().equals(c.id()))throw rejected();
                    replayAuthority(scope,c,r);return null;
                }
                Instant now=store.databaseNow(scope);unused(c,now);
                AccountSnapshot account;UUID member=null,contact=null;long resultVersion;
                if(c.purpose()==Purpose.NEW_ACCOUNT) {
                    requirePassword(password);if(store.loginExists(scope,c.intendedLogin()))throw rejected();
                    UUID accountId=UUID.randomUUID(),actorId=UUID.randomUUID();member=UUID.randomUUID();contact=UUID.randomUUID();
                    currentInvitationAuthority(scope,c);
                    account=store.createAccount(scope,accountId,actorId,c.intendedLogin(),password.hash(),now);
                    store.createRecovery(scope,new RecoveryBinding(contact,accountId,c.destination(),1,true),c.id(),now);
                    memberships.create(scope,accountId,member,c.displayName());resultVersion=1;
                } else {
                    account=activeAccount(scope,c.accountId());
                    RecoveryBinding binding=exactContact(scope,c,account.accountId());contact=binding.id();
                    if(c.purpose()==Purpose.PASSWORD_RESET) {
                        requirePassword(password);if(account.credentialVersion()!=c.issuedCredentialVersion())throw rejected();
                        store.resetPassword(scope,account,password.hash(),now);resultVersion=Math.addExact(account.credentialVersion(),1);
                        store.revokeOtherResets(scope,account.accountId(),c.id());
                    } else {
                        if(scope.sourceSession()==null||!account.accountId().equals(scope.sourceSession().context().accountId())||memberships.find(scope,account.accountId()).isPresent())throw rejected();
                        currentInvitationAuthority(scope,c);
                        member=UUID.randomUUID();memberships.create(scope,account.accountId(),member,scope.sourceSession().verifiedDisplayName());
                        contact=null;resultVersion=1;
                    }
                }
                Receipt receipt=receipt(scope,OwnerKind.CAPABILITY,c.id(),command.commandKey(),fingerprint,account.accountId(),account.actorId(),member,contact,null,resultVersion,null,now);
                store.receipt(scope,receipt);store.terminal(scope,c,State.CONSUMED,receipt.id());
                success(scope,c,account.accountId(),account.actorId(),null,traceId,now);return null;
            });
        }
    }

    private void replayAuthority(IdentityLifecycleSecurityTransaction.Scope scope,ChallengeSnapshot c,Receipt r) {
        AccountSnapshot account=activeAccount(scope,r.resultAccountId());
        if(!account.actorId().equals(r.resultActorId())||c.accountId()!=null&&!c.accountId().equals(account.accountId()))throw rejected();
        if(c.purpose()==Purpose.NEW_ACCOUNT) {
            RecoveryBinding contact=store.recovery(scope,account.accountId()).orElseThrow(IdentityLifecycleService::rejected);
            if(!contact.active()||!contact.id().equals(r.resultContactId())||!contact.destination().equals(c.destination()))throw rejected();
        } else exactContact(scope,c,account.accountId());
        if(c.purpose()!=Purpose.PASSWORD_RESET) {
            var member=memberships.find(scope,account.accountId()).orElseThrow(IdentityLifecycleService::rejected);
            if(!"ACTIVE".equals(member.status())||!member.memberId().equals(r.resultMemberId()))throw rejected();
        }
        if(c.purpose()==Purpose.EXISTING_ACCOUNT_MEMBERSHIP&&(scope.sourceSession()==null||!account.accountId().equals(scope.sourceSession().context().accountId())))throw rejected();
        // Credential generations and original expiry intentionally do not gate a committed receipt.
        writer.requireActive(scope);
    }
    private void currentInvitationAuthority(IdentityLifecycleSecurityTransaction.Scope scope,ChallengeSnapshot c) {
        if(c.purpose()!=Purpose.PASSWORD_RESET) {
            memberships.requireActiveTarget(scope);authorization.requireIssuerStillAuthorized(scope,c.issuer(),Action.CREATE);
        }
        if(scope.sourceSession()!=null)scope.sourceSession().requireRequestCurrent();
    }
    private void authorize(IdentityLifecycleSecurityTransaction.Scope scope,IssuerBinding issuer,Action action) {
        memberships.requireActiveTarget(scope);authorization.requireInvitationAction(scope,issuer,action);
    }
    private void managementAuthority(IdentityLifecycleSecurityTransaction.Scope scope,ChallengeSnapshot c,IssuerBinding issuer,boolean reissue) {
        if(c.purpose()==Purpose.PASSWORD_RESET||!issuer.tenantId().equals(c.tenantId()))throw rejected();
        authorize(scope,issuer,Action.REVOKE);
        if(reissue) {
            if(!sameIssuerIdentity(c.issuer(),issuer))throw rejected();authorize(scope,issuer,Action.CREATE);
        }
    }
    private RecoveryBinding exactContact(IdentityLifecycleSecurityTransaction.Scope scope,ChallengeSnapshot c,UUID accountId) {
        RecoveryBinding contact=store.recovery(scope,accountId).orElseThrow(IdentityLifecycleService::rejected);
        if(!contact.active()||!contact.id().equals(c.recoveryContactId())||!contact.destination().equals(c.destination())
                ||contact.version()!=c.contactVersion())throw rejected();
        return contact;
    }
    private AccountSnapshot activeAccount(IdentityLifecycleSecurityTransaction.Scope scope,UUID accountId) {
        AccountSnapshot account=store.localAccount(scope,accountId).orElseThrow(IdentityLifecycleService::rejected);
        if(!"ACTIVE".equals(account.status())||!store.activeHuman(scope,account.accountId(),account.actorId()))throw rejected();return account;
    }
    private InvitationResult invitationReplay(IdentityLifecycleSecurityTransaction.Scope scope,UUID key,UUID owner,IdentityLifecycleFingerprint.Prepared fingerprint) {
        var r=store.receipt(scope,scope.operation(),OwnerKind.SESSION,owner,key).orElseThrow(IdentityLifecycleService::rejected);
        return result(r,fingerprint,true);
    }
    private InvitationResult result(Receipt receipt,IdentityLifecycleFingerprint.Prepared fingerprint,boolean replay) {
        match(receipt,fingerprint);
        return new InvitationResult(receipt.resultChallengeId(),receipt.resultVersion(),receipt.resultExpiresAt(),receipt.resultDeliveryState(),replay);
    }
    private static void match(Receipt receipt,IdentityLifecycleFingerprint.Prepared fingerprint) {
        if(!fingerprint.matches(receipt.fingerprintKeyId(),receipt.fingerprint()))throw conflict();
    }
    private Receipt receipt(IdentityLifecycleSecurityTransaction.Scope scope,OwnerKind kind,UUID owner,UUID key,IdentityLifecycleFingerprint.Prepared fingerprint,
            UUID account,UUID actor,UUID member,UUID contact,UUID resultChallenge,long version,Instant expiry,Instant now) {
        DeliveryState deliveryState=kind==OwnerKind.CAPABILITY?null:scope.operation()==Operation.REVOKE_INVITATION?state(scope,scope.challengeId()):DeliveryState.PREPARED;
        return new Receipt(UUID.randomUUID(),scope.operation(),kind,owner,key,scope.challengeId(),fingerprint.activeKeyId(),fingerprint.activeFingerprint(),account,actor,member,contact,resultChallenge,version,expiry,deliveryState,now);
    }
    private IdentityLifecycleAdmission.Permit admit(String source) {return admission.admit(policy.crypto().selector("SOURCE",source));}
    private void reserveBudget(String domain,String selector,int maximum) {
        var decision=budgetDecision(domain,selector,maximum);
        if(decision==IdentityLifecycleStore.RateDecision.CAPACITY)throw IdentityLifecycleException.unavailable();
        if(decision==IdentityLifecycleStore.RateDecision.SELECTOR_LIMIT)throw new IdentityLifecycleException(IdentityLifecycleException.Reason.RATE_LIMITED);
    }
    private IdentityLifecycleStore.RateDecision budgetDecision(String domain,String selector,int maximum) {
        String digest=policy.crypto().selector(domain,selector);
        var scope=IdentityLifecycleSecurityTransaction.Scope.resetRequest(UUID.randomUUID());
        var decision=writer.execute(scope,()->store.reserveRate(scope,digest,store.databaseNow(scope).truncatedTo(ChronoUnit.MINUTES),maximum,policy.getRateBucketCapacity()));
        if(decision==null)throw IdentityLifecycleException.unavailable();return decision;
    }
    private PreparedPassword preparePassword(char[] password) {
        if(!validPassword(password))throw invalid();if(!expensive.tryAcquire())throw new IdentityLifecycleException(IdentityLifecycleException.Reason.RATE_LIMITED);
        try {Instant created=clock.instant();String encoded=passwords.encode(CharBuffer.wrap(password));
            if(encoded==null||!encoded.matches("\\{bcrypt\\}\\$2[aby]\\$1[2-6]\\$[./A-Za-z0-9]{53}"))throw IdentityLifecycleException.unavailable();
            return new PreparedPassword(encoded,created);
        }finally {expensive.release();}
    }
    private void requirePassword(PreparedPassword password) {
        if(password==null)throw rejected();Instant now=clock.instant();
        if(now.isBefore(password.created())||Duration.between(password.created(),now).compareTo(Duration.ofSeconds(60))>0)throw rejected();
    }
    private CredentialCapabilityDeliveryPort.Readiness readiness() {
        var ready=delivery.readiness();if(ready==null||policy.getConsumeOrigin()==null||!policy.getConsumeOrigin().equals(ready.consumeOrigin())||!delivery.isCurrent(ready))throw IdentityLifecycleException.unavailable();return ready;
    }
    private DeliveryAttempt attempt(ChallengeSnapshot c,CredentialCapabilityDeliveryPort.Readiness ready,Instant now) {
        return new DeliveryAttempt(UUID.randomUUID(),c.id(),ready.generation(),ready.templateVersion(),DeliveryState.PREPARED,1,instanceId,now.plus(policy.deliveryWindow()),now,now);
    }
    private CredentialCapabilityDeliveryPort.PreparedDelivery prepared(ChallengeSnapshot c,DeliveryAttempt attempt,OneUseSecret secret,CredentialCapabilityDeliveryPort.Readiness ready) {
        // Distinct memory owner: the caller's preparation secret is always erased at method exit.
        OneUseSecret copy=secret.duplicate();
        return new CredentialCapabilityDeliveryPort.PreparedDelivery(attempt.id(),c.id(),c.purpose(),c.destination(),copy,ready,attempt.deliveryDeadlineAt());
    }
    private void dispatch(IdentityCapabilityMemoryDispatcher.Ticket ticket,CredentialCapabilityDeliveryPort.PreparedDelivery pending) {
        if(pending==null)return;
        try {dispatcher.submit(ticket,pending,this::deliver);}catch(RuntimeException failure){
            pending.close();diagnostics.record(IdentityLifecycleDiagnostics.Stage.QUEUE_SUBMISSION,failure,pending.attemptId());
            // Durable PREPARED becomes UNKNOWN at its deadline; never retry.
        }
    }
    private void deliver(CredentialCapabilityDeliveryPort.PreparedDelivery prepared) {
        var stage=IdentityLifecycleDiagnostics.Stage.DELIVERY_PREPARATION;
        try {
            ChallengeSnapshot hint=store.readChallenge(prepared.challengeId()).orElseThrow(IdentityLifecycleService::rejected);
            var scope=IdentityLifecycleSecurityTransaction.Scope.deliveryObservation(hint.id(),hint.tenantId());
            DispatchFence fence=writer.execute(scope,()->{
                ChallengeSnapshot c=store.lockChallenge(scope,hint.id());DeliveryAttempt attempt=store.delivery(scope,c.id()).orElseThrow(IdentityLifecycleService::rejected);
                long monotonicStart=System.nanoTime();
                Instant now=store.databaseNow(scope);
                if(!attempt.id().equals(prepared.attemptId())||!attempt.originInstanceId().equals(instanceId)||attempt.state()!=DeliveryState.PREPARED
                        ||!attempt.deliveryDeadlineAt().equals(prepared.deadlineAt())||!attempt.deliveryDeadlineAt().isAfter(now))throw rejected();
                unused(c,now);if(!prepared.matches(c.secretDigest())||!prepared.destination().equals(c.destination())||!delivery.isCurrent(prepared.readiness())
                        ||!attempt.providerGeneration().equals(prepared.readiness().generation())||!attempt.templateVersion().equals(prepared.readiness().templateVersion()))throw rejected();
                currentInvitationAuthority(scope,c);
                if(c.purpose()!=Purpose.NEW_ACCOUNT) {AccountSnapshot account=activeAccount(scope,c.accountId());exactContact(scope,c,account.accountId());
                    if(c.purpose()==Purpose.PASSWORD_RESET&&account.credentialVersion()!=c.issuedCredentialVersion())throw rejected();}
                store.deliveryTransition(scope,attempt,DeliveryState.DISPATCHING,now);
                Duration remaining=Duration.between(now,attempt.deliveryDeadlineAt());
                Duration bounded=remaining.compareTo(policy.getProviderTimeout())<0?remaining:policy.getProviderTimeout();
                return new DispatchFence(new CredentialCapabilityDeliveryPort.DispatchBudget(monotonicStart+bounded.toNanos()),clock.instant());
            });
            // Process wall-clock regression cannot extend an already fenced deadline.
            Instant start=clock.instant();if(start.isBefore(fence.observedAt())||!start.isBefore(prepared.deadlineAt()))throw rejected();
            fence.budget().remaining();
            DeliveryOutcome outcome;
            stage=IdentityLifecycleDiagnostics.Stage.PROVIDER;
            try {outcome=delivery.dispatch(prepared,fence.budget());if(outcome==null){diagnostics.record(stage,new IllegalStateException(),prepared.attemptId());outcome=DeliveryOutcome.UNKNOWN;}}
            catch(RuntimeException uncertain){diagnostics.record(stage,uncertain,prepared.attemptId());outcome=DeliveryOutcome.UNKNOWN;}
            stage=IdentityLifecycleDiagnostics.Stage.DELIVERY_CHECKPOINT;
            checkpoint(hint,outcome);
        } catch(RuntimeException unavailable) {diagnostics.record(stage,unavailable,prepared.attemptId());}
    }
    private void checkpoint(ChallengeSnapshot hint,DeliveryOutcome outcome) {
        var scope=IdentityLifecycleSecurityTransaction.Scope.deliveryObservation(hint.id(),hint.tenantId());
        writer.execute(scope,()->{
            store.lockChallenge(scope,hint.id());var attempt=store.delivery(scope,hint.id()).orElseThrow(IdentityLifecycleService::rejected);
            if(attempt.state()!=DeliveryState.DISPATCHING)return null; // terminal UNKNOWN can never be upgraded
            Instant now=store.databaseNow(scope);DeliveryState state=now.isBefore(attempt.deliveryDeadlineAt())?DeliveryState.valueOf(outcome.name()):DeliveryState.UNKNOWN;
            store.deliveryTransition(scope,attempt,state,now);return null;
        });
    }
    /** Bounded observer may classify uncertainty after a deadline; it never declares another node dead. */
    public void observeExpiredDeliveries() {
        java.util.List<DeliveryAttempt> candidates;
        try{candidates=Objects.requireNonNull(store.expiredDeliveries(32));}
        catch(RuntimeException unavailable){diagnostics.record(IdentityLifecycleDiagnostics.Stage.DEADLINE_OBSERVATION,unavailable,null);throw IdentityLifecycleException.unavailable();}
        for(var hint:candidates) {
            try {
                var challenge=store.readChallenge(hint.challengeId()).orElseThrow(IdentityLifecycleService::rejected);
                var scope=IdentityLifecycleSecurityTransaction.Scope.deliveryObservation(challenge.id(),challenge.tenantId());
                writer.execute(scope,()->{
                    store.lockChallenge(scope,challenge.id());var actual=store.delivery(scope,challenge.id()).orElseThrow(IdentityLifecycleService::rejected);
                    Instant now=store.databaseNow(scope);
                    if((actual.state()==DeliveryState.PREPARED||actual.state()==DeliveryState.DISPATCHING)&&!now.isBefore(actual.deliveryDeadlineAt()))store.deliveryTransition(scope,actual,DeliveryState.UNKNOWN,now);
                    return null;
                });
            }catch(RuntimeException failure){diagnostics.record(IdentityLifecycleDiagnostics.Stage.DEADLINE_OBSERVATION,failure,hint==null?null:hint.id());}
        }
    }
    private DeliveryState state(IdentityLifecycleSecurityTransaction.Scope scope,UUID id){return store.delivery(scope,id).map(DeliveryAttempt::state).orElse(DeliveryState.UNKNOWN);}
    private void success(IdentityLifecycleSecurityTransaction.Scope scope,ChallengeSnapshot c,UUID account,UUID actor,IssuerBinding issuer,UUID trace,Instant now) {
        audit.success(scope,new LifecycleEvent(UUID.randomUUID(),c.id(),scope.operation(),"SUCCESS",null,issuer!=null?InitiatorKind.SESSION:
                scope.operation()==Operation.REQUEST_RESET?InitiatorKind.ANONYMOUS:InitiatorKind.CAPABILITY,issuer,c.tenantId(),account,actor,trace,now));
    }
    private <T>T write(IdentityLifecycleSecurityTransaction.Scope scope,UUID trace,UUID persistedChallenge,Supplier<T> work) {
        AtomicBoolean complete=new AtomicBoolean();var resultHolder=new java.util.concurrent.atomic.AtomicReference<T>();
        try{return writer.execute(scope,()->{T result=work.get();resultHolder.set(result);writer.requireActive(scope);complete.set(true);return result;});}
        catch(RuntimeException failure) {
            diagnostics.record(IdentityLifecycleDiagnostics.Stage.WRITE,failure,trace);
            if(resultHolder.get() instanceof AutoCloseable owned)try{owned.close();}catch(Exception ignored){diagnostics.record(IdentityLifecycleDiagnostics.Stage.WRITE,new IllegalStateException(),trace);}
            IdentityLifecycleException safe=complete.get()?new IdentityLifecycleException(IdentityLifecycleException.Reason.COMMIT_OUTCOME_UNKNOWN):
                    failure instanceof IdentityLifecycleException known?known:IdentityLifecycleException.unavailable();
            try {IssuerBinding issuer=scope.sourceSession()==null||scope.operation()==Operation.CONSUME_INVITATION?null:IssuerBinding.from(scope.sourceSession().context());
                audit.failure(new LifecycleEvent(UUID.randomUUID(),persistedChallenge,scope.operation(),complete.get()?"UNKNOWN":"REJECTED",safe.reason().name(),
                        issuer!=null?InitiatorKind.SESSION:persistedChallenge==null?InitiatorKind.ANONYMOUS:InitiatorKind.CAPABILITY,issuer,
                        scope.targetTenantId(),null,null,trace,clock.instant()));
            }catch(RuntimeException unavailable){diagnostics.record(IdentityLifecycleDiagnostics.Stage.FAILURE_AUDIT,unavailable,trace);}
            throw safe;
        }
    }
    private static boolean immutableSame(ChallengeSnapshot a,ChallengeSnapshot b) {
        return a.id().equals(b.id())&&a.purpose()==b.purpose()&&Objects.equals(a.accountId(),b.accountId())&&Objects.equals(a.tenantId(),b.tenantId())
                &&Objects.equals(a.issuer(),b.issuer())&&Objects.equals(a.intendedLogin(),b.intendedLogin())&&Objects.equals(a.displayName(),b.displayName())
                &&a.destination().equals(b.destination())&&Objects.equals(a.recoveryContactId(),b.recoveryContactId())&&Objects.equals(a.contactVersion(),b.contactVersion())
                &&Objects.equals(a.issuedCredentialVersion(),b.issuedCredentialVersion())&&a.secretDigest().equals(b.secretDigest())&&a.createdAt().equals(b.createdAt())&&a.expiresAt().equals(b.expiresAt());
    }
    private static boolean sameIssuerIdentity(IssuerBinding a,IssuerBinding b){return a.accountId().equals(b.accountId())&&a.actorId().equals(b.actorId())&&a.memberId().equals(b.memberId())&&a.tenantId().equals(b.tenantId());}
    private static void unused(ChallengeSnapshot c,Instant now){if(c.state()!=State.ISSUED||!c.expiresAt().isAfter(now))throw rejected();}
    private static IdentityLifecycleException rejected(){return IdentityLifecycleException.rejected();}
    private static IdentityLifecycleException conflict(){return new IdentityLifecycleException(IdentityLifecycleException.Reason.CONFLICT);}
    private <T>T safe(UUID trace,Supplier<T> operation){
        try{return operation.get();}
        catch(IdentityLifecycleException known){
            if(known.reason()==IdentityLifecycleException.Reason.UNAVAILABLE||known.reason()==IdentityLifecycleException.Reason.COMMIT_OUTCOME_UNKNOWN)
                diagnostics.record(IdentityLifecycleDiagnostics.Stage.REQUEST,known,trace);
            throw known;
        }
        catch(UnauthenticatedSessionException known){throw known;}
        catch(RuntimeException unavailable){diagnostics.record(IdentityLifecycleDiagnostics.Stage.REQUEST,unavailable,trace);throw IdentityLifecycleException.unavailable();}
    }
    private record PreparedPassword(String hash,Instant created){@Override public String toString(){return "PreparedPassword[REDACTED]";}}
    private record DispatchFence(CredentialCapabilityDeliveryPort.DispatchBudget budget,Instant observedAt){}
    private record Pending(InvitationResult result,CredentialCapabilityDeliveryPort.PreparedDelivery delivery) implements AutoCloseable {
        @Override public void close(){if(delivery!=null)delivery.close();}
    }
    @Override public void close(){dispatcher.close();}
}

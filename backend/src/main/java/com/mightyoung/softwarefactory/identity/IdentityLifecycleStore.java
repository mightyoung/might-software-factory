package com.mightyoung.softwarefactory.identity;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import static com.mightyoung.softwarefactory.identity.IdentityLifecycleContracts.*;

/** Identity-owned persistence; hints never replace the guarded replay/new-effect branch. */
public interface IdentityLifecycleStore {
    Optional<ChallengeSnapshot> readChallenge(UUID id);
    Optional<AccountSnapshot> readLocalAccount(String normalizedLogin);
    Optional<Receipt> readReceipt(Operation operation,OwnerKind ownerKind,UUID ownerId,UUID key);
    java.util.List<DeliveryAttempt> expiredDeliveries(int limit);
    Instant databaseNow(IdentityLifecycleSecurityTransaction.Scope scope);
    boolean loginExists(IdentityLifecycleSecurityTransaction.Scope scope,String normalizedLogin);
    ChallengeSnapshot lockChallenge(IdentityLifecycleSecurityTransaction.Scope scope,UUID id);
    Optional<AccountSnapshot> localAccount(IdentityLifecycleSecurityTransaction.Scope scope,UUID id);
    boolean activeHuman(IdentityLifecycleSecurityTransaction.Scope scope,UUID accountId,UUID actorId);
    Optional<RecoveryBinding> recovery(IdentityLifecycleSecurityTransaction.Scope scope,UUID accountId);
    Optional<ChallengeSnapshot> liveReset(IdentityLifecycleSecurityTransaction.Scope scope,UUID accountId,Instant now);
    Optional<Receipt> receipt(IdentityLifecycleSecurityTransaction.Scope scope,Operation operation,OwnerKind ownerKind,UUID ownerId,UUID key);
    RateDecision reserveRate(IdentityLifecycleSecurityTransaction.Scope scope,String selector,Instant window,int maximum,int bucketCapacity);
    void issue(IdentityLifecycleSecurityTransaction.Scope scope,ChallengeSnapshot challenge,String destinationDigest,DeliveryAttempt delivery);
    void receipt(IdentityLifecycleSecurityTransaction.Scope scope,Receipt receipt);
    void terminal(IdentityLifecycleSecurityTransaction.Scope scope,ChallengeSnapshot expected,State state,UUID consumedReceiptId);
    void expireResets(IdentityLifecycleSecurityTransaction.Scope scope,UUID accountId,Instant now);
    void expireInvitations(IdentityLifecycleSecurityTransaction.Scope scope,ChallengeSnapshot match,String destinationDigest,Instant now);
    void revokeOtherResets(IdentityLifecycleSecurityTransaction.Scope scope,UUID accountId,UUID exceptId);
    AccountSnapshot createAccount(IdentityLifecycleSecurityTransaction.Scope scope,UUID accountId,UUID actorId,String loginKey,String passwordHash,Instant now);
    void createRecovery(IdentityLifecycleSecurityTransaction.Scope scope,RecoveryBinding binding,UUID challengeId,Instant now);
    void resetPassword(IdentityLifecycleSecurityTransaction.Scope scope,AccountSnapshot expected,String encodedPassword,Instant now);
    Optional<DeliveryAttempt> delivery(IdentityLifecycleSecurityTransaction.Scope scope,UUID challengeId);
    void deliveryTransition(IdentityLifecycleSecurityTransaction.Scope scope,DeliveryAttempt expected,DeliveryState next,Instant now);
    enum RateDecision { ADMITTED, SELECTOR_LIMIT, CAPACITY }
}

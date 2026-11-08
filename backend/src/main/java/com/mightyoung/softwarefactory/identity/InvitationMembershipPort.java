package com.mightyoung.softwarefactory.identity;

import java.util.Optional;
import java.util.UUID;

/** Tenancy owns all tenant/member SQL, including absence checks under the lifecycle marker. */
public interface InvitationMembershipPort {
    void requireActiveTarget(IdentityLifecycleSecurityTransaction.Scope scope);
    Optional<Membership> find(IdentityLifecycleSecurityTransaction.Scope scope,UUID accountId);
    void create(IdentityLifecycleSecurityTransaction.Scope scope,UUID accountId,UUID memberId,String displayName);
    record Membership(UUID memberId,UUID accountId,String status,long authEpoch) {
        public Membership {IdentityLifecycleContracts.required(memberId,accountId,status);IdentityLifecycleContracts.nonnegative(authEpoch);}
    }
}

package com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload;

import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

public final class FindingEvidenceSetInitializationContracts {
    public static final String OPERATION="FINDING_REMEDIATION_EVIDENCE_SET_INITIALIZE_V1";
    public static final String FINGERPRINT_VERSION="evidence-set-init-v1";
    private FindingEvidenceSetInitializationContracts() {}
    public record InitializeCommand(UUID idempotencyKey,UUID findingId,int roundNo,long expectedFindingVersion) {
        public InitializeCommand {
            Objects.requireNonNull(idempotencyKey);Objects.requireNonNull(findingId);
            if(roundNo<1 || expectedFindingVersion<0)throw new IllegalArgumentException("Evidence set initialization context is invalid");
        }
        public String fingerprint(CurrentTenantContext owner,UUID noticeId) {
            try {
                var hash=MessageDigest.getInstance("SHA-256");
                for(String field:new String[]{OPERATION,FINGERPRINT_VERSION,owner.tenantId().toString(),owner.accountId().toString(),
                        owner.actorId().toString(),owner.memberId().toString(),findingId.toString(),Integer.toString(roundNo),
                        noticeId.toString(),Long.toString(expectedFindingVersion)}) {
                    byte[] bytes=field.getBytes(StandardCharsets.UTF_8);
                    hash.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));hash.update((byte)':');hash.update(bytes);
                }
                return HexFormat.of().formatHex(hash.digest());
            }catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        }
    }
    /** Immutable initialization result, not a claim about the set's later editable revision/state. */
    public record Result(UUID evidenceSetId,long initialEvidenceRevision,OffsetDateTime createdAt,boolean replayed) {}
    public record Receipt(UUID evidenceSetId,UUID ownerAccountId,UUID ownerActorId,UUID ownerMemberId,
            UUID findingId,int roundNo,UUID noticeId,String payloadSha256,OffsetDateTime createdAt) {
        public Result result(boolean replayed){return new Result(evidenceSetId,0,createdAt,replayed);}
    }
    public record Quota(long ownerCount,long tenantCount) {}
    public record Limits(int ownerOpenSets,int tenantOpenSets) {
        public Limits {
            if(ownerOpenSets<1 || ownerOpenSets>1000 || tenantOpenSets<ownerOpenSets || tenantOpenSets>10000) {
                throw new IllegalArgumentException("Evidence set initialization limits are invalid");
            }
        }
    }
}

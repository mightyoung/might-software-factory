package com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload;

import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

public final class FindingEvidenceUploadContracts {
    public static final String OPERATION = "FINDING_REMEDIATION_UPLOAD_INTENT_V1";
    public static final String FINGERPRINT_VERSION = "upload-intent-v1";
    private FindingEvidenceUploadContracts() {}

    public record ReserveCommand(UUID idempotencyKey, UUID findingId, int roundNo, UUID evidenceSetId,
            long expectedFindingVersion, long expectedEvidenceRevision, String filename,
            String declaredMediaType, long declaredSizeBytes) {
        public ReserveCommand {
            Objects.requireNonNull(idempotencyKey); Objects.requireNonNull(findingId); Objects.requireNonNull(evidenceSetId);
            if (roundNo < 1 || expectedFindingVersion < 0 || expectedEvidenceRevision < 0
                    || declaredSizeBytes < 1 || declaredSizeBytes > 67_108_864L
                    || filename == null || filename.isBlank() || filename.codePointCount(0, filename.length()) > 255
                    || filename.codePoints().anyMatch(c -> Character.isISOControl(c) || c == '/' || c == '\\'
                        || c >= 0xD800 && c <= 0xDFFF)
                    || filename.equals(".") || filename.equals("..")
                    || declaredMediaType == null || declaredMediaType.length() > 255
                    || !declaredMediaType.matches("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")) {
                throw new IllegalArgumentException("Upload declaration is invalid");
            }
        }
        public String fingerprint(CurrentTenantContext owner, UUID noticeId) {
            try {
                MessageDigest hash = MessageDigest.getInstance("SHA-256");
                for (String field : new String[]{OPERATION, FINGERPRINT_VERSION, owner.tenantId().toString(),
                        owner.accountId().toString(), owner.actorId().toString(), owner.memberId().toString(),
                        findingId.toString(), Integer.toString(roundNo), noticeId.toString(), evidenceSetId.toString(),
                        Long.toString(expectedFindingVersion), Long.toString(expectedEvidenceRevision), filename,
                        declaredMediaType, Long.toString(declaredSizeBytes)}) {
                    byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                    hash.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
                    hash.update((byte)':'); hash.update(bytes);
                }
                return HexFormat.of().formatHex(hash.digest());
            } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        }
    }
    public enum State { INTENT_CREATED, RECEIVING, QUARANTINED, SCANNING, VERIFIED_PENDING_FINALIZATION, RETRY_WAIT, FAILED, REJECTED, CANCELLED, EXPIRED, FINALIZED }
    public record Status(UUID intentId, State state, OffsetDateTime expiresAt, boolean cancelRequested) {
        public Status(UUID intentId,State state,OffsetDateTime expiresAt){this(intentId,state,expiresAt,false);}
    }
    public record Reservation(Status status, boolean replayed) {}
    public record Finding(UUID id, UUID organizationId, String state, int roundNo, long version) {}
    public record Notice(UUID id, UUID assigneeMemberId, boolean openRound) {}
    public record EvidenceSet(UUID id, UUID findingId, int roundNo, UUID ownerMemberId,
            long revision, boolean sealed) {}
    public record Intent(UUID id, UUID ownerAccountId, UUID ownerActorId, UUID ownerMemberId,
            UUID findingId, int roundNo, UUID noticeId, UUID evidenceSetId, String payloadSha256,
            OffsetDateTime createdAt, OffsetDateTime expiresAt, State storedState, boolean cancelRequested) {
        public Intent(UUID id,UUID ownerAccountId,UUID ownerActorId,UUID ownerMemberId,UUID findingId,int roundNo,
                UUID noticeId,UUID evidenceSetId,String payloadSha256,OffsetDateTime createdAt,OffsetDateTime expiresAt){
            this(id,ownerAccountId,ownerActorId,ownerMemberId,findingId,roundNo,noticeId,evidenceSetId,payloadSha256,
                    createdAt,expiresAt,State.INTENT_CREATED,false);
        }
        public Status status(OffsetDateTime now) {
            return new Status(id, storedState==State.INTENT_CREATED&&!now.isBefore(expiresAt)?State.EXPIRED:storedState, expiresAt,cancelRequested);
        }
    }
    public record Quota(long ownerCount, long ownerBytes, long tenantCount, long tenantBytes, long setCount) {}
}

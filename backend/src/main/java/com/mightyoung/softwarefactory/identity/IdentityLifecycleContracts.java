package com.mightyoung.softwarefactory.identity;

import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Identity-owned values. No caller-supplied tenant/role is promoted to authority by a value. */
public final class IdentityLifecycleContracts {
    private IdentityLifecycleContracts() { }
    public enum Purpose { NEW_ACCOUNT, EXISTING_ACCOUNT_MEMBERSHIP, PASSWORD_RESET }
    public enum State { ISSUED, CONSUMED, REVOKED, EXPIRED }
    public enum Operation { ISSUE_INVITATION, REISSUE_INVITATION, REVOKE_INVITATION, REQUEST_RESET, CONSUME_INVITATION, CONSUME_RESET, DELIVERY_CHECKPOINT }
    public enum OwnerKind { SESSION, CAPABILITY }
    public enum DeliveryOutcome { ACCEPTED, DEFINITELY_NOT_SENT, UNKNOWN }
    public enum DeliveryState { PREPARED, DISPATCHING, ACCEPTED, DEFINITELY_NOT_SENT, UNKNOWN }
    public enum InitiatorKind { SESSION, CAPABILITY, ANONYMOUS }

    /** Random capability material exists only in bounded process memory. */
    public static final class OneUseSecret implements AutoCloseable {
        private byte[] value;
        private OneUseSecret(byte[] value) { this.value = value.clone(); }
        static OneUseSecret generate(SecureRandom random) {
            byte[] bytes = new byte[32]; random.nextBytes(bytes);
            try { return new OneUseSecret(bytes); } finally { Arrays.fill(bytes, (byte) 0); }
        }
        public static OneUseSecret parse(String token) {
            if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw invalid();
            byte[] bytes;
            try { bytes = Base64.getUrlDecoder().decode(token); }
            catch (IllegalArgumentException malformed) { throw invalid(); }
            try {
                if (bytes.length != 32 || !Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(token)) throw invalid();
                return new OneUseSecret(bytes);
            } finally { Arrays.fill(bytes, (byte) 0); }
        }
        String digest() { return HexFormat.of().formatHex(sha256(requiredValue())); }
        OneUseSecret duplicate() { return new OneUseSecret(requiredValue()); }
        public <T> T use(java.util.function.Function<char[], T> work) {
            byte[] bytes=Base64.getUrlEncoder().withoutPadding().encode(requiredValue());
            char[] encoded=new char[bytes.length];for(int i=0;i<bytes.length;i++)encoded[i]=(char)bytes[i];
            try { return work.apply(encoded); } finally { Arrays.fill(encoded, '\0');Arrays.fill(bytes,(byte)0); }
        }
        boolean matches(String digest) {
            hash(digest);
            return MessageDigest.isEqual(sha256(requiredValue()), HexFormat.of().parseHex(digest));
        }
        private byte[] requiredValue() { if (value == null) throw invalid(); return value; }
        @Override public void close() { if (value != null) { Arrays.fill(value, (byte) 0); value = null; } }
        @Override public String toString() { return "OneUseSecret[REDACTED]"; }
    }

    /** Stored destination is never a login-key inference or ordinary diagnostic field. */
    public record Destination(String channel, String value) {
        public Destination {
            if (!"EMAIL".equals(channel)) throw invalid();
            text(value, 320);
            if (value.codePoints().anyMatch(Character::isISOControl)) throw invalid();
        }
        @Override public String toString() { return "Destination[REDACTED]"; }
    }
    public record IssuerBinding(UUID accountId, UUID actorId, UUID tenantId, UUID memberId,
                                long credentialVersion, long tenantAuthEpoch, long memberAuthEpoch) {
        public IssuerBinding { required(accountId,actorId,tenantId,memberId); positive(credentialVersion); nonnegative(tenantAuthEpoch); nonnegative(memberAuthEpoch); }
        static IssuerBinding from(CurrentTenantContext context) {
            return new IssuerBinding(context.accountId(),context.actorId(),context.tenantId(),context.memberId(),
                    context.credentialVersion(),context.tenantAuthEpoch(),context.memberAuthEpoch());
        }
    }
    public record RecoveryBinding(UUID id, UUID accountId, Destination destination, long version, boolean active) {
        public RecoveryBinding { required(id,accountId,destination); positive(version); }
        @Override public String toString() { return "RecoveryBinding[id="+id+",version="+version+"]"; }
    }
    public record AccountSnapshot(UUID accountId, UUID actorId, String loginKey, String status,
                                  long credentialVersion, String passwordHash) {
        public AccountSnapshot { required(accountId,actorId,loginKey,status,passwordHash); positive(credentialVersion); }
        @Override public String toString() { return "LifecycleAccount[id="+accountId+"]"; }
    }
    public record ChallengeSnapshot(UUID id, Purpose purpose, UUID accountId, UUID tenantId, IssuerBinding issuer,
                                    String intendedLogin, String displayName, Destination destination,
                                    UUID recoveryContactId, Long contactVersion, Long issuedCredentialVersion,
                                    String secretDigest, State state, long version, Instant createdAt, Instant expiresAt,
                                    UUID consumedReceiptId) {
        public ChallengeSnapshot {
            required(id,purpose,destination,state,createdAt,expiresAt); hash(secretDigest); positive(version);
            createdAt=micros(createdAt); expiresAt=micros(expiresAt);
            if (!expiresAt.isAfter(createdAt) || (state==State.CONSUMED)!=(consumedReceiptId!=null)) throw invalid();
            if (purpose==Purpose.NEW_ACCOUNT) {
                required(tenantId,issuer,intendedLogin,displayName); text(intendedLogin,200); text(displayName,200);
                if (accountId!=null || recoveryContactId!=null || contactVersion!=null || issuedCredentialVersion!=null
                        || !tenantId.equals(issuer.tenantId()) || !intendedLogin.equals(LocalLoginKey.normalize(intendedLogin))) throw invalid();
            } else if (purpose==Purpose.EXISTING_ACCOUNT_MEMBERSHIP) {
                required(accountId,tenantId,issuer,recoveryContactId,contactVersion);positive(contactVersion);
                if (intendedLogin!=null || displayName!=null
                        || issuedCredentialVersion!=null || !tenantId.equals(issuer.tenantId())) throw invalid();
            } else {
                required(accountId,recoveryContactId,contactVersion,issuedCredentialVersion);
                positive(contactVersion); positive(issuedCredentialVersion);
                if (tenantId!=null || issuer!=null || intendedLogin!=null || displayName!=null) throw invalid();
            }
        }
        @Override public String toString() { return "LifecycleChallenge[id="+id+",purpose="+purpose+",state="+state+"]"; }
    }
    public record Receipt(UUID id, Operation operation, OwnerKind ownerKind, UUID ownerId, UUID commandKey,
                          UUID challengeId, String fingerprintKeyId, String fingerprint,
                          UUID resultAccountId, UUID resultActorId, UUID resultMemberId, UUID resultContactId,
                          UUID resultChallengeId, long resultVersion, Instant resultExpiresAt, DeliveryState resultDeliveryState, Instant completedAt) {
        public Receipt {
            required(id,operation,ownerKind,ownerId,commandKey,challengeId,completedAt); identifier(fingerprintKeyId); hash(fingerprint);
            positive(resultVersion); completedAt=micros(completedAt); if(resultExpiresAt!=null)resultExpiresAt=micros(resultExpiresAt);
            boolean consume=operation==Operation.CONSUME_INVITATION||operation==Operation.CONSUME_RESET;
            if(operation==Operation.REQUEST_RESET||operation==Operation.DELIVERY_CHECKPOINT)throw invalid();
            if (consume!=(ownerKind==OwnerKind.CAPABILITY) || consume&&!ownerId.equals(challengeId)) throw invalid();
            if (consume) required(resultAccountId,resultActorId);
            else if(resultAccountId!=null||resultActorId!=null||resultMemberId!=null||resultContactId!=null)throw invalid();
            if(consume!=(resultDeliveryState==null))throw invalid();
        }
        @Override public String toString() { return "LifecycleReceipt[id="+id+",operation="+operation+"]"; }
    }
    public record InvitationResult(UUID invitationId, long version, Instant expiresAt, DeliveryState deliveryState, boolean replayed) {
        public InvitationResult { required(invitationId,expiresAt,deliveryState); positive(version); expiresAt=micros(expiresAt); }
    }
    public record InvitationCommand(UUID commandKey, Purpose purpose, UUID targetAccountId,
                                    String loginKey, String displayName, Destination destination) {
        public InvitationCommand {
            required(commandKey,purpose,destination);
            if (purpose==Purpose.NEW_ACCOUNT) {
                loginKey=LocalLoginKey.normalize(loginKey); text(loginKey,200); text(displayName,200);
                if(targetAccountId!=null)throw invalid();
            } else if(purpose==Purpose.EXISTING_ACCOUNT_MEMBERSHIP) {
                required(targetAccountId); if(loginKey!=null||displayName!=null)throw invalid();
            } else throw invalid();
        }
        @Override public String toString(){return "InvitationCommand[REDACTED]";}
    }
    public record MutationCommand(UUID challengeId, UUID commandKey, long expectedVersion) {
        public MutationCommand {required(challengeId,commandKey);positive(expectedVersion);}
    }
    public static final class ConsumeCommand implements AutoCloseable {
        private final UUID challengeId,commandKey;
        private final OneUseSecret token;
        private final char[] password;
        public ConsumeCommand(UUID challengeId,UUID commandKey,OneUseSecret token,char[] password) {
            required(challengeId,commandKey,token);this.challengeId=challengeId;this.commandKey=commandKey;this.token=token;
            this.password=password==null?null:password.clone();
            if(password!=null&&!validPassword(this.password)) {close();throw invalid();}
        }
        public UUID challengeId(){return challengeId;}
        public UUID commandKey(){return commandKey;}
        OneUseSecret token(){return token;}
        char[] password(){return password;}
        @Override public void close(){token.close();if(password!=null)Arrays.fill(password,'\0');}
        @Override public String toString(){return "ConsumeCommand[REDACTED]";}
    }
    public record DeliveryAttempt(UUID id,UUID challengeId,String providerGeneration,String templateVersion,
                                  DeliveryState state,long version,UUID originInstanceId,Instant deliveryDeadlineAt,
                                  Instant createdAt,Instant occurredAt) {
        public DeliveryAttempt {required(id,challengeId,state,originInstanceId,deliveryDeadlineAt,createdAt,occurredAt);identifier(providerGeneration);identifier(templateVersion);positive(version);createdAt=micros(createdAt);occurredAt=micros(occurredAt);deliveryDeadlineAt=micros(deliveryDeadlineAt);if(occurredAt.isBefore(createdAt)||!deliveryDeadlineAt.isAfter(createdAt))throw invalid();}
    }
    public record LifecycleEvent(UUID id,UUID challengeId,Operation operation,String result,String reason,
                                 InitiatorKind initiatorKind,IssuerBinding sessionInitiator,
                                 UUID tenantId,UUID accountId,UUID actorId,UUID traceId,Instant occurredAt) {
        public LifecycleEvent {
            required(id,operation,initiatorKind,traceId,occurredAt);identifier(result);if(reason!=null)identifier(reason);occurredAt=micros(occurredAt);
            if(!java.util.Set.of("SUCCESS","REJECTED","UNKNOWN","ACCEPTED","DEFINITELY_NOT_SENT").contains(result)
                    ||reason!=null&&!reason.matches("[A-Z][A-Z0-9_]{0,63}")||actorId!=null&&accountId==null)throw invalid();
            if((initiatorKind==InitiatorKind.SESSION)!=(sessionInitiator!=null)
                    ||initiatorKind==InitiatorKind.CAPABILITY&&challengeId==null
                    ||sessionInitiator!=null&&!sessionInitiator.tenantId().equals(tenantId))throw invalid();
        }
    }
    static boolean validPassword(char[] value) {
        if (value==null || Character.codePointCount(value,0,value.length)<15) return false;
        try {
            var encoded=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));
            try {return encoded.remaining()>=1&&encoded.remaining()<=72;}
            finally {if(encoded.hasArray())Arrays.fill(encoded.array(),(byte)0);}
        } catch(java.nio.charset.CharacterCodingException malformed){return false;}
    }
    static byte[] sha256(byte[] value) {try{return MessageDigest.getInstance("SHA-256").digest(value);}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    static Instant micros(Instant value){return value.truncatedTo(ChronoUnit.MICROS);}
    static void text(String value,int maximum) {
        if(value==null||value.isBlank()||value.codePointCount(0,value.length())>maximum||value.indexOf('\0')>=0)throw invalid();
        for(int i=0;i<value.length();i++){char c=value.charAt(i);if(Character.isHighSurrogate(c)){if(++i>=value.length()||!Character.isLowSurrogate(value.charAt(i)))throw invalid();}else if(Character.isLowSurrogate(c))throw invalid();}
    }
    static void hash(String value){if(value==null||!value.matches("[0-9a-f]{64}"))throw invalid();}
    static void identifier(String value){if(value==null||!value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}"))throw invalid();}
    static void required(Object... values){for(Object value:values)Objects.requireNonNull(value);}
    static void positive(long value){if(value<1)throw invalid();}
    static void nonnegative(long value){if(value<0)throw invalid();}
    static IdentityLifecycleException invalid(){return new IdentityLifecycleException(IdentityLifecycleException.Reason.INVALID_REQUEST);}
}

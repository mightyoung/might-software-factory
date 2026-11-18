package com.mightyoung.softwarefactory.identity;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class SessionSensitiveContracts {
    private SessionSensitiveContracts(){}
    public enum Operation { REGISTER_SESSION, ISSUE_REAUTH, REVOKE_SESSION, LOGOUT,
        REQUEST_CONTACT_CHANGE, CONSUME_CONTACT_CHANGE, CONTACT_DELIVERY_CHECKPOINT }
    public enum AuthorityState { ABSENT, ACTIVE, REVOKED }
    public enum Coverage { INCOMPLETE_LEGACY, COMPLETE }
    public enum Enforcement { OFF, ENFORCING }
    public enum RevocationState { CONFIRMED, LOCAL_ONLY, UNKNOWN }
    public enum Provenance { FRESH_SESSION, KNOWN_LINEAGE, PROVED_SUBJECT, SYSTEM_OBSERVATION, ANONYMOUS }
    public record ExpectedAuthority(long version) {
        public ExpectedAuthority { if(version<0)throw new IllegalArgumentException("Invalid authority version"); }
        public boolean absent(){return version==0;}
    }
    /** Exact row identity prevents replacement-contact version-one ABA. */
    public record ExpectedContact(UUID id,long version) {
        public ExpectedContact { if((id==null&&version!=0)||(id!=null&&version<1))throw new IllegalArgumentException("Invalid contact binding"); }
    }
    public record SessionItem(SessionHandle handle,boolean current,Instant createdAt,Instant lastAccessAt,
                              Instant idleExpiresAt,AuthorityState authorityState,long authorityVersion) {
        public SessionItem {
            Objects.requireNonNull(handle);Objects.requireNonNull(createdAt);Objects.requireNonNull(lastAccessAt);
            Objects.requireNonNull(idleExpiresAt);Objects.requireNonNull(authorityState);
            if((authorityState==AuthorityState.ABSENT&&authorityVersion!=0)||(authorityState!=AuthorityState.ABSENT&&authorityVersion<1))
                throw new IllegalArgumentException("Invalid session authority");
        }
    }
    /** Cursor transport encoding and reviewed full-inventory coverage are subsequent integrations. */
    public record SessionPage(List<SessionItem> items,Coverage coverage,SessionHandle next) {
        public SessionPage { items=List.copyOf(items);Objects.requireNonNull(coverage);if(items.size()>50)throw new IllegalArgumentException("Page too large"); }
    }
    public record Registration(SessionHandle handle,boolean created){public Registration{Objects.requireNonNull(handle);}}
    public record FenceResult(SessionHandle source,SessionHandle target,long version,boolean created,java.util.UUID eventId,Instant occurredAt){
        public FenceResult{Objects.requireNonNull(source);Objects.requireNonNull(target);if(version<1)throw new IllegalArgumentException("Invalid fence");}
    }
    public record RevokeCommand(java.util.UUID commandKey,SessionHandle target,ExpectedAuthority expected,java.util.UUID proofId,
                                IdentityLifecycleContracts.OneUseSecret secret){
        public RevokeCommand{Objects.requireNonNull(commandKey);Objects.requireNonNull(target);Objects.requireNonNull(expected);Objects.requireNonNull(proofId);Objects.requireNonNull(secret);}
        @Override public String toString(){return "SessionRevokeCommand[REDACTED]";}
    }
    public record SensitiveEvent(UUID id,Operation operation,String result,String reason,Provenance provenance,
                                 UUID accountId,UUID actorId,SessionHandle source,SessionHandle target,
                                 UUID proofId,UUID challengeId,UUID traceId,Instant occurredAt) {
        public SensitiveEvent {
            Objects.requireNonNull(id);Objects.requireNonNull(operation);Objects.requireNonNull(provenance);
            Objects.requireNonNull(traceId);Objects.requireNonNull(occurredAt);
            if(!List.of("SUCCESS","REJECTED","UNKNOWN","ACCEPTED","DEFINITELY_NOT_SENT").contains(result)
                    ||(reason!=null&&!reason.matches("[A-Z][A-Z0-9_]{0,63}")))throw new IllegalArgumentException("Invalid event");
            boolean valid=switch(provenance){
                case FRESH_SESSION -> accountId!=null&&actorId!=null&&source!=null;
                case KNOWN_LINEAGE -> operation==Operation.LOGOUT&&accountId!=null&&source!=null;
                case PROVED_SUBJECT -> accountId!=null&&List.of("REJECTED","UNKNOWN").contains(result);
                case SYSTEM_OBSERVATION -> operation==Operation.CONTACT_DELIVERY_CHECKPOINT&&accountId!=null&&challengeId!=null&&actorId==null;
                case ANONYMOUS -> List.of("REJECTED","UNKNOWN").contains(result)&&accountId==null&&actorId==null&&source==null&&target==null&&proofId==null&&challengeId==null;
            };
            if(!valid)throw new IllegalArgumentException("Invalid event provenance");
        }
        @Override public String toString(){return "SensitiveEvent[operation="+operation+",result="+result+"]";}
    }
}

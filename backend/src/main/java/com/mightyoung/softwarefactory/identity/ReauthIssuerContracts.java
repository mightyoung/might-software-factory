package com.mightyoung.softwarefactory.identity;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import static com.mightyoung.softwarefactory.identity.SessionSensitiveContracts.*;

/** Finite remote-session proof issuance values; never session attributes. */
public final class ReauthIssuerContracts {
    private ReauthIssuerContracts() { }
    public static final class Request implements AutoCloseable {
        private final SessionHandle target;
        private final ExpectedAuthority expected;
        private char[] password;
        public Request(SessionHandle target,ExpectedAuthority expected,char[] currentPassword){
            this.target=Objects.requireNonNull(target);this.expected=Objects.requireNonNull(expected);
            if(currentPassword==null||currentPassword.length<1||currentPassword.length>72)throw SessionSensitiveException.rejected();
            password=currentPassword.clone();
        }
        public SessionHandle target(){return target;}
        public ExpectedAuthority expected(){return expected;}
        char[] password(){if(password==null)throw SessionSensitiveException.rejected();return password;}
        @Override public void close(){if(password!=null){Arrays.fill(password,'\0');password=null;}}
        @Override public String toString(){return "ReauthIssueRequest[REDACTED]";}
    }
    public record Metadata(UUID proofId,UUID issuanceReceiptId,Instant expiresAt){
        public Metadata{Objects.requireNonNull(proofId);Objects.requireNonNull(issuanceReceiptId);Objects.requireNonNull(expiresAt);}
    }
    public sealed interface Outcome extends AutoCloseable permits Issued,Replay {
        Metadata metadata();
        @Override void close();
    }
    public static final class Issued implements Outcome {
        private final Metadata metadata;
        private final IdentityLifecycleContracts.OneUseSecret secret;
        Issued(Metadata metadata,IdentityLifecycleContracts.OneUseSecret secret){this.metadata=Objects.requireNonNull(metadata);this.secret=Objects.requireNonNull(secret);}
        @Override public Metadata metadata(){return metadata;}
        public IdentityLifecycleContracts.OneUseSecret secret(){return secret;}
        @Override public void close(){secret.close();}
        @Override public String toString(){return "ReauthIssued[REDACTED]";}
    }
    public record Replay(Metadata metadata) implements Outcome {
        public Replay{Objects.requireNonNull(metadata);}
        public String tokenDelivery(){return "UNAVAILABLE_ON_REPLAY";}
        @Override public void close(){ }
        @Override public String toString(){return "ReauthReplay[tokenDelivery=UNAVAILABLE_ON_REPLAY]";}
    }
}

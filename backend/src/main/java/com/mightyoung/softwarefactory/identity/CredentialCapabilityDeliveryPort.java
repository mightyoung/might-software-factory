package com.mightyoung.softwarefactory.identity;

import java.net.URI;
import java.util.UUID;
import static com.mightyoung.softwarefactory.identity.IdentityLifecycleContracts.*;

/** Actual provider adapter must be reviewed separately; no plaintext job or general mail relay. */
public interface CredentialCapabilityDeliveryPort {
    Readiness readiness();
    /** Pure local generation check; must not perform provider I/O under a writer guard. */
    default boolean isCurrent(Readiness readiness){return false;}
    DeliveryOutcome dispatch(PreparedDelivery delivery,DispatchBudget budget);
    record DispatchBudget(long deadlineNanos) {
        public DispatchBudget {long remaining=deadlineNanos-System.nanoTime();if(remaining<=0||remaining>java.time.Duration.ofSeconds(30).toNanos())throw IdentityLifecycleException.unavailable();}
        public static DispatchBudget after(java.time.Duration duration){if(duration.isNegative()||duration.isZero()||duration.compareTo(java.time.Duration.ofSeconds(30))>0)throw IdentityLifecycleException.unavailable();return new DispatchBudget(System.nanoTime()+duration.toNanos());}
        public java.time.Duration remaining(){long left=deadlineNanos-System.nanoTime();if(left<=0)throw IdentityLifecycleException.unavailable();return java.time.Duration.ofNanos(left);}
    }
    record Readiness(String generation,String templateVersion,URI consumeOrigin) {
        public Readiness {
            identifier(generation);identifier(templateVersion);required(consumeOrigin);
            if(!"https".equals(consumeOrigin.getScheme())||consumeOrigin.getHost()==null||consumeOrigin.getUserInfo()!=null
                    ||consumeOrigin.getQuery()!=null||consumeOrigin.getFragment()!=null
                    ||!(consumeOrigin.getPath().isEmpty()||consumeOrigin.getPath().equals("/")))throw invalid();
        }
    }
    final class PreparedDelivery implements AutoCloseable {
        private final UUID attemptId,challengeId;
        private final Purpose purpose;
        private final Destination destination;
        private final OneUseSecret secret;
        private final Readiness readiness;
        private final java.time.Instant deadlineAt;
        public PreparedDelivery(UUID attemptId,UUID challengeId,Purpose purpose,Destination destination,OneUseSecret secret,Readiness readiness,java.time.Instant deadlineAt) {
            required(attemptId,challengeId,purpose,destination,secret,readiness,deadlineAt);
            this.attemptId=attemptId;this.challengeId=challengeId;this.purpose=purpose;this.destination=destination;this.secret=secret;this.readiness=readiness;this.deadlineAt=micros(deadlineAt);
        }
        public UUID attemptId(){return attemptId;}
        public UUID challengeId(){return challengeId;}
        public Purpose purpose(){return purpose;}
        public Destination destination(){return destination;}
        public Readiness readiness(){return readiness;}
        public java.time.Instant deadlineAt(){return deadlineAt;}
        public <T> T useSecret(java.util.function.Function<char[],T> work){return secret.use(work);}
        boolean matches(String digest){return secret.matches(digest);}
        @Override public void close(){secret.close();}
        @Override public String toString(){return "PreparedCapabilityDelivery[REDACTED]";}
    }
}

package com.mightyoung.softwarefactory.identity;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static com.mightyoung.softwarefactory.identity.IdentityLifecycleContracts.*;
import static org.assertj.core.api.Assertions.*;

class IdentityLifecycleContractsTest {
    @Test void tokenIsCanonicalHighEntropyMaterialAndDiagnosticsAreRedacted() {
        var token=OneUseSecret.parse("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        assertThat(token.digest()).hasSize(64);
        assertThat(token.toString()).isEqualTo("OneUseSecret[REDACTED]");
        assertThat(token.matches(token.digest())).isTrue();
        assertThat(token.matches("f".repeat(64))).isFalse();
        assertThatThrownBy(()->OneUseSecret.parse("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAB"))
                .isInstanceOf(IdentityLifecycleException.class);
        token.close();
        assertThatThrownBy(token::digest).isInstanceOf(IdentityLifecycleException.class);
    }
    @Test void passwordAndDestinationBoundariesRejectLossyOrUnboundedMaterial() {
        for(String password:new String[]{"short", "a".repeat(73), "a".repeat(15)+"\ud800"})
            assertThat(validPassword(password.toCharArray())).isFalse();
        assertThat(validPassword("an acceptable new password".toCharArray())).isTrue();
        assertThatThrownBy(()->new Destination("EMAIL","x\r\ny"))
                .isInstanceOf(IdentityLifecycleException.class);
        assertThat(new Destination("EMAIL","owned@example.invalid").toString()).doesNotContain("owned");
    }
    @Test void loginCanonicalizationPreservesExistingGoldenExamples() {
        assertThat(LocalLoginKey.normalize("  Ａlice  ")).isEqualTo("alice");
        assertThat(LocalLoginKey.normalize(" Straße ")).isEqualTo("straße");
        assertThat(LocalLoginKey.normalize(" E\u0301 ")).isEqualTo("é");
    }
    @Test void resetNeverHasAnInventedTenantOrIssuerAndDurableTimesAreMicroseconds() {
        Instant now=Instant.parse("2026-11-03T12:00:00.123456789Z");
        var reset=new ChallengeSnapshot(UUID.randomUUID(),Purpose.PASSWORD_RESET,UUID.randomUUID(),null,null,
                null,null,new Destination("EMAIL","owned@example.invalid"),UUID.randomUUID(),1L,1L,
                "a".repeat(64),State.ISSUED,1,now,now.plusSeconds(900),null);
        assertThat(reset.createdAt().getNano()).isEqualTo(123456000);
        assertThatThrownBy(()->new ChallengeSnapshot(reset.id(),reset.purpose(),reset.accountId(),UUID.randomUUID(),null,
                null,null,reset.destination(),reset.recoveryContactId(),1L,1L,reset.secretDigest(),State.ISSUED,1,now,now.plusSeconds(900),null))
                .isInstanceOf(IdentityLifecycleException.class);
    }
}

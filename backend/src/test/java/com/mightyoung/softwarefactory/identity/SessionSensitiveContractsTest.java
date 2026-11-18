package com.mightyoung.softwarefactory.identity;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static com.mightyoung.softwarefactory.identity.SessionSensitiveContracts.*;

class SessionSensitiveContractsTest {
    @Test void primaryHandleRequiresCanonicalUuidAndNeverEstablishesOwnership(){
        UUID id=UUID.randomUUID();assertThat(SessionHandle.parse(id.toString()).value()).isEqualTo(id);
        assertThatThrownBy(()->SessionHandle.parse(id.toString().toUpperCase())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->SessionHandle.parse("1-1-1-1-1")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void absenceHasVersionZeroAndContactReplacementFreezesIdentity(){
        assertThat(new ExpectedAuthority(0).absent()).isTrue();
        assertThatThrownBy(()->new ExpectedAuthority(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new ExpectedContact(null,1)).isInstanceOf(IllegalArgumentException.class);
        UUID old=UUID.randomUUID(),replacement=UUID.randomUUID();
        assertThat(new ExpectedContact(old,1)).isNotEqualTo(new ExpectedContact(replacement,1));
        Instant now=Instant.parse("2026-11-04T00:00:00Z");
        assertThatThrownBy(()->new SessionItem(new SessionHandle(old),false,now,now,now.plusSeconds(1),AuthorityState.ABSENT,1)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void retainedSessionKeysFailClosedAndDomainsAreSeparated(){
        byte[] key=new byte[32];java.util.Arrays.fill(key,(byte)7);
        var keys=new SessionSensitiveKeys("k1",Map.of("k1",key));String bearer="private-bearer";
        String binding=keys.sessionDigest("k1",bearer);
        key[0]=42;assertThat(keys.matchesSession("k1",binding,bearer)).isTrue();
        assertThat(keys.commandDigest("k1",bearer)).isNotEqualTo(binding);
        assertThat(keys.actionDigest("k1",bearer)).isNotEqualTo(binding);
        assertThat(keys.matchesSession("k1",binding,"different")).isFalse();
        assertThatThrownBy(()->keys.matchesSession("missing",binding,bearer)).isInstanceOf(SessionSensitiveException.class);
        assertThat(keys.toString()).doesNotContain(bearer,binding);
        assertThatThrownBy(()->new SessionSensitiveKeys("k1",Map.of())).isInstanceOf(SessionSensitiveException.class);
    }
    @Test void failedSubjectsCannotClaimFreshSuccessProvenance(){
        UUID id=UUID.randomUUID();Instant now=Instant.parse("2026-11-04T00:00:00Z");
        assertThatThrownBy(()->new SensitiveEvent(id,Operation.LOGOUT,"SUCCESS",null,Provenance.PROVED_SUBJECT,
                id,null,null,null,null,null,id,now)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new SensitiveEvent(id,Operation.REGISTER_SESSION,"SUCCESS",null,Provenance.KNOWN_LINEAGE,
                id,null,new SessionHandle(id),null,null,null,id,now)).isInstanceOf(IllegalArgumentException.class);
    }
}

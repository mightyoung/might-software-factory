package com.mightyoung.softwarefactory.dualprevention.asset;

import com.mightyoung.softwarefactory.authorization.RegisteredEntityAction;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AssetFoundationFingerprintTest {
    private final CurrentTenantContext context=new CurrentTenantContext(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1,1,1);
    @Test void rowOrderingMatchesUnsignedPostgresUuidBytesRatherThanJavaSignedLongs() {
        UUID low=UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff");
        UUID high=UUID.fromString("80000000-0000-0000-0000-000000000000");
        assertTrue(low.compareTo(high)>0);assertTrue(AssetContracts.compareUuid(low,high)<0);
        UUID lowTail=UUID.fromString("00000000-0000-0000-7fff-ffffffffffff");
        UUID highTail=UUID.fromString("00000000-0000-0000-8000-000000000000");
        assertTrue(AssetContracts.compareUuid(lowTail,highTail)<0);
        assertEquals(0,AssetContracts.compareUuid(high,high));
    }
    @Test void framingDoesNotCollideWhenFieldBoundariesMove() {
        assertNotEquals(hash(List.of("ab","c")),hash(List.of("a","bc")));
        assertNotEquals(hash(List.of("1")),hash(List.of(1L)));
    }
    @Test void originalExpectedVersionAndNullPresenceAreRetained() {
        assertNotEquals(hash(Arrays.asList(1L,null)),hash(Arrays.asList(2L,null)));
        assertNotEquals(hash(Arrays.asList(1L,null)),hash(List.of(1L)));
    }
    @Test void authorityIdentityAndRouteTargetArePartOfFingerprint() {
        var action=RegisteredEntityAction.WORKSHOP_UPDATE;UUID target=UUID.randomUUID();
        String first=AssetFoundationFingerprint.command(context,action,target,List.of(1L,"Name"));
        assertNotEquals(first,AssetFoundationFingerprint.command(context,action,UUID.randomUUID(),List.of(1L,"Name")));
        var changed=new CurrentTenantContext(context.actorId(),UUID.randomUUID(),context.tenantId(),context.memberId(),1,1,1);
        assertNotEquals(first,AssetFoundationFingerprint.command(changed,action,target,List.of(1L,"Name")));
    }
    @Test void authorityEpochRefreshDoesNotChangeImmutableRequestIdentity() {
        var fresh=new CurrentTenantContext(context.actorId(),context.accountId(),context.tenantId(),context.memberId(),2,3,4);
        assertEquals(hash(List.of(1L,"A")),AssetFoundationFingerprint.command(fresh,RegisteredEntityAction.WORKSHOP_UPDATE,null,List.of(1L,"A")));
    }
    @Test void normalizedInstantsAreStableAndTextIsNotNormalized() {
        assertEquals(hash(List.of(Instant.parse("2026-11-04T00:00:00Z"))),hash(List.of(Instant.parse("2026-11-04T00:00:00.000000Z"))));
        assertNotEquals(hash(List.of("é")),hash(List.of("é")));
    }
    private String hash(List<?> fields) { return AssetFoundationFingerprint.command(context,RegisteredEntityAction.WORKSHOP_UPDATE,null,fields); }
}

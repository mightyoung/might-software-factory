package com.mightyoung.softwarefactory.dualprevention.risk;

import com.mightyoung.softwarefactory.authorization.EntityActionAuthorization.ActionScope;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.mightyoung.softwarefactory.authorization.RegisteredEntityAction.*;
import static com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioContracts.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RiskScenarioReadCursorCodecTest {
    private static final UUID ID=UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
    private final CurrentTenantContext context=new CurrentTenantContext(ID,ID,ID,ID,2,3,4);
    private ActionScope scope(CurrentTenantContext context,Set<UUID> organizations){var s=mock(ActionScope.class);when(s.context()).thenReturn(context);when(s.organizationIds()).thenReturn(organizations);when(s.action()).thenReturn(RISK_SCENARIO_READ);return s;}
    private QueryRequest query(){return new QueryRequest(List.of(),List.of(new Sort("id",Direction.ASC)),List.of("id"),20,1,null);}
    @Test void canonicalRoundTripCarriesUnsignedUuidAndSafeVersion(){
        var fingerprint=RiskScenarioReadCursorCodec.fingerprint(scope(context,Set.of(ID)),7,query());
        var anchor=new RiskScenarioReadCursorCodec.Anchor(UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"),MAX_VERSION);
        String token=RiskScenarioReadCursorCodec.encode(fingerprint,anchor);assertEquals(78,token.length());assertEquals(anchor,RiskScenarioReadCursorCodec.decode(token,fingerprint));
    }
    @Test void everyIdentityAndEpochAndScopeAndApplicationChangeRejectsOldToken(){
        var original=scope(context,Set.of(ID));byte[] fingerprint=RiskScenarioReadCursorCodec.fingerprint(original,7,query());String token=RiskScenarioReadCursorCodec.encode(fingerprint,new RiskScenarioReadCursorCodec.Anchor(ID,1));
        UUID other=new UUID(3,4);var variants=List.of(
                new CurrentTenantContext(other,ID,ID,ID,2,3,4),new CurrentTenantContext(ID,other,ID,ID,2,3,4),
                new CurrentTenantContext(ID,ID,other,ID,2,3,4),new CurrentTenantContext(ID,ID,ID,other,2,3,4),
                new CurrentTenantContext(ID,ID,ID,ID,5,3,4),new CurrentTenantContext(ID,ID,ID,ID,2,5,4),new CurrentTenantContext(ID,ID,ID,ID,2,3,5));
        for(var c:variants)assertThrows(Failure.class,()->RiskScenarioReadCursorCodec.decode(token,RiskScenarioReadCursorCodec.fingerprint(scope(c,Set.of(ID)),7,query())));
        assertThrows(Failure.class,()->RiskScenarioReadCursorCodec.decode(token,RiskScenarioReadCursorCodec.fingerprint(scope(context,Set.of(other)),7,query())));
        assertThrows(Failure.class,()->RiskScenarioReadCursorCodec.decode(token,RiskScenarioReadCursorCodec.fingerprint(original,8,query())));
    }
    @Test void filtersProjectionSortAndSizeBindButTransportPageDoesNot(){
        var scope=scope(context,Set.of(ID));byte[] fp=RiskScenarioReadCursorCodec.fingerprint(scope,7,query());String token=RiskScenarioReadCursorCodec.encode(fp,new RiskScenarioReadCursorCodec.Anchor(ID,1));
        var variants=List.of(new QueryRequest(List.of(),List.of(new Sort("code",Direction.DESC)),List.of("id"),20,1,null),
                new QueryRequest(List.of(),query().sort(),List.of("description"),20,1,null),new QueryRequest(List.of(),query().sort(),List.of("id"),50,1,null),
                new QueryRequest(List.of(new Filter("version",FilterOperator.GT,List.of(new BigDecimal("1e-999999")))),query().sort(),List.of("id"),20,1,null));
        for(var q:variants)assertThrows(Failure.class,()->RiskScenarioReadCursorCodec.decode(token,RiskScenarioReadCursorCodec.fingerprint(scope,7,q)));
        assertArrayEquals(fp,RiskScenarioReadCursorCodec.fingerprint(scope,7,new QueryRequest(query().filters(),query().sort(),query().fields(),20,null,token)));
    }
    @Test void malformedNoncanonicalForeignPurposeAndUnsafeAnchorFail(){
        byte[] fp=new byte[32];String token=RiskScenarioReadCursorCodec.encode(fp,new RiskScenarioReadCursorCodec.Anchor(ID,1));
        for(String bad:List.of("",token+"=",token.substring(1),"!".repeat(78)))assertThrows(Failure.class,()->RiskScenarioReadCursorCodec.decode(bad,fp));
        byte[] raw=Base64.getUrlDecoder().decode(token);raw[1]=6;String foreign=Base64.getUrlEncoder().withoutPadding().encodeToString(raw);assertThrows(Failure.class,()->RiskScenarioReadCursorCodec.decode(foreign,fp));
        raw[1]=100;java.nio.ByteBuffer.wrap(raw).putLong(50,MAX_VERSION+1);String unsafe=Base64.getUrlEncoder().withoutPadding().encodeToString(raw);assertThrows(Failure.class,()->RiskScenarioReadCursorCodec.decode(unsafe,fp));
    }
    @Test void organizationSetOrderIsStableAndNoDescriptionsAppearInToken(){
        UUID other=new UUID(4,5);var one=new LinkedHashSet<>(List.of(ID,other));var two=new LinkedHashSet<>(List.of(other,ID));
        assertArrayEquals(RiskScenarioReadCursorCodec.fingerprint(scope(context,one),7,query()),RiskScenarioReadCursorCodec.fingerprint(scope(context,two),7,query()));
        assertTrue(RiskScenarioReadCursorCodec.encode(new byte[32],new RiskScenarioReadCursorCodec.Anchor(ID,1)).matches("[A-Za-z0-9_-]{78}"));
    }
}

package com.mightyoung.softwarefactory.dualprevention.asset;

import com.mightyoung.softwarefactory.authorization.EntityActionAuthorization.ActionScope;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationEntitySchemas.Root.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssetFoundationQueryCursorTest {
    private final JsonMapper json=JsonMapper.builder().build();
    private AssetFoundationQueryContracts.Plan plan(String filter){return new AssetFoundationQueryValidator().validate(HAZARD_SOURCE,AssetFoundationReadJson.tree("{\"page\":{\"number\":1},\"size\":20,\"sort\":[{\"field\":\"description\",\"direction\":\"DESC\"}],\"filterAST\":"+filter+"}",json));}
    private ActionScope scope(){var context=mock(CurrentTenantContext.class);when(context.tenantId()).thenReturn(new UUID(1,2));when(context.actorId()).thenReturn(new UUID(3,4));when(context.accountId()).thenReturn(new UUID(7,8));when(context.memberId()).thenReturn(new UUID(9,10));var scope=mock(ActionScope.class);when(scope.context()).thenReturn(context);when(scope.organizationIds()).thenReturn(Set.of(new UUID(5,6)));return scope;}
    @Test void longestAllowedSortStillProducesFixed78CharacterAnchor(){
        var scope=scope();var plan=plan("null");byte[] fingerprint=AssetFoundationQueryCursor.fingerprint(HAZARD_SOURCE,plan,scope);
        UUID id=UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        String encoded=AssetFoundationQueryCursor.encode(HAZARD_SOURCE,fingerprint,id,AssetContracts.MAX_VERSION);
        assertThat(encoded).hasSize(78).doesNotContain("=");
        assertThat(AssetFoundationQueryCursor.decode(encoded,HAZARD_SOURCE,fingerprint)).isEqualTo(new AssetFoundationQueryCursor.Anchor(id,AssetContracts.MAX_VERSION));
    }
    @Test void wrongRootFingerprintAndNoncanonicalEncodingFail(){
        byte[] fingerprint=AssetFoundationQueryCursor.fingerprint(HAZARD_SOURCE,plan("null"),scope());
        String encoded=AssetFoundationQueryCursor.encode(HAZARD_SOURCE,fingerprint,UUID.randomUUID(),1);
        assertThatThrownBy(()->AssetFoundationQueryCursor.decode(encoded,WORKSHOP,fingerprint)).isInstanceOf(AssetFoundationFailure.class);
        byte[] different=fingerprint.clone();different[0]^=1;
        assertThatThrownBy(()->AssetFoundationQueryCursor.decode(encoded,HAZARD_SOURCE,different)).isInstanceOf(AssetFoundationFailure.class);
        assertThatThrownBy(()->AssetFoundationQueryCursor.decode(encoded+"=",HAZARD_SOURCE,fingerprint)).isInstanceOf(AssetFoundationFailure.class);
    }
    @Test void decimalSpellingsHaveOneFingerprintAndCursorDoesNotUsePageNumber(){
        String condition="{\"operator\":\"AND\",\"conditions\":[{\"field\":\"version\",\"operator\":\"EQ\",\"value\":%s}]}";
        var scope=scope();assertThat(AssetFoundationQueryCursor.fingerprint(HAZARD_SOURCE,plan(condition.formatted("1.00")),scope))
                .containsExactly(AssetFoundationQueryCursor.fingerprint(HAZARD_SOURCE,plan(condition.formatted("1e0")),scope));
    }
}

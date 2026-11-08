package com.mightyoung.softwarefactory.dualprevention.asset;

import com.mightyoung.softwarefactory.authorization.EntityActionAuthorization.ActionScope;
import com.mightyoung.softwarefactory.identity.CapturedHumanRequest;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationEntitySchemas.Root.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AssetFoundationQueryServiceTest {
    @Test void currentFixedReadAuthorizationPrecedesSemanticValidationAndQuery(){
        var reads=mock(AssetFoundationReadService.class);var snapshot=mock(AssetFoundationReadService.ReadSnapshot.class);var scope=mock(ActionScope.class);
        var request=mock(CapturedHumanRequest.class);var repository=mock(AssetFoundationQueryRepository.class);
        when(reads.inReadSnapshot(eq(request),any())).thenAnswer(call->{Function<AssetFoundationReadService.ReadSnapshot,Object> work=call.getArgument(1);return work.apply(snapshot);});
        when(snapshot.authorize(WORKSHOP.readAction())).thenReturn(scope);
        when(repository.query(eq(WORKSHOP),any(),eq(scope))).thenReturn(new AssetFoundationQueryContracts.Page(List.of(),new com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.PageMetadata(20,1,null,null)));
        var input=JsonMapper.builder().build().readTree("{\"page\":{\"number\":1},\"size\":20}");
        new AssetFoundationQueryService(reads,repository).query(request,WORKSHOP,input);
        var order=inOrder(snapshot,repository);order.verify(snapshot).authorize(WORKSHOP.readAction());order.verify(repository).query(eq(WORKSHOP),any(),eq(scope));order.verify(snapshot).requireActive();
        reset(snapshot,repository);when(snapshot.authorize(WORKSHOP.readAction())).thenThrow(new AssetFoundationFailure(403));
        assertThatThrownBy(()->new AssetFoundationQueryService(reads,repository).query(request,WORKSHOP,JsonMapper.builder().build().readTree("{}"))).isInstanceOfSatisfying(AssetFoundationFailure.class,failure->assertThat(failure.status()).isEqualTo(403));
        verifyNoInteractions(repository);
    }
}

package com.mightyoung.softwarefactory.dualprevention.asset;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;
import static com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationEntitySchemas.Root.*;
import static org.assertj.core.api.Assertions.*;

class AssetFoundationQueryValidationTest {
    private final JsonMapper json=JsonMapper.builder().build();
    private final AssetFoundationQueryValidator validator=new AssetFoundationQueryValidator();
    private AssetFoundationQueryContracts.Plan parse(String value){return validator.validate(WORKSHOP,AssetFoundationReadJson.tree(value,json));}
    @Test void defaultsOnlyStableIdAndDefaultProjection(){
        var plan=parse("{\"page\":{\"number\":1},\"size\":20,\"sort\":null,\"fields\":null}");
        assertThat(plan.sort()).extracting(AssetFoundationQueryContracts.Sort::field).containsExactly("id");
        assertThat(plan.fields()).containsExactlyElementsOf(AssetFoundationEntitySchemas.schema(WORKSHOP).defaultFields());
        assertThatThrownBy(()->plan.fields().add("tenantId")).isInstanceOf(UnsupportedOperationException.class);
    }
    @ParameterizedTest @ValueSource(ints={20,50,100}) void sizesUseSharedQueryContract(int size){assertThat(parse("{\"page\":{\"number\":1},\"size\":"+size+"}").size()).isEqualTo(size);}
    @Test void offsetBoundaryAndCursorAreDistinct(){
        assertThat(parse("{\"page\":{\"number\":501},\"size\":20}").offset()).isEqualTo(10000);
        assertThatThrownBy(()->parse("{\"page\":{\"number\":502},\"size\":20}")).isInstanceOf(AssetFoundationFailure.class);
        assertThatThrownBy(()->parse("{\"page\":{\"number\":1,\"cursor\":\"x\"},\"size\":20}")).isInstanceOf(AssetFoundationFailure.class);
    }
    @Test void exactDecimalAndTinyExponentAreNotCoercedToIntegerOrDouble(){
        var plan=parse("{\"page\":{\"number\":1},\"size\":20,\"filterAST\":{\"operator\":\"AND\",\"conditions\":[{\"field\":\"version\",\"operator\":\"GT\",\"value\":1.25},{\"field\":\"version\",\"operator\":\"LTE\",\"value\":1e-999999}]}}");
        assertThat(plan.filters().getFirst().values()).containsExactly(new BigDecimal("1.25"));
        assertThat((BigDecimal)plan.filters().get(1).values().getFirst()).isPositive().hasScaleOf(999999);
    }
    @ParameterizedTest @ValueSource(strings={
            "{\"page\":{\"number\":1},\"size\":20,\"actorId\":\"x\"}",
            "{\"page\":{\"number\":1},\"size\":20,\"size\":50}",
            "{\"page\":{\"number\":1.0},\"size\":20}",
            "{\"page\":{\"number\":1},\"size\":20} 1",
            "{\"page\":{\"number\":1},\"size\":20} {}",
            "{\"page\":{\"number\":1},\"size\":20,\"fields\":[\"id\",\"id\"]}"
    }) void malformedStructuredInputFails(String body){assertThatThrownBy(()->parse(body)).isInstanceOf(AssetFoundationFailure.class);}
    @Test void scalarRulesUseCanonicalUuidMicrosecondsAndCodePoints(){
        String prefix="{\"page\":{\"number\":1},\"size\":20,\"filterAST\":{\"operator\":\"AND\",\"conditions\":[";
        for(String condition:List.of("{\"field\":\"id\",\"operator\":\"EQ\",\"value\":\"AAAAAAAA-0000-0000-0000-000000000001\"}",
                "{\"field\":\"createdAt\",\"operator\":\"EQ\",\"value\":\"2026-11-04T00:00:00.1234567Z\"}",
                "{\"field\":\"name\",\"operator\":\"IS_NULL\",\"value\":null}"))
            assertThatThrownBy(()->parse(prefix+condition+"]}}")).isInstanceOf(AssetFoundationFailure.class);
        String supplementary="\uD83D\uDE00".repeat(200);
        assertThat(parse(prefix+"{\"field\":\"name\",\"operator\":\"EQ\",\"value\":\""+supplementary+"\"}]}}").filters()).hasSize(1);
        assertThatThrownBy(()->parse(prefix+"{\"field\":\"name\",\"operator\":\"EQ\",\"value\":\"\\uD800\"}]}}")).isInstanceOf(AssetFoundationFailure.class);
    }
    @Test void transportEnforcesRawBytesAndMediaBeforeJson(){
        var request=new MockHttpServletRequest();request.setContentType("application/json");request.setContent(new byte[32769]);
        assertThatThrownBy(()->new AssetFoundationQueryParser(json).parse(request)).isInstanceOfSatisfying(AssetFoundationFailure.class,error->assertThat(error.status()).isEqualTo(413));
        request.setContentType("application/json;charset=UTF-16");request.setContent("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(()->new AssetFoundationQueryParser(json).parse(request)).isInstanceOfSatisfying(AssetFoundationFailure.class,error->assertThat(error.status()).isEqualTo(415));
    }
}

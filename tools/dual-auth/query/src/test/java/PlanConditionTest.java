import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class PlanConditionTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test void unsupportedOrAmbiguousExpressionsRefuseRatherThanAllow() {
        for (String shape : new String[] {
            "{\"expression\":{\"operator\":\"unknown\",\"operands\":[{\"value\":\"x\"},{\"value\":\"x\"}]}}",
            "{\"expression\":{\"operator\":\"eq\",\"operands\":[{\"variable\":\"request.resource.attr.unknown\"},{\"value\":\"x\"}]}}",
            "{\"expression\":{\"operator\":\"eq\",\"operands\":[{\"value\":\"a\",\"variable\":\"request.resource.attr.owner\"},{\"value\":\"x\"}]}}",
            "{\"expression\":{\"operator\":\"eq\",\"operands\":[{\"variable\":\"request.resource.attr.owner\"},{\"value\":null}]}}",
            "{\"expression\":{\"operator\":\"and\",\"operands\":[]}}",
            "{\"expression\":{\"operator\":\"in\",\"operands\":[{\"variable\":\"request.resource.attr.owner\"},{\"value\":[\"x\",null]}]}}"
        }) {
            var filter = json.readTree("{\"kind\":\"KIND_CONDITIONAL\",\"condition\":" + shape + "}");
            assertThrows(IllegalArgumentException.class, () -> PlanCondition.from(filter), shape);
        }
    }
}

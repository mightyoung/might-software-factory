package com.mightyoung.softwarefactory.platformadmin;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** Coordinates retain their integer JSON contract instead of being truncated or coerced from text. */
public final class WorkflowCoordinateDeserializer extends ValueDeserializer<Integer> {
    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
            return context.reportInputMismatch(Integer.class, "Workflow coordinate must be an integer");
        }
        return parser.getIntValue();
    }
}

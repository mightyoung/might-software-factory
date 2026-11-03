package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.EntitySchema;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Legacy v5 query view of the single code-published Finding definition. */
@Component
public final class FindingEntitySchema {
    public static final String SCHEMA_VERSION = "5";
    public static final List<String> DEFAULT_FIELDS =
            List.of("id", "code", "organizationId", "organizationName", "description", "discoveredAt",
                    "sourceCategory", "urgency", "location", "interimMeasure", "backfill",
                    "sourceEventId", "riskId", "relatedFindingId", "grade", "state", "roundNo", "createdAt");

    private final EntitySchema schema;
    public FindingEntitySchema() { this(new FindingDefinitionProvider()); }
    @Autowired public FindingEntitySchema(FindingDefinitionProvider provider) {
        schema = provider.publication().definition().querySchema();
    }
    public EntitySchema schema() { return schema; }
}

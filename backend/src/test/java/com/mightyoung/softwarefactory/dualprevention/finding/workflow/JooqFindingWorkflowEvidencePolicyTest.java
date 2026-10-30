package com.mightyoung.softwarefactory.dualprevention.finding.workflow;

import org.jooq.DSLContext;
import org.jooq.Record1;
import org.jooq.SQLDialect;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.using;

class JooqFindingWorkflowEvidencePolicyTest {

    private static final UUID TENANT_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID FINDING_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID SUBMITTER_ID = UUID.fromString("20000000-0000-0000-0000-000000000004");
    private static final UUID EVIDENCE_SET_ID = UUID.fromString("50000000-0000-0000-0000-000000000001");

    @Test
    void rejectsEvidenceNotBoundToThisFindingRoundUploaderAndFullyVerified() {
        List<String> statements = new ArrayList<>();
        List<List<Object>> bindings = new ArrayList<>();
        DSLContext values = using(SQLDialect.POSTGRES);
        MockDataProvider provider = context -> {
            statements.add(context.sql());
            bindings.add(Arrays.asList(context.bindings()));
            var readyField = field("exists", Boolean.class);
            var result = values.newResult(readyField);
            Record1<Boolean> row = values.newRecord(readyField);
            row.set(readyField, false);
            result.add(row);
            return new MockResult[] {new MockResult(1, result)};
        };
        DSLContext dsl = using(new MockConnection(provider), SQLDialect.POSTGRES);
        JooqFindingWorkflowEvidencePolicy policy = new JooqFindingWorkflowEvidencePolicy(dsl);

        assertThatThrownBy(() -> policy.requireReady(TENANT_ID, FINDING_ID, 2, SUBMITTER_ID, EVIDENCE_SET_ID))
                .isInstanceOf(FindingWorkflowEvidenceException.class);

        String sql = statements.getFirst().toLowerCase(java.util.Locale.ROOT);
        assertThat(sql).contains("es.finding_id", "es.round_no", "es.owner_member_id",
                "ei.verification_level", "content", "not exists");
        assertThat(bindings.getFirst()).contains(TENANT_ID, EVIDENCE_SET_ID, FINDING_ID, 2, SUBMITTER_ID);
    }
}

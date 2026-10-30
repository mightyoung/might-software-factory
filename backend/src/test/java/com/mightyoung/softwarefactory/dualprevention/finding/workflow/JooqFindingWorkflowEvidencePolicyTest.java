package com.mightyoung.softwarefactory.dualprevention.finding.workflow;

import com.mightyoung.softwarefactory.files.EvidenceFileUse;
import com.mightyoung.softwarefactory.files.FileEvidenceTrustUnavailableException;
import com.mightyoung.softwarefactory.files.FindingEvidenceFileAccess;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.using;

class JooqFindingWorkflowEvidencePolicyTest {

    private static final UUID TENANT_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID ACTOR_ID = UUID.fromString("10000000-0000-0000-0000-000000000004");
    private static final UUID ACCOUNT_ID = UUID.fromString("10000000-0000-0000-0000-000000000005");
    private static final UUID FINDING_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID SUBMITTER_ID = UUID.fromString("20000000-0000-0000-0000-000000000004");
    private static final UUID EVIDENCE_SET_ID = UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final UUID EVIDENCE_ITEM_ID = UUID.fromString("51000000-0000-0000-0000-000000000001");
    private static final UUID FILE_ID = UUID.fromString("52000000-0000-0000-0000-000000000001");

    @Test
    void locksEvidenceRowsAndPassesExactServerScopeToTrustedFileAccess() {
        List<String> statements = new ArrayList<>();
        List<List<Object>> bindings = new ArrayList<>();
        DSLContext values = using(SQLDialect.POSTGRES);
        MockDataProvider provider = context -> {
            statements.add(context.sql());
            bindings.add(Arrays.asList(context.bindings()));
            if (context.sql().contains("public.evidence_set")) {
                return evidenceSetResult(values, SUBMITTER_ID, "READY", true);
            }
            return evidenceItemResult(values, EVIDENCE_ITEM_ID, FILE_ID, "FILE", "READY", "CONTENT");
        };
        DSLContext dsl = using(new MockConnection(provider), SQLDialect.POSTGRES);
        AtomicReference<EvidenceFileUse> authorizedUse = new AtomicReference<>();
        FindingEvidenceFileAccess fileAccess = use -> {
            authorizedUse.set(use);
            throw new FileEvidenceTrustUnavailableException();
        };
        var policy = new JooqFindingWorkflowEvidencePolicy(dsl, fileAccess);
        CurrentTenantContext context = new CurrentTenantContext(
                ACTOR_ID, ACCOUNT_ID, TENANT_ID, SUBMITTER_ID, 4, 9);

        assertThatThrownBy(() -> policy.requireReady(context, FINDING_ID, 2, EVIDENCE_SET_ID))
                .isInstanceOf(FileEvidenceTrustUnavailableException.class);

        assertThat(statements).hasSize(2);
        String setSql = statements.get(0).toLowerCase(java.util.Locale.ROOT);
        assertThat(setSql).contains("es.finding_id", "es.round_no", "for update of es");
        String itemSql = statements.get(1).toLowerCase(java.util.Locale.ROOT);
        assertThat(itemSql).contains("ei.id", "ei.file_id", "ei.state", "ei.verification_level",
                "order by ei.id", "for update of ei");
        assertThat(bindings.get(0)).contains(TENANT_ID, EVIDENCE_SET_ID, FINDING_ID, 2);
        assertThat(bindings.get(1)).contains(TENANT_ID, EVIDENCE_SET_ID);
        assertThat(authorizedUse.get()).isEqualTo(new EvidenceFileUse(
                TENANT_ID, ACTOR_ID, SUBMITTER_ID, FINDING_ID, 2, EVIDENCE_SET_ID,
                SUBMITTER_ID, EVIDENCE_ITEM_ID, FILE_ID, "CONTENT"));
    }

    @Test
    void rejectsDatabaseOnlyContentClaimAndUnsupportedExternalUrlItems() {
        assertItemRejected("FILE", "READY", "METADATA");
        assertItemRejected("URL", "READY", "CONTENT");
        assertItemRejected("FILE", "QUARANTINED", "CONTENT");
    }

    private static void assertItemRejected(String sourceKind, String state, String level) {
        DSLContext values = using(SQLDialect.POSTGRES);
        MockDataProvider provider = context -> context.sql().contains("public.evidence_set")
                ? evidenceSetResult(values, SUBMITTER_ID, "READY", true)
                : evidenceItemResult(values, EVIDENCE_ITEM_ID, FILE_ID, sourceKind, state, level);
        DSLContext dsl = using(new MockConnection(provider), SQLDialect.POSTGRES);
        AtomicReference<EvidenceFileUse> authorizedUse = new AtomicReference<>();
        FindingEvidenceFileAccess fileAccess = authorizedUse::set;
        var policy = new JooqFindingWorkflowEvidencePolicy(dsl, fileAccess);
        CurrentTenantContext context = new CurrentTenantContext(
                ACTOR_ID, ACCOUNT_ID, TENANT_ID, SUBMITTER_ID, 4, 9);

        assertThatThrownBy(() -> policy.requireReady(context, FINDING_ID, 2, EVIDENCE_SET_ID))
                .isInstanceOf(FindingWorkflowEvidenceException.class);
        assertThat(authorizedUse).hasValue(null);
    }

    private static MockResult[] evidenceSetResult(
            DSLContext dsl, UUID ownerMemberId, String state, boolean verified) {
        Field<UUID> owner = nameField("owner_member_id", UUID.class);
        Field<String> stateField = nameField("state", String.class);
        Field<OffsetDateTime> verifiedAt = nameField("verified_at", OffsetDateTime.class);
        var result = dsl.newResult(owner, stateField, verifiedAt);
        var row = dsl.newRecord(owner, stateField, verifiedAt);
        row.set(owner, ownerMemberId);
        row.set(stateField, state);
        row.set(verifiedAt, verified ? OffsetDateTime.parse("2026-10-30T10:00:00Z") : null);
        result.add(row);
        return new MockResult[] {new MockResult(1, result)};
    }

    private static MockResult[] evidenceItemResult(
            DSLContext dsl, UUID itemId, UUID fileId, String sourceKind, String state, String level) {
        Field<UUID> id = nameField("id", UUID.class);
        Field<String> source = nameField("source_kind", String.class);
        Field<UUID> file = nameField("file_id", UUID.class);
        Field<String> stateField = nameField("state", String.class);
        Field<String> verification = nameField("verification_level", String.class);
        var result = dsl.newResult(id, source, file, stateField, verification);
        var row = dsl.newRecord(id, source, file, stateField, verification);
        row.set(id, itemId);
        row.set(source, sourceKind);
        row.set(file, fileId);
        row.set(stateField, state);
        row.set(verification, level);
        result.add(row);
        return new MockResult[] {new MockResult(1, result)};
    }

    private static <T> Field<T> nameField(String name, Class<T> type) {
        return field(name(name), type);
    }
}

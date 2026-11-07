package com.mightyoung.softwarefactory.dualprevention.finding;

import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.Direction;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FieldType;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterCondition;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryValidator.ValidatedQuery;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SortField;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator.BETWEEN;
import static com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator.CONTAINS;
import static com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator.EQ;
import static com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator.GT;
import static com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator.GTE;
import static com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator.IN;
import static com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator.IS_EMPTY;
import static com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator.IS_NOT_EMPTY;
import static com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator.IS_NOT_NULL;
import static com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator.IS_NULL;
import static com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator.LT;
import static com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator.LTE;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

/** jOOQ read repository with transaction-local tenant RLS and explicit organization scope predicates. */
@Repository
public final class JooqFindingRepository implements FindingRepository {

    private static final int HISTORY_LIMIT = 200;
    private static final int SUBMISSION_LIMIT = 20;
    private static final int SUBMITTED_ITEM_LIMIT = 100;

    private static final Table<?> FINDING = table(name("public", "finding")).as("finding");
    private static final Table<?> ORGANIZATION = table(name("public", "org_unit")).as("reportable_organization");
    private static final Field<UUID> ID = field(name("finding", "id"), UUID.class);
    private static final Field<UUID> TENANT_ID = field(name("finding", "tenant_id"), UUID.class);
    private static final Field<UUID> ORGANIZATION_ID = field(name("finding", "organization_id"), UUID.class);
    private static final Field<UUID> ORGANIZATION_TENANT_ID =
            field(name("reportable_organization", "tenant_id"), UUID.class);
    private static final Field<UUID> ORGANIZATION_KEY = field(name("reportable_organization", "id"), UUID.class);
    private static final Field<String> ORGANIZATION_NAME =
            field(name("reportable_organization", "name"), String.class).as("organization_name");
    private static final Field<String> CODE = field(name("finding", "code"), String.class);
    private static final Field<String> DESCRIPTION = field(name("finding", "description"), String.class);
    private static final Field<OffsetDateTime> DISCOVERED_AT =
            field(name("finding", "discovered_at"), OffsetDateTime.class);
    private static final Field<UUID> SOURCE_EVENT_ID = field(name("finding", "source_event_id"), UUID.class);
    private static final Field<UUID> RISK_ID = field(name("finding", "risk_id"), UUID.class);
    private static final Field<UUID> RELATED_FINDING_ID = field(name("finding", "related_finding_id"), UUID.class);
    private static final Field<String> SOURCE_CATEGORY = field(name("finding", "source_category"), String.class);
    private static final Field<String> URGENCY = field(name("finding", "urgency"), String.class);
    private static final Field<String> LOCATION = field(name("finding", "location"), String.class);
    private static final Field<String> INTERIM_MEASURE = field(name("finding", "interim_measure"), String.class);
    private static final Field<Boolean> BACKFILL = field(name("finding", "backfill"), Boolean.class);
    private static final Field<String> GRADE = field(name("finding", "grade"), String.class);
    private static final Field<String> STATE = field(name("finding", "state"), String.class);
    private static final Field<Integer> ROUND_NO = field(name("finding", "round_no"), Integer.class);
    private static final Field<Integer> REVIEW_CYCLE = field(name("finding", "review_cycle"), Integer.class);
    private static final Field<String> REVIEW_PROTOCOL = field(name("finding", "review_protocol"), String.class);
    private static final Field<Long> VERSION = field(name("finding", "version"), Long.class);
    private static final Field<OffsetDateTime> CREATED_AT = field(name("finding", "created_at"), OffsetDateTime.class);
    private static final Field<OffsetDateTime> UPDATED_AT = field(name("finding", "updated_at"), OffsetDateTime.class);
    private static final Map<String, Field<?>> API_FIELDS = apiFields();
    private static final List<Field<?>> ALL_FIELDS = List.copyOf(API_FIELDS.values());

    private final DSLContext dsl;
    private final TransactionTemplate readTransaction;

    public JooqFindingRepository(DSLContext dsl, PlatformTransactionManager transactionManager) {
        this.dsl = dsl;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        this.readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    public PageResult findPage(FindingReadScope scope, ValidatedQuery query) {
        requireNonEmptyScope(scope);
        return readTransaction.execute(status -> {
            setLocalTenant(scope.tenantId());
            Condition where = scopeCondition(scope).and(filterCondition(query.filters()));
            long total = dsl.selectCount().from(FINDING).where(where).fetchOne(0, Long.class);
            List<Field<?>> projection = query.fields().stream().map(JooqFindingRepository::apiField).toList();
            List<SortField<?>> ordering = query.sort().stream().map(JooqFindingRepository::sortField).toList();
            int offset = Math.toIntExact((long) (query.page().number() - 1) * query.size());
            List<FindingRecord> items = dsl.select(projection)
                    .from(FINDING)
                    .leftJoin(ORGANIZATION).on(organizationJoinCondition())
                    .where(where)
                    .orderBy(ordering)
                    .offset(offset)
                    .limit(query.size())
                    .fetch(JooqFindingRepository::toRecord);
            return new PageResult(items, total);
        });
    }

    @Override
    public Optional<FindingRecord> findVisible(FindingReadScope scope, UUID findingId) {
        requireNonEmptyScope(scope);
        return readTransaction.execute(status -> {
            setLocalTenant(scope.tenantId());
            return dsl.select(ALL_FIELDS)
                    .from(FINDING)
                    .leftJoin(ORGANIZATION).on(organizationJoinCondition())
                    .where(scopeCondition(scope).and(ID.eq(findingId)))
                    .limit(1)
                    .fetchOptional(JooqFindingRepository::toRecord);
        });
    }

    @Override
    public Optional<FindingDetailContext> findDetailContext(FindingReadScope scope, UUID findingId) {
        requireNonEmptyScope(scope);
        return readTransaction.execute(status -> {
            setLocalTenant(scope.tenantId());
            UUID[] organizations = scope.organizationIds().toArray(UUID[]::new);
            Record members = dsl.fetchOne("""
                    select f.created_by_member_id as reporter_id, reporter.display_name as reporter_name,
                           notice.assignee_id as responsible_id, responsible.display_name as responsible_name
                      from public.finding f
                      left join public.tenant_member reporter
                        on reporter.tenant_id = f.tenant_id and reporter.id = f.created_by_member_id
                      left join public.remediation_notice notice
                        on notice.tenant_id = f.tenant_id and notice.finding_id = f.id
                       and notice.round_no = f.round_no
                      left join public.tenant_member responsible
                        on responsible.tenant_id = notice.tenant_id and responsible.id = notice.assignee_id
                     where f.tenant_id = cast(? as uuid) and f.id = cast(? as uuid)
                       and f.organization_id = any(?)
                    """, scope.tenantId(), findingId, organizations);
            if (members == null) return Optional.empty();

            List<FindingDetailContext.HistoryEntry> history = new ArrayList<>(dsl.fetch("""
                    select transition.id, transition.occurred_at, transition.from_state,
                           transition.to_state, transition.from_version, transition.to_version,
                           actor.display_name as actor_name, transition.reason
                      from public.finding_transition transition
                      join public.finding f
                        on f.tenant_id = transition.tenant_id and f.id = transition.finding_id
                      left join public.principal principal on principal.id = transition.actor_id
                      left join public.tenant_member actor
                        on actor.tenant_id = transition.tenant_id and actor.account_id = principal.account_id
                     where f.tenant_id = cast(? as uuid) and f.id = cast(? as uuid)
                       and f.organization_id = any(?)
                     order by transition.to_version desc, transition.id desc
                     limit ?
                    """, scope.tenantId(), findingId, organizations, HISTORY_LIMIT + 1)
                    .map(row -> new FindingDetailContext.HistoryEntry(
                            row.get("id", UUID.class), row.get("occurred_at", OffsetDateTime.class),
                            row.get("from_state", String.class), row.get("to_state", String.class),
                            row.get("from_version", Long.class), row.get("to_version", Long.class),
                            row.get("actor_name", String.class), row.get("reason", String.class))));
            boolean truncated = history.size() > HISTORY_LIMIT;
            if (truncated) history.removeLast();
            Collections.reverse(history);
            List<Record> submissions = new ArrayList<>(dsl.fetch("""
                    select submission.id, submission.round_no, submission.statement, submission.submitted_at,
                           submission.submitted_by, submitter.display_name as submitter_name,
                           submission.evidence_snapshot_count, submission.evidence_snapshot_manifest,
                           submission.evidence_snapshots_sealed_at
                      from public.remediation_submission submission
                      join public.finding f on f.tenant_id = submission.tenant_id and f.id = submission.finding_id
                      left join public.tenant_member submitter
                        on submitter.tenant_id = submission.tenant_id and submitter.id = submission.submitted_by
                     where f.tenant_id = cast(? as uuid) and f.id = cast(? as uuid)
                       and f.organization_id = any(?)
                     order by submission.round_no desc, submission.id desc
                     limit ?
                    """, scope.tenantId(), findingId, organizations, SUBMISSION_LIMIT + 1));
            boolean submissionsTruncated = submissions.size() > SUBMISSION_LIMIT;
            if (submissionsTruncated) submissions.removeLast();
            List<FindingDetailContext.SubmittedRemediation> submitted = submittedEvidence(
                    scope, findingId, organizations, submissions);
            Collections.reverse(submitted);
            UUID responsibleId = members.get("responsible_id", UUID.class);
            return Optional.of(new FindingDetailContext(
                    new FindingDetailContext.Member(members.get("reporter_id", UUID.class),
                            members.get("reporter_name", String.class)),
                    responsibleId == null ? null : new FindingDetailContext.Member(responsibleId,
                            members.get("responsible_name", String.class)), history, truncated,
                    submitted, submissionsTruncated));
        });
    }

    private List<FindingDetailContext.SubmittedRemediation> submittedEvidence(
            FindingReadScope scope, UUID findingId, UUID[] organizations, List<Record> submissions) {
        if (submissions.isEmpty()) return new ArrayList<>();
        Map<UUID, List<FindingDetailContext.SubmittedEvidence>> evidence = new LinkedHashMap<>();
        Map<UUID, java.util.Set<UUID>> manifests = new LinkedHashMap<>();
        for (Record submission : submissions) {
            UUID id = submission.get("id", UUID.class);
            Integer count = submission.get("evidence_snapshot_count", Integer.class);
            UUID[] manifest = submission.get("evidence_snapshot_manifest", UUID[].class);
            if (submission.get("evidence_snapshots_sealed_at") == null || count == null || count < 1
                    || count > SUBMITTED_ITEM_LIMIT || manifest == null || manifest.length != count) {
                throw new com.mightyoung.softwarefactory.files.FileEvidenceTrustUnavailableException();
            }
            var ids = new HashSet<>(java.util.Arrays.asList(manifest));
            if (ids.contains(null) || ids.size() != count) {
                throw new com.mightyoung.softwarefactory.files.FileEvidenceTrustUnavailableException();
            }
            manifests.put(id, ids);
            evidence.put(id, new ArrayList<>());
        }
        var rows = dsl.fetch("""
                select submission.id as submission_id, snapshot.evidence_item_id, snapshot.file_id,
                       snapshot.sha256, snapshot.verification_level
                  from public.remediation_submission submission
                  join public.finding f on f.tenant_id = submission.tenant_id and f.id = submission.finding_id
                  join lateral (
                      select pinned.evidence_item_id, pinned.file_id, pinned.sha256, pinned.verification_level
                        from public.remediation_submission_evidence pinned
                       where pinned.tenant_id = submission.tenant_id and pinned.submission_id = submission.id
                         and pinned.finding_id = submission.finding_id and pinned.round_no = submission.round_no
                         and pinned.submitted_by = submission.submitted_by
                         and pinned.evidence_set_id = submission.evidence_set_id
                         and pinned.evidence_item_id = any(submission.evidence_snapshot_manifest)
                       order by pinned.evidence_item_id
                       limit 101
                  ) snapshot on true
                 where f.tenant_id = cast(? as uuid) and f.id = cast(? as uuid)
                   and f.organization_id = any(?) and submission.id = any(?)
                 order by submission.id, snapshot.evidence_item_id
                 limit 2001
                """, scope.tenantId(), findingId, organizations, evidence.keySet().toArray(UUID[]::new));
        if (rows.size() > SUBMISSION_LIMIT * SUBMITTED_ITEM_LIMIT) {
            throw new com.mightyoung.softwarefactory.files.FileEvidenceTrustUnavailableException();
        }
        for (Record row : rows) {
            UUID submissionId = row.get("submission_id", UUID.class);
            UUID itemId = row.get("evidence_item_id", UUID.class);
            String level = row.get("verification_level", String.class);
            if (!manifests.containsKey(submissionId) || !manifests.get(submissionId).remove(itemId)
                    || !("CONTENT".equals(level) || "SCANNED_CONTENT_V2".equals(level))) {
                throw new com.mightyoung.softwarefactory.files.FileEvidenceTrustUnavailableException();
            }
            evidence.get(submissionId).add(new FindingDetailContext.SubmittedEvidence(itemId,
                    row.get("file_id", UUID.class), row.get("sha256", String.class), level));
        }
        List<FindingDetailContext.SubmittedRemediation> result = new ArrayList<>();
        for (Record row : submissions) {
            UUID id = row.get("id", UUID.class);
            if (!manifests.get(id).isEmpty()) {
                throw new com.mightyoung.softwarefactory.files.FileEvidenceTrustUnavailableException();
            }
            result.add(new FindingDetailContext.SubmittedRemediation(id, row.get("round_no", Integer.class),
                    row.get("statement", String.class), row.get("submitted_at", OffsetDateTime.class),
                    row.get("submitted_by", UUID.class), row.get("submitter_name", String.class), evidence.get(id)));
        }
        return result;
    }

    private void setLocalTenant(UUID tenantId) {
        String applied = (String) dsl.fetchValue("select set_config(?, ?, true)",
                "app.tenant_id", tenantId.toString());
        if (!tenantId.toString().equals(applied)) {
            throw new IllegalStateException("Unable to apply the finding tenant context");
        }
    }

    private static Condition scopeCondition(FindingReadScope scope) {
        return TENANT_ID.eq(scope.tenantId()).and(ORGANIZATION_ID.in(scope.organizationIds()));
    }

    private static Condition organizationJoinCondition() {
        return ORGANIZATION_TENANT_ID.eq(TENANT_ID).and(ORGANIZATION_KEY.eq(ORGANIZATION_ID));
    }

    private static Condition filterCondition(List<FilterCondition> filters) {
        Condition condition = DSL.noCondition();
        for (FilterCondition filter : filters) {
            condition = condition.and(filterCondition(filter));
        }
        return condition;
    }

    private static Condition filterCondition(FilterCondition filter) {
        Field<Object> column = objectField(filter.field());
        FilterOperator operator = filter.operator();
        if (operator == IS_NULL) return column.isNull();
        if (operator == IS_NOT_NULL) return column.isNotNull();
        if (operator == IS_EMPTY) return column.eq("");
        if (operator == IS_NOT_EMPTY) return column.ne("");

        FieldType type = fieldType(filter.field());
        if (operator == IN) {
            List<Object> values = new ArrayList<>();
            filter.value().forEach(value -> values.add(databaseValue(filter.field(), type, value)));
            return column.in(values);
        }
        if (operator == BETWEEN) {
            Object lower = databaseValue(filter.field(), type, filter.value().get(0));
            Object upper = databaseValue(filter.field(), type, filter.value().get(1));
            return column.between(lower).and(upper);
        }
        Object value = databaseValue(filter.field(), type, filter.value());
        return switch (operator) {
            case EQ -> column.eq(value);
            case GT -> column.gt(value);
            case GTE -> column.ge(value);
            case LT -> column.lt(value);
            case LTE -> column.le(value);
            case CONTAINS -> ((Field<String>) API_FIELDS.get(filter.field())).contains((String) value);
            default -> throw new IllegalArgumentException("Unsupported finding filter operator");
        };
    }

    private static Object databaseValue(String key, FieldType type, tools.jackson.databind.JsonNode value) {
        try {
            return switch (type) {
                case TEXT, ENUM -> value.stringValue();
                case UUID, USER, ORGANIZATION, REFERENCE -> UUID.fromString(value.stringValue());
                case DATETIME -> OffsetDateTime.parse(value.stringValue());
                case DATE -> java.time.LocalDate.parse(value.stringValue());
                case BOOLEAN -> value.booleanValue();
                case NUMBER -> (key.equals("roundNo") || key.equals("reviewCycle"))
                        ? value.decimalValue().intValueExact()
                        : value.decimalValue().longValueExact();
                case MONEY -> new BigDecimal(value.numberValue().toString());
                case IMAGE, ATTACHMENT -> throw new IllegalArgumentException("Unsupported finding filter type");
            };
        } catch (IllegalArgumentException | DateTimeParseException | ArithmeticException exception) {
            throw new IllegalArgumentException("Validated finding filter could not be converted", exception);
        }
    }

    private static SortField<?> sortField(
            com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.SortField sort) {
        Field<?> field = apiField(sort.field());
        return sort.direction() == Direction.ASC ? field.asc() : field.desc();
    }

    private static Field<?> apiField(String key) {
        Field<?> selected = API_FIELDS.get(key);
        if (selected == null) {
            throw new IllegalArgumentException("Finding field is not registered");
        }
        return selected;
    }

    @SuppressWarnings("unchecked")
    private static Field<Object> objectField(String key) {
        return (Field<Object>) API_FIELDS.get(key);
    }

    private static FieldType fieldType(String key) {
        return switch (key) {
            case "id", "organizationId", "sourceEventId", "riskId", "relatedFindingId" -> FieldType.UUID;
            case "code", "description", "organizationName" -> FieldType.TEXT;
            case "grade", "state", "sourceCategory", "urgency", "reviewProtocol" -> FieldType.ENUM;
            case "location", "interimMeasure" -> FieldType.TEXT;
            case "backfill" -> FieldType.BOOLEAN;
            case "discoveredAt", "createdAt", "updatedAt" -> FieldType.DATETIME;
            case "roundNo", "version", "reviewCycle" -> FieldType.NUMBER;
            default -> throw new IllegalArgumentException("Finding field is not registered");
        };
    }

    private static FindingRecord toRecord(Record row) {
        return new FindingRecord(
                selectedValue(row, ID), selectedValue(row, CODE), selectedValue(row, DESCRIPTION),
                selectedValue(row, DISCOVERED_AT), selectedValue(row, SOURCE_EVENT_ID), selectedValue(row, RISK_ID),
                selectedValue(row, GRADE), selectedValue(row, STATE), selectedValue(row, ROUND_NO),
                selectedValue(row, VERSION), selectedValue(row, CREATED_AT), selectedValue(row, UPDATED_AT),
                selectedValue(row, ORGANIZATION_ID), selectedValue(row, ORGANIZATION_NAME),
                selectedValue(row, SOURCE_CATEGORY), selectedValue(row, URGENCY), selectedValue(row, LOCATION),
                selectedValue(row, INTERIM_MEASURE), selectedValue(row, BACKFILL),
                selectedValue(row, RELATED_FINDING_ID), selectedValue(row, REVIEW_CYCLE), selectedValue(row, REVIEW_PROTOCOL));
    }

    private static <T> T selectedValue(Record row, Field<T> field) {
        boolean selected = java.util.Arrays.stream(row.fields())
                .anyMatch(resultField -> resultField.getName().equals(field.getName()));
        return selected ? row.get(field) : null;
    }

    private static void requireNonEmptyScope(FindingReadScope scope) {
        if (scope == null || scope.organizationIds().isEmpty()) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "A non-empty finding data scope is required");
        }
    }

    private static Map<String, Field<?>> apiFields() {
        Map<String, Field<?>> fields = new LinkedHashMap<>();
        fields.put("id", ID);
        fields.put("code", CODE);
        fields.put("organizationId", ORGANIZATION_ID);
        fields.put("organizationName", ORGANIZATION_NAME);
        fields.put("description", DESCRIPTION);
        fields.put("discoveredAt", DISCOVERED_AT);
        fields.put("sourceEventId", SOURCE_EVENT_ID);
        fields.put("riskId", RISK_ID);
        fields.put("relatedFindingId", RELATED_FINDING_ID);
        fields.put("sourceCategory", SOURCE_CATEGORY);
        fields.put("urgency", URGENCY);
        fields.put("location", LOCATION);
        fields.put("interimMeasure", INTERIM_MEASURE);
        fields.put("backfill", BACKFILL);
        fields.put("grade", GRADE);
        fields.put("state", STATE);
        fields.put("roundNo", ROUND_NO);
        fields.put("version", VERSION);
        fields.put("reviewCycle", REVIEW_CYCLE);
        fields.put("reviewProtocol", REVIEW_PROTOCOL);
        fields.put("createdAt", CREATED_AT);
        fields.put("updatedAt", UPDATED_AT);
        return Map.copyOf(fields);
    }
}

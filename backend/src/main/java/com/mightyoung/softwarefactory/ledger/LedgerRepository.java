package com.mightyoung.softwarefactory.ledger;

import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryValidator.ValidatedQuery;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.SortField;
import org.jooq.Table;
import org.jooq.impl.DSL;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Registered identifiers only; tenant predicates and technical metadata remain owned by this runtime. */
final class LedgerRepository {
    record Row(UUID id,long version,Map<String,Object> values) {
        Row { values=Collections.unmodifiableMap(new LinkedHashMap<>(values)); }
    }
    record Receipt(String hash,LedgerContracts.CommandData result) { }
    private final ObjectMapper json;
    LedgerRepository(ObjectMapper json) { this.json=json; }
    private static Table<?> table(LedgerBinding binding) { return DSL.table(DSL.name("public",binding.storage().table())).as("root"); }
    private static Field<UUID> id() { return DSL.field(DSL.name("root","id"),UUID.class); }
    private static Field<Long> version() { return DSL.field(DSL.name("root","version"),Long.class); }
    private static Field<Object> column(LedgerBinding binding,String key) {
        if("id".equals(key)) return DSL.field(DSL.name("root","id"),Object.class);
        String registered=binding.storage().fieldColumns().get(key);
        if(registered==null) throw new LedgerException(400,"INVALID_QUERY");
        return DSL.field(DSL.name("root",registered),Object.class);
    }
    private static Condition tenant(LedgerExecution execution) {
        return DSL.field(DSL.name("root","tenant_id"),UUID.class).eq(execution.decision().context().tenantId());
    }
    private Condition visibility(LedgerBinding binding,LedgerExecution execution) {
        execution.requireCurrent(); Condition policy=Objects.requireNonNull(binding.scopePolicy().visible(execution));
        execution.requireCurrent(); return tenant(execution).and(policy);
    }
    private static List<Field<?>> projection(LedgerBinding binding) {
        List<Field<?>> selected=new ArrayList<>(); selected.add(id()); selected.add(version());
        binding.storage().fieldColumns().forEach((logical,physical)->selected.add(DSL.field(DSL.name("root",physical),Object.class).as(logical)));
        return selected;
    }
    Row find(LedgerBinding binding,LedgerExecution execution,UUID recordId,boolean lock) {
        var query=execution.dsl().select(projection(binding)).from(table(binding)).where(visibility(binding,execution).and(id().eq(recordId)));
        Record record=lock ? query.forUpdate().fetchOne() : query.fetchOne();
        execution.requireCurrent();
        if(record==null) throw new LedgerException(404,"RESOURCE_NOT_VISIBLE");
        return row(binding,record);
    }
    boolean visible(LedgerBinding binding,LedgerExecution execution,UUID recordId) {
        boolean result=execution.dsl().fetchExists(execution.dsl().selectOne().from(table(binding)).where(visibility(binding,execution).and(id().eq(recordId))));
        execution.requireCurrent(); return result;
    }
    List<Row> query(LedgerBinding binding,LedgerExecution execution,ValidatedQuery query) {
        var condition=visibility(binding,execution).and(filters(binding,query));
        List<SortField<?>> sort=new ArrayList<>();
        for(var field:query.sort()) sort.add(field.direction()==EntityQueryContract.Direction.ASC ? column(binding,field.field()).asc() : column(binding,field.field()).desc());
        List<Row> result=execution.dsl().select(projection(binding)).from(table(binding)).where(condition).orderBy(sort)
                .limit(query.size()).offset((query.page().number()-1)*query.size()).fetch(record->row(binding,record));
        execution.requireCurrent(); return result;
    }
    long count(LedgerBinding binding,LedgerExecution execution,ValidatedQuery query) {
        Long result=execution.dsl().select(DSL.count()).from(table(binding)).where(visibility(binding,execution).and(filters(binding,query))).fetchOne(0,Long.class);
        execution.requireCurrent(); return Objects.requireNonNull(result);
    }
    private static Condition filters(LedgerBinding binding,ValidatedQuery query) {
        Condition condition=DSL.trueCondition();
        for(var filter:query.filters()) {
            Field<Object> field=column(binding,filter.field());
            Object value=scalar(binding,filter.field(),filter.value());
            Condition item=switch(filter.operator()) {
                case EQ -> field.eq(value);
                case GT -> field.gt(value); case GTE -> field.ge(value); case LT -> field.lt(value); case LTE -> field.le(value);
                case IS_NULL -> field.isNull(); case IS_NOT_NULL -> field.isNotNull();
                case IS_EMPTY -> field.eq(""); case IS_NOT_EMPTY -> field.ne("");
                case CONTAINS -> field.cast(String.class).contains((String)value);
                case IN -> field.in(array(binding,filter.field(),filter.value()));
                case BETWEEN -> { var range=array(binding,filter.field(),filter.value()); yield field.between(range.get(0),range.get(1)); }
                default -> throw new LedgerException(400,"INVALID_QUERY");
            };
            condition=condition.and(item);
        }
        return condition;
    }
    private static List<Object> array(LedgerBinding binding,String field,JsonNode value) {
        List<Object> items=new ArrayList<>();value.forEach(node->items.add(scalar(binding,field,node)));return items;
    }
    private static Object scalar(LedgerBinding binding,String field,JsonNode node) {
        if(node==null || node.isNull() || node.isArray()) return null;
        if("id".equals(field)) return UUID.fromString(node.stringValue());
        return switch(binding.definition().fields().get(field).type()) {
            case UUID,REFERENCE -> UUID.fromString(node.stringValue());
            case INTEGER -> {
                if(!node.isIntegralNumber() || !node.canConvertToLong()) throw new LedgerException(400,"INVALID_QUERY");
                yield node.longValue();
            }
            case DATE_TIME -> OffsetDateTime.parse(node.stringValue());
            default -> node.stringValue();
        };
    }
    static Row row(LedgerBinding binding,Record record) {
        Map<String,Object> values=new LinkedHashMap<>();
        binding.storage().fieldColumns().forEach((key,physical)->{
            Object value=record.get(key);
            value=normalizeValue(value);
            if(binding.definition().fields().containsKey(key) && binding.definition().fields().get(key).type()==LedgerDefinition.Type.INTEGER && value instanceof Number number) value=number.longValue();
            values.put(key,value);
        });
        return new Row(record.get("id",UUID.class),record.get("version",Long.class),values);
    }
    static Object normalizeValue(Object value) {
        if(value instanceof java.sql.Timestamp timestamp)return timestamp.toInstant();
        if(value instanceof OffsetDateTime time)return time.toInstant();
        return value;
    }
    Row create(LedgerBinding binding,LedgerExecution execution,UUID recordId,Map<String,Object> supplied) {
        var context=execution.decision().context(); var time=execution.decision().observedAt().atOffset(ZoneOffset.UTC);
        Map<Field<?>,Object> values=new LinkedHashMap<>();
        values.put(DSL.field(DSL.name("tenant_id")),context.tenantId());values.put(DSL.field(DSL.name("id")),recordId);values.put(DSL.field(DSL.name("version")),1L);
        values.put(DSL.field(DSL.name("created_at")),time);values.put(DSL.field(DSL.name("updated_at")),time);
        values.put(DSL.field(DSL.name("created_by_actor_id")),context.actorId());values.put(DSL.field(DSL.name("updated_by_actor_id")),context.actorId());
        supplied.forEach((key,value)->values.put(DSL.field(DSL.name(binding.storage().fieldColumns().get(key))),sqlValue(value)));
        int changed=execution.dsl().insertInto(DSL.table(DSL.name("public",binding.storage().table()))).set(values).execute();
        execution.requireCurrent();if(changed!=1) throw new LedgerException(409,"COMMAND_CONFLICT");
        return find(binding,execution,recordId,false);
    }
    Row update(LedgerBinding binding,LedgerExecution execution,UUID recordId,long expectedVersion,Map<String,Object> supplied) {
        Map<Field<?>,Object> values=new LinkedHashMap<>();
        supplied.forEach((key,value)->values.put(DSL.field(DSL.name(binding.storage().fieldColumns().get(key))),sqlValue(value)));
        values.put(DSL.field(DSL.name("version")),expectedVersion+1);values.put(DSL.field(DSL.name("updated_at")),execution.decision().observedAt().atOffset(ZoneOffset.UTC));
        values.put(DSL.field(DSL.name("updated_by_actor_id")),execution.decision().context().actorId());
        int changed=execution.dsl().update(table(binding)).set(values).where(visibility(binding,execution).and(id().eq(recordId)).and(version().eq(expectedVersion))).execute();
        execution.requireCurrent();if(changed!=1) throw new LedgerException(409,"VERSION_CONFLICT");
        return find(binding,execution,recordId,false);
    }
    private static Object sqlValue(Object value) { return value instanceof Instant time ? time.atOffset(ZoneOffset.UTC) : value; }
    void lockIdempotency(LedgerBinding binding,LedgerExecution execution,String action,UUID key) {
        var c=execution.decision().context();
        String namespace=c.tenantId()+"/"+c.actorId()+"/"+c.accountId()+"/"+c.memberId()+"/"+binding.definition().appKey()+"/"+binding.definition().entityKey()+"/"+action+"/"+key;
        int digest=ByteBuffer.wrap(sha(namespace.getBytes(StandardCharsets.UTF_8))).getInt();
        execution.dsl().fetch("select pg_advisory_xact_lock(?,?)",0x4D53464C,digest);execution.requireCurrent();
    }
    Receipt receipt(LedgerBinding binding,LedgerExecution execution,String action,UUID key) {
        var c=execution.decision().context();
        var row=execution.dsl().fetchOne("""
            select r.request_hash,r.result,v.event_id,v.record_id,v.version,v.command_id,v.definition_version,v.occurred_at
            from public.ledger_command_receipt r join public.ledger_record_revision v
              on v.tenant_id=r.tenant_id and v.app_key=r.app_key and v.entity_key=r.entity_key and v.record_id=r.record_id
             and v.version=r.result_version and v.command_id=r.command_id and v.actor_id=r.actor_id
             and v.account_id=r.account_id and v.member_id=r.member_id and v.action=r.action and v.definition_version=r.definition_version
            where r.tenant_id=? and r.actor_id=? and r.account_id=? and r.member_id=? and r.app_key=? and r.entity_key=? and r.action=? and r.idempotency_key=?
            """,c.tenantId(),c.actorId(),c.accountId(),c.memberId(),binding.definition().appKey(),binding.definition().entityKey(),action,key);
        execution.requireCurrent();if(row==null)return null;
        var result=json.readValue(row.get("result",JSONB.class).data(),LedgerContracts.CommandData.class);
        if(!result.id().equals(row.get("record_id",UUID.class)) || result.version()!=row.get("version",Long.class)
                || !result.commandId().equals(row.get("command_id",UUID.class)) || !result.eventId().equals(row.get("event_id",UUID.class))
                || !result.definitionVersion().equals(row.get("definition_version",String.class))
                || !result.occurredAt().equals(row.get("occurred_at",OffsetDateTime.class).toInstant())) throw new LedgerException(503,"DEPENDENCY_UNAVAILABLE");
        return new Receipt(row.get("request_hash",String.class),result);
    }
    void appendRevision(LedgerBinding binding,LedgerExecution execution,Row row,LedgerContracts.CommandData result,UUID auditId,String action) {
        var c=execution.decision().context();var fact=new LedgerChangeFactV1(result.eventId(),LedgerChangeFactV1.EVENT_TYPE,1,
                binding.definition().appKey(),binding.definition().entityKey(),c.tenantId(),row.id(),row.version(),result.definitionVersion(),action,
                result.commandId(),c.actorId(),c.accountId(),c.memberId(),result.occurredAt(),row.values());
        int changed=execution.dsl().execute("""
            insert into public.ledger_record_revision(tenant_id,app_key,entity_key,record_id,version,command_id,event_id,definition_version,
                action,audit_event_id,actor_id,account_id,member_id,occurred_at,snapshot,change_fact)
            values(?,?,?,?,?,?,?,?,?,?,?,?,?,cast(? as timestamptz),cast(? as jsonb),cast(? as jsonb))
            """,c.tenantId(),binding.definition().appKey(),binding.definition().entityKey(),row.id(),row.version(),result.commandId(),result.eventId(),
                result.definitionVersion(),action,auditId,c.actorId(),c.accountId(),c.memberId(),result.occurredAt().atOffset(ZoneOffset.UTC),
                json.writeValueAsString(row.values()),json.writeValueAsString(fact));
        execution.requireCurrent();if(changed!=1)throw new LedgerException(503,"DEPENDENCY_UNAVAILABLE");
    }
    void appendReceipt(LedgerBinding binding,LedgerExecution execution,String action,UUID key,String hash,LedgerContracts.CommandData result) {
        var c=execution.decision().context();
        int changed=execution.dsl().execute("""
            insert into public.ledger_command_receipt(tenant_id,actor_id,account_id,member_id,app_key,entity_key,action,idempotency_key,
                command_id,record_id,result_version,request_hash,definition_version,result,completed_at)
            values(?,?,?,?,?,?,?,?,?,?,?,?,?,cast(? as jsonb),cast(? as timestamptz))
            """,c.tenantId(),c.actorId(),c.accountId(),c.memberId(),binding.definition().appKey(),binding.definition().entityKey(),action,key,
                result.commandId(),result.id(),result.version(),hash,result.definitionVersion(),json.writeValueAsString(result),result.occurredAt().atOffset(ZoneOffset.UTC));
        execution.requireCurrent();if(changed!=1)throw new LedgerException(503,"DEPENDENCY_UNAVAILABLE");
    }
    boolean matchesReceiptFingerprint(LedgerBinding binding,String action,UUID id,String definitionVersion,Long version,
                                      Map<String,Object> values,String receiptHash) {
        if(receiptHash.equals(fingerprint(action,id,definitionVersion,version,values)))return true;
        // The tool V1 EDIT contract gained an optional scope without changing its version.
        // An omitted/null scope still preserves the current binding; only that old shape can replay.
        if(!"UPDATE".equals(action) || !"1".equals(definitionVersion)
                || !"tooling".equals(binding.definition().appKey()) || !"tool".equals(binding.definition().entityKey())
                || !values.containsKey("custodyScopeId") || values.get("custodyScopeId")!=null)return false;
        var legacyValues=new LinkedHashMap<>(values);
        legacyValues.remove("custodyScopeId");
        return receiptHash.equals(fingerprint(action,id,definitionVersion,version,legacyValues));
    }
    String fingerprint(String action,UUID recordId,String definitionVersion,Long version,Map<String,Object> values) {
        Map<String,Object> payload=new java.util.TreeMap<>();payload.put("action",action);payload.put("id",recordId);payload.put("definitionVersion",definitionVersion);
        payload.put("expectedVersion",version);payload.put("values",new java.util.TreeMap<>(values));
        return java.util.HexFormat.of().formatHex(sha(json.writeValueAsBytes(payload)));
    }
    private static byte[] sha(byte[] bytes) {
        try{return MessageDigest.getInstance("SHA-256").digest(bytes);}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}

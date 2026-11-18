package com.mightyoung.softwarefactory.dualprevention.asset;

import com.mightyoung.softwarefactory.authorization.EntityActionAuthorization;
import com.mightyoung.softwarefactory.authorization.EntityActionAuthorization.ActionScope;
import com.mightyoung.softwarefactory.dualprevention.hazard.HazardSourceContracts;
import java.sql.Connection;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static com.mightyoung.softwarefactory.dualprevention.asset.AssetContracts.*;

/** Borrows only the exact owner-held source. No connection acquisition or nested transaction fallback. */
public final class AssetFoundationRepository {
    private final DataSource source;
    private final EntityActionAuthorization actions;
    public AssetFoundationRepository(DataSource source,EntityActionAuthorization actions) {
        this.source=Objects.requireNonNull(source);this.actions=Objects.requireNonNull(actions);
    }
    record Row(UUID id,UUID organizationId,long version,String lifecycle) {}
    record Link(UUID id,UUID organizationId,long version,UUID parentId,UUID counterpartId,UUID positionId,String duty,
                Instant validFrom,Instant validTo,Instant endObservedAt,String orgCode,String orgName,
                String positionCode,String positionName,Instant directoryObservedAt) {}
    record Receipt(UUID commandId,UUID accountId,UUID memberId,String fingerprint,String resourceType,long version,Instant occurredAt,UUID id) {
        CommandResult result() { return new CommandResult(commandId,resourceType,id,version,occurredAt); }
    }
    record Statement(AssetFoundationSql sql,List<Object> bindings) {
        Statement { bindings=Collections.unmodifiableList(new ArrayList<>(bindings)); }
    }
    Connection connection() {
        if(!(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder holder)
                ||!holder.isSynchronizedWithTransaction()||holder.isRollbackOnly()||!TransactionSynchronizationManager.isActualTransactionActive())throw new AssetFoundationFailure(503);
        return holder.getConnection();
    }
    void require(ActionScope scope) { actions.requireSqlConnection(scope,source,connection()); }
    void setTenant(UUID tenant) {
        String actual=one(AssetFoundationSql.SET_TENANT,List.of(tenant.toString()),rows->rows.get(0,String.class)).orElseThrow(()->new AssetFoundationFailure(503));
        if(!tenant.toString().equals(actual))throw new AssetFoundationFailure(503);
    }
    void lock(AssetFoundationSql sql,List<Object> bindings) { one(sql,bindings,rows->Boolean.TRUE).orElseThrow(()->new AssetFoundationFailure(503)); }
    Optional<Row> row(AssetFoundationSql sql,UUID tenant,UUID id) {
        return one(sql,List.of(tenant,id),AssetFoundationRepository::row);
    }
    Optional<Row> current(AssetFoundationSql sql,ActionScope scope,UUID id) {
        require(scope);
        return one(sql,List.of(scope.context().tenantId(),id,organizations(scope)),AssetFoundationRepository::row);
    }
    private static Row row(Record rows) {
        // Hazard existence is not lifecycle; its fixed mapper handles it separately.
        return new Row(rows.get("id",UUID.class),rows.get("organization_id",UUID.class),rows.get("version",Long.class),rows.field("lifecycle")==null?null:rows.get("lifecycle",String.class));
    }
    boolean active(AssetFoundationSql sql,ActionScope scope,UUID id) {
        require(scope);
        return one(sql,List.of(scope.context().tenantId(),id,organizations(scope)),rows->"ACTIVE".equals(rows.get("lifecycle",String.class))).orElse(false);
    }
    boolean archiveClear(AssetFoundationSql sql,ActionScope scope,UUID id,Instant observedAt) {
        require(scope);
        return one(sql,List.of(scope.context().tenantId(),id,observedAt),rows->Boolean.TRUE.equals(rows.get("archive_clear",Boolean.class)))
                .orElseThrow(()->new AssetFoundationFailure(503));
    }
    Optional<Receipt> receipt(ActionScope scope,UUID key) {
        require(scope);
        var context=scope.context();
        return one(AssetFoundationSql.RECEIPT_FIND,List.of(organizations(scope),context.tenantId(),context.actorId(),scope.action().permissionCode(),key),rows->new Receipt(
                rows.get("command_id",UUID.class),rows.get("account_id",UUID.class),rows.get("member_id",UUID.class),
                rows.get("payload_sha256",String.class),rows.get("result_resource_type",String.class),rows.get("result_version",Long.class),
                rows.get("result_occurred_at",OffsetDateTime.class).toInstant(),rows.get("result_id",UUID.class)));
    }
    public Workshop workshop(ActionScope scope,UUID id) {
        require(scope);
        return one(AssetFoundationSql.WORKSHOP_CURRENT,List.of(scope.context().tenantId(),id,organizations(scope)),rows->new Workshop(
                id,rows.get("organization_id",UUID.class),rows.get("version",Long.class),rows.get("code",String.class),rows.get("name",String.class),
                rows.get("boundary_description",String.class),Lifecycle.valueOf(rows.get("lifecycle",String.class)),time(rows,"created_at"),time(rows,"updated_at"))).orElseThrow(()->new AssetFoundationFailure(404));
    }
    public WorkUnit workUnit(ActionScope scope,UUID id) {
        require(scope);
        return one(AssetFoundationSql.WORK_UNIT_CURRENT,List.of(scope.context().tenantId(),id,organizations(scope)),rows->new WorkUnit(
                id,rows.get("organization_id",UUID.class),rows.get("version",Long.class),rows.get("code",String.class),rows.get("name",String.class),
                WorkUnitKind.valueOf(rows.get("kind",String.class)),rows.get("workshop_id",UUID.class),rows.get("line_id",UUID.class),
                rows.get("stage_id",UUID.class),rows.get("equipment_id",UUID.class),rows.get("boundary_description",String.class),
                Lifecycle.valueOf(rows.get("lifecycle",String.class)),time(rows,"created_at"),time(rows,"updated_at"))).orElseThrow(()->new AssetFoundationFailure(404));
    }
    public HazardSourceContracts.Detail hazard(ActionScope scope,UUID id) {
        require(scope);
        return one(AssetFoundationSql.HAZARD_SOURCE_CURRENT,List.of(scope.context().tenantId(),id,organizations(scope)),rows->new HazardSourceContracts.Detail(
                id,rows.get("organization_id",UUID.class),rows.get("version",Long.class),rows.get("code",String.class),rows.get("work_unit_id",UUID.class),
                rows.get("description",String.class),rows.get("identified_at",OffsetDateTime.class).toInstant(),
                HazardSourceContracts.ExistenceState.valueOf(rows.get("existence_state",String.class)),time(rows,"created_at"),time(rows,"updated_at"),
                rows.get("factor_code_ref",String.class),rows.get("factor_version",String.class))).orElseThrow(()->new AssetFoundationFailure(404));
    }
    public ProductionLine productionLine(ActionScope scope,UUID id) {
        require(scope);
        return one(AssetFoundationSql.PRODUCTION_LINE_CURRENT,List.of(scope.context().tenantId(),id,organizations(scope)),rows->new ProductionLine(
                id,rows.get("organization_id",UUID.class),rows.get("version",Long.class),rows.get("code",String.class),rows.get("name",String.class),
                rows.get("workshop_id",UUID.class),rows.get("description",String.class),Lifecycle.valueOf(rows.get("lifecycle",String.class)),
                time(rows,"created_at"),time(rows,"updated_at"))).orElseThrow(()->new AssetFoundationFailure(404));
    }
    public ProcessStage processStage(ActionScope scope,UUID id) {
        require(scope);
        return one(AssetFoundationSql.PROCESS_STAGE_CURRENT,List.of(scope.context().tenantId(),id,organizations(scope)),rows->new ProcessStage(
                id,rows.get("organization_id",UUID.class),rows.get("version",Long.class),rows.get("code",String.class),rows.get("name",String.class),
                rows.get("line_id",UUID.class),rows.get("ordinal",Integer.class),rows.get("description",String.class),Lifecycle.valueOf(rows.get("lifecycle",String.class)),
                time(rows,"created_at"),time(rows,"updated_at"))).orElseThrow(()->new AssetFoundationFailure(404));
    }
    public Equipment equipment(ActionScope scope,UUID id) {
        require(scope);
        return one(AssetFoundationSql.EQUIPMENT_CURRENT,List.of(scope.context().tenantId(),id,organizations(scope)),rows->new Equipment(
                id,rows.get("organization_id",UUID.class),rows.get("version",Long.class),rows.get("code",String.class),rows.get("name",String.class),
                rows.get("type_description",String.class),rows.get("location_description",String.class),OperationalState.valueOf(rows.get("operational_state",String.class)),
                Lifecycle.valueOf(rows.get("lifecycle",String.class)),time(rows,"created_at"),time(rows,"updated_at"))).orElseThrow(()->new AssetFoundationFailure(404));
    }
    private static Instant time(Record row,String field) { return row.get(field,OffsetDateTime.class).toInstant(); }
    Link link(AssetFoundationSql sql,UUID tenant,UUID id,ActionScope scope) {
        if(scope!=null)require(scope);
        List<Object> values=scope==null?List.of(tenant,id):List.of(tenant,id,organizations(scope));
        return one(sql,values,rows->{
            boolean line=sql.name().startsWith("LINE_WORKSHOP_");
            boolean stage=sql.name().startsWith("STAGE_EQUIPMENT_");
            boolean responsibility=!line&&!stage;
            return new Link(id,rows.get("organization_id",UUID.class),rows.get("version",Long.class),
                    rows.get(line?"line_id":stage?"stage_id":"work_unit_id",UUID.class),
                    rows.get(line?"workshop_id":stage?"equipment_id":"org_id",UUID.class),
                    responsibility?rows.get("position_id",UUID.class):null,responsibility?rows.get("duty",String.class):null,
                    time(rows,"valid_from"),optionalTime(rows,"valid_to"),optionalTime(rows,"end_observed_at"),
                    responsibility?rows.get("org_code_snapshot",String.class):null,responsibility?rows.get("org_name_snapshot",String.class):null,
                    responsibility?rows.get("position_code_snapshot",String.class):null,responsibility?rows.get("position_name_snapshot",String.class):null,
                    responsibility?time(rows,"directory_observed_at"):null);
        }).orElseThrow(()->new AssetFoundationFailure(404));
    }
    private static Instant optionalTime(Record row,String field) { var value=row.get(field,OffsetDateTime.class);return value==null?null:value.toInstant(); }
    void execute(ActionScope scope,Statement command) {
        require(scope);
        try {
            if(database().execute(command.sql().sql,bindings(command.sql(),command.bindings()))!=1)
                throw new AssetFoundationFailure(409);
        } catch(DataAccessException error) { throw failed(error); }
    }
    private DSLContext database() {
        return DSL.using(connection(),SQLDialect.POSTGRES,
                new Settings().withExecuteLogging(false).withFetchWarnings(false).withBindOffsetDateTimeType(true));
    }
    private static Object[] bindings(AssetFoundationSql sql,List<?> bindings) {
        if(bindings.size()!=sql.template().parameterCount())throw new IllegalArgumentException("Fixed SQL binding count mismatch");
        return bindings.stream().map(value->value instanceof Instant time?time.atOffset(ZoneOffset.UTC):
                value instanceof Enum<?> choice?choice.name():value).toArray();
    }
    private <T> Optional<T> one(AssetFoundationSql sql,List<?> bindings,Mapper<T> mapper) {
        try {
            Record row=database().resultQuery(sql.sql,bindings(sql,bindings)).coerce(fields(sql)).fetchOne();
            return row==null?Optional.empty():Optional.of(mapper.read(row));
        } catch(DataAccessException error) { throw failed(error); }
    }
    /** Exact SELECT projections avoid result metadata, including PostgreSQL type-resolution SQL in FINAL. */
    static Field<?>[] fields(AssetFoundationSql sql) {
        return switch(sql) {
            case SET_TENANT -> fields("set_config");
            case RECEIPT_LOCK -> fields("g014_lock_receipt_key");
            case MUTATION_LOCK -> fields("g014_lock_mutation_keys");
            case WORKSHOP_NATURAL,WORK_UNIT_NATURAL,HAZARD_SOURCE_NATURAL,PRODUCTION_LINE_NATURAL,PROCESS_STAGE_NATURAL,EQUIPMENT_NATURAL -> fields("g014_lock_natural_key");
            case RECEIPT_FIND -> fields("command_id","account_id","member_id","payload_sha256","result_resource_type",
                    "result_version","result_occurred_at","result_id");
            case WORKSHOP_LOCK,WORKSHOP_CURRENT -> rootFields("code","name","boundary_description","lifecycle");
            case WORK_UNIT_LOCK,WORK_UNIT_CURRENT -> rootFields("code","name","kind","workshop_id","line_id","stage_id",
                    "equipment_id","boundary_description","lifecycle");
            case HAZARD_SOURCE_LOCK,HAZARD_SOURCE_CURRENT -> rootFields("code","work_unit_id","description","identified_at",
                    "existence_state","factor_code_ref","factor_version");
            case PRODUCTION_LINE_LOCK,PRODUCTION_LINE_CURRENT -> rootFields("code","name","workshop_id","description","lifecycle");
            case PROCESS_STAGE_LOCK,PROCESS_STAGE_CURRENT -> rootFields("code","name","line_id","ordinal","description","lifecycle");
            case EQUIPMENT_LOCK,EQUIPMENT_CURRENT -> rootFields("code","name","type_description","location_description","operational_state","lifecycle");
            case LINE_WORKSHOP_PEEK,LINE_WORKSHOP_LOCK,LINE_WORKSHOP_CURRENT -> rootFields("line_id","line_version_at_bind","workshop_id","workshop_version_at_bind","valid_from","valid_to","end_observed_at");
            case STAGE_EQUIPMENT_PEEK,STAGE_EQUIPMENT_LOCK,STAGE_EQUIPMENT_CURRENT -> rootFields("stage_id","stage_version_at_bind","equipment_id","equipment_version_at_bind","valid_from","valid_to","end_observed_at");
            case RESPONSIBILITY_BINDING_PEEK,RESPONSIBILITY_BINDING_LOCK,RESPONSIBILITY_BINDING_CURRENT -> rootFields("work_unit_id","org_id","position_id","duty","valid_from","valid_to","end_observed_at","org_code_snapshot","org_name_snapshot","position_code_snapshot","position_name_snapshot","directory_observed_at");
            case WORKSHOP_ARCHIVE_CLEAR,PRODUCTION_LINE_ARCHIVE_CLEAR,PROCESS_STAGE_ARCHIVE_CLEAR,EQUIPMENT_ARCHIVE_CLEAR,WORK_UNIT_ARCHIVE_CLEAR -> new Field<?>[]{DSL.field("archive_clear",Boolean.class)};
            case PARENT_FENCE -> new Field<?>[]{DSL.field("g014_fence_parent",UUID.class)};
            default -> throw new IllegalArgumentException("No SELECT projection for "+sql);
        };
    }
    private static Field<?>[] rootFields(String... extra) {
        var names=new ArrayList<>(List.of("tenant_id","id","organization_id","version","created_at","updated_at",
                "created_by","updated_by","last_command_id"));
        names.addAll(Arrays.asList(extra));return fields(names.toArray(String[]::new));
    }
    private static Field<?>[] fields(String... names) {
        return Arrays.stream(names).map(name->DSL.field(name,type(name))).toArray(Field<?>[]::new);
    }
    private static Class<?> type(String name) {
        if(name.equals("ordinal"))return Integer.class;
        if(name.equals("version")||name.equals("result_version")||name.endsWith("_version_at_bind"))return Long.class;
        if(name.endsWith("_at")||name.equals("valid_from")||name.equals("valid_to"))return OffsetDateTime.class;
        if(name.equals("id")||name.endsWith("_id")||name.equals("created_by")||name.equals("updated_by"))return UUID.class;
        return String.class;
    }
    static String organizations(ActionScope scope) { return scope.organizationIds().stream().sorted().map(UUID::toString).collect(java.util.stream.Collectors.joining(",","{","}")); }
    private AssetFoundationFailure failed(DataAccessException error) {
        if(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder holder)holder.setRollbackOnly();
        String state=error.sqlState();return new AssetFoundationFailure(state!=null&&(state.startsWith("23")||state.equals("40001")||state.equals("40P01"))?409:503,error);
    }
    @FunctionalInterface private interface Mapper<T> { T read(Record rows); }
}

package com.mightyoung.softwarefactory.dualprevention.risk;

import com.mightyoung.softwarefactory.authorization.EntityActionAuthorization;
import com.mightyoung.softwarefactory.authorization.EntityActionAuthorization.ActionScope;
import com.mightyoung.softwarefactory.authorization.RegisteredEntityAction;
import com.mightyoung.softwarefactory.dualprevention.hazard.HazardSourceContracts.ExistenceState;
import java.sql.*;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static com.mightyoung.softwarefactory.authorization.RegisteredEntityAction.*;
import static com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioContracts.*;
import static com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioLockPlan.*;

/** Private same-holder write graph. No bean fallback, connection acquisition, transaction or authority issuance. */
final class RiskScenarioRepository {
    private final DataSource source;
    private final EntityActionAuthorization actions;
    RiskScenarioRepository(DataSource source,EntityActionAuthorization actions){this.source=Objects.requireNonNull(source);this.actions=Objects.requireNonNull(actions);}
    record Root(UUID tenantId,UUID id,UUID organizationId,String code,long version,String description,String triggeringConditions,
            String exposureDescription,String consequence,UUID createdByActorId,UUID createdByMemberId,Instant createdAt,
            UUID updatedByActorId,UUID updatedByMemberId,Instant updatedAt,UUID lastCommandId) {
        CommandResult result(){return new CommandResult(id,code,version,updatedAt);}
    }
    record Discovery(Root root,List<ActiveLink> links){Discovery{Objects.requireNonNull(root);links=canonicalOld(root.tenantId(),root.id(),links);}}
    record Locked(Root root,Map<UUID,Long> hazardVersions){Locked{hazardVersions=Map.copyOf(hazardVersions);}}
    record HazardReference(UUID id,UUID organizationId,long version,String code,String descriptionSha256,ExistenceState existence) {}
    record Receipt(UUID commandId,UUID accountId,UUID memberId,UUID actorId,String actionCode,String fingerprint,
            UUID scenarioId,long version,String code,Instant occurredAt,UUID auditEventId,UUID traceId){
        CommandResult result(){return new CommandResult(scenarioId,code,version,occurredAt);}
    }
    record SnapshotIndex(UUID hazardId,UUID linkId,long linkVersion,long hazardVersionAtLink,long hazardVersionAtScenarioRevision,
            String code,String descriptionSha256,ExistenceState existence,UUID commandId,UUID actorId,UUID memberId,Instant occurredAt) {}
    record Statement(RiskScenarioSql sql,List<Object> bindings,RegisteredEntityAction action){
        Statement {Objects.requireNonNull(sql);Objects.requireNonNull(action);bindings=Collections.unmodifiableList(new ArrayList<>(bindings));
            if(sql.effect==null||!commandAction(action)||sql==RiskScenarioSql.ROOT_INSERT&&action!=RISK_SCENARIO_CREATE
                    ||(sql==RiskScenarioSql.ROOT_UPDATE||sql==RiskScenarioSql.LINK_END)&&action!=RISK_SCENARIO_UPDATE)
                throw new IllegalArgumentException("Invalid fixed risk write");sql.requireBindings(bindings);}
    }
    /** A local execution result, not proof of audit, event publication, authorization or commit. */
    record Applied(Statement statement,long actualRows) {}

    void setTenant(UUID tenant){
        String value=one(RiskScenarioSql.SET_TENANT,List.of(tenant.toString()),null,row->row.text("set_config")).orElseThrow(()->refuse(503));
        if(!tenant.toString().equals(value))throw refuse(503);
    }
    void lockReceipt(UUID tenant,UUID actor,RegisteredEntityAction action,UUID key){
        if(!commandAction(action))throw refuse(503);
        control(RiskScenarioSql.RECEIPT_LOCK,List.of(tenant,actor,action.permissionCode(),key),null);
    }
    Optional<Root> currentParent(ActionScope scope,UUID id){
        require(scope,RISK_SCENARIO_READ,RISK_SCENARIO_CREATE,RISK_SCENARIO_UPDATE);
        return one(RiskScenarioSql.ROOT_CURRENT,List.of(scope.context().tenantId(),id,organizations(scope)),scope,RiskScenarioRepository::root);
    }
    Discovery discover(ActionScope scope,UUID id){
        require(scope,RISK_SCENARIO_UPDATE);
        Root root=currentParent(scope,id).orElseThrow(()->refuse(404));
        return new Discovery(root,activeLinks(scope,id));
    }
    List<ActiveLink> activeLinks(ActionScope scope,UUID id){
        require(scope,RISK_SCENARIO_READ,RISK_SCENARIO_CREATE,RISK_SCENARIO_UPDATE);
        return many(RiskScenarioSql.ACTIVE_LINKS,List.of(scope.context().tenantId(),id,organizations(scope)),scope,RiskScenarioRepository::link,100);
    }
    Optional<HazardReference> hazardReference(ActionScope scope,UUID id){
        require(scope,HAZARD_SOURCE_READ);
        return one(RiskScenarioSql.HAZARD_REFERENCE,List.of(scope.context().tenantId(),id,organizations(scope)),scope,row->new HazardReference(
                row.uuid("id"),row.uuid("organization_id"),row.number("version"),row.text("code"),row.text("description_sha256"),ExistenceState.valueOf(row.text("existence_state"))));
    }
    Optional<Receipt> receipt(ActionScope scope,UUID idempotency){
        require(scope,RISK_SCENARIO_CREATE,RISK_SCENARIO_UPDATE);var c=scope.context();
        return one(RiskScenarioSql.RECEIPT_FIND,List.of(c.tenantId(),c.actorId(),scope.action().permissionCode(),idempotency,organizations(scope)),scope,row->new Receipt(
                row.uuid("command_id"),row.uuid("account_id"),row.uuid("member_id"),row.uuid("actor_id"),row.text("action_code"),row.text("payload_sha256"),
                row.uuid("result_scenario_id"),row.number("result_version"),row.text("result_code"),row.time("occurred_at"),row.uuid("audit_event_id"),row.uuid("trace_id")));
    }
    Locked lockAndVerify(ActionScope scope,RiskScenarioLockPlan plan,Discovery discovered,String createCode){
        require(scope,plan.create?RISK_SCENARIO_CREATE:RISK_SCENARIO_UPDATE);
        if(!scope.context().tenantId().equals(plan.tenantId))throw refuse(503);
        if(plan.create){if(discovered!=null)throw refuse(503);RiskScenarioContracts.code(createCode);}
        else if(discovered==null||createCode!=null||!discovered.root().tenantId().equals(plan.tenantId)
                ||!discovered.root().id().equals(plan.scenarioId)||discovered.root().version()!=plan.expectedVersion
                ||!discovered.links().equals(plan.oldLinks))throw refuse(503);
        if(plan.create)control(RiskScenarioSql.NATURAL_LOCK,List.of(plan.tenantId,createCode),scope);
        var actual=many(RiskScenarioSql.MUTATION_PLAN,plan.databasePlanBindings(),scope,
                row->new Key(row.integer("family"),row.bytes("canonical_bytes")),503);
        if(!actual.equals(plan.mutationKeys))throw refuse(503);
        control(RiskScenarioSql.MUTATION_LOCK,plan.databasePlanBindings(),scope);
        var hazardVersions=new LinkedHashMap<UUID,Long>();
        for(var rowLock:plan.rows){
            var held=one(RiskScenarioSql.ROW_LOCK,List.of(rowLock.kind().number,plan.tenantId,rowLock.id()),scope,row->row.optionalNumber("held_version"));
            Long version=held.orElse(null);
            if(version==null)throw refuse(rowLock.kind()==RowKind.HAZARD_SOURCE?404:409);
            if(rowLock.kind()==RowKind.HAZARD_SOURCE)hazardVersions.put(rowLock.id(),version);
            else if(rowLock.kind()==RowKind.RISK_SCENARIO&&version!=plan.expectedVersion||rowLock.kind()==RowKind.RISK_HAZARD&&version!=1)throw refuse(409);
        }
        if(plan.create)return new Locked(null,hazardVersions);
        Root current=currentParent(scope,plan.scenarioId).orElseThrow(()->refuse(409));
        try{plan.requireStable(current.version(),activeLinks(scope,plan.scenarioId));}
        catch(RuntimeException|Error failure){poison();throw failure;}
        if(!current.equals(discovered.root()))throw refuse(409);
        return new Locked(current,hazardVersions);
    }
    List<SnapshotIndex> snapshotIndex(ActionScope scope,UUID id,long version){
        require(scope,RISK_SCENARIO_CREATE,RISK_SCENARIO_UPDATE,RISK_SCENARIO_READ);
        return many(RiskScenarioSql.SNAPSHOT_INDEX,List.of(scope.context().tenantId(),id,version,organizations(scope)),scope,row->new SnapshotIndex(
                row.uuid("hazard_id"),row.uuid("link_id"),row.number("link_version"),row.number("hazard_version_at_link"),row.number("hazard_version_at_scenario_revision"),
                row.text("snapshot_code"),row.text("description_sha256"),ExistenceState.valueOf(row.text("existence")),row.uuid("command_id"),row.uuid("actor_id"),row.uuid("member_id"),row.time("occurred_at")),100);
    }
    Statement createRoot(ActionScope scope,UUID id,UUID commandId,Create input){
        require(scope,RISK_SCENARIO_CREATE);var c=scope.context();
        if(!scope.organizationIds().contains(input.organizationId()))throw refuse(404);
        return statement(scope,RiskScenarioSql.ROOT_INSERT,List.of(c.tenantId(),id,input.organizationId(),input.code(),input.description(),input.triggeringConditions(),
                input.exposureDescription(),input.consequence(),c.actorId(),c.memberId(),scope.observedAt(),commandId,organizations(scope)));
    }
    Statement updateRoot(ActionScope scope,UUID id,UUID commandId,Update input){
        require(scope,RISK_SCENARIO_UPDATE);var c=scope.context();
        return statement(scope,RiskScenarioSql.ROOT_UPDATE,List.of(input.description(),input.triggeringConditions(),input.exposureDescription(),input.consequence(),nextVersion(input.expectedVersion()),
                c.actorId(),c.memberId(),scope.observedAt(),commandId,c.tenantId(),id,input.expectedVersion(),organizations(scope)));
    }
    Statement rootRevision(ActionScope scope,UUID id,long version,UUID commandId){
        require(scope,RISK_SCENARIO_CREATE,RISK_SCENARIO_UPDATE);
        return statement(scope,RiskScenarioSql.ROOT_REVISION,List.of(scope.context().tenantId(),id,version,commandId,organizations(scope)));
    }
    Statement newLink(ActionScope scope,UUID scenario,long version,UUID command,UUID link,UUID hazard,long hazardVersion){
        return statement(scope,RiskScenarioSql.LINK_NEW,plus(commandBindings(scope,scenario,version,command),link,hazard,hazardVersion));
    }
    Statement endLink(ActionScope scope,UUID scenario,long version,UUID command,ActiveLink old){
        require(scope,RISK_SCENARIO_UPDATE);
        if(!old.tenantId().equals(scope.context().tenantId())||!old.scenarioId().equals(scenario))throw refuse(503);
        return statement(scope,RiskScenarioSql.LINK_END,plus(commandBindings(scope,scenario,version,command),old.id(),old.hazardId(),old.hazardVersionAtLink()));
    }
    Statement linkRevision(ActionScope scope,UUID scenario,long version,UUID command,Change change){
        require(scope,RISK_SCENARIO_CREATE,RISK_SCENARIO_UPDATE);
        return statement(scope,RiskScenarioSql.LINK_REVISION,List.of(scope.context().tenantId(),change.id(),scenario,version,change.version(),command,organizations(scope)));
    }
    Statement snapshot(ActionScope scope,UUID scenario,long version,UUID command,UUID link,UUID hazard,long atLink,long observed){
        require(scope,RISK_SCENARIO_CREATE,RISK_SCENARIO_UPDATE);
        return statement(scope,RiskScenarioSql.SNAPSHOT_INSERT,List.of(scope.context().tenantId(),scenario,version,command,link,hazard,atLink,observed,organizations(scope)));
    }
    Statement receiptInsert(ActionScope scope,UUID command,UUID idempotency,String fingerprint,CommandResult result,UUID auditId,UUID trace){
        require(scope,RISK_SCENARIO_CREATE,RISK_SCENARIO_UPDATE);var c=scope.context();
        if(fingerprint==null||!fingerprint.matches("[0-9a-f]{64}")||!result.occurredAt().equals(scope.observedAt()))throw refuse(503);
        return statement(scope,RiskScenarioSql.RECEIPT_INSERT,List.of(c.tenantId(),command,c.actorId(),c.accountId(),c.memberId(),scope.action().permissionCode(),idempotency,
                fingerprint,result.id(),result.version(),result.code(),result.occurredAt(),auditId,trace,organizations(scope)));
    }
    Applied apply(ActionScope scope,Statement statement){
        require(scope,statement.action());validateScopeBindings(scope,statement.sql(),statement.bindings());
        Connection connection=connection();
        try(var query=connection.prepareStatement(statement.sql().sql)){
            bind(query,statement.sql(),statement.bindings());long actual=query.executeUpdate();
            if(actual!=1)throw refuse(409);
            requireSame(scope,connection);return new Applied(statement,actual);
        }catch(SQLException error){throw failed(error);}catch(RuntimeException|Error failure){poison();throw failure;}
    }
    private Statement statement(ActionScope scope,RiskScenarioSql sql,List<Object> values){
        require(scope,RISK_SCENARIO_CREATE,RISK_SCENARIO_UPDATE);validateScopeBindings(scope,sql,values);return new Statement(sql,values,scope.action());
    }
    private List<Object> commandBindings(ActionScope scope,UUID id,long version,UUID command){
        require(scope,RISK_SCENARIO_CREATE,RISK_SCENARIO_UPDATE);var c=scope.context();
        return List.of(c.tenantId(),id,version,command,c.actorId(),c.memberId(),scope.observedAt(),organizations(scope));
    }
    private static List<Object> plus(List<Object> first,Object... tail){var result=new ArrayList<>(first);result.addAll(Arrays.asList(tail));return List.copyOf(result);}
    private void validateScopeBindings(ActionScope scope,RiskScenarioSql sql,List<?> values){
        sql.requireBindings(values);var c=scope.context();
        for(int i=0;i<values.size();i++){
            Object expected=switch(sql.binds.get(i).name()){
                case "tenant_id"->c.tenantId();case "actor_id"->c.actorId();case "member_id"->c.memberId();case "account_id"->c.accountId();
                case "action_code"->scope.action().permissionCode();case "occurred_at"->scope.observedAt();case "organization_scope"->organizations(scope);default->null;
            };
            if(expected!=null&&!expected.equals(values.get(i)))throw refuse(503);
        }
    }
    private void require(ActionScope scope,RegisteredEntityAction... allowed){
        try{Objects.requireNonNull(scope);actions.requireSqlConnection(scope,source,connection());
            if(!Arrays.asList(allowed).contains(scope.action()))throw refuse(503);
        }catch(RuntimeException|Error failure){poison();throw failure;}
    }
    private void requireSame(ActionScope scope,Connection expected){
        if(connection()!=expected)throw refuse(503);if(scope!=null)actions.requireSqlConnection(scope,source,expected);
    }
    private static boolean commandAction(RegisteredEntityAction action){return action==RISK_SCENARIO_CREATE||action==RISK_SCENARIO_UPDATE;}
    private Connection connection(){
        if(!(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder holder)
                ||!holder.isSynchronizedWithTransaction()||holder.isRollbackOnly()
                ||!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isSynchronizationActive()
                ||TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw refuse(503);
        return holder.getConnection();
    }
    private void control(RiskScenarioSql sql,List<Object> values,ActionScope scope){
        if(many(sql,values,scope,row->Boolean.TRUE,1).size()!=1)throw refuse(503);
    }
    private <T> Optional<T> one(RiskScenarioSql sql,List<Object> values,ActionScope scope,Mapper<T> mapper){
        var rows=many(sql,values,scope,mapper,1);return rows.isEmpty()?Optional.empty():Optional.ofNullable(rows.getFirst());
    }
    private <T> List<T> many(RiskScenarioSql sql,List<Object> values,ActionScope scope,Mapper<T> mapper,int maximum){
        if(sql.effect!=null)throw refuse(503);sql.requireBindings(values);
        if(scope!=null){require(scope,sql==RiskScenarioSql.HAZARD_REFERENCE?new RegisteredEntityAction[]{HAZARD_SOURCE_READ}:
                new RegisteredEntityAction[]{RISK_SCENARIO_READ,RISK_SCENARIO_CREATE,RISK_SCENARIO_UPDATE});validateScopeBindings(scope,sql,values);}
        Connection connection=connection();var result=new ArrayList<T>();
        try(var query=connection.prepareStatement(sql.sql)){
            bind(query,sql,values);
            try(var rows=query.executeQuery()){
                while(rows.next()){if(result.size()==maximum)throw refuse(503);result.add(mapper.read(read(rows,sql)));}
            }
            requireSame(scope,connection);return Collections.unmodifiableList(result);
        }catch(SQLException error){throw failed(error);}catch(RuntimeException|Error failure){poison();throw failure;}
    }
    private static void bind(PreparedStatement statement,RiskScenarioSql sql,List<?> values)throws SQLException {
        sql.requireBindings(values);
        for(int i=0;i<values.size();i++){
            Object value=values.get(i);if(value instanceof Instant time)value=time.atOffset(ZoneOffset.UTC);
            statement.setObject(i+1,value);
        }
    }
    private static Row read(ResultSet rows,RiskScenarioSql sql)throws SQLException {
        var values=new HashMap<String,Object>();for(var column:sql.columns){Object value=switch(column.kind()){
            case UUID->rows.getObject(column.name(),UUID.class);case LONG->rows.getObject(column.name(),Long.class);
            case INTEGER->rows.getObject(column.name(),Integer.class);case TEXT,UUID_ARRAY,LONG_ARRAY->rows.getString(column.name());
            case INSTANT->{var time=rows.getObject(column.name(),OffsetDateTime.class);yield time==null?null:time.toInstant();}
            case BYTES->rows.getBytes(column.name());};values.put(column.name(),value);}
        return new Row(Collections.unmodifiableMap(values));
    }
    private record Row(Map<String,Object> values){
        UUID uuid(String name){return Objects.requireNonNull((UUID)values.get(name));}String text(String name){return Objects.requireNonNull((String)values.get(name));}
        long number(String name){return Objects.requireNonNull((Long)values.get(name));}Long optionalNumber(String name){return (Long)values.get(name);}
        int integer(String name){return Objects.requireNonNull((Integer)values.get(name));}Instant time(String name){return Objects.requireNonNull((Instant)values.get(name));}
        byte[] bytes(String name){return Objects.requireNonNull((byte[])values.get(name)).clone();}
    }
    private static Root root(Row row){
        if(row.values().get("accident_code_ref")!=null||row.values().get("accident_version")!=null)throw new Failure(503);
        return new Root(row.uuid("tenant_id"),row.uuid("id"),row.uuid("organization_id"),row.text("code"),row.number("version"),row.text("description"),
                row.text("triggering_conditions"),row.text("exposure_description"),row.text("consequence"),row.uuid("created_by_actor_id"),row.uuid("created_by_member_id"),row.time("created_at"),
                row.uuid("updated_by_actor_id"),row.uuid("updated_by_member_id"),row.time("updated_at"),row.uuid("last_command_id"));
    }
    private static ActiveLink link(Row row){return new ActiveLink(row.uuid("tenant_id"),row.uuid("id"),row.uuid("scenario_id"),row.uuid("hazard_id"),row.number("hazard_version_at_link"),row.number("version"),
            row.time("linked_at"),row.uuid("created_by_actor_id"),row.uuid("created_by_member_id"),row.number("last_scenario_version"),row.uuid("last_command_id"),row.uuid("last_actor_id"),row.uuid("last_member_id"),row.time("last_occurred_at"));}
    static String organizations(ActionScope scope){return RiskScenarioLockPlan.uuidArray(scope.organizationIds().stream().sorted(RiskScenarioContracts::compareUuid).toList());}
    private void poison(){if(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder holder)holder.setRollbackOnly();}
    private Failure refuse(int status){poison();return new Failure(status);}
    private Failure failed(SQLException error){String state=error.getSQLState();return refuse(state!=null&&(state.startsWith("23")||state.equals("40001")||state.equals("40P01"))?409:503);}
    @FunctionalInterface private interface Mapper<T>{T read(Row row);}
}

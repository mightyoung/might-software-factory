package com.mightyoung.softwarefactory.dualprevention.risk;

import com.mightyoung.softwarefactory.authorization.EntityActionAuthorization.ActionScope;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.*;
import com.mightyoung.softwarefactory.dualprevention.hazard.HazardSourceContracts.ExistenceState;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static com.mightyoung.softwarefactory.authorization.RegisteredEntityAction.*;
import static com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioContracts.*;

/** Finite read SQL on a risk-owned RO/RR capsule. Never acquires a connection or uses the write graph. */
public final class RiskScenarioReadRepository {
    private final DataSource source;
    public RiskScenarioReadRepository(DataSource source){this.source=Objects.requireNonNull(source);}
    void requireSource(DataSource expected){if(source!=expected)throw new IllegalArgumentException("Risk read repository/source mismatch");}
    record Root(UUID id,UUID organizationId,String code,long version,String description,String triggeringConditions,String exposureDescription,String consequence,Instant createdAt,Instant updatedAt) {
        Root{Objects.requireNonNull(id);Objects.requireNonNull(organizationId);RiskScenarioContracts.code(code);RiskScenarioContracts.version(version);narrative(description);narrative(triggeringConditions);narrative(exposureDescription);narrative(consequence);instant(createdAt);instant(updatedAt);}
    }
    record RevisionKey(long version,Instant occurredAt,UUID commandId) { }
    record SnapshotKey(UUID hazardId) { }
    record QueryKey(UUID id,long version,List<Object> sortValues) { QueryKey {sortValues=List.copyOf(sortValues);} }
    private static final String ROOT_COLUMNS="s.id,s.organization_id,s.code,s.version,s.description,s.triggering_conditions,s.exposure_description,s.consequence,s.created_at,s.updated_at";
    private static final String ROOT_SCOPE="s.tenant_id=? and s.organization_id=any(cast(? as uuid[]))";
    void setTenant(UUID tenant) {
        if(!(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder holder)
                ||!holder.isSynchronizedWithTransaction()||holder.isRollbackOnly()||!TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw new Failure(503);
        try(var q=holder.getConnection().prepareStatement("select set_config('app.tenant_id',?,true)")) {
            q.setQueryTimeout(5);q.setString(1,tenant.toString());try(var r=q.executeQuery()){if(!r.next()||!tenant.toString().equals(r.getString(1))||r.next())throw new Failure(503);}
        }catch(SQLException failure){holder.setRollbackOnly();throw new Failure(503);}
    }
    Optional<Root> parent(RiskScenarioReadService.ReadSnapshot snapshot,ActionScope scope,UUID id) {
        requireAction(snapshot,scope,RISK_SCENARIO_READ,RISK_SCENARIO_CREATE,RISK_SCENARIO_UPDATE);
        return one(snapshot,scope,"select "+ROOT_COLUMNS+" from public.risk_scenario s where "+ROOT_SCOPE+" and s.id=?",scopeValues(scope,id),RiskScenarioReadRepository::root);
    }
    boolean organization(RiskScenarioReadService.ReadSnapshot snapshot,ActionScope scope,UUID id) {
        requireAction(snapshot,scope,RISK_SCENARIO_CREATE,RISK_SCENARIO_UPDATE);
        if(!scope.organizationIds().contains(id))return false;
        return one(snapshot,scope,"select o.id from public.org_unit o where o.tenant_id=? and o.id=? and o.active",List.of(scope.context().tenantId(),id),r->r.getObject(1,UUID.class)).isPresent();
    }
    List<RevisionKey> revisionKeys(RiskScenarioReadService.ReadSnapshot snapshot,ActionScope scope,UUID id,HistoryRequest request) {
        requireAction(snapshot,scope,RISK_SCENARIO_READ);
        var values=scopeValues(scope,id);String after="";
        if(request.after()!=null){after=" and (r.occurred_at,r.command_id)>(?,?)";values.add(request.after().occurredAt());values.add(request.after().commandId());}
        values.add(request.pageSize()+1);
        return rows(snapshot,scope,"select r.version,r.occurred_at,r.command_id from public.risk_scenario_revision r join public.risk_scenario s on s.tenant_id=r.tenant_id and s.id=r.entity_id where "+ROOT_SCOPE+" and s.id=?"+after+" order by r.occurred_at,r.command_id limit ?",values,51,r->new RevisionKey(r.getLong(1),time(r,2),r.getObject(3,UUID.class)));
    }
    boolean historyAnchor(RiskScenarioReadService.ReadSnapshot snapshot,ActionScope scope,UUID id,HistoryCursor after) {
        requireAction(snapshot,scope,RISK_SCENARIO_READ);
        if(after==null)return true;var values=scopeValues(scope,id);values.add(after.occurredAt());values.add(after.commandId());
        return one(snapshot,scope,"select r.version from public.risk_scenario_revision r join public.risk_scenario s on s.tenant_id=r.tenant_id and s.id=r.entity_id where "+ROOT_SCOPE+" and s.id=? and r.occurred_at=? and r.command_id=?",values,r->r.getLong(1)).isPresent();
    }
    Optional<HistoryEntry> revision(RiskScenarioReadService.ReadSnapshot snapshot,ActionScope scope,UUID id,long version,Hazards hazards) {
        requireAction(snapshot,scope,RISK_SCENARIO_READ);
        var values=scopeValues(scope,id);values.add(version);
        return one(snapshot,scope,"select r.version,r.command_id,r.occurred_at,r.organization_id,r.code,r.description,r.triggering_conditions,r.exposure_description,r.consequence from public.risk_scenario_revision r join public.risk_scenario s on s.tenant_id=r.tenant_id and s.id=r.entity_id where "+ROOT_SCOPE+" and s.id=? and r.version=?",values,
                r->new HistoryEntry(r.getLong(1),r.getObject(2,UUID.class),time(r,3),r.getObject(4,UUID.class),r.getString(5),r.getString(6),r.getString(7),r.getString(8),r.getString(9),AssessmentStatus.UNASSESSED,null,hazards));
    }
    boolean revisionExists(RiskScenarioReadService.ReadSnapshot snapshot,ActionScope scope,UUID id,long version) {
        requireAction(snapshot,scope,RISK_SCENARIO_READ);
        var values=scopeValues(scope,id);values.add(version);
        return one(snapshot,scope,"select r.version from public.risk_scenario_revision r join public.risk_scenario s on s.tenant_id=r.tenant_id and s.id=r.entity_id where "+ROOT_SCOPE+" and s.id=? and r.version=?",values,row->row.getLong(1)).isPresent();
    }
    List<SnapshotKey> snapshotKeys(RiskScenarioReadService.ReadSnapshot snapshot,ActionScope scope,UUID id,long version) {
        requireAction(snapshot,scope,RISK_SCENARIO_READ);
        var values=scopeValues(scope,id);values.add(version);
        return rows(snapshot,scope,"select d.hazard_id from public.risk_scenario_revision_hazard d join public.risk_scenario s on s.tenant_id=d.tenant_id and s.id=d.scenario_id where "+ROOT_SCOPE+" and s.id=? and d.scenario_version=? order by d.hazard_id limit 101",values,100,r->new SnapshotKey(r.getObject(1,UUID.class)));
    }
    boolean targetVisible(RiskScenarioReadService.ReadSnapshot snapshot,ActionScope scope,UUID hazard) {
        requireAction(snapshot,scope,HAZARD_SOURCE_READ);
        return one(snapshot,scope,"select h.id from public.hazard_source h join public.org_unit o on o.tenant_id=h.tenant_id and o.id=h.organization_id and o.active where h.tenant_id=? and h.id=? and h.organization_id=any(cast(? as uuid[]))",List.of(scope.context().tenantId(),hazard,organizations(scope)),r->r.getObject(1,UUID.class)).isPresent();
    }
    List<HazardSummary> summaries(RiskScenarioReadService.ReadSnapshot snapshot,ActionScope scope,UUID id,long version) {
        requireAction(snapshot,scope,RISK_SCENARIO_READ);
        var values=scopeValues(scope,id);values.add(version);
        return rows(snapshot,scope,"select d.hazard_id,d.link_id,d.link_version,d.snapshot_code,d.description_preview,d.description_truncated,d.existence,d.hazard_version_at_link,d.hazard_version_at_scenario_revision from public.risk_scenario_revision_hazard d join public.risk_scenario s on s.tenant_id=d.tenant_id and s.id=d.scenario_id where "+ROOT_SCOPE+" and s.id=? and d.scenario_version=? order by d.hazard_id limit 101",values,100,
                r->new HazardSummary(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getLong(3),r.getString(4),r.getString(5),r.getBoolean(6),ExistenceState.valueOf(r.getString(7)),r.getLong(8),r.getLong(9)));
    }
    Optional<HistoricalHazardSnapshot> historicalSnapshot(RiskScenarioReadService.ReadSnapshot snapshot,ActionScope scope,UUID id,long version,UUID hazard) {
        requireAction(snapshot,scope,RISK_SCENARIO_READ);
        var values=scopeValues(scope,id);values.add(version);values.add(hazard);
        return one(snapshot,scope,"select d.hazard_id,d.link_id,d.link_version,d.snapshot_code,d.full_description,d.existence,d.hazard_version_at_link,d.hazard_version_at_scenario_revision from public.risk_scenario_revision_hazard d join public.risk_scenario s on s.tenant_id=d.tenant_id and s.id=d.scenario_id where "+ROOT_SCOPE+" and s.id=? and d.scenario_version=? and d.hazard_id=?",values,
                r->new HistoricalHazardSnapshot(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getLong(3),r.getString(4),r.getString(5),ExistenceState.valueOf(r.getString(6)),r.getLong(7),r.getLong(8)));
    }
    List<UUID> eligibleKeys(RiskScenarioReadService.ReadSnapshot snapshot,ActionScope target,SelectorRequest request) {
        requireAction(snapshot,target,HAZARD_SOURCE_READ);
        var values=eligibleValues(target,request);String after="";if(request.afterId()!=null){after=" and h.id>?";values.add(request.afterId());}values.add(request.limit()+1);
        return rows(snapshot,target,eligibleSql()+after+" order by h.id limit ?",values,51,r->r.getObject(1,UUID.class));
    }
    boolean selectorAnchor(RiskScenarioReadService.ReadSnapshot snapshot,ActionScope target,SelectorRequest request) {
        requireAction(snapshot,target,HAZARD_SOURCE_READ);
        if(request.afterId()==null)return true;var values=eligibleValues(target,request);values.add(request.afterId());
        return one(snapshot,target,eligibleSql()+" and h.id=?",values,r->r.getObject(1,UUID.class)).isPresent();
    }
    Optional<HazardCandidate> candidate(RiskScenarioReadService.ReadSnapshot snapshot,ActionScope scope,UUID id) {
        requireAction(snapshot,scope,HAZARD_SOURCE_READ);
        return one(snapshot,scope,"select h.id,h.organization_id,h.code,h.version,left(h.description,200),char_length(h.description)>200,h.existence_state from public.hazard_source h join public.org_unit o on o.tenant_id=h.tenant_id and o.id=h.organization_id and o.active where h.tenant_id=? and h.organization_id=any(cast(? as uuid[])) and h.id=?",List.of(scope.context().tenantId(),organizations(scope),id),
                r->new HazardCandidate(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),r.getLong(4),r.getString(5),r.getBoolean(6),ExistenceState.valueOf(r.getString(7))));
    }
    private static String eligibleSql(){return "select h.id from public.hazard_source h join public.org_unit o on o.tenant_id=h.tenant_id and o.id=h.organization_id and o.active where h.tenant_id=? and h.organization_id=any(cast(? as uuid[])) and (h.code ilike ? escape '!' or h.description ilike ? escape '!')";}
    private static ArrayList<Object> eligibleValues(ActionScope scope,SelectorRequest request){String query="%"+escape(request.query())+"%";return new ArrayList<>(List.of(scope.context().tenantId(),organizations(scope),query,query));}

    List<QueryKey> queryKeys(RiskScenarioReadService.ReadSnapshot snapshot,ActionScope scope,QueryRequest request,QueryKey after) {
        requireAction(snapshot,scope,RISK_SCENARIO_READ);
        var query=predicate(scope,request);String seek="";if(after!=null){var alternatives=new ArrayList<String>();var prefix=new ArrayList<String>();var sorts=sorts(request);for(int i=0;i<sorts.size();i++){var sort=sorts.get(i);String field=orderColumn(sort.field());var branch=new ArrayList<>(prefix);branch.add(field+(sort.direction()==Direction.ASC?">?":"<?"));alternatives.add("("+String.join(" and ",branch)+")");for(int j=0;j<i;j++)query.values.add(after.sortValues().get(j));query.values.add(after.sortValues().get(i));prefix.add(field+"=?");}seek=" and ("+String.join(" or ",alternatives)+")";}
        query.values.add(request.size()+1);query.values.add(request.offset());
        return rows(snapshot,scope,"select "+keyColumns(request)+" from public.risk_scenario s where "+query.sql+seek+" order by "+order(request)+" limit ? offset ?",query.values,101,r->key(r,request));
    }
    Optional<QueryKey> queryAnchor(RiskScenarioReadService.ReadSnapshot snapshot,ActionScope scope,QueryRequest request,RiskScenarioReadCursorCodec.Anchor anchor) {
        requireAction(snapshot,scope,RISK_SCENARIO_READ);
        var query=predicate(scope,request);query.values.add(anchor.id());query.values.add(anchor.version());
        return one(snapshot,scope,"select "+keyColumns(request)+" from public.risk_scenario s where "+query.sql+" and s.id=? and s.version=?",query.values,r->key(r,request));
    }
    long count(RiskScenarioReadService.ReadSnapshot snapshot,ActionScope scope,QueryRequest request) {
        requireAction(snapshot,scope,RISK_SCENARIO_READ);
        var query=predicate(scope,request);return one(snapshot,scope,"select count(*) from public.risk_scenario s where "+query.sql,query.values,r->r.getLong(1)).orElseThrow(()->new Failure(503));
    }
    Map<String,Object> projection(RiskScenarioReadService.ReadSnapshot snapshot,ActionScope scope,QueryRequest request,QueryKey key) {
        requireAction(snapshot,scope,RISK_SCENARIO_READ);
        var query=predicate(scope,request);query.values.add(key.id());query.values.add(key.version());
        String columns=request.fields().isEmpty()?"s.id":String.join(",",request.fields().stream().map(RiskScenarioReadRepository::column).toList());
        return one(snapshot,scope,"select "+columns+" from public.risk_scenario s where "+query.sql+" and s.id=? and s.version=?",query.values,r->{var value=new LinkedHashMap<String,Object>();for(int i=0;i<request.fields().size();i++){String name=request.fields().get(i);Object field=switch(name){case "id","organizationId"->r.getObject(i+1,UUID.class);case "version"->r.getLong(i+1);case "createdAt","updatedAt"->time(r,i+1);default->r.getString(i+1);};value.put(name,field);}return Collections.unmodifiableMap(value);}).orElseThrow(()->new Failure(503));
    }
    private record Predicate(String sql,ArrayList<Object> values) { }
    private static Predicate predicate(ActionScope scope,QueryRequest request) {
        if(scope.action()!=RISK_SCENARIO_READ)throw new Failure(503);validate(request);var values=new ArrayList<Object>(List.of(scope.context().tenantId(),organizations(scope)));var conditions=new ArrayList<String>();conditions.add(ROOT_SCOPE);
        for(var f:request.filters()) {String col=column(f.field());String part;Object value=f.values().isEmpty()?null:f.values().getFirst();
            if(value instanceof BigDecimal decimal){part=numberCondition(col,f.operator(),decimal,f.values().size()==2?(BigDecimal)f.values().get(1):null,values);}
            else part=switch(f.operator()){
                case IS_EMPTY->col+"=''";case IS_NOT_EMPTY->col+"<>''";
                case CONTAINS->{values.add("%"+escape((String)value)+"%");yield col+" ilike ? escape '!'";}
                case IN->{values.addAll(f.values());yield col+" in ("+String.join(",",Collections.nCopies(f.values().size(),"?"))+")";}
                case BETWEEN->{values.addAll(f.values());yield col+" between ? and ?";}
                default->{values.add(value);yield col+switch(f.operator()){case EQ->"=?";case GT->">?";case GTE->">=?";case LT->"<?";case LTE->"<=?";default->throw invalid();};}
            };conditions.add(part);
        }return new Predicate(String.join(" and ",conditions),values);
    }
    private static String numberCondition(String col,FilterOperator op,BigDecimal value,BigDecimal upper,List<Object> values){
        long floor=floor(value),ceil=ceil(value);return switch(op){
            case EQ->{if(value.stripTrailingZeros().scale()>0)yield "false";values.add(floor);yield col+"=?";}
            case GT->{values.add(floor+1);yield col+">=?";}case GTE->{values.add(ceil);yield col+">=?";}
            case LT->{values.add(ceil-1);yield col+"<=?";}case LTE->{values.add(floor);yield col+"<=?";}
            case BETWEEN->{values.add(ceil);values.add(floor(upper));yield col+" between ? and ?";}default->throw invalid();};
    }
    private static long floor(BigDecimal value){if(value.signum()==0)return 0;if(value.abs().compareTo(BigDecimal.ONE)<0)return value.signum()<0?-1:0;return value.setScale(0,RoundingMode.FLOOR).longValueExact();}
    private static long ceil(BigDecimal value){long floor=floor(value);return value.compareTo(BigDecimal.valueOf(floor))==0?floor:floor+1;}
    static void validate(QueryRequest request) {
        Objects.requireNonNull(request);if(request.filters().size()>50||request.sort().size()>4||request.fields().size()>50||new HashSet<>(request.fields()).size()!=request.fields().size())throw invalid();
        var schema=RiskScenarioEntitySchema.schema();for(var f:request.fields())if(!schema.fields().containsKey(f))throw invalid();
        var seen=new HashSet<String>();for(var sort:request.sort())if(!seen.add(sort.field())||!RiskScenarioEntitySchema.SORTABLE_FIELDS.contains(sort.field())&&!sort.field().equals("id")||sort.field().equals("id")&&sort.direction()!=Direction.ASC)throw invalid();
        if(request.sort().stream().filter(s->!s.field().equals("id")).count()>3)throw invalid();
        long utf8=0;for(var f:request.filters()) {var field=schema.fields().get(f.field());if(field==null||!field.operators().contains(f.operator()))throw invalid();
            int n=f.values().size();boolean unary=f.operator()==FilterOperator.IS_EMPTY||f.operator()==FilterOperator.IS_NOT_EMPTY;
            if(unary?n!=0:f.operator()==FilterOperator.IN?n<1||n>100:f.operator()==FilterOperator.BETWEEN?n!=2:n!=1)throw invalid();
            for(var value:f.values())switch(field.type()){
                case UUID->{if(!(value instanceof UUID))throw invalid();}
                case NUMBER->{if(!(value instanceof BigDecimal d)||d.abs().compareTo(BigDecimal.valueOf(MAX_VERSION))>0)throw invalid();}
                case DATETIME->{if(!(value instanceof OffsetDateTime d))throw invalid();instant(d.toInstant());}
                case ENUM->{if(!(value instanceof String text)||!field.enumValues().contains(text))throw invalid();}
                case TEXT->{if(!(value instanceof String text))throw invalid();unicode(text);if(text.codePointCount(0,text.length())>field.maxLength())throw invalid();utf8+=text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;}
                default->throw invalid();
            }
            if(f.operator()==FilterOperator.BETWEEN&&compare(f.values().get(0),f.values().get(1))>0)throw invalid();
        }if(utf8>QUERY_BODY_BYTES)throw invalid();
    }
    @SuppressWarnings({"unchecked","rawtypes"}) private static int compare(Object a,Object b){return ((Comparable)a).compareTo(b);}
    private static List<Sort> sorts(QueryRequest request){var sorts=new ArrayList<>(request.sort());sorts.removeIf(s->s.field().equals("id"));sorts.add(new Sort("id",Direction.ASC));return List.copyOf(sorts);}
    private static QueryKey key(ResultSet row,QueryRequest request)throws SQLException {var values=new ArrayList<Object>();int i=3;for(var sort:sorts(request)){Object value=switch(sort.field()){case "id","organizationId"->row.getObject(i,UUID.class);case "version"->row.getLong(i);case "createdAt","updatedAt"->row.getObject(i,OffsetDateTime.class);default->row.getString(i);};values.add(value);i++;}return new QueryKey(row.getObject(1,UUID.class),row.getLong(2),values);}
    private static String keyColumns(QueryRequest request){return "s.id,s.version,"+String.join(",",sorts(request).stream().map(s->column(s.field())).toList());}
    private static String order(QueryRequest request){return String.join(",",sorts(request).stream().map(s->orderColumn(s.field())+(s.direction()==Direction.ASC?" asc":" desc")).toList());}
    private static String orderColumn(String field){return column(field)+(field.equals("code")?" collate \"C\"":"");}
    static String column(String field){return switch(field){case "id","code","version","description","consequence"->"s."+field;case "organizationId"->"s.organization_id";case "triggeringConditions"->"s.triggering_conditions";case "exposureDescription"->"s.exposure_description";case "createdAt"->"s.created_at";case "updatedAt"->"s.updated_at";case "assessmentStatus"->"cast('UNASSESSED' as text)";default->throw invalid();};}
    private static Root root(ResultSet r)throws SQLException{return new Root(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),r.getLong(4),r.getString(5),r.getString(6),r.getString(7),r.getString(8),time(r,9),time(r,10));}
    private static Instant time(ResultSet r,int index)throws SQLException{return r.getObject(index,OffsetDateTime.class).toInstant();}
    private static void requireAction(RiskScenarioReadService.ReadSnapshot snapshot,ActionScope scope,com.mightyoung.softwarefactory.authorization.RegisteredEntityAction... allowed){snapshot.connection(scope);if(Arrays.stream(allowed).noneMatch(action->scope.action()==action))throw snapshot.invalidate();}
    private static UUID[] organizations(ActionScope scope){return scope.organizationIds().stream().sorted(Comparator.comparing(UUID::toString)).toArray(UUID[]::new);}
    private static ArrayList<Object> scopeValues(ActionScope scope,UUID id){return new ArrayList<>(List.of(scope.context().tenantId(),organizations(scope),id));}
    private static String escape(String text){return text.replace("!","!!").replace("%","!%").replace("_","!_");}
    private <T> Optional<T> one(RiskScenarioReadService.ReadSnapshot snapshot,ActionScope scope,String sql,List<Object> values,Mapper<T> mapper){var result=rows(snapshot,scope,sql,values,1,mapper);return result.isEmpty()?Optional.empty():Optional.of(result.getFirst());}
    private <T> List<T> rows(RiskScenarioReadService.ReadSnapshot snapshot,ActionScope scope,String sql,List<Object> values,int max,Mapper<T> mapper) {
        Connection connection=snapshot.connection(scope);var result=new ArrayList<T>();var arrays=new ArrayList<java.sql.Array>();
        try(var query=connection.prepareStatement(sql)){
            query.setQueryTimeout(5);query.setMaxRows(max+1);int i=1;
            for(Object value:values){if(value instanceof UUID[] ids){var array=connection.createArrayOf("uuid",ids);arrays.add(array);query.setArray(i++,array);}else if(value instanceof Instant time)query.setObject(i++,time.atOffset(ZoneOffset.UTC));else query.setObject(i++,value);}
            try(var rows=query.executeQuery()){while(rows.next()){if(result.size()==max)throw new Failure(503);result.add(mapper.read(rows));}}
            if(snapshot.connection(scope)!=connection)throw new Failure(503);return List.copyOf(result);
        }catch(SQLException failure){snapshot.invalidate();throw new Failure(503);}catch(RuntimeException failure){snapshot.invalidate();if(failure instanceof Failure refusal&&refusal.status()==503)throw refusal;throw new Failure(503);}catch(Error failure){snapshot.invalidate();throw failure;}
        finally{for(var array:arrays)try{array.free();}catch(SQLException failure){snapshot.invalidate();}}
    }
    @FunctionalInterface private interface Mapper<T>{T read(ResultSet row)throws SQLException;}
}

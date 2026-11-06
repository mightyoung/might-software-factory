package com.mightyoung.softwarefactory.dualprevention.asset;

import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.Direction;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.PageMetadata;
import com.mightyoung.softwarefactory.authorization.EntityActionAuthorization.ActionScope;
import com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationEntitySchemas.Root;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.SortField;
import org.jooq.conf.Settings;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import static com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationQueryContracts.*;

/** Finite root mapping on the already authorized caller connection; no data-source acquisition. */
public final class AssetFoundationQueryRepository {
    private final AssetFoundationRepository owner;
    public AssetFoundationQueryRepository(AssetFoundationRepository owner){this.owner=Objects.requireNonNull(owner);}

    Page query(Root root,Plan plan,ActionScope scope) {
        owner.require(scope);
        if(scope.action()!=root.readAction())throw new AssetFoundationFailure(503);
        try {
            DSLContext sql=database();
            Condition allowed=where(root,plan,scope);
            byte[] fingerprint=AssetFoundationQueryCursor.fingerprint(root,plan,scope);
            Condition page=DSL.trueCondition();
            if(plan.cursor()!=null) {
                var anchor=AssetFoundationQueryCursor.decode(plan.cursor(),root,fingerprint);
                Record row=sql.select(projection(root,plan)).from(table(root))
                        .where(allowed.and(uuid("id").eq(anchor.id())).and(number("version").eq(anchor.version())))
                        .fetchOne();
                if(row==null)throw AssetFoundationReadJson.invalid();
                page=seek(root,plan.sort(),row);
            }
            List<Record> found=new ArrayList<>(sql.select(projection(root,plan)).from(table(root))
                    .where(allowed.and(page)).orderBy(order(root,plan.sort())).limit(plan.size()+1).offset(plan.offset()).fetch());
            boolean more=found.size()>plan.size();
            List<Record> rows=found.subList(0,Math.min(plan.size(),found.size()));
            var items=rows.stream().map(row->wire(root,plan.fields(),row)).toList();
            String next=more?AssetFoundationQueryCursor.encode(root,fingerprint,rows.getLast().get("id",UUID.class),rows.getLast().get("version",Long.class)):null;
            owner.require(scope);
            return new Page(items,new PageMetadata(plan.size(),plan.number(),next,null));
        }catch(DataAccessException failure){throw new AssetFoundationFailure(503,failure);}
    }

    private DSLContext database(){return DSL.using(owner.connection(),SQLDialect.POSTGRES,
            new Settings().withExecuteLogging(false).withFetchWarnings(false).withBindOffsetDateTimeType(true));}
    static org.jooq.Table<?> table(Root root){return DSL.table(DSL.name("public",root.key())).as("a");}
    static Condition where(Root root,Plan plan,ActionScope scope) {
        Condition result=uuid("tenant_id").eq(scope.context().tenantId())
                .and(DSL.condition("{0} = any(cast({1} as uuid[]))",uuid("organization_id"),DSL.val(AssetFoundationRepository.organizations(scope))));
        for(Filter filter:plan.filters())result=result.and(condition(root,filter));
        return result;
    }
    static Condition condition(Root root,Filter filter) {
        Field<Object> field=field(root,filter.field());
        Object value=filter.values().isEmpty()?null:filter.values().getFirst();
        return switch(filter.operator()) {
            case IS_NULL->field.isNull();case IS_NOT_NULL->field.isNotNull();
            case IS_EMPTY->field.eq("");case IS_NOT_EMPTY->field.ne("");
            case CONTAINS->field.coerce(String.class).likeIgnoreCase("%"+escape((String)value)+"%",'\\');
            case IN->field.in(filter.values());
            case BETWEEN->value instanceof BigDecimal low?
                    integerCondition(number(column(filter.field())),filter.operator(),low,(BigDecimal)filter.values().get(1)):
                    field.ge(value).and(field.le(filter.values().get(1)));
            case EQ,GT,GTE,LT,LTE->value instanceof BigDecimal decimal?
                    integerCondition(number(column(filter.field())),filter.operator(),decimal,null):compare(field,filter.operator(),value);
            default->throw AssetFoundationReadJson.invalid();
        };
    }
    static Condition integerCondition(Field<Long> field,com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator operator,BigDecimal value,BigDecimal upper) {
        long floor=floor(value),ceil=ceil(value);
        return switch(operator) {
            case EQ->value.stripTrailingZeros().scale()>0?DSL.falseCondition():field.eq(floor);
            case GT->field.ge(floor+1);case GTE->field.ge(ceil);case LT->field.le(ceil-1);case LTE->field.le(floor);
            case BETWEEN->field.ge(ceil).and(field.le(floor(upper)));
            default->throw AssetFoundationReadJson.invalid();
        };
    }
    private static long floor(BigDecimal value) {
        if(value.signum()==0)return 0;
        if(value.abs().compareTo(BigDecimal.ONE)<0)return value.signum()<0?-1:0;
        return value.setScale(0,RoundingMode.FLOOR).longValueExact();
    }
    private static long ceil(BigDecimal value){long floor=floor(value);return value.compareTo(BigDecimal.valueOf(floor))==0?floor:floor+1;}
    private static Condition compare(Field<Object> field,com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator operator,Object value) {
        return switch(operator){case EQ->field.eq(value);case GT->field.gt(value);case GTE->field.ge(value);case LT->field.lt(value);case LTE->field.le(value);default->throw AssetFoundationReadJson.invalid();};
    }
    static Condition seek(Root root,List<Sort> sorts,Record anchor) {
        Condition prefix=DSL.trueCondition(),result=DSL.falseCondition();
        for(Sort sort:sorts) {
            Field<Object> field=orderedField(root,sort.field());Object value=anchor.get(sort.field());
            if(value!=null)result=result.or(prefix.and((sort.direction()==Direction.ASC?field.gt(value):field.lt(value)).or(field.isNull())));
            prefix=prefix.and(field.isNotDistinctFrom(value));
        }
        return result;
    }
    static List<SortField<?>> order(Root root,List<Sort> sorts) {
        var result=new ArrayList<SortField<?>>();
        for(Sort sort:sorts){Field<Object> field=orderedField(root,sort.field());result.add(sort.direction()==Direction.ASC?field.asc().nullsLast():field.desc().nullsLast());}
        return result;
    }
    private static List<Field<?>> projection(Root root,Plan plan) {
        var names=new LinkedHashSet<>(plan.fields());plan.sort().forEach(sort->names.add(sort.field()));names.add("id");names.add("version");
        if(root==Root.WORK_UNIT)names.add("kind");
        var result=new ArrayList<Field<?>>();for(String key:names)result.add(field(root,key).as(key));return result;
    }
    static Map<String,Object> wire(Root root,List<String> fields,Record row) {
        var result=new LinkedHashMap<String,Object>();
        for(String key:fields) {
            if(root==Root.WORK_UNIT&&List.of("workshopId","lineId","stageId","equipmentId").contains(key)
                    &&!sourceKey(row.get("kind",String.class)).equals(key))continue;
            Object value=row.get(key);
            result.put(key,value instanceof OffsetDateTime time?time.toInstant():value);
        }
        return result;
    }
    private static String sourceKey(String kind){return switch(kind){case "WORKSHOP"->"workshopId";case "LINE"->"lineId";case "STAGE"->"stageId";case "EQUIPMENT"->"equipmentId";default->"";};}
    @SuppressWarnings("unchecked")
    static Field<Object> field(Root root,String key) {
        var schema=AssetFoundationEntitySchemas.schema(root).fields().get(key);
        if(schema==null)throw AssetFoundationReadJson.invalid();
        Class<?> type=switch(schema.type()){case UUID,USER,ORGANIZATION->UUID.class;case NUMBER->Long.class;case DATETIME->OffsetDateTime.class;default->String.class;};
        return (Field<Object>)(Field<?>)DSL.field(DSL.name("a",column(key)),type);
    }
    @SuppressWarnings("unchecked")
    private static Field<Object> orderedField(Root root,String key){Field<Object> result=field(root,key);return result.getType().equals(String.class)?(Field<Object>)(Field<?>)result.coerce(String.class).collate("C"):result;}
    static String column(String key) {
        return switch(key){
            case "organizationId"->"organization_id";case "boundaryDescription"->"boundary_description";case "workshopId"->"workshop_id";
            case "lineId"->"line_id";case "stageId"->"stage_id";case "equipmentId"->"equipment_id";case "workUnitId"->"work_unit_id";
            case "typeDescription"->"type_description";case "operationalState"->"operational_state";case "locationDescription"->"location_description";
            case "createdAt"->"created_at";case "updatedAt"->"updated_at";case "identifiedAt"->"identified_at";case "existenceState"->"existence_state";
            case "factorCodeRef"->"factor_code_ref";case "factorVersion"->"factor_version";
            case "id","version","code","name","description","lifecycle","ordinal","kind"->key;
            default->throw AssetFoundationReadJson.invalid();
        };
    }
    private static Field<UUID> uuid(String column){return DSL.field(DSL.name("a",column),UUID.class);}
    private static Field<Long> number(String column){return DSL.field(DSL.name("a",column),Long.class);}
    private static String escape(String value){return value.replace("\\","\\\\").replace("%","\\%").replace("_","\\_");}
}

package com.mightyoung.softwarefactory.dualprevention.asset;

import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.Direction;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FieldSchema;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator;
import com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationEntitySchemas.Root;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import static com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationQueryContracts.*;
import static com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationReadJson.*;

/** Asset scalar policies augment the shared structure without changing Finding query semantics. */
public final class AssetFoundationQueryValidator {
    private static final BigDecimal MAX_NUMBER=BigDecimal.valueOf(AssetContracts.MAX_VERSION);

    Plan validate(Root root,JsonNode input) {
        fields(input,Set.of("page","size"),Set.of("filterAST","sort","fields"));
        int size=integer(input.get("size"),20,100);
        if(size!=20&&size!=50&&size!=100)throw invalid();
        var page=input.get("page");fields(page,Set.of(),Set.of("number","cursor"));
        boolean number=page.hasNonNull("number"),cursor=page.hasNonNull("cursor");
        if(number==cursor)throw invalid();
        Integer pageNumber=number?integer(page.get("number"),1,501):null;
        if(pageNumber!=null&&(long)(pageNumber-1)*size>10000)throw invalid();
        String pageCursor=cursor?text(page.get("cursor")):null;
        if(pageCursor!=null&&(pageCursor.isBlank()||pageCursor.length()>2048))throw invalid();
        var schema=AssetFoundationEntitySchemas.schema(root);
        List<String> projection=new ArrayList<>();
        JsonNode requested=input.get("fields");
        if(requested==null||requested.isNull())projection.addAll(schema.defaultFields());
        else {
            if(!requested.isArray()||requested.size()>50)throw invalid();
            for(JsonNode value:requested) {
                String key=text(value);FieldSchema field=schema.fields().get(key);
                if(field==null||!field.selectable()||projection.contains(key))throw invalid();
                projection.add(key);
            }
        }
        List<Sort> sort=new ArrayList<>();
        JsonNode ordering=input.get("sort");
        if(ordering!=null&&!ordering.isNull()) {
            if(!ordering.isArray()||ordering.size()>3)throw invalid();
            Set<String> seen=new HashSet<>();
            for(JsonNode entry:ordering) {
                fields(entry,Set.of("field","direction"),Set.of());
                String key=text(entry.get("field"));FieldSchema field=schema.fields().get(key);
                if(field==null||!field.sortable()||!seen.add(key))throw invalid();
                sort.add(new Sort(key,choice(Direction.class,text(entry.get("direction")))));
            }
        }
        if(sort.stream().noneMatch(value->value.field().equals("id")))sort.add(new Sort("id",Direction.ASC));
        List<Filter> filters=new ArrayList<>();
        JsonNode group=input.get("filterAST");
        if(group!=null&&!group.isNull()) {
            fields(group,Set.of("operator","conditions"),Set.of());
            if(!text(group.get("operator")).equals("AND")||!group.get("conditions").isArray()||group.get("conditions").size()>50)throw invalid();
            for(JsonNode condition:group.get("conditions")) {
                fields(condition,Set.of("field","operator"),Set.of("value"));
                String key=text(condition.get("field"));FieldSchema field=schema.fields().get(key);
                FilterOperator operator=choice(FilterOperator.class,text(condition.get("operator")));
                if(field==null||!field.operators().contains(operator))throw invalid();
                boolean unary=switch(operator){case IS_EMPTY,IS_NOT_EMPTY,IS_NULL,IS_NOT_NULL->true;default->false;};
                if(unary) {
                    if(condition.has("value"))throw invalid();
                    filters.add(new Filter(key,operator,List.of()));continue;
                }
                JsonNode value=condition.get("value");
                List<Object> values=new ArrayList<>();
                if(operator==FilterOperator.IN||operator==FilterOperator.BETWEEN) {
                    if(value==null||!value.isArray()||value.isEmpty()||value.size()>(operator==FilterOperator.IN?100:2)
                            ||operator==FilterOperator.BETWEEN&&value.size()!=2)throw invalid();
                    for(JsonNode item:value)values.add(scalar(field,item));
                }else values.add(scalar(field,value));
                if(operator==FilterOperator.BETWEEN&&compare(values.get(0),values.get(1))>0)throw invalid();
                filters.add(new Filter(key,operator,values));
            }
        }
        return new Plan(filters,sort,projection,size,pageNumber,pageCursor);
    }

    private static Object scalar(FieldSchema field,JsonNode value) {
        if(value==null||value.isNull())throw invalid();
        return switch(field.type()) {
            case UUID,USER,ORGANIZATION -> uuid(text(value));
            case DATETIME -> time(text(value)).atOffset(java.time.ZoneOffset.UTC);
            case TEXT -> {
                String result=text(value);
                if(result.codePointCount(0,result.length())>field.maxLength())throw invalid();
                yield result;
            }
            case ENUM -> {
                String result=text(value);if(!field.enumValues().contains(result))throw invalid();yield result;
            }
            case NUMBER -> {
                if(!value.isNumber())throw invalid();
                BigDecimal number=value.decimalValue();
                if(number.abs().compareTo(MAX_NUMBER)>0)throw invalid();
                yield number.signum()==0?BigDecimal.ZERO:number.stripTrailingZeros();
            }
            default -> throw invalid();
        };
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    private static int compare(Object left,Object right){return ((Comparable)left).compareTo(right);}
    private static <T extends Enum<T>> T choice(Class<T> type,String value) {
        try{return Enum.valueOf(type,value);}catch(IllegalArgumentException error){throw invalid();}
    }
}

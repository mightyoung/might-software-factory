package com.mightyoung.softwarefactory.dualprevention.asset;

import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.Direction;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FilterOperator;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.PageMetadata;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable validated query values, distinct from both authority and SQL identifiers. */
public final class AssetFoundationQueryContracts {
    private AssetFoundationQueryContracts() { }
    record Filter(String field,FilterOperator operator,List<Object> values) {
        Filter { values=List.copyOf(values); }
    }
    record Sort(String field,Direction direction) { }
    record Plan(List<Filter> filters,List<Sort> sort,List<String> fields,int size,Integer number,String cursor) {
        Plan { filters=List.copyOf(filters);sort=List.copyOf(sort);fields=List.copyOf(fields); }
        int offset(){return number==null?0:(number-1)*size;}
    }
    public record Page(List<Map<String,Object>> items,PageMetadata page) {
        public Page {
            items=items.stream().map(row->Collections.unmodifiableMap(new LinkedHashMap<>(row))).toList();
        }
    }
}

package com.mightyoung.softwarefactory.dualprevention.asset;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationHistoryContracts.*;
import static com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationReadJson.*;

public final class AssetFoundationHistoryParser {
    private final ObjectMapper json;
    public AssetFoundationHistoryParser(ObjectMapper json){this.json=Objects.requireNonNull(json);}
    Request parse(HttpServletRequest request){return parse(body(request,json,32768));}
    Request parse(JsonNode input){
        fields(input,Set.of(),Set.of("after","limit"));
        int limit=input.has("limit")?integer(input.get("limit"),1,50):20;
        Cursor after=null;
        if(input.hasNonNull("after")){
            JsonNode cursor=input.get("after");fields(cursor,Set.of("occurredAt","id"),Set.of());
            after=new Cursor(time(text(cursor.get("occurredAt"))),uuid(text(cursor.get("id"))));
        }
        return new Request(after,limit);
    }
}

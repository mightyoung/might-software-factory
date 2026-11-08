package com.mightyoung.softwarefactory.dualprevention.asset;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mightyoung.softwarefactory.authorization.RegisteredEntityAction;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AssetFoundationHistoryContracts {
    private AssetFoundationHistoryContracts() {}
    enum Kind {
        LINE_WORKSHOP("line_workshop",RegisteredEntityAction.PRODUCTION_LINE_READ),
        STAGE_EQUIPMENT("stage_equipment",RegisteredEntityAction.PROCESS_STAGE_READ),
        RESPONSIBILITY_BINDING("responsibility_binding",RegisteredEntityAction.WORK_UNIT_READ);
        final String key;final RegisteredEntityAction read;
        Kind(String key,RegisteredEntityAction read){this.key=key;this.read=read;}
    }
    public record Cursor(Instant occurredAt,UUID id) {}
    record Request(Cursor after,int limit) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Page(List<Map<String,Object>> items,Cursor nextAfter){public Page{items=List.copyOf(items);}}
    public record Endpoint(UUID id,long version,String code,String name) {}
}

package com.mightyoung.softwarefactory.platformadmin;

import org.springframework.web.bind.annotation.*;
import java.util.UUID;
import static com.mightyoung.softwarefactory.platformadmin.RuntimeLedgerCommandModels.*;

@RestController
@RequestMapping("/api/v1/ledgers/{key}/records")
public final class RuntimeLedgerCommandController {
    private final RuntimeLedgerCommandService service;
    public RuntimeLedgerCommandController(RuntimeLedgerCommandService service) { this.service=service; }
    @PostMapping public RecordView create(@PathVariable String key,@RequestBody CreateRequest request) { return service.create(key,request); }
    @PutMapping("/{id}") public RecordView update(@PathVariable String key,@PathVariable UUID id,@RequestBody UpdateRequest request) { return service.update(key,id,request); }
    @PostMapping("/{id}/delete") public RecordView delete(@PathVariable String key,@PathVariable UUID id,@RequestBody DeleteRequest request) { return service.delete(key,id,request); }
}

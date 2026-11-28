package com.mightyoung.softwarefactory.platformadmin;

import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import static com.mightyoung.softwarefactory.platformadmin.RuntimeLedgerReadModels.*;

@RestController
@RequestMapping("/api/v1/ledgers")
public final class RuntimeLedgerReadController {
    private final RuntimeLedgerReadService service;
    public RuntimeLedgerReadController(RuntimeLedgerReadService service) { this.service = service; }
    @GetMapping public Items catalog() { return service.catalog(); }
    @GetMapping("/{key}") public Item definition(@PathVariable String key) { return service.definition(key); }
    @PostMapping("/{key}/records/query") public Page query(@PathVariable String key, @RequestBody Query query) { return service.query(key, query); }
    @GetMapping("/{key}/records/{id}") public Detail detail(@PathVariable String key, @PathVariable UUID id) { return service.detail(key, id); }
}

package com.mightyoung.softwarefactory.platformadmin;

import org.springframework.web.bind.annotation.*;
import static com.mightyoung.softwarefactory.platformadmin.LedgerConfigurationModels.*;

@RestController
@RequestMapping("/api/v1/admin/ledger-configurations")
public final class LedgerConfigurationController {
    private final LedgerConfigurationService service;
    public LedgerConfigurationController(LedgerConfigurationService service) { this.service = service; }
    @GetMapping public Items list() { return service.list(); }
    @GetMapping("/{key}") public Snapshot latest(@PathVariable String key) { return service.latest(key); }
    @GetMapping("/{key}/versions") public Versions versions(@PathVariable String key) { return service.versions(key); }
    @PostMapping("/{key}/versions") public Snapshot save(@PathVariable String key, @RequestBody SaveRequest request) { return service.save(key, request); }
    @PostMapping("/{key}/restore") public Snapshot restore(@PathVariable String key, @RequestBody RestoreRequest request) { return service.restore(key, request); }
    @PostMapping("/{key}/publish") public Snapshot publish(@PathVariable String key, @RequestBody PublishRequest request) { return service.publish(key, request); }
}

package com.mightyoung.softwarefactory.ledger;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/apps/{appKey}/entities/{entityKey}")
public final class LedgerController {
    private final LedgerRuntime runtime;
    private final LedgerHttpParser parser;
    public LedgerController(LedgerRuntime runtime,LedgerHttpParser parser){this.runtime=runtime;this.parser=parser;}
    @GetMapping("/definition")
    public ResponseEntity<?> definition(HttpServletRequest request,@PathVariable String appKey,@PathVariable String entityKey,@RequestParam LedgerDefinition.Use use) {
        return response(runtime.definition(request,appKey,entityKey,use),UUID.randomUUID());
    }
    @PostMapping("/query")
    public ResponseEntity<?> query(HttpServletRequest request,@PathVariable String appKey,@PathVariable String entityKey) {
        return response(runtime.query(request,appKey,entityKey,parser.read(request,LedgerContracts.QueryRequest.class)),UUID.randomUUID());
    }
    @GetMapping("/records/{id}")
    public ResponseEntity<?> detail(HttpServletRequest request,@PathVariable String appKey,@PathVariable String entityKey,@PathVariable String id) {
        return response(runtime.detail(request,appKey,entityKey,LedgerHttpParser.uuid(id,"id")),UUID.randomUUID());
    }
    @PostMapping("/records")
    public ResponseEntity<?> create(HttpServletRequest request,@PathVariable String appKey,@PathVariable String entityKey,@RequestHeader("Idempotency-Key") String key) {
        UUID trace=UUID.randomUUID();return response(runtime.create(request,appKey,entityKey,LedgerHttpParser.uuid(key,"Idempotency-Key"),parser.read(request,LedgerContracts.CreateRequest.class),trace),trace);
    }
    @PostMapping("/records/{id}/update")
    public ResponseEntity<?> update(HttpServletRequest request,@PathVariable String appKey,@PathVariable String entityKey,@PathVariable String id,@RequestHeader("Idempotency-Key") String key) {
        UUID trace=UUID.randomUUID();return response(runtime.update(request,appKey,entityKey,LedgerHttpParser.uuid(id,"id"),LedgerHttpParser.uuid(key,"Idempotency-Key"),parser.read(request,LedgerContracts.UpdateRequest.class),trace),trace);
    }
    @PostMapping("/references/{referenceKey}/query")
    public ResponseEntity<?> references(HttpServletRequest request,@PathVariable String appKey,@PathVariable String entityKey,@PathVariable String referenceKey) {
        return response(runtime.references(request,appKey,entityKey,referenceKey,parser.read(request,LedgerContracts.ReferenceRequest.class)),UUID.randomUUID());
    }
    private static <T>ResponseEntity<LedgerContracts.Envelope<T>> response(T data,UUID trace) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new LedgerContracts.Envelope<>(data,new LedgerContracts.Meta(trace.toString())));
    }
}

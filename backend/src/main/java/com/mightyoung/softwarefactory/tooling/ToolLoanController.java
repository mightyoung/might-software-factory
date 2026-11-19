package com.mightyoung.softwarefactory.tooling;

import com.mightyoung.softwarefactory.ledger.LedgerContracts;
import com.mightyoung.softwarefactory.ledger.LedgerHttpParser;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tooling/loan-applications")
public final class ToolLoanController {
    private final ToolLoanService service;
    private final LedgerHttpParser parser;

    public ToolLoanController(ToolLoanService service, LedgerHttpParser parser) {
        this.service = service;
        this.parser = parser;
    }

    @GetMapping
    public ResponseEntity<?> list(HttpServletRequest request) {
        return response(service.list(request));
    }

    @GetMapping("/visibility/{visibility}")
    public ResponseEntity<?> scopedList(HttpServletRequest request,@PathVariable String visibility) {
        return response(service.scopedList(request,visibility));
    }

    @GetMapping("/visibility/{visibility}/{id}")
    public ResponseEntity<?> scopedDetail(HttpServletRequest request,@PathVariable String visibility,@PathVariable UUID id) {
        return response(service.scopedDetail(request,visibility,id));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> detail(HttpServletRequest request, @PathVariable UUID id) {
        return response(service.detail(request, id));
    }

    @GetMapping("/candidates")
    public ResponseEntity<?> candidates(HttpServletRequest request,
            @RequestParam(required = false) UUID custodyScopeId,
            @RequestParam(required = false) UUID loanId) {
        return response(service.candidates(request, custodyScopeId, loanId));
    }

    @GetMapping("/issue-queue")
    public ResponseEntity<?> issueQueue(HttpServletRequest request) {
        return response(service.issueQueue(request));
    }

    @GetMapping("/{id}/issue-detail")
    public ResponseEntity<?> issueDetail(HttpServletRequest request, @PathVariable UUID id) {
        return response(service.issueDetail(request, id));
    }

    @PostMapping("/{id}/issue")
    public ResponseEntity<?> issue(HttpServletRequest request, @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String key) {
        return response(service.issue(request, id, LedgerHttpParser.uuid(key,"Idempotency-Key"),
                parser.read(request, ToolLoanService.IssueInput.class)));
    }

    @GetMapping("/loss-queue")
    public ResponseEntity<?> lossQueue(HttpServletRequest request) {
        return response(service.lossQueue(request));
    }

    @GetMapping("/{id}/loss-detail")
    public ResponseEntity<?> lossDetail(HttpServletRequest request,@PathVariable UUID id) {
        return response(service.lossDetail(request,id));
    }

    @PostMapping("/{id}/close-lost")
    public ResponseEntity<?> closeLost(HttpServletRequest request,@PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String key) {
        return response(service.closeLost(request,id,LedgerHttpParser.uuid(key,"Idempotency-Key"),
                parser.read(request,ToolLoanService.LossInput.class)));
    }

    @GetMapping("/receive-queue")
    public ResponseEntity<?> receiveQueue(HttpServletRequest request) {
        return response(service.receiveQueue(request));
    }

    @GetMapping("/{id}/receive-detail")
    public ResponseEntity<?> receiveDetail(HttpServletRequest request,@PathVariable UUID id) {
        return response(service.receiveDetail(request,id));
    }

    @PostMapping("/{id}/return-requests")
    public ResponseEntity<?> requestReturn(HttpServletRequest request,@PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String key) {
        return response(service.requestReturn(request,id,LedgerHttpParser.uuid(key,"Idempotency-Key"),
                parser.read(request,ToolLoanService.ReturnInput.class)));
    }

    @PostMapping("/{id}/receive")
    public ResponseEntity<?> receive(HttpServletRequest request,@PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String key) {
        return response(service.receive(request,id,LedgerHttpParser.uuid(key,"Idempotency-Key"),
                parser.read(request,ToolLoanService.ReceiveInput.class)));
    }

    @PostMapping
    public ResponseEntity<?> create(HttpServletRequest request, @RequestHeader("Idempotency-Key") String key) {
        return response(service.command(request, null, LedgerHttpParser.uuid(key, "Idempotency-Key"),
                "CREATE", parser.read(request, ToolLoanService.Input.class)));
    }

    @PostMapping("/{id}/revise")
    public ResponseEntity<?> revise(HttpServletRequest request, @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String key) {
        return response(service.command(request, id, LedgerHttpParser.uuid(key, "Idempotency-Key"),
                "UPDATE", parser.read(request, ToolLoanService.Input.class)));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancel(HttpServletRequest request, @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String key) {
        return response(service.command(request, id, LedgerHttpParser.uuid(key, "Idempotency-Key"),
                "WITHDRAW", parser.read(request, ToolLoanService.Input.class)));
    }

    private static ResponseEntity<?> response(Object data) {
        var meta = new LedgerContracts.Meta(UUID.randomUUID().toString());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new LedgerContracts.Envelope<>(data, meta));
    }
}

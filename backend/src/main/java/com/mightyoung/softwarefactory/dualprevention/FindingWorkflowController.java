package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.dualprevention.FindingWorkflowApiModels.CommandResponse;
import com.mightyoung.softwarefactory.dualprevention.FindingWorkflowApiModels.CreateRequest;
import com.mightyoung.softwarefactory.dualprevention.FindingWorkflowApiModels.DispatchRequest;
import com.mightyoung.softwarefactory.dualprevention.FindingWorkflowApiModels.NotEstablishedRequest;
import com.mightyoung.softwarefactory.dualprevention.FindingWorkflowApiModels.PersonConfirmationRequest;
import com.mightyoung.softwarefactory.dualprevention.FindingWorkflowApiModels.SafetyConfirmationRequest;
import com.mightyoung.softwarefactory.dualprevention.FindingWorkflowApiModels.SubmitRemediationRequest;
import com.mightyoung.softwarefactory.dualprevention.FindingWorkflowApiModels.VerifyRequest;
import com.mightyoung.softwarefactory.dualprevention.FindingWorkflowApiModels.WorkflowInitializationRequest;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowCommandService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Session-authenticated HTTP entry points for the finding workflow command facade. */
@RestController
public final class FindingWorkflowController {

    private final FindingWorkflowCommandService service;

    public FindingWorkflowController(FindingWorkflowCommandService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/findings")
    public CommandResponse create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateRequest request) {
        return response(service.create(parseIdempotencyKey(idempotencyKey), request.toCommand()));
    }

    @PostMapping("/api/v1/findings/{id}/workflow-initialization")
    public CommandResponse initializeLegacyWorkflow(
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody WorkflowInitializationRequest request) {
        return response(service.initializeDiscoveredWorkflow(
                parseIdempotencyKey(idempotencyKey), id, request.expectedVersion()));
    }

    @PostMapping("/api/v1/findings/{id}/confirm/person")
    public CommandResponse confirmPerson(
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PersonConfirmationRequest request) {
        return response(service.confirmPerson(parseIdempotencyKey(idempotencyKey), id,
                request.expectedVersion(), request.toCommand()));
    }

    @PostMapping("/api/v1/findings/{id}/confirm/safety")
    public CommandResponse confirmSafety(
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody SafetyConfirmationRequest request) {
        return response(service.confirmSafety(parseIdempotencyKey(idempotencyKey), id,
                request.expectedVersion(), request.toCommand()));
    }

    @PostMapping("/api/v1/findings/{id}/close-not-established")
    public CommandResponse closeNotEstablished(
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody NotEstablishedRequest request) {
        return response(service.closeNotEstablished(parseIdempotencyKey(idempotencyKey), id,
                request.expectedVersion(), request.toCommand()));
    }

    @PostMapping("/api/v1/findings/{id}/dispatch")
    public CommandResponse dispatch(
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody DispatchRequest request) {
        return response(service.dispatch(parseIdempotencyKey(idempotencyKey), id,
                request.expectedVersion(), request.toCommand()));
    }

    @PostMapping("/api/v1/findings/{id}/remediation-submissions")
    public CommandResponse submitRemediation(
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody SubmitRemediationRequest request) {
        return response(service.submitRemediation(parseIdempotencyKey(idempotencyKey), id,
                request.expectedVersion(), request.toCommand()));
    }

    @PostMapping("/api/v1/findings/{id}/verifications")
    public CommandResponse verify(
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody VerifyRequest request) {
        return response(service.verify(parseIdempotencyKey(idempotencyKey), id,
                request.expectedVersion(), request.toCommand()));
    }

    private static CommandResponse response(
            com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowContracts.CommandResult result) {
        return new CommandResponse(result);
    }

    private static UUID parseIdempotencyKey(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
            return parsed;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Idempotency-Key must be a canonical UUID", exception);
        }
    }
}

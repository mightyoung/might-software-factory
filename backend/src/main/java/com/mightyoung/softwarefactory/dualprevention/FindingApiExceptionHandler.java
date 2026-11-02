package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.ErrorResponse;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FieldError;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.ValidationException;
import com.mightyoung.softwarefactory.identity.UnauthenticatedSessionException;
import com.mightyoung.softwarefactory.authorization.AuthorizationWriteGuardUnavailableException;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowConfigurationException;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowEvidenceException;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowIdempotencyConflictException;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowRuleException;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowVersionConflictException;
import com.mightyoung.softwarefactory.files.FileEvidenceTrustUnavailableException;
import com.mightyoung.softwarefactory.workflow.WorkflowEngineUnavailableException;
import com.mightyoung.softwarefactory.workflow.WorkflowResourceNotVisibleException;
import com.mightyoung.softwarefactory.workflow.WorkflowTaskStateConflictException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@RestControllerAdvice
public final class FindingApiExceptionHandler {

    @ExceptionHandler(UnauthenticatedSessionException.class)
    ResponseEntity<ErrorResponse> unauthenticated() {
        return error(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "A verified session is required", false, List.of());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ErrorResponse> forbidden() {
        return error(HttpStatus.FORBIDDEN, "ACTION_DENIED", "The finding is not available in this context",
                false, List.of());
    }

    @ExceptionHandler(FindingNotVisibleException.class)
    ResponseEntity<ErrorResponse> notVisible() {
        return error(HttpStatus.NOT_FOUND, "RESOURCE_NOT_VISIBLE", "The finding is not visible", false, List.of());
    }

    @ExceptionHandler(WorkflowResourceNotVisibleException.class)
    ResponseEntity<ErrorResponse> workflowNotVisible() {
        return error(HttpStatus.NOT_FOUND, "RESOURCE_NOT_VISIBLE", "The finding is not visible", false, List.of());
    }

    @ExceptionHandler(FindingWorkflowVersionConflictException.class)
    ResponseEntity<ErrorResponse> versionConflict() {
        return error(HttpStatus.CONFLICT, "VERSION_CONFLICT", "The finding version is stale", false, List.of());
    }

    @ExceptionHandler(FindingWorkflowIdempotencyConflictException.class)
    ResponseEntity<ErrorResponse> idempotencyConflict() {
        return error(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                "The idempotency key conflicts with a prior command", false, List.of());
    }

    @ExceptionHandler(WorkflowTaskStateConflictException.class)
    ResponseEntity<ErrorResponse> workflowTaskConflict() {
        return error(HttpStatus.CONFLICT, "COMMAND_CONFLICT",
                "The finding changed or this command key conflicts with a previous command", false, List.of());
    }

    @ExceptionHandler({FindingWorkflowRuleException.class, FindingWorkflowEvidenceException.class,
            FindingWorkflowConfigurationException.class})
    ResponseEntity<ErrorResponse> workflowRule() {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "WORKFLOW_RULE",
                "The finding workflow command is not valid in its current context", false, List.of());
    }

    @ExceptionHandler(ValidationException.class)
    ResponseEntity<ErrorResponse> invalidQuery(ValidationException exception) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_QUERY", "The finding query is invalid", false,
                List.of(new FieldError(exception.field(), exception.code())));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> invalidBody(MethodArgumentNotValidException exception) {
        List<FieldError> fieldErrors = exception.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> new FieldError(fieldError.getField(), validationCode(fieldError)))
                .sorted(Comparator.comparing(FieldError::field).thenComparing(FieldError::reason))
                .toList();
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The finding command request is invalid",
                false, fieldErrors);
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorResponse> invalidRequest() {
        return error(HttpStatus.BAD_REQUEST, "INVALID_QUERY", "The finding request is invalid", false, List.of());
    }

    @ExceptionHandler({DataAccessException.class, org.jooq.exception.DataAccessException.class,
            WorkflowEngineUnavailableException.class, FileEvidenceTrustUnavailableException.class,
            AuthorizationWriteGuardUnavailableException.class})
    ResponseEntity<ErrorResponse> unavailable() {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE",
                "The finding command could not be completed safely", true, List.of());
    }

    private static ResponseEntity<ErrorResponse> error(
            HttpStatus status, String code, String message, boolean retryable, List<FieldError> fields) {
        return ResponseEntity.status(status).body(new ErrorResponse(
                code, message, UUID.randomUUID().toString(), retryable, fields, null));
    }

    private static String validationCode(org.springframework.validation.FieldError fieldError) {
        String[] codes = fieldError.getCodes();
        if (codes == null || codes.length == 0) {
            return "Invalid";
        }
        return codes[codes.length - 1];
    }
}

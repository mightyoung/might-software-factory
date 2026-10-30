package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.ErrorResponse;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FieldError;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.ValidationException;
import com.mightyoung.softwarefactory.identity.UnauthenticatedSessionException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;

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

    @ExceptionHandler(ValidationException.class)
    ResponseEntity<ErrorResponse> invalidQuery(ValidationException exception) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_QUERY", "The finding query is invalid", false,
                List.of(new FieldError(exception.field(), exception.code())));
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorResponse> invalidRequest() {
        return error(HttpStatus.BAD_REQUEST, "INVALID_QUERY", "The finding request is invalid", false, List.of());
    }

    @ExceptionHandler({DataAccessException.class, org.jooq.exception.DataAccessException.class})
    ResponseEntity<ErrorResponse> unavailable() {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE",
                "The finding read could not be completed", true, List.of());
    }

    private static ResponseEntity<ErrorResponse> error(
            HttpStatus status, String code, String message, boolean retryable, List<FieldError> fields) {
        return ResponseEntity.status(status).body(new ErrorResponse(
                code, message, UUID.randomUUID().toString(), retryable, fields, null));
    }
}

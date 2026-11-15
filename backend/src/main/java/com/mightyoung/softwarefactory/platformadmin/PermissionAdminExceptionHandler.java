package com.mightyoung.softwarefactory.platformadmin;

import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.UUID;

@RestControllerAdvice(assignableTypes = PermissionAdminController.class)
public final class PermissionAdminExceptionHandler {
    @ExceptionHandler(PermissionAdminException.class)
    ResponseEntity<ErrorResponse> handle(PermissionAdminException failure) {
        return switch (failure.reason()) {
            case ROLE_NOT_FOUND -> error(HttpStatus.NOT_FOUND, "ROLE_NOT_FOUND", "The role was not found");
            case VERSION_CONFLICT -> error(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                    "The role permissions changed; reload and retry");
            case ROLE_HAS_NO_SCOPE -> error(HttpStatus.UNPROCESSABLE_ENTITY, "ROLE_HAS_NO_SCOPE",
                    "The role has no scope rule to inherit");
            case LAST_MANAGER -> error(HttpStatus.UNPROCESSABLE_ENTITY, "LAST_PERMISSION_MANAGER",
                    "At least one active role with members must keep permission management");
            case UNKNOWN_PERMISSION -> error(HttpStatus.UNPROCESSABLE_ENTITY, "UNKNOWN_PERMISSION",
                    "A permission code is not in the catalog");
        };
    }

    private static ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .body(new ErrorResponse(code, message, UUID.randomUUID().toString(), false, List.of(), null));
    }
}

package com.mightyoung.softwarefactory.platformadmin;

import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.ErrorResponse;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.FieldError;
import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract.ValidationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.List;
import java.util.UUID;

@RestControllerAdvice(assignableTypes = {LedgerConfigurationController.class, RuntimeLedgerCommandController.class, RuntimeLedgerReadController.class})
public final class LedgerConfigurationExceptionHandler {
    @ExceptionHandler(LedgerConfigurationException.class)
    ResponseEntity<ErrorResponse> conflict(LedgerConfigurationException failure) {
        return ResponseEntity.status(failure.reason() == LedgerConfigurationException.Reason.NOT_FOUND ? 404 : 409)
                .body(new ErrorResponse(failure.reason().name(), "Ledger configuration is unavailable or changed", UUID.randomUUID().toString(), false, List.of(), null));
    }
    @ExceptionHandler(ValidationException.class)
    ResponseEntity<ErrorResponse> invalid(ValidationException failure) {
        return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_CONFIGURATION", failure.getMessage(), UUID.randomUUID().toString(), false,
                List.of(new FieldError(failure.field(), failure.code())), null));
    }
}

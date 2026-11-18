package com.mightyoung.softwarefactory.tooling;

import com.mightyoung.softwarefactory.ledger.*;

import com.mightyoung.softwarefactory.applicationregistry.query.EntityQueryContract;
import com.mightyoung.softwarefactory.identity.SessionSensitiveException;
import com.mightyoung.softwarefactory.identity.UnauthenticatedSessionException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes=ToolLoanController.class)
public final class ToolLoanExceptionHandler {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(ToolLoanExceptionHandler.class);
    @ExceptionHandler(LedgerException.class) ResponseEntity<?> ledger(LedgerException failure){return failure.status()==503 ? unavailable(failure) : error(failure.status(),failure.code(),List.of());}
    @ExceptionHandler(LedgerInputException.class) ResponseEntity<?> input(LedgerInputException failure) {
        boolean conflict=failure.reason().equals("definition_conflict");
        return error(conflict?409:400,conflict?"DEFINITION_CONFLICT":"INVALID_REQUEST",List.of(new EntityQueryContract.FieldError(failure.field(),failure.reason())));
    }
    @ExceptionHandler(EntityQueryContract.ValidationException.class) ResponseEntity<?> query(EntityQueryContract.ValidationException failure){return error(400,"INVALID_QUERY",List.of(new EntityQueryContract.FieldError(failure.field(),failure.code())));}
    @ExceptionHandler(AccessDeniedException.class) ResponseEntity<?> denied(){return error(403,"FORBIDDEN",List.of());}
    @ExceptionHandler(UnauthenticatedSessionException.class) ResponseEntity<?> authentication(){return error(401,"UNAUTHENTICATED",List.of());}
    @ExceptionHandler(SessionSensitiveException.class) ResponseEntity<?> authority(SessionSensitiveException failure){return failure.reason()==SessionSensitiveException.Reason.UNAVAILABLE?unavailable(failure):error(401,"UNAUTHENTICATED",List.of());}
    @ExceptionHandler({org.springframework.web.bind.MissingServletRequestParameterException.class,org.springframework.web.bind.MissingRequestHeaderException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class}) ResponseEntity<?> transport(){return error(400,"INVALID_REQUEST",List.of());}
    @ExceptionHandler(org.jooq.exception.DataAccessException.class) ResponseEntity<?> persistence(org.jooq.exception.DataAccessException failure) {
        return "23505".equals(failure.sqlState()) ? error(409,"COMMAND_CONFLICT",List.of()) : unavailable(failure);
    }
    @ExceptionHandler(RuntimeException.class) ResponseEntity<?> unavailable(RuntimeException failure){
        String trace=UUID.randomUUID().toString();
        var sources=java.util.Arrays.stream(failure.getStackTrace()).filter(frame->frame.getClassName().startsWith("com.mightyoung.softwarefactory."))
                .limit(8).map(StackTraceElement::toString).toList();
        String cause=failure.getCause()==null?"none":failure.getCause().getClass().getName();
        String sqlState=failure instanceof org.jooq.exception.DataAccessException data?data.sqlState():"none";
        LOG.warn("Ledger unavailable trace={} type={} causeType={} sqlState={} source={}",trace,failure.getClass().getName(),cause,sqlState,sources);
        return response(503,"DEPENDENCY_UNAVAILABLE",List.of(),trace);
    }
    private static ResponseEntity<?> error(int status,String code,List<EntityQueryContract.FieldError> fields) {
        return response(status,code,fields,UUID.randomUUID().toString());
    }
    private static ResponseEntity<?> response(int status,String code,List<EntityQueryContract.FieldError> fields,String trace) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new EntityQueryContract.ErrorResponse(code,"请求未完成",trace,false,fields,null));
    }
}

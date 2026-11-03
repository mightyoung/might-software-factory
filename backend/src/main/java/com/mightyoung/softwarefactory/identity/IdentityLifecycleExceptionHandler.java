package com.mightyoung.softwarefactory.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.io.IOException;
import java.util.UUID;

@RestControllerAdvice(assignableTypes=IdentityLifecycleController.class)
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public final class IdentityLifecycleExceptionHandler {
    @ExceptionHandler({IdentityLifecycleException.class,UnauthenticatedSessionException.class,RuntimeException.class})
    public void handle(RuntimeException failure,HttpServletRequest request,HttpServletResponse response)throws IOException {
        IdentityCapabilityRoutePolicy.noStore(response);
        Object value=request.getAttribute(IdentityLifecycleController.TRACE_ATTRIBUTE);
        UUID trace=value instanceof UUID serverTrace?serverTrace:UUID.randomUUID();response.setHeader("X-Trace-Id",trace.toString());
        int status=503;String code="DEPENDENCY_UNAVAILABLE";
        if(failure instanceof UnauthenticatedSessionException){status=401;code="AUTH_REQUIRED";}
        else if(failure instanceof IdentityLifecycleException known) {
            switch(known.reason()) {
                case INVALID_REQUEST,CAPABILITY_REJECTED -> {status=400;code="INVALID_REQUEST";}
                case CONFLICT -> {status=409;code="VERSION_CONFLICT";}
                case RATE_LIMITED -> {status=429;code="RATE_LIMITED";response.setHeader("Retry-After","60");}
                case COMMIT_OUTCOME_UNKNOWN -> code="OUTCOME_UNKNOWN";
                case UNAVAILABLE -> { }
            }
        }
        IdentityHttpResponses.writeError(response,status,code,"Identity operation could not be completed",status==503||status==429,trace);
    }
}

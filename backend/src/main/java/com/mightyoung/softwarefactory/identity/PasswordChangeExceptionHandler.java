package com.mightyoung.softwarefactory.identity;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.io.IOException;

@RestControllerAdvice(assignableTypes=PasswordChangeController.class)
public final class PasswordChangeExceptionHandler {
    @ExceptionHandler(PasswordChangeException.class)
    public void handle(PasswordChangeException failure,HttpServletRequest request,HttpServletResponse response)throws IOException {
        Object attribute=request.getAttribute(PasswordChangeController.TRACE_ATTRIBUTE);
        UUID trace=attribute instanceof UUID serverTrace?serverTrace:UUID.randomUUID();
        response.setHeader("X-Trace-Id",trace.toString());
        if(failure.status()==429)response.setHeader("Retry-After",Long.toString(Math.max(1,failure.retryAfterSeconds())));
        String code=switch(failure.status()){case 400->"INVALID_QUERY";case 401->"AUTH_REQUIRED";case 403->"ACTION_DENIED";case 409->"VERSION_CONFLICT";case 429->"RATE_LIMITED";default->"DEPENDENCY_UNAVAILABLE";};
        IdentityHttpResponses.writeError(response,failure.status(),code,"Password change could not be completed",failure.status()==503||failure.status()==429,trace);
    }
}

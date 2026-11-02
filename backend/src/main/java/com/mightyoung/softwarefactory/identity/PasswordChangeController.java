package com.mightyoung.softwarefactory.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;

@RestController
public final class PasswordChangeController {
    static final String TRACE_ATTRIBUTE = PasswordChangeController.class.getName() + ".traceId";
    private final PasswordChangeRequestParser parser;
    private final PasswordChangeService service;
    public PasswordChangeController(PasswordChangeRequestParser parser,PasswordChangeService service){this.parser=parser;this.service=service;}
    @PostMapping("/api/v1/account/password")
    public void change(HttpServletRequest request,HttpServletResponse response) {
        UUID trace=UUID.randomUUID();
        request.setAttribute(TRACE_ATTRIBUTE,trace);
        response.setHeader("X-Trace-Id",trace.toString());
        service.change(()->parser.parse(request),trace);
        // Password/audit commit has completed. Cleanup cannot turn this into a false rollback response.
        SecurityContextHolder.clearContext();
        try { var session=request.getSession(false);if(session!=null)session.invalidate(); }
        catch(RuntimeException unavailable) { /* Current cookies/authentication are cleared; old generations fail globally. */ }
        IdentityHttpResponses.clearSessionCookies(response);
        IdentityHttpResponses.noContent(response);
    }
}

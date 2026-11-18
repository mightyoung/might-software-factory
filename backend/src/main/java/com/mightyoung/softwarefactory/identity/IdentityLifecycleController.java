package com.mightyoung.softwarefactory.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import static com.mightyoung.softwarefactory.identity.IdentityLifecycleContracts.*;

/** No public capability lookup, automatic login, recipient hint, or secret response. */
@RestController
public final class IdentityLifecycleController {
    static final String TRACE_ATTRIBUTE=IdentityLifecycleController.class.getName()+".traceId";
    private final IdentityLifecycleRequestParser parser;
    private final ObjectProvider<IdentityLifecycleService> services;
    public IdentityLifecycleController(IdentityLifecycleRequestParser parser,ObjectProvider<IdentityLifecycleService> services){this.parser=parser;this.services=services;}

    @PostMapping("/api/v1/account-invitations")
    public InvitationResponse invite(HttpServletRequest request,HttpServletResponse response) {
        UUID trace=begin(request,response);
        var result=service().invite(source(request),()->parser.invitation(request),trace);
        response.setStatus(result.replayed()?200:201);return InvitationResponse.of(result);
    }
    @PostMapping("/api/v1/account-invitations/{id}/revoke")
    public InvitationResponse revoke(@PathVariable("id") String id,HttpServletRequest request,HttpServletResponse response) {
        UUID trace=begin(request,response);
        var result=service().revoke(source(request),()->parser.mutation(request,id),trace);
        response.setStatus(200);return InvitationResponse.of(result);
    }
    @PostMapping("/api/v1/account-invitations/{id}/reissue")
    public InvitationResponse reissue(@PathVariable("id") String id,HttpServletRequest request,HttpServletResponse response) {
        UUID trace=begin(request,response);
        var result=service().reissue(source(request),()->parser.mutation(request,id),trace);
        response.setStatus(result.replayed()?200:201);return InvitationResponse.of(result);
    }
    @PostMapping("/api/v1/auth/password-reset-requests")
    public Map<String,String> resetRequest(HttpServletRequest request,HttpServletResponse response) {
        UUID trace=begin(request,response);service().requestReset(source(request),()->parser.resetRequest(request),trace);
        response.setStatus(202);return Map.of("status","ACCEPTED");
    }
    @PostMapping("/api/v1/auth/password-resets/consume")
    public void resetConsume(HttpServletRequest request,HttpServletResponse response) {
        UUID trace=begin(request,response);service().consumeReset(source(request),()->parser.resetConsume(request),trace);
        // The domain commit/replay completed. Cleanup failure cannot claim it rolled back.
        SecurityContextHolder.clearContext();
        try {var session=request.getSession(false);if(session!=null)session.invalidate();}
        catch(RuntimeException unavailable){ /* Future protected requests still enforce credential generation. */ }
        IdentityHttpResponses.clearSessionCookies(response);IdentityHttpResponses.noContent(response);
    }
    @PostMapping("/api/v1/auth/invitations/consume")
    public void invitationConsume(HttpServletRequest request,HttpServletResponse response) {
        UUID trace=begin(request,response);service().consumeInvitation(source(request),()->parser.invitationConsume(request),trace);
        IdentityHttpResponses.noContent(response);
    }
    private static UUID begin(HttpServletRequest request,HttpServletResponse response) {
        UUID trace=UUID.randomUUID();request.setAttribute(TRACE_ATTRIBUTE,trace);
        response.setHeader("X-Trace-Id",trace.toString());IdentityCapabilityRoutePolicy.noStore(response);return trace;
    }
    private static String source(HttpServletRequest request){String source=request.getRemoteAddr();return source==null?"unknown-transport":source;}
    private IdentityLifecycleService service(){var service=services.getIfAvailable();if(service==null)throw IdentityLifecycleException.unavailable();return service;}
    public record InvitationResponse(UUID invitationId,long version,Instant expiresAt,DeliveryState deliveryState) {
        static InvitationResponse of(InvitationResult value){return new InvitationResponse(value.invitationId(),value.version(),value.expiresAt(),value.deliveryState());}
    }
}

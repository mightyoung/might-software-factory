package com.mightyoung.softwarefactory.identity;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Server-issued correlation identifiers; never identity authority or values accepted from request headers. */
public final class IdentityRequestIds {
    private static final String ATTRIBUTE=IdentityRequestIds.class.getName()+".ids";
    private IdentityRequestIds() { }
    public record Ids(UUID requestId,UUID traceId) {
        public Ids { Objects.requireNonNull(requestId);Objects.requireNonNull(traceId); }
    }
    public static Ids get(HttpServletRequest request) {
        Objects.requireNonNull(request);
        synchronized(request) {
            Object current=request.getAttribute(ATTRIBUTE);
            if(current instanceof Ids ids)return ids;
            if(current!=null)throw new IllegalStateException("Request correlation state is invalid");
            var ids=new Ids(UUID.randomUUID(),UUID.randomUUID());request.setAttribute(ATTRIBUTE,ids);return ids;
        }
    }
    public static Optional<Ids> existing(HttpServletRequest request) {
        Objects.requireNonNull(request);
        synchronized(request) {
            Object current=request.getAttribute(ATTRIBUTE);
            if(current==null)return Optional.empty();
            if(current instanceof Ids ids)return Optional.of(ids);
            throw new IllegalStateException("Request correlation state is invalid");
        }
    }
}

package com.mightyoung.softwarefactory.identity;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Objects;

/** Created with the same configured absolute timeout as SessionLifetimePolicy during integration. */
public final class SessionSensitiveRequests {
    private final SessionContextResolver contexts;
    private final Duration absoluteTimeout;
    public SessionSensitiveRequests(SessionContextResolver contexts,Duration absoluteTimeout){
        this.contexts=Objects.requireNonNull(contexts);this.absoluteTimeout=Objects.requireNonNull(absoluteTimeout);
        if(absoluteTimeout.isZero()||absoluteTimeout.isNegative())throw new IllegalArgumentException("Positive timeout required");
    }
    public CapturedHumanRequest capture(HttpServletRequest request){return CapturedHumanRequest.capture(request,contexts,absoluteTimeout);}
}

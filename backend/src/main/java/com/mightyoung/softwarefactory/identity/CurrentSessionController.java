package com.mightyoung.softwarefactory.identity;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Same-origin endpoint for the active user's verified session identity. */
@RestController
public final class CurrentSessionController {

    private final CurrentSessionService service;

    public CurrentSessionController(CurrentSessionService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/session/current")
    public CurrentSessionResponse current() {
        return new CurrentSessionResponse(service.current());
    }

    public record CurrentSessionResponse(CurrentSessionDetails data) {
    }
}

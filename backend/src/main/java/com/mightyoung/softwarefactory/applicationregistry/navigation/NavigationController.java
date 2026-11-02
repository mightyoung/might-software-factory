package com.mightyoung.softwarefactory.applicationregistry.navigation;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class NavigationController {
    private final NavigationService service;
    public NavigationController(NavigationService service) { this.service = service; }

    @GetMapping("/api/v1/session/bootstrap")
    public Response<NavigationService.Bootstrap> bootstrap() { return new Response<>(service.bootstrap()); }

    @GetMapping("/api/v1/applications/{applicationId}/menu")
    public Response<NavigationService.Menu> menu(@PathVariable("applicationId") String applicationId) {
        return new Response<>(service.menu(applicationId));
    }

    public record Response<T>(T data) {}
}

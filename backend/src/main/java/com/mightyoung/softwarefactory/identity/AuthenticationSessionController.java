package com.mightyoung.softwarefactory.identity;

import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public CSRF bootstrap route for same-origin login and authenticated write requests. */
@RestController
public final class AuthenticationSessionController {

    @GetMapping("/api/v1/auth/csrf")
    public ResponseEntity<Void> csrf(CsrfToken csrfToken) {
        csrfToken.getToken();
        return ResponseEntity.noContent().build();
    }
}

package com.mightyoung.softwarefactory.identity;

import org.springframework.security.core.AuthenticationException;

final class InvalidLoginRequestException extends AuthenticationException {

    InvalidLoginRequestException() {
        super("The login request is invalid");
    }
}

package com.mightyoung.softwarefactory.identity;

import org.springframework.security.authentication.AbstractAuthenticationToken;

import java.util.Arrays;
import java.util.List;

final class LocalPasswordAuthenticationToken extends AbstractAuthenticationToken {

    private final String loginKey;
    private final String tenantCode;
    private char[] password;

    LocalPasswordAuthenticationToken(String loginKey, char[] password, String tenantCode) {
        super(List.of());
        this.loginKey = loginKey;
        this.password = password;
        this.tenantCode = tenantCode;
        super.setAuthenticated(false);
    }

    @Override
    public Object getCredentials() {
        return password == null ? null : password.clone();
    }

    @Override
    public Object getPrincipal() {
        return loginKey;
    }

    String loginKey() {
        return loginKey;
    }

    String tenantCode() {
        return tenantCode;
    }

    @Override
    public void setAuthenticated(boolean authenticated) {
        if (authenticated) {
            throw new IllegalArgumentException("Use an authenticated token factory");
        }
        super.setAuthenticated(false);
    }

    @Override
    public void eraseCredentials() {
        if (password != null) {
            Arrays.fill(password, '\0');
            password = null;
        }
        super.eraseCredentials();
    }

    @Override
    public String toString() {
        return "LocalPasswordAuthenticationToken";
    }
}

package com.mightyoung.softwarefactory.identity;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.CharBuffer;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.UUID;

/**
 * Authenticates a persisted local password and signs only server-resolved identity and membership
 * state into the session principal. All expected account/context failures share one response.
 */
@Component
public final class LocalPasswordAuthenticationProvider implements AuthenticationProvider {

    private static final String GENERIC_FAILURE = "Invalid credentials or account context";
    private static final UUID UNKNOWN_ACCOUNT_ID = new UUID(0L, 0L);
    private static final Pattern ACCEPTED_PASSWORD_HASH =
            Pattern.compile("\\{bcrypt\\}\\$2[aby]\\$1[2-6]\\$[./A-Za-z0-9]{53}");

    private final AccountPasswordCredentialReadRepository credentials;
    private final TenantLoginContextResolver tenantContextResolver;
    private final PasswordEncoder passwordEncoder;
    private final TransactionOperations authenticationReadTransaction;
    private final String dummyPasswordHash;

    @Autowired
    public LocalPasswordAuthenticationProvider(
            AccountPasswordCredentialReadRepository credentials,
            TenantLoginContextResolver tenantContextResolver,
            PasswordEncoder passwordEncoder,
            PlatformTransactionManager transactionManager) {
        this(credentials, tenantContextResolver, passwordEncoder, readTransaction(transactionManager));
    }

    LocalPasswordAuthenticationProvider(
            AccountPasswordCredentialReadRepository credentials,
            TenantLoginContextResolver tenantContextResolver,
            PasswordEncoder passwordEncoder,
            TransactionOperations authenticationReadTransaction) {
        this.credentials = credentials;
        this.tenantContextResolver = tenantContextResolver;
        this.passwordEncoder = passwordEncoder;
        this.authenticationReadTransaction = authenticationReadTransaction;
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    private static TransactionOperations readTransaction(PlatformTransactionManager transactionManager) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return transaction;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        if (!(authentication instanceof LocalPasswordAuthenticationToken request)) {
            return null;
        }
        char[] submittedPassword = (char[]) request.getCredentials();
        try {
            Authentication authenticated = authenticationReadTransaction.execute(status ->
                    authenticateWithinTransaction(request, submittedPassword));
            if (authenticated == null) {
                throw new AuthenticationServiceException("Authentication is temporarily unavailable");
            }
            return authenticated;
        } catch (AuthenticationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AuthenticationServiceException("Authentication is temporarily unavailable", exception);
        } finally {
            Arrays.fill(submittedPassword, '\0');
            request.eraseCredentials();
        }
    }

    private Authentication authenticateWithinTransaction(
            LocalPasswordAuthenticationToken request, char[] submittedPassword) {
        String loginKey = Normalizer.normalize(request.loginKey().strip(), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        Optional<LocalPasswordCredential> found = credentials.findByLoginKey(loginKey);
        String hash = found.map(LocalPasswordCredential::secretHash).orElse(dummyPasswordHash);
        boolean passwordMatches = matchesPassword(submittedPassword, hash);

        LocalPasswordCredential credential = found.orElse(null);
        UUID accountId = credential == null ? UNKNOWN_ACCOUNT_ID : credential.accountId();
        Optional<TenantLoginContext> resolved = tenantContextResolver.resolve(accountId, request.tenantCode());

        if (!passwordMatches || credential == null
                || !"ACTIVE".equals(credential.accountStatus())
                || !"ACTIVE".equals(credential.principalStatus())
                || resolved.isEmpty()) {
            throw new BadCredentialsException(GENERIC_FAILURE);
        }
        TenantLoginContext context = resolved.orElseThrow();
        if (!"ACTIVE".equals(context.tenantStatus()) || !"ACTIVE".equals(context.memberStatus())) {
            throw new BadCredentialsException(GENERIC_FAILURE);
        }

        VerifiedSessionPrincipal principal = new VerifiedSessionPrincipal(
                credential.actorId(), credential.accountId(), context.tenantId(), context.memberId(),
                context.tenantAuthEpoch(), context.memberAuthEpoch());
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of());
    }

    private boolean matchesPassword(char[] submittedPassword, String encodedPassword) {
        if (encodedPassword == null || !ACCEPTED_PASSWORD_HASH.matcher(encodedPassword).matches()) {
            passwordEncoder.matches(CharBuffer.wrap(submittedPassword), dummyPasswordHash);
            return false;
        }
        try {
            return passwordEncoder.matches(CharBuffer.wrap(submittedPassword), encodedPassword);
        } catch (IllegalArgumentException malformedStoredHash) {
            passwordEncoder.matches(CharBuffer.wrap(submittedPassword), dummyPasswordHash);
            return false;
        }
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return LocalPasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}

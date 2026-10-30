package com.mightyoung.softwarefactory.identity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
class LocalPasswordAuthenticationProviderTest {

    private static final UUID ACTOR_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ACCOUNT_ID = UUID.fromString("11000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID MEMBER_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");

    private final PasswordEncoder passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    private final FakeCredentials credentials = new FakeCredentials();
    private final FakeTenantResolver tenantResolver = new FakeTenantResolver();
    private final TransactionOperations transactions = new TransactionOperations() {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
            return action.doInTransaction(new SimpleTransactionStatus());
        }
    };
    private LocalPasswordAuthenticationProvider provider;

    @BeforeEach
    void setUp() {
        credentials.failure = null;
        credentials.value = Optional.empty();
        tenantResolver.failure = null;
        tenantResolver.value = Optional.of(activeContext());
        provider = new LocalPasswordAuthenticationProvider(credentials, tenantResolver, passwordEncoder, transactions);
    }

    @Test
    void onlyVerifiedPersistedIdentityAndMembershipBecomeASessionPrincipal() {
        credentials.value = Optional.of(credential("ACTIVE", "ACTIVE", passwordEncoder.encode("synthetic-pass")));

        Authentication result = provider.authenticate(login("synthetic-pass"));

        assertThat(result.isAuthenticated()).isTrue();
        assertThat(result.getPrincipal()).isInstanceOf(VerifiedSessionPrincipal.class);
        VerifiedSessionPrincipal principal = (VerifiedSessionPrincipal) result.getPrincipal();
        assertThat(principal.actorId()).isEqualTo(ACTOR_ID);
        assertThat(principal.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(principal.tenantId()).isEqualTo(TENANT_ID);
        assertThat(principal.memberId()).isEqualTo(MEMBER_ID);
        assertThat(principal.tenantAuthEpoch()).isEqualTo(17L);
        assertThat(principal.memberAuthEpoch()).isEqualTo(23L);
    }

    @Test
    void normalizesLoginKeyBeforeThePersistedLookup() {
        credentials.value = Optional.of(credential("ACTIVE", "ACTIVE", passwordEncoder.encode("synthetic-pass")));

        provider.authenticate(new LocalPasswordAuthenticationToken(
                "ＯＰＥＲＡＴＯＲ-1", "synthetic-pass".toCharArray(), "north-plant"));

        assertThat(credentials.lastLoginKey).isEqualTo("operator-1");
    }

    @Test
    void credentialAndRequestDiagnosticStringsNeverExposeSecretMaterial() {
        LocalPasswordCredential credential = credential("ACTIVE", "ACTIVE", "{bcrypt}secret-hash-value");
        LocalPasswordAuthenticationToken request = login("synthetic-pass");

        assertThat(credential.toString()).doesNotContain("secret-hash-value");
        assertThat(request.toString()).doesNotContain("operator-1", "synthetic-pass");
    }

    @Test
    void malformedStoredHashUsesTheSameCredentialFailureInsteadOfLeakingHashConfiguration() {
        credentials.value = Optional.of(credential("ACTIVE", "ACTIVE", "plaintext-secret"));

        BadCredentialsException failure = failureFor("synthetic-pass");

        assertThat(failure.getMessage()).isEqualTo("Invalid credentials or account context");
    }

    @Test
    void inactiveAccountPrincipalTenantOrMemberUseTheSameCredentialFailure() {
        String encoded = passwordEncoder.encode("synthetic-pass");
        credentials.value = Optional.of(credential("DISABLED", "ACTIVE", encoded));
        BadCredentialsException accountFailure = failureFor("synthetic-pass");

        credentials.value = Optional.of(credential("ACTIVE", "DISABLED", encoded));
        BadCredentialsException principalFailure = failureFor("synthetic-pass");

        credentials.value = Optional.of(credential("ACTIVE", "ACTIVE", encoded));
        tenantResolver.value = Optional.of(context("SUSPENDED", "ACTIVE"));
        BadCredentialsException tenantFailure = failureFor("synthetic-pass");

        tenantResolver.value = Optional.of(context("ACTIVE", "DISABLED"));
        BadCredentialsException memberFailure = failureFor("synthetic-pass");

        tenantResolver.value = Optional.empty();
        BadCredentialsException missingMemberFailure = failureFor("synthetic-pass");

        assertThat(principalFailure.getMessage()).isEqualTo(accountFailure.getMessage());
        assertThat(tenantFailure.getMessage()).isEqualTo(accountFailure.getMessage());
        assertThat(memberFailure.getMessage()).isEqualTo(accountFailure.getMessage());
        assertThat(missingMemberFailure.getMessage()).isEqualTo(accountFailure.getMessage());
    }

    @Test
    void unknownAccountAndWrongPasswordShareTheSameFailureAndClearSubmittedCredentials() {
        credentials.value = Optional.of(credential("ACTIVE", "ACTIVE", passwordEncoder.encode("different")));
        BadCredentialsException wrongPassword = failureFor("synthetic-pass");

        credentials.value = Optional.empty();
        BadCredentialsException unknownAccount = failureFor("synthetic-pass");

        assertThat(unknownAccount.getMessage()).isEqualTo(wrongPassword.getMessage());
        LocalPasswordAuthenticationToken token = login("synthetic-pass");
        assertThatThrownBy(() -> provider.authenticate(token)).isInstanceOf(BadCredentialsException.class);
        assertThat(token.getCredentials()).isNull();
    }

    @Test
    void storageFailureIsReportedAsDependencyFailureWithoutDetails() {
        credentials.failure = new IllegalStateException("private database detail");

        assertThatThrownBy(() -> provider.authenticate(login("synthetic-pass")))
                .isInstanceOf(AuthenticationServiceException.class)
                .hasMessage("Authentication is temporarily unavailable");
    }

    private BadCredentialsException failureFor(String password) {
        Throwable failure = catchThrowable(() -> provider.authenticate(login(password)));
        assertThat(failure).isInstanceOf(BadCredentialsException.class);
        return (BadCredentialsException) failure;
    }

    private static LocalPasswordAuthenticationToken login(String password) {
        return new LocalPasswordAuthenticationToken("operator-1", password.toCharArray(), "north-plant");
    }

    private static LocalPasswordCredential credential(String accountStatus, String principalStatus, String encoded) {
        return new LocalPasswordCredential(ACTOR_ID, ACCOUNT_ID, accountStatus, principalStatus, encoded);
    }

    private static TenantLoginContext activeContext() {
        return context("ACTIVE", "ACTIVE");
    }

    private static TenantLoginContext context(String tenantStatus, String memberStatus) {
        return new TenantLoginContext(TENANT_ID, MEMBER_ID, tenantStatus, memberStatus, 17L, 23L);
    }

    private static final class FakeCredentials implements AccountPasswordCredentialReadRepository {
        private Optional<LocalPasswordCredential> value = Optional.empty();
        private RuntimeException failure;
        private String lastLoginKey;

        @Override
        public Optional<LocalPasswordCredential> findByLoginKey(String normalizedLoginKey) {
            lastLoginKey = normalizedLoginKey;
            if (failure != null) {
                throw failure;
            }
            return value;
        }
    }

    private static final class FakeTenantResolver implements TenantLoginContextResolver {
        private Optional<TenantLoginContext> value = Optional.empty();
        private RuntimeException failure;

        @Override
        public Optional<TenantLoginContext> resolve(UUID accountId, String tenantCode) {
            if (failure != null) {
                throw failure;
            }
            return value;
        }
    }
}

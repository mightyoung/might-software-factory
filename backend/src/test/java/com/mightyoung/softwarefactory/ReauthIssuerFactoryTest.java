package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.auditops.JooqSessionSensitiveAudit;
import com.mightyoung.softwarefactory.authorization.ManagedSessionSensitiveSecurityTransaction;
import com.mightyoung.softwarefactory.authorization.PostgresAuthorizationWriteGuard;
import com.mightyoung.softwarefactory.authorization.SessionSensitiveJdbcTransactionManager;
import com.mightyoung.softwarefactory.identity.LoginAttemptLimiter;
import com.mightyoung.softwarefactory.identity.SessionContextResolver;
import com.mightyoung.softwarefactory.identity.SessionSensitiveKeys;
import com.mightyoung.softwarefactory.identity.SessionSensitiveRequests;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReauthIssuerFactoryTest {
    @Test void createsCanonicalSafeTransactionAndAuditTogetherWithoutBorrowing() {
        DataSource source=mock(DataSource.class);
        var owner=new AtomicReference<SessionSensitiveJdbcTransactionManager>();
        try(var guards=mockConstruction(PostgresAuthorizationWriteGuard.class,(mock,context)->
                assertThat(context.arguments().get(0)).isSameAs(source));
                var boundaries=mockConstruction(ManagedSessionSensitiveSecurityTransaction.class,(mock,context)->{
            assertThat(context.arguments().get(0)).isSameAs(source);
            assertThat(context.arguments().get(1)).isInstanceOf(SessionSensitiveJdbcTransactionManager.class);
            var manager=(SessionSensitiveJdbcTransactionManager)context.arguments().get(1);
            assertThat(manager.getDataSource()).isSameAs(source);owner.set(manager);
            assertThat(context.arguments().get(2)).isSameAs(guards.constructed().getFirst());
        });var audit=mockConstruction(JooqSessionSensitiveAudit.class,(mock,context)->{
            assertThat(context.arguments().get(0)).isSameAs(source);
            assertThat(context.arguments().get(1)).isSameAs(boundaries.constructed().getFirst());
            assertThat(context.arguments().get(2)).isSameAs(owner.get());
        })) {
            var issuer=ReauthIssuerFactory.create(new SessionSensitiveRequests(new SessionContextResolver(),Duration.ofHours(1)),source,
                    PasswordEncoderFactories.createDelegatingPasswordEncoder(),new SessionSensitiveKeys("k0",Map.of("k0",new byte[32])),
                    new LoginAttemptLimiter(LoginAttemptLimiter.Config.defaults(),System::nanoTime),
                    new LoginAttemptLimiter(LoginAttemptLimiter.Config.defaults(),System::nanoTime),Clock.systemUTC(),new SecureRandom());
            assertThat(issuer).isNotNull();
            assertThat(guards.constructed()).hasSize(1);assertThat(boundaries.constructed()).hasSize(1);assertThat(audit.constructed()).hasSize(1);
            verifyNoInteractions(source);
        }
    }
}

package com.mightyoung.softwarefactory.authorization;

import com.mightyoung.softwarefactory.identity.SessionSensitiveException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TenantAuditReadAuthorizationTest {
    @Test void gettersValidateEveryAccessAndTheScopeDoesNotPrintItsIdentity() {
        UUID tenant=UUID.randomUUID();Instant time=Instant.parse("2026-11-07T00:00:00Z");var checks=new AtomicInteger();
        var scope=new TenantAuditReadAuthorization.Scope(new Object(),tenant,time,checks::incrementAndGet,(source,connection)->{},()->{});
        assertThat(scope.tenantId()).isEqualTo(tenant);assertThat(scope.observedAt()).isEqualTo(time);assertThat(checks.get()).isEqualTo(2);
        assertThat(scope.toString()).isEqualTo("TenantAuditReadScope[REDACTED]").doesNotContain(tenant.toString());
    }
    @Test void aCaughtValidationFailureCannotBeRecoveredByRestoringItsOriginalCondition() {
        var fault=new AtomicBoolean(true);var invalidated=new AtomicInteger();
        var scope=new TenantAuditReadAuthorization.Scope(new Object(),UUID.randomUUID(),Instant.EPOCH,
                ()->{if(fault.get())throw SessionSensitiveException.unavailable();},(source,connection)->{},invalidated::incrementAndGet);
        assertThatThrownBy(scope::tenantId).isInstanceOf(SessionSensitiveException.class);fault.set(false);
        assertThatThrownBy(scope::observedAt).isInstanceOf(SessionSensitiveException.class);assertThat(invalidated.get()).isEqualTo(2);
    }
    @Test void connectionValidationAlsoPermanentlyInvalidatesTheOpaqueScope() {
        var scope=new TenantAuditReadAuthorization.Scope(new Object(),UUID.randomUUID(),Instant.EPOCH,()->{},
                (source,connection)->{throw SessionSensitiveException.unavailable();},()->{});
        assertThatThrownBy(()->scope.requireConnection(null,null)).isInstanceOf(SessionSensitiveException.class);
        assertThatThrownBy(scope::tenantId).isInstanceOf(SessionSensitiveException.class);
    }
}

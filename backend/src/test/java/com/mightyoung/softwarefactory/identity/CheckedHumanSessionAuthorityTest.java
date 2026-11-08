package com.mightyoung.softwarefactory.identity;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static com.mightyoung.softwarefactory.identity.SessionSensitiveContracts.Enforcement;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CheckedHumanSessionAuthorityTest {
    @Test void bindsTheCheckedTimeToExactScopeAndCallbackLifetime() {
        var request=mock(CapturedHumanRequest.class);
        var boundary=mock(HumanSessionAuthorityTransaction.ReadBoundary.class);
        var live=new AtomicBoolean(true); var validations=new AtomicInteger();
        Instant cut=Instant.parse("2026-11-04T00:00:00Z");
        var checked=new CheckedHumanSessionAuthority(request,boundary,Enforcement.OFF,cut,()->{
            validations.incrementAndGet(); if(!live.get()) throw SessionSensitiveException.unavailable();
        });
        assertThat(checked.observedAt(request,boundary)).isEqualTo(cut);
        assertThat(checked.enforcement()).isEqualTo(Enforcement.OFF);
        assertThatThrownBy(()->checked.observedAt(request,mock(HumanSessionAuthorityTransaction.ReadBoundary.class)))
                .isInstanceOf(SessionSensitiveException.class);
        assertThatThrownBy(()->checked.observedAt(mock(CapturedHumanRequest.class),boundary)).isInstanceOf(SessionSensitiveException.class);
        live.set(false);
        assertThatThrownBy(()->checked.observedAt(request,boundary)).isInstanceOf(SessionSensitiveException.class);
        assertThatThrownBy(checked::enforcement).isInstanceOf(SessionSensitiveException.class);
        assertThat(validations.get()).isEqualTo(4);
        verifyNoInteractions(boundary);
    }
    @Test void resultDoesNotExposePublicArbitraryInstantConstruction() {
        assertThat(CheckedHumanSessionAuthority.class.getConstructors()).isEmpty();
        assertThat(java.lang.reflect.Modifier.isFinal(CheckedHumanSessionAuthority.class.getModifiers())).isTrue();
    }
}

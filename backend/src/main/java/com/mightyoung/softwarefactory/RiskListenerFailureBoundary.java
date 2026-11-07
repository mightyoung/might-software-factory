package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioDraftChangedEvent;
import com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioDraftChangedListener;
import java.lang.reflect.Method;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.support.StaticMethodMatcherPointcutAdvisor;
import org.springframework.core.Ordered;

/**
 * Explicit composition only: Async -> this boundary -> native Completion -> listener.
 * Register this as an infrastructure advisor when using Spring transaction auto-proxying.
 * It sanitizes escaping failures, not logs already emitted inside a registry transaction.
 */
public final class RiskListenerFailureBoundary extends StaticMethodMatcherPointcutAdvisor {
    private static final String SAFE = "Risk asynchronous listener is unavailable";

    public RiskListenerFailureBoundary() {
        super((MethodInterceptor) invocation -> {
            try { return invocation.proceed(); }
            catch (Throwable failure) { throw new IllegalStateException(SAFE); }
        });
        setOrder(Ordered.HIGHEST_PRECEDENCE + 5);
    }

    @Override public boolean matches(Method method, Class<?> targetClass) {
        return targetClass == RiskScenarioDraftChangedListener.class
                && method.getName().equals("capture") && method.getReturnType() == void.class
                && java.util.Arrays.equals(method.getParameterTypes(), new Class<?>[]{RiskScenarioDraftChangedEvent.class});
    }
}

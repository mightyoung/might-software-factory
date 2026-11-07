package com.mightyoung.softwarefactory;

import java.lang.reflect.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;

/** Fixed diagnostics for the explicitly composed risk executor; no automatic registration. */
public final class RiskAsyncUncaughtExceptionHandler implements AsyncUncaughtExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(RiskAsyncUncaughtExceptionHandler.class);

    @Override public void handleUncaughtException(Throwable failure, Method method, Object... parameters) {
        // Never inspect or pass an exception, reflective method or event argument to the logger.
        try { LOG.warn("Risk asynchronous listener failed; inspect durable publication state"); }
        catch (RuntimeException | Error loggingFailure) { /* A diagnostic failure cannot escape the executor. */ }
    }
}

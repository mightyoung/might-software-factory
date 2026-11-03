package com.mightyoung.softwarefactory.identity;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Lifecycle-only bounded uncertainty observer; it never dispatches or reconstructs a token. */
final class IdentityCapabilityDeadlineObserver implements AutoCloseable {
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(work->{
        Thread thread=new Thread(work,"identity-capability-deadline");thread.setDaemon(true);return thread;
    });
    IdentityCapabilityDeadlineObserver(IdentityLifecycleService service) {
        timer.scheduleWithFixedDelay(()->{
            try{service.observeExpiredDeliveries();}catch(RuntimeException unavailable){/* The service records a bounded safe diagnostic; retain uncertainty. */}
        },5,30,TimeUnit.SECONDS);
    }
    @Override public void close(){timer.shutdownNow();}
}

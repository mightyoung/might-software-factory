package com.mightyoung.softwarefactory.support;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.context.support.AbstractApplicationContext;

/** Graph-owned bounded asynchronous dispatch. Never executes a rejected task on its caller. */
public final class RiskScenarioExecutor implements Executor, AutoCloseable {
    public static final String BEAN_NAME = "riskScenarioExecutor";
    private final AbstractApplicationContext owner;
    private final ThreadPoolExecutor executor;
    private final ThreadLocal<Long> accepted = ThreadLocal.withInitial(() -> 0L);

    RiskScenarioExecutor(AbstractApplicationContext owner) {
        this.owner = Objects.requireNonNull(owner);
        executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(256), task -> {
            Thread thread = new Thread(task, "risk-scenario-consumer");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    void requireOwner(AbstractApplicationContext context) {
        if (owner != context || executor.isShutdown()
                || owner.getBeanFactory().getSingleton(BEAN_NAME) != this) throw unavailable();
    }

    @Override public void execute(Runnable command) {
        requireOwner(owner);
        if (!owner.isActive() || command == null) throw unavailable();
        long previous = accepted.get();
        if (previous == Long.MAX_VALUE) throw unavailable();
        try { executor.execute(command); }
        catch (RejectedExecutionException rejected) { throw unavailable(); }
        accepted.set(previous + 1);
    }

    long acceptedOnCurrentThread() { requireOwner(owner); return accepted.get(); }

    public int pendingTaskCount() { return executor.getQueue().size(); }

    /** Waits for a snapshot barrier; concurrent later submissions are not included. */
    public boolean awaitQuiescence(Duration timeout) throws InterruptedException {
        if (timeout == null || timeout.isNegative() || timeout.isZero()
                || timeout.compareTo(Duration.ofMinutes(1)) > 0) throw unavailable();
        var completed = new java.util.concurrent.CountDownLatch(1);
        execute(completed::countDown);
        return completed.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    @Override public void close() { executor.shutdownNow(); }

    private static RejectedExecutionException unavailable() {
        return new RejectedExecutionException("Risk scenario executor unavailable");
    }
}

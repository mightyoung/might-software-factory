package com.mightyoung.softwarefactory.support;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import static org.assertj.core.api.Assertions.*;

class RiskScenarioExecutorTest {
    @Test void runsOnOwnedWorkerAndBarrierObservesEarlierCompletion() throws Exception {
        try (var context = new GenericApplicationContext(); var executor = owned(context)) {
            var worker = new AtomicReference<Thread>();
            var workerAccepts = new java.util.concurrent.atomic.AtomicLong(-1);
            assertThat(executor.acceptedOnCurrentThread()).isZero();
            executor.execute(() -> { worker.set(Thread.currentThread()); workerAccepts.set(executor.acceptedOnCurrentThread()); });
            assertThat(executor.acceptedOnCurrentThread()).isEqualTo(1);
            assertThat(executor.awaitQuiescence(Duration.ofSeconds(5))).isTrue();
            assertThat(worker.get()).isNotNull().isNotSameAs(Thread.currentThread());
            assertThat(workerAccepts).hasValue(0);
        }
    }

    @Test void saturationRejectsWithoutCallerRunsAndCloseDiscardsPendingTasks() throws Exception {
        try (var context = new GenericApplicationContext(); var executor = owned(context)) {
            var started = new CountDownLatch(1);
            var stopped = new CountDownLatch(1);
            var hold = new CountDownLatch(1);
            var ran = new AtomicBoolean();
            executor.execute(() -> {
                started.countDown();
                try { hold.await(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                finally { stopped.countDown(); }
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 256; i++) executor.execute(() -> ran.set(true));
            assertThat(executor.pendingTaskCount()).isEqualTo(256);
            long accepted = executor.acceptedOnCurrentThread();
            Runnable rejected = new Runnable() {
                @Override public void run() { ran.set(true); }
                @Override public String toString() { return "private-task-material"; }
            };
            assertThatThrownBy(() -> executor.execute(rejected)).isInstanceOf(RejectedExecutionException.class)
                    .hasMessage("Risk scenario executor unavailable").hasNoCause();
            assertThat(executor.acceptedOnCurrentThread()).isEqualTo(accepted);
            assertThat(ran).isFalse();
            executor.close();
            assertThat(stopped.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(executor.pendingTaskCount()).isZero();
            assertThat(ran).isFalse();
            assertThatThrownBy(() -> executor.execute(() -> ran.set(true))).isInstanceOf(RejectedExecutionException.class);
        }
    }

    @Test void crossGraphOrReplacedNamedBeanIsRejectedBeforeDispatch() {
        try (var context = new GenericApplicationContext(); var other = new GenericApplicationContext();
                var executor = owned(context)) {
            assertThatThrownBy(() -> executor.requireOwner(other)).isInstanceOf(RejectedExecutionException.class);
            context.getDefaultListableBeanFactory().destroySingleton(RiskScenarioExecutor.BEAN_NAME);
            context.getDefaultListableBeanFactory().registerSingleton(RiskScenarioExecutor.BEAN_NAME,
                    (java.util.concurrent.Executor) Runnable::run);
            var ran = new AtomicBoolean();
            assertThatThrownBy(() -> executor.execute(() -> ran.set(true))).isInstanceOf(RejectedExecutionException.class);
            assertThat(ran).isFalse();
        }
    }

    @Test void unregisteredAndClosedContextCannotDispatch() {
        try (var context = new GenericApplicationContext(); var executor = new RiskScenarioExecutor(context)) {
            assertThatThrownBy(() -> executor.execute(() -> {})).isInstanceOf(RejectedExecutionException.class);
            context.getBeanFactory().registerSingleton(RiskScenarioExecutor.BEAN_NAME, executor);
            context.refresh();
            context.close();
            assertThatThrownBy(() -> executor.execute(() -> {})).isInstanceOf(RejectedExecutionException.class);
        }
    }

    private static RiskScenarioExecutor owned(GenericApplicationContext context) {
        var executor = new RiskScenarioExecutor(context);
        context.getBeanFactory().registerSingleton(RiskScenarioExecutor.BEAN_NAME, executor);
        context.refresh();
        return executor;
    }
}

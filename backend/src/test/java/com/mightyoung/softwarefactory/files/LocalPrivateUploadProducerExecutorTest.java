package com.mightyoung.softwarefactory.files;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static com.mightyoung.softwarefactory.files.FileIngestionContracts.Deadline;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Deterministic executor handoff tests; no scanner, filesystem readiness or upload proof is simulated. */
class LocalPrivateUploadProducerExecutorTest {
    @Test
    void sequentialWorkIsAcceptedAfterSuccessBeforeWorkerReentersQueue() throws Exception {
        try (var harness = new HandoffHarness()) {
            assertThat(harness.external(() -> "first")).isEqualTo("first");
            harness.requireFirstWorkerPaused();
            harness.requireNextSubmissionAccepted();
        }
    }

    @Test
    void sequentialWorkIsAcceptedAfterExceptionalCompletionBeforeWorkerReentersQueue() throws Exception {
        try (var harness = new HandoffHarness()) {
            assertThatThrownBy(() -> harness.external(() -> {
                throw new IOException("synthetic I/O failure");
            })).isInstanceOf(FileEvidenceTrustUnavailableException.class);
            harness.requireFirstWorkerPaused();
            harness.requireNextSubmissionAccepted();
        }
    }

    private static final class HandoffHarness implements AutoCloseable {
        private final LocalPrivateUploadProducer producer;
        private final PausedHandoffExecutor io;
        private final ExecutorService caller = Executors.newSingleThreadExecutor(
                Thread.ofPlatform().daemon().name("private-io-test-caller-", 0).factory());
        private final Method external;

        HandoffHarness() throws Exception {
            producer = new LocalPrivateUploadProducer(
                    ClamScanProcessAdapterTest.properties(), IngestionProofCodec.unavailable());
            Field field = LocalPrivateUploadProducer.class.getDeclaredField("io");
            field.setAccessible(true);
            var original = (ThreadPoolExecutor) field.get(producer);
            assertThat(original.getCorePoolSize()).isEqualTo(1);
            assertThat(original.getTaskCount()).isZero();
            assertThat(original.getPoolSize()).isZero();
            assertThat(original.getQueue()).isEmpty();
            // Keep the actual production queue and executor policy; only add an afterExecute barrier.
            // The harness therefore observes a production queue change without prescribing its fix.
            io = new PausedHandoffExecutor(original);
            original.shutdownNow();
            field.set(producer, io);
            external = LocalPrivateUploadProducer.class.getDeclaredMethod(
                    "external", Callable.class, Deadline.class, InputStream.class);
            external.setAccessible(true);
        }

        String external(Callable<String> work) throws Exception {
            try {
                return (String) external.invoke(producer, work, Deadline.after(Duration.ofSeconds(10)), null);
            } catch (InvocationTargetException invocation) {
                if (invocation.getCause() instanceof Exception exception) throw exception;
                if (invocation.getCause() instanceof Error error) throw error;
                throw new AssertionError(invocation.getCause());
            }
        }

        void requireFirstWorkerPaused() throws InterruptedException {
            assertThat(io.firstFinished.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(io.getActiveCount()).isEqualTo(1);
            assertThat(io.resumeFirst.getCount()).isEqualTo(1);
            assertThat(io.getQueue()).isEmpty();
        }

        void requireNextSubmissionAccepted() throws Exception {
            var executions = new AtomicInteger();
            Future<String> second = caller.submit(() -> external(() -> {
                executions.incrementAndGet();
                return "second";
            }));
            try {
                assertThat(io.secondAttempted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(io.secondAccepted.get())
                        .as("completed work must permit the next serial submission before its worker dequeues again")
                        .isTrue();
                assertThat(executions).hasValue(0);
                assertThat(io.resumeFirst.getCount()).isEqualTo(1);
                io.resumeFirst.countDown();
                assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("second");
                assertThat(executions).hasValue(1);
            } finally {
                io.resumeFirst.countDown();
                second.cancel(true);
            }
        }

        @Override
        public void close() throws InterruptedException {
            io.resumeFirst.countDown();
            producer.close();
            caller.shutdownNow();
            assertThat(caller.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            assertThat(io.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static final class PausedHandoffExecutor extends ThreadPoolExecutor {
        private final AtomicInteger submissions = new AtomicInteger();
        private final AtomicInteger completions = new AtomicInteger();
        private final CountDownLatch firstFinished = new CountDownLatch(1);
        private final CountDownLatch resumeFirst = new CountDownLatch(1);
        private final CountDownLatch secondAttempted = new CountDownLatch(1);
        private final AtomicBoolean secondAccepted = new AtomicBoolean();

        PausedHandoffExecutor(ThreadPoolExecutor actual) {
            super(actual.getCorePoolSize(), actual.getMaximumPoolSize(),
                    actual.getKeepAliveTime(TimeUnit.NANOSECONDS), TimeUnit.NANOSECONDS,
                    actual.getQueue(), actual.getThreadFactory(), actual.getRejectedExecutionHandler());
        }

        @Override
        public void execute(Runnable command) {
            int number = submissions.incrementAndGet();
            try {
                super.execute(command);
                if (number == 2) secondAccepted.set(true);
            } finally {
                if (number == 2) secondAttempted.countDown();
            }
        }

        @Override
        protected void afterExecute(Runnable task, Throwable failure) {
            super.afterExecute(task, failure);
            if (completions.incrementAndGet() != 1) return;
            firstFinished.countDown();
            try {
                if (!resumeFirst.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("test did not release the first worker");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}

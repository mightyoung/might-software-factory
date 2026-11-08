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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static com.mightyoung.softwarefactory.files.FileIngestionContracts.Deadline;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Deterministic executor handoff tests; no scanner, filesystem readiness or upload proof is simulated. */
class LocalPrivateUploadProducerExecutorTest {
    @Test
    void actualDeadlineExpiryRetainsCapacityUntilInterruptIgnoringWorkExits() throws Exception {
        try (var harness = new HandoffHarness()) {
            var entered = new CountDownLatch(1);
            var interrupted = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var enteredAt = new AtomicLong();
            var deadline = new AtomicReference<Deadline>();
            Future<String> first = harness.caller.submit(() -> {
                Deadline current = Deadline.after(Duration.ofSeconds(5));
                deadline.set(current);
                return harness.external(() -> {
                    enteredAt.set(System.nanoTime());
                    entered.countDown();
                    awaitIgnoringInterruption(release, interrupted, 15);
                    return "late untrusted work";
                }, current);
            });
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(enteredAt.get()).isLessThan(deadline.get().deadlineNanos());
                // No caller interruption: external's actual deadline drives cancellation.
                requireUnavailable(first, 10);
                assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(harness.availableSlots()).isZero();
                var forbidden = new AtomicInteger();
                assertThatThrownBy(() -> harness.external(() -> {
                    forbidden.incrementAndGet();
                    return "must not run";
                })).isInstanceOf(FileEvidenceTrustUnavailableException.class);
                assertThat(forbidden).hasValue(0);
                release.countDown();
                harness.requireFirstWorkerPaused();
                harness.requireNextSubmissionAccepted();
            } finally {
                release.countDown();
                first.cancel(true);
            }
        }
    }

    @Test
    void expiredDequeuedWorkRefusesEntryBeforeCallerCanObserveOrCancelItsFuture() throws Exception {
        try (var harness = new HandoffHarness()) {
            assertThat(harness.external(() -> "first")).isEqualTo("first");
            harness.requireFirstWorkerPaused();
            harness.io.pauseSecondBeforeRun.set(true);
            harness.io.pauseSecondExecuteReturn.set(true);
            var deadline = new AtomicReference<Deadline>();
            var executions = new AtomicInteger();
            Future<String> second = harness.caller.submit(() -> {
                Deadline current = Deadline.after(Duration.ofSeconds(5));
                deadline.set(current);
                return harness.external(() -> {
                    executions.incrementAndGet();
                    return "must not run after expiry";
                }, current);
            });
            try {
                assertThat(harness.io.secondAdmitted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(harness.io.secondAccepted.get()).isTrue();
                assertThat(harness.io.secondAttempted.getCount()).isEqualTo(1);
                harness.io.resumeFirst.countDown();
                assertThat(harness.io.secondDequeued.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(harness.io.getQueue()).isEmpty();
                awaitDeadlineExpiry(deadline.get());
                assertThat(harness.io.secondAttempted.getCount()).isEqualTo(1);
                assertThat(second.isDone()).isFalse();
                // execute() still holds the caller: it has not reached future.get() or cancellation.
                harness.io.resumeSecond.countDown();
                assertThat(harness.io.secondFinished.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(executions).hasValue(0);
                assertThat(harness.availableSlots()).isEqualTo(1);
                assertThat(harness.io.secondAttempted.getCount()).isEqualTo(1);
                assertThat(second.isDone()).isFalse();
                harness.io.resumeSecondExecuteReturn.countDown();
                requireUnavailable(second, 10);
                assertThat(harness.external(() -> "after expiry")).isEqualTo("after expiry");
                assertThat(executions).hasValue(0);
                assertThat(harness.availableSlots()).isEqualTo(1);
            } finally {
                harness.io.resumeSecond.countDown();
                harness.io.resumeSecondExecuteReturn.countDown();
                second.cancel(true);
            }
        }
    }

    private static void awaitDeadlineExpiry(Deadline deadline) throws InterruptedException {
        long remaining = deadline.deadlineNanos() - System.nanoTime();
        if (remaining > 0) {
            assertThat(new CountDownLatch(1).await(remaining, TimeUnit.NANOSECONDS)).isFalse();
        }
        assertThatThrownBy(deadline::remainingNanos).isInstanceOf(FileEvidenceTrustUnavailableException.class);
    }

    @Test
    void closeAfterDequeuePreventsWorkFromStartingOutsideTheShutdownDrain() throws Exception {
        try (var harness = new HandoffHarness()) {
            assertThat(harness.external(() -> "first")).isEqualTo("first");
            harness.requireFirstWorkerPaused();
            harness.io.pauseSecondBeforeRun.set(true);
            var executions = new AtomicInteger();
            Future<String> dequeued = harness.caller.submit(() -> harness.external(() -> {
                executions.incrementAndGet();
                return "must not start after close";
            }));
            try {
                assertThat(harness.io.secondAttempted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(harness.io.secondAccepted.get()).isTrue();
                harness.io.resumeFirst.countDown();
                assertThat(harness.io.secondDequeued.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(harness.io.getQueue()).isEmpty();
                assertThat(harness.io.resumeSecond.getCount()).isEqualTo(1);
                // shutdownNow cannot drain this task: the worker has already removed it from the queue.
                harness.producer.close();
                requireUnavailable(dequeued);
                assertThat(harness.io.secondFinished.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(executions).hasValue(0);
                assertThat(harness.availableSlots()).isEqualTo(1);
            } finally {
                harness.io.resumeSecond.countDown();
                dequeued.cancel(true);
            }
        }
    }

    @Test
    void twoUnfinishedTasksExhaustConfiguredCapacityWithoutAdmittingAThird() throws Exception {
        try (var harness = new HandoffHarness(2)) {
            var entered = new CountDownLatch(2);
            var release = new CountDownLatch(1);
            var active = new AtomicInteger();
            var maximum = new AtomicInteger();
            Callable<String> work = () -> {
                maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                entered.countDown();
                try {
                    assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                    return "completed";
                } finally {
                    active.decrementAndGet();
                }
            };
            Future<String> first = harness.caller.submit(() -> harness.external(work));
            Future<String> second = harness.caller.submit(() -> harness.external(work));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var rejectedWork = new AtomicInteger();
                assertThatThrownBy(() -> harness.external(() -> {
                    rejectedWork.incrementAndGet();
                    return "not admitted";
                })).isInstanceOf(FileEvidenceTrustUnavailableException.class);
                assertThat(rejectedWork).hasValue(0);
                assertThat(maximum).hasValue(2);
                release.countDown();
                assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("completed");
                assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("completed");
                assertThat(active).hasValue(0);
                assertThat(harness.availableSlots()).isEqualTo(2);
            } finally {
                release.countDown();
                first.cancel(true);
                second.cancel(true);
            }
        }
    }

    @Test
    void interruptedCallerDoesNotReleaseCapacityUntilIgnoringWorkActuallyExits() throws Exception {
        try (var harness = new HandoffHarness()) {
            var entered = new CountDownLatch(1);
            var interrupted = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var callerThread = new AtomicReference<Thread>();
            Future<String> first = harness.caller.submit(() -> {
                callerThread.set(Thread.currentThread());
                return harness.external(() -> {
                    entered.countDown();
                    awaitIgnoringInterruption(release, interrupted);
                    return "untrusted late result";
                });
            });
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                callerThread.get().interrupt();
                requireUnavailable(first);
                assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
                var forbidden = new AtomicInteger();
                assertThatThrownBy(() -> harness.external(() -> {
                    forbidden.incrementAndGet();
                    return "must not run";
                })).isInstanceOf(FileEvidenceTrustUnavailableException.class);
                assertThat(forbidden).hasValue(0);
                assertThat(harness.availableSlots()).isZero();
                release.countDown();
                harness.requireFirstWorkerPaused();
                harness.requireNextSubmissionAccepted();
            } finally {
                release.countDown();
                first.cancel(true);
            }
        }
    }

    @Test
    void cancelledQueuedWorkIsRemovedAndItsSlotCanBeReusedBeforeWorkerHandoff() throws Exception {
        try (var harness = new HandoffHarness()) {
            assertThat(harness.external(() -> "first")).isEqualTo("first");
            harness.requireFirstWorkerPaused();
            var callerThread = new AtomicReference<Thread>();
            var executions = new AtomicInteger();
            Future<String> queued = harness.caller.submit(() -> {
                callerThread.set(Thread.currentThread());
                return harness.external(() -> {
                    executions.incrementAndGet();
                    return "cancelled";
                });
            });
            try {
                assertThat(harness.io.secondAttempted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(harness.io.secondAccepted.get()).isTrue();
                assertThat(harness.io.getQueue()).hasSize(1);
                callerThread.get().interrupt();
                requireUnavailable(queued);
                assertThat(harness.io.getQueue()).isEmpty();
                assertThat(harness.availableSlots()).isEqualTo(1);
                Future<String> replacement = harness.caller.submit(() -> harness.external(() -> "replacement"));
                try {
                    assertThat(harness.io.thirdAttempted.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThat(harness.io.thirdAccepted.get()).isTrue();
                    assertThat(executions).hasValue(0);
                    assertThat(harness.io.resumeFirst.getCount()).isEqualTo(1);
                    harness.io.resumeFirst.countDown();
                    assertThat(replacement.get(5, TimeUnit.SECONDS)).isEqualTo("replacement");
                    assertThat(executions).hasValue(0);
                    assertThat(harness.availableSlots()).isEqualTo(1);
                } finally {
                    harness.io.resumeFirst.countDown();
                    replacement.cancel(true);
                }
            } finally {
                queued.cancel(true);
            }
        }
    }

    @Test
    void cancellationAfterDequeueRetainsAdmissionUntilTheCancelledTaskLeavesItsWorker() throws Exception {
        try (var harness = new HandoffHarness()) {
            assertThat(harness.external(() -> "first")).isEqualTo("first");
            harness.requireFirstWorkerPaused();
            harness.io.pauseSecondBeforeRun.set(true);
            var callerThread = new AtomicReference<Thread>();
            var executions = new AtomicInteger();
            Future<String> dequeued = harness.caller.submit(() -> {
                callerThread.set(Thread.currentThread());
                return harness.external(() -> {
                    executions.incrementAndGet();
                    return "cancelled before work";
                });
            });
            try {
                assertThat(harness.io.secondAttempted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(harness.io.secondAccepted.get()).isTrue();
                harness.io.resumeFirst.countDown();
                assertThat(harness.io.secondDequeued.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(harness.io.getQueue()).isEmpty();
                callerThread.get().interrupt();
                requireUnavailable(dequeued);
                assertThatThrownBy(() -> harness.external(() -> "not admitted"))
                        .isInstanceOf(FileEvidenceTrustUnavailableException.class);
                assertThat(harness.availableSlots()).isZero();
                assertThat(executions).hasValue(0);
                harness.io.resumeSecond.countDown();
                assertThat(harness.io.secondFinished.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(harness.external(() -> "after cancellation")).isEqualTo("after cancellation");
                assertThat(executions).hasValue(0);
                assertThat(harness.availableSlots()).isEqualTo(1);
            } finally {
                harness.io.resumeSecond.countDown();
                dequeued.cancel(true);
            }
        }
    }

    @Test
    void knownSubmissionRejectionDoesNotLeakTheAvailableSlot() throws Exception {
        try (var harness = new HandoffHarness()) {
            harness.io.rejectNext.set(true);
            var executions = new AtomicInteger();
            assertThatThrownBy(() -> harness.external(() -> {
                executions.incrementAndGet();
                return "rejected";
            })).isInstanceOf(FileEvidenceTrustUnavailableException.class);
            assertThat(executions).hasValue(0);
            assertThat(harness.availableSlots()).isEqualTo(1);
            assertThat(harness.external(() -> "recovered")).isEqualTo("recovered");
            assertThat(harness.availableSlots()).isEqualTo(1);
        }
    }

    @Test
    void closeCancelsDrainedQueuedWorkAndReleasesOnlyItsUnusedAdmission() throws Exception {
        try (var harness = new HandoffHarness()) {
            assertThat(harness.external(() -> "first")).isEqualTo("first");
            harness.requireFirstWorkerPaused();
            var executions = new AtomicInteger();
            Future<String> queued = harness.caller.submit(() -> harness.external(() -> {
                executions.incrementAndGet();
                return "must not run after close";
            }));
            try {
                assertThat(harness.io.secondAttempted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(harness.io.secondAccepted.get()).isTrue();
                harness.producer.close();
                requireUnavailable(queued);
                assertThat(harness.io.getQueue()).isEmpty();
                assertThat(executions).hasValue(0);
                assertThat(harness.availableSlots()).isEqualTo(1);
                assertThatThrownBy(() -> harness.external(() -> "closed"))
                        .isInstanceOf(FileEvidenceTrustUnavailableException.class);
                assertThat(harness.availableSlots()).isEqualTo(1);
            } finally {
                queued.cancel(true);
            }
        }
    }

    private static void requireUnavailable(Future<?> result) {
        requireUnavailable(result, 5);
    }

    private static void requireUnavailable(Future<?> result, long waitSeconds) {
        assertThatThrownBy(() -> result.get(waitSeconds, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(FileEvidenceTrustUnavailableException.class);
    }

    private static void awaitIgnoringInterruption(CountDownLatch release, CountDownLatch observed)
            throws InterruptedException {
        awaitIgnoringInterruption(release, observed, 5);
    }

    private static void awaitIgnoringInterruption(CountDownLatch release, CountDownLatch observed, long waitSeconds)
            throws InterruptedException {
        for (;;) {
            try {
                assertThat(release.await(waitSeconds, TimeUnit.SECONDS)).isTrue();
                return;
            } catch (InterruptedException deliberatelyIgnored) {
                // Model unknown I/O that ignores cancellation; the work is not retried.
                observed.countDown();
            }
        }
    }

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

    private static FileIngestionProperties properties(int concurrency) {
        var base = ClamScanProcessAdapterTest.properties();
        var limit = base.limits();
        var limits = new FileIngestionProperties.Limits(limit.maxBytes(), limit.maxItemsPerSet(),
                limit.ownerPendingCount(), limit.ownerPendingBytes(), limit.tenantPendingCount(),
                limit.tenantPendingBytes(), limit.intentTtl(), limit.streamTimeout(), concurrency,
                limit.scratchBytes(), limit.scanTimeout(), limit.proofFreshness(), limit.maxImagePixels());
        return new FileIngestionProperties(base.enabled(), base.storage(), base.scanner(), limits,
                base.allowedMediaTypes(), base.policyVersion(), base.verifierId());
    }

    private static final class HandoffHarness implements AutoCloseable {
        private final LocalPrivateUploadProducer producer;
        private final PausedHandoffExecutor io;
        private final ExecutorService caller;
        private final Method external;

        HandoffHarness() throws Exception { this(1); }

        HandoffHarness(int concurrency) throws Exception {
            producer = new LocalPrivateUploadProducer(
                    properties(concurrency), IngestionProofCodec.unavailable());
            caller = Executors.newFixedThreadPool(concurrency,
                    Thread.ofPlatform().daemon().name("private-io-test-caller-", 0).factory());
            Field field = LocalPrivateUploadProducer.class.getDeclaredField("io");
            field.setAccessible(true);
            var original = (ThreadPoolExecutor) field.get(producer);
            assertThat(original.getCorePoolSize()).isEqualTo(concurrency);
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
            return external(work, Deadline.after(Duration.ofSeconds(10)));
        }

        String external(Callable<String> work, Deadline deadline) throws Exception {
            try {
                return (String) external.invoke(producer, work, deadline, null);
            } catch (InvocationTargetException invocation) {
                if (invocation.getCause() instanceof Exception exception) throw exception;
                if (invocation.getCause() instanceof Error error) throw error;
                throw new AssertionError(invocation.getCause());
            }
        }

        int availableSlots() throws ReflectiveOperationException {
            Field field = LocalPrivateUploadProducer.class.getDeclaredField("ioSlots");
            field.setAccessible(true);
            return ((Semaphore) field.get(producer)).availablePermits();
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
            io.resumeSecond.countDown();
            io.resumeSecondExecuteReturn.countDown();
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
        private final CountDownLatch thirdAttempted = new CountDownLatch(1);
        private final AtomicBoolean thirdAccepted = new AtomicBoolean();
        private final AtomicBoolean rejectNext = new AtomicBoolean();
        private final AtomicInteger starts = new AtomicInteger();
        private final AtomicBoolean pauseSecondBeforeRun = new AtomicBoolean();
        private final CountDownLatch secondDequeued = new CountDownLatch(1);
        private final CountDownLatch resumeSecond = new CountDownLatch(1);
        private final CountDownLatch secondFinished = new CountDownLatch(1);
        private final AtomicBoolean pauseSecondExecuteReturn = new AtomicBoolean();
        private final CountDownLatch secondAdmitted = new CountDownLatch(1);
        private final CountDownLatch resumeSecondExecuteReturn = new CountDownLatch(1);

        PausedHandoffExecutor(ThreadPoolExecutor actual) {
            super(actual.getCorePoolSize(), actual.getMaximumPoolSize(),
                    actual.getKeepAliveTime(TimeUnit.NANOSECONDS), TimeUnit.NANOSECONDS,
                    actual.getQueue(), actual.getThreadFactory(), actual.getRejectedExecutionHandler());
        }

        @Override
        public void execute(Runnable command) {
            int number = submissions.incrementAndGet();
            try {
                if (rejectNext.compareAndSet(true, false)) {
                    throw new RejectedExecutionException("synthetic rejection before execution");
                }
                super.execute(command);
                if (number == 2) {
                    secondAccepted.set(true);
                    secondAdmitted.countDown();
                    if (pauseSecondExecuteReturn.get()) {
                        try {
                            if (!resumeSecondExecuteReturn.await(10, TimeUnit.SECONDS)) {
                                throw new AssertionError("test did not release execute return");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
                if (number == 3) thirdAccepted.set(true);
            } finally {
                if (number == 2) secondAttempted.countDown();
                if (number == 3) thirdAttempted.countDown();
            }
        }

        @Override
        protected void beforeExecute(Thread thread, Runnable task) {
            super.beforeExecute(thread, task);
            if (starts.incrementAndGet() != 2 || !pauseSecondBeforeRun.get()) return;
            secondDequeued.countDown();
            try {
                if (!resumeSecond.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("test did not release the dequeued worker");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        protected void afterExecute(Runnable task, Throwable failure) {
            super.afterExecute(task, failure);
            int number = completions.incrementAndGet();
            if (number == 2) secondFinished.countDown();
            if (number != 1) return;
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

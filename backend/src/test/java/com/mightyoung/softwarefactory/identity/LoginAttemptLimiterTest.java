package com.mightyoung.softwarefactory.identity;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class LoginAttemptLimiterTest {

    private final AtomicLong now = new AtomicLong();

    @Test
    void failuresAccumulateAcrossNeutralSuccessesAndCooldownDoesNotSlide() {
        var limiter = limiter(100, 100, 3, 8, 10, 10);
        fail(limiter, "peer", "operator");
        try (var success = limiter.reserve("peer", "operator")) { }
        fail(limiter, "peer", "operator");
        fail(limiter, "peer", "operator");
        assertLimited(() -> limiter.reserve("peer", "operator"), 30);
        advance(Duration.ofSeconds(29).plusNanos(1));
        assertLimited(() -> limiter.reserve("peer", "operator"), 1);
        advance(Duration.ofSeconds(1).minusNanos(1));
        try (var fresh = limiter.reserve("peer", "operator")) { }
    }

    @Test
    void normalizationSharesTheAccountBudgetButDifferentSocketPeersRemainIsolated() {
        var limiter = limiter(100, 100, 2, 8, 10, 10);
        fail(limiter, "peer", "  ＯＰＥＲＡＴＯＲ  ");
        fail(limiter, "peer", "operator");
        assertLimited(() -> limiter.reserve("peer", "Operator"), 30);
        try (var otherSource = limiter.reserve("another-peer", "operator");
             var otherAccount = limiter.reserve("peer", "another-operator")) { }
    }

    @Test
    void simultaneousReservationsCannotOverrunTheFailureBudget() throws Exception {
        var limiter = limiter(100, 100, 3, 8, 10, 10);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<LoginAttemptLimiter.Attempt>> futures = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 8; i++) futures.add(executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("Start signal missing");
                try { return limiter.reserve("peer", "operator"); }
                catch (LoginRateLimitedException rejected) { return null; }
            }));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<LoginAttemptLimiter.Attempt> accepted = new ArrayList<>();
            for (var future : futures) {
                var attempt = future.get(5, TimeUnit.SECONDS);
                if (attempt != null) accepted.add(attempt);
            }
            assertThat(accepted).hasSize(3);
            accepted.forEach(LoginAttemptLimiter.Attempt::close);
        } finally {
            start.countDown();
        }
        try (var released = limiter.reserve("peer", "operator")) { }
    }

    @Test
    void concurrentPasswordWorkIsBoundedAcrossDistinctPairsAndCloseIsIdempotent() {
        var limiter = limiter(100, 100, 5, 1, 10, 10);
        var first = limiter.reserve("peer-a", "a");
        assertLimited(() -> limiter.reserve("peer-b", "b"), 1);
        first.close();
        first.close();
        try (var second = limiter.reserve("peer-b", "b")) {
            assertLimited(() -> limiter.reserve("peer-c", "c"), 1);
        }
    }

    @Test
    void aLateFailureCannotEnterTheNextFailureWindow() {
        var limiter = limiter(100, 100, 2, 8, 10, 10);
        var old = limiter.reserve("peer", "operator");
        advance(Duration.ofMinutes(5));
        fail(limiter, "peer", "operator");
        old.badCredentials();
        old.close();
        // Exactly one current-window failure: a second attempt must still be allowed.
        fail(limiter, "peer", "operator");
        assertLimited(() -> limiter.reserve("peer", "operator"), 30);
    }

    @Test
    void expiredEntriesWithOutstandingWorkAreNotEvictedForNewAccounts() {
        var limiter = limiter(100, 100, 5, 8, 10, 1);
        var old = limiter.reserve("peer", "old-account");
        advance(Duration.ofMinutes(5));
        assertLimited(() -> limiter.reserve("peer", "new-account"), 1);
        old.close();
        try (var fresh = limiter.reserve("peer", "new-account")) { }
    }

    @Test
    void capacitySaturationDoesNotEvictAnActiveCooldownAndRecoversAtItsBoundary() {
        var limiter = limiter(100, 100, 1, 8, 10, 1);
        fail(limiter, "peer", "operator");
        assertLimited(() -> limiter.reserve("peer", "other-account"), 30);
        advance(Duration.ofSeconds(30));
        try (var recovered = limiter.reserve("peer", "other-account")) { }
    }

    @Test
    void sourceAndGlobalAttemptBudgetsRecoverWithoutDeniedRequestsExtendingThem() {
        var limiter = limiter(3, 2, 5, 8, 10, 10);
        limiter.admitSource("peer-a");
        limiter.admitSource("peer-a");
        assertLimited(() -> limiter.admitSource("peer-a"), 60);
        limiter.admitSource("peer-b");
        advance(Duration.ofSeconds(59).plusNanos(1));
        assertLimited(() -> limiter.admitSource("peer-c"), 1);
        advance(Duration.ofSeconds(1).minusNanos(1));
        limiter.admitSource("peer-a");
        limiter.admitSource("peer-b");
        limiter.admitSource("peer-c");
    }

    @Test
    void globallyRejectedUnknownSourcesCannotPolluteTheNextWindowCapacity() {
        var limiter = limiter(2, 10, 5, 8, 2, 10);
        limiter.admitSource("peer-a");
        limiter.admitSource("peer-a");
        advance(Duration.ofSeconds(59));
        assertLimited(() -> limiter.admitSource("peer-b"), 1);
        advance(Duration.ofSeconds(1));
        limiter.admitSource("peer-c");
        limiter.admitSource("peer-d");
    }

    @Test
    void sourceCapacityFailsClosedWithoutEvictingAnActivePeer() {
        var limiter = limiter(100, 100, 5, 8, 1, 10);
        limiter.admitSource("peer-a");
        assertLimited(() -> limiter.admitSource("peer-b"), 60);
        limiter.admitSource("peer-a");
        advance(Duration.ofSeconds(60));
        limiter.admitSource("peer-b");
    }

    @Test
    void monotonicIntervalsSurviveSignedNanoTimeWrap() {
        now.set(Long.MAX_VALUE - Duration.ofSeconds(10).toNanos());
        var limiter = limiter(100, 1, 1, 8, 10, 10);
        limiter.admitSource("peer");
        fail(limiter, "peer", "operator");
        advance(Duration.ofSeconds(29));
        assertLimited(() -> limiter.reserve("peer", "operator"), 1);
        advance(Duration.ofSeconds(1));
        try (var fresh = limiter.reserve("peer", "operator")) { }
        advance(Duration.ofSeconds(30));
        limiter.admitSource("peer");
    }

    @Test
    void impossibleOrUnboundedConfigurationIsRejected() {
        assertThatThrownBy(() -> new LoginAttemptLimiter.Config(0, 1, Duration.ofSeconds(1), 1,
                Duration.ofSeconds(1), Duration.ofSeconds(1), 1, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginAttemptLimiter.Config(1, 1, Duration.ofSeconds(1), 1,
                Duration.ofSeconds(1), Duration.ofSeconds(1), 1, 100_001, 1))
                .isInstanceOf(IllegalArgumentException.class);
        for (Duration invalid : List.of(Duration.ZERO, Duration.ofNanos(1), Duration.ofSeconds(Long.MAX_VALUE))) {
            assertThatThrownBy(() -> new LoginAttemptLimiter.Config(1, 1, invalid, 1,
                    Duration.ofSeconds(1), Duration.ofSeconds(1), 1, 1, 1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private LoginAttemptLimiter limiter(int global, int source, int failures, int concurrent, int sources, int pairs) {
        return new LoginAttemptLimiter(new LoginAttemptLimiter.Config(global, source, Duration.ofMinutes(1),
                failures, Duration.ofMinutes(5), Duration.ofSeconds(30), concurrent, sources, pairs), now::get);
    }

    private void advance(Duration duration) { now.addAndGet(duration.toNanos()); }

    private static void fail(LoginAttemptLimiter limiter, String source, String login) {
        try (var attempt = limiter.reserve(source, login)) { attempt.badCredentials(); }
    }

    private static void assertLimited(ThrowingAction action, long seconds) {
        Throwable failure = catchThrowable(action::run);
        assertThat(failure).isInstanceOf(LoginRateLimitedException.class);
        assertThat(((LoginRateLimitedException) failure).retryAfterSeconds()).isEqualTo(seconds);
        assertThat(failure.getMessage()).isEqualTo("Too many login attempts; try again later");
    }

    @FunctionalInterface
    private interface ThrowingAction { void run() throws Exception; }
}

package com.mightyoung.softwarefactory.identity;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Bounded, process-local protection. Fixed-window source/global attempts precede body parsing;
 * source/login failure reservations precede password work. This is not a distributed account lock.
 */
public final class LoginAttemptLimiter {

    private static final long SECOND_NANOS = Duration.ofSeconds(1).toNanos();
    private final Config config;
    private final LongSupplier nanoTime;
    private final SecretKeySpec key;
    private final long attemptWindowNanos;
    private final long failureWindowNanos;
    private final long cooldownNanos;
    private final Map<String, Window> sources = new HashMap<>();
    private final Map<String, Failures> pairs = new HashMap<>();
    private Window global;
    private int concurrent;

    public LoginAttemptLimiter(Config config, LongSupplier nanoTime) {
        this.config = Objects.requireNonNull(config, "config");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        attemptWindowNanos = config.attemptWindow().toNanos();
        failureWindowNanos = config.failureWindow().toNanos();
        cooldownNanos = config.cooldown().toNanos();
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        key = new SecretKeySpec(secret, "HmacSHA256");
        java.util.Arrays.fill(secret, (byte) 0);
    }

    /** Every admitted POST consumes a source/global slot, including malformed input and success. */
    public void admitSource(String remoteAddr) {
        String sourceKey = digest("source", source(remoteAddr));
        synchronized (this) {
            long now = nanoTime.getAsLong();
            if (global == null || elapsed(now, global.started) >= attemptWindowNanos) global = new Window(now);
            Window source = sources.get(sourceKey);
            long wait = 0;
            if (global.attempts >= config.globalAttempts()) wait = remaining(now, global.started, attemptWindowNanos);
            if (source != null && elapsed(now, source.started) < attemptWindowNanos
                    && source.attempts >= config.sourceAttempts()) {
                wait = Math.max(wait, remaining(now, source.started, attemptWindowNanos));
            }
            // Rejected global traffic must not allocate/refresh an otherwise unused source slot.
            if (wait > 0) throw limited(wait);
            if (source == null) {
                if (sources.size() >= config.maxSources()) {
                    sources.values().removeIf(window -> elapsed(now, window.started) >= attemptWindowNanos);
                    if (sources.size() >= config.maxSources()) {
                        throw limited(earliestSourceExpiry(now));
                    }
                }
                source = new Window(now);
                sources.put(sourceKey, source);
            } else if (elapsed(now, source.started) >= attemptWindowNanos) {
                source.started = now;
                source.attempts = 0;
            }
            global.attempts++;
            source.attempts++;
        }
    }

    /** Tenant selection cannot split this budget; normalization matches the password provider. */
    public Attempt reserve(String remoteAddr, String loginKey) {
        Objects.requireNonNull(loginKey, "loginKey");
        if (loginKey.length() > 200) throw new IllegalArgumentException("Login key exceeds the parser boundary");
        String normalized = Normalizer.normalize(loginKey.strip(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        String pairKey = digest("pair", source(remoteAddr), normalized);
        synchronized (this) {
            long now = nanoTime.getAsLong();
            if (concurrent >= config.maxConcurrent()) throw limited(SECOND_NANOS);
            Failures pair = pairs.get(pairKey);
            if (pair == null) {
                if (pairs.size() >= config.maxPairs()) {
                    pairs.values().removeIf(state -> state.inFlight == 0 && state.expired(now));
                    if (pairs.size() >= config.maxPairs()) throw limited(earliestPairExpiry(now));
                }
                pair = new Failures(now);
                pairs.put(pairKey, pair);
            } else if (pair.expired(now)) {
                // Keep outstanding reservations, but their late results belong to the old window.
                pair.failures = 0;
                pair.blocked = false;
                pair.started = now;
                pair.generation++;
            }
            if (pair.blocked) throw limited(remaining(now, pair.blockedAt, cooldownNanos));
            if (pair.failures + pair.inFlight >= config.failureThreshold()) throw limited(SECOND_NANOS);
            pair.inFlight++;
            concurrent++;
            return new Attempt(pair, pair.generation);
        }
    }

    private long earliestSourceExpiry(long now) {
        return sources.values().stream().mapToLong(window -> remaining(now, window.started, attemptWindowNanos))
                .min().orElse(SECOND_NANOS);
    }

    private long earliestPairExpiry(long now) {
        return pairs.values().stream().mapToLong(state -> state.inFlight == 0
                        ? state.remaining(now) : SECOND_NANOS)
                .min().orElse(SECOND_NANOS);
    }

    private static long elapsed(long now, long started) {
        // nanoTime subtraction remains correct across signed wrap for the bounded (< 1 day) intervals.
        return now - started;
    }

    private static long remaining(long now, long started, long duration) {
        return Math.max(1, duration - elapsed(now, started));
    }

    private static LoginRateLimitedException limited(long nanos) {
        return new LoginRateLimitedException(Math.max(1, (nanos + SECOND_NANOS - 1) / SECOND_NANOS));
    }

    private static String source(String remoteAddr) {
        // Only the servlet socket peer is passed here; forwarding headers are deliberately ignored.
        return remoteAddr == null || remoteAddr.isBlank() || remoteAddr.length() > 256
                ? "unavailable-source" : remoteAddr.strip().toLowerCase(Locale.ROOT);
    }

    private String digest(String... fields) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            for (String field : fields) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                mac.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                mac.update(bytes);
                java.util.Arrays.fill(bytes, (byte) 0);
            }
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("Login attempt protection is unavailable", unavailable);
        }
    }

    public record Config(int globalAttempts, int sourceAttempts, Duration attemptWindow,
                         int failureThreshold, Duration failureWindow, Duration cooldown,
                         int maxConcurrent, int maxSources, int maxPairs) {
        public Config {
            bounded(globalAttempts, 1_000_000);
            bounded(sourceAttempts, 1_000_000);
            bounded(failureThreshold, 1_000);
            bounded(maxConcurrent, 1_024);
            bounded(maxSources, 100_000);
            bounded(maxPairs, 100_000);
            duration(attemptWindow);
            duration(failureWindow);
            duration(cooldown);
        }

        public static Config defaults() {
            return new Config(600, 60, Duration.ofMinutes(1), 5, Duration.ofMinutes(5),
                    Duration.ofSeconds(30), 8, 4_096, 8_192);
        }

        private static void bounded(int value, int maximum) {
            if (value < 1 || value > maximum) throw new IllegalArgumentException("Invalid login protection capacity");
        }

        private static void duration(Duration value) {
            Objects.requireNonNull(value, "duration");
            if (value.compareTo(Duration.ofMillis(1)) < 0 || value.compareTo(Duration.ofDays(1)) > 0) {
                throw new IllegalArgumentException("Login protection intervals must be between 1ms and 1d");
            }
        }
    }

    private static final class Window {
        private long started;
        private int attempts;
        private Window(long now) { started = now; }
    }

    private final class Failures {
        private long started;
        private int failures;
        private int inFlight;
        private boolean blocked;
        private long blockedAt;
        private long generation;
        private Failures(long now) { started = now; }
        private boolean expired(long now) {
            return blocked ? elapsed(now, blockedAt) >= cooldownNanos : elapsed(now, started) >= failureWindowNanos;
        }
        private long remaining(long now) {
            return blocked ? LoginAttemptLimiter.remaining(now, blockedAt, cooldownNanos)
                    : LoginAttemptLimiter.remaining(now, started, failureWindowNanos);
        }
    }

    /** Close is the single release/commit point, including exceptional or neutral outcomes. */
    public final class Attempt implements AutoCloseable {
        private final Failures pair;
        private final long generation;
        private boolean failed;
        private boolean closed;

        private Attempt(Failures pair, long generation) {
            this.pair = pair;
            this.generation = generation;
        }

        public void badCredentials() {
            synchronized (LoginAttemptLimiter.this) {
                if (!closed) failed = true;
            }
        }

        @Override
        public void close() {
            synchronized (LoginAttemptLimiter.this) {
                if (closed) return;
                closed = true;
                pair.inFlight--;
                concurrent--;
                long now = nanoTime.getAsLong();
                if (failed && pair.generation == generation && !pair.blocked
                        && elapsed(now, pair.started) < failureWindowNanos) {
                    pair.failures++;
                    if (pair.failures >= config.failureThreshold()) {
                        pair.blocked = true;
                        pair.blockedAt = now;
                        pair.generation++;
                    }
                }
            }
        }
    }
}

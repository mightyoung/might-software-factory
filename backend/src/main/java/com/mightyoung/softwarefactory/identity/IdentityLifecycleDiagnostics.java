package com.mightyoung.softwarefactory.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.security.access.AccessDeniedException;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLongArray;

/** Fixed-cardinality process counters plus at most eight safe diagnostic lines per minute. */
final class IdentityLifecycleDiagnostics {
    enum Stage { REQUEST, WRITE, FAILURE_AUDIT, QUEUE_SUBMISSION, DELIVERY_PREPARATION, PROVIDER, DELIVERY_CHECKPOINT, DEADLINE_OBSERVATION }
    private enum Failure { DATABASE, UNAVAILABLE, COMMIT_UNKNOWN, REJECTED, AUTHORIZATION, UNEXPECTED }
    private static final Logger LOG=LoggerFactory.getLogger(IdentityLifecycleDiagnostics.class);
    private final AtomicLongArray counters=new AtomicLongArray(Stage.values().length);
    private long windowStart=System.nanoTime(),suppressed;
    private int emitted;
    void record(Stage stage,RuntimeException failure,UUID reference) {
        int index=stage.ordinal();long total=counters.updateAndGet(index,value->value==Long.MAX_VALUE?value:value+1);
        synchronized(this) {
            long elapsed=System.nanoTime()-windowStart;
            if(elapsed>=java.time.Duration.ofMinutes(1).toNanos()){windowStart=System.nanoTime();emitted=0;}
            if(elapsed<0||emitted>=8){if(suppressed<Long.MAX_VALUE)suppressed++;return;}
            emitted++;long skipped=suppressed;suppressed=0;
            // Never pass a Throwable, its message, class name, cause, stack, or arbitrary caller text.
            LOG.warn("Identity lifecycle diagnostic stage={} failure={} reference={} total={} suppressed={}",
                    stage,kind(failure),reference==null?"-":reference.toString(),total,skipped);
        }
    }
    Map<Stage,Long> counts(){var values=new EnumMap<Stage,Long>(Stage.class);for(var stage:Stage.values())values.put(stage,counters.get(stage.ordinal()));return Map.copyOf(values);}
    private static Failure kind(RuntimeException failure) {
        if(failure instanceof DataAccessException)return Failure.DATABASE;
        if(failure instanceof UnauthenticatedSessionException||failure instanceof AccessDeniedException)return Failure.AUTHORIZATION;
        if(failure instanceof IdentityLifecycleException known)return switch(known.reason()) {
            case UNAVAILABLE -> Failure.UNAVAILABLE;
            case COMMIT_OUTCOME_UNKNOWN -> Failure.COMMIT_UNKNOWN;
            default -> Failure.REJECTED;
        };
        return Failure.UNEXPECTED;
    }
}

package com.mightyoung.softwarefactory.identity;

import java.time.Clock;
import java.util.HashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded process pre-admission precedes request parsing, persistence and durable event writes. */
public final class IdentityLifecycleAdmission {
    private final HashMap<String,Window> sources=new HashMap<>();
    private final Semaphore inflight=new Semaphore(8);
    private final Clock clock;private final int perMinute,globalMaximum,capacity;
    private Window global;
    public IdentityLifecycleAdmission(Clock clock,int perMinute,int globalMaximum,int capacity){
        if(perMinute<1||perMinute>120||globalMaximum<1||globalMaximum>1000||capacity<1||capacity>4096)throw IdentityLifecycleContracts.invalid();
        this.clock=clock;this.perMinute=perMinute;this.globalMaximum=globalMaximum;this.capacity=capacity;
    }
    public Permit admit(String sourceDigest) {
        IdentityLifecycleContracts.hash(sourceDigest);
        synchronized(sources){
            long now=clock.millis();sources.entrySet().removeIf(entry->now>=entry.getValue().start&&now-entry.getValue().start>=60_000L);
            if(global==null||now>=global.start&&now-global.start>=60_000L)global=new Window(now);
            if(now<global.start||global.count>=globalMaximum)throw limited();global.count++;
            Window window=sources.get(sourceDigest);
            if(window==null){if(sources.size()>=capacity)throw limited();window=new Window(now);sources.put(sourceDigest,window);}
            if(now<window.start||window.count>=perMinute)throw limited();
            window.count++;
        }
        if(!inflight.tryAcquire())throw limited();return new Permit();
    }
    public final class Permit implements AutoCloseable {
        private final AtomicBoolean released=new AtomicBoolean();
        @Override public void close(){if(released.compareAndSet(false,true))inflight.release();}
    }
    private static final class Window {final long start;int count;Window(long start){this.start=start;}}
    private static IdentityLifecycleException limited(){return new IdentityLifecycleException(IdentityLifecycleException.Reason.RATE_LIMITED);}
}

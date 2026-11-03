package com.mightyoung.softwarefactory.identity;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Memory-only bridge. A lost process cannot reconstruct or automatically resend a capability. */
final class IdentityCapabilityMemoryDispatcher implements AutoCloseable {
    private final Semaphore slots=new Semaphore(18);
    private final ThreadPoolExecutor executor=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),
            runnable->{Thread thread=new Thread(runnable,"identity-capability-dispatch");thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());
    public Ticket reserve(){if(!slots.tryAcquire())throw IdentityLifecycleException.unavailable();return new Ticket();}
    public void submit(Ticket ticket,CredentialCapabilityDeliveryPort.PreparedDelivery delivery,
                       Consumer<CredentialCapabilityDeliveryPort.PreparedDelivery> operation) {
        if(ticket.owner()!=this||!ticket.state.compareAndSet(0,1)){delivery.close();throw IdentityLifecycleException.unavailable();}
        Job job=new Job(ticket,delivery,operation);
        try{executor.execute(job);}catch(RuntimeException rejected){job.discard();throw IdentityLifecycleException.unavailable();}
    }
    public final class Ticket implements AutoCloseable {
        private final AtomicInteger state=new AtomicInteger();
        private IdentityCapabilityMemoryDispatcher owner(){return IdentityCapabilityMemoryDispatcher.this;}
        @Override public void close(){if(state.compareAndSet(0,2))slots.release();}
        private void release(){if(state.compareAndSet(1,2))slots.release();}
    }
    private static final class Job implements Runnable {
        private final Ticket ticket;private final CredentialCapabilityDeliveryPort.PreparedDelivery delivery;
        private final Consumer<CredentialCapabilityDeliveryPort.PreparedDelivery> operation;
        private Job(Ticket ticket,CredentialCapabilityDeliveryPort.PreparedDelivery delivery,Consumer<CredentialCapabilityDeliveryPort.PreparedDelivery> operation){this.ticket=ticket;this.delivery=delivery;this.operation=operation;}
        @Override public void run(){try(delivery){operation.accept(delivery);}finally{ticket.release();}}
        private void discard(){delivery.close();ticket.release();}
    }
    @Override public void close(){executor.shutdownNow().forEach(task->{if(task instanceof Job job)job.discard();});}
}

package com.mightyoung.softwarefactory.support;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.modulith.events.core.TargetEventPublication;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Private, one-use orchestration authority. It never owns a JDBC transaction. */
final class NativeRiskRecovery {
    private final NativePublicationInventory inventory;
    private final ThreadLocal<Window> current=new ThreadLocal<>();
    private volatile IncompleteEventPublications multicaster;
    private org.springframework.context.support.AbstractApplicationContext context;
    private Object registry,repository;
    private ProxyWitness registryWitness,repositoryWitness;
    NativeRiskRecovery(NativePublicationInventory inventory){this.inventory=inventory;}
    void bind(org.springframework.context.support.AbstractApplicationContext context){
        if(multicaster!=null)throw reject();
        Object actual=context.getBean("applicationEventMulticaster");
        if(actual.getClass()!=org.springframework.modulith.events.support.PersistentApplicationEventMulticaster.class)throw reject();
        this.context=context;
        registry=context.getBean(org.springframework.modulith.events.core.EventPublicationRegistry.class);
        repository=context.getBean(org.springframework.modulith.events.core.EventPublicationRepository.class);
        registryWitness=new ProxyWitness(registry);repositoryWitness=new ProxyWitness(repository);
        multicaster=(IncompleteEventPublications)actual;
    }
    void recover(){
        if(multicaster==null||context.getBean("applicationEventMulticaster")!=multicaster
                ||context.getBean(org.springframework.modulith.events.core.EventPublicationRegistry.class)!=registry
                ||context.getBean(org.springframework.modulith.events.core.EventPublicationRepository.class)!=repository)throw reject();
        requireProxies();
        if(current.get()!=null||!detached())throw reject();
        inventory.requireWritable();
        Window window=new Window();current.set(window);
        try{
            multicaster.resubmitIncompletePublications(window.selector);
            if(window.failed||!window.entered||!window.readClosed||window.selected!=null||!detached())throw reject();
        }finally{window.live=false;current.remove();}
    }
    boolean active(){return current.get()!=null;}
    boolean process(Object[] args){
        Window w=require();
        if(w.entered||args.length!=3||args[0]!=w.selector||args[2]!=null||!(args[1] instanceof Consumer<?>))throw reject();
        w.entered=true;
        @SuppressWarnings("unchecked") Consumer<TargetEventPublication> nativeConsumer=(Consumer<TargetEventPublication>)args[1];
        args[1]=(Consumer<TargetEventPublication>)publication->{
            try{
                require();if(w.selected!=publication||!w.resubmitted||!detached())throw reject();
                RiskScenarioExecutor executor=inventory.riskExecutor();
                long before=executor.acceptedOnCurrentThread();
                nativeConsumer.accept(publication);
                require();
                if(before==Long.MAX_VALUE||executor.acceptedOnCurrentThread()!=before+1||!w.candidates.remove(publication))throw reject();
                w.selected=null;w.resubmitted=false;
            }catch(Throwable failure){w.failed=true;throw reject();}
        };
        return true;
    }
    void materialize(Object result){
        Window w=require();if(!w.entered||w.readClosed||!(result instanceof Collection<?> rows)||rows.size()>256)throw reject();
        for(Object row:rows){
            if(!(row instanceof TargetEventPublication publication)||publication.getIdentifier()==null
                    ||!w.ids.add(publication.getIdentifier())||!w.candidates.add(publication))throw reject();
            inventory.requirePair(publication.getEvent(),publication.getTargetIdentifier(),true);
        }
    }
    void readClosed(){Window w=require();if(w.readClosed||!detached())throw reject();w.readClosed=true;}
    void resubmitted(Object[] args){
        Window w=require();if(!w.readClosed||w.selected==null||w.resubmitted||args.length!=2
                ||!w.selected.getIdentifier().equals(args[0])||!(args[1] instanceof java.time.Instant)||!detached())throw reject();
        w.resubmitted=true;
    }
    void serialized(String value){
        Window w=require();int bytes=value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if(bytes>65536||(w.bytes+=bytes)>1048576)throw reject();
    }
    void fail(){Window w=current.get();if(w!=null)w.failed=true;}
    private void requireProxies(){
        try{registryWitness.require();repositoryWitness.require();}catch(Throwable failure){throw reject();}
    }
    private static final class ProxyWitness {
        final org.springframework.aop.framework.Advised proxy;
        final org.springframework.aop.TargetSource source;
        final Object target;
        ProxyWitness(Object value){
            if(!(value instanceof org.springframework.aop.framework.Advised advised))throw new IllegalStateException("Risk recovery unavailable");
            proxy=advised;source=proxy.getTargetSource();
            try{if(!source.isStatic()||(target=source.getTarget())==null)throw new IllegalStateException("Risk recovery unavailable");}
            catch(Exception failure){throw new IllegalStateException("Risk recovery unavailable");}
        }
        void require()throws Exception{if(proxy.getTargetSource()!=source||!source.isStatic()||source.getTarget()!=target)throw new IllegalStateException("Risk recovery unavailable");}
    }
    private Window require(){requireProxies();Window w=current.get();if(w==null||!w.live||w.failed||w.thread!=Thread.currentThread())throw reject();return w;}
    private IllegalStateException reject(){fail();return new IllegalStateException("Risk recovery unavailable");}
    private static boolean detached(){return !TransactionSynchronizationManager.isActualTransactionActive()
            &&!TransactionSynchronizationManager.isSynchronizationActive()&&TransactionSynchronizationManager.getResourceMap().isEmpty();}
    private final class Window {
        final Thread thread=Thread.currentThread();
        final Set<UUID> ids=new HashSet<>();
        final Set<TargetEventPublication> candidates=Collections.newSetFromMap(new IdentityHashMap<>());
        boolean live=true,failed,entered,readClosed,resubmitted;
        int bytes;
        TargetEventPublication selected;
        final Predicate<EventPublication> selector=publication->{
            require();if(!readClosed||selected!=null||!candidates.contains(publication)||!detached())throw reject();
            if(!inventory.risk(publication.getEvent()))return false;
            selected=(TargetEventPublication)publication;return true;
        };

    }
}

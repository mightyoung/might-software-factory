package com.mightyoung.softwarefactory.support;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.ApplicationContextEvent;
import org.springframework.context.event.GenericApplicationListener;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.core.ResolvableType;
import org.springframework.modulith.events.core.PublicationTargetIdentifier;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalApplicationListener;
import org.springframework.transaction.event.TransactionalApplicationListenerMethodAdapter;
import org.springframework.transaction.event.TransactionalEventListenerFactory;

/** Finite metadata plus publicly observable, refreshed-context registration evidence. Not a tenant authorization. */
final class NativePublicationInventory implements ApplicationListener<ApplicationContextEvent> {
    private record Family(Class<?> event, Class<?> listener, Method method, String id, boolean risk) {}
    private final AbstractApplicationContext context;
    private final List<Family> families;
    private final RiskScenarioExecutor riskExecutor;
    private volatile boolean riskRegistered;
    private volatile String riskListenerBean;
    private volatile Object riskListenerInstance;
    private volatile Set<Class<?>> registered = Set.of();
    private volatile boolean verified;
    private volatile boolean closing;
    private volatile String repositoryBean;
    private volatile Object repositoryInstance;

    NativePublicationInventory(AbstractApplicationContext context) {
        this.context = java.util.Objects.requireNonNull(context);
        this.riskExecutor = new RiskScenarioExecutor(context);
        try {
            String base = "com.mightyoung.softwarefactory.dualprevention.";
            var definitions = List.of(
                    new String[]{"finding.workflow.FindingWorkflowChangedEvent", "FindingWorkflowEventListener", "record"},
                    new String[]{"asset.AssetFoundationChangedEvent", "asset.AssetFoundationEventListener", "record"},
                    new String[]{"finding.evidenceupload.FindingEvidenceQuarantinedEvent", "finding.evidenceupload.FindingEvidenceIngestionWakeListener", "wake"},
                    new String[]{"risk.RiskScenarioDraftChangedEvent", "risk.RiskScenarioDraftChangedListener", "capture"});
            var values = new ArrayList<Family>();
            for (int index = 0; index < definitions.size(); index++) {
                var definition = definitions.get(index);
                Class<?> event = Class.forName(base + definition[0], false, context.getClassLoader());
                Class<?> listener = Class.forName(base + definition[1], false, context.getClassLoader());
                Method method = listener.getMethod(definition[2], event);
                var adapter = new TransactionalApplicationListenerMethodAdapter("metadataOnly", listener, method);
                if (method.getReturnType() != void.class || adapter.getTransactionPhase() != TransactionPhase.AFTER_COMMIT
                        || !adapter.supportsEventType(payload(event))) throw unavailable();
                values.add(new Family(event, listener, method, adapter.getListenerId(), index == 3));
            }
            if (values.stream().map(Family::id).distinct().count() != 4) throw unavailable();
            families = List.copyOf(values);
            // EventListenerMethodProcessor has not necessarily run during composition. No early readiness assertion.
            context.addApplicationListener(this);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { throw unavailable(); }
    }

    Set<Class<?>> eventTypes() { return families.stream().map(Family::event).collect(java.util.stream.Collectors.toUnmodifiableSet()); }
    RiskScenarioExecutor riskExecutor() { return riskExecutor; }
    boolean risk(Object event) { return family(event == null ? null : event.getClass()).risk(); }
    String target(Object event) { return family(event == null ? null : event.getClass()).id(); }
    void requirePair(Object event, Object target, boolean requireRegistered) {
        Family family = family(event == null ? null : event.getClass());
        if (!(target instanceof PublicationTargetIdentifier identifier) || target.getClass() != PublicationTargetIdentifier.class
                || !family.id().equals(identifier.getValue())) throw unavailable();
        if (requireRegistered && (!verified || !registered.contains(family.event()))) throw unavailable();
    }
    void requireRow(String eventName, String target) {
        if (eventName == null || target == null || families.stream().noneMatch(family -> family.event().getName().equals(eventName)
                && family.id().equals(target))) throw unavailable();
    }
    void requireReady() {
        if (!verified || !context.isActive() && !closing) throw unavailable();
        try {
            if (repositoryBean == null || context.getBeanFactory().getSingleton(repositoryBean) != repositoryInstance) throw unavailable();
            // Native destroy may still read after the worker has closed; writes requireWritable below.
            if (riskRegistered && !closing) {
                riskExecutor.requireOwner(context);
                if (context.getBeanFactory().getSingleton(riskListenerBean) != riskListenerInstance) throw unavailable();
                requireRiskAsync(families.stream().filter(Family::risk).findFirst().orElseThrow(), riskListenerInstance);
            }
        } catch (RuntimeException | LinkageError failure) { throw unavailable(); }
    }
    void requireWritable() { requireReady(); if (closing || !context.isActive()) throw unavailable(); }

    @Override public void onApplicationEvent(ApplicationContextEvent event) {
        if (event.getApplicationContext() != context) return;
        if (event instanceof ContextClosedEvent) { closing = true; return; }
        if (!(event instanceof ContextRefreshedEvent)) return;
        closing = false;
        verified = false;
        registered = Set.of();
        riskRegistered = false;
        try {
            Object multicaster = context.getBean(AbstractApplicationContext.APPLICATION_EVENT_MULTICASTER_BEAN_NAME);
            if (multicaster.getClass() != Class.forName("org.springframework.modulith.events.support.PersistentApplicationEventMulticaster", false,
                    context.getClassLoader())) throw unavailable();
            String[] registryNames = context.getBeanNamesForType(org.springframework.modulith.events.core.EventPublicationRegistry.class, false, false);
            String[] repositoryNames = context.getBeanNamesForType(org.springframework.modulith.events.core.EventPublicationRepository.class, false, false);
            if (registryNames.length != 1 || repositoryNames.length != 1
                    || !AopUtils.getTargetClass(context.getBean(registryNames[0])).getName().equals("org.springframework.modulith.events.core.DefaultEventPublicationRegistry")
                    || !AopUtils.getTargetClass(context.getBean(repositoryNames[0])).getName().equals("org.springframework.modulith.events.jdbc.JdbcEventPublicationRepository")) throw unavailable();
            repositoryBean = repositoryNames[0];
            repositoryInstance = context.getBean(repositoryBean);
            var factories = context.getBeansOfType(TransactionalEventListenerFactory.class);
            if (factories.size() != 1) throw unavailable();
            TransactionalEventListenerFactory factory = factories.values().iterator().next();
            String factoryName = factory.getClass().getName();
            if (!factoryName.equals("org.springframework.transaction.event.TransactionalEventListenerFactory")
                    && !factoryName.equals("org.springframework.transaction.annotation.RestrictedTransactionalEventListenerFactory")) throw unavailable();
            var found = new java.util.HashSet<Class<?>>();
            var ids = new java.util.HashSet<String>();
            for (Family family : families) {
                String[] names = context.getBeanNamesForType(family.listener(), false, false);
                if (names.length == 0 && family.risk()) continue;
                if (names.length != 1 || AopUtils.getTargetClass(context.getBean(names[0])) != family.listener()
                        || !factory.supportsMethod(family.method())) throw unavailable();
                if (family.risk()) {
                    var async = family.method().getAnnotation(org.springframework.scheduling.annotation.Async.class);
                    if (async == null || !RiskScenarioExecutor.BEAN_NAME.equals(async.value())
                            || context.getBean(RiskScenarioExecutor.BEAN_NAME) != riskExecutor) throw unavailable();
                    riskExecutor.requireOwner(context);
                    riskListenerBean = names[0];
                    riskListenerInstance = context.getBean(names[0]);
                    requireRiskAsync(family, riskListenerInstance);
                    riskRegistered = true;
                }
                List<ApplicationListener<?>> matching = context.getApplicationListeners().stream()
                        .filter(listener -> listener instanceof TransactionalApplicationListener<?> transaction
                                && family.id().equals(transaction.getListenerId())).toList();
                if (matching.size() != 1 || !(matching.getFirst() instanceof TransactionalApplicationListener<?> transaction)
                        || transaction.getTransactionPhase() != TransactionPhase.AFTER_COMMIT
                        || !(matching.getFirst() instanceof GenericApplicationListener generic)
                        || !generic.supportsEventType(payload(family.event())) || !ids.add(family.id())) throw unavailable();
                for (Family other : families) if (other != family && generic.supportsEventType(payload(other.event()))) throw unavailable();
                long payloadListeners = context.getApplicationListeners().stream()
                        .filter(listener -> listener instanceof TransactionalApplicationListener<?> && listener instanceof GenericApplicationListener candidate
                                && candidate.supportsEventType(payload(family.event()))).count();
                if (payloadListeners != 1) throw unavailable();
                found.add(family.event());
            }
            // Unregistered risk is a deliberate closed gate; all three existing consumers are mandatory.
            if (found.size() < 3) throw unavailable();
            registered = Set.copyOf(found);
            verified = true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { throw unavailable(); }
    }

    private Family family(Class<?> event) {
        return families.stream().filter(family -> family.event() == event).findFirst().orElseThrow(NativePublicationInventory::unavailable);
    }
    private void requireRiskAsync(Family family, Object listener) {
        try {
            if (!(listener instanceof org.springframework.aop.framework.Advised advised)
                    || advised.getTargetSource().getClass() != org.springframework.aop.target.SingletonTargetSource.class
                    || advised.getTargetSource().getTarget().getClass() != family.listener()) throw unavailable();
            var advisors = advised.getAdvisors();
            if (advisors.length == 0
                    || advisors[0].getClass() != org.springframework.scheduling.annotation.AsyncAnnotationAdvisor.class
                    || advisors[0].getAdvice().getClass() != org.springframework.scheduling.annotation.AnnotationAsyncExecutionInterceptor.class)
                throw unavailable();
            var pointcut = ((org.springframework.aop.PointcutAdvisor) advisors[0]).getPointcut();
            if (!pointcut.getClassFilter().matches(family.listener())
                    || !pointcut.getMethodMatcher().matches(family.method(), family.listener())) throw unavailable();
            long asyncCount = java.util.Arrays.stream(advisors)
                    .filter(advisor -> advisor.getAdvice() instanceof org.springframework.aop.interceptor.AsyncExecutionInterceptor).count();
            if (asyncCount != 1) throw unavailable();
        } catch (Exception | LinkageError failure) { throw unavailable(); }
    }
    private static ResolvableType payload(Class<?> event) { return ResolvableType.forClassWithGenerics(PayloadApplicationEvent.class, event); }
    private static IllegalStateException unavailable() { return new IllegalStateException("Native publication inventory unavailable"); }
}

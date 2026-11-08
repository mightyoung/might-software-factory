package com.mightyoung.softwarefactory.support;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.support.AopUtils;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.core.PublicationTargetIdentifier;
import org.springframework.modulith.events.core.TargetEventPublication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.interceptor.TransactionAttribute;

/** Private invocation routes for one native graph. No caller can issue a route or readiness assertion. */
final class SharedNativePublicationRouting {
    private enum Route { LEGACY_SOURCE, LEGACY_REGISTRY, RISK_SOURCE, RISK_REGISTRY, MIXED_READ, RECOVERY, RECOVERY_NOOP }
    private final NativeRiskRecovery recovery;
    private final DataSource canonical;
    private final org.springframework.context.support.AbstractApplicationContext context;
    private final RiskJdbcTransactionOwner owned;
    private final RiskPublicationJdbcTemplate ownedTemplate;
    private volatile G014RiskNativeParticipation riskSource;
    private volatile boolean configurationClosed;
    private boolean configurationAttempted;
    private final NativePublicationInventory inventory;
    private final Supplier<PlatformTransactionManager> original;
    private final JdbcTemplate ordinary;
    private final RiskPublicationSqlCatalog catalog;
    private final Set<Class<?>> publications;
    private final Class<?> registryClass;
    private final Class<?> repositoryClass;
    private final ThreadLocal<Frame> current = new ThreadLocal<>();
    private final NativePublicationReadJdbc reads;
    private final JdbcTemplate dispatcher;

    SharedNativePublicationRouting(DataSource canonical, Supplier<PlatformTransactionManager> original, JdbcTemplate ordinary,
            NativePublicationInventory inventory, RiskPublicationSqlCatalog catalog, Class<?> registryClass, Class<?> repositoryClass,
            Set<Class<?>> publications, RiskJdbcTransactionOwner owned, RiskPublicationJdbcTemplate ownedTemplate,
            org.springframework.context.support.AbstractApplicationContext context) {
        if (ordinary == null || ordinary.getDataSource() != canonical) throw unavailable();
        this.canonical = canonical;
        this.context = java.util.Objects.requireNonNull(context);
        this.owned = java.util.Objects.requireNonNull(owned);
        this.ownedTemplate = java.util.Objects.requireNonNull(ownedTemplate);
        this.original = original; this.ordinary = ordinary; this.inventory = inventory; this.catalog = catalog;
        this.registryClass = registryClass; this.repositoryClass = repositoryClass; this.publications = publications;
        recovery=new NativeRiskRecovery(inventory);
        reads = new NativePublicationReadJdbc(canonical, ordinary, inventory, this::requireFrame);
        JdbcOperations closed = (JdbcOperations) Proxy.newProxyInstance(JdbcOperations.class.getClassLoader(), new Class<?>[]{JdbcOperations.class},
                (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                        case "toString" -> "Native publication dispatcher";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == arguments[0];
                        default -> throw unavailable();
                    };
                    try { return jdbc(method, arguments); }
                    catch (Throwable failure) { rejectCurrent(); throw unavailable(); }
                });
        dispatcher = new NativePublicationDispatchTemplate(closed, this::rejectCurrent);
        context.addApplicationListener((org.springframework.context.ApplicationListener<org.springframework.context.event.ApplicationContextEvent>) event -> {
            if(event.getApplicationContext()==context&&event instanceof org.springframework.context.event.ContextRefreshedEvent)recovery.bind(context);
            if (event.getApplicationContext() == context && (event instanceof org.springframework.context.event.ContextRefreshedEvent
                    || event instanceof org.springframework.context.event.ContextClosedEvent)) closeConfiguration();
        });
    }

    /** Construction-time only; failed attempts cannot switch manager or retry partially configured state. */
    synchronized void configureRiskSource(G014JdbcTransactionManager manager) {
        if (configurationClosed || configurationAttempted || riskSource != null) throw unavailable();
        configurationAttempted = true;
        try {
            if (manager == null || !context.isActive() || current.get() != null || manager.commandDataSource() == canonical
                    || org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                    || org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()
                    || org.springframework.transaction.support.TransactionSynchronizationManager.hasResource(canonical)) throw unavailable();
            Object actualRegistry = context.getBean(org.springframework.modulith.events.core.EventPublicationRegistry.class);
            Object actualRepository = context.getBean(org.springframework.modulith.events.core.EventPublicationRepository.class);
            if (AopUtils.getTargetClass(actualRegistry) != registryClass || AopUtils.getTargetClass(actualRepository) != repositoryClass)
                throw unavailable();
            Class<?> riskType = inventory.eventTypes().stream().filter(type -> type.getName().equals(
                    "com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioDraftChangedEvent")).findFirst().orElseThrow(SharedNativePublicationRouting::unavailable);
            var prepared = new G014RiskNativeParticipation(manager, canonical, catalog, Set.of(riskType), context.getClassLoader());
            // The only side-effecting bind is last: alias and publisher come from this fixed graph, never the caller.
            manager.configureRiskPublication(canonical, context);
            riskSource = prepared;
        } catch (Throwable failure) { configurationClosed = true; throw unavailable(); }
    }
    private synchronized void closeConfiguration() { configurationClosed = true; }

    JdbcTemplate jdbcOperations() { return dispatcher; }
    RiskScenarioExecutor riskExecutor(){return inventory.riskExecutor();}
    void recoverRisk(){
        try{recovery.recover();}
        catch(Throwable failure){rejectCurrent();throw unavailable();}
    }
    boolean recoveryMethod(Method method){return recovery.active()&&(method.getName().equals("processIncompletePublications")||method.getName().equals("markResubmitted"));}
    void materializeRecoveryRead(Object result){Frame frame=requireFrame();if(frame.parent!=null&&frame.parent.route==Route.RECOVERY&&frame.route==Route.MIXED_READ)recovery.materialize(result);}
    boolean active() { return current.get() != null; }
    void requireInvocation(MethodInvocation call) {
        Frame frame = requireFrame();
        if (frame.target != call.getThis() || !frame.method.equals(AopUtils.getMostSpecificMethod(call.getMethod(), call.getThis().getClass())))
            throw unavailable();
    }
    void nativeReturned(MethodInvocation call) {
        requireInvocation(call);
        Frame frame = requireFrame();
        if (frame.target.getClass() == repositoryClass && frame.route!=Route.RECOVERY_NOOP && !Set.of("markProcessing", "markFailed").contains(frame.method.getName()) && !frame.sqlUsed)
            throw reject(frame);
        frame.bodyReturned = true;
    }
    Object around(MethodInvocation call, Method method, TransactionAttribute attribute) throws Throwable {
        inventory.requireReady();
        Frame parent = current.get();
        Frame frame = classify(call.getThis(), method, call.getArguments(), attribute, parent);
        current.set(frame);
        RiskStatus manual = null;
        try {
            Object result;
            if (frame.route == Route.RISK_SOURCE && frame.target.getClass() == registryClass) {
                result = riskSource.store(frame.event, () -> {
                    try { return call.proceed(); }
                    catch (Throwable failure) { throw reject(frame); }
                });
            } else {
                if (frame.route == Route.RISK_REGISTRY && attribute == null && !owned.hasOwnedTransaction()) {
                    var definition = new org.springframework.transaction.support.DefaultTransactionDefinition();
                    definition.setName(frame.target.getClass().getName() + "." + method.getName());
                    definition.setReadOnly(method.getName().startsWith("find"));
                    frame.beginIssued = true;
                    manual = newRiskStatus(frame, owned.beginNative(definition));
                }
                result = call.proceed();
                if(frame.parent!=null&&frame.parent.route==Route.RECOVERY&&frame.route==Route.MIXED_READ)recovery.readClosed();
                if (manual != null) completeRisk(manual, true);
            }
            if (frame.rejected) throw unavailable();
            return result;
        } catch (Throwable failure) {
            reject(frame);
            if (manual != null && !manual.completionSent) completeRisk(manual, false);
            throw failure;
        } finally {
            frame.live = false;
            if (parent == null) current.remove(); else current.set(parent);
        }
    }

    TransactionStatus begin(TransactionDefinition definition) {
        Frame frame = requireFrame();
        TransactionAttribute expected = frame.attribute;
        if (expected == null || definition == null
                || !(frame.target.getClass().getName() + "." + frame.method.getName()).equals(definition.getName())
                || expected.getPropagationBehavior() != definition.getPropagationBehavior()
                || expected.getIsolationLevel() != definition.getIsolationLevel()
                || expected.isReadOnly() != definition.isReadOnly() || expected.getTimeout() != definition.getTimeout()) throw reject(frame);
        if (frame.beginIssued) throw reject(frame);
        frame.beginIssued = true;
        if (frame.route == Route.RISK_SOURCE) {
            if (frame.target.getClass() != repositoryClass || !frame.method.getName().equals("create")
                    || frame.parent == null || frame.parent.route != Route.RISK_SOURCE) throw reject(frame);
            return newRiskStatus(frame, riskSource.beginCreate(definition));
        }
        if (frame.route == Route.RISK_REGISTRY) return newRiskStatus(frame, owned.beginNative(definition));
        PlatformTransactionManager manager = original.get();
        if (manager == null) throw reject(frame);
        try {
            var status = new OriginalStatus(frame, manager, manager.getTransaction(definition));
            frame.holder = org.springframework.transaction.support.TransactionSynchronizationManager.getResource(canonical);
            return status;
        } catch (RuntimeException | Error failure) { throw reject(frame); }
    }
    boolean complete(TransactionStatus status, boolean commit) {
        if (status instanceof SharedNativePublicationRouting.RiskStatus risk) {
            if (risk.issuer != this) { risk.refuse(); rejectCurrent(); throw unavailable(); }
            completeRisk(risk, commit);
            return true;
        }
        if (!(status instanceof SharedNativePublicationRouting.OriginalStatus value)) return false;
        if (value.issuer != this) { value.issuer.reject(value.frame); rejectCurrent(); throw unavailable(); }
        value.require(false);
        if (commit && !value.frame.bodyReturned) throw reject(value.frame);
        value.completionSent = true;
        if (commit && value.frame.rejected) {
            value.manager.rollback(value.delegate);
            throw unavailable();
        }
        if (commit) value.manager.commit(value.delegate); else value.manager.rollback(value.delegate);
        return true;
    }

    private Frame classify(Object target, Method method, Object[] args, TransactionAttribute attribute, Frame parent) {
        boolean registry = target.getClass() == registryClass;
        String name = method.getName();
        if(registry&&name.equals("processIncompletePublications")){
            if(parent!=null||attribute!=null||!recovery.process(args))throw unavailable();
            return new Frame(target,method,null,Route.RECOVERY,null,null,null,null);
        }
        if(!registry&&name.equals("markResubmitted")){
            if(parent==null||parent.route!=Route.RECOVERY||attribute!=null)throw unavailable();
            recovery.resubmitted(args);return new Frame(target,method,null,Route.RECOVERY_NOOP,null,null,null,parent);
        }
        if (mixed(method, registry)) {
            rejectSourceIfActive();
            if(parent!=null){
                if(parent.target.getClass()!=registryClass)throw unavailable();
                if(parent.route==Route.RECOVERY){if(registry||!name.equals("findIncompletePublications"))throw unavailable();}
                else if(parent.route!=Route.MIXED_READ||!mixedChild(parent.method.getName(),name))throw unavailable();
            }
            return new Frame(target, method, attribute, Route.MIXED_READ, null, null, null, parent);
        }
        inventory.requireWritable();
        Object event;
        PublicationTargetIdentifier listener;
        UUID completion = null;
        if (registry && name.equals("store")) {
            if (parent != null || !(args[1] instanceof Stream<?> stream)) throw unavailable();
            event = args[0];
            List<?> listeners;
            try (stream) { listeners = stream.limit(2).toList(); }
            if (listeners.size() != 1 || !(listeners.getFirst() instanceof PublicationTargetIdentifier value)) throw unavailable();
            listener = value;
            inventory.requirePair(event, listener, true);
            args[1] = listeners.stream();
        } else if (!registry && (name.equals("create") || method.getParameterTypes()[0] == TargetEventPublication.class)) {
            if (!(args[0] instanceof TargetEventPublication publication) || !publications.contains(publication.getClass())) throw unavailable();
            event = publication.getEvent(); listener = publication.getTargetIdentifier();
            inventory.requirePair(event, listener, true);
            if (parent == null || parent.target.getClass() != registryClass || (parent.route == Route.RISK_SOURCE ? parent.event != event : parent.event != event && !parent.event.equals(event))
                    || !parent.listener.equals(listener)) throw unavailable();
            if (name.equals("create")) {
                if (!parent.method.getName().equals("store")) throw unavailable();
            } else {
                if (!name.equals("markCompleted") || !parent.method.getName().equals("markCompleted")) throw unavailable();
                completion = publication.getIdentifier();
                if (completion == null) throw unavailable();
            }
        } else if (args.length >= 2 && method.getParameterTypes()[1] == PublicationTargetIdentifier.class) {
            event = args[0];
            if (!(args[1] instanceof PublicationTargetIdentifier value)) throw unavailable();
            listener = value;
            inventory.requirePair(event, listener, true);
            if (parent != null && (registry || parent.target.getClass() != registryClass
                    || !parent.method.getName().equals("markCompleted") || !name.equals("markCompleted")
                        && !name.equals("findIncompletePublicationsByEventAndTargetIdentifier")
                    || !parent.event.equals(event) || !parent.listener.equals(listener))) throw unavailable();
        } else if (!registry && (name.equals("markProcessing") || name.equals("markFailed")) && args.length == 1
                && args[0] instanceof UUID && parent != null && parent.target.getClass() == registryClass
                && parent.method.getName().equals(name)) {
            // V1 defaults are true no-ops. This route grants no SQL or UUID completion authority.
            event = parent.event; listener = parent.listener;
        } else throw unavailable();
        boolean sourceCall = registry && name.equals("store") || !registry && name.equals("create");
        Route route;
        if (inventory.risk(event)) {
            G014RiskNativeParticipation source = riskSource;
            if (source == null) throw unavailable();
            if (sourceCall) {
                if (!source.hasSourceWitness(event) || !registry && (parent == null || parent.route != Route.RISK_SOURCE
                        || parent.event != event) || registry && attribute != null) throw unavailable();
                route = Route.RISK_SOURCE;
            } else {
                // REQUIRES_NEW acknowledgement never borrows or suspends the active source window.
                source.rejectIfSourceActive();
                if (parent != null && parent.route != Route.RISK_REGISTRY) throw unavailable();
                route = Route.RISK_REGISTRY;
            }
        } else {
            rejectSourceIfActive();
            route = sourceCall ? Route.LEGACY_SOURCE : Route.LEGACY_REGISTRY;
        }
        return new Frame(target, method, attribute, route, event, listener, completion, parent);
    }

    private boolean mixed(Method method, boolean registry) {
        String name = method.getName();
        return registry ? name.equals("findIncompletePublications") && method.getParameterCount() == 0
                || name.equals("findAll") && method.getParameterCount() == 0
                || name.equals("findIncompletePublicationsOlderThan") && List.of(method.getParameterTypes()).equals(List.of(Duration.class))
                : name.equals("findIncompletePublications") && method.getParameterCount() == 0
                || name.equals("findCompletedPublications") && method.getParameterCount() == 0
                || name.equals("findIncompletePublicationsPublishedBefore") && List.of(method.getParameterTypes()).equals(List.of(Instant.class));
    }
    private boolean mixedChild(String parent, String child) {
        return switch (parent) {
            case "findIncompletePublications" -> child.equals("findIncompletePublications");
            case "findIncompletePublicationsOlderThan" -> child.equals("findIncompletePublicationsPublishedBefore");
            case "findAll" -> child.equals("findCompletedPublications");
            default -> false;
        };
    }

    private Object jdbc(Method method, Object[] args) throws Throwable {
        Frame frame = requireFrame();
        if (frame.target.getClass() != repositoryClass || frame.sqlUsed) throw unavailable();
        var parameters = List.of(method.getParameterTypes());
        RiskPublicationSqlCatalog.Kind kind;
        Object[] values;
        if (method.getName().equals("update") && parameters.equals(List.of(String.class, Object[].class))) {
            kind = RiskPublicationSqlCatalog.Kind.UPDATE; values = ((Object[]) args[1]).clone();
        } else if (method.getName().equals("query") && parameters.equals(List.of(String.class, org.springframework.jdbc.core.ResultSetExtractor.class))) {
            kind = RiskPublicationSqlCatalog.Kind.QUERY; values = new Object[0];
        } else if (method.getName().equals("query") && parameters.equals(List.of(String.class, org.springframework.jdbc.core.ResultSetExtractor.class, Object[].class))) {
            kind = RiskPublicationSqlCatalog.Kind.QUERY_ARGS; values = ((Object[]) args[2]).clone();
        } else throw unavailable();
        var operation = catalog.require((String) args[0], kind, values);
        String allowed = switch (frame.method.getName()) {
            case "create" -> "insert";
            case "markCompleted" -> frame.completion == null ? "complete-event-target" : "complete-id";
            case "findIncompletePublicationsByEventAndTargetIdentifier" -> "event-target";
            case "findIncompletePublications" -> "incomplete";
            case "findCompletedPublications" -> "completed";
            case "findIncompletePublicationsPublishedBefore" -> "incomplete-before";
            default -> throw unavailable();
        };
        if (!operation.label().equals("Risk publication " + allowed)) throw unavailable();
        if (kind == RiskPublicationSqlCatalog.Kind.UPDATE && frame.route == Route.MIXED_READ) throw unavailable();
        if (allowed.equals("insert") && (!frame.event.getClass().getName().equals(values[1]) || !frame.listener.getValue().equals(values[2]))) throw unavailable();
        if ((allowed.equals("event-target") || allowed.equals("complete-event-target")) && !frame.listener.getValue().equals(values[1])) throw unavailable();
        if (allowed.equals("complete-id") && !frame.completion.equals(values[1])) throw unavailable();
        frame.sqlUsed = true;
        if (frame.route == Route.RISK_SOURCE) {
            if (!allowed.equals("insert") || !riskSource.hasSourceWitness(frame.event)) throw reject(frame);
            try { return method.invoke(riskSource.template(), args); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        }
        if (frame.route == Route.RISK_REGISTRY) {
            try { return method.invoke(ownedTemplate, args); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        }
        if (kind != RiskPublicationSqlCatalog.Kind.UPDATE) return reads.query(operation, values,
                (org.springframework.jdbc.core.ResultSetExtractor<?>) args[1],frame.parent!=null&&frame.parent.route==Route.RECOVERY?recovery:null);
        try { return method.invoke(ordinary, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    private Frame requireFrame() {
        Frame frame = current.get();
        inventory.requireReady();
        if (frame == null || !frame.live || frame.rejected || frame.thread != Thread.currentThread()) throw unavailable();
        if (frame.holder != null && org.springframework.transaction.support.TransactionSynchronizationManager.getResource(canonical) != frame.holder)
            throw reject(frame);
        if (frame.route == Route.RISK_SOURCE && (riskSource == null || !riskSource.hasSourceWitness(frame.event))) throw reject(frame);
        return frame;
    }
    boolean allowedCompletion(UUID id) {
        Frame frame = requireFrame();
        return frame.route == Route.RISK_REGISTRY && frame.completion != null && frame.completion.equals(id);
    }
    private void rejectSourceIfActive() { G014RiskNativeParticipation source = riskSource; if (source != null) source.rejectIfSourceActive(); }
    void rejectCurrent() {
        recovery.fail();
        Frame frame = current.get();
        if (frame != null) reject(frame);
        else { owned.poisonIfActive(); rejectSourceIfActive(); }
    }
    private IllegalStateException reject(Frame frame) {
        rejectCaptured(frame);
        Frame active = current.get();
        for (Frame value = active; value != null; value = value.parent) {
            if (value == frame) {
                // Only the invocation actually being rejected may poison the current writer authority.
                owned.poisonIfActive();
                try { rejectSourceIfActive(); } catch (RuntimeException | Error ignored) { }
                break;
            }
        }
        return unavailable();
    }
    private IllegalStateException rejectCaptured(Frame frame) {
        for (Frame value = frame; value != null; value = value.parent) {
            value.rejected = true;
            if (value.holder instanceof org.springframework.jdbc.datasource.ConnectionHolder holder) holder.setRollbackOnly();
        }
        return unavailable();
    }
    private final class Frame {
        final Object target;
        final Method method;
        final TransactionAttribute attribute;
        final Route route;
        final Object event;
        final PublicationTargetIdentifier listener;
        final UUID completion;
        final Thread thread = Thread.currentThread();
        final Frame parent;
        Object holder;
        RiskStatus riskStatus;
        volatile boolean rejected;
        boolean live = true, sqlUsed, beginIssued, bodyReturned;
        Frame(Object target, Method method, TransactionAttribute attribute, Route route, Object event, PublicationTargetIdentifier listener, UUID completion, Frame parent) {
            this.target = target; this.method = method; this.attribute = attribute; this.route = route;
            this.event = event; this.listener = listener; this.completion = completion; this.parent = parent;
            this.holder = org.springframework.transaction.support.TransactionSynchronizationManager.getResource(canonical);
        }
    }
    private RiskStatus newRiskStatus(Frame frame, TransactionStatus delegate) {
        if (frame.riskStatus != null || delegate == null) throw reject(frame);
        var status = new RiskStatus(frame, delegate);
        frame.holder = org.springframework.transaction.support.TransactionSynchronizationManager.getResource(canonical);
        frame.riskStatus = status;
        return status;
    }
    private void completeRisk(RiskStatus status, boolean commit) {
        status.require(false);
        if (commit && !status.frame.bodyReturned) throw reject(status.frame);
        status.completionSent = true;
        if (status.frame.route == Route.RISK_SOURCE) {
            if (commit && !status.frame.rejected) riskSource.commit(status.delegate); else riskSource.rollback(status.delegate);
        } else {
            owned.finishNative(status.delegate, commit && !status.frame.rejected);
        }
        if (commit && status.frame.rejected) throw unavailable();
    }
    private final class RiskStatus implements TransactionStatus {
        final SharedNativePublicationRouting issuer = SharedNativePublicationRouting.this;
        final Frame frame;
        final TransactionStatus delegate;
        final Thread thread = Thread.currentThread();
        boolean completionSent;
        RiskStatus(Frame frame, TransactionStatus delegate) { this.frame = frame; this.delegate = delegate; }
        void require(boolean clean) {
            if (issuer != SharedNativePublicationRouting.this || thread != Thread.currentThread() || current.get() != frame
                    || !frame.live || frame.riskStatus != this || completionSent || clean && frame.rejected
                    || frame.holder != null && org.springframework.transaction.support.TransactionSynchronizationManager.getResource(canonical) != frame.holder)
                throw refuse();
        }
        private IllegalStateException refuse() {
            try { delegate.setRollbackOnly(); } catch (Throwable ignored) { }
            return rejectCaptured(frame);
        }
        public String getTransactionName() { require(true); return delegate.getTransactionName(); }
        public boolean hasTransaction() { require(true); return delegate.hasTransaction(); }
        public boolean isNewTransaction() { require(true); return delegate.isNewTransaction(); }
        public boolean isNested() { require(true); return delegate.isNested(); }
        public boolean isReadOnly() { require(true); return delegate.isReadOnly(); }
        public void setRollbackOnly() { require(true); delegate.setRollbackOnly(); }
        public boolean isRollbackOnly() { require(true); return delegate.isRollbackOnly(); }
        public boolean isCompleted() { require(true); return delegate.isCompleted(); }
        public boolean hasSavepoint() { require(true); return delegate.hasSavepoint(); }
        public void flush() { require(true); delegate.flush(); }
        public Object createSavepoint() { throw refuse(); }
        public void rollbackToSavepoint(Object point) { throw refuse(); }
        public void releaseSavepoint(Object point) { throw refuse(); }
    }
    private final class OriginalStatus implements TransactionStatus {
        final SharedNativePublicationRouting issuer = SharedNativePublicationRouting.this;
        final Frame frame;
        final PlatformTransactionManager manager;
        final TransactionStatus delegate;
        final Thread thread = Thread.currentThread();
        boolean completionSent;
        OriginalStatus(Frame frame, PlatformTransactionManager manager, TransactionStatus delegate) {
            this.frame = frame; this.manager = manager; this.delegate = delegate;
        }
        void require(boolean clean) {
            if (issuer != SharedNativePublicationRouting.this || thread != Thread.currentThread() || !frame.live
                    || current.get() != frame || completionSent || clean && frame.rejected) throw rejectCaptured(frame);
            if (frame.holder != null && org.springframework.transaction.support.TransactionSynchronizationManager.getResource(canonical) != frame.holder)
                throw rejectCaptured(frame);
        }

        public String getTransactionName() { require(true); return delegate.getTransactionName(); }
        public boolean hasTransaction() { require(true); return delegate.hasTransaction(); }
        public boolean isNewTransaction() { require(true); return delegate.isNewTransaction(); }
        public boolean isNested() { require(true); return delegate.isNested(); }
        public boolean isReadOnly() { require(true); return delegate.isReadOnly(); }
        public void setRollbackOnly() { require(true); delegate.setRollbackOnly(); }
        public boolean isRollbackOnly() { require(true); return delegate.isRollbackOnly(); }
        public boolean isCompleted() { require(true); return delegate.isCompleted(); }
        public boolean hasSavepoint() { require(true); return delegate.hasSavepoint(); }
        public void flush() { require(true); delegate.flush(); }
        public Object createSavepoint() { require(true); throw rejectCaptured(frame); }
        public void rollbackToSavepoint(Object point) { require(true); throw rejectCaptured(frame); }
        public void releaseSavepoint(Object point) { require(true); throw rejectCaptured(frame); }
    }
    private static IllegalStateException unavailable() { return new IllegalStateException("Shared native publication unavailable"); }
}

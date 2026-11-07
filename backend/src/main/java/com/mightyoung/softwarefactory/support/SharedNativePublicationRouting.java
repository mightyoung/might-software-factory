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
    private enum Route { LEGACY_SOURCE, LEGACY_REGISTRY, MIXED_READ }
    private final DataSource canonical;
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
            Set<Class<?>> publications) {
        if (ordinary == null || ordinary.getDataSource() != canonical) throw unavailable();
        this.canonical = canonical;
        this.original = original; this.ordinary = ordinary; this.inventory = inventory; this.catalog = catalog;
        this.registryClass = registryClass; this.repositoryClass = repositoryClass; this.publications = publications;
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
    }

    JdbcTemplate jdbcOperations() { return dispatcher; }
    boolean active() { return current.get() != null; }
    void requireInvocation(MethodInvocation call) {
        Frame frame = requireFrame();
        if (frame.target != call.getThis() || !frame.method.equals(AopUtils.getMostSpecificMethod(call.getMethod(), call.getThis().getClass())))
            throw unavailable();
    }
    void nativeReturned(MethodInvocation call) {
        requireInvocation(call);
        Frame frame = requireFrame();
        if (frame.target.getClass() == repositoryClass && !Set.of("markProcessing", "markFailed").contains(frame.method.getName()) && !frame.sqlUsed)
            throw reject(frame);
        frame.bodyReturned = true;
    }
    Object around(MethodInvocation call, Method method, TransactionAttribute attribute) throws Throwable {
        inventory.requireReady();
        Frame parent = current.get();
        Frame frame = classify(call.getThis(), method, call.getArguments(), attribute, parent);
        current.set(frame);
        try {
            Object result = call.proceed();
            if (frame.rejected) throw unavailable();
            return result;
        } catch (Throwable failure) { reject(frame); throw failure; }
        finally {
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
        PlatformTransactionManager manager = original.get();
        if (manager == null) throw reject(frame);
        try {
            var status = new OriginalStatus(frame, manager, manager.getTransaction(definition));
            frame.holder = org.springframework.transaction.support.TransactionSynchronizationManager.getResource(canonical);
            return status;
        } catch (RuntimeException | Error failure) { throw reject(frame); }
    }
    boolean complete(TransactionStatus status, boolean commit) {
        if (!(status instanceof SharedNativePublicationRouting.OriginalStatus value)) return false;
        if (value.issuer != this) { rejectCurrent(); throw unavailable(); }
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
        if (mixed(method, registry)) {
            if (parent != null && (parent.route != Route.MIXED_READ || parent.target.getClass() != registryClass
                    || !mixedChild(parent.method.getName(), name))) throw unavailable();
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
            if (parent == null || parent.target.getClass() != registryClass || parent.event != event && !parent.event.equals(event)
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
        // Source authority is not installed by this slice. A registered risk listener does not supply that authority.
        if (inventory.risk(event)) throw unavailable();
        Route route = registry && name.equals("store") || !registry && name.equals("create") ? Route.LEGACY_SOURCE : Route.LEGACY_REGISTRY;
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
        if (kind != RiskPublicationSqlCatalog.Kind.UPDATE) return reads.query(operation, values,
                (org.springframework.jdbc.core.ResultSetExtractor<?>) args[1]);
        try { return method.invoke(ordinary, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    private Frame requireFrame() {
        Frame frame = current.get();
        inventory.requireReady();
        if (frame == null || !frame.live || frame.rejected || frame.thread != Thread.currentThread()) throw unavailable();
        if (frame.holder != null && org.springframework.transaction.support.TransactionSynchronizationManager.getResource(canonical) != frame.holder)
            throw reject(frame);
        return frame;
    }
    void rejectCurrent() { Frame frame = current.get(); if (frame != null) reject(frame); }
    private IllegalStateException reject(Frame frame) {
        for (Frame value = frame; value != null; value = value.parent) {
            value.rejected = true;
            // Never mark an unrelated replacement holder. The captured original is the only legitimate owner.
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
        volatile boolean rejected;
        boolean live = true, sqlUsed, beginIssued, bodyReturned;
        Frame(Object target, Method method, TransactionAttribute attribute, Route route, Object event, PublicationTargetIdentifier listener, UUID completion, Frame parent) {
            this.target = target; this.method = method; this.attribute = attribute; this.route = route;
            this.event = event; this.listener = listener; this.completion = completion; this.parent = parent;
            this.holder = org.springframework.transaction.support.TransactionSynchronizationManager.getResource(canonical);
        }
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
                    || current.get() != frame || completionSent || clean && frame.rejected) throw reject(frame);
            if (frame.holder != null && org.springframework.transaction.support.TransactionSynchronizationManager.getResource(canonical) != frame.holder)
                throw reject(frame);
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
        public Object createSavepoint() { require(true); throw reject(frame); }
        public void rollbackToSavepoint(Object point) { require(true); throw reject(frame); }
        public void releaseSavepoint(Object point) { require(true); throw reject(frame); }
    }
    private static IllegalStateException unavailable() { return new IllegalStateException("Shared native publication unavailable"); }
}

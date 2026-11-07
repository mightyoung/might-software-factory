package com.mightyoung.softwarefactory.support;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.core.Ordered;
import org.springframework.modulith.events.jdbc.JdbcRepositorySettings;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.TransactionManagementConfigurer;
import org.springframework.transaction.interceptor.TransactionAttribute;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.DefaultTransactionDefinition;

/**
 * Explicit composition only. No bean, source-publication borrowing, capture or recovery is activated.
 * The two advisors must be infrastructure beans around the native transaction interceptor.
 */
public final class RiskNativePublicationTransactions {
    private static final String SAFE = "Risk native publication unavailable";
    private final Class<?> registryClass;
    private final Class<?> repositoryClass;
    private final Set<Method> registryMethods;
    private final Set<Method> repositoryMethods;
    private final Set<Class<?>> eventTypes;
    private final Set<Class<?>> publicationTypes;
    private static final String LISTENER = "risk-scenario-draft-change";
    private final Supplier<?> registry;
    private final Supplier<?> repository;
    private final Supplier<PlatformTransactionManager> original;
    private final RiskJdbcTransactionOwner owner;
    private final RiskPublicationJdbcTemplate template;
    private final AnnotationTransactionAttributeSource attributes = new AnnotationTransactionAttributeSource();
    private final ThreadLocal<Invocation> current = new ThreadLocal<>();
    private final Advisor outer;
    private final Advisor inner;
    private final PlatformTransactionManager router = new Router();

    public RiskNativePublicationTransactions(DataSource canonical, Supplier<PlatformTransactionManager> original,
            Supplier<?> registry, Supplier<?> repository, JdbcRepositorySettings settings, Environment environment,
            Set<Class<?>> eventTypes, ClassLoader loader) {
        try {
            if (canonical == null || original == null || registry == null || repository == null) throw unavailable();
            this.eventTypes = Set.copyOf(eventTypes);
            this.original = original; this.registry = registry; this.repository = repository;
            registryClass = Class.forName("org.springframework.modulith.events.core.DefaultEventPublicationRegistry", false, loader);
            repositoryClass = Class.forName("org.springframework.modulith.events.jdbc.JdbcEventPublicationRepository", false, loader);
            var target = org.springframework.modulith.events.core.PublicationTargetIdentifier.class;
            registryMethods = Set.of(registryClass.getMethod("store", Object.class, java.util.stream.Stream.class),
                    registryClass.getMethod("findIncompletePublications"), registryClass.getMethod("findIncompletePublicationsOlderThan", java.time.Duration.class),
                    registryClass.getMethod("findAll"), registryClass.getMethod("markProcessing", Object.class, target),
                    registryClass.getMethod("markCompleted", Object.class, target), registryClass.getMethod("markFailed", Object.class, target));
            repositoryMethods = Set.of(repositoryClass.getMethod("create", org.springframework.modulith.events.core.TargetEventPublication.class),
                    repositoryClass.getMethod("markCompleted", Object.class, target, java.time.Instant.class),
                    repositoryClass.getMethod("markCompleted", org.springframework.modulith.events.core.TargetEventPublication.class, java.time.Instant.class),
                    repositoryClass.getMethod("markProcessing", java.util.UUID.class), repositoryClass.getMethod("markFailed", java.util.UUID.class),
                    repositoryClass.getMethod("findIncompletePublicationsByEventAndTargetIdentifier", Object.class, target),
                    repositoryClass.getMethod("findCompletedPublications"), repositoryClass.getMethod("findIncompletePublications"),
                    repositoryClass.getMethod("findIncompletePublicationsPublishedBefore", java.time.Instant.class));
            publicationTypes = Set.of(Class.forName("org.springframework.modulith.events.core.DefaultEventPublication", false, loader),
                    Class.forName("org.springframework.modulith.events.jdbc.JdbcEventPublicationRepository$JdbcEventPublication", false, loader));
            owner = RiskJdbcTransactionOwner.create(canonical);
            template = owner.template(new RiskPublicationSqlCatalog(settings, environment), eventTypes, loader, this::allowedCompletion);
            outer = advisor((MethodInterceptor) this::around, Ordered.HIGHEST_PRECEDENCE + 20);
            inner = advisor((MethodInterceptor) invocation -> {
                requireInvocation(invocation);
                try { return invocation.proceed(); }
                catch (RiskJdbcTransactionOwner.Failure failure) { throw failure; }
                catch (Throwable failure) { owner.poisonIfActive(); throw unavailable(); }
            }, Ordered.LOWEST_PRECEDENCE);
        } catch (RuntimeException | Error | ReflectiveOperationException failure) { throw unavailable(); }
    }
    public RiskPublicationJdbcTemplate template() { return template; }
    public Advisor outerAdvisor() { return outer; }
    public Advisor sanitizingAdvisor() { return inner; }
    public TransactionManagementConfigurer transactionConfigurer() { return () -> router; }

    /** Repository-local inner bean only; the existing global serializer remains unchanged. */
    public org.springframework.modulith.events.core.EventSerializer serializer(org.springframework.modulith.events.core.EventSerializer delegate) {
        if (delegate == null) throw unavailable();
        return new org.springframework.modulith.events.core.EventSerializer() {
            @Override public Object serialize(Object event) {
                try {
                    requireEvent(event);
                    Object serialized = delegate.serialize(event);
                    if (serialized == null) throw unavailable();
                    // Native JDBC consumes serialize(...).toString(); perform that exact conversion inside this cut.
                    String value = serialized.toString();
                    if (value == null) throw unavailable();
                    return value;
                } catch (Throwable failure) { owner.poisonIfActive(); throw unavailable(); }
            }
            @Override public <T> T deserialize(Object serialized, Class<T> type) {
                try {
                    if (type == null || !eventTypes.contains(type)) throw unavailable();
                    T event = delegate.deserialize(serialized, type);
                    if (event == null || event.getClass() != type) throw unavailable();
                    return event;
                } catch (Throwable failure) { owner.poisonIfActive(); throw unavailable(); }
            }
        };
    }

    private Advisor advisor(MethodInterceptor advice, int order) {
        var pointcut = new StaticMethodMatcherPointcut() {
            @Override public boolean matches(Method method, Class<?> targetClass) {
                return (targetClass == registryClass || targetClass == repositoryClass)
                        && method.getDeclaringClass() != Object.class;
            }
        };
        var advisor = new DefaultPointcutAdvisor(pointcut, advice);
        advisor.setOrder(order);
        return advisor;
    }
    /** Immutable outcome facts; construction remains inside the owned transaction boundary. */
    public static class Failure extends org.springframework.transaction.TransactionException {
        private final String outcome;
        private final String cleanup;
        Failure(String outcome, String cleanup) {
            super("Risk native transaction unavailable [" + outcome + "," + cleanup + "]");
            this.outcome = outcome; this.cleanup = cleanup;
        }
        public final String transactionOutcome() { return outcome; }
        public final String cleanupOutcome() { return cleanup; }
    }
    private Object around(MethodInvocation call) throws Throwable {
        try { return aroundNative(call); }
        catch (RiskJdbcTransactionOwner.Failure failure) { throw failure; }
        catch (Throwable failure) { owner.poisonIfActive(); throw unavailable(); }
    }
    private Object aroundNative(MethodInvocation call) throws Throwable {
        Class<?> targetClass = call.getThis() == null ? null : call.getThis().getClass();
        Object proxy = (targetClass == registryClass ? registry : repository).get();
        if (!(proxy instanceof Advised advised) || !advised.getTargetSource().isStatic()
                || advised.getTargetSource().getTarget() != call.getThis()) throw unavailable();
        int outerIndex = -1, innerIndex = -1, transactionIndex = -1, transactionCount = 0;
        Advisor[] advisors = advised.getAdvisors();
        for (int i = 0; i < advisors.length; i++) {
            if (advisors[i] == outer) outerIndex = i;
            if (advisors[i] == inner) innerIndex = i;
            if (advisors[i].getAdvice() instanceof TransactionInterceptor) { transactionIndex = i; transactionCount++; }
        }
        if (outerIndex < 0 || innerIndex <= outerIndex || transactionCount != 1
                || transactionIndex <= outerIndex || transactionIndex >= innerIndex
                || !(advisors[transactionIndex] instanceof Ordered ordered) || ordered.getOrder() >= Ordered.LOWEST_PRECEDENCE) throw unavailable();
        Method method = AopUtils.getMostSpecificMethod(call.getMethod(), targetClass);
        if (!(targetClass == registryClass ? registryMethods : repositoryMethods).contains(method)) throw unavailable();
        Invocation previous = current.get();
        Validation validation = validateArguments(method, call.getArguments(), previous);
        TransactionAttribute attribute = attributes.getTransactionAttribute(method, targetClass);
        Invocation invocation = new Invocation(call.getThis(), method, attribute, validation);
        current.set(invocation);
        TransactionStatus manual = null;
        boolean completionSent = false;
        try {
            if (attribute == null && !owner.hasOwnedTransaction()) {
                var definition = new DefaultTransactionDefinition();
                definition.setName(targetClass.getName() + "." + method.getName());
                definition.setReadOnly(!(method.getName().equals("store") || method.getName().equals("create") || method.getName().startsWith("mark")));
                manual = owner.beginNative(definition);
            }
            Object result;
            try { result = call.proceed(); }
            catch (Throwable failure) {
                if (manual != null) {
                    completionSent = true;
                    owner.finishNative(manual, false);
                }
                if (failure instanceof RiskJdbcTransactionOwner.Failure safe) throw safe;
                throw unavailable();
            }
            if (manual != null) {
                completionSent = true;
                owner.finishNative(manual, true);
            }
            return result;
        } finally {
            // A completion exception is never followed by a compensating rollback.
            try { if (manual != null && !completionSent) owner.finishNative(manual, false); }
            finally { if (previous == null) current.remove(); else current.set(previous); }
        }
    }
    private void requireEvent(Object event) {
        if (event == null || !eventTypes.contains(event.getClass())) throw unavailable();
    }
    private void requireTarget(Object target) {
        if (target == null || target.getClass() != org.springframework.modulith.events.core.PublicationTargetIdentifier.class
                || !LISTENER.equals(((org.springframework.modulith.events.core.PublicationTargetIdentifier) target).getValue())) throw unavailable();
    }
    private Validation requirePublication(Object publication) {
        if (!(publication instanceof org.springframework.modulith.events.core.TargetEventPublication value)
                || !publicationTypes.contains(publication.getClass())) throw unavailable();
        Object event = value.getEvent();
        requireEvent(event); requireTarget(value.getTargetIdentifier());
        java.util.UUID id = value.getIdentifier();
        if (id == null) throw unavailable();
        return new Validation(event, id);
    }
    private record Validation(Object event, java.util.UUID completionId) {}
    private boolean allowedCompletion(java.util.UUID id) {
        Invocation invocation = current.get();
        return invocation != null && invocation.thread == Thread.currentThread() && invocation.validation != null
                && id.equals(invocation.validation.completionId());
    }
    private Validation validateArguments(Method method, Object[] arguments, Invocation previous) {
        if (method.getName().equals("store")) {
            requireEvent(arguments[0]);
            if (!(arguments[1] instanceof java.util.stream.Stream<?> stream)) throw unavailable();
            java.util.List<?> targets;
            try (stream) { targets = stream.limit(2).toList(); }
            if (targets.size() != 1) throw unavailable();
            requireTarget(targets.getFirst());
            // Only this immutable bounded inventory reaches native DEBUG logging.
            arguments[1] = targets.stream();
            return new Validation(arguments[0], null);
        } else if (method.getName().equals("create") || arguments.length > 0
                && method.getParameterTypes()[0] == org.springframework.modulith.events.core.TargetEventPublication.class) {
            Validation validation = requirePublication(arguments[0]);
            if (method.getName().equals("markCompleted")) {
                if (previous == null || previous.target.getClass() != registryClass
                        || !previous.method.equals(registryClassMethod("markCompleted")) || previous.validation == null
                        || !validation.event().equals(previous.validation.event())) throw unavailable();
            }
            return method.getName().equals("markCompleted") ? validation : new Validation(validation.event(), null);
        } else if (arguments.length >= 2 && method.getParameterTypes()[1] == org.springframework.modulith.events.core.PublicationTargetIdentifier.class) {
            requireEvent(arguments[0]); requireTarget(arguments[1]);
            return new Validation(arguments[0], null);
        }
        return null;
    }
    private Method registryClassMethod(String name) {
        try { return registryClass.getMethod(name, Object.class, org.springframework.modulith.events.core.PublicationTargetIdentifier.class); }
        catch (ReflectiveOperationException failure) { throw unavailable(); }
    }
    private Invocation requireInvocation(MethodInvocation call) {
        Invocation invocation = current.get();
        if (invocation == null || invocation.thread != Thread.currentThread() || invocation.target != call.getThis()
                || !invocation.method.equals(AopUtils.getMostSpecificMethod(call.getMethod(), call.getThis().getClass())))
            throw unavailable();
        return invocation;
    }
    private static final class Invocation {
        final Object target;
        final Method method;
        final TransactionAttribute attribute;
        final Validation validation;
        final Thread thread = Thread.currentThread();
        Invocation(Object target, Method method, TransactionAttribute attribute, Validation validation) {
            this.target = target; this.method = method; this.attribute = attribute; this.validation = validation;
        }
    }
    private record RoutedStatus(PlatformTransactionManager manager, TransactionStatus delegate) implements TransactionStatus {
        public String getTransactionName() { return delegate.getTransactionName(); }
        public boolean hasTransaction() { return delegate.hasTransaction(); }
        public boolean isNewTransaction() { return delegate.isNewTransaction(); }
        public boolean isNested() { return delegate.isNested(); }
        public boolean isReadOnly() { return delegate.isReadOnly(); }
        public void setRollbackOnly() { delegate.setRollbackOnly(); }
        public boolean isRollbackOnly() { return delegate.isRollbackOnly(); }
        public boolean isCompleted() { return delegate.isCompleted(); }
        public boolean hasSavepoint() { return delegate.hasSavepoint(); }
        public void flush() { delegate.flush(); }
        public Object createSavepoint() { return delegate.createSavepoint(); }
        public void rollbackToSavepoint(Object point) { delegate.rollbackToSavepoint(point); }
        public void releaseSavepoint(Object point) { delegate.releaseSavepoint(point); }
    }
    private final class Router implements PlatformTransactionManager {
        @Override public TransactionStatus getTransaction(TransactionDefinition definition) {
            Invocation invocation = current.get();
            if (invocation == null) {
                String name = definition == null ? null : definition.getName();
                if (name != null && (name.startsWith(registryClass.getName() + ".") || name.startsWith(repositoryClass.getName() + "."))) throw unavailable();
                PlatformTransactionManager delegate = original.get();
                if (delegate == null || delegate == this) throw unavailable();
                return new RoutedStatus(delegate, delegate.getTransaction(definition));
            }
            TransactionAttribute expected = invocation.attribute;
            if (expected == null || definition == null || invocation.thread != Thread.currentThread()
                    || !(invocation.target.getClass().getName() + "." + invocation.method.getName()).equals(definition.getName())
                    || definition.getPropagationBehavior() != expected.getPropagationBehavior()
                    || definition.isReadOnly() != expected.isReadOnly()
                    || definition.getIsolationLevel() != expected.getIsolationLevel()
                    || definition.getTimeout() != expected.getTimeout()) throw unavailable();
            return owner.beginNative(definition);
        }
        @Override public void commit(TransactionStatus status) {
            if (status instanceof RoutedStatus routed) routed.manager.commit(routed.delegate);
            else owner.finishNative(status, true);
        }
        @Override public void rollback(TransactionStatus status) {
            if (status instanceof RoutedStatus routed) routed.manager.rollback(routed.delegate);
            else owner.finishNative(status, false);
        }
    }
    private static IllegalStateException unavailable() { return new IllegalStateException(SAFE); }
}

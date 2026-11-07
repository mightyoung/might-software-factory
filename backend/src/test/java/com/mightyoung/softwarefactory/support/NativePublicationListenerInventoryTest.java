package com.mightyoung.softwarefactory.support;

import com.mightyoung.softwarefactory.dualprevention.FindingWorkflowEventListener;
import com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationChangedEvent;
import com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationEventListener;
import com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceIngestionWakeListener;
import com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceQuarantinedEvent;
import com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowChangedEvent;
import com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioDraftChangedEvent;
import com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioDraftChangedListener;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.modulith.events.core.PublicationTargetIdentifier;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalApplicationListenerMethodAdapter;
import org.springframework.transaction.event.TransactionalEventListener;
import static org.assertj.core.api.Assertions.assertThat;

/** Actual production-method metadata only: no listener instance, bean registration or consumption proof. */
class NativePublicationListenerInventoryTest {
    private static final String NATIVE_REGISTRY = "org.springframework.modulith.events.core.DefaultEventPublicationRegistry";
    private static final String NATIVE_REPOSITORY = "org.springframework.modulith.events.jdbc.JdbcEventPublicationRepository";
    private record Binding(String beanName, Class<?> listener, String method, Class<?> event,
            String observedId, boolean explicitId, boolean requiresNew) {
        @Override public String toString() { return beanName; }
    }

    private static Stream<Binding> bindings() {
        return Stream.of(
                new Binding("findingWorkflowEventListener", FindingWorkflowEventListener.class, "record", FindingWorkflowChangedEvent.class,
                        "com.mightyoung.softwarefactory.dualprevention.FindingWorkflowEventListener.record(com.mightyoung.softwarefactory.dualprevention.finding.workflow.FindingWorkflowChangedEvent)", false, true),
                new Binding("assetFoundationEventListener", AssetFoundationEventListener.class, "record", AssetFoundationChangedEvent.class,
                        "asset-foundation-change-trace", true, true),
                new Binding("findingEvidenceIngestionWakeListener", FindingEvidenceIngestionWakeListener.class, "wake", FindingEvidenceQuarantinedEvent.class,
                        "com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceIngestionWakeListener.wake(com.mightyoung.softwarefactory.dualprevention.finding.evidenceupload.FindingEvidenceQuarantinedEvent)", false, true),
                new Binding("riskScenarioDraftChangedListener", RiskScenarioDraftChangedListener.class, "capture", RiskScenarioDraftChangedEvent.class,
                        "risk-scenario-draft-change", true, false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bindings")
    void actualSpringAdapterDiscoversTheProductionEventListenerContract(Binding binding) throws Exception {
        Method method = binding.listener().getMethod(binding.method(), binding.event());
        assertThat(method.getParameterTypes()).containsExactly(binding.event());
        assertThat(method.getReturnType()).isEqualTo(void.class);
        assertThat(Modifier.isFinal(binding.listener().getModifiers())).isFalse();
        assertThat(AnnotatedElementUtils.findMergedAnnotation(method, Async.class)).isNotNull();

        var adapter = new TransactionalApplicationListenerMethodAdapter(binding.beanName(), binding.listener(), method);
        // The ID is discovered by Spring, not supplied as an adapter argument or derived by a hand-written formula.
        assertThat(adapter.getListenerId()).isEqualTo(binding.observedId());
        assertThat(adapter.getTransactionPhase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(adapter.supportsEventType(payload(binding.event()))).isTrue();
        bindings().filter(other -> other.event() != binding.event())
                .forEach(other -> assertThat(adapter.supportsEventType(payload(other.event()))).isFalse());

        var annotation = AnnotatedElementUtils.findMergedAnnotation(method, TransactionalEventListener.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.id().isEmpty()).isEqualTo(!binding.explicitId());
        if (binding.explicitId()) assertThat(adapter.getListenerId()).isEqualTo(annotation.id());

        var transaction = new AnnotationTransactionAttributeSource().getTransactionAttribute(method, binding.listener());
        if (binding.requiresNew()) {
            assertThat(transaction).isNotNull();
            assertThat(transaction.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            assertThat(transaction.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_DEFAULT);
            assertThat(transaction.getTimeout()).isEqualTo(TransactionDefinition.TIMEOUT_DEFAULT);
            assertThat(transaction.isReadOnly()).isFalse();
        } else {
            assertThat(transaction).isNull();
        }
    }

    private static ResolvableType payload(Class<?> eventType) {
        return ResolvableType.forClassWithGenerics(PayloadApplicationEvent.class, eventType);
    }

    private record NativeRead(String targetClass, String method, boolean requiredReadOnly, Class<?>... parameters) {
        @Override public String toString() { return targetClass + "." + method; }
    }

    private static Stream<NativeRead> nativeReads() {
        return Stream.of(
                new NativeRead(NATIVE_REGISTRY, "findIncompletePublications", false),
                new NativeRead(NATIVE_REGISTRY, "findIncompletePublicationsOlderThan", false, Duration.class),
                new NativeRead(NATIVE_REGISTRY, "findAll", false),
                new NativeRead(NATIVE_REPOSITORY, "findIncompletePublications", true),
                new NativeRead(NATIVE_REPOSITORY, "findCompletedPublications", false),
                new NativeRead(NATIVE_REPOSITORY, "findIncompletePublicationsPublishedBefore", false, Instant.class),
                new NativeRead(NATIVE_REPOSITORY, "findIncompletePublicationsByEventAndTargetIdentifier", true,
                        Object.class, PublicationTargetIdentifier.class));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nativeReads")
    void actualNativeReadMetadataPreservesTheDifferenceBetweenRequiredReadOnlyAndNoTransaction(NativeRead binding) throws Exception {
        Class<?> target = Class.forName(binding.targetClass());
        Method method = target.getMethod(binding.method(), binding.parameters());
        assertThat(method.getDeclaringClass()).isSameAs(target);
        var transaction = new AnnotationTransactionAttributeSource().getTransactionAttribute(method, target);
        if (binding.requiredReadOnly()) {
            assertThat(transaction).isNotNull();
            assertThat(transaction.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
            assertThat(transaction.isReadOnly()).isTrue();
            assertThat(transaction.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_DEFAULT);
            assertThat(transaction.getTimeout()).isEqualTo(TransactionDefinition.TIMEOUT_DEFAULT);
        } else {
            // NONE is real native metadata, not a simulated attribute or permission to create an owned transaction.
            assertThat(transaction).isNull();
        }
    }
}

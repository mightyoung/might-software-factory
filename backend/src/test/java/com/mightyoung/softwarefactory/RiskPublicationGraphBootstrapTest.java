package com.mightyoung.softwarefactory;

import java.util.List;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.modulith.events.config.EventPublicationAutoConfiguration;
import org.springframework.modulith.events.core.EventPublicationRegistry;
import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.modulith.events.core.EventSerializer;
import org.springframework.modulith.events.core.PublicationTargetIdentifier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Native legacy factory injection prototype only. The dedicated template is a real JDBC spy,
 * not a safe execution boundary; no production configuration or listener is activated here.
 */
class RiskPublicationGraphBootstrapTest {
    private static final String REPOSITORY_BEAN = "jdbcEventPublicationRepository";
    private static final String DEDICATED_JDBC = "riskPublicationJdbc";
    private static final String NATIVE_REPOSITORY =
            "org.springframework.modulith.events.jdbc.JdbcEventPublicationRepository";

    @Test
    void explicitFactoryArgumentRoutesNativeRegistryThroughDedicatedCanonicalTemplate() throws Exception {
        verifyGraph(true);
    }

    @Test
    void unboundDedicatedBeanDoesNotReplaceThePrimaryTemplate() throws Exception {
        verifyGraph(false);
    }

    private static void verifyGraph(boolean bindFactoryArgument) throws Exception {
        EmbeddedDatabase source = new EmbeddedDatabaseBuilder().generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2).build();
        try {
            // Minimal original V1 shape for this H2 composition test, not production migration proof.
            new JdbcTemplate(source).execute("""
                    create table EVENT_PUBLICATION (
                        ID uuid primary key,
                        EVENT_TYPE varchar(512) not null,
                        LISTENER_ID varchar(512) not null,
                        PUBLICATION_DATE timestamp not null,
                        SERIALIZED_EVENT varchar(4096) not null,
                        COMPLETION_DATE timestamp
                    )
                    """);
            var primary = spy(new JdbcTemplate(source));
            var dedicated = spy(new JdbcTemplate(source));
            var serializer = mock(EventSerializer.class);
            var event = new ProbeEvent("composition-probe");
            when(serializer.serialize(event)).thenReturn("serialized-composition-probe");
            when(serializer.deserialize("serialized-composition-probe", ProbeEvent.class)).thenReturn(event);

            var runner = new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            Class.forName("org.springframework.modulith.events.jdbc.JdbcEventPublicationAutoConfiguration"),
                            EventPublicationAutoConfiguration.class))
                    .withPropertyValues("spring.modulith.events.jdbc.schema-initialization.enabled=false")
                    .withInitializer(context -> {
                        try {
                            for (var properties : new YamlPropertySourceLoader().load(
                                    "application", new ClassPathResource("application.yml"))) {
                                context.getEnvironment().getPropertySources().addLast(properties);
                            }
                        } catch (java.io.IOException failure) {
                            throw new IllegalStateException(failure);
                        }
                    })
                    .withBean(DataSource.class, () -> source)
                    .withBean("primaryJdbc", JdbcTemplate.class, () -> primary, definition -> definition.setPrimary(true))
                    .withBean(DEDICATED_JDBC, JdbcTemplate.class, () -> dedicated)
                    .withBean(EventSerializer.class, () -> serializer);
            if (bindFactoryArgument) runner = runner.withUserConfiguration(FactoryArgumentBinding.class);
            runner.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBeansOfType(DataSource.class)).hasSize(1);
                assertThat(context.getBean(DataSource.class)).isSameAs(source);
                assertThat(primary.getDataSource()).isSameAs(source);
                assertThat(dedicated.getDataSource()).isSameAs(source);
                assertThat(context.getEnvironment().getProperty(
                        "spring.modulith.events.jdbc.use-legacy-structure")).isEqualTo("true");
                assertThat(context.getBeansOfType(EventPublicationRepository.class)).hasSize(1);
                assertThat(AopUtils.getTargetClass(context.getBean(EventPublicationRepository.class)).getName())
                        .isEqualTo(NATIVE_REPOSITORY);
                assertThat(context.getBeansOfType(EventPublicationRegistry.class)).hasSize(1);
                var registry = context.getBean(EventPublicationRegistry.class);
                assertThat(AopUtils.getTargetClass(registry).getName())
                        .isEqualTo("org.springframework.modulith.events.core.DefaultEventPublicationRegistry");

                clearInvocations(primary, dedicated);
                var target = PublicationTargetIdentifier.of("risk-scenario-draft-change");
                var stored = registry.store(event, Stream.of(target));
                assertThat(stored).hasSize(1);
                var incomplete = registry.findIncompletePublications();
                assertThat(incomplete).singleElement().satisfies(publication -> {
                    assertThat(publication.getIdentifier()).isEqualTo(stored.iterator().next().getIdentifier());
                    assertThat(publication.getTargetIdentifier()).isEqualTo(target);
                    assertThat(publication.getEvent()).isEqualTo(event);
                });
                JdbcTemplate selected = bindFactoryArgument ? dedicated : primary;
                JdbcTemplate unselected = bindFactoryArgument ? primary : dedicated;
                assertThat(jdbcMethods(selected)).contains("update", "query");
                verifyNoInteractions(unselected);
            });
        } finally {
            source.shutdown();
        }
    }

    private static List<String> jdbcMethods(JdbcTemplate template) {
        return mockingDetails(template).getInvocations().stream()
                .map(invocation -> invocation.getMethod().getName()).toList();
    }

    @Configuration(proxyBeanMethods = false)
    static class FactoryArgumentBinding {
        @Bean
        static BeanFactoryPostProcessor bindNativeFactoryArgument() {
            return factory -> {
                var definition = factory.getBeanDefinition(REPOSITORY_BEAN);
                if (!"jdbcEventPublicationRepository".equals(definition.getFactoryMethodName())
                        || definition.getFactoryBeanName() == null
                        || !definition.getConstructorArgumentValues().isEmpty()) {
                    throw new IllegalStateException("Unexpected native publication factory");
                }
                definition.getConstructorArgumentValues().addIndexedArgumentValue(
                        0, new RuntimeBeanReference(DEDICATED_JDBC));
            };
        }
    }

    record ProbeEvent(String marker) {}
}

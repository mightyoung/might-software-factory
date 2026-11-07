package com.mightyoung.softwarefactory.support;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.core.EventSerializer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.modulith.events.jdbc.JdbcRepositorySettings;
import static com.mightyoung.softwarefactory.support.RiskPublicationSqlCatalog.Kind;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Original native strings are also replayed through actual auto-configuration in the companion template test. */
class RiskPublicationSqlCatalogTest {
    @Test void freezesSevenNativeShapesWithoutBorrowingAConnection() throws Exception {
        fixture(true).run(context -> {
            clearInvocations(context.getBean(javax.sql.DataSource.class));
            var catalog = new RiskPublicationSqlCatalog(context.getBean(JdbcRepositorySettings.class),
                    context.getEnvironment());
            for (var sample : samples("")) {
                assertThat(catalog.require(sample.sql(), sample.kind(), sample.arguments()).sql()).isEqualTo(sample.sql());
            }
            verifyNoInteractions(context.getBean(javax.sql.DataSource.class));
        });
    }

    @Test void publicQualificationIsExactAndOtherSchemasFailClosed() throws Exception {
        fixture(true).withPropertyValues("spring.modulith.events.jdbc.schema=public").run(context -> {
            var catalog = new RiskPublicationSqlCatalog(context.getBean(JdbcRepositorySettings.class),
                    context.getEnvironment());
            for (var sample : samples("public.")) assertThat(catalog.require(sample.sql(), sample.kind(), sample.arguments()).sql()).isEqualTo(sample.sql());
            assertThatThrownBy(() -> catalog.require(samples("").getFirst().sql(), Kind.UPDATE, samples("").getFirst().arguments()))
                    .isInstanceOf(IllegalStateException.class);
        });
        for (String schema : List.of("other", "PUBLIC", "public;PRIVATE")) {
            fixture(true).withPropertyValues("spring.modulith.events.jdbc.schema=" + schema).run(context -> {
                assertThatThrownBy(() -> new RiskPublicationSqlCatalog(context.getBean(JdbcRepositorySettings.class),
                        context.getEnvironment())).isInstanceOf(IllegalStateException.class)
                        .hasMessage("Risk publication SQL catalog unavailable").hasNoCause();
            });
        }
    }

    @Test void rejectsV2UnsupportedDatabaseAndNonUpdateCompletion() throws Exception {
        fixture(false).run(context ->
                assertThatThrownBy(() -> new RiskPublicationSqlCatalog(context.getBean(JdbcRepositorySettings.class),
                        context.getEnvironment())).isInstanceOf(IllegalStateException.class));
        fixture(true, "H2").run(context ->
                assertThatThrownBy(() -> new RiskPublicationSqlCatalog(context.getBean(JdbcRepositorySettings.class),
                        context.getEnvironment())).isInstanceOf(IllegalStateException.class));
        for (String mode : List.of("DELETE", "ARCHIVE")) {
            fixture(true).withPropertyValues("spring.modulith.events.completion-mode=" + mode).run(context ->
                    assertThatThrownBy(() -> new RiskPublicationSqlCatalog(context.getBean(JdbcRepositorySettings.class),
                            context.getEnvironment())).isInstanceOf(IllegalStateException.class));
        }
    }

    @Test void missingOrFalseLegacyDeclarationCannotConstructCatalog() throws Exception {
        fixture(true).run(context -> {
            for (var environment : List.of(new org.springframework.mock.env.MockEnvironment(),
                    new org.springframework.mock.env.MockEnvironment().withProperty("spring.modulith.events.jdbc.use-legacy-structure", "false"))) {
                assertThatThrownBy(() -> new RiskPublicationSqlCatalog(context.getBean(JdbcRepositorySettings.class), environment))
                        .isInstanceOf(IllegalStateException.class).hasMessage("Risk publication SQL catalog unavailable").hasNoCause();
            }
        });
    }

    @Test void doesNotNormalizeSqlOrAcceptWrongKindCountTypeOrNull() throws Exception {
        var catalog = catalog();
        for (var sample : samples("")) {
            assertThatThrownBy(() -> catalog.require(sample.sql() + " ", sample.kind(), sample.arguments())).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> catalog.require(sample.sql(), sample.kind() == Kind.UPDATE ? Kind.QUERY : Kind.UPDATE,
                    sample.arguments())).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> catalog.require(sample.sql(), sample.kind(), null)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> catalog.require(sample.sql(), sample.kind(), new Object[sample.arguments().length + 1])).isInstanceOf(IllegalStateException.class);
            for (int index = 0; index < sample.arguments().length; index++) {
                Object[] malformed = sample.arguments().clone();
                malformed[index] = 42;
                assertThatThrownBy(() -> catalog.require(sample.sql(), sample.kind(), malformed)).isInstanceOf(IllegalStateException.class);
                malformed[index] = null;
                assertThatThrownBy(() -> catalog.require(sample.sql(), sample.kind(), malformed)).isInstanceOf(IllegalStateException.class);
            }
        }
        for (String sql : List.of("DELETE FROM EVENT_PUBLICATION", "SELECT PRIVATE", "select 1")) {
            assertThatThrownBy(() -> catalog.require(sql, Kind.QUERY, new Object[0])).isInstanceOf(IllegalStateException.class);
        }
    }

    static RiskPublicationSqlCatalog catalog() throws Exception {
        var result = new AtomicReference<RiskPublicationSqlCatalog>();
        fixture(true).run(context -> result.set(new RiskPublicationSqlCatalog(
                context.getBean(JdbcRepositorySettings.class), context.getEnvironment())));
        return result.get();
    }

    record Sample(String sql, Kind kind, Object[] arguments) {}
    static List<Sample> samples(String schema) {
        String table = schema + "EVENT_PUBLICATION";
        Timestamp at = Timestamp.from(Instant.parse("2026-11-07T00:00:00Z"));
        return List.of(
                new Sample(RiskPublicationSqlCatalog.SQL_STATEMENT_INSERT.formatted(table), Kind.UPDATE,
                        new Object[]{UUID.randomUUID(), Probe.class.getName(), "risk-scenario-draft-change", at, "PRIVATE_PAYLOAD"}),
                new Sample(RiskPublicationSqlCatalog.SQL_STATEMENT_FIND_COMPLETED.formatted(table), Kind.QUERY, new Object[0]),
                new Sample(RiskPublicationSqlCatalog.SQL_STATEMENT_FIND_INCOMPLETE.formatted(table), Kind.QUERY, new Object[0]),
                new Sample(RiskPublicationSqlCatalog.SQL_STATEMENT_FIND_INCOMPLETE_PUBLISHED_BEFORE.formatted(table), Kind.QUERY_ARGS, new Object[]{at}),
                new Sample(RiskPublicationSqlCatalog.SQL_STATEMENT_FIND_BY_EVENT_AND_LISTENER_ID.formatted(table), Kind.QUERY_ARGS,
                        new Object[]{"PRIVATE_PAYLOAD", "risk-scenario-draft-change"}),
                new Sample(RiskPublicationSqlCatalog.SQL_STATEMENT_UPDATE_BY_EVENT_AND_LISTENER_ID.formatted(table), Kind.UPDATE,
                        new Object[]{at, "risk-scenario-draft-change", "PRIVATE_PAYLOAD"}),
                new Sample(RiskPublicationSqlCatalog.SQL_STATEMENT_UPDATE_BY_ID.formatted(table), Kind.UPDATE, new Object[]{at, UUID.randomUUID()}));
    }

    record Probe(String value) {}
    static ApplicationContextRunner fixture(boolean applicationYaml)throws Exception {
        return fixture(applicationYaml,"PostgreSQL");
    }

    static ApplicationContextRunner fixture(boolean applicationYaml,String product)throws Exception {
        var source=mock(DataSource.class);var connection=mock(Connection.class);var metadata=mock(DatabaseMetaData.class);
        when(source.getConnection()).thenReturn(connection);when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn(product);
        doThrow(new AssertionError("Configuration fixture must not execute schema SQL")).when(connection).createStatement();
        doThrow(new AssertionError("Configuration fixture must not execute SQL")).when(connection).prepareStatement(anyString());
        var runner=new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(Class.forName("org.springframework.modulith.events.jdbc.JdbcEventPublicationAutoConfiguration")))
                .withPropertyValues("spring.modulith.events.jdbc.schema-initialization.enabled=false")
                .withBean(DataSource.class,()->source).withBean(JdbcTemplate.class,()->new JdbcTemplate(source))
                .withBean(EventSerializer.class,()->mock(EventSerializer.class));
        if(applicationYaml)runner=runner.withInitializer(context->{
            try { for(var properties:new YamlPropertySourceLoader().load("application",new ClassPathResource("application.yml")))
                context.getEnvironment().getPropertySources().addLast(properties); }
            catch(java.io.IOException failure){throw new IllegalStateException(failure);}
        });
        return runner;
    }
}
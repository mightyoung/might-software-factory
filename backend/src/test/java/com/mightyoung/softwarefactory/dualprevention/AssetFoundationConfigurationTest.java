package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.AssetFoundationSqlCatalog;
import com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationReadService;
import com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationDefinitionProvider;
import com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationService;
import com.mightyoung.softwarefactory.identity.SessionSensitiveKeyProvider;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.modulith.events.core.EventSerializer;
import org.springframework.modulith.events.jdbc.JdbcRepositorySettings;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual SDK settings with metadata/lease fixtures, not PostgreSQL storage acceptance. */
class AssetFoundationConfigurationTest {
    @Test void acceptedRawLeaseIsReturnedUnchangedBeforeAnyTransactionOrSql()throws Exception {
        var fixture=new Fixture("PostgreSQL");
        fixture.context().run(context->{
            var bridge=new AssetFoundationConfiguration.PostgresPoolBridge(fixture.pool,context.getBean(JdbcRepositorySettings.class));
            clearInvocations(fixture.pool,fixture.connection,fixture.metadata);
            assertThat(bridge.getConnection()).isSameAs(fixture.connection);
            verify(fixture.pool).getConnection();verify(fixture.connection).getMetaData();
            verify(fixture.connection,never()).prepareStatement(anyString());
            verify(fixture.connection,never()).setAutoCommit(anyBoolean());
            verify(fixture.connection,never()).close();
        });
    }

    @Test void mismatchedLeaseIsClosedBeforeHandoffAndHasANarrowUnavailableMarker()throws Exception {
        var fixture=new Fixture("PostgreSQL");
        fixture.context().run(context->{
            when(fixture.metadata.getDatabaseProductName()).thenReturn("H2");
            clearInvocations(fixture.pool,fixture.connection,fixture.metadata);
            var bridge=new AssetFoundationConfiguration.PostgresPoolBridge(fixture.pool,context.getBean(JdbcRepositorySettings.class));
            assertThatThrownBy(bridge::getConnection).isInstanceOf(AssetFoundationCapabilityUnavailable.class)
                    .satisfies(failure->assertThat(((SQLException)failure).getSQLState()).isEqualTo("0A000"));
            verify(fixture.connection).close();verify(fixture.connection,never()).prepareStatement(anyString());
            verify(fixture.connection,never()).setAutoCommit(anyBoolean());
        });
    }

    @Test void metadataFailureRetainsCauseAndCloseFailureWithoutLeakingALease()throws Exception {
        var fixture=new Fixture("PostgreSQL");
        fixture.context().run(context->{
            var metadataFailure=new SQLException("fixture metadata unavailable","08006");
            var closeFailure=new SQLException("fixture close unavailable","08006");
            when(fixture.connection.getMetaData()).thenThrow(metadataFailure);
            doThrow(closeFailure).when(fixture.connection).close();
            var bridge=new AssetFoundationConfiguration.PostgresPoolBridge(fixture.pool,context.getBean(JdbcRepositorySettings.class));
            assertThatThrownBy(bridge::getConnection).isInstanceOf(AssetFoundationCapabilityUnavailable.class)
                    .hasCause(metadataFailure).satisfies(failure->assertThat(failure.getSuppressed()).containsExactly(closeFailure));
            verify(fixture.connection,never()).prepareStatement(anyString());
        });
    }

    @Test void truthfulH2SettingsRejectUseBeforeAnyPoolBorrowButCanComposeRealServices()throws Exception {
        var fixture=new Fixture("H2");
        fixture.context().withUserConfiguration(AssetFoundationConfiguration.class,DescriptorFixture.class)
                .withBean(AssetFoundationDefinitionProvider.class,AssetFoundationDefinitionProvider::new)
                .withBean(ObjectMapper.class,()->JsonMapper.builder().build())
                .withBean(SessionSensitiveKeyProvider.class,()->()->{throw new AssertionError("Graph composition must not require keys");})
                .run(context->{
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(AssetFoundationService.class)).isNotNull();
                    assertThat(context.getBean(AssetFoundationReadService.class)).isNotNull();
                    assertThat(context.getBean(com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationQueryService.class)).isNotNull();
                    assertThat(context.getBean(com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationHistoryService.class)).isNotNull();
                    assertThat(context.getBean(com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationDefinitionService.class)).isNotNull();
                    assertThat(context.getBean(com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationSelectorService.class)).isNotNull();
                    assertThat(context.getBeansOfType(DataSource.class)).hasSize(1);
                    assertThat(context.getBeansOfType(org.springframework.transaction.PlatformTransactionManager.class)).isEmpty();
                    assertThat(context.getBeansOfType(org.jooq.DSLContext.class)).isEmpty();
                    clearInvocations(fixture.pool);
                    var bridge=new AssetFoundationConfiguration.PostgresPoolBridge(fixture.pool,context.getBean(JdbcRepositorySettings.class));
                    assertThatThrownBy(bridge::getConnection).isInstanceOf(AssetFoundationCapabilityUnavailable.class);
                    verifyNoInteractions(fixture.pool);
                });
    }

    @Test void markerClassifierDoesNotClassifyAnArbitraryTransactionFailure() {
        var marker=new AssetFoundationCapabilityUnavailable();
        assertThat(AssetFoundationCapabilityUnavailable.causedBy(new org.springframework.transaction.CannotCreateTransactionException("fixture",marker))).isTrue();
        assertThat(AssetFoundationCapabilityUnavailable.causedBy(new org.springframework.transaction.TransactionSystemException("fixture"))).isFalse();
    }

    private static final class Fixture {
        final DataSource pool=mock(DataSource.class);
        final Connection connection=mock(Connection.class);
        final DatabaseMetaData metadata=mock(DatabaseMetaData.class);
        Fixture(String product)throws SQLException {
            when(pool.getConnection()).thenReturn(connection);when(connection.getMetaData()).thenReturn(metadata);
            when(metadata.getDatabaseProductName()).thenReturn(product);
            doThrow(new AssertionError("Fixture must never execute SQL")).when(connection).prepareStatement(anyString());
            doThrow(new AssertionError("Fixture must never initialize schema")).when(connection).createStatement();
        }
        ApplicationContextRunner context()throws ClassNotFoundException {
            return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
                    Class.forName("org.springframework.modulith.events.jdbc.JdbcEventPublicationAutoConfiguration")))
                    .withPropertyValues("spring.modulith.events.jdbc.schema-initialization.enabled=false",
                            "spring.modulith.events.jdbc.use-legacy-structure=true")
                    .withBean(DataSource.class,()->pool).withBean(JdbcTemplate.class,()->new JdbcTemplate(pool))
                    .withBean(EventSerializer.class,()->mock(EventSerializer.class));
        }
    }

    @Configuration(proxyBeanMethods=false)
    static class DescriptorFixture {
        @Bean AssetFoundationCommandCatalog catalog(EventPublicationRepository repository,JdbcRepositorySettings settings) {
            return new AssetFoundationCommandCatalog(AssetFoundationSqlCatalog.bootstrapTemplates(DSL.using(SQLDialect.POSTGRES),repository,settings));
        }
    }
}

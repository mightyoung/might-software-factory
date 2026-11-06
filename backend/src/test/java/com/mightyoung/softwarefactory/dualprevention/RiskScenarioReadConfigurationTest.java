package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.authorization.EntityActionAuthorization;
import com.mightyoung.softwarefactory.dualprevention.risk.*;
import com.mightyoung.softwarefactory.identity.CapturedHumanRequest;
import com.mightyoung.softwarefactory.identity.SessionSensitiveKeyProvider;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.core.EventSerializer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual private graph construction and SDK metadata; never PostgreSQL or HUMAN acceptance. */
class RiskScenarioReadConfigurationTest {
    @Test void composesRealReadServicesWithoutCommandGraphOrGlobalInfrastructure() throws Exception {
        var f=new Fixture("PostgreSQL");
        f.context().run(c->{
            assertThat(c).hasNotFailed();
            assertThat(c).hasSingleBean(RiskScenarioReadService.class).hasSingleBean(RiskScenarioMetadataService.class)
                    .hasSingleBean(RiskScenarioParser.class).hasSingleBean(DataSource.class);
            assertThat(c.getBeansOfType(RiskScenarioService.class)).isEmpty();
            assertThat(c.getBeansOfType(RiskScenarioEventPublisher.class)).isEmpty();
            assertThat(c.getBeansOfType(RiskScenarioReadRepository.class)).isEmpty();
            assertThat(c.getBeansOfType(EntityActionAuthorization.class)).isEmpty();
            assertThat(c.getBeansOfType(org.springframework.transaction.PlatformTransactionManager.class)).isEmpty();
            assertThat(c.getBeansOfType(org.jooq.DSLContext.class)).isEmpty();
            assertThat(c.getBeansOfType(RiskScenarioDefinitionProvider.class)).isEmpty();
            verify(f.connection,never()).prepareStatement(anyString());
            verifyNoInteractions(f.keys);
        });
    }
    @Test void truthfulUnsupportedSettingsRefuseReadBeforeBorrowingOrLookingUpKeys() throws Exception {
        var f=new Fixture("H2");
        f.context().run(c->{
            assertThat(c).hasNotFailed();clearInvocations(f.pool);
            assertThatThrownBy(()->c.getBean(RiskScenarioReadService.class).detail(mock(CapturedHumanRequest.class),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID()))
                    .isInstanceOf(RiskScenarioContracts.Failure.class)
                    .satisfies(e->assertThat(((RiskScenarioContracts.Failure)e).status()).isEqualTo(503));
            verifyNoInteractions(f.pool,f.keys);
        });
    }
    @Test void actualLeaseMismatchClosesLeaseWithoutExecutingSql() throws Exception {
        var f=new Fixture("PostgreSQL");
        f.context().run(c->{
            assertThat(c).hasNotFailed();
            try { when(f.metadata.getDatabaseProductName()).thenReturn("H2"); }
            catch(SQLException e) { throw new AssertionError(e); }
            clearInvocations(f.pool,f.connection);
            assertThatThrownBy(()->c.getBean(RiskScenarioReadService.class).detail(mock(CapturedHumanRequest.class),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID()))
                    .isInstanceOf(RiskScenarioContracts.Failure.class);
            try { verify(f.pool).getConnection();verify(f.connection).close();verify(f.connection,never()).prepareStatement(anyString()); }
            catch(SQLException e) { throw new AssertionError(e); }
            verifyNoInteractions(f.keys);
        });
    }
    private static final class Fixture {
        final DataSource pool=mock(DataSource.class);final Connection connection=mock(Connection.class);
        final DatabaseMetaData metadata=mock(DatabaseMetaData.class);final SessionSensitiveKeyProvider keys=mock(SessionSensitiveKeyProvider.class);
        Fixture(String product)throws SQLException {
            when(pool.getConnection()).thenReturn(connection);when(connection.getMetaData()).thenReturn(metadata);
            when(metadata.getDatabaseProductName()).thenReturn(product);
            doThrow(new AssertionError("Read configuration must not execute SQL")).when(connection).prepareStatement(anyString());
            doThrow(new AssertionError("Read configuration must not initialize schema")).when(connection).createStatement();
        }
        ApplicationContextRunner context()throws ClassNotFoundException {
            return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
                    Class.forName("org.springframework.modulith.events.jdbc.JdbcEventPublicationAutoConfiguration")))
                    .withUserConfiguration(RiskScenarioReadConfiguration.class)
                    .withPropertyValues("spring.modulith.events.jdbc.schema-initialization.enabled=false","spring.modulith.events.jdbc.use-legacy-structure=true")
                    .withBean(DataSource.class,()->pool).withBean(JdbcTemplate.class,()->new JdbcTemplate(pool))
                    .withBean(EventSerializer.class,()->mock(EventSerializer.class))
                    .withBean(ObjectMapper.class,()->JsonMapper.builder().build()).withBean(SessionSensitiveKeyProvider.class,()->keys);
        }
    }
}

package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.authorization.JooqRoleScopeReadRepository;
import com.mightyoung.softwarefactory.authorization.PostgresAuthorizationWriteGuard;
import com.mightyoung.softwarefactory.directory.JooqMemberDirectoryReadRepository;
import com.mightyoung.softwarefactory.identity.JooqAccountStatusReadRepository;
import com.mightyoung.softwarefactory.identity.JooqPrincipalStatusReadRepository;
import com.mightyoung.softwarefactory.identity.SessionAuthoritySql;
import com.mightyoung.softwarefactory.support.TransactionSqlSealGate.SqlTemplate;
import com.mightyoung.softwarefactory.tenancy.JooqTenantMembershipReadRepository;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.modulith.events.core.EventSerializer;
import org.springframework.modulith.events.jdbc.JdbcRepositorySettings;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real rendered queries and actual SDK auto-configuration, with no database or application endpoint. */
class AssetFoundationSqlCatalogTest {
    @Test void existingRepositoriesEmitExactlyTheirOwnerExportedShapes() {
        var seen=new ArrayList<String>();
        var expected=new LinkedHashMap<String,Integer>();
        var values=DSL.using(SQLDialect.POSTGRES);
        var db=DSL.using(new MockConnection(context->{
            assertThat(expected).containsEntry(context.sql(),context.bindings().length);
            seen.add(context.sql());
            return new MockResult[]{new MockResult(0,values.newResult(DSL.field("id",UUID.class)))};
        }),SQLDialect.POSTGRES,new Settings().withExecuteLogging(false).withFetchWarnings(false));
        expected.putAll(JooqAccountStatusReadRepository.commandReadSql(db));
        expected.putAll(JooqPrincipalStatusReadRepository.commandReadSql());
        expected.putAll(JooqTenantMembershipReadRepository.commandReadSql());
        expected.putAll(JooqMemberDirectoryReadRepository.commandReadSql());
        expected.putAll(JooqRoleScopeReadRepository.commandReadSql());
        UUID id=UUID.randomUUID();var at=OffsetDateTime.parse("2026-11-04T00:00:00Z");
        new JooqAccountStatusReadRepository(db).findState(id);
        new JooqPrincipalStatusReadRepository(db).findByActorId(id);
        new JooqTenantMembershipReadRepository(db).findCurrentMembership(id,id,id);
        var directory=new JooqMemberDirectoryReadRepository(db);
        directory.findCurrentMembershipsAt(id,id,at);
        directory.expandDepartmentTreeAt(id,id,at);
        directory.findActiveOrganizationsInCaller(id,Set.of(id,UUID.randomUUID()));
        new JooqRoleScopeReadRepository(db).findActiveActionScopesAt(id,id,Set.of(id),Set.of(),Set.of(),
                "workshop","update","workshop.update",at);
        assertThat(seen).hasSize(9).containsExactlyInAnyOrderElementsOf(expected.keySet());
    }

    @Test void sharedReadExportsAreImmutableAndNeverAdmitSecurityWriterLocks() {
        assertThatThrownBy(()->SessionAuthoritySql.commandReadSql().put("select 1",0))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(PostgresAuthorizationWriteGuard.commandStagingSql().keySet())
                .contains("select pg_advisory_xact_lock_shared(?, ?)","select pg_advisory_xact_lock_shared(?)")
                .doesNotContain("select pg_advisory_xact_lock(?, ?)","select pg_advisory_xact_lock(?)");
        assertThat(SessionAuthoritySql.commandReadSql()).hasSize(2);
    }

    @Test void duplicateSqlMustHaveIdenticalCountPhaseEffectAndAcknowledgment() {
        var map=new LinkedHashMap<String,SqlTemplate>();
        var query=new SqlTemplate("select cast(? as uuid)",1,true,null);
        AssetFoundationSqlCatalog.merge(map,List.of(query,query));
        assertThat(map).hasSize(1);
        for(var incompatible:List.of(new SqlTemplate(query.sql(),2,true,null),
                new SqlTemplate(query.sql(),1,false,null),new SqlTemplate(query.sql(),1,false,"write"))) {
            assertThatThrownBy(()->AssetFoundationSqlCatalog.merge(map,List.of(incompatible)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(map).containsEntry(query.sql(),query);
        }
    }

    @Test void applicationYamlSelectsActualLegacyRepositoryAndACompleteImmutableCatalog()throws Exception {
        fixture(true).run(context->{
            assertThat(context).hasNotFailed();
            var repository=context.getBean(EventPublicationRepository.class);
            assertThat(repository.getClass().getName()).isEqualTo("org.springframework.modulith.events.jdbc.JdbcEventPublicationRepository");
            var catalog=AssetFoundationSqlCatalog.commandTemplates(DSL.using(SQLDialect.POSTGRES),repository,
                    context.getBean(JdbcRepositorySettings.class));
            assertThat(catalog).extracting(SqlTemplate::sql).doesNotHaveDuplicates();
            assertThat(catalog).filteredOn(t->"asset.publication".equals(t.effect())).singleElement()
                    .satisfies(t->{assertThat(t.parameterCount()).isEqualTo(5);assertThat(t.finalRead()).isFalse();
                        assertThat(t.sql()).startsWith("INSERT INTO EVENT_PUBLICATION ");});
            assertThat(catalog).contains(SqlTemplate.deferredConstraintsImmediate());
            assertThatThrownBy(()->catalog.clear()).isInstanceOf(UnsupportedOperationException.class);
        });
    }

    @Test void actualSdkDefaultV2IsRejectedRatherThanGivenAFiveParameterWitness()throws Exception {
        fixture(false).run(context->{
            assertThat(context).hasNotFailed();
            var repository=context.getBean(EventPublicationRepository.class);
            assertThat(repository.getClass().getName()).endsWith("JdbcEventPublicationRepositoryV2");
            assertThatThrownBy(()->AssetFoundationSqlCatalog.commandTemplates(DSL.using(SQLDialect.POSTGRES),repository,
                    context.getBean(JdbcRepositorySettings.class))).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("actual legacy");
            assertThatThrownBy(()->AssetFoundationSqlCatalog.bootstrapTemplates(DSL.using(SQLDialect.POSTGRES),repository,
                    context.getBean(JdbcRepositorySettings.class))).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("actual legacy");
        });
    }

    @Test void bootstrapRendersWithoutBorrowingAndPreservesTruthfulUnsupportedDatabaseSettings()throws Exception {
        fixture(true,"H2").run(context->{
            assertThat(context).hasNotFailed();
            var source=context.getBean(DataSource.class);
            var repository=context.getBean(EventPublicationRepository.class);
            var settings=context.getBean(JdbcRepositorySettings.class);
            assertThat(((Enum<?>)settings.getDatabaseType()).name()).isEqualTo("H2");
            clearInvocations(source);
            var catalog=AssetFoundationSqlCatalog.bootstrapTemplates(DSL.using(SQLDialect.POSTGRES),repository,settings);
            assertThat(catalog).contains(SqlTemplate.deferredConstraintsImmediate());
            verifyNoInteractions(source);
            assertThatThrownBy(()->AssetFoundationSqlCatalog.commandTemplates(DSL.using(SQLDialect.POSTGRES),repository,settings))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("PostgreSQL publication settings");
        });
    }

    @Test void onlyFixedPublicQualificationIsAdmitted()throws Exception {
        fixture(true).withPropertyValues("spring.modulith.events.jdbc.schema=public").run(context->{
            assertThat(context).hasNotFailed();
            assertThat(AssetFoundationSqlCatalog.legacyPublicationTemplate(context.getBean(EventPublicationRepository.class),
                    context.getBean(JdbcRepositorySettings.class)).sql()).startsWith("INSERT INTO public.EVENT_PUBLICATION ");
        });
        fixture(true).withPropertyValues("spring.modulith.events.jdbc.schema=other").run(context->{
            assertThat(context).hasNotFailed();
            assertThatThrownBy(()->AssetFoundationSqlCatalog.legacyPublicationTemplate(context.getBean(EventPublicationRepository.class),
                    context.getBean(JdbcRepositorySettings.class))).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("schema must be public");
        });
    }

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

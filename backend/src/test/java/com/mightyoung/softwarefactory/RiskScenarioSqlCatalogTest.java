package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioService;
import com.mightyoung.softwarefactory.support.G014SqlProfile;
import com.mightyoung.softwarefactory.support.TransactionSqlSealGate.SqlTemplate;
import java.util.LinkedHashMap;
import java.util.List;
import javax.sql.DataSource;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.modulith.events.jdbc.JdbcRepositorySettings;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RiskScenarioSqlCatalogTest {
    @Test void actualLegacySdkBuildsOnlyRiskEffectsWithoutBorrowingAConnection() throws Exception {
        AssetFoundationSqlCatalogTest.fixture(true).run(context->{
            assertThat(context).hasNotFailed();
            var source=context.getBean(DataSource.class);clearInvocations(source);
            var catalog=RiskScenarioSqlCatalog.commandTemplates(DSL.using(SQLDialect.POSTGRES),
                    context.getBean(EventPublicationRepository.class),context.getBean(JdbcRepositorySettings.class));
            verifyNoInteractions(source);
            assertThat(catalog).allSatisfy(t->assertThat(t.profile()).isEqualTo(G014SqlProfile.RISK_SCENARIO));
            assertThat(catalog).extracting(SqlTemplate::sql).doesNotHaveDuplicates();
            assertThat(catalog).containsAll(RiskScenarioService.businessSqlTemplates())
                    .contains(RiskScenarioService.auditSqlTemplate(),SqlTemplate.deferredConstraintsImmediate(G014SqlProfile.RISK_SCENARIO))
                    .doesNotContain(SqlTemplate.deferredConstraintsImmediate());
            assertThat(catalog).filteredOn(t->t.effect()!=null).extracting(SqlTemplate::effect)
                    .containsExactlyInAnyOrder("risk_scenario.insert","risk_scenario.update","risk_scenario.revision",
                            "risk_hazard.new","risk_hazard.end","risk_hazard.revision","risk_scenario.snapshot",
                            "risk_scenario.receipt","risk.audit","risk.publication");
            assertThat(catalog).filteredOn(t->RiskScenarioService.PUBLICATION_EFFECT.equals(t.effect())).singleElement()
                    .satisfies(t->{assertThat(t.parameterCount()).isEqualTo(5);assertThat(t.finalRead()).isFalse();
                        assertThat(t.sql()).startsWith("INSERT INTO EVENT_PUBLICATION ");});
            assertThat(catalog).filteredOn(t->t.sql().equals("select pg_advisory_xact_lock_shared(?)")).singleElement()
                    .satisfies(t->assertThat(t.finalRead()).isFalse());
            assertThatThrownBy(catalog::clear).isInstanceOf(UnsupportedOperationException.class);
        });
    }
    @Test void rejectsActualV2AndNonPostgresSettingsAndRenderer() throws Exception {
        AssetFoundationSqlCatalogTest.fixture(false).run(context->assertThatThrownBy(()->RiskScenarioSqlCatalog.commandTemplates(
                DSL.using(SQLDialect.POSTGRES),context.getBean(EventPublicationRepository.class),context.getBean(JdbcRepositorySettings.class)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("actual legacy"));
        AssetFoundationSqlCatalogTest.fixture(true,"H2").run(context->assertThatThrownBy(()->RiskScenarioSqlCatalog.commandTemplates(
                DSL.using(SQLDialect.POSTGRES),context.getBean(EventPublicationRepository.class),context.getBean(JdbcRepositorySettings.class)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("PostgreSQL publication settings"));
        assertThatThrownBy(()->RiskScenarioSqlCatalog.commandTemplates(DSL.using(SQLDialect.H2),null,null))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void admitsOnlyFixedPublicPublicationQualification() throws Exception {
        AssetFoundationSqlCatalogTest.fixture(true).withPropertyValues("spring.modulith.events.jdbc.schema=public").run(context->{
            var catalog=RiskScenarioSqlCatalog.commandTemplates(DSL.using(SQLDialect.POSTGRES),context.getBean(EventPublicationRepository.class),context.getBean(JdbcRepositorySettings.class));
            assertThat(catalog).filteredOn(t->RiskScenarioService.PUBLICATION_EFFECT.equals(t.effect())).singleElement()
                    .satisfies(t->assertThat(t.sql()).startsWith("INSERT INTO public.EVENT_PUBLICATION "));
        });
        AssetFoundationSqlCatalogTest.fixture(true).withPropertyValues("spring.modulith.events.jdbc.schema=other").run(context->
                assertThatThrownBy(()->RiskScenarioSqlCatalog.commandTemplates(DSL.using(SQLDialect.POSTGRES),context.getBean(EventPublicationRepository.class),context.getBean(JdbcRepositorySettings.class)))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("schema must be public"));
    }
    @Test void refusesForeignProfilesAndConflictingReadOrEffectMetadata() {
        var catalog=new LinkedHashMap<String,SqlTemplate>();
        var valid=new SqlTemplate("select cast(? as uuid)",1,true,null,null,G014SqlProfile.RISK_SCENARIO);
        RiskScenarioSqlCatalog.merge(catalog,List.of(valid,valid));assertThat(catalog).hasSize(1);
        for(var bad:List.of(new SqlTemplate(valid.sql(),1,true,null),
                new SqlTemplate(valid.sql(),2,true,null,null,G014SqlProfile.RISK_SCENARIO),
                new SqlTemplate(valid.sql(),1,false,"risk.mutation",null,G014SqlProfile.RISK_SCENARIO))) {
            assertThatThrownBy(()->RiskScenarioSqlCatalog.merge(catalog,List.of(bad))).isInstanceOf(IllegalArgumentException.class);
            assertThat(catalog).containsEntry(valid.sql(),valid);
        }
    }
}

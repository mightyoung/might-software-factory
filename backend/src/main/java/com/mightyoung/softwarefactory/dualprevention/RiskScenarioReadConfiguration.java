package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.authorization.JooqRoleScopeReadRepository;
import com.mightyoung.softwarefactory.authorization.ManagedSessionSensitiveSecurityTransaction;
import com.mightyoung.softwarefactory.authorization.PersistentEntityActionAuthorization;
import com.mightyoung.softwarefactory.authorization.PostgresAuthorizationWriteGuard;
import com.mightyoung.softwarefactory.directory.JooqMemberDirectoryReadRepository;
import com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioDefinitionProvider;
import com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioMetadataService;
import com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioParser;
import com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioReadRepository;
import com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioReadService;
import com.mightyoung.softwarefactory.identity.JooqAccountStatusReadRepository;
import com.mightyoung.softwarefactory.identity.JooqCurrentHumanSessionAuthority;
import com.mightyoung.softwarefactory.identity.JooqPrincipalStatusReadRepository;
import com.mightyoung.softwarefactory.identity.SessionSensitiveKeyProvider;
import com.mightyoung.softwarefactory.tenancy.JooqTenantApplicationReadRepository;
import com.mightyoung.softwarefactory.tenancy.JooqTenantMembershipReadRepository;
import javax.sql.DataSource;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.modulith.events.jdbc.JdbcRepositorySettings;
import tools.jackson.databind.ObjectMapper;

/** Independent private RO/RR graph. It neither creates a command graph nor registers an event consumer. */
@Configuration(proxyBeanMethods=false)
class RiskScenarioReadConfiguration {
    @Bean
    ReadGraph riskScenarioReadGraph(DataSource original,JdbcRepositorySettings settings,SessionSensitiveKeyProvider keys) {
        var source=new AssetFoundationConfiguration.PostgresPoolBridge(original,settings);
        var manager=new JdbcTransactionManager(source);
        var db=DSL.using(new TransactionAwareDataSourceProxy(source),SQLDialect.POSTGRES,
                new Settings().withExecuteLogging(false).withFetchWarnings(false));
        var guard=new PostgresAuthorizationWriteGuard(source);
        var authority=new ManagedSessionSensitiveSecurityTransaction(source,manager,guard);
        var human=new JooqCurrentHumanSessionAuthority(source,authority,keys,true);
        var applications=new JooqTenantApplicationReadRepository(db,manager,source);
        var actions=new PersistentEntityActionAuthorization(source,authority,human,guard,
                new JooqAccountStatusReadRepository(db),new JooqPrincipalStatusReadRepository(db),
                new JooqTenantMembershipReadRepository(db),applications,
                new JooqMemberDirectoryReadRepository(db),new JooqRoleScopeReadRepository(db));
        var reads=new RiskScenarioReadService(new RiskScenarioReadRepository(source),
                authority,actions,manager,source,applications);
        return new ReadGraph(reads,new RiskScenarioMetadataService(reads,new RiskScenarioDefinitionProvider()));
    }
    @Bean RiskScenarioReadService riskScenarioReadService(ReadGraph graph) { return graph.reads; }
    @Bean RiskScenarioMetadataService riskScenarioMetadataService(ReadGraph graph) { return graph.metadata; }
    @Bean RiskScenarioParser riskScenarioParser(ObjectMapper json) { return new RiskScenarioParser(json); }
    private record ReadGraph(RiskScenarioReadService reads,RiskScenarioMetadataService metadata) { }
}

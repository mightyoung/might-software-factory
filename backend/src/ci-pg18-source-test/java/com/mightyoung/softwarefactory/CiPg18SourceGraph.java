package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.auditops.JooqBusinessAuditAppend;
import com.mightyoung.softwarefactory.authorization.JooqRoleScopeReadRepository;
import com.mightyoung.softwarefactory.authorization.ManagedSessionSensitiveSecurityTransaction;
import com.mightyoung.softwarefactory.authorization.PersistentEntityActionAuthorization;
import com.mightyoung.softwarefactory.authorization.PostgresAuthorizationWriteGuard;
import com.mightyoung.softwarefactory.directory.JooqDirectoryReferenceReadRepository;
import com.mightyoung.softwarefactory.directory.JooqMemberDirectoryReadRepository;
import com.mightyoung.softwarefactory.dualprevention.risk.ModulithRiskScenarioEventPublisher;
import com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioService;
import com.mightyoung.softwarefactory.identity.JooqAccountStatusReadRepository;
import com.mightyoung.softwarefactory.identity.JooqCurrentHumanSessionAuthority;
import com.mightyoung.softwarefactory.identity.JooqPrincipalStatusReadRepository;
import com.mightyoung.softwarefactory.identity.SessionSensitiveKeyProvider;
import com.mightyoung.softwarefactory.support.G014JdbcTransactionManager;
import com.mightyoung.softwarefactory.support.G014SqlProfile;
import com.mightyoung.softwarefactory.support.RiskNativePublicationTransactions;
import com.mightyoung.softwarefactory.tenancy.JooqTenantApplicationReadRepository;
import com.mightyoung.softwarefactory.tenancy.JooqTenantMembershipReadRepository;
import javax.sql.DataSource;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.modulith.events.jdbc.JdbcRepositorySettings;
import tools.jackson.databind.ObjectMapper;

/** Actual source collaborators; no mock authority, reduced SQL catalog or private native repository. */
public record CiPg18SourceGraph(G014JdbcTransactionManager manager, RiskScenarioService service) {
    /** Invoke after the real native repository exists and before its application context is refreshed. */
    public static CiPg18SourceGraph compose(DataSource canonical, EventPublicationRepository publications,
            JdbcRepositorySettings settings, RiskNativePublicationTransactions nativeTransactions,
            SessionSensitiveKeyProvider keys, ObjectMapper json) {
        var rendering = DSL.using(SQLDialect.POSTGRES, new Settings().withExecuteLogging(false).withFetchWarnings(false));
        var manager = new G014JdbcTransactionManager(G014SqlProfile.RISK_SCENARIO, canonical,
                RiskScenarioSqlCatalog.commandTemplates(rendering, publications, settings));
        DataSource source = manager.commandDataSource();
        var db = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES,
                new Settings().withExecuteLogging(false).withFetchWarnings(false));
        var guard = new PostgresAuthorizationWriteGuard(source);
        var authority = new ManagedSessionSensitiveSecurityTransaction(source, manager, guard);
        var human = new JooqCurrentHumanSessionAuthority(source, authority, keys, true);
        var applications = new JooqTenantApplicationReadRepository(db, manager, source);
        var actions = new PersistentEntityActionAuthorization(source, authority, human, guard,
                new JooqAccountStatusReadRepository(db), new JooqPrincipalStatusReadRepository(db),
                new JooqTenantMembershipReadRepository(db), applications,
                new JooqMemberDirectoryReadRepository(db), new JooqRoleScopeReadRepository(db));
        var service = RiskScenarioService.compose(manager, actions, authority, guard,
                new JooqDirectoryReferenceReadRepository(source), new JooqBusinessAuditAppend(source, actions),
                new ModulithRiskScenarioEventPublisher(manager), json, RiskScenarioService.REQUIRED_LISTENER_ID);
        nativeTransactions.configureRiskSource(manager);
        return new CiPg18SourceGraph(manager, service);
    }
}

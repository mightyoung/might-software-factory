package com.mightyoung.softwarefactory.dualprevention;

import com.mightyoung.softwarefactory.auditops.JooqBusinessAuditAppend;
import com.mightyoung.softwarefactory.authorization.JooqRoleScopeReadRepository;
import com.mightyoung.softwarefactory.authorization.ManagedSessionSensitiveSecurityTransaction;
import com.mightyoung.softwarefactory.authorization.PersistentEntityActionAuthorization;
import com.mightyoung.softwarefactory.authorization.PostgresAuthorizationWriteGuard;
import com.mightyoung.softwarefactory.directory.JooqDirectoryReferenceReadRepository;
import com.mightyoung.softwarefactory.directory.JooqMemberDirectoryReadRepository;
import com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationEventListener;
import com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationReadService;
import com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationRepository;
import com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationRequestParser;
import com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationService;
import com.mightyoung.softwarefactory.dualprevention.asset.ModulithAssetFoundationEventPublisher;
import com.mightyoung.softwarefactory.identity.JooqAccountStatusReadRepository;
import com.mightyoung.softwarefactory.identity.JooqCurrentHumanSessionAuthority;
import com.mightyoung.softwarefactory.identity.JooqPrincipalStatusReadRepository;
import com.mightyoung.softwarefactory.identity.SessionSensitiveKeyProvider;
import com.mightyoung.softwarefactory.support.G014JdbcTransactionManager;
import com.mightyoung.softwarefactory.tenancy.JooqTenantApplicationReadRepository;
import com.mightyoung.softwarefactory.tenancy.JooqTenantMembershipReadRepository;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.modulith.events.jdbc.JdbcRepositorySettings;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/** Keeps the command lease and read snapshot graphs private to this module. */
@Configuration(proxyBeanMethods=false)
class AssetFoundationConfiguration {
    @Bean
    Graph assetFoundationGraph(DataSource original,AssetFoundationCommandCatalog catalog,
            JdbcRepositorySettings settings,SessionSensitiveKeyProvider keys,ObjectMapper json,
            ApplicationEventPublisher events) {
        var validated=new PostgresPoolBridge(original,settings);
        var manager=new G014JdbcTransactionManager(validated,catalog.templates());
        var commands=collaborators(manager.commandDataSource(),manager,keys);
        var commandService=new AssetFoundationService(manager,commands.repository,commands.actions,commands.authority,
                commands.guard,new JooqDirectoryReferenceReadRepository(manager.commandDataSource()),
                new JooqBusinessAuditAppend(manager.commandDataSource(),commands.actions),
                new ModulithAssetFoundationEventPublisher(manager,original,events),json,AssetFoundationEventListener.ID);
        var readManager=new JdbcTransactionManager(validated);
        var reads=collaborators(validated,readManager,keys);
        return new Graph(commandService,new AssetFoundationReadService(reads.repository,reads.authority,reads.actions,readManager));
    }

    @Bean AssetFoundationService assetFoundationService(Graph graph) { return graph.commands; }
    @Bean AssetFoundationReadService assetFoundationReadService(Graph graph) { return graph.reads; }
    @Bean AssetFoundationRequestParser assetFoundationRequestParser(ObjectMapper json) { return new AssetFoundationRequestParser(json); }
    @Bean AssetFoundationEventListener assetFoundationEventListener() { return new AssetFoundationEventListener(); }

    private static Collaborators collaborators(DataSource source,PlatformTransactionManager manager,SessionSensitiveKeyProvider keys) {
        DSLContext db=DSL.using(new TransactionAwareDataSourceProxy(source),SQLDialect.POSTGRES,
                new Settings().withExecuteLogging(false).withFetchWarnings(false));
        var guard=new PostgresAuthorizationWriteGuard(source);
        var authority=new ManagedSessionSensitiveSecurityTransaction(source,manager,guard);
        var human=new JooqCurrentHumanSessionAuthority(source,authority,keys,true);
        var actions=new PersistentEntityActionAuthorization(source,authority,human,guard,
                new JooqAccountStatusReadRepository(db),new JooqPrincipalStatusReadRepository(db),
                new JooqTenantMembershipReadRepository(db),new JooqTenantApplicationReadRepository(db,manager,source),
                new JooqMemberDirectoryReadRepository(db),new JooqRoleScopeReadRepository(db));
        return new Collaborators(new AssetFoundationRepository(source,actions),actions,authority,guard);
    }

    private record Graph(AssetFoundationService commands,AssetFoundationReadService reads) {}
    private record Collaborators(AssetFoundationRepository repository,PersistentEntityActionAuthorization actions,
            ManagedSessionSensitiveSecurityTransaction authority,PostgresAuthorizationWriteGuard guard) {}

    /** Validates the real dependency before any transaction owner receives a raw lease. Not a Spring bean or a pool. */
    static final class PostgresPoolBridge extends AbstractDataSource {
        private final DataSource original;
        private final JdbcRepositorySettings settings;
        PostgresPoolBridge(DataSource original,JdbcRepositorySettings settings) {
            this.original=Objects.requireNonNull(original);this.settings=Objects.requireNonNull(settings);
        }
        @Override public Connection getConnection()throws SQLException {
            requireSettings();return requireConnection(original.getConnection());
        }
        @Override public Connection getConnection(String username,String password)throws SQLException {
            requireSettings();return requireConnection(original.getConnection(username,password));
        }
        private void requireSettings()throws AssetFoundationCapabilityUnavailable {
            Object database=settings.getDatabaseType();
            if(!(database instanceof Enum<?> type)||!type.name().equals("POSTGRES"))throw new AssetFoundationCapabilityUnavailable();
        }
        private Connection requireConnection(Connection connection)throws SQLException {
            try {
                if(!"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName()))throw new AssetFoundationCapabilityUnavailable();
                return connection;
            } catch(SQLException|RuntimeException failure) {
                var refusal=failure instanceof AssetFoundationCapabilityUnavailable unavailable?unavailable:
                        new AssetFoundationCapabilityUnavailable(failure);
                try { connection.close(); } catch(SQLException|RuntimeException closeFailure) { refusal.addSuppressed(closeFailure); }
                throw refusal;
            }
        }
    }
}

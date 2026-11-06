package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.authorization.JooqRoleScopeReadRepository;
import com.mightyoung.softwarefactory.authorization.ManagedSessionSensitiveSecurityTransaction;
import com.mightyoung.softwarefactory.authorization.PostgresAuthorizationWriteGuard;
import com.mightyoung.softwarefactory.directory.JooqDirectoryReferenceReadRepository;
import com.mightyoung.softwarefactory.directory.JooqMemberDirectoryReadRepository;
import com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioService;
import com.mightyoung.softwarefactory.support.G014SqlProfile;
import com.mightyoung.softwarefactory.identity.JooqAccountStatusReadRepository;
import com.mightyoung.softwarefactory.identity.JooqPrincipalStatusReadRepository;
import com.mightyoung.softwarefactory.identity.SessionAuthoritySql;
import com.mightyoung.softwarefactory.support.TransactionSqlSealGate.SqlTemplate;
import com.mightyoung.softwarefactory.tenancy.JooqTenantApplicationReadRepository;
import com.mightyoung.softwarefactory.tenancy.JooqTenantMembershipReadRepository;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.modulith.events.jdbc.JdbcRepositorySettings;

/** Explicit construction data for risk commands. No bean registration or runtime activation. */
public final class RiskScenarioSqlCatalog {
    private RiskScenarioSqlCatalog() { }
    public static List<SqlTemplate> commandTemplates(DSLContext commandDsl,
            EventPublicationRepository publications, JdbcRepositorySettings settings) {
        Objects.requireNonNull(commandDsl);
        if(commandDsl.dialect().family()!=SQLDialect.POSTGRES)
            throw new IllegalArgumentException("Risk commands require PostgreSQL SQL rendering");
        // Reuse the existing exact SDK/schema witness; never import foundation business SQL.
        var legacy=AssetFoundationSqlCatalog.legacyPublicationTemplate(publications,settings);
        var catalog=new LinkedHashMap<String,SqlTemplate>();
        addReads(catalog,PostgresAuthorizationWriteGuard.commandReadSql());
        addReads(catalog,ManagedSessionSensitiveSecurityTransaction.commandReadSql());
        add(catalog,PostgresAuthorizationWriteGuard.commandStagingSql(),false);
        addReads(catalog,SessionAuthoritySql.commandReadSql());
        addReads(catalog,JooqPrincipalStatusReadRepository.commandReadSql());
        addReads(catalog,JooqAccountStatusReadRepository.commandReadSql(commandDsl));
        addReads(catalog,JooqTenantMembershipReadRepository.commandReadSql());
        addReads(catalog,JooqTenantApplicationReadRepository.commandReadSql());
        addReads(catalog,JooqMemberDirectoryReadRepository.commandReadSql());
        addReads(catalog,JooqRoleScopeReadRepository.commandReadSql());
        addReads(catalog,JooqDirectoryReferenceReadRepository.commandReadSql());
        merge(catalog,List.of(RiskScenarioService.auditSqlTemplate(),
                new SqlTemplate(legacy.sql(),legacy.parameterCount(),false,
                        RiskScenarioService.PUBLICATION_EFFECT,null,G014SqlProfile.RISK_SCENARIO)));
        merge(catalog,RiskScenarioService.businessSqlTemplates());
        return List.copyOf(catalog.values());
    }
    private static void addReads(Map<String,SqlTemplate> catalog,Map<String,Integer> sql) { add(catalog,sql,true); }
    private static void add(Map<String,SqlTemplate> catalog,Map<String,Integer> sql,boolean finalRead) {
        sql.forEach((text,count)->merge(catalog,List.of(new SqlTemplate(text,count,finalRead,null,null,G014SqlProfile.RISK_SCENARIO))));
    }
    static void merge(Map<String,SqlTemplate> catalog,Collection<SqlTemplate> additions) {
        for(var template:additions) {
            if(template.profile()!=G014SqlProfile.RISK_SCENARIO)
                throw new IllegalArgumentException("Risk catalog rejects another profile");
            var previous=catalog.putIfAbsent(template.sql(),template);
            if(previous!=null&&!previous.equals(template))
                throw new IllegalArgumentException("Conflicting fixed risk SQL templates");
        }
    }
}

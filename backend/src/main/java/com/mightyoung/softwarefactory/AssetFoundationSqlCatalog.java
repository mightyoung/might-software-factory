package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.auditops.JooqBusinessAuditAppend;
import com.mightyoung.softwarefactory.authorization.JooqRoleScopeReadRepository;
import com.mightyoung.softwarefactory.authorization.ManagedSessionSensitiveSecurityTransaction;
import com.mightyoung.softwarefactory.authorization.PostgresAuthorizationWriteGuard;
import com.mightyoung.softwarefactory.directory.JooqDirectoryReferenceReadRepository;
import com.mightyoung.softwarefactory.directory.JooqMemberDirectoryReadRepository;
import com.mightyoung.softwarefactory.dualprevention.AssetFoundationSqlTemplates;
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
import org.springframework.aop.support.AopUtils;
import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.modulith.events.jdbc.JdbcRepositorySettings;

/** Construction-time catalog for the dedicated command graph; does not register a bean or change global SQL settings. */
public final class AssetFoundationSqlCatalog {
    private static final String LEGACY_REPOSITORY = "org.springframework.modulith.events.jdbc.JdbcEventPublicationRepository";
    private static final String LEGACY_INSERT = """
            INSERT INTO EVENT_PUBLICATION (ID, EVENT_TYPE, LISTENER_ID, PUBLICATION_DATE, SERIALIZED_EVENT)
            VALUES (?, ?, ?, ?, ?)
            """;
    private static final String PUBLIC_LEGACY_INSERT = """
            INSERT INTO public.EVENT_PUBLICATION (ID, EVENT_TYPE, LISTENER_ID, PUBLICATION_DATE, SERIALIZED_EVENT)
            VALUES (?, ?, ?, ?, ?)
            """;

    private AssetFoundationSqlCatalog() { }

    /** Call once while composing the graph, with its exact DSL configuration and actual default Modulith beans. */
    public static List<SqlTemplate> commandTemplates(DSLContext commandDsl,
            EventPublicationRepository publicationRepository, JdbcRepositorySettings publicationSettings) {
        requirePostgres(publicationSettings);
        return bootstrapTemplates(commandDsl,publicationRepository,publicationSettings);
    }

    /** Renders the PostgreSQL command shape without borrowing a connection. The private pool bridge checks the actual database before use. */
    public static List<SqlTemplate> bootstrapTemplates(DSLContext commandDsl,
            EventPublicationRepository publicationRepository, JdbcRepositorySettings publicationSettings) {
        Objects.requireNonNull(commandDsl);
        if(commandDsl.dialect().family()!=SQLDialect.POSTGRES)throw new IllegalArgumentException("G014 requires PostgreSQL SQL rendering");
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
        merge(catalog,List.of(new SqlTemplate(JooqBusinessAuditAppend.commandInsertSql(),11,false,"asset.audit"),
                legacyPublicationShape(publicationRepository,publicationSettings)));
        merge(catalog,AssetFoundationSqlTemplates.commandTemplates());
        return List.copyOf(catalog.values());
    }

    /** No arbitrary schema interpolation or V2 fallback: this witness is for the exact existing V5 legacy schema. */
    static SqlTemplate legacyPublicationTemplate(EventPublicationRepository repository,JdbcRepositorySettings settings) {
        requirePostgres(settings);
        return legacyPublicationShape(repository,settings);
    }

    private static void requirePostgres(JdbcRepositorySettings settings) {
        Objects.requireNonNull(settings);
        Object database=settings.getDatabaseType();
        if(!(database instanceof Enum<?> type)||!type.name().equals("POSTGRES"))
            throw new IllegalStateException("G014 requires PostgreSQL publication settings");
    }

    private static SqlTemplate legacyPublicationShape(EventPublicationRepository repository,JdbcRepositorySettings settings) {
        Objects.requireNonNull(repository);Objects.requireNonNull(settings);
        if(!LEGACY_REPOSITORY.equals(AopUtils.getTargetClass(repository).getName()))
            throw new IllegalStateException("G014 requires the actual legacy Modulith publication repository");
        String schema=settings.getSchema();
        String sql;
        if(schema==null||schema.isEmpty())sql=LEGACY_INSERT;
        else if(schema.equals("public"))sql=PUBLIC_LEGACY_INSERT;
        else throw new IllegalStateException("G014 publication schema must be public");
        return new SqlTemplate(sql,5,false,"asset.publication");
    }

    private static void addReads(Map<String,SqlTemplate> catalog,Map<String,Integer> sql) { add(catalog,sql,true); }
    private static void add(Map<String,SqlTemplate> catalog,Map<String,Integer> sql,boolean finalRead) {
        sql.forEach((text,count)->merge(catalog,List.of(new SqlTemplate(text,count,finalRead,null))));
    }

    static void merge(Map<String,SqlTemplate> catalog,Collection<SqlTemplate> additions) {
        for(var template:additions) {
            var previous=catalog.putIfAbsent(template.sql(),template);
            if(previous!=null&&!previous.equals(template))throw new IllegalArgumentException("Conflicting fixed SQL templates");
        }
    }
}

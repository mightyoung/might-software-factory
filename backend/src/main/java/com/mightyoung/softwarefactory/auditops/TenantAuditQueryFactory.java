package com.mightyoung.softwarefactory.auditops;

import com.mightyoung.softwarefactory.authorization.ManagedSessionSensitiveSecurityTransaction;
import com.mightyoung.softwarefactory.authorization.PersistentTenantAuditReadAuthorization;
import com.mightyoung.softwarefactory.authorization.PostgresAuthorizationWriteGuard;
import com.mightyoung.softwarefactory.identity.JooqAccountStatusReadRepository;
import com.mightyoung.softwarefactory.identity.JooqCurrentHumanSessionAuthority;
import com.mightyoung.softwarefactory.identity.JooqPrincipalStatusReadRepository;
import com.mightyoung.softwarefactory.identity.SessionSensitiveKeyProvider;
import com.mightyoung.softwarefactory.tenancy.JooqTenantApplicationReadRepository;
import com.mightyoung.softwarefactory.tenancy.JooqTenantMembershipReadRepository;
import java.util.Objects;
import javax.sql.DataSource;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.jdbc.support.JdbcTransactionManager;

/** Constructs one private same-source graph; creates no bean, endpoint, credentials, schema or grant. */
public final class TenantAuditQueryFactory {
    private TenantAuditQueryFactory() { }
    public static TenantAuditQueryService create(DataSource source,SessionSensitiveKeyProvider keys){
        Objects.requireNonNull(source);Objects.requireNonNull(keys);
        var manager=new JdbcTransactionManager(source);
        var guard=new PostgresAuthorizationWriteGuard(source);
        var callers=new ManagedSessionSensitiveSecurityTransaction(source,manager,guard);
        var human=new JooqCurrentHumanSessionAuthority(source,callers,keys,true);
        var db=DSL.using(new TransactionAwareDataSourceProxy(source),SQLDialect.POSTGRES,
                new Settings().withExecuteLogging(false).withFetchWarnings(false));
        var authority=new PersistentTenantAuditReadAuthorization(source,callers,human,new JooqAccountStatusReadRepository(db),
                new JooqPrincipalStatusReadRepository(db),new JooqTenantMembershipReadRepository(db),
                new JooqTenantApplicationReadRepository(db,manager,source));
        return new TenantAuditQueryService(source,manager,callers,authority);
    }
}

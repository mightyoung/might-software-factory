package com.mightyoung.softwarefactory.authorization;

import com.mightyoung.softwarefactory.directory.JooqMemberDirectoryReadRepository;
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
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods=false)
class ApplicationActionConfiguration {
    @Bean ApplicationActionRuntime applicationActionRuntime(DataSource source,PlatformTransactionManager manager,
            SessionSensitiveKeyProvider keys, PostgresAuthorizationWriteGuard guard) {
        var db=DSL.using(new TransactionAwareDataSourceProxy(source),SQLDialect.POSTGRES,new Settings().withExecuteLogging(false).withFetchWarnings(false));
        var callers=new ManagedSessionSensitiveSecurityTransaction(source,manager,guard);
        var human=new JooqCurrentHumanSessionAuthority(source,callers,keys);
        var authority=new PersistentEntityActionAuthorization(source,callers,human,guard,new JooqAccountStatusReadRepository(db),
                new JooqPrincipalStatusReadRepository(db),new JooqTenantMembershipReadRepository(db),
                new JooqTenantApplicationReadRepository(db,manager,source),new JooqMemberDirectoryReadRepository(db),new JooqRoleScopeReadRepository(db));
        return new ApplicationActionRuntime(callers,guard,authority);
    }
}

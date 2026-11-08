package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.auditops.JooqSessionSensitiveAudit;
import com.mightyoung.softwarefactory.authorization.ManagedSessionSensitiveSecurityTransaction;
import com.mightyoung.softwarefactory.authorization.PostgresAuthorizationWriteGuard;
import com.mightyoung.softwarefactory.authorization.SessionSensitiveJdbcTransactionManager;
import com.mightyoung.softwarefactory.identity.LoginAttemptLimiter;
import com.mightyoung.softwarefactory.identity.ReauthIssuerService;
import com.mightyoung.softwarefactory.identity.SessionSensitiveKeys;
import com.mightyoung.softwarefactory.identity.SessionSensitiveRequests;
import java.security.SecureRandom;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Explicit trusted application assembly; no bean, endpoint or default activation. */
public final class ReauthIssuerFactory {
    private ReauthIssuerFactory() {}

    public static ReauthIssuerService create(SessionSensitiveRequests requests,DataSource source,
            PasswordEncoder encoder,SessionSensitiveKeys keys,LoginAttemptLimiter account,LoginAttemptLimiter network,Clock clock,SecureRandom random) {
        var manager=new SessionSensitiveJdbcTransactionManager(source);
        var boundaries=new ManagedSessionSensitiveSecurityTransaction(source,manager,new PostgresAuthorizationWriteGuard(source));
        var audit=new JooqSessionSensitiveAudit(source,boundaries,manager);
        return ReauthIssuerService.assembleTrusted(requests,source,boundaries,audit,encoder,keys,account,network,clock,random);
    }
}

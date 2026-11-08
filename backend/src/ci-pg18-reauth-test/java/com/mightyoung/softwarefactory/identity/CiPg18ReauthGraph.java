package com.mightyoung.softwarefactory.identity;

import com.mightyoung.softwarefactory.auditops.JooqSessionSensitiveAudit;
import com.mightyoung.softwarefactory.authorization.ManagedSessionSensitiveSecurityTransaction;
import com.mightyoung.softwarefactory.authorization.PostgresAuthorizationWriteGuard;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import com.mightyoung.softwarefactory.authorization.SessionSensitiveJdbcTransactionManager;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Actual production ports and guarded transaction manager; no mocked authority or SQL engine. */
final class CiPg18ReauthGraph {
    final ManagedSessionSensitiveSecurityTransaction boundaries;
    final SessionSensitiveRequests requests;
    final SessionAuthorityService sessions;
    final ReauthIssuerService issuer;
    CiPg18ReauthGraph(CiPg18ReauthFixture fixture,SessionSensitiveKeys keys){this(fixture,keys,PasswordEncoderFactories.createDelegatingPasswordEncoder());}
    CiPg18ReauthGraph(CiPg18ReauthFixture fixture,SessionSensitiveKeys keys,PasswordEncoder encoder){
        var source=fixture.runtime();var manager=new SessionSensitiveJdbcTransactionManager(source);
        boundaries=new ManagedSessionSensitiveSecurityTransaction(source,manager,new PostgresAuthorizationWriteGuard(source));
        requests=new SessionSensitiveRequests(new SessionContextResolver(),Duration.ofHours(1));
        var audit=new JooqSessionSensitiveAudit(source,boundaries,manager);
        sessions=new SessionAuthorityService(requests,boundaries,new JooqSessionAuthorityStore(source,boundaries,keys),audit,new KnownLogoutRequests(serializer->serializer.setUseBase64Encoding(false)));
        issuer=com.mightyoung.softwarefactory.ReauthIssuerFactory.create(requests,source,encoder,keys,
                new LoginAttemptLimiter(LoginAttemptLimiter.Config.defaults(),System::nanoTime),new LoginAttemptLimiter(LoginAttemptLimiter.Config.defaults(),System::nanoTime),Clock.systemUTC(),fixture.random());
    }
    void register(CiPg18ReauthFixture.Actor actor,CiPg18ReauthFixture.Session session){
        try(var current=new CiPg18ReauthSession(actor,session)){if(!sessions.register(current.request,UUID.randomUUID()).toString().equals(session.primary()))throw CiPg18ReauthFixture.rejected();}
    }
    ReauthIssuerContracts.Outcome issue(CiPg18ReauthSession current,CiPg18ReauthFixture.Actor actor,CiPg18ReauthFixture.Session target,long version,UUID command){
        char[] password=actor.password().toCharArray();
        try(var input=new ReauthIssuerContracts.Request(SessionHandle.parse(target.primary()),new SessionSensitiveContracts.ExpectedAuthority(version),password)){
            return issuer.issueSessionRevoke(current.request,command,UUID.randomUUID(),input);
        }finally{java.util.Arrays.fill(password,'\0');}
    }
    void revoke(CiPg18ReauthSession current,CiPg18ReauthFixture.Session target,long version,UUID command,ReauthIssuerContracts.Issued issue){
        sessions.revoke(current.request,new SessionSensitiveContracts.RevokeCommand(command,SessionHandle.parse(target.primary()),new SessionSensitiveContracts.ExpectedAuthority(version),issue.metadata().proofId(),issue.secret()),UUID.randomUUID());
    }
}

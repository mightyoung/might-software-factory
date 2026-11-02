package com.mightyoung.softwarefactory.identity;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.Optional;

/** Exact trusted account/principal read and two-row CAS under the mandatory security writer marker. */
@Repository
public final class JooqPasswordCredentialStore implements PasswordCredentialStore {
    private final DSLContext dsl;
    private final IdentitySecurityTransaction securityTransaction;
    public JooqPasswordCredentialStore(DSLContext dsl,IdentitySecurityTransaction securityTransaction) {
        var privateSettings=(org.jooq.conf.Settings)dsl.configuration().settings().clone();
        this.dsl=org.jooq.impl.DSL.using(dsl.configuration().derive(privateSettings.withExecuteLogging(false)));
        this.securityTransaction=securityTransaction;
    }
    @Override public Optional<PasswordCredentialState> findOwn(CurrentTenantContext context) {
        var row=dsl.fetchOne("""
                select a.credential_version,c.secret_hash from public.account a
                join public.principal p on p.account_id=a.id and p.id=cast(? as uuid)
                  and p.kind='HUMAN' and p.status='ACTIVE'
                join public.credential c on c.account_id=a.id and c.kind='PASSWORD'
                where a.id=cast(? as uuid) and a.status='ACTIVE' and a.credential_version=?
                """,context.actorId(),context.accountId(),context.credentialVersion());
        return row==null?Optional.empty():Optional.of(new PasswordCredentialState(
                row.get("credential_version",Long.class),row.get("secret_hash",String.class)));
    }
    @Override public void replace(CurrentTenantContext context,PasswordCredentialState expected,String encodedPassword,Instant changedAt) {
        securityTransaction.requireActive(context); // never issue writer SQL without the same-datasource guard marker
        if(expected.credentialVersion()!=context.credentialVersion()||expected.credentialVersion()==Long.MAX_VALUE)
            throw stale();
        if(encodedPassword==null||encodedPassword.length()<20||encodedPassword.length()>512||changedAt==null)
            throw new IllegalArgumentException("Invalid prepared credential state");
        int credential=dsl.execute("""
                update public.credential c set secret_hash=?,changed_at=cast(? as timestamptz)
                 where c.account_id=cast(? as uuid) and c.kind='PASSWORD' and c.secret_hash=?
                   and exists(select 1 from public.account a join public.principal p on p.account_id=a.id
                     where a.id=c.account_id and a.status='ACTIVE' and a.credential_version=?
                       and p.id=cast(? as uuid) and p.kind='HUMAN' and p.status='ACTIVE')
                """,encodedPassword,changedAt.atOffset(java.time.ZoneOffset.UTC),context.accountId(),expected.encodedPassword(),expected.credentialVersion(),context.actorId());
        if(credential!=1)throw stale();
        int account=dsl.execute("""
                update public.account set credential_version=credential_version+1
                 where id=cast(? as uuid) and status='ACTIVE' and credential_version=?
                """,context.accountId(),expected.credentialVersion());
        if(account!=1)throw stale(); // exception must escape, rolling back the first CAS too
    }
    private static PasswordChangeException stale(){return new PasswordChangeException(PasswordChangeFailureReason.STALE_IDENTITY,401);}
}

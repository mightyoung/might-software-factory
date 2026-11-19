package com.mightyoung.softwarefactory.identity;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static com.mightyoung.softwarefactory.identity.SessionSensitiveContracts.*;

/** Explicit identity DAL; all issuance effects use the existing exact pinned sensitive writer. */
final class JooqReauthIssuerStore implements ReauthIssuerStore {
    private static final String CREDENTIAL="""
            select a.credential_version,c.secret_hash from public.account a
            join public.principal p on p.account_id=a.id and p.id=cast(? as uuid) and p.kind='HUMAN' and p.status='ACTIVE'
            join public.credential c on c.account_id=a.id and c.kind='PASSWORD'
            where a.id=cast(? as uuid) and a.status='ACTIVE' and a.credential_version=?
            """;
    private final DataSource source;
    private final SessionSensitiveSecurityTransaction boundaries;
    private final SessionSensitiveKeys keys;
    private ReauthIssuerService issuer;
    JooqReauthIssuerStore(DataSource source,SessionSensitiveSecurityTransaction boundaries,SessionSensitiveKeys keys){
        this.source=Objects.requireNonNull(source);this.boundaries=Objects.requireNonNull(boundaries);this.keys=Objects.requireNonNull(keys);
    }
    @Override public synchronized void bindIssuer(ReauthIssuerService issuer){
        if(this.issuer!=null||issuer==null)throw SessionSensitiveException.unavailable();this.issuer=issuer;
    }
    @Override public Optional<PasswordCredentialState> readCredential(CurrentTenantContext context){
        if(TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isSynchronizationActive()
                ||!TransactionSynchronizationManager.getResourceMap().isEmpty()){
            if(issuer!=null)issuer.rejectActivePreparation();throw SessionSensitiveException.rejected();
        }
        if(issuer==null)throw SessionSensitiveException.unavailable();
        return sql(null,null,db->credential(db,context));
    }
    @Override public Stored issue(SessionSensitiveSecurityTransaction.Scope scope,SessionSensitiveSecurityTransaction.GuardedBoundary boundary,
            ReauthIssuerService.VerifiedPreparation verified){
        if(issuer==null){if(verified!=null)verified.fail();throw SessionSensitiveException.rejected();}
        if(verified==null){issuer.rejectActivePreparation();throw SessionSensitiveException.rejected();}
        try{
        verified.claimFor(issuer,scope,boundary);
        SessionHandle target=verified.target();ExpectedAuthority expected=verified.expected();UUID commandKey=verified.commandKey();
        PasswordCredentialState prepared=verified.credential();Instant preparedAt=verified.preparedAt();String secretDigest=verified.secretDigest();
        UUID proofId=verified.proofId(),receiptId=verified.receiptId(),eventId=verified.eventId();
        Stored result=sql(scope,boundary,db->{
            var request=scope.request();request.requireCurrent();var context=request.context();Instant now=boundary.observedAt();
            if(preparedAt.isAfter(now)||Duration.between(preparedAt,now).compareTo(Duration.ofSeconds(60))>0)throw SessionSensitiveException.rejected();
            if(SessionAuthoritySql.control(db)!=Enforcement.ENFORCING)throw SessionSensitiveException.unavailable();
            SessionHandle current=SessionAuthoritySql.current(db,request,now,keys);
            if(!credential(db,context).filter(prepared::equals).isPresent())throw SessionSensitiveException.rejected();
            if(current.equals(target))throw SessionSensitiveException.rejected();
            Record stored=db.fetchOne("select principal_name from public.spring_session where primary_id=?",target.toString());
            Record authority=db.fetchOne("select account_id,state,version from public.account_session_authority where primary_id=?",target.toString());
            if(stored==null&&authority==null||stored!=null&&!context.accountId().toString().equals(stored.get("principal_name",String.class))
                    ||authority!=null&&!context.accountId().equals(authority.get("account_id",UUID.class)))throw SessionSensitiveException.rejected();
            String canonical=SessionRevokeCanonical.action(context,current,target,expected);
            Record receipt=db.fetchOne("""
                    select r.id,r.proof_id,r.actor_id,r.fingerprint_key_id,r.fingerprint,r.expires_at,r.event_id,r.completed_at,
                           p.action,p.account_id as proof_account,p.actor_id as proof_actor,p.source_primary_id,p.issued_credential_version,
                           p.target_primary_id,p.expected_authority_version,p.body_key_id,p.body_digest,p.issuance_receipt_id,
                           p.created_at as proof_created_at,p.expires_at as proof_expires_at
                    from public.sensitive_reauth_issuance_receipt r left join public.sensitive_reauth_proof p on p.id=r.proof_id
                    where r.account_id=cast(? as uuid) and r.source_primary_id=? and r.command_key=cast(? as uuid)
                    """,context.accountId(),current.toString(),commandKey);
            if(receipt!=null){
                if(!context.actorId().equals(receipt.get("actor_id",UUID.class))
                        ||!keys.matchesIssuance(receipt.get("fingerprint_key_id",String.class),receipt.get("fingerprint",String.class),canonical))throw conflict();
                if(!"SESSION_REVOKE".equals(receipt.get("action",String.class))||!context.accountId().equals(receipt.get("proof_account",UUID.class))
                        ||!context.actorId().equals(receipt.get("proof_actor",UUID.class))||!current.toString().equals(receipt.get("source_primary_id",String.class))
                        ||!Long.valueOf(context.credentialVersion()).equals(receipt.get("issued_credential_version",Long.class))
                        ||!target.toString().equals(receipt.get("target_primary_id",String.class))||!Long.valueOf(expected.version()).equals(receipt.get("expected_authority_version",Long.class))
                        ||!receipt.get("id",UUID.class).equals(receipt.get("issuance_receipt_id",UUID.class))
                        ||!receipt.get("completed_at",OffsetDateTime.class).equals(receipt.get("proof_created_at",OffsetDateTime.class))
                        ||!receipt.get("expires_at",OffsetDateTime.class).equals(receipt.get("proof_expires_at",OffsetDateTime.class))
                        ||!keys.matchesAction(receipt.get("body_key_id",String.class),receipt.get("body_digest",String.class),canonical))throw SessionSensitiveException.unavailable();
                return new Stored(new ReauthIssuerContracts.Metadata(receipt.get("proof_id",UUID.class),receipt.get("id",UUID.class),instant(receipt,"expires_at")),
                        current,receipt.get("event_id",UUID.class),instant(receipt,"completed_at"),false);
            }
            long version=authority==null?0:authority.get("version",Long.class);
            if(version!=expected.version()||authority!=null&&!"ACTIVE".equals(authority.get("state",String.class)))throw conflict();
            var prior=db.fetch("""
                    select p.id,p.version,p.body_key_id,p.body_digest,p.issuance_receipt_id,p.created_at,p.expires_at,
                           r.id as receipt_id,r.proof_id as receipt_proof,r.account_id as receipt_account,r.actor_id as receipt_actor,
                           r.source_primary_id as receipt_source,r.expires_at as receipt_expires,r.completed_at as receipt_completed
                    from public.sensitive_reauth_proof p left join public.sensitive_reauth_issuance_receipt r on r.id=p.issuance_receipt_id
                    where p.account_id=cast(? as uuid) and p.source_primary_id=? and p.action='SESSION_REVOKE'
                      and p.actor_id=cast(? as uuid) and p.issued_credential_version=? and p.target_primary_id=?
                      and p.expected_authority_version=? and p.state='ISSUED'
                    order by p.id limit 33
                    """,context.accountId(),current.toString(),context.actorId(),context.credentialVersion(),target.toString(),expected.version());
            if(prior.size()>32)throw SessionSensitiveException.unavailable();
            for(Record proof:prior){
                if(!keys.matchesAction(proof.get("body_key_id",String.class),proof.get("body_digest",String.class),canonical)
                        ||!proof.get("id",UUID.class).equals(proof.get("receipt_proof",UUID.class))
                        ||!proof.get("issuance_receipt_id",UUID.class).equals(proof.get("receipt_id",UUID.class))
                        ||!context.accountId().equals(proof.get("receipt_account",UUID.class))||!context.actorId().equals(proof.get("receipt_actor",UUID.class))
                        ||!current.toString().equals(proof.get("receipt_source",String.class))
                        ||!proof.get("created_at",OffsetDateTime.class).equals(proof.get("receipt_completed",OffsetDateTime.class))
                        ||!proof.get("expires_at",OffsetDateTime.class).equals(proof.get("receipt_expires",OffsetDateTime.class)))throw SessionSensitiveException.unavailable();
                if(db.execute("""
                        update public.sensitive_reauth_proof set state=case when expires_at<=statement_timestamp() then 'EXPIRED' else 'REVOKED' end,version=version+1
                        where id=cast(? as uuid) and state='ISSUED' and version=?
                        """,proof.get("id",UUID.class),proof.get("version",Long.class))!=1)throw conflict();
            }
            Instant issued=now.truncatedTo(java.time.temporal.ChronoUnit.MICROS),expires=issued.plusSeconds(300);String key=keys.currentKeyId();
            if(db.execute("""
                    insert into public.sensitive_reauth_proof(id,account_id,actor_id,source_primary_id,issued_credential_version,action,
                     target_primary_id,expected_authority_version,body_key_id,body_digest,secret_digest,state,version,created_at,expires_at,issuance_receipt_id)
                    values(cast(? as uuid),cast(? as uuid),cast(? as uuid),?,?,'SESSION_REVOKE',?,?,?, ?,?,'ISSUED',1,cast(? as timestamptz),cast(? as timestamptz),cast(? as uuid))
                    """,proofId,context.accountId(),context.actorId(),current.toString(),context.credentialVersion(),target.toString(),expected.version(),key,
                    keys.actionDigest(key,canonical),secretDigest,time(issued),time(expires),receiptId)!=1)throw conflict();
            if(db.execute("""
                    insert into public.sensitive_reauth_issuance_receipt(id,account_id,actor_id,source_primary_id,command_key,fingerprint_key_id,fingerprint,proof_id,expires_at,event_id,completed_at)
                    values(cast(? as uuid),cast(? as uuid),cast(? as uuid),?,cast(? as uuid),?,?,cast(? as uuid),cast(? as timestamptz),cast(? as uuid),cast(? as timestamptz))
                    """,receiptId,context.accountId(),context.actorId(),current.toString(),commandKey,key,keys.issuanceDigest(key,canonical),proofId,time(expires),eventId,time(issued))!=1)throw conflict();
            return new Stored(new ReauthIssuerContracts.Metadata(proofId,receiptId,expires),current,eventId,issued,true);
        });
        verified.completeFor(issuer,scope,boundary,result);return result;
        }catch(Throwable failure){verified.fail();throw failure instanceof SessionSensitiveException known?known:SessionSensitiveException.unavailable();}
    }
    private Optional<PasswordCredentialState> credential(DSLContext db,CurrentTenantContext context){
        Record row=db.fetchOne(CREDENTIAL,context.actorId(),context.accountId(),context.credentialVersion());
        return row==null?Optional.empty():Optional.of(new PasswordCredentialState(row.get("credential_version",Long.class),row.get("secret_hash",String.class)));
    }
    private <T>T sql(SessionSensitiveSecurityTransaction.Scope scope,SessionSensitiveSecurityTransaction.GuardedBoundary boundary,
            java.util.function.Function<DSLContext,T> body){
        Connection connection=null;
        try{
            connection=DataSourceUtils.getConnection(source);
            if(scope!=null)boundaries.requireGuarded(scope,boundary,source,connection);
            T result=body.apply(DSL.using(connection,SQLDialect.POSTGRES,new Settings().withExecuteLogging(false).withFetchWarnings(false)));
            if(scope!=null)boundaries.requireGuarded(scope,boundary,source,connection);
            return result;
        }catch(Throwable failure){
            if(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder holder)holder.setRollbackOnly();
            throw failure instanceof SessionSensitiveException known?known:SessionSensitiveException.unavailable();
        }finally{
            try{DataSourceUtils.doReleaseConnection(connection,source);}
            catch(Throwable failure){
                if(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder holder)holder.setRollbackOnly();
                throw SessionSensitiveException.unavailable();
            }
        }
    }
    private static Instant instant(Record record,String field){return record.get(field,OffsetDateTime.class).toInstant();}
    private static OffsetDateTime time(Instant instant){return instant.atOffset(ZoneOffset.UTC);}
    private static SessionSensitiveException conflict(){return new SessionSensitiveException(SessionSensitiveException.Reason.CONFLICT);}
}

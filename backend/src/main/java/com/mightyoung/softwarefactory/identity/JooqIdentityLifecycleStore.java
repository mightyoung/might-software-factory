package com.mightyoung.softwarefactory.identity;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.ConnectionHolder;
import javax.sql.DataSource;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import static com.mightyoung.softwarefactory.identity.IdentityLifecycleContracts.*;

/** Only global identity tables are queried here; membership/audit SQL remains in owning modules. */
@Repository
@ConditionalOnProperty(prefix="software-factory.identity.lifecycle",name="enabled",havingValue="true")
public final class JooqIdentityLifecycleStore implements IdentityLifecycleStore {
    private final DSLContext hints;
    private final IdentityLifecycleSecurityTransaction writer;
    private final DataSource source;
    public JooqIdentityLifecycleStore(DSLContext dsl,DataSource source,IdentityLifecycleSecurityTransaction writer) {
        this.hints=DSL.using(dsl.configuration().derive(((Settings)dsl.configuration().settings().clone()).withExecuteLogging(false)));
        this.writer=writer;
        this.source=source;
    }
    @Override public Optional<ChallengeSnapshot> readChallenge(UUID id) {
        outside();required(id);return Optional.ofNullable(challenge(hints.fetchOne("select * from public.identity_credential_challenge where id=cast(? as uuid)",id)));
    }
    @Override public Optional<AccountSnapshot> readLocalAccount(String login) {
        outside();text(login,200);
        return Optional.ofNullable(account(hints.fetchOne(ACCOUNT_SQL+" where lower(btrim(a.login_key))=?",login)));
    }
    @Override public Optional<Receipt> readReceipt(Operation operation,OwnerKind kind,UUID ownerId,UUID key) {
        outside();return Optional.ofNullable(receipt(hints.fetchOne("""
                select * from public.identity_lifecycle_command_receipt where operation=? and owner_kind=?
                 and owner_id=cast(? as uuid) and command_key=cast(? as uuid)
                """,operation.name(),kind.name(),ownerId,key)));
    }
    @Override public java.util.List<DeliveryAttempt> expiredDeliveries(int limit) {
        outside();if(limit<1||limit>32)throw invalid();
        return hints.fetch("""
                select d.* from public.identity_capability_delivery_attempt d
                 where d.state in ('PREPARED','DISPATCHING') and d.delivery_deadline_at<=statement_timestamp()
                   and not exists(select 1 from public.identity_capability_delivery_attempt newer
                    where newer.challenge_id=d.challenge_id and newer.version>d.version)
                 order by d.delivery_deadline_at,d.id limit ?
                """,limit).map(JooqIdentityLifecycleStore::delivery);
    }
    @Override public Instant databaseNow(IdentityLifecycleSecurityTransaction.Scope scope) {
        return sql(scope,db->db.fetchOne("select statement_timestamp() as now").get("now",OffsetDateTime.class).toInstant());
    }
    @Override public boolean loginExists(IdentityLifecycleSecurityTransaction.Scope scope,String login) {
        text(login,200);return sql(scope,db->Boolean.TRUE.equals(db.fetchValue("select exists(select 1 from public.account where lower(btrim(login_key))=?)",login)));
    }
    @Override public ChallengeSnapshot lockChallenge(IdentityLifecycleSecurityTransaction.Scope scope,UUID id) {
        if(!scope.challengeId().equals(id))throw IdentityLifecycleException.rejected();
        return sql(scope,db->{var row=challenge(db.fetchOne("select * from public.identity_credential_challenge where id=cast(? as uuid) for update",id));
            if(row==null)throw IdentityLifecycleException.rejected();return row;});
    }
    @Override public Optional<AccountSnapshot> localAccount(IdentityLifecycleSecurityTransaction.Scope scope,UUID id) {
        return sql(scope,db->Optional.ofNullable(account(db.fetchOne(ACCOUNT_SQL+" where a.id=cast(? as uuid) for update of a",id))));
    }
    @Override public boolean activeHuman(IdentityLifecycleSecurityTransaction.Scope scope,UUID accountId,UUID actorId) {
        return sql(scope,db->Boolean.TRUE.equals(db.fetchValue("""
                select exists(select 1 from public.account a join public.principal p on p.account_id=a.id
                 where a.id=cast(? as uuid) and p.id=cast(? as uuid) and a.status='ACTIVE'
                   and p.kind='HUMAN' and p.status='ACTIVE')
                """,accountId,actorId)));
    }
    @Override public Optional<RecoveryBinding> recovery(IdentityLifecycleSecurityTransaction.Scope scope,UUID accountId) {
        return sql(scope,db->{Record row=db.fetchOne("""
                select id,account_id,channel,destination,version,status from public.account_recovery_contact
                 where account_id=cast(? as uuid) and status='ACTIVE' and channel='EMAIL' for update
                """,accountId);
            return row==null?Optional.empty():Optional.of(new RecoveryBinding(row.get("id",UUID.class),row.get("account_id",UUID.class),
                    new Destination(row.get("channel",String.class),row.get("destination",String.class)),row.get("version",Long.class),"ACTIVE".equals(row.get("status",String.class))));});
    }
    @Override public Optional<ChallengeSnapshot> liveReset(IdentityLifecycleSecurityTransaction.Scope scope,UUID accountId,Instant now) {
        return sql(scope,db->Optional.ofNullable(challenge(db.fetchOne("""
                select * from public.identity_credential_challenge where target_account_id=cast(? as uuid)
                 and purpose='PASSWORD_RESET' and state='ISSUED' and expires_at>cast(? as timestamptz) for update
                """,accountId,time(now)))));
    }
    @Override public Optional<Receipt> receipt(IdentityLifecycleSecurityTransaction.Scope scope,Operation operation,OwnerKind kind,UUID ownerId,UUID key) {
        return sql(scope,db->Optional.ofNullable(receipt(db.fetchOne("""
                select * from public.identity_lifecycle_command_receipt where operation=? and owner_kind=?
                 and owner_id=cast(? as uuid) and command_key=cast(? as uuid)
                """,operation.name(),kind.name(),ownerId,key))));
    }
    @Override public RateDecision reserveRate(IdentityLifecycleSecurityTransaction.Scope scope,String selector,Instant window,int maximum,int capacity) {
        hash(selector);if(maximum<1||maximum>1000||capacity<1||capacity>100_000)throw invalid();
        return sql(scope,db->{
            Long size=db.fetchOne("select count(*) from public.identity_lifecycle_rate_bucket").get(0,Long.class);
            if(size==null||size>=capacity)return RateDecision.CAPACITY;
            Record current=db.fetchOne("select attempts from public.identity_lifecycle_rate_bucket where selector_digest=? and window_start=cast(? as timestamptz) for update",selector,time(window));
            if(current!=null){int count=current.get(0,Integer.class);if(count>=maximum)return RateDecision.SELECTOR_LIMIT;
                one(db.execute("update public.identity_lifecycle_rate_bucket set attempts=attempts+1 where selector_digest=? and window_start=cast(? as timestamptz) and attempts=?",selector,time(window),count));return RateDecision.ADMITTED;}
            one(db.execute("insert into public.identity_lifecycle_rate_bucket(selector_digest,window_start,attempts) values(?,cast(? as timestamptz),1)",selector,time(window)));
            return RateDecision.ADMITTED;
        });
    }
    @Override public void issue(IdentityLifecycleSecurityTransaction.Scope scope,ChallengeSnapshot c,String destinationDigest,DeliveryAttempt delivery) {
        hash(destinationDigest);if((scope.operation()!=Operation.REISSUE_INVITATION&&!scope.challengeId().equals(c.id()))||c.state()!=State.ISSUED||c.version()!=1
                ||!delivery.challengeId().equals(c.id())||delivery.state()!=DeliveryState.PREPARED||delivery.version()!=1)throw invalid();
        sql(scope,db->{IssuerBinding i=c.issuer();one(db.execute("""
                insert into public.identity_credential_challenge(id,purpose,target_account_id,target_tenant_id,
                 issuer_account_id,issuer_actor_id,issuer_tenant_id,issuer_member_id,issuer_credential_version,issuer_tenant_epoch,issuer_member_epoch,
                 intended_login,display_name,channel,destination,destination_digest,recovery_contact_id,issued_contact_version,
                 issued_credential_version,secret_digest,state,version,created_at,expires_at)
                values(cast(? as uuid),?,cast(? as uuid),cast(? as uuid),cast(? as uuid),cast(? as uuid),cast(? as uuid),cast(? as uuid),?,?,?,
                 ?,?,?,?,?,cast(? as uuid),?,?,?,'ISSUED',1,cast(? as timestamptz),cast(? as timestamptz))
                """,c.id(),c.purpose().name(),c.accountId(),c.tenantId(),i==null?null:i.accountId(),i==null?null:i.actorId(),
                    i==null?null:i.tenantId(),i==null?null:i.memberId(),i==null?null:i.credentialVersion(),i==null?null:i.tenantAuthEpoch(),
                    i==null?null:i.memberAuthEpoch(),c.intendedLogin(),c.displayName(),c.destination().channel(),c.destination().value(),
                    destinationDigest,c.recoveryContactId(),c.contactVersion(),c.issuedCredentialVersion(),c.secretDigest(),time(c.createdAt()),time(c.expiresAt())));
            insertDelivery(db,delivery);return null;});
    }
    @Override public void receipt(IdentityLifecycleSecurityTransaction.Scope scope,Receipt r) {
        if(!scope.challengeId().equals(r.challengeId())||scope.operation()!=r.operation())throw invalid();
        sql(scope,db->{one(db.execute("""
                insert into public.identity_lifecycle_command_receipt(id,operation,owner_kind,owner_id,command_key,challenge_id,
                 fingerprint_key_id,fingerprint,result_account_id,result_actor_id,result_member_id,result_contact_id,
                 result_challenge_id,result_version,result_expires_at,result_delivery_state,completed_at)
                values(cast(? as uuid),?,?,cast(? as uuid),cast(? as uuid),cast(? as uuid),?,?,cast(? as uuid),cast(? as uuid),
                 cast(? as uuid),cast(? as uuid),cast(? as uuid),?,cast(? as timestamptz),?,cast(? as timestamptz))
                """,r.id(),r.operation().name(),r.ownerKind().name(),r.ownerId(),r.commandKey(),r.challengeId(),r.fingerprintKeyId(),r.fingerprint(),
                    r.resultAccountId(),r.resultActorId(),r.resultMemberId(),r.resultContactId(),r.resultChallengeId(),r.resultVersion(),time(r.resultExpiresAt()),r.resultDeliveryState()==null?null:r.resultDeliveryState().name(),time(r.completedAt())));return null;});
    }
    @Override public void terminal(IdentityLifecycleSecurityTransaction.Scope scope,ChallengeSnapshot expected,State state,UUID receiptId) {
        if(expected.state()!=State.ISSUED||state==State.ISSUED||(state==State.CONSUMED)!=(receiptId!=null))throw IdentityLifecycleException.rejected();
        sql(scope,db->{one(db.execute("""
                update public.identity_credential_challenge set state=?,version=version+1,consumed_receipt_id=cast(? as uuid)
                 where id=cast(? as uuid) and state='ISSUED' and version=?
                """,state.name(),receiptId,expected.id(),expected.version()));return null;});
    }
    @Override public void expireResets(IdentityLifecycleSecurityTransaction.Scope scope,UUID accountId,Instant now) {
        sql(scope,db->{db.execute("""
                update public.identity_credential_challenge set state='EXPIRED',version=version+1
                 where target_account_id=cast(? as uuid) and purpose='PASSWORD_RESET' and state='ISSUED'
                   and expires_at<=cast(? as timestamptz)
                """,accountId,time(now));return null;});
    }
    @Override public void expireInvitations(IdentityLifecycleSecurityTransaction.Scope scope,ChallengeSnapshot match,String digest,Instant now) {
        hash(digest);if(match.tenantId()==null||!match.tenantId().equals(scope.targetTenantId()))throw invalid();
        sql(scope,db->{db.execute("""
                update public.identity_credential_challenge set state='EXPIRED',version=version+1
                 where target_tenant_id=cast(? as uuid) and purpose=? and destination_digest=?
                   and intended_login is not distinct from cast(? as varchar)
                   and target_account_id is not distinct from cast(? as uuid)
                   and state='ISSUED' and expires_at<=cast(? as timestamptz)
                """,match.tenantId(),match.purpose().name(),digest,match.intendedLogin(),match.accountId(),time(now));return null;});
    }
    @Override public void revokeOtherResets(IdentityLifecycleSecurityTransaction.Scope scope,UUID accountId,UUID exceptId) {
        sql(scope,db->{db.execute("""
                update public.identity_credential_challenge set state='REVOKED',version=version+1
                 where target_account_id=cast(? as uuid) and purpose='PASSWORD_RESET' and state='ISSUED' and id<>cast(? as uuid)
                """,accountId,exceptId);return null;});
    }
    @Override public AccountSnapshot createAccount(IdentityLifecycleSecurityTransaction.Scope scope,UUID accountId,UUID actorId,String login,String encodedPassword,Instant now) {
        if(scope.operation()!=Operation.CONSUME_INVITATION)throw invalid();text(login,200);passwordHash(encodedPassword);
        return sql(scope,db->{
            one(db.execute("insert into public.account(id,login_key,status,credential_version) values(cast(? as uuid),?,'ACTIVE',1)",accountId,login));
            one(db.execute("insert into public.principal(id,kind,account_id,status) values(cast(? as uuid),'HUMAN',cast(? as uuid),'ACTIVE')",actorId,accountId));
            one(db.execute("insert into public.credential(account_id,kind,secret_hash,changed_at) values(cast(? as uuid),'PASSWORD',?,cast(? as timestamptz))",accountId,encodedPassword,time(now)));
            return new AccountSnapshot(accountId,actorId,login,"ACTIVE",1,encodedPassword);
        });
    }
    @Override public void createRecovery(IdentityLifecycleSecurityTransaction.Scope scope,RecoveryBinding binding,UUID challengeId,Instant now) {
        if(scope.operation()!=Operation.CONSUME_INVITATION||!binding.active()||binding.version()!=1)throw invalid();
        sql(scope,db->{one(db.execute("""
                insert into public.account_recovery_contact(id,account_id,channel,destination,status,version,verified_at,verified_by_challenge_id)
                 values(cast(? as uuid),cast(? as uuid),?,?,'ACTIVE',1,cast(? as timestamptz),cast(? as uuid))
                """,binding.id(),binding.accountId(),binding.destination().channel(),binding.destination().value(),time(now),challengeId));return null;});
    }
    @Override public void resetPassword(IdentityLifecycleSecurityTransaction.Scope scope,AccountSnapshot expected,String encodedPassword,Instant now) {
        if(scope.operation()!=Operation.CONSUME_RESET||expected.credentialVersion()==Long.MAX_VALUE)throw invalid();passwordHash(encodedPassword);
        sql(scope,db->{
            one(db.execute("""
                    update public.credential set secret_hash=?,changed_at=cast(? as timestamptz)
                     where account_id=cast(? as uuid) and kind='PASSWORD' and secret_hash=?
                       and exists(select 1 from public.account a join public.principal p on p.account_id=a.id
                        where a.id=cast(? as uuid) and a.status='ACTIVE' and a.credential_version=?
                          and p.id=cast(? as uuid) and p.kind='HUMAN' and p.status='ACTIVE')
                    """,encodedPassword,time(now),expected.accountId(),expected.passwordHash(),expected.accountId(),expected.credentialVersion(),expected.actorId()));
            one(db.execute("update public.account set credential_version=credential_version+1 where id=cast(? as uuid) and credential_version=? and status='ACTIVE'",expected.accountId(),expected.credentialVersion()));return null;
        });
    }
    @Override public Optional<DeliveryAttempt> delivery(IdentityLifecycleSecurityTransaction.Scope scope,UUID challengeId) {
        return sql(scope,db->Optional.ofNullable(delivery(db.fetchOne("select * from public.identity_capability_delivery_attempt where challenge_id=cast(? as uuid) order by version desc limit 1",challengeId))));
    }
    @Override public void deliveryTransition(IdentityLifecycleSecurityTransaction.Scope scope,DeliveryAttempt expected,DeliveryState next,Instant now) {
        sql(scope,db->{DeliveryAttempt actual=delivery(db.fetchOne("select * from public.identity_capability_delivery_attempt where challenge_id=cast(? as uuid) order by version desc limit 1",expected.challengeId()));
            if(!expected.equals(actual))throw IdentityLifecycleException.rejected();
            insertDelivery(db,new DeliveryAttempt(expected.id(),expected.challengeId(),expected.providerGeneration(),expected.templateVersion(),next,Math.addExact(expected.version(),1),expected.originInstanceId(),expected.deliveryDeadlineAt(),expected.createdAt(),now));return null;});
    }
    private static void insertDelivery(DSLContext db,DeliveryAttempt d) {
        one(db.execute("""
                insert into public.identity_capability_delivery_attempt(id,challenge_id,provider_generation,template_version,state,version,origin_instance_id,delivery_deadline_at,created_at,occurred_at)
                 values(cast(? as uuid),cast(? as uuid),?,?,?,?,cast(? as uuid),cast(? as timestamptz),cast(? as timestamptz),cast(? as timestamptz))
                """,d.id(),d.challengeId(),d.providerGeneration(),d.templateVersion(),d.state().name(),d.version(),d.originInstanceId(),time(d.deliveryDeadlineAt()),time(d.createdAt()),time(d.occurredAt())));
    }
    private <T>T sql(IdentityLifecycleSecurityTransaction.Scope scope,Function<DSLContext,T> work) {
        writer.requireActive(scope);
        var connection=DataSourceUtils.getConnection(source);
        try {
            writer.requireSqlConnection(scope,source,connection);
            T result=work.apply(DSL.using(connection,SQLDialect.POSTGRES,new Settings().withExecuteLogging(false)));
            writer.requireSqlConnection(scope,source,connection);return result;
        } catch(RuntimeException error){
            if(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder holder)holder.setRollbackOnly();
            throw error;
        } finally {DataSourceUtils.releaseConnection(connection,source);}
    }
    private static void outside(){if(TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isSynchronizationActive())throw IdentityLifecycleException.unavailable();}
    private static void one(int rows){if(rows!=1)throw IdentityLifecycleException.rejected();}
    private static OffsetDateTime time(Instant time){return time==null?null:micros(time).atOffset(ZoneOffset.UTC);}
    private static Instant instant(Record row,String field){OffsetDateTime time=row.get(field,OffsetDateTime.class);return time==null?null:time.toInstant();}
    private static void passwordHash(String hash){if(hash==null||!hash.matches("\\{bcrypt\\}\\$2[aby]\\$1[2-6]\\$[./A-Za-z0-9]{53}"))throw invalid();}
    private static final String ACCOUNT_SQL="""
            select a.id as account_id,p.id as actor_id,a.login_key,a.status,a.credential_version,c.secret_hash
             from public.account a join public.principal p on p.account_id=a.id and p.kind='HUMAN' and p.status='ACTIVE'
             join public.credential c on c.account_id=a.id and c.kind='PASSWORD'
            """;
    private static AccountSnapshot account(Record r){return r==null?null:new AccountSnapshot(r.get("account_id",UUID.class),r.get("actor_id",UUID.class),r.get("login_key",String.class),r.get("status",String.class),r.get("credential_version",Long.class),r.get("secret_hash",String.class));}
    private static DeliveryAttempt delivery(Record r){return r==null?null:new DeliveryAttempt(r.get("id",UUID.class),r.get("challenge_id",UUID.class),r.get("provider_generation",String.class),r.get("template_version",String.class),DeliveryState.valueOf(r.get("state",String.class)),r.get("version",Long.class),r.get("origin_instance_id",UUID.class),instant(r,"delivery_deadline_at"),instant(r,"created_at"),instant(r,"occurred_at"));}
    private static ChallengeSnapshot challenge(Record r) {
        if(r==null)return null;
        UUID issuerAccount=r.get("issuer_account_id",UUID.class);
        IssuerBinding issuer=issuerAccount==null?null:new IssuerBinding(issuerAccount,r.get("issuer_actor_id",UUID.class),r.get("issuer_tenant_id",UUID.class),r.get("issuer_member_id",UUID.class),r.get("issuer_credential_version",Long.class),r.get("issuer_tenant_epoch",Long.class),r.get("issuer_member_epoch",Long.class));
        return new ChallengeSnapshot(r.get("id",UUID.class),Purpose.valueOf(r.get("purpose",String.class)),r.get("target_account_id",UUID.class),r.get("target_tenant_id",UUID.class),issuer,
                r.get("intended_login",String.class),r.get("display_name",String.class),new Destination(r.get("channel",String.class),r.get("destination",String.class)),r.get("recovery_contact_id",UUID.class),
                r.get("issued_contact_version",Long.class),r.get("issued_credential_version",Long.class),r.get("secret_digest",String.class),State.valueOf(r.get("state",String.class)),r.get("version",Long.class),instant(r,"created_at"),instant(r,"expires_at"),r.get("consumed_receipt_id",UUID.class));
    }
    private static Receipt receipt(Record r){return r==null?null:new Receipt(r.get("id",UUID.class),Operation.valueOf(r.get("operation",String.class)),OwnerKind.valueOf(r.get("owner_kind",String.class)),r.get("owner_id",UUID.class),r.get("command_key",UUID.class),r.get("challenge_id",UUID.class),r.get("fingerprint_key_id",String.class),r.get("fingerprint",String.class),r.get("result_account_id",UUID.class),r.get("result_actor_id",UUID.class),r.get("result_member_id",UUID.class),r.get("result_contact_id",UUID.class),r.get("result_challenge_id",UUID.class),r.get("result_version",Long.class),instant(r,"result_expires_at"),r.get("result_delivery_state",String.class)==null?null:DeliveryState.valueOf(r.get("result_delivery_state",String.class)),instant(r,"completed_at"));}
}

package com.mightyoung.softwarefactory.identity;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.sql.DataSource;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Objects;
import java.util.function.Function;
import static com.mightyoung.softwarefactory.identity.SessionSensitiveContracts.*;

/** Safe relational metadata only; never decodes another session's serialized attributes. */
public final class JooqSessionAuthorityStore implements SessionAuthorityStore {
    private final DataSource source;
    private final SessionSensitiveSecurityTransaction boundaries;
    private final SessionSensitiveKeys keys;
    public JooqSessionAuthorityStore(DataSource source,SessionSensitiveSecurityTransaction boundaries,SessionSensitiveKeys keys){
        this.source=Objects.requireNonNull(source);this.boundaries=Objects.requireNonNull(boundaries);this.keys=Objects.requireNonNull(keys);
    }
    @Override public Enforcement enforcement(SessionSensitiveSecurityTransaction.Scope scope,SessionSensitiveSecurityTransaction.GuardedBoundary boundary){
        return write(scope,boundary,JooqSessionAuthorityStore::control);
    }
    @Override public Registration register(SessionSensitiveSecurityTransaction.Scope scope,SessionSensitiveSecurityTransaction.GuardedBoundary boundary,java.util.UUID eventId){
        if(scope.operation()!=Operation.REGISTER_SESSION)throw SessionSensitiveException.rejected();
        return write(scope,boundary,db->{
            if(control(db)!=Enforcement.ENFORCING)throw SessionSensitiveException.unavailable();
            CapturedHumanRequest request=scope.request();CurrentTenantContext context=request.context();Instant now=boundary.observedAt();
            if(request.authenticatedAt().isAfter(now)||!request.absoluteExpiresAt().isAfter(now))throw SessionSensitiveException.rejected();
            Record stored=db.fetchOne("""
                    select s.primary_id,s.principal_name from public.spring_session s
                     join public.account a on a.id=cast(? as uuid)
                     join public.principal p on p.account_id=a.id
                     join public.tenant t on t.id=cast(? as uuid)
                     join public.tenant_member m on m.tenant_id=t.id and m.account_id=a.id and m.id=cast(? as uuid)
                    where s.session_id=? and s.expiry_time>? and a.status='ACTIVE' and a.credential_version=?
                      and p.id=cast(? as uuid) and p.kind='HUMAN' and p.status='ACTIVE'
                      and t.status='ACTIVE' and t.auth_epoch=? and m.status='ACTIVE' and m.auth_epoch=?
                    """,context.accountId(),context.tenantId(),context.memberId(),request.bearerId(),now.toEpochMilli(),context.credentialVersion(),context.actorId(),context.tenantAuthEpoch(),context.memberAuthEpoch());
            if(stored==null)throw SessionSensitiveException.rejected();
            SessionHandle handle=SessionHandle.parse(stored.get("primary_id",String.class));
            Record existing=db.fetchOne("select account_id from public.account_session_authority where primary_id=?",handle.toString());
            if(existing!=null){
                if(!context.accountId().equals(existing.get("account_id",java.util.UUID.class)))throw SessionSensitiveException.rejected();
                // This assertion denies tombstones/ID changes; it never rewrites an old binding.
                SessionHandle valid=current(db,request,now);request.canonicalizeCurrentIndex();return new Registration(valid,false);
            }
            String owner=stored.get("principal_name",String.class);
            if(!context.accountId().toString().equals(owner)){
                if(owner!=null&&!"VerifiedSessionPrincipal".equals(owner))throw SessionSensitiveException.rejected();
                int changed=db.execute("""
                        update public.spring_session set principal_name=? where primary_id=? and session_id=?
                          and (principal_name is null or principal_name='VerifiedSessionPrincipal')
                        """,context.accountId().toString(),handle.toString(),request.bearerId());
                if(changed!=1)throw SessionSensitiveException.rejected();
                request.canonicalizeCurrentIndex();
            }
            request.canonicalizeCurrentIndex();
            String key=keys.currentKeyId();
            int changed=db.execute("""
                    insert into public.account_session_authority(primary_id,account_id,binding_kind,session_key_id,session_digest,
                     bound_credential_version,authenticated_at,absolute_expires_at,state,version,created_at,created_event_id)
                    values(?,cast(? as uuid),'CURRENT_REQUEST',?,?,?,cast(? as timestamptz),cast(? as timestamptz),
                     'ACTIVE',1,cast(? as timestamptz),cast(? as uuid))
                    """,handle.toString(),context.accountId(),key,keys.sessionDigest(key,request.bearerId()),context.credentialVersion(),
                    time(request.authenticatedAt()),time(request.absoluteExpiresAt()),time(now),eventId);
            if(changed!=1)throw SessionSensitiveException.rejected();return new Registration(handle,true);
        });
    }
    @Override public SessionHandle requireCurrent(SessionSensitiveSecurityTransaction.Scope scope,SessionSensitiveSecurityTransaction.GuardedBoundary boundary){
        return write(scope,boundary,db->current(db,scope.request(),boundary.observedAt()));
    }
    @Override public SessionHandle requireCurrent(CapturedHumanRequest request,SessionSensitiveSecurityTransaction.ReadSnapshotBoundary boundary){
        return read(request,boundary,db->current(db,request,boundary.observedAt()));
    }
    @Override public SessionPage readOwnPage(CapturedHumanRequest request,SessionSensitiveSecurityTransaction.ReadSnapshotBoundary boundary,SessionHandle after,int limit){
        if(limit<1||limit>50)throw new IllegalArgumentException("Limit must be between one and fifty");
        return read(request,boundary,db->{
            Instant now=boundary.observedAt();SessionHandle current=current(db,request,now);
            var rows=db.fetch("""
                    select s.primary_id,s.creation_time,s.last_access_time,s.expiry_time,a.state,a.version,a.account_id as authority_account_id
                      from public.spring_session s left join public.account_session_authority a on a.primary_id=s.primary_id
                     where s.principal_name=? and s.expiry_time>? and (cast(? as varchar) is null or s.primary_id>?)
                     order by s.primary_id limit ?
                    """,request.context().accountId().toString(),now.toEpochMilli(),after==null?null:after.toString(),
                    after==null?null:after.toString(),limit+1);
            var items=new ArrayList<SessionItem>();
            for(int i=0;i<Math.min(limit,rows.size());i++){
                Record row=rows.get(i);SessionHandle handle=SessionHandle.parse(row.get("primary_id",String.class));
                String state=row.get("state",String.class);Long version=row.get("version",Long.class);
                if(state!=null&&!request.context().accountId().equals(row.get("authority_account_id",java.util.UUID.class)))throw SessionSensitiveException.unavailable();
                items.add(new SessionItem(handle,handle.equals(current),millis(row,"creation_time"),millis(row,"last_access_time"),millis(row,"expiry_time"),
                        state==null?AuthorityState.ABSENT:AuthorityState.valueOf(state),version==null?0:version));
            }
            // COMPLETE requires separately reviewed old-writer drain and target activation evidence.
            return new SessionPage(items,Coverage.INCOMPLETE_LEGACY,rows.size()>limit?items.getLast().handle():null);
        });
    }
    @Override public FenceResult revoke(SessionSensitiveSecurityTransaction.Scope scope,SessionSensitiveSecurityTransaction.GuardedBoundary boundary,RevokeCommand command,java.util.UUID eventId){
        if(scope.operation()!=Operation.REVOKE_SESSION)throw SessionSensitiveException.rejected();
        return write(scope,boundary,db->{
            Instant now=boundary.observedAt();CapturedHumanRequest request=scope.request();CurrentTenantContext context=request.context();
            SessionHandle sourceHandle=current(db,request,now);
            if(sourceHandle.equals(command.target()))throw SessionSensitiveException.rejected();
            OwnedTarget target=owned(db,context.accountId(),command.target());
            String canonical=commandCanonical(context,sourceHandle,command);
            // Completed receipt precedes ALL mutable target-version/proof-state/expiry predicates.
            Record receipt=db.fetchOne("""
                    select target_primary_id,expected_authority_version,fingerprint_key_id,fingerprint,proof_id,
                           result_authority_version,event_id,completed_at
                      from public.session_authority_command_receipt
                     where operation='REVOKE_SESSION' and account_id=cast(? as uuid) and source_primary_id=? and command_key=cast(? as uuid)
                    """,context.accountId(),sourceHandle.toString(),command.commandKey());
            if(receipt!=null){
                if(!command.target().toString().equals(receipt.get("target_primary_id",String.class))
                        ||command.expected().version()!=receipt.get("expected_authority_version",Long.class)
                        ||!command.proofId().equals(receipt.get("proof_id",java.util.UUID.class))
                        ||!keys.matchesCommand(receipt.get("fingerprint_key_id",String.class),receipt.get("fingerprint",String.class),canonical))throw conflict();
                return new FenceResult(sourceHandle,command.target(),receipt.get("result_authority_version",Long.class),false,
                        receipt.get("event_id",java.util.UUID.class),receipt.get("completed_at",OffsetDateTime.class).toInstant());
            }
            if(target.version()!=command.expected().version()||target.revoked())throw conflict();
            Record proof=db.fetchOne("select * from public.sensitive_reauth_proof where id=cast(? as uuid)",command.proofId());
            if(proof==null||!"SESSION_REVOKE".equals(proof.get("action",String.class))||!"ISSUED".equals(proof.get("state",String.class))
                    ||!context.accountId().equals(proof.get("account_id",java.util.UUID.class))||!context.actorId().equals(proof.get("actor_id",java.util.UUID.class))
                    ||!sourceHandle.toString().equals(proof.get("source_primary_id",String.class))
                    ||context.credentialVersion()!=proof.get("issued_credential_version",Long.class)
                    ||!command.target().toString().equals(proof.get("target_primary_id",String.class))
                    ||command.expected().version()!=proof.get("expected_authority_version",Long.class)
                    ||!proof.get("expires_at",OffsetDateTime.class).toInstant().isAfter(now)
                    ||!command.secret().matches(proof.get("secret_digest",String.class))
                    ||!keys.matchesAction(proof.get("body_key_id",String.class),proof.get("body_digest",String.class),actionCanonical(context,sourceHandle,command)))throw SessionSensitiveException.rejected();
            java.util.UUID receiptId=java.util.UUID.randomUUID();
            int consumed=db.execute("""
                    update public.sensitive_reauth_proof set state='CONSUMED',version=version+1,session_consume_receipt_id=cast(? as uuid)
                     where id=cast(? as uuid) and state='ISSUED' and version=? and expires_at>statement_timestamp()
                    """,receiptId,command.proofId(),proof.get("version",Long.class));
            if(consumed!=1)throw conflict();
            long version=fence(db,context.accountId(),command.target(),target,false,eventId,now);
            String key=keys.currentKeyId();
            int inserted=db.execute("""
                    insert into public.session_authority_command_receipt(id,operation,account_id,actor_id,source_primary_id,target_primary_id,
                     command_key,expected_authority_version,fingerprint_key_id,fingerprint,proof_id,result_authority_version,event_id,completed_at)
                    values(cast(? as uuid),'REVOKE_SESSION',cast(? as uuid),cast(? as uuid),?,?,cast(? as uuid),?,?,?,cast(? as uuid),?,cast(? as uuid),cast(? as timestamptz))
                    """,receiptId,context.accountId(),context.actorId(),sourceHandle.toString(),command.target().toString(),command.commandKey(),
                    command.expected().version(),key,keys.commandDigest(key,canonical),command.proofId(),version,eventId,time(now));
            if(inserted!=1)throw conflict();return new FenceResult(sourceHandle,command.target(),version,true,eventId,now);
        });
    }
    @Override public FenceResult logout(SessionSensitiveSecurityTransaction.Scope scope,SessionSensitiveSecurityTransaction.GuardedBoundary boundary,java.util.UUID eventId){
        if(scope.operation()!=Operation.LOGOUT)throw SessionSensitiveException.rejected();
        return write(scope,boundary,db->{
            if(control(db)!=Enforcement.ENFORCING)throw SessionSensitiveException.unavailable();
            CapturedHumanRequest request=scope.request();CurrentTenantContext context=request.context();Instant now=boundary.observedAt();
            Record stored=db.fetchOne("select primary_id from public.spring_session where session_id=? and principal_name=?",request.bearerId(),context.accountId().toString());
            // Retained lineage may prove this actual ID after framework row cleanup. Scan only
            // the bounded retained key set, never client handles or serialized attributes.
            SessionHandle handle=stored==null?null:SessionHandle.parse(stored.get("primary_id",String.class));
            if(handle==null){
                for(var candidate:keys.sessionCandidates(request.bearerId()).entrySet()){
                    Record retained=db.fetchOne("select primary_id from public.account_session_authority where account_id=cast(? as uuid) and session_key_id=? and session_digest=?",
                            context.accountId(),candidate.getKey(),candidate.getValue());
                    if(retained!=null){
                        SessionHandle found=SessionHandle.parse(retained.get("primary_id",String.class));
                        if(handle!=null&&!handle.equals(found))throw SessionSensitiveException.unavailable();handle=found;
                    }
                }
            }
            if(handle==null)throw new SessionSensitiveException(SessionSensitiveException.Reason.NO_TRUSTED_LINEAGE);
            OwnedTarget target=owned(db,context.accountId(),handle);
            if(target.authority()!=null&&!keys.matchesSession(target.authority().get("session_key_id",String.class),target.authority().get("session_digest",String.class),request.bearerId()))throw SessionSensitiveException.rejected();
            if(target.revoked()){
                Record original=db.fetchOne("""
                        select event_id,completed_at from public.session_authority_command_receipt
                         where target_primary_id=? and account_id=cast(? as uuid) and result_authority_version=?
                        """,handle.toString(),context.accountId(),target.version());
                if(original==null)throw SessionSensitiveException.unavailable();
                return new FenceResult(handle,handle,target.version(),false,original.get("event_id",java.util.UUID.class),original.get("completed_at",OffsetDateTime.class).toInstant());
            }
            long version=fence(db,context.accountId(),handle,target,true,eventId,now);
            int inserted=db.execute("""
                    insert into public.session_authority_command_receipt(id,operation,account_id,actor_id,source_primary_id,target_primary_id,
                     expected_authority_version,result_authority_version,event_id,completed_at)
                    values(cast(? as uuid),'LOGOUT',cast(? as uuid),cast(? as uuid),?,?,?,?,cast(? as uuid),cast(? as timestamptz))
                    """,java.util.UUID.randomUUID(),context.accountId(),context.actorId(),handle.toString(),handle.toString(),target.version(),version,eventId,time(now));
            if(inserted!=1)throw conflict();return new FenceResult(handle,handle,version,true,eventId,now);
        });
    }
    private OwnedTarget owned(DSLContext db,java.util.UUID account,SessionHandle handle){
        Record stored=db.fetchOne("select session_id,principal_name from public.spring_session where primary_id=?",handle.toString());
        Record authority=db.fetchOne("select * from public.account_session_authority where primary_id=?",handle.toString());
        if(authority!=null&&!account.equals(authority.get("account_id",java.util.UUID.class)))throw SessionSensitiveException.rejected();
        if(stored!=null&&!account.toString().equals(stored.get("principal_name",String.class)))throw SessionSensitiveException.rejected();
        if(authority==null&&stored==null)throw SessionSensitiveException.rejected();
        return new OwnedTarget(stored,authority);
    }
    private long fence(DSLContext db,java.util.UUID account,SessionHandle handle,OwnedTarget target,boolean logout,java.util.UUID eventId,Instant now){
        long next=Math.addExact(target.version(),1);int changed;
        if(target.authority()==null){
            if(target.stored()==null)throw SessionSensitiveException.rejected();
            String key=keys.currentKeyId();
            changed=db.execute("""
                    insert into public.account_session_authority(primary_id,account_id,binding_kind,session_key_id,session_digest,
                     state,version,created_at,created_event_id,revoked_at,revocation_event_id)
                    values(?,cast(? as uuid),?,?,?,'REVOKED',1,cast(? as timestamptz),cast(? as uuid),cast(? as timestamptz),cast(? as uuid))
                    """,handle.toString(),account,logout?"KNOWN_LOGOUT":"REMOTE_ABSENCE",key,keys.sessionDigest(key,target.stored().get("session_id",String.class)),time(now),eventId,time(now),eventId);
        }else{
            changed=db.execute("""
                    update public.account_session_authority set state='REVOKED',version=version+1,revoked_at=cast(? as timestamptz),revocation_event_id=cast(? as uuid)
                     where primary_id=? and account_id=cast(? as uuid) and state='ACTIVE' and version=?
                    """,time(now),eventId,handle.toString(),account,target.version());
        }
        if(changed!=1)throw conflict();return next;
    }
    private record OwnedTarget(Record stored,Record authority){
        long version(){return authority==null?0:authority.get("version",Long.class);}
        boolean revoked(){return authority!=null&&"REVOKED".equals(authority.get("state",String.class));}
        @Override public String toString(){return "OwnedSessionTarget[REDACTED]";}
    }
    private static String actionCanonical(CurrentTenantContext context,SessionHandle source,RevokeCommand command){
        return context.accountId()+"|"+context.actorId()+"|"+source+"|"+context.credentialVersion()+"|"+command.target()+"|"+command.expected().version();
    }
    private static String commandCanonical(CurrentTenantContext context,SessionHandle source,RevokeCommand command){
        return actionCanonical(context,source,command)+"|"+command.proofId()+"|"+command.secret().digest();
    }
    private static SessionSensitiveException conflict(){return new SessionSensitiveException(SessionSensitiveException.Reason.CONFLICT);}
    private SessionHandle current(DSLContext db,CapturedHumanRequest request,Instant now){
        if(control(db)!=Enforcement.ENFORCING)throw SessionSensitiveException.unavailable();
        request.requireCurrent();CurrentTenantContext context=request.context();
        Record row=db.fetchOne("""
                select stored.primary_id,authority.session_key_id,authority.session_digest,
                       authority.authenticated_at,authority.absolute_expires_at
                  from public.spring_session stored
                  join public.account_session_authority authority on authority.primary_id=stored.primary_id
                  join public.account a on a.id=authority.account_id
                  join public.principal p on p.account_id=a.id
                  join public.tenant t on t.id=cast(? as uuid)
                  join public.tenant_member m on m.tenant_id=t.id and m.account_id=a.id and m.id=cast(? as uuid)
                 where stored.session_id=? and stored.principal_name=? and authority.account_id=cast(? as uuid)
                   and stored.expiry_time>? and authority.state='ACTIVE'
                   and authority.bound_credential_version=? and authority.authenticated_at<=cast(? as timestamptz)
                   and authority.absolute_expires_at>cast(? as timestamptz)
                   and a.status='ACTIVE' and a.credential_version=?
                   and p.id=cast(? as uuid) and p.kind='HUMAN' and p.status='ACTIVE'
                   and t.status='ACTIVE' and t.auth_epoch=? and m.status='ACTIVE' and m.auth_epoch=?
                """,context.tenantId(),context.memberId(),request.bearerId(),context.accountId().toString(),context.accountId(),
                now.toEpochMilli(),context.credentialVersion(),time(now),time(now),context.credentialVersion(),context.actorId(),context.tenantAuthEpoch(),context.memberAuthEpoch());
        if(row==null||!request.authenticatedAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS).equals(row.get("authenticated_at",OffsetDateTime.class).toInstant())
                ||!request.absoluteExpiresAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS).equals(row.get("absolute_expires_at",OffsetDateTime.class).toInstant())
                ||!keys.matchesSession(row.get("session_key_id",String.class),row.get("session_digest",String.class),request.bearerId()))throw SessionSensitiveException.rejected();
        request.requireCurrent();return SessionHandle.parse(row.get("primary_id",String.class));
    }
    private static Enforcement control(DSLContext db){
        Record row=db.fetchOne("select state,version,activation_evidence_sha256 from public.session_authority_activation where singleton=true");
        if(row==null)throw SessionSensitiveException.unavailable();
        String state=row.get("state",String.class);Long version=row.get("version",Long.class);
        if("OFF".equals(state)&&Long.valueOf(0).equals(version)&&row.get("activation_evidence_sha256")==null)return Enforcement.OFF;
        if("ENFORCING".equals(state)&&Long.valueOf(1).equals(version)&&row.get("activation_evidence_sha256",String.class)!=null
                &&row.get("activation_evidence_sha256",String.class).matches("[0-9a-f]{64}"))return Enforcement.ENFORCING;
        throw SessionSensitiveException.unavailable();
    }
    private <T> T write(SessionSensitiveSecurityTransaction.Scope scope,SessionSensitiveSecurityTransaction.GuardedBoundary boundary,Function<DSLContext,T> work){
        return sql(connection->boundaries.requireGuarded(scope,boundary,source,connection),work);
    }
    private <T> T read(CapturedHumanRequest request,SessionSensitiveSecurityTransaction.ReadSnapshotBoundary boundary,Function<DSLContext,T> work){
        return sql(connection->boundaries.requireSnapshot(request,boundary,source,connection),work);
    }
    private <T> T sql(java.util.function.Consumer<java.sql.Connection> assertion,Function<DSLContext,T> work){
        var connection=DataSourceUtils.getConnection(source);
        try {
            assertion.accept(connection);DSLContext db=DSL.using(connection,SQLDialect.POSTGRES,new Settings().withExecuteLogging(false));
            T result=work.apply(db);assertion.accept(connection);return result;
        }catch(RuntimeException failure){
            if(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder holder)holder.setRollbackOnly();
            if(failure instanceof SessionSensitiveException known)throw known;
            throw SessionSensitiveException.unavailable();
        }finally{DataSourceUtils.releaseConnection(connection,source);}
    }
    private static Instant millis(Record row,String name){return Instant.ofEpochMilli(row.get(name,Long.class));}
    private static OffsetDateTime time(Instant instant){return instant.truncatedTo(java.time.temporal.ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);}
}

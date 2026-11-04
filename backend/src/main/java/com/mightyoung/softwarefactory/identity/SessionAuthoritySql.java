package com.mightyoung.softwarefactory.identity;

import org.jooq.DSLContext;
import org.jooq.Record;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import static com.mightyoung.softwarefactory.identity.SessionSensitiveContracts.*;

/** Identity-internal SQL, called only after the owning boundary asserts its connection. */
public final class SessionAuthoritySql {
    private static final String CURRENT_SQL = """
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
                """;

    private static final String CONTROL_SQL = "select state,version,activated_at,activated_by_database_role,activation_event_id,activation_evidence_sha256 from public.session_authority_activation where singleton=true";
    /** Fixed SQL used by the caller-owned current authority, including persisted OFF checks. */
    public static java.util.Map<String,Integer> commandReadSql() {
        return java.util.Map.of(CONTROL_SQL,0,CURRENT_SQL,13);
    }
    private SessionAuthoritySql(){}
    static SessionHandle current(DSLContext db,CapturedHumanRequest request,Instant now,SessionSensitiveKeys keys){
        return current(db,request,now,keys,false);
    }
    static SessionHandle current(DSLContext db,CapturedHumanRequest request,Instant now,SessionSensitiveKeys keys,boolean typed){
        if(control(db,typed)!=Enforcement.ENFORCING)throw SessionSensitiveException.unavailable();
        request.requireCurrent();CurrentTenantContext context=request.context();
        var query=db.resultQuery(CURRENT_SQL,context.tenantId(),context.memberId(),request.bearerId(),context.accountId().toString(),context.accountId(),
                now.toEpochMilli(),context.credentialVersion(),time(now),time(now),context.credentialVersion(),context.actorId(),context.tenantAuthEpoch(),context.memberAuthEpoch());
        Record row=typed?query.coerce(
                org.jooq.impl.DSL.field("primary_id",String.class),org.jooq.impl.DSL.field("session_key_id",String.class),
                org.jooq.impl.DSL.field("session_digest",String.class),org.jooq.impl.DSL.field("authenticated_at",OffsetDateTime.class),
                org.jooq.impl.DSL.field("absolute_expires_at",OffsetDateTime.class)).fetchOne():query.fetchOne();
        if(row==null||!request.authenticatedAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS).equals(row.get("authenticated_at",OffsetDateTime.class).toInstant())
                ||!request.absoluteExpiresAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS).equals(row.get("absolute_expires_at",OffsetDateTime.class).toInstant())
                ||!keys.matchesSession(row.get("session_key_id",String.class),row.get("session_digest",String.class),request.bearerId()))throw SessionSensitiveException.rejected();
        request.requireCurrent();return SessionHandle.parse(row.get("primary_id",String.class));
    }
    static void requireRetainedKeys(DSLContext db,SessionSensitiveKeys keys){
        var rows=db.fetch("select distinct session_key_id from public.account_session_authority order by session_key_id limit 33");
        if(rows.size()>32||rows.stream().anyMatch(row->!keys.retainedKeyIds().contains(row.get("session_key_id",String.class))))
            throw SessionSensitiveException.unavailable();
    }
    static Enforcement control(DSLContext db){return control(db,false);}
    static Enforcement control(DSLContext db,boolean typed){
        var query=db.resultQuery(CONTROL_SQL);
        Record row=typed?query.coerce(org.jooq.impl.DSL.field("state",String.class),org.jooq.impl.DSL.field("version",Long.class),
                org.jooq.impl.DSL.field("activated_at",OffsetDateTime.class),org.jooq.impl.DSL.field("activated_by_database_role",String.class),
                org.jooq.impl.DSL.field("activation_event_id",java.util.UUID.class),org.jooq.impl.DSL.field("activation_evidence_sha256",String.class))
                .fetchOne():query.fetchOne();
        if(row==null)throw SessionSensitiveException.unavailable();
        String state=row.get("state",String.class);Long version=row.get("version",Long.class);
        Object at=row.get("activated_at"),role=row.get("activated_by_database_role"),event=row.get("activation_event_id");
        String evidence=row.get("activation_evidence_sha256",String.class);
        if("OFF".equals(state)&&Long.valueOf(0).equals(version)&&at==null&&role==null&&event==null&&evidence==null)return Enforcement.OFF;
        if("ENFORCING".equals(state)&&Long.valueOf(1).equals(version)&&at!=null&&role!=null&&event!=null
                &&evidence!=null&&evidence.matches("[0-9a-f]{64}"))return Enforcement.ENFORCING;
        throw SessionSensitiveException.unavailable();
    }
    private static OffsetDateTime time(Instant instant){return instant.truncatedTo(java.time.temporal.ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);}
}

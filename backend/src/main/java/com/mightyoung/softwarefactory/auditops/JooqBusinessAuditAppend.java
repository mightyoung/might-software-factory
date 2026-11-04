package com.mightyoung.softwarefactory.auditops;

import com.mightyoung.softwarefactory.authorization.EntityActionAuthorization;
import com.mightyoung.softwarefactory.authorization.EntityActionAuthorization.ActionScope;
import javax.sql.DataSource;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** No bean fallback or separate success transaction; exact scope and physical connection own the append. */
public final class JooqBusinessAuditAppend implements BusinessAuditAppend {
    private static final String INSERT_SQL = """
                    insert into public.audit_event
                     (tenant_id,id,actor_id,action,resource_type,resource_id,result,reason,diff_redacted,trace_id,policy_version,occurred_at)
                    values(cast(? as uuid),cast(? as uuid),cast(? as uuid),?,?,cast(? as uuid),'SUCCESS',null,
                     jsonb_build_object('schemaVersion',1,'commandId',cast(? as text),'changeKind',cast(? as text),
                      'changedFieldCategories',cast(? as jsonb)),?,'asset-foundation-v1',cast(? as timestamptz))
                    """;

    /** Source-owned INSERT, shared with the construction-time guarded SQL catalog. */
    public static String commandInsertSql() { return INSERT_SQL; }

    private final Object issuer=new Object();
    private final DataSource source;
    private final EntityActionAuthorization authorization;
    public JooqBusinessAuditAppend(DataSource source, EntityActionAuthorization authorization) {
        this.source=Objects.requireNonNull(source); this.authorization=Objects.requireNonNull(authorization);
    }
    @Override public AuditAppendReceipt appendSuccess(ActionScope scope, BusinessAuditRecord record) {
        Objects.requireNonNull(record);
        authorization.requireCurrent(scope);
        if(!(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder)) throw unavailable();
        var connection=DataSourceUtils.getConnection(source);
        try {
            authorization.requireSqlConnection(scope,source,connection);
            if(scope.action().isRead() || !scope.action().action().equals(record.changeKind().name().toLowerCase(java.util.Locale.ROOT))) throw unavailable();
            var context=scope.context();
            String fields=record.changedFields().stream().sorted().map(x -> "\""+x.name()+"\"").collect(Collectors.joining(",","[","]"));
            int count=DSL.using(connection,SQLDialect.POSTGRES,new Settings().withExecuteLogging(false).withFetchWarnings(false).withBindOffsetDateTimeType(true)).execute(INSERT_SQL,context.tenantId(),record.auditId(),context.actorId(),scope.action().permissionCode(),scope.action().resourceType(),
                    record.resourceId(),record.commandId().toString(),record.changeKind().name(),fields,record.traceId().toString(),scope.observedAt().atOffset(ZoneOffset.UTC));
            if(count!=1) throw unavailable();
            authorization.requireSqlConnection(scope,source,connection);
            return new AuditAppendReceipt(issuer,scope,record,connection);
        } catch(RuntimeException failure) {
            if(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder holder) holder.setRollbackOnly();
            throw failure;
        } finally { DataSourceUtils.releaseConnection(connection,source); }
    }
    @Override public UUID requireMatches(AuditAppendReceipt receipt, ActionScope scope, BusinessAuditRecord record) {
        if(receipt==null || receipt.issuer!=issuer || receipt.scope!=scope || !receipt.record.equals(record)) {
            if(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder holder) holder.setRollbackOnly();
            throw unavailable();
        }
        authorization.requireSqlConnection(scope,source,receipt.connection);
        return receipt.record.auditId();
    }
    private static IllegalStateException unavailable() { return new IllegalStateException("Business audit boundary is unavailable"); }
}

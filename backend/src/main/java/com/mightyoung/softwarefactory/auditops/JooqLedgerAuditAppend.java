package com.mightyoung.softwarefactory.auditops;

import com.mightyoung.softwarefactory.authorization.ApplicationActionRuntime;
import com.mightyoung.softwarefactory.authorization.ApplicationActionAuthorization.Decision;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import javax.sql.DataSource;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public final class JooqLedgerAuditAppend implements LedgerAuditAppend {
    private final DataSource source;
    private final ApplicationActionRuntime actions;
    private final ObjectMapper json;
    public JooqLedgerAuditAppend(DataSource source,ApplicationActionRuntime actions,ObjectMapper json) {this.source=source;this.actions=actions;this.json=json;}
    @Override public UUID appendSuccess(Decision decision,UUID commandId,UUID recordId,UUID traceId,Set<String> changedFields) {
        var connection=DataSourceUtils.getConnection(source);
        try {
            actions.authorization().requireSqlConnection(decision,source,connection);
            if(decision.action().action().equals("read"))throw new IllegalStateException("Read action cannot append write audit");
            var c=decision.context();UUID auditId=UUID.randomUUID();
            String fields=json.writeValueAsString(new TreeSet<>(changedFields));
            int count=DSL.using(connection,SQLDialect.POSTGRES,new Settings().withExecuteLogging(false).withFetchWarnings(false)).execute("""
                insert into public.audit_event(tenant_id,id,actor_id,action,resource_type,resource_id,result,diff_redacted,trace_id,policy_version,occurred_at)
                values(?,?,?,?,?,?,'SUCCESS',jsonb_build_object('schemaVersion',1,'commandId',cast(? as text),'changedFields',cast(? as jsonb)),?,'ledger-v1',cast(? as timestamptz))
                """,c.tenantId(),auditId,c.actorId(),decision.action().permissionCode(),decision.action().resourceType(),recordId,
                    commandId.toString(),fields,traceId.toString(),decision.observedAt().atOffset(ZoneOffset.UTC));
            if(count!=1)throw new IllegalStateException("Ledger audit insert unavailable");
            actions.authorization().requireSqlConnection(decision,source,connection);return auditId;
        } finally {DataSourceUtils.releaseConnection(connection,source);}
    }
}

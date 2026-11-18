package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.authorization.PostgresAuthorizationWriteGuard;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;
import java.util.function.Function;

/** Retained synthetic empty set only; this does not prove the production G005 initializer. */
final class FindingEvidenceUploadIntentPostgresFixture implements AutoCloseable {
    final BusinessWorkflowPostgresFixture business;
    final UUID setId=UUID.randomUUID(), noticeId=UUID.randomUUID();
    final HikariDataSource source;
    final JdbcTemplate jdbc;
    final TransactionTemplate transaction;
    final PostgresAuthorizationWriteGuard guard;
    static LanPostgresCompatibilityIT.DatabaseTarget target() {
        var target=BusinessWorkflowPostgresFixture.validateTarget();
        if (!("I_CONFIRM_UPLOAD_INTENT_FIXTURE_WRITES:"+target.databaseName()).equals(System.getenv("LAN_PG_UPLOAD_INTENT_ACCEPTANCE_ACK")))
            throw new IllegalStateException("Upload intent acceptance requires database-specific fixture confirmation");
        return target;
    }
    FindingEvidenceUploadIntentPostgresFixture(PasswordEncoder passwords) {
        var target=target();
        HikariConfig config=new HikariConfig();config.setJdbcUrl(target.jdbcUrl());
        config.setUsername(System.getenv("LAN_PG_USERNAME"));config.setPassword(System.getenv("LAN_PG_PASSWORD"));
        config.setMaximumPoolSize(2);config.setMinimumIdle(0);source=new HikariDataSource(config);
        jdbc=new JdbcTemplate(source);transaction=new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        guard=new PostgresAuthorizationWriteGuard(source);
        business=new BusinessWorkflowPostgresFixture(passwords);
        write(db->{
            db.update("update public.finding set state='RECTIFYING' where tenant_id=? and id=?",business.tenantId,business.legacyFindingId);
            db.update("insert into public.remediation_round(tenant_id,finding_id,round_no,state,opened_at,opened_by_command_id) values (?,?,1,'OPEN',current_timestamp,?)",business.tenantId,business.legacyFindingId,UUID.randomUUID());
            db.update("insert into public.remediation_notice(tenant_id,id,finding_id,revision,round_no,assignee_id,due_at,original_due_at,requirements,state,issued_at,command_id) values (?,?,?,1,1,?,current_timestamp+interval '1 day',current_timestamp+interval '1 day','G021 synthetic notice','ISSUED',current_timestamp,?)",business.tenantId,noticeId,business.legacyFindingId,business.remediator.memberId(),UUID.randomUUID());
            db.update("insert into public.evidence_set(tenant_id,id,finding_id,round_no,owner_member_id,state,created_at) values (?,?,?,1,?,'UPLOADING',current_timestamp)",business.tenantId,setId,business.legacyFindingId,business.remediator.memberId());
            return null;
        });
    }
    CurrentTenantContext owner() {var a=business.remediator;return new CurrentTenantContext(a.principalId(),a.accountId(),business.tenantId,a.memberId(),1,1,1);}
    <T>T write(Function<JdbcTemplate,T> work) {return transaction.execute(s->{guard.forGlobalSecurityChange();jdbc.queryForObject("select set_config('app.tenant_id',?,true)",String.class,business.tenantId.toString());return work.apply(jdbc);});}
    long count(String table) {return write(db->db.queryForObject("select count(*) from public."+table+" where tenant_id=?",Long.class,business.tenantId));}
    @Override public void close(){try{business.close();}finally{source.close();}}
}

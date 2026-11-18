package com.mightyoung.softwarefactory.auditops;

import com.mightyoung.softwarefactory.authorization.TenantAuditReadAuthorization;
import com.mightyoung.softwarefactory.identity.CapturedHumanRequest;
import com.mightyoung.softwarefactory.identity.HumanSessionAuthorityTransaction;
import com.mightyoung.softwarefactory.identity.SessionSensitiveException;
import java.io.IOException;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.mightyoung.softwarefactory.auditops.TenantAuditQueryContracts.*;

/** Complete bounded metadata in one current authorized RO/RR callback; transport receives final owned bytes. */
public final class TenantAuditQueryService {
    static final String SET_TENANT_SQL="select pg_catalog.set_config('app.tenant_id', ?, true)";
    static final String SET_TIMEOUT_SQL="select pg_catalog.set_config('statement_timeout','2000ms',true)";
    static final String VERIFY_TENANT_SQL="select pg_catalog.current_setting('app.tenant_id',true)";
    static final String VERIFY_TIMEOUT_SQL="select setting::bigint = 2000 and unit = 'ms' as timeout_ok from pg_catalog.pg_settings where name='statement_timeout'";
    private final DataSource source;
    private final HumanSessionAuthorityTransaction callers;
    private final TenantAuditReadAuthorization authority;
    private final TenantAuditQueryRepository repository;
    private final TransactionTemplate transaction;
    public TenantAuditQueryService(DataSource source,PlatformTransactionManager manager,
            HumanSessionAuthorityTransaction callers,TenantAuditReadAuthorization authority){
        this.source=Objects.requireNonNull(source);this.callers=Objects.requireNonNull(callers);this.authority=Objects.requireNonNull(authority);
        repository=new TenantAuditQueryRepository(source,authority);transaction=new TransactionTemplate(Objects.requireNonNull(manager));
        transaction.setReadOnly(true);transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);transaction.setTimeout(10);
    }
    public EncodedResult query(CapturedHumanRequest request,Query query,UUID requestId,UUID traceId){
        if(request==null||requestId==null||traceId==null)throw new Unavailable();
        if(query==null)throw invalid();
        if(TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isSynchronizationActive()
                ||TransactionSynchronizationManager.hasResource(source))throw new Unavailable();
        request.requireCurrent();
        try{
            EncodedResult result=transaction.execute(status->{
                Connection connection=bound();setLocal(connection,request.context().tenantId());verifyLocal(connection,request.context().tenantId());
                return callers.withRead(request,boundary->{
                    var scope=authority.requireRead(request,boundary);authority.requireSqlConnection(scope,source,connection);
                    var cut=scope.observedAt().truncatedTo(ChronoUnit.MICROS);
                    Window window;
                    if(query.from()==null){try{window=new Window(cut.minus(1,ChronoUnit.DAYS),cut);}catch(RuntimeException invalidClock){throw new Unavailable();}}
                    else window=new Window(query.from(),query.to());
                    if(window.to().isAfter(cut))throw invalid();
                    String fingerprint=TenantAuditQueryFingerprint.of(scope.tenantId(),window,query);
                    if(query.cursor()!=null&&!query.cursor().queryFingerprint().equals(fingerprint))throw invalid();
                    var candidates=repository.query(scope,window,query);int count=Math.min(query.pageSize(),candidates.size());
                    var items=new ArrayList<Item>(candidates.subList(0,count));boolean more=candidates.size()>count;
                    TenantAuditQueryEncoder.EncodedResponse encoded;
                    try{
                        Cursor cursor=more?new Cursor(items.getLast().occurredAt(),items.getLast().id(),fingerprint):null;
                        encoded=TenantAuditQueryEncoder.encode(new Response(new Data(items,window,more,cursor),new Meta(requestId,traceId)));
                    }
                    catch(RuntimeException failedEncoding){throw new Unavailable();}
                    authority.requireSqlConnection(scope,source,connection);verifyLocal(connection,scope.tenantId());request.requireCurrent();
                    authority.requireSqlConnection(scope,source,connection);return new EncodedResult(encoded);
                });
            });
            if(result==null)throw new Unavailable();return result;
        }catch(Failure|SessionSensitiveException|AccessDeniedException failure){throw failure;}
        catch(RuntimeException failure){throw new Unavailable();}
    }
    private Connection bound(){
        if(!(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder holder)||!holder.isSynchronizedWithTransaction()
                ||holder.isRollbackOnly()||!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isSynchronizationActive()
                ||!TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                ||!Integer.valueOf(TransactionDefinition.ISOLATION_REPEATABLE_READ).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()))throw new Unavailable();
        Connection connection=holder.getConnection();
        try{if(connection==null||connection.getAutoCommit()||!connection.isReadOnly()||connection.getTransactionIsolation()!=Connection.TRANSACTION_REPEATABLE_READ)throw new Unavailable();}
        catch(SQLException failure){throw new Unavailable();}return connection;
    }
    private void setLocal(Connection connection,UUID tenant){
        setting(connection,SET_TENANT_SQL,tenant.toString());setting(connection,SET_TIMEOUT_SQL,null);
    }
    private void setting(Connection connection,String sql,String value){
        try(var statement=connection.prepareStatement(sql)){
            DataSourceUtils.applyTransactionTimeout(statement,source);if(value!=null)statement.setString(1,value);
            try(var rows=statement.executeQuery()){if(!rows.next()||rows.next())throw new Unavailable();}
        }catch(SQLException failure){throw new Unavailable();}
    }
    private void verifyLocal(Connection connection,UUID tenant){
        Object applied=single(connection,VERIFY_TENANT_SQL);if(!tenant.toString().equals(applied))throw new Unavailable();
        Object timeout=single(connection,VERIFY_TIMEOUT_SQL);if(!(timeout instanceof Boolean valid)||!valid)throw new Unavailable();
    }
    private Object single(Connection connection,String sql){
        try(var statement=connection.prepareStatement(sql)){
            DataSourceUtils.applyTransactionTimeout(statement,source);
            try(var rows=statement.executeQuery()){
                var metadata=rows.getMetaData();if(metadata==null||metadata.getColumnCount()!=1||!rows.next())throw new Unavailable();
                Object value=rows.getObject(1);if(rows.next())throw new Unavailable();return value;
            }
        }catch(SQLException failure){throw new Unavailable();}
    }
    public static final class EncodedResult {
        private final TenantAuditQueryEncoder.EncodedResponse encoded;
        private EncodedResult(TenantAuditQueryEncoder.EncodedResponse encoded){this.encoded=encoded;}
        public int size(){return encoded.size();}
        public void writeTo(OutputStream destination)throws IOException{encoded.writeTo(destination);}
    }
    static final class Unavailable extends IllegalStateException {
        Unavailable(){super("Tenant audit query is unavailable");}
    }
}

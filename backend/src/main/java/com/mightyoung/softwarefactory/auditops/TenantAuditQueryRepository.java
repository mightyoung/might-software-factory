package com.mightyoung.softwarefactory.auditops;

import com.mightyoung.softwarefactory.authorization.TenantAuditReadAuthorization;
import com.mightyoung.softwarefactory.authorization.TenantAuditReadAuthorization.Scope;
import com.mightyoung.softwarefactory.identity.SessionSensitiveException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static com.mightyoung.softwarefactory.auditops.TenantAuditQueryContracts.*;

/** One finite projection in the already authorized caller transaction; never opens another connection. */
final class TenantAuditQueryRepository {
    static final String QUERY_SQL="""
            select tenant_id,id,actor_id,delegator_id,action,resource_type,resource_id,result,trace_id,policy_version,occurred_at
              from public.audit_event
             where tenant_id=cast(? as uuid) and occurred_at>=cast(? as timestamptz) and occurred_at<cast(? as timestamptz)
               and (cast(? as uuid) is null or actor_id=cast(? as uuid))
               and (cast(? as text) is null or resource_type=cast(? as text))
               and (cast(? as uuid) is null or resource_id=cast(? as uuid))
               and (cast(? as text) is null or action=cast(? as text))
               and (cast(? as text) is null or result=cast(? as text))
               and (cast(? as timestamptz) is null or (occurred_at,id)<(cast(? as timestamptz),cast(? as uuid)))
             order by occurred_at desc,id desc limit ?
            """;
    private static final List<String> COLUMNS=List.of("tenant_id","id","actor_id","delegator_id","action","resource_type","resource_id","result","trace_id","policy_version","occurred_at");
    private final DataSource source;
    private final TenantAuditReadAuthorization authority;
    TenantAuditQueryRepository(DataSource source,TenantAuditReadAuthorization authority){this.source=Objects.requireNonNull(source);this.authority=Objects.requireNonNull(authority);}

    List<Item> query(Scope scope,Window window,Query query){
        try{
            if(scope==null||window==null||query==null)throw new TenantAuditQueryService.Unavailable();
            if(!(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder holder))throw new TenantAuditQueryService.Unavailable();
            Connection connection=holder.getConnection();authority.requireSqlConnection(scope,source,connection);
            if(window.to().isAfter(scope.observedAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS))
                    ||query.from()!=null&&(!query.from().equals(window.from())||!query.to().equals(window.to())))throw new TenantAuditQueryService.Unavailable();
            var cursor=query.cursor();var at=cursor==null?null:cursor.occurredAt().atOffset(ZoneOffset.UTC);
            String result=query.result()==null?null:query.result().name();
            Object[] parameters={scope.tenantId(),window.from().atOffset(ZoneOffset.UTC),window.to().atOffset(ZoneOffset.UTC),
                    query.actorId(),query.actorId(),query.resourceType(),query.resourceType(),query.resourceId(),query.resourceId(),
                    query.action(),query.action(),result,result,at,at,cursor==null?null:cursor.id(),query.pageSize()+1};
            var items=new ArrayList<Item>();var ids=new HashSet<UUID>();Item previous=null;
            try(var statement=connection.prepareStatement(QUERY_SQL)){
                DataSourceUtils.applyTransactionTimeout(statement,source);
                for(int i=0;i<parameters.length;i++)statement.setObject(i+1,parameters[i]);
                try(var rows=statement.executeQuery()){
                    var metadata=rows.getMetaData();if(metadata==null||metadata.getColumnCount()!=COLUMNS.size())throw new TenantAuditQueryService.Unavailable();
                    for(int i=0;i<COLUMNS.size();i++)if(!COLUMNS.get(i).equals(metadata.getColumnLabel(i+1)))throw new TenantAuditQueryService.Unavailable();
                    while(rows.next()){
                        authority.requireSqlConnection(scope,source,connection);
                        if(items.size()==query.pageSize()+1||!scope.tenantId().equals(uuid(rows,1,false)))throw new TenantAuditQueryService.Unavailable();
                        OffsetDateTime occurred=rows.getObject(11,OffsetDateTime.class);if(occurred==null)throw new TenantAuditQueryService.Unavailable();
                        Item item=new Item(uuid(rows,2,false),uuid(rows,3,false),uuid(rows,4,true),text(rows,5,false),text(rows,6,false),
                                uuid(rows,7,true),Result.valueOf(text(rows,8,false)),trace(text(rows,9,true)),text(rows,10,false),occurred.toInstant());
                        if(!window.contains(item.occurredAt())||!ids.add(item.id())||previous!=null&&compare(previous.occurredAt(),previous.id(),item.occurredAt(),item.id())<=0
                                ||cursor!=null&&compare(item.occurredAt(),item.id(),cursor.occurredAt(),cursor.id())>=0||!matches(item,query))throw new TenantAuditQueryService.Unavailable();
                        items.add(item);previous=item;authority.requireSqlConnection(scope,source,connection);
                    }
                }
            }
            authority.requireSqlConnection(scope,source,connection);return List.copyOf(items);
        }catch(SessionSensitiveException|AccessDeniedException failure){throw failure;}
        catch(SQLException|RuntimeException failure){throw new TenantAuditQueryService.Unavailable();}
    }
    private static UUID uuid(ResultSet rows,int column,boolean nullable)throws SQLException{
        Object value=rows.getObject(column);if(value==null&&nullable)return null;if(value instanceof UUID id)return id;throw new TenantAuditQueryService.Unavailable();
    }
    private static String text(ResultSet rows,int column,boolean nullable)throws SQLException{
        Object value=rows.getObject(column);if(value==null&&nullable)return null;if(value instanceof String text)return text;throw new TenantAuditQueryService.Unavailable();
    }
    private static UUID trace(String value){
        if(value==null||!value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))return null;
        try{UUID id=UUID.fromString(value);return id.toString().equals(value)?id:null;}catch(IllegalArgumentException failure){return null;}
    }
    private static boolean matches(Item item,Query query){
        return (query.actorId()==null||query.actorId().equals(item.actorId()))&&(query.resourceType()==null||query.resourceType().equals(item.resourceType()))
                &&(query.resourceId()==null||query.resourceId().equals(item.resourceId()))&&(query.action()==null||query.action().equals(item.action()))
                &&(query.result()==null||query.result()==item.result());
    }
    private static int compare(Instant left,UUID leftId,Instant right,UUID rightId){
        int time=left.compareTo(right);if(time!=0)return time;
        int high=Long.compareUnsigned(leftId.getMostSignificantBits(),rightId.getMostSignificantBits());
        return high!=0?high:Long.compareUnsigned(leftId.getLeastSignificantBits(),rightId.getLeastSignificantBits());
    }
}

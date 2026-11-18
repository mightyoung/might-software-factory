package com.mightyoung.softwarefactory.dualprevention.risk;

import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static com.mightyoung.softwarefactory.dualprevention.risk.RiskChangeCaptureScope.*;

/** Finite metadata-only SQL. Borrows the active capture witness; never acquires a connection or transaction. */
final class RiskChangeCauseRepository {
    static final String SOURCE_SQL="""
            select r.tenant_id,r.command_id,r.entity_id,r.version,r.occurred_at,r.change_kind,
                   c.tenant_id,c.command_id,c.action_code,c.result_scenario_id,c.result_version,c.occurred_at,c.trace_id
              from public.risk_scenario_revision r
              join public.risk_scenario_receipt c on c.tenant_id=r.tenant_id and c.command_id=r.command_id
             where r.tenant_id=? and r.command_id=?
            """;
    static final String INSERT_SQL="""
            insert into public.risk_change_cause
                   (tenant_id,source_command_id,consumer_version,recorded_at,recorded_by_service_actor_id)
            values (?,?,1,pg_catalog.clock_timestamp(),?)
            on conflict (tenant_id,source_command_id) do nothing
            returning tenant_id,source_command_id,consumer_version,recorded_at,recorded_by_service_actor_id
            """;
    static final String FIND_SQL="""
            select tenant_id,source_command_id,consumer_version,recorded_at,recorded_by_service_actor_id
              from public.risk_change_cause where tenant_id=? and source_command_id=?
            """;
    record Cause(UUID tenantId,UUID commandId,int consumerVersion,Instant recordedAt,UUID recordedByServiceActorId) { }
    record Stored(Cause cause,boolean replay) { }
    private final DataSource source;
    private final Object issuer;
    RiskChangeCauseRepository(DataSource source,Object issuer){this.source=Objects.requireNonNull(source);this.issuer=Objects.requireNonNull(issuer);}
    void requireSource(RiskChangeCaptureScope scope,RiskScenarioDraftChangedEvent event){
        try {
            if(!scope.tenant(issuer).equals(event.tenantId())||!"risk_scenario".equals(event.resourceType()))throw rejected();
            try(var statement=prepare(scope,SOURCE_SQL)){
                statement.setObject(1,event.tenantId());statement.setObject(2,event.commandId());
                try(var rows=statement.executeQuery()){
                    width(rows,13);if(!rows.next())throw rejected();
                    if(!uuid(rows,1).equals(event.tenantId())||!uuid(rows,2).equals(event.commandId())
                            ||!uuid(rows,3).equals(event.resourceId())||version(rows,4)!=event.version()
                            ||!time(rows,5).equals(event.occurredAt())||!text(rows,6).equals(event.changeKind())
                            ||!uuid(rows,7).equals(event.tenantId())||!uuid(rows,8).equals(event.commandId())
                            ||!text(rows,9).equals(event.actionCode())||!uuid(rows,10).equals(event.resourceId())
                            ||version(rows,11)!=event.version()||!time(rows,12).equals(event.occurredAt())
                            ||!uuid(rows,13).equals(event.traceId())||rows.next())throw rejected();
                }
            }
            scope.business(issuer);
        }catch(SQLException|RuntimeException|Error failure){scope.reject();throw rejected();}
    }
    Stored store(RiskChangeCaptureScope scope,RiskScenarioDraftChangedEvent event){
        try {
            UUID tenant=scope.tenant(issuer),actor=scope.actor(issuer);
            if(!tenant.equals(event.tenantId()))throw rejected();Cause cause;
            try(var statement=prepare(scope,INSERT_SQL)){
                statement.setObject(1,tenant);statement.setObject(2,event.commandId());statement.setObject(3,actor);
                try(var rows=statement.executeQuery()){
                    width(rows,5);if(rows.next()){cause=cause(rows,tenant,event.commandId());if(rows.next())throw rejected();}else cause=null;
                }
            }
            scope.business(issuer);
            if(cause!=null){if(!cause.recordedByServiceActorId().equals(actor))throw rejected();return new Stored(cause,false);}
            // Separate READ COMMITTED statement after conflict wait; the prior winner may have just committed.
            try(var statement=prepare(scope,FIND_SQL)){
                statement.setObject(1,tenant);statement.setObject(2,event.commandId());
                try(var rows=statement.executeQuery()){
                    width(rows,5);if(!rows.next())throw rejected();cause=cause(rows,tenant,event.commandId());if(rows.next())throw rejected();
                }
            }
            scope.business(issuer);return new Stored(cause,true);
        }catch(SQLException|RuntimeException|Error failure){scope.reject();throw rejected();}
    }
    private PreparedStatement prepare(RiskChangeCaptureScope scope,String sql)throws SQLException {
        Connection connection=scope.business(issuer);
        if(!(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder holder)||holder.getConnection()!=connection)throw rejected();
        PreparedStatement statement=connection.prepareStatement(sql);
        try {timeout(statement,holder);statement.setMaxRows(2);return statement;}
        catch(SQLException|RuntimeException|Error failure){try{statement.close();}catch(SQLException|RuntimeException|Error ignored){}throw failure;}
    }
    private static Cause cause(ResultSet rows,UUID tenant,UUID command)throws SQLException {
        UUID actualTenant=uuid(rows,1),actualCommand=uuid(rows,2),actor=uuid(rows,5);Object consumer=rows.getObject(3);
        if(!actualTenant.equals(tenant)||!actualCommand.equals(command)||!(consumer instanceof Integer value)||value!=1)throw rejected();
        return new Cause(actualTenant,actualCommand,1,time(rows,4),actor);
    }
    private static UUID uuid(ResultSet rows,int index)throws SQLException {Object value=rows.getObject(index);if(!(value instanceof UUID id))throw rejected();return id;}
    private static String text(ResultSet rows,int index)throws SQLException {Object value=rows.getObject(index);if(!(value instanceof String text)||text.isEmpty())throw rejected();return text;}
    private static long version(ResultSet rows,int index)throws SQLException {Object value=rows.getObject(index);if(!(value instanceof Long number)||number<1||number>9007199254740991L)throw rejected();return number;}
    private static Instant time(ResultSet rows,int index)throws SQLException {
        Object value=rows.getObject(index);Instant instant;
        if(value instanceof OffsetDateTime time)instant=time.toInstant();else if(value instanceof Timestamp time)instant=time.toInstant();else throw rejected();
        if(instant.getNano()%1000!=0||instant.isBefore(Instant.parse("0001-01-01T00:00:00Z"))||!instant.isBefore(Instant.parse("+10000-01-01T00:00:00Z")))throw rejected();return instant;
    }
}

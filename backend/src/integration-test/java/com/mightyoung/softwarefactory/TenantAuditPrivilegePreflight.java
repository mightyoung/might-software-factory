package com.mightyoung.softwarefactory;

import java.sql.*;
import java.util.*;

/** Catalog-only PG18 necessary-privilege preflight, not RLS isolation, authorization or escalation proof.
 * Uses the conservative existing no-membership policy without changing roles. Statement deadlines do not
 * establish a connection timeout; the global DriverManager configuration is never changed. */
final class TenantAuditPrivilegePreflight {
    private static final String FAILURE="Tenant audit privilege preflight rejected";
    private static final int QUERY_TIMEOUT_SECONDS=2;
    private static final int MAX_USER_COLUMNS=1600;
    static final Set<String> QUERY_COLUMNS=Set.of("tenant_id","id","actor_id","delegator_id","action","resource_type",
            "resource_id","result","trace_id","policy_version","occurred_at");
    static final String ROLE_SQL="""
            select pg_catalog.current_database(),current_user,session_user,
                   pg_catalog.pg_get_userbyid(d.datdba),pg_catalog.pg_get_userbyid(n.nspowner),
                   r.rolsuper,r.rolbypassrls,r.rolcreatedb,r.rolcreaterole,r.rolreplication,r.rolinherit,
                   exists(select 1 from pg_catalog.pg_auth_members m where m.member=r.oid),
                   pg_catalog.has_database_privilege(current_user,pg_catalog.current_database(),'CONNECT'),
                   pg_catalog.has_schema_privilege(current_user,'public','USAGE'),
                   pg_catalog.has_schema_privilege(current_user,'public','CREATE')
              from pg_catalog.pg_roles r
              join pg_catalog.pg_database d on d.datname=pg_catalog.current_database()
              join pg_catalog.pg_namespace n on n.nspname='public'
             where r.rolname=current_user
            """;
    static final String AUDIT_SQL="""
            select c.relkind::pg_catalog.text,pg_catalog.pg_get_userbyid(c.relowner)=current_user,c.relrowsecurity,c.relforcerowsecurity
              from pg_catalog.pg_class c join pg_catalog.pg_namespace n on n.oid=c.relnamespace
             where n.nspname='public' and c.relname='audit_event'
            """;
    static final String TABLE_PRIVILEGES_SQL="select pg_catalog.has_table_privilege(current_user,cast(? as pg_catalog.regclass),?)";
    static final String COLUMN_PRIVILEGES_SQL="select pg_catalog.has_any_column_privilege(current_user,cast(? as pg_catalog.regclass),?)";
    static final String EXISTS_SQL="select pg_catalog.to_regclass(?) is not null";
    static final String COLUMNS_SQL="""
            select a.attname,pg_catalog.has_column_privilege(current_user,a.attrelid,a.attname,'SELECT')
              from pg_catalog.pg_attribute a
             where a.attrelid='public.audit_event'::pg_catalog.regclass and a.attnum>0 and not a.attisdropped
             order by a.attnum
            """;
    @FunctionalInterface interface Connector { Connection connect(String url,String username,String password)throws SQLException; }
    private TenantAuditPrivilegePreflight() { }
    static void inspect(Map<String,String> environment){inspect(environment,DriverManager::getConnection);}
    static void inspect(Map<String,String> environment,Connector connector){
        try {
            var target=LanPostgresCompatibilityIT.DatabaseTarget.forAppRole(environment);
            String runtime=required(environment,"LAN_PG_APP_USERNAME"),password=required(environment,"LAN_PG_APP_PASSWORD");
            Objects.requireNonNull(connector);
            try(var connection=connector.connect(target.jdbcUrl(),runtime,password)){
                if(connection==null)throw rejected();
                try {
                    connection.setReadOnly(true);connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);connection.setAutoCommit(false);
                    var metadata=connection.getMetaData();
                    if(metadata==null||!"PostgreSQL".equals(metadata.getDatabaseProductName())||metadata.getDatabaseMajorVersion()!=18)throw rejected();
                    Object[] role=single(connection,ROLE_SQL,15);
                    var state=new LanPostgresCompatibilityIT.DatabaseTarget.RolePreflight(text(role[0]),text(role[1]),text(role[2]),text(role[3]),text(role[4]),
                            bool(role[5]),bool(role[6]),bool(role[7]),bool(role[8]),bool(role[9]),bool(role[10]),bool(role[11]),bool(role[12]),bool(role[13]),bool(role[14]));
                    LanPostgresCompatibilityIT.DatabaseTarget.assertRolePreflight(state,target.databaseName(),runtime,false);
                    Object[] audit=single(connection,AUDIT_SQL,4);
                    if(!"r".equals(text(audit[0]))||bool(audit[1])||!bool(audit[2])||!bool(audit[3]))throw rejected();
                    if(bool(single(connection,TABLE_PRIVILEGES_SQL,1,"public.audit_event","SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER,MAINTAIN")[0])
                            ||bool(single(connection,COLUMN_PRIVILEGES_SQL,1,"public.audit_event","INSERT,UPDATE,REFERENCES")[0]))throw rejected();
                    columns(connection);
                    for(String table:List.of("public.security_event","public.authentication_event")){
                        if(!bool(single(connection,EXISTS_SQL,1,table)[0])
                                ||bool(single(connection,TABLE_PRIVILEGES_SQL,1,table,"SELECT")[0])
                                ||bool(single(connection,COLUMN_PRIVILEGES_SQL,1,table,"SELECT")[0]))throw rejected();
                    }
                } finally { connection.rollback(); }
            }
        }catch(SQLException|RuntimeException|Error failure){throw rejected();}
    }
    private static void columns(Connection connection)throws SQLException {
        Set<String> found=new HashSet<>();int count=0;
        try(var statement=connection.prepareStatement(COLUMNS_SQL)){
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);statement.setMaxRows(MAX_USER_COLUMNS+1);
            try(var rows=statement.executeQuery()){
                width(rows,2);
                while(rows.next()){
                    String name=text(rows.getObject(1));boolean readable=bool(rows.getObject(2));
                    if(++count>MAX_USER_COLUMNS||!found.add(name)||readable!=QUERY_COLUMNS.contains(name))throw rejected();
                }
            }
        }
        if(!found.containsAll(QUERY_COLUMNS))throw rejected();
    }
    private static Object[] single(Connection connection,String sql,int width,String... parameters)throws SQLException {
        try(var statement=connection.prepareStatement(sql)){
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);statement.setMaxRows(2);
            for(int i=0;i<parameters.length;i++)statement.setString(i+1,parameters[i]);
            try(var rows=statement.executeQuery()){
                width(rows,width);if(!rows.next())throw rejected();var values=new Object[width];
                for(int i=0;i<width;i++)values[i]=rows.getObject(i+1);
                if(rows.next())throw rejected();return values;
            }
        }
    }
    private static void width(ResultSet rows,int expected)throws SQLException {
        var metadata=rows.getMetaData();if(metadata==null||metadata.getColumnCount()!=expected)throw rejected();
    }
    private static boolean bool(Object value){if(!(value instanceof Boolean result))throw rejected();return result;}
    private static String text(Object value){if(!(value instanceof String result)||result.isEmpty())throw rejected();return result;}
    private static String required(Map<String,String> environment,String name){String value=environment.get(name);if(value==null||value.isBlank())throw rejected();return value;}
    private static IllegalStateException rejected(){return new IllegalStateException(FAILURE);}
}

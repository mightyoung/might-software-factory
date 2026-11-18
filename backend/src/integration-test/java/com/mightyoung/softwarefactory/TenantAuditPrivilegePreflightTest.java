package com.mightyoung.softwarefactory;

import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Controlled JDBC/effective-result fixtures only, not PostgreSQL or real PUBLIC/inheritance privilege proof. */
class TenantAuditPrivilegePreflightTest {
    private static final String DB="audit_privilege_test";
    private static final String APP="synthetic_query_role";
    private static final String APP_PASSWORD="synthetic-app-secret";
    private static Map<String,String> environment(){return new HashMap<>(Map.of(
            "LAN_PG_JDBC_URL","jdbc:postgresql://192.0.2.10:5432/"+DB,
            "LAN_PG_TEST_JDBC_URL","jdbc:postgresql://192.0.2.10:5432/"+DB,
            "LAN_PG_TEST_DATABASE_NAME",DB,"LAN_PG_DEDICATED_TEST_DB_ACK","I_UNDERSTAND_THIS_IS_A_DISPOSABLE_DB:"+DB,
            "LAN_PG_TRUSTED_TEST_TARGET_ALLOWLIST","jdbc:postgresql://192.0.2.10:5432/"+DB,
            "LAN_PG_USERNAME","synthetic_migrator","LAN_PG_PASSWORD","synthetic-migration-secret",
            "LAN_PG_APP_USERNAME",APP,"LAN_PG_APP_PASSWORD",APP_PASSWORD));}
    @Test void onlyTheApplicationConnectionIsUsedAndReadOnlyRrAlwaysRollsBackThenCloses()throws Exception {
        var f=new Fixture();TenantAuditPrivilegePreflight.inspect(environment(),f.connector());assertThat(f.connections.get()).isEqualTo(1);
        var order=inOrder(f.connection);order.verify(f.connection).setReadOnly(true);order.verify(f.connection).setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);order.verify(f.connection).setAutoCommit(false);order.verify(f.connection).getMetaData();order.verify(f.connection).rollback();order.verify(f.connection).close();
        verify(f.connection,never()).commit();verify(f.connection,never()).createStatement();
        assertThat(f.sql).containsExactly(TenantAuditPrivilegePreflight.ROLE_SQL,TenantAuditPrivilegePreflight.AUDIT_SQL,
                TenantAuditPrivilegePreflight.TABLE_PRIVILEGES_SQL,TenantAuditPrivilegePreflight.COLUMN_PRIVILEGES_SQL,TenantAuditPrivilegePreflight.COLUMNS_SQL,
                TenantAuditPrivilegePreflight.EXISTS_SQL,TenantAuditPrivilegePreflight.TABLE_PRIVILEGES_SQL,TenantAuditPrivilegePreflight.COLUMN_PRIVILEGES_SQL,
                TenantAuditPrivilegePreflight.EXISTS_SQL,TenantAuditPrivilegePreflight.TABLE_PRIVILEGES_SQL,TenantAuditPrivilegePreflight.COLUMN_PRIVILEGES_SQL);
        for(var statement:f.statements){verify(statement).setQueryTimeout(2);verify(statement).close();}
    }
    @Test void targetValidationAndApplicationPasswordAreRequiredBeforeConnecting()throws Exception {
        for(String fault:List.of("ack","allowlist","app-url","same-role","password","null")){
            var f=new Fixture();var env=environment();switch(fault){case "ack"->env.remove("LAN_PG_DEDICATED_TEST_DB_ACK");case "allowlist"->env.remove("LAN_PG_TRUSTED_TEST_TARGET_ALLOWLIST");case "app-url"->env.put("LAN_PG_TEST_JDBC_URL","jdbc:postgresql://192.0.2.10:5432/other");case "same-role"->env.put("LAN_PG_APP_USERNAME",env.get("LAN_PG_USERNAME"));case "password"->env.remove("LAN_PG_APP_PASSWORD");default->env=null;}
            final var input=env;refused(()->TenantAuditPrivilegePreflight.inspect(input,f.connector()));assertThat(f.connections.get()).isZero();verifyNoInteractions(f.connection);
        }
    }
    @ParameterizedTest @ValueSource(strings={"NOT_PG","PG17","METADATA_NULL","METADATA_SQL","ROLE_SQL","AUDIT_MISSING","AUDIT_OWNER","AUDIT_VIEW","AUDIT_RLS_OFF","AUDIT_FORCE_OFF"})
    void wrongPlatformCatalogOrAuditTablePropertiesRefuseAndRelease(String fault)throws Exception {
        var f=new Fixture();f.fault=fault;refused(()->TenantAuditPrivilegePreflight.inspect(environment(),f.connector()));assertThat(f.connections.get()).isEqualTo(1);verify(f.connection).rollback();verify(f.connection).close();verify(f.connection,never()).commit();
    }
    @Test void existingRolePolicyRejectsEveryElevatedOrMismatchedRuntimeIdentity()throws Exception {
        for(int column=0;column<15;column++){
            if(column==3||column==4){var f=new Fixture();f.role[column]=APP;refused(()->TenantAuditPrivilegePreflight.inspect(environment(),f.connector()));continue;}
            var f=new Fixture();f.role[column]=column<3?"wrong-synthetic-identity":column<12||column==14?true:false;
            refused(()->TenantAuditPrivilegePreflight.inspect(environment(),f.connector()));
        }
    }
    @ParameterizedTest @ValueSource(strings={"PUBLIC_TABLE_SELECT","AUDIT_MUTATION","AUDIT_COLUMN_MUTATION","INHERITED_REASON_SELECT","DIFF_SELECT","FUTURE_SELECT","MISSING_QUERY_COLUMN","DUPLICATE_COLUMN","NULL_COLUMN","NULL_COLUMN_SELECT","STRING_COLUMN_SELECT","NO_COLUMNS","TOO_MANY_COLUMNS","SECURITY_TABLE_SELECT","SECURITY_COLUMN_SELECT","AUTH_TABLE_SELECT","AUTH_COLUMN_SELECT","SECURITY_MISSING","AUTH_MISSING"})
    void effectivePermissionsAndCompleteColumnInventoryMustMatchTheClosedProjection(String fault)throws Exception {
        var f=new Fixture();f.fault=fault;refused(()->TenantAuditPrivilegePreflight.inspect(environment(),f.connector()));verify(f.connection).rollback();verify(f.connection).close();
    }
    @Test void unreadableAdditionalFutureColumnsAreAllowedButNeverRead()throws Exception {
        var f=new Fixture();f.columns.add(new Object[]{"future_metadata",false});TenantAuditPrivilegePreflight.inspect(environment(),f.connector());verify(f.connection).rollback();
    }
    @ParameterizedTest @ValueSource(strings={"ZERO_ROWS","TWO_ROWS","WRONG_WIDTH","NULL_METADATA","NULL_BOOLEAN","STRING_BOOLEAN","NULL_IDENTITY"})
    void NullUnknownAndNonSingletonResultsFailClosedWithoutBooleanCoercion(String fault)throws Exception {
        var f=new Fixture();f.fault=fault;refused(()->TenantAuditPrivilegePreflight.inspect(environment(),f.connector()));verify(f.connection).rollback();verify(f.connection).close();
    }
    @Test void connectRollbackAndCloseFailuresExposeNoValuesCauseOrSuppressedException()throws Exception {
        var f=new Fixture();refused(()->TenantAuditPrivilegePreflight.inspect(environment(),(url,user,password)->{throw new SQLException(url+user+password);}));
        for(boolean rollback:new boolean[]{false,true}){
            var g=new Fixture();if(rollback)doThrow(new SQLException("synthetic-app-secret jdbc:postgresql://private")).when(g.connection).rollback();else doThrow(new SQLException("synthetic-migration-secret")).when(g.connection).close();
            refused(()->TenantAuditPrivilegePreflight.inspect(environment(),g.connector()));verify(g.connection).rollback();verify(g.connection).close();verify(g.connection,never()).commit();
        }
    }
    private static void refused(org.assertj.core.api.ThrowableAssert.ThrowingCallable action){
        assertThatThrownBy(action).isExactlyInstanceOf(IllegalStateException.class).hasMessage("Tenant audit privilege preflight rejected")
                .hasNoCause().satisfies(failure->assertThat(failure.getSuppressed()).isEmpty());
    }
    private static final class Fixture {
        final Connection connection=mock(Connection.class);final DatabaseMetaData metadata=mock(DatabaseMetaData.class);final AtomicInteger connections=new AtomicInteger();
        final List<String> sql=new ArrayList<>();final List<PreparedStatement> statements=new ArrayList<>();
        final Object[] role={DB,APP,APP,"synthetic_database_owner","synthetic_schema_owner",false,false,false,false,false,false,false,true,true,false};
        final List<Object[]> columns=new ArrayList<>();String fault="";
        Fixture()throws Exception {
            for(String name:List.of("tenant_id","id","actor_id","delegator_id","action","resource_type","resource_id","result","trace_id","policy_version","occurred_at"))columns.add(new Object[]{name,true});
            columns.add(new Object[]{"reason",false});columns.add(new Object[]{"diff_redacted",false});
            when(connection.getMetaData()).thenAnswer(call->{if(fault.equals("METADATA_SQL"))throw new SQLException("private metadata");return fault.equals("METADATA_NULL")?null:metadata;});
            when(metadata.getDatabaseProductName()).thenAnswer(call->fault.equals("NOT_PG")?"H2":"PostgreSQL");when(metadata.getDatabaseMajorVersion()).thenAnswer(call->fault.equals("PG17")?17:18);
            when(connection.prepareStatement(anyString())).thenAnswer(call->{String query=call.getArgument(0);sql.add(query);if(fault.equals("ROLE_SQL"))throw new SQLException("private catalog");
                var statement=mock(PreparedStatement.class);statements.add(statement);var params=new HashMap<Integer,String>();
                doAnswer(bind->{params.put(bind.getArgument(0),bind.getArgument(1));return null;}).when(statement).setString(anyInt(),anyString());
                when(statement.executeQuery()).thenAnswer(execute->rows(query,params));return statement;
            });
        }
        TenantAuditPrivilegePreflight.Connector connector(){return (url,user,password)->{
            assertThat(url).isEqualTo(environment().get("LAN_PG_JDBC_URL"));assertThat(user).isEqualTo(APP);assertThat(password).isEqualTo(APP_PASSWORD);connections.incrementAndGet();return connection;};}
        ResultSet rows(String query,Map<Integer,String> parameters)throws Exception {
            List<Object[]> values=new ArrayList<>();int width;
            if(query.equals(TenantAuditPrivilegePreflight.ROLE_SQL)){
                width=15;Object[] current=role.clone();if(fault.equals("NULL_IDENTITY"))current[0]=null;
                if(fault.equals("NULL_BOOLEAN"))current[5]=null;if(fault.equals("STRING_BOOLEAN"))current[5]="false";
                if(!fault.equals("ZERO_ROWS"))values.add(current);if(fault.equals("TWO_ROWS"))values.add(current.clone());
            }else if(query.equals(TenantAuditPrivilegePreflight.AUDIT_SQL)){
                width=4;if(!fault.equals("AUDIT_MISSING"))values.add(new Object[]{fault.equals("AUDIT_VIEW")?"v":"r",fault.equals("AUDIT_OWNER"),!fault.equals("AUDIT_RLS_OFF"),!fault.equals("AUDIT_FORCE_OFF")});
            }else if(query.equals(TenantAuditPrivilegePreflight.COLUMNS_SQL)){
                width=2;for(Object[] item:columns)values.add(item.clone());
                if(fault.equals("INHERITED_REASON_SELECT"))values.get(11)[1]=true;if(fault.equals("DIFF_SELECT"))values.get(12)[1]=true;
                if(fault.equals("FUTURE_SELECT"))values.add(new Object[]{"future_metadata",true});if(fault.equals("MISSING_QUERY_COLUMN"))values.removeFirst();
                if(fault.equals("DUPLICATE_COLUMN"))values.add(values.getFirst().clone());if(fault.equals("NULL_COLUMN"))values.getFirst()[0]=null;
                if(fault.equals("NULL_COLUMN_SELECT"))values.getFirst()[1]=null;if(fault.equals("STRING_COLUMN_SELECT"))values.getFirst()[1]="true";
                if(fault.equals("NO_COLUMNS"))values.clear();if(fault.equals("TOO_MANY_COLUMNS"))for(int i=0;i<1601;i++)values.add(new Object[]{"future_"+i,false});
            }else{
                width=1;String table=parameters.get(1);boolean result=false;
                assertThat(table).isIn("public.audit_event","public.security_event","public.authentication_event");
                if(query.equals(TenantAuditPrivilegePreflight.EXISTS_SQL))assertThat(parameters).hasSize(1);
                else{assertThat(parameters).hasSize(2);String expected=table.equals("public.audit_event")?(query.equals(TenantAuditPrivilegePreflight.TABLE_PRIVILEGES_SQL)?"SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER,MAINTAIN":"INSERT,UPDATE,REFERENCES"):"SELECT";assertThat(parameters.get(2)).isEqualTo(expected); }
                if(query.equals(TenantAuditPrivilegePreflight.EXISTS_SQL))result=!(table.equals("public.security_event")&&fault.equals("SECURITY_MISSING")||table.equals("public.authentication_event")&&fault.equals("AUTH_MISSING"));
                else if(query.equals(TenantAuditPrivilegePreflight.TABLE_PRIVILEGES_SQL))result=table.equals("public.audit_event")&&(fault.equals("PUBLIC_TABLE_SELECT")||fault.equals("AUDIT_MUTATION"))||table.equals("public.security_event")&&fault.equals("SECURITY_TABLE_SELECT")||table.equals("public.authentication_event")&&fault.equals("AUTH_TABLE_SELECT");
                else if(query.equals(TenantAuditPrivilegePreflight.COLUMN_PRIVILEGES_SQL))result=table.equals("public.audit_event")&&fault.equals("AUDIT_COLUMN_MUTATION")||table.equals("public.security_event")&&fault.equals("SECURITY_COLUMN_SELECT")||table.equals("public.authentication_event")&&fault.equals("AUTH_COLUMN_SELECT");
                else throw new AssertionError("Unexpected SQL in catalog-only fixture");values.add(new Object[]{result});
            }
            var rows=mock(ResultSet.class);var md=mock(ResultSetMetaData.class);when(rows.getMetaData()).thenReturn(fault.equals("NULL_METADATA")?null:md);when(md.getColumnCount()).thenReturn(fault.equals("WRONG_WIDTH")?width+1:width);
            var at=new AtomicInteger(-1);when(rows.next()).thenAnswer(call->at.incrementAndGet()<values.size());when(rows.getObject(anyInt())).thenAnswer(call->values.get(at.get())[call.<Integer>getArgument(0)-1]);return rows;
        }
    }
}

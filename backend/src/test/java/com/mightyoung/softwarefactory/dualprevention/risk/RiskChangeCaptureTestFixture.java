package com.mightyoung.softwarefactory.dualprevention.risk;

import com.mightyoung.softwarefactory.identity.JooqPrincipalStatusReadRepository;
import com.mightyoung.softwarefactory.tenancy.JooqTenantApplicationReadRepository;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.jooq.*;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.*;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real private graph/transaction manager and controlled JDBC; never PostgreSQL/ACL/concurrency proof. */
final class RiskChangeCaptureTestFixture {
    final UUID tenant=new UUID(1,1),command=new UUID(2,2),scenario=new UUID(3,3),trace=new UUID(4,4),actor=new UUID(5,5);
    final Instant occurred=Instant.parse("2026-11-07T00:00:00.123456Z"),recorded=occurred.plusSeconds(30);
    UUID currentActor=actor;
    final DataSource source=mock(DataSource.class);final Connection connection;
    final AtomicBoolean auto=new AtomicBoolean(true),readOnly=new AtomicBoolean(false);
    final AtomicInteger isolation=new AtomicInteger(Connection.TRANSACTION_READ_COMMITTED),principalReads=new AtomicInteger(),tenantReads=new AtomicInteger(),appReads=new AtomicInteger();
    final List<String> sql=new ArrayList<>(),events=new ArrayList<>();final List<Object[]> appBindings=new ArrayList<>();
    String appliedTenant="",fault="";int badSourceColumn=-1;Object badSourceValue;Object[] existing,pending;
    boolean replay,sourceDuplicated,sourceMissing,drifted;
    final String principalSql=JooqPrincipalStatusReadRepository.commandReadSql().keySet().iterator().next().strip();
    final String applicationSql=JooqTenantApplicationReadRepository.commandReadSql().keySet().stream().filter(s->s.contains("tenant_application")).findFirst().orElseThrow().strip();
    RiskChangeCaptureTestFixture()throws SQLException {
        connection=spy(new MockConnection(this::execute));when(source.getConnection()).thenReturn(connection);
        var metadata=mock(DatabaseMetaData.class);when(metadata.getDatabaseProductName()).thenAnswer(c->fault.equals("NOT_PG")?"H2":"PostgreSQL");when(metadata.getDatabaseMajorVersion()).thenReturn(18);doReturn(metadata).when(connection).getMetaData();
        doAnswer(c->auto.get()).when(connection).getAutoCommit();doAnswer(c->{auto.set(c.getArgument(0));return null;}).when(connection).setAutoCommit(anyBoolean());
        doAnswer(c->readOnly.get()).when(connection).isReadOnly();doAnswer(c->{readOnly.set(c.getArgument(0));return null;}).when(connection).setReadOnly(anyBoolean());
        doAnswer(c->isolation.get()).when(connection).getTransactionIsolation();doAnswer(c->{isolation.set(c.getArgument(0));return null;}).when(connection).setTransactionIsolation(anyInt());
        doAnswer(call->{
            PreparedStatement statement=spy((PreparedStatement)call.callRealMethod());
            doAnswer(execute->{
                ResultSet rows=spy((ResultSet)execute.callRealMethod());boundaryDrift("EXECUTE");
                doAnswer(next->{Object value=next.callRealMethod();boundaryDrift("NEXT");return value;}).when(rows).next();
                doAnswer(close->{Object value=close.callRealMethod();boundaryDrift("CLOSE");return value;}).when(rows).close();return rows;
            }).when(statement).executeQuery();return statement;
        }).when(connection).prepareStatement(RiskChangeCaptureScope.TENANT_SQL);
        doAnswer(c->{events.add("commit");if(fault.equals("COMMIT"))throw new SQLException("private-sql-credential");if(pending!=null)existing=pending.clone();pending=null;return null;}).when(connection).commit();
        doAnswer(c->{events.add("rollback");pending=null;if(fault.equals("ROLLBACK"))throw new SQLException("private-sql-credential");return null;}).when(connection).rollback();
        doAnswer(c->{events.add("close");assertThat(TransactionSynchronizationManager.hasResource(source)).isFalse();if(fault.equals("CLOSE"))throw new SQLException("private-sql-credential");return null;}).when(connection).close();
    }
    RiskScenarioDraftChangedEvent event(){return new RiskScenarioDraftChangedEvent(tenant,command,"risk_scenario",scenario,"risk_scenario.create",1,occurred,trace,"CREATE");}
    RiskChangeCaptureService service(){return RiskChangeCaptureService.create(source,currentActor,Set.of(tenant));}
    Object[] cause(UUID by,Instant at){return new Object[]{tenant,command,1,at.atOffset(ZoneOffset.UTC),by};}
    MockResult[] execute(MockExecuteContext execution)throws SQLException {
        String query=execution.sql().strip();sql.add(query);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
        var holder=(ConnectionHolder)TransactionSynchronizationManager.getResource(source);assertThat(holder).isNotNull();assertThat(holder.getConnection()).isSameAs(connection);
        assertThat(auto.get()).isFalse();assertThat(readOnly.get()).isFalse();assertThat(isolation.get()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
        Object[] binds=execution.bindings();
        if(query.equals(RiskChangeCaptureService.SET_TENANT_SQL)){assertThat(binds).containsExactly(tenant.toString());appliedTenant=binds[0].toString();if(fault.equals("SET_HOLDER"))replaceHolder();return row(new String[]{"set_config"},new Object[]{appliedTenant});}
        if(query.equals(RiskChangeCaptureScope.TENANT_SQL)||query.equals("select current_setting('app.tenant_id',true)"))return row(new String[]{"value"},new Object[]{appliedTenant});
        if(query.equals("select pg_catalog.current_setting('app.tenant_id',true) as tenant_context"))return row(new String[]{"tenant_context"},new Object[]{appliedTenant});
        if(query.equals("select current_setting('transaction_isolation')"))return row(new String[]{"value"},new Object[]{"read committed"});
        if(query.equals("select current_setting('transaction_read_only')"))return row(new String[]{"value"},new Object[]{"off"});
        if(query.equals("select current_setting('lock_timeout')"))return row(new String[]{"value"},new Object[]{"0"});
        if(query.startsWith("select case when current_setting('lock_timeout')"))return row(new String[]{"value"},new Object[]{"5000ms"});
        if(query.equals("select set_config('lock_timeout', ?, true)")||query.startsWith("select pg_advisory_xact_lock_shared("))return new MockResult[]{new MockResult(0)};
        if(query.equals(principalSql)){
            assertThat(binds).containsExactly(currentActor);int n=principalReads.incrementAndGet();
            Object[] values={fault.equals("ACTOR_MISMATCH")?new UUID(99,99):currentActor,fault.equals("ACCOUNT_BOUND")?new UUID(98,98):null,
                    fault.equals("NOT_SERVICE")?"HUMAN":"SERVICE",fault.equals("SERVICE_DISABLED")||fault.equals("REVOKE_BEFORE_COMMIT")&&n>1?"DISABLED":"ACTIVE"};
            return rows(new String[]{"principal_id","principal_account_id","principal_kind","principal_status"},fault.equals("SERVICE_MISSING")?List.of():Collections.singletonList(values));
        }
        if(query.equals("select id,status,auth_epoch from public.tenant where id=cast(? as pg_catalog.uuid)")){
            assertThat(binds).containsExactly(tenant);int n=tenantReads.incrementAndGet();Object[] values={tenant,fault.equals("TENANT_SUSPENDED")?"SUSPENDED":"ACTIVE",fault.equals("TENANT_EPOCH_DRIFT")&&n>1?1L:0L};
            return rows(new String[]{"id","status","auth_epoch"},fault.equals("TENANT_MISSING")?List.of():Collections.singletonList(values));
        }
        if(query.equals(applicationSql)){
            appReads.incrementAndGet();appBindings.add(binds.clone());assertThat(binds).containsExactly(tenant,"safety");Object[] values={!fault.equals("APP_DISABLED"),fault.equals("APP_VERSION")?0L:1L};
            return rows(new String[]{"enabled","version"},fault.equals("APP_MISSING")?List.of():Collections.singletonList(values));
        }
        if(query.equals(RiskChangeCauseRepository.SOURCE_SQL.strip())){
            assertThat(binds).containsExactly(tenant,command);if(fault.equals("SOURCE_SQL"))throw new SQLException("private-sql-credential");
            Object[] values={tenant,command,scenario,1L,occurred.atOffset(ZoneOffset.UTC),"CREATE",tenant,command,"risk_scenario.create",scenario,1L,occurred.atOffset(ZoneOffset.UTC),trace};
            if(badSourceColumn>=0)values[badSourceColumn]=badSourceValue;
            var records=new ArrayList<Object[]>();if(!sourceMissing)records.add(values);if(sourceDuplicated)records.add(values.clone());
            return rows(new String[]{"tenant_id","command_id","entity_id","version","occurred_at","change_kind","receipt_tenant","receipt_command","action_code","result_scenario_id","result_version","receipt_time","trace_id"},records);
        }
        if(query.equals(RiskChangeCauseRepository.INSERT_SQL.strip())){
            assertThat(binds).containsExactly(tenant,command,currentActor);if(fault.equals("INSERT_SQL")||fault.equals("ROLLBACK"))throw new SQLException("private-sql-credential");
            if(existing!=null||replay)return rows(causeLabels(),List.of());
            pending=cause(currentActor,recorded);if(fault.equals("INSERT_ACTOR"))pending[4]=new UUID(99,99);if(fault.equals("INSERT_VERSION"))pending[2]=2;
            return row(causeLabels(),pending);
        }
        if(query.equals(RiskChangeCauseRepository.FIND_SQL.strip())){
            assertThat(binds).containsExactly(tenant,command);if(fault.equals("FIND_SQL"))throw new SQLException("private-sql-credential");
            return rows(causeLabels(),existing==null?List.of():fault.equals("DUPLICATE_CAUSE")?Arrays.asList(existing,existing.clone()):Collections.singletonList(existing));
        }
        throw new SQLException("Unexpected controlled risk capture statement");
    }
    void boundaryDrift(String phase){if(!drifted&&fault.equals("SCOPE_HOLDER_"+phase)){drifted=true;replaceHolder();}}
    void replaceHolder(){TransactionSynchronizationManager.unbindResource(source);TransactionSynchronizationManager.bindResource(source,new ConnectionHolder(mock(Connection.class)));}
    static String[] causeLabels(){return new String[]{"tenant_id","source_command_id","consumer_version","recorded_at","recorded_by_service_actor_id"};}
    static MockResult[] row(String[] labels,Object[] values){return rows(labels,Collections.singletonList(values));}
    @SuppressWarnings({"rawtypes","unchecked"}) static MockResult[] rows(String[] labels,List<Object[]> values){
        DSLContext db=DSL.using(SQLDialect.POSTGRES);Field<?>[] fields=new Field<?>[labels.length];
        for(int i=0;i<labels.length;i++){Object value=values.isEmpty()?null:values.getFirst()[i];fields[i]=DSL.field(DSL.name(labels[i]),(Class)(value==null?String.class:value.getClass()));}
        org.jooq.Result<Record> result=db.newResult(fields);for(var data:values){Record record=db.newRecord(fields);for(int i=0;i<fields.length;i++)record.set((Field)fields[i],data[i]);result.add(record);}
        return new MockResult[]{new MockResult(values.size(),result)};
    }
}

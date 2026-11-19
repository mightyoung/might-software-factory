package com.mightyoung.softwarefactory.dualprevention.risk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.AbstractDataSource;

/** Ephemeral CI only. Does not disable, replace or repair any production migration or constraint. */
final class CiPg18RiskCaptureFixture implements AutoCloseable {
    private static final List<String> VERSIONS = List.of("2","3","4","5","6","7","8","11","12","13","14","15","16","17","18","18.1","18.2","18.3","19","20","21","22","23","24","25","26","27","28","29","30","31","32","33","34");
    private static final String HISTORY = "flyway_schema_history_app";
    private static final Set<String> PHASES=Set.of("migrate","open","admin-connect","runtime-connect","target-version","target-marker","empty-schema","empty-runtime","seed-tenant","seed-template","seed-graph","history","schema","cause-table","cause-fks","cause-triggers","cause-key","cause-columns","cause-checks","cause-policy","runtime-role","runtime-columns","runtime-required","runtime-rights","runtime-login","runtime-table","instant-function","cause-count","source-snapshot","closed","unknown");
    private static final Map<String, Set<String>> READ_COLUMNS = Map.of(
            "principal", Set.of("id","account_id","kind","status"),
            "tenant", Set.of("id","status","auth_epoch"),
            "tenant_application", Set.of("tenant_id","application_key","enabled","version"),
            "risk_scenario_revision", Set.of("tenant_id","command_id","entity_id","version","occurred_at","change_kind"),
            "risk_scenario_receipt", Set.of("tenant_id","command_id","action_code","result_scenario_id","result_version","occurred_at","trace_id"));
    private final CiPg18CaptureTarget target;
    private final UUID tenantA=UUID.randomUUID(), tenantB=UUID.randomUUID();
    private final UUID serviceActor=UUID.randomUUID(), alternateServiceActor=UUID.randomUUID();
    private final Map<UUID, Identity> identities=new HashMap<>();
    private final DataSource runtime;
    private Flyway flyway;
    private boolean closed;
    private record Identity(UUID account, UUID actor, UUID member, UUID organization) {}

    private CiPg18RiskCaptureFixture(CiPg18CaptureTarget target) {
        this.target=Objects.requireNonNull(target);
        this.runtime=source(false);
    }

    static CiPg18RiskCaptureFixture open(CiPg18CaptureTarget target) {
        var fixture=new CiPg18RiskCaptureFixture(target);
        String phase="open";
        try {
            // This is deliberately before Flyway, CREATE ROLE, grants and all fixture DML.
            try(var connection=fixture.connect(true)) { fixture.requireEmpty(connection); }
            fixture.flyway=Flyway.configure().dataSource(fixture.source(true))
                    .locations("classpath:db/migration").table(HISTORY)
                    .baselineOnMigrate(false).outOfOrder(false).cleanDisabled(true)
                    .validateMigrationNaming(true).load();
            phase="migrate";
            if(fixture.flyway.migrate().migrationsExecuted!=VERSIONS.size()) throw failed("migrate",null);
            fixture.flyway.validate();
            phase="runtime-role";
            fixture.provisionRuntime();
            phase="seed-tenant";
            fixture.seedIdentities();
            phase="schema";
            fixture.assertMigrationsAndCauseSchema();
            phase="runtime-rights";
            fixture.verifyRuntimeRights();
            fixture.writeEvidence();
            return fixture;
        } catch (SQLException | IOException | RuntimeException failure) {
            fixture.closed=true;
            throw failed(phase,failure);
        }
    }

    DataSource runtimeSource(){ensureOpen();return runtime;}
    Connection openAdmin(){ensureOpen();try{return connect(true);}catch(SQLException failure){throw failed("admin-connect",failure);}}
    Connection openRuntime(){ensureOpen();try{return connect(false);}catch(SQLException failure){throw failed("runtime-connect",failure);}}
    UUID tenantA(){return tenantA;}
    UUID tenantB(){return tenantB;}
    UUID serviceActor(){return serviceActor;}
    UUID alternateServiceActor(){return alternateServiceActor;}
    @Override public void close(){closed=true;} // No retained connection/pool and no destructive cleanup.

    private DataSource source(boolean admin) {
        return new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {ensureOpen();return connect(admin);}
            @Override public Connection getConnection(String username,String password) throws SQLException {
                throw new SQLException("CI fixture connection identity is fixed","08004");
            }
        };
    }

    private Connection connect(boolean admin) throws SQLException {
        Connection connection=null;
        try {
            var properties=new Properties();
            properties.setProperty("user",admin?target.adminUser():target.runtimeUser());
            properties.setProperty("password",admin?target.adminPassword():target.runtimePassword());
            properties.setProperty("connectTimeout","10");
            properties.setProperty("socketTimeout","15");
            connection=DriverManager.getConnection(target.jdbcUrl(),properties);
            requireTarget(connection,admin);
            return connection;
        } catch (SQLException | RuntimeException failure) {
            if(connection!=null)try{connection.close();}catch(SQLException|RuntimeException ignored){}
            throw new SQLException("CI fixture connection rejected",state(failure));
        }
    }

    private void requireTarget(Connection connection, boolean admin) throws SQLException {
        var metadata=connection.getMetaData();
        if(metadata==null||!"PostgreSQL".equals(metadata.getDatabaseProductName())||metadata.getDatabaseMajorVersion()!=18)
            throw failed("target-version",null);
        try(var statement=connection.createStatement();var rows=statement.executeQuery(
                "select pg_catalog.current_database(),current_user,session_user,pg_catalog.current_setting('msf.ci_instance',true)")) {
            String expected=admin?target.adminUser():target.runtimeUser();
            if(!rows.next()||!target.databaseName().equals(rows.getString(1))
                    ||!expected.equals(rows.getString(2))||!expected.equals(rows.getString(3))
                    ||!target.instanceId().toString().equals(rows.getString(4))||rows.next())throw failed("target-marker",null);
        }
    }

    private void requireEmpty(Connection connection)throws SQLException {
        requireTrue(connection,"select not exists(select 1 from pg_catalog.pg_class c join pg_catalog.pg_namespace n on n.oid=c.relnamespace where n.nspname='public')"
                +" and not exists(select 1 from pg_catalog.pg_proc p join pg_catalog.pg_namespace n on n.oid=p.pronamespace where n.nspname='public')"
                +" and not exists(select 1 from pg_catalog.pg_type t join pg_catalog.pg_namespace n on n.oid=t.typnamespace where n.nspname='public')"
                +" and not exists(select 1 from pg_catalog.pg_namespace where nspname not in ('public','pg_catalog','information_schema','pg_toast'))",
                "empty-schema");
        try(var statement=connection.prepareStatement("select not exists(select 1 from pg_catalog.pg_roles where rolname=?)")){
            statement.setString(1,target.runtimeUser());try(var rows=statement.executeQuery()){requireBoolean(rows,"empty-runtime");}
        }
    }

    private void provisionRuntime()throws SQLException,IOException {
        try(var connection=openAdmin()) {
            connection.setAutoCommit(false);
            try(var statement=connection.createStatement()) {
                // The role name is target-fixed. Password is never logged or included in an exception.
                statement.execute("create role msf_ci_capture_runtime login nosuperuser nocreatedb nocreaterole noinherit noreplication nobypassrls password '"
                        +target.runtimePassword().replace("'","''")+"'");
                statement.execute(resource("runtime-grants.sql"));
                connection.commit();
            }
        }
    }

    private void seedIdentities()throws SQLException {
        try(var connection=openAdmin()) {
            connection.setAutoCommit(false);
            for(UUID tenant:List.of(tenantA,tenantB)) {
                var identity=new Identity(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());
                identities.put(tenant,identity);
                execute(connection,"insert into public.tenant(id,code,name,status,auth_epoch) values(?,?,?,'ACTIVE',0)",tenant,"CI-"+tenant,"CI isolated tenant");
                execute(connection,"select pg_catalog.set_config('app.tenant_id',?,true)",tenant.toString());
                execute(connection,"insert into public.account(id,login_key,status,credential_version) values(?,?,'ACTIVE',1)",identity.account(),"ci-"+identity.account());
                execute(connection,"insert into public.principal(id,kind,account_id,status) values(?,'HUMAN',?,'ACTIVE')",identity.actor(),identity.account());
                execute(connection,"insert into public.tenant_member(tenant_id,id,account_id,status,auth_epoch,display_name) values(?,?,?,'ACTIVE',0,'CI source author')",tenant,identity.member(),identity.account());
                execute(connection,"insert into public.org_unit(tenant_id,id,code,name,active) values(?,?,?,'CI organization',true)",tenant,identity.organization(),"ORG-"+identity.organization());
                execute(connection,"insert into public.tenant_application(tenant_id,application_key,enabled,version) values(?,'safety',true,1)",tenant);
            }
            for(UUID actor:List.of(serviceActor,alternateServiceActor))
                execute(connection,"insert into public.principal(id,kind,account_id,status) values(?,'SERVICE',null,'ACTIVE')",actor);
            connection.commit();
        }
    }

    RiskScenarioDraftChangedEvent seedEvent(UUID tenant) {
        ensureOpen();Identity identity=identities.get(tenant);
        if(identity==null)throw failed("seed-tenant",null);
        var values=new LinkedHashMap<String,String>();
        for(String key:List.of("work","hazard","scenario","link","workCommand","hazardCommand","command","trace",
                "workAudit","hazardAudit","riskAudit","workIdempotency","hazardIdempotency","riskIdempotency"))
            values.put(key,UUID.randomUUID().toString());
        values.put("tenant",tenant.toString());values.put("account",identity.account().toString());
        values.put("actor",identity.actor().toString());values.put("member",identity.member().toString());
        values.put("organization",identity.organization().toString());
        Instant occurred=Instant.now().truncatedTo(ChronoUnit.MICROS);values.put("occurredAt",occurred.toString());
        try(var connection=openAdmin()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            String sql=resource("risk-source-graph.sql");
            for(var entry:values.entrySet())sql=sql.replace("@"+entry.getKey()+"@",entry.getValue());
            if(sql.indexOf('@')>=0)throw failed("seed-template",null);
            try(var statement=connection.createStatement()){statement.setQueryTimeout(10);statement.execute(sql);}
            connection.commit(); // Real deferred FKs and all history/closure triggers must succeed.
            return new RiskScenarioDraftChangedEvent(tenant,UUID.fromString(values.get("command")),"risk_scenario",
                    UUID.fromString(values.get("scenario")),"risk_scenario.create",1,occurred,
                    UUID.fromString(values.get("trace")),"CREATE");
        } catch(SQLException|IOException|RuntimeException failure){throw failed("seed-graph",failure);}
    }

    void assertMigrationsAndCauseSchema() {
        ensureOpen();
        try {
            flyway.validate();
            var actual=new ArrayList<String>();
            for(var migration:flyway.info().applied()) {
                if(migration.getVersion()==null||!migration.getState().isApplied()||migration.getChecksum()==null)throw failed("history",null);
                actual.add(migration.getVersion().getVersion());
            }
            if(!actual.equals(VERSIONS))throw failed("history",null);
            try(var connection=openAdmin()) {
                requireExactCauseSchema(connection);
                requireTrue(connection,"select c.relkind='r' and c.relrowsecurity and c.relforcerowsecurity"
                        +" and c.relowner=(select oid from pg_catalog.pg_roles where rolname=current_user)"
                        +" from pg_catalog.pg_class c where c.oid='public.risk_change_cause'::pg_catalog.regclass","cause-table");
                requireTrue(connection,"select count(*)=3 and bool_and(convalidated and conenforced and confdeltype='r') from pg_catalog.pg_constraint"
                        +" where conrelid='public.risk_change_cause'::pg_catalog.regclass and contype='f'","cause-fks");
                requireTrue(connection,"select count(*)=2 and bool_and(tgenabled='O' and not tgisinternal) from pg_catalog.pg_trigger"
                        +" where tgrelid='public.risk_change_cause'::pg_catalog.regclass and not tgisinternal"
                        +" and tgname in ('g014r_cause_no_rewrite','g014r_cause_no_truncate')","cause-triggers");
                requireTrue(connection,"select count(*)=1 from pg_catalog.pg_constraint where conrelid='public.risk_change_cause'::pg_catalog.regclass"
                        +" and contype='p' and convalidated and conenforced and pg_catalog.pg_get_constraintdef(oid)='PRIMARY KEY (tenant_id, source_command_id)'","cause-key");
            }
        }catch(SQLException|RuntimeException failure){throw failed("schema",failure);}
    }


    /** Catalog observations, not a replacement DDL or fixture-only constraint. */
    private static void requireExactCauseSchema(Connection connection)throws SQLException {
        requireTrue(connection,"select count(*)=11 from pg_catalog.pg_constraint where conrelid='public.risk_change_cause'::pg_catalog.regclass","cause-table");
        var columns=List.of("tenant_id:uuid:-1","source_command_id:uuid:-1","consumer_version:int2:-1",
                "recorded_at:timestamptz:6","recorded_by_service_actor_id:uuid:-1");
        int count=0;
        try(var statement=connection.createStatement();var rows=statement.executeQuery("""
                select a.attname,t.typname,a.atttypmod,a.attnotnull,a.atthasdef,a.attidentity,a.attgenerated,
                       exists(select 1 from pg_catalog.pg_constraint n
                              where n.conrelid=a.attrelid and n.contype='n'
                                and n.conkey=array[a.attnum]::pg_catalog.int2[] and n.convalidated and n.conenforced)
                  from pg_catalog.pg_attribute a
                  join pg_catalog.pg_type t on t.oid=a.atttypid
                  join pg_catalog.pg_namespace ns on ns.oid=t.typnamespace
                 where a.attrelid='public.risk_change_cause'::pg_catalog.regclass
                   and a.attnum>0 and not a.attisdropped and ns.nspname='pg_catalog'
                 order by a.attnum
                """)){
            while(rows.next()){
                String shape=rows.getString(1)+":"+rows.getString(2)+":"+rows.getInt(3);
                if(count>=columns.size()||!columns.get(count++).equals(shape)||!rows.getBoolean(4)
                        ||rows.getBoolean(5)||!"".equals(rows.getString(6))||!"".equals(rows.getString(7))
                        ||!Boolean.TRUE.equals(rows.getObject(8)))throw failed("cause-columns",null);
            }
        }
        if(count!=5)throw failed("cause-columns",null);
        var checks=new HashSet<String>();
        try(var statement=connection.createStatement();var rows=statement.executeQuery("""
                select pg_catalog.pg_get_expr(c.conbin,c.conrelid),c.convalidated,c.conenforced,
                       exists(select 1 from pg_catalog.pg_depend d where d.classid='pg_catalog.pg_constraint'::pg_catalog.regclass
                         and d.objid=c.oid and d.refclassid='pg_catalog.pg_proc'::pg_catalog.regclass
                         and d.refobjid='public.g014_instant_valid(timestamp with time zone)'::pg_catalog.regprocedure)
                  from pg_catalog.pg_constraint c
                 where c.conrelid='public.risk_change_cause'::pg_catalog.regclass and c.contype='c'
                """)){
            while(rows.next()){
                String expression=canonicalExpression(rows.getString(1));
                if(!rows.getBoolean(2)||!rows.getBoolean(3)||!checks.add(expression)
                        ||("g014_instant_validrecorded_at".equals(expression)!=rows.getBoolean(4)))
                    throw failed("cause-checks",null);
            }
        }
        if(!checks.equals(Set.of("consumer_version=1","g014_instant_validrecorded_at")))throw failed("cause-checks",null);
        var expected=Map.of(
                "risk_change_cause_revision_fkey","risk_scenario_revision:tenant_id,source_command_id:tenant_id,command_id",
                "risk_change_cause_receipt_fkey","risk_scenario_receipt:tenant_id,source_command_id:tenant_id,command_id",
                "risk_change_cause_service_actor_fkey","principal:recorded_by_service_actor_id:id");
        var found=new HashSet<String>();
        try(var statement=connection.createStatement();var rows=statement.executeQuery("""
                select c.conname,n.nspname,r.relname,
                  (select pg_catalog.string_agg(a.attname,',' order by k.ord)
                     from pg_catalog.unnest(c.conkey) with ordinality k(num,ord)
                     join pg_catalog.pg_attribute a on a.attrelid=c.conrelid and a.attnum=k.num),
                  (select pg_catalog.string_agg(a.attname,',' order by k.ord)
                     from pg_catalog.unnest(c.confkey) with ordinality k(num,ord)
                     join pg_catalog.pg_attribute a on a.attrelid=c.confrelid and a.attnum=k.num),
                  c.convalidated,c.conenforced,c.condeferrable,c.condeferred,c.confdeltype,c.confupdtype,c.confmatchtype,c.conperiod
                from pg_catalog.pg_constraint c join pg_catalog.pg_class r on r.oid=c.confrelid
                join pg_catalog.pg_namespace n on n.oid=r.relnamespace
                where c.conrelid='public.risk_change_cause'::pg_catalog.regclass and c.contype='f'
                """)){
            while(rows.next()){
                String name=rows.getString(1),shape=rows.getString(3)+":"+rows.getString(4)+":"+rows.getString(5);
                if(!found.add(name)||!"public".equals(rows.getString(2))||!shape.equals(expected.get(name))
                        ||!rows.getBoolean(6)||!rows.getBoolean(7)||rows.getBoolean(8)||rows.getBoolean(9)
                        ||!"r".equals(rows.getString(10))||!"a".equals(rows.getString(11))||!"s".equals(rows.getString(12))||rows.getBoolean(13))
                    throw failed("cause-fks",null);
            }
        }
        if(!found.equals(expected.keySet()))throw failed("cause-fks",null);
        var triggers=Map.of("g014r_cause_no_rewrite",27,"g014r_cause_no_truncate",34);
        found.clear();
        try(var statement=connection.createStatement();var rows=statement.executeQuery("""
                select t.tgname,t.tgtype,t.tgenabled,t.tgisinternal,
                       t.tgfoid='public.g014r_reject_cause_mutation()'::pg_catalog.regprocedure,
                       t.tgqual is null,t.tgnargs,t.tgattr::pg_catalog.text,t.tgdeferrable,t.tginitdeferred
                  from pg_catalog.pg_trigger t where t.tgrelid='public.risk_change_cause'::pg_catalog.regclass
                """)){
            while(rows.next()){
                if(rows.getBoolean(4))continue; // Actual FK RI triggers have independent metadata.
                String name=rows.getString(1);
                if(!found.add(name)||!Objects.equals(triggers.get(name),rows.getInt(2))
                        ||!"O".equals(rows.getString(3))||!rows.getBoolean(5)||!rows.getBoolean(6)
                        ||rows.getInt(7)!=0||!"".equals(rows.getString(8))||rows.getBoolean(9)||rows.getBoolean(10))
                    throw failed("cause-triggers",null);
            }
        }
        if(!found.equals(triggers.keySet()))throw failed("cause-triggers",null);
        try(var statement=connection.createStatement();var rows=statement.executeQuery("""
                select polname,polcmd,polpermissive,polroles=array[0::pg_catalog.oid],
                       pg_catalog.pg_get_expr(polqual,polrelid),pg_catalog.pg_get_expr(polwithcheck,polrelid)
                  from pg_catalog.pg_policy where polrelid='public.risk_change_cause'::pg_catalog.regclass
                """)){
            String expression="tenant_id=nullifcurrent_setting'app.tenant_id'::text,true,''::text::uuid";
            if(!rows.next()||!"g014r_cause_tenant_isolation".equals(rows.getString(1))||!"*".equals(rows.getString(2))
                    ||!rows.getBoolean(3)||!rows.getBoolean(4)||!expression.equals(canonicalExpression(rows.getString(5)))
                    ||!expression.equals(canonicalExpression(rows.getString(6)))||rows.next())throw failed("cause-policy",null);
        }
    }

    private static String canonicalExpression(String expression) {
        if(expression==null)return "";
        return expression.toLowerCase(Locale.ROOT).replace("pg_catalog.","").replace("public.g014_instant_valid","g014_instant_valid")
                .replaceAll("[\\s()]","");
    }

    void verifyRuntimeRights() {
        try(var connection=openAdmin()) {
            try(var statement=connection.prepareStatement("select not rolsuper and not rolcreatedb and not rolcreaterole and not rolinherit and not rolreplication and not rolbypassrls"
                    +" and rolcanlogin and not exists(select 1 from pg_catalog.pg_auth_members m where m.member=r.oid)"
                    +" and not exists(select 1 from pg_catalog.pg_class c where c.relowner=r.oid)"
                    +" and not exists(select 1 from pg_catalog.pg_database d where d.datdba=r.oid)"
                    +" from pg_catalog.pg_roles r where rolname=?")) {
                statement.setString(1,target.runtimeUser());try(var rows=statement.executeQuery()){requireBoolean(rows,"runtime-role");}
            }
            var relations=new ArrayList<String>();
            try(var statement=connection.createStatement();var rows=statement.executeQuery(
                    "select c.relname from pg_catalog.pg_class c join pg_catalog.pg_namespace n on n.oid=c.relnamespace where n.nspname='public' and c.relkind in ('r','p') order by c.relname")){
                while(rows.next())relations.add(rows.getString(1));
            }
            if(!relations.containsAll(READ_COLUMNS.keySet())||!relations.contains("risk_change_cause"))throw failed("runtime-table",null);
            for(String table:relations) {
                boolean cause="risk_change_cause".equals(table);
                String relation="public."+table;
                Set<String> expected=cause?Set.of("tenant_id","source_command_id","consumer_version","recorded_at","recorded_by_service_actor_id")
                        :READ_COLUMNS.getOrDefault(table,Set.of());
                checkTableRights(connection,relation,false,false);
                try(var statement=connection.prepareStatement("select a.attname,pg_catalog.has_column_privilege(?,a.attrelid,a.attnum,'SELECT'),"
                        +" pg_catalog.has_column_privilege(?,a.attrelid,a.attnum,'INSERT'),"
                        +" pg_catalog.has_column_privilege(?,a.attrelid,a.attnum,'UPDATE,REFERENCES')"
                        +" from pg_catalog.pg_attribute a where a.attrelid=cast(? as pg_catalog.regclass) and a.attnum>0 and not a.attisdropped order by a.attnum")){
                    for(int i=1;i<=3;i++)statement.setString(i,target.runtimeUser());statement.setString(4,relation);
                    var found=new HashSet<String>();
                    try(var rows=statement.executeQuery()){while(rows.next()){
                        String column=rows.getString(1);
                        if(!found.add(column)||!(rows.getObject(2) instanceof Boolean select)
                                ||!(rows.getObject(3) instanceof Boolean insert)||!(rows.getObject(4) instanceof Boolean mutation)
                                ||select!=expected.contains(column)||insert!=cause||mutation)throw failed("runtime-columns",null);
                    }}
                    if(!found.containsAll(expected)||(cause&&!found.equals(expected)))throw failed("runtime-columns",null);
                }
            }
            try(var statement=connection.prepareStatement("select pg_catalog.has_function_privilege(?,'public.g014_instant_valid(timestamp with time zone)','EXECUTE')"
                    +" and pg_catalog.has_database_privilege(?,pg_catalog.current_database(),'CONNECT')"
                    +" and pg_catalog.has_schema_privilege(?,'public','USAGE')"
                    +" and not pg_catalog.has_database_privilege(?,pg_catalog.current_database(),'CREATE,TEMP')"
                    +" and not pg_catalog.has_schema_privilege(?,'public','CREATE')")){
                for(int i=1;i<=5;i++)statement.setString(i,target.runtimeUser());
                try(var rows=statement.executeQuery()){requireBoolean(rows,"runtime-required");}
            }
        }catch(SQLException|RuntimeException failure){throw failed("runtime-rights",failure);}
        try(var ignored=openRuntime()){}catch(SQLException failure){throw failed("runtime-login",failure);}
    }

    private void checkTableRights(Connection connection,String relation,boolean select,boolean insert)throws SQLException {
        for(String privilege:List.of("SELECT","INSERT","UPDATE","DELETE","TRUNCATE","REFERENCES","TRIGGER","MAINTAIN")) {
            try(var statement=connection.prepareStatement("select pg_catalog.has_table_privilege(?,cast(? as pg_catalog.regclass),?)")){
                statement.setString(1,target.runtimeUser());statement.setString(2,relation);statement.setString(3,privilege);
                try(var rows=statement.executeQuery()){
                    boolean expected=("SELECT".equals(privilege)&&select)||("INSERT".equals(privilege)&&insert);
                    if(!rows.next()||!(rows.getObject(1) instanceof Boolean value)||value!=expected||rows.next())throw failed("runtime-table",null);
                }
            }
        }
    }

    void revokeInstantFunction(){instantFunction(false);}
    void grantInstantFunction(){instantFunction(true);}
    private void instantFunction(boolean grant) {
        try(var connection=openAdmin();var statement=connection.createStatement()){
            statement.execute((grant?"grant execute on function public.g014_instant_valid(timestamptz) to ":"revoke execute on function public.g014_instant_valid(timestamptz) from ")+"msf_ci_capture_runtime");
        }catch(SQLException failure){throw failed("instant-function",failure);}
    }

    long causeCount(UUID tenant,UUID command) {
        try(var connection=openAdmin();var statement=connection.prepareStatement("select count(*) from public.risk_change_cause where tenant_id=? and source_command_id=?")){
            statement.setObject(1,tenant);statement.setObject(2,command);
            try(var rows=statement.executeQuery()){if(!rows.next())throw failed("cause-count",null);long count=rows.getLong(1);if(rows.next())throw failed("cause-count",null);return count;}
        }catch(SQLException failure){throw failed("cause-count",failure);}
    }

    String sourceSnapshot(RiskScenarioDraftChangedEvent event) {
        try(var connection=openAdmin();var statement=connection.prepareStatement(resource("source-snapshot.sql"))){
            statement.setObject(1,event.tenantId());statement.setObject(2,event.resourceId());
            try(var rows=statement.executeQuery()){if(!rows.next())throw failed("source-snapshot",null);String digest=rows.getString(1);if(digest==null||!digest.matches("[0-9a-f]{64}")||rows.next())throw failed("source-snapshot",null);return digest;}
        }catch(SQLException|IOException failure){throw failed("source-snapshot",failure);}
    }

    private void writeEvidence()throws IOException {
        var migrations=new ArrayList<String>();
        for(var migration:flyway.info().applied())migrations.add("{\"version\":\""+migration.getVersion().getVersion()+"\",\"script\":\""+migration.getScript()+"\",\"checksum\":"+migration.getChecksum()+"}");
        String json="{\"schemaVersion\":1,\"postgresMajor\":18,\"database\":\"msf_ci_risk_capture\",\"instanceMarkerVerified\":true,\"imageDigest\":\""+target.imageDigest()+"\",\"migrations\":["+String.join(",",migrations)+"],\"causeSchemaVerified\":true,\"runtimeRightsVerified\":true}\n";
        Path path=Path.of("target","ci-pg18-safe-evidence.json");Files.createDirectories(path.getParent());Files.writeString(path,json,StandardCharsets.UTF_8);
    }

    private static String resource(String name)throws IOException {
        try(var input=CiPg18RiskCaptureFixture.class.getResourceAsStream("/ci-pg18/"+name)){
            if(input==null)throw new IOException("CI fixture resource unavailable");
            return new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    private static void execute(Connection connection,String sql,Object...values)throws SQLException {
        try(var statement=connection.prepareStatement(sql)){for(int i=0;i<values.length;i++)statement.setObject(i+1,values[i]);statement.execute();}
    }
    private static void requireTrue(Connection connection,String sql,String phase)throws SQLException {
        try(var statement=connection.createStatement();var rows=statement.executeQuery(sql)){requireBoolean(rows,phase);}
    }
    private static void requireBoolean(ResultSet rows,String phase)throws SQLException {
        if(!rows.next()||!Boolean.TRUE.equals(rows.getObject(1))||rows.next())throw failed(phase,null);
    }
    private void ensureOpen(){if(closed)throw failed("closed",null);}
    private static String state(Throwable failure){
        for(Throwable cursor=failure;cursor!=null;cursor=cursor.getCause())
            if(cursor instanceof SQLException sql&&sql.getSQLState()!=null&&sql.getSQLState().matches("[0-9A-Z]{5}"))return sql.getSQLState();
        return "XXXXX";
    }
    private static IllegalStateException failed(String phase,Throwable failure){
        String safePhase=PHASES.contains(phase)?phase:"unknown";
        String sqlState=state(failure);
        String diagnostic="{\"schemaVersion\":1,\"phase\":\""+safePhase+"\",\"migrationVersion\":null,\"sqlState\":"
                +("XXXXX".equals(sqlState)?"null":"\""+sqlState+"\"")+"}\n";
        try {
            Path path=Path.of("target","ci-pg18-safe-failure.json");Files.createDirectories(path.getParent());
            Files.writeString(path,diagnostic,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        }catch(IOException|RuntimeException ignored){} // A diagnostic failure never replaces the original safe rejection.
        return new IllegalStateException("CI PG18 fixture rejected: phase="+safePhase+", version=18, SQLState="+sqlState);
    }
}

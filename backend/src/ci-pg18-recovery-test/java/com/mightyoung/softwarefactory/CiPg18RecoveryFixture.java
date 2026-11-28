package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.authorization.PostgresAuthorizationWriteGuard;
import com.mightyoung.softwarefactory.identity.CiPg18SourceSession;
import com.mightyoung.softwarefactory.identity.SessionSensitiveKeys;
import com.mightyoung.softwarefactory.testing.CiPg18EphemeralTarget;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.flywaydb.core.internal.resolver.ChecksumCalculator;
import org.flywaydb.core.internal.resource.StringResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Fixed owned CI fixture. Bootstrap is not a human producer; SERVICE attach has no HUMAN state. */
public final class CiPg18RecoveryFixture implements AutoCloseable {
    static final String DATABASE="msf_ci_risk_recovery",CANONICAL="msf_ci_recovery_runtime",CAPTURE="msf_ci_recovery_capture_runtime";
    private static final List<String> VERSIONS=List.of("2","3","4","5","6","7","8","11","12","13","14","15","16","17","18","18.1","18.2","18.3","19","20","21","22","23","24","25","26","27","28","29","30","31","32","33","34","35","36","37");
    private static final String EXTRA="/ci-pg18-recovery/canonical-extra-grants.sql",CAPTURE_GRANTS="/ci-pg18-recovery/capture-grants.sql";
    private final DataSource canonical,capture,admin;
    private final String imageDigest;
    private final UUID tenant,serviceActor;
    private CiPg18SourceFixture bootstrap;
    private BootstrapState state;
    private SessionSensitiveKeys keys;
    private CiPg18SourceSession session;
    private boolean producer,closed,verified;

    public record BootstrapState(CiPg18SourceFixture.Identity identity,UUID hazard,UUID serviceActor){
        @Override public String toString(){return "RecoveryBootstrapState[REDACTED]";}
    }

    private CiPg18RecoveryFixture(String url,UUID marker,String imageDigest,String runtimePassword,String capturePassword,
            String adminPassword,UUID tenant,UUID serviceActor){
        if(!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]{4,5}/"+DATABASE)||marker==null
                ||!imageDigest.matches("sha256:[a-f0-9]{64}"))throw rejected();
        this.imageDigest=imageDigest;this.tenant=Objects.requireNonNull(tenant);this.serviceActor=Objects.requireNonNull(serviceActor);
        canonical=fixedSource(url,marker,CANONICAL,runtimePassword);
        capture=fixedSource(url,marker,CAPTURE,capturePassword);
        admin=adminPassword==null?null:fixedSource(url,marker,"msf_ci_bootstrap",adminPassword);
    }

    public static CiPg18RecoveryFixture bootstrap(CiPg18EphemeralTarget target,String capturePassword){
        if(target==null||!DATABASE.equals(target.databaseName())||!CANONICAL.equals(target.runtimeUser())
                ||!"msf_ci_bootstrap".equals(target.adminUser()))throw rejected();
        secret(capturePassword);
        if(capturePassword.equals(target.runtimePassword())||capturePassword.equals(target.adminPassword()))throw rejected();
        CiPg18SourceFixture source=null;
        String phase="open";
        try{
            source=CiPg18SourceFixture.openRecovery(target);
            UUID actor=UUID.randomUUID();
            var fixture=new CiPg18RecoveryFixture(target.jdbcUrl(),target.instanceId(),target.imageDigest(),target.runtimePassword(),
                    capturePassword,target.adminPassword(),source.identity().tenant(),actor);
            fixture.bootstrap=source;
            fixture.state=new BootstrapState(source.identity(),source.hazard(),actor);
            phase="seed-service";
            fixture.securitySetup(connection->{
                try(var statement=connection.createStatement()){
                    statement.execute("create role "+CAPTURE+" login nosuperuser nocreatedb nocreaterole noinherit noreplication nobypassrls password '"+capturePassword+"'");
                    statement.execute(resource(CAPTURE_GRANTS));
                }
                try(var statement=connection.prepareStatement("insert into public.principal(id,kind,account_id,status) values(?,'SERVICE',null,'ACTIVE')")){
                    statement.setObject(1,actor);if(statement.executeUpdate()!=1)throw rejected();
                }
            });
            fixture.verifyAll();return fixture;
        }catch(Throwable failure){
            if(!(failure instanceof CiPg18SourceFixture.RecoveryBootstrapFailure)&&!(failure instanceof RecordedFailure))writeFailure(phase,failure);
            if(source!=null)source.close();throw rejected();
        }
    }

    public static CiPg18RecoveryFixture attachProducer(CiPg18RecoveryWorkerTarget target){
        if(target==null||!target.producer())throw rejected();
        var fixture=new CiPg18RecoveryFixture(target.jdbcUrl(),target.instanceId(),target.imageDigest(),target.runtimePassword(),
                target.capturePassword(),target.adminPassword(),target.tenant(),target.serviceActor());
        fixture.producer=true;fixture.state=new BootstrapState(target.identity(),target.hazard(),target.serviceActor());
        if(!target.tenant().equals(target.identity().tenant()))throw rejected();
        try{
            fixture.verifyAll();
            byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);
            try{fixture.keys=new SessionSensitiveKeys("ci-recovery-k1",Map.of("ci-recovery-k1",bytes));}finally{Arrays.fill(bytes,(byte)0);}
            fixture.securitySetup(connection->fixture.session=CiPg18SourceSession.create(connection,fixture.tenant,
                    fixture.state.identity().account(),fixture.state.identity().actor(),fixture.state.identity().member(),fixture.keys));
            return fixture;
        }catch(Throwable failure){if(!(failure instanceof RecordedFailure))writeFailure("source-producer",failure);fixture.close();throw rejected();}
    }

    public static CiPg18RecoveryFixture attachRecovery(CiPg18RecoveryWorkerTarget target){
        if(target==null||target.producer())throw rejected();
        var fixture=new CiPg18RecoveryFixture(target.jdbcUrl(),target.instanceId(),target.imageDigest(),target.runtimePassword(),
                target.capturePassword(),null,target.tenant(),target.serviceActor());
        try{fixture.verifyAll();return fixture;}catch(Throwable failure){if(!(failure instanceof RecordedFailure))writeFailure("service-consumer",failure);fixture.close();throw rejected();}
    }

    public DataSource canonicalSource(){open();return canonical;}
    public DataSource captureSource(){open();return capture;}
    public UUID tenant(){open();return tenant;}
    public UUID serviceActor(){open();return serviceActor;}
    public BootstrapState bootstrapState(){open();if(bootstrap==null)throw rejected();return state;}
    public CiPg18SourceFixture.Identity identity(){open();if(!producer)throw rejected();return state.identity();}
    public UUID hazard(){open();if(!producer)throw rejected();return state.hazard();}
    public SessionSensitiveKeys keys(){open();if(!producer||keys==null)throw rejected();return keys;}
    public CiPg18SourceSession session(){open();if(!producer||session==null)throw rejected();return session;}

    /** Parent-only independent server READ ONLY transaction, never a bootstrap mutation channel. */
    public Connection observer()throws SQLException{
        open();if(admin==null||producer||TransactionSynchronizationManager.isActualTransactionActive()
                ||TransactionSynchronizationManager.isSynchronizationActive()||!TransactionSynchronizationManager.getResourceMap().isEmpty())throw rejected();
        Connection connection=admin.getConnection();
        try{connection.setReadOnly(true);connection.setAutoCommit(false);return connection;}
        catch(Throwable failure){try{connection.close();}catch(Throwable ignored){}throw new SQLException("Recovery observer unavailable","08004");}
    }
    public void setServiceActive(boolean active){
        open();if(bootstrap==null)throw rejected();
        try{securitySetup(connection->{try(var statement=connection.prepareStatement("update public.principal set status=? where id=? and kind='SERVICE' and account_id is null")){
            statement.setString(1,active?"ACTIVE":"DISABLED");statement.setObject(2,serviceActor);if(statement.executeUpdate()!=1)throw rejected();
        }});}catch(Throwable failure){throw rejected();}
    }

    /** Six real owned-PG negative checks; called by the normal scenario before any producer starts. */
    public void verifyGrantOptionsRefused(){
        open();if(bootstrap==null||producer)throw rejected();
        try{
            for(String role:List.of(CANONICAL,CAPTURE)){
                String column=role.equals(CANONICAL)?"select(id) on public.account":"select(id) on public.principal";
                for(String privilege:List.of(column,"connect on database "+DATABASE,"usage on schema public")){
                    verifyAll();
                    securitySetup(connection->{try(var statement=connection.createStatement()){
                        statement.execute("grant "+privilege+" to "+role+" with grant option");
                    }});
                    boolean refused=false;
                    try(Connection connection=canonical.getConnection()){
                        try{verifyRole(connection,role,role.equals(CANONICAL)?canonicalGrants():resource(CAPTURE_GRANTS));}
                        catch(IllegalStateException expected){refused=true;}
                    }finally{
                        securitySetup(connection->{try(var statement=connection.createStatement()){
                            statement.execute("revoke grant option for "+privilege+" from "+role);
                        }});
                    }
                    if(!refused)throw rejected();
                    verifyAll();
                }
            }
        }catch(Throwable failure){if(!(failure instanceof RecordedFailure))writeFailure("canonical-rights",failure);throw rejected();}
    }

    public void verifyAll(){
        open();verified=false;
        String phase="canonical-rights";
        try(Connection connection=canonical.getConnection()){
            phase="history";verifyHistory(connection);
            phase="canonical-rights";verifySchema(connection);verifyRole(connection,CANONICAL,canonicalGrants());
            phase="capture-rights";verifyRole(connection,CAPTURE,resource(CAPTURE_GRANTS));verified=true;
        }catch(Throwable failure){writeFailure(phase,failure);throw new RecordedFailure();}
    }
    public void writeEvidence(){
        open();if(bootstrap==null)throw rejected();verifyAll();
        try(Connection connection=canonical.getConnection()){
            var migrations=verifyHistory(connection);
            var value=Map.of("schemaVersion",1,"postgresMajor",18,"database",DATABASE,"instanceMarkerVerified",true,
                    "imageDigest",imageDigest,"migrations",migrations,"recoverySchemaVerified",true,"runtimeRightsVerified",true,"captureRightsVerified",true);
            Files.writeString(Path.of("target","ci-pg18-recovery-safe-evidence.json"),
                    tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(value),StandardCharsets.UTF_8);
        }catch(Throwable failure){throw rejected();}
    }

    private interface Setup{void run(Connection connection)throws Exception;}
    private void securitySetup(Setup setup){
        if(admin==null)throw rejected();
        var manager=new JdbcTransactionManager(admin);var tx=new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.executeWithoutResult(status->{new PostgresAuthorizationWriteGuard(admin).forGlobalSecurityChange();
            Connection connection=DataSourceUtils.getConnection(admin);
            try{setup.run(connection);}catch(Throwable failure){status.setRollbackOnly();throw new SetupFailure(sqlState(failure));}
            finally{DataSourceUtils.releaseConnection(connection,admin);}
        });
    }
    private static DataSource fixedSource(String url,UUID marker,String role,String password){
        secret(password);
        return new AbstractDataSource(){
            @Override public Connection getConnection()throws SQLException{
                Connection connection=null;
                try{
                    var properties=new Properties();properties.setProperty("user",role);properties.setProperty("password",password);
                    properties.setProperty("connectTimeout","10");properties.setProperty("socketTimeout","15");
                    properties.setProperty("ApplicationName","msf-ci-risk-recovery-"+marker);
                    connection=DriverManager.getConnection(url,properties);
                    if(!"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())||connection.getMetaData().getDatabaseMajorVersion()!=18)throw rejected();
                    try(var statement=connection.createStatement();var rows=statement.executeQuery("select current_database(),current_user,session_user,current_setting('msf.ci_instance',true)")){
                        if(!rows.next()||!DATABASE.equals(rows.getString(1))||!role.equals(rows.getString(2))||!role.equals(rows.getString(3))
                                ||!marker.toString().equals(rows.getString(4))||rows.next())throw rejected();
                    }
                    return connection;
                }catch(Throwable failure){if(connection!=null)try{connection.close();}catch(Throwable ignored){}throw new SQLException("Recovery connection unavailable","08004");}
            }
            @Override public Connection getConnection(String user,String supplied)throws SQLException{throw new SQLException("Fixed recovery identity","08004");}
            @Override public String toString(){return "RecoveryDataSource[REDACTED]";}
        };
    }

    private static List<Map<String,Object>> verifyHistory(Connection connection)throws Exception{
        var expected=new HashMap<String,Integer>();
        for(var resource:new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/V*__*.sql")){
            String name=resource.getFilename();if(name==null||!name.matches("V[0-9]+(?:_[0-9]+)*__[A-Za-z0-9_]+\\.sql"))throw rejected();
            try(var input=resource.getInputStream()){
                int checksum=ChecksumCalculator.calculate(new StringResource(new String(input.readAllBytes(),StandardCharsets.UTF_8)));
                if(expected.put(name,checksum)!=null)throw rejected();
            }
        }
        if(expected.size()!=VERSIONS.size())throw rejected();
        var versions=new ArrayList<String>();var evidence=new ArrayList<Map<String,Object>>();
        try(var statement=connection.createStatement();var rows=statement.executeQuery("select version,script,checksum,success from public.flyway_schema_history_app order by installed_rank")){
            while(rows.next()){
                String version=rows.getString(1),script=rows.getString(2);int checksum=rows.getInt(3);
                if(rows.wasNull()||!rows.getBoolean(4)||!Objects.equals(expected.get(script),checksum)||!script.startsWith("V"+version.replace('.','_')+"__"))throw rejected();
                versions.add(version);evidence.add(Map.of("version",version,"script",script,"checksum",checksum));
            }
        }
        if(!versions.equals(VERSIONS))throw rejected();return List.copyOf(evidence);
    }

    private static void verifySchema(Connection connection)throws Exception{
        require(connection,"select count(*)=6 and bool_and(c.relkind='r' and c.relrowsecurity and c.relforcerowsecurity and c.relowner=(select oid from pg_roles where rolname='msf_ci_bootstrap')) from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname='public' and c.relname in ('risk_scenario','risk_scenario_revision','risk_hazard','risk_hazard_revision','risk_scenario_revision_hazard','risk_scenario_receipt')");
        require(connection,"select count(*)=13 and bool_and(convalidated and conenforced and condeferred) from pg_constraint where conrelid in ('public.risk_scenario'::regclass,'public.risk_scenario_revision'::regclass,'public.risk_hazard'::regclass,'public.risk_hazard_revision'::regclass,'public.risk_scenario_revision_hazard'::regclass,'public.risk_scenario_receipt'::regclass) and contype='f' and condeferrable");
        require(connection,"select relrowsecurity and relforcerowsecurity and relkind='r' and relowner=(select oid from pg_roles where rolname='msf_ci_bootstrap') from pg_class where oid='public.risk_change_cause'::regclass");
        require(connection,"select count(*)=11 and bool_and(convalidated and conenforced) from pg_constraint where conrelid='public.risk_change_cause'::regclass");
        require(connection,"select count(*)=3 and bool_and(confdeltype='r') from pg_constraint where conrelid='public.risk_change_cause'::regclass and contype='f'");
        require(connection,"select count(*)=2 and bool_and(tgenabled='O') from pg_trigger where tgrelid='public.risk_change_cause'::regclass and not tgisinternal and tgname in ('g014r_cause_no_rewrite','g014r_cause_no_truncate')");
        require(connection,"select state='ENFORCING' and version=1 and activation_event_id is not null from public.session_authority_activation where singleton");
    }

    private static void verifyRole(Connection connection,String role,String grants)throws Exception{
        var expected=new HashMap<String,Map<String,Set<String>>>();var functions=new HashSet<String>();
        var column=Pattern.compile("grant (select|insert|update)\\(([a-z0-9_,]+)\\) on public\\.\\\"?([a-z_]+)\\\"? to "+role+";");
        String normalized=grants.replaceAll("(?m)--[^\\n]*","").replaceAll("\\s+"," ").strip();
        for(String raw:normalized.split(";")){
            String line=raw.strip()+";";if(line.equals(";"))continue;
            var match=column.matcher(line);
            if(match.matches())expected.computeIfAbsent(match.group(3),ignored->new HashMap<>()).put(match.group(1),Set.of(match.group(2).split(",")));
            else if(line.startsWith("grant execute on function ")&&line.endsWith(" to "+role+";"))functions.add(line.substring(26,line.length()-(" to "+role+";").length()));
            else if(line.startsWith("revoke ")||line.equals("grant connect on database "+DATABASE+" to "+role+";")||line.equals("grant usage on schema public to "+role+";")){}
            else throw rejected();
        }
        require(connection,"select not rolsuper and not rolcreatedb and not rolcreaterole and not rolinherit and not rolreplication and not rolbypassrls and rolcanlogin and not exists(select 1 from pg_auth_members where member=r.oid) and not exists(select 1 from pg_class where relowner=r.oid) and not exists(select 1 from pg_database where datdba=r.oid) from pg_roles r where rolname='"+role+"'");
        require(connection,"select has_database_privilege('"+role+"',current_database(),'CONNECT') and not has_database_privilege('"+role+"',current_database(),'CREATE,TEMP') and has_schema_privilege('"+role+"','public','USAGE') and not has_schema_privilege('"+role+"','public','CREATE')");
        require(connection,"select not exists(select 1 from pg_database d cross join lateral aclexplode(nullif(coalesce(d.datacl,acldefault('d',d.datdba)),'{}'::aclitem[])) a where d.datname=current_database() and a.is_grantable and a.grantee in (0,(select oid from pg_roles where rolname='"+role+"')))");
        require(connection,"select not exists(select 1 from pg_namespace n cross join lateral aclexplode(nullif(coalesce(n.nspacl,acldefault('n',n.nspowner)),'{}'::aclitem[])) a where n.nspname='public' and a.is_grantable and a.grantee in (0,(select oid from pg_roles where rolname='"+role+"')))");
        require(connection,"select not exists(select 1 from pg_attribute c join pg_class r on r.oid=c.attrelid join pg_namespace n on n.oid=r.relnamespace cross join lateral aclexplode(nullif(c.attacl,'{}'::aclitem[])) a where n.nspname='public' and c.attnum>0 and not c.attisdropped and a.is_grantable and a.grantee in (0,(select oid from pg_roles where rolname='"+role+"')))");
        try(var statement=connection.createStatement();var rows=statement.executeQuery("select c.oid,c.relkind,c.relname from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname='public' and c.relkind in ('r','p','v','m','f','S')")){
            var found=new HashSet<String>();
            while(rows.next()){
                long oid=rows.getLong(1);String relation=rows.getString(3);found.add(relation);
                try(var query=connection.prepareStatement(rows.getString(2).equals("S")?"select not has_sequence_privilege(?,?::oid,'USAGE,SELECT,UPDATE')":"select not has_table_privilege(?,?::oid,'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER,MAINTAIN')")){
                    query.setString(1,role);query.setLong(2,oid);try(var values=query.executeQuery()){truth(values);}
                }
                if(rows.getString(2).equals("S"))continue;
                try(var query=connection.prepareStatement("select attname,has_column_privilege(?,attrelid,attnum,'SELECT'),has_column_privilege(?,attrelid,attnum,'INSERT'),has_column_privilege(?,attrelid,attnum,'UPDATE'),has_column_privilege(?,attrelid,attnum,'REFERENCES') from pg_attribute where attrelid=?::oid and attnum>0 and not attisdropped")){
                    for(int i=1;i<=4;i++)query.setString(i,role);query.setLong(5,oid);
                    try(var values=query.executeQuery()){while(values.next())for(int i=0;i<4;i++)if(values.getBoolean(i+2)!=expected.getOrDefault(relation,Map.of()).getOrDefault(List.of("select","insert","update","references").get(i),Set.of()).contains(values.getString(1)))throw rejected();}
                }
            }
            if(!found.containsAll(expected.keySet()))throw rejected();
        }
        require(connection,"select not exists(select 1 from pg_proc p where p.proowner=(select oid from pg_roles where rolname='"+role+"'))");
        verifyFunctions(connection,role,functions);
    }

    private static void verifyFunctions(Connection connection,String role,Set<String> signatures)throws Exception{
        if(signatures.size()!=(role.equals(CANONICAL)?22:1))throw rejected();
        var expected=new HashSet<Long>();
        for(String signature:signatures)try(var statement=connection.prepareStatement("select to_regprocedure(?)::oid")){
            statement.setString(1,signature);try(var rows=statement.executeQuery()){if(!rows.next()||rows.getObject(1)==null||!expected.add(rows.getLong(1))||rows.next())throw rejected();}
        }
        var observed=new HashSet<Long>();
        try(var statement=connection.prepareStatement("select p.oid,p.prosecdef,p.prorettype='pg_catalog.trigger'::regtype,p.proowner=(select oid from pg_roles where rolname='msf_ci_bootstrap'),has_function_privilege(?,p.oid,'EXECUTE'),exists(select 1 from aclexplode(nullif(coalesce(p.proacl,acldefault('f',p.proowner)),'{}'::aclitem[])) a where a.grantee=0 and a.privilege_type='EXECUTE'),exists(select 1 from aclexplode(nullif(coalesce(p.proacl,acldefault('f',p.proowner)),'{}'::aclitem[])) a where a.grantee=(select oid from pg_roles where rolname=?) and a.privilege_type='EXECUTE'),exists(select 1 from aclexplode(nullif(coalesce(p.proacl,acldefault('f',p.proowner)),'{}'::aclitem[])) a where a.grantee=(select oid from pg_roles where rolname=?) and a.is_grantable) from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname='public'")){
            statement.setString(1,role);statement.setString(2,role);statement.setString(3,role);
            try(var rows=statement.executeQuery()){while(rows.next()){
                boolean allowed=expected.contains(rows.getLong(1));if(rows.getBoolean(7)!=allowed||rows.getBoolean(8))throw rejected();
                if(allowed){if(rows.getBoolean(2)||rows.getBoolean(3)||!rows.getBoolean(4)||!rows.getBoolean(5)||rows.getBoolean(6))throw rejected();observed.add(rows.getLong(1));}
                else if(rows.getBoolean(5)!=rows.getBoolean(6))throw rejected();
            }}
        }
        if(!observed.equals(expected))throw rejected();
    }

    private static String canonicalGrants()throws Exception{
        return resource("/ci-pg18-source/runtime-grants.sql").replace("msf_ci_risk_source",DATABASE).replace("msf_ci_source_runtime",CANONICAL)+"\n"+resource(EXTRA);
    }
    private static String resource(String path)throws Exception{
        try(var input=CiPg18RecoveryFixture.class.getResourceAsStream(path)){if(input==null)throw rejected();return new String(input.readAllBytes(),StandardCharsets.UTF_8);}
    }
    private static final class RecordedFailure extends IllegalStateException { RecordedFailure(){super("CI recovery fixture rejected");} }
    private static final class SetupFailure extends IllegalStateException {
        final String state;SetupFailure(String state){super("CI recovery setup rejected");this.state=state;}
    }
    private static String sqlState(Throwable failure){
        for(int index=0;index<8&&failure!=null;index++,failure=failure.getCause()){
            if(failure instanceof SetupFailure safe)return safe.state;
            if(failure instanceof SQLException sql&&sql.getSQLState()!=null&&sql.getSQLState().matches("[A-Z0-9]{5}"))return sql.getSQLState();
        }
        return null;
    }
    private static void writeFailure(String phase,Throwable failure){
        String safe=Set.of("open","history","canonical-rights","capture-rights","seed-service","source-producer","service-consumer").contains(phase)?phase:"unknown";
        try{
            var value=new LinkedHashMap<String,Object>();value.put("schemaVersion",1);value.put("database",DATABASE);value.put("phase",safe);
            value.put("migrationVersion",null);value.put("sqlState",sqlState(failure));
            Files.writeString(Path.of("target","ci-pg18-recovery-safe-failure.json"),
                    tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(value),StandardCharsets.UTF_8);
        }catch(Exception ignored){}
    }
    private static void secret(String value){if(value==null||!value.matches("[a-f0-9]{64}"))throw rejected();}
    private static void require(Connection connection,String sql)throws SQLException{try(var statement=connection.createStatement();var rows=statement.executeQuery(sql)){truth(rows);}}
    private static void truth(ResultSet rows)throws SQLException{if(!rows.next()||!rows.getBoolean(1)||rows.wasNull()||rows.next())throw rejected();}
    private static IllegalStateException rejected(){return new IllegalStateException("CI recovery fixture rejected");}
    private void open(){if(closed)throw rejected();}
    @Override public void close(){if(session!=null){session.close();session=null;}keys=null;if(bootstrap!=null)bootstrap.close();closed=true;}
    @Override public String toString(){return "CiPg18RecoveryFixture[REDACTED]";}
}

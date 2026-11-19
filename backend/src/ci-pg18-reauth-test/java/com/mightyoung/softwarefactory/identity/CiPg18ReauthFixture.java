package com.mightyoung.softwarefactory.identity;

import com.mightyoung.softwarefactory.authorization.PostgresAuthorizationWriteGuard;
import com.mightyoung.softwarefactory.testing.CiPg18EphemeralTarget;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.*;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Owned fresh CI target only. No proof, issuance/revoke receipt or revocation fence is seeded. */
final class CiPg18ReauthFixture implements AutoCloseable {
    static final String ROLE="msf_ci_reauth_runtime";
    static final List<String> VERSIONS=List.of("2","3","4","5","6","7","8","11","12","13","14","15","16","17","18","18.1","18.2","18.3","19","20","21","22","23","24","25","26","27","28","29","30","31","32","33","34","35","36");
    static final Set<String> PHASES=Set.of("open","target-version","target-marker","empty-schema","migrate","history","framework-schema","reauth-schema","runtime-role","runtime-rights","seed-identity","activate-authority","closed","unknown");
    static final List<String> REQUIRED_FUNCTIONS=List.of("public.require_sensitive_global_guard()","public.require_sensitive_enforcement()","public.require_sensitive_source(character varying,uuid,uuid,bigint)");
    private final CiPg18EphemeralTarget target;
    private final DataSource admin,runtime;
    private final Map<String,byte[]> keyMaterial=new LinkedHashMap<>();
    private final Map<Thread,Object> managedLeases=new ConcurrentHashMap<>();
    final AtomicReference<Fault> fault=new AtomicReference<>(Fault.NONE);
    final AtomicReference<Runnable> afterProof=new AtomicReference<>();
    final AtomicInteger proofWrites=new AtomicInteger(),receiptWrites=new AtomicInteger(),successAuditWrites=new AtomicInteger();
    final AtomicInteger issueCommitAttempts=new AtomicInteger(),issueCommits=new AtomicInteger(),issueRollbacks=new AtomicInteger(),extraBorrows=new AtomicInteger();
    final AtomicReference<String> lastSqlState=new AtomicReference<>();
    private final AtomicReference<String> lastToken=new AtomicReference<>(),lastSecretDigest=new AtomicReference<>(),lastBodyDigest=new AtomicReference<>(),lastFingerprint=new AtomicReference<>(),lastCredentialHash=new AtomicReference<>();
    private Flyway flyway;
    private boolean closed;
    enum Fault { NONE,AUDIT_WRITE,DEFERRED_TARGET,COMMIT_SQL,COMMIT_RUNTIME,COMMIT_ERROR }
    record Actor(UUID tenant,UUID account,UUID actor,UUID member,String password){
        @Override public String toString(){return "SyntheticReauthActor[private]";}
    }
    record Session(String primary,String bearer,Instant authenticated){
        @Override public String toString(){return "SyntheticReauthSession[private]";}
    }
    private CiPg18ReauthFixture(CiPg18EphemeralTarget target){
        if(target==null||!"msf_ci_g008_reauth".equals(target.databaseName())||!ROLE.equals(target.runtimeUser())||!"msf_ci_bootstrap".equals(target.adminUser()))throw rejected();
        this.target=target;admin=source(true);runtime=source(false);
        for(String id:List.of("k0","k1","k2")){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);keyMaterial.put(id,bytes);}
    }
    static CiPg18ReauthFixture open(CiPg18EphemeralTarget target){
        var fixture=new CiPg18ReauthFixture(target);String phase="open";
        try{
            phase="empty-schema";
            try(Connection c=fixture.connect(true)){
                require(c,"select not exists(select 1 from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname='public') and not exists(select 1 from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname='public') and not exists(select 1 from pg_type t join pg_namespace n on n.oid=t.typnamespace where n.nspname='public') and not exists(select 1 from pg_namespace where nspname not in ('public','pg_catalog','information_schema','pg_toast')) and not exists(select 1 from pg_roles where rolname='msf_ci_reauth_runtime')");
            }
            phase="migrate";fixture.flyway=Flyway.configure().dataSource(fixture.admin).locations("classpath:db/migration").table("flyway_schema_history_app").baselineOnMigrate(false).outOfOrder(false).cleanDisabled(true).validateMigrationNaming(true).load();
            if(fixture.flyway.migrate().migrationsExecuted!=VERSIONS.size())throw rejected();
            phase="history";fixture.verifyHistory();
            phase="framework-schema";new ResourceDatabasePopulator(new ClassPathResource("org/springframework/session/jdbc/schema-postgresql.sql")).execute(fixture.admin);
            phase="runtime-role";
            try(Connection c=fixture.connect(true)){c.setAutoCommit(false);try(Statement s=c.createStatement()){
                s.execute("create role msf_ci_reauth_runtime login nosuperuser nocreatedb nocreaterole noinherit noreplication nobypassrls password '"+target.runtimePassword()+"'");s.execute(grants());}c.commit();}
            phase="runtime-rights";fixture.verifyRights();
            phase="activate-authority";fixture.adminTransaction(List.of(),c->{
                UUID event=UUID.randomUUID();Object at=scalar(c,"select statement_timestamp()");
                String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("isolated-g008-ci-activation-v1".getBytes(StandardCharsets.UTF_8)));
                execute(c,"insert into public.identity_sensitive_event(id,operation,result,provenance,database_role,trace_id,policy_version,occurred_at) values(?,'ACTIVATE_SESSION_AUTHORITY','SUCCESS','CONTROLLED_ACTIVATION',current_user,?,'session-sensitive-v1',?)",event,UUID.randomUUID(),at);
                execute(c,"update public.session_authority_activation set state='ENFORCING',version=1,activated_at=?,activated_by_database_role=current_user,activation_event_id=?,activation_evidence_sha256=? where singleton and state='OFF' and version=0",at,event,digest);
            });
            phase="reauth-schema";fixture.verifySchema();fixture.evidence();return fixture;
        }catch(Throwable failure){failure(phase,failure);fixture.close();throw rejected();}
    }
    DataSource runtime(){ensureOpen();return runtime;}
    SessionSensitiveKeys keys(String current,String... retained){Map<String,byte[]> values=new LinkedHashMap<>();for(String id:retained)values.put(id,keyMaterial.get(id));return new SessionSensitiveKeys(current,values);}
    SecureRandom random(){return new SecureRandom(){@Override public void nextBytes(byte[] bytes){super.nextBytes(bytes);lastToken.set(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));}};}
    List<String> privateCanaries(){var values=new ArrayList<String>();for(var ref:List.of(lastToken,lastSecretDigest,lastBodyDigest,lastFingerprint,lastCredentialHash)){String value=ref.get();if(value!=null)values.add(value);}return values;}
    Actor actor(){
        ensureOpen();var actor=new Actor(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"Ci-Reauth-"+UUID.randomUUID());
        String encoded="{bcrypt}"+new BCryptPasswordEncoder(12).encode(actor.password());
        lastCredentialHash.set(encoded);
        adminTransaction(List.of(actor.tenant()),c->{
            execute(c,"insert into public.tenant(id,code,name,status,auth_epoch) values(?,?,?,'ACTIVE',1)",actor.tenant(),"CI-"+actor.tenant(),"CI reauth tenant");
            scalar(c,"select set_config('app.tenant_id',?,true)",actor.tenant().toString());
            execute(c,"insert into public.account(id,login_key,status,credential_version) values(?,?,'ACTIVE',1)",actor.account(),"ci-"+actor.account());
            execute(c,"insert into public.principal(id,kind,account_id,status) values(?,'HUMAN',?,'ACTIVE')",actor.actor(),actor.account());
            execute(c,"insert into public.tenant_member(tenant_id,id,account_id,status,auth_epoch,display_name) values(?,?,?,'ACTIVE',1,'CI reauth member')",actor.tenant(),actor.member(),actor.account());
            execute(c,"insert into public.credential(account_id,kind,secret_hash,changed_at) values(?,'PASSWORD',?,statement_timestamp())",actor.account(),encoded);
        });return actor;
    }
    Session session(Actor actor){return session(actor,true);}
    Session session(Actor actor,boolean legacyIndex){
        Instant authenticated=Instant.now().minusSeconds(2).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var session=new Session(UUID.randomUUID().toString(),UUID.randomUUID().toString(),authenticated);
        ensureOpen();if(TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isSynchronizationActive()||!TransactionSynchronizationManager.getResourceMap().isEmpty())throw rejected();
        try(Connection c=connect(true)){
            execute(c,"insert into public.spring_session(primary_id,session_id,creation_time,last_access_time,max_inactive_interval,expiry_time,principal_name) values(?,?,?,?,3600,?,?)",
                    session.primary(),session.bearer(),authenticated.toEpochMilli(),authenticated.toEpochMilli(),authenticated.plusSeconds(3600).toEpochMilli(),legacyIndex?"VerifiedSessionPrincipal":actor.account().toString());
        }catch(Exception failure){throw rejected();}return session;
    }
    Connection observer()throws SQLException{
        ensureOpen();if(TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isSynchronizationActive()||!TransactionSynchronizationManager.getResourceMap().isEmpty())throw rejected();
        Connection connection=connect(true);
        try{connection.setReadOnly(true);connection.setAutoCommit(false);if(!"on".equals(scalar(connection,"show transaction_read_only")))throw rejected();return connection;}
        catch(SQLException|RuntimeException|Error failure){try{connection.close();}catch(Throwable ignored){}throw failure;}
    }
    long count(String sql,Object... values){try(Connection c=observer()){return ((Number)scalar(c,sql,values)).longValue();}catch(Exception failure){throw rejected();}}
    Object value(String sql,Object... values){try(Connection c=observer()){return scalar(c,sql,values);}catch(Exception failure){throw rejected();}}
    void adminTransaction(List<UUID> tenants,SqlWork work){
        var manager=new JdbcTransactionManager(admin);var tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(10);
        tx.executeWithoutResult(status->{
            new PostgresAuthorizationWriteGuard(admin).forGlobalSecurityChange(tenants);
            Connection c=DataSourceUtils.getConnection(admin);try{work.run(c);}catch(Exception failure){throw rejected();}finally{DataSourceUtils.releaseConnection(c,admin);}
        });
    }
    interface SqlWork{void run(Connection connection)throws Exception;}
    private DataSource source(boolean privileged){return new AbstractDataSource(){
        @Override public Connection getConnection()throws SQLException{return connect(privileged);}
        @Override public Connection getConnection(String user,String password)throws SQLException{throw new SQLException("Fixed reauth identity","08004");}
    };}
    private Connection connect(boolean privileged)throws SQLException{
        ensureOpen();Connection c=null;
        try{
            if(!privileged&&managedLeases.containsKey(Thread.currentThread())){extraBorrows.incrementAndGet();throw rejected();}
            Properties p=new Properties();p.setProperty("user",privileged?target.adminUser():target.runtimeUser());p.setProperty("password",privileged?target.adminPassword():target.runtimePassword());
            p.setProperty("connectTimeout","10");p.setProperty("socketTimeout","15");p.setProperty("ApplicationName","msf-ci-reauth-"+target.instanceId()+(privileged?"-admin":"-runtime"));
            c=DriverManager.getConnection(target.jdbcUrl(),p);
            if(!"PostgreSQL".equals(c.getMetaData().getDatabaseProductName())||c.getMetaData().getDatabaseMajorVersion()!=18)throw rejected();
            try(Statement s=c.createStatement();ResultSet r=s.executeQuery("select current_database(),current_user,session_user,current_setting('msf.ci_instance',true)")){
                String user=privileged?target.adminUser():target.runtimeUser();if(!r.next()||!target.databaseName().equals(r.getString(1))||!user.equals(r.getString(2))||!user.equals(r.getString(3))||!target.instanceId().toString().equals(r.getString(4))||r.next())throw rejected();
            }return privileged?c:observe(c);
        }catch(Throwable failure){if(c!=null)try{c.close();}catch(Throwable ignored){}throw new SQLException("CI reauth connection rejected","08004");}
    }
    private Connection observe(Connection raw){
        Object lease=new Object();Thread thread=Thread.currentThread();boolean[] issue={false};
        return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
            if(method.getName().equals("toString"))return "CI reauth connection[private]";
            if(method.getName().equals("commit")&&issue[0])issueCommitAttempts.incrementAndGet();
            Object result;
            try{result=invoke(raw,method,args);}catch(Throwable failure){recordState(failure);throw failure;}
            if(method.getName().equals("setAutoCommit")&&Boolean.FALSE.equals(args[0])){if(managedLeases.putIfAbsent(thread,lease)!=null)throw rejected();}
            if(method.getName().equals("close")||method.getName().equals("setAutoCommit")&&Boolean.TRUE.equals(args[0]))managedLeases.remove(thread,lease);
            if(method.getName().equals("rollback")&&method.getParameterCount()==0&&issue[0])issueRollbacks.incrementAndGet();
            if(method.getName().equals("commit")&&issue[0]){
                issueCommits.incrementAndGet();Fault f=fault.get();
                if(Set.of(Fault.COMMIT_SQL,Fault.COMMIT_RUNTIME,Fault.COMMIT_ERROR).contains(f)&&fault.compareAndSet(f,Fault.NONE)){
                    if(f==Fault.COMMIT_SQL)throw new SQLException("PRIVATE_REAUTH_PG_DRIVER_SENTINEL","08006");
                    if(f==Fault.COMMIT_RUNTIME)throw new IllegalStateException("PRIVATE_REAUTH_PG_DRIVER_SENTINEL");
                    throw new AssertionError("PRIVATE_REAUTH_PG_DRIVER_SENTINEL");
                }
            }
            if(method.getName().equals("prepareStatement")&&args!=null&&args[0] instanceof String sql&&result instanceof PreparedStatement statement){
                Map<Integer,Object> bindings=new HashMap<>();String normalized=sql.toLowerCase(Locale.ROOT).replaceAll("\\s+"," ").strip();
                return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PreparedStatement.class},(ignored,call,values)->{
                    if(call.getName().startsWith("set")&&values!=null&&values.length>=2&&values[0] instanceof Integer index)bindings.put(index,call.getName().equals("setNull")?null:values[1]);
                    boolean execute=Set.of("execute","executeUpdate","executeLargeUpdate").contains(call.getName())&&call.getParameterCount()==0;
                    boolean proof=normalized.startsWith("insert into public.sensitive_reauth_proof(");
                    boolean audit=normalized.startsWith("insert into public.identity_sensitive_event(")&&"ISSUE_REAUTH".equals(bindings.get(2))&&"SUCCESS".equals(bindings.get(3));
                    if(execute&&audit&&fault.compareAndSet(Fault.AUDIT_WRITE,Fault.NONE))throw new SQLException("PRIVATE_REAUTH_PG_DRIVER_SENTINEL","08006");
                    if(execute&&audit&&fault.compareAndSet(Fault.DEFERRED_TARGET,Fault.NONE))statement.setString(9,UUID.randomUUID().toString());
                    Object value;try{value=invoke(statement,call,values);}catch(Throwable failure){recordState(failure);throw failure;}
                    boolean changed=execute&&(value instanceof Number count?count.longValue()==1:value instanceof Boolean&&statement.getUpdateCount()==1);
                    if(changed){
                        if(proof){issue[0]=true;proofWrites.incrementAndGet();lastBodyDigest.set((String)bindings.get(9));lastSecretDigest.set((String)bindings.get(10));Runnable hook=afterProof.getAndSet(null);if(hook!=null)hook.run();}
                        if(normalized.startsWith("insert into public.sensitive_reauth_issuance_receipt(")){receiptWrites.incrementAndGet();lastFingerprint.set((String)bindings.get(7));}
                        if(audit)successAuditWrites.incrementAndGet();
                    }return value;
                });
            }return result;
        });
    }
    private void recordState(Throwable failure){if(failure instanceof SQLException sql&&sql.getSQLState()!=null&&sql.getSQLState().matches("[0-9A-Z]{5}"))lastSqlState.set(sql.getSQLState());}
    private static Object invoke(Object raw,java.lang.reflect.Method method,Object[] args)throws Throwable{try{return method.invoke(raw,args);}catch(InvocationTargetException failure){throw failure.getCause();}}
    void verifyHistory(){flyway.validate();var actual=new ArrayList<String>();for(var m:flyway.info().applied()){if(m.getVersion()==null||!m.getState().isApplied()||m.getChecksum()==null)throw rejected();actual.add(m.getVersion().getVersion());}if(!actual.equals(VERSIONS))throw rejected();}
    void verifySchema()throws Exception{
        try(Connection c=observer()){
            require(c,"select count(*)=9 and bool_and(c.relkind='r' and not c.relrowsecurity and c.relowner=(select oid from pg_roles where rolname=current_user)) from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname='public' and c.relname in ('session_authority_activation','account_session_authority','sensitive_reauth_proof','sensitive_reauth_issuance_receipt','session_authority_command_receipt','recovery_contact_change','recovery_contact_command_receipt','recovery_contact_delivery_attempt','identity_sensitive_event')");
            require(c,"select not exists(select 1 from pg_constraint where conrelid in ('public.sensitive_reauth_proof'::regclass,'public.sensitive_reauth_issuance_receipt'::regclass,'public.session_authority_command_receipt'::regclass,'public.account_session_authority'::regclass) and (not convalidated or not conenforced))");
            require(c,"select count(*)=8 and bool_and(k.condeferrable and k.condeferred and k.convalidated and k.conenforced) from pg_constraint k join pg_attribute a on a.attrelid=k.conrelid and a.attnum=k.conkey[1] where k.contype='f' and ((k.conrelid='public.session_authority_activation'::regclass and a.attname='activation_event_id') or (k.conrelid='public.account_session_authority'::regclass and a.attname in ('created_event_id','revocation_event_id')) or (k.conrelid='public.sensitive_reauth_proof'::regclass and a.attname in ('issuance_receipt_id','session_consume_receipt_id')) or (k.conrelid='public.sensitive_reauth_issuance_receipt'::regclass and a.attname in ('proof_id','event_id')) or (k.conrelid='public.session_authority_command_receipt'::regclass and a.attname='event_id'))");
            require(c,"select count(*)=7 and bool_and(tgenabled='O' and tgdeferrable and tginitdeferred) from pg_trigger where not tgisinternal and tgname in ('check_sensitive_activation','check_sensitive_authority','check_sensitive_proof','check_sensitive_contact_change','check_sensitive_reauth_receipt_event','check_session_receipt_event','check_contact_receipt_event')");
            require(c,"select state='ENFORCING' and version=1 and activation_event_id is not null from public.session_authority_activation where singleton");
            require(c,"select count(*)=1 and bool_and(relrowsecurity and relforcerowsecurity) from pg_class where oid='public.tenant_member'::regclass");
        }
    }
    void verifyRights()throws Exception{
        var expected=expectedRights();try(Connection c=observer()){
            require(c,"select not rolsuper and not rolcreatedb and not rolcreaterole and not rolinherit and not rolreplication and not rolbypassrls and rolcanlogin and not exists(select 1 from pg_auth_members m where m.member=r.oid) and not exists(select 1 from pg_class c where c.relowner=r.oid) and not exists(select 1 from pg_database d where d.datdba=r.oid) from pg_roles r where rolname='msf_ci_reauth_runtime'");
            require(c,"select has_database_privilege('msf_ci_reauth_runtime',current_database(),'CONNECT') and not has_database_privilege('msf_ci_reauth_runtime',current_database(),'CREATE,TEMP') and has_schema_privilege('msf_ci_reauth_runtime','public','USAGE') and not has_schema_privilege('msf_ci_reauth_runtime','public','CREATE')");
            require(c,"select not exists(select 1 from pg_attribute a join pg_class c on c.oid=a.attrelid join pg_namespace n on n.oid=c.relnamespace cross join lateral aclexplode(a.attacl) x where n.nspname='public' and x.grantee=(select oid from pg_roles where rolname='msf_ci_reauth_runtime') and x.is_grantable)");
            Set<String> observed=new HashSet<>();try(Statement s=c.createStatement();ResultSet rows=s.executeQuery("select c.oid,c.relkind,c.relname from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname='public' and c.relkind in ('r','p','v','m','f','S')")){
                while(rows.next()){
                    long oid=rows.getLong(1);String name=rows.getString(3);observed.add(name);
                    try(PreparedStatement q=prepared(c,rows.getString(2).equals("S")?"select not has_sequence_privilege('msf_ci_reauth_runtime',?::oid,'USAGE,SELECT,UPDATE')":"select not has_table_privilege('msf_ci_reauth_runtime',?::oid,'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER,MAINTAIN')",oid);ResultSet result=q.executeQuery()){require(result);}
                    if(rows.getString(2).equals("S"))continue;
                    try(PreparedStatement q=prepared(c,"select attname,has_column_privilege('msf_ci_reauth_runtime',attrelid,attnum,'SELECT'),has_column_privilege('msf_ci_reauth_runtime',attrelid,attnum,'INSERT'),has_column_privilege('msf_ci_reauth_runtime',attrelid,attnum,'UPDATE'),has_column_privilege('msf_ci_reauth_runtime',attrelid,attnum,'REFERENCES') from pg_attribute where attrelid=?::oid and attnum>0 and not attisdropped",oid);ResultSet columns=q.executeQuery()){
                        while(columns.next())for(int index=0;index<4;index++){String privilege=List.of("select","insert","update","references").get(index);if(columns.getBoolean(index+2)!=expected.getOrDefault(name,Map.of()).getOrDefault(privilege,Set.of()).contains(columns.getString(1)))throw rejected();}
                    }
                }
            }if(!observed.containsAll(expected.keySet()))throw rejected();
            for(String function:REQUIRED_FUNCTIONS)try(PreparedStatement q=prepared(c,"select not prosecdef and prokind='f' and proowner=(select oid from pg_roles where rolname=current_user) and has_function_privilege('msf_ci_reauth_runtime',oid,'EXECUTE') and exists(select 1 from aclexplode(coalesce(proacl,acldefault('f',proowner))) a where a.grantee=0 and a.privilege_type='EXECUTE') from pg_proc where oid=?::regprocedure",function);ResultSet rows=q.executeQuery()){require(rows);}
            require(c,"select not exists(select 1 from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname='public' and (exists(select 1 from aclexplode(coalesce(p.proacl,acldefault('f',p.proowner))) a where a.grantee=(select oid from pg_roles where rolname='msf_ci_reauth_runtime')) or has_function_privilege('msf_ci_reauth_runtime',p.oid,'EXECUTE') is distinct from exists(select 1 from aclexplode(coalesce(p.proacl,acldefault('f',p.proowner))) a where a.grantee=0 and a.privilege_type='EXECUTE')))");
        }
    }
    private static Map<String,Map<String,Set<String>>> expectedRights()throws Exception{
        var pattern=Pattern.compile("grant (select|insert|update)\\(([a-z0-9_,]+)\\) on public\\.\"([a-z_]+)\" to msf_ci_reauth_runtime;");Map<String,Map<String,Set<String>>> expected=new HashMap<>();
        for(String line:grants().lines().toList()){var m=pattern.matcher(line);if(m.matches())expected.computeIfAbsent(m.group(3),ignored->new HashMap<>()).put(m.group(1),Set.of(m.group(2).split(",")));else if(line.startsWith("grant ")&&!line.startsWith("grant connect ")&&!line.startsWith("grant usage "))throw rejected();}if(expected.size()!=12)throw rejected();return expected;
    }
    private static String grants()throws Exception{try(var in=CiPg18ReauthFixture.class.getResourceAsStream("/ci-pg18-reauth/runtime-grants.sql")){if(in==null)throw rejected();return new String(in.readAllBytes(),StandardCharsets.UTF_8);}}
    private void evidence()throws Exception{
        var migrations=new ArrayList<Map<String,Object>>();for(var m:flyway.info().applied())migrations.add(Map.of("version",m.getVersion().getVersion(),"script",m.getScript(),"checksum",m.getChecksum()));
        String json=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(Map.of("schemaVersion",1,"postgresMajor",18,"database",target.databaseName(),"instanceMarkerVerified",true,"imageDigest",target.imageDigest(),"migrations",migrations,"reauthSchemaVerified",true,"runtimeRightsVerified",true));
        Files.writeString(Path.of("target/ci-pg18-reauth-safe-evidence.json"),json,StandardCharsets.UTF_8);
    }
    private static void failure(String phase,Throwable failure){
        String state=null;for(int n=0;n<8&&failure!=null;n++,failure=failure.getCause())if(failure instanceof SQLException sql&&sql.getSQLState()!=null&&sql.getSQLState().matches("[0-9A-Z]{5}")){state=sql.getSQLState();break;}
        try{Files.writeString(Path.of("target/ci-pg18-reauth-safe-failure.json"),"{\"schemaVersion\":1,\"database\":\"msf_ci_g008_reauth\",\"phase\":\""+(PHASES.contains(phase)?phase:"unknown")+"\",\"migrationVersion\":null,\"sqlState\":"+(state==null?"null":"\""+state+"\"")+"}",StandardCharsets.UTF_8);}catch(Exception ignored){}
    }
    static PreparedStatement prepared(Connection c,String sql,Object... values)throws SQLException{PreparedStatement s=c.prepareStatement(sql);for(int i=0;i<values.length;i++)s.setObject(i+1,values[i]);s.setQueryTimeout(10);return s;}
    static void execute(Connection c,String sql,Object... values)throws SQLException{try(PreparedStatement s=prepared(c,sql,values)){if(s.executeUpdate()!=1)throw rejected();}}
    static Object scalar(Connection c,String sql,Object... values)throws SQLException{try(PreparedStatement s=prepared(c,sql,values);ResultSet r=s.executeQuery()){if(!r.next())throw rejected();Object value=r.getObject(1);if(r.next())throw rejected();return value;}}
    static void require(Connection c,String sql)throws SQLException{try(Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)){require(r);}}
    private static void require(ResultSet r)throws SQLException{if(!r.next()||!r.getBoolean(1)||r.wasNull()||r.next())throw rejected();}
    private void ensureOpen(){if(closed)throw rejected();}
    static IllegalStateException rejected(){return new IllegalStateException("CI reauth fixture rejected");}
    @Override public void close(){closed=true;for(byte[] bytes:keyMaterial.values())Arrays.fill(bytes,(byte)0);for(var ref:List.of(lastToken,lastSecretDigest,lastBodyDigest,lastFingerprint,lastCredentialHash))ref.set(null);}
}

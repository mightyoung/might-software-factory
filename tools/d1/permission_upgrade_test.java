import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;

/** True V26 -> V27 -> V28 Flyway acceptance on three explicitly owned disposable upgrade databases. */
class permission_upgrade_test {
    static String url,user,password,scenario,database;
    static final UUID TENANT=UUID.fromString("c42ec44a-7055-4308-b3ab-f0381381af73");
    static final UUID ROLE=UUID.fromString("72f832de-e54a-4e6e-8f35-69ab3150a644");
    static final List<String> ACTIONS=List.of("read","create","update");
    static final Map<String,UUID> IDS=new LinkedHashMap<>();
    public static void main(String[] args)throws Exception {
        scenario=required("D1_UPGRADE_SCENARIO");database=required("D1_DATABASE");url=required("D1_JDBC_URL");user=required("D1_OPERATOR_USERNAME");password=required("D1_OPERATOR_PASSWORD");
        if(args.length!=0 || !Set.of("missing","compatible","incompatible").contains(scenario)
          || !database.equals(switch(scenario){case "compatible"->"msf_d0_d1_upgrade_test";case "incompatible"->"msf_d0_d1_incompatible_test";default->"msf_d0_d1_missing_test";})
          || !required("D1_UPGRADE_ACK").equals("isolated-d1-upgrade-only:"+database)
          || !url.matches("jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]{1,5}/"+database))throw new IllegalArgumentException("Owned disposable D1 upgrade target required");
        try(var c=connection();var s=c.createStatement();var r=s.executeQuery("select current_database(),current_setting('server_version_num')::int,rolsuper or rolbypassrls or rolcreatedb or rolcreaterole or rolreplication from pg_roles where rolname=current_user")){
            r.next();check(database.equals(r.getString(1))&&r.getInt(2)>=180000&&r.getInt(2)<190000&&!r.getBoolean(3),"Target/version/migrator mismatch");
        }
        check("26".equals(flyway("26").info().current().getVersion().toString()),"A real V26 baseline is required");
        for(String action:ACTIONS)IDS.put(action,UUID.nameUUIDFromBytes(("d1-permission-upgrade/"+action).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        try(var c=connection()){
            c.setAutoCommit(false);context(c);exec(c,"select pg_advisory_xact_lock(1297303111,1)");
            check(scalar(c,"select count(*) from public.permission where code in ('tool.read','tool.create','tool.update')")==0,"Unseeded upgrade target required");
            exec(c,"insert into public.tenant(id,code,name,status,auth_epoch) values (?,'d1-upgrade-owned','D1 owned permission upgrade fixture','ACTIVE',1)",TENANT);
            exec(c,"insert into public.\"role\"(tenant_id,id,code,name,active) values (?,?,'D1-UPGRADE','D1 upgrade',true)",TENANT,ROLE);
            if(!scenario.equals("missing"))for(String action:ACTIONS){
                exec(c,"insert into public.permission(id,code,resource_type,action,application_key,label,sort_order) values (?,?,'tool',?,null,?,?)",IDS.get(action),"tool."+action,action,action.equals("create")?"Custom operator label":null,action.equals("update")?999:0);
                exec(c,"insert into public.role_permission(tenant_id,role_id,permission_id) values (?,?,?)",TENANT,ROLE,IDS.get(action));
            }
            c.commit();
        }
        flyway("27").migrate();
        if(!scenario.equals("missing"))try(var c=connection()){check(scalar(c,"select count(*) from public.permission where code like 'tool.%' and application_key is null")==3,"Reproduce V27 legacy NULL state");}
        if(scenario.equals("incompatible")) {
            for(String mutation:List.of("resource_type='wrong'","action='wrong'","application_key='wrong'")){
                try(var c=connection()){exec(c,"update public.permission set "+mutation+" where code='tool.read'");}
                try {flyway("28").migrate();throw new AssertionError("Incompatible permission was silently repaired");}
                catch(org.flywaydb.core.api.FlywayException expected){check(hasState(expected,"23514"),"Expected incompatible tuple SQLState23514");}
                try(var c=connection()){
                    check(scalar(c,"select count(*) from public.flyway_schema_history_app where version='28'")==0,"Failed V28 must leave no forged/success history");
                    check(scalar(c,"select count(*) from public.permission where code='tool.create' and application_key is null and label='Custom operator label'")==1,"Rejected upgrade must remain atomic");
                    assertAssociations(c,3);exec(c,"update public.permission set resource_type='tool',action='read',application_key=null where code='tool.read'");
                }
            }
        } else {
            flyway("28").migrate();flyway("28").validate();
            try(var c=connection()){
                check(scalar(c,"select count(*) from public.permission where resource_type='tool' and code='tool.'||action and application_key='tooling' and label is not null and btrim(label)<>'' and sort_order<>0")==3,"All canonical capabilities must be catalog-manageable");
                assertAssociations(c,scenario.equals("missing")?0:3);
                if(scenario.equals("compatible")){
                    check(scalar(c,"select count(*) from public.permission where code='tool.create' and label='Custom operator label'")==1,"Retain custom nonempty label");
                    check(scalar(c,"select count(*) from public.permission where code='tool.update' and sort_order=999")==1,"Retain custom nonzero order");
                }
                check(scalar(c,"select count(*) from public.flyway_schema_history_app where version in ('27','28') and success")==2,"Both versions require real successful Flyway history");
            }
        }
        System.out.println("D1_PERMISSION_UPGRADE_OK scenario="+scenario+" no_new_grants=true real_flyway=true");
    }
    static void assertAssociations(Connection c,int expected)throws SQLException {
        context(c);check(scalar(c,"select count(*) from public.role_permission where tenant_id='"+TENANT+"'")==expected,"Permission associations changed");
        check(scalar(c,"select count(*) from public.role_grant where tenant_id='"+TENANT+"'")==0,"No role granted to a user");
        check(scalar(c,"select count(*) from public.tenant_application where tenant_id='"+TENANT+"'")==0,"No existing tenant enabled");
        if(expected>0)for(String action:ACTIONS)try(var s=c.prepareStatement("select id from public.permission where code=?")){s.setString(1,"tool."+action);try(var r=s.executeQuery()){check(r.next()&&IDS.get(action).equals(r.getObject(1)),"Preserve legacy ID");}}
    }
    static boolean hasState(Throwable e,String state){for(Throwable x=e;x!=null;x=x.getCause())if(x instanceof SQLException s&&state.equals(s.getSQLState()))return true;return false;}
    static Flyway flyway(String target){return Flyway.configure().dataSource(url,user,password).locations("filesystem:"+Path.of("backend/src/main/resources/db/migration").toAbsolutePath()).table("flyway_schema_history_app").target(target).load();}
    static Connection connection()throws SQLException{return DriverManager.getConnection(url,user,password);}
    static void context(Connection c)throws SQLException{exec(c,"select set_config('app.tenant_id',?,false)",TENANT.toString());}
    static long scalar(Connection c,String sql)throws SQLException{try(var s=c.createStatement();var r=s.executeQuery(sql)){r.next();return r.getLong(1);}}
    static void exec(Connection c,String sql,Object...args)throws SQLException{try(var s=c.prepareStatement(sql)){for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);s.execute();}}
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static String required(String key){String value=System.getenv(key);if(value==null||value.isBlank())throw new IllegalArgumentException("Missing "+key);return value;}
}

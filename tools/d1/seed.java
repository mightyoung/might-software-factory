import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Explicit isolated D1 identity/scope seed; no existing tenant gets entitlements or grants. */
class seed {
    static final List<String> ACTORS=List.of("keeper","reader","createonly","updateonly","nonkeeper","outside","admin","foreignkeeper");
    static String database;
    static final BCryptPasswordEncoder PASSWORDS=new BCryptPasswordEncoder(12);
    public static void main(String[] args) {
        try {
            if(args.length!=0) throw new IllegalArgumentException("No positional inputs");
            database=required("D1_DATABASE");String url=required("D1_JDBC_URL");
            if(!database.matches("msf_d0_d1_[a-z0-9_]+_test") || !required("D1_SEED_ACK").equals("isolated-d1-seed-only:"+database)
                    || !url.matches("jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]{1,5}/"+database)) throw new IllegalArgumentException("Explicit isolated D1 target required");
            Map<String,String> passwords=new LinkedHashMap<>();
            for(String actor:ACTORS) {
                String secret=required("D1_SEED_PASSWORD_"+actor.toUpperCase(Locale.ROOT));int bytes=secret.getBytes(StandardCharsets.UTF_8).length;
                if(bytes<16 || bytes>72 || !StandardCharsets.UTF_8.newEncoder().canEncode(secret)) throw new IllegalArgumentException("Private UTF-8 passwords require 16-72 bytes");
                passwords.put(actor,secret);
            }
            try(var c=DriverManager.getConnection(url,required("D1_OPERATOR_USERNAME"),required("D1_OPERATOR_PASSWORD"))) {
                try(var s=c.createStatement();var r=s.executeQuery("select current_database(),current_setting('server_version_num')::int")) {r.next();if(!database.equals(r.getString(1))||r.getInt(2)<180000||r.getInt(2)>=190000)throw new IllegalArgumentException("Wrong target");}
                c.setAutoCommit(false);c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                try {
                    sql(c,"select pg_advisory_xact_lock(1297303111,1)");
                    try(var s=c.createStatement();var r=s.executeQuery("select exists(select 1 from public.flyway_schema_history_app where version='27' and success)")) {r.next();if(!r.getBoolean(1))throw new IllegalStateException("Apply V27 first");}
                    for(String tenant:List.of("main","foreign")) {
                        tenant(c,tenant);boolean exists;
                        try(var q=c.prepareStatement("select id,name from public.tenant where code=?")) {
                            q.setString(1,code(tenant));try(var r=q.executeQuery()) {exists=r.next();if(exists&&(!id(tenant+"/tenant").equals(r.getObject(1))||!r.getString(2).equals("D1 isolated tooling seed v1")))throw new IllegalStateException("Unowned seed tenant collision");}
                        }
                        if(!exists) seedTenant(c,tenant,passwords);
                        verify(c,tenant,passwords);
                    }
                    c.commit();
                } catch(Exception e) {c.rollback();throw e;}
            }
            System.out.println("D1_SEED_OK tenant=d1seed foreignTenant=d1foreign");
            for(String actor:ACTORS)System.out.println("D1_ACCOUNT "+login(actor));
            System.out.println("D1_MAIN_SCOPE="+id("main/scopeA"));System.out.println("D1_OUTSIDE_SCOPE="+id("main/scopeB"));System.out.println("D1_FOREIGN_SCOPE="+id("foreign/scopeA"));
        } catch(Exception e) {System.err.println("D1_SEED_FAILED type="+e.getClass().getSimpleName()+" sqlState="+(e instanceof SQLException q?q.getSQLState():"n/a"));System.exit(1);}
    }
    static void seedTenant(Connection c,String t,Map<String,String> passwords)throws SQLException {
        UUID tenant=id(t+"/tenant");
        sql(c,"insert into public.tenant(id,code,name,status,auth_epoch) values (?,?,?,'ACTIVE',1)",tenant,code(t),"D1 isolated tooling seed v1");
        for(String app:List.of("tooling","platform"))sql(c,"insert into public.tenant_application(tenant_id,application_key,enabled,version) values (?,?,true,1)",tenant,app);
        for(String suffix:List.of("A","B")) {
            sql(c,"insert into public.org_unit(tenant_id,id,code,name,active) values (?,?,?,?,true)",tenant,id(t+"/org"+suffix),"D1-ORG-"+suffix,"D1 synthetic organization "+suffix);
            sql(c,"insert into public.tool_custody_scope(tenant_id,id,organization_id,code,name,active) values (?,?,?,?,?,true)",tenant,id(t+"/scope"+suffix),id(t+"/org"+suffix),"D1-SCOPE-"+suffix,"D1 synthetic custody "+suffix);
        }
        for(String actor:actors(t)) {
            String suffix=actor.equals("outside")?"B":"A";
            sql(c,"insert into public.account(id,login_key,status,credential_version) values (?,?,'ACTIVE',1)",id(actor+"/account"),login(actor));
            sql(c,"insert into public.principal(id,kind,account_id,status) values (?,'HUMAN',?,'ACTIVE')",id(actor+"/actor"),id(actor+"/account"));
            sql(c,"insert into public.credential(account_id,kind,secret_hash,changed_at) values (?,'PASSWORD',?,current_timestamp)",id(actor+"/account"),"{bcrypt}"+PASSWORDS.encode(passwords.get(actor)));
            sql(c,"insert into public.tenant_member(tenant_id,id,account_id,status,auth_epoch,display_name) values (?,?,?,'ACTIVE',1,?)",tenant,id(actor+"/member"),id(actor+"/account"),"D1 "+actor);
            sql(c,"insert into public.member_org(tenant_id,member_id,org_id,kind,valid_from) values (?,?,?,'PRIMARY',timestamptz '2026-01-01 00:00:00+00')",tenant,id(actor+"/member"),id(t+"/org"+suffix));
            sql(c,"insert into public.\"role\"(tenant_id,id,code,name,active) values (?,?,?,?,true)",tenant,id(actor+"/role"),"D1-"+actor,"D1 "+actor);
            sql(c,"insert into public.scope_rule(tenant_id,id,kind,version_no,definition) values (?,?,'EXPLICIT',1,'{}'::jsonb)",tenant,id(actor+"/scope"));
            sql(c,"insert into public.scope_org(tenant_id,scope_rule_id,org_id) values (?,?,?)",tenant,id(actor+"/scope"),id(t+"/org"+suffix));
            for(String permission:permissions(actor)) {
                if(sql(c,"insert into public.role_permission(tenant_id,role_id,permission_id,scope_rule_id) select ?,?,id,? from public.permission where code=?",tenant,id(actor+"/role"),id(actor+"/scope"),permission)!=1)throw new IllegalStateException("Canonical permission missing");
            }
            sql(c,"insert into public.role_grant(tenant_id,id,role_id,subject_kind,member_id,source_type,source_id,granted_by_member_id,valid_from) values (?,?,?,'MEMBER',?,'D1_ACCEPTANCE',?,?,timestamptz '2026-01-01 00:00:00+00')",tenant,id(actor+"/grant"),id(actor+"/role"),id(actor+"/member"),id(actor+"/source"),id(actor+"/member"));
            if(!Set.of("reader","nonkeeper","admin").contains(actor))sql(c,"insert into public.tool_custodian(tenant_id,custody_scope_id,member_id,valid_from) values (?,?,?,timestamptz '2026-01-01 00:00:00+00')",tenant,id(t+"/scope"+suffix),id(actor+"/member"));
        }
    }
    static void verify(Connection c,String t,Map<String,String> passwords)throws SQLException {
        UUID tenant=id(t+"/tenant");int actorCount=actors(t).size();
        expect(c,"select count(*) from public.tenant where id=? and code=? and status='ACTIVE' and auth_epoch=1",1,tenant,code(t));
        expect(c,"select count(*) from public.tenant_application where tenant_id=?",2,tenant);
        expect(c,"select count(*) from public.tenant_application where tenant_id=? and enabled and version=1 and application_key in ('tooling','platform')",2,tenant);
        for(String table:List.of("org_unit","tool_custody_scope"))expect(c,"select count(*) from public."+table+" where tenant_id=?",2,tenant);
        for(String table:List.of("tenant_member","member_org","role","scope_rule","scope_org","role_grant"))expect(c,"select count(*) from public.\""+table+"\" where tenant_id=?",actorCount,tenant);
        for(String table:List.of("member_position","group_member"))expect(c,"select count(*) from public."+table+" where tenant_id=?",0,tenant);
        int keeperCount=(int)actors(t).stream().filter(a->!Set.of("reader","nonkeeper","admin").contains(a)).count();
        expect(c,"select count(*) from public.tool_custodian where tenant_id=?",keeperCount,tenant);
        expect(c,"select count(*) from public.role_permission where tenant_id=?",actors(t).stream().mapToInt(a->permissions(a).size()).sum(),tenant);
        for(String suffix:List.of("A","B")) {
            expect(c,"select count(*) from public.org_unit where tenant_id=? and id=? and code=? and active",1,tenant,id(t+"/org"+suffix),"D1-ORG-"+suffix);
            expect(c,"select count(*) from public.tool_custody_scope where tenant_id=? and id=? and organization_id=? and code=? and active",1,tenant,id(t+"/scope"+suffix),id(t+"/org"+suffix),"D1-SCOPE-"+suffix);
        }
        for(String actor:actors(t)) {
            String suffix=actor.equals("outside")?"B":"A";
            try(var q=c.prepareStatement("select a.login_key,a.status,p.id,p.status,m.id,m.status,cr.secret_hash from public.account a join public.principal p on p.account_id=a.id and p.kind='HUMAN' join public.tenant_member m on m.account_id=a.id and m.tenant_id=? join public.credential cr on cr.account_id=a.id and cr.kind='PASSWORD' where a.id=?")) {
                q.setObject(1,id(t+"/tenant"));q.setObject(2,id(actor+"/account"));try(var r=q.executeQuery()) {
                    if(!r.next()||!r.getString(1).equals(login(actor))||!r.getString(2).equals("ACTIVE")||!r.getObject(3).equals(id(actor+"/actor"))||!r.getString(4).equals("ACTIVE")||!r.getObject(5).equals(id(actor+"/member"))||!r.getString(6).equals("ACTIVE")||!r.getString(7).startsWith("{bcrypt}")||!PASSWORDS.matches(passwords.get(actor),r.getString(7).substring(8)))throw new IllegalStateException("Seed credential/identity drift; no reset permitted");
                }
            }
            Set<String> codes=new HashSet<>();
            try(var q=c.prepareStatement("select p.code from public.role_permission rp join public.permission p on p.id=rp.permission_id where rp.tenant_id=? and rp.role_id=?")) {q.setObject(1,id(t+"/tenant"));q.setObject(2,id(actor+"/role"));try(var r=q.executeQuery()){while(r.next())codes.add(r.getString(1));}}
            if(!codes.equals(new HashSet<>(permissions(actor))))throw new IllegalStateException("Seed permission drift; no regrant permitted");
            expect(c,"select count(*) from public.account where id=? and credential_version=1",1,id(actor+"/account"));
            expect(c,"select count(*) from public.tenant_member where tenant_id=? and id=? and account_id=? and auth_epoch=1",1,tenant,id(actor+"/member"),id(actor+"/account"));
            expect(c,"select count(*) from public.member_org where tenant_id=? and member_id=? and org_id=? and kind='PRIMARY' and valid_from=timestamptz '2026-01-01 00:00:00+00' and valid_to is null",1,tenant,id(actor+"/member"),id(t+"/org"+suffix));
            expect(c,"select count(*) from public.\"role\" where tenant_id=? and id=? and code=? and active",1,tenant,id(actor+"/role"),"D1-"+actor);
            expect(c,"select count(*) from public.scope_rule where tenant_id=? and id=? and kind='EXPLICIT' and version_no=1 and definition='{}'::jsonb",1,tenant,id(actor+"/scope"));
            expect(c,"select count(*) from public.scope_org where tenant_id=? and scope_rule_id=? and org_id=?",1,tenant,id(actor+"/scope"),id(t+"/org"+suffix));
            expect(c,"select count(*) from public.role_permission where tenant_id=? and role_id=? and scope_rule_id=?",permissions(actor).size(),tenant,id(actor+"/role"),id(actor+"/scope"));
            expect(c,"select count(*) from public.role_grant where tenant_id=? and id=? and role_id=? and subject_kind='MEMBER' and member_id=? and source_type='D1_ACCEPTANCE' and source_id=? and granted_by_member_id=? and valid_from=timestamptz '2026-01-01 00:00:00+00' and valid_to is null",1,tenant,id(actor+"/grant"),id(actor+"/role"),id(actor+"/member"),id(actor+"/source"),id(actor+"/member"));
            expect(c,"select count(*) from public.tool_custodian where tenant_id=? and custody_scope_id=? and member_id=? and valid_from=timestamptz '2026-01-01 00:00:00+00' and valid_to is null",Set.of("reader","nonkeeper","admin").contains(actor)?0:1,tenant,id(t+"/scope"+suffix),id(actor+"/member"));
        }
    }
    static void expect(Connection c,String query,int expected,Object...args)throws SQLException {
        try(var q=c.prepareStatement(query)){for(int i=0;i<args.length;i++)q.setObject(i+1,args[i]);try(var r=q.executeQuery()){r.next();if(r.getInt(1)!=expected)throw new IllegalStateException("Seed authorization graph drift; no implicit repair permitted");}}
    }
    static List<String> actors(String t) {return t.equals("foreign")?List.of("foreignkeeper"):ACTORS.stream().filter(a->!a.equals("foreignkeeper")).toList();}
    static List<String> permissions(String actor) {return switch(actor){case "reader"->List.of("tool.read");case "createonly"->List.of("tool.create");case "updateonly"->List.of("tool.update");case "admin"->List.of("platform.permission.read","platform.permission.manage");default->List.of("tool.read","tool.create","tool.update");};}
    static String login(String actor){return (actor.equals("foreignkeeper")?"d1foreign":"d1seed")+"-"+actor;}
    static String code(String t){return t.equals("main")?"d1seed":"d1foreign";}
    static void tenant(Connection c,String t)throws SQLException{sql(c,"select set_config('app.tenant_id',?,true)",id(t+"/tenant").toString());}
    static UUID id(String key){return UUID.nameUUIDFromBytes(("msf/d1/"+database+"/"+key).getBytes(StandardCharsets.UTF_8));}
    static String required(String key){String v=System.getenv(key);if(v==null||v.isBlank())throw new IllegalArgumentException("Missing "+key);return v;}
    static int sql(Connection c,String query,Object...args)throws SQLException{try(var q=c.prepareStatement(query)){for(int i=0;i<args.length;i++)q.setObject(i+1,args[i]);return q.execute()?-1:q.getUpdateCount();}}
}

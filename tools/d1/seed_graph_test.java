import java.sql.*;
import java.util.*;

/** Real PostgreSQL seed drift checks; all owned fixture rows are rolled back, including successful setup. */
class seed_graph_test {
    public static void main(String[] args)throws Exception {
        String db=seed.required("D1_DATABASE"),url=seed.required("D1_JDBC_URL");
        if(!db.equals("msf_d0_d1_core_test") || !seed.required("D1_SEED_ACK").equals("isolated-d1-seed-only:"+db)
                || !url.matches("jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]{1,5}/"+db))throw new IllegalArgumentException("Owned D1 core target only");
        seed.database=db;var passwords=new LinkedHashMap<String,String>();for(String actor:seed.ACTORS)passwords.put(actor,UUID.randomUUID().toString());
        int checks=0;
        try(var c=DriverManager.getConnection(url,seed.required("D1_OPERATOR_USERNAME"),seed.required("D1_OPERATOR_PASSWORD"))) {
            c.setAutoCommit(false);c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try {
                seed.sql(c,"select pg_advisory_xact_lock(1297303111,1)");
                for(String tenant:List.of("main","foreign")) {
                    seed.tenant(c,tenant);
                    try(var q=c.prepareStatement("select count(*) from public.tenant where id=? or code=?")){q.setObject(1,seed.id(tenant+"/tenant"));q.setString(2,seed.code(tenant));try(var r=q.executeQuery()){r.next();if(r.getInt(1)!=0)throw new IllegalStateException("Seed drift harness requires unseeded owned core target");}}
                    seed.seedTenant(c,tenant,passwords);seed.verify(c,tenant,passwords);checks++;
                }
                seed.tenant(c,"main");
                checks+=reject(c,passwords,"update public.tool_custody_scope set active=false where tenant_id=? and id=?","update public.tool_custody_scope set active=true where tenant_id=? and id=?",seed.id("main/tenant"),seed.id("main/scopeA"));
                checks+=reject(c,passwords,"update public.tool_custodian set valid_to=current_timestamp where tenant_id=? and member_id=?","update public.tool_custodian set valid_to=null where tenant_id=? and member_id=?",seed.id("main/tenant"),seed.id("keeper/member"));
                UUID extra=UUID.randomUUID();
                seed.sql(c,"insert into public.role_grant(tenant_id,id,role_id,subject_kind,member_id,source_type,source_id,granted_by_member_id,valid_from) values (?,?,?,'MEMBER',?,'D1_DRIFT_TEST',?,?,current_timestamp)",seed.id("main/tenant"),extra,seed.id("keeper/role"),seed.id("keeper/member"),UUID.randomUUID(),seed.id("keeper/member"));
                expectDrift(c,passwords);seed.sql(c,"delete from public.role_grant where tenant_id=? and id=?",seed.id("main/tenant"),extra);seed.verify(c,"main",passwords);checks++;
                checks+=reject(c,passwords,"update public.tenant_application set enabled=false where tenant_id=? and application_key=?","update public.tenant_application set enabled=true where tenant_id=? and application_key=?",seed.id("main/tenant"),"tooling");
                checks+=reject(c,passwords,"update public.\"role\" set active=false where tenant_id=? and id=?","update public.\"role\" set active=true where tenant_id=? and id=?",seed.id("main/tenant"),seed.id("keeper/role"));
                checks+=reject(c,passwords,"update public.tenant_member set auth_epoch=2 where tenant_id=? and id=?","update public.tenant_member set auth_epoch=1 where tenant_id=? and id=?",seed.id("main/tenant"),seed.id("keeper/member"));
            } finally {c.rollback();}
            for(String tenant:List.of("main","foreign")){
                seed.tenant(c,tenant);seed.expect(c,"select count(*) from public.tenant where id=?",0,seed.id(tenant+"/tenant"));c.rollback();
            }
        }
        System.out.println("D1_SEED_GRAPH_POSTGRES_OK checks="+checks+" all_fixture_rows_rolled_back=true");
    }
    static int reject(Connection c,Map<String,String> passwords,String mutation,String restore,Object...args)throws SQLException {
        seed.sql(c,mutation,args);expectDrift(c,passwords);seed.sql(c,restore,args);seed.verify(c,"main",passwords);return 1;
    }
    static void expectDrift(Connection c,Map<String,String> passwords)throws SQLException {
        try {seed.verify(c,"main",passwords);}catch(IllegalStateException expected){return;}
        throw new AssertionError("Authorization graph drift was silently accepted");
    }
}

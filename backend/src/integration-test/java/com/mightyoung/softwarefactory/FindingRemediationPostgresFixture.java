package com.mightyoung.softwarefactory;

import com.mightyoung.softwarefactory.authorization.PostgresAuthorizationWriteGuard;
import com.mightyoung.softwarefactory.files.*;
import com.zaxxer.hikari.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Function;

/** Retained fixture evidence, never an upload/scanning API. No trigger bypass or business-history cleanup. */
final class FindingRemediationPostgresFixture implements AutoCloseable {
    record Member(UUID accountId,UUID actorId,UUID memberId,String loginKey,String password) {
        @Override public String toString(){return "RemediationFixtureMember[REDACTED]";}
    }
    record Evidence(UUID setId,UUID itemId,UUID fileId,UUID linkId,UUID receiptId,int roundNo,UUID ownerMemberId) { }
    static final class Storage {
        final Path root;final byte[] key;
        private Storage(Path root,byte[] key){this.root=root;this.key=key;}
        static Storage prepare() throws Exception {
            Path requested=Path.of(required("MSF_REMEDIATION_STORAGE_ROOT"));
            if(!requested.isAbsolute())throw new IllegalStateException("MSF_REMEDIATION_STORAGE_ROOT must be absolute");
            Path root=requested.normalize();
            if(!root.getFileName().toString().startsWith(".env.remediation-storage-"))throw new IllegalStateException("Explicit ignored remediation storage root required");
            Path repo=root.getParent();
            if(!Files.exists(repo.resolve(".git"))||!Files.isRegularFile(repo.resolve(".gitignore")))throw new IllegalStateException("Storage must be directly under the checkout");
            Process ignored=new ProcessBuilder("git","-C",repo.toString(),"check-ignore","-q","--",root.toString()).start();
            if(!ignored.waitFor(5,java.util.concurrent.TimeUnit.SECONDS)){ignored.destroyForcibly();throw new IllegalStateException("Ignored storage check timed out");}
            if(ignored.exitValue()!=0)throw new IllegalStateException("Storage root must be explicitly git ignored");
            if(!root.getFileSystem().supportedFileAttributeViews().contains("posix"))throw new IllegalStateException("B4 requires POSIX and SecureDirectoryStream; use a supported Linux/JDK runner");
            try(var directory=Files.newDirectoryStream(root.getRoot())) {
                if(!(directory instanceof SecureDirectoryStream<?>))throw new IllegalStateException("B4 requires SecureDirectoryStream; no skipped storage acceptance");
            }
            privateDirectory(root);privateDirectory(root.resolve(".receipt-registry"));privateDirectory(root.resolve(".acceptance-manifests"));
            Path keyFile=root.resolve("receipt-hmac.key");
            if(!Files.exists(keyFile,LinkOption.NOFOLLOW_LINKS)) {
                byte[] generated=new byte[32];new SecureRandom().nextBytes(generated);
                Files.write(keyFile,generated,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
                Files.setPosixFilePermissions(keyFile,PosixFilePermissions.fromString("rw-------"));Arrays.fill(generated,(byte)0);
            }
            if(Files.isSymbolicLink(keyFile)||!Files.isRegularFile(keyFile)||!Files.getPosixFilePermissions(keyFile).equals(PosixFilePermissions.fromString("rw-------")))throw new IllegalStateException("Private retained receipt key required");
            byte[] key=Files.readAllBytes(keyFile);if(key.length!=32)throw new IllegalStateException("Expected retained 256-bit receipt key");
            Storage storage=new Storage(root.toRealPath(),key);
            try(var adapter=new LocalPrivateFileStorageAdapter(storage.root,key,"b4-local-verifier","b4-local-key-1","b4-fixture-v1",1048576)) {
                storage.verifyRetainedProbe(adapter);
            }
            return storage;
        }
        private void verifyRetainedProbe(LocalPrivateFileStorageAdapter adapter) throws Exception {
            UUID tenant=UUID.randomUUID(),file=UUID.randomUUID(),member=UUID.randomUUID(),actor=UUID.randomUUID(),item=UUID.randomUUID(),link=UUID.randomUUID();
            String version="storage-preflight-v1";
            Path tenantDirectory=root.resolve(tenant.toString());privateDirectory(tenantDirectory);
            Path folder=tenantDirectory.resolve(file.toString());privateDirectory(folder);
            Path blob=folder.resolve(version+".blob");
            byte[] expected=("Retained B4 storage capability probe "+UUID.randomUUID()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Files.write(blob,expected,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
            Files.setPosixFilePermissions(blob,PosixFilePermissions.fromString("r--------"));
            String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(expected));
            Path probeManifest=root.resolve(".acceptance-manifests").resolve("storage-preflight-"+file+".json");
            Map<String,Object> data=new LinkedHashMap<>();
            data.put("purpose","storage-capability-probe-not-database-seed");data.put("verified",false);
            data.put("tenantId",tenant.toString());data.put("fileId",file.toString());data.put("ownerMemberId",member.toString());data.put("ownerActorId",actor.toString());data.put("evidenceItemId",item.toString());data.put("fileLinkId",link.toString());
            data.put("blob",root.relativize(blob).toString());data.put("storageVersion",version);data.put("sha256",digest);
            Files.createFile(probeManifest,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.writeString(probeManifest,new ObjectMapper().writeValueAsString(data));
            var metadata=new FileObjectMetadata(tenant,file,member,actor,FileObjectState.READY,expected.length,"text/plain",digest,version,OffsetDateTime.now());
            var receipt=adapter.verifyAndRegister(metadata);
            data.put("receiptId",receipt.receiptId().toString());data.put("verifiedAt",receipt.verifiedAt().toString());
            data.put("verifierId",receipt.verifierId());data.put("verifierKeyId",receipt.verifierKeyId());data.put("verificationPolicyVersion",receipt.verificationPolicyVersion());
            Files.writeString(probeManifest,new ObjectMapper().writeValueAsString(data));
            var snapshot=new VerifiedFileEvidenceSnapshot(tenant,item,file,link,member,actor,digest,version,
                    receipt.receiptId(),receipt.verifierId(),receipt.verifierKeyId(),receipt.verificationPolicyVersion(),receipt.verificationLevel(),receipt.verifiedAt());
            try(var content=adapter.openVerifiedVersion(snapshot);var actual=new java.io.ByteArrayOutputStream()) {
                content.transferTo(actual);
                if(content.sizeBytes()!=expected.length||!Arrays.equals(expected,actual.toByteArray()))throw new IllegalStateException("Retained immutable probe content did not round-trip");
            }
            data.put("verified",true);Files.writeString(probeManifest,new ObjectMapper().writeValueAsString(data));
            // Preserve blob, signed receipt and private manifest for replay; never delete this preflight evidence.
        }
        @Override public String toString(){return "RetainedEvidenceStorage[REDACTED]";}
    }
    final BusinessWorkflowPostgresFixture business;
    final Member replacement;
    private final HikariDataSource pool;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PostgresAuthorizationWriteGuard guard;
    private final Storage storage;
    private final LocalPrivateFileStorageAdapter verifier;
    private final List<Map<String,Object>> evidenceManifest=new ArrayList<>();
    private final List<String> commands=new ArrayList<>();
    private final Path manifest;
    static LanPostgresCompatibilityIT.DatabaseTarget validateTarget() {
        var target=LanPostgresCompatibilityIT.DatabaseTarget.forAppRole(System.getenv());
        if(!("I_CONFIRM_REMEDIATION_EVIDENCE_FIXTURE_WRITES:"+target.databaseName()).equals(System.getenv("LAN_PG_REMEDIATION_ACCEPTANCE_ACK")))throw new IllegalStateException("B4 requires database-specific evidence fixture acknowledgement");
        return BusinessWorkflowPostgresFixture.validateTarget();
    }
    FindingRemediationPostgresFixture(PasswordEncoder passwords,Storage storage,LocalPrivateFileStorageAdapter verifier) {
        var target=validateTarget();this.storage=storage;this.verifier=verifier;
        // Validate the actual storage instance/root before creating any synthetic database rows.
        if(!Files.isDirectory(storage.root.resolve(".receipt-registry")))throw new IllegalStateException("Retained storage was not prepared");
        HikariConfig c=new HikariConfig();c.setJdbcUrl(target.jdbcUrl());c.setUsername(required("LAN_PG_USERNAME"));c.setPassword(required("LAN_PG_PASSWORD"));c.setMaximumPoolSize(2);c.setMinimumIdle(0);c.setPoolName("b4-fixture-"+UUID.randomUUID());
        pool=new HikariDataSource(c);jdbc=new JdbcTemplate(pool);tx=new TransactionTemplate(new DataSourceTransactionManager(pool));tx.setReadOnly(false);tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);guard=new PostgresAuthorizationWriteGuard(pool);
        try { runtimeGate(target.jdbcUrl()); }
        catch(RuntimeException|Error failure){pool.close();throw failure;}
        BusinessWorkflowPostgresFixture created=null;
        try {
            created=new BusinessWorkflowPostgresFixture(passwords);business=created;
            replacement=new Member(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"b4-remediator-"+UUID.randomUUID(),UUID.randomUUID().toString());
            manifest=storage.root.resolve(".acceptance-manifests").resolve(business.run+".json");
            securityWrite(()->{
                jdbc.update("insert into public.account(id,login_key,status,credential_version) values (?,?,'ACTIVE',1)",replacement.accountId(),replacement.loginKey());
                jdbc.update("insert into public.principal(id,kind,account_id,status) values (?,'HUMAN',?,'ACTIVE')",replacement.actorId(),replacement.accountId());
                jdbc.update("insert into public.credential(account_id,kind,secret_hash,changed_at) values (?,'PASSWORD',?,current_timestamp)",replacement.accountId(),passwords.encode(replacement.password()));
                jdbc.update("insert into public.tenant_member(tenant_id,id,account_id,status,auth_epoch,display_name) values (?,?,?,'ACTIVE',1,'B4 replacement remediation member')",business.tenantId,replacement.memberId(),replacement.accountId());
                jdbc.update("insert into public.member_org(tenant_id,member_id,org_id,kind,valid_from) values (?,?,?,'PRIMARY',current_timestamp - interval '1 day')",business.tenantId,replacement.memberId(),business.organizationId);
                UUID role=UUID.randomUUID(),scope=UUID.randomUUID();
                jdbc.update("insert into public.\"role\"(tenant_id,id,code,name,active) values (?,?,?,'B4 replacement remediation',true)",business.tenantId,role,"b4-"+UUID.randomUUID());
                jdbc.update("insert into public.scope_rule(tenant_id,id,kind,version_no,definition) values (?,?,'EXPLICIT',1,'{}'::jsonb)",business.tenantId,scope);
                jdbc.update("insert into public.scope_org(tenant_id,scope_rule_id,org_id) values (?,?,?)",business.tenantId,scope,business.organizationId);
                requireOne(jdbc.update("insert into public.role_permission(tenant_id,role_id,permission_id,scope_rule_id) select ?,?,id,? from public.permission where code='finding.remediate'",business.tenantId,role,scope));
                jdbc.update("insert into public.role_grant(tenant_id,id,role_id,subject_kind,member_id,source_type,source_id,granted_by_member_id,valid_from) values (?,?,?,'MEMBER',?,'B4_EVIDENCE_ACCEPTANCE',?,?,current_timestamp - interval '1 day')",business.tenantId,UUID.randomUUID(),role,replacement.memberId(),UUID.randomUUID(),business.reporter.memberId());
                // Give the real submitter legitimate verify scope: self-verification must fail on separation, not missing permissions.
                requireOne(jdbc.update("""
                    insert into public.role_permission(tenant_id,role_id,permission_id,scope_rule_id)
                    select rp.tenant_id,rp.role_id,verify.id,rp.scope_rule_id from public.role_permission rp
                    join public.permission remediate on remediate.id=rp.permission_id and remediate.code='finding.remediate'
                    join public.role_grant g on g.tenant_id=rp.tenant_id and g.role_id=rp.role_id and g.member_id=?
                    join public.permission verify on verify.code='finding.verify'
                    where rp.tenant_id=?
                    """,business.remediator.memberId(),business.tenantId));
            });
            writeManifest();
        }catch(RuntimeException|Error failure){if(created!=null)created.close();pool.close();throw failure;}
    }
    private static void runtimeGate(String url) {
        HikariConfig c=new HikariConfig();c.setJdbcUrl(url);c.setUsername(required("LAN_PG_APP_USERNAME"));c.setPassword(required("LAN_PG_APP_PASSWORD"));c.setMaximumPoolSize(1);c.setMinimumIdle(0);
        try(var runtime=new HikariDataSource(c)) {
            var db=new JdbcTemplate(runtime);
            if(!Boolean.FALSE.equals(db.queryForObject("select rolsuper or rolbypassrls or rolinherit from pg_roles where rolname=current_user",Boolean.class)))throw new IllegalStateException("B4 requires nonprivileged runtime");
            if(!Boolean.FALSE.equals(db.queryForObject("select has_schema_privilege(current_user,'public','CREATE')",Boolean.class)))throw new IllegalStateException("Runtime DDL is forbidden");
            for(String table:List.of("evidence_set","evidence_item","file_object","file_link","remediation_submission","remediation_submission_evidence","verification")) {
                if(!Boolean.TRUE.equals(db.queryForObject("select has_table_privilege(current_user,cast(? as regclass),'SELECT')",Boolean.class,"public."+table)))throw new IllegalStateException("Missing approved evidence read privilege");
                if(!Boolean.TRUE.equals(db.queryForObject("select relrowsecurity and relforcerowsecurity and pg_get_userbyid(relowner)<>current_user from pg_class where oid=cast(? as regclass)",Boolean.class,"public."+table)))throw new IllegalStateException("B4 requires forced tenant RLS and nonowner runtime");
            }
            for(String column:List.of("evidence_revision","submission_sealed_at"))if(!Boolean.TRUE.equals(db.queryForObject("select has_column_privilege(current_user,'public.evidence_set',?,'UPDATE')",Boolean.class,column)))throw new IllegalStateException("Missing approved evidence seal privilege");
            if(!Boolean.TRUE.equals(db.queryForObject("select has_column_privilege(current_user,'public.remediation_submission','evidence_snapshots_sealed_at','UPDATE')",Boolean.class)))throw new IllegalStateException("Missing approved submission seal privilege");
            for(String table:List.of("file_object","file_link"))if(!Boolean.TRUE.equals(db.queryForObject("select has_column_privilege(current_user,cast(? as regclass),'tenant_id','UPDATE')",Boolean.class,"public."+table)))throw new IllegalStateException("Missing approved metadata locking privilege");
            for(String table:List.of("remediation_submission","remediation_submission_evidence","verification"))if(!Boolean.TRUE.equals(db.queryForObject("select has_table_privilege(current_user,cast(? as regclass),'SELECT') and has_table_privilege(current_user,cast(? as regclass),'INSERT')",Boolean.class,"public."+table,"public."+table)))throw new IllegalStateException("Missing approved remediation persistence privilege");
        }
    }
    Member reporter(){return member(business.reporter);}Member person(){return member(business.person);}Member safety(){return member(business.safety);}Member remediator(){return member(business.remediator);}
    private static Member member(BusinessWorkflowPostgresFixture.Actor actor){return new Member(actor.accountId(),actor.principalId(),actor.memberId(),actor.loginKey(),actor.password());}
    Evidence evidence(UUID finding,int round,Member owner) throws Exception {
        UUID set=UUID.randomUUID(),item=UUID.randomUUID(),file=UUID.randomUUID(),link=UUID.randomUUID();String version="fixture-v1";
        Path folder=storage.root.resolve(business.tenantId.toString()).resolve(file.toString());privateDirectory(folder.getParent());privateDirectory(folder);
        byte[] bytes=("Explicit B4 fixture evidence "+UUID.randomUUID()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        Path blob=folder.resolve(version+".blob");Files.write(blob,bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);Files.setPosixFilePermissions(blob,PosixFilePermissions.fromString("r--------"));
        var metadata=new FileObjectMetadata(business.tenantId,file,owner.memberId(),owner.actorId(),FileObjectState.READY,bytes.length,"text/plain",digest,version,OffsetDateTime.now());
        var receipt=verifier.verifyAndRegister(metadata); // Actual bytes hashed and signed outside every DB transaction.
        if(verifier.findByReceiptId(business.tenantId,receipt.receiptId()).filter(receipt::equals).isEmpty())throw new IllegalStateException("Real receipt could not be reverified");
        securityWrite(()->{
            jdbc.update("""
                insert into public.file_object(tenant_id,id,owner_member_id,owner_actor_id,object_key,size_bytes,mime,checksum,state,storage_version,created_at,verified_at)
                values (?,?,?,?,?,?,?,?,'READY',?,current_timestamp,cast(? as timestamptz))
                """,business.tenantId,file,owner.memberId(),owner.actorId(),"fixture/"+file,bytes.length,"text/plain",digest,version,receipt.verifiedAt());
            jdbc.update("insert into public.evidence_set(tenant_id,id,finding_id,round_no,owner_member_id,state,created_at,verified_at) values (?,?,?,?,?,'READY',current_timestamp,cast(? as timestamptz))",business.tenantId,set,finding,round,owner.memberId(),receipt.verifiedAt());
            jdbc.update("insert into public.evidence_item(tenant_id,id,evidence_set_id,source_kind,file_id,sha256,state,captured_at,verification_level) values (?,?,?,'FILE',?,?,'READY',current_timestamp,'CONTENT')",business.tenantId,item,set,file,digest);
            jdbc.update("insert into public.file_link(tenant_id,id,file_id,resource_type,resource_id,purpose,linked_by_actor_id,linked_at) values (?,?,?,'FINDING_EVIDENCE_ITEM',?,'REMEDIATION_EVIDENCE',?,current_timestamp)",business.tenantId,link,file,item,owner.actorId());
        });
        evidenceManifest.add(Map.of("findingId",finding.toString(),"roundNo",round,"evidenceSetId",set.toString(),"evidenceItemId",item.toString(),"fileId",file.toString(),"receiptId",receipt.receiptId().toString(),"blob",storage.root.relativize(blob).toString()));writeManifest();
        return new Evidence(set,item,file,link,receipt.receiptId(),round,owner.memberId());
    }
    <T>T read(Function<JdbcTemplate,T> work){var read=new TransactionTemplate(tx.getTransactionManager());read.setReadOnly(true);return read.execute(status->{tenant();return work.apply(jdbc);});}
    Map<String,Long> counts(){return read(db->{Map<String,Long> result=new TreeMap<>();for(String table:List.of("finding","finding_command_receipt","finding_transition","finding_confirmation","workflow_binding","audit_event","remediation_round","remediation_notice","remediation_submission","remediation_submission_evidence","verification","evidence_set","evidence_item","file_object","file_link"))result.put(table,db.queryForObject("select count(*) from public."+table+" where tenant_id=?",Long.class,business.tenantId));
        result.put("evidenceRevision",db.queryForObject("select coalesce(sum(evidence_revision),0) from public.evidence_set where tenant_id=?",Long.class,business.tenantId));
        result.put("sealedSets",db.queryForObject("select count(*) from public.evidence_set where tenant_id=? and submission_sealed_at is not null",Long.class,business.tenantId));
        result.put("sealedSubmissions",db.queryForObject("select count(*) from public.remediation_submission where tenant_id=? and evidence_snapshots_sealed_at is not null",Long.class,business.tenantId));
        result.put("manifestItems",db.queryForObject("select coalesce(sum(evidence_snapshot_count),0) from public.remediation_submission where tenant_id=?",Long.class,business.tenantId));
        result.put("publications",db.queryForObject("select count(*) from public.event_publication where serialized_event like ?",Long.class,"%"+business.tenantId+"%"));return Map.copyOf(result);});}
    void recordCommand(UUID command){commands.add(command.toString());writeManifest();}
    private void securityWrite(Runnable work){tx.executeWithoutResult(status->{guard.forGlobalSecurityChange();tenant();work.run();});}
    private void tenant(){jdbc.queryForObject("select set_config('app.tenant_id',?,true)",String.class,business.tenantId.toString());}
    private void writeManifest(){try{Map<String,Object> data=Map.of("run",business.run,"tenantId",business.tenantId.toString(),"fixtureProvisionedEvidence",true,"uploadAcceptance",false,"evidence",evidenceManifest,"commands",commands);byte[] json=new ObjectMapper().writeValueAsBytes(data);
        Path next=manifest.resolveSibling(manifest.getFileName()+".new");Files.write(next,json,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);Files.setPosixFilePermissions(next,PosixFilePermissions.fromString("rw-------"));Files.move(next,manifest,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }catch(Exception failure){throw new IllegalStateException("Unable to retain private remediation manifest");}}
    private static void privateDirectory(Path path)throws Exception{if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))Files.createDirectory(path,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));if(Files.isSymbolicLink(path)||!Files.isDirectory(path)||!Files.getPosixFilePermissions(path).equals(PosixFilePermissions.fromString("rwx------")))throw new IllegalStateException("Private owned directory required");}
    private static String required(String key){String value=System.getenv(key);if(value==null||value.isBlank())throw new IllegalStateException("Missing explicit B4 input");return value;}
    private static void requireOne(int count){if(count!=1)throw new IllegalStateException("Expected exactly one owned fixture row");}
    @Override public void close(){try{jdbc.update("delete from public.spring_session where principal_name=?",replacement.accountId().toString());}finally{try{business.close();}finally{pool.close();}}}
    @Override public String toString(){return "FindingRemediationPostgresFixture[REDACTED]";}
}

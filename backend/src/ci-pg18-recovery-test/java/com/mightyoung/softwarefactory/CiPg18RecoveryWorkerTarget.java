package com.mightyoung.softwarefactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;

/** Fixed trusted-worker view. It cannot migrate, choose a target or load a consumer HUMAN context. */
final class CiPg18RecoveryWorkerTarget {
    enum Mode { PRODUCER, CONSUMER }
    enum Fault { NONE, ACK_UPDATE_ROLLBACK, ACK_COMMIT_UNKNOWN }
    private static final Set<String> COMMON = Set.of("schemaVersion","caseId","mode","fault","jdbcUrl",
            "containerId","instanceId","imageDigest","repository","runId","runAttempt","ownershipFile",
            "runtimePasswordFile","capturePasswordFile","tenantId","serviceActorId");
    private static final Set<String> PRODUCER = Set.of("adminPasswordFile","accountId","actorId","memberId","organizationId","hazardId");
    private static final Set<String> CONSUMER = Set.of("sourceCommandId","publicationId");
    private static final Set<String> WORKER_ENV = Set.of("MSF_RECOVERY_CONFIG_FILE","MSF_RECOVERY_CONFIG_SHA256",
            "MSF_RECOVERY_WORKER_MODE","MSF_RECOVERY_CASE_ID","MSF_RECOVERY_WORKER_MARKER");
    private final Map<String,Object> values;
    private final Mode mode;
    private final Fault fault;
    private final String runtimePassword,capturePassword,adminPassword;
    private final Path config;
    private final String marker;

    private CiPg18RecoveryWorkerTarget(Map<String,Object> values,Mode mode,Fault fault,String runtime,
            String capture,String admin,Path config,String marker) {
        this.values=Map.copyOf(values);this.mode=mode;this.fault=fault;
        runtimePassword=runtime;capturePassword=capture;adminPassword=admin;this.config=config;this.marker=marker;
    }

    static CiPg18RecoveryWorkerTarget from(Map<String,String> environment) {
        try {
            if(!"true".equals(environment.get("GITHUB_ACTIONS"))||!"true".equals(environment.get("CI"))
                    ||!"mightyoung/might-software-factory".equals(environment.get("GITHUB_REPOSITORY")))throw rejected();
            for(var entry:environment.entrySet()) {
                String key=entry.getKey();
                if(key.startsWith("MSF_CI_PG18_")||key.startsWith("LAN_PG_")||key.startsWith("PG18_TUNNEL_")
                        ||key.startsWith("MSF_RECOVERY_")&&!WORKER_ENV.contains(key))throw rejected();
            }
            String process=required(environment,"MSF_PG18_PROCESS_MARKER");
            if(!process.matches("[0-9a-f]{32}"))throw rejected();
            String marker=required(environment,"MSF_RECOVERY_WORKER_MARKER");uuid(marker);
            Mode mode=Mode.valueOf(required(environment,"MSF_RECOVERY_WORKER_MODE"));
            String caseId=required(environment,"MSF_RECOVERY_CASE_ID");uuid(caseId);
            Path root=Path.of(required(environment,"RUNNER_TEMP")).toRealPath();
            Path config=privatePath(required(environment,"MSF_RECOVERY_CONFIG_FILE"),root);
            if(!Files.getPosixFilePermissions(config.getParent()).equals(PosixFilePermissions.fromString("rwx------")))throw rejected();
            byte[] bytes=read(config,16384);
            String digest=required(environment,"MSF_RECOVERY_CONFIG_SHA256");
            if(!digest.matches("[0-9a-f]{64}")||!digest.equals(sha256(bytes)))throw rejected();
            @SuppressWarnings("unchecked") Map<String,Object> value=JsonMapper.builder().build().readValue(bytes,Map.class);
            Set<String> keys=new HashSet<>(COMMON);keys.addAll(mode==Mode.PRODUCER?PRODUCER:CONSUMER);
            if(!value.keySet().equals(keys)||!(value.get("schemaVersion") instanceof Integer version)||version!=1
                    ||!mode.name().equals(string(value,"mode"))||!caseId.equals(string(value,"caseId")))throw rejected();
            Fault fault=Fault.valueOf(string(value,"fault"));
            if(mode==Mode.PRODUCER&&fault!=Fault.NONE)throw rejected();
            String run=required(environment,"GITHUB_RUN_ID"),attempt=required(environment,"GITHUB_RUN_ATTEMPT");
            if(!run.matches("[1-9][0-9]{0,19}")||!attempt.matches("[1-9][0-9]{0,9}")
                    ||!run.equals(string(value,"runId"))||!attempt.equals(string(value,"runAttempt"))
                    ||!"mightyoung/might-software-factory".equals(string(value,"repository")))throw rejected();
            var url=Pattern.compile("jdbc:postgresql://127\\.0\\.0\\.1:([1-9][0-9]{0,4})/msf_ci_risk_recovery").matcher(string(value,"jdbcUrl"));
            if(!url.matches())throw rejected();
            int port=Integer.parseInt(url.group(1));if(port<1024||port>65535)throw rejected();
            if(!string(value,"containerId").matches("[0-9a-f]{64}")||!string(value,"imageDigest").matches("sha256:[0-9a-f]{64}"))throw rejected();
            uuid(string(value,"instanceId"));uuid(string(value,"tenantId"));uuid(string(value,"serviceActorId"));
            if(mode==Mode.PRODUCER)for(String id:Set.of("accountId","actorId","memberId","organizationId","hazardId"))uuid(string(value,id));
            else {uuid(string(value,"sourceCommandId"));uuid(string(value,"publicationId"));}
            Path ownership=privatePath(string(value,"ownershipFile"),root);
            Map<String,String> receipt=new HashMap<>();
            for(String line:new String(read(ownership,4096),StandardCharsets.US_ASCII).lines().toList()) {
                int split=line.indexOf('=');
                if(split<1||split!=line.lastIndexOf('=')||receipt.put(line.substring(0,split),line.substring(split+1))!=null)throw rejected();
            }
            String engine=receipt.remove("engine_major");
            if(engine==null||!engine.matches("[1-9][0-9]{0,2}")||Integer.parseInt(engine)<28
                    ||!receipt.equals(Map.of("container_id",string(value,"containerId"),"instance_id",string(value,"instanceId"),
                    "host","127.0.0.1","port",url.group(1),"database","msf_ci_risk_recovery","image_digest",string(value,"imageDigest"),
                    "repository",string(value,"repository"),"run_id",run,"run_attempt",attempt)))throw rejected();
            String runtime=password(privatePath(string(value,"runtimePasswordFile"),root));
            String capture=password(privatePath(string(value,"capturePasswordFile"),root));
            if(runtime.equals(capture))throw rejected();
            String admin=mode==Mode.PRODUCER?password(privatePath(string(value,"adminPasswordFile"),root)):null;
            if(admin!=null&&(admin.equals(runtime)||admin.equals(capture)))throw rejected();
            return new CiPg18RecoveryWorkerTarget(value,mode,fault,runtime,capture,admin,config,marker);
        }catch(Exception|LinkageError failure){throw rejected();}
    }

    static Path privatePath(String value,Path root)throws Exception {
        Path path=Path.of(value);
        if(!path.isAbsolute()||!path.normalize().equals(path)||!path.startsWith(root))throw rejected();
        Path cursor=path.getRoot();for(Path part:path){cursor=cursor.resolve(part);if(Files.isSymbolicLink(cursor))throw rejected();}
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||!Files.getOwner(path).equals(Files.getOwner(root))
                ||!Files.getPosixFilePermissions(path).equals(PosixFilePermissions.fromString("rw-------")))throw rejected();
        return path;
    }
    private static byte[] read(Path path,int maximum)throws Exception {
        try(var input=Files.newInputStream(path)){byte[] bytes=input.readNBytes(maximum+1);if(bytes.length==0||bytes.length>maximum)throw rejected();return bytes;}
    }
    private static String password(Path path)throws Exception {
        byte[] bytes=read(path,4096);String value=new String(bytes,StandardCharsets.US_ASCII);
        if(!value.matches("[0-9a-f]{64}")||!java.util.Arrays.equals(bytes,value.getBytes(StandardCharsets.US_ASCII)))throw rejected();return value;
    }
    static String sha256(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static String required(Map<String,String> values,String key){String value=values.get(key);if(value==null||value.isBlank())throw rejected();return value;}
    private static String string(Map<String,Object> values,String key){Object value=values.get(key);if(!(value instanceof String text)||text.isBlank())throw rejected();return text;}
    private static UUID uuid(String value){UUID id=UUID.fromString(value);if(!id.toString().equals(value))throw rejected();return id;}
    Mode mode(){return mode;}boolean producer(){return mode==Mode.PRODUCER;}Fault fault(){return fault;}
    UUID caseId(){return uuid(string(values,"caseId"));}UUID tenant(){return uuid(string(values,"tenantId"));}
    UUID serviceActor(){return uuid(string(values,"serviceActorId"));}UUID instanceId(){return uuid(string(values,"instanceId"));}
    String jdbcUrl(){return string(values,"jdbcUrl");}String runtimePassword(){return runtimePassword;}String capturePassword(){return capturePassword;}
    String adminPassword(){if(!producer())throw rejected();return adminPassword;}
    String imageDigest(){return string(values,"imageDigest");}String containerId(){return string(values,"containerId");}
    UUID sourceCommandId(){if(producer())throw rejected();return uuid(string(values,"sourceCommandId"));}
    UUID publicationId(){if(producer())throw rejected();return uuid(string(values,"publicationId"));}
    CiPg18SourceFixture.Identity identity(){if(!producer())throw rejected();return new CiPg18SourceFixture.Identity(tenant(),
            uuid(string(values,"accountId")),uuid(string(values,"actorId")),uuid(string(values,"memberId")),uuid(string(values,"organizationId")));}
    UUID hazard(){if(!producer())throw rejected();return uuid(string(values,"hazardId"));}
    Path config(){return config;}String workerMarker(){return marker;}
    Set<String> privateCanaries(){var values=new HashSet<String>();values.add(runtimePassword);values.add(capturePassword);if(adminPassword!=null)values.add(adminPassword);return Set.copyOf(values);}
    @Override public String toString(){return "CiPg18RecoveryWorkerTarget[private owned target]";}
    static IllegalStateException rejected(){return new IllegalStateException("CI recovery worker target rejected");}
}

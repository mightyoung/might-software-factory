package com.mightyoung.softwarefactory;

import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.json.JsonMapper;

/** Parent-owned process and private receipt. It never kills an inherited process group. */
final class CiPg18RecoveryProcess implements AutoCloseable {
    private static final Set<String> IDENTITY=Set.of("stage","pid","start","workerMarker","caseId");
    private static final Set<String> DELIVERY=Set.of("stage","pid","start","workerMarker","caseId","failures","completionRows",
            "ackCommits","ackRollbacks","unknown","faultReached","captureBorrows","captureCommits","captureRollbacks");
    private final Process process;
    private final Instant start;
    private final Path executable;
    private final List<String> arguments;
    private final UUID caseId,marker;
    private final ArrayBlockingQueue<Map<String,Object>> messages=new ArrayBlockingQueue<>(8);
    private final Thread reader;
    private volatile boolean invalid;
    private boolean ownershipVerified,cleanupVerified;
    private final long created=System.nanoTime();

    CiPg18RecoveryProcess(Path config,byte[] bytes,CiPg18RecoveryWorkerTarget.Mode mode,UUID caseId,Path directory)throws Exception {
        this.caseId=caseId;marker=UUID.randomUUID();
        executable=Path.of(System.getProperty("java.home"),"bin","java").toRealPath();
        if(Runtime.version().feature()!=21||!Files.isExecutable(executable))throw rejected();
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        if(classpath==null||classpath.isBlank()||classpath.length()>131072||classpath.contains("\n"))throw rejected();
        arguments=List.of("-cp",classpath,CiPg18RecoveryWorkerMain.class.getName());
        var builder=new ProcessBuilder(executable.toString(),arguments.get(0),arguments.get(1),arguments.get(2));
        builder.redirectErrorStream(true);var env=builder.environment();env.clear();
        for(String key:Set.of("GITHUB_ACTIONS","CI","GITHUB_REPOSITORY","GITHUB_RUN_ID","GITHUB_RUN_ATTEMPT","RUNNER_TEMP","MSF_PG18_PROCESS_MARKER")){
            String value=System.getenv(key);if(value==null||value.isBlank())throw rejected();env.put(key,value);
        }
        env.put("MSF_RECOVERY_CONFIG_FILE",config.toString());env.put("MSF_RECOVERY_CONFIG_SHA256",CiPg18RecoveryWorkerTarget.sha256(bytes));
        env.put("MSF_RECOVERY_WORKER_MODE",mode.name());env.put("MSF_RECOVERY_CASE_ID",caseId.toString());env.put("MSF_RECOVERY_WORKER_MARKER",marker.toString());
        process=builder.start();
        try{
            start=process.info().startInstant().orElseThrow(CiPg18RecoveryProcess::rejected);
            var receipt=Map.of("pid",process.pid(),"start",start.toString(),"workerMarker",marker.toString(),"caseId",caseId.toString(),"mode",mode.name());
            privateWrite(directory.resolve(marker+".process.json"),JsonMapper.builder().build().writeValueAsBytes(receipt));
            Path log=directory.resolve(marker+".protocol");
            privateWrite(log,new byte[0]);
            reader=Thread.ofPlatform().daemon().name("recovery-worker-protocol").start(()->read(log));
        }catch(Throwable failure){
            // ProcessBuilder's returned Process is the direct creation receipt even before READY.
            process.destroyForcibly();
            try{if(!process.waitFor(5,TimeUnit.SECONDS))throw rejected();}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            throw rejected();
        }
    }
    void awaitReady(){await("READY");verifyLive();ownershipVerified=true;}
    Map<String,Object> await(String expected){
        long deadline=System.nanoTime()+Duration.ofSeconds(120).toNanos();
        try{while(System.nanoTime()<deadline){
            if(invalid||System.nanoTime()-created>Duration.ofSeconds(180).toNanos())throw rejected();
            var message=messages.poll(100,TimeUnit.MILLISECONDS);
            if(message!=null){verify(message);if(!expected.equals(message.get("stage")))throw rejected();return message;}
            if(!process.isAlive())throw rejected();verifyLive();
        }}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw rejected();}
        throw rejected();
    }
    void send(String value){
        if(!Set.of("CREATE","DELIVER","RECOVER","EXIT").contains(value))throw rejected();verifyLive();
        try{process.getOutputStream().write((value+"\n").getBytes(StandardCharsets.US_ASCII));process.getOutputStream().flush();}
        catch(Exception failure){throw rejected();}
    }
    void exitNormally(){send("EXIT");waitForExit(true);}
    void finish(){waitForExit(true);}
    void terminate(){
        if(process.isAlive()){
            verifyLive();process.destroy();
            try{if(!process.waitFor(5,TimeUnit.SECONDS)){verifyLive();process.destroyForcibly();if(!process.waitFor(5,TimeUnit.SECONDS))throw rejected();}}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw rejected();}
        }
        completeCleanup();
    }
    private void waitForExit(boolean zero){
        try{if(!process.waitFor(30,TimeUnit.SECONDS)||zero&&process.exitValue()!=0)throw rejected();}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw rejected();}
        completeCleanup();
    }
    private void completeCleanup(){
        if(process.isAlive()||process.toHandle().descendants().findAny().isPresent())throw rejected();
        try{reader.join(1000);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw rejected();}
        if(reader.isAlive()||invalid||!messages.isEmpty())throw rejected();
        cleanupVerified=true;
    }
    private void verifyLive(){
        var info=process.info();
        if(!process.isAlive()||!start.equals(info.startInstant().orElse(null))
                ||!executable.toString().equals(info.command().orElse(null))
                ||!Arrays.equals(arguments.toArray(String[]::new),info.arguments().orElse(null))
                ||process.toHandle().descendants().findAny().isPresent())throw rejected();
    }
    private void verify(Map<String,Object> value){
        if(!Set.of("READY","SOURCE_COMMITTED","DELIVERY_FINISHED","UNKNOWN_ACK","FAILED").contains(value.get("stage"))
                ||!marker.toString().equals(value.get("workerMarker"))||!caseId.toString().equals(value.get("caseId"))
                ||!start.toString().equals(value.get("start"))||number(value.get("pid"))!=process.pid())throw rejected();
        if(!value.keySet().equals(IDENTITY)&&!value.keySet().equals(DELIVERY))throw rejected();
        if(value.keySet().equals(DELIVERY)){
            for(String key:Set.of("failures","completionRows","ackCommits","ackRollbacks","captureBorrows","captureCommits","captureRollbacks")){
                long count=number(value.get(key));if(count<0||count>16)throw rejected();
            }
            if(!(value.get("unknown") instanceof Boolean)||!(value.get("faultReached") instanceof Boolean))throw rejected();
        }
    }
    private void read(Path log){
        try(var input=process.getInputStream();OutputStream output=new BufferedOutputStream(Files.newOutputStream(log,StandardOpenOption.APPEND))){
            var line=new java.io.ByteArrayOutputStream();int total=0;
            for(int ch;(ch=input.read())>=0;){
                if(++total>1048576||line.size()>4096){invalid=true;return;}output.write(ch);
                if(ch=='\n'){
                    @SuppressWarnings("unchecked") Map<String,Object> value=JsonMapper.builder().build().readValue(line.toByteArray(),Map.class);
                    if(!messages.offer(value)){invalid=true;return;}line.reset();
                }else line.write(ch);
            }
            if(line.size()!=0)invalid=true;
        }catch(Throwable failure){invalid=true;}
    }
    long pid(){return process.pid();}Instant start(){return start;}
    boolean ownershipVerified(){return ownershipVerified;}boolean cleanupVerified(){return cleanupVerified;}
    boolean exited(){return !process.isAlive();}
    static long number(Object value){if(!(value instanceof Integer||value instanceof Long))throw rejected();return ((Number)value).longValue();}
    static void privateWrite(Path file,byte[] bytes)throws Exception {
        Files.createFile(file,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.write(file,bytes,StandardOpenOption.WRITE);
    }
    @Override public void close(){if(process.isAlive())terminate();else if(!cleanupVerified)completeCleanup();}
    private static IllegalStateException rejected(){return new IllegalStateException("Recovery worker process rejected");}
}

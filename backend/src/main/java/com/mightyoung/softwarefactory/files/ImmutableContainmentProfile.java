package com.mightyoung.softwarefactory.files;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static com.mightyoung.softwarefactory.files.FileIngestionContracts.*;

/** Root-owned fixed broker profile. Loading never establishes target containment acceptance. */
public final class ImmutableContainmentProfile {
    enum Probe { CLEAN_CONTROL,EICAR_CONTROL,APP_FILE_READ,PRIVATE_ROOT_READ,PARENT_PROC_READ,NETWORK_SOCKET,
        PRIVILEGE_GAIN,OUTSIDE_SCRATCH_WRITE,MEMORY_LIMIT,CPU_LIMIT,PID_LIMIT,FD_LIMIT,SCRATCH_LIMIT,DESCENDANT_REAP,
        PIDS_RELEASE_ACCOUNTING,ORPHAN_REAPER }
    record Slot(String id,Path hostScratch,Path cgroup) {}
    private final String id,sha256;private final Path path;private final UUID realm;private final long scratchBytes;private final List<Slot> slots;private final Map<String,String> inventory;
    private ImmutableContainmentProfile(String id,String sha256,Path path,UUID realm,long scratchBytes,List<Slot> slots,Map<String,String> inventory){
        this.id=id;this.sha256=sha256;this.path=path;this.realm=realm;this.scratchBytes=scratchBytes;this.slots=List.copyOf(slots);this.inventory=Map.copyOf(inventory);}
    static ImmutableContainmentProfile load(FileIngestionProperties properties,Deadline deadline)throws IOException {
        var scanner=properties.scanner();
        if(!scanner.containmentProfile().equals(retainedPath(scanner.profileSha256())))throw unavailable();
        return readProfile(scanner.containmentProfile(),scanner.profileSha256(),null,properties,deadline);
    }
    static ImmutableContainmentProfile retained(String hash,UUID realm,Deadline deadline)throws IOException {
        return readProfile(retainedPath(hash),hash,realm,null,deadline);
    }
    static Path retainedPath(String hash){FileIngestionContracts.hash(hash);return Path.of("/etc/msf-scanner/profiles",hash);}
    private static ImmutableContainmentProfile readProfile(Path path,String expectedHash,UUID expectedRealm,FileIngestionProperties candidate,Deadline deadline)throws IOException {
        if(!System.getProperty("os.name","").equals("Linux"))throw unavailable();
        var pin=ScannerGenerationManifest.PinnedFile.read(path,32768,deadline,true);
        if(!pin.sha256.equals(expectedHash))throw unavailable();byte[] bytes=java.nio.file.Files.readAllBytes(path);
        if(!ScannerGenerationManifest.sha256(bytes).equals(expectedHash))throw unavailable();
        return parseProfile(path,expectedHash,expectedRealm,candidate,bytes);
    }
    /** Pure parser seam, not a root ownership or accepted-resource capability. */
    static ImmutableContainmentProfile parseProfile(Path path,String expectedHash,UUID expectedRealm,FileIngestionProperties candidate,byte[] bytes){
        FileIngestionContracts.hash(expectedHash);var fields=parse(bytes);
        if(!"MSF_SCANNER_BROKER_PROFILE_V1".equals(take(fields,"format")))throw unavailable();
        UUID realm;try{String raw=take(fields,"realm-id");realm=UUID.fromString(raw);if(!realm.toString().equals(raw))throw unavailable();}catch(IllegalArgumentException malformed){throw unavailable();}
        if(expectedRealm!=null&&!expectedRealm.equals(realm))throw unavailable();
        int count=Math.toIntExact(number(take(fields,"slot-count")));if(count<1||count>2||candidate!=null&&count!=candidate.limits().concurrency())throw unavailable();
        var slots=new ArrayList<Slot>();var ids=new HashSet<String>();for(int i=0;i<count;i++){String slot=take(fields,"slot."+i+".id");FileIngestionContracts.ident(slot);if(!ids.add(slot))throw unavailable();Path base=Path.of("/var/lib/msf-scanner/realms",realm.toString(),"slots",slot);slots.add(new Slot(slot,base.resolve("rootfs/scratch"),Path.of("/sys/fs/cgroup/msf-scanner",realm.toString(),slot)));}
        long caller=number(take(fields,"caller-uid")),callerGid=nonnegative(take(fields,"caller-gid"));
        long uid=number(take(fields,"scanner-uid")),gid=number(take(fields,"scanner-gid"));if(uid==caller||uid>4294967295L||caller>4294967295L||gid>4294967295L||callerGid>4294967295L)throw unavailable();
        long memory=number(take(fields,"memory-bytes")),scratch=number(take(fields,"scratch-bytes")),max=number(take(fields,"max-bytes"));
        long cpu=number(take(fields,"cpu-seconds")),pids=number(take(fields,"max-pids")),fds=number(take(fields,"max-fds"));String executable=take(fields,"scanner-sha256");FileIngestionContracts.hash(executable);
        int resourceCount=Math.toIntExact(number(take(fields,"resource-count")));if(resourceCount<6||resourceCount>64)throw unavailable();
        var inventory=new LinkedHashMap<String,String>();for(int i=0;i<resourceCount;i++){
            String relative=take(fields,"resource."+i+".path");relativeResource(relative);String hash=take(fields,"resource."+i+".sha256");FileIngestionContracts.hash(hash);if(inventory.putIfAbsent(relative,hash)!=null)throw unavailable();
        }
        if(!Objects.equals(inventory.get("scanner/clamscan"),executable)||!inventory.keySet().containsAll(Set.of("definitions/main.cvd","definitions/daily.cvd","definitions/bytecode.cvd"))
                ||inventory.keySet().stream().noneMatch(n->Path.of(n).getFileName().toString().startsWith("libclamav"))
                ||inventory.keySet().stream().noneMatch(n->Path.of(n).getFileName().toString().startsWith("clamav-package")))throw unavailable();
        if(memory>536870912||scratch>134217728||max>MAX_CONTENT_BYTES||scratch<max||cpu>90||pids>16||fds<3||fds>64||!fields.isEmpty())throw unavailable();
        if(candidate!=null){
            Path rootfs=slots.getFirst().hostScratch().getParent();var config=candidate.scanner();
            if(scratch!=candidate.limits().scratchBytes()||max!=candidate.limits().maxBytes()||cpu!=candidate.limits().scanTimeout().toSeconds()
                    ||!executable.equals(config.executableSha256())||!config.executable().equals(rootfs.resolve("scanner/clamscan"))||!config.definitions().equals(rootfs.resolve("definitions")))throw unavailable();
            var expected=new HashMap<String,String>();expected.put("scanner/clamscan",config.executableSha256());config.definitionSha256().forEach((name,hash)->expected.put("definitions/"+name,hash));
            config.inventorySha256().forEach((absolute,hash)->{Path resourcePath=Path.of(absolute);if(!resourcePath.startsWith(rootfs))throw unavailable();String relative=rootfs.relativize(resourcePath).toString();relativeResource(relative);if(expected.putIfAbsent(relative,hash)!=null)throw unavailable();});
            if(!expected.equals(inventory))throw unavailable();
        }
        return new ImmutableContainmentProfile(expectedHash,expectedHash,path,realm,scratch,slots,inventory);
    }
    private static void relativeResource(String value){
        if(value.length()>240||!value.matches("(?:scanner|definitions|lib|usr/lib|etc)/[A-Za-z0-9_.+/-]+")||value.contains("//"))throw unavailable();
        for(String component:value.split("/"))if(component.isEmpty()||component.equals(".")||component.equals(".."))throw unavailable();
    }
    Map<String,String> inventory(){return inventory;}
    static Map<String,String> parse(byte[] bytes) {
        if(bytes.length==0||bytes.length>32768)throw unavailable();for(byte value:bytes)if(value!=10&&(value<32||value>126))throw unavailable();
        String text=new String(bytes,StandardCharsets.US_ASCII);if(!text.endsWith("\n"))throw unavailable();var fields=new HashMap<String,String>();
        for(String line:text.substring(0,text.length()-1).split("\n",-1)){int at=line.indexOf('=');if(at<1||at==line.length()-1||!line.substring(0,at).matches("[a-z][a-z0-9.-]{0,63}")||fields.putIfAbsent(line.substring(0,at),line.substring(at+1))!=null)throw unavailable();}return fields;
    }
    private static String take(Map<String,String> fields,String name){String value=fields.remove(name);if(value==null)throw unavailable();return value;}
    private static long number(String value){long n=nonnegative(value);if(n<1)throw unavailable();return n;}
    private static long nonnegative(String value){if(!value.matches("0|[1-9][0-9]{0,18}"))throw unavailable();try{return Long.parseLong(value);}catch(NumberFormatException invalid){throw unavailable();}}
    String id(){return id;}String sha256(){return sha256;}Path path(){return path;}UUID realm(){return realm;}
    long scratchBytes(){return scratchBytes;}List<Slot> slots(){return slots;}
    @Override public String toString(){return "ImmutableContainmentProfile[id="+id+"]";}
    private static FileEvidenceTrustUnavailableException unavailable(){return new FileEvidenceTrustUnavailableException();}
}

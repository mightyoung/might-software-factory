package com.mightyoung.softwarefactory.files;

import java.io.*;
import java.nio.file.Path;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.mightyoung.softwarefactory.files.FileIngestionContracts.*;
import static com.mightyoung.softwarefactory.files.ScannerBrokerProtocol.*;

/** Pinned native supervisor owns scanner pipes, flock, and actual child kernel observations. */
public final class ScannerProcessLauncher implements AutoCloseable {
    private final FileIngestionProperties properties;private final ImmutableContainmentProfile profile;private final ProcessStarter starter;
    private final ThreadPoolExecutor calls;private final Set<Session> active=ConcurrentHashMap.newKeySet();private final AtomicBoolean closed=new AtomicBoolean();
    @FunctionalInterface interface ProcessStarter {Process start(ProcessBuilder builder)throws IOException;}
    ScannerProcessLauncher(FileIngestionProperties properties,ImmutableContainmentProfile profile){this(properties,profile,ProcessBuilder::start);}
    // Synthetic fixture seam only; production configuration always uses ProcessBuilder::start.
    ScannerProcessLauncher(FileIngestionProperties properties,ImmutableContainmentProfile profile,ProcessStarter starter){this.properties=properties;this.profile=profile;this.starter=Objects.requireNonNull(starter);
        calls=new ThreadPoolExecutor(properties.limits().concurrency(),properties.limits().concurrency(),0,TimeUnit.MILLISECONDS,new SynchronousQueue<>(),Thread.ofPlatform().daemon().name("scanner-broker-",0).factory(),new ThreadPoolExecutor.AbortPolicy());}
    PreparedSlotLease prepareSlot(ScanPlan plan,Deadline deadline){
        if(closed.get()||!plan.physicalResourceRealmId().equals(profile.realm())||!plan.profileSha256().equals(profile.sha256())||profile.slots().stream().noneMatch(s->s.id().equals(plan.slotId())))throw unavailable();
        Session session=new Session(Binding.fresh(plan));active.add(session);
        try{return call(session,()->{session.start("launch",deadline);session.protocol.write(session.output,Type.PREPARE,NO_DESCRIPTOR,prepareBody(plan));
            Frame prepared=session.protocol.read(session.input,Type.PREPARED,NO_DESCRIPTOR);var body=new DataInputStream(new ByteArrayInputStream(prepared.body()));
            UUID boot=uuid(body);long pid=body.readLong(),ticks=body.readLong(),dev=body.readLong(),ino=body.readLong();byte[] control=body.readNBytes(32);end(body);
            if(pid!=session.process.pid()||ticks<1||dev<0||ino<1||control.length!=32)throw unavailable();session.boot=boot;session.supervisor=pid;session.supervisorTicks=ticks;session.prepared.set(true);
            if(session.cancelled.get())throw unavailable();return new PreparedSlotLease(this,plan,session);
        },deadline);}catch(RuntimeException failure){session.stop();throw failure;}
    }
    Result scan(RecordedExecution execution,PreparedSlotLease lease,InputStream input,long expected,String expectedHash,Deadline deadline){
        if(!lease.ownedBy(this,execution))throw unavailable();Session session=(Session)lease.session(this);var descriptor=execution.descriptor();IngestionProofCodec.validateExecution(descriptor);
        return call(session,()->{
            try {
                if(!session.prepared.compareAndSet(true,false)||session.cancelled.get())throw unavailable();
                var bytes=new ByteArrayOutputStream();var start=new DataOutputStream(bytes);byte[] canonical=descriptor.canonicalDescriptor();start.writeInt(canonical.length);start.write(canonical);start.writeLong(expected);start.write(HexFormat.of().parseHex(expectedHash));
                session.protocol.write(session.output,Type.START,descriptor.descriptorSha256(),bytes.toByteArray());
                KernelExecutionFacts child=facts(session,FactsTarget.of(descriptor),session.protocol.read(session.input,Type.CHILD,descriptor.descriptorSha256()).body());
                if(!child.childObserved()||child.childPid()==null||child.processState()!=ProcessState.UNKNOWN||child.imageVerified())throw unavailable();
                session.protocol.write(session.output,Type.GO,descriptor.descriptorSha256(),new byte[0]);
                KernelExecutionFacts image=facts(session,FactsTarget.of(descriptor),session.protocol.read(session.input,Type.IMAGE,descriptor.descriptorSha256()).body());
                if(!image.imageVerified()||!Objects.equals(child.childPid(),image.childPid())||!Objects.equals(child.childStartTicks(),image.childStartTicks())||!child.bootId().equals(image.bootId()))throw unavailable();
                var digest=java.security.MessageDigest.getInstance("SHA-256");long count=0;byte[] buffer=new byte[MAX_DATA];int n;
                while((n=input.read(buffer,0,(int)Math.min(buffer.length,Math.max(1,expected-count+1))))>=0){deadline.remainingNanos();if(session.cancelled.get())throw unavailable();if(n==0)continue;count=Math.addExact(count,n);if(count>expected)throw unavailable();digest.update(buffer,0,n);session.protocol.write(session.output,Type.DATA,descriptor.descriptorSha256(),Arrays.copyOf(buffer,n));}
                byte[] sha=digest.digest();if(count!=expected||!HexFormat.of().formatHex(sha).equals(expectedHash))throw unavailable();
                var ending=new ByteArrayOutputStream();var end=new DataOutputStream(ending);end.writeLong(count);end.write(sha);session.protocol.write(session.output,Type.END,descriptor.descriptorSha256(),ending.toByteArray());session.output.close();
                var result=new DataInputStream(new ByteArrayInputStream(session.protocol.read(session.input,Type.RESULT,descriptor.descriptorSha256()).body()));
                int exit=result.readInt();long actual=result.readLong();String actualHash=hash(result),stdoutHash=hash(result),stderrHash=hash(result);long stdout=result.readLong(),stderr=result.readLong();int reason=result.readUnsignedByte();
                KernelExecutionFacts finalFacts=facts(session,FactsTarget.of(descriptor),result.readAllBytes());
                if(!finalFacts.normalClean()||actual!=expected||!actualHash.equals(expectedHash)||stdout<0||stdout>65536||stderr<0||stderr>65536||reason!=(exit==0?0:exit==1?1:2)
                        ||!Objects.equals(finalFacts.childPid(),image.childPid())||!Objects.equals(finalFacts.childStartTicks(),image.childStartTicks()))throw unavailable();
                session.finish(deadline);return new Result(exit,count,actualHash,stdout,stderr,stdoutHash,stderrHash,finalFacts);
            }catch(Exception failure){session.stop();throw failure;}
        },deadline);
    }
    ProbeResult probe(ImmutableContainmentProfile.Slot slot,ImmutableContainmentProfile.Probe probe,Deadline deadline){
        if(!profile.slots().contains(slot))throw unavailable();byte[] challenge=new byte[32];new java.security.SecureRandom().nextBytes(challenge);
        Session session=new Session(new Binding(challenge,UUID.randomUUID(),profile.realm(),profile.sha256(),slot.id()));active.add(session);
        return call(session,()->{try{session.start("probe",deadline);session.protocol.write(session.output,Type.PROBE,NO_DESCRIPTOR,new byte[]{(byte)probe.ordinal()});session.output.close();
            var body=new DataInputStream(new ByteArrayInputStream(session.protocol.read(session.input,Type.PROBE_RESULT,NO_DESCRIPTOR).body()));
            int result=body.readUnsignedByte(),exit=body.readInt();long count=body.readLong();String sha=hash(body),control=hash(body),policy=hash(body);end(body);
            session.finish(deadline);return new ProbeResult(result,exit,count,sha,control,policy);
        }catch(Exception failure){session.stop();throw failure;}},deadline);
    }
    static RecoveryObservation observeRecovery(FileIngestionProperties properties,IngestionProofCodec codec,RecordedAllocation allocation,RecordedExecution execution,RecoveryBinding binding,Deadline deadline,Clock clock)throws IOException {
        var descriptor=execution.descriptor();var profile=ImmutableContainmentProfile.retained(descriptor.profileSha256(),descriptor.physicalResourceRealmId(),deadline);verifyBroker(properties,deadline);
        if(!descriptor.physicalResourceRealmId().equals(profile.realm())||!descriptor.profileSha256().equals(profile.sha256()))throw unavailable();
        try(var launcher=new ScannerProcessLauncher(properties,profile)){
            KernelExecutionFacts facts=launcher.quiesce(execution,deadline);
            if(!facts.quiescent())throw unavailable();Instant now=clock.instant().truncatedTo(ChronoUnit.MICROS);
            return codec.observeRecovery(binding,allocation.request(),RecoveryPresence.ABSENT,facts.processState(),false,null,null,facts,null,
                    allocation.baselineEventId(),allocation.baseline()==null?null:allocation.baseline().observationSha256(),facts.stopSha256(),now);
        }
    }
    static DisposalObservation disposeScratch(FileIngestionProperties properties,IngestionProofCodec codec,DisposalClaim claim,RecoveryBinding postClaim,Deadline deadline,Clock clock)throws IOException {
        var previous=claim.allocation().lastRecoveryObservation();codec.validateRecovery(previous);
        if(previous.kernelFacts()==null)throw unavailable();var original=previous.kernelFacts();
        var profile=ImmutableContainmentProfile.retained(original.profileSha256(),original.physicalResourceRealmId(),deadline);verifyBroker(properties,deadline);
        if(!original.physicalResourceRealmId().equals(profile.realm())||!original.profileSha256().equals(profile.sha256()))throw unavailable();
        try(var launcher=new ScannerProcessLauncher(properties,profile)){
            var facts=launcher.quiesce(new FactsTarget(original.executionId(),original.descriptorSha256(),original.physicalResourceRealmId(),original.profileSha256(),original.slotId()),deadline);
            if(!facts.quiescent())throw unavailable();Instant now=clock.instant().truncatedTo(ChronoUnit.MICROS);
            var absence=codec.observeRecovery(postClaim,claim.allocation().request(),RecoveryPresence.ABSENT,facts.processState(),false,null,null,facts,null,
                    previous.baselineEventId(),previous.baselineObservationSha256(),facts.stopSha256(),now);
            return codec.observeDisposal(claim,absence);
        }
    }
    private KernelExecutionFacts quiesce(RecordedExecution execution,Deadline deadline){return quiesce(FactsTarget.of(execution.descriptor()),execution.descriptor().canonicalDescriptor(),deadline);}
    private KernelExecutionFacts quiesce(FactsTarget d,Deadline deadline){return quiesce(d,null,deadline);}
    private KernelExecutionFacts quiesce(FactsTarget d,byte[] canonical,Deadline deadline){byte[] nonce=new byte[32];new java.security.SecureRandom().nextBytes(nonce);Session session=new Session(new Binding(nonce,d.executionId(),d.physicalResourceRealmId(),d.profileSha256(),d.slotId()));active.add(session);
        return call(session,()->{try{session.start("quiesce",deadline);var request=new ByteArrayOutputStream();var writer=new DataOutputStream(request);writer.writeByte(canonical==null?0:1);if(canonical!=null){writer.writeInt(canonical.length);writer.write(canonical);}
            session.protocol.write(session.output,Type.QUIESCE,d.descriptorSha256(),request.toByteArray());session.output.close();
            var observed=facts(session,d,session.protocol.read(session.input,Type.RECOVERY,d.descriptorSha256()).body());session.finish(deadline);return observed;
        }catch(Exception failure){session.stop();throw failure;}},deadline);
    }
    private KernelExecutionFacts facts(Session session,FactsTarget d,byte[] bytes)throws IOException {
        var in=new DataInputStream(new ByteArrayInputStream(bytes));UUID boot=uuid(in);long supervisor=in.readLong(),ticks=in.readLong();boolean present=bit(in);long rawPid=in.readLong(),rawTicks=in.readLong();int absent=in.readUnsignedByte();
        if(present&&(absent!=0||rawPid<1||rawTicks<1)||!present&&(absent!=2||rawPid!=0||rawTicks!=0))throw unavailable();Long[] ns=new Long[5];for(int i=0;i<5;i++){long n=in.readLong();ns[i]=n==0?null:n;}
        UUID cgroup=uuid(in);long mount=in.readLong(),inode=in.readLong();String policy=hash(in);long current=in.readLong();boolean populated=bit(in),continuous=bit(in),migration=bit(in),lock=bit(in);String lockHash=hash(in);boolean stop=bit(in);String stopHash=hash(in);
        boolean child=bit(in),image=bit(in),reap=bit(in),input=bit(in),out=bit(in),err=bit(in),discard=bit(in);int state=in.readUnsignedByte();String control=hash(in),report=hash(in);long epoch=in.readLong();int micros=in.readInt();end(in);
        if(state>=ProcessState.values().length||micros<0||micros>999999||supervisor!=session.supervisor&&session.supervisor!=0||ticks!=session.supervisorTicks&&session.supervisorTicks!=0
                ||session.boot!=null&&!session.boot.equals(boot)||!cgroup.equals(d.executionId()))throw unavailable();
        return new KernelExecutionFacts(d.executionId(),d.descriptorSha256(),session.binding.challengeHex(),d.physicalResourceRealmId(),d.slotId(),d.profileSha256(),boot,supervisor,ticks,
                present?rawPid:null,present?rawTicks:null,present?null:"CHILD_NOT_RECORDED",ns[0],ns[1],ns[2],ns[3],ns[4],cgroup,mount,inode,policy,current,populated,continuous,migration,lock,lockHash,stop,stop?stopHash:null,
                child,image,reap,input,out,err,discard,ProcessState.values()[state],control,report,Instant.ofEpochSecond(epoch,(long)micros*1000));
    }
    private record FactsTarget(UUID executionId,String descriptorSha256,UUID physicalResourceRealmId,String profileSha256,String slotId){
        static FactsTarget of(ExecutionDescriptor d){return new FactsTarget(d.executionId(),d.descriptorSha256(),d.physicalResourceRealmId(),d.profileSha256(),d.slotId());}
    }
    private static void verifyBroker(FileIngestionProperties p,Deadline deadline)throws IOException {var pin=ScannerGenerationManifest.PinnedFile.read(p.scanner().launcher(),67108864,deadline,true);if(!pin.sha256.equals(p.scanner().launcherSha256()))throw unavailable();}
    private static boolean bit(DataInputStream in)throws IOException {int value=in.readUnsignedByte();if(value>1)throw new IOException("Scanner broker protocol rejected");return value==1;}
    private static String hash(DataInputStream in)throws IOException{byte[] bytes=in.readNBytes(32);if(bytes.length!=32)throw new EOFException();return HexFormat.of().formatHex(bytes);}
    private static byte[] prepareBody(ScanPlan p)throws IOException {var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);for(UUID id:List.of(p.tenantId(),p.intentId(),p.fileId(),p.sourceAttemptId(),p.scanAttemptId(),p.quarantineAllocationId(),p.scratchAllocationId()))uuid(out,id);out.writeLong(p.expectedOldTechnicalVersion());out.writeLong(p.plannedClaimEpoch());out.write(HexFormat.of().parseHex(p.generationSha256()));out.write(HexFormat.of().parseHex(p.quarantineObservationSha256()));return bytes.toByteArray();}
    private <T>T call(Session session,Callable<T> operation,Deadline deadline){if(closed.get())throw unavailable();Future<T> future;try{future=calls.submit(operation);}catch(RuntimeException refused){throw unavailable();}
        try{return future.get(deadline.remainingNanos(),TimeUnit.NANOSECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();future.cancel(true);session.stop();throw unavailable();}catch(ExecutionException|TimeoutException|RuntimeException failure){future.cancel(true);session.stop();throw unavailable();}}
    boolean hasUnknownResources(){return active.stream().anyMatch(s->s.cancelled.get());}
    @Override public void close(){if(closed.compareAndSet(false,true)){active.forEach(Session::stop);calls.shutdownNow();}}
    private final class Session implements PreparedSlotLease.Session {
        final Binding binding;final ScannerBrokerProtocol protocol;final AtomicBoolean cancelled=new AtomicBoolean(),prepared=new AtomicBoolean(),finished=new AtomicBoolean();
        volatile Process process;volatile InputStream input;volatile OutputStream output;UUID boot;long supervisor,supervisorTicks;
        Session(Binding binding){this.binding=binding;protocol=new ScannerBrokerProtocol(binding);}
        void start(String verb,Deadline deadline)throws IOException {
            deadline.remainingNanos();var builder=new ProcessBuilder(properties.scanner().launcher().toString(),verb,profile.id(),profile.realm().toString(),binding.slotId(),binding.execution().toString());
            builder.environment().clear();builder.environment().putAll(Map.of("LC_ALL","C","TZ","UTC"));builder.redirectError(ProcessBuilder.Redirect.DISCARD);process=starter.start(builder);if(cancelled.get()){stop();throw unavailable();}input=process.getInputStream();output=process.getOutputStream();
        }
        public boolean locallyLive(){return prepared.get()&&!cancelled.get()&&!finished.get()&&!closed.get();}
        public void stop(){cancelled.set(true);prepared.set(false);var supervisorProcess=process;if(supervisorProcess!=null){
            // No blocking close while another thread may hold the pipe writer monitor. Killing
            // only the exact supervisor poisons this execution; protected cgroup recovery owns
            // descendant quiescence and byte release. Repeated stop also catches late start().
            supervisorProcess.destroy();try{if(!supervisorProcess.waitFor(2,TimeUnit.SECONDS))supervisorProcess.destroyForcibly();}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();supervisorProcess.destroyForcibly();}
        }}
        void finish(Deadline deadline)throws InterruptedException,IOException {if(!process.waitFor(deadline.remainingNanos(),TimeUnit.NANOSECONDS)||process.exitValue()!=0)throw unavailable();if(input.read()!=-1)throw unavailable();finished.set(true);active.remove(this);output.close();input.close();}
    }
    record Result(int exitCode,long bytes,String inputSha256,long stdoutBytes,long stderrBytes,String stdoutSha256,String stderrSha256,KernelExecutionFacts kernelFacts){}
    record ProbeResult(int resultCode,int exitCode,long bytes,String inputSha256,String controlSha256,String policySha256){}
    private static FileEvidenceTrustUnavailableException unavailable(){return new FileEvidenceTrustUnavailableException();}
}

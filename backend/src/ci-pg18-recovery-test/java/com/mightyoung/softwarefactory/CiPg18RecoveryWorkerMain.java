package com.mightyoung.softwarefactory;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.databind.json.JsonMapper;

/** Only the fixed orchestrator starts this main. Business work always runs through the actual graph. */
public final class CiPg18RecoveryWorkerMain {
    private CiPg18RecoveryWorkerMain(){}
    public static void main(String[] args) {
        PrintStream protocol=System.out;
        var privateOutput=new PrivateOutput();
        var sink=new PrintStream(privateOutput,true,StandardCharsets.UTF_8);
        System.setOut(sink);System.setErr(sink);
        CiPg18RecoveryWorkerTarget target=null;
        int exit=0;
        try{
            if(args.length!=0)throw CiPg18RecoveryGraph.unavailable();
            target=CiPg18RecoveryWorkerTarget.from(System.getenv());
            final var selected=target;
            var delivery=new AtomicReference<CiPg18RecoveryGraph.Delivery>();
            try(var fixture=target.producer()?CiPg18RecoveryFixture.attachProducer(target):CiPg18RecoveryFixture.attachRecovery(target)){
                var input=new BufferedReader(new java.io.InputStreamReader(System.in,StandardCharsets.US_ASCII));
                CiPg18RecoveryGraph.run(fixture,target,graph->{
                    if(selected.producer())graph.holdProducer();
                    send(protocol,selected,"READY",null);
                    if(selected.producer()){
                        requireCommand(input,"CREATE");graph.createSource();send(protocol,selected,"SOURCE_COMMITTED",null);
                        String command=command(input);
                        if("DELIVER".equals(command)){graph.releaseDelivery();delivery.set(graph.awaitDelivery());}
                        else if(!"EXIT".equals(command))throw CiPg18RecoveryGraph.unavailable();
                    }else{
                        requireCommand(input,"RECOVER");graph.recover();delivery.set(graph.awaitDelivery());
                    }
                });
            }
            String captured=privateOutput.text();
            if(privateOutput.overflow)throw CiPg18RecoveryGraph.unavailable();
            for(String canary:target.privateCanaries())if(captured.contains(canary))throw CiPg18RecoveryGraph.unavailable();
            var finished=delivery.get();
            if(finished!=null)send(protocol,target,finished.unknown()?"UNKNOWN_ACK":"DELIVERY_FINISHED",finished);
        }catch(Throwable failure){exit=2;send(protocol,target,"FAILED",null);}
        finally{
            sink.close();
            if(target!=null)try{
                CiPg18RecoveryProcess.privateWrite(target.config().getParent().resolve(target.workerMarker()+".worker.log"),privateOutput.copy());
            }catch(Throwable failure){exit=2;send(protocol,target,"FAILED",null);}
        }
        System.exit(exit);
    }
    private static void requireCommand(BufferedReader input,String expected){if(!expected.equals(command(input)))throw CiPg18RecoveryGraph.unavailable();}
    private static String command(BufferedReader input){try{
        StringBuilder value=new StringBuilder();for(int count=0;count<17;count++){
            int ch=input.read();if(ch<0)throw CiPg18RecoveryGraph.unavailable();if(ch=='\n')return value.toString();
            if(ch<'A'||ch>'Z')throw CiPg18RecoveryGraph.unavailable();value.append((char)ch);
        }throw CiPg18RecoveryGraph.unavailable();
    }catch(Exception failure){throw CiPg18RecoveryGraph.unavailable();}}
    private static void send(PrintStream output,CiPg18RecoveryWorkerTarget target,String stage,CiPg18RecoveryGraph.Delivery delivery){
        try{
            var process=ProcessHandle.current();Instant start=process.info().startInstant().orElseThrow();
            Map<String,Object> value=new LinkedHashMap<>();value.put("stage",stage);value.put("pid",process.pid());value.put("start",start.toString());
            value.put("workerMarker",target==null?"UNAVAILABLE":target.workerMarker());value.put("caseId",target==null?"UNAVAILABLE":target.caseId().toString());
            if(delivery!=null){value.put("failures",delivery.failures());value.put("completionRows",delivery.completionRows());
                value.put("ackCommits",delivery.ackCommits());value.put("ackRollbacks",delivery.ackRollbacks());value.put("unknown",delivery.unknown());
                value.put("faultReached",delivery.faultReached());value.put("captureBorrows",delivery.captureBorrows());
                value.put("captureCommits",delivery.captureCommits());value.put("captureRollbacks",delivery.captureRollbacks());}
            output.println(JsonMapper.builder().build().writeValueAsString(value));output.flush();
        }catch(Throwable failure){throw new IllegalStateException("Recovery protocol unavailable");}
    }
    private static final class PrivateOutput extends OutputStream {
        final ByteArrayOutputStream bytes=new ByteArrayOutputStream();boolean overflow;
        @Override public synchronized void write(int value){if(bytes.size()<1048576)bytes.write(value);else overflow=true;}
        @Override public synchronized void write(byte[] value,int start,int length){int take=Math.min(length,1048576-bytes.size());
            if(take>0)bytes.write(value,start,take);if(take<length)overflow=true;}
        synchronized String text(){return bytes.toString(StandardCharsets.UTF_8);}
        synchronized byte[] copy(){return bytes.toByteArray();}
    }
}

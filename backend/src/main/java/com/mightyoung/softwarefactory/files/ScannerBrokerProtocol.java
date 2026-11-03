package com.mightyoung.softwarefactory.files;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;

/**
 * MSF_SCANNER_BROKER_V1 wire codec; parsing alone is not kernel acceptance.
 * Frame: u32 payload length, u16 version=1,u16 type,u32 directional sequence,
 * challenge[32], execution UUID[16], realm UUID[16], profile SHA256[32],
 * u16 slot ASCII length + slot[1..64], descriptor SHA256[32], then typed body.
 * All integers are big endian; UUID is most/least significant u64. Sequence starts at 1
 * independently in each direction. PREPARE/PREPARED use zero descriptor, thereafter
 * exact committed descriptor. DATA body is raw bounded bytes; END body is u64 count
 * plus SHA256[32]. Other bodies have one fixed grammar per type, no extensible maps.
 * Control ceiling excludes DATA and is cumulative over both directions.
 */
final class ScannerBrokerProtocol {
    static final int VERSION=1,MAX_FRAME=32768,MAX_CONTROL=262144,MAX_DATA=30000;
    enum Type { PREPARE(1),PREPARED(2),START(3),CHILD(4),GO(5),IMAGE(6),DATA(7),END(8),RESULT(9),
        INSPECT(10),QUIESCE(11),RECOVERY(12),PROBE(13),PROBE_RESULT(14),ERROR(15);
        final int code;Type(int code){this.code=code;}
        static Type of(int code)throws IOException {for(var t:values())if(t.code==code)return t;throw invalid();}
    }
    record Binding(byte[] challenge,UUID execution,UUID realm,String profileSha256,String slotId) {
        Binding {challenge=challenge.clone();if(challenge.length!=32)throw new IllegalArgumentException();
            Objects.requireNonNull(execution);Objects.requireNonNull(realm);FileIngestionContracts.hash(profileSha256);FileIngestionContracts.ident(slotId);}
        @Override public byte[] challenge(){return challenge.clone();}
        static Binding fresh(FileIngestionContracts.ScanPlan plan){byte[] nonce=new byte[32];new SecureRandom().nextBytes(nonce);
            return new Binding(nonce,plan.plannedExecutionId(),plan.physicalResourceRealmId(),plan.profileSha256(),plan.slotId());}
        String challengeHex(){return HexFormat.of().formatHex(challenge);}
    }
    record Frame(Type type,String descriptorSha256,byte[] body) {
        Frame {Objects.requireNonNull(type);FileIngestionContracts.hash(descriptorSha256);body=body.clone();}
        @Override public byte[] body(){return body.clone();}
    }
    static final String NO_DESCRIPTOR="0".repeat(64);
    private final Binding binding;
    private long incoming=1,outgoing=1;private int controlBytes;
    ScannerBrokerProtocol(Binding binding){this.binding=Objects.requireNonNull(binding);}
    synchronized void write(OutputStream stream,Type type,String descriptor,byte[] body)throws IOException {
        if(outgoing>0xffffffffL)throw invalid();byte[] payload=payload(type,outgoing++,descriptor,body);
        account(type,payload.length);var out=new DataOutputStream(stream);out.writeInt(payload.length);out.write(payload);out.flush();
    }
    synchronized Frame read(InputStream stream,Type expected,String descriptor)throws IOException {
        var input=new DataInputStream(stream);long length=Integer.toUnsignedLong(input.readInt());
        if(length<8||length>MAX_FRAME)throw invalid();byte[] payload=input.readNBytes((int)length);if(payload.length!=length)throw invalid();
        var in=new DataInputStream(new ByteArrayInputStream(payload));if(in.readUnsignedShort()!=VERSION)throw invalid();
        Type type=Type.of(in.readUnsignedShort());long seq=Integer.toUnsignedLong(in.readInt());if(seq!=incoming++||incoming>0x100000000L||type!=expected)throw invalid();
        if(!MessageDigest.isEqual(in.readNBytes(32),binding.challenge)||!uuid(in).equals(binding.execution)||!uuid(in).equals(binding.realm))throw invalid();
        if(!MessageDigest.isEqual(in.readNBytes(32),HexFormat.of().parseHex(binding.profileSha256)))throw invalid();
        int slotLength=in.readUnsignedShort();if(slotLength<1||slotLength>64)throw invalid();byte[] slot=in.readNBytes(slotLength);
        if(!Arrays.equals(slot,binding.slotId.getBytes(StandardCharsets.US_ASCII)))throw invalid();
        byte[] sha=in.readNBytes(32);if(!MessageDigest.isEqual(sha,HexFormat.of().parseHex(descriptor)))throw invalid();
        account(type,payload.length);byte[] body=in.readAllBytes();if(type==Type.DATA&&body.length>MAX_DATA)throw invalid();
        return new Frame(type,descriptor,body);
    }
    private byte[] payload(Type type,long sequence,String descriptor,byte[] body)throws IOException {
        FileIngestionContracts.hash(descriptor);if(type==Type.DATA&&body.length>MAX_DATA)throw invalid();
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeShort(VERSION);out.writeShort(type.code);out.writeInt((int)sequence);
        out.write(binding.challenge);uuid(out,binding.execution);uuid(out,binding.realm);out.write(HexFormat.of().parseHex(binding.profileSha256));
        byte[] slot=binding.slotId.getBytes(StandardCharsets.US_ASCII);out.writeShort(slot.length);out.write(slot);out.write(HexFormat.of().parseHex(descriptor));out.write(body);
        if(bytes.size()>MAX_FRAME)throw invalid();return bytes.toByteArray();
    }
    private void account(Type type,int size)throws IOException {if(type!=Type.DATA){controlBytes=Math.addExact(controlBytes,size+4);if(controlBytes>MAX_CONTROL)throw invalid();}}
    static UUID uuid(DataInputStream in)throws IOException{return new UUID(in.readLong(),in.readLong());}
    static void uuid(DataOutputStream out,UUID id)throws IOException{out.writeLong(id.getMostSignificantBits());out.writeLong(id.getLeastSignificantBits());}
    static void end(DataInputStream in)throws IOException{if(in.read()!=-1)throw invalid();}
    private static IOException invalid(){return new IOException("Scanner broker protocol rejected");}
}

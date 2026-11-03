package com.mightyoung.softwarefactory.identity;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import static com.mightyoung.softwarefactory.identity.IdentityLifecycleContracts.*;

/** Prepares all retained-key candidates before locks; replay selects only the receipt's own key. */
public final class IdentityLifecycleFingerprint {
    private final Map<String, SecretKeySpec> keys;
    private final String activeKeyId;
    private final SecretKeySpec rateKey;

    public IdentityLifecycleFingerprint(String activeKeyId, Map<String, byte[]> retainedKeys, byte[] rateKey) {
        identifier(activeKeyId);
        if (retainedKeys == null || retainedKeys.isEmpty() || retainedKeys.size() > 8 || !retainedKeys.containsKey(activeKeyId)) throw invalid();
        Map<String, SecretKeySpec> copied = new LinkedHashMap<>();
        retainedKeys.forEach((id,key) -> { identifier(id); validKey(key); copied.put(id,new SecretKeySpec(key.clone(),"HmacSHA256")); });
        validKey(rateKey);
        this.keys=Map.copyOf(copied); this.activeKeyId=activeKeyId;
        this.rateKey=new SecretKeySpec(rateKey.clone(),"HmacSHA256");
    }

    public Prepared prepare(Operation operation, Object... canonicalFields) {
        Objects.requireNonNull(operation);
        try (var bytes = new WipeableBytes(); var out = new DataOutputStream(bytes)) {
            field(out,"IDENTITY_LIFECYCLE_COMMAND_V1"); field(out,operation.name());
            if (canonicalFields.length > 32) throw invalid();
            for (Object value : canonicalFields) {
                if (value instanceof char[] secret) {
                    var encoded=StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(secret));
                    byte[] raw=new byte[encoded.remaining()]; encoded.get(raw);
                    try { out.writeInt(raw.length); out.write(raw); }
                    finally { Arrays.fill(raw,(byte)0); if(encoded.hasArray())Arrays.fill(encoded.array(),(byte)0); }
                } else if(value==null||value instanceof String||value instanceof java.util.UUID
                        ||value instanceof Long||value instanceof Integer||value instanceof Boolean||value instanceof Enum<?>) {
                    field(out,value==null?null:value.toString());
                } else throw invalid();
            }
            out.flush();
            byte[] canonical=bytes.toByteArray();
            try {
                Map<String,String> candidates=new LinkedHashMap<>();
                keys.forEach((id,key) -> candidates.put(id,mac(key,canonical)));
                return new Prepared(activeKeyId,candidates);
            } finally { Arrays.fill(canonical,(byte)0); }
        } catch(java.io.IOException impossible) { throw IdentityLifecycleException.unavailable(); }
    }

    /** Separate domain and key prevent a stored receipt from serving as a selector oracle. */
    public String selector(String purpose, String selector) {
        identifier(purpose); text(selector,400);
        byte[] input=("IDENTITY_LIFECYCLE_RATE_V1\0"+purpose+"\0"+selector).getBytes(StandardCharsets.UTF_8);
        try { return mac(rateKey,input); } finally { Arrays.fill(input,(byte)0); }
    }

    public static final class Prepared {
        private final String activeKeyId;
        private final Map<String,String> candidates;
        private Prepared(String activeKeyId,Map<String,String> candidates){this.activeKeyId=activeKeyId;this.candidates=Map.copyOf(candidates);}
        public String activeKeyId(){return activeKeyId;}
        public String activeFingerprint(){return candidates.get(activeKeyId);}
        public boolean matches(String keyId,String fingerprint) {
            String candidate=candidates.get(keyId);
            if(candidate==null)throw IdentityLifecycleException.unavailable();
            hash(fingerprint);
            return MessageDigest.isEqual(HexFormat.of().parseHex(candidate),HexFormat.of().parseHex(fingerprint));
        }
        @Override public String toString(){return "PreparedLifecycleFingerprint[REDACTED]";}
    }
    private static void field(DataOutputStream out,String value) throws java.io.IOException {
        if(value==null){out.writeInt(-1);return;}
        if(value.length()>4096)throw invalid();
        // Reject replacement encodings; two different Java strings cannot share canonical UTF-8.
        var encoded=StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(value));
        byte[] bytes=new byte[encoded.remaining()];encoded.get(bytes);
        try {out.writeInt(bytes.length);out.write(bytes);}
        finally {Arrays.fill(bytes,(byte)0);if(encoded.hasArray())Arrays.fill(encoded.array(),(byte)0);}
    }
    private static String mac(SecretKeySpec key,byte[] value) {
        try {Mac mac=Mac.getInstance("HmacSHA256");mac.init(key);return HexFormat.of().formatHex(mac.doFinal(value));}
        catch(java.security.GeneralSecurityException unavailable){throw IdentityLifecycleException.unavailable();}
    }
    private static void validKey(byte[] key){if(key==null||key.length<32||key.length>64)throw invalid();}
    private static final class WipeableBytes extends ByteArrayOutputStream {
        @Override public void close(){Arrays.fill(buf,(byte)0);reset();}
    }
}

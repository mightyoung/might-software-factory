package com.mightyoung.softwarefactory.identity;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Dedicated retained keys supplied explicitly; no random/development/lifecycle-key fallback. */
public final class SessionSensitiveKeys {
    private final String current;
    private final Map<String,byte[]> retained;
    public SessionSensitiveKeys(String current,Map<String,byte[]> retained) {
        if(current==null||retained==null||retained.isEmpty()||retained.size()>32)throw SessionSensitiveException.unavailable();
        Map<String,byte[]> copy=new LinkedHashMap<>();
        retained.forEach((id,key)->{
            if(id==null||!id.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}")||key==null||key.length<32||key.length>64)throw SessionSensitiveException.unavailable();
            copy.put(id,key.clone());
        });
        if(!copy.containsKey(current))throw SessionSensitiveException.unavailable();
        this.current=current;this.retained=Map.copyOf(copy);
    }
    java.util.Set<String> retainedKeyIds(){return retained.keySet();}
    public String currentKeyId(){return current;}
    Map<String,String> sessionCandidates(String bearerId){
        Map<String,String> result=new LinkedHashMap<>();
        retained.keySet().forEach(id->result.put(id,sessionDigest(id,bearerId)));return Map.copyOf(result);
    }
    String sessionDigest(String keyId,String bearerId){return digest(keyId,"session-binding",bearerId);}
    String commandDigest(String keyId,String canonical){return digest(keyId,"session-revoke-command",canonical);}
    String actionDigest(String keyId,String canonical){return digest(keyId,"session-revoke-action",canonical);}
    String issuanceDigest(String keyId,String canonical){return digest(keyId,"session-reauth-issuance",canonical);}
    boolean matchesIssuance(String keyId,String expected,String canonical){return equal(expected,issuanceDigest(keyId,canonical));}
    boolean matchesAction(String keyId,String expected,String canonical){return equal(expected,actionDigest(keyId,canonical));}
    boolean matchesSession(String keyId,String expected,String bearerId){return equal(expected,sessionDigest(keyId,bearerId));}
    boolean matchesCommand(String keyId,String expected,String canonical){return equal(expected,commandDigest(keyId,canonical));}
    private String digest(String keyId,String domain,String value) {
        byte[] key=retained.get(keyId);if(key==null||value==null)throw SessionSensitiveException.unavailable();
        try {
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));
            mac.update(("msf.session-sensitive.v1/"+domain+"\0").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        }catch(java.security.GeneralSecurityException error){throw SessionSensitiveException.unavailable();}
    }
    private static boolean equal(String expected,String actual){
        if(expected==null||!expected.matches("[0-9a-f]{64}"))throw SessionSensitiveException.unavailable();
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),actual.getBytes(StandardCharsets.US_ASCII));
    }
    @Override public String toString(){return "SessionSensitiveKeys[REDACTED]";}
}

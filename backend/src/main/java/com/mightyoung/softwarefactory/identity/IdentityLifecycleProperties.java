package com.mightyoung.softwarefactory.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Dedicated retained configuration; no random, password-derived or development fallback keys. */
@ConfigurationProperties("software-factory.identity.lifecycle")
public final class IdentityLifecycleProperties {
    private boolean enabled;
    private Duration invitationTtl=Duration.ofHours(2),resetTtl=Duration.ofMinutes(15);
    private Duration providerTimeout=Duration.ofSeconds(30);
    private String fingerprintKeyId,rateKey;
    private Map<String,String> fingerprintKeys=new LinkedHashMap<>();
    private URI consumeOrigin;
    private int requestsPerSourceMinute=30,requestsGlobalMinute=128,sourceCapacity=2048,rateBucketCapacity=100_000;
    private volatile IdentityLifecycleFingerprint cached;
    public IdentityLifecycleFingerprint crypto() {
        IdentityLifecycleFingerprint found=cached;if(found!=null)return found;
        synchronized(this){if(cached!=null)return cached;
            try {
                Map<String,byte[]> keys=new LinkedHashMap<>();
                try {
                    if(fingerprintKeyId==null||rateKey==null||fingerprintKeys.isEmpty())throw IdentityLifecycleException.unavailable();
                    fingerprintKeys.forEach((id,value)->keys.put(id,Base64.getDecoder().decode(value)));
                    byte[] rate=Base64.getDecoder().decode(rateKey);
                    try {cached=new IdentityLifecycleFingerprint(fingerprintKeyId,keys,rate);}
                    finally {java.util.Arrays.fill(rate,(byte)0);}
                } finally {keys.values().forEach(key->java.util.Arrays.fill(key,(byte)0));}
                return cached;
            } catch(RuntimeException invalid){throw IdentityLifecycleException.unavailable();}
        }
    }
    public void validatePolicy() {
        if(invitationTtl==null||invitationTtl.compareTo(Duration.ofMinutes(15))<0||invitationTtl.compareTo(Duration.ofHours(24))>0
                ||resetTtl==null||resetTtl.compareTo(Duration.ofMinutes(5))<0||resetTtl.compareTo(Duration.ofMinutes(30))>0
                ||requestsPerSourceMinute<1||requestsPerSourceMinute>120||requestsGlobalMinute<1||requestsGlobalMinute>1000||sourceCapacity<1||sourceCapacity>4096
                ||rateBucketCapacity<1||rateBucketCapacity>100_000)throw IdentityLifecycleException.unavailable();
        if(providerTimeout==null||providerTimeout.isNegative()||providerTimeout.isZero()||providerTimeout.compareTo(Duration.ofSeconds(30))>0
                ||deliveryWindow().compareTo(resetTtl)>0||deliveryWindow().compareTo(invitationTtl)>0)throw IdentityLifecycleException.unavailable();
    }
    public Duration deliveryWindow(){return providerTimeout.multipliedBy(9).plusSeconds(60);}
    public boolean isEnabled(){return enabled;} public void setEnabled(boolean value){enabled=value;}
    public Duration getInvitationTtl(){return invitationTtl;} public void setInvitationTtl(Duration value){invitationTtl=value;}
    public Duration getResetTtl(){return resetTtl;} public void setResetTtl(Duration value){resetTtl=value;}
    public Duration getProviderTimeout(){return providerTimeout;} public void setProviderTimeout(Duration value){providerTimeout=value;}
    public String getFingerprintKeyId(){return fingerprintKeyId;} public void setFingerprintKeyId(String value){fingerprintKeyId=value;}
    public Map<String,String> getFingerprintKeys(){return fingerprintKeys;} public void setFingerprintKeys(Map<String,String> value){fingerprintKeys=new LinkedHashMap<>(value);}
    public String getRateKey(){return rateKey;} public void setRateKey(String value){rateKey=value;}
    public URI getConsumeOrigin(){return consumeOrigin;} public void setConsumeOrigin(URI value){consumeOrigin=value;}
    public int getRequestsPerSourceMinute(){return requestsPerSourceMinute;} public void setRequestsPerSourceMinute(int value){requestsPerSourceMinute=value;}
    public int getRequestsGlobalMinute(){return requestsGlobalMinute;} public void setRequestsGlobalMinute(int value){requestsGlobalMinute=value;}
    public int getSourceCapacity(){return sourceCapacity;} public void setSourceCapacity(int value){sourceCapacity=value;}
    public int getRateBucketCapacity(){return rateBucketCapacity;} public void setRateBucketCapacity(int value){rateBucketCapacity=value;}
    @Override public String toString(){return "IdentityLifecycleProperties[REDACTED]";}
}

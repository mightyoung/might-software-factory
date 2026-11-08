package com.mightyoung.softwarefactory.dualprevention.risk;

import com.mightyoung.softwarefactory.authorization.EntityActionAuthorization.ActionScope;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;
import static com.mightyoung.softwarefactory.dualprevention.risk.RiskScenarioContracts.*;

/** Bounded mismatch detector, never an authority token or a cross-page historical snapshot. */
final class RiskScenarioReadCursorCodec {
    record Anchor(UUID id,long version) { Anchor { java.util.Objects.requireNonNull(id); RiskScenarioContracts.version(version); } }
    private RiskScenarioReadCursorCodec() { }
    static byte[] fingerprint(ActionScope scope,long applicationVersion,QueryRequest request) {
        try {
            var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
            frame(out,"risk-scenario-root-query-v1");frame(out,scope.action().permissionCode());
            var c=scope.context();for(Object value:new Object[]{c.tenantId(),c.actorId(),c.accountId(),c.memberId(),c.tenantAuthEpoch(),c.memberAuthEpoch(),c.credentialVersion(),applicationVersion})frame(out,value);
            frame(out,scope.organizationIds().size());scope.organizationIds().stream().map(UUID::toString).sorted().forEach(value->frame(out,value));
            var schema=RiskScenarioEntitySchema.schema();frame(out,schema.key());frame(out,schema.schemaVersion());
            schema.fields().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(entry->{
                frame(out,entry.getKey());frame(out,RiskScenarioReadRepository.column(entry.getKey()));var f=entry.getValue();frame(out,f.type());frame(out,f.nullable());frame(out,f.selectable());frame(out,f.sortable());frame(out,f.maxLength());
                frame(out,f.operators().size());f.operators().stream().map(Enum::name).sorted().forEach(value->frame(out,value));
                frame(out,f.enumValues().size());f.enumValues().stream().sorted().forEach(value->frame(out,value));
            });
            frame(out,request.size());frame(out,request.filters().size());
            request.filters().forEach(filter->{frame(out,filter.field());frame(out,filter.operator());frame(out,filter.values().size());filter.values().forEach(value->frame(out,value));});
            frame(out,request.sort().size());request.sort().forEach(sort->{frame(out,sort.field());frame(out,sort.direction());});
            frame(out,"implicit-id-ASC-C-NULLS-LAST");frame(out,request.fields().size());request.fields().forEach(value->frame(out,value));
            return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
        } catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static String encode(byte[] fingerprint,Anchor anchor) {
        if(fingerprint==null||fingerprint.length!=32||anchor==null)throw invalid();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(ByteBuffer.allocate(58).put((byte)1).put((byte)100).put(fingerprint)
                .putLong(anchor.id().getMostSignificantBits()).putLong(anchor.id().getLeastSignificantBits()).putLong(anchor.version()).array());
    }
    static Anchor decode(String token,byte[] fingerprint) {
        if(token==null||token.length()!=78||!token.matches("[A-Za-z0-9_-]{78}")||fingerprint==null||fingerprint.length!=32)throw invalid();
        try {
            byte[] raw=Base64.getUrlDecoder().decode(token);if(raw.length!=58||!Base64.getUrlEncoder().withoutPadding().encodeToString(raw).equals(token))throw invalid();
            var in=ByteBuffer.wrap(raw);if(in.get()!=1||in.get()!=100)throw invalid();var actual=new byte[32];in.get(actual);
            if(!MessageDigest.isEqual(fingerprint,actual))throw invalid();
            return new Anchor(new UUID(in.getLong(),in.getLong()),in.getLong());
        } catch(IllegalArgumentException failure) { throw invalid(); }
    }
    private static void frame(DataOutputStream out,Object value) {
        String type=value instanceof UUID?"uuid":value instanceof BigDecimal?"decimal":value instanceof Number?"number":value instanceof java.time.OffsetDateTime?"instant":"text";
        String text=value instanceof java.time.OffsetDateTime time?time.toInstant().toString():value.toString();
        try {byte[] tag=type.getBytes(StandardCharsets.UTF_8),data=text.getBytes(StandardCharsets.UTF_8);out.writeInt(tag.length);out.write(tag);out.writeInt(data.length);out.write(data);}
        catch(java.io.IOException impossible) {throw new IllegalStateException(impossible);}
    }
}

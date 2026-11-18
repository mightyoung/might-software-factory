package com.mightyoung.softwarefactory.dualprevention.asset;

import com.mightyoung.softwarefactory.authorization.EntityActionAuthorization.ActionScope;
import com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationEntitySchemas.Root;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import static com.mightyoung.softwarefactory.dualprevention.asset.AssetFoundationQueryContracts.*;

/** A bounded row anchor and mismatch checksum, never an authority token or a historical snapshot. */
final class AssetFoundationQueryCursor {
    record Anchor(UUID id,long version) {}
    private AssetFoundationQueryCursor() {}
    static byte[] fingerprint(Root root,Plan plan,ActionScope scope) {
        try {
            var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
            frame(out,"asset-query-v1");frame(out,root.key());var schema=AssetFoundationEntitySchemas.schema(root);frame(out,schema.schemaVersion());
            frame(out,schema.fields().size());
            schema.fields().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(entry->{
                var field=entry.getValue();frame(out,entry.getKey());frame(out,AssetFoundationQueryRepository.column(entry.getKey()));frame(out,field.type().name());frame(out,field.nullable());
                frame(out,field.selectable());frame(out,field.sortable());frame(out,field.maxLength());
                frame(out,field.operators().size());field.operators().stream().map(Enum::name).sorted().forEach(value->frame(out,value));
                frame(out,field.enumValues().size());field.enumValues().stream().sorted().forEach(value->frame(out,value));
            });
            frame(out,scope.context().tenantId());frame(out,scope.context().actorId());frame(out,scope.context().accountId());frame(out,scope.context().memberId());
            frame(out,scope.organizationIds().size());scope.organizationIds().stream().sorted(java.util.Comparator.comparing(UUID::toString)).forEach(value->frame(out,value));
            frame(out,plan.size());
            frame(out,plan.filters().size());
            for(Filter filter:plan.filters()) {frame(out,filter.field());frame(out,filter.operator().name());frame(out,filter.values().size());filter.values().forEach(value->frame(out,value));}
            frame(out,"sort");frame(out,"NULLS_LAST");frame(out,plan.sort().size());for(Sort sort:plan.sort()){frame(out,sort.field());frame(out,sort.direction().name());}
            frame(out,"fields");frame(out,plan.fields().size());plan.fields().forEach(value->frame(out,value));
            return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
        }catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    static String encode(Root root,byte[] fingerprint,UUID id,long version) {
        AssetContracts.version(version);
        if(fingerprint==null||fingerprint.length!=32||id==null)throw new IllegalArgumentException("Invalid cursor source");
        ByteBuffer frame=ByteBuffer.allocate(58).put((byte)1).put(tag(root)).put(fingerprint)
                .putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).putLong(version);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(frame.array());
    }
    static Anchor decode(String value,Root root,byte[] fingerprint) {
        if(value==null||value.length()!=78||!value.matches("[A-Za-z0-9_-]{78}"))throw AssetFoundationReadJson.invalid();
        try {
            byte[] raw=Base64.getUrlDecoder().decode(value);
            if(raw.length!=58||!Base64.getUrlEncoder().withoutPadding().encodeToString(raw).equals(value))throw AssetFoundationReadJson.invalid();
            ByteBuffer bytes=ByteBuffer.wrap(raw);
            if(bytes.get()!=1||bytes.get()!=tag(root))throw AssetFoundationReadJson.invalid();
            byte[] found=new byte[32];bytes.get(found);
            if(!MessageDigest.isEqual(found,fingerprint))throw AssetFoundationReadJson.invalid();
            return new Anchor(new UUID(bytes.getLong(),bytes.getLong()),AssetContracts.version(bytes.getLong()));
        }catch(IllegalArgumentException error){throw AssetFoundationReadJson.invalid();}
    }
    private static byte tag(Root root) {return switch(root){case WORKSHOP->1;case PRODUCTION_LINE->2;case PROCESS_STAGE->3;case EQUIPMENT->4;case WORK_UNIT->5;case HAZARD_SOURCE->6;};}
    private static void frame(DataOutputStream out,Object value) {
        String type=value instanceof UUID?"uuid":value instanceof BigDecimal?"decimal":value instanceof Number?"number":value instanceof java.time.OffsetDateTime?"instant":"text";
        String text=value instanceof java.time.OffsetDateTime instant?instant.toInstant().toString():value.toString();
        try {byte[] marker=type.getBytes(StandardCharsets.UTF_8),data=text.getBytes(StandardCharsets.UTF_8);out.writeInt(marker.length);out.write(marker);out.writeInt(data.length);out.write(data);}
        catch(java.io.IOException impossible){throw new IllegalStateException(impossible);}
    }
}

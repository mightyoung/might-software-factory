package com.mightyoung.softwarefactory.dualprevention.asset;

import com.mightyoung.softwarefactory.authorization.RegisteredEntityAction;
import com.mightyoung.softwarefactory.identity.CurrentTenantContext;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Versioned, typed length framing; field order is supplied only by fixed command constructors. */
final class AssetFoundationFingerprint {
    private AssetFoundationFingerprint() {}
    static String command(CurrentTenantContext context,RegisteredEntityAction action,UUID target,List<?> fields) {
        var values=new ArrayList<Object>();
        values.add(1L); values.add(context.tenantId()); values.add(context.actorId()); values.add(context.accountId());
        values.add(context.memberId()); values.add(action.permissionCode()); values.add(target); values.addAll(fields);
        return HexFormat.of().formatHex(digest(values));
    }
    private static byte[] digest(List<?> values) {
        try {
            var bytes=new ByteArrayOutputStream(); var out=new DataOutputStream(bytes);
            for(Object value:values) {
                if(value==null) { out.writeByte(0);out.writeInt(-1);continue; }
                int type; String encoded;
                if(value instanceof String s) { type=1;encoded=s; }
                else if(value instanceof UUID id) { type=2;encoded=id.toString(); }
                else if(value instanceof Long n) { type=3;encoded=n.toString(); }
                else if(value instanceof Instant time) { type=4;encoded=AssetContracts.instant(time).toString(); }
                else if(value instanceof Enum<?> e) { type=5;encoded=e.name(); }
                else throw new IllegalArgumentException("Unsupported canonical field");
                byte[] utf8=encoded.getBytes(StandardCharsets.UTF_8);out.writeByte(type);out.writeInt(utf8.length);out.write(utf8);
            }
            return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
        } catch(java.security.NoSuchAlgorithmException|IOException impossible) { throw new IllegalStateException(impossible); }
    }
}

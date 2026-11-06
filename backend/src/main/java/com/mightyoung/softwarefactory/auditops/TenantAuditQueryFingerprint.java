package com.mightyoung.softwarefactory.auditops;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import static com.mightyoung.softwarefactory.auditops.TenantAuditQueryContracts.*;

/** Unkeyed continuation consistency only. A digest is never identity, authorization or a signed snapshot. */
final class TenantAuditQueryFingerprint {
    private TenantAuditQueryFingerprint() { }

    static String of(UUID tenantId, Window window, Query query) {
        if (tenantId == null || window == null || query == null || query.from() != null
                && (!query.from().equals(window.from()) || !query.to().equals(window.to()))) throw unavailable();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("audit-event-query-v1".getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0);
            // Fixed order: tenant, effective from/to, actor, resource type/id, action, result.
            value(digest, tenantId.toString()); value(digest, window.from().toString()); value(digest, window.to().toString());
            value(digest, query.actorId() == null ? null : query.actorId().toString());
            value(digest, query.resourceType()); value(digest, query.resourceId() == null ? null : query.resourceId().toString());
            value(digest, query.action()); value(digest, query.result() == null ? null : query.result().name());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) { throw unavailable(); }
    }
    private static void value(MessageDigest digest, String value) {
        if (value == null) { digest.update((byte) 0); return; }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8); digest.update((byte) 1);
        digest.update((byte) (bytes.length >>> 24)); digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8)); digest.update((byte) bytes.length); digest.update(bytes);
    }
    private static IllegalStateException unavailable() { return new IllegalStateException("Audit query fingerprint is unavailable"); }
}

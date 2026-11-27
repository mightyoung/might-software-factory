package com.mightyoung.softwarefactory.platformadmin.digitaltwin;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.mightyoung.softwarefactory.platformadmin.digitaltwin.StudioPlayerPublication.Published;
import com.mightyoung.softwarefactory.platformadmin.digitaltwin.StudioPublicationPolicy.Target;

/** Dormant JDBC storage. An authorized Player operation must supply and own the scoped connection. */
final class StudioPublishedLookup implements StudioPlayerPublication.PublishedLookup {
    static final String PIN_SQL = """
            select v.tenant_id,v.screen_key,v.version,v.canonical_sha256,v.canonical_bytes
              from public.studio_published_version v
             where v.tenant_id=? and v.screen_key=? and v.version=?
            """;
    static final String CURRENT_SQL = """
            select v.tenant_id,v.screen_key,v.version,v.canonical_sha256,v.canonical_bytes
              from public.studio_publication_stream s
              join public.studio_published_version v
                on v.tenant_id=s.tenant_id and v.screen_key=s.screen_key and v.version=s.current_version
             where s.tenant_id=? and s.screen_key=?
            """;
    private final Connection connection;

    StudioPublishedLookup(Connection connection) {
        this.connection = Objects.requireNonNull(connection);
    }

    @Override public Optional<Published> find(Target target, Long version) {
        try (var statement = connection.prepareStatement(version == null ? CURRENT_SQL : PIN_SQL)) {
            statement.setObject(1, target.tenantId());
            statement.setString(2, target.screenKey());
            if (version != null) statement.setLong(3, version);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                var tenant = rows.getObject(1, UUID.class);
                var screen = rows.getString(2);
                var storedVersion = rows.getLong(3);
                var digest = rows.getString(4);
                var bytes = rows.getBytes(5);
                if (rows.next() || tenant == null || screen == null || storedVersion < 1 || digest == null || bytes == null)
                    throw new IllegalStateException("Invalid stored Studio publication");
                try {
                    return Optional.of(new Published(new Target(tenant, screen), storedVersion, digest, bytes));
                } catch (IllegalArgumentException invalidTarget) {
                    throw new IllegalStateException("Invalid stored Studio publication");
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Studio published lookup unavailable");
        }
    }
}

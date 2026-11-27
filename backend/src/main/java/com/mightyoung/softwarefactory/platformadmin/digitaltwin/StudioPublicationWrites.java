package com.mightyoung.softwarefactory.platformadmin.digitaltwin;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

import static com.mightyoung.softwarefactory.platformadmin.digitaltwin.StudioPublicationPolicy.*;
import static com.mightyoung.softwarefactory.platformadmin.digitaltwin.StudioPublicationService.Snapshot;

/** Dormant SQL for a single transaction borrowed from StudioGuardedPublish. No audit or commit authority. */
final class StudioPublicationWrites {
    static final String CREATE_SQL = """
            insert into public.studio_publication_stream(tenant_id,screen_key,stream_id)
            select tenant_id,screen_key,cast(? as uuid) from public.studio_screen
             where tenant_id=? and screen_key=? and retired_at is null
            on conflict (tenant_id,screen_key) do nothing
            """;
    static final String LOCK_SQL = """
            select s.stream_id,s.current_version from public.studio_publication_stream s
            join public.studio_screen r on r.tenant_id=s.tenant_id and r.screen_key=s.screen_key
             where s.tenant_id=? and s.screen_key=? and r.retired_at is null for update of s
            """;
    static final String HEAD_SQL = """
            select canonical_sha256 from public.studio_published_version where tenant_id=? and screen_key=? and version=?
            """;
    static final String APPEND_SQL = """
            insert into public.studio_published_version(tenant_id,screen_key,version,canonical_bytes,
                canonical_sha256,publisher_member_id,audit_event_id,published_at) values (?,?,?,?,?,?,?,?)
            """;
    static final String POINTER_SQL = """
            update public.studio_publication_stream set current_version=?
             where tenant_id=? and screen_key=? and current_version is not distinct from cast(? as bigint)
            """;
    private final Connection connection;

    StudioPublicationWrites(Connection connection) { this.connection = Objects.requireNonNull(connection); }

    final class Locked {
        private final Target target;
        private final UUID streamId;
        private final Head head;
        private Snapshot appended;
        private boolean pointerAttempted;

        private Locked(Target target, UUID streamId, Head head) {
            this.target = target;
            this.streamId = streamId;
            this.head = head;
        }
        UUID streamId() { return streamId; }
        Head head() { return head; }
        private StudioPublicationWrites owner() { return StudioPublicationWrites.this; }
    }

    Locked lock(Target target, UUID candidateStreamId) {
        try {
            try (var sql = connection.prepareStatement(CREATE_SQL)) {
                sql.setObject(1, Objects.requireNonNull(candidateStreamId));
                sql.setObject(2, target.tenantId());
                sql.setString(3, target.screenKey());
                int count = sql.executeUpdate();
                if (count != 0 && count != 1) throw unavailable();
            }
            UUID streamId;
            Long version;
            try (var sql = connection.prepareStatement(LOCK_SQL)) {
                sql.setObject(1, target.tenantId());
                sql.setString(2, target.screenKey());
                try (var rows = sql.executeQuery()) {
                    if (!rows.next()) throw new Forbidden();
                    streamId = rows.getObject(1, UUID.class);
                    version = rows.getObject(2, Long.class);
                    if (streamId == null || rows.next()) throw unavailable();
                }
            }
            return new Locked(target, streamId, version == null ? null : readHead(target, version));
        } catch (SQLException failure) {
            throw unavailable();
        }
    }

    private Head readHead(Target target, long version) throws SQLException {
        try (var sql = connection.prepareStatement(HEAD_SQL)) {
            sql.setObject(1, target.tenantId());
            sql.setString(2, target.screenKey());
            sql.setLong(3, version);
            try (var rows = sql.executeQuery()) {
                if (!rows.next()) throw unavailable();
                String digest = rows.getString(1);
                if (rows.next()) throw unavailable();
                try { return new Head(target, version, digest); }
                catch (IllegalArgumentException invalidHead) { throw unavailable(); }
            }
        }
    }

    /** auditId/time are reserved by the trusted owner; the deferred FK does not prove audit semantics. */
    void append(Locked locked, Snapshot snapshot, Caller publisher, UUID auditId, Instant observedAt) {
        requireNext(locked, snapshot);
        authorizePublish(publisher, locked.target);
        if (locked.appended != null) throw unavailable();
        try (var sql = connection.prepareStatement(APPEND_SQL)) {
            sql.setObject(1, locked.target.tenantId());
            sql.setString(2, locked.target.screenKey());
            sql.setLong(3, snapshot.version());
            sql.setBytes(4, snapshot.canonicalBytes());
            sql.setString(5, snapshot.canonicalSha256());
            sql.setObject(6, publisher.memberId());
            sql.setObject(7, Objects.requireNonNull(auditId));
            sql.setObject(8, Objects.requireNonNull(observedAt).atOffset(ZoneOffset.UTC));
            if (sql.executeUpdate() != 1) throw unavailable();
            locked.appended = snapshot;
        } catch (SQLException failure) {
            throw unavailable();
        }
    }

    /** Future owner must call only after a matching, same-transaction audit receipt. Not wired. */
    void advance(Locked locked, Snapshot snapshot) {
        requireNext(locked, snapshot);
        if (locked.appended != snapshot || locked.pointerAttempted) throw unavailable();
        locked.pointerAttempted = true;
        try (var sql = connection.prepareStatement(POINTER_SQL)) {
            sql.setLong(1, snapshot.version());
            sql.setObject(2, locked.target.tenantId());
            sql.setString(3, locked.target.screenKey());
            if (locked.head == null) sql.setNull(4, Types.BIGINT);
            else sql.setLong(4, locked.head.version());
            if (sql.executeUpdate() != 1) throw unavailable();
        } catch (SQLException failure) {
            throw unavailable();
        }
    }

    private void requireNext(Locked locked, Snapshot snapshot) {
        if (locked.owner() != this || !locked.target.equals(snapshot.target())) throw unavailable();
        long old = locked.head == null ? 0 : locked.head.version();
        if (old == Long.MAX_VALUE || snapshot.version() != old + 1) throw unavailable();
    }

    private static IllegalStateException unavailable() { return new IllegalStateException("Studio publication write unavailable"); }
}

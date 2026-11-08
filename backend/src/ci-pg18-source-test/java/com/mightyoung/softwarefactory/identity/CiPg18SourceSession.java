package com.mightyoung.softwarefactory.identity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** Real persisted ENFORCING authority and public request capture; does not claim HTTP password login. */
public final class CiPg18SourceSession implements AutoCloseable {
    private static final Duration TIMEOUT = Duration.ofHours(1);
    private final CapturedHumanRequest captured;
    private final org.springframework.security.core.context.SecurityContext previous;
    private final Thread owner = Thread.currentThread();

    private CiPg18SourceSession(CapturedHumanRequest captured,
            org.springframework.security.core.context.SecurityContext previous) {
        this.captured = captured; this.previous = previous;
    }

    /** Caller owns an admin READ COMMITTED transaction with the real global writer guard already held. */
    public static CiPg18SourceSession create(Connection admin, UUID tenant, UUID account, UUID actor, UUID member,
            SessionSensitiveKeys keys) throws SQLException {
        if (admin.getAutoCommit() || admin.isReadOnly()
                || admin.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED)
            throw new IllegalStateException("Source session requires owned guarded transaction");
        var previous = SecurityContextHolder.getContext();
        Instant authenticated = Instant.now().minusSeconds(2).truncatedTo(ChronoUnit.MICROS);
        Instant created = authenticated.plusSeconds(1);
        var request = new MockHttpServletRequest();
        var session = new MockHttpSession(request.getServletContext(), UUID.randomUUID().toString());
        request.setSession(session);
        session.setAttribute(SessionLifetimePolicy.AUTHENTICATED_AT_ATTRIBUTE, authenticated);
        String primary = UUID.randomUUID().toString();
        UUID event = UUID.randomUUID();
        try {
            execute(admin, "insert into public.spring_session(primary_id,session_id,creation_time,last_access_time,max_inactive_interval,expiry_time,principal_name) values(?,?,?,?,?,?,?)",
                    primary, session.getId(), authenticated.toEpochMilli(), created.toEpochMilli(), 3600,
                    authenticated.plus(TIMEOUT).toEpochMilli(), account.toString());
            execute(admin, """
                    insert into public.account_session_authority(primary_id,account_id,binding_kind,session_key_id,session_digest,
                        bound_credential_version,authenticated_at,absolute_expires_at,state,version,created_at,created_event_id)
                    values(?,?,'CURRENT_REQUEST',?,?,1,?,?,'ACTIVE',1,?,?)
                    """, primary, account, keys.currentKeyId(), keys.sessionDigest(keys.currentKeyId(), session.getId()),
                    time(authenticated), time(authenticated.plus(TIMEOUT)), time(created), event);
            execute(admin, """
                    insert into public.identity_sensitive_event(id,operation,result,provenance,subject_account_id,actor_id,
                        source_primary_id,target_primary_id,trace_id,policy_version,occurred_at)
                    values(?,'REGISTER_SESSION','SUCCESS','FRESH_SESSION',?,?,?,?,?,'session-sensitive-v1',?)
                    """, event, account, actor, primary, primary, UUID.randomUUID(), time(created));
            var current = SecurityContextHolder.createEmptyContext();
            current.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    new VerifiedSessionPrincipal(actor, account, tenant, member, 1, 1, 1), "", List.of()));
            SecurityContextHolder.setContext(current);
            var captured = new SessionSensitiveRequests(new SessionContextResolver(), TIMEOUT).capture(request);
            return new CiPg18SourceSession(captured, previous);
        } catch (SQLException | RuntimeException | Error failure) {
            SecurityContextHolder.setContext(previous);
            throw failure;
        }
    }

    public CapturedHumanRequest captured() { captured.requireCurrent(); return captured; }
    @Override public void close() {
        if (owner != Thread.currentThread()) throw new IllegalStateException("Source session thread changed");
        captured.close(); SecurityContextHolder.setContext(previous);
    }
    private static OffsetDateTime time(Instant value) { return value.atOffset(ZoneOffset.UTC); }
    private static void execute(Connection connection, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) statement.setObject(index + 1, arguments[index]);
            if (statement.executeUpdate() != 1) throw new SQLException("Source session invariant rejected");
        }
    }
    @Override public String toString() { return "CiPg18SourceSession[private synthetic test identity]"; }
}

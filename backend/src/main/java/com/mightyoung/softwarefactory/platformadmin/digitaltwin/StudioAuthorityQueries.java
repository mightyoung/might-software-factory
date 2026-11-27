package com.mightyoung.softwarefactory.platformadmin.digitaltwin;

import com.mightyoung.softwarefactory.identity.CurrentTenantContext;

import java.sql.Connection;
import java.sql.SQLException;

/** Dormant SQL only. The owner must prove the current human scope and physical connection first. */
final class StudioAuthorityQueries {
    private StudioAuthorityQueries() {}

    static final String PUBLISH_SQL = """
            select exists(select 1 from public.studio_tenant_scene_admin as g
             where g.tenant_id=? and g.member_id=?)
            """;

    static final String SCREEN_SQL = """
            select exists(select 1 from public.studio_screen_player_reader as g
             join public.studio_screen as s on s.tenant_id=g.tenant_id and s.screen_key=g.screen_key
             where g.tenant_id=? and g.member_id=? and g.screen_key=? and s.retired_at is null)
            """;

    static boolean mayPublish(Connection connection, CurrentTenantContext current) throws SQLException {
        return exists(connection, current, PUBLISH_SQL, null);
    }

    static boolean mayRead(Connection connection, CurrentTenantContext current, StudioPublicationPolicy.Target target)
            throws SQLException {
        if (!current.tenantId().equals(target.tenantId())) return false;
        return exists(connection, current, SCREEN_SQL, target.screenKey());
    }

    private static boolean exists(Connection connection, CurrentTenantContext current, String query, String screen)
            throws SQLException {
        try (var statement = connection.prepareStatement(query)) {
            statement.setObject(1, current.tenantId());
            statement.setObject(2, current.memberId());
            if (screen != null) statement.setString(3, screen);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("Missing Studio authorization result");
                boolean granted = rows.getBoolean(1);
                if (rows.wasNull() || rows.next()) throw new SQLException("Invalid Studio authorization result");
                return granted;
            }
        }
    }
}

package com.mightyoung.softwarefactory.platformadmin.digitaltwin;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Dormant domain policy. No Spring bean, transport, authorization source, or storage. */
public final class StudioPublicationPolicy {
    private static final Pattern SCREEN = Pattern.compile("[a-z][a-z0-9_-]{0,63}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private StudioPublicationPolicy() {}

    public enum Permission { STUDIO_PUBLISH, PLAYER_READ }
    public enum Outcome { APPEND, UNCHANGED }

    public record Caller(UUID verifiedTenantId, Set<Permission> verifiedPermissions) {
        public Caller {
            Objects.requireNonNull(verifiedTenantId);
            verifiedPermissions = Set.copyOf(verifiedPermissions);
        }
    }
    public record Target(UUID tenantId, String screenKey) {
        public Target {
            Objects.requireNonNull(tenantId);
            if (screenKey == null || !SCREEN.matcher(screenKey).matches())
                throw new IllegalArgumentException("Invalid screen key");
        }
    }
    public record Head(Target target, long version, String canonicalSha256) {
        public Head {
            Objects.requireNonNull(target);
            if (version < 1 || !validHash(canonicalSha256)) throw new IllegalArgumentException("Invalid head");
        }
    }

    /** Produced only by a future authoritative Studio schema parser from the same normalized payload that is hashed.
     * This policy does not accept raw JSON, parse device/asset facts, or attest the caller's digest.
     */
    public record DraftFacts(String screenKey, String canonicalSha256, int devices, List<Wall> walls, List<Zone> zones) {
        public DraftFacts {
            walls = List.copyOf(walls);
            zones = List.copyOf(zones);
        }
    }
    public record Point(int xDm, int zDm) {
        public Point {
            if (Math.abs((long) xDm) > 100 || Math.abs((long) zDm) > 100)
                throw new IllegalArgumentException("Point outside Studio v1");
        }
        private String key() { return xDm + "," + zDm; }
    }
    public record Wall(int id, Point start, Point end) {
        public Wall {
            Objects.requireNonNull(start);
            Objects.requireNonNull(end);
        }
    }
    public record Zone(int id, List<Point> points) {
        public Zone { points = List.copyOf(points); }
    }
    /** Zone area is the sum of authored polygons, not area of their union. */
    public record Metrics(int devices, int walls, int zones, double wallLengthM, double zoneAreaM2) {}
    public record Decision(Outcome outcome, long version, String canonicalSha256, Metrics metrics) {}

    public static final class Forbidden extends RuntimeException {}
    public static final class VersionConflict extends RuntimeException {}

    public static void authorizeRead(Caller caller, Target target) { authorize(caller, target, Permission.PLAYER_READ); }

    public static Decision decide(Caller caller, Target target, long expectedVersion, Head head, DraftFacts draft) {
        authorize(caller, target, Permission.STUDIO_PUBLISH);
        if (head != null && !head.target().equals(target)) throw new Forbidden();
        if (expectedVersion < 0) throw new IllegalArgumentException("Negative expected version");
        var current = head == null ? 0 : head.version();
        if (expectedVersion != current) throw new VersionConflict();
        if (draft == null || !target.screenKey().equals(draft.screenKey()) || !validHash(draft.canonicalSha256()))
            throw new IllegalArgumentException("Invalid authoritative draft facts");
        var metrics = metrics(draft);
        if (head != null && head.canonicalSha256().equals(draft.canonicalSha256()))
            return new Decision(Outcome.UNCHANGED, current, draft.canonicalSha256(), metrics);
        if (current == Long.MAX_VALUE) throw new VersionConflict();
        return new Decision(Outcome.APPEND, current + 1, draft.canonicalSha256(), metrics);
    }

    private static boolean validHash(String value) { return value != null && SHA256.matcher(value).matches(); }

    private static void authorize(Caller caller, Target target, Permission required) {
        if (caller == null || target == null || !caller.verifiedTenantId().equals(target.tenantId()) ||
                !caller.verifiedPermissions().contains(required)) throw new Forbidden();
    }

    static Metrics metrics(DraftFacts draft) {
        if (draft.devices() < 0 || draft.devices() > 500 || draft.walls().size() > 100 || draft.zones().size() > 20)
            throw new IllegalArgumentException("Studio geometry exceeds v1 limits");
        var ids = new HashSet<Integer>();
        var segments = new HashSet<String>();
        double lengthDm = 0;
        for (var wall : draft.walls()) {
            registerId(ids, wall.id());
            var a = wall.start().key();
            var b = wall.end().key();
            if (a.equals(b) || !segments.add(a.compareTo(b) < 0 ? a + ":" + b : b + ":" + a))
                throw new IllegalArgumentException("Zero-length or duplicate wall");
            lengthDm += StrictMath.hypot(wall.start().xDm() - wall.end().xDm(), wall.start().zDm() - wall.end().zDm());
        }
        long twiceAreaDm2 = 0;
        for (var zone : draft.zones()) {
            registerId(ids, zone.id());
            twiceAreaDm2 += twiceArea(zone.points());
        }
        return new Metrics(draft.devices(), draft.walls().size(), draft.zones().size(), lengthDm / 10, twiceAreaDm2 / 200.0);
    }

    private static void registerId(Set<Integer> ids, int id) {
        if (id < 1 || id > 10_000 || !ids.add(id)) throw new IllegalArgumentException("Invalid shape identifier");
    }

    private static long twiceArea(List<Point> points) {
        int n = points.size();
        if (n < 3 || n > 60 || new HashSet<>(points).size() != n)
            throw new IllegalArgumentException("Invalid zone vertex count or duplicate");
        long doubleArea = 0;
        for (int i = 0; i < n; i++) {
            Point a = points.get(i), b = points.get((i + 1) % n), c = points.get((i + 2) % n);
            if (onSegment(a,b,c) || onSegment(b,c,a)) throw new IllegalArgumentException("Overlapping adjacent edges");
            for (int j = i + 2; j < n; j++) {
                if (i == 0 && j == n - 1) continue;
                if (intersects(a,b,points.get(j),points.get((j + 1) % n)))
                    throw new IllegalArgumentException("Zone edges cross or touch");
            }
            doubleArea += (long) a.xDm() * b.zDm() - (long) b.xDm() * a.zDm();
        }
        if (doubleArea == 0) throw new IllegalArgumentException("Zero-area zone");
        return Math.abs(doubleArea);
    }

    private static boolean intersects(Point a, Point b, Point c, Point d) {
        return (cross(a,b,c) * cross(a,b,d) < 0 && cross(c,d,a) * cross(c,d,b) < 0)
                || onSegment(a,b,c) || onSegment(a,b,d) || onSegment(c,d,a) || onSegment(c,d,b);
    }
    private static long cross(Point a, Point b, Point p) {
        return (long) (b.xDm() - a.xDm()) * (p.zDm() - a.zDm())
                - (long) (b.zDm() - a.zDm()) * (p.xDm() - a.xDm());
    }
    private static boolean onSegment(Point a, Point b, Point p) {
        return cross(a,b,p) == 0 && between(a.xDm(), b.xDm(), p.xDm()) && between(a.zDm(), b.zDm(), p.zDm());
    }
    private static boolean between(int a, int b, int p) { return p >= Math.min(a,b) && p <= Math.max(a,b); }
}

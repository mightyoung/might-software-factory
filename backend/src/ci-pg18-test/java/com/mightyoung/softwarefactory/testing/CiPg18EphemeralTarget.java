package com.mightyoung.softwarefactory.testing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.regex.Pattern;

/** Dedicated ephemeral CI target. Never accepts LAN URLs or JDBC connection overrides. */
public final class CiPg18EphemeralTarget {
    public enum Lane {
        CAPTURE("msf_ci_risk_capture", "ephemeral-risk-capture-only", "msf_ci_capture_runtime"),
        REGISTRY("msf_ci_risk_registry", "ephemeral-risk-registry-only", "msf_ci_registry_runtime");
        private final String database, acknowledgment, runtime;
        Lane(String database, String acknowledgment, String runtime) {
            this.database = database; this.acknowledgment = acknowledgment; this.runtime = runtime;
        }
    }
    private final Lane lane;
    private static final Set<PosixFilePermission> PRIVATE = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private static final Set<String> KEYS = Set.of("MSF_CI_PG18_ACK", "MSF_CI_PG18_URL",
            "MSF_CI_PG18_CONTAINER_ID", "MSF_CI_PG18_INSTANCE_ID", "MSF_CI_PG18_IMAGE_DIGEST",
            "MSF_CI_PG18_OWNERSHIP_FILE", "MSF_CI_PG18_ADMIN_PASSWORD_FILE", "MSF_CI_PG18_RUNTIME_PASSWORD_FILE");
    private final String jdbcUrl, adminPassword, runtimePassword, imageDigest, containerId;
    private final UUID instanceId;

    private CiPg18EphemeralTarget(Lane lane, String jdbcUrl, String adminPassword, String runtimePassword,
            String imageDigest, String containerId, UUID instanceId) {
        this.lane = lane;
        this.jdbcUrl = jdbcUrl; this.adminPassword = adminPassword; this.runtimePassword = runtimePassword;
        this.imageDigest = imageDigest; this.containerId = containerId; this.instanceId = instanceId;
    }

    public static CiPg18EphemeralTarget from(Map<String, String> environment, Lane lane) {
        try {
            if (lane == null || !"true".equals(environment.get("GITHUB_ACTIONS")) || !"true".equals(environment.get("CI"))
                    || !"mightyoung/might-software-factory".equals(environment.get("GITHUB_REPOSITORY"))
                    || !lane.acknowledgment.equals(environment.get("MSF_CI_PG18_ACK"))) throw rejected();
            for (var entry : environment.entrySet()) {
                String key = entry.getKey();
                if ((key.startsWith("LAN_PG_") || key.startsWith("PG18_TUNNEL_"))
                        && entry.getValue() != null && !entry.getValue().isBlank()) throw rejected();
                if (key.startsWith("MSF_CI_PG18_") && !KEYS.contains(key)) throw rejected();
            }
            String run = required(environment, "GITHUB_RUN_ID"), attempt = required(environment, "GITHUB_RUN_ATTEMPT");
            if (!run.matches("[1-9][0-9]{0,19}") || !attempt.matches("[1-9][0-9]{0,9}")) throw rejected();
            String url = required(environment, "MSF_CI_PG18_URL");
            var match = Pattern.compile("jdbc:postgresql://127\\.0\\.0\\.1:([1-9][0-9]{0,4})/" + lane.database).matcher(url);
            if (!match.matches()) throw rejected();
            int port = Integer.parseInt(match.group(1));
            if (port < 1024 || port > 65535) throw rejected();
            String container = required(environment, "MSF_CI_PG18_CONTAINER_ID");
            String digest = required(environment, "MSF_CI_PG18_IMAGE_DIGEST");
            String marker = required(environment, "MSF_CI_PG18_INSTANCE_ID");
            UUID instance = UUID.fromString(marker);
            if (!container.matches("[0-9a-f]{64}") || !digest.matches("sha256:[0-9a-f]{64}")
                    || !instance.toString().equals(marker)) throw rejected();
            Path root = Path.of(required(environment, "RUNNER_TEMP")).toRealPath();
            String receipt = readPrivate(environment, "MSF_CI_PG18_OWNERSHIP_FILE", root);
            Map<String, String> ownership = new HashMap<>();
            for (String line : receipt.lines().toList()) {
                int split = line.indexOf('=');
                if (split < 1 || split != line.lastIndexOf('=') || ownership.put(line.substring(0, split), line.substring(split + 1)) != null) throw rejected();
            }
            Map<String, String> expected = Map.of("container_id", container, "instance_id", marker,
                    "host", "127.0.0.1", "port", match.group(1), "database", lane.database,
                    "image_digest", digest, "repository", "mightyoung/might-software-factory",
                    "run_id", run, "run_attempt", attempt);
            String engine = ownership.remove("engine_major");
            if (engine == null || !engine.matches("[1-9][0-9]{0,2}") || Integer.parseInt(engine) < 28
                    || !ownership.equals(expected)) throw rejected();
            String admin = readPrivate(environment, "MSF_CI_PG18_ADMIN_PASSWORD_FILE", root);
            String runtime = readPrivate(environment, "MSF_CI_PG18_RUNTIME_PASSWORD_FILE", root);
            if (!admin.matches("[0-9a-f]{64}") || !runtime.matches("[0-9a-f]{64}") || admin.equals(runtime)) throw rejected();
            return new CiPg18EphemeralTarget(lane, url, admin, runtime, digest, container, instance);
        } catch (IOException | RuntimeException failure) {
            throw rejected();
        }
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) throw rejected();
        return value;
    }

    private static String readPrivate(Map<String, String> environment, String name, Path root) throws IOException {
        Path path = Path.of(required(environment, name));
        if (!path.isAbsolute() || !path.normalize().equals(path) || !path.startsWith(root)) throw rejected();
        Path component = path.getRoot();
        for (Path part : path) {
            component = component.resolve(part);
            if (Files.isSymbolicLink(component)) throw rejected();
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !Files.getPosixFilePermissions(path).equals(PRIVATE)
                || !Files.getOwner(path).equals(Files.getOwner(root))) throw rejected();
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(4097);
            if (bytes.length == 0 || bytes.length > 4096) throw rejected();
            String value = new String(bytes, StandardCharsets.US_ASCII);
            if (!Arrays.equals(bytes, value.getBytes(StandardCharsets.US_ASCII))) throw rejected();
            return value;
        }
    }

    public String jdbcUrl() { return jdbcUrl; }
    public String databaseName() { return lane.database; }
    public UUID instanceId() { return instanceId; }
    public String adminUser() { return "msf_ci_bootstrap"; }
    public String runtimeUser() { return lane.runtime; }
    public String adminPassword() { return adminPassword; }
    public String runtimePassword() { return runtimePassword; }
    public String imageDigest() { return imageDigest; }
    public String containerId() { return containerId; }
    @Override public String toString() { return "CiPg18EphemeralTarget[private ephemeral target]"; }
    private static IllegalStateException rejected() { return new IllegalStateException("CI PostgreSQL target rejected"); }
}

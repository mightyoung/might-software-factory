package com.mightyoung.softwarefactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Test-only TLS material and a real cookie client; never disables certificate or hostname checks. */
final class IdentityAcceptanceHttps implements AutoCloseable {

    static final String TRACE_HEADER = "X-Identity-Acceptance-Request";
    private static final String ALIAS = "identity-acceptance";
    private final Path directory;
    private final Path keyStore;
    private final String password;
    private final SSLContext clientContext;

    private IdentityAcceptanceHttps(Path directory, Path keyStore, String password, SSLContext clientContext) {
        this.directory = directory;
        this.keyStore = keyStore;
        this.password = password;
        this.clientContext = clientContext;
    }

    static IdentityAcceptanceHttps create() throws Exception {
        Path directory = Files.createTempDirectory("identity-acceptance-https-");
        if (directory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        }
        Path keyStore = directory.resolve("server.p12");
        String password = UUID.randomUUID().toString();
        Process process = null;
        try {
            Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
            ProcessBuilder builder = new ProcessBuilder(keytool.toString(), "-genkeypair", "-noprompt",
                    "-alias", ALIAS, "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
                    "-dname", "CN=localhost,OU=Acceptance,O=Synthetic Test,C=CN",
                    "-ext", "SAN=ip:127.0.0.1,dns:localhost", "-storetype", "PKCS12",
                    "-keystore", keyStore.toString(), "-storepass:env", "IDENTITY_TEST_TLS_PASSWORD");
            builder.environment().keySet().removeIf(name ->
                    name.startsWith("LAN_PG_") || name.startsWith("P0_OPERATOR_"));
            builder.environment().put("IDENTITY_TEST_TLS_PASSWORD", password);
            builder.redirectErrorStream(true).redirectOutput(directory.resolve("keytool.log").toFile());
            process = builder.start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out generating test-only HTTPS material");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("Could not generate test-only HTTPS material");
            }
            if (keyStore.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(keyStore, PosixFilePermissions.fromString("rw-------"));
            }
            KeyStore server = KeyStore.getInstance("PKCS12");
            try (var stream = Files.newInputStream(keyStore)) {
                server.load(stream, password.toCharArray());
            }
            KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
            trust.load(null, null);
            trust.setCertificateEntry(ALIAS, server.getCertificate(ALIAS));
            TrustManagerFactory managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            managers.init(trust);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, managers.getTrustManagers(), new SecureRandom());
            return new IdentityAcceptanceHttps(directory, keyStore, password, context);
        } catch (Exception failure) {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                try {
                    if (!process.waitFor(5, TimeUnit.SECONDS)) {
                        failure.addSuppressed(new IllegalStateException("Test keytool did not terminate"));
                    }
                } catch (InterruptedException interrupted) {
                    failure.addSuppressed(interrupted);
                    Thread.currentThread().interrupt();
                }
            }
            try {
                deleteDirectory(directory);
            } catch (Exception cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw failure;
        }
    }

    Path keyStore() { return keyStore; }
    String password() { return password; }

    Browser browser(int port) {
        return new Browser(port, clientContext);
    }

    @Override
    public void close() throws Exception {
        deleteDirectory(directory);
    }

    private static void deleteDirectory(Path directory) throws Exception {
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    static final class Browser implements AutoCloseable {
        private final URI origin;
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
        private final HttpClient client;
        private final SSLContext context;

        private Browser(int port, SSLContext context) {
            origin = URI.create("https://127.0.0.1:" + port);
            this.context = context;
            SSLParameters parameters = new SSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            client = HttpClient.newBuilder().sslContext(context).sslParameters(parameters)
                    .cookieHandler(cookies).connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NEVER).build();
        }

        Response get(String path) throws Exception {
            return send("GET", path, null, null);
        }

        Response getWithHeaders(String path, Map<String, String> headers) throws Exception {
            return send("GET", path, null, null, headers);
        }

        /** Separate cookie jar, preserving actual cookies while changing only the same-host test port. */
        Browser copyTo(int port) {
            if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid local test port");
            Browser copy = new Browser(port, context);
            for (HttpCookie cookie : cookies.getCookieStore().getCookies()) {
                if (!cookie.hasExpired()) copy.cookies.getCookieStore().add(copy.origin, (HttpCookie) cookie.clone());
            }
            return copy;
        }

        Response getWithJsonBody(String path, String body) throws Exception {
            return send("GET", path, body, null);
        }

        Response post(String path, String body, String csrf) throws Exception {
            return send("POST", path, body, csrf);
        }

        Response postWithHeaders(String path, String body, String csrf, Map<String, String> headers)
                throws Exception {
            return send("POST", path, body, csrf, headers);
        }

        private Response send(String method, String path, String body, String csrf) throws Exception {
            return send(method, path, body, csrf, Map.of());
        }

        private Response send(String method, String path, String body, String csrf, Map<String, String> headers)
                throws Exception {
            String trace = UUID.randomUUID().toString();
            HttpRequest.Builder request = HttpRequest.newBuilder(origin.resolve(path))
                    .timeout(Duration.ofSeconds(15)).header(TRACE_HEADER, trace);
            headers.forEach(request::header);
            if (csrf != null) request.header("X-XSRF-TOKEN", csrf);
            if (body == null) request.method(method, HttpRequest.BodyPublishers.noBody());
            else request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            return new Response(trace, client.send(request.build(), HttpResponse.BodyHandlers.ofString()));
        }

        Optional<HttpCookie> cookie(String name) {
            return cookies.getCookieStore().getCookies().stream()
                    .filter(cookie -> cookie.getName().equals(name) && !cookie.hasExpired()).findFirst();
        }

        String csrf() {
            return cookie("XSRF-TOKEN").orElseThrow(() ->
                    new IllegalStateException("Expected the real CSRF bootstrap cookie")).getValue();
        }

        @Override
        public void close() {
            client.close();
            cookies.getCookieStore().removeAll();
        }
    }

    record Response(String trace, HttpResponse<String> http) {
        int status() { return http.statusCode(); }
        String body() { return http.body(); }
    }
}

package com.mightyoung.softwarefactory;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.ConnectionProxy;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/** Transparent test observer; it neither supplies authority nor changes SQL, bindings or rows. */
final class G013ObservedDataSource extends DelegatingDataSource implements AutoCloseable {
    static final String DEFINITION_PATH = "/api/v1/entities/finding/definition";
    static final String REFERENCE_PATH = "/api/v1/entities/finding/references/report-organization/query";
    private static final String METADATA_SQL = """
            select pg_backend_pid() pid, transaction_timestamp() transaction_at,
             current_setting('transaction_isolation') isolation,
             current_setting('transaction_read_only') readonly,
             nullif(current_setting('app.tenant_id',true),'') tenant
            """;
    private final DataSource actual;
    private final Map<UUID, Capture> captures = new ConcurrentHashMap<>();
    private final ThreadLocal<Capture> current = new ThreadLocal<>();
    private final ThreadLocal<Boolean> metadataRead = ThreadLocal.withInitial(() -> false);
    private final AtomicReference<Barrier> armed = new AtomicReference<>();

    G013ObservedDataSource(DataSource actual) { super(actual); this.actual = actual; }

    Filter requestScope() {
        return (request, response, chain) -> {
            var http = (HttpServletRequest) request;
            String path = http.getRequestURI();
            String trace = http.getHeader(IdentityAcceptanceHttps.TRACE_HEADER);
            if ((!DEFINITION_PATH.equals(path) && !REFERENCE_PATH.equals(path)) || trace == null) {
                chain.doFilter(request, response); return;
            }
            UUID id;
            try { id = UUID.fromString(trace); }
            catch (IllegalArgumentException malformed) { throw new IllegalStateException("Invalid owned test trace"); }
            if (current.get() != null) throw new IllegalStateException("Nested observed request");
            Capture capture = new Capture(REFERENCE_PATH.equals(path) && "POST".equals(http.getMethod()));
            if (captures.putIfAbsent(id, capture) != null) throw new IllegalStateException("Duplicate owned test trace");
            current.set(capture);
            try { chain.doFilter(request, response); }
            finally { current.remove(); metadataRead.remove(); }
        };
    }

    List<Event> events(String trace) {
        Capture capture = captures.get(UUID.fromString(trace));
        return capture == null ? List.of() : List.copyOf(capture.events);
    }
    void clear() {
        if (armed.get() != null) throw new IllegalStateException("A reference barrier is still armed");
        captures.clear();
    }
    Barrier armCandidate(UUID ownedTenant) {
        var barrier = new Barrier(ownedTenant);
        if (!armed.compareAndSet(null, barrier)) throw new IllegalStateException("Only one owned barrier may be armed");
        return barrier;
    }

    @Override public Connection getConnection() throws SQLException { return observe(actual.getConnection()); }
    @Override public Connection getConnection(String username, String password) throws SQLException {
        return observe(actual.getConnection(username, password));
    }
    private Connection observe(Connection delegate) {
        return (Connection) Proxy.newProxyInstance(ConnectionProxy.class.getClassLoader(),
                new Class<?>[]{ConnectionProxy.class}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
                    if (method.getName().equals("getTargetConnection")) return delegate;
                    Object result = invoke(delegate, method, args);
                    if (List.of("commit", "rollback", "close").contains(method.getName())
                            || (method.getName().equals("setAutoCommit") && Boolean.TRUE.equals(args[0]))) {
                        Capture capture = current.get();
                        if (capture != null && capture.ownerConnection == delegate) {
                            capture.ownerSeen = false;
                            capture.ownerConnection = null;
                        }
                    }
                    if (method.getName().equals("prepareStatement") && result instanceof PreparedStatement statement) {
                        return observeStatement(delegate, (Connection) proxy, statement, (String) args[0], true);
                    }
                    if (method.getName().equals("createStatement") && result instanceof Statement statement) {
                        return observeStatement(delegate, (Connection) proxy, statement, null, false);
                    }
                    return result;
                });
    }
    private Statement observeStatement(Connection real, Connection observed, Statement delegate, String preparedSql,
            boolean prepared) {
        Class<?> contract = prepared ? PreparedStatement.class : Statement.class;
        return (Statement) Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
                    if (method.getName().equals("getConnection")) return observed;
                    String name = method.getName();
                    if (!List.of("execute", "executeQuery", "executeUpdate", "executeLargeUpdate").contains(name)
                            || current.get() == null || metadataRead.get()) return invoke(delegate, method, args);
                    String sql = preparedSql;
                    if (!prepared && args != null && args.length > 0 && args[0] instanceof String value) sql = value;
                    Label label = classify(sql);
                    if (label == null) return invoke(delegate, method, args);
                    Capture capture = current.get();
                    State state = state(real);
                    Phase phase = capture.ownerSeen ? Phase.OWNER_RO_RR : Phase.PREFILTER_OR_CONTEXT;
                    capture.add(new Event(label, Stage.ATTEMPT, phase, state));
                    if (label == Label.CANDIDATE) pauseOwnedCandidate(capture, state);
                    Object result = invoke(delegate, method, args);
                    if (label == Label.OWNER_BOUNDARY && state.ownerRead()) {
                        capture.ownerSeen = true;
                        capture.ownerConnection = real;
                        phase = Phase.OWNER_RO_RR;
                    }
                    capture.add(new Event(label, Stage.SUCCESS, phase, state));
                    return result;
                });
    }
    private State state(Connection connection) throws SQLException {
        if (metadataRead.get()) throw new IllegalStateException("Recursive SQL observation");
        metadataRead.set(true);
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(METADATA_SQL)) {
            if (!rows.next()) throw new IllegalStateException("Missing actual connection metadata");
            var result = new State(rows.getInt("pid"), rows.getObject("transaction_at", OffsetDateTime.class).toInstant(),
                    rows.getString("isolation"), rows.getString("readonly"), rows.getString("tenant"));
            if (rows.next()) throw new IllegalStateException("Ambiguous connection metadata");
            return result;
        } finally { metadataRead.set(false); }
    }
    private void pauseOwnedCandidate(Capture capture, State state) throws InterruptedException {
        Barrier barrier = armed.get();
        if (barrier == null) return;
        if (!capture.referencePost || !state.ownerRead() || !barrier.tenant.toString().equals(state.tenant())) {
            throw new IllegalStateException("Reference barrier cannot intercept a different source");
        }
        if (!armed.compareAndSet(barrier, null)) throw new IllegalStateException("Reference barrier was reused");
        barrier.reached.countDown();
        if (!barrier.release.await(1500, TimeUnit.MILLISECONDS)) {
            throw new AssertionError("Owned writer did not release the reference barrier within the transaction budget");
        }
    }
    private static Label classify(String input) {
        if (input == null) return null;
        String sql = input.toLowerCase(Locale.ROOT).replace("\"", "").replaceAll("\\s+", " ").strip();
        if (!sql.startsWith("select ") && !sql.startsWith("with ")) return null;
        if (sql.contains("statement_timeout_valid") && sql.contains("lock_timeout_valid")) return Label.OWNER_BOUNDARY;
        if (sql.contains("from public.org_unit") && sql.contains("order by")
                && (sql.contains("limit") || sql.contains("fetch next"))) return Label.CANDIDATE;
        if (sql.contains("public.tenant_application")) return Label.APPLICATION;
        if (sql.contains("from public.account")) return Label.ACCOUNT;
        if (sql.contains("from public.principal")) return Label.PRINCIPAL;
        if (sql.contains("public.tenant_member")) return Label.MEMBERSHIP;
        if (sql.contains("public.role_grant")) return Label.ROLE;
        if (sql.contains("public.member_org") || sql.contains("public.member_position") || sql.contains("public.group_member")
                || sql.contains("public.org_unit")) return Label.DIRECTORY;
        return null;
    }
    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "OwnedG013JdbcObservation";
            default -> throw new IllegalStateException("Unexpected Object method");
        };
    }
    @Override public void close() throws Exception {
        Barrier barrier = armed.getAndSet(null);
        if (barrier != null) barrier.close();
        if (actual instanceof AutoCloseable closeable) closeable.close();
    }
    enum Label { OWNER_BOUNDARY, APPLICATION, ACCOUNT, PRINCIPAL, MEMBERSHIP, ROLE, DIRECTORY, CANDIDATE }
    enum Stage { ATTEMPT, SUCCESS }
    enum Phase { PREFILTER_OR_CONTEXT, OWNER_RO_RR }
    record State(int pid, Instant transactionAt, String isolation, String readOnly, String tenant) {
        boolean ownerRead() { return "repeatable read".equals(isolation) && "on".equals(readOnly) && tenant != null; }
    }
    record Event(Label label, Stage stage, Phase phase, State state) { }
    private static final class Capture {
        private final boolean referencePost;
        private final List<Event> events = new CopyOnWriteArrayList<>();
        private boolean ownerSeen;
        private Connection ownerConnection;
        Capture(boolean referencePost) { this.referencePost = referencePost; }
        void add(Event event) {
            if (events.size() >= 4096) throw new IllegalStateException("Owned SQL observation limit exceeded");
            events.add(event);
        }
    }
    final class Barrier implements AutoCloseable {
        private final UUID tenant;
        private final CountDownLatch reached = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private Barrier(UUID tenant) { this.tenant = tenant; }
        boolean awaitReached() throws InterruptedException { return reached.await(2, TimeUnit.SECONDS); }
        @Override public void close() { armed.compareAndSet(this, null); release.countDown(); }
    }
}

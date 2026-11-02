# P0 database target guard — 2026-11-02

Base: `845f191` (`origin/dev`). This batch is independent of the transaction retry probes in PR #17. It changes the LAN integration input gate and its offline CI coverage, without changing production schemas or database grants.

## Corrected behavior

pgJDBC URL query properties take precedence over separately supplied connection properties. In the selected 42.7.14 driver, query properties can also replace the host, port, and database parsed from the visible URL. An exact allowlist containing such a URL therefore does not ensure that the driver connects to the endpoint inspected by the URI-based gate. The subsequent database/role checks do not prevent that initial connection attempt.

The gate now rejects every query string and fragment before opening a connection, including empty `?`/`#` suffixes. The supported input is a plain `jdbc:postgresql://host:port/database` endpoint. This deliberately also rejects benign-looking options such as `sslmode` or `currentSchema`; adding transport options later requires a separately reviewed property contract. There is no automatic removal of parameters or downgrade to a different connection mode.

Both passwords must now be present before either role is inspected. A missing application secret fails locally before the migration identity connects.

Regression coverage uses documentation-only IP addresses and includes host/database/identity overrides, aliases, session/connection options, fragments, and missing configuration. A parser-only probe exercises the actual pgJDBC runtime dependency with synthetic credentials and verifies the override behavior without opening a connection.

References: [pgJDBC connection documentation](https://jdbc.postgresql.org/documentation/use/), [42.7.14 Driver parser](https://github.com/pgjdbc/pgjdbc/blob/REL42.7.14/pgjdbc/src/main/java/org/postgresql/Driver.java).

## CI and acceptance boundary

Backend CI retains the default clean verification and additionally runs:

```sh
./mvnw --batch-mode --no-transfer-progress -Plan-postgres-compatibility -DskipITs verify
```

This compiles the LAN integration sources and runs their ordinary `*Test` input/policy regressions. Failsafe database-backed `*IT` tests are explicitly skipped. No LAN credentials are supplied by the workflow.

Live PostgreSQL acceptance remains NOT RUN for this batch: the current strict migration/application identities, approved provisioning, and exclusive executor target have not been established. Offline test or CI success does not complete P0 database/transaction acceptance.

## Fresh local evidence

- `clean verify`: PASS, 211 tests, zero failures/errors/skips, JAR packaged.
- Profile command above: PASS, 226 non-database tests, zero failures/errors/skips; integration sources compiled and Failsafe explicitly reported skipped execution.
- Target guard class: 9 tests, including the actual driver parser probe.
- `git diff --check`: PASS.

Environment: JBR 25.0.3, Java release target 21. Local Maven runs used the existing dependency cache (`-o`) and `SERVER_ADDRESS=127.0.0.1` for the existing temporary startup-test listener. Remote JDK21 results must be checked at the submitted PR head.

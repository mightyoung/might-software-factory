# Temporary PostgreSQL 18 capture acceptance

This job creates a dedicated PostgreSQL container on the hosted runner and exercises the original application migrations and capture core. It does not connect to NAS, install vendor schemas, enable a listener, or establish historical V9 upgrade acceptance.

The dedicated Maven profile selects one integration class and the strict CI target tests. Both Maven and the harness reject missing tests. The harness requires all nine named integration scenarios, nonzero target tests and zero failures/errors/skips. A successful Maven exit alone does not pass acceptance.

The harness:
- rejects externally supplied CI database and LAN/tunnel settings;
- pulls the hardcoded official postgres:18-bookworm tag, resolves its immutable digest, and runs only that digest for this job on linux/amd64;
- requires Docker Engine 28 or later, a single 127.0.0.1 random-port mapping, a new server-instance marker and matching job ownership labels;
- gives Java a fixed database name and plain JDBC URL, with separate generated credentials in runner-private files;
- records original migration checksums and only the approved database/schema/privilege evidence;
- removes its exact owned container and anonymous volume, verifies absence and deletes credentials and private process output.

The digest is resolved and recorded for each run; this is not a claim that a mutable tag has a permanent digest. The job never executes the tag after resolution. A missing ownership record or unknown anonymous volume state produces a failed or unverified cleanup result.

Before any database credentials or container exist, the workflow runs the dedicated profile through the test lifecycle to expose compilation and offline target failures.

Raw Maven/Flyway output is captured in a private temporary file because failed migrations may echo SQL. It is never printed or uploaded. Only artifacts/ci-pg18-risk-capture/result.json is uploaded. The fixture's optional failure evidence contains fixed phase/version/SQLState fields only. Container logs, complete inspect output, raw JUnit XML, environment files and dumps are excluded.

The workflow runs the pure guard suite first:

    python3 -B -m unittest discover -s tools/ci_pg18 -p 'test_*.py' -v

Container creation is available only in the expected GitHub Actions repository context:

    python3 -B tools/ci_pg18/risk_capture.py run

Maven runs in an exclusive session. Forked JVMs must carry this run’s process marker before the harness can signal the exact group; timeouts terminate and wait before database cleanup. Unproven process ownership prevents destructive cleanup and keeps the result failed. Maven failure diagnostics contain only counts and the nine reviewed case states.

The script uses try/finally, and the workflow repeats the idempotent cleanup entry with if: always(). A terminated or lost runner cannot be described as verified cleanup unless the recorded result actually proves it.

Official runtime references:
- [PostgreSQL image data layout](https://hub.docker.com/_/postgres?tab=description)
- [Docker port publishing](https://docs.docker.com/engine/network/port-publishing/)
- [Docker immutable digest pulls](https://docs.docker.com/reference/cli/docker/image/pull/#pull-an-image-by-digest-immutable-identifier)
- [Docker anonymous-volume removal](https://docs.docker.com/reference/cli/docker/container/rm/#remove-a-container-and-its-volumes--v---volumes)
- [GitHub masking](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-commands#masking-a-value-in-a-log)

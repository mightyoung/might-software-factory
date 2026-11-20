# Cerbos data authorization verification

This directory exercises a real Cerbos 0.57.0 server over HTTP. Its ledger policy
grants supervisors department reads, registrars owner reads and edits, and
collaborators temporary access to specified record IDs. Every rule checks the
tenant. Quantity and price read permissions are separate actions so that field
access can vary by row.

Start a Cerbos binary installed from the official 0.57.0 release, from the
repository root. Bind only loopback for the local check.

```sh
cerbos compile tools/dual-auth/data/policies
cerbos server --set=server.httpListenAddr=127.0.0.1:43592 --set=server.grpcListenAddr=127.0.0.1:43593 --set=storage.driver=disk --set=storage.disk.directory=tools/dual-auth/data/policies
```

In another terminal, invoke the standard-library Python runner.

```sh
python3 tools/dual-auth/data/verify.py --url http://127.0.0.1:43592 --output /tmp/cerbos-decisions.json
```

For CI, `run-ci.sh` downloads Cerbos 0.57.0 from its official release, verifies
the pinned SHA256, and starts a temporary loopback server on dynamic ports.
Linux x86_64 and Darwin arm64 are supported. Set `AUTHZ_PLAN_FILE` to the output
file. Optional command arguments run while the server is alive and receive
`CERBOS_URL`. The runner returns that command's status and cleans up its server
and temporary directory even on failure.

```sh
AUTHZ_PLAN_FILE=/tmp/cerbos-decisions.json tools/dual-auth/data/run-ci.sh
AUTHZ_PLAN_FILE=/tmp/cerbos-decisions.json tools/dual-auth/data/run-ci.sh ./mvnw -f tools/dual-auth/query/pom.xml test
```

The runner makes real `CheckResources` and `PlanResources` calls for 14 baseline cases and two separate nullable-attribute cases.
It checks the decisions against explicit expected record IDs, then evaluates
the returned plans over the same records. Unknown plan operators fail the run.
The output separates baseline `records`/`cases` from `nullableRecords`/`nullableCases`. Each case contains its action, the
unmodified `filter`, and `expectedIds` obtained from individual engine decisions.
The raw check and plan responses are included for inspection. The SQL harness
can consume this file to verify database filtering, ordering, paging and counts.
Map the policy attribute `record_id` to the record's `id` column.

The nullable fixture keeps tenant and id required. It sends otherwise valid records
with department/owner omitted and with each explicitly null, plus matching
department/owner positive controls. A missing individual engine decision is not
a denial. The JDBC fixture binds both missing and explicit null as SQL NULL and
compares the exact IDs, count, and one-row pages with the individual engine
decisions. Unsupported plan operands or literal null are not translated; this
does not establish null-aware authorization for an application's schema or add
a null operator to the permitted plan subset.

The revoke cases supply refreshed principal data after removing the temporary
record grant. They prove that Cerbos uses those current facts. Application
transaction ordering, persisted grants, concurrent revocation, policy refresh,
and production field projection require separate integration checks. A caller
must supply authenticated principal roles and grant facts. Field checks here
cover read access to quantity and price.

The initial local run used the official Darwin arm64 archive, verified against
its release SHA256 file, and passed all 14 cases. The PostgreSQL harness verifies
the plans independently. No binary, server log, or generated decision output is
stored in this directory.

Sources are the [official release](https://github.com/cerbos/cerbos/releases/tag/v0.57.0),
[CheckResources and PlanResources API](https://docs.cerbos.dev/cerbos/latest/api/),
and [field authorization recipe](https://docs.cerbos.dev/cerbos/latest/recipes/field-level-security.html).

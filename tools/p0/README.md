# Dependency and license evidence inventory

`dependency_inventory.py` uses only Python's standard library. It inventories an
explicit **resolved Maven dependency-plugin JSON tree** and reconciles it with a
freshly packaged Spring Boot JAR. It does not scan the whole Maven cache, resolve
versions from raw POMs, install packages, contact a server, or extract JAR members.

This custom evidence format is **not SPDX or CycloneDX**. A complete inventory is
not a license approval. The project's own license, JDK/build-plugin inventory,
vulnerability analysis and distribution obligations require separate evidence.

## Generate and inspect

Use one controlled source/build snapshot. Supply the JSON tree for the **backend
module**, including test dependencies, from Maven dependency-plugin `tree` with
`outputType=json`. Duplicate keys at any depth and nonstandard NaN/Infinity
constants are rejected. Do not supply verbose omitted/conflict nodes, an aggregator
tree instead of the backend, or a hand-written approximation. Keep the exact
plugin version and invocation in the execution record. Creating that tree and
building the Boot JAR remain the build owner's serialized tasks; this script does
not invoke Maven.

```sh
python3 tools/p0/dependency_inventory.py \
  --tree /controlled-run/backend-dependencies.json \
  --boot-jar /controlled-run/backend.jar \
  --repository /path/to/maven/repository \
  --output /controlled-run/dependency-license-evidence.json
python3 -m unittest discover -s tools/p0 -p 'test_*.py'
```

The report records the input SHA256 values, resolved coordinates/scopes,
direct/transitive appearances, dependency edges, local artifact and exact POM
hashes, raw POM license declarations, and hashes/sizes of JAR LICENSE, NOTICE and
COPYING entries. For JAR manifests it retains the original digest/size and the
safe main-section fields `Manifest-Version` and `Spring-Boot-Jar-Type`. Header
names are case-insensitive; continuation bytes are joined before UTF-8 decoding;
duplicate main headers and orphan continuations are rejected. Named sections
cannot set the whole archive's type. Paths are Maven-relative or archive-relative;
local absolute input paths and timestamps are not emitted. Reusing unchanged
input bytes produces deterministic JSON.

Boot `BOOT-INF/lib` entries match runtime artifacts **by bytes**, not by their
filenames. Missing cache artifacts/POMs, unmatched or duplicate packaged entries,
ambiguous identical bytes under different sources, and required runtime JARs
missing from the package produce `INCOMPLETE` and exit code **2**. The incomplete
report is retained. Invalid, unavailable or unsafe input also exits 2 with a
generic diagnostic; it does not publish a new report. Only exact coverage exits 0.

Runtime reconciliation covers Maven `compile` and `runtime` JAR-like types using
this project's verified Spring Boot 4.1.2 default JarTypeFilter policy. The exact
main-manifest values `annotation-processor`, `dependencies-starter`, and
`development-tool` mark artifacts `BUILD_TOOL_EXCLUDED`; their coordinates,
hashes, licenses and manifest evidence remain in the dependency inventory.
Unknown values, different case or trailing spaces do not excuse missing jars.
The policy is recorded in the report; this is not filename-based filtering.

Boot can also inject a tool library from its build plugin. When that is part of
the controlled build, explicitly supply its actual host and embedded entry:

```sh
python3 tools/p0/dependency_inventory.py \
  --tree /controlled-run/backend-dependencies.json \
  --boot-jar /controlled-run/backend.jar \
  --repository /path/to/maven/repository \
  --embedded-packaging-library 'org.springframework.boot:spring-boot-loader-tools:jar:4.1.2!META-INF/jarmode/spring-boot-jarmode-tools.jar' \
  --output /controlled-run/dependency-license-evidence.json
```

Only one explicit embedded source is supported; repeated flags are rejected.
`packagingEvidence` separately records the exact host JAR/POM/notice evidence and
bounded embedded-entry digest, without extraction. It creates no fake project
dependency or graph edge. A matching packaged library has origin
`EMBEDDED_PACKAGING_LIBRARY` and a null coordinate: no standalone Maven artifact
is invented. Missing hosts/entries, byte mismatch, duplicate packaged copies and
ambiguity with a resolved dependency fail coverage. Undeclared extra libraries
remain errors. The explicit source is included in deterministic input hashing;
its host and embedded byte hashes remain in the proof. The resolved dependency
license UNKNOWN list excludes this separate host; inspect its own `hostPom` too.

If a package includes `provided`/`system`, custom packaging, shaded or
transformed dependency bytes, it fails coverage rather than guessing. Investigate
and explicitly extend the supported build contract before accepting such a run.
Metadata and all dependency scopes remain in the inventory independently of this
runtime reconciliation rule.

License declarations are marked `DECLARED` only when the exact artifact POM has a
nonempty name or URL without unresolved properties. This is an unverified raw
declaration, not an SPDX identifier or legal conclusion. Missing declarations,
possible inheritance and unresolved properties stay **UNKNOWN**, even when a
LICENSE/NOTICE file exists. Parent POM inheritance is deliberately not guessed.
Unknowns do not hide or invalidate otherwise exact artifact coverage; consumers
must separately decide whether unresolved license evidence blocks distribution.

Input bounds: JSON/POM documents 16 MiB; at most 10,000 tree nodes and depth 128.
Before constructing `ZipFile`, a bounded EOCD tail-window check rejects ZIP64,
multi-disk archives, declared entry counts over 20,000, central directories over
16 MiB, and archive files over 2 GiB. The same file descriptor then goes to the
standard library for format validation; the actual parsed entry count is also
checked. The last EOCD signature must have a complete record and comment ending
exactly at EOF; preflight never falls back to an earlier record that differs from
the standard library's selection. Unsupported archive versions, compression,
encrypted evidence, and corrupt DEFLATE/LZMA streams are rejected with the
generic exit-2 diagnostic, without overwriting an existing report.
Manifest is limited to 1 MiB; each license entry 8 MiB and aggregate
32 MiB per JAR; each nested library 256 MiB, aggregate 2 GiB in the Boot JAR.
Artifact hashing is limited to files of 2 GiB. POM DTD/entities and unsafe archive
names are rejected. Repeated safe directory markers are allowed; duplicate
files remain ambiguous and are rejected. No archive
member is extracted or executed. Use controlled build artifacts and keep failed
logs and source/build evidence with the successful record.

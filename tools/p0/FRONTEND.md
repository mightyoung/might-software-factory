# Frontend dependency evidence

`frontend_inventory.py` uses Python's standard library to inspect an explicit npm
v3 lockfile and its matching manifest. It never installs, executes package scripts,
fetches tarballs, extracts files, or scans an entire cache. Its deterministic custom
JSON is evidence, not SPDX/CycloneDX or a license clearance.

Implementation plan: validate the explicit input graph first; retain all platforms
and raw declarations; optionally inspect only exact installed package paths and
root notice files; verify safety, missing evidence, and determinism with focused
fixtures, then compare actual inputs with npm's separate standard SBOM.

```sh
python3 tools/p0/frontend_inventory.py \
  --lock frontend/package-lock.json \
  --manifest frontend/package.json \
  --installed-root /explicit/original/frontend/node_modules \
  --output /fresh-run/frontend-inventory.json
python3 -m unittest discover -s tools/p0 -p test_frontend_inventory.py
```

Use the corresponding lock, manifest and explicit original `node_modules` root
for `frontend/tools/api-types`. The installed root is optional; omission produces
`NOT_REQUESTED`, missing exact package directories produce `ABSENT`, and mismatched
installed package.json name/version rejects the input. Missing optional platform
packages remain in the lock inventory. No other version or directory is substituted.
Use controlled, immutable inputs; this is not protection against concurrent filesystem
replacement by another process.

The root manifest and lock must also agree on peerDependenciesMeta (absent means
`{}`). Both inputs must have object metadata and strictly boolean optional flags;
validation precedes installed evidence reads, so a drifting lock cannot silently
turn a required root peer into an optional declaration.

Inputs have SHA256 hashes. Package identities retain the lock-relative installation
path; metadata retains dev/optional/peer/devOptional flags, OS/CPU/libc restrictions,
peer metadata, engines and install-script declarations. Declared edges retain their
kind and a nearest ancestor `node_modules` path lookup, including unresolved peers
as `UNKNOWN`. Each edge has `declarationOptional`, derived from optionalDependencies
or the source peerDependenciesMeta optional flag; required peers and ordinary
dependencies remain distinct. Missing optional peers are still unresolved evidence.
This lookup does not implement semver satisfaction, an install plan,
or a complete npm resolver. Root edge targets are labelled direct; other entries
are transitive. Links/workspaces and non-node_modules paths are unsupported.

License values are retained raw with `DECLARED` only for a nonempty string;
other values remain `UNKNOWN`. Neither SPDX normalization nor legal interpretation
occurs. Integrity strings remain raw `DECLARED_NOT_VERIFIED`: no downloaded or
installed tarball is verified. Installed package.json has its own hash and raw
license field; the script does not override a lock declaration with it.

Installed notice inspection covers only regular root files whose names start with
LICENSE, LICENCE, COPYING or NOTICE (case insensitive). Each has a size and SHA256;
the sorted root entry-name list also has a hash. No recursive license search occurs.
No matching root file means `UNKNOWN` / `ROOT_NOTICE_NOT_FOUND`; a recorded file
means `ROOT_FILES_RECORDED`, never complete obligation coverage. README statements,
source headers, nested distributions and dependency notices require separate review.
The project's own license remains `UNDECIDED`.

Bounds: each JSON document 16 MiB, 10,000 lock entries, package path 4096 characters,
4096 entries per installed root directory, 8 MiB per notice and 32 MiB of notices
per package. Duplicate JSON keys, NaN/Infinity, traversal paths, linked lock entries,
nonregular input files and symlinks along installed package/notice paths are rejected.
No resolved download URLs, absolute input paths or timestamps are emitted. Nonregistry
dependency specifications are replaced with a marker plus the original specification's
hash; retained declaration fields with credential URLs or absolute paths are rejected.
The CLI uses exclusive output creation: an existing report is never overwritten.
Invalid or unavailable input exits 2 with a generic diagnostic; accepted evidence
exits 0, which does not imply installation completeness or legal approval.

For a separate standard SBOM, existing npm 11.18.0 supports this offline virtual-tree
command (choose a fresh output and temporary cache/log directory):

```sh
npm --prefix frontend sbom --package-lock-only \
  --sbom-format=cyclonedx --sbom-type=application \
  --include=dev --include=optional --include=peer \
  --offline --ignore-scripts --update-notifier=false \
  --cache=/tmp/fresh-run/npm-cache --logs-dir=/tmp/fresh-run/npm-logs \
  > /tmp/fresh-run/frontend.cdx.json
```

Repeat with `--prefix frontend/tools/api-types`. This is CycloneDX 1.5, lifecycle
`pre-build`, including platform-specific lock packages absent on the host. Its UUID
and timestamp vary: compare semantic component identities and dependency relations,
not raw file hashes. Neither this lock SBOM nor the custom inventory proves the actual
Vite bundle, runtime shipment, third-party notice completeness or full P0–P2 acceptance.

The fixed npm implementation uses `loadVirtual` for lock-only mode:
[command](https://github.com/npm/cli/blob/a9c8c06d245347da89c798ebc82fdceb221de08b/lib/commands/sbom.js#L41),
[CycloneDX output](https://github.com/npm/cli/blob/a9c8c06d245347da89c798ebc82fdceb221de08b/lib/utils/sbom-cyclonedx.js#L7-L55),
[virtual tree](https://github.com/npm/cli/blob/a9c8c06d245347da89c798ebc82fdceb221de08b/workspaces/arborist/lib/arborist/load-virtual.js#L40-L98).

# P0 frontend dependency evidence — 2026-11-03

This bounded batch adds a stdlib npm v3 lock inventory, focused tests and usage
instructions. Base commit: `edae0e6e4cc3e3764291b38a451d2d71fba149a5`;
branch: `codex/p0-frontend-evidence-20261103`. It does not establish legal clearance,
actual frontend bundle contents or full P0–P2 completion. The project license is undecided.

The implementation plan was recorded before editing: validate graph/manifest,
retain all platform entries and declarations, inspect exact original installed paths
and bounded root notices, then validate with fixtures and separate npm lock SBOMs.
No dependencies, manifests, lockfiles, backend inventory code or product code changed.

## Actual inputs and observations

| Evidence | Frontend | API-types generator |
|---|---:|---:|
| Nonroot locked packages | 149 | 33 |
| dev flag | 146 | 33 |
| optional flag | 47 | 0 |
| Without dev flag | 3 | 0 |
| Exact installed metadata matches | 106 | 33 |
| Exact installed package absent | 43 | 0 |
| Linux-only entries retained | 20 | 0 |
| Declared edges | 220 | 34 |
| Unresolved declared optional peer edges | 28 | 0 |
| Unresolved required peer or ordinary dependency edges | 0 | 0 |
| CycloneDX nonroot components | 149 | 33 |
| CycloneDX dependency nodes including root | 150 | 34 |

All 28 unresolved frontend edges are declared optional peers, identified from each
source package peerDependenciesMeta. They remain UNKNOWN path resolutions. No
required peer or ordinary dependency edge is unresolved in this limited lookup;
this is not a semver check or complete npm peer solver. The separate standard SBOM
uses npm Arborist's virtual tree for its actual resolved graph.

All locked package license and integrity fields are declarations; integrity was not
verified against tarballs. The original installation roots were supplied explicitly:
`/Users/muyi/Downloads/dev/might-software-factory/frontend/node_modules` and its
`frontend/tools/api-types/node_modules` counterpart. They were read only. No install,
package script, Maven, Docker, network fetch or cache-wide scan was performed.
The 43 absent frontend packages remain in the graph, with optional platform metadata.
Their absence is not evidence that license obligations are absent.

The exact installed package.json files match each locked name/version. Root notice
inspection leaves these four packages explicitly UNKNOWN:

- Frontend: `@rolldown/binding-darwin-arm64`, `saxes`.
- API-types: `@redocly/openapi-core`, `change-case`.

Targeted root listings confirm no LICENSE/LICENCE/COPYING/NOTICE files for those
four. Their package.json and root-entry-list hashes remain in the evidence. This
bounded observation makes no statement about README terms, nested notices, source
headers, upstream terms or all distribution obligations. `decimal.js` and `std-env`
have British-spelled LICENCE files, which are included.

## Validation and artifacts

`python3 -m unittest discover -s tools/p0 -p test_frontend_inventory.py`: 12 tests
passed. Cases cover exact-version mismatch, optional absence, direct/transitive
edges, nested lookup, unknown peers/licenses, raw integrity, duplicate/nonfinite and
oversized JSON, manifest drift, traversal, links, symlink escape, notice byte and
entry bounds, deterministic output (including byte-identical actual report regeneration),
optional versus required peer declarations, privacy, no subprocess invocation and preserving
an existing output. No build is needed for a stdlib evidence script; product build
or device/browser/runtime acceptance was not exercised in this batch.

Fresh output directory: `/tmp/msf-p0-frontend-evidence-20261103/newfiles`.
Final accepted custom reports are `frontend-inventory-reviewed.json` and
`api-types-inventory-reviewed.json`. Initial and `-final` filenames are earlier outputs retained without overwrite;
use the `-reviewed` reports for LICENCE scope and explicit edge optionality.
Standard reports are `frontend.cdx.json` and `api-types.cdx.json`; their `-repeat`
counterparts preserve separate executions.

Existing runtime: Node `v22.22.0`, npm `11.18.0`. Both npm invocations used exactly
the command in [FRONTEND.md](../../tools/p0/FRONTEND.md), with the relevant prefix
and cache/log directories under the fresh output directory. Both exited 0, emitted
CycloneDX 1.5 `pre-build` lifecycle and retained all lock identities. A second run
matched each first run's root component, complete component array and dependency
array exactly. Custom inventory and SBOM package name/version multisets also match.
UUID/timestamp changes are expected; SBOM bytes are not claimed deterministic.

| Input | SHA256 |
|---|---|
| frontend/package.json | `e4ba9df929638dcf8bcde50e9a7760a4d06e3d74407f60e59d4c02957e9f1395` |
| frontend/package-lock.json | `89b0702b7ad327f25ee71283bdf9769951318a6251e42c78d8059ebfe1469aa7` |
| frontend/tools/api-types/package.json | `b6b187dd8bafd3048f6f07ee9800533833ce4e200fa3e9dab9748e4fcc95f282` |
| frontend/tools/api-types/package-lock.json | `14f813b42af5d765d78e6710d7951de27d2da48703eb90038a70fefcb7ea1ef1` |

| Artifact | SHA256 |
|---|---|
| frontend-inventory-reviewed.json | `72526dda37a70150d4bc9b6c9b2d506d27e0f658bab9e47bd0735745a406f920` |
| api-types-inventory-reviewed.json | `594e3d8b67cd6f677ceb83507f4d49ba4b9721d7eacec43e57c100d73ae8123c` |
| frontend.cdx.json | `89bde6a2bc0103089e9c0810cf1331f425a34e4c1479b759a01ab83ede0e7c3a` |
| api-types.cdx.json | `55a55644fbb684386286ee86335208f4a072c006688fe417ed339a9c62a09dfc` |
| frontend-repeat.cdx.json | `837f06b320b9ba00bc6b628220d9d36305ddffec6a4e4cf9c3c3741fe71e7322` |
| api-types-repeat.cdx.json | `c26cc5d25c26765fbd6463d747e6cfda2fac11a150e6ce0959dbea32f5125ce8` |

The peer-owned backend inventory remained SHA256
`ddb127b785fce87a3dad4e3ac3b0dd186116ceee5a42664d912e974ac1ba0f98`.
This report freezes only bounded local evidence. Release/legal decisions and the
remaining P0–P2 gates stay with their respective owners.


## Root peer metadata review correction

Review reproduced a manifest/lock drift where the root manifest declared an
optional flag false and the lock declared it true. The initial drift fixture failed
as expected (`InventoryError not raised`). The correction validates both root
peerDependenciesMeta objects, strictly requires boolean optional flags, and compares
them with absent metadata defaulting to `{}` before reading installed evidence.
The shared metadata validator is also used for transitive declaration edges.
Fixtures cover drift, malformed containers/entries and nonboolean flags in either
input, including Python's `False == 0` equality trap. All 12 focused tests now pass.

Separate evidence logs: `root-peer-metadata-red.log` (12 tests, six failing assertions
before the fix) and `root-peer-metadata-green.log` (12 tests passing) under the same
fresh output directory. Final real reports are regenerated as
`frontend-inventory-root-peer-fixed.json` and `api-types-inventory-root-peer-fixed.json`
without changing the existing reviewed reports. Both are byte-identical to their
reviewed predecessors, so the input and report SHA256 values above remain valid.
No SBOM re-execution was necessary: lock and manifest bytes remain unchanged.
Source/log/artifact hashes are separately frozen in `root-peer-fixed-freeze.json`.

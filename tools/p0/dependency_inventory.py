#!/usr/bin/env python3
"""Evidence inventory from a resolved Maven tree and its packaged Boot JAR.

This is neither an SPDX/CycloneDX document nor a legal license assessment.
Only the supplied resolved tree is inventoried; the Maven cache is not scanned.
"""

import argparse
from contextlib import contextmanager
import hashlib
import json
import lzma
import os
from pathlib import Path, PurePosixPath
import re
import struct
import sys
import xml.etree.ElementTree as ET
import zipfile
import zlib


MAX_DOCUMENT_BYTES = 16 * 1024 * 1024
MAX_LICENSE_BYTES = 8 * 1024 * 1024
MAX_LICENSE_TOTAL = 32 * 1024 * 1024
MAX_ENTRIES = 20_000
MAX_NESTED_JAR_BYTES = 256 * 1024 * 1024
MAX_MANIFEST_BYTES = 1024 * 1024
MAX_ARTIFACT_BYTES = 2 * 1024 * 1024 * 1024
MAX_CENTRAL_DIRECTORY_BYTES = 16 * 1024 * 1024
TOKEN = re.compile(r"[A-Za-z0-9_+.-]+\Z")
RUNTIME_SCOPES = {"compile", "runtime"}
JAR_TYPES = {"jar", "bundle", "test-jar", "maven-plugin"}
BUILD_TOOL_JAR_TYPES = {"annotation-processor", "dependencies-starter", "development-tool"}


class InventoryError(ValueError):
    """Invalid or unsafe evidence input; messages contain no local paths."""


def digest_file(path):
    if path.stat().st_size > MAX_ARTIFACT_BYTES:
        raise InventoryError("Artifact exceeds the size limit")
    with path.open("rb") as stream:
        return digest_stream(stream)


def digest_stream(stream, maximum=None):
    digest = hashlib.sha256()
    size = 0
    while chunk := stream.read(64 * 1024):
        size += len(chunk)
        if maximum is not None and size > maximum:
            raise InventoryError("Archive evidence exceeds the size limit")
        digest.update(chunk)
    return digest.hexdigest()


def document(path):
    with path.open("rb") as stream:
        data = stream.read(MAX_DOCUMENT_BYTES + 1)
    if len(data) > MAX_DOCUMENT_BYTES:
        raise InventoryError("Evidence document exceeds the size limit")
    return data


def strict_json_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise InventoryError("Duplicate dependency JSON key")
        result[key] = value
    return result


def reject_json_constant(value):
    raise InventoryError("Nonstandard dependency JSON constant")


@contextmanager
def evidence_archive(path):
    # Check the bounded EOCD before ZipFile allocates its central-directory model.
    # Keep the same descriptor; callers supply controlled, immutable build inputs.
    with path.open("rb") as stream:
        size = os.fstat(stream.fileno()).st_size
        if size > MAX_ARTIFACT_BYTES:
            raise InventoryError("Archive exceeds the artifact size limit")
        tail_size = min(size, 65_535 + 22)
        stream.seek(size - tail_size)
        tail = stream.read(tail_size)
        position = tail.rfind(b"PK\x05\x06")
        # Match ZipFile's last-signature selection; never validate an earlier
        # record while the standard library will consume a later one in a comment.
        if (position < 0 or position + 22 > len(tail)
                or position + 22 + struct.unpack_from("<H", tail, position + 20)[0] != len(tail)):
            raise InventoryError("Archive has no supported bounded EOCD")
        _, disk, directory_disk, disk_entries, entries, directory_size, directory_offset, _ = struct.unpack_from(
            "<4s4H2LH", tail, position)
        end_offset = size - tail_size + position
        if entries == 0xffff or disk_entries == 0xffff or directory_size == 0xffffffff or directory_offset == 0xffffffff:
            raise InventoryError("ZIP64 evidence is unsupported")
        if end_offset >= 20:
            stream.seek(end_offset - 20)
            if stream.read(4) == b"PK\x06\x07":
                raise InventoryError("ZIP64 evidence is unsupported")
        if disk or directory_disk or disk_entries != entries:
            raise InventoryError("Multi-disk ZIP evidence is unsupported")
        if entries > MAX_ENTRIES or directory_size > MAX_CENTRAL_DIRECTORY_BYTES:
            raise InventoryError("Archive central-directory budget exceeded")
        if directory_offset + directory_size > end_offset:
            raise InventoryError("Archive central directory exceeds its input bounds")
        try:
            archive = zipfile.ZipFile(stream)
        except NotImplementedError as error:
            raise InventoryError("Unsupported ZIP archive version") from error
        with archive:
            yield archive


@contextmanager
def archive_member(archive, entry):
    if entry.flag_bits & 1:
        raise InventoryError("Encrypted ZIP evidence is unsupported")
    try:
        with archive.open(entry) as stream:
            yield stream
    except (NotImplementedError, zlib.error, lzma.LZMAError) as error:
        raise InventoryError("Unsupported or corrupt ZIP evidence encoding") from error


def token(value, field):
    if not isinstance(value, str) or not TOKEN.fullmatch(value) or value in {".", ".."}:
        raise InventoryError("Invalid resolved coordinate field: " + field)
    return value


def identity(node):
    if not isinstance(node, dict):
        raise InventoryError("Dependency tree nodes must be objects")
    group = token(node.get("groupId"), "groupId")
    if any(part in {"", ".", ".."} for part in group.split(".")):
        raise InventoryError("Invalid groupId segments")
    artifact = token(node.get("artifactId"), "artifactId")
    version = token(node.get("version"), "version")
    kind = token(node.get("type", "jar"), "type")
    classifier = node.get("classifier", "")
    if classifier:
        classifier = token(classifier, "classifier")
    elif classifier not in ("", None):
        raise InventoryError("Invalid classifier")
    classifier = classifier or ""
    coordinate = ":".join(part for part in (group, artifact, kind, classifier, version) if part)
    return coordinate, group, artifact, version, kind, classifier


def resolve_tree(tree):
    root = identity(tree)[0]
    dependencies = {}
    edges = set()
    visited = 0

    def visit(node, parent, depth):
        nonlocal visited
        visited += 1
        if visited > 10_000 or depth > 128:
            raise InventoryError("Dependency tree exceeds the node or depth limit")
        fields = identity(node)
        coordinate = fields[0]
        # The caller must supply the resolved tree, not verbose omitted/conflict nodes.
        if node.get("omitted") not in (None, False, "false") or node.get("omittedForConflict"):
            raise InventoryError("Omitted dependency nodes are not resolved artifacts")
        if parent is not None:
            scope = node.get("scope")
            if not isinstance(scope, str) or scope not in {"compile", "runtime", "test", "provided", "system", "import"}:
                raise InventoryError("Missing or unsupported resolved dependency scope")
            entry = dependencies.setdefault(coordinate, {"fields": fields, "scopes": set(), "kinds": set()})
            entry["scopes"].add(scope)
            entry["kinds"].add("direct" if depth == 1 else "transitive")
            edges.add((parent, coordinate))
        children = node.get("children", [])
        if not isinstance(children, list):
            raise InventoryError("Dependency children must be an array")
        for child in children:
            visit(child, coordinate, depth + 1)

    visit(tree, None, 0)
    return root, dependencies, edges


def pom_evidence(path, relative_path):
    raw = document(path)
    # ElementTree has no external resolver; reject all DTD/entity declarations as well.
    upper = raw.upper()
    if b"<!DOCTYPE" in upper or b"<!ENTITY" in upper or b"\x00" in raw:
        raise InventoryError("POM DTD, entity declarations or non-UTF8-compatible XML are unsupported")
    project = ET.fromstring(raw)
    if project.tag.rsplit("}", 1)[-1] != "project":
        raise InventoryError("POM root must be project")
    namespace = project.tag.removesuffix("project")
    declared = []
    for license_element in project.findall(namespace + "licenses/" + namespace + "license"):
        license_fields = {}
        for field in ("name", "url", "distribution", "comments"):
            value = license_element.findtext(namespace + field)
            if value is not None:
                if len(value) > 16_384:
                    raise InventoryError("POM license declaration exceeds the size limit")
                license_fields[field] = value.strip()
        declared.append(license_fields)
    known = bool(declared) and all(item.get("name") or item.get("url") for item in declared)
    unresolved = any("${" in value for item in declared for value in item.values())
    return {
        "source": relative_path,
        "sha256": hashlib.sha256(raw).hexdigest(),
        "licenseStatus": "DECLARED" if known and not unresolved else "UNKNOWN",
        "unknownReason": None if known and not unresolved else (
            "UNRESOLVED_DECLARATION" if unresolved else "ABSENT_OR_INHERITED_NOT_RESOLVED"),
        "declaredLicenses": declared,
    }


def license_notices(path):
    evidence = []
    total = 0
    with evidence_archive(path) as archive:
        entries = archive.infolist()
        if len(entries) > MAX_ENTRIES:
            raise InventoryError("JAR entry count exceeds the limit")
        names = set()
        for entry in sorted(entries, key=lambda item: item.filename):
            name = entry.filename
            member_path = PurePosixPath(name)
            if member_path.is_absolute() or ".." in member_path.parts or "\\" in name:
                raise InventoryError("Unsafe JAR member name")
            if entry.is_dir():
                continue
            if name in names:
                raise InventoryError("Duplicate JAR entries are ambiguous evidence")
            names.add(name)
            basename = PurePosixPath(name).name.upper()
            if not basename.startswith(("LICENSE", "NOTICE", "COPYING")):
                continue
            total += entry.file_size
            if entry.file_size > MAX_LICENSE_BYTES or total > MAX_LICENSE_TOTAL:
                raise InventoryError("JAR license evidence exceeds the size limit")
            with archive_member(archive, entry) as stream:
                digest = digest_stream(stream, MAX_LICENSE_BYTES)
            evidence.append({"entry": name, "size": entry.file_size, "sha256": digest})
    return evidence


def packaged_libraries(path):
    libraries = []
    total = 0
    with evidence_archive(path) as archive:
        entries = archive.infolist()
        if len(entries) > MAX_ENTRIES:
            raise InventoryError("Boot JAR entry count exceeds the limit")
        names = set()
        for entry in sorted(entries, key=lambda item: item.filename):
            if not entry.filename.startswith("BOOT-INF/lib/") or entry.is_dir():
                continue
            relative = entry.filename.removeprefix("BOOT-INF/lib/")
            if "/" in relative or "\\" in relative or not relative.endswith(".jar"):
                raise InventoryError("Unexpected Boot library entry")
            if entry.filename in names:
                raise InventoryError("Duplicate Boot library entries")
            names.add(entry.filename)
            total += entry.file_size
            if entry.file_size > MAX_NESTED_JAR_BYTES or total > 2 * 1024 * 1024 * 1024:
                raise InventoryError("Boot nested library evidence exceeds the size limit")
            with archive_member(archive, entry) as stream:
                digest = digest_stream(stream, MAX_NESTED_JAR_BYTES)
            libraries.append({"entry": entry.filename, "size": entry.file_size, "sha256": digest})
    return libraries


def manifest_evidence(path):
    """Read only the manifest main section, folding byte continuations first."""
    with evidence_archive(path) as archive:
        entries = [entry for entry in archive.infolist() if entry.filename == "META-INF/MANIFEST.MF"]
        if not entries:
            return None
        if len(entries) != 1 or entries[0].file_size > MAX_MANIFEST_BYTES:
            raise InventoryError("Missing unique bounded JAR manifest")
        with archive_member(archive, entries[0]) as stream:
            raw = stream.read(MAX_MANIFEST_BYTES + 1)
        if len(raw) > MAX_MANIFEST_BYTES or b"\x00" in raw:
            raise InventoryError("Unsafe JAR manifest")
    attributes = {}
    previous = None
    for line in raw.replace(b"\r\n", b"\n").replace(b"\r", b"\n").split(b"\n"):
        if not line:
            break  # Named entry sections cannot determine the archive's type.
        if line.startswith(b" "):
            if previous is None:
                raise InventoryError("Orphan JAR manifest continuation")
            attributes[previous] += line[1:]
            continue
        parts = line.split(b": ", 1)
        if len(parts) != 2 or not re.fullmatch(rb"[A-Za-z0-9_-]{1,70}", parts[0]):
            raise InventoryError("Invalid JAR manifest header")
        key = parts[0].lower()
        if key in attributes:
            raise InventoryError("Duplicate JAR manifest main header")
        previous = key
        attributes[key] = parts[1]
    selected = {}
    for name in ("Manifest-Version", "Spring-Boot-Jar-Type"):
        if name.lower().encode() in attributes:
            value = attributes[name.lower().encode()].decode("utf-8", errors="strict")
            if any(ord(character) < 32 or ord(character) == 127 for character in value):
                raise InventoryError("Unsafe selected JAR manifest value")
            selected[name] = value
    return {"entry": "META-INF/MANIFEST.MF", "size": len(raw),
            "sha256": hashlib.sha256(raw).hexdigest(), "mainAttributes": selected}


def embedded_packaging_evidence(specification, repository, errors):
    """One explicit build-tool host and embedded library; no synthetic dependency."""
    if not isinstance(specification, str) or specification.count("!") != 1:
        raise InventoryError("Embedded packaging source must be host-coordinate!entry")
    coordinate, entry_name = specification.split("!", 1)
    parts = coordinate.split(":")
    if len(parts) != 4 or parts[2] != "jar":
        raise InventoryError("Packaging host requires group:artifact:jar:version")
    _, group, artifact, version, _, _ = identity({"groupId": parts[0], "artifactId": parts[1],
                                                "type": parts[2], "version": parts[3]})
    member = PurePosixPath(entry_name)
    if (member.is_absolute() or ".." in member.parts or "\\" in entry_name
            or not entry_name.endswith(".jar") or entry_name != str(member)):
        raise InventoryError("Unsafe embedded packaging library entry")
    directory = PurePosixPath(*group.split("."), artifact, version)
    jar_relative = str(directory / (artifact + "-" + version + ".jar"))
    pom_relative = str(directory / (artifact + "-" + version + ".pom"))
    host, pom = repository / jar_relative, repository / pom_relative
    evidence = {"source": specification, "hostCoordinate": coordinate,
                "hostArtifact": {"source": jar_relative, "sha256": None},
                "hostPom": None, "hostLicenseNotices": [],
                "embeddedEntry": entry_name, "embeddedSha256": None, "embeddedSize": None}
    if pom.is_file():
        evidence["hostPom"] = pom_evidence(pom, pom_relative)
    else:
        errors.append({"code": "MISSING_PACKAGING_HOST_POM", "coordinate": coordinate})
    if not host.is_file():
        errors.append({"code": "MISSING_PACKAGING_HOST_ARTIFACT", "coordinate": coordinate})
        return evidence
    evidence["hostArtifact"]["sha256"] = digest_file(host)
    evidence["hostLicenseNotices"] = license_notices(host)
    with evidence_archive(host) as archive:
        matches = [item for item in archive.infolist() if item.filename == entry_name]
        if not matches:
            errors.append({"code": "MISSING_EMBEDDED_PACKAGING_ENTRY", "source": specification})
            return evidence
        if len(matches) != 1 or matches[0].is_dir() or matches[0].file_size > MAX_NESTED_JAR_BYTES:
            raise InventoryError("Embedded packaging entry is not unique and bounded")
        with archive_member(archive, matches[0]) as stream:
            evidence["embeddedSha256"] = digest_stream(stream, MAX_NESTED_JAR_BYTES)
        evidence["embeddedSize"] = matches[0].file_size
    return evidence


def build_inventory(tree_path, boot_jar, repository, embedded_packaging_library=None):
    tree_bytes = document(tree_path)
    tree = json.loads(tree_bytes, object_pairs_hook=strict_json_object, parse_constant=reject_json_constant)
    root, dependencies, edges = resolve_tree(tree)
    errors = []
    packaging = embedded_packaging_evidence(embedded_packaging_library, repository, errors) if embedded_packaging_library else None
    artifacts = []
    runtime_by_digest = {}
    required_runtime = set()
    for coordinate, node in sorted(dependencies.items()):
        _, group, artifact, version, kind, classifier = node["fields"]
        extension = "jar" if kind in JAR_TYPES else kind
        suffix = classifier or ("tests" if kind == "test-jar" else "")
        stem = artifact + "-" + version + ("-" + suffix if suffix else "")
        directory = PurePosixPath(*group.split("."), artifact, version)
        artifact_relative = str(directory / (stem + "." + extension))
        pom_relative = str(directory / (artifact + "-" + version + ".pom"))
        artifact_path, pom_path = repository / artifact_relative, repository / pom_relative
        item = {
            "coordinate": coordinate, "scopes": sorted(node["scopes"]),
            "origin": "RESOLVED_DEPENDENCY_TREE",
            "dependencyKinds": sorted(node["kinds"]),
            "artifact": {"source": artifact_relative, "sha256": None},
            "pom": None, "licenseNotices": [], "manifest": None,
            "packagingCoverage": "NOT_RUNTIME",
        }
        runtime = extension == "jar" and bool(node["scopes"] & RUNTIME_SCOPES)
        if artifact_path.is_file():
            item["artifact"]["sha256"] = digest_file(artifact_path)
            if extension == "jar":
                item["licenseNotices"] = license_notices(artifact_path)
                item["manifest"] = manifest_evidence(artifact_path)
                marker = item["manifest"]["mainAttributes"].get("Spring-Boot-Jar-Type") if item["manifest"] else None
                if runtime and marker in BUILD_TOOL_JAR_TYPES:
                    item["packagingCoverage"] = "BUILD_TOOL_EXCLUDED"
                    runtime = False
            if runtime:
                runtime_by_digest.setdefault(item["artifact"]["sha256"], []).append(coordinate)
        else:
            errors.append({"code": "MISSING_RESOLVED_ARTIFACT", "coordinate": coordinate})
        if runtime:
            required_runtime.add(coordinate)
            item["packagingCoverage"] = "REQUIRED_RUNTIME"
        if pom_path.is_file():
            item["pom"] = pom_evidence(pom_path, pom_relative)
        else:
            errors.append({"code": "MISSING_RESOLVED_POM", "coordinate": coordinate})
        artifacts.append(item)

    matched = set()
    matched_packaging = False
    libraries = packaged_libraries(boot_jar)
    for library in libraries:
        candidates = runtime_by_digest.get(library["sha256"], [])
        embedded_match = packaging is not None and library["sha256"] == packaging["embeddedSha256"]
        library["coordinate"] = candidates[0] if len(candidates) == 1 else None
        library["origin"] = None
        if len(candidates) + int(embedded_match) > 1:
            library["coordinate"] = None
            errors.append({"code": "AMBIGUOUS_PACKAGED_LIBRARY", "entry": library["entry"],
                           "coordinates": candidates, "packagingSources": [packaging["source"]] if embedded_match else []})
        elif embedded_match:
            if matched_packaging:
                errors.append({"code": "DUPLICATE_PACKAGED_EMBEDDED_LIBRARY", "source": packaging["source"]})
            library["origin"] = "EMBEDDED_PACKAGING_LIBRARY"
            library["packagingSource"] = packaging["source"]
            matched_packaging = True
        elif not candidates:
            errors.append({"code": "UNMATCHED_PACKAGED_LIBRARY", "entry": library["entry"]})
        else:
            library["origin"] = "RESOLVED_DEPENDENCY_TREE"
            if candidates[0] in matched:
                errors.append({"code": "DUPLICATE_PACKAGED_ARTIFACT", "coordinate": candidates[0]})
            matched.add(candidates[0])
    for coordinate in sorted(required_runtime - matched):
        errors.append({"code": "RUNTIME_ARTIFACT_NOT_PACKAGED", "coordinate": coordinate})
    if packaging and not matched_packaging:
        errors.append({"code": "EMBEDDED_PACKAGING_LIBRARY_NOT_PACKAGED", "source": packaging["source"]})
    unknown = [item["coordinate"] for item in artifacts
               if item["pom"] is None or item["pom"]["licenseStatus"] == "UNKNOWN"]
    return {
        "format": "msf-dependency-license-evidence-v1",
        "project": root,
        "inputHashes": {"resolvedTreeSha256": hashlib.sha256(tree_bytes).hexdigest(),
                        "bootJarSha256": digest_file(boot_jar),
                        "packagingInputSha256": hashlib.sha256(json.dumps(embedded_packaging_library).encode()).hexdigest()},
        "completeness": "COMPLETE" if not errors else "INCOMPLETE",
        "licenseReview": "NOT_PERFORMED",
        "projectLicense": "NOT_EVALUATED",
        "limitations": ["Not SPDX or CycloneDX", "Only supplied tree and explicit embedded packaging source; complete build plugins and JDK excluded",
                        "POM licenses are unverified declarations; parent inheritance is not resolved",
                        "Runtime reconciliation covers compile/runtime JAR artifacts only"],
        "artifacts": artifacts,
        "packagingEvidence": [packaging] if packaging else [],
        "packagingPolicy": {"excludedMainManifestJarTypes": sorted(BUILD_TOOL_JAR_TYPES)},
        "dependencyEdges": [{"from": parent, "to": child} for parent, child in sorted(edges)],
        "packagedLibraries": libraries,
        "unknownLicenseCoordinates": unknown,
        "errors": errors,
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tree", required=True, type=Path)
    parser.add_argument("--boot-jar", required=True, type=Path)
    parser.add_argument("--repository", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--embedded-packaging-library", action="append", default=[],
                        help="One explicit group:artifact:jar:version!embedded-entry source")
    args = parser.parse_args(argv)
    try:
        if len(args.embedded_packaging_library) > 1:
            raise InventoryError("Only one explicit embedded packaging source is supported")
        inventory = build_inventory(args.tree, args.boot_jar, args.repository,
                                    args.embedded_packaging_library[0] if args.embedded_packaging_library else None)
        args.output.write_text(json.dumps(inventory, ensure_ascii=False, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    except (InventoryError, OSError, ValueError, ET.ParseError, zipfile.BadZipFile, RecursionError):
        print("Inventory failed: invalid, unsafe or unavailable evidence input", file=sys.stderr)
        return 2
    print("Inventory " + inventory["completeness"] + ": " + str(len(inventory["artifacts"]))
          + " artifacts; " + str(len(inventory["unknownLicenseCoordinates"])) + " unknown license declarations")
    return 0 if inventory["completeness"] == "COMPLETE" else 2


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""Offline, deterministic npm v3 lock evidence; no install or legal assessment."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import stat
import sys

MAX_JSON_BYTES = 16 * 1024 * 1024
MAX_PACKAGES = 10_000
MAX_ROOT_ENTRIES = 4096
MAX_NOTICE_BYTES = 8 * 1024 * 1024
MAX_NOTICE_TOTAL = 32 * 1024 * 1024
DEPENDENCY_FIELDS = ("dependencies", "devDependencies", "optionalDependencies", "peerDependencies")
NAME = re.compile(r"(?:@[a-zA-Z0-9_.-]+/)?[a-zA-Z0-9_.-]+\Z")


class InventoryError(ValueError):
    """Invalid evidence, without disclosing local paths."""


def sha(data):
    return hashlib.sha256(data).hexdigest()


def unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise InventoryError("Duplicate JSON key")
        result[key] = value
    return result


def nonfinite(_):
    raise InventoryError("Nonstandard JSON constant")


def read_bytes(path, limit):
    if not stat.S_ISREG(path.stat().st_mode):
        raise InventoryError("Evidence must be a regular file")
    with path.open("rb") as stream:
        data = stream.read(limit + 1)
    if len(data) > limit:
        raise InventoryError("Evidence byte limit exceeded")
    return data


def read_json(path):
    data = read_bytes(path, MAX_JSON_BYTES)
    value = json.loads(data, object_pairs_hook=unique, parse_constant=nonfinite)
    if not isinstance(value, dict):
        raise InventoryError("JSON document must be an object")
    return data, value


def package_name(value):
    if not isinstance(value, str) or not NAME.fullmatch(value) or any(p in {".", ".."} for p in value.split("/")):
        raise InventoryError("Invalid package name")
    return value


def package_path(value):
    if not isinstance(value, str) or len(value) > 4096:
        raise InventoryError("Invalid package path")
    parts = value.split("/")
    index = 0
    name = None
    while index < len(parts):
        if parts[index] != "node_modules" or index + 1 >= len(parts):
            raise InventoryError("Unsupported package path")
        index += 1
        name = parts[index]
        index += 1
        if name.startswith("@"):
            if index >= len(parts):
                raise InventoryError("Incomplete scoped path")
            name += "/" + parts[index]
            index += 1
        package_name(name)
    return name


def public(value):
    # Retained fields must not disclose credentials or local absolute paths.
    if isinstance(value, str):
        if re.search(r"[a-zA-Z][a-zA-Z0-9+.-]*://[^/\s]*@", value) or value.startswith(("/", "file:", "\\")):
            raise InventoryError("Private declaration is unsupported in public evidence")
    elif isinstance(value, dict):
        for key, item in value.items():
            public(key)
            public(item)
    elif isinstance(value, list):
        for item in value:
            public(item)
    return value


def dependencies(node, field):
    value = node.get(field, {})
    if not isinstance(value, dict):
        raise InventoryError("Dependency declarations must be objects")
    for name, spec in value.items():
        package_name(name)
        if not isinstance(spec, str) or len(spec) > 16384:
            raise InventoryError("Invalid dependency specification")
    return value


def peer_metadata(node):
    value = node.get("peerDependenciesMeta", {})
    if not isinstance(value, dict):
        raise InventoryError("Invalid peer dependency metadata")
    for name, metadata in value.items():
        package_name(name)
        if not isinstance(metadata, dict) or ("optional" in metadata and type(metadata["optional"]) is not bool):
            raise InventoryError("Invalid optional peer declaration")
    return value


def target_path(source, name, packages):
    current = source
    while True:
        candidate = (current + "/" if current else "") + "node_modules/" + name
        if candidate in packages:
            return candidate
        if not current:
            return None
        # A package's enclosing node_modules directory is never a lookup root.
        current = current.rsplit("/node_modules/", 1)[0] if "/node_modules/" in current else ""


def safe_child(root, relative):
    path = root
    for part in relative.split("/"):
        path = path / part
        if path.is_symlink():
            raise InventoryError("Symlink evidence is unsupported")
    if not path.resolve().is_relative_to(root):
        raise InventoryError("Evidence escapes its supplied root")
    return path


def installed_evidence(root, relative, name, version):
    if root is None:
        return {"status": "NOT_REQUESTED", "noticeStatus": "UNKNOWN", "rootNotices": []}
    directory = safe_child(root, relative.removeprefix("node_modules/"))
    if not directory.exists():
        return {"status": "ABSENT", "noticeStatus": "UNKNOWN", "rootNotices": []}
    if not directory.is_dir():
        raise InventoryError("Installed package must be a directory")
    metadata = safe_child(root, relative.removeprefix("node_modules/") + "/package.json")
    raw, declared = read_json(metadata)
    if declared.get("name") != name or declared.get("version") != version:
        raise InventoryError("Installed package identity does not match lock")
    entries = []
    for item in directory.iterdir():
        entries.append(item.name)
        if len(entries) > MAX_ROOT_ENTRIES:
            raise InventoryError("Package root entry limit exceeded")
    notices = []
    total = 0
    for entry in sorted(entries):
        if not entry.upper().startswith(("LICENSE", "LICENCE", "COPYING", "NOTICE")):
            continue
        path = safe_child(root, relative.removeprefix("node_modules/") + "/" + entry)
        if not path.is_file():
            continue
        content = read_bytes(path, MAX_NOTICE_BYTES)
        total += len(content)
        if total > MAX_NOTICE_TOTAL:
            raise InventoryError("Package notice aggregate limit exceeded")
        notices.append({"entry": entry, "size": len(content), "sha256": sha(content)})
    return {"status": "MATCHED", "packageJsonSha256": sha(raw),
            "licenseDeclaration": public(declared.get("license")),
            "rootEntryNamesSha256": sha(json.dumps(sorted(entries), ensure_ascii=True).encode()),
            "noticeStatus": "ROOT_FILES_RECORDED" if notices else "UNKNOWN",
            "unknownReason": None if notices else "ROOT_NOTICE_NOT_FOUND",
            "rootNotices": notices}


def build_inventory(lock_path, manifest_path, installed_root=None):
    lock_raw, lock = read_json(lock_path)
    manifest_raw, manifest = read_json(manifest_path)
    packages = lock.get("packages")
    if type(lock.get("lockfileVersion")) is not int or lock["lockfileVersion"] != 3 or not isinstance(packages, dict):
        raise InventoryError("Expected npm v3 lock packages")
    if not 1 <= len(packages) <= MAX_PACKAGES or not isinstance(packages.get(""), dict):
        raise InventoryError("Missing root or package count limit exceeded")
    root = packages[""]
    # Validate both sides before equality: Python considers False equal to 0.
    peer_metadata(manifest)
    peer_metadata(root)
    for field in ("name", "version", *DEPENDENCY_FIELDS, "peerDependenciesMeta"):
        default = {} if field in (*DEPENDENCY_FIELDS, "peerDependenciesMeta") else None
        if manifest.get(field, default) != root.get(field, default):
            raise InventoryError("Manifest does not match lock root")
    package_name(root.get("name"))
    if not isinstance(root.get("version"), str):
        raise InventoryError("Missing root version")
    if installed_root is not None:
        if installed_root.is_symlink() or installed_root.name != "node_modules" or not installed_root.is_dir():
            raise InventoryError("Explicit installed root must be a node_modules directory")
        installed_root = installed_root.resolve()
    artifacts = []
    for path, node in sorted(packages.items()):
        if not isinstance(node, dict) or node.get("link"):
            raise InventoryError("Unsupported package or linked workspace")
        if not path:
            continue
        name = package_path(path)
        if node.get("name", name) != name:
            raise InventoryError("Package path identity mismatch")
        version = node.get("version")
        if not isinstance(version, str) or not version or len(version) > 1024:
            raise InventoryError("Invalid locked version")
        metadata = {key: node[key] for key in ("dev", "optional", "peer", "devOptional", "os", "cpu", "libc", "peerDependenciesMeta", "engines", "hasInstallScript") if key in node}
        license_value = node.get("license")
        integrity = node.get("integrity")
        artifacts.append({"path": path, "name": name, "version": public(version),
                          "metadata": public(metadata), "licenseDeclaration": public(license_value),
                          "licenseStatus": "DECLARED" if isinstance(license_value, str) and license_value.strip() else "UNKNOWN",
                          "integrityDeclaration": public(integrity),
                          "integrityStatus": "DECLARED_NOT_VERIFIED" if isinstance(integrity, str) and integrity else "UNKNOWN",
                          "installed": installed_evidence(installed_root, path, name, version)})
    edges = []
    direct = set()
    for source, node in sorted(packages.items()):
        peer_meta = peer_metadata(node)
        for field in DEPENDENCY_FIELDS:
            for name, spec in sorted(dependencies(node, field).items()):
                target = target_path(source, name, packages)
                if not source and target:
                    direct.add(target)
                # Nonregistry locators may disclose local paths/credentials.
                displayed = "REDACTED_NONREGISTRY_SPEC" if re.search(r"[:/\\]", spec) else spec
                edges.append({"from": source, "to": target, "name": name, "kind": field,
                              "specification": displayed, "specificationSha256": sha(spec.encode()),
                              "declarationOptional": field == "optionalDependencies" or (field == "peerDependencies" and peer_meta.get(name, {}).get("optional", False)),
                              "resolution": "LOCK_PATH_LOOKUP" if target else "UNKNOWN"})
    for artifact in artifacts:
        artifact["dependencyKind"] = "direct" if artifact["path"] in direct else "transitive"
    return {"schemaVersion": 1, "evidenceType": "NPM_V3_LOCK_PREBUILD", "licenseReview": "NOT_PERFORMED",
            "projectLicense": "UNDECIDED", "integrityVerification": "NOT_PERFORMED",
            "noticeScope": "INSTALLED_PACKAGE_ROOT_FILES_ONLY", "inputs": {"lockSha256": sha(lock_raw), "manifestSha256": sha(manifest_raw)},
            "root": {"name": root["name"], "version": public(root["version"])},
            "packages": artifacts, "declaredEdges": edges}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lock", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--installed-root", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        result = build_inventory(args.lock, args.manifest, args.installed_root)
        rendered = json.dumps(result, indent=2, sort_keys=True, ensure_ascii=True, allow_nan=False) + "\n"
        with args.output.open("x", encoding="utf-8") as stream:
            stream.write(rendered)
    except (OSError, ValueError, RecursionError, RuntimeError):
        print("Invalid, unavailable, unsafe input or existing output; no evidence accepted", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())

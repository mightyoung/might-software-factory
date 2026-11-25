"""Read-only inventory for AU-08 bootstrap review, not a provisioning tool."""

from hashlib import sha256
import re

from hosted_contract import DATABASE

RESOURCES = "backend/src/main/resources/"
APP = RESOURCES + "db/migration"
OP = RESOURCES + "db/operator/"
VERSIONS = (*map(str, range(2, 9)), *map(str, range(11, 19)), "18.1", "18.2", "18.3",
            *map(str, range(19, 38)))
SOURCES = {
    "framework": ("backend/pom.xml", "tools/d0/migrate.java", "tools/d0/bootstrap.java",
                  OP + "V23-superuser-apply.sql"),
    "d0_grants": (OP + "d0-legacy-runtime-grants.sql", OP + "d0-runtime-grants.sql"),
    "d1_grants": (OP + "d1-runtime-grants.sql",),
    "d2_grants": tuple(OP + f"d2{suffix}-runtime-grants.sql"
                       for suffix in ("", "-return", "-loss", "-transfer", "-version")),
    "d1_fixture": ("tools/d1/seed.java",),
    "d2_fixture": ("tools/d2/seed.sql", "tools/d2-issue/seed.sql", "tools/d2-return/seed.sql",
                   "tools/d2-read-scope/seed.sql"),
    "deploy_bpmn": ("tools/d2/DeployIssueProcess.java",),
    "bpmn": (RESOURCES + "processes/tool-loan-v1.bpmn20.xml",),
    "acceptance": ("tools/d2-authz/acceptance.py",),
}


def digest(root, relative):
    path = root / relative
    if not path.is_file() or path.is_symlink() or path.resolve() != root / relative:
        raise ValueError("Expected regular checkout source")
    return sha256(path.read_bytes()).hexdigest()


def app_migrations(root):
    root = root.resolve()
    migrations = {}
    for path in (root / APP).iterdir():
        match = re.fullmatch(r"V([0-9]+(?:_[0-9]+)?)__[a-z0-9_]+\.sql", path.name)
        if not match:
            raise ValueError("Unexpected application migration source")
        version = match[1].replace("_", ".")
        if version in migrations or version not in VERSIONS:
            raise ValueError("Unexpected application migration version")
        migrations[version] = str(path.relative_to(root))
    if migrations.keys() != set(VERSIONS):
        raise ValueError("Incomplete reviewed application migration history")
    return {version: {migrations[version]: digest(root, migrations[version])}
            for version in VERSIONS}


def review_inputs(root):
    """Fingerprint checked-in inputs without suggesting their guards may be skipped."""
    root = root.resolve()
    return {
        "target": DATABASE,
        "ready": False,
        "migrations": app_migrations(root),
        "sources": {group: {path: digest(root, path) for path in paths}
                    for group, paths in SOURCES.items()},
    }

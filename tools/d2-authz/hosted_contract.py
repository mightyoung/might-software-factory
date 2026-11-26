"""Pure preflight and evidence checks for a future AU-08 hosted runner.

No process, database or network access. Phase witnesses are only meaningful if
the caller derived them from its owned process and actual PostgreSQL instance.
"""

from dataclasses import dataclass
import re
from uuid import UUID

DATABASE = "msf_au08_test"
REPOSITORY = "mightyoung/might-software-factory"
OVERRIDE_PREFIXES = ("PG", "LAN_PG_", "AU08_", "D2_", "CI_PG18_", "SPRING_DATASOURCE_")
DOCKER_OVERRIDES = {"DOCKER_HOST", "DOCKER_CONTEXT", "DOCKER_TLS_VERIFY", "DOCKER_CERT_PATH"}
WITNESS_FIELDS = {"commit", "jar_sha256", "instance", "database", "definition_version"}
REQUIRED_CASES = (
    "issue/revocation-commits-before-blocked-command",
    "issue/permission", "issue/member",
    "issue/current-tool-unavailable/no-partial-commit",
    "return-requests/permission", "return-requests/member",
    "receive/permission", "receive/member",
    "damaged-issued-tool/return-and-receive/settled",
)


@dataclass(frozen=True)
class Target:
    commit: str
    database_port: int
    database_host: str = "127.0.0.1"
    database_name: str = DATABASE


def check_checkout(environment, head, dirty):
    """Refuse any non-project runner, inexact checkout, or inherited target override."""
    if (environment.get("GITHUB_ACTIONS") != "true"
            or environment.get("RUNNER_ENVIRONMENT") != "github-hosted"
            or environment.get("RUNNER_OS") != "Linux"
            or environment.get("GITHUB_REPOSITORY") != REPOSITORY):
        raise ValueError("AU-08 requires the project's GitHub-hosted Linux job")
    if (not isinstance(head, str) or not re.fullmatch(r"[0-9a-f]{40}", head)
            or head != environment.get("GITHUB_SHA") or dirty is not False):
        raise ValueError("AU-08 requires the exact clean CI checkout")
    if any(name.startswith(OVERRIDE_PREFIXES) or name in DOCKER_OVERRIDES
           for name in environment):
        raise ValueError("Inherited database, acceptance or Docker endpoint overrides are forbidden")


def checked_target(environment, head, dirty, mapped_port):
    """Validate runner and checkout; the port must come from the owned mapping, not inputs."""
    check_checkout(environment, head, dirty)
    if type(mapped_port) is not int or not 1024 <= mapped_port <= 65535 or mapped_port == 5432:
        raise ValueError("AU-08 requires an owned non-default host port")
    return Target(head, mapped_port)


def phase_transition(target, phase_one, phase_two):
    """Require 1 → 2 on the same checked-out artifact and disposable instance."""
    for expected, witness in ((1, phase_one), (2, phase_two)):
        if (not isinstance(witness, dict) or witness.keys() != WITNESS_FIELDS
                or witness["commit"] != target.commit or witness["database"] != DATABASE
                or not isinstance(witness["jar_sha256"], str)
                or not re.fullmatch(r"[0-9a-f]{64}", witness["jar_sha256"])
                or not canonical_uuid(witness["instance"])
                or type(witness["definition_version"]) is not int
                or witness["definition_version"] != expected):
            raise ValueError("Invalid AU-08 phase witness")
    if any(phase_one[field] != phase_two[field] for field in ("jar_sha256", "instance")):
        raise ValueError("AU-08 phases must reuse the same artifact and instance")


def canonical_uuid(value):
    if not isinstance(value, str):
        return False
    try:
        identifier = UUID(value)
        return str(identifier) == value and identifier.version == 4
    except ValueError:
        return False


def result_summary(raw):
    """Allow only the authoritative ordered case list; discard raw fixture identifiers."""
    if (not isinstance(raw, dict) or raw.keys() != {"loan", "checks"}
            or not canonical_uuid(raw["loan"]) or raw["checks"] != list(REQUIRED_CASES)):
        raise ValueError("Unexpected AU-08 result shape or case list")
    return {"passed": len(REQUIRED_CASES), "checks": list(REQUIRED_CASES)}

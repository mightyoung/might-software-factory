"""Allocate one owned PostgreSQL 18 container on the exact project runner."""

from contextlib import contextmanager
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import tempfile
import time
from uuid import uuid4

from hosted_contract import DATABASE, REPOSITORY, check_checkout, checked_target
from hosted_observation import BOOTSTRAP_ROLE, PREFIX, identity_inputs, inspect_container

IMAGE = "docker.io/library/postgres@sha256:e742a2668255221388004f3a42b6150a2103f5054ab3a4a93ae9e4f01e551c35"
DOCKER = ("docker", "--host", "unix:///var/run/docker.sock")
SAFE_ENV = {"PATH": "/usr/local/bin:/usr/bin:/bin"}


def runner_context(environment, head, dirty):
    check_checkout(environment, head, dirty)
    run, attempt = environment.get("GITHUB_RUN_ID"), environment.get("GITHUB_RUN_ATTEMPT")
    if (not isinstance(run, str) or not re.fullmatch(r"[1-9][0-9]{0,19}", run)
            or not isinstance(attempt, str) or not re.fullmatch(r"[1-9][0-9]{0,9}", attempt)):
        raise ValueError("Missing exact GitHub run identity")
    raw = environment.get("RUNNER_TEMP")
    if not raw or not Path(raw).is_absolute() or not Path(raw).is_dir():
        raise ValueError("Missing private runner temporary directory")
    return run, attempt, Path(raw).resolve(strict=True)


def label_values(run, attempt, marker):
    return {PREFIX + key: value for key, value in {
        "kind": "au08", "repository": REPOSITORY, "run": run, "attempt": attempt, "instance": marker,
    }.items()}


def create_command(run, attempt, marker, env_file):
    if not identity_inputs("0" * 64, run, attempt, marker, IMAGE):
        raise ValueError("Invalid AU-08 allocation identity")
    cmd = [*DOCKER, "create", "--name", f"msf-ci-au08-{run}-{attempt}", "--platform", "linux/amd64",
           "--publish", "127.0.0.1::5432/tcp", "--env-file", str(env_file),
           "--memory", "1g", "--cpus", "2", "--shm-size", "128m"]
    for key, value in label_values(run, attempt, marker).items():
        cmd.extend(("--label", key + "=" + value))
    return [*cmd, IMAGE, "postgres", "-c", "msf.ci_instance=" + marker,
            "-c", "log_statement=none", "-c", "log_min_error_statement=panic",
            "-c", "log_parameter_max_length_on_error=0"]


def cleanup_volume(row, identifier, run, attempt, marker):
    """Check actual Docker ownership even if the allocated container has stopped."""
    if not identity_inputs(identifier, run, attempt, marker, IMAGE):
        raise ValueError("Invalid AU-08 cleanup identity")
    try:
        mount, = row["Mounts"]
        labels = {key: value for key, value in row["Config"]["Labels"].items() if key.startswith(PREFIX)}
        if (row["Id"] != identifier or row["Name"] != f"/msf-ci-au08-{run}-{attempt}"
                or row["Config"]["Image"] != IMAGE or labels != label_values(run, attempt, marker)
                or mount["Type"] != "volume" or mount["Destination"] != "/var/lib/postgresql"
                or not re.fullmatch(r"[0-9a-f]{64}", mount["Name"])):
            raise ValueError
        return mount["Name"]
    except (AttributeError, KeyError, TypeError, ValueError):
        raise ValueError("AU-08 cleanup could not verify the exact container and volume") from None


def call(args, stage, timeout=30, cwd=None, check=True):
    try:
        return subprocess.run(args, env=SAFE_ENV, capture_output=True, text=True, timeout=timeout,
                              cwd=cwd, check=check)
    except (OSError, subprocess.SubprocessError):
        raise ValueError("AU-08 " + stage + " failed") from None


def cleanup(identifier, run, attempt, marker):
    raw = call([*DOCKER, "inspect", "--type", "container", identifier], "cleanup inspection").stdout
    try:
        row, = json.loads(raw)
    except (ValueError, TypeError):
        raise ValueError("Missing exact cleanup observation") from None
    volume = cleanup_volume(row, identifier, run, attempt, marker)
    call([*DOCKER, "rm", "--force", "--volumes", identifier], "cleanup removal")
    rows = call([*DOCKER, "ps", "-a", "--no-trunc", "--filter", "id=" + identifier,
                 "--format", "{{.ID}}"], "cleanup verification").stdout
    volumes = call([*DOCKER, "volume", "ls", "--format", "{{.Name}}"], "volume verification").stdout
    if rows.strip() or volume in volumes.splitlines():
        raise ValueError("AU-08 owned container or volume remains")


@contextmanager
def allocate(root, environment):
    if not __debug__:
        raise ValueError("AU-08 requires Python assertions")
    head = call(["git", "rev-parse", "HEAD"], "checkout verification", cwd=root).stdout.strip()
    dirty = bool(call(["git", "status", "--porcelain", "--untracked-files=all"],
                      "worktree verification", cwd=root).stdout)
    run, attempt, temporary = runner_context(environment, head, dirty)
    major = call([*DOCKER, "version", "--format", "{{.Server.Version}}"], "Docker version").stdout.split(".")[0]
    if not re.fullmatch(r"[0-9]{2,3}", major) or int(major) < 28:
        raise ValueError("AU-08 requires Docker 28 or newer for loopback isolation")
    call([*DOCKER, "pull", "--platform", "linux/amd64", IMAGE], "pinned image pull", timeout=180)
    marker = str(uuid4())
    bootstrap_password = secrets.token_hex(32)
    print("::add-mask::" + bootstrap_password, flush=True)
    with tempfile.TemporaryDirectory(prefix="msf-ci-au08-", dir=temporary) as private:
        directory = Path(private)
        env_file = directory / "postgres.env"
        fd = os.open(env_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            stream.write(f"POSTGRES_USER={BOOTSTRAP_ROLE}\nPOSTGRES_DB={DATABASE}\n"
                         f"POSTGRES_PASSWORD={bootstrap_password}\nPOSTGRES_INITDB_ARGS=--auth-host=scram-sha-256\n")
        identifier = call(create_command(run, attempt, marker, env_file), "container creation", timeout=60).stdout.strip()
        if not identity_inputs(identifier, run, attempt, marker, IMAGE):
            raise ValueError("Invalid private Docker create receipt")
        try:
            env_file.unlink()
            call([*DOCKER, "start", identifier], "container start", timeout=60)
            owned = inspect_container(identifier, run, attempt, marker, IMAGE)
            target = checked_target(environment, head, dirty, owned.port)
            for _ in range(60):
                ready = call([*DOCKER, "exec", identifier, "pg_isready", "-h", "127.0.0.1",
                              "-U", BOOTSTRAP_ROLE, "-d", DATABASE], "database readiness", timeout=5, check=False)
                if ready.returncode == 0:
                    break
                time.sleep(0.5)
            else:
                raise ValueError("The private AU-08 database did not start")
            yield owned, target, directory, bootstrap_password
        finally:
            cleanup(identifier, run, attempt, marker)

#!/usr/bin/env bash
set -euo pipefail

: "${AUTHZ_PLAN_FILE:?Set AUTHZ_PLAN_FILE to the output JSON file}"

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
cerbos_version=0.57.0
case "$(uname -s)-$(uname -m)" in
    Linux-x86_64)
        platform=Linux_x86_64
        archive_sha256=baec1b2b29a5f748528c16aa3cf1191a09ba6207add4e8e0d752e07cbffbf659
        ;;
    Darwin-arm64)
        platform=Darwin_arm64
        archive_sha256=45af16804d2642a4de31cc013fd152fc9c9c1bedb72786577cde51becc07ed57
        ;;
    *)
        echo "Supported platforms are Linux x86_64 and Darwin arm64" >&2
        exit 1
        ;;
esac

run_dir=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/cerbos-authz.XXXXXX")
cerbos_pid=
cleanup() {
    status=$?
    trap - EXIT
    if [[ -n "$cerbos_pid" ]]; then
        kill "$cerbos_pid" 2>/dev/null || true
        wait "$cerbos_pid" 2>/dev/null || true
    fi
    if [[ $status -ne 0 && -f "$run_dir/server.log" ]]; then
        cat "$run_dir/server.log" >&2
    fi
    rm -rf -- "$run_dir"
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

archive="cerbos_${cerbos_version}_${platform}.tar.gz"
curl --fail --silent --show-error --location --retry 3 --connect-timeout 20 --max-time 180 \
    "https://github.com/cerbos/cerbos/releases/download/v${cerbos_version}/${archive}" \
    --output "$run_dir/cerbos.tar.gz"
python3 - "$run_dir/cerbos.tar.gz" "$archive_sha256" <<'PY'
import hashlib
from pathlib import Path
import sys

actual = hashlib.sha256(Path(sys.argv[1]).read_bytes()).hexdigest()
if actual != sys.argv[2]:
    raise SystemExit(f"Cerbos archive SHA256 mismatch: {actual}")
print("PASS: pinned Cerbos archive SHA256")
PY
tar -xzf "$run_dir/cerbos.tar.gz" -C "$run_dir" cerbos
"$run_dir/cerbos" compile "$script_dir/policies"

# Reserve distinct ephemeral ports together before handing them to the server.
read -r http_port grpc_port < <(python3 - <<'PY'
import socket

with socket.socket() as http, socket.socket() as grpc:
    http.bind(("127.0.0.1", 0))
    grpc.bind(("127.0.0.1", 0))
    print(http.getsockname()[1], grpc.getsockname()[1])
PY
)
export CERBOS_URL="http://127.0.0.1:$http_port"
"$run_dir/cerbos" server \
    --set="server.httpListenAddr=127.0.0.1:$http_port" \
    --set="server.grpcListenAddr=127.0.0.1:$grpc_port" \
    --set=storage.driver=disk \
    --set="storage.disk.directory=$script_dir/policies" \
    --log-level=error >"$run_dir/server.log" 2>&1 &
cerbos_pid=$!
ready=false
for attempt in {1..100}; do
    if ! kill -0 "$cerbos_pid" 2>/dev/null; then
        echo "Cerbos exited before becoming ready" >&2
        exit 1
    fi
    if curl --fail --silent --noproxy '*' --max-time 1 \
        "$CERBOS_URL/_cerbos/health" --output /dev/null; then
        ready=true
        break
    fi
    sleep 0.1
done
if [[ "$ready" != true ]]; then
    echo "Cerbos did not become ready" >&2
    exit 1
fi

python3 "$script_dir/verify.py" --url "$CERBOS_URL" --output "$AUTHZ_PLAN_FILE"
if [[ $# -gt 0 ]]; then
    "$@"
fi

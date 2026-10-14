#!/usr/bin/env bash
# Updates a PhoneTag server in place: pulls the latest code, builds the server and swaps it into
# a running Docker container or a native install, restarts it, checks that it came back up, and
# rolls back automatically if it didn't.
#
#   server/update.sh                         # auto-detect (container "phonetag", else /opt/phonetag-server)
#   server/update.sh --docker [CONTAINER]    # update a running container (default: phonetag)
#   server/update.sh --native [DIR]          # update a native install (default: /opt/phonetag-server)
#   server/update.sh --rollback --native     # go back to the previous version
#
# Run with --help for all options.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="$REPO_ROOT/server/build/install/phonetag-server"
CONTAINER_PATH="/opt/phonetag-server" # where server/Dockerfile installs it
STATE_DIR="${XDG_STATE_HOME:-$HOME/.local/state}/phonetag"
GRADLE_IMAGE="gradle:8.14.3-jdk17"

mode=""
container="phonetag"
native_dir="/opt/phonetag-server"
service=""
port=47474
pull=1
build_only=0
rollback=0
rebuild_image=""
health_timeout=30

usage() {
    cat <<EOF
Usage: server/update.sh [--docker [CONTAINER] | --native [DIR]] [options]

Targets (auto-detected if neither is given):
  --docker [CONTAINER]   Update a running Docker container (default: phonetag).
  --native [DIR]         Update a native install directory (default: /opt/phonetag-server).

Options:
  --service NAME         Native only: systemd service to restart (default: phonetag-server, if installed).
  --port N               Port for the native health check (default: 47474).
  --rebuild-image TAG    Docker only: also rebuild the image as TAG, so recreating the container
                         later doesn't bring the old version back.
  --rollback             Restore the version that was running before the last update.
  --no-pull              Don't 'git pull' first (build the code as it is).
  --build-only           Just build the server; don't touch any running instance.
  -h, --help             Show this help.
EOF
}

log() { printf '\033[1;36m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33mwarning:\033[0m %s\n' "$*" >&2; }
die() { printf '\033[1;31merror:\033[0m %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------------------- arguments

while [[ $# -gt 0 ]]; do
    case "$1" in
        --docker)
            mode="docker"
            if [[ $# -gt 1 && "$2" != -* ]]; then container="$2"; shift; fi ;;
        --native)
            mode="native"
            if [[ $# -gt 1 && "$2" != -* ]]; then native_dir="$2"; shift; fi ;;
        --service) [[ $# -gt 1 ]] || die "--service needs a name"; service="$2"; shift ;;
        --port) [[ $# -gt 1 && "$2" =~ ^[0-9]+$ ]] || die "--port needs a number"; port="$2"; shift ;;
        --rebuild-image) [[ $# -gt 1 ]] || die "--rebuild-image needs a tag"; rebuild_image="$2"; shift ;;
        --rollback) rollback=1 ;;
        --no-pull) pull=0 ;;
        --build-only) build_only=1 ;;
        -h|--help) usage; exit 0 ;;
        *) usage >&2; die "unknown option: $1" ;;
    esac
    shift
done
native_dir="${native_dir%/}"

have() { command -v "$1" >/dev/null 2>&1; }
docker_ok() { have docker && docker info >/dev/null 2>&1; }

detect_mode() {
    [[ -n "$mode" ]] && return
    if docker_ok && docker inspect "$container" >/dev/null 2>&1; then
        mode="docker"
    elif [[ -d "$native_dir" ]]; then
        mode="native"
    else
        die "no container '$container' and no install at $native_dir; pass --docker NAME or --native DIR"
    fi
    log "Detected $mode target"
}

protocol_version() {
    grep -oE 'const val VERSION = [0-9]+' "$REPO_ROOT/core/src/main/kotlin/com/phonetag/core/Protocol.kt" | grep -oE '[0-9]+$' || echo "?"
}

# ---------------------------------------------------------------------------- build

pull_latest() {
    [[ $pull -eq 1 ]] || return 0
    if ! git -C "$REPO_ROOT" rev-parse --git-dir >/dev/null 2>&1; then
        warn "not a git checkout; building the files as they are"
        return 0
    fi
    if [[ -n "$(git -C "$REPO_ROOT" status --porcelain --untracked-files=no)" ]]; then
        warn "local changes present; skipping git pull (use --no-pull to silence this)"
        return 0
    fi
    log "Pulling latest code"
    if [[ $EUID -eq 0 && -n "${SUDO_USER:-}" && "$SUDO_USER" != "root" ]]; then
        sudo -u "$SUDO_USER" -H git -C "$REPO_ROOT" pull --ff-only
    else
        git -C "$REPO_ROOT" pull --ff-only
    fi
}

java_major() {
    have java || { echo 0; return; }
    java -version 2>&1 | awk -F'"' '/version/ { split($2, v, "."); print (v[1] == "1" ? v[2] : v[1]); exit }'
}

build() {
    # Under sudo (needed to write /opt), build as the invoking user so the checkout stays theirs.
    local as_user=()
    if [[ $EUID -eq 0 && -n "${SUDO_USER:-}" && "$SUDO_USER" != "root" ]]; then as_user=(sudo -u "$SUDO_USER" -H); fi
    if [[ "$(java_major)" -ge 17 ]]; then
        log "Building server with local JDK $(java_major)"
        (cd "$REPO_ROOT" && "${as_user[@]}" env PHONETAG_SERVER_ONLY=1 ./gradlew --no-daemon -q :server:installDist)
    elif docker_ok; then
        log "No JDK 17+ found; building inside $GRADLE_IMAGE"
        # Run as the calling user so build outputs aren't root-owned; keep Gradle's cache in the repo.
        docker run --rm -u "$(id -u):$(id -g)" -v "$REPO_ROOT":/src -w /src \
            -e PHONETAG_SERVER_ONLY=1 -e GRADLE_USER_HOME=/src/.gradle-docker \
            "$GRADLE_IMAGE" gradle --no-daemon -q :server:installDist
    else
        die "need JDK 17+ or Docker to build the server"
    fi
    [[ -x "$BUILD_DIR/bin/phonetag-server" ]] || die "build finished but $BUILD_DIR/bin/phonetag-server is missing"
    log "Built server (protocol v$(protocol_version))"
}

# ---------------------------------------------------------------------------- docker

container_running() { [[ "$(docker inspect -f '{{.State.Running}}' "$container" 2>/dev/null)" == "true" ]]; }

# Restarts the container and waits until the server logs that it is listening again.
docker_restart_and_check() {
    local since
    since="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    docker restart "$container" >/dev/null
    local waited=0
    while [[ $waited -lt $health_timeout ]]; do
        if container_running && docker logs --since "$since" "$container" 2>&1 | grep -q "PhoneTag server listening"; then
            return 0
        fi
        sleep 1
        waited=$((waited + 1))
    done
    return 1
}

docker_update() {
    docker_ok || die "Docker isn't available (is the daemon running, and can you use it?)"
    docker inspect "$container" >/dev/null 2>&1 || die "no container named '$container'"
    local backup="$STATE_DIR/docker-$container"
    mkdir -p "$STATE_DIR"

    log "Backing up the current server from container '$container'"
    rm -rf "$backup.tmp"
    docker cp "$container:$CONTAINER_PATH" "$backup.tmp" >/dev/null
    rm -rf "$backup"
    mv "$backup.tmp" "$backup"

    log "Copying the new server into the container"
    docker cp "$BUILD_DIR/." "$container:$CONTAINER_PATH/" >/dev/null

    log "Restarting '$container' (current lobbies will be dropped)"
    if docker_restart_and_check; then
        log "Update complete: '$container' is up (backup of the previous version: $backup)"
    else
        warn "server didn't come back up; recent logs:"
        docker logs --tail 30 "$container" >&2 || true
        log "Rolling back"
        docker cp "$backup/." "$container:$CONTAINER_PATH/" >/dev/null
        docker_restart_and_check || warn "rollback restart also failed; check 'docker logs $container'"
        die "update failed and was rolled back"
    fi

    if [[ -n "$rebuild_image" ]]; then
        log "Rebuilding image '$rebuild_image' so a recreated container gets this version too"
        docker build -q -f "$REPO_ROOT/server/Dockerfile" -t "$rebuild_image" "$REPO_ROOT" >/dev/null
    else
        log "Tip: add --rebuild-image <tag> to keep the image in sync, or recreating the container brings back the old version"
    fi
}

docker_rollback() {
    docker_ok || die "Docker isn't available"
    local backup="$STATE_DIR/docker-$container"
    [[ -d "$backup" ]] || die "no backup for container '$container' (looked in $backup)"
    log "Restoring the previous server into '$container'"
    docker cp "$backup/." "$container:$CONTAINER_PATH/" >/dev/null
    docker_restart_and_check || die "server didn't come back up; check 'docker logs $container'"
    log "Rollback complete"
}

# ---------------------------------------------------------------------------- native

port_open() { timeout 1 bash -c ">/dev/tcp/127.0.0.1/$port" 2>/dev/null; }

resolve_service() {
    [[ -n "$service" ]] && return
    if have systemctl && systemctl cat phonetag-server >/dev/null 2>&1; then service="phonetag-server"; fi
}

# Restarts the service and waits for the port. Returns 2 if there is no service to restart.
native_restart_and_check() {
    [[ -n "$service" ]] || return 2
    systemctl restart "$service"
    local waited=0
    while [[ $waited -lt $health_timeout ]]; do
        if systemctl is-active --quiet "$service" && port_open; then return 0; fi
        sleep 1
        waited=$((waited + 1))
    done
    return 1
}

native_update() {
    local parent
    parent="$(dirname "$native_dir")"
    mkdir -p "$parent" 2>/dev/null || true
    [[ -w "$parent" ]] || die "can't write to $parent; run with sudo (or pass --native DIR you own)"
    resolve_service

    log "Installing into $native_dir"
    local staged="$native_dir.new"
    rm -rf "$staged"
    cp -a "$BUILD_DIR" "$staged"
    # Swap directories: the running process keeps the files it already opened until it restarts.
    if [[ -d "$native_dir" ]]; then
        rm -rf "$native_dir.old"
        mv "$native_dir" "$native_dir.old"
    fi
    mv "$staged" "$native_dir"

    local rc=0
    native_restart_and_check || rc=$?
    case $rc in
        0) log "Update complete: service '$service' is up on port $port (previous version kept in $native_dir.old)" ;;
        2) log "Installed. No systemd service found, so restart your server process yourself" \
               "(previous version kept in $native_dir.old; see server/phonetag-server.service to run it as a service)" ;;
        *)
            warn "service '$service' didn't come back up on port $port; recent logs:"
            journalctl -u "$service" -n 30 --no-pager >&2 2>/dev/null || true
            log "Rolling back"
            native_swap_back
            native_restart_and_check || warn "rollback restart also failed; check 'journalctl -u $service'"
            die "update failed and was rolled back"
            ;;
    esac
}

# Exchanges the current install with the previous one (DIR <-> DIR.old).
native_swap_back() {
    [[ -d "$native_dir.old" ]] || die "no previous version at $native_dir.old"
    rm -rf "$native_dir.failed"
    [[ -d "$native_dir" ]] && mv "$native_dir" "$native_dir.failed"
    mv "$native_dir.old" "$native_dir"
    [[ -d "$native_dir.failed" ]] && mv "$native_dir.failed" "$native_dir.old"
    return 0
}

native_rollback() {
    resolve_service
    log "Restoring the previous version in $native_dir"
    native_swap_back
    local rc=0
    native_restart_and_check || rc=$?
    case $rc in
        0) log "Rollback complete: service '$service' is up on port $port" ;;
        2) log "Rolled back. Restart your server process to load it" ;;
        *) die "service '$service' didn't come back up; check 'journalctl -u $service'" ;;
    esac
}

# ---------------------------------------------------------------------------- main

if [[ $rollback -eq 1 ]]; then
    detect_mode
    if [[ "$mode" == "docker" ]]; then docker_rollback; else native_rollback; fi
    exit 0
fi

pull_latest
build
[[ $build_only -eq 1 ]] && { log "Build only: output in $BUILD_DIR"; exit 0; }
detect_mode
if [[ "$mode" == "docker" ]]; then docker_update; else native_update; fi
log "Players need an app build with protocol v$(protocol_version) to connect"

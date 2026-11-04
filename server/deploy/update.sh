#!/usr/bin/env bash
# Kid Phone Server — updater. Downloads the latest release and replaces the
# running app, without touching your .env. Migrations run automatically on
# the next startup - so before swapping anything, this copies the database
# and the old binary to /var/backups/kid-phone-server/<timestamp>/ (the last
# 3 are kept).
# An older binary refuses to start against a database that newer migrations
# have touched, so rolling back means restoring both - see DEPLOY.md.
#
# Usage (as root, e.g. via sudo), from a file rather than piped:
#   curl -fsSLo update.sh https://raw.githubusercontent.com/palchrb/vibb-launcher/main/server/deploy/update.sh
#   sudo bash update.sh
#
# KPS_REPO=owner/repo picks a different fork (same as install.sh), KPS_TAG=server-vX.Y.Z a
# release (an older install's root-side updater passes both: the repo it was installed from and
# the release this script was fetched from). KPS_ALLOW_DOWNGRADE=1 allows installing a release
# older than the installed one.
#
# The server never makes this machine do anything (the user, 2026-11-04). An install from before
# 0.67.0 has root helpers that did - a watcher for the server's requests, a scheduler, a backup
# copy to a drive: this removes them (remove_host_helpers).

set -euo pipefail

REPO="${KPS_REPO:-palchrb/vibb-launcher}"
# The pre-monorepo server repo: its installs pass it in (an older install's root-side updater
# remembered the repo it was installed from). Its releases now live in the monorepo.
if [ "$REPO" = "palchrb/kid-phone-server" ]; then
    REPO="palchrb/vibb-launcher"
fi
if ! [[ "$REPO" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]]; then
    echo "KPS_REPO must look like owner/repo, got: $REPO" >&2
    exit 1
fi
INSTALL_DIR="/opt/kid-phone-server"
SERVICE_USER="kidphone"
# The installed server version, for the downgrade check: root's, outside data/. Installs from
# before 0.67.0 kept it in OLD_UPDATER_DIR, the root helpers' directory (remove_host_helpers). The
# same lines in install.sh and update.sh.
VERSION_FILE="/var/lib/kid-phone-server/installed_version"
OLD_UPDATER_DIR="/opt/kid-phone-server-updater"

# Security review M5: every release file root installs or runs is signed with the project's
# minisign key, pinned here (the same line in install.sh and update.sh - a server test keeps them
# equal; server/DEPLOY.md, "Release signing"). The trusted comment names the file and
# the tag, so a signed file can't be passed off under another name or as another release.
SERVER_SIGNING_PUBKEY="RWQp6olF7G4WFeRgDWSWwq2/qOkClyqqXEzDBrPPf2EUrdZlAUQATU5g"

if [ "$(id -u)" -ne 0 ]; then
    echo "Please run this as root (e.g. 'sudo bash update.sh')." >&2
    exit 1
fi

# Nothing to install without the key (QA of the security wave, Low 7): say so before touching
# anything, instead of failing on a download later.
if [ "$SERVER_SIGNING_PUBKEY" = "not-set" ]; then
    echo "No release signing key is pinned in this script, so it installs nothing: root takes only" >&2
    echo "server releases signed with the project's key, and this copy predates the key. Use the" >&2
    echo "script from main once the first signed release (server 0.24.0) is published" >&2
    echo "(server/DEPLOY.md, \"Release signing\")." >&2
    exit 1
fi

if [ ! -f "$INSTALL_DIR/.env" ]; then
    echo "$INSTALL_DIR doesn't look like an existing install (no .env found)." >&2
    echo "Run install.sh first." >&2
    exit 1
fi

# The release's tarball for this machine: both are static musl binaries, each built natively on a
# runner of its own architecture. The same text in install.sh and update.sh.
case "$(uname -m)" in
    aarch64 | arm64) TARGET="aarch64-unknown-linux-musl" ;;
    x86_64 | amd64) TARGET="x86_64-unknown-linux-musl" ;;
    *)
        echo "Unsupported architecture: $(uname -m)" >&2
        echo "The server is released for 64-bit Linux on aarch64 or x86_64 only." >&2
        exit 1
        ;;
esac

# The repo is a monorepo that also publishes the launcher, whose stable releases are GitHub's
# "latest" - so the server's release is looked up in the releases list (newest first): the first
# server-vX.Y.Z that is neither a draft nor a prerelease. Never releases/latest. Plain grep/awk on
# the API's JSON (each release lists tag_name, then draft, then prerelease; asset objects have
# none of these keys). Same text in install.sh and update.sh.
resolve_server_tag() {
    curl -fsSL -H "Accept: application/vnd.github+json" \
        "https://api.github.com/repos/$REPO/releases?per_page=100" \
        | grep -oE '"(tag_name|draft|prerelease)": *("[^"]*"|true|false)' \
        | awk -F': *' '
            /"tag_name"/ { tag = $2; gsub(/"/, "", tag); draft = ""; next }
            /"draft"/ { draft = $2; next }
            /"prerelease"/ {
                if (draft == "false" && $2 == "false" && tag ~ /^server-v[0-9]+\.[0-9]+\.[0-9]+$/) { print tag; exit }
            }' || true
}

# vX.Y.Z > vX.Y.Z. Both are checked against the pattern first: bash arithmetic on an unchecked
# string (one comes from a file) would evaluate it.
version_gt() {
    local re='^v([0-9]+)\.([0-9]+)\.([0-9]+)$' a b i
    [[ "$1" =~ $re ]] || return 1
    a=("${BASH_REMATCH[@]:1}")
    [[ "$2" =~ $re ]] || return 1
    b=("${BASH_REMATCH[@]:1}")
    for i in 0 1 2; do
        if ((10#${a[i]} > 10#${b[i]})); then return 0; fi
        if ((10#${a[i]} < 10#${b[i]})); then return 1; fi
    done
    return 1
}

# The installed server version (vX.Y.Z), or nothing if unknown: install.sh/update.sh record it in
# VERSION_FILE; older installs have it in OLD_UPDATER_DIR, the oldest only in data/watcher_version
# (written by install.sh at install time; read as a version string, never run).
installed_version() {
    local f v
    for f in "$VERSION_FILE" "$OLD_UPDATER_DIR/installed_version" "$INSTALL_DIR/data/watcher_version"; do
        [ -f "$f" ] || continue
        v="$(head -c 32 "$f" | tr -d '\n')"
        if [[ "$v" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
            echo "$v"
            return
        fi
    done
}

# Refuses to install an older release than the installed one (it would refuse to start on a
# database the newer version migrated) unless KPS_ALLOW_DOWNGRADE=1.
refuse_downgrade() {
    local new="$1" current
    current="$(installed_version)"
    if [ -n "$current" ] && version_gt "$current" "$new"; then
        if [ "${KPS_ALLOW_DOWNGRADE:-}" = "1" ]; then
            echo "Installing $new over the newer $current (KPS_ALLOW_DOWNGRADE=1)."
        else
            echo "The newest release ($new) is older than the installed $current - not installing." >&2
            echo "To go back on purpose, see DEPLOY.md (Rolling back) and set KPS_ALLOW_DOWNGRADE=1." >&2
            exit 1
        fi
    fi
}

# KPS_TAG (a release picked by hand; an older install's root-side updater sets it to the tag it
# fetched update.sh from) or the newest stable server release.
pick_tag() {
    if [ -n "${KPS_TAG:-}" ]; then
        if ! [[ "$KPS_TAG" =~ ^server-v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
            echo "KPS_TAG must look like server-vX.Y.Z, got: $KPS_TAG" >&2
            exit 1
        fi
        echo "$KPS_TAG"
    else
        resolve_server_tag
    fi
}

# verify_signed FILE NAME TAG: FILE.minisig must be a good signature of FILE by the pinned key,
# with the trusted comment "NAME TAG". Anything else - no key pinned, no minisign, no or a bad
# signature, another comment - refuses.
verify_signed() {
    local file="$1" name="$2" tag="$3" comment
    if [ "$SERVER_SIGNING_PUBKEY" = "not-set" ]; then
        echo "No release signing key is pinned in this script - refusing $name (server/DEPLOY.md, \"Release signing\")." >&2
        return 1
    fi
    if ! command -v minisign >/dev/null 2>&1; then
        { apt-get update -qq && apt-get install -y -qq minisign; } >/dev/null 2>&1 || true
    fi
    if ! command -v minisign >/dev/null 2>&1; then
        echo "minisign is missing and couldn't be installed - refusing $name unchecked." >&2
        return 1
    fi
    if ! comment="$(minisign -V -Q -P "$SERVER_SIGNING_PUBKEY" -m "$file" -x "$file.minisig" 2>/dev/null)" ||
        [ "$comment" != "$name $tag" ]; then
        echo "$name has no valid signature for $tag - not using it." >&2
        return 1
    fi
}

# Downloads the release tarball, its .sha256 and its .minisig into $1 and checks the signature and
# the hash; exits on any failure.
download_release() {
    local dir="$1" asset="kid-phone-server-$TARGET.tar.gz" expected actual
    local url="https://github.com/$REPO/releases/download/$TAG/$asset"
    echo "Downloading $TAG from $url ..."
    curl -fsSL "$url" -o "$dir/$asset"
    curl -fsSL "$url.sha256" -o "$dir/$asset.sha256"
    if ! curl -fsSL "$url.minisig" -o "$dir/$asset.minisig"; then
        echo "$TAG has no signature ($asset.minisig): server releases before 0.24.0 aren't signed, and" >&2
        echo "this script installs only signed ones - not installing (server/DEPLOY.md, \"Release signing\")." >&2
        exit 1
    fi
    verify_signed "$dir/$asset" "$asset" "$TAG" || exit 1
    expected="$(head -c 64 "$dir/$asset.sha256")"
    actual="$(sha256sum "$dir/$asset" | cut -c1-64)"
    if ! [[ "$expected" =~ ^[0-9a-f]{64}$ ]] || [ "$expected" != "$actual" ]; then
        echo "SHA-256 of $asset doesn't match $asset.sha256 - not installing." >&2
        exit 1
    fi
    tar -xzf "$dir/$asset" -C "$dir"
    rm -f "$dir/$asset" "$dir/$asset.sha256" "$dir/$asset.minisig"
    if [ ! -s "$dir/kid_phone_server" ] || [ ! -d "$dir/static" ]; then
        echo "The download doesn't contain kid_phone_server and static/ - not installing." >&2
        exit 1
    fi
}

# The host helpers' removal. Since 0.67.0 the server never makes its host do anything (the user,
# 2026-11-04): no root watcher for its requests (update, restart, apt, Tailscale, reboot, format a
# drive, restore), no root scheduler for the system's updates, no root copy of the backups to a
# drive. An install from before has them; this removes them, the last time root touches them. A
# helper that is running right now is never stopped (an older server's "Update now" runs update.sh
# inside the watcher's own service): with its unit gone it ends by itself. A backup drive mounted
# at /mnt/kid-phone-server-backup and its /etc/fstab line are the owner's and stay. The same text
# in install.sh and update.sh.

# record_version vX.Y.Z: the installed server version, into VERSION_FILE (root's).
record_version() {
    install -d -m 755 "${VERSION_FILE%/*}"
    printf '%s' "$1" >"$VERSION_FILE.new"
    mv -f "$VERSION_FILE.new" "$VERSION_FILE"
}

remove_host_helpers() {
    local unit current
    for unit in kid-phone-server-updater.path kid-phone-server-scheduler.timer kid-phone-server-backup-sync.timer; do
        systemctl disable --now "$unit" >/dev/null 2>&1 || true
    done
    for unit in kid-phone-server-updater kid-phone-server-scheduler kid-phone-server-backup-sync; do
        rm -f "/etc/systemd/system/$unit.service"
        rm -rf "/etc/systemd/system/$unit.service.d"
    done
    rm -f /etc/systemd/system/kid-phone-server-updater.path \
        /etc/systemd/system/kid-phone-server-scheduler.timer \
        /etc/systemd/system/kid-phone-server-backup-sync.timer
    # The version the downgrade check reads moves out of the directory that goes.
    current="$(installed_version)"
    if [ -n "$current" ] && [ ! -f "$VERSION_FILE" ]; then
        record_version "$current"
    fi
    # Root's alone (0700): actions.sh, watcher.sh, scheduler.sh, backup_sync.sh, their run dates.
    rm -rf "$OLD_UPDATER_DIR"
    systemctl daemon-reload
}

TAG="$(pick_tag)"
if [ -z "$TAG" ]; then
    echo "No server-vX.Y.Z release found in $REPO - not updating." >&2
    exit 1
fi
NEW_VERSION="${TAG#server-}"
refuse_downgrade "$NEW_VERSION"

TMP_EXTRACT="$(mktemp -d)"
trap 'rm -rf "$TMP_EXTRACT"' EXIT
# Download, verify and unpack everything *before* stopping the service: a 404, a GitHub error
# page, a truncated or altered download must fail here, with the old version still running.
download_release "$TMP_EXTRACT"
remove_host_helpers

echo "Stopping service..."
systemctl stop kid-phone-server
# From here on, any failure (full disk during the backup, a failed copy) must not leave the
# server stopped: start whatever is installed and exit with the error.
trap 'echo "Update failed - starting the service again." >&2; systemctl start kid-phone-server || true' ERR

# Taken with the service stopped, so the database files are consistent. The
# fixed path matches install.sh's DATABASE_URL; .env itself is never read
# here, since the service user can write it and this runs as root. The copies
# go to a root-owned directory outside data/: the service user owns data/ and
# could otherwise swap in a symlink that the cleanup below would follow.
BACKUP_ROOT="/var/backups/kid-phone-server"
BACKUP_DIR="$BACKUP_ROOT/$(date +%Y%m%d-%H%M%S)"
echo "Backing up the database and current binary to $BACKUP_DIR ..."
mkdir -p -m 700 "$BACKUP_ROOT"
mkdir -p "$BACKUP_DIR"
# Plain files only, never through a link the service user put in data/ (QA of the security wave,
# Low 2): the copy is root-only, and a rollback copies it back.
for f in kidphone.db kidphone.db-wal kidphone.db-shm; do
    if [ -f "$INSTALL_DIR/data/$f" ] && [ ! -L "$INSTALL_DIR/data/$f" ]; then
        cp -P -p "$INSTALL_DIR/data/$f" "$BACKUP_DIR/$f"
    fi
done
cp -p "$INSTALL_DIR/kid_phone_server" "$BACKUP_DIR/kid_phone_server"
chmod 700 "$BACKUP_DIR"

echo "Installing update..."
cp "$TMP_EXTRACT/kid_phone_server" "$INSTALL_DIR/kid_phone_server"
chmod +x "$INSTALL_DIR/kid_phone_server"

rm -rf "$INSTALL_DIR/static"
cp -r "$TMP_EXTRACT/static" "$INSTALL_DIR/static"

chown -R "$SERVICE_USER:$SERVICE_USER" "$INSTALL_DIR/kid_phone_server" "$INSTALL_DIR/static"

# data/ is the service user's alone, and so is everything the server writes from now on
# (security review M2): installs from before 0.24.0 get the unit's UMask as a drop-in and their
# files tightened. chmod runs as the service user, so a symlink in data/ can't point it anywhere
# else. The same in install.sh.
if [ -d "$INSTALL_DIR/data" ] && [ ! -L "$INSTALL_DIR/data" ]; then
    chmod 700 "$INSTALL_DIR/data"
    runuser -u "$SERVICE_USER" -- find "$INSTALL_DIR/data" -xdev \
        \( -type d -exec chmod 700 {} + \) -o \( -type f -exec chmod 600 {} + \) 2>/dev/null || true
fi
mkdir -p /etc/systemd/system/kid-phone-server.service.d
printf '[Service]\nUMask=0077\n' >/etc/systemd/system/kid-phone-server.service.d/umask.conf
systemctl daemon-reload

echo "Starting service..."
trap - ERR
systemctl start kid-phone-server

sleep 2
if systemctl is-active --quiet kid-phone-server; then
    # Pruned only now that the new version runs: after a bad update, retries would otherwise
    # each back up the already-migrated DB and push out the pre-migration copy that a rollback
    # needs. Keep the newest 3 (directory names sort by time).
    ls -1d "$BACKUP_ROOT"/*/ 2>/dev/null | sort | head -n -3 | xargs -r rm -rf
    record_version "$NEW_VERSION"
    echo ""
    echo "Update complete and running."
else
    echo ""
    echo "The service didn't start cleanly — check 'systemctl status kid-phone-server'" >&2
    echo "and 'journalctl -u kid-phone-server -n 50' for details." >&2
    echo "The previous binary and database are in $BACKUP_DIR (see DEPLOY.md, Rolling back)." >&2
    exit 1
fi

#!/usr/bin/env bash
# Kid Phone Server — updater. Downloads the latest release and replaces the
# running app, without touching your .env. Migrations run automatically on
# the next startup - so before swapping anything, this copies the database
# and the old binary to /var/backups/kid-phone-server/<timestamp>/ (the last
# 3 are kept).
# An older binary refuses to start against a database that newer migrations
# have touched, so rolling back means restoring both - see DEPLOY.md.
#
# Usage (as root, e.g. via sudo):
#   curl -fsSL https://raw.githubusercontent.com/palchrb/vibb-launcher/main/server/deploy/update.sh | sudo bash
#
# KPS_REPO=owner/repo picks a different fork (same as install.sh). The
# root-side updater passes the repo it was installed from, and KPS_TAG=server-vX.Y.Z
# (the release this script was fetched from). KPS_ALLOW_DOWNGRADE=1 allows
# installing a release older than the installed one.

set -euo pipefail

REPO="${KPS_REPO:-palchrb/vibb-launcher}"
# The pre-monorepo server repo: its installs pass it in (the root-side updater remembers the
# repo it was installed from). Its releases now live in the monorepo.
if [ "$REPO" = "palchrb/kid-phone-server" ]; then
    REPO="palchrb/vibb-launcher"
fi
if ! [[ "$REPO" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]]; then
    echo "KPS_REPO must look like owner/repo, got: $REPO" >&2
    exit 1
fi
INSTALL_DIR="/opt/kid-phone-server"
SERVICE_USER="kidphone"

if [ "$(id -u)" -ne 0 ]; then
    echo "Please run this as root (e.g. 'sudo bash update.sh')." >&2
    exit 1
fi

if [ ! -f "$INSTALL_DIR/.env" ]; then
    echo "$INSTALL_DIR doesn't look like an existing install (no .env found)." >&2
    echo "Run install.sh first." >&2
    exit 1
fi

case "$(uname -m)" in
    aarch64) TARGET="aarch64-unknown-linux-musl" ;;
    *)
        echo "Unsupported architecture: $(uname -m)" >&2
        exit 1
        ;;
esac

# The repo is a monorepo that also publishes the launcher, whose stable releases are GitHub's
# "latest" - so the server's release is looked up in the releases list (newest first): the first
# server-vX.Y.Z that is neither a draft nor a prerelease. Never releases/latest. Plain grep/awk on
# the API's JSON (each release lists tag_name, then draft, then prerelease; asset objects have
# none of these keys). Same text in install.sh, update.sh and install.sh's actions.sh.
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
# the root-owned updater directory; installs from before that only have data/watcher_version
# (written by install.sh, at install time).
installed_version() {
    local f v
    for f in /opt/kid-phone-server-updater/installed_version "$INSTALL_DIR/data/watcher_version"; do
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

# KPS_TAG (set by the root-side updater, which fetched this script at that tag) or the newest
# stable server release.
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

# Downloads the release tarball and its .sha256 into $1 and checks the hash; exits on any failure.
download_release() {
    local dir="$1" asset="kid-phone-server-$TARGET.tar.gz" expected actual
    local url="https://github.com/$REPO/releases/download/$TAG/$asset"
    echo "Downloading $TAG from $url ..."
    curl -fsSL "$url" -o "$dir/$asset"
    curl -fsSL "$url.sha256" -o "$dir/$asset.sha256"
    expected="$(head -c 64 "$dir/$asset.sha256")"
    actual="$(sha256sum "$dir/$asset" | cut -c1-64)"
    if ! [[ "$expected" =~ ^[0-9a-f]{64}$ ]] || [ "$expected" != "$actual" ]; then
        echo "SHA-256 of $asset doesn't match $asset.sha256 - not installing." >&2
        exit 1
    fi
    tar -xzf "$dir/$asset" -C "$dir"
    rm -f "$dir/$asset" "$dir/$asset.sha256"
    if [ ! -s "$dir/kid_phone_server" ] || [ ! -d "$dir/static" ]; then
        echo "The download doesn't contain kid_phone_server and static/ - not installing." >&2
        exit 1
    fi
}

UPDATER_DIR="/opt/kid-phone-server-updater"

# Rewrites the root-side helper scripts this release changed, so an existing Pi gets their fixes
# without re-running install.sh (security review H1: scheduler.sh no longer sources data/). The
# text is the same as install.sh's (a server test keeps them equal). Written to a temp file and
# renamed, so a script running right now keeps its old copy.
refresh_root_scripts() {
    [ -d "$UPDATER_DIR" ] || return 0
    local tmp
    tmp="$(mktemp "$UPDATER_DIR/.scheduler.sh.XXXXXX")"
    cat >"$tmp" <<'SCHEDULER_EOF'
#!/usr/bin/env bash
# Root-run, every 10 minutes: the OS/Tailscale schedule from the Software updates page.
# schedule.conf lives in data/, which the unprivileged service writes, so it is never sourced or
# executed (security review H1): each key is read with grep and must match its pattern, else its
# default is used and the problem logged. A symlinked file is ignored. update.sh rewrites this
# script on every update (the same text is in install.sh and update.sh; a server test keeps them
# equal).
set -euo pipefail
# shellcheck source=/dev/null
source /opt/kid-phone-server-updater/actions.sh

CONFIG_FILE="/opt/kid-phone-server/data/schedule.conf"
LAST_RUN_FILE="/opt/kid-phone-server-updater/last_run_date"

[ -f "$CONFIG_FILE" ] || exit 0
if [ -L "$CONFIG_FILE" ]; then
    echo "scheduler: $CONFIG_FILE is a symlink - ignored" >&2
    exit 0
fi

# setting KEY DEFAULT PATTERN: the key's value if it matches PATTERN (an anchored regex), else
# DEFAULT.
setting() {
    local value
    value="$(grep -m1 "^$1=" "$CONFIG_FILE" | cut -d= -f2- || true)"
    if [[ "$value" =~ $3 ]]; then
        printf '%s\n' "$value"
    else
        if [ -n "$value" ]; then
            echo "scheduler: ignoring an invalid $1 in $CONFIG_FILE" >&2
        fi
        printf '%s\n' "$2"
    fi
}

FREQUENCY="$(setting FREQUENCY daily '^(daily|weekly|monthly)$')"
DAY_OF_WEEK="$(setting DAY_OF_WEEK 0 '^[0-6]$')"
DAY_OF_MONTH="$(setting DAY_OF_MONTH 1 '^([1-9]|1[0-9]|2[0-8])$')"
CHECK_TIME="$(setting CHECK_TIME 03:00 '^([01][0-9]|2[0-3]):[0-5][0-9]$')"
AUTO_APPLY_OS="$(setting AUTO_APPLY_OS false '^(true|false)$')"
AUTO_APPLY_TAILSCALE="$(setting AUTO_APPLY_TAILSCALE false '^(true|false)$')"
AUTO_REBOOT="$(setting AUTO_REBOOT false '^(true|false)$')"

TODAY="$(date +%F)"
LAST_RUN="$(cat "$LAST_RUN_FILE" 2>/dev/null || true)"
[ "$LAST_RUN" != "$TODAY" ] || exit 0

case "$FREQUENCY" in
    weekly)
        [ "$(date +%w)" = "$DAY_OF_WEEK" ] || exit 0
        ;;
    monthly)
        [ "$(date +%-d)" = "$DAY_OF_MONTH" ] || exit 0
        ;;
esac

NOW_MINUTES=$(( 10#$(date +%H) * 60 + 10#$(date +%M) ))
TARGET_MINUTES=$(( 10#${CHECK_TIME%%:*} * 60 + 10#${CHECK_TIME##*:} ))
DIFF=$(( NOW_MINUTES - TARGET_MINUTES ))
# Only fire in the 10-minute window right after the scheduled time (this
# runs on a 10-minute timer, so this is the precision that's actually
# achievable - good enough for background maintenance).
[ "$DIFF" -ge 0 ] && [ "$DIFF" -lt 10 ] || exit 0

echo "$TODAY" >"$LAST_RUN_FILE"

action_os_check
if [ "$AUTO_APPLY_OS" = "true" ]; then
    action_os_upgrade
fi
if [ "$AUTO_APPLY_TAILSCALE" = "true" ]; then
    action_tailscale_update
fi
if [ "$AUTO_REBOOT" = "true" ] && [ -f /var/run/reboot-required ]; then
    action_reboot
fi
SCHEDULER_EOF
    chown root:root "$tmp"
    chmod 700 "$tmp"
    mv -f "$tmp" "$UPDATER_DIR/scheduler.sh"
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
refresh_root_scripts

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
for f in kidphone.db kidphone.db-wal kidphone.db-shm; do
    if [ -f "$INSTALL_DIR/data/$f" ]; then
        cp -p "$INSTALL_DIR/data/$f" "$BACKUP_DIR/$f"
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

echo "Starting service..."
trap - ERR
systemctl start kid-phone-server

sleep 2
if systemctl is-active --quiet kid-phone-server; then
    # Pruned only now that the new version runs: after a bad update, retries would otherwise
    # each back up the already-migrated DB and push out the pre-migration copy that a rollback
    # needs. Keep the newest 3 (directory names sort by time).
    ls -1d "$BACKUP_ROOT"/*/ 2>/dev/null | sort | head -n -3 | xargs -r rm -rf
    mkdir -p /opt/kid-phone-server-updater
    echo -n "$NEW_VERSION" >/opt/kid-phone-server-updater/installed_version
    echo ""
    echo "Update complete and running."
else
    echo ""
    echo "The service didn't start cleanly — check 'systemctl status kid-phone-server'" >&2
    echo "and 'journalctl -u kid-phone-server -n 50' for details." >&2
    echo "The previous binary and database are in $BACKUP_DIR (see DEPLOY.md, Rolling back)." >&2
    exit 1
fi

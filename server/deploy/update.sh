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

# Security review M5: every release file root installs or runs is signed with the project's
# minisign key, pinned here (the same line in install.sh, update.sh and actions.sh - a server test
# keeps them equal; server/DEPLOY.md, "Release signing"). The trusted comment names the file and
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

UPDATER_DIR="/opt/kid-phone-server-updater"

# Writes stdin to $UPDATER_DIR/$1 (root, 0700) through a temp file and a rename, so a script
# running right now keeps its old copy.
install_root_script() {
    local tmp
    tmp="$(mktemp "$UPDATER_DIR/.$1.XXXXXX")"
    cat >"$tmp"
    chown root:root "$tmp"
    chmod 700 "$tmp"
    mv -f "$tmp" "$UPDATER_DIR/$1"
}

# Rewrites the root-side helper scripts on every update, so an existing Pi gets their fixes
# without re-running install.sh (security review H1: scheduler.sh no longer sources data/; M4: the
# restore never writes through data/). The texts are install.sh's (a server test keeps them
# equal); no privileged action is added, so the watcher schema stays.
refresh_root_scripts() {
    [ -d "$UPDATER_DIR" ] && [ ! -L "$UPDATER_DIR" ] || return 0
    sed "s|@REPO@|$REPO|" <<'ACTIONS_EOF' | install_root_script actions.sh
#!/usr/bin/env bash
set -euo pipefail

# Filled in by install.sh (sed below) with the repo it was installed from.
REPO="@REPO@"
DATA_DIR="/opt/kid-phone-server/data"
BACKUP_DIR="$DATA_DIR/backups"

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

# Security review M5: every release file root installs or runs is signed with the project's
# minisign key, pinned here (the same line in install.sh, update.sh and actions.sh - a server test
# keeps them equal; server/DEPLOY.md, "Release signing"). The trusted comment names the file and
# the tag, so a signed file can't be passed off under another name or as another release.
SERVER_SIGNING_PUBKEY="RWQp6olF7G4WFeRgDWSWwq2/qOkClyqqXEzDBrPPf2EUrdZlAUQATU5g"

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

# Runs update.sh as the release it installs carries it (a release asset, signed - never a branch's
# copy), from a file: a failed, truncated or unsigned download is never executed.
action_app_update() {
    local tag dir rc=0
    tag="$(resolve_server_tag)"
    if [ -z "$tag" ]; then
        echo "app_update: no server-vX.Y.Z release found in $REPO" >&2
        return 1
    fi
    dir="$(mktemp -d)"
    if ! curl -fsSL "https://github.com/$REPO/releases/download/$tag/update.sh" -o "$dir/update.sh" ||
        ! curl -fsSL "https://github.com/$REPO/releases/download/$tag/update.sh.minisig" -o "$dir/update.sh.minisig"; then
        rm -rf "$dir"
        echo "app_update: couldn't download update.sh for $tag" >&2
        return 1
    fi
    if ! verify_signed "$dir/update.sh" update.sh "$tag"; then
        rm -rf "$dir"
        return 1
    fi
    KPS_REPO="$REPO" KPS_TAG="$tag" bash "$dir/update.sh" || rc=$?
    rm -rf "$dir"
    return "$rc"
}

action_app_restart() {
    systemctl restart kid-phone-server
}

action_os_check() {
    apt-get update -qq
}

action_os_upgrade() {
    apt-get update -qq
    DEBIAN_FRONTEND=noninteractive apt-get upgrade -y -qq
}

action_tailscale_update() {
    tailscale update --yes
}

action_reboot() {
    systemctl reboot
}

# Formats a removable drive as ext4 and mounts it at the external backup
# path. Defense in depth: independently re-validates the device from
# scratch - matches the expected /dev/sd[a-z] pattern, is actually marked
# removable by the kernel, and is definitely not whatever backs / or /boot
# - regardless of what the (unprivileged, internet-facing) app already
# checked before requesting this, since that process is exactly the one
# component that must never be trusted to have gotten this right on its own.
action_format_drive() {
    local device="$1"

    if [ -z "$device" ]; then
        echo "format_drive: no device specified" >&2
        return 1
    fi
    if ! echo "$device" | grep -qE '^/dev/sd[a-z]$'; then
        echo "format_drive: refusing to format '$device' — doesn't match the expected /dev/sd[a-z] pattern" >&2
        return 1
    fi

    local devname
    devname="$(basename "$device")"
    if [ ! -f "/sys/block/$devname/removable" ] || [ "$(cat "/sys/block/$devname/removable")" != "1" ]; then
        echo "format_drive: refusing to format '$device' — not marked removable by the kernel" >&2
        return 1
    fi

    local root_device boot_device
    root_device="$(findmnt -no SOURCE / | sed -E 's/p?[0-9]+$//')"
    if [ "$device" = "$root_device" ]; then
        echo "format_drive: refusing to format '$device' — this is the root filesystem's device" >&2
        return 1
    fi
    boot_device="$(findmnt -no SOURCE /boot 2>/dev/null | sed -E 's/p?[0-9]+$//' || true)"
    if [ -n "$boot_device" ] && [ "$device" = "$boot_device" ]; then
        echo "format_drive: refusing to format '$device' — this is the boot device" >&2
        return 1
    fi

    echo "Formatting $device as ext4..."
    umount "${device}"* 2>/dev/null || true
    mkfs.ext4 -F -q "$device"

    mkdir -p /mnt/kid-phone-server-backup
    local uuid
    uuid="$(blkid -s UUID -o value "$device")"

    # Replace any existing fstab entry for this mount point first, so
    # re-formatting a previously-configured drive doesn't leave stale
    # duplicate entries behind.
    sed -i '\#/mnt/kid-phone-server-backup#d' /etc/fstab
    echo "UUID=$uuid /mnt/kid-phone-server-backup ext4 defaults,nofail 0 2" >>/etc/fstab

    mount /mnt/kid-phone-server-backup
    echo "Drive formatted and mounted at /mnt/kid-phone-server-backup."
}

# Restores a named backup zip: stops the app, swaps in the backup's database, and restarts.
# data/ belongs to the unprivileged service user, so this root code never follows or writes
# through anything it finds there (security review M4): only the zip's kidphone.db is read, as a
# stream into a root-only temp directory, at most RESTORE_MAX_BYTES and only if it is an SQLite
# file; the live database is copied aside into a root-owned directory; and the restored file goes
# into place by rename, which replaces a symlink at the target instead of writing through it.
# Everything that can be checked happens *before* the app is stopped, so a bad request never takes
# the app down for nothing. Once stopped, an EXIT trap restarts it however this ends.
RESTORE_MAX_BYTES=$((1024 * 1024 * 1024))
PRERESTORE_ROOT="/var/backups/kid-phone-server-prerestore"

action_restore_backup() {
    local filename="$1"

    if [ -z "$filename" ]; then
        echo "restore_backup: no filename specified" >&2
        return 1
    fi
    case "$filename" in
        backup-*.zip) ;;
        *)
            echo "restore_backup: refusing to restore '$filename' — doesn't match the expected backup-*.zip pattern" >&2
            return 1
            ;;
    esac
    if echo "$filename" | grep -qE '/|\.\.'; then
        echo "restore_backup: refusing to restore '$filename' — invalid characters" >&2
        return 1
    fi
    if [ -L "$DATA_DIR" ] || [ -L "$BACKUP_DIR" ]; then
        echo "restore_backup: $DATA_DIR or $BACKUP_DIR is a symlink - refusing" >&2
        return 1
    fi

    local backup_path="$BACKUP_DIR/$filename"
    if [ -L "$backup_path" ] || [ ! -f "$backup_path" ]; then
        echo "restore_backup: '$backup_path' not found (or not a plain file)" >&2
        return 1
    fi

    local restore_tmp staged size magic
    restore_tmp="$(mktemp -d)"
    staged="$restore_tmp/kidphone.db"
    # Only kidphone.db, streamed: no other entry (a symlink, a path) is ever written anywhere, and
    # head stops a zip bomb at the limit (unzip then fails on the closed pipe).
    if ! timeout 900 unzip -p "$backup_path" kidphone.db 2>/dev/null | head -c "$((RESTORE_MAX_BYTES + 1))" >"$staged"; then
        echo "restore_backup: couldn't read kidphone.db from '$backup_path' (missing, damaged or larger than $RESTORE_MAX_BYTES bytes)" >&2
        rm -rf "$restore_tmp"
        return 1
    fi
    size="$(stat -c %s "$staged")"
    magic="$(head -c 16 "$staged" | od -An -tx1 | tr -d ' \n')"
    if [ "$size" -eq 0 ] || [ "$size" -gt "$RESTORE_MAX_BYTES" ] || [ "$magic" != "53514c69746520666f726d6174203300" ]; then
        echo "restore_backup: the kidphone.db in '$backup_path' is not an SQLite database (or too large) - aborting" >&2
        rm -rf "$restore_tmp"
        return 1
    fi

    echo "Stopping the app for restore..."
    systemctl stop kid-phone-server
    trap 'systemctl start kid-phone-server' EXIT

    # Safety copy of whatever's live right now, in a root-owned directory (never inside data/): the
    # newest 3 are kept. Only plain files are copied; a symlink in data/ is never followed.
    local prerestore_dir f incoming
    install -d -m 700 "$PRERESTORE_ROOT"
    prerestore_dir="$PRERESTORE_ROOT/$(date +%Y%m%d-%H%M%S)"
    mkdir -m 700 "$prerestore_dir"
    for f in kidphone.db kidphone.db-wal kidphone.db-shm; do
        if [ -f "$DATA_DIR/$f" ] && [ ! -L "$DATA_DIR/$f" ]; then
            cp -p "$DATA_DIR/$f" "$prerestore_dir/$f"
        fi
    done
    find "$PRERESTORE_ROOT" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort | head -n -3 \
        | while read -r old; do rm -rf "${PRERESTORE_ROOT:?}/$old"; done

    # Into place by rename: mktemp creates a new file (never an existing one), and mv -T replaces
    # whatever sits at the target - a symlink included - instead of writing through it.
    incoming="$(mktemp "$DATA_DIR/.restore.XXXXXX")"
    cat "$staged" >"$incoming"
    chown kidphone:kidphone "$incoming"
    chmod 600 "$incoming"
    rm -f "$DATA_DIR/kidphone.db-wal" "$DATA_DIR/kidphone.db-shm"
    mv -fT "$incoming" "$DATA_DIR/kidphone.db"
    rm -rf "$restore_tmp"
    # The next start forgets every admin session the backup holds (security review M2), and logs
    # the restore. The same new-file-and-rename as above.
    incoming="$(mktemp "$DATA_DIR/.restore.XXXXXX")"
    printf '%s' "$filename" >"$incoming"
    chown kidphone:kidphone "$incoming"
    mv -fT "$incoming" "$DATA_DIR/restore_applied"

    echo "Restore complete (the previous database is in $prerestore_dir)."
}
ACTIONS_EOF
    install_root_script watcher.sh <<'WATCHER_EOF'
#!/usr/bin/env bash
# Runs as root, triggered only when kid-phone-server (unprivileged) drops
# a flag file asking for one of a fixed set of actions.
set -euo pipefail
source /opt/kid-phone-server-updater/actions.sh

FLAG_FILE="/opt/kid-phone-server/data/update_requested"
# data/ belongs to the service user: only a regular file is read (security review M4; a FIFO
# would block the read, and with it every later request - QA of the security wave, Low 1), at most
# one short line of it, and the read is bounded in time for a file swapped after the check.
[ -e "$FLAG_FILE" ] || [ -L "$FLAG_FILE" ] || exit 0
if [ -L "$FLAG_FILE" ] || [ ! -f "$FLAG_FILE" ]; then
    rm -f "$FLAG_FILE"
    echo "The update request is not a regular file - ignored" >&2
    exit 1
fi
FLAG_CONTENT="$(timeout 5 head -c 256 -- "$FLAG_FILE" 2>/dev/null | head -n 1 || true)"
rm -f "$FLAG_FILE"

# Most actions are a single word; format_drive/restore_backup additionally
# carry a second, space-separated argument.
ACTION="${FLAG_CONTENT%% *}"
if [ "$ACTION" != "$FLAG_CONTENT" ]; then
    ARG="${FLAG_CONTENT#* }"
else
    ARG=""
fi

case "$ACTION" in
    update) action_app_update ;;
    restart) action_app_restart ;;
    os_check) action_os_check ;;
    os_upgrade) action_os_upgrade ;;
    tailscale_update) action_tailscale_update ;;
    reboot) action_reboot ;;
    format_drive) action_format_drive "$ARG" ;;
    restore_backup) action_restore_backup "$ARG" ;;
    *)
        echo "Unknown or empty update-request action: '$ACTION'" >&2
        exit 1
        ;;
esac
WATCHER_EOF
    install_root_script scheduler.sh <<'SCHEDULER_EOF'
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
    value="$(timeout 5 grep -m1 "^$1=" -- "$CONFIG_FILE" | cut -d= -f2- || true)"
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
    install_root_script backup_sync.sh <<'BACKUP_SYNC_EOF'
#!/usr/bin/env bash
set -euo pipefail

BACKUP_CONFIG_FILE="/opt/kid-phone-server/data/backup_schedule.conf"
EXTERNAL_MOUNT="/mnt/kid-phone-server-backup"
DATA_DIR="/opt/kid-phone-server/data"

[ -f "$BACKUP_CONFIG_FILE" ] || exit 0
mountpoint -q "$EXTERNAL_MOUNT" || exit 0
# data/ belongs to the service user: never follow a symlink it put there (security review M4).
if [ -L "$DATA_DIR" ] || [ -L "$DATA_DIR/backups" ] || [ -L "$BACKUP_CONFIG_FILE" ]; then
    echo "backup_sync: a symlink in $DATA_DIR - not copying" >&2
    exit 0
fi

EXTERNAL_COPY_ENABLED="$(timeout 5 grep -m1 '^EXTERNAL_COPY_ENABLED=' -- "$BACKUP_CONFIG_FILE" | cut -d= -f2 || true)"
CONTINUOUS_MIRROR_ENABLED="$(timeout 5 grep -m1 '^CONTINUOUS_MIRROR_ENABLED=' -- "$BACKUP_CONFIG_FILE" | cut -d= -f2 || true)"

# The copies hold the whole database: root's alone on the Pi (security review M2).
chmod 700 "$EXTERNAL_MOUNT"
# Root never follows, reads or writes through anything else the service user put in data/ (QA of
# the security wave, Medium 1): only regular files at the top of data/backups/ and a regular
# live_mirror.db are copied - never a symlink, a FIFO, a device or a directory. Each copy lands by
# --remove-destination, so whatever sits at the destination is replaced, never written through;
# -a never dereferences, so a file swapped for a link in between arrives as a link, and anything
# on the drive that isn't a regular file or a directory is deleted first, every run.
find "$EXTERNAL_MOUNT" -xdev -mindepth 1 ! -type f ! -type d -delete 2>/dev/null || true

if [ "$EXTERNAL_COPY_ENABLED" = "true" ] && [ -d "$DATA_DIR/backups" ]; then
    install -d -m 700 "$EXTERNAL_MOUNT/snapshots"
    find "$DATA_DIR/backups" -mindepth 1 -maxdepth 1 -type f -print0 |
        while IFS= read -r -d '' file; do
            cp -au --remove-destination -- "$file" "$EXTERNAL_MOUNT/snapshots/" 2>/dev/null || true
        done
fi

if [ "$CONTINUOUS_MIRROR_ENABLED" = "true" ] && [ -f "$DATA_DIR/live_mirror.db" ] &&
    [ ! -L "$DATA_DIR/live_mirror.db" ]; then
    install -d -m 700 "$EXTERNAL_MOUNT/live"
    cp -au --remove-destination -- "$DATA_DIR/live_mirror.db" "$EXTERNAL_MOUNT/live/kidphone.db" 2>/dev/null || true
fi
BACKUP_SYNC_EOF
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
# The root helpers are oneshots, which have no start timeout by default: one hung on something in
# data/ would block every later request (QA of the security wave, Low 1). install.sh has the same
# values in the units.
for helper in updater:2h scheduler:2h backup-sync:1h; do
    mkdir -p "/etc/systemd/system/kid-phone-server-${helper%%:*}.service.d"
    printf '[Service]\nTimeoutStartSec=%s\n' "${helper##*:}" \
        >"/etc/systemd/system/kid-phone-server-${helper%%:*}.service.d/timeout.conf"
done
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

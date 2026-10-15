# QA (code) - the launcher's catalog row, created at startup

Reviewer: code QA, 2026-10-15. Branch `seed-launcher-row`, one commit, `38c71d7c` ("Server: create the launcher's
catalog row automatically"), on `369c07cd`. I read it against `server/CLAUDE.md` ("This fork", the global-catalog /
`is_launcher` bullets, M10 `check_pins`/`check_new_package`/`store_pins`, `release_allowed`), `server/DEPLOY.md`
("The launcher's own self-update"), `docs/testing/emulator.md` (§1, §6d) and the code it builds on (`config.rs`,
`main.rs`'s startup order, `AssetFilter::matches`, `set_is_launcher`, `delete_tracked_app`). Line numbers are
`38c71d7c`'s `server/src/handlers/tracked_apps.rs` unless named. No product code was changed; the mutations were
temporary and reverted (`git status` clean before this file was written).

**Verdict: sound for the default install, merge after a small fix round.** On a server with the default env the seed
does exactly what it should: one flagged row, `palchrb/vibb-launcher`, `kids-launcher-mdm.apk`, `launcher-v`,
pre-releases off, enabled, idempotent, no package/key so M10 pins both at the first sync, and a refused first release
stores nothing and serves nothing. The two Mediums are about the non-default servers - a test/debug server and a
self-hosted `LAUNCHER_APK_URL` - where the seed now pushes upstream's release launcher to every phone without anyone
having asked for it. Both are one- or two-line fixes.

Checked and fine:
- **Repo parse** (`launcher_repo_from_url`, `:2192-2201`): `https://` only, host exactly `github.com`/`www.github.com`
  (not `github.com.evil.org`, not `http://`), third segment exactly `releases`, then `config::is_valid_repo`. The repo
  is bound as an SQL parameter and only ever reaches `api.github.com/repos/<repo>/...` - no injection. (`is_valid_repo`
  accepts `.`/`..` parts, but the host is fixed and the value is the admin's own env; shared with
  `SERVER_RELEASE_REPO`, not this change's.)
- **Asset filter / prefix**: `AssetFilter::matches` requires `.apk`, so the release's `kids-launcher-mdm.apk.sha256`
  can never be picked; `launcher-v` plus `release_allowed` keeps `server-v*`/`music-v*` out; pre-releases off keeps
  `-rc.N` out (`the_created_row_takes_only_stable_launcher_releases`).
- **Ordering**: `main.rs:437`, after the migrations and `state` setup, before `remove_partial_downloads`, the sync's
  spawn and the listeners (`:536`). Called unconditionally - Docker and systemd alike.
- **DB errors**: `Err` -> `tracing::error!`, start continues (`a_database_error_does_not_stop_the_start`). The early
  `return Ok(..)` drops the transaction (rollback of a read-only tx) - fine.
- **Idempotence**: the `is_launcher = 1` count; a second start returns `Exists` (tested twice over).
- **Gates**: `cargo fmt --check` clean; `cargo clippy --all-targets --locked -j 13` - 13 warnings, all pre-existing
  dead code in `models.rs`/`dns_engine.rs`/`devices.rs`/`storytel.rs`, none in the diff; `cargo test` 963 passed;
  `node jstest/*.test.js` 3/3 ok.

## Findings

### Medium 1 - a debug/test or fork server now pushes upstream's release launcher to every phone

`seed_launcher_row`, INSERT at `:2273-2282` (`package_name` `''`). The row pins nothing, so the first sync accepts
whatever package upstream's newest `launcher-v*` release is (`me.vibb.launcher`) and, being `is_launcher`, serves it to
every phone. Before this change that needed an admin to add the row; now it happens on the first start of any server
with no flagged row:
- **the emulator loop** (`docs/testing/emulator.md` §1/§6d: debug launcher `me.vibb.launcher.debug`, a local server,
  provisioned with adb, the env left at its defaults): the emulator gets the release Vibb One installed as a **second**
  launcher app next to the debug Device Owner, and §6d's "add a manual app, mark it as the launcher" then makes two
  flagged rows (`set_is_launcher`, `:2321`, isn't exclusive). Every emulator QA run since this change starts with a
  stray app on the phone;
- **a fork with its own applicationId** (`LAUNCHER_ADMIN_COMPONENT=<their package>/...`, `LAUNCHER_APK_URL` on their
  GitHub repo but before their first `launcher-v*` release - or Medium 2's fallback): upstream's APK goes to every
  phone as a second app.

**Fix**: pin the package the server already knows the launcher is - bind `package` (from `LAUNCHER_ADMIN_COMPONENT`,
`:2244-2248`, already computed) as `package_name` in the INSERT. M10's `check_pins` then refuses a release of any
other package (`OtherApp`, shown on the row; nothing stored, nothing served), and the signer still pins at the first
sync. On a default server it is `me.vibb.launcher`, the same thing the first sync would pin. Plus one line in
`emulator.md` §4: a test server sets `LAUNCHER_ADMIN_COMPONENT=me.vibb.launcher.debug/com.kidslauncher.mdm.server.MdmDeviceAdminReceiver`
(the seeded row then refuses the release builds harmlessly), or unticks Enabled on the seeded row. Test:
`a_fresh_database_gets_the_launchers_row_once` asserts `package_name == "me.vibb.launcher"`, and a debug
`LAUNCHER_ADMIN_COMPONENT` gives the debug package. Docs: "no package or key" -> "the launcher's package (from
`LAUNCHER_ADMIN_COMPONENT`), no key" in `server/CLAUDE.md` and DEPLOY.md.

### Medium 2 - a non-GitHub `LAUNCHER_APK_URL` falls back to upstream's repo

`:2240-2243`. The fallback to `DEFAULT_SERVER_RELEASE_REPO` is only reached when the admin deliberately set
`LAUNCHER_APK_URL` to something that isn't a GitHub releases URL (the default derives to `palchrb/vibb-launcher`
already) - i.e. a self-hosted mirror or a fork's own host, exactly the setups where upstream's launcher is the wrong
one. The result is an info log and a flagged row pushing upstream's release to every phone: with the same package and
another key Android refuses the update on every phone at every sync (the launcher's REFUSE_RELEASE path); with another
package it installs a second app (Medium 1's fix turns that into a server-side refusal, but the row is still wrong).

**Fix**: don't guess - when `launcher_repo_from_url` is `None`, create nothing and warn ("LAUNCHER_APK_URL isn't a
github.com/<owner>/<repo>/releases URL - add the launcher's row under Apps and turn on This is the launcher app").
Less code: the `derived` field and the `if !derived` log go; the second half of
`the_row_watches_the_repo_of_launcher_apk_url` asserts no row. DEPLOY.md's "else `palchrb/vibb-launcher`" goes too.

### Low 1 - a hand-made launcher row from a manual upload isn't recognised

The "looks like the launcher" query, `:2257-2262`: `source_type = 'github'` wraps both branches, so the package branch
never matches a manual-upload row. A row pinned to the launcher's package that the admin uploads APKs to by hand
(§6d's flow, or a family that sideloads its builds) and hasn't flagged gets a second, flagged GitHub row next to it.
**Fix**: scope `source_type = 'github'` to the repo branch only:
`WHERE (source_type = 'github' AND LOWER(github_repo) = LOWER(?) AND (...)) OR (package_name != '' AND package_name = ?)`,
and add a manual row (`source_type = 'manual'`, package `me.vibb.launcher`) to `a_hand_made_launcher_row_gets_no_duplicate`.

On the width of the test otherwise: it is about right. Too wide costs only "no row, a warning" (status quo before
this change); too narrow costs a duplicate. A renamed repo is covered by migration 0031 and GitHub's redirect, a fork
by the package branch once its row has synced once (`store_pins` pins it). The one gap left is a fork's row in
another repo that has never synced - narrow and self-healing at the restart after its first sync; not worth more SQL.

### Nit 1 - the HandMade warning tells a parent who unflagged on purpose to flag again

`:2291-2295`. A parent who turned the flag off deliberately (the one case the no-silent-flagging rule protects) gets
"turn on This is the launcher app" at every start. Word it as "if '{name}' (id {id}) is the launcher, turn on ...".

### Nit 2 - the transaction comment overclaims

`:2235`: "In one transaction, so a second start (or a crash half-way) never makes two rows" - a second start is safe
because of the `is_launcher` check, and a crash half-way leaves nothing because there is one write. The transaction
only matters for two processes starting at once on one database. Say that, or drop the transaction and the comment.

## The two policy questions

- **Is a log warning enough for the hand-made case, or an Attention row on the Apps tab?** The log is enough. The
  HandMade state is exactly what every such install had before this change (an unflagged launcher row that phones
  get only where it is selected), so nothing regresses, and the fix is one switch the DEPLOY.md text names. An
  Attention row would need a persisted "suggest flagging id N" state and a dismiss for the deliberate-unflag case -
  more code than the problem. Revisit only if a real parent hits it.
- **Should a deliberate deletion be remembered?** No - accept it as documented. Deleting needs the flag off first
  (`delete_tracked_app`), the documented off switch is Enabled, and "the launcher always has a row" is the property
  the user asked for. Remembering would need new state (there is no generic settings table; a migration for one
  boolean) and would re-open the "no launcher updates and nobody knows" hole this change closes. The only real harm of
  resurrection was a server that shouldn't serve upstream's launcher at all, and that is Mediums 1-2.

## Mutations (all temporary, reverted)

Run with `cargo test tracked_apps::tests` (27 tests).

| # | Mutation | Result |
|---|----------|--------|
| 1 | INSERT with `include_prereleases = 1` | killed - `a_fresh_database_gets_the_launchers_row_once`, `the_created_row_takes_only_stable_launcher_releases` |
| 2 | INSERT drops the prefix (`NULLIF(?, 'launcher-v')`) | killed - `a_fresh_database_gets_the_launchers_row_once` (the field assertion only: with no prefix `release_allowed` still keeps `server-v`/`music-v` out, so the release-pick test passes) |
| 3 | duplicate check off (`hand_made.filter(\|_\| false)`) | killed - `a_hand_made_launcher_row_gets_no_duplicate` |
| 4 | repo parse accepts any host (`split_once('/')` instead of `strip_prefix("github.com/")`) | killed - `the_launchers_repo_comes_from_a_github_releases_url` |
| 5 | package branch off (`OR (0 AND package_name = ?)`) | killed - `a_hand_made_launcher_row_gets_no_duplicate` |

5/5 killed. Missing pins after the fix round: Medium 1's package assertion, Medium 2's "no row", Low 1's manual row.

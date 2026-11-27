# 140 - A launcher release names the oldest server it needs, and an older server holds it back

Status: design note, revision 2, 2026-11-27, on `main` at 218627933, branch `min-server-design`. Approved in principle
by the owner on 2026-11-27 ("stopp auto oppdatering av vibb launcher om man egentlig er på en for gammel server
utgave"); the owner answered Q1 the same day: (a), no QR while the QR's Vibb One needs a newer server (§12). Revision
2 takes the design QA (0H 6M 11L, all taken; §13). Revision 1 was 4e14d87a8. Both apps' builds, the three release
workflows, one script, the server; one migration (0187), one new contract file. No phone-side behaviour change, so no
Android 14-17 notes (§11).

## 0. Short answer

- **The finding.** A server takes the newest `launcher-v*` release for its launcher row (prereleases too, when the
  parent switched them on) whatever the server's own version. On 2026-11-27 rc.7 was published while server 0.95.0
  still waited for signing: a family's 0.94.1 would have handed it to the phones. C1's code QA found the same hazard
  (the ship order flipped: a C1 launcher on a pre-C1 server would have lost `unified_push` and unregistered Element X).
  It hits every self-hosting family, not only ours: they update the server when they get to it, the launcher row
  follows GitHub at once.
- **The value lives inside the signed APK**, not in a separate release asset: a text file `assets/vibb/min_server`
  (`0.95.0`), written by Gradle in every build, from `server/Cargo.toml`'s version - in a tag build by
  `scripts/min-server.sh`, which adds one patch when the server's code the app talks to moved since that version's
  tag (§2). The server already downloads the APK before it serves it, the setup QR's check already downloads the QR's
  APK, and a manual upload brings the file itself: all three read the value with no new fetch and no new way to fail.
- **The server holds a release that needs a newer server** in design 90's held-release slot with a new reason
  `server`: downloaded, kept, never served, never nudged, no inbox row or push. Install Now only ever sends the served
  release, so it can't reach a phone. A manual upload that needs a newer server is refused. One orange line where a
  held release shows today; it goes out by itself at the first start of a server that is new enough.
- **The music app the same way** (§5): the server serves `music-v*` releases through its own catalog row, and the
  music app calls the server's routes (`/api/devices/music/...`).
- **The setup QR blocks** while its launcher needs a newer server (§6, Q1 (a)), and the check looks again as soon as
  `releases/latest` moves.
- **Fail closed** on a release from the first tag that carries the value: no value, or one that can't be read, is
  refused like an unreadable APK. A release from before carries none and is served as today (§7).

## 1. Why not a release asset (`kids-launcher-mdm.json`)

The first proposal was a JSON asset next to the APK. Weighed:

| | Release asset `{"min_server": ...}` | Inside the APK (`assets/vibb/min_server`) |
|---|---|---|
| Integrity | Unsigned. A `.sha256` beside it adds nothing: whoever can swap the JSON can swap its hash. Signing it needs a key in the `release` environment and a pinned public key on the server - a second trust chain. | Inside the v2/v3-signed APK. The server reads the certificate but does not verify the signature (`apk.rs`), so a forged file reaches the server - but the phone verifies it on install and refuses it. A lowered value therefore only ever travels with an APK no phone installs; a raised one only holds a release, which deleting the release does too. |
| Fetches | One more per release, with its own failure (missing, 404, rate limit, a draft without it - immutable releases since 2026-11-26 take no asset once published). | None: the sync, the QR check and an upload already have the file. |
| Manual upload of the launcher | Has no asset to read. | Carries it. |
| Forks | Must copy the workflow step. | Any build of this tree writes it (Gradle). |
| Readable by a person | Yes. | The release body says it (§3), from the same script. |

Manifest `<meta-data>` was the third option: signed the same way, but the server would need a new walk of nested
elements under `apk.rs`'s by-resource-id discipline (design 90's parser rules). A ZIP entry is one `by_name` read and
a line of text. The APK's asset is the simpler and safer shape.

Signing and the build are otherwise unaffected (QA L11): the entry is an ordinary asset inside the unsigned APK
before `zipalign` and `apksigner`, `RELEASE_CERT_SHA256` checks the same certificate, and the value is a pure function
of the tagged commit and the tags present when it is built. A later rebuild of an old tag may compute another value
(case 2 becomes case 3 once the server tag exists); the signed release on GitHub is what counts.

## 2. The value: which server a build needs

`scripts/min-server.sh <launcher|music>` (bash and git, `--selftest`; run by `launcher.yml`'s and `music.yml`'s
always-run changes jobs, the same pattern as `release-version.sh`) prints `X.Y.Z` and, on stderr, which case gave it:

0. **Refuses to guess** (QA L3): it finds the repo by `git rev-parse --show-toplevel` (Cargo.toml at
   `<top>/server/Cargo.toml`, wherever it is called from); a shallow clone (`git rev-parse --is-shallow-repository`)
   or a repo with no `server-v*` tag at all fails - both mean the tags weren't fetched, and case 2 would then answer
   wrongly. (Both release jobs already check out with `fetch-depth: 0`.)
1. `V` = `server/Cargo.toml`'s `version` (must be `X.Y.Z`, else it fails).
2. No tag `server-vV`: `V`, "not yet tagged" - an unreleased bump. §2.1's guard makes sure the server tagged `V` later
   carries this commit's server code.
3. `server-vV` is on `HEAD` or after it (`git merge-base --is-ancestor HEAD server-vV`): `V`.
4. `server-vV` is before `HEAD` and nothing this app talks to changed since (`git diff --quiet server-vV HEAD -- <the
   app's paths>`): `V`.
5. Else `X.Y.(Z+1)`: "any server released after `V`" - every later server tag is at or above it, whether the next one
   is a patch or a minor. Without this a launcher tagged after a server change that had no version bump yet would
   name the older server, which lacks the change.

**The app's paths** (QA M6) - what changes the server's behaviour towards that app:

| | Counted | Not counted |
|---|---|---|
| Both | `server/src`, `server/migrations`, `server/Cargo.toml`, `server/Cargo.lock` | `server/src/tests/`, any `server/src/**/tests.rs` and `tests/` module dir, `*.md` anywhere, `server/templates`, `server/static` (the PWA), `server/testdata`, `server/deploy` |
| `launcher` | `contracts/core/`, `contracts/integrations/`, `contracts/shipped/` (the server compiles in the DoH list) | `contracts/music/` |
| `music` | `contracts/music/` except `bridge_contract.json` (launcher to music, the server never reads it) | `contracts/core/`, `contracts/integrations/`, `contracts/shipped/` |

The server's own code is counted whole for both apps: telling a music route's change from a launcher route's change in
`server/src` would need a map of modules to apps that rots; a spurious case 5 costs only a wait for the next server
release, which the release body names. The path lists live once, in the script; the guard in §2.1 uses the same.

The owner tags the launcher on the same commit as the server (root CLAUDE.md, "a release still ships the server
first"), so case 3 or 4 is the usual one and the value is exactly that server. Case 5 holds a launcher until the next
server release; that is the point of the design.

### 2.1 The tagging rule and its guard (QA M5)

Case 2 trusts that `server-vV`, when it comes, is tagged on a commit that carries the app's server code. The rule:
**a `server-vV` tag goes on the same commit as, or a commit after, every `launcher-v*`/`music-v*` tag built while
`server/Cargo.toml` said `V`** - unless nothing the app talks to differs between the two. `server-release.yml`'s first
job enforces it before any build: `scripts/min-server.sh --check-server-tag vV` lists the `launcher-v*` and
`music-v*` tags whose commit has `version = "V"` in `server/Cargo.toml` and refuses the server tag when one of them
is not an ancestor of it and the app's paths differ between them ("launcher-v0.44.0-rc.1 was built for server V on a
commit this tag lacks: tag the server on it or later, or bump to V+1"). The rule goes into root CLAUDE.md's "CI and
releases" (one line) and `docs/notes/ci.md` "Server releases". `--selftest` covers the guard: an app tag before the
server tag (passes), after it with a counted change (refused), after it with only an uncounted change (passes).

### 2.2 Gradle

`launcher/app/build.gradle.kts`, `music/app/build.gradle.kts`: a small task writes
`build/generated/minServer/<variant>/vibb/min_server` and adds it to the variant's assets
(`androidComponents.onVariants { it.sources.assets?.addGeneratedSourceDirectory(...) }`). Its input is
`-PminServer=` when given, else `../server/Cargo.toml`'s version read through
`providers.fileContents(rootProject.layout.projectDirectory.file("../server/Cargo.toml"))` as a task input, so the
configuration cache stays valid (QA L6). A value that isn't `X.Y.Z` fails the build. `checkReleaseHasMinServer` (next
to `checkReleaseHasNoGoogleServices`) fails `assembleRelease` if the APK lacks the entry. A local release build without
`-PminServer` takes Cargo's version as it is - no git, so no case 5; only the tag builds apply the full rule.

## 3. The workflows

- `launcher.yml`'s `launcher-release-build` (already `fetch-depth: 0`): after "Derive version from tag",
  `min=$(../scripts/min-server.sh launcher)`, outputs `min-server` and `min-server-case`; the case goes to the job
  summary and as a `::notice::` annotation ("min server 0.95.1: server/src changed since server-v0.95.0");
  `assembleRelease ... -PminServer="$min"`.
- `launcher-release-publish`: the release action's `body:` (prepended to the generated notes) - "Needs Vibb server
  X.Y.Z or newer.", or in case 2 "Needs Vibb server X.Y.Z or newer (not yet tagged)."
- `music.yml`'s `music-release-build`/`-publish` the same, with `music`.
- `server-release.yml`: §2.1's guard as the first step of its first job.
- The changes jobs of `launcher.yml`, `music.yml` and `server-ci.yml` run `scripts/min-server.sh --selftest`; their
  path filters gain the script (`BuildChainTest` and `tests::deploy_scripts` check the filters).
- No new asset, no change to signing (§1).

## 4. The server

### 4.1 Reading it

- `apk.rs`: `ApkIdentity.min_server: MinServer` - `Absent`, `Unreadable` or `Version((u64, u64, u64))` - from the ZIP
  entry `assets/vibb/min_server`, read through `take(33)`: more than 32 bytes, or anything but exactly
  `^[0-9]{1,4}\.[0-9]{1,4}\.[0-9]{1,4}\n?$` (no leading zeros beyond `0`), is `Unreadable`. Duplicate entries aren't
  checked (QA L2): only the signer can make such a file, the phone never reads the entry, and a forged one fails on
  the phone (§1). Read for every APK; used only for ours.
- `catalog_holds::needs_server(ours, min_server, version_code, first_code, own) -> Serve | Hold(min) | Refuse`, pure:
  not ours `Serve`; `Version(v)` with `v > own` `Hold(v)`, else `Serve`; `Absent` with a `version_code` below the
  app's first code `Serve` (today); `Absent` at or above it, `Absent` with no `version_code` (QA L7), or `Unreadable`,
  `Refuse`. The first codes: `contracts/music/min_server.json`'s `launcher_first` and `music_first`, read as
  versionCodes by `launcher_tag_codes.json`'s rule.
- `own` = the server's own version: `APP_VERSION` (`main.rs`, `"v" + CARGO_PKG_VERSION`) parsed by Software Updates'
  own parser (`handlers::system_update::parse_version`, made `pub(crate)`), and every compare is that module's (`>` on
  the `(u64, u64, u64)`; QA L10). It is carried as `AppState`'s `server_version` so a test can set another;
  `server_version_is_x_y_z` pins that the real one parses.

### 4.2 The sync, the hold, the release

- `tracked_apps::sync_release`, ours only: `needs_server` runs after the pins and **before** the
  `first || app_wait(state, app).is_none()` short-circuit that serves ours and first releases at once (QA L1) - so a
  first release is held too, and the row then serves nothing (the copy has a variant, §10). `Refuse` is a sync failure
  (`SyncProblem`'s `apps.sync.unreadable`, "Couldn't read the APK" - nothing stored, nothing held, the next release can
  replace it). `Hold(v)` goes through design 90's `hold_release` with `reason = 'server'` and `min_server = v`: the file
  kept next to the served one, nothing nudged, the same `(tag, asset)` "nothing to do" at the next sync, a newer
  release held replaces it, a newer one served supersedes it, `remove_orphan_files` keeps its file.
- **No inbox row, no push** (QA M1): `hold_release` raises `parent_events::app_update_held` ("waits for your
  approval") for every reason today; it skips it for `server`.
- **Only the newest release is judged** (QA L8): ours have no wait, so `catalog_holds::pick` takes the newest listed
  release; if that one is held, an older release between it and the served one is not tried. Served rc.5, rc.6
  servable, rc.7 held: the phones stay on rc.5 until the server update brings rc.7. Rare (two releases between server
  updates, only the newer needing more), and the phones then skip straight to the newest.
- **Manual upload** (QA M3): `upload_tracked_app_release` writes `latest_release_*` directly, the launcher row
  included. For ours it runs `needs_server` on the uploaded file first: `Hold` and `Refuse` are refused with a flash
  on the upload group (400, nothing stored, design 90's upload refusals' shape) - an upload is the parent's own act, so
  it is told at once rather than parked.
- **Out by itself**: `catalog_holds::release_server_holds` at start (after the backfills and `remove_orphan_files`,
  before the hourly sync's first pass), for each held row with `reason = 'server'`:
  - still ahead of the served release (`tracked_apps::ahead`, the sync's own rule)? If not - an upload or a newer
    served release overtook it - the held row and its file go, nothing is served (QA M3);
  - its stored `min_server` and the file's own value both at or below `own`: design 90's Approve move without the
    parent - the file read again (pins, SHA-256, the value), into `latest_release_*` in one transaction, the held row
    deleted, the old file removed, the phones nudged; Security Log `catalog_release_server_ready` (no inbox row, no
    push). Else it stays held.

  The version only changes with a restart, so the start is enough.
- Approve and Keep never show for a `server` hold, on any page (§4.3); their POSTs answer `stale`. Update Now and the
  wait don't apply to ours (design 90). Install Now sends `latest_release_*` only (design 37), so a held release can't
  be installed by it; its rows read Up to date on the served one.
- A server rolled back below a release it already served: nothing is pulled back (no step back, the phones keep what
  they run) and nothing is shown - a deliberate host action of the owner.
- **Migration 0187** (0186 is design 139's, on `app-groups`; check the next free number at build time): rebuild
  `tracked_app_held_releases` (SQLite can't alter a CHECK) - new table with every 0141 column, the key kept as
  `tracked_app_id INTEGER PRIMARY KEY REFERENCES tracked_apps(id) ON DELETE CASCADE` (QA L9), `reason IN
  ('permissions', 'unreadable', 'always_ask', 'server')`, a column `min_server TEXT` and `CHECK (reason <> 'server' OR
  min_server IS NOT NULL)`; rows copied as they are, the old table dropped, the new one renamed (the pattern of 0099
  and 0175). Nothing references this table, so no other table is touched.

### 4.3 The pages (QA M2)

Every page that shows a held release today, for a `server` hold: the orange line in place of "waits for you", no
Approve or Keep.

- The catalog app's page (`tracked_app_detail.html`, `#update`, launcher and music rows): the line and its (i).
- A phone's app page (`handlers/app_page.rs`, `held_for_page`): the same line and (i).
- Device › Apps (`handlers/device_pages.rs`, `held_versions`, design 127 §2.2's orange part): the line without the (i);
  the row links to the app page.
- The Apps tab: the row's badge "Waits for the server" in place of "Waits for you", same rank.
- Software Updates (`updates.html`, the server group, under the version rows): one orange line per held app, no (i) -
  the page itself says how to update.
- The setup QR group (`partials/launcher_check.html`): §6.

`held_for_page` and `held_versions` return the reason and `min_server` with the release, so one helper words all four.

## 5. The music app: the same

The server serves `music-v*` releases from the music catalog row (`catalog_holds::ours`), and the music app talks to
the server itself (`/api/devices/music/library`, the listing, Storytel and Spotify through the server - design
21/26b/26c), so a new music app against an old server breaks the same way. The mechanism costs nothing more: the same
script and Gradle task, the same `needs_server` (it already knows ours), the same hold. Not covered: a music release
that needs a newer launcher (the bridge AIDL) - a different pair, not asked for.

## 6. The setup QR's check (`launcher_check.rs`)

The QR's address is fixed in `.env` (`LAUNCHER_APK_URL`, by default GitHub's `releases/latest/download/...`): the new
phone downloads it straight from there, so the server can't pick another release for it - it can only judge the one
the address serves. The owner chose (Q1 (a)): no QR while that one needs a newer server.

- `judge` gains `Verdict::NeedsServer { version, server }` from the downloaded APK's value (the check already has the
  file), ranked after `WrongPackage`/`WrongSigner`/`TooOld`; `blocks_qr()` is true: a phone set up with a launcher
  this server can't serve would enrol against routes it doesn't have, and its catalog row would then hold back the
  very release the phone runs. `Absent`/`Unreadable` at or above the first code is `NotAnApk` ("not a readable Vibb
  One").
- **The result follows `releases/latest`** (QA M4). A result stands an hour (`RESULT_MAX_AGE`), but the address can
  serve another APK the moment a stable release is published - a passing result would then show a QR for an APK
  never checked. So when a page asks for a result older than 5 minutes (`FRESH_FOR`), `seen` compares it with the
  newest stable `launcher-v*` tag `known_releases` lists (the release list read at most once per 5 minutes, kept with
  the result) against the checked APK's version; on a mismatch it forgets the result and starts a check. "Checking"
  already blocks the QR (`Line::blocks_qr`), so the parent waits the download, never gets a QR for an unchecked APK.
  A failed list read keeps the result (the APK check is the gate; the list only tells it moved). Only for the default
  GitHub address or one whose repo `launcher_repo_from_url` reads; any other address keeps the hour.
- `known_releases`' "a newer release exists" note (`Older`) is unchanged.

## 7. Fail closed or open

| Case | What happens | Why |
|---|---|---|
| A release from before the first tag, no value | Served, as today | It can't carry one; holding every old release would freeze the row. |
| A release at or above the first tag, or with no versionCode, no value or unreadable | Refused (sync failure), the served one stays | Our Gradle writes it in every build, so its absence is a broken or foreign build; serving it would be exactly the bug. The next good release replaces it. |
| A value above the server | Held, out by itself after the server update; an upload refused | The design. |
| A forged value (APK swapped on GitHub) | Raised: held - no worse than a deleted release. Lowered: served, and every phone refuses it (signature) | The phone's signature check is the gate; the server's read needs no trust of its own. |
| A network failure | Nothing new: the release list and the download fail as today, the served release stays | No new fetch exists; the QR's freshness read keeps the last result on failure. |
| The QR's APK at or above the first tag, no value | `NotAnApk`, no QR | Fail closed, as every other QR verdict. |
| `releases/latest` moved since the last QR check | Checked again before a QR shows | §6. |
| A build without tags, or a shallow clone | The script fails, no release | §2, step 0. |
| The server's own version | Compiled in, never unknown | - |

## 8. Steps and files

One branch, two commits (the contract with both sides in the first, design 133's rule):

1. **Build side + contract**: `contracts/music/min_server.json` (the entry's path, the format, `launcher_first`,
   `music_first`, valid and invalid rows; in `contracts/music/` because the server, the launcher and the music app all
   read it and only that folder is in all three filters - `music.yml` watches `contracts/music/`, the other two the
   whole folder; QA L4) and its `contracts/README.md` row; `scripts/min-server.sh` with `--selftest` and
   `--check-server-tag`; `scripts/README.md`; both `build.gradle.kts` (task, generated assets,
   `checkReleaseHasMinServer`); a unit test in each app reading the contract's path and rows; `launcher.yml`,
   `music.yml`, `server-release.yml`, `server-ci.yml` (build, publish body, notice, the guard, changes jobs, filters);
   root `CLAUDE.md` (the tagging rule, the script's line in Layout), `docs/notes/ci.md`.
2. **Server**: `apk.rs` (`MinServer`), `catalog_holds.rs` (`needs_server`, `release_server_holds`, the `Server`
   reason), `handlers/tracked_apps.rs` (`sync_release`, `hold_release`'s event, `upload_tracked_app_release`,
   Approve/Keep's `stale`, `held_for_page`/`held_versions`), `handlers/app_page.rs`, `handlers/device_pages.rs`,
   `handlers/system_update.rs` (`parse_version` shared), `launcher_check.rs` (`NeedsServer`, the freshness compare),
   `handlers/updates.rs` + `updates.html`, `tracked_app_detail.html`, the phone's app page and Device › Apps
   templates, the Apps tab's badge, `partials/launcher_check.html`, `main.rs`/`AppState` (`server_version`, the start
   hook), migration 0187, `i18n/en.json` + `nb.json`, `docs/notes/server/play-and-updates.md` (design 90's section
   and the QR's), `docs/notes/server/data-model.md`.

`launcher_first`/`music_first` name the first tags built after step 1 merges (as design 129b's
`enrol_attempt_first`). Release order: as always, the server first; an older server ignores the entry, so either
order is safe, but only a server with this design holds anything.

Size: about 350 lines of server code, 500 of tests, 40 of Gradle per app, 200 of script with its selftest and guard,
30 of workflow - one build round, M. No emulator gate: nothing on the phone changes.

## 9. Tests

- `scripts/min-server.sh --selftest`, in a throwaway git repo: no tag (`V`, case 2); the tag on `HEAD`; the tag after
  `HEAD`; the tag before with only `launcher/`, `*.md`, `server/src/tests/`, a `tests.rs` or `server/templates`
  changed (`V`); with `server/src` changed (`X.Y.Z+1`, both apps); `contracts/music/music_library.json` changed (music
  `+1`, launcher `V`); `contracts/core/` changed (launcher `+1`, music `V`); `bridge_contract.json` changed (both
  `V`); a non-`X.Y.Z` Cargo version, a shallow clone and no `server-v*` tag fail; called from a subdirectory finds the
  same Cargo.toml; `--check-server-tag`'s three cases (§2.1).
- Both apps: the generated asset's path and format against `contracts/music/min_server.json`; a release build
  without it fails (`checkReleaseHasMinServer`).
- `apk::tests`: the entry read from a ZIP the test writes (absent, `0.95.0`, `0.95.0\n`, `00.1.0`, `0.95`, 33 bytes) -
  the signing-block fixtures stay as they are.
- `catalog_holds::tests`: `needs_server`'s table (not ours, equal, above, below, absent below and at the first code,
  absent with no versionCode, unreadable), the compare through `parse_version` (`0.100.0 > 0.99.9`).
- `tests::catalog_holds` / `handlers::tracked_apps::tests`, for the launcher row and the music row: a release above
  held with no nudge, no `app_update_held` event and no push, not in `/api/devices/apps`; a first release above held
  with nothing served; equal served; at-first-code without the entry refused; a newer held release replaces it; start
  with `server_version` raised serves it, nudges, logs; one whose file's value disagrees with the row stays held; one
  no longer ahead of the served release is dropped at start; Approve/Keep answer `stale`; an upload above or without
  the entry refused with its flash (`assert_lands`), an equal one stored; the migration keeps 0141's rows and the
  cascade on delete.
- `launcher_check::tests`: `NeedsServer` blocks, equal works, the line's words; a result older than `FRESH_FOR` with a
  newer stable tag listed is forgotten and reads "checking"; with the same tag, or a failed list read, it stands.
- Pages: the orange line and (i) on the catalog app page and a phone's app page, Device › Apps' line, the badge,
  Software Updates' line, no Approve/Keep on any; `nb_pages_have_no_english`; `i18n::tests`.

## 10. Copy (checked against root CLAUDE.md "Words in the UI")

| Where | en | nb |
|---|---|---|
| Catalog app page, a phone's app page, Device › Apps - orange | `{version} waits for server {server}` | `{version} venter på server {server}` |
| Its (i), something served (app pages only) | `The phones stay on {served} until this server runs {server} or newer; then {version} goes out by itself. Settings › Software Updates shows how to update.` | `Telefonene blir på {served} til serveren kjører {server} eller nyere; da går {version} ut av seg selv. Innstillinger › Oppdateringer viser hvordan du oppdaterer.` |
| Its (i), nothing served yet (QA L1) | `No phone gets {version} until this server runs {server} or newer; then it goes out by itself. Settings › Software Updates shows how to update.` | `Ingen telefon får {version} før serveren kjører {server} eller nyere; da går den ut av seg selv. Innstillinger › Oppdateringer viser hvordan du oppdaterer.` |
| Upload refused, flash on the upload group | `This file needs server {server}` | `Denne filen trenger server {server}` |
| Upload refused, no value | `Couldn't read which server this file needs` | `Fant ikke hvilken server denne filen trenger` |
| Software Updates, orange, per held app | `{app} {version} waits for server {server}` | `{app} {version} venter på server {server}` |
| Apps tab badge | `Waits for the server` | `Venter på serveren` |
| Setup QR, orange title | `Vibb One in the QR code needs server {server}` | `Vibb One i QR-koden trenger server {server}` |
| Setup QR, short visible line under it (QA L5) | `No QR code until the server is updated.` | `Ingen QR-kode før serveren er oppdatert.` |
| Setup QR, the fix behind its (i) | `Update this server to {server} or newer, or point LAUNCHER_APK_URL at an older release.` | `Oppdater serveren til {server} eller nyere, eller pek LAUNCHER_APK_URL til en eldre utgivelse.` |
| GitHub release body | `Needs Vibb server {server} or newer.` / `... or newer (not yet tagged).` | - (the repo is English) |

No push and no inbox row: a held update is a state, and the pages show it (Software Updates already says when a newer
server is out).

## 11. Android versions

None: the phones' code doesn't change; the asset is a file in the APK no code on the phone reads.

## 12. Decisions

- **Q1 - the setup QR while the stable Vibb One needs a newer server: (a)**, decided by the owner 2026-11-27. No QR
  and one orange line: update the server first. Not chosen: (b) the server pointing the QR at the per-tag address of
  the newest stable it can serve.
- The +1 patch rule can hold a launcher until the next server release; the release body names the server it waits
  for.
- A server rolled back below a served release: nothing is pulled back, nothing shown.
- No push or inbox notice for a held release.

## 13. Design QA of revision 1 (all taken)

- M1 `hold_release` raised the inbox and push for every reason: skipped for `server` (§4.2, test in §9).
- M2 a phone's app page and Device › Apps show held releases with Approve/Keep: both covered (§4.3).
- M3 a manual upload wrote the served release unchecked: refused with a flash; the start re-checks the held release
  is still ahead (§4.2).
- M4 the QR check's hour-long result outlived a moved `releases/latest`: compared with the newest stable tag after 5
  minutes, re-checked on a mismatch (§6).
- M5 case 2 trusted a later server tag blindly: the tagging rule and `server-release.yml`'s guard (§2.1).
- M6 case 5's diff was too broad: per-app paths without tests, docs, templates and the other app's contracts; the
  case annotated and in the release body (§2, §3).
- L1 the check sits before the first-release short-circuit; copy for nothing served (§4.2, §10).
- L2 the duplicate-entry rule dropped, the read bounded by `take(33)` (§4.1).
- L3 no tags or a shallow clone fail; Cargo.toml found from the repo's top (§2).
- L4 the contract in `contracts/music/`, the only folder all three filters watch (§8).
- L5 the QR's short visible line, the fix behind the (i) (§10).
- L6 Cargo.toml read through `providers.fileContents`; a local release build skips the +1 rule (§2.2).
- L7 `Absent` with no versionCode is `Refuse` (§4.1).
- L8 only the newest release is judged, said with its cost (§4.2).
- L9 the rebuild keeps the key's cascade; 0099 and 0175 as precedent (§4.2).
- L10 `APP_VERSION` and Software Updates' parser and compare reused (§4.1).
- L11 signing and reproducibility unaffected (§1).

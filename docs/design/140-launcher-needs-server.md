# 140 - A launcher release names the oldest server it needs, and an older server holds it back

Status: design note, 2026-11-27, on `main` at 218627933, branch `min-server-design`. Approved in principle by the
owner on 2026-11-27 ("stopp auto oppdatering av vibb launcher om man egentlig er på en for gammel server utgave"); this
note is the shape. Both apps' builds, the two release workflows, one script, the server; one migration, one new
contract file. No phone-side behaviour change, so no Android 14-17 notes (§9).

## 0. Short answer

- **The finding.** A server takes the newest `launcher-v*` release for its launcher row (prereleases too, when the
  parent switched them on) whatever the server's own version. On 2026-11-27 rc.7 was published while server 0.95.0
  still waited for signing: a family's 0.94.1 would have handed it to the phones. C1's code QA found the same hazard (the
  ship order flipped: a C1 launcher on a pre-C1 server would have lost `unified_push` and unregistered Element X). It hits every self-hosting family, not
  only ours: they update the server when they get to it, the launcher row follows GitHub at once.
- **The value lives inside the signed APK**, not in a separate release asset: a text file `assets/vibb/min_server`
  (`0.95.0`), written by Gradle in every build, from `server/Cargo.toml`'s version - in a tag build by
  `scripts/min-server.sh`, which adds one patch when the server's code moved since that version's tag (§2). The
  server already downloads the APK before it serves it, the setup QR's check already downloads the QR's APK, and a
  manual upload brings the file itself: all three read the value with no new fetch and no new way to fail.
- **The server holds a release that needs a newer server** in design 90's held-release slot with a new reason
  `server`: downloaded, kept, never served, never nudged. Install Now only ever sends the served release, so it can't
  reach a phone. One orange line on the app's page and on Software Updates; it goes out by itself at the first start
  of a server that is new enough.
- **The music app the same way** (§5): the server serves `music-v*` releases through its own catalog row, and the
  music app calls the server's routes (`/api/devices/music/...`).
- **The setup QR blocks** while its launcher needs a newer server (§6; owner question Q1).
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
| Readable by a person | Yes. | The release body gets the line "Needs Vibb server X.Y.Z or newer." (§3), from the same script. |

Manifest `<meta-data>` was the third option: signed the same way, but the server would need a new walk of nested
elements under `apk.rs`'s by-resource-id discipline (design 90's parser rules). A ZIP entry is one `by_name` read and
a line of text. The APK's asset is the simpler and safer shape.

## 2. The value: which server a build needs

`scripts/min-server.sh` (bash and git, `--selftest`; run by `launcher.yml`'s and `music.yml`'s always-run changes
jobs, the same pattern as `release-version.sh`) prints `X.Y.Z`:

1. `V` = `server/Cargo.toml`'s `version` (must be `X.Y.Z`, else it fails).
2. No tag `server-vV`: `V` (an unreleased bump - the server that ships under it carries at least this commit's code).
3. `server-vV` is on `HEAD` or after it (`git merge-base --is-ancestor HEAD server-vV`): `V`.
4. `server-vV` is before `HEAD` and nothing the phones talk to changed since (`git diff --quiet server-vV HEAD --
   server/src server/migrations server/Cargo.toml server/Cargo.lock contracts ':!*.md'`): `V`.
5. Else `X.Y.(Z+1)`: "any server released after `V`" - every later server tag is at or above it, whether the next one
   is a patch or a minor. Without this a launcher tagged after a server change that had no version bump yet would
   name the older server, which lacks the change.

The owner tags the launcher on the same commit as the server (root CLAUDE.md, "a release still ships the server
first"), so case 3 or 4 is the usual one and the value is exactly that server. Case 5 holds a launcher until the next
server release; that is the point of the design, and the release body says which server it waits for.

Gradle (`launcher/app/build.gradle.kts`, `music/app/build.gradle.kts`): a small task writes
`build/generated/minServer/<variant>/vibb/min_server` from `-PminServer=` and adds it to the variant's assets
(`androidComponents.onVariants { it.sources.assets?.addGeneratedSourceDirectory(...) }`); without the property it
reads `../server/Cargo.toml`'s version (local and debug builds; no git in Gradle). A value that isn't `X.Y.Z` fails the
build. `checkReleaseHasMinServer` (next to `checkReleaseHasNoGoogleServices`) fails `assembleRelease` if the APK lacks
the entry.

## 3. The workflows

- `launcher.yml`'s `launcher-release-build` (already `fetch-depth: 0`): after "Derive version from tag",
  `min=$(../scripts/min-server.sh)`, an output `min-server`; `assembleRelease ... -PminServer="$min"`.
- `launcher-release-publish`: the release action's `body: "Needs Vibb server ${{ ... min-server }} or newer."`
  (prepended to the generated notes).
- `music.yml`'s `music-release-build`/`-publish` the same.
- Both changes jobs: `scripts/min-server.sh --selftest`; both path filters gain the script (`BuildChainTest` and
  `tests::deploy_scripts` check the filters).
- No new asset, no change to signing (`RELEASE_CERT_SHA256` covers the value with the APK).

## 4. The server

### 4.1 Reading it

- `apk.rs`: `ApkIdentity.min_server: MinServer` - `Absent`, `Unreadable` or `Version(Semver3)` - from the ZIP entry
  `assets/vibb/min_server`: at most 32 bytes, exactly `^[0-9]{1,4}\.[0-9]{1,4}\.[0-9]{1,4}\n?$` (no leading zeros
  beyond `0`), else `Unreadable`; two entries of that name `Unreadable`. Read for every APK; used only for ours.
- `catalog_holds::needs_server(ours, min_server, version_code, first_code, own) -> Serve | Hold(min) | Refuse`, pure:
  not ours `Serve`; `Version(v)` with `v > own` `Hold(v)`, else `Serve`; `Absent` below the row's first code
  (`contracts/core/min_server.json`: `launcher_first`, `music_first`, read as the versionCode by
  `launcher_tag_codes.json`'s rule) `Serve` (today); `Absent` at or above it, or `Unreadable`, `Refuse`.
- `own` = the server's own version, `X.Y.Z` from `CARGO_PKG_VERSION`, carried as `AppState`'s `server_version` (a
  test sets another; `server_version_is_x_y_z` pins the parse).

### 4.2 The sync, the hold, the release

- `tracked_apps::sync_release` (ours only, after the pins, where design 90's `judge` runs for the others):
  `Refuse` is a sync failure (`SyncProblem`'s `apps.sync.unreadable`, "Couldn't read the APK" - nothing stored, nothing held, the next
  release can replace it); `Hold(v)` writes `tracked_app_held_releases` with `reason = 'server'` and `min_server = v`
  through design 90's existing path: the file kept next to the served one, nothing nudged, the same `(tag, asset)`
  "nothing to do" at the next sync, a newer release held replaces it, a newer one served supersedes it,
  `remove_orphan_files` keeps its file.
- **Out by itself**: `catalog_holds::release_server_holds` at start (after the backfills and `remove_orphan_files`,
  before the hourly sync's first pass): for each held row with `reason = 'server'` whose stored `min_server` and the
  file's own value are both at or below `own`, design 90's Approve move without the parent - the file read again (pins,
  SHA-256, the value), into `latest_release_*` in one transaction, the held row deleted, the old file removed, the
  phones nudged; Security Log `catalog_release_server_ready` (no inbox row, no push). The version only changes with a
  restart, so the start is enough.
- Approve and Keep never show for a `server` hold; their POSTs answer `stale`. Update Now and the wait don't apply to
  ours (design 90). Install Now sends `latest_release_*` only (design 37), so a held release can't be installed by it;
  its rows read Up to date on the served one.
- A server rolled back below a release it already served: nothing is pulled back (no step back, the phones keep what
  they run) and nothing is shown - a deliberate host action of the owner.
- Migration (the next free number at build time - 0187 if design 139's 0186 merges first): rebuild
  `tracked_app_held_releases` (SQLite can't alter a CHECK) with `reason IN ('permissions', 'unreadable', 'always_ask',
  'server')`, a column `min_server TEXT`, and `CHECK (reason <> 'server' OR min_server IS NOT NULL)`; rows copied as they
  are. Nothing references the table, so the rebuild needs no foreign-key dance.

### 4.3 The pages

- The app's page (`tracked_app_detail.html`, `#update`, launcher and music rows): one orange line and its (i).
- Software Updates (`updates.html`, the server group, under the version rows): one orange line per held app, no (i) -
  the page itself says how to update.
- The Apps tab: the row's badge "Waits for the server" in place of "Waits for you", same rank.
- The setup QR group (`partials/launcher_check.html`): §6.

## 5. The music app: the same

The server serves `music-v*` releases from the music catalog row (`catalog_holds::ours`), and the music app talks to
the server itself (`/api/devices/music/library`, the listing, Storytel and Spotify through the server - design
21/26b/26c), so a new music app against an old server breaks the same way. The mechanism costs nothing more: the same
script and Gradle task, the same `needs_server` (it already knows ours), the same hold. Not covered: a music release
that needs a newer launcher (the bridge AIDL) - a different pair, not asked for.

## 6. The setup QR's check (`launcher_check.rs`)

The QR's address is fixed in `.env` (`LAUNCHER_APK_URL`, by default GitHub's `releases/latest/download/...`): the new
phone downloads it straight from there, so the server can't pick another release for it - it can only judge the one
the address serves. `judge` gains `Verdict::NeedsServer { version, server }` from the downloaded APK's value (the
check already has the file), ranked after `WrongPackage`/`WrongSigner`/`TooOld`; `blocks_qr()` is true: a phone set up
with a launcher this server can't serve would enrol against routes it doesn't have, and its catalog row would then
hold back the very release the phone runs. `Absent`/`Unreadable` at or above the first code is `NotAnApk` ("not a
readable Vibb One"). `known_releases`' "a newer release exists" note (`Older`) is unchanged.

The alternative - the server rewriting the QR to the per-tag address of the newest stable it can serve - is owner
question Q1.

## 7. Fail closed or open

| Case | What happens | Why |
|---|---|---|
| A release from before the first tag, no value | Served, as today | It can't carry one; holding every old release would freeze the row. |
| A release at or above the first tag, no value or unreadable | Refused (sync failure), the served one stays | Our Gradle writes it in every build, so its absence is a broken or foreign build; serving it would be exactly the bug. The next good release replaces it. |
| A value above the server | Held, out by itself after the server update | The design. |
| A forged value (APK swapped on GitHub) | Raised: held - no worse than a deleted release. Lowered: served, and every phone refuses it (signature) | The phone's signature check is the gate; the server's read needs no trust of its own. |
| A network failure | Nothing new: the release list and the download fail as today, the served release stays | No new fetch exists. |
| The QR's APK at or above the first tag, no value | `NotAnApk`, no QR | Fail closed, as every other QR verdict. |
| The server's own version | Compiled in, never unknown | - |

## 8. Steps and files

One branch, two commits (the contract with both sides in the first, design 133's rule):

1. **Build side + contract**: `contracts/core/min_server.json` (the entry's path, the format, `launcher_first`,
   `music_first`, valid and invalid rows) and its `contracts/README.md` row; `scripts/min-server.sh` with `--selftest`;
   `scripts/README.md`; both `build.gradle.kts` (task, generated assets, `checkReleaseHasMinServer`); a unit test in
   each app reading the contract's path and rows; `launcher.yml`, `music.yml` (build, publish body, changes job,
   filters); `docs/notes/ci.md` "Launcher and music releases".
2. **Server**: `apk.rs` (`MinServer`), `catalog_holds.rs` (`needs_server`, `release_server_holds`, the `Server`
   reason), `handlers/tracked_apps.rs` (`sync_release`, the upload, Approve/Keep's `stale`), `launcher_check.rs`
   (`NeedsServer`), `handlers/updates.rs` + `updates.html`, `tracked_app_detail.html`, the Apps tab's badge,
   `partials/launcher_check.html`, `main.rs`/`AppState` (`server_version`, the start hook), the migration,
   `i18n/en.json` + `nb.json`, `docs/notes/server/play-and-updates.md` (design 90's section and the QR's),
   `docs/notes/server/data-model.md`.

`launcher_first`/`music_first` name the first tags built after step 1 merges (as design 129b's
`enrol_attempt_first`). Release order: as always, the server first; an older server ignores the entry, so either
order is safe, but only a server with this design holds anything.

Size: about 250 lines of server code, 400 of tests, 40 of Gradle per app, 120 of script with its selftest, 20 of
workflow - one build round, M. No emulator gate: nothing on the phone changes.

## 9. Tests

- `scripts/min-server.sh --selftest`, in a throwaway git repo: no tag (`V`); the tag on `HEAD`; the tag after `HEAD`;
  the tag before with only `launcher/` or `*.md` changed (`V`); with `server/src` or `contracts/` changed
  (`X.Y.Z+1`); a non-`X.Y.Z` Cargo version fails.
- Both apps: the generated asset's path and format against `contracts/core/min_server.json`; a release build without
  it fails (`checkReleaseHasMinServer`).
- `apk::tests`: the entry read from a ZIP the test writes (absent, `0.95.0`, `0.95.0\n`, `00.1.0`, `0.95`, 33 bytes,
  two entries) - the signing-block fixtures stay as they are.
- `catalog_holds::tests`: `needs_server`'s table (not ours, equal, above, below, absent below and at the first code,
  unreadable), the compare (`0.100.0 > 0.99.9`).
- `tests::catalog_holds` / `handlers::tracked_apps::tests`: the launcher row and the music row - a release above
  held with no nudge and not in `/api/devices/apps`; equal served; at-first-code without the entry refused; a newer
  held release replaces it; start with `server_version` raised serves it, nudges, logs; one whose file's value
  disagrees with the row stays held; Approve/Keep answer `stale`; the upload path; the migration keeps 0141's rows.
- `launcher_check::tests`: `NeedsServer` blocks, equal works, the line's words.
- Pages: the orange line and (i) on the app page, Software Updates' line, the badge; `nb_pages_have_no_english`;
  `i18n::tests`.

## 10. Copy (checked against root CLAUDE.md "Words in the UI")

| Where | en | nb |
|---|---|---|
| App page, `#update`, orange | `{version} waits for server {server}` | `{version} venter på server {server}` |
| Its (i) | `The phones stay on {served} until this server runs {server} or newer; then {version} goes out by itself. Settings › Software Updates shows how to update.` | `Telefonene blir på {served} til serveren kjører {server} eller nyere; da går {version} ut av seg selv. Innstillinger › Oppdateringer viser hvordan du oppdaterer.` |
| Software Updates, orange, per held app | `{app} {version} waits for server {server}` | `{app} {version} venter på server {server}` |
| Apps tab badge | `Waits for the server` | `Venter på serveren` |
| Setup QR, orange | `Vibb One in the QR code needs server {server}` | `Vibb One i QR-koden trenger server {server}` |
| Its sub-line (design 113's pattern) | `Update this server to {server} or newer, or point LAUNCHER_APK_URL at an older release.` | `Oppdater serveren til {server} eller nyere, eller pek LAUNCHER_APK_URL til en eldre utgivelse.` |
| GitHub release body | `Needs Vibb server {server} or newer.` | - (the repo is English) |

No push and no inbox row: a held update is a state, and the two pages show it (Software Updates already says when a
newer server is out).

## 11. Android versions

None: the phones' code doesn't change; the asset is a file in the APK no code on the phone reads.

## 12. Open questions for the owner

- **Q1 - the setup QR while the stable Vibb One needs a newer server.** (a, recommended) No QR, one orange line: update
  the server first. Simple, and rare - stable launchers ship after their server, and a Watchtower server follows on
  its own. Cost: a family that is behind can't add a phone until it updates. (b) The server points the QR at the
  per-tag address of the newest stable release it can serve when `LAUNCHER_APK_URL` is the default "latest" address -
  the phone is set up on the older Vibb One and updates later. Cost: the QR's address stops being what `.env` says,
  the check runs on a computed address, and about 150 more lines.

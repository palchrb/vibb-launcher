# 26c handover: merging and releasing `music-3-spotify` (Spotify, Vibb music phase 3)

> **Done; history since 2026-10-14. Status 2026-10-26 (main 8d8ba426).** The branch was merged into `main` as `36cabb48`
> (2026-10-14, "Merge design 26c", after a last merge of `main` into the branch, `9a1a4a4d`) and shipped in `music-v0.4.0-rc.1` (2026-10-14). The "Not verified anywhere" list in §3 is out of date:
> `docs/testing/emulator.md` §6l **part A** ran on 2026-10-14 (`docs/testing/2026-10-14-spotify-6l-a.md`, then the
> re-run in `docs/testing/2026-10-14-emulator-round.md`: 59 pass, 7 fail, 8 blocked over §6l and design 34).
> **The DNS capture is dropped** (the user, 2026-10-16: list-mode playback works with every blocklist on; 2026-10-25:
> "Glem spotify vertslisten"): the host list stays as it is, "provisional" wording included. Still open, and parked
> with Spotify since 2026-10-25: §6l part B (the real SDK, the kid's Premium account), 26c's QA checklist on the Jelly
> Star, the 2.5-hour Doze run. Nothing below is an instruction any more.

For the agent that merges this branch into `main` and releases it. Self-contained; the details live in
`docs/design/26c-spotify.md` (the design, its §14 open points, its **Release** section and one status section per step).
Written 2026-10-14 at branch head `102745b`, plus this document's own commit; brought up to date at the docs commit
after `cffd63c5` (the fix round of the merge of main `7936e6d`, then the merge of main `59c7fbe`).

## 1. What the branch is

`music-3-spotify` is design 26c, Vibb music phase 3: Spotify links added in the parent's PWA, listed by the server like
an RSS feed, played on the kid's phone by Spotify's Web Playback SDK in a headless WebView inside the music app. Steps
1-8 are built, each with a code QA and a fix round. **`origin/main` (`59c7fbe`) is an ancestor**
(`git merge-base --is-ancestor origin/main music-3-spotify`); count how far the branch is ahead with
`git rev-list --count origin/main..music-3-spotify` (if `main` moved again, merge it first, §4.1).

Five merges of `main` were made into the branch, each followed by the full suites (the second to fourth by a code QA):
- `5d6bd5b` - main at `a5d30b9`: designs 29, 31 step 1 (the PWA shell), 32 step 1.
- `e4d4feb` - main at `571f86f`: designs 30 (mobile-data switches, migration 0073), 31 steps 2/3/11, 32 step 2, 33. QA
  `c630b87`, fix round `665ea7b`. Spotify's migration was renumbered 0073 -> 0074 here.
- `a023e4d` - main at `5584772`: design 31 steps 4-10 and 12-15 (Device › Music, Web Filter per phone, the Music tab,
  the summary, add-phone/sign-in). QA `8ba881b`, fix round `102745b` (the Spotify page rebuilt in design 31's layout).
- `5d7de47` - main at `7936e6d`: design 31's CSS clean-up and the PWA browser pass's fix round (every form posts and
  redirects to its group, `assert_lands`, the CSS guard); follow-up `d42df69` (the Spotify login in `leaves_the_page`,
  the Spotify pages in the shell test). QA `bfe3b5a` (`0211d6b` on `qa-26c-merge3`), fix round `cffd63c` (the
  leaving-form guard, a class-coverage test, the 0073/0074 order pinned, `/music/spotify/done` on the head partial).
- `444deaa` - main at `59c7fbe`: design 34's docs, mockups and QA, and the stray `server.pid` removed (`*.pid`
  ignored). Docs only; no conflict, no code QA needed.

| Step | What | Build | QA | Fix round |
|---|---|---|---|---|
| design | architect draft; design QA | `bcb36e0` | `8b6a3b4` | `2c95349` |
| 1 | server: developer app, accounts, PKCE login, token cache | `ec6e405` | `8f2db5d` | `ca2b454` |
| 2a | server: the listing, list/context modes, the sweep check | `0fc42a1` | `9aaef2f` | `914b993` |
| 2b | server: entries, PWA add form and cards, phone binding | `9413ff7` | `0f4a20c` | `45a6b51` |
| 3 | the contract: token endpoint, policy key, bridge, shared files | `b304e77` | `41bc2e0` | `726f533` |
| 4 | music: the Android-free core, the fake Spotify | `a10e7fe` | `6871630` | `17a2543` |
| 5a | music: WebView host, `SpotifyRenderer` | `afaa351` | `7dff6f8` | `1bebd77` |
| 5b | music: service, UI, report; emulator §6l (then §6j) | `1d30a0f` | `1c5b24d` | `2d0805e` |
| 6 | music: context mode, the state recorder | `74c4942` | `315a27e` | `f59f472` |
| 7 | docs, DEPLOY note, NOTICE, the Release | `b6c5b92` | `debee3f` | `d23038a` |
| 8 | no "Spotify" word in the kid's app; one child account per phone | `598a152` | `9653177` | `038822a` |

## 2. What it changes

**Server** (`server/`). New `src/spotify.rs` (link grammar, PKCE, the token cache with refresh-token rotation, the Web
API reads, the listing parsers, the host list), `src/handlers/music_spotify.rs` (the `/music/spotify` page, app,
login, paste, accounts, add), large additions to `src/handlers/music.rs` (cards, entry page, Device › Music's "Plays
as" group, `spotify_binding_card`, `spotify_account_warnings`), `music_sweep.rs` (`spotify_check`), `music.rs`
(`spotify_gate!()` on the library, listing, covers and art), `handlers/music_api.rs` (`POST
/api/devices/music/spotify/token`, device bearer), `handlers/device_api.rs` (the policy key `music.spotify` = the phone
is bound; Spotify's hosts left out of a bound phone's delivered blocklist; the binding hashed into
`dns_filter_version`), `attention.rs` (five Spotify Attention rows), `handlers/admin.rs` (event labels).
**Migration `migrations/0074_music_spotify.sql`**: the app row, the accounts (refresh tokens sealed with the music
key), `device_policy.music_spotify_account`, the listing's Spotify columns. It is 0074 because design 30 took 0073 on
`main`. **Hazard**: `startup::pre_migration_backup` compares only `MAX(version)`, so if 0073 ever reaches a server that
already ran 0074, sqlx applies it out of order **with no backup first** - so 0073 must ship before or together with
0074, never after (`server/CLAUDE.md` "Data model"). **The route `GET /music/spotify/callback` (and `/done`) must never
move**: it is the redirect URI every family registered in its Spotify dashboard (`spotify::CALLBACK_PATH`, the only
https path `valid_redirect_uri` accepts, the login cookie's `Path`); it is in `build_admin_router`'s `public_routes`
(the admin cookie is `SameSite=Strict`), still behind the admin gate. New `.env` settings: `SPOTIFY_CLIENT_ID`,
`SPOTIFY_REDIRECT_URI`, `SPOTIFY_ARTIST_ALBUMS` (opt-in), and the test-only, loopback-only `SPOTIFY_ACCOUNTS_BASE` /
`SPOTIFY_API_BASE`. No new crate.

**Launcher** (`launcher/`). The token courier `MusicBridgeService.getSpotifyToken` (no cache, no log; pure mapping in
`music/SpotifyTokenCourier.kt`), the gated DNS exemption (`music/SpotifyHosts.kt`, `server/DnsFilterEngine.kt`'s
`classifyDomain`, the flag from `music.spotify`), the WebView provider in `EnforcementPlan`'s `neverRestrict` (on every
phone - the one change that applies without Spotify), `MusicPlan.musicSpotify`. The AIDL: `getSpotifyToken` **appended**
to `IMusicBridge.aidl` (13 methods, order pinned) and the new `ISpotifyTokenCallback.aidl`; both files are byte-identical
in `launcher/` and `music/`.

**Music app** (`music/`). The `spotify/` package (Web API client, `SpotifyTracker` state machine, `TokenCache`,
`SpotifyWebHost` - the one headless WebView, never attached to a window - and `SpotifyRenderer`, a Media3
`SimpleBasePlayer`), `assets/spotify/player.html` + `player.js` (the SDK is loaded from `sdk.scdn.co` at run time,
never copied), `playback/RendererRules.kt` and Spotify rules throughout `PlaybackService`, `logic/Spotify.kt`,
`data/Store.kt` (`spotify_state.json`, `spotify_context.json`), the debug switch `src/debug/.../DebugSpotify.kt`. Since
step 8 **no kid-facing string names Spotify** (`SourceScanTest` pins both languages and the literals of `ui/`,
`playback/`, `bridge/`, `sync/`); a Spotify entry is "Musikk" or "Podkast". No new dependency.

**Contract files** (byte-identical copies, one commit changes all copies):
`server/testdata/music_library.json` = `music/app/src/test/resources/music_library.json` (server
`music_library_snapshot`, music `ContractFilesTest`), `server/testdata/music_state.json` (`spotify` word),
`spotify_hosts.json` in `server/testdata/` and both apps' `src/test/resources/` (server
`spotify::tests::hosts_match_the_contract`, both apps' `SpotifyHostsTest`, `ContractFilesTest`, `MusicPlanTest`),
`bridge_contract.json` in both apps (`spotify_token`; `MusicPlanTest`, `BridgeTest`), the policy shape
(`policy_json_keys_snapshot`, `PolicyResponseCompatTest`). **The host list is PROVISIONAL** (its comment says so): the
DNS capture replaces it before any tag (§4 below).

**Scripts and CI.** `scripts/fake-spotify.py` (stdlib, `--selftest`): the accounts service with PKCE, the Web API, a
stub SDK; `music.yml` runs its selftest and watches the Spotify fixtures; `launcher.yml`, `music.yml` and
`server-ci.yml` watch the three `spotify_hosts.json` copies. Root `CLAUDE.md` names the paths.

**Docs.** `docs/design/26c-spotify.md` (spec, deviations, open points, QA checklist, Release, statuses), 14 QA files
`docs/design/qa-26c-*.md`, `docs/testing/emulator.md` §6l, the Spotify sections of `server/CLAUDE.md`,
`music/CLAUDE.md`, `launcher/CLAUDE.md`, `NOTICE.md` (vibb's ported link grammar, MIT), design 26's status, `PLAN.md`.

## 3. How it was verified, and what was not

Last local runs (the worktree; the server at `cffd63c`, the launcher and music at `d42df69` - nothing since reached
`launcher/` or `music/`):
- Server: `cargo fmt --check` clean; `cargo clippy --all-targets --locked` 13 warnings, `main`'s 13; `cargo test`
  **939 passed**; `node --test jstest/*.test.js` 30 passed.
- Launcher: `./gradlew assembleDebug testDebugUnitTest` **1146 tests** (on the tsnet stub; `assembleRelease` needs
  `app/libs/tsnet.aar`, which only CI builds).
- Music: `./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug -PwarningsAsErrors=true` **845 tests**,
  lint 0 errors.
- `python3 -I scripts/fake-{sonos,storytel,spotify,audio}.py --selftest`: all four OK.

GitHub CI: every pushed head of the branch up to `a023e4d` ran **Server CI**, **Launcher** and **Music** green (19
runs). The branch is pushed (to the docs commit after `cffd63c`, 2026-10-14); CI for `8ba881b..` (the heads pushed
since) is to be checked: wait for the three workflows green on the branch's head before merging.

**Not verified anywhere** (no emulator, no browser, no Spotify account, no Jelly Star in the sessions that built it):
- `docs/testing/emulator.md` §6l **part A** (A1 the whole chain against the fake, A2 the phone alone) - the acceptance
  of steps 5b and 6 - and **part B** (the real SDK with the kid's Premium account, the state recorder's real
  sequences).
- A browser pass (390 px, light/dark, JS off) of the rebuilt `/music/spotify` page, Device › Music's Spotify group,
  the phone's Web Filter footer and the Music tab's "From Spotify ›" row.
- The user's QA checklist in 26c (items 1-21 on the Jelly Star: logins, links, play, resume, the 2.5-hour Doze run,
  mobile data, calls, bedtime, autoplay, the artist option's dev-mode check, ...) and the "device checks with a
  default in code" listed in the Release.
- **The DNS capture**: the real host list replacing the provisional one.

## 4. The merge procedure

1. `git fetch origin` and confirm `git merge-base --is-ancestor origin/main origin/music-3-spotify` still holds.
   If `main` moved, merge it **into the branch first** (`git merge --no-ff origin/main` on `music-3-spotify`, message
   `Merge main (<what it brought>) into music-3-spotify` with the trailers), fix what breaks inside that merge commit,
   run all three suites, have it code-QA'd as the earlier merges were, and add a "Merge of main" status section
   to 26c. The files that conflicted most: `server/src/handlers/music.rs`, the device pages
   (`templates/device_*.html` - `device_filter.html` again at `7936e6d`, `handlers/device_pages.rs`, `handlers/dns_filter.rs`), `templates/music.html` and the
   music partials (`partials/music_card.html` again at `7936e6d`), `server/src/tests/music.rs`, `server/CLAUDE.md`, `docs/testing/emulator.md`, `PLAN.md`; the
   contract JSONs if `main` touched them (regenerate with `MUSIC_LIBRARY_SNAPSHOT_WRITE=1` and copy byte for byte).
   Re-check that 0074 stays the highest migration.
2. **Before any tag** (26c Release, "Before any tag of this release"): the DNS capture as **one commit** - the three
   `spotify_hosts.json` copies (comment without "PROVISIONAL", naming the capture's date and source) and the six
   constants (`HOST_SUFFIXES`/`EXACT_HOSTS` in `server/src/spotify.rs`, `SPOTIFY_HOST_SUFFIXES`/`SPOTIFY_EXACT_HOSTS` in
   both `SpotifyHosts.kt`) - on the branch before the merge or on `main` before the tags; §6l part A run by the user.
3. On `main`: `git checkout main && git merge --no-ff music-3-spotify` (`main` is an ancestor, so without `--no-ff`
   git would fast-forward). Message in `main`'s convention, e.g. `Merge design 26c: Spotify (music phase 3), steps
   1-8, with its QA fix rounds`, ending with the two trailer lines. Push, then run the three workflows on the merge
   commit (`gh workflow run launcher.yml --ref main`, likewise `music.yml` and `server-ci.yml`; `workflow_dispatch`
   always builds).
4. **The server version bump**, one commit on `main`: `server/Cargo.toml` `version = "0.26.0"` and the
   `kid_phone_server` entry in `server/Cargo.lock`. (0.25.0 is design 30's - 0073 without 0074 - if `main` releases
   it on its own before the merge.)
5. **Tags, in the Release's order** (each on a `main` commit; versionCode `X*1_000_000 + Y*1_000 + Z`, an RC minus 1):
   0. `server-v0.24.0` is **skipped as a release** (the user, 2026-10-14): with `SERVER_MINISIGN_KEY` re-entered its
      re-run signed fine, but `server-release-image` failed on a workflow bug (`docker pull` of the same digest for the
      second platform: "cannot overwrite digest"; fixed on `main` after `9cde918`), and a re-run uses the tag's own
      workflow. The tag stays without a GitHub release; its image was pushed by digest only (untagged). The first
      published server release is `server-v0.26.0`, built with the fixed workflow.
   1. `launcher-v0.26.0` (26000; above `launcher-v0.25.0-rc.1` = 24999; becomes "latest", the provisioning QR's APK).
   2. `music-v0.4.0-rc.1` (3999, a prerelease; the catalog offers it with the music row's "include prereleases").
   3. `server-v0.25.0` - skipped (0.26.0 carries 0073 and 0074).
   4. `server-v0.26.0` with 0074 - after 0073 or carrying both, **never before 0073**.
   The stable `music-v0.4.0` (4000) only after the owed checks of §3.
   The `release` environment's deployment rule must admit `launcher-v*`, `music-v*` and `server-v*` (proven by the
   0.24.0 run); `RELEASE_CERT_SHA256` is checked for both APKs.
6. **`server/DEPLOY.md`**: paste the block in 26c's Release, "### DEPLOY.md note" (the env table, https-or-paste for
   the login, keys and backups, what it fetches, the phones' DNS override, the test-only overrides, the order 0073 ->
   0074, a smoke check, rollback). Its place: after "Vibb music: episode art (design 27, migration 0070)".
7. **Deploying to the Pi**, in this order: the launcher (as a catalog/self-update release) and the music app (through
   the catalog), then the server with `update.sh` (0.25.0 then 0.26.0, or 0.26.0 carrying both) after a backup.
   Nothing visible changes until a parent registers an app on Music › From Spotify, logs in a child's account and
   binds a phone. Smoke check: `journalctl -u kid-phone-server -b | grep -i spotify` shows no "ignored" warning, and
   the "From Spotify ›" row says "No app".
8. A **dev database that ran this branch before the merge of `571f86f`** holds the old migration 73 (Spotify's, under
   another checksum) and stops at `VersionMismatch(73)`: start it on a fresh `data/`. Production servers never ran
   the branch.

## 5. Decisions already taken by the user (do not reopen)

- **One Spotify account per phone, the child's own; the parent's account is never logged in** - the parent pastes
  links; a playlist lists its tracks only when a child's account owns it or has joined it as a collaborator (2026-10-14).
- **No "Spotify" word anywhere in the kid's app** - a Spotify entry is a tile like any other (2026-10-14). This and
  the player being for a child are two departures from Spotify's Developer Policy - II (attribution) and III
  ("targeted to children", accepted 2026-10-06) - accepted for private use; the attribution lives in the PWA only
  (26c §7.3). The sanction is the family's app being switched off; the tiles then say "Spør en voksen".
- **Links no account may list play in context mode** (Spotify's order, no track list; an artist without resume).
- **Artists as their albums' tracks** only behind `SPOTIFY_ARTIST_ALBUMS=1`, off until QA checklist item 18 passes.
- **Spotify streams under the streaming switch** (design 30's `stream_mobile_data`), never the downloads switch or
  the storage limit; nothing of it is downloaded or cached.
- **The WebView route** (no Spotify app on the phone), **no shuffle** for Spotify, **Spotify on Sonos is step 9** -
  a later architect pass, not in this release.

## 6. Open points still for the user

- Autoplay on the children's accounts: switch it off only if QA checklist item 17 shows the web player autoplaying
  (26c §0.2 item 8, §14 item 2, Release open point 2).
- The wake lock while playing: kept unless the Jelly Star's Doze run shows an hour without it (§14 item 6, Release
  open point 3).
- The artist option: turn `SPOTIFY_ARTIST_ALBUMS` on after QA checklist item 18 (§0.2 item 7, Release open point 1).
- Episodes in a `uris` list: if the real SDK refuses them, the server's parser must refuse shows and episodes - a
  small code change, no switch built (§3.1, Release "Device checks").
- For `main`: `pre_migration_backup` comparing the set of applied versions would end the 0073/0074 hazard (§14 item
  12, Release open point 7); the timing-dependent `tests::parent_pin::a_new_pin_ends_the_notices_but_not_the_list`
  (green in the last runs; Release open point 6).
- A later contract change: `{"rejected": true}` on the token route and a report field naming a context playlist the
  account can't open (Release open point 9).
- Spotify on Sonos: its own architect pass (§11 step 9, Release open point 5).

## 7. Where to read more

- `docs/design/26c-spotify.md`: §0 (summary, assumptions, decisions), §2-§3 server, §4-§5 phone, §6 launcher, §7
  security and the Developer Policy table, §8 the contract commit, §9 the fake, §11 steps, §12 deviations from vibb,
  §13 non-goals, §14 open points, the QA checklist, **the Release** (with the DEPLOY.md block), then the status
  sections in order: design QA fix round, steps 1, 2a, merge `a5d30b9`, 2b, 3, 4, 5a, 5b, merge `571f86f`, 6, 7, 8,
  merge `5584772`, merge `7936e6d` (and `59c7fbe`) - each with its fix round.
- The QA files, in order: `qa-26c-design.md`, `qa-26c-step1-code.md`, `-step2a-code`, `-step2b-code`,
  `-step3-code`, `-step4-code`, `-step5a-code`, `-step5b-code`, `qa-26c-merge-code.md`, `-step6-code`,
  `-step7-docs`, `-step8-code`, `qa-26c-merge2-code.md`, `qa-26c-merge3-code.md`.
- `server/CLAUDE.md` "Vibb music: Spotify on the server"; `music/CLAUDE.md` (the `spotify/` package, the renderer
  rules, "No source on the kid's screens"); `launcher/CLAUDE.md` (the Spotify bullet in the music section).
- `docs/testing/emulator.md` §6l (parts A1, A2, B) - the emulator loop with `scripts/fake-spotify.py`.
- `docs/testing/spotify-spike.md` and `docs/design/vibb-spotify-options.md` - why the WebView route.
- The dead-code review on branch `review-dead-code` (`docs/review/dead-code-and-duplication-2026-10-13.md`), which
  also covers this branch.

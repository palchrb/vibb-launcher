# QA 26c step 7 - the docs and the Release

Scope: branch `music-3-spotify`, `f59f472..b6c5b92` (one commit, docs only: root `CLAUDE.md`, `music/CLAUDE.md`,
`server/CLAUDE.md`, `launcher/CLAUDE.md`, `NOTICE.md`, `PLAN.md`, `docs/design/26-music-phases-1b-2-3.md`,
`docs/design/26c-spotify.md`'s "Release", its "DEPLOY.md note" and "Step 7 implementation status"). Every factual
claim in the new text was checked against the code on this branch (`b6c5b92`), not against the design's intentions.
Also read: every `qa-26c-*.md` (owed lists, device checks, questions), design 30 §"Step 1" L4, the handoff note
`docs/handoff/2026-10-13.md` (`571f86f`), design 31 §8 step 12, vibb's `LICENSE`, `pi/vibb/spotify.py`,
`pi/vibb/library.py` and `pi/daemon.py` at `/home/user/palchrb/vibb`.

The template named for this review, `docs/design/qa-26b-step5-code.md`, does not exist on this branch, on `main` or on
any ref (26b's step 5 had no QA file); this file follows the shape of `qa-26c-step6-code.md`.

Checked, here:
- `git tag -l | sort -V`: `launcher-v0.2.7`, `launcher-v0.25.0-rc.1`, `music-v0.0.1`, `music-v0.0.2`, `server-v0.19.0`,
  `server-v0.24.0` (the same six from `git ls-remote --tags origin`). `server-v0.24.0` -> `6ae54e8` (on `main`,
  migrations up to 0072, `Cargo.toml` 0.24.0). `origin/main` = `571f86f`, an ancestor of `HEAD`.
- GitHub API: releases are exactly `music-v0.0.2`, `launcher-v0.2.7` ("latest", confirmed by `get_latest_release`)
  and `launcher-v0.25.0-rc.1` (prerelease). `server-release.yml` has two runs, both `failure`: run 1 (`server-v0.19.0`,
  2026-10-07) and run 2 (`server-v0.24.0`, 2026-10-13, id 38404900686). In run 2 `server-release-build` passed every
  step (the tests included, so `parent_pin` was green on CI), **`server-release-sign` failed at "Sign the tarball and
  update.sh"** with minisign's `Error while loading the secret key file` (exit 2), and `-image`/`-publish` were skipped.
  **The failure is confirmed.** What the log also shows is in L1.
- `music.yml:198` and `launcher.yml:193`: `code=$(( major * 1000000 + minor * 1000 + patch ))`, an RC minus 1; both
  refuse a tag whose commit isn't on `main`/`master`; `launcher.yml:324` makes every stable launcher "latest",
  `music.yml:319` never. 3999, 4000, 26000 and 24999 are right.
- The three `spotify_hosts.json` copies: sha256 `6b5547…793a` each, still PROVISIONAL.
- `grep` of the diff for a model identifier: none (only the commit's mandated trailer).

**Verdict: nearly ready; a short fix round first.** Counts: **0 High, 2 Medium, 6 Low.** The step is careful and most
of it is right, line by line. The new `music/CLAUDE.md`, `server/CLAUDE.md` and `launcher/CLAUDE.md` text holds up
against the code almost everywhere (each constant, route, status word, file, test name, env variable and workflow path
listed under "Checked and fine" was opened). It reflects the fix rounds, not the overturned deviations: the 8 s token
budget, `null` context handling, the offset-404 retry, the recorder's cap and thread, the paused step, the artist
without resume and `detached()`. Every rule the task names is there: the secrets on all three sides, the append-only
AIDL, the three host-list copies, `queueKind`, the debug-only recorder, the login-address rule, the token route's
limit, the 0074/backup hazard and the stream switch. The Release's facts check out, and so do its versionCodes and the
order against design 30's L4 and the handoff note. The DEPLOY note uses the env names exactly as `spotify::Config::from_vars`
reads them.

The two Mediums concern instructions a later contributor will follow and get wrong:
- The capture commit, as every doc describes it, turns three suites red: three tests assert "PROVISIONAL".
- Open point 8 hands design 31 step 12 a Spotify move that design 31 doesn't know about. That move includes the
  callback path, which every family has registered at Spotify.

## Findings

### M1 (Medium) - the capture's one commit, as written, turns all three suites red: three tests assert "PROVISIONAL"

- **Where:** every description of the capture:
  - `26c-spotify.md:1884-1888`: "replaces the three provisional `spotify_hosts.json` copies and the three constants
    (...; the JSON's "PROVISIONAL" comment goes) in **one commit**, all three suites green".
  - `server/CLAUDE.md:881-882` ("replaces the three copies and the constants in one commit").
  - `launcher/CLAUDE.md:306` ("replaces the three copies and the three constants in one commit").
  - `music/CLAUDE.md:356-357`, root `CLAUDE.md:70`, `PLAN.md:125-126`.
- **The code:** three tests require the comment to say PROVISIONAL:
  - `server/src/spotify.rs:4496-4502` (`hosts_match_the_contract`: `contract["comment"]...contains("PROVISIONAL")`,
    "the list says it is provisional until the capture replaces it");
  - `launcher/app/src/test/java/com/kidslauncher/mdm/music/SpotifyHostsTest.kt:24`;
  - `music/app/src/test/java/me/vibb/music/spotify/SpotifyHostsTest.kt:27`.

  The constants' docs say provisional too, with a deadline that has passed: `spotify.rs:139-140` ("until the spike's
  DNS capture replaces the list (design 26c step 6 at the latest)"), and the JSON's own comment ("step 6 at the
  latest"). Both apps' `SpotifyHosts.kt` say the same.
- **Why it matters:** the capture is the one change the Release puts before any tag. Done as the docs say (the copies,
  the constants, the comment gone), the three PROVISIONAL assertions fail. "All three suites green" then forces
  someone to work out under time pressure what else belongs in the commit. That someone is the main session or the
  user after part B, not this branch's implementer. A careless way out is to keep "PROVISIONAL" in the new comment,
  which leaves the list marked unverified forever.
- **Fix:** name the whole commit wherever the capture is described (the Release's bullet in full; the CLAUDE.md files
  can point to it): the three JSON copies (comment without PROVISIONAL, the capture's date and source), the six
  constants (`HOST_SUFFIXES`/`EXACT_HOSTS`, both apps' `SPOTIFY_HOST_SUFFIXES`/`SPOTIFY_EXACT_HOSTS`), **the three
  tests' PROVISIONAL assertions reversed** (assert it is gone, so a stale copy can't come back), the "provisional"
  KDoc/comments on the three constants, and `emulator.md` §6l's provisional line, if any. Alternatively, this fix round
  can turn the three assertions into one shared rule now (for example "the comment names its source"), so the capture
  commit edits data only. "Three constants" (`launcher/CLAUDE.md:306`, `PLAN.md:126`, `music/CLAUDE.md:357`) should be
  "the three pairs of constants" or "six".

### M2 (Medium) - open point 8 hands design 31 step 12 a move design 31 doesn't know about, and the callback path must not move

- **Where:** `26c-spotify.md:1933-1934` (open point 8: "**Design 31 step 12** moves the Spotify app and logins to
  `/music/settings` (...); the callback's path and the login-address rule move with it"). Also step 2b's status
  (`:2438-2439`, `:2559`) and the step 7 status (`:3956-3957`, "design 31 step 12's move of the login pages").
- **The facts:**
  1. `docs/design/31-pwa-redesign.md` (this branch, and `571f86f`) never names Spotify: 0 matches. Its step 12
     (§8, `:864-878`) gives the Music tab's "Add" exactly **four rows** (podcast/NRK link, own files, "From Storytel ›",
     "Import from Vibb ›") and a Settings page with Storytel's login only. Its redirect list (`:876-880`) has no
     `/music/spotify` entry. Built from design 31 alone, step 12 would drop the "From Spotify ›" row
     (`templates/music.html:74-80`). The move is 26c's own wish, phrased as if design 31 already said it.
  2. The callback path can't move. `spotify::CALLBACK_PATH` = `/music/spotify/callback` (`spotify.rs:68`) is the path
     **every family registered** in its Spotify developer dashboard. It is also the only https form
     `valid_redirect_uri` accepts (`spotify.rs:582-604`: `url.path() == CALLBACK_PATH`). The DEPLOY note tells
     operators to put that exact path into `SPOTIFY_REDIRECT_URI`. If the path moves, an `.env` value is "ignored"
     at start, a stored row stops validating, and every family's next login fails at Spotify's redirect check until
     the parent edits the dashboard. That is the 6-month login every bound phone needs.
- **Fix:** reword open point 8. "Design 31 doesn't name Spotify. When the branch merges, its step 12 must take the
  'From Spotify ›' row (a fifth Add row, 'No app / No login / Signed in') and may move the app and account cards to
  `/music/settings` (a redirect from `/music/spotify` like its other anchors). **`/music/spotify/callback` and
  `/music/spotify/done` stay where they are** (registered at Spotify, `CALLBACK_PATH`, the cookie's `Path`), and so
  does the login-address rule." Then add one line to `PLAN.md`'s design 31 entry (or a note in design 31 §8 step 12
  at the merge) so whoever builds step 12 sees it. Design 26c is not on that reader's list.

### L1 (Low) - the 0.24.0 failure's cause: the environment rule is proven; the secret is set but minisign can't read it

- **Where:** `26c-spotify.md:1817-1821` ("the `SERVER_MINISIGN_KEY` secret or the `release` environment's `server-v*`
  rule"), `:1857-1858` ("the failed 0.24.0 run says it isn't proven yet"); `PLAN.md:131-132` (only "failed at the
  signing step", which is right).
- **Evidence:** the `server-release-sign` job (id 115328665357) ran, so the `release` environment admitted the
  `server-v0.24.0` tag and the deployment rule is proven. It passed the workflow's empty-secret check
  (`server-release.yml:190-193`, whose own message would have said "missing"), installed minisign, and failed in
  `minisign -S -s "$key"` with `Error while loading the secret key file`. So the secret is present but is not a key file
  minisign can parse. The likely causes are a paste without the `untrusted comment:` first line (DEPLOY.md's
  "Release signing" step 2 asks for "both lines") or a key made without `-W`.
- **Fix:** state the run's result: environment rule OK, the secret is set but unreadable. The remedy: re-paste the
  whole `handy-server.key` (both lines, made with `-W`) as the environment secret, then re-run the failed job, or
  delete and re-push the tag as DEPLOY.md says. `:1857-1858` then reads "the server's signing secret isn't proven yet".
  This is main's to do, but the user acts on this sentence.

### L2 (Low) - `SHOWS_PLAYABLE` is named as if it were code; it doesn't exist

- **Where:** `music/CLAUDE.md:359` ("episodes in a `uris` list - `SHOWS_PLAYABLE` -"); the Release's "Device checks
  with a default in code" (`26c-spotify.md:1910`, "`SHOWS_PLAYABLE`").
- **The code:** no `SHOWS_PLAYABLE` in any `.kt`, `.rs`, `.js` or `.py` file. §3.1's fallback (the parser refusing
  `show`/`episode` "with one constant") was never built; shows and episodes are listed and played unconditionally.
- **Why it matters:** the Release lists this under "a default in code". If the Jelly Star shows the SDK refusing
  episodes in a `uris` list, the docs promise a one-line switch that isn't there.
- **Fix:** say what is built: "episodes in a `uris` list (§3.1; no switch is built - if the SDK refuses them, the
  server's parser must refuse `show`/`episode`, a small code change)". Or add the constant, but that is code, not
  step 7.

### L3 (Low) - the DEPLOY note files `SPOTIFY_ARTIST_ALBUMS` under "testing only, never on a real server"

- **Where:** `26c-spotify.md:1978-1983`, inside the bullet "**Testing only, never on a real server**".
- **The conflict:** QA checklist item 18 (`:1789-1793`) runs `SPOTIFY_ARTIST_ALBUMS=1` on the real server, and
  open point 1 (`:1918-1920`) says the parent may keep it on once item 18 passes. `server/CLAUDE.md:847` describes it
  as a real option ("off by default; QA checklist item 18"). An operator following the DEPLOY note would never try it.
- **Also in the note:** "`spotify.link` short links when one is added" (`:1971`). The server follows `spotify.app.link`
  too (`server/CLAUDE.md` "Links and entries"; `resolve_short_link`), so say "Spotify's short links". The note also
  doesn't say where it goes in DEPLOY.md: after "Vibb music: episode art", before "Server 0.23.0".
- **Fix:** move `SPOTIFY_ARTIST_ALBUMS` into the `.env` table (default unset; "`1` lists an artist as its newest 20
  albums' tracks instead of a Spotify context, once the design's checklist item 18 shows it works for your app") and
  keep the testing bullet for the two base overrides and `INSECURE_COOKIES`. Name both short-link hosts and the
  placement.

### L4 (Low) - "Inert without Spotify" isn't true of the WebView guard

- **Where:** `26c-spotify.md:1849-1850` ("Inert without Spotify: `music.spotify` is false until the parent binds a
  phone, and nothing asks for a token before").
- **The code:** `computeEnforcementPlan` puts `webViewProvider` in `neverRestrict` on **every** phone at every
  `apply()` (`EnforcementPlan.kt:172`, `AppEnforcer.kt:363`, `:844-849`), Spotify or not. It is harmless (minSdk 34,
  so the provider is never Chrome, `qa-26c-step3-code.md:203-206`; it has no launcher icon), but it is the one part of
  this launcher tag that changes enforcement on every phone the moment the stable tag becomes "latest".
- **Fix:** "Inert without Spotify, except the WebView provider's place in `neverRestrict`, which holds on every phone
  (a system package with no icon; §6.3)".

### L5 (Low) - three small inaccuracies in the Release, and one owed line not named

- `26c-spotify.md:1863` (order step 0): "Storytel's 0070-0072". 0070 is design 27's episode art and 0071 is 26a's Sonos
  rooms; only 0072 is Storytel's (`:1818` has it right: "a `main` commit with 0065-0072"). Write "0070-0072 (design 27,
  26a, 26b)".
- `:1867` (order step 2): design 30's L4 is about the phones **having** the apps that read the switches before the
  server goes out. `music-v0.4.0-rc.1` is a prerelease, and the catalog offers it only when the music row includes
  prereleases (`:1855-1856` says so for the RC, but not here). Add "(the music row's 'include prereleases' on, or step 3
  waits for a stable `music-v*`)".
- `:1809` and design 26 `:34-35`: "two merges of `main` (`a5d30b9`; `571f86f`...)". These are `main`'s heads that were
  merged; the merge commits on the branch are `5d6bd5b` and `e4d4feb`. Write "merges of `main` at `a5d30b9` and
  `571f86f` (`5d6bd5b`, `e4d4feb`)" so `git show` finds the merge.
- Owed, not named: `qa-26c-step5a-code.md`'s "Owed by step 5b" asks for "a recorded cold start's `needToken` ->
  `ready` order (L9)" from the real SDK. Neither §6l (no `needToken` in `emulator.md`) nor the Release's part B list
  names it. Any part B recording starts with a cold start, so it costs one more line: add "the cold start's
  `needToken` -> `ready` order" to part B's list (`26c-spotify.md:1896-1902`).

### L6 (Low) - two cross-references name headings that don't exist

- `launcher/CLAUDE.md:97`: 'design 26c, "Vibb music: Spotify" below'. The launcher has no such heading; the text is the
  "**Spotify**" bullet in "Vibb music: the bridge, the courier, own files" (`:294`, `:304`).
- `server/CLAUDE.md:542`: '"Vibb music: Spotify" below'. The heading is "Vibb music: Spotify on the server" (`:759`).
- **Fix:** name the real places.

## The Release, section by section

- **Facts on 2026-10-13**: right, except for the 0.24.0 cause (L1). The tags, the commits, "no `server-v*` GitHub
  release at all", `server-v0.19.0`'s failed run, the versionCodes 24999/2007/1/2, and 26b's untagged `music-v0.3.0-rc.1`
  all check out.
- **The merge** (`--no-ff`, `main` an ancestor, all three build jobs on the merge commit): right.
- **Tags and versions**: right. Spotify is 0.26.0 with the bump one commit on `main` after the merge; 0.25.0 is design
  30's alone or never tagged; `is_newer` exists (`system_update.rs:81`); the tag must equal `Cargo.toml`
  (server-release's "Verify Cargo.toml version matches the tag" step). The launcher is 26000 above 24999 and becomes
  "latest". The music RC is 3999 with the stable 4000.
- **The order** against design 30's L4 (`30-music-streaming-data.md:855-857`) and the handoff note ("launcher + music
  releases that read the two data switches before any `server-v*` with design 30"): consistent. Step 0 is right too:
  `server-v0.24.0` (`6ae54e8`) holds no 0073, so publishing it breaks no L4 rule. The "0074 never before 0073"
  reasoning matches `startup.rs:255-271` (`MAX(version)` only). L5's two wording fixes aside.
- **Before any tag / before the stable music**: every owed line of every `qa-26c-*.md` is present:
  - §6l A1/A2 with the streaming-switch line (merge QA);
  - part B with the six recorded sequences (step 6 QA);
  - the QA checklist 1-21 with item 6's Doze run (5b QA's "item-10 Doze run"), 17 with a context and 18 (step 6 QA),
    20 and 21 (step 4 QA);
  - the permission origin, the blocked-hosts counter, the DNS log's redirect targets and `csp` (5a QA's "owed by 5b");
  - the proxy's `Range` is a §6l A1 line (`emulator.md:1135`), covered by "every line"; the real SDK's cold-start
    `needToken` -> `ready` order is in neither list (L5);
  - the WebView's own audio focus, `getCurrentWebViewPackage`, the headless page's timer throttling (design QA H2)
    and the track-less states (step 4 QA L2), covered by item 6's state-post gaps and part B's recordings;
  - design 30's §8 checks as main's.

  Nothing else was dropped. The capture's commit needs M1's addition.
- **Open points**: 1-7 and 9 are right. On 6: CI's own run of that test on `6ae54e8` passed, which supports
  "timing-dependent". 8 is M2.
- **The DEPLOY note**: paste-ready markdown, DEPLOY.md's table form, the heading style of its other music sections.
  Its env names, defaults and accepted values match `spotify.rs:487-547` and `main.rs:358-370`: `SPOTIFY_CLIENT_ID`
  is 32 alphanumerics; `SPOTIFY_REDIRECT_URI` is `https://<host>/music/spotify/callback` or exactly
  `http://127.0.0.1:43117/callback`; `SPOTIFY_ACCOUNTS_BASE`/`SPOTIFY_API_BASE` are loopback literals with a port;
  `SPOTIFY_ARTIST_ALBUMS` is `1`/`true`; `INSECURE_COOKIES` is `true`/`1`. One warning per ignored value, and the
  override line names both bases. The smoke check's `grep -i spotify` catches both kinds of line. The key, backup and
  rollback lines are right. L3 aside.

## Checked and fine

- **`music/CLAUDE.md`**:
  - The intro's section map of 26c (§0-§10 spec, §12 deviations).
  - The layout's 13 `spotify/` files, exactly the package; `logic/Spotify.kt`, `bridge/SpotifyBridgeToken.kt`,
    `assets/spotify/player.{html,js}`, `DebugSpotify.kt` with `spotify_base`/`record_spotify_states`.
  - `noBackupFilesDir`'s `spotify_state.json`, `spotify_context.json` (`Store.kt:62,73`) and `spotify_page_account`
    (`SpotifyRenderer.kt:1818`).
  - `phoneRendererFor`, `standDown`, `queue = { active.takeIf { renderer === spotify } }` (`PlaybackService.kt:794-804`).
  - `queueKind` in `playEntry`/`playItem`/`restore` (`:661,672,728`) and `claimsQueue` (`RendererRules.kt:39,47`).
  - The constants: `SPOTIFY_WINDOW_MAX` 50, `SPOTIFY_STEP_COALESCE_MS` 300, `LOOK_MS` 2 s, `RELINK_DURATION_MS` 2 s,
    `UNPLAYABLE_STOP` 3, `SPOTIFY_HOST_IDLE_MS` 5 min, `SdkState.MAX_BYTES` 8 KiB, `TokenCache`'s 5/10/2 min, the
    10 min reauthorize cooldown and the 5 s prompt retry, `SpotifyBridgeToken` 10 s.
  - `spotifyPlayability` = `streamAllowed` -> `STREAM`/`NEEDS_NETWORK`, and `networkLine` on `streamMobileData`.
  - The WebView settings (`SpotifyWebHost.kt:128-140`), the protected-media grant to `sdk.scdn.co` or our origin only,
    `onConsoleMessage` returning true, `ServiceWorkerController`, the manifest's Safe Browsing and metrics flags.
  - `SpotifyPage.FROM_PAGE` with its five names. No `Log.` anywhere in `spotify/`; `spotifyLog` is `BuildConfig.DEBUG`
    only (`PlaybackService.kt:813-815`). `Authorization`/`Bearer` appear in code only in `StorytelApi.kt` and
    `SpotifyApi.kt:282`.
  - The recorder: null in main, set only in `DebugSpotify.kt:66`, one daemon executor, 32 MiB.
  - `SpotifyEndpoints`' six readers (`:39-63`), `debugTokens` only when unmanaged (`SpotifyRenderer.kt:1843-1857`),
    `debugSwitchToken`.
  - The fake's options are exactly `fake-spotify.py`'s argparse list (plus `--port`/`--bind`/`--version`), with its
    fake values. `play_request.json`'s keys. `ContractFilesTest:27` takes `spotify_hosts.json`.
- **`server/CLAUDE.md`**:
  - Every constant in the section: `PENDING_MAX` 8, `PENDING_TTL` 10 min, `LOGIN_BUDGET` 45 s, the 180/14 days,
    `DEV_MODE_USERS` 5, `EXPIRY_MARGIN` 60 s, `MAX_EXPIRES_IN` 86 400, `RETRY_WAIT_MAX` 5 s, `QUOTA_PAUSE` 1 h,
    `SHORT_LINK_HOPS` 3, 1000 tracks, 100 episodes, the artist's 20 albums / pages of 10 / 500 tracks, `ADD_BUDGET`
    45 s, `PHONE_TOKEN_MIN_LEFT` 10 min, 20 an hour, `PHONE_TOKEN_BUDGET` 8 s, `LOOPBACK_REDIRECT`.
  - The token route's statuses and codes (`music_api.rs:330-393`, the 30 s default included).
  - The cookie's attributes (`music_spotify.rs:654`), the CSP's `form-action`, `request_host`/`redirect_host`, and the
    routes in `main.rs:696-704,1027-1042,1092`.
  - The eight login outcomes (`spotify.rs:1058-1065`), the seven events, `secret_types_have_no_debug`'s exact twelve
    types, and the `spotify_gate!` SQL (`music.rs:982-988`).
  - `ReaderTurns`, `restamp_versions`, `flag_reports`, `SPOTIFY_WORDS`, `SPOTIFY_ON_ITS_PAGE`, `PLAYS_SPOTIFY`,
    `Skip::SpotifyShortLink`/`SpotifyProfile`, the import through `spotify::parse_link` (`music_import.rs:127-143`),
    `policy_music`'s `spotify` (`music.rs:1342`), `\nspotify:true` (`device_api.rs:643`) and `is_spotify_host` in
    `effective_blocklist` and `dns_filter`.
  - The `/dns` marks' text (`dns_filter.html:134,142`). The migration's two tables, column, five listing columns, two
    triggers, `AUTOINCREMENT`, and no `ON DELETE`.
  - `src/tests/music_spotify.rs`'s `listing`/`entries`/`phones` modules and `testdata/spotify/`.
  - The 0074 "Data model" paragraph is still right.
- **`launcher/CLAUDE.md`**:
  - `getSpotifyToken` with `check()` first (`MusicBridgeService.kt:149-153`), 13 AIDL methods with it last,
    `SPOTIFY_TOKEN_TIMEOUT_MS` 10 s, the seven statuses, the `ok`-not-whole/404/5xx/unknown mapping and the redacted
    `toString` (`SpotifyTokenCourier.kt`).
  - `setSpotify(musicSpotify(...))` before `refreshIfNeeded` (`MdmSyncWorker.kt:141-142`), `loadFromDisk` from
    `KidVpnService`, and `webViewProviderPackage()`.
  - `MusicPlanTest`'s entries 6 and 7 (the library's ids).
- **Root `CLAUDE.md`**: the layout line, and the CI paths against the three workflows' `grep -qE` filters
  (`launcher.yml:73`, `music.yml:67`, `server-ci.yml:48`). The implementer's "found, not changed" is right: root's CI
  line doesn't name `launcher.yml`'s `music/app/src/main/AndroidManifest.xml`.
- **`NOTICE.md`**: vibb's `LICENSE` is "MIT License / Copyright (c) 2026 palchrb", byte-equal (trailing space aside) to
  `MIT-vibb.txt`, so no new file is right, as in the sweep's and Sonos' paragraphs.
  - The named vibb symbols exist: `URI_RE`/`LINK_RE`/`to_uri` (`spotify.py:20-47`, `intl-` prefix, `spotify.link`),
    `bookmark_step`/`save_bookmark`/`read_bookmark` (`:256-288`), `play_origin` (`daemon.py:568-586`), the precache
    gate (`library.py:425-620`, snapshot-gated playlists, immutable kinds daily).
  - The "Title — Artists" rows are built in `library.py:988-1006` from `spotify.py:331`'s `context_tracks`.
  - The ported files credit vibb: `spotify.rs:34`, `SpotifyRef.kt:9`, `SpotifyTracker.kt:10`, `music_sweep.rs:2694`.
- **`PLAN.md` and design 26**: the status lines match what is built (steps 1-7, both merges, 0074, what is owed).
  Found, not this step's: `PLAN.md:133-135` still has design 29 "built on branch `design-29-impl`", but it is merged on
  `main` (`5c1f40f`) and in this branch (`5d6bd5b`). That is main's to update, like the phase 1b/2 lines the
  implementer noted.
- **The status's run counts** (827 / 1145 / 843, clippy's 12) match the merge QA's and step 6 fix round's records.
  No server or launcher file changed after the merge (`git diff --stat e4d4feb..f59f472 -- server launcher`: empty).
  No test reads a CLAUDE.md, NOTICE, PLAN or design doc.

## Fix-round checklist

- [ ] M1: the capture's commit named in full wherever it is described (three copies, six constants, the three
      PROVISIONAL assertions reversed, the "provisional"/"step 6 at the latest" comments in `spotify.rs` and both
      `SpotifyHosts.kt`), or the assertions made data-independent now; "three constants" -> "six".
- [ ] M2: open point 8 reworded (design 31 doesn't name Spotify; the "From Spotify ›" row is step 12's fifth Add row;
      `/music/spotify/callback` and `/done` never move); a line in `PLAN.md`'s design 31 entry.
- [ ] L1: the 0.24.0 run's cause (rule proven; secret set but unreadable: both lines, `-W`) and its remedy;
      `:1857-1858`.
- [ ] L2: `SHOWS_PLAYABLE` replaced by what is built (no switch) in `music/CLAUDE.md:359` and `:1910`.
- [ ] L3: `SPOTIFY_ARTIST_ALBUMS` into the DEPLOY note's table; "Spotify's short links"; where the note goes.
- [ ] L4: "Inert without Spotify" names the WebView guard's exception.
- [ ] L5: step 0's migrations by design; step 2's prerelease row; the merge commits' hashes; part B's cold-start line.
- [ ] L6: the two cross-references.
- [ ] Docs only: no suite needed; re-check the three hosts files are still byte-identical if M1 touches tests.

No question for the user that blocks the fix round. One for the user, from L1: the `SERVER_MINISIGN_KEY` environment
secret must be re-entered (the whole two-line key file, made with `-W`), and `server-v0.24.0`'s failed job re-run (or
the tag re-pushed) before any later `server-v*`.

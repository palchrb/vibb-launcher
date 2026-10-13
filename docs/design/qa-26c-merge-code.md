# QA (code): the merge of main `571f86f` into `music-3-spotify` (2026-10-13)

Reviewed: `e4d4feb`, the merge of `origin/main` `571f86f` (designs 30, 31 steps 2/3/11, 32 step 2, 33; 43 commits after
the merge base `a5d30b9`) into `2d0805e` (26c step 5b's fix round; 25 commits after the base), with the conflict
resolutions, the renumberings and the design 30 adaptation inside it. Read against root, `server/`, `music/` and
`launcher/CLAUDE.md`, design 30 (§1.1-§1.8, its step 1 L4 and step 3b), 26c's "Merge of main (571f86f, 2026-10-13)"
and the rows it says it changed (§0.2 assumption 3, §3.6, §3.7, §5.8, §5.10, §8 item 1, §9.4, §11, §14 item 12, QA
checklist item 7, Release), `emulator.md` §6k/§6l, and `qa-26b-merge-code.md`/`qa-26b-merge27-code.md` for the shape.

**Separating the adaptation.** `git merge-tree --write-tree 2d0805e 571f86f` gives tree `03e1652` with conflicts in
exactly the 16 files 26c names (`music.yml`, root `CLAUDE.md`, `PLAN.md`, `emulator.md`, `MusicPlan.kt`,
`PolicyResponse.kt`, `PolicyResponseCompatTest`, `DebugFeedReceiver.kt`, `Store.kt`, `PlaybackService.kt`,
`GuardsTest`, `ReportTest`, `models.rs`, `tests/device_api.rs`, `tests/music.rs`, `device_detail.html`).
`git diff 03e1652 e4d4feb` (30 files, +476 -386) was read in full: the 16 resolutions, the migration rename, the §6l
pointers, the `streamMobileData` renames (`logic/Spotify.kt`, `RendererRules.kt`, `SpotifyRenderer.kt`), the tests
(`GuardsTest`, `RendererRulesTest`, `SpotifyRulesTest`, `music_spotify.rs`) and the docs.

- **Main's side.** Of the 120 files only main changed since the base, 119 equal `571f86f` byte for byte; the one that
  doesn't is `server/CLAUDE.md` (+4 lines, the 0074 paragraph). For the 41 files both sides changed, a line-multiset
  check (every line either side added is in the merge unless the other side removed it; no line either side removed
  is back) flags only the conflict hunks above and the 0073 -> 0074 comments, each intended: main's `firstPlayable`,
  `RendererKind.LOCAL` in `comeHome`, `if (renderer.kind == SONOS) return true` and the plain `gated || copy == null`
  are the only main lines replaced, by our `playableIndex`, `phoneRendererFor(a0.entry)`, `itemPlayable` (`!kind.onPhone
  -> true`, the same for SONOS: `RendererKind(onPhone)` is `LOCAL(true), SONOS(false), SPOTIFY(true)`) and the
  Spotify-aware `offline`. Main's §6k (keyguard cover) in `emulator.md` lost no line.
- **Our side.** Of the 151 files only we changed, 140 equal `2d0805e`; the 11 that don't are the deliberate ones
  (26c, `DebugSpotify.kt`, `logic/Spotify.kt`, `RendererRules.kt`, `SpotifyRenderer.kt`, `RendererRulesTest`,
  `SpotifyRulesTest`, `fake-spotify.py`, the 0073 -> 0074 rename, `music_sweep.rs`, `music_spotify.rs`).

## Runs (this worktree, `e4d4feb`, 2026-10-13)

- `server/` (`CARGO_TARGET_DIR` in the scratchpad, Rust 1.97 locally; CI pins 1.99.0): `cargo fmt --check` clean;
  `cargo clippy --all-targets` **12 warnings**; `cargo test` **826 passed, 1 failed** -
  `tests::parent_pin::a_new_pin_ends_the_notices_but_not_the_list` (3 more runs of `parent_pin`: the same one red).
  It fails on main's own tree too (below), so it is not this merge's: its events are stamped `now + 1 s`/`now + 2 s`
  and the notice goes only if the second PIN save (`override_pin_set_at_ms`) lands after them, i.e. if hashing that
  PIN takes over 2 s. 26c's merge section saw it pass (827).
- **Clippy baseline, settled:** `git archive 571f86f server .github` (the tests `include_str!` the workflows) into a
  scratch dir, `cargo clippy --all-targets` with the same target dir: **12 warnings, the same 12** (9 dead
  fields/structs in `models.rs`, 2 doc-list lines in `dns_engine.rs:54-55`, 1 `sort_by_key` in
  `handlers/devices.rs:1457`); after stripping line numbers the two lists are identical - `models.rs`' sit 4 lines
  lower on the merge (our `music_spotify_account` and its doc). None new. The same tree's
  `parent_pin::a_new_pin_ends_the_notices_but_not_the_list`: red 2 of 2. Scratch tree deleted.
- `launcher/`: `./gradlew assembleDebug testDebugUnitTest` BUILD SUCCESSFUL, **1145 tests, 0 failures**
  (`MusicPlanTest` 35, `PolicyResponseCompatTest` 38).
- `music/`: `./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug -PwarningsAsErrors=true` BUILD
  SUCCESSFUL (up to date from the merge's own run, same tree), then `testDebugUnitTest --rerun`: **809 tests, 0
  failures** (`GuardsTest` 39, `SpotifyRulesTest` 18, `RendererRulesTest` 7, `ReportTest` 9, `BridgeTest` 12,
  `ContractFilesTest` 16, `SourceScanTest` 23); lint 0 errors, 42 warnings (dependency versions, `UseKtx`, the old
  `StaticFieldLeak`/`UsableSpace`/`ExportedService` lines - none in the merge's code).
- `python3 -I -B scripts/fake-{sonos,storytel,spotify,audio}.py --selftest`: all four OK.
- Mutations (each reverted, `git status` clean): (A) `downloadPlan` without the Spotify block - `SpotifyRulesTest` 2
  red (the new design 30 case on "no downloads state", and "never fetchable, never kept"); (C) `AndroidSpotifyPorts.
  networkLine` on `store.downloadMobileData` - `GuardsTest` red ("the web player's own gate: the streaming switch
  too"); (M) the Spotify block moved **after** the extras rule (`prune += rows.filter { it !in extras && it !in prune
  }`) - `SpotifyRulesTest` **green**, see Low 1.

## Findings

0 High, 1 Medium, 3 Low. The textual merge is right: nothing of main's or ours is lost, the renumberings are complete,
the contract files agree, the suites are green but for main's own red `parent_pin` test. The Medium is a semantic seam
the merge created and no test sees: main's design 30 keys the queue's claim and stale check on `kind == LOCAL`, and
this branch's callers pass the *current* renderer's kind, which is `SPOTIFY` after any Spotify play.

### Medium 1 - After a Spotify play, the next podcast/Storytel queue skips design 30's claim and stale check

- **Where:** `music/.../playback/PlaybackService.kt:654` (`playEntry`), `:665` (`playItem`), `:721` (`restore`, also
  under `onPlaybackResumption`'s non-Spotify branch `:538-547`): `queueFor(snap, rows, entry, plan, ..., renderer.kind)`;
  `:587-596` (`activeFor`: `if (kind == RendererKind.LOCAL && entry.source != Source.OWN) { PlayerHub.claim(...);
  CacheThrough.queueRows(...) }`); `:762` (`start` -> `usePhoneRenderer`, which changes the renderer only *after* the
  queue was built). 26c §5.10's new row (`docs/design/26c-spotify.md:1112`) and the merge section (`:3348`, `:3369`).
- **Evidence:** before the merge `activeFor` read `kind` only as `kind.onPhone` (the speaker filter), so `LOCAL` and
  `SPOTIFY` were the same to it and passing `renderer.kind` was harmless. Main's design 30 (§1.6, qa-30 L4) added the
  `kind == LOCAL` arm on the assumption that a phone queue's kind is `LOCAL`; `comeHome` was adapted
  (`phoneRendererFor(a0.entry)`, `:1842`), these three callers were not. The renderer stays the web player after a
  Spotify queue until the next `start` attaches `local` (`usePhoneRenderer`, `:785-795`), so the very next tap on an
  NRK/RSS/Storytel tile builds its queue with `kind = SPOTIFY`.
- **Failure path:** for that queue (1) no claim of its start item and the next before the rows are read: the window
  L4 closed - a sync's prune or the storage limit's eviction removing the start item's copy between the read and
  `start`'s `updateHeld(loaded = true)` - is open again; (2) no `CacheThrough.queueRows`: `stale` stays empty and the
  rows are `load()`'s, so an NRK/RSS item with orphan cached bytes or a partial from another source (§1.6
  `staleThrough`) plays **through the cache** instead of uncached - the old bytes first, until the length guard's
  416 reloads it (`playOnUncached`), or to the end if the lengths happen to match. The reverse also happens: a Spotify
  tile tapped while the renderer is `local` claims the Spotify item and its next and runs `queueRows` for a Spotify
  entry (harmless - `start`'s `updateHeld(loaded = true)` with the web player ends the claims, and nothing matches -
  but it contradicts "the web player ... claims nothing", §5.10 and `:3369`). `GuardsTest:812` pins the `kind ==
  LOCAL` line, not what the callers pass, so the suite is green.
- **Fix:** pass the kind the queue will play on, as `comeHome` does: a pure `queueKind(current: RendererKind, entry:
  Entry): RendererKind = if (current.onPhone) phoneRendererFor(entry) else current` in `RendererRules.kt`, used by
  `playEntry`, `playItem` and `restore` (`queueFor(..., queueKind(renderer.kind, entry))`). `RendererRulesTest`: LOCAL
  or SPOTIFY current + a feed entry -> LOCAL, + a Spotify entry -> SPOTIFY, SONOS + either -> SONOS. `GuardsTest`: the
  three call sites pass `queueKind(renderer.kind, entry)` and no `queueFor(`/`activeFor(` call passes a bare
  `renderer.kind`. Then §5.10's row and the merge section's sentence are true as written (say "the queue's kind" for
  `activeFor`, not `renderer.kind`).

### Low 1 - The design 30 test's "never a played extra" can't fail

- **Where:** `music/.../spotify/SpotifyRulesTest.kt:181-195`; the merge section `docs/design/26c-spotify.md:3366-3368`.
- **Evidence:** the three depths are 0 (`spotify()`'s `cache = 0`: the extras rule needs `cache != 0`), 5 and -1, with
  two listed tracks and `t2` played - at 5 and -1 `t2` is inside the keep set, so the extras rule never looks at it.
  Mutation M (the Spotify block after the extras rule, so a Spotify row *would* become an extra) leaves all 18 tests
  green. The test's other assertions (pruned, nothing wanted, no Storytel floor, no `downloads` state) do bite (mutation
  A), and today's code is right: the Spotify `continue` comes before the rule (`DownloadPlan.kt:71-74`).
- **Fix:** add depth 1 with the **older** track played (`done(6, "t1")`, held `t1`): `keepSet` keeps `t2`
  (`takeLast(1)`), so `t1` is listed, outside the keep set and played - an extra for any other source. Checked here:
  with that case the test is green on the real code and red on mutation M ("depth 1: never an extra").

### Low 2 - 26c's Release order contradicts design 30's

- **Where:** `docs/design/26c-spotify.md:1779-1790` (Release: "server 0.26.0 ... Deploy order: the server first ...,
  then the launcher, then the music app"), the merge section's last line (`:3392`: only "a server with design 30's
  0073 before or with this branch's 0074"); design 30's step 1 L4 (`30-music-streaming-data.md:855-857`) and
  `docs/handoff/2026-10-13.md:20`: no `server-v*` with design 30 before the `launcher-v*` and `music-v*` that read the
  two switches.
- **Evidence:** main is at `server-v0.24.0` with design 30 untagged (`Cargo.toml` 0.24.0, the last tag
  `server-v0.24.0`), and since this merge every Spotify server build carries design 30's step 1. If 26b's 0.25.0 or
  this 0.26.0 is the first `server-v*` after the merge, "the server first" ships the card's "Update both" note before
  the updates it names exist - what L4 forbids.
- **Fix:** one line in Release and the merge section: the first `server-v*` carrying design 30 waits for the
  `launcher-v*` and `music-v*` that read the switches (main's rule). Spotify allows that order: an older server sends
  no `music.spotify` (false) and no Spotify entry, so a new launcher and music app ahead of it show no Spotify and
  break nothing; "server first" is only the better order *once* design 30's apps are out.

### Low 3 - The cleartext config doesn't name the fake Spotify, and the merge section misses that §6l needs it

- **Where:** `music/app/src/main/res/xml/network_security_config.xml:3-10` ("the emulator's fakes (fake-sonos.py,
  fake-storytel.py, fake-audio.py) are on http://10.0.2.2"); `docs/design/26c-spotify.md:3373`.
- **Evidence:** `2d0805e` had no network security config and no `usesCleartextTraffic` (`git grep` of
  `music/app/src` at `2d0805e`: nothing), so with targetSdk 28+ OkHttp refused every `http://10.0.2.2:8731` call of the
  debug switch - `SpotifyApi`'s Web API and token route and `WebPolicy`'s `Proxy` fetch (the LAN client) - the same
  hole main's qa-30-s3b-code H2 found for Sonos and the other fakes. §6l part A2 (and A1's phone half) can only work
  since this merge brought main's `cleartextTrafficPermitted="true"`; it was never run (owed), so nobody saw it. The
  release path is unaffected: `SpotifyEndpoints`' bases are https constants unless the debug-only switch is set, and
  `WebPolicy.decide` refuses any non-https URL or port before the host check (`WebPolicy.kt:44-50`) - verified, so
  the merge section's sentence holds.
- **Fix:** add `fake-spotify.py` (the Web API, the token route and the web player's proxied SDK, debug only) to the
  config's comment (`SourceScanTest` pins the config's elements and the manifest reference, not the comment), and to
  the merge section's cleartext sentence: "§6l's fake is reachable from the phone only since this merge".

## Verdicts

1. **Nothing of main lost or altered - OK.** See "Separating the adaptation". Main's `onError` (`gated`, the `http_416`
   -> `playOnUncached` path, `renderer.kind == LOCAL && source != STORYTEL`), `markPlayed`, `updateHeld`, `activeFor`'s
   claim and `CacheThrough`, `mediaItem`'s `through` (NRK/RSS only), `stateReport`'s `audioBytes`/`extraBytes`
   (`Sync.kt:273-287`), the debug feed's switches and limit, the device card's two switches, storage limit and older
   phone note, `older_phone_note`/`mobile_data_wire` and main's tests are all present and unchanged in meaning.
   `Renderer.Error.gated` defaults to false and only `LocalRenderer` sets it (`SpotifyRenderer.kt:1188` emits
   `Error(UNPLAYABLE)`), so `offline = gated || !isSpotify(...) && ...` is right for both players.
2. **Nothing of ours lost - OK.** `playableIndex`, `itemPlayable`, `refuseSpotify`, `usePhoneRenderer`, the Spotify
   `mediaItem` branch, the resumption branch, `onError`'s synchronous move-on (the `http_416` arm can't take a
   Spotify item: `item.url != null` and `kind == LOCAL`), the report's `spotify`, the `spotify_gate!`, the device
   card's Spotify select, and every Spotify test are there.
3. **The renumberings - OK.** `0074_music_spotify.sql`: the body equals the old 0073 (the rename diff touches only
   lines 6-9 of the header), §3.7's block equals the file byte for byte (2666 bytes, compared in Python). Every
   reference outside the historical status/QA sections says 0074 (`models.rs:110`, `music.rs:979`,
   `music_sweep.rs:815, 1357`, `handlers/music.rs:4088`, `server/CLAUDE.md:345-348`, PLAN.md, 26c §3.7, §11, step 1's
   file list, step 7, §14 item 12, Release); `migrations/0073_music_spotify.sql` is named only in step 1's status and
   `qa-26c-step1-code.md`, as written. The `pre_migration_backup` note is right (`startup.rs:261`: `MAX(version)` only).
   The dev-database note is right: a pre-merge Spotify database holds version 73 with the Spotify checksum, 1-72 match,
   sqlx's applied-but-missing check passes (73 exists locally), so `run` stops at `VersionMismatch(73)` (after a
   `pre-migrate-*.db`, since 73 < 74). Strictly, "0073 reaching a server that ran 0074" can't come from any commit any
   more - every commit with 0074 has design 30's 0073 - so the hazard sentence is belt and braces. §6l: the heading and
   first paragraph, root `CLAUDE.md:28`, `music.yml:114`, `DebugSpotify.kt:14`, `SpotifyRenderer.kt:1643`,
   `fake-spotify.py:3, 638`, 26c §9, §9.4, §11 step 5b, the QA checklist and Release say §6l; every remaining "§6k" in
   the repo is main's keyguard cover (design 32, its QA files, the launcher's debug receiver, the handoff note) or in
   26c's status/QA sections and `qa-26c-step5b-code.md`, which the merge section re-maps. §6l's own "§6j's checks"
   (`emulator.md` part A1) is design 28's listening window - right.
4. **The design 30 adaptation - OK but Medium 1.** Server: `policy_music` sends `spotify` beside main's three keys
   (`music.rs:1312-1344`); a Spotify stream has no server-side switch of its own. Launcher: `musicState`'s keys carry
   `audio_bytes`, `extra_bytes` and `spotify`; `musicPolicy`/`musicSpotify` both read the one object (the KDoc names
   all eight keys). Music: `spotifyPlayability`, `spotifyNetworkLine` and `itemPlayable` take `streamMobileData`;
   `playable` defaults it from `store.streamMobileData`, `playableFrom` reads it once, `refuseSpotify` and
   `AndroidSpotifyPorts.networkLine` read it; the downloads switch and the storage limit appear in none of
   `SpotifyRenderer.kt`, `logic/Spotify.kt`, `RendererRules.kt` (pinned on comment-stripped code); `RendererRulesTest`
   and `SpotifyRulesTest` exercise the gate with the switch by name. Played extras/cache-through: `markPlayed` and
   `updateHeld` read the live `renderer.kind` and are right; `mediaItem`'s Spotify branch has no `ItemTag`, so the
   sink never sees one; `DownloadPlan`'s Spotify `continue` precedes the extras rule (Low 1 for the pin). Only
   `activeFor`'s `kind` comes from callers that pass the wrong one (Medium 1). `comeHome`'s
   `activeFor(..., phoneRendererFor(a0.entry), plan.startIndex)`: for a feed or own entry it is main's call exactly
   (`LOCAL`, the claim at the plan's start); for a Spotify entry (which never reaches a room) `SPOTIFY` makes no claim;
   a Sonos queue is unaffected (`toRoom`/`lookAtRoom` pass `SONOS`). The device card: the Spotify sentence sits on
   the **streaming** switch, "about 60-150 MB an hour ... never saved on the phone", which matches §3.6, §0.2
   assumption 3 ("nothing of it is cached"), §6l's `stream_mobile_data`/`download_mobile_data` line (part A2 is
   unmanaged, so `config.json`'s switches are the ones `Store` reads) and QA item 7; the test pins the order
   (streaming < Spotify < downloads). Cleartext: `WebPolicy` refuses http and non-443 ports itself (Low 3 for the docs).
5. **Contract files - OK.** `cmp`: both `bridge_contract.json` (main's three restrictions + our `spotify_token`), the
   three `spotify_hosts.json`, `music_library.json`/`music_listing.json`/`music_icons.json` (server vs music), both
   `phone_vectors.json`, and the three AIDL files are identical. `music_state.json` has main's `audio_bytes`/
   `extra_bytes` and our `spotify`: `music::tests::music_state_keeps_every_field_the_phone_sends`,
   `the_phone_reports_music_state_with_known_fields_only`, the launcher's `MusicPlanTest` and the music app's
   `BridgeTest`/`ContractFilesTest`/`ReportTest` pass. `policy_json_keys_snapshot`, `music_library_snapshot` and
   `music_listing_snapshot` pass unregenerated (main changed neither snapshot file; this merge didn't either).
6. **`GuardsTest`'s fixed pins - not weakened.** Every changed assertion is the same kind of check with the new text
   or stricter: the `RendererKind.` site list is still an exact list (main's four `LOCAL` lines added); `playable`
   gains its default-argument pin; `start`'s no-playable pin includes `updateHeld(loaded = true)` (the trailing space is
   the stripped comment); `onError`'s signature, the event call and the "own file and copy first" order follow main's
   `gated`; ours `val offline = !isSpotify(...)` and main's `val offline = gated || copy == null` became one pin on the
   whole merged expression (stricter than either); `comeHome` adds `plan.startIndex`. The one numeric change,
   `renderer.load(` 1 -> 2, is matched by a pin that the second is `playOnUncached`'s and by the `LOCAL`-only `onError`
   condition in the site list. New: the web player's `networkLine` reads the streaming switch (mutation C red), the
   no-`downloadMobileData`/`storageLimit` scan, `through` NRK/RSS only, `markPlayed`/`updateHeld`/`activeFor` lines.
7. **Hygiene - OK.** Root `CLAUDE.md` names all four fakes and their selftests; `music.yml`'s filter is the union of
   both sides' (Spotify's host lists and fixtures, `fake-spotify.py`, main's `fake-audio.py`) and runs four selftests
   under `-I`; `server-ci.yml`/`launcher.yml` are main's plus ours unchanged. `server/Cargo.toml` 0.24.0 and
   `Cargo.lock` are main's (`chrono-tz`); this branch adds no dependency. The merge section is accurate except the
   statements Medium 1, Low 1 and Low 3 correct, and its 827-passed server run (main's flaky `parent_pin`, above).

## Fix-round checklist

- [ ] M1: `queueKind(current, entry)` in `RendererRules.kt`; `playEntry`, `playItem` and `restore` pass
      `queueKind(renderer.kind, entry)`; `RendererRulesTest` (LOCAL/SPOTIFY/SONOS x feed/Spotify entry); `GuardsTest`
      pins the three call sites and that no `queueFor(`/`activeFor(` call passes a bare `renderer.kind`; §5.10's row and
      the merge section say the claim follows the queue's kind.
- [ ] L1: `SpotifyRulesTest`'s design 30 case gains depth 1 with `t1` played and held; check it red against the block
      moved after the extras rule, then revert.
- [ ] L2: Release and the merge section: the first `server-v*` carrying design 30 comes after the `launcher-v*` and
      `music-v*` that read the two switches (design 30 L4); Spotify tolerates apps first.
- [ ] L3: `network_security_config.xml`'s comment names `fake-spotify.py`; the merge section says §6l's phone half
      needs main's cleartext config.
- [ ] Not this branch's, for the main session: `tests::parent_pin::a_new_pin_ends_the_notices_but_not_the_list` is red
      on `571f86f` here (2 of 2) - its events at `now + 1-2 s` need the PIN save after them; stamping them in the past
      (`now - 2_000`, `now - 1_000`) or the save's time read back would make it deterministic. Server CI on `main` may
      be red on a fast runner.
- [ ] Still owed (unchanged): §6l parts A1/A2 on the user's emulator (now incl. the streaming-switch line), part B,
      the QA checklist, design 30's own §8 checks (main's).

## Not worth fixing

- `PLAN.md:124-125` and `emulator.md` §6l's first paragraph have one over-long line each from the resolution.
- `GuardsTest`'s `start` pin carries a trailing space where `code()` strips the `// the queue's claims end` comment;
  a reworded comment can't break it, a removed one would.
- §5.10's table still cites pre-merge line numbers (`:706-712`, `:1150-1182`); they were approximate before too.

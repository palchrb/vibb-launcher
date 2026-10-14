# QA 26c step 3 code review - the contract commit (the token route, the policy key, the bridge, the shared files)

Scope: branch `music-3-spotify`, `45a6b51..b304e77` (one commit, 67 files across `server/`, `launcher/`, `music/`,
`.github/` and root `CLAUDE.md`), read in full against root, `server/`, `launcher/` and `music/CLAUDE.md`, design 26c
(§0.3-§0.4, §2.1, §2.4-§2.6, §3.6, §4.1-§4.4, §5.5, §6.1-§6.3, §7.1, §8, §10, §11 step 3, the design QA fix round and
every status section to "Step 3 implementation status" with its 13 deviations and 4 spec findings),
`qa-26c-step1-code.md` (its note for step 3), `qa-26c-step2b-code.md` (M1, L3), `qa-26b-step3-code.md` (the template),
the launcher's DNS/FCM notes (`launcher/CLAUDE.md`, `push/FcmHosts.kt`, `play::is_fcm_protected`) and security review
2026-10-10 (M6). Line numbers are `b304e77`'s.

Runs, here:
- Server (`server/`, Rust 1.99): `cargo fmt --check` clean; `cargo clippy --all-targets` 12 warnings - `models.rs` (9),
  `dns_engine.rs` (2), `handlers/devices.rs:1321` (1), the known ones, none new; `cargo test` **751 passed, 1 failed**
  (`tests::parent_pin::a_new_pin_ends_the_notices_but_not_the_list`, main's timing test, the tolerated one).
- Music: `./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug -PwarningsAsErrors=true --rerun-tasks`
  BUILD SUCCESSFUL, **624 of 624** JVM tests (fresh), lint 0 errors / 39 warnings, none on a line step 3 touched.
- Launcher: `./gradlew assembleDebug testDebugUnitTest` BUILD SUCCESSFUL (tsnet stub), compile and tests executed,
  **1108 of 1108**.
- Byte identity, `cmp` by hand: `spotify_hosts.json` x3, `music_library.json`, `music_listing.json`,
  `music_icons.json` (server = music), `bridge_contract.json`, `IMusicBridge.aidl`, `IStorytelCallback.aidl`,
  `ISpotifyTokenCallback.aidl` (launcher = music), `phone_vectors.json` - all identical; the hosts file is §6.2's block
  byte for byte. `git status` clean before this file.

**Verdict: good to merge after a short fix round.** Counts: **0 High, 0 Medium, 6 Low.** The contract is right on all
three sides: the token route answers §2.6's table with the edges the implementer chose (each sound), is cancel-safe
through 2b's spawned `MintJob`, limits per phone without a race, and never puts a token anywhere but the 200's body;
`music.spotify` and the DNS version follow the binding exactly; the delivered list and the launcher's filter exempt
the list and nothing beyond it; the AIDL is appended; the music app parses Spotify entries and shows, fetches, keeps
and announces nothing of them. The Lows are two unpinned wiring points, a gate wait the 10 s budget turns into
`unreachable`, a 0/0 download state §4.4 says never exists, the override's invisibility where a parent types the block,
and spec text to bring in line with the deviations.

## Findings

### L1 (Low) - the launcher's Spotify flag is wired in two places no test reads

`DnsFilterEngine.classify` reads `spotifyOn` (`DnsFilterEngine.kt:144`), set only by `MdmSyncWorker.kt:141`
(`setSpotify(musicSpotify(freshPolicy.music))`) and by `loadFromDisk`'s read of the cached policy
(`DnsFilterEngine.kt:86-88`). `DnsFilterEngineTest` covers the pure `classifyDomain`; nothing in `launcher/app/src/test`
names `setSpotify`, `spotifyOn` or `loadFromDisk` (grep). Deleting line 141 keeps all 1108 tests green and the
exemption never switches on until the VPN restarts; deleting the `loadFromDisk` read keeps them green and a reboot
blocks Spotify behind a blocked parent until the first sync. (The ordering itself is right - see "Checked": the cache is
written at `:118` before the flag at `:141`, so the VPN's load can't read an older value than the sync set.)
Fix: a source-scan row like `EnforcementPlanTest`'s for the enforcer - `MdmSyncWorker.kt` holds
`DnsFilterEngine.setSpotify(com.kidslauncher.mdm.music.musicSpotify(freshPolicy.music))` before
`DnsFilterEngine.refreshIfNeeded(`, and `loadFromDisk` assigns `spotifyOn` from `cachedPolicy()`.

### L2 (Low) - "appended, never reordered" pins only the tail of `IMusicBridge`

`MusicPlanTest.kt:40-41` (last = `getSpotifyToken`, second-last = `wantArt`), `BridgeTest.kt:36-37` (`wantArt` before
`getSpotifyToken`, nothing after it) and `EpisodeArtTest.kt:160` (the last two) all check the end. A reorder of any of
the first eleven methods in both copies - the transaction codes an installed older app or launcher uses - passes every
test, because the byte-identity checks compare the two new copies with each other. Step 3 is the commit that changes the
AIDL, and the rule matters more with every append.
Fix: pin the whole sequence once on each side - `assertEquals(listOf("version", "openLibrary", "openCover", "openFile",
"releaseFile", "getStorytelLogin", "reportState", "setNowPlaying", "setForeground", "listingVersion", "openListing",
"wantArt", "getSpotifyToken"), methods)` in `MusicPlanTest` and `BridgeTest`; a future append adds one name at the end.

### L3 (Low) - a phone's refresh waits in the server-wide gate, so the 10 s budget answers `unreachable` during a sweep

`MintJob::mint` sends the refresh through `state.music_fetch` (`spotify.rs:1670-1673`), which is `music_net::Gated`:
one request in flight server-wide, FIFO, 200 ms apart (`music_net.rs:512-544`). During a sweep (feeds up to 120 s,
listings, design 27's art fetches after a new playlist) the token POST queues behind everything already waiting, so a
phone ask that needs a refresh - the server's cached token under 10 minutes, or none after a restart - meets
`PHONE_TOKEN_BUDGET` (`spotify.rs:131`, `music_api.rs:356`) and gets 502 `unreachable` while Spotify and the server are
fine. Nothing is lost (the refresh finishes and is kept: `a_slow_refresh_is_unreachable_then_kept`), and step 1's QA
accepted the gate with the remark "the phone's retry beats a hung request" - but §5.5's retry rule for this case is
"after `Unreachable` the next play asks again", i.e. the kid's next tap. The budgets also nest the wrong way: server
10 s, launcher `withTimeout(10 s)` (`MusicBridgeService.kt:164`), music app `withTimeoutOrNull(10 s)`
(`SpotifyBridgeToken.kt:82`) - each outer one starts earlier, so in the slow case the server's honest `unreachable`
never arrives; the music app's own timeout fires first (all map to `Unreachable`, so only the logs differ).
Required change (spec now, code in step 4): §2.6 says a refresh can outlast the budget behind the sweep and is kept;
§5.5 gives `TokenCache` one prompt retry (about 5 s) after an `Unreachable` at a play start, before the line shows -
it then finds the kept token. Optional: a server budget of 8 s so the 502 reaches the phone first. (The alternative -
a second `Gated` lane for `Kind::Token`, a different host - is a design change to §0.3's "one gate"; not needed if the
retry is there.)

### L4 (Low) - every listed Spotify entry reports a 0/0 download state; §4.4 says `downloads` never lists one

`downloadPlan` skips only `Source.OWN` (`sync/DownloadPlan.kt:49`); a Spotify entry with a parsed listing gets
`keepSet` = {} (cache 0, `Offline.kt:35`), `wantable` = {} and still `states += DownloadState(entry.id, version, 0, 0,
null)` (`DownloadPlan.kt:79`), which `stateReport` writes into `downloads` (`Report.kt`). §4.4: "`downloads` never lists
a Spotify entry"; the status: "the plan, the keep set and the prune never see one". Harmless today (a cache-0 NRK entry
does the same, and the server's Spotify card shows no counts), but it spends `DOWNLOADS_MAX` rows and contradicts the
contract text step 5b will build on.
Fix: `if (entry.source == Source.OWN || entry.source == Source.SPOTIFY) continue` at `DownloadPlan.kt:49` (no Spotify
row can exist to prune: nothing is ever fetched), and a row in `SpotifyRulesTest`'s "never fetchable" test:
`downloadPlan(snap, emptyList(), now).states` has no entry 6.

### L5 (Low) - the override is said only on the device page, not where the parent types the block

The binding drops `spotify.com`, `scdn.co`, ... from the phone's delivered list whatever a category or a **device
custom block** says (`device_api.rs:894-927`), and the launcher passes them before its walk. The only trace is the
device page's note (`device_detail.html:762-764`). A parent who adds `spotify.com` as a custom block on
`/dns?device=<id>` (`handlers/dns_filter.rs`, `create_custom_domain`) sees it saved and listed, and it silently never
applies on that phone; no event, and the DNS log never shows it (nothing is blocked). §6.2 asks for the device card's
sentence only, so this is beyond the spec, but it is the question a parent will have.
Fix: on `/dns?device=<id>` for a bound phone, mark each custom block `spotify::is_spotify_host` names: "not applied
while Spotify is on for this phone"; a test beside `the_policy_and_the_blocklist_follow_the_binding`.

### L6 (Low) - the spec body still says what the code deliberately doesn't

The status lists the spec findings, but the sections later steps read are unchanged:
- §6.2 / §8 item 2: `spotify:<bool>` in the version (built: `\nspotify:true` only while bound, deviation 2) and
  `expiresInMs` "0 unless ok" (built: also `busy`'s wait, deviation 1).
- §2.1 says the policy's `music.spotify` is false while no app is set up; §2.5 says it is true exactly while bound, and
  the code follows §2.5 (`music.rs:1237`, `policy.music_spotify_account.is_some()`). The two differ only in 2b L1's case
  (an environment client ID gone with bindings left): the phone keeps the exemption and the token route answers `off`.
  Keep the code; amend §2.1.
- §8 item 5 and §10: the music app's `SpotifyHostsTest` and constant are step 4's (deviation 11); §11 step 4's file list
  should name them; §11 step 3's music list should name `logic/Spotify.kt` (deviation 7) and `sonos/PlayBody.kt`
  (deviation 10).
- §4.4: after L4, the sentence stands as written.
Fix: amend those lines in `26c-spotify.md` in the fix round (text only).

## The implementer's deviations (13, all judged)

1. **`expiresInMs` carries `busy`'s wait**: accept. §2.6's `retry_after_s` and §5.5's `Busy(retryAfter)` had no other
   field, and a fourth parameter later would mean a second callback. Both readers honour it (courier
   `SpotifyTokenCourier.kt:69`, 1 s..1 h else 30 s; `tokenResult` > 0 else 30 s), the AIDL doc says so. Text: L6.
2. **`\nspotify:true` only while bound**: accept - sound. Bind and unbind each move the version (unbind returns it to
   the unbound value, which differs from the bound one the launcher holds, so it refetches); unbound phones keep their
   version across the upgrade (no 100k-domain refetch storm). Collision: the hash concatenates without lengths, so a
   device custom domain containing a newline (`create_custom_domain` trims only the ends) could in theory produce the
   same bytes - admin-only input, a stale list at worst, and the same property `ov:`/`cd:` already have. Not a finding.
3. **The route's order and edges**: accept. Binding then app (404 `off`, uncounted), then the limit, then the mint
   under the route's own budget; DB errors 500 `unreachable` (the courier passes the word through); Spotify's 429
   without a number 30 s, `Some(0)` raised to 1 s; `expires_in_s` whole seconds (≥ 600 on the kept path, 3540 fresh).
4. **`spotify::hosts` as three items, the server's test reading both app copies**: accept; `phone::tests` and
   `app_display` already read `../launcher` the same way, and it is what makes `server-ci.yml`'s new paths meaningful.
5. **A volatile flag, set at sync and at VPN load**: accept - checked for staleness both ways (below, "Checked"). L1 for
   its pins.
6. **The courier's mapping where §6.1 is silent**: accept. An incomplete `ok` never yields half a token; an older
   server's bare 404 is `off` (it can't bind anyone), a bare 5xx `unreachable`, the edge's bodiless 429/401 `launcher`;
   a newer word passes through (`[a-z_]{1,32}`) and the music app maps it to `Unreachable`.
7. **`SPOTIFY_PLAYABLE` a `val` in `logic/Spotify.kt`, defaults on `Snapshot`/`newsEligible`/`listedOf`**: accept.
   Nothing in `main/` passes `spotifyPlayable` or copies a snapshot with it (grep): only tests flip it, so release
   behaviour is pinned to "hidden" and the tests already exercise 5b's branch.
8. **NY gated too**: accept, and right. While hidden, `newsEligible`/`listedOf` give an empty eligible set, so
   `entryNews` adds nothing but `markNewSeen` still records every key as seen; after the flip only songs added later
   are news. A Spotify entry added after the design-29 baseline stays `pending` (no item to give, `NewItems.kt:223`
   only clears it with one), so after 5b's flip its next listing change gives it one new-entry item - design 29's rule,
   one row per entry, not a flood. Step 5b should know.
9. **Minimal `Kind.SPOTIFY` UI branches**: accept; the four `when`s needed them to compile, only `kind_spotify` added
   (both locales), the episode/track choice by `spotifyEpisodes`.
10. **`speakerDropReason` = `spotify`**: accept; makes "visible in both views" safe at the flip
    (`SpotifyRulesTest` pins the drop and `PlayBodies.build` null).
11. **No music `SpotifyHosts.kt` yet**: accept; `ContractFilesTest` already compares the music copy with the server's.
    Text: L6.
12. **Report `spotify` always sent, `none`**: accept. Consistent with `music_state.json` "every field": the app sends
    exactly the fixture's keys (`BridgeTest`'s fixture check), the launcher passes it (`musicState`'s key list), the
    server keeps `none` (`SPOTIFY_WORDS`).
13. **The snapshot's ids, names, versions, the fake account**: accept; checked that the string replacements in
    `OfflineTest`, `BookmarksTest` (`"resume": true,\n` - the Spotify entries default to true as sent) and
    `ContractFilesTest` still hit only what they meant; `OfflineTest`'s unnamed entry moved to 99 for the right reason.

## The spec findings (4)

All four are right: §8 item 2's callback had no room for the wait; `spotify:<bool>` would have refetched every phone's
list at the upgrade; §8 item 6's `server-ci.yml` path needed a server test that reads the copies; the music
`SpotifyHostsTest` belongs with step 4's constant. L6 asks for the body text to follow.

## Checked and fine

- **The route** (`music_api.rs:317-394`): behind `require_device_token` (`device_routes.rs:102-105`), `General` class
  (8 in flight per phone, the device's own bucket); every answer JSON with `Cache-Control: no-store` (pinned per answer);
  no token outside `ok` (pinned, and `FAKE-ACCESS` absent from every other body); `account` = `"<id>:<generation>"`.
  Only `debug`/`info` lines with the device id, the account id and the error code.
- **The limit** (`spotify.rs:1213-1234`): in memory, per device id, a rolling hour of `Instant`s under one
  `std::sync::Mutex` (check and push atomic - no race between two asks), refused asks not counted, the wait rounded up
  to when the oldest ask leaves, phones with an empty window pruned on every ask (bounded by the phone count), reset by
  a restart (acceptable: 20 an hour is a guard against a looping phone, not a quota). `off` never reaches it.
- **Budget and coalescing**: `tokio::time::timeout` drops only the caller; the refresh, the rotated token's write and
  the slot's update run in the spawned `MintJob` holding the account's `OwnedMutexGuard` (2b M1), so a refresh past the
  budget is kept, and a second ask within the budget waits on the same lock and then takes the kept token (one request:
  `a_slow_refresh_is_unreachable_then_kept`, `the_token_route_is_limited_per_phone`'s single refresh). `now` is read
  after the lock. A phone's 10-minute prefetch always meets a server token under 10 minutes (the phone's expiry is the
  server's plus the transport time), so it gets a fresh one rather than the same token back.
- **The policy**: `MusicPolicy.spotify` from the same column as the gate's binding; `policy_json_keys_snapshot`,
  `the_policy_carries_a_small_music_object` and `music_library_snapshot` updated; `needs_login` keeps it true (the tiles
  stay, §3.6). An older launcher ignores the key; `PolicyResponse.music` stays raw JSON.
- **The delivered list** (`device_api.rs:894-927`): FCM's exemption unchanged, Spotify's only for a bound phone, over
  categories and custom blocks alike; the parent's other blocks (`ads.example`, `evilspotify.com`, the third-party parent
  `akamaized.net`) stay - pinned for the bound and the other phone, and back after unbind.
- **`is_spotify_host`** (`spotify.rs:157-166`) and **`isSpotifyHost`**: lower-cased (ASCII on the server; dnsjava's
  `Name.toString(true)` escapes non-ASCII as `\DDD`, so Kotlin's Unicode `lowercase()` can't turn a look-alike into
  ASCII), trailing dot dropped, a suffix only after a dot with a non-empty label (`.spotify.com`, `evilspotify.com`,
  `spotify.com.evil.net`, `scdn.com`, `spotify.co` refused), exact CDN hosts only exactly. A query label holding an
  escaped dot (`a\.spotify` + `com`) would pass the string test, but it is a single label under `.com` that resolves
  nowhere - and the walk has the same property for blocks. The exemption is the list: nothing outside the five suffixes
  and two hosts passes.
- **The launcher's flag**: `storeAcceptedPolicy` (`MdmSyncWorker.kt:118`) writes the cache before `setSpotify`
  (`:141`), which runs before `refreshIfNeeded` (`:142`); `KidVpnService.onCreate` (`:75`) loads it from that cache,
  in the same process, so after a reboot the flag and the on-disk list come from the same accepted policy. Bind: the
  exemption starts before the list is refetched. Unbind: the old list (without Spotify's hosts) is kept until the
  refetch, so only the exact CDN hosts under a blocked `akamaized.net` close a moment early - the safe direction. A
  failed fetch keeps the in-memory flag. `classifyDomain` exempts FCM, then Spotify, both before the walk.
- **The courier** (`MusicBridgeService.kt:149-185`): `check()` first (pinned for all 13 methods), off main on the
  service's IO scope, one callback per call whatever happens (the answer computed in one `try`, the `onToken` in
  another), exceptions logged by class name only, `RemoteException` from a gone caller caught, no cache, nothing
  stored (source-scanned); the request goes through `createMusicServer` - the device bearer to the server's own host,
  over tsnet, no logging interceptor. The token crosses to the music app only through the signature-permission bridge.
- **The WebView provider** (`AppEnforcer.kt:856-861`, `EnforcementPlan.kt:172`): `getCurrentWebViewPackage()` returns
  the installed, current provider (Google's, `com.android.webview`, or the Jelly Star's, whatever its name) or null;
  null spares nothing new, and a package that isn't controllable is never in a hide/suspend set anyway. Read per apply,
  a cheap binder call; the `apply` callers read (the sync, the receivers' and runtimes' IO coroutines) are off main. On API 34+ Chrome is never the provider, so no browser is
  spared by this.
- **Music parsing** (`Library.kt:205-235`): `spotify` accepted with a canonical target and a known mode, else hidden
  with the id kept; cache forced to 0 before the range check; `lan` never; the field ignored on other sources; an older
  music app still drops the whole entry with its id kept. `SpotifyRef.uriOf` gives `spotify:episode:` for a show and an
  episode entry (2a's correction), `spotify:track:` otherwise.
- **Shows nothing, breaks nothing**: `Snapshot.visible` returns nothing for Spotify before the flip, in both views;
  `Snapshot.entries` (the carousel, search, categories via `categoryTiles(…, s.entries)`, the Nytt rows, `nowView`'s
  stored path, the service's lookups) filters on visibility, so no tile, count or empty category appears; `countedKeys`
  and `playerNumber` never see an entry that isn't shown; `NewNotifier` counts visible keys; `downloadPlan`/`artWant`
  want nothing (cache 0, not fetchable; L4 for the state row); the listing is still applied into Room and acknowledged.
- **`EpisodeArtTest`**: was "wantArt is the last AIDL method"; now "the last two are wantArt, getSpotifyToken" - the
  append rule still pinned at the tail on both sides (L2 for the rest).
- **`MusicFileDownloadsTest`** (launcher): the fake `MusicServer` gains `spotifyToken()` throwing, as its other unused
  methods do.
- **CI** (both ways): a change to any copy of `spotify_hosts.json` runs all three build jobs; the new AIDL is under the
  directories each app's workflow already watches in the other; `music_state.json` (read by both apps) is watched by
  both. Root `CLAUDE.md`'s new lines say exactly that.
- **Secrets**: no token in a log, `toString` (`SpotifyTokenAnswer`, `TokenResult.Ok` redacted and pinned), Room, a file
  or the report on any side; memory only on the phone (no disk in step 3); fixtures `FAKE-ACCESS-*`/`FAKE-REFRESH-*`.

## What the fix round must do

- [ ] Low 1: pin `setSpotify` before `refreshIfNeeded` in `MdmSyncWorker` and `loadFromDisk`'s read of the cached
      policy.
- [ ] Low 2: pin the full ordered method list of `IMusicBridge` in `MusicPlanTest` and `BridgeTest`.
- [ ] Low 3: §2.6/§5.5 text (a refresh can outlast the budget behind the sweep, it is kept; `TokenCache` retries once
      about 5 s after an `Unreachable` at a play start - step 4); optionally an 8 s server budget.
- [ ] Low 4: `downloadPlan` skips Spotify entries; a `SpotifyRulesTest` row.
- [ ] Low 5: `/dns?device=<id>` marks a custom block Spotify's binding overrides; a test.
- [ ] Low 6: amend §2.1, §6.2, §8 items 2 and 5, §10 and §11 steps 3/4 to the built contract.
- [ ] Re-run the three suites (server: fmt, clippy 12 none new, test; music with lint; launcher).

## Notes (not findings)

- `musicSpotify(null)` is false, and the server sends `music: null` when its library query fails
  (`a_failing_library_query_sends_music_null_and_never_fails_the_policy`): one such sync lifts the launcher's exemption
  until the next good one. The delivered list still lacks Spotify's hosts (the version hashes the binding, not
  `music`), so only the exact CDN hosts under a blocked `akamaized.net` are affected. Too narrow to change.
- Spotify's `Retry-After` on the token endpoint is passed on uncapped; the courier turns anything over an hour into
  30 s, and the server keeps no memory of an accounts-service 429, so each phone ask asks Spotify again - bounded by
  the 20-an-hour limit. Not worth a change.
- On a refresh failure the route answers `unreachable` even when the kept token still has some minutes left (below
  the 10-minute floor). A phone that already holds that token keeps playing; one that doesn't could have played those
  minutes. Rare (an accounts-service outage), not worth a rule.
- The `Gated` FIFO (L3) also means a phone's ask can wait behind another account's refresh; each account's own lock is
  separate, the gate is not.

## Not worth fixing

- `isSpotify(entry)` and `SpotifyBridgeToken` are unused in `main/` until step 4 (R8 strips them from the release).
- `the_policy_and_the_blocklist_follow_the_binding`'s "the version an unbound phone had before step 3" compares two
  unbound phones, not the old formula; the code only appends while bound, which is plain from reading it.
- `EpisodeArtTest`'s name still says "appended last" for `wantArt`; the assertion is right.

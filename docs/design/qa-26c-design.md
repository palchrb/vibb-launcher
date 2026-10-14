# QA 26c design review - Spotify (music phase 3) (2026-10-13)

Scope: `docs/design/26c-spotify.md` at `bcb36e0` (branch `music-3-spotify`, one commit on `main` `437eb84`), read against
the root, music, server and launcher `CLAUDE.md`, design 26 (the brief, "How to work"), 26a, 26b and its design QA (the
form), 27, 28, `vibb-spotify-options.md`, `docs/testing/spotify-spike.md` (§1, §7 item 10, §7b), vibb `pipewire`
`1fcadac` (`pi/vibb/spotify.py` all 403 lines, `library.py` `state_key`/`ORDERS`/`expand_target`), and checked against
the code on this branch: the music app's `playback/` (`PlaybackService` 1663 lines, `Renderer`, `LocalRenderer`,
`PlayerHub`), `data/Snapshot.kt`, `logic/{NetGate,Switch,Queue,Transport,Report}.kt`, `model/Library.kt`,
`storytel/StorytelEndpoints.kt`, `media/MediaCache.kt`, `sonos/PlayBody.kt`, the AIDL and `bridge_contract.json`; the
server's `music.rs`, `music_sweep.rs`, `music_net.rs`, `music_secret.rs`, `music_import.rs`, `play.rs`,
`handlers/{music,device_api}.rs`, `startup.rs`, `main.rs` (routes, `log_filter`), migrations 0049/0051/0066-0072; the
launcher's `MusicBridgeService`, `MusicServer`, `push/FcmHosts.kt`, `DnsFilterEngine`, `EnforcementPlan`. The Spotify
facts were re-read live on developer.spotify.com on 2026-10-13 (Web Playback SDK overview, reference and the
web-app-player how-to; Start/Resume and Transfer Playback; the February 2026 migration guide; Refreshing tokens;
Redirect URIs; Quota modes; Rate limits; Scopes; the Developer Policy and Terms). Line numbers are `bcb36e0`'s. QA wrote
no product code.

**Verdict: buildable after a fix round.** The shape is right and is the user's product: the parent's own developer app,
PKCE in the parent's browser with the refresh token sealed on the server, the server listing a link like an RSS feed,
the phone playing our own list by track URIs through a service-owned headless WebView, the launcher as a cache-less
courier, a per-phone binding gating library, policy and DNS. The OAuth design, the secrets rules, the migration and
the Policy table are sound (details under "Checked and fine"). The four Highs are all in the phone's service layer,
where the spec treats `SpotifyRenderer` as a drop-in renderer but the service still decides playability, resumption
and visibility with Storytel-era rules that make a Spotify item unplayable or invisible: every start says "Ikke
lastet ned" (H1), a headset play after a restart hands Spotify items to ExoPlayer (H2), context-mode entries never
show (H3), and the tracker adopts any track Spotify plays after a list ends as ours (H4). The Mediums are spec edits;
M9 splits two steps. Counts: 4 High, 9 Medium, 14 Low.

## High

1. **H1: the service's play gate makes every Spotify item "Ikke lastet ned"** (§4.2, §5.4, §5.10). §5.10's table
   names the `RendererKind` comparisons, but the service decides playability itself, before and after the renderer:
   `playable()` (`PlaybackService.kt:706-712`) returns true only for `RendererKind.SONOS`; for any other renderer it is
   `playability(hasCopy, item.url != null, ...)` (`logic/NetGate.kt:43-48`), and a Spotify item has no copy and
   `url == null`, so it is `GONE`. Consequences on the code as it stands:
   - `start()` (`:675-679`): `playableFrom` finds nothing, emits `NOT_DOWNLOADED` and returns - no Spotify start ever
     reaches the renderer; `goTo()` (`:885-888`, every step and swipe) the same;
   - `onItemChanged()` (`:1059-1070`): every natural advance the tracker reports is "not playable", so the queue
     seeks on and finally pauses with "Ikke lastet ned";
   - `onError()` (`:1167`): `item.url != null -> ItemErrors.record` never records a Spotify `unplayable` (the §4.4
     `item_errors` row never reaches the parent), and its `offline` test says `NOT_DOWNLOADED`;
   - `mediaItem()` (`:740`) builds the uri `item.url.orEmpty()` = `""` with `ItemTag(publicOnly)` - the renderer has no
     `spotify:track:<key>` to send (it must come from `uriOf(kind, key)`, which the spec never wires to the MediaItem);
   - the identity checks that mean "on the phone": `renderer !== local` at `:321`, `:1372`, `:1388`, `:1451`
     (`lookAgainBeforePlaying` - skipped while the Spotify renderer is attached), `:1602`.
   **Fix:** a pure `spotifyPlayability(caps, mobileData)` (stream gate only; never `GONE`) used by `playable` for a
   Spotify entry, with its own message (`NEEDS_WIFI`/`NEEDS_INTERNET`, not `NOT_DOWNLOADED`) and no "move on to the
   next item" (the whole entry is streams: pause, as §5.4 step 1 says); `onError` records `unplayable` for a Spotify
   item; `mediaItem` gives a Spotify item the uri `uriOf(...)` and no `ItemTag` (`GuardsTest`: it never reaches
   `ItemMediaSourceFactory`); §5.10's table lists `playable`, `playableFrom`, `start`, `goTo`, `onItemChanged`,
   `onError`, `mediaItem`, `wakeModeFor` and every `renderer === local`/`!== local` site with the rule each follows.
   Tests: a Spotify cover tap reaches `SpotifyRenderer.load/play` (no `NOT_DOWNLOADED`); offline it says "trenger
   internett" and nothing moves.

2. **H2: playback resumption hands Spotify items to ExoPlayer** (§5.7 "Restart", §5.10). After a restart the service
   attaches `LocalRenderer` (§5.7). `onPlaybackResumption` (`PlaybackService.kt:473-509`: a Bluetooth/headset play with
   nothing loaded, and Android's media-resumption card in the shade) does not go through `start()`: unless the renderer
   is a room it calls `restore()` and returns `MediaItemsWithStartPosition(a.items.map { mediaItem(a, it) }, ...)`
   (`:505`) - Media3 then sets and plays them on the session's player, `KidPlayer(local.player)`. A stored Spotify
   queue (H1's empty uris) goes into the ExoPlayer: an error per item, the queue walked, nothing plays. §10's
   `GuardsTest` row ("never reaches `LocalRenderer.load`") doesn't catch it - this path never calls `load`. **Fix:** in
   `onPlaybackResumption`, a stored Spotify queue takes the room's branch shape: attach the Spotify renderer, `resumeLast()`
   (through `start`, gated), and fail the future (no local items); `restore()` names the renderer it builds for. Test: a
   headset play after a restart with a stored Spotify queue starts `SpotifyRenderer` and hands the session nothing.

3. **H3: context mode can't reach the screen or the service** (§3.3, §3.6, §5.9, step 6). The server lists a
   context-mode entry with **no items** (§3.3). On the phone `Snapshot.entries` keeps only entries with visible items
   (`data/Snapshot.kt:107`), `playEntry` returns on a missing entry or an empty order (`PlaybackService.kt:581-583`),
   and `restore()`'s `storedPlan` drops stored keys not in `snap.order(entry)` (`:652`). The "one synthetic item" exists
   only in the service (§5.9, step 6's files: `SpotifyRenderer`, `SpotifyTracker`, `PlaybackService`, `Store`,
   `ListScreens`) - so an artist or a foreign playlist never shows as a tile, and if it did, its key "= the
   bookmarked track id, else `context`", changing with every track, breaks the stored queue (`NowPlayingRow.keys`),
   `Active.items`, `lastIndex` and the position keys (positions by (entry key, item key), music/CLAUDE.md). **Fix:**
   the synthetic item is made in one pure place the snapshot uses (`Snapshot.listed` for a `spotifyMode == "context"`
   entry: one `PlayItem` with a **stable** key `context`, the entry's name, no url); its position row is `(entry key,
   "context")` holding the live track's position, and the live track id/name live in `spotify_context.json` (already
   there for the hint) - the bookmark's item key never changes; step 6's files add `data/Snapshot.kt`,
   `logic/LibraryView.kt` (hint, no "N av M") and `ui/UiModel.kt`; tests: a context entry shows as a tile, plays, and a
   restart restores it paused at the live track.

4. **H4: the tracker adopts whatever Spotify plays after our list** (§5.4 "Relinking", "Natural advance").
   "A current uri that isn't in the window is ... taken as the next index when it came without a command of ours right
   after an end." After the window's or the queue's last track, the account's **Autoplay** ("when your audio ends, we'll
   play you something similar") can start a recommended track on the Connect device [device check: whether the SDK's
   device autoplays after a `uris` list]; the rule then maps it to index + 1 (or past the queue's end), saves positions
   under our item and moves the bookmark - uncurated audio counted as the kid's list, against "nothing starts by
   itself" and the parent-picked library. The `linked` escape hatch is weak too: the Web API removed `linked_from` from
   track objects in February 2026 (migration guide, "Removed fields: Track ... linked_from") and the SDK's documented
   `WebPlaybackTrack` has no `linked_from`. **Fix:** a uri not in our window is ours only when it matches the expected
   next item by `linked` (if present) **or** by name and duration within 2 s; otherwise it is "not ours" (paused, never
   saved), and at the queue's end any later state of another uri is paused at once. The next window is sent before the
   window's last track ends (at its last 15 s), so a list never runs out under Spotify's control. `SpotifyTrackerTest`
   gains "autoplay after the window", "autoplay after the queue", "relink matched by name/duration". The QA checklist
   gains: play a 3-track album to its end with Autoplay on in the kid's account - nothing plays after it.

## Medium

1. **M1: the renderer's Media3 state during a start and a transient is unspecified** (§5.4 `showsPlaying`, §5.10,
   design 28). `showsPlaying` is defined as "the last state is ours, not paused, at most 12 s old" - false for the whole
   cold start (WebView + SDK + `ready` + `PUT`, up to 25 s) and after any 12 s gap in the 5 s posts. On the phone the
   rule is the intent (`LocalRenderer.showsPlaying` = play-when-ready, not ended, `LocalRenderer.kt:159`), and on a room
   26a kept `playWhenReady` through transients "so the FGS survives". The switch hold does not cover the start: the
   service's command ends when `start()` returns (synchronously), and `requestStands` ends the hold "as soon as the
   player moves" (`logic/Switch.kt:49-55`), not when sound starts. So the play button shows "play" for up to 20 s
   (the kid taps again: `playEntry` calls `renderer.play()` mid-start, `:577-580`); design 28's `sessionPlay` and
   `setNowPlaying(playing)` flip to paused on a gap; and a SimpleBasePlayer reporting not-playing lets Media3 leave the
   foreground. A headless page also risks Chromium's background-timer throttling of the 5 s `setInterval` [device check;
   audible pages are exempt]. **Fix:** a §5.4 table like 26a §3.2's: the `SpotifyPlayer`'s `playWhenReady` and
   `playbackState` (BUFFERING with play-when-ready during a start; READY/playing on a fresh state of ours; held through a
   gap of up to 30 s), `showsPlaying` = intent while a start of ours runs or a fresh playing state is in, false on a
   paused/`null`/foreign state; `play()` during a start is a no-op. `SpotifyRendererTest`: a 15 s start shows playing
   intent throughout and one `PUT`.

2. **M2: the hourly token is fetched on demand, at expiry, in Doze, over two IPC hops and tsnet** (§5.5, §1.1, QA
   checklist 6). The SDK calls `getOAuthToken` "when a user's access token has expired" (SDK reference, verified). The
   phone answers from `TokenCache` only while the token has 5 minutes left, else it asks the bridge -> launcher ->
   tsnet -> server -> (maybe) Spotify, at the moment the old one died, typically with the screen off. The spike never ran
   this path (its token was pasted; §7b's night run was paused), and tsnet in Doze is untested (launcher `CLAUDE.md`:
   "tsnet gets no network callbacks", design 19's Jelly Star run still owed). **Fix:** while the renderer shows playing,
   `TokenCache` refreshes ahead (at 10 minutes left, under the renderer's wake lock), so `needToken` is answered from
   memory; a failed prefetch retries every 2 minutes until expiry; the debug log names each refresh. Checklist item 6
   becomes "2.5 h screen off, playing", crossing at least two refreshes, with the log lines; item 13's "Six months"
   stays.

3. **M3: the emulator chain (§9.3, §9.4 part A) can't work as written** (26b QA M2 again). (a) The page's origin is
   `https://appassets.androidplatform.net`; the fake's stub SDK, its tone (`/fake/audio/<id>.wav`) and its long-poll
   (`/fake/device/<id>/commands`) are `http://10.0.2.2:8731` - mixed content, which the WebView blocks by default
   (`MIXED_CONTENT_NEVER_ALLOW` on API 21+); the CSP line doesn't change that. (b) The play call goes "through the
   guarded public-only OkHttp client" (§5.4), which refuses 10.0.2.2 (`MediaCache.guarded`, `MediaCache.kt:64-67`); so
   does `debugTokens()`. Storytel needed a fifth-style reader for exactly this (`StorytelEndpoints.apiOverLan`,
   `StorytelEndpoints.kt:52-53`). (c) Server side, §2.4 sends token requests `Reach::Public`; `music_net` refuses a
   private or loopback address for `Public` (`music_net.rs:71-76`, `redirect_policy` `:311-325`), so
   `SPOTIFY_ACCOUNTS_BASE=http://127.0.0.1:...` is refused - Storytel's login uses `Reach::Any`
   (`storytel.rs:1733`). **Fix:** the debug switch's readers become: `apiBase()`, `apiOverLan()` (the LAN client for the
   play call and `debugTokens`), `sdkUrl()`, `webHosts()`, `debugTokens()` - and the page reaches the fake through the
   interceptor (the stub SDK, audio and long-poll fetched by `shouldInterceptRequest` from the fake and served under the
   https origin, debug only) rather than relaxing mixed content; `SourceScanTest` pins them. Server: `Reach::Any` only
   when the loopback override is set. Acceptance gains "§6j part A runs end to end" before step 5 closes.

4. **M4: §5.10's natural-advance row contradicts §5.4** ("the same branch with `onPhone`" vs "Spotify plays on through
   the window by itself (no call from a sleeping phone)"). The branch (`PlaybackService.kt:1072-1077`) seeks a newly
   current item to its own resume position; every Spotify entry is `resume` on (§3.2), so each natural advance into a
   track heard before sends a new `PUT` (a new window) from a sleeping phone, with a gap and a restart of the window.
   vibb's Spotify context just plays on (`spotify.py` has no per-track seek on advance; the bookmark is per context).
   **Fix:** keep that branch `kind == LOCAL` (ExoPlayer only): on Spotify, as on a room, the next item starts where
   Spotify starts it. `SpotifyRendererTest`: a natural advance into a partly heard track sends no `PUT`.

5. **M5: a 403 on the play call is taken as the track's fault, and nothing caps the cascade** (§5.4 step 3, §5.8).
   "403 -> `not_premium` when `reason` is `PREMIUM_REQUIRED`, else `unplayable` for the item" - but a 403 is far more
   often about the account or the app: a user not in User Management ("API requests ... will receive a 403", Quota
   modes, verified), the app owner's Premium lapsed ("the app will stop working", migration guide), `DEVICE_NOT_CONTROLLABLE`,
   `REMOTE_CONTROL_DISALLOW`. A track the account can't play usually starts and is skipped by the SDK, not refused by the
   call. With "unplayable -> the queue moves on" (`onError`, `:1172-1179`: seek, prepare, play), every item fails in
   turn: a `PUT` per track through a 1000-track list, `item_errors` full of false `unplayable`s, then the rate limit.
   **Fix:** the play call's 403 is an account/app outcome (`unreachable`-class line "Spotify - spør en voksen", the report
   `reauthorize` or a new `refused`), never an item error; `unplayable` comes only from the SDK (`playback_error` on a
   uri, or a skip past a uri we sent); and the renderer stops after 3 unplayable items in a row (paused, "Kan ikke
   spilles"). `SpotifyApiTest`/`SpotifyRendererTest` rows.

6. **M6: §5.8's lines have no carrier** (§5.8, §4.3). The table gives "the tile and the player's quiet line" per
   failure, but the code has (a) `RendererState`, the Sonos enum `{GROUPED_AWAY, TAKEN_OVER, LOST_SESSION, UNREACHABLE}`
   (`PlayerModel.kt:46`) read by `PlayerScreen.rendererStateText`; (b) `PlayerHub.Message`, transient
   `{NOT_DOWNLOADED, CANT_PLAY, CANT_PLAY_ON_SONOS, ROOM_NOT_ALLOWED}` (`PlayerHub.kt:124`); (c) the tile's line, which is
   `LibraryView.hint` from the snapshot (no renderer state). Also `callStep` treats `rendererState != null` as "not
   paused by us" (`PlaybackService.kt:1121`), so a Spotify `ELSEWHERE` state would break the call rule. **Fix:** name
   the carrier per row: new `RendererState` values (`SPOTIFY_NEEDS_WIFI`, `_NEEDS_INTERNET`, `_ASK_ADULT`,
   `_NOT_ON_PHONE`, `_ELSEWHERE`, `_STOPPED`) with strings, shown on the player and - for the entry that is loaded -
   on its tile through `PlayerHub.State`; the persistent account states (`reauthorize`, `not_premium`, `no_widevine`) on
   every Spotify tile from the stored outcome (L8); `callStep`'s "paused" test reads a Spotify state as paused.

7. **M7: the DNS exemption's delivered half goes stale** (§6.2). The launcher refetches the blocklist only when
   `dns_filter_version` changes; `compute_dns_filter_version` hashes the global list, the overrides and the custom
   domains (`handlers/device_api.rs:591-624`), not the binding. Binding a phone drops the Spotify domains server-side,
   but the launcher keeps its cached list (the launcher's gated exemption covers that direction); **unbinding** leaves
   the phone with a list that lacks them until some other change, so a family block of `spotify.com` stays off on a
   phone that no longer has Spotify. Also not said: on a bound phone a parent's deliberate block of `spotify.com` or
   `scdn.co` (a "streaming" category) is overridden for every app on the phone. **Fix:** `compute_dns_filter_version`
   hashes `spotify:<bool>`; a test that bind/unbind changes the version; the device card's DNS section says "Spotify's
   hosts are allowed on this phone while Spotify is on". (Launcher name: the exemption goes in
   `DnsFilterEngine.classify`, `DnsFilterEngine.kt:107-111`, where `isFcmHost` is; there is no `blockedCategory`.)

8. **M8: the server steps miss places and machinery** (§3.1, §3.4, §3.6, step 2). Beyond those named: `restamp_versions`
   (`music_sweep.rs:960-966`, `IN ('nrk', 'rss', 'storytel')`: a Spotify listing is never restamped after a restore);
   `SweepView::gated` and `phone_count` (`handlers/music.rs:2786-2800`: the "not sent - Spotify is off on this phone"
   line and "on N phones" live there); `models::DevicePolicy` (gains `music_spotify_account`; `policy_music` reads it,
   `music.rs:1062-1087`); `interval(source, ...)` (`music_sweep.rs:415`) has no kind, so "playlist/show `I`, others 24 h"
   needs a signature change; `default_category`/`default_kind` are per source (`music.rs:319-325`), so "Podkast for
   show and episode" needs a kind-aware variant. And the short-link resolution has no machinery: the gate always follows
   up to 5 redirects (`music_net.rs:311-345`) and `SourceResponse` carries no `Location` (only ETag/Last-Modified,
   `:429-430`). **Fix:** add these to step 2's files (and `music_net.rs`: a `follow: bool` on `SourceRequest` and the
   `Location` in `SourceResponse`, `Reach::Public`); acceptance "Check now on a Spotify card", "a restore restamps a
   Spotify listing".

9. **M9: steps 2 and 5 are too big, and step 4 waits on step 3** (§11). Step 2 is 26b's 2a+2b in one (parsers, readers,
   modes, the snapshot gate, the sweep, art, import, add with ticks, four templates, the device card, binding); step 5
   is the WebView host, the renderer, the locks, every service site (H1, H2), the UI words and quiet lines (M6), the
   debug switch (M3), the manifest and §6j - 26a and 26b each split their equivalent (26a §11 M8, 26b QA M8). And
   step 4's `SpotifyHosts.kt`/`SpotifyHostsTest` read the music copy of `spotify_hosts.json`, which only step 3 adds -
   while step 3 "waits for the capture" (§11, §14 item 12): step 4 can't start first as claimed. **Fix:** 2a (spotify.rs
   parsers and readers, the sweep check and modes, art; CannedFetch tests) and 2b (the add form, ticks, cards, binding,
   import, device card); 5a (`SpotifyWebHost`, `SpotifyRenderer`, `SpotifyLocks` against a scripted host:
   `SpotifyRendererTest`) and 5b (the service sites, UI, debug switch, §6j part A). Step 3 lands with the provisional
   host list (marked unverified in its comment) and the capture updates the three copies in a later one-line commit;
   don't gate the contract on it.

## Low

1. **L1: §5.10's table, details.** The UI's `onRoom` is read from the output (`OutputKind.SONOS`,
   `MusicViewModel.kt:41`, `MusicRoot.kt:75`), not `RendererKind`: no change there. `attach(spotify)` from the phone
   must `local.standDown()` like `toRoom` (`:1552`; "a paused Exo keeps its audio focus"), or our own ExoPlayer holds
   focus against the Spotify renderer's request. `toRoom` from a Spotify entry keeps the phone playing with
   `CANT_PLAY_ON_SONOS` (`:1534-1539`), not "paused" as the row says - the code's behaviour is the better one; say so.
2. **L2: scopes.** `user-read-playback-state` is justified by "the phone's transfer check", which no part of the design
   makes (state comes from the SDK; transfer needs only `user-modify-playback-state`): drop it. The SDK's three are
   from the web-app-player how-to (developer.spotify.com/documentation/web-playback-sdk/howtos/web-app-player), not the
   getting-started page. `user-read-private` "gives the token its country": `/me` no longer returns `country` and
   `market=from_token` is documented on the token's account, so the scope's reason is the SDK's list. Say that the
   phone's hourly token carries every scope of its account (one scope set; a parent account bound as "plays as" sends
   `playlist-read-private` to the phone) - acceptable for 1 h, but state it.
3. **L3: platform facts to correct or settle.** (a) `/me` 403 for an account not in User Management is documented
   (Quota modes: "API requests with an access token associated to that user and app will receive a 403") - drop the
   [unverified] in §2.3. (b) Start/Resume documents 204/401/403/429 only and "the order of execution is not guaranteed
   when you use this API with other Player API endpoints": the 404 -> transfer -> play retry must wait for the transfer's
   204 and the SDK's state (or `ready`) before the second `PUT`. (c) The SDK overview now says it "is supported by most
   web browsers ... in both mobile (Android and iOS) and Desktop", and "requires a Spotify Premium subscription (mobile
   only types of premium subscriptions are excluded)": open point 10's "unsupported on a phone" becomes "supported in
   mobile browsers; a WebView isn't named"; assumption 1 adds "not a mobile-only Premium plan". (d) H4's `linked_from`.
4. **L4: short links** (§3.1). vibb follows the whole chain (`urlopen(target)` then `r.url`, `spotify.py:42-46`);
   `spotify.link` may go through `spotify.app.link` before `open.spotify.com` [unverified]. Follow at most 3 redirects,
   each `Location` on `spotify.link`/`spotify.app.link`/`open.spotify.com` only (M8's no-follow, looped), and parse the
   final one.
5. **L5: the redirect suggestion** (§2.1): "https when the host is a name" is wrong for a plain-http deploy by name
   (Docker on the LAN, `http://pi.lan:8080`): the suggested `https://pi.lan/...` leads nowhere. Suggest https only when
   the request was https (the server's own TLS or a trusted proxy's `X-Forwarded-Proto: https`), else the loopback form.
6. **L6: the callback renders on the code's URL** (§2.3). The page "Logged in as ..." stays at
   `/music/spotify/callback?code=...&state=...` in the history, and its "Back" link sends that URL as `Referer`
   (`Referrer-Policy: same-origin`, `admin_auth.rs:483`). The code is spent, so harmless, but 303 to
   `/music/spotify?login=<outcome>` costs nothing. Optional defence in depth: a 10-minute `SameSite=Lax` cookie holding
   the state's hash, checked at the callback (a leaked authorize URL then can't be completed from another browser).
7. **L7: §7.3's Terms row** says "nothing is cached by us". The server stores track titles (`music_items`) and album
   art (the art store), the phone stores them in Room and its covers. Terms: "Do not store Spotify Content
   indefinitely", local caching "limited to the temporary caching of metadata and cover art". Make it an accepted-risk row
   and say what deletes them (the entry removed: its listing cascades, design 27's art prune).
8. **L8: the report's `spotify` outcome has no store** (§4.4). `Sync.writeReport` runs in the worker
   (`sync/Sync.kt:214-234`) and reads Storytel's state from `StorytelAccount.state()` (a file); the renderer's last
   outcome lives in the service's memory. Persist it (`noBackupFilesDir/spotify_state.json`, tmp + rename) and read it
   there; M6's tiles read the same.
9. **L9: visibility on a room** (§4.2). "Never on a Sonos room" in `Snapshot.visible` would drop every Spotify tile from
   the carousel whenever a room is the output (`MusicViewModel` builds the screens' snapshot with `forRenderer(onRoom)`),
   and `speakerDropReason` would never run. Say: visible on both views; a room's queue drops them
   (`PlayBodies.speakerDropReason` -> `spotify`, "Kan ikke spilles på Sonos").
10. **L10: names and files.** `IMusicBridge.aidl` also needs `import me.vibb.bridge.ISpotifyTokenCallback;` at its top
    (the §8 snippet shows only the method; imports are not "reordering"). The courier goes through `MusicServer`
    (`createMusicServer`, `music/MusicServer.kt`, the music routes' one interface), not `MdmApi`. `new_track` already
    exists ("New track", `strings.xml:80`): reuse it or rename consistently. CI: `launcher.yml` and `server-ci.yml` must
    watch `server/testdata/spotify_hosts.json` and the app copies (root CLAUDE.md lists the shared files' paths), and
    the spec must say which test pins the three copies byte-identical (music `ContractFilesTest` vs the server's, the
    launcher's vs the music app's, as `MusicPlanTest` does for the AIDL).
11. **L11: WebView hardening gaps.** Service workers' requests bypass `shouldInterceptRequest` unless
    `ServiceWorkerController.getInstance().setServiceWorkerClient(...)` applies the same `WebPolicy` (platform API 24, no
    `androidx.webkit`). A single `null` state can be transient around a transfer or track change: confirm `Elsewhere` by
    `getCurrentState()` 2 s later (or `not_ready`) before pausing. On `MY_PACKAGE_SUSPENDED` destroy the host whatever
    the state (no Connect device while suspended - a remote "transfer to Vibb" plays a moment before "not ours" pauses
    it, since media needs no gesture); say the blip is accepted otherwise.
12. **L12: artists could be list mode** (§3.1, §3.3, open point 2). Only `/artists/{id}/top-tracks` was removed;
    `GET /artists/{id}/albums` is not in the migration guide's removed list [unverified: dev-mode paging limits]. An
    artist listed as its albums' tracks (one request per album, batch endpoints gone) gets resume, the order setting,
    art and NY like an album - vibb listed artist contexts too (`context_tracks`, `spotify.py:331-366`). Offer it as the
    default with a track cap, context mode as the fallback; the user picks (question 1).
13. **L13: the quota is shared with the phones** (§3.4, §5.4). Quota is "per developer account" across its dev-mode
    apps (Quota modes); every phone's `PUT /me/player/play` counts against the same bucket as the sweep. The phone maps
    any 429 to "busy"/`unreachable`; a `QUOTA_EXCEEDED` should say so in the report (`quota`) so the parent's card
    explains a dead Spotify, and the sweep's one-quota-ends-the-pass rule should leave the playing phones room.
14. **L14: shuffle is not a user question** (§14 item 3). The brief: "Music behaviour questions ... follow vibb: check
    its code and decide; don't ask". `canShuffle` is own files without resume (`logic/Queue.kt:18`) and every Spotify
    entry is resume on, so the phone rule already says no; vibb shuffles contexts. Decide (keep off, with that reason in
    §12) and drop the question.

## What the fix round must do

- [ ] H1: Spotify playability in the service (`playable`, `playableFrom`, `start`, `goTo`, `onItemChanged`, `onError`,
      `mediaItem`, `wakeModeFor`, every `renderer === local` site) and §5.10's table naming each; tests.
- [ ] H2: `onPlaybackResumption` for a stored Spotify queue: attach, `resumeLast`, no local items; test.
- [ ] H3: the context-mode synthetic item in the snapshot with a stable key; position/bookmark keyed `context`, the live
      track in `spotify_context.json`; step 6's files; tests.
- [ ] H4: the tracker's ownership rule (expected next by `linked` or name + duration), the next window sent before the
      last track ends, anything after the queue's end paused; tests and a checklist line on Autoplay.
- [ ] M1: the Media3 state table for `SpotifyPlayer`, `showsPlaying` as intent during a start, `play()` mid-start a no-op.
- [ ] M2: token prefetch while playing; checklist item 6 at 2.5 h with refresh log lines.
- [ ] M3: the debug readers (`apiOverLan`), the fake served through the interceptor, `Reach::Any` only with the override.
- [ ] M4: the natural-advance branch stays `LOCAL` only.
- [ ] M5: play-call 403 = account/app outcome; `unplayable` only from the SDK; stop after 3 in a row.
- [ ] M6: the carriers of §5.8's lines (`RendererState` values, `PlayerHub.State`, the stored outcome), `callStep`.
- [ ] M7: `dns_filter_version` hashes the binding; the device card says the override.
- [ ] M8: step 2's missing files and machinery (`restamp_versions`, `gated`/`phone_count`, `DevicePolicy`, `interval`,
      `default_category`, no-follow + `Location` in `music_net`).
- [ ] M9: split 2 -> 2a/2b and 5 -> 5a/5b; step 3 with the provisional host list, the capture as a follow-up.
- [ ] L1-L14 as written (L3's corrections in §1.1/§0.2/§14; L12 becomes question 1).

## Open points (§14): settled here, or still the user's

- **Settled from the docs and code:** 3 (shuffle: L14), 4 and 5 (text attribution with the word "Spotify" and the
  link-back in the PWA sit inside the accepted Policy risk - nothing for the user unless they want the logo), 6, 8 and 9
  (device checks with a default, not questions), 7's second half (per-phone exemption is the stricter choice; FCM is
  global because every phone needs it), 10 (L3c: Spotify now lists Android browsers as supported), 11 (the family's
  matter, stated), 13 (later step 8), 14 (the main session's).
- **Still the user's:** 1 (accounts: below), 2 (below), 12 (the remaining spike runs are the user's to run - the DNS
  capture, and the Jelly Star runs extended per M2).

## Questions for the user

1. **Links the family's accounts can't list** - an artist, and a playlist neither logged-in account owns or
   collaborates on: (a) play them as a Spotify context (Spotify picks the order, no track list, an artist with no
   resume - the draft's default), (b) refuse them at add with a sentence, or (c) for artists, list their albums' tracks
   like an album (resume, order and track list work; more requests per daily check; L12)?
2. **Accounts:** each child gets their own Spotify Premium Family member account (not a mobile-only plan), and the
   parent's account is logged in too only if it owns the playlists - so at most two to three of the app's five users?
   And may the design tell you to switch off Autoplay on the children's accounts if the device check (H4) shows the web
   player autoplays after a list?

## Checked and fine

- **OAuth/PKCE** (§2.1-§2.4): public client, refresh with `client_id` and no secret ("client_id - Only required for the
  PKCE extension"), 6-month refresh tokens not extended by a refresh, "when a refresh token is not returned, continue
  using the existing token", `invalid_grant` -> re-authorize (Refreshing tokens, read 2026-10-13); redirect URIs https
  or a loopback literal, `localhost` refused, exact match except a loopback port (Redirect URIs). The callback among
  `public_routes` (`main.rs:655-670`) behind `gate::admin_gate`, outside `require_full_auth`, GET past `csrf_guard`;
  the session cookie is `SameSite=Strict` (`security.rs:369`), so the exemption is needed and the single-use,
  in-memory, admin-started `state` is the right guard (an outsider can't create one). Verifier 64 chars of the RFC 7636
  alphabet, S256, state 128 bits, 8 pending x 10 min. The paste flow re-enters the same code path behind a session and
  CSRF. Refresh-token rotation written before use with an in-memory `unsaved` fallback. Sealing through
  `seal_with`/`open_with` keeps Storytel's `storytel-v1:<fp>` AAD (`music_secret.rs:46-48`) untouched.
- **Spotify facts marked verified** hold against the live docs: dev mode (Premium owner, 5 users, 25 client IDs since
  July 2026, `/playlists/{id}/items` only for owner/collaborator, `items[].item`, batch endpoints, top-tracks and
  `/users/{id}*` removed, `/me` without `product`/`country`/`email`), `QUOTA_EXCEEDED` 429, the rolling 30 s window and
  `Retry-After`, Start/Resume (`uris` "track URIs", `offset` only for album/playlist contexts, `position_ms`), Transfer
  (`device_ids` single, `play`), the SDK (`getOAuthToken` at `connect` and on expiry, `enableMediaSession` default false,
  the events and errors, `type` track/episode/ad), the Developer Policy text and section numbers (effective 15 May
  2025: II attribution/link-back/cover art, III children/other services/mix/core experience/analysis, IV Premium,
  VI "Spot"), and the Terms' copying limits that rule out pinning or vendoring the SDK.
- **The migration** (§3.7): 0073 is next after `0072_music_storytel.sql`; nothing edits 0066-0072; `music_entries.source`
  already allows `'spotify'` (`0049_music.sql:35`); `ADD COLUMN ... REFERENCES` with a NULL default is valid with
  `foreign_keys(true)` (`main.rs:1195`), and unbinding before deleting is required by it (no `ON DELETE`) - as written;
  `AUTOINCREMENT` for never-reused ids; the revision triggers mirror 0072's; the `MAX(version)` backup hazard
  (`startup.rs:255-271`) is named.
- **The gate and the library**: `spotify_gate!()` beside `storytel_gate!()` (`music.rs:761-783`) adds one bind per use
  (say it in the binds' comments); `LIBRARY_ENTRIES`, `device_listing`, `cover_in_library` (`music.rs:1092-1110`),
  `art_in_library` (`:1132-1142`), `schedule` (`music_sweep.rs:590`) are the right places; an older music app drops a
  `spotify` entry with its id kept (`model/Library.kt:184`), so nothing is pruned; `art_candidate`
  (`music_sweep.rs:2591-2594`) is the one rule to widen; `MAX_LISTING_BYTES` 1 MB fits 1000 tracks; `valid_key` fits
  22-character ids; vibb's `state_key` and `bm_path` both hash the canonical URI (`library.py:32-38`, `spotify.py:250-253`).
- **The bridge contract** (§8): appended method plus a new callback file, byte-identical copies, the statuses in
  `bridge_contract.json`, `callerAllowed` on the launcher side, a oneway call to an older launcher dropped and timed
  out; `music_listing.json` unchanged; `music_state.json` gains one key; `PolicyResponse.music` is computed per request
  (`music.rs:1062-1087`), so a binding change reaches the next policy without a version.
- **The launcher**: FCM's split (server drops, launcher exempts before the suffix walk, `play.rs:20-58`,
  `push/FcmHosts.kt`, `DnsFilterEngine.kt:111`) fits Spotify; the WebView provider has no launcher icon so
  `controllablePackages` already skips it (`AppEnforcer.kt:96-103`), and on SDK 34 Chrome can't be the provider, so the
  `neverRestrict` guard never exposes a browser.
- **Secrets and logs**: `log_filter` drops `reqwest` directives (`main.rs:137-152`), `network_error` strips URLs, the
  gate logs paths only (`gate.rs:126`); fakes never look real; the token reaches the page only through a quoted
  `evaluateJavascript` argument; `Authorization` stays in two files (`SourceScanTest`).
- **Product**: the tile, the list of tracks or episodes, play/pause/skip/seek, resume per entry, NY on a new track,
  mobile data through the existing switch, the offline line, "fails gracefully" on no app / no account / no Premium /
  expired login / revoked app (once H1, M5 and M6 are in) - the user's §0.1 definition is met; Sonos as a later step 8 is
  a reasonable cut.

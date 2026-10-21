# Security and privacy review: music app (2026-10-21)

Scope: `music/` (Vibb music, `me.vibb.music`) at `ff61b0aa` (design 54-a1, Spotify on Sonos, just merged; `main` is at
`f9d6d5f5`, but the two commits since then only add the sibling reviews and touch neither `music/`, `scripts/` nor
the bridge AIDL). Also in scope: the scripts the app's fixtures come from (`scripts/sonos-play-test.py`,
`scripts/sonos-ws-probe.py`) and the shared test data the app reads (`server/testdata/music_*.json`,
`server/testdata/spotify/`, `spotify_hosts.json`). This is the music app's first full security review. Its siblings
from today are `security-review-2026-10-21-server.md` (cited as "server ...") and
`security-review-2026-10-21-launcher.md` (cited as "launcher ..."). Their findings are cross-referenced here, not
repeated.

Read-only: no code changed, Gradle not run, nothing run on an emulator or a phone. Media3's behaviour (1.7.1) was
checked in its bytecode (`javap`). Line numbers are those of `ff61b0aa`. Paths are relative to
`music/app/src/main/java/me/vibb/music/` unless they start with `music/`, `server/`, `scripts/` or `docs/`.

## Summary

| Severity | Security | Privacy |
|---|---|---|
| High | 0 | 0 |
| Medium | 1 (S-M1, with a privacy side) | 0 |
| Low | 5 | 4 |
| Info | 9 | 3 |

There are no High findings. The secrets are handled with care:
- The Storytel login is sealed under a Keystore key in `noBackupFilesDir`.
- The Storytel JWT, the minted URLs and the Spotify token stay in memory, every holder's `toString` redacts them, and
  `spotify/` has no `Log.` at all.
- The web player's console is swallowed.
- The bridge binds only a launcher signed like this app.
- The Sonos event socket's pinning (53-c) is sound, and certificate identifiers are never logged in a release build.
- Spotify on Sonos (54-a1) cannot be made to load anything but `spotify:<kind>:<id>`.

The weak spot is the Sonos **control** plane. UPnP has no authentication, and the app trusts whatever the LAN says:
- any device that answers SSDP can become a "speaker";
- a speaker may live at any IP literal, public ones included;
- the speaker cache never forgets.

A hostile network that the phone joins once can therefore plant a speaker that follows the phone home (S-M1). The
other findings are robustness and defence in depth:
- bodies of any size;
- a media session any app can drive;
- a CSP wider than the host list;
- release logs that name rooms and titles.

| ID | Severity | Finding |
|---|---|---|
| S-M1 | Medium (security + privacy) | SSDP and the topology trust any LAN device; a planted "speaker" at any IP (public too) stays in `sonos.json` for good, is asked first at every sheet opening, and gets the plays (Storytel's minted URL included) |
| S-L1 | Low | SOAP replies are read whole with no size cap and often no total deadline, and Android's DOM parser takes a DOCTYPE: a LAN device can hang a room's actor or crash the app |
| S-L2 | Low (security + privacy) | Any installed app can connect to the media session, read what plays, and replace the queue with any public URL |
| S-L3 | Low | The web player's CSP allows any `https:`/`wss:` host, WebSockets and redirect targets bypass `WebPolicy`, and the JS bridge is open to every frame |
| S-L4 | Low | Cleartext `http` enclosures can be swapped on a hostile network: a stranger's audio in a child's player |
| S-L5 | Low | `sonos-ws-probe.py --save` scrubs by denylist: URL tokens, public/CGNAT IPs and unlisted keys would be committed as they are |
| P-L1 | Low (privacy) | Release builds log Sonos uids (the speaker's MAC), room names, episode titles, foreign stream URIs and raw exception text |
| P-L2 | Low (privacy) | Every speaker the phone ever found, on any network, is reported to the parent's server with its MAC and room name |
| P-L3 | Low (privacy) | No `dataExtractionRules`: `allowBackup="false"` does not stop a device-to-device transfer on Android 12+ |
| P-L4 | Low (privacy) | Real-looking ids and possibly real first names in test data, against the standing rule |

## Medium

### S-M1. A planted Sonos speaker: any LAN device, any address, never forgotten

- **Evidence:**
  - `sonos/Ssdp.kt:41-49` (`parseReply`): a reply counts as a speaker when its `SERVER` header contains "Sonos" and its
    `LOCATION` is an IP literal. `DatagramSsdpIo.receive` (`:88-96`) returns the text only and drops
    `packet.address`. So the LOCATION host is never compared with the sender and never limited to the LAN: a public
    address, loopback or `192.0.2.2` (the launcher's tun) all pass `Topology.location`/`isIpLiteral`
    (`sonos/Topology.kt:73-87`), on any port from 1 to 65535.
  - `RoomDirectory.round()` (`sonos/SpeakerCache.kt:307-318`) POSTs `GetZoneGroupState` to the hits in the order they
    answered, and the first household that parses wins. `Topology.member` (`Topology.kt:62-66`) takes any non-empty
    `UUID` as the uid (no `RINCON_` shape), any `ZoneName`, and the ip:port from the reply's own `Location`.
  - `applyTopology` merges players in (`SpeakerCache.kt:94`) and nothing ever removes one. A topology can therefore map
    a **real** room's uid to the attacker's address, or add rooms of its own.
  - `refreshTopology` (`SpeakerCache.kt:241-256`) asks the cached players **in uid order** and stops at the first
    answer. A planted uid that sorts first (`"0"`, `RINCON_0…`) is asked first at every sheet opening and every 60 s
    group watch, from whichever Wi-Fi the phone is on.
  - SOAP has no address guard by design (`sonos/Soap.kt:30`). `CachedSpeakers.speaker(verify = true)`
    (`SpeakerCache.kt:343-352`) asks the cached ip for its UDN, so the attacker answers the expected one.
  - A play then sends `SetAVTransportURI` with the item's URL and DIDL to that address (`sonos/SonosTransport.kt:186`).
    For a Storytel book that URL is the freshly minted CDN URL, whose `token=` is a bearer for the book (design 26b
    §5.1).
- **Scenario:**
  1. The kid's phone joins a hostile Wi-Fi: a café, a school, a friend's compromised router. The kid opens "Spill av
     på" and taps "Se etter høyttalere" (a cache miss or a failed play does the same).
  2. A device there answers the M-SEARCH with `LOCATION: http://203.0.113.5:1400/` and then serves a topology with a
     room "0" at that address, named whatever it likes.
  3. From then on, every time the sheet opens, at home too, the phone first POSTs to `203.0.113.5` over the home's
     internet. The attacker learns the household's public IP and when the child uses the app. The attacker's answer
     replaces the groups, so the real speakers' topology isn't read: a DoS of grouping.
  4. If the kid picks the planted room, the attacker gets the play URLs: what the child listens to, and a Storytel
     book's bearer URL.

  On the home LAN itself, a compromised device can instead take over a real room's uid. Note that it could already
  sniff the cleartext UPnP traffic, if it can see the traffic at all.
- **Why Medium:** it needs a hostile network or device, and the kid has to open the sheet there. But it is
  persistent, and it reaches public hosts from the home network. The launcher's DNS filter doesn't see it (IP
  literals), and nothing in the app ever undoes it.
- **Fix:**
  1. Accept an SSDP reply only when the LOCATION host equals `packet.address` **and** is in a speaker's range. Reuse
     `speakerLiteral` from `sonos/SonosSocket.kt:77-96` (RFC 1918, link-local, ULA): the socket gate already encodes
     "a speaker is on the LAN".
  2. Apply the same range check to every topology member's `Location` and in `Soap.url`. A speaker is never public,
     loopback, CGNAT or the tailnet.
  3. Accept a uid only when it is `RINCON_[0-9A-F]{12,20}` (as `logic/Report.kt:23` already does).
  4. Bind the cache to the network: store the Wi-Fi network's identity with each player (for example the gateway's
     address, or a hash of the BSSID, if a permission allows reading it) and offer, ask and refresh only the current
     network's players. Prune a player not seen for N days.
  5. Optional, stronger: before a topology may move a **known** uid to a new ip, check the new ip with the 53-c pinned
     TLS handshake on 1443 (`speakerCertRefusal` proves the uid). That is the only real identity a speaker has.
  6. Cap a round: at most N hits, and a total budget like `refreshTopology`'s `TOTAL_MS` (today `round()` has none, and
     each hit costs up to `PER_IP_MS` 2 s).

## Low: security

| ID | Finding | Evidence | Fix |
|---|---|---|---|
| S-L1 | **SOAP replies: no size cap, often no deadline, DOCTYPE allowed on Android.** `execute` reads `r.body?.string()` whole (`sonos/Soap.kt:115`), and a call deadline is set only when a budget is given (`:113`; `SoapSpeaker.left()` is null for the play, `playShare`, `readQueue` and `learnQueue` paths, `sonos/SonosTransport.kt:171, 248, 401, 415`). Only connect 2 s / read 5 s apply (`Soap.kt:143-148`), and the read timeout restarts with every byte. A Browse `Result` is parsed a second time from a string (`Soap.kt:188-190`). `parseXml` (`Soap.kt:203-211`) sets the two Xerces features inside `runCatching`, and Android's `DocumentBuilderFactory` throws on both, so on the phone a DOCTYPE is not refused (the comment says "Android's ignores DTDs anyway"). KXmlParser fetches no external entities, so XXE is very unlikely. **Doubt:** I could not confirm whether Android's DOM builder expands nested internal entities ("billion laughs"); my belief that it doesn't is about 65 % sure. With S-M1 the sender can be any LAN device, or a public host. | a device that trickles one byte every 4 s holds the room's `SonosActor` and the transport lock indefinitely (no play, no pause on that room); a 500 MB body is an OOM, and the process dies | Read at most 1 MiB (`source().readUtf8(limit)` / `peekBody`), refuse anything else as `SpeakerUnreachable`; always give the call a deadline (6 s when no budget); refuse a reply containing `<!DOCTYPE` or `<!ENTITY` before parsing (cheap, and it holds on Android and the JVM alike) |
| S-L2 | **The media session is open to every app.** `PlaybackService` is exported with the `MediaSessionService` action (`music/app/src/main/AndroidManifest.xml:66-73`). `onConnect` accepts every other package with Media3's defaults (`playback/PlaybackService.kt:534`), which means all player commands. `KidPlayer` removes only shuffle and repeat (`:484-489`). Media3 1.7.1's controller sends `setMediaItem(s)`/`addMediaItem(s)` with their `LocalConfiguration` (`toBundleIncludeLocalConfiguration`), and the default `Callback.onAddMediaItems` accepts any item that has one. On the phone's ExoPlayer such an item gets `ItemMediaSourceFactory`'s untagged path (`media/ItemMediaSourceFactory.kt:58`): public-only, through the stream gate, cache read-only. That fails closed as far as the network goes, but it plays. Any app can also read the session's metadata and timeline. | an allowed app (a game, a browser) connects with `SessionToken(ctx, ComponentName("me.vibb.music", ".playback.PlaybackService"))` and calls `setMediaItem(fromUri("https://…/anything.mp3")); play()`: the child's curated player plays it, and what the service then saves for the active entry is unclear (**doubt**: I didn't trace `savePosition` with a foreign item). Or it just reads `mediaMetadata` and learns what the child listens to. `playAllowed` still gates `play()` during a time rule. | For packages other than our own and the launcher, remove `COMMAND_SET_MEDIA_ITEM`, `COMMAND_CHANGE_MEDIA_ITEMS` (and `COMMAND_GET_TIMELINE`/`COMMAND_GET_METADATA` if the privacy side matters) from the `AcceptedResultBuilder`'s player commands; override `onAddMediaItems`/`onSetMediaItems` to fail for every controller but our own process. The system UI, Bluetooth and the lock screen use transport commands only. |
| S-L3 | **The web player's bounds are looser than its host list.** The CSP is `connect-src https: wss:; media-src https: blob:; img-src https: data:` (`spotify/WebPolicy.kt:111-113`, `music/app/src/main/assets/spotify/player.html:11`): any host. `shouldInterceptRequest` sees neither WebSockets nor a redirect's target (the code says so, `spotify/SpotifyWebHost.kt:40-42`, and leaves them to "the DNS filter and the CSP"), so neither bound holds for them. `addJavascriptInterface` (`SpotifyWebHost.kt:139`) exposes `VibbSpotify` to every frame, of any origin. The inputs are validated and capped (`:206-236`), and the token goes only to the main frame (`player.js:147-151`), so a frame can't read it. The host suffixes (`spotify/SpotifyHosts.kt:15`) admit every subdomain of `spotify.com`/`scdn.co`/…, and the list is still provisional. No `base-uri`/`form-action` in the CSP. | A compromised or changed SDK, or a 3xx on any Spotify host, sends the token (it lives in the main frame's SDK) over `wss://` or a redirect to any host. A frame on any Spotify subdomain can forge `state`/`ready` (steering which device id the Web API play targets) or raise errors (a DoS). That is within the trust given to the SDK, but the "loads nothing else" claim isn't enforced. | Write the host list into the CSP: `connect-src https://*.spotify.com wss://*.spotify.com https://*.scdn.co …` (generated from `SpotifyHosts`, like the slots), the same for `media-src`/`img-src`, plus `base-uri 'none'; form-action 'none'`. Do it with the DNS capture's real list (26c "Release"). In `call()`, check that the main frame is still `BASE_URL` before `evaluateJavascript`. |
| S-L4 | **Cleartext enclosures.** `base-config cleartextTrafficPermitted="true"` (`music/app/src/main/res/xml/network_security_config.xml:17`) is a deliberate choice (Sonos SOAP, `lan` feeds, http enclosures). A public entry's http enclosure is then plain on whatever network the phone is on. The address guard (`media/MediaCache.kt:89-98`) stops private addresses, not tampering. Downloads and through-writes keep the substituted bytes as the copy. The launcher's transport is its own finding (launcher S-M1). | On a hostile Wi-Fi, an http podcast episode is replaced in flight with other audio, and the swapped copy stays on the phone until the source changes length (`LengthGuard`). | For a public (non-`lan`) entry, try `https://` first for an `http://` enclosure and fall back only on a TLS/connect failure, or have the server's sweep record whether the host does https and rewrite the URL in the listing. Keep cleartext for Sonos SOAP and `lan`. |
| S-L5 | **The capture scrubber is a denylist.** `Saver.scrub` (`scripts/sonos-ws-probe.py:166-179`) rewrites values by key (`NAME_KEYS`, `ID_KEYS`, `IMAGE_KEYS`) and by a few regexes (`:91-113`). Gaps: dict keys are never scrubbed; no rule for URL query tokens (a Storytel `token=` stream URL under any key but `imageUrl`/`url`); `IPV4` matches only 10/172/192/169 first octets (public, CGNAT and tailnet addresses pass); a separator-less MAC outside `RINCON_`; names under `author`/`narrator`/`displayName`/`description`; `RINCON_`/`Sonos_` case-sensitive. The committed captures were checked and are clean (`music/app/src/test/resources/sonos/ws/`: `RINCON_000000000000014NN`, `Sonos_FAKEhousehold…`, `FAKE-SERIAL-N`, `10.0.0.x`, `Navn N`). Both scripts print real uids, names, the household id and full TrackURIs to the console (`sonos-ws-probe.py:454`, `sonos-play-test.py:369`, `:567-568`). | The next capture is of a Storytel book or a radio station: its signed stream URL or a public IP goes into a fixture and into git history. | Add a fail-closed pass after scrubbing: refuse to write the file if any string still matches `https?://` with a query, an IPv4 not in `10.0.0.0/24`, `[0-9A-Fa-f]{12}`, `RINCON_(?!0{12})`, or `Sonos_(?!FAKE)`, and name the key; scrub dict keys too; make the scripts' console lines print shapes unless `--verbose`. |

## Low: privacy

| ID | Finding | Evidence | Fix |
|---|---|---|---|
| P-L1 | **Release logs name rooms, uids and titles.** `SonosHome.log` is an unconditional `Log.i` (`sonos/SonosHome.kt:142-144`), the controller, session, transport, socket and group verbs all log through it (`sonos/SonosRenderer.kt:85-87`, `:146`), and R8 keeps it (no `-assumenosideeffects`, `music/app/proguard-rules.pro`). Lines with a uid (`RINCON_` + the speaker's MAC): `SonosSocket.kt:575, 582, 619, 645`, `GroupVerbs.kt:69, 74, 86, 91`, `playback/PlaybackService.kt:1878, 1913, 2079, 2159`. Room names: `SonosSession.kt:828`, `SonosController.kt:1657`, `PlaybackService.kt:2039, 2042`. Episode titles (from third-party feeds; they say what the child listens to): `SonosController.kt:746`, `:1225`. Raw exception text, which can carry a UPnP `errorDescription`, `ip:port` or a uid: `SonosController.kt:1236`, `:1271` (`$e`), `:1455`. Someone else's stream: `SonosSession.kt:574` logs the speaker's TrackURI through `redactUri`, which keeps scheme, host and path (only `x-sonos-spotify:` is cut to its prefix). Certificate identifiers are **not** logged in release (`SonosSocket.kt:293`, `certLog` only under `BuildConfig.DEBUG`, `SonosHome.kt:92`), as design 53 requires. Logcat isn't readable by other apps, but it goes into bug reports and to anyone with adb. | A bug report from the kid's phone, shared to debug a Sonos issue, lists the household's speaker MACs, room names and a day of episode titles | Gate `SonosHome.log` like `spotifyLog` (`BuildConfig.DEBUG`), or keep a release subset with fixed words only; replace `$e`/`e.message` with `e.javaClass.simpleName`; or strip `Log.v/d/i` in release with `-assumenosideeffects` (launcher P-L4 proposes the same) |
| P-L2 | **Every speaker ever found is reported.** The speaker cache never drops a player (`SpeakerCache.kt:94`, S-M1), and the report's `sonos_rooms` is the whole cache, up to 32 (`sync/Sync.kt:324`, `logic/Report.kt:38-42`). The server keeps it 30 days and in the room picker (server I16, which judged it needed for the home's own rooms only). | The kid opens "Spill av på" at a friend's or the grandparents' house: their speakers' MACs and room names ("Emmas rom") go to the parent's server at every sync, and stay in the kid's sheet at home for good | With S-M1's network binding, report only the home network's players (or those the parent's scope names), and prune players not seen for N days |
| P-L3 | **Device-to-device transfer isn't excluded.** The manifest has `allowBackup="false"` (`music/app/src/main/AndroidManifest.xml:33`) but no `android:dataExtractionRules`. For an app targeting Android 12+ (`targetSdk = 36`), `allowBackup="false"` stops cloud backup but not a D2D transfer. The secrets are safe (`noBackupFilesDir` is never transferred), but `databases/` (positions, favourites, played times), `filesDir/music` (library, listings, covers, `report.json`) and the WebView's data (Spotify's cookies and storage) are transferred. The launcher excludes everything in both rule files (launcher review, "Backup"). | A parent sets up a new phone from the kid's old one with a cable: the old phone's listening history and the web player's Spotify cookies come along to a phone that may not be managed | Add `data_extraction_rules.xml` with `<cloud-backup>` and `<device-transfer>` excluding every domain, as the launcher does |
| P-L4 | **Test data that looks real (standing rule).** `music/app/src/test/java/me/vibb/music/bluetooth/BluetoothAudioTest.kt:223`: `RINCON_B8E9373C2A0201400` has a Sonos OUI (B8:E9:37) and no fake pattern, unlike every other fixture (`000E58A0B1C2…`, `0000…014NN`). It doesn't match the vendor prefix of the speakers in the user's local captures, so it is probably made up. `server/testdata/spotify/me_parent.json:2` (`"display_name": "Forelder"`, also `playlist.json:6`) and `me_kid.json:2` (`"Ella"`): **if** these are the family's real first names, they are in a public repo next to "fakeparent"/"fakekid". Everything else checked is fake: no `eyJ…`, `BQ…`/`AQ…`; tokens `FAKE-*`; e-mails `@example.invalid`; Spotify ids `…Fake…`. The Storytel ids 160661/160662 and the series 26175 are public catalogue ids. The AES key and IV are Storytel's public client constant. | | `RINCON_000E58A0B1C401400` in the test; `"Forelder"`/`"Barn"` (or obviously fake names) in the Spotify fixtures, with the fake's `--selftest` updated in the same commit |

## Info

Security:

1. **The bridge (clean).** The music app is the bridge's client only: it binds the launcher's
   `MusicBridgeService` (signature permission, launcher manifest `:96-98`, `:512-515`) only for a package in
   `LAUNCHER_PACKAGES` that `sameSigner` confirms (`bridge/Bridge.kt:41-54`, `bridge/BridgeConfig.kt:147-148`). The
   callbacks it hands over (`IStorytelCallback`, `ISpotifyTokenCallback`) reach the launcher alone. The session's
   `CALL_HOLD`/`CALL_RELEASE` are granted only to a same-signer launcher (`PlaybackService.kt:529-533`), and the
   other custom commands only to our own process (`:534-536`). The launcher's side (name-only trust, release trusting
   `.debug`) is launcher S-L8.
2. **Other exported components.**
   - `BridgeChangedReceiver` honours only `sentFromPackage` in `LAUNCHER_PACKAGES` (name only, no signer check;
     `MusicApp.kt:61-66`). It only nudges a sync.
   - `MainActivity`'s `EXTRA_RESUME` can come from any app that may start activities (`ui/MainActivity.kt:70`). It
     resumes only the kid's own stored queue, is gated by `playAllowed`, and is honoured once.
   - `KidMediaButtonReceiver` is Media3's.
   - Nothing to change unless S-L2 is done, in which case also check `sameSigner` for the nudge.
3. **Storytel's minted URL may be `http`.** `StorytelApi.mintedUrl` (`storytel/StorytelApi.kt:268-273`) accepts any
   http(s) Location, and `mintedDropReason` checks only addresses (`sonos/PlayBody.kt:176-179`). Storytel's CDN is
   https, so this only matters if that changes. Require https for the minted URL in a release build. A Sonos play
   carries it over cleartext UPnP on the LAN regardless (inherent).
4. **The address guard** (`logic/Addresses.kt:29-59`) has the same gaps as the server's (server I3: 6to4, Teredo,
   IPv4-compatible, `64:ff9b:1::/48`). Fix both sides together.
5. **NRK's service URI** (`sonos/NrkService.kt:58-59`) inlines `programId` (the server's item key, checked only for
   non-empty, `PlayBody.kt:164`) and the speaker's `SerialNum` unescaped. A `?`/`&` in either can add query
   parameters to the speaker's URI; XML is still escaped. Validate both as `[A-Za-z0-9_-]+` / digits.
6. **The event socket** checks its 64 K message cap (`sonos/SonosEvents.kt:132`) only after OkHttp has buffered the
   whole message. Only a speaker that passed the pinned root and the UDN check can send one, so this is acceptable.
7. **Titles with XML-invalid characters** (C0 controls, U+FFFE/FFFF, lone surrogates) pass `xmlEscape`
   (`sonos/SonosUris.kt:131`), and the speaker then faults the play. This is robustness, not injection: every use is
   element text, and the attributes are constants. Strip them in `didl()`. vibb would differ, so this is the user's
   call under the DIDL rule.
8. **Debug builds only:**
   - The Storytel debug base accepts loopback/`10.0.2.2` with no fake-login guard, unlike Spotify's `FAKE-ACCESS-`
     check (`storytel/StorytelEndpoints.kt:66-69` vs `logic/Spotify.kt:74-75`). A debug build with a leftover
     `config.json` would send the real login to a local port.
   - A debuggable app's WebView is probably inspectable over adb.
   - Both are covered by the standing rule "no debug APK on the kid's phone". Seeding `storytel.json` already does it
     for tests, so a "fake login only" check would be cheap.
9. **`checkReleaseHasNoDebugFeed`** is real (`music/app/build.gradle.kts:124-212`), but it hangs on `assemble*` only.
   A `bundleRelease` wouldn't run it. Releases are APKs today.

Privacy:

1. Third parties see the phone directly:
   - Podcast hosts and CDNs see the child's IP and `User-Agent: VibbMusic/<version>` (`media/MediaCache.kt:135`). It
     is neutral (no "kid", unlike server P5) but distinctive.
   - Spotify sees the WebView's default UA (device model, Android version) and the SDK's own telemetry.
   - Storytel sees its login and asset calls.
   - Safe Browsing and WebView metrics are off (manifest `:43-48`).
2. **The Storytel login at rest.** It is sealed under a Keystore AES-GCM key that isn't bound to any user secret, so
   it is protected by the sandbox only. The clear generation in front isn't in the AAD (`bridge/StorytelLogin.kt:22`,
   `:68-80`). The root issue is server M3 (the family's password on every phone). If M3 moves to a server-side mint,
   this file goes away.
3. **`music_state` is minimal** and matches `server/testdata/music_state.json`: versions, byte counts, error codes,
   numeric entry ids, item keys, the account words, and `sonos_rooms` (P-L2). It never has positions, titles or
   now-playing (`logic/Report.kt:88-139`). The now-playing Bundle goes to the launcher only.

## What looked clean

- **Sonos event socket (53-c):**
  - `rootOnlyTrust` is a `KeyStore` holding only the pinned root (`SonosSocket.kt:170-177`).
  - The UDN compare is exact equality on SAN type 6 after the `urn:sonos:udn:` prefix (`:184-187`, `:216-220`), and
    an unreadable SAN is refused.
  - The host verifier accepts exactly the dialled ip (`:328`). `socketGate`/`speakerLiteral` require wss, port 1443,
    the exact path and a LAN literal (`:77-96`).
  - Parks are per uid and ip. `onFailure` logs the class only.
  - A refusal logs its reason class only. CN/issuer/year are logged in debug only; the SAN never.
- **Spotify on Sonos (54-a1):**
  - `PlayBodies.share` takes only a target matching `^spotify:(…):([A-Za-z0-9]{1,64})$` (`spotify/SpotifyRef.kt:27`).
  - `ShareLink.of` rebuilds `spotify:<enum kind>:<\w+>` (`sonos/ShareLink.kt:32-60`), so the `EnqueuedURI` is a
    constant prefix plus that, and the metadata's `item id` is the enum key plus that. `desc` and `sn` are constants.
  - No input reaches the URI or the DIDL in a way that could make the speaker load something other than Spotify.
  - The share kinds are logged by type only (`ShareLink.toString`, `Fields.shown`).
  - Spotify is still dropped for a room (`PlayBody.kt:160`).
- **DIDL and SOAP escaping:** each value is escaped once in `didl()` (`sonos/Didl.kt:42-53`; attributes are
  constants, `mimeFor` or `hms`) and once more as an envelope argument (`Soap.kt:151-156`). SOAP URLs are built from IP
  literals with `HttpUrl.Builder`, with no redirects and no proxy (`Soap.kt:126-148`).
- **Storytel:**
  - `Authorization` goes only to Storytel's API, and the asset call never follows the 302.
  - The CDN client follows no redirect (so no `exo_redir`).
  - Every secret holder redacts its `toString`: `Login`, `StorytelRequest`/`Response`, `Mint.Url`, the JWT holder.
  - `storytel/` has no `Log.`.
  - The debug base is honoured only for loopback/`10.0.2.2`.
- **Spotify:**
  - The token is kept in memory only (`spotify/TokenCache.kt`). `Authorization` is set only in `SpotifyApi.kt`, to
    `api.spotify.com` (or, in debug, the fake with a `FAKE-ACCESS-` token only). Redirects are off
    (`SpotifyHttp.kt:61-62`).
  - The page argument is a JSON-quoted literal, with U+2028/2029 escaped (`WebPolicy.kt:133-146`).
- **WebView settings** (`SpotifyWebHost.kt:126-138`, `:316-327`):
  - File and content access are off, also for service workers.
  - Geolocation is off, and there are no multiple windows and no navigation.
  - SSL errors use the default (cancel). The protected-media grant needs https and an exact host.
  - The console is swallowed.
  - Mixed content is blocked by the minSdk 34 defaults. `WebPolicy.decide` refuses http, ports other than 443 and
    userinfo.
- **The public-only client:** private addresses are refused in DNS and again on the connected address, on every
    hop, with `NO_PROXY` (`MediaCache.kt:89-104`). Own files never use the network, and an untagged item fails closed
    to public-only.
- **Secrets at rest:** `noBackupFilesDir` for `storytel.bin`, `storytel_refused.json`, `sonos.json`, `renderer.json`,
  the Spotify files and the media cache. `allowBackup="false"` (but see P-L3).
- **Build:**
  - Release is minified with R8, not debuggable, and signed with the launcher's key (`build.gradle.kts:56-69`).
  - The wrapper pins its SHA-256, and `verification-metadata.xml` verifies every artifact (sources/javadoc trusted
    only).
  - `checkReleaseHasNoGoogleServices` and `checkReleaseHasNoDebugFeed` exist.
  - The debug feed receiver needs `DUMP`, its provider isn't exported, and `config.json` lives in the app's own
    external dir.
- **Fixtures:** the tokens, ids, e-mails and captures are fake (P-L4 has the two exceptions). The test certificates
  hold no private key.

## Privacy inventory

| Data | Where it comes from | Kept where | Leaves the phone to | Needed? |
|---|---|---|---|---|
| Library, listings, covers, own files | launcher (bridge) | `filesDir/music`, covers, `noBackupFilesDir` own copies | – | Yes (D2D: P-L3) |
| Positions, bookmarks, favourites, flips, played times | the player | Room (`databases/`) | never (positions are not reported) | Yes (D2D: P-L3) |
| What plays now (title, art) | the player | memory; the media session | the launcher (`setNowPlaying`); **any app** through the session (S-L2) | Yes; S-L2 |
| Audio and HLS cache | sources | `noBackupFilesDir/media` | – | Yes |
| Storytel login | launcher (server M3) | `noBackupFilesDir/storytel.bin`, sealed | Storytel (login) | While M3 stands |
| Storytel JWT, minted URLs | Storytel | memory, used once | Storytel CDN; the Sonos speaker (cleartext UPnP); a planted speaker (S-M1) | Yes |
| Spotify token | launcher | memory | the page (main frame), `api.spotify.com` | Yes |
| Spotify page account, context track | the renderer | `noBackupFilesDir` | – | Yes |
| WebView cookies and storage | Spotify's SDK | app data (`app_webview`) | Spotify | Yes; cleared at `forget()`; D2D: P-L3 |
| Sonos speakers (uid = MAC, ip, name, groups) | SSDP, ZoneGroupState | `noBackupFilesDir/sonos.json`, never pruned | the report's `sonos_rooms` (uid, name; never an ip) | Home only; P-L2 |
| Bluetooth names and addresses | A2DP proxy | memory only, never logged | – | Yes |
| `music_state` | the sync | `filesDir/music/report.json` | the launcher, then the server | Yes (Info privacy 3) |
| Logs | – | logcat | bug reports, adb | P-L1 |

## Suggested order

1. **S-M1:** the LAN check on SSDP and SOAP (source address = LOCATION, `speakerLiteral` ranges, `RINCON_` uids),
   then the network-bound, pruned cache (which also fixes P-L2). This is Android-free code in `sonos/` with pure
   tests (`SsdpTest`, `TopologyTest`, `SpeakerCacheTest`), and fake-sonos stays on 10.0.2.2's private range. Check
   that the emulator path still passes: the debug feed seeds `10.0.2.2`, which is RFC 1918.
2. **S-L1:** the 1 MiB cap, the default deadline and the DOCTYPE refusal in `Soap.execute`/`parseXml`. Small.
3. **P-L1:** gate `SonosHome.log` (or a release subset of fixed words), and no `$e`/`e.message`. Small, but every
   log call site in `sonos/` needs a look.
4. **S-L2:** narrow the session's commands for foreign controllers. Check on a phone that Bluetooth, the lock screen
   and the launcher's media card still work.
5. **P-L3, P-L4, S-L5:** rule files, fixture names, and the scrubber's fail-closed pass. Each is small.
6. **S-L3:** CSP from the host list, together with 26c's DNS capture that makes the list final.
7. **S-L4:** https-first for public http enclosures. This needs the user's decision: it is a behaviour change from
   vibb.

# Music notes: the binding rules in full

Moved from music/CLAUDE.md's "Rules that are easy to break" on 2026-11-27: the four long rules (the cache-through stream, pruning and the played extras, cleartext and the Sonos socket's pin, the bridge) in full; the core keeps each as a short rule and the short rules verbatim.

## Rules that are easy to break (continued)

- **A stream is written to the cache as it plays** (design 30 §1.4-§1.7; ours, not vibb's "streams aren't cached"):
  the `ThroughSink` decides at every open (`throughWrites`: NRK/RSS, the entry's live depth above 0 - "Ingen" writes
  nothing - and 1 GiB free), makes the item's not-done row before its first byte (`insertThrough`), and a cache
  error never fails playback (`FLAG_IGNORE_CACHE_ON_ERROR`). "On the phone" is still a done row: a stream played to
  its end is settled into one by a non-blocking span check (`CacheThrough.settle` when it leaves the held set, on
  IO, never the service's `writer` - a played one left incomplete asks for the job; `catchUp` at every sync, before
  `runLock`, enqueues nothing); the job completes a partial with Range. The player's **held set**
  (`PlayerHub.heldLocal`: the current item, the next, and every item starting within `HELD_AHEAD_MS` of the position
  - with short items ExoPlayer opens more than the next - plus a new queue's claims, which end at its load, its
  give-up or an exception) is never pruned, evicted, swept or settled - every removal reads it again right before it removes - and
  its progressive items that aren't done are never fetched (the downloader would wait on the player's lock).
  `sameSource` ignores an http url's query; another source's partial, or bytes with no row, play uncached
  (`staleThrough`); a resume against another total length is the `LengthGuard`'s synthetic `http_416` (its cause
  `SourceChangedException`, never retried by the player's `SourceChangedPolicy`; the job refetches from 0, the player
  drops the row and loads the queue again with the item uncached and without a cache key - never `replaceItem`,
  which Media3 turns into an in-place update of the same cache-backed source). The HLS rendition is pinned to the download's
  (`setMaxAudioBitrate(HLS_MAX_BITRATE)` + highest). Sonos, Storytel and Spotify never write through. **A Storytel book
  is sealed** (design 117, `docs/notes/music/storytel.md` "Sealed books"): its own `sealed:` cache key, written only
  through `SealDataSink` and read only through `SealDataSource` (the `storytel*` factories); every plain factory passes
  an explicit sink (`ThroughSink`, `NoWriteSink`, `PlainCacheSink`) that refuses a sealed key or a Storytel source id -
  never Media3's default plain `CacheDataSink`.
- Prune only on a parsed listing, and only what the library no longer names: an entry or file the app can't read is
  hidden with everything kept (qa-21-step2-code H1). Positions and bookmarks are never deleted except by vibb's
  `clear_state` at a queue's natural end (partial positions only). Above depth 0 the bookmarked item is never
  deleted; depth 0 ("Ingen") keeps nothing. A kept withdrawn copy plays. Design 30 §1.8: a row outside the keep set
  that the phone played (`playedAt`, Room 4; set by `markPlayed` only, kept by `upsertDownload`'s `keepPlayedAt`),
  still listed, above depth 0 and not Storytel is a **played extra**: kept, never fetched, evicted least recently
  played first (`extrasToEvict`) while the audio cache is over the parent's storage limit or free space under the
  floor (`extrasFloor`: 1 GiB, 1.5 GB while a Storytel book is wanted and the login is ok) - by the sync and by the
  job before each round. The keep set and the held set are never evicted. Cache bytes no row owns are swept at
  every sync (`orphanResources`, keys read before rows).
- **Cleartext http is allowed** (`res/xml/network_security_config.xml`, qa-30-s3b-code H2; `SourceScanTest`): Sonos
  SOAP to a speaker's LAN address, http enclosures, `lan` feeds and the emulator's fakes all need it, and a config
  can name hosts but not ranges. What may be fetched is the app's own rule (the public-only client's address checks,
  Sonos URLs from IP literals). A public entry's `http://` audio (port 80) is asked over `https://` first
  (`media/HttpsFirst`, security review S-L4): any failure, an HTML page included (`htmlType`, the player's own
  check), falls back to the listed URL and that host stays http for
  24 h; the rows, keys and `sameSource` keep the listed URL; never `lan`, Sonos or Storytel. Trust is the system's CAs only - **with one exception** (design 53 §2.2, round 53-c): the
  Sonos event socket's own client (`speakerSocketClient`, `SonosSocket.kt`) trusts **the pinned Sonos Device
  Authentication Root CA alone** (`SONOS_ROOT_PEM`, its SHA-256 checked by `SonosTrustTest`; a `KeyStore` holding only
  it - never the system's CAs, never trust-all; intermediates the speaker sends are path-built under it) **and the
  leaf's SAN `urn:sonos:udn:` must equal the uid we dial** (`speakerCertRefusal`, in the handshake, before any message),
  only for `wss` to the cached ip literal of that uid, in a speaker's range (`speakerLiteral`: RFC 1918, link-local,
  IPv6 ULA/link-local - never loopback or the tailnet), port 1443, `/websocket/api` (`socketGate`, checked before any
  connect), its host name verifier accepting exactly that ip. A refused certificate counts as a refused connect (five
  in a row at one ip park the uid; polling stays) but `dates` (the phone's clock, likely: backed off, never parked), and
  logs its reason class only (`chain`, `dates`, `no udn`, `udn mismatch`) - never the certificate's identifiers (its
  SAN holds the household, a user, the serial); a debug build logs the leaf's subject CN, issuer CN and last year once
  per uid and verdict. `SpeakerTrust` stays a plain `X509TrustManager` calling the 2-argument check (no host reaches the
  platform's trust manager: no per-host policy, no Certificate Transparency - qa-53c-code 6). No other client gets
  that trust, and a URL from a socket message (a `MOVED`'s, a player's `websocketUrl`) is never dialled
  (`SourceScanTest`'s `d53`).
- The bridge (design 21 step 3, `bridge/`): the launcher is the only way to the server. `BridgeSync.pull` runs first
  in every sync (library, changed listings, covers, own files copied + checked + released; Storytel's session is asked
  only when a mint needs it, design 58) and `push` sends the report (with the own files held) at its end; `LiveBridge` sends
  `setNowPlaying`/`setForeground` and retries a bind. Bind only the restrictions' launcher, and only when it is
  signed like this app; honour `BRIDGE_CHANGED` only from a launcher. An in-place update syncs at once (`PackageReplacedReceiver` on `MY_PACKAGE_REPLACED`; the launcher nudges too), so Device › Music never keeps the old app's report. The launcher asks an idle app for a report with the same nudge (its report 12 h old; "unbound" only when the ask goes unanswered 30 min, `docs/notes/launcher/music-bridge.md`), and app standby may defer the nudge's sync for hours, so `BridgeChangedReceiver` also writes and pushes the report at once inside the broadcast (`goAsync`, 9 s of a background broadcast's 60; `Sync.answerNudge`, one at a time): always for the launcher's ask (`EXTRA_REPORT_ASK`, `bridge_contract.json` `extra_report_ask`), for any other nudge only when the report file is an hour old or missing (`reportOnNudge`, `ReportTest`). The receiver honours a nudge only from a launcher signed like this app (`launcherAllowed`). The two mobile-data switches
  (`stream_mobile_data`, `download_mobile_data`; `true` is on, anything else - absent included - off, `mobileSwitch`),
  the storage limit (`storage_limit_gb`, 0 = none, absent or junk =
  2 GB - `storageLimit`; the played extras' eviction counts against it) and `volume_cap_pct` (the parent's cap on our
  own Sonos volume sets, design 26a §5.3) are read live from the managed config. The report always carries
  `audio_bytes` (the audio cache: what the limit counts) and `extra_bytes` (the played extras now, from the
  report's own plan, so the job's report says it too). The AIDL is
  the launcher's own, `../contracts/shipped/bridge-aidl/` (design 133) - append, never reorder; shared names are in
  `../contracts/music/bridge_contract.json`.

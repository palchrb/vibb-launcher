# 19 - Remove FCM: SSE is the only push transport

User decision (2026-10-07): FCM goes; the SSE command stream is the one way the server nudges a phone. One APK must
work the same with anyone's server (the Firebase config baked into the APK lets only its owner's server send); less
code and setup; no nudge metadata to Google; fewer failure modes. Element X's own FCM is unaffected: Play services stays
installed and unrestricted. Replaces the FCM half of design 07 (the Play half stays). [inferred]/[device] mark guesses.

## Touchpoints

- **Launcher, removed**: `push/FcmSupport.kt`, `KidFcmService.kt`, `PushTransport.kt` (`decidePushTransport`,
  `tokenAction`, `SseReason`, FID/hash helpers, `fcm_push_v1`); `PushState` keeps only `sseConnected`; the manifest's
  Firebase service/provider/`ComponentDiscoveryService`/meta-data entries; Gradle `firebase-messaging`, `HANDY_FCM_*`,
  `requireFcm`, `FCM_*` BuildConfig, `fcmEnabled`; `MdmSyncWorker` token upkeep and status `push`; DTOs `PushPolicy`,
  `PushReport`; `CommandListenerService` Firebase init and `reevaluateTransport` (stream always on);
  `backstopDelayMs`'s transport argument.
- **Server, removed**: `src/fcm.rs`, `src/push.rs` (dispatcher, `Health`, acks, test nudges), `AppState.fcm`, policy and
  status `push`, the card's FCM lines, `FCM_SERVICE_ACCOUNT_FILE` (if still set: one startup warning), `.env.example`
  lines, direct deps `base64`/`ring` and dev-dep `tokio/test-util` (only FCM used them), the fixture PEM. **CI**:
  `launcher.yml` drops the Firebase-variables check, `HANDY_FCM_*` env and `-PrequireFcm`; delete the 4 variables.
- **Kept**: the anchor FGS, `SyncRunner`, the backstop (30/15 min), `command_notify` and `commands_stream`; the Play
  policy and `play_policy_v1` (Play services + GSF never restricted, Store suspended not hidden, install mode); the
  FCM-host DNS exemption on both sides (`FcmHosts`, `is_fcm_protected`) - comments there and in `PlayPolicy`,
  `EnforcementPlan`, `PolicyGate`, `UpdateFencePlan` now say "other apps' FCM (Element X)". Card "Push and Play" ->
  "Play and kiosk" (kiosk block switch stays).
- **Docs**: both `CLAUDE.md`s; `DEPLOY.md` ("FCM (optional)" -> "Removing FCM" + a proxy-timeout line); `PLAN.md`
  (Battery decision "reversed 2026-10-07, design 19"; rest mode); `README.md`; `emulator.md` §6b/§8 and
  `docs/setup/google-account.md` (card name); a "Superseded by 19" note atop design 07. Other docs stay as history.

## Commit order (each green alone)

1. **Server + contract**: server side, migration `0048`, tests (FCM tests go; Play/DNS/keepalive tests move to
   `tests/play.rs`; the snapshot drops `push` from both lists; an old launcher's status `push` -> 204, nothing stored).
   Same commit (root rule): `PolicyResponseCompatTest` decodes a 0.19 response and cached blob that carry `push`.
2. **Server: keepalive default 240** (below), alone so it reverts alone.
3. **Launcher + CI**, plus a one-shot cleanup at the first unlocked start: prefs `push_state` (hold the installation ID)
   and Firebase's leftover files/prefs. `PushTransportTest` -> `SyncScheduleTest` (backstop, coalescer, reopen kept);
   `PushManifestTest` -> "no Firebase in the manifest" plus a Gradle guard against `com.google.firebase`/
   `com.google.android.gms`/`com.google.android.datatransport` in `releaseRuntimeClasspath`.
4. **Launcher: SSE hardening** (holes 1-2). 5. **Docs**. Release the server (1-2), update the Pi, then the launcher
   (3-4). If FCM was ever configured: revoke the service-account key, delete the key file and the Firebase project.

## Compatibility and data

- **Old launcher, new server**: no policy `push` -> `PushPolicy? = null` -> SSE (`server_off`), today's keyless-server
  path; `tokenAction` is NONE once it holds an ID. Its status `push` and `fcm_push_v1` are ignored (no
  `deny_unknown_fields`; nothing read the capability). **No vestigial `push`**: `{fcm_enabled: false}` and an absent key
  behave identically on every shipped launcher, so a stub would only keep FCM in the contract.
- **New launcher, old server**: `ignoreUnknownKeys` drops the policy `push` (fresh and cached). A 0.19 server with a key
  test-nudges the stale ID hourly until FCM says UNREGISTERED [device] - harmless; avoided by server-first.
- **`0048_remove_fcm.sql`**: `DROP TABLE device_push` (IDs are credentials to message a phone);
  `UPDATE device_status SET push_state_json = NULL` (it held the ID; column kept unused - DROP COLUMN would rewrite the
  append-only table). Play columns and old security events stay. Rollback = restore the `update.sh` backup, as always.

## SSE keepalive and reconnects

- **NAT**: the stream is TCP inside tsnet's WireGuard; carrier NAT sees only tsnet's outer flows (direct UDP, or DERP
  kept alive about every 60 s) [inferred], so the inner keepalive holds no mapping. `tailscale serve` streams
  unbuffered; another self-hoster's proxy needs a read timeout above the keepalive (nginx's 60 s default cuts streams).
- **Recommend 240 s default** (cap stays 240: shipped launchers read with 300 s). Our radio wakes halve (30/h -> 15/h);
  a dead stream is noticed 300 s after its last byte either way; 60 s margin suffices on a live path; `.env` reverts it.
- **Hole 1**: the read timeout runs on Okio's `nanoTime` watchdog, which stops in deep sleep [inferred], and the
  backstop reconnects only while `sseConnected` is false: a silently dead stream (Pi restart) can count as up for
  hours. okhttp-sse hides keepalive comments (reader callback: only `onEvent`/`onRetryChange`, verified in 4.12.0
  bytecode), so a network interceptor stamps `elapsedRealtime` per body read; the backstop reconnects after 300 s quiet.
- **Hole 2**: `syncOnSseReopen` measures from detection, not the last byte: after a read timeout (up to 300 s deaf) the
  5 s reconnect skips the sync and a ring waits up to 15 min. Fix: a timeout/staleness drop always syncs on reopen (at
  most once per 300 s); quick reconnects keep the 150 s rule, its comment untied from the keepalive.

## Battery measurement (Jelly Star)

Release builds, SIM in, Element X as in daily use. Design 07 §3 protocol: "Block USB debugging" off, 100 %,
`dumpsys batterystats --reset`, unplug, 8 h screen off, then `--checkin`, `adb bugreport`, `dumpsys deviceidle`,
`logcat | grep CommandListener`. A = `SSE_KEEPALIVE_SECS=120`, B = 240 (Pi `.env` only), twice each on mobile data,
once each on Wi-Fi. Read %/h, radio active time and count, wakeup reasons, our alarms (<= 4/h), reconnects (~0). Keep
240 if B drains no more and reconnects don't rise. If the radio count barely moves, tsnet dominates: tune that next.

## Open questions

1. A live "instant changes: connected now / not" line on the card (in-memory count of open streams, no DB)?
2. Exact catch-up after reconnects (event ids + `Last-Event-ID`, compatible both ways) now, or only if the device run
   shows missed nudges? Recommended: later.

## QA review (2026-10-07)

**Checked against the code.** Compat holds both ways. An old launcher gets `server == null` -> SSE (`server_off`) from its first fresh policy (`onSyncFinished` -> `reevaluateTransport`, and the cache is re-encoded without `push`), and `tokenAction` is NONE for an FID. 0.19 always broadcasts nudges to SSE too (`push.rs` header), so a new launcher on 0.19 misses nothing: its status has no `push`, so `record_report` never clears the FID and the test nudges go on as written. Nothing reads `fcm_push_v1`; `base64`/`ring`/`test-util` are used only by FCM code. `WAKE_LOCK` is declared by us, so removing Firebase only drops `c2dm.RECEIVE` from the merged manifest. An FGS keeps network and wake locks in Doze, and the DO is exempt from App Standby [inferred, AOSP]. Server first is the right order, and the keepalive alone in commit 2 is right.

1. **Medium - hole 3: a clean drop in deep sleep.** The reconnect backoff runs on a `Handler`, which counts uptime only. After a FIN (every server restart, `update.sh` for this release included, or a Pi reboot), a sleeping phone retries only when the backstop fires, which the up->down edge moved to 15 min. SSE phones have this today; after this change it hits every phone on every update. Change: on the up->down edge, take a timed partial wake lock (~30 s, released at `onOpen`, at most once per 10 min) so the 5 s and 10 s retries run.
2. **Medium - hole 1 details.** (a) The interceptor's wrapped body must keep `contentType()`: okhttp-sse 4.12 fails any body that isn't `text/event-stream` ("Invalid content-type", checked in the jar), which would turn every connect into a retry loop. Unit-test the wrapper. (b) A staleness reconnect must do `streamDown`'s bookkeeping first (`sseConnected = false`, down time); `connect()` alone leaves `sseConnected` true, so `onOpen` sees `wasDown == false`. (c) Call the stream stale after about 2 keepalives (>= 480 s), not 300: otherwise a keepalive that woke the phone but wasn't read before it slept again forces a needless reconnect. Detection still happens at the same backstop. (d) Also run the check on screen-on/unlock (the process-wide screen receiver): it costs nothing, and that is when a lift or lock matters.
3. **Low - hole 2 with one clock.** Replace the drop classification (timeout vs staleness, plus "once per 300 s") with `syncOnSseReopen(downForMs, sinceLastByteMs)`: sync when the stream was down >= 150 s, or when the wall time since the old stream's last byte is >= `SSE_READ_TIMEOUT_MS`. A read timeout always meets it, because Okio's watchdog and `SO_TIMEOUT` both count awake time only. A quick reconnect of a live stream doesn't (<= 240 s + backoff). The rule is pure, so it goes into `SyncScheduleTest`. On a staleness reconnect, the coalescer merges it with the backstop's sync.
4. **Low - the switch gap for phones on FCM.** A phone on FCM gets no nudges from the server update until its next successful sync (<= 30 min backstop), then switches as described. Only a phone whose fresh policies keep being rejected stays on the cached `push`, and that phone is already broken. Change: say this in the release steps ("Sync now" or a reboot shortens it).
5. **Low - the reasoning for 0048.** The migration is safe as written: `device_push` is an FK child (`ON DELETE CASCADE`), its unique index is dropped with it, and nothing else reads it. Status history is pruned to 30 days, so the `UPDATE` is small. The 0.19 binary panics on an unknown 0048 (sqlx `VersionMissing`), so rollback means restoring the backup. But the `UPDATE` rewrites the same rows `DROP COLUMN` would, and neither one erases freed pages or the `update.sh` and drive backups. Change: keep the `UPDATE` as cleanup, drop the "rewrite" argument, and say that revoking the key is what makes stored IDs useless.
6. **Low - tests.** The plan is right: the snapshot drops `push` from both lists, and `PolicyResponseCompatTest` decodes a 0.19 response and a cached blob. Add to commit 3: a check that the status JSON carries neither `push` nor `fcm_push_v1`.
7. **Low - 240 s is safe.** The 60 s margin only matters while the phone is awake, because both timeouts stop in sleep. WireGuard keys expire after 180 s, so each keepalive waits one handshake round trip. DERP's 60 s server keepalive holds the outer NAT mappings [inferred]. Expect a small A/B difference, because DERP and control keepalives (~60 s) keep waking the radio: keep 240 unless B is clearly worse. `DEPLOY.md`: any proxy needs a read timeout >= 300 s.
8. **Medium - measure latency, not only drain.** On the Jelly Star, also time a ring: (a) after >= 1 h with the screen off and unplugged. An SSE nudge gets its wake lock only after tsnet, SOCKS and OkHttp have run on the packet's wakeup; FCM had Play services' wake lock. (b) Right after a server restart (#1). (c) After switching Wi-Fi <-> mobile while asleep. tsnet gets no network callbacks (netlink is blocked, `tsembed.go`); if this is slow, feed the default-network callback into tsnet as the Tailscale app does. (d) After cutting the Pi's network for 10 min (silent death: expect <= one backstop). Emulator: `kill -STOP` the server for > 300 s, then `-CONT`; expect one reconnect and one `sse_open` sync (`emulator.md` §5b).
9. **Low - DNS.** Keep `FcmHosts`/`is_fcm_protected` as written (both checks verified). Nothing new interacts with the filter: OkHttp passes the unresolved name to SOCKS, so the filter sees only tsnet's control/DERP lookups. But tsnet is now the only nudge path, so consider never blocking the configured control host, like the FCM hosts (follow-up).
10. **Low - docs and release.** The touchpoints miss `templates/device_detail.html` (heading l.60, FCM subtitle l.74). Add the `Cargo.toml` 0.20.0 bump before the `server-v` tag. Delete the repository variables only after the first `launcher-v` tag on commit 3 or later: a tag on `0.25.0-rc.1`'s commit still runs the check and `-PrequireFcm`. PLAN's rest mode says "maybe pause tsnet", which would now stop all nudges; reword it.
11. **Q1 - yes.** Keep a per-device open-stream count and its last change time in `AppState`, updated by a drop guard on the stream: no DB and no contract change. It replaces the transport line the card loses: "Instant changes: connected / not connected - the phone still checks in every 30 min". A peer that dies silently stays "connected" until the Pi's TCP gives up (~15 min), so word it as "as far as the server can tell".
12. **Q2 - later, agreed.** Nudges carry no content, so a sync is the exact catch-up, and #3 ends every deaf period with one. Event IDs would need per-device buffering on the server and would save at most one sync per reconnect.

## Decisions after QA review

- All QA findings accepted, in the doc's commit order plus the server 0.20.0 bump.
  - SSE hardening includes:
    - a ~30 s wake lock when the stream drops (reconnect in deep sleep);
    - the stream marked down before reconnecting, and stale after ~2 missed keepalives (time since the last byte),
      also checked at screen-on;
    - one rule for a sync after any reconnect that follows a gap;
    - `contentType()` kept.
  - The keepalive default goes to 240 s.
- Q1: yes. The device page gets an in-memory "stream connected now / since" line (not stored).
- Q2: later. Exact catch-up with event ids is not built now.
- The GitHub `HANDY_FCM_*` variables are deleted by the user only after a launcher tag built from the
  launcher-removal commit or later. The workflow's check step and `-PrequireFcm` go in that commit.
- Device run on the Jelly Star (QA #8): time a ring in Doze, after a server restart and after a Wi-Fi/mobile switch,
  and measure battery at 120 s vs 240 s.

## Implementation status (2026-10-07)

Done in the doc's commit order, each commit green alone (server `cargo test`/fmt/clippy, launcher unit tests + debug
and release builds with `-PwarningsAsErrors=true`, the release without any `HANDY_FCM_*` value):

1. **Server 0.20.0 + contract**: FCM code, `push` (policy and status), `device_push` (migration `0048`) and the card's
   FCM lines are gone; an old launcher's `push` is accepted and stored nowhere; `FCM_SERVICE_ACCOUNT_FILE` = one
   startup warning. Card "Play and kiosk" with the in-memory "Instant changes: connected since ... / not connected"
   line (Q1, `src/streams.rs`, a drop guard on the SSE response). Tests in `src/tests/play.rs`; the snapshot drops
   `push`; `PolicyResponseCompatTest` decodes a 0.19 response and a cached blob with `push`.
2. **Keepalive default 240 s** (`.env` `SSE_KEEPALIVE_SECS=120` reverts).
3. **Launcher + CI**: Firebase, the transport decision and the token upkeep are gone; the stream is always on;
   `checkReleaseHasNoGoogleServices` guards `releaseRuntimeClasspath`; the one-shot cleanup deletes `push_state` and
   Firebase's files, prefs, database and jobs; `launcher.yml` has no Firebase-variables check, `HANDY_FCM_*` or
   `-PrequireFcm`.
4. **SSE hardening**: last-byte stamps (network interceptor, `contentType()` kept - tested through okhttp-sse's own
   EventSource), `checkStream` on every sync request and at screen-on/unlock (down: reconnect; silent >= 480 s: mark
   down, reconnect), the 30 s drop wake lock (at most every 10 min, released at the open), and
   `syncOnSseReopen(downForMs, sinceLastByteMs)`.
5. **Docs**: both `CLAUDE.md`s, `DEPLOY.md` ("Removing FCM", proxy read timeout >= 300 s), `PLAN.md`, `README.md`,
   `emulator.md` (§5b `kill -STOP` check, §6 item 8, §6b), `google-account.md`, the note atop design 07.

Left for the user: release the server, update the Pi, then the launcher; remove `FCM_SERVICE_ACCOUNT_FILE` from
`.env`, delete the key file, revoke the key and delete the Firebase project; delete the four `HANDY_FCM_*` repository
variables after the first `launcher-v*` tag on commit 3 or later. Not device-tested: the Jelly Star run above (ring
latency in Doze, after a server restart, after a Wi-Fi/mobile switch; battery at 120 s vs 240 s) and the emulator's
`kill -STOP` check.

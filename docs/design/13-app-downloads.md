# 13 - Catalog downloads on the phone: Wi-Fi only, resumable

User request (2026-10-06): a per-device switch "App updates only on Wi-Fi", default on. Element X's APK is 326 MB.

## Problem (checked in the code and AOSP main)

- `checkForTrackedAppUpdates` downloads inside `performMdmSync`, under the 10 min `SYNC_TIMEOUT_MS` and
  `syncMutex`: 326 MB needs ~4.4 Mbit/s sustained. Meanwhile a ring/lock nudge waits (the coalescer runs again only
  after this run), "Sync now" hangs, and a timeout skips `commitPendingSelfUpdateIfDue`.
- Every attempt starts at zero (new `nanoTime` file, no Range, the cancel clears the marker). The server has no Range
  and serves any app id to any device token. Cancelled/failed `cacheDir/tracked_app_*.apk` files are never deleted.
- **The VPN makes the whole phone metered.** `KidVpnService` never calls `setMetered`; `Vpn.agentConnect` drops
  `NOT_METERED` for a Q+ target with `isMetered` (default true). `ConnectivityService.applyUnderlyingCapabilities`:
  metered = agent metered OR any underlying metered, `null` underlying = the system default network. So we, Play
  and every UNMETERED job see "metered" on home Wi-Fi - and with `setUnderlyingNetworks` unset a VPN can't claim
  unmetered over cellular.
- **tsnet**: OkHttp -> local SOCKS5 -> tsnet -> WireGuard/DERP over the physical default network (our VPN routes only
  192.0.2.2/32). On Wi-Fi -> cellular magicsock moves and the tunnelled TCP stream survives: the download carries on
  over cellular. Binding to the Wi-Fi `Network` can't help (only the localhost hop is our socket).

## Design

1. **Real meteredness.** `KidVpnService`'s builder gets `.setMetered(false)` (inherit from the default network),
   never `setUnderlyingNetworks`; source-scan test like `BackupServiceInvariantsTest`. The gate reads
   `getNetworkCapabilities(activeNetwork)`: the VPN's caps when up (they carry the underlying transport too), else
   the physical network. A still-metered old VPN fails safe (downloads wait).
2. **Policy.** `device_policy.app_updates_wifi_only` (default 1) -> top-level `app_updates_wifi_only`, always sent;
   missing (older server) = off, as today. Switch in the device page's Apps card (auto-save, anchor redirect, no
   scroll jump). Override/pause don't lift it: it guards data, not the kid.
3. **Gate** (pure `downloadGate`, `AppDownloadPlanTest`): INTERNET; Wi-Fi-only adds `NOT_METERED`; roaming
   (`NOT_ROAMING` missing) always waits; space = remaining + total (session copy) + 100 MB
   (`StorageManager.getAllocatableBytes`). Result GO / WAIT_NETWORK / WAIT_WIFI / WAIT_ROAMING / WAIT_SPACE.
4. **Downloads leave the sync.** The sync fetches the list and enqueues (same tag/backoff/Play rules), before the
   status report. `AppDownloads` runs one download at a time on its own coroutine in the anchor FGS (FGS procstate
   keeps network in Doze), with its own `kidslauncher:download` partial wake lock (5 min, renewed on progress,
   released when idle/waiting) and no overall timeout (OkHttp's 10 s read timeout makes a stall a pause). Triggers:
   each sync, process start, a default-network callback while something waits. Losing `NOT_METERED` or the network
   calls `Call.cancel()` (a blocking read ignores coroutine cancellation: the endpoint becomes `Call<ResponseBody>`).
   The runner installs (`installSilently`); `attemptStartedAtMs` now covers only commit -> receiver.
   `TrackedAppUpdateState`'s mutators become `@Synchronized` (two threads).
5. **Resume.** Server: scope check (404 unless launcher or in this device's `device_tracked_apps`), then tower-http
   0.7 `ServeFile` (in the tree; its source: single `Range` -> 206 + `Content-Range`, `ETag` = mtime+size, strong
   `If-Match` -> 412, no `If-Range`). Launcher: `Range: bytes=<have>-` + `If-Match: <first ETag>`; 206 appends, 200
   (old server) truncates, 412 deletes and restarts, 416 = done if have == total else delete. SHA-256 over the whole
   file at the end; PackageInstaller's signature check catches a bad splice, and a failed install deletes the file.
6. **Files.** One stable partial per (app id, release tag - the server's tag is `tag@asset_id`):
   `noBackupFilesDir/app_downloads/<appId>-<16 hex SHA-256(tag)>.part`, record in CE prefs `app_downloads`
   (`commit()`: id, tag, ETag, total, first seen). Not `cacheDir`: the OS clears it under pressure, exactly when a
   326 MB partial sits there. A new tag deletes the old partial. Sweep at each sync and process start: files without
   a record, tags no longer advertised or installed, deselected apps, legacy `cacheDir/tracked_app_*.apk` > 1 h old.
   A finished self-update is renamed into `self_update/` -> `recordPending`; the commit stays the sync's last step.
7. **Self-update.** Same switch, with a grace: Wi-Fi only for **3 days** from when this phone first saw the tag, then
   any non-roaming network (date/time are locked while managed). Security fixes arrive within days; the commit waits
   for the night window anyway. Catalog apps: no grace (the device page shows how long they have waited).
8. **Status.** Status report `app_downloads`, a full snapshot (no stale rows): <= 20 `{tracked_app_id, release_tag,
   state, bytes, total, since_ms, any_network_at_ms}` + `wifi_only` + `network` (`unmetered`/`metered`/`roaming`/
   `none`); states `waiting_wifi|waiting_network|waiting_roaming|waiting_space|downloading|installing`. Server
   `device_status.app_downloads_json`. App row: "Waiting for Wi-Fi · 120 of 326 MB" (installed apps: "Update
   waiting for Wi-Fi"); launcher row: "0.32.0 waits for Wi-Fi, any network from 9 Oct". A fresh `apps/progress`
   row (< 10 min) still wins for the live %.
9. **Play** keeps its own "over Wi-Fi only" (runbook `docs/setup/google-account.md` §4). Its unmetered jobs couldn't
   run behind our metered VPN; §1 fixes that. Add the device check below to the runbook.

## Checks

- Launcher: `downloadGate` (VPN caps with/without `NOT_METERED`, roaming, grace edge, no network, space),
  `resumeAction`, partial name + sweep plan, `PolicyResponseCompatTest` (missing = off), the `setMetered` scan.
- Server (`src/tests/tracked_apps.rs`): 206 + `Content-Range`, 412, 416, 200 with `Content-Length`, unscoped id 404;
  `policy_json_keys_snapshot`; status JSON stored and shown; the switch POST.
- Emulator: `dumpsys connectivity` - the VPN has `NOT_METERED` on Wi-Fi, not on cellular; `svc wifi disable`
  mid-download (with tsnet) pauses within seconds and keeps the partial, enabling resumes with a Range request (server
  log); a ring during a download arrives at once; Play updates in the night window with the filter on.

## Open questions

1. Is a 3-day grace right for the launcher? Should catalog apps get one (e.g. 7 days), or wait for Wi-Fi forever?
2. Should roaming always block downloads, even with the switch off?
3. With the switch off, should very large APKs (say > 100 MB) still wait for Wi-Fi?

## QA review (AOSP main `applyUnderlyingCapabilities`, `Vpn.agentConnect`, PMS/NPMS idle rules; tower-http 0.7.0 source)

Verified: `setMetered(false)` never makes cellular unmetered (`metered |= underlying`, null = the default network), so
Play's and other apps' Wi-Fi-only logic only gains on Wi-Fi; ServeFile does what §5 says.
1. **High - "no network" passes the gate.** Without a default network the VPN still has INTERNET + NOT_ROAMING. GO also
   needs a physical transport (WIFI/CELLULAR/ETHERNET, merged in from the underlying). Cancel from
   `onCapabilitiesChanged`: a Wi-Fi -> cellular switch only changes the VPN's caps, there is no `onLost`.
2. **High - the file isn't bound to the tag.** Downloads now start hours after the list, and a sync may swap the file.
   A fresh request has no If-Match, so B is saved, installed and recorded as A, then fetched again (326 MB twice).
   Server: `X-Release-Tag` on 200/206 and a SHA-256 (computed in `stream_to_file`/upload) in `/apps`. Launcher: tag
   mismatch = drop + re-list; hash mismatch = delete + restart, without installing and without a backoff.
3. **High - the self-update commit kills a session copy.** The commit waits while the runner is between
   `createSession` and `commit()`; the runner opens no session after `committedInThisProcess`. A download may die.
4. **Medium - scope and sweep.** One query for list and download (`enabled`, a tag, launcher OR `device_tracked_apps`);
   old launchers only fetch listed ids. `ServeFile.oneshot(req)` keeps the headers. The sweep now runs beside the
   runner: skip the active file, no tag deletes when the list fetch failed; AppInstallReceiver also clears the record.
5. **Low - Doze.** Wake lock and network only hold in FGS procstate: start via the anchor (`SyncRunner.request`
   style), `setReferenceCounted(false)`, release in `finally`, a new `createMdmApi` per attempt (it fixes the proxy).
6. **Open questions:** 3-day launcher grace is fine; catalog apps wait for Wi-Fi; roaming always blocks; no size rule.

## Decisions after QA review

All five QA findings accepted as written. User answers (2026-10-06):
- The launcher waits for Wi-Fi up to 3 days, then any network. Catalog apps wait for Wi-Fi with no grace; the parent
  switches the per-device switch off when an app is needed now.
- Roaming always blocks every download, the launcher's included, switch on or off.
- No size rule: with the switch off, any size downloads on any (non-roaming) network.
- Play's own "Wi-Fi only" stays a runbook item (`docs/setup/google-account.md`).

# QA/security review: design 07 (battery, FCM, Play). L = launcher, S = server; most line refs verified.

## Findings

1. **blocker - §4 other paths to Play.** Persistent preferred activities only steer implicit intents, so an explicit
   `setPackage("com.android.vending")` (rate-us, ad, update SDKs) skips the blocker. Lock task blocks only tasks, and
   without NEW_TASK Play joins the allowed app's task: full Play UI (search, install, uninstall, account and help
   pages). Required: device test with explicit intents, with and without NEW_TASK. If Play opens, add a control (a
   watcher that finishes vending tasks outside install mode, `setUninstallBlocked` on allowed apps, or suspending Play
   if allowed). The blocker must declare matching filters (market://, play.google.com/store) or AOSP ignores it.
2. **should-fix - §4 install mode.** Lifting the blocker via `clearPackagePersistentPreferredActivities(admin, own)`
   also clears the HOME pin (`enforceDefaultHome`, AppEnforcer.kt:803-815). Install mode doesn't need it lifted: Play
   is pinned and the parent opens it. Never lift it; derive it in `apply()` so a crash can't leave it off.
3. **should-fix - §4 install mode scope.** Pinning gms and gsf exposes Google settings and account UI. Pin only
   vending; add gms only if a device test shows sign-in needs it. Deadline = elapsedRealtime + boot id. State
   whether install mode works during a time-rule lock (recommend: refused).
4. **should-fix - §4 runbook; the correction is right.** `dpm set-device-owner` needs zero accounts only at that
   moment. Before enrollment L is unmanaged: `hardeningManaged` is false with no allowlist, so MODIFY_ACCOUNTS and
   all are cleared (Hardening.kt:56-74), no kiosk (EnforcementPlan.kt:150). Safest: reset → set device owner → add
   account + Play settings in the normal UI → enroll (first policy sets `no_modify_accounts` and kiosk). Keep the
   toggle path for enrolled phones. Re-provisioning needs the account removed; FRP needs the parent's credentials.
5. **should-fix - §1/§5 Firebase before init.** Application's uncaught handler calls `exitProcess(1)`, which also kills
   the direct-boot call path. A message in the gap between unlock and `initUnlocked()` (or after init threw) →
   `FirebaseApp.getInstance()` ISE → process dies. `KidFcmService.onCreate` must init idempotently (CE unlocked) or no-op.
6. **should-fix - §1 manifest.** Not "exported per SDK": Firebase declares the subclass `exported="false"`. Delivery
   comes through `FirebaseInstanceIdReceiver`, guarded by `c2dm.permission.SEND`. Exported, any local app could fire
   syncs. Handle `onDeletedMessages` (>100 queued) → sync. Sync on every priority (the HIGH check only logs).
7. **should-fix - §1/§2 FGS and wakelock.** A separate `dataSync` SyncRunService means a 6 h budget, an onTimeout crash,
   and Android 15's ban on starting `dataSync` from BOOT_COMPLETED (developer.android.com/about/versions/15/
   behavior-changes-15), which "sync at process start" can hit. Run syncs inside the `specialUse` anchor instead.
   Hold a timed PARTIAL_WAKE_LOCK for each sync: neither an FGS nor `onReceive`/`onMessageReceived` keeps the CPU up,
   and L has no sync wakelock today.
8. **should-fix - §2 SSE fallback.** The reconnect loop is also `Handler.postDelayed` (CommandListenerService.kt
   :233-239), so it stalls in deep sleep. The 15-min "SSE down" alarm must also call `connect()`, and the backstop
   alarm must restart the anchor if it died (screen time counts only while it runs).
9. **should-fix - §1 health check.** "No nudge for 2 periods" is undefined when nothing was sent. S must compare each
   successful send with the next `last_nudge_ms`: N sends without a sync within 60 s → `fcm_ok=false`. Document the
   worst case: a silent FCM failure delays ring, lock and lifts by up to 2 × 30 min. Show it on the device page.
10. **should-fix - §1 DNS filter.** gms's FCM traffic goes through KidVpnService's server blocklist (DnsFilterEngine).
    Ad lists often block Firebase hosts, and §1 proposes blocking firelog. S must always allow mtalk*.google.com,
    fcm/firebaseinstallations/android.apis.googleapis.com and android.clients.google.com, and block telemetry by
    exact host only.
11. **should-fix - §1 S sender.** reqwest has no default timeout, so one hung send blocks the only dispatcher and every
    nudge. Add per-request timeouts (like tracked_apps.rs:53), spawned and bounded sends, a dispatcher restart, and
    redacting Debug impls (JWT, access token, key never logged).
12. **should-fix - secrets.** Use a dedicated service account with only `roles/firebasecloudmessaging.admin`, not the
    broad Admin SDK default, in a project used for nothing else. Keep the key outside `/opt/kid-phone-server/data`,
    which backups zip and mirror (backups.rs:214-229), at 0600 owned by the service user. S refuses a
    group/world-readable key. Add a rotation runbook. Restrict the API key (also the `.debug` SHA-1).
13. **note - §4 installs and shade.** DISALLOW_INSTALL_APPS is never set (AppEnforcer.kt:661), and setting it would
    block Play updates and L's catalog installs. So Play can always install (via #1, a web install, or an auto-install).
    Containment is `enforceOnNewPackage`, live only while the process runs (Application.kt:57-64), else the next
    `apply()`. Apply at every process start. Cancel vending notifications in the shade outside install mode.
14. **note - §1 downgrades.** FCM deprioritises HIGH messages that show no notification, so measure the downgrade rate
    before counting on savings. The DO app should be in the EXEMPTED standby bucket; check `am get-standby-bucket`.
15. **note - §3 measurement.** "Before vs after" mixes five changes. Run A/B on the new build (FCM vs forced SSE, with
    and without tsnet). Per-UID wakeups miss packet wakeups (tsnet/DERP), so use kernel wakeup reasons and radio time.
    ≤4/h is unlikely with tsnet up. Same place, signal and SIM each night.
16. **note.** The 6 h `dataSync` cap resets on foreground, but a screen-off night exceeds it, so the motivation holds.
    `specialUse` needs a subtype property. Existing bug: `commands_stream` drops `Lagged` (device_api.rs:792-795).

## Acceptance criteria

- [local] `decidePushTransport`: no config, gms missing or disabled, Play hidden, no token, `fcm_ok` for an old token,
  S module off → SSE.
- [local] Plan: PLAY_CORE never hidden or suspended (`[]`, locked, restrictSms, fallback). Kiosk gets only vending, and
  only in install mode. AppFilter drops it. Install mode ends on elapsedRealtime and on reboot. HOME pin and link
  blocker survive install-mode start, end and crash.
- [local] Manifest: KidFcmService not exported, no FirebaseInitProvider, anchor `specialUse`. Pre-init message: no throw.
- [local] S: JWT signed with a fixture key; timeouts against a hanging fake server; error classes; Retry-After; secrets
  not in logs; a world-readable key refused; health check. `assembleRelease` with and without FCM config.
- [device] Explicit-package and component Play intents (with and without NEW_TASK), a tapped Play notification, and a
  play.google.com link: Play UI is never usable outside install mode.
- [device] Install mode: only Play pinned; ends with Play in front, after 15 min, on reboot; HOME pin kept; new app
  hidden; a web install while L is killed gets hidden by the next apply.
- [device] #4 order; `no_modify_accounts` set, account kept; token with and without an account.
- [device] `force-idle` + ring ≤10 s, sync completes under a wakelock; FCM DNS-blocked → fallback within the
  worst case; Android 15+ reboot without an FGS exception.
- [device] Play auto-updates while not launchable; Play Protect; bucket EXEMPTED; A/B (#15); 112 in install mode.

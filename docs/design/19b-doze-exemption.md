# 19b - Battery optimisation exemption (Doze) for the launcher

User decision (2026-10-09): before ntfy (PLAN.md "Plan B"), put the launcher on Android's power-save allowlist
("battery: Unrestricted"), so the SSE stream (design 19, the only nudge) keeps working while the phone sleeps.
AOSP references are `android16-release`; the proc-state rules below are the same in `android14-release` (our
minSdk). [inferred]/[device] mark guesses and what only the phone can answer.

## 0. What the allowlist changes for us (AOSP, read for this doc)

- **The anchor FGS already passes Doze.** Doze's network firewall lets a UID through when its proc state is at or
  below `FOREGROUND_THRESHOLD_STATE` (= `PROCESS_STATE_BOUND_FOREGROUND_SERVICE`; `NetworkPolicyManager.
  isProcStateAllowedWhileIdleOrPowerSaveMode`), and `PowerManagerService.setWakeLockDisabledStateLocked` disables
  partial wake locks in idle only above that state. `CommandListenerService` is a `specialUse` FGS whenever it runs
  (design 19), so our process sits at `FOREGROUND_SERVICE`: the SSE socket (through tsnet, same UID) and our wake
  locks work in Doze without the allowlist. Battery Saver uses the same rule. So "Doze cuts network and wake locks
  unless allowlisted" holds only for a process without the FGS. Design 19's QA review said the same.
- **What the allowlist adds:** all our alarms become `FLAG_ALLOW_WHILE_IDLE_UNRESTRICTED`
  (`AlarmManagerService.setImpl`, `isUidPowerSaveUserExempt`). That changes almost nothing, because our while-idle
  alarms (<= 4/h) never reach the 72/h quota. It also gives network and wake locks in the gap while the anchor isn't
  foreground (crash, restart), and an exemption from OEM battery managers that honour the allowlist [inferred, §4].
  We have no jobs (WorkManager went in v0.8.0), and App Standby already exempts the device owner.
- **So expect no ring-latency change on AOSP.** The real question is the Jelly Star's own battery layer, which only
  the device run (§5) answers. The exemption is still worth having: one tap at setup. The status line (§2) makes
  the state visible either way.
- **Who can grant it.** `setApplicationExemptions` is out (`MANAGE_DEVICE_POLICY_APP_EXEMPTIONS`, `internal|role`).
  **Android's Settings can't do it either.** SettingsLib's `PowerAllowlistBackend.isDefaultActiveApp` counts every
  package with an active admin (`packageHasActiveAdmins`). So Settings shows the launcher as "Unrestricted" (app
  battery page) or "not available" (special access, `HighPowerDetail`), greyed out, whatever `DeviceIdleController`
  holds. Choosing "Don't optimise" there writes nothing (old value == new value). Never trust Settings' display: the
  truth is `PowerManager.isIgnoringBatteryOptimizations` or `dumpsys deviceidle whitelist`.
  - What works: the `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` dialog (`RequestIgnoreBatteryOptimizations`). It
    finishes silently if we are already exempt or don't declare `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. Allow ->
    `setAppUsageState(UNRESTRICTED, forceMode = true)` -> `addPowerSaveWhitelistApp`.
  - Also works: `adb shell dumpsys deviceidle whitelist +<pkg>` (the shell holds `DEVICE_POWER`).
  - Persistence: the entry survives updates (`DeviceIdleController` drops a package only on `PACKAGE_REMOVED`
    without `EXTRA_REPLACING`). It is lost on uninstall and wipe. `me.vibb.launcher` and `.debug` have separate entries.
- **The dialog lives in `com.android.settings`, which a managed phone suspends and hides.** It is a controllable
  package (a system app with a launcher icon), so it is hidden unless allowlisted (16d: "Settings hidden"). It is
  also never pinned (`lockTaskHelpers` excludes Settings). A hidden package doesn't resolve. A suspended one shows
  the "app paused" dialog. With the app block, any non-pinned package's activity, even one started into our task,
  becomes `BlockedAppActivity` (`ActivityStartInterceptor.interceptLockTaskModeViolationPackageIfNeeded` ->
  `LockTaskController.isActivityAllowed`).

## 1. Getting the exemption with the parent present

**Rule: we start the dialog only while Android's Settings is already visible, and we never change enforcement or
the lock-task list for it.** A LockTaskChrome-only window can't work: pinning Settings doesn't unhide or unsuspend
it. A window that also did that would go through `computeEnforcementPlan`. That is install-mode-sized machinery in
the safety-critical plan, and the one exception to "never pin Settings", for a step done once per phone life.
LockTaskChrome and `lockTaskHelpers` stay untouched.

- **P0 - adb setups** (the preferred path, `docs/setup/google-account.md` §3): one more line after
  `dpm set-device-owner`, next to `cmd notification allow_listener`:
  `adb shell dumpsys deviceidle whitelist +$P` -> "Added: ...". No code.
- **P1 - QR provisioning**: `PolicyComplianceActivity` runs inside the setup wizard. The parent is present, there
  is no policy yet and Settings is visible.
  - It calls `setResult(RESULT_OK)` first, and only then starts the dialog for a result, if we aren't exempt and the
    dialog resolves. It starts it once, not again after a recreate.
  - It finishes on the dialog's return, whatever the outcome, and at once on any exception. It never blocks
    provisioning.
- **P2 - enrolling in launcher Settings** (setup mode, before the first policy): both the code entry
  (`enrollWithServer`) and the in-app QR scan (`handleSetupQrScanResult`) show the same dialog on success.
  `enrollWithServer`'s `SyncRunner.request("enrolled")` moves into the dialog's result callback, or runs at once
  when no dialog is shown. The first policy hides Settings, which kills its process and would close the dialog
  under the parent's finger. The scan path requests no sync today and stays that way. A stream reopen or a backstop
  sync can still land during the dialog [device]: it then closes, and P3 is the fallback.
- **P3 - a row in the PIN-gated Settings** ("Background connection" / "Bakgrunnstilkobling", in the MDM section
  next to "Sync now"):
  - The summary shows the state (§2).
  - A tap opens the dialog only when it resolves to an unsuspended system package. That is the case before the
    first policy, when unmanaged, during "Pause all restrictions" or the override PIN, or if the parent allowlisted
    Settings.
  - Otherwise the summary reads: "Pause all restrictions first (Android's Settings is hidden while the phone is
    managed), tap here, then end the pause". This is the same pattern as adding the Google account
    (google-account.md §3).
- **Nowhere else.** Nothing appears on Home, in kid Settings, on the lock or as a notification. Nothing repeats by
  itself (§3).
- **Launch.** The intent is explicit to `resolveActivity(intent, MATCH_SYSTEM_ONLY)`: a `FLAG_SYSTEM` package, not
  ours, not `FLAG_SUSPENDED`, with `data = package:<ours>`.
  - It is started from the activity without `NEW_TASK`, so it lands in our task.
  - The manifest declares `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
  - On return we re-read the state. If it changed, we request a sync, so the PWA follows within seconds.
- **Kiosk on/off, LOCKED, what the kid can reach.**
  - P0-P2 run before any policy: no kiosk, nothing hidden, no PIN lock.
  - P3 can only open the dialog in a state that already shows Settings. With the kiosk on, that means the pause or
    the override (the kiosk is off then) or Settings allowlisted (pinned then). With the kiosk off, Settings is
    still hidden by the allowlist, so it is the same.
  - While LOCKED the PIN lock is in front, so the parent can't be in launcher Settings. During a time-rule lock the
    row says to pause first, and the pause lifts the lock.
  - The dialog itself has a title, a text and Allow/Deny: no links, and it hides non-system overlays. It adds
    nothing to what setup or the pause already exposes.
  - The "window" is the existing pause (`timedWindowActive`: 2 h, elapsed realtime, boot count). It ends by the
    parent's switch, after 2 h or at a reboot, as it always has.

## 2. Status: one new field (API change: launcher and server in the same commit)

- **Launcher**: `StatusReportRequest.batteryOptimizationExempt: Boolean? = null` -> `battery_optimization_exempt`,
  in every status report. The value is `PowerManager.isIgnoringBatteryOptimizations(packageName)`, wrapped in
  `runCatching`. `null` (unreadable) is left out of the JSON.
- **Server**:
  - The model: `StatusReport.battery_optimization_exempt: Option<bool>` (`#[serde(default)]`).
  - The migration takes the next free number, `0052_battery_exemption.sql` at 8aeaf77d:
    `ALTER TABLE device_status ADD COLUMN battery_optimization_exempt INTEGER;`
  - Storage: stored by `device_api.rs`, read back as `DeviceStatus.battery_optimization_exempt`.
- **Device page**, "Play and kiosk" card (`play_card`), right after the "Instant changes" line. It is a plain line
  for now (open question 1).
  - `Some(false)`: "Battery optimisation: on - instant changes may lag while the phone sleeps. To fix: on the phone,
    launcher Settings -> Pause all restrictions -> Background connection -> Allow, then end the pause (or, with USB
    debugging allowed: `adb shell dumpsys deviceidle whitelist +me.vibb.launcher`)."
  - `Some(true)`: "Battery optimisation: off for the launcher."
  - `None` (an older launcher, or unreadable): nothing.
- **Phone**: the P3 summary has four states:
  - "Unrestricted - changes arrive while the phone sleeps";
  - "Battery optimisation is on - tap to allow";
  - the pause-first text;
  - "Unknown".
  The strings go in `values/` and `values-nb/`.
- **No policy field and no capability.** Nothing the server says changes what the phone does here.
  `policy_json_keys_snapshot` and the policy half of `PolicyResponseCompatTest` must not change: a diff there is a
  mistake.
- **Tests**:
  - Launcher, `PolicyResponseCompatTest`: "battery_optimization_exempt is reported under the server's key and left
    out when unknown", like `backup_service_enabled`.
  - Server, `src/tests/play.rs`: `true` and `false` are stored; a report without the key (an older launcher) stores
    NULL; the card shows the line for each of the three.

## 3. Re-check, never nag

- Every status report sends the live value, so the PWA shows the truth within one sync, after anything. The P3
  row re-reads it in `onResume`.
- **Updates** (self-update, `MY_PACKAGE_REPLACED`) and OTAs keep the entry. Nothing to do.
- **Wipe, reinstall, debug <-> release**: the entry is gone (and the package may be different). The phone is
  provisioned again, so P0/P1/P2 ask again, and the PWA line says "on" until it is done.
- **Restore**: nothing to restore. The allowlist is system state, not app data, and `allowBackup="false"`. A
  restored phone goes through provisioning too.
- **No automatic prompt after setup**: no notification, nothing kid-facing. A lost exemption shows only on the
  parent's device page and in the gated row.

## 4. OEM battery managers (Jelly Star: MediaTek, Unihertz)

- **Look**:
  - `adb shell pm list packages -s | grep -iE 'duraspeed|power|batt|background|freez|sleep|boost'`;
  - `adb shell settings list system | grep -iE 'dura|power|bg'` (also `global`, `secure`);
  - Android Settings search for "DuraSpeed", "Background", "Battery manager" and "Auto-start".
  - Note whether `dumpsys deviceidle` shows OEM-shortened idle timeouts (its `Settings:` block).
- **If one exists**, run §5's overnight nights in its default state. Look for:
  - `logcat -b events | grep -E 'am_kill|am_proc_died|am_stop' | grep vibb`;
  - `dumpsys activity services me.vibb.launcher` (is `CommandListenerService` still there?);
  - `CommandListener` gaps in logcat.
- **What to do**:
  - Switch it off in the setup runbook, before enrolling, while Settings is still visible. A kid phone gains
    nothing from it. Once managed, Settings is hidden, so the kid can't switch it back on.
  - If it can't be switched off, add the launcher and Play services (other apps' FCM, Element X) to its list.
  - Re-check after an OTA.
  - If it still stops the anchor, that is the case for plan B: Play services' FCM survives most OEM managers.

## 5. Device checks

**Jelly Star, extending design 19's run** (release build, SIM, Element X as in daily use, "Block USB debugging"
off for the run). Read the state with `adb shell dumpsys deviceidle whitelist =me.vibb.launcher`, set it with `+`,
clear it with `-`. The A/B needs no new build.

1. **Forced Doze, 3 rings in each state**:
   - Setup: `dumpsys battery unplug`, screen off, `dumpsys deviceidle force-idle` (expect "Now forced in to deep
     idle mode"), wait 5 min.
   - Ring from the PWA from another network. Time the click -> ring (the server's nudge log vs the launcher's
     sync start).
   - While idle: `dumpsys netpolicy` -> our UID's `blocked_state` should be allowed `FOREGROUND` (plus
     `POWER_SAVE_ALLOWLIST` when exempt) with nothing blocked. `dumpsys power` should show our wake locks not
     disabled.
   - Then `deviceidle unforce`, `battery reset`.
2. **Real overnight, one night each state**, keepalive 240 s, mobile data, unplugged, screen off >= 6 h.
   - In the morning, before touching the phone, ring from the PWA and time it.
   - Then design 19's read-out: `batterystats --checkin`, a bugreport, `dumpsys deviceidle` (did it reach IDLE,
     and the maintenance windows), `logcat | grep CommandListener` (reconnects, stale drops).
3. **Battery at 120 s vs 240 s**: design 19's A/B (twice each on mobile data, once each on Wi-Fi), with the
   exemption in the state step 2 favours. Step 2's nights count as 240 s nights.
4. **Settings' display**: Apps -> launcher -> Battery shows "Unrestricted", greyed out, while `=` says `false`.
   This confirms §0 on the OEM's Settings, and the runbook says so.
5. **P1, when QR provisioning is tested**:
   - The dialog shows in the setup wizard, and Allow, Deny and Back all finish provisioning.
   - Check `whitelist =` afterwards.
   - Note whether the first policy arrived while the dialog was up (if so: open question 3).
6. **§4's OEM check.**

**Decision**: if the exemption makes Doze rings clearly faster, or a non-exempt night has a ring that waited for
the backstop, the PWA line becomes a warning. If there is no difference, it stays a plain line, and we keep the
exemption as cheap insurance. If rings are slow in both states, the cause is tsnet or the radio (design 19 QA #8c),
and plan B is next.

**Emulator** (AOSP, `docs/testing/emulator.md`, `P=me.vibb.launcher.debug`):

1. **Fresh install**: `dumpsys deviceidle whitelist` has no `user,$P` entry, and the row says "on".
   - Enrol in Settings. The dialog shows before the first sync (logcat: the "enrolled" request comes after the
     dialog closes).
   - Allow -> `user,$P,<uid>` is listed, and after the sync the PWA line says "off".
2. **Managed with the kiosk on**: the row says to pause first. A tap starts nothing: no `BlockedAppActivity`, and
   no Settings activity in `dumpsys activity activities`.
   - Pause -> row -> Allow -> end the pause. Settings is hidden again afterwards
     (`dumpsys package com.android.settings | grep hidden=true`).
3. **Toggle and update**: `whitelist -$P`, then "Sync now", and the PWA says "on". `+$P` -> "off".
   `adb install -r` keeps the entry.
4. **Doze**: `dumpsys battery unplug`, `input keyevent KEYCODE_SLEEP`, `dumpsys deviceidle force-idle`, then ring
   in both states.
   - Expect a ring within ~10 s both times (§0), and check `netpolicy` as above.
   - Then `unforce`, `battery reset`.

## 6. Steps

Each step is green alone. The device A/B (§5) can run before any of them.

1. **Server + contract** (one commit, root rule):
   - the migration, `models.rs`, `device_api.rs` and the `play_card` line, with tests in `src/tests/play.rs`;
   - the launcher DTO field and its `PolicyResponseCompatTest` case;
   - a `server/CLAUDE.md` line.
   - The `Cargo.toml` bump comes with the next server release.
2. **Launcher**:
   - the manifest permission, with a `PushManifestTest` check that it is declared (without it the dialog fails
     silently);
   - `server/BatteryExemption.kt`: pure `dialogTarget` (system, not ours, not suspended, else null) and
     `batteryRowState(exempt, dialogAvailable)`, plus the glue;
   - `BatteryExemptionTest`: every refusal, every row state, and no setup prompt when exempt;
   - the value in `MdmSyncWorker`'s report;
   - the P3 row, with preference XML and strings in en/nb (`TranslationsTest`);
   - P2 in both enrol paths, and P1 in `PolicyComplianceActivity`;
   - a `launcher/CLAUDE.md` section.
   - `KidScreensEscapeTest` stays as it is: the action is referenced only in `server/BatteryExemption.kt`, called
     from the gated Settings and the compliance activity.
3. **Docs**:
   - `emulator.md` §4 (the adb line) and §5's checks;
   - google-account.md §3: after QR provisioning, check the PWA line; Android's battery page shows the launcher as
    "Unrestricted" whatever the real state (§0);
   - §4's OEM step in the setup runbook;
   - PLAN.md's Battery line points here.

## Open questions (each with a recommendation)

1. **Is the device-page line a warning or a plain line?** Plain until the Jelly Star run shows the exemption
   matters (§0: on AOSP the FGS already passes Doze). Make it a warning if it does.
2. **Should enrolled phones get a narrow in-kiosk window** (unhide, unsuspend and pin only Settings for <= 60 s,
   via the plan and LockTaskChrome) instead of the pause? No. It is needed only when setup skipped P0-P2. It costs
   new code in the enforcement plan and an exception to "never pin Settings". Revisit only if the run shows the
   exemption matters and the pause proves clumsy in practice.
3. **Should the dialog run inside QR provisioning (P1)?** Yes, guarded (`RESULT_OK` first): it is the one moment
   the parent is surely there on the QR path. Drop it if the Jelly Star QR run shows the first policy closing the
   dialog. The pause path (P3) is then the fallback.
4. **If an OEM manager exists, switch it off or allowlist the launcher?** Switch it off, before enrolling.
   Allowlist the launcher and Play services only if it can't be switched off.

## User decision (2026-10-09)
Two phases ("Ok ift ditt forslag"):
- **Phase 1, now:**
  - the `battery_optimization_exempt` status field and a plain device-page line;
  - the `adb shell dumpsys deviceidle whitelist +me.vibb.launcher` line in the setup docs;
  - the row in the PIN-gated Settings during the existing pause.
- **Phase 2, the prompt inside QR provisioning:** only if the Jelly Star run shows a difference, or the phone has an OEM battery manager.
- Open questions 1-4: the architect's recommendations stand.

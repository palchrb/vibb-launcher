# 18 - Sound mode on the kid's Settings page

User wish (2026-10-07): the kid can switch the phone between sound and silent from the kid Settings page
(`KidSettingsActivity`, the left swipe). The page already has Wi-Fi, Bluetooth and brightness, each behind a
`quick_controls_mask` bit the parent sets on the device page.

## Design

1. **New quick-control bit** `QUICK_CONTROL_SOUND = 8`.
   - Server: a device-page checkbox under "Quick Controls" with the same pattern as the other three.
   - New devices get it on (mask 15). Existing devices are unchanged.
   - Launcher: `QuickControls` and `kidSettingsModel` get a "Lyd" row.
2. **Control.** A three-way segmented row: **Lyd / Vibrer / Lydløs** (en: Sound / Vibrate / Silent). It reads and sets
   `AudioManager.ringerMode`.
   - Normal and vibrate need no permission.
   - Silent may need Do Not Disturb (notification policy) access on Android 7+. Changing into or out of silent can
     toggle zen, and AudioService then requires access.
   - Research: does notification-listener access (we have it) imply policy access on Android 14-16?
     - If yes, all three modes work.
     - If no, the provisioning runbook adds `adb shell cmd notification allow_dnd <pkg>`. Without it, the row offers
       only Sound/Vibrate, and the status report says so.
   - Never set DND or interruption filters ourselves.
3. **Volume.** Keep it out of this step. The hardware volume keys still work.
4. **Interplay.**
   - The parent's "Ring" (Find My Device) still forces normal and full volume.
   - Our VoIP ringtone and Telecom already respect ringer mode and DND.
   - Kiosk/lock: the row is only on the kid Settings page, never on the PIN lock.
   - The override PIN and the pause change nothing here.
   - Status report: `ringer_mode` (normal/vibrate/silent), shown on the device page so the parent sees why the kid
     didn't hear a call.
5. **Not in scope:** automatic silent during school mode (later idea), and a parent "always ring for parents"
   (needs DND priority senders; later).

## Checks

- Unit tests:
  - the model rows with the new bit;
  - the segmented-row state from the ringer mode;
  - Silent hidden without access;
  - compat for the mask and `ringer_mode` on both sides.
- Emulator: switching all three modes; a phone call and an Element X call in each mode; the device page shows the
  mode; Find My Device ring from silent.

## QA review (2026-10-07)

**Research (AOSP android14/15/16-release: `AudioService`, `NotificationManagerService`, `ManagedServices`, `ZenModeHelper`, `DevicePolicyManagerService`).** `setRingerMode` -> `setRingerModeExternal` throws only when `wouldToggleZenMode`: the *external* mode crosses Silent (Normal/Vibrate -> Silent, Silent -> Normal/Vibrate); Normal <-> Vibrate never needs access. `checkPolicyAccess` grants policy access implicitly to a package with an approved notification listener (`isComponentEnabledForPackage`: approved is enough, bound or not) **and** to the active device/profile owner (all three releases) - so yes, and we don't even need the listener; no `allow_dnd` step. No other DO path: no DPM ringer API, `MODE_RINGER` is in DPMS `GLOBAL_SETTINGS_DEPRECATED` (`setGlobalSetting` ignores it), the system-settings allowlist is brightness/timeout only, `setRingerModeInternal` is SystemUI-only; the 15/16 `HardeningEnforcer` volume block is automotive-only / off by default on phones.

1. **High - Silent is DND.** `ZenModeHelper.RingerModeDelegate.onSetRingerModeExternal` (same in 14-16): an app's Silent turns on a manual DND (priority only) when zen is off; an app's Sound or Vibrate while any DND is on turns the manual DND off and deactivates every active automatic rule (schedules, bedtime). "Never set DND" can't hold with Silent. Change: drop Lydløs (row = Lyd / Vibrer; Vibrate is the school mode), or state that Lydløs = Android's DND (priority) and rewrite the rule; either way, while `currentInterruptionFilter != ALL` (and it isn't our own Silent) the row is read-only ("Ikke forstyrr er på"), so a tap never ends a parent's or bedtime DND.
2. **High - FMD ring already does this.** `LocateCommands.ring()`'s `ringerMode = NORMAL` succeeds for the DO (the "can't grant itself" comment is wrong): it ends any DND/bedtime; the restore to Silent then creates a manual DND; raising/restoring `STREAM_RING` (the UI-sounds stream) also sets the ringer internally (restoring 0 gives Vibrate); a second `ring` overwrites `originalRingerMode`/`restoredStreams` with the forced values. The alarm stream is not ringer-affected (`MODE_RINGER_STREAMS_AFFECTED` default = RING/NOTIFICATION/SYSTEM), so: drop the ringer-mode set/restore and the RING/NOTIFICATION raise from `ring()`, keep the first ring's saved values, fix the comment and the launcher CLAUDE.md line; the design's "Ring forces normal" goes.
3. **Medium - access branch.** Replace "if no: runbook `allow_dnd`, Silent hidden, status says so" with the answer above; keep a defensive `isNotificationPolicyAccessGranted()` check (hide Silent, log) with its unit test, and catch `SecurityException` per tap.
4. **Medium - the bit is not a lock.** Volume keys reach Vibrate and the volume panel's ringer button reaches Silent (internal path; no DND with the default `VolumePolicy`). `DISALLOW_ADJUST_VOLUME` is no ringer lock: AudioService master-mutes the whole device under it (and `DISALLOW_UNMUTE_DEVICE`) - never set it (we don't today). The device-page text says the box only shows the row.
5. **Medium - status.** Report `ringer_mode` and the interruption filter (DND also explains a missed call; `ringPlan` already uses both): launcher fields nullable and omitted when unknown (pinned in `PolicyResponseCompatTest`'s status checks), server `#[serde(default)] Option<..>` + a migration column, device page "ved siste synk". Policy shape unchanged: the mask stays an integer, `policy_json_keys_snapshot` untouched.
6. **Low - mask.** `QuickControlFeature.SOUND = 8`; `controlsSection` must count it (mask 8 alone is not `NoneEnabled`); `DEFAULT_QUICK_CONTROLS` = 15 and `tests/device_api.rs` (asserts `1 | 2 | 4` and `7`) follow; old launchers ignore bit 8 (no card for mask 8 alone - fine). Default on is fine given 4.
7. **Low - details.** Hide Vibrate when `!Vibrator.hasVibrator()` (AudioService turns it into Silent, i.e. DND); re-read on `RINGER_MODE_CHANGED_ACTION` while the page is shown (volume keys change it underneath). `VoipRinger`: no change (reads mode + filter at ring start; the row is never on the lock).

## Decisions after QA review

- **No real Silent** (QA #1): an app's Silent is Android's DND, and leaving it ends bedtime/schedules. The row has two
  choices, **Lyd** and **Lydløs (vibrerer)** = RINGER_MODE_VIBRATE (en: Sound / Silent (vibrate)); Normal <-> Vibrate
  never touches DND or needs access. On a phone without a vibrator the row is hidden. While the interruption filter
  isn't ALL the row is read-only ("Ikke forstyrr er på"), and it re-reads on `RINGER_MODE_CHANGED_ACTION`.
- QA #2-#7 accepted as written, incl. fixing Find My Device's `ring()` (alarm stream only, no ringer-mode or
  RING/NOTIFICATION changes, keep the first ring's saved values), the status fields `ringer_mode` + interruption
  filter shown on the device page "ved siste synk", `QUICK_CONTROL_SOUND = 8`, new devices 15, and the device-page
  text that the box only shows the row (volume keys still work).

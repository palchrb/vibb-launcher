# Launcher round 7 - small items (proposal, revised after `qa-r7-launcher-design.md`)

Asked 2026-10-20. Each item is its own section with its own tests; they ship in one launcher round after 0.31.0.
§1 any allowed app may ring over the lock (the residual accepted by the user 2026-10-20, no ring budget); §2 the catalog apps' battery state in the report (no battery-exemption flow, the user's call;
the force-stop protection dropped after code QA); §3 the global wake-on-notification switch.

## 1. Any allowed app may ring over the lock

The residual (§1.1.2) was accepted by the user on 2026-10-20, without a ring budget; built on branch `l7-a` (see
"Implementation status" at the end of §1).

The user, 2026-10-20: "det er ikke sånn at vi bare automatisk kan sense om en app har call funksjon med den call
style biten? Isåfall kan den få automatisk tilgang" and "La oss legge inn at en tillatt app kan vise calls skjerm over
lås". Builds on design 17/17b (`lock/VoipCallPlan.kt`, `lock/VoipCalls.kt`, `badges/VoipCallReader`), launcher only
plus one status field in both directories.

### 1.0 Today

The reading is generic: a ring is a notification of category `call` with a full-screen intent (or an incoming
CallStyle whose FSI Android dropped but that has an answer action), from an unsuspended package; the in-call phase is
the app's foreground-service notification of category `call` (or Element's channel) plus audio `IN_COMMUNICATION`.
Only the candidate set is fixed: `voipCandidates(effective)` (`VoipCallPlan.kt:47`) is the contacts' messaging apps
(`messagingAppPackages`: Element X, Signal/Molly, never the SMS app) while calls are managed and on,
`KNOWN_VOIP_PACKAGES` while unmanaged, nothing while calls are off or unknown. `VoipCalls.eligible` then drops
suspended packages (not allowlisted = suspended; a time rule suspends what it doesn't leave usable). A new app
(WhatsApp, Teams, a school's app) needs a launcher change today.

### 1.1 Decisions

1. **The candidate set is every allowed app, minus an exclusion set.** The rule is automatic (no per-app tick).
   `voipCandidates(effective: CallPolicyState, voipAllowed: Set<String>?, policyKnown: Boolean)` keeps its gates -
   calls managed and on, or unmanaged (`effective` already folds a no-calls time rule in, `VoipCallPlan.kt:41`);
   nothing while calls are off or `UnknownFailClosed` - and, when they pass,
   answers `voipAllowed` instead of a fixed list. **Fallbacks** (in the pure function, so the §1.2 table is the whole
   rule): no enforced policy yet in this process (`policyKnown == false`) or `voipAllowed == null` (apps unmanaged)
   -> today's sets (the messaging apps, or `KNOWN_VOIP_PACKAGES`), so a phone before its first apply and a
   calls-only phone behave as before; never wider than today in those cases.
   - `voipAllowed` is computed **once**, in `NotificationRuleRuntime.refresh()` (the end of `apply()`), as a new
     `@Volatile Set<String>?`: the **effective** allowlist (`effectiveAllowlist(..., WindowRelock.blockedPackages)`,
     not the raw `policy.allowlist`) + the contacts' messaging apps + a time lock's usable apps, minus the exclusion
     set; `null` when the allowlist is `null`. `VoipCalls.eligible` only reads it and does `isPackageSuspended`: no
     binder call (no `CallSystem.defaultSmsPackage`, a RoleManager call) on the main thread, no raw allowlist.
   - **The exclusion set** = `smsPackages(defaultSms)` (`KNOWN_SMS_PACKAGES` + the default SMS role holder; Google
     Messages posts call-category notifications for RCS video calls that our rules never saw) + `NagPolicy.essential`
     (the system and default dialer, Telecom, the emergency dialer, `com.android.phone`, the clock, the keyboards,
     SystemUI - resolved in `apply()`; without it the system dialer's cellular ring, which carries an FSI, would be
     taken for a VoIP ring) + our own package family (`OWN_PACKAGES`). It is a set difference: a package the allowlist
     names twice is no bypass.
   - A throw earlier in `apply()` keeps the previous `voipAllowed` (`refresh` runs last); that is today's behaviour of
     `policy` too and fails toward the older, narrower value.
2. **What the kid can do with it, and the residual the user is asked to accept.** An allowed app that posts a
   call-style ring gets its own screen over the PIN lock for that ring (and, if it keeps a call-category foreground
   notification with audio in communication, for the call). The app is the parent's choice; another call, the
   emergency flow and an alarm always win; Home stays below the lock. A kid with a "fake call" app gets into that app
   without the PIN - visible to the parent through §1.1.3. The whole residual, from the code (QA r7-1):
   - **Not time-bound by the ring.** The 2-minute ring limit is per ring and the 3 h cap is per record. An app that
     cancels its ring and posts it again gets a fresh `ringStartedElapsedMs` after the 15 s grace
     (`VoipCallPlan.kt:278-284`, `continueCall` :297-313); once a record ends, the next ring starts a new record with
     a new 3 h cap (:288-290). An app also controls the in-call phase itself (a call-category foreground notification
     and `MODE_IN_COMMUNICATION`, which any app may set). **No budget bounds this (the user's call, below): the lock is
     the kid's own PIN, so an app that restarts its ring gains the kid nothing.**
   - **The kiosk does not keep every other app locked while the ring is on.** The re-front loop yields `"voip"` to
     whatever is in front and never checks the package (`lock/RefrontPolicy.kt:86`). With the kiosk **on**, the
     lock-task list is the whole kiosk list, untouched while LOCKED (`server/LockTaskHelpers.kt:188-190`): Home and
     Recents are off, but the ringing app's own screen can open **other allowed apps** (a link, a share, a "settings"
     screen) for the call's duration, and that app then stays in front over the lock - the same as today for Element
     X. With the kiosk **off**, the pin plus the app-block bit hold that one package (:191-202), but only while lock
     task runs; a ring that began unlocked gets no lock task when the phone locks (`lockTaskEntry`,
     `lock/LockTaskRoot.kt:50`), and `setStatusBarDisabled` doesn't block Recents, so other unsuspended apps are
     probably one swipe away (a device check, §1.3).
   - **Any caller, not just the contacts.** "Who may call is set on vibb.me" holds for Element and Signal under the
     contact rules. A WhatsApp, Teams or school-app call from anyone who has the kid's number rings over the lock,
     and Answer opens the call: **strangers can ring through apps whose contacts Vibb doesn't manage.**
   - **Noise and light.** Every accepted ring wakes the screen (`VoipWakeActivity`) and plays the default ringtone
     (`ringPlan` follows only the ringer mode and DND).
   - Bounded: Home sits below the lock; the permission controller is pinned with the kiosk off but grants that app
     nothing it couldn't ask for unlocked.
   - **No ring budget** (the user, 2026-10-20, on a proposed cap of 6 accepted rings per app per hour: "jeg liker
     ikke tanken på at man ikke kan ringe noen 7 ganger på en time ... For legitime anrop fra apper jeg har godtatt
     forstår jeg ikke helt hvorfor vi skal begrense"). The reasoning that makes the cap unnecessary: the PIN lock's code
     is the **kid's own** (design 10, `kid_lock`), so an allowed app that fakes or restarts rings to keep the lock away
     gives the kid nothing the kid can't get by typing the code; anyone else can only trigger a ring from outside,
     which is an incoming call - the behaviour wanted. What the lock protects (another person holding the phone) is
     untouched by the widening. Every ring of a candidate app rings; nothing is counted.
3. **The parent sees which apps rang.** `lock_state.voip_rang`: `[{"package": "<pkg>", "last_at_ms": <wall ms>}]`,
   the packages whose ring the lock accepted (phase RINGING began) in the last 7 days, newest first, at most 8, valid
   package names only; always sent (empty list). **A ring is recorded only when it began while the lock mode
   was LOCKED, or when the phone locked during it** (`VoipCalls.evaluate` builds records in every mode, so a call taken
   on an unlocked phone is not "over the lock"). Kept in `VoipCalls`' CE prefs `voip_call` (`rang_v1`, package -> wall
   ms; pruned at each write and each report; nothing but package names and times). Server: `kid_lock::LockState` gets
   `voip_rang: Vec<serde_json::Value>` with `#[serde(default)]` (missing = empty); `sanitize_lock_state` validates
   **each entry** (`valid_package_name`, an integer `last_at_ms`, a future time dropped),
   dedupes by package, newest first, `take(MAX_FSI_DENIED)`; **a bad entry is dropped, never the whole `lock_state`**
   (the lock-off warnings stay). The sanitizer takes `now_ms` (its two callers pass it) for the future-time rule. The
   Lock Screen card shows an info note per package "Calls from <label> rang over the lock · <when>"
   (`LockNote::RangOverLock`; a quiet line, not a warning). The label is the catalog/app-display name for the package, else the package; the time is in the device's
   reported zone (`time_state.zone`), like the other device-page times. Contract: `StatusReportRequest.LockStateReport
   .voipRang` (`LockStateReport` is built in `PinLockRuntime` only), the launcher's `PolicyResponseCompatTest`
   (`status report lock_state uses the server's keys` pins the key set) and the server's `server/src/tests/step10.rs`
   round trip, one commit for both directories.
4. **Unchanged**: the reader reads no text (`VoipCallGuardTest`), the in-call phase's rule (an app without a
   call-category foreground notification gets the lock back after the grace, its call going on in the background -
   a known limit, noted for the device checks), the kiosk-off lock-task list (the ringing package is pinned as
   today), `voip_fsi_denied`, the ring card and 17b's FSI-first send.

### 1.2 Tests (required)

`VoipCallPlanTest`: the new `voipCandidates` table - managed and on with `voipAllowed` (every allowed app), managed and
off / unknown (none), unmanaged with an allowlist (the allowlist), the two fallbacks (`policyKnown == false`,
`voipAllowed == null`), and that `voipAllowed` never widens the gates; a duplicate or excluded package stays out
(SMS packages, an essential/dialer package, our family); no counting of rings anywhere (a source check that
`VoipCallPlan.kt` holds no per-hour constant). `NotificationRuleRuntime`: `voipAllowed` from the
effective allowlist (a relock-blocked package is out), `null` when apps are unmanaged. `VoipCallGuardTest` unchanged and
still green (no new read of text). `VoipCalls`: `voip_rang`'s prune (7 days, 8 entries, newest first), a ring of a
non-candidate never recorded, a ring while unlocked never recorded, a ring during which the phone locks recorded; the status field's encoding in `LockStateReport`. Server
`kid_lock`: `voip_rang` sanitized (package names, cap, times, future time with the passed `now_ms`), a
malformed entry leaves the rest of `lock_state` intact (`step10.rs::lock_state_is_stored_sanitized_and_capped`), the
card's note and its label/zone; `step10`'s lock-state round trip with the field. `PolicyResponseCompatTest`: `status report
lock_state uses the server's keys` gains `voip_rang` (default `[]`); no policy change.

### 1.3 Device checks

Emulator (§6 of `docs/testing/emulator.md`, the Element X flow of design 17 as the regression): Element still rings
over the lock in both kiosk modes; with Element removed from the contacts' message apps but kept on the allowlist it
still rings (the new rule); with Element off the allowlist it doesn't. Seven rings inside an hour from the same app all
ring (no budget). With the kiosk off: whether Recents reaches other unsuspended apps
during a ring that began unlocked and then locked (the QA's open point). With the system dialer allowlisted and calls
unmanaged, a cellular ring is not a VoIP ring. Jelly Star with Signal: a Signal call rings over the lock and the
device page shows "Calls from Signal rang over the lock · today 14:02".

### 1.4 Implementation status (branch `l7-a`, 2026-10-20)

Built as designed; launcher and server in one commit.
- **Launcher**: `voipCandidates(effective, voipAllowed, policyKnown)`, `voipAllowedApps` and the `voip_rang` rules
  (`voipRangDue`, `voipRangAfter`/`voipRangEntries`, the `rang_v1` codec) in `lock/VoipCallPlan.kt`;
  `voipExcluded` and `NotificationRuleRuntime.voipAllowed` (computed in `refresh` from the effective allowlist, no
  change to `refresh`'s signature or its call in `AppEnforcer.apply()`); `VoipCalls.eligible` reads it with
  `policyKnown = NotificationRuleRuntime.policy != null`; `VoipCalls.noteRingWhileLocked` from
  `PinLockRuntime.syncVoipRinger` (every VoIP pass, the 2 s poll included, and every lock-mode change) and
  `VoipCalls.rangReport` in `PinLockRuntime.report`; `LockStateReport.voipRang` (`VoipRang`, `@SerialName("package")`,
  required so the empty list is always encoded - `ServerJson` doesn't encode defaults).
- **Server**: `kid_lock::LockState.voip_rang: Vec<VoipRang>` with a lenient per-entry `deserialize_with` (a non-list
  is empty, a bad entry dropped) under the struct's `#[serde(default)]`; `sanitize_lock_state(value, now_ms)`;
  `kid_lock::rang_notes` -> `LockNote::RangOverLock { label, when }` rows, appended to the Child PIN group's rows by
  `view_lock` (`handlers/device_pages.rs`) with the names from `app_rows` - `device_lock.html` is unchanged (the
  rows render through `partials/note_row.html`).
- **Tests**: `VoipCallPlanTest` (the candidate table and fallbacks, the allowed set and the exclusions, no per-hour
  constant, `voip_rang`'s rules and codec, the glue by source), `PolicyResponseCompatTest` (`voip_rang` in the key set,
  `[]` and an entry's keys); server `kid_lock::voip_rang_is_checked_entry_by_entry`,
  `kid_lock::rang_notes_name_the_app_and_its_time`, `step10::lock_state_is_stored_sanitized_and_capped` (a malformed
  entry, the rest kept, missing = `[]`) and `step10::lock_page_says_which_apps_rang_over_the_lock`.
- **Deviations**: (1) `LockState.voip_rang` is typed (`Vec<VoipRang>`) with the lenient deserializer rather than
  `Vec<serde_json::Value>` - the same "a bad entry is dropped, never the `lock_state`", and the stored JSON is
  always the clean shape; (2) a `last_at_ms` up to 5 minutes ahead of the server's clock is kept (clock skew), later
  ones are dropped; (3) the time is shown in the device page's zone (`Zone::load`, Phone Setup's - the zone every other
  time on that page uses), not the report's `time_state.zone`; (4) the launcher's `VoipCalls` tests are pure-rule
  and source tests (`voipRangDue` covers "unlocked never", "locked during the ring" and "a non-candidate never rings";
  a non-candidate never reaches RINGING).
- **Device checks owed**: §1.3 as written (the budget line now reads "all ring"), plus the PWA's row on Lock Screen &
  PINs after a ring over the lock, and that a ring answered on an unlocked phone adds no row.

## 2. Catalog apps' battery state in the report

The user, 2026-10-20: "er det ikke noe vi kan fikse på for å sikre at vi kan installere apper direkte på telefonen, og
samtidig live gi de batteriunntak?" - and, on the proposal to open Android's battery dialog for the kid through a
time-boxed Settings window: "Nei vil ikke auto åpne settings, da er det bedre at vi ikke legger inn noe spesielt rundt
batteriunntaket. Da får folk styre det selv." So: **no battery-exemption flow at all** - no sheet on the kid's screen,
no Settings window, no `battery_ask` command. The exemption stays what design 19b made it: the parent grants it by
hand during a pause (Settings › Apps › the app › Battery). The parent only sees the state.

### 2.0 Today

A catalog app the launcher installs is an ordinary user-installed app: it can be force-stopped where a Settings
surface is reachable (a pause; an OEM battery manager), and Android may hibernate it after months unused and reset
its permissions. Fossify Clock's alarms survive all of it (`setAlarmClock`); its running timers and a messenger's
background work don't survive a force-stop. The parent can't see whether an app is battery-exempt.

### 2.1 Decisions

1. **Not built: force-stop protection.** The first version of this section had every `apply()` set
   `setUserControlDisabledPackages(admin, pkgs)` for the catalog apps we installed (lifted by the override and the
   pause). Code QA (`docs/review/qa-l7-b-code.md`, finding 1, High) found: "a protected package can't be suspended,
   hidden or uninstalled by us either: §2 breaks the time rules, the camera lock, the update fence and
   uncheck/uninstall for every catalog app it protects." DPMS hands the list to
   `ProtectedPackages.setOwnerProtectedPackages`, and `isPackageStateProtected` is then checked by the platform
   (AOSP `android14-release`, the launcher's minSdk; `SuspendPackageHelper` also on `android15-release`,
   `android16-release` and `main`):
   - `SuspendPackageHelper.canSuspendPackageForUser`: `"Cannot suspend package …: protected package"` - with no
     exception for the device owner (`isCallerOwner` only skips the block-uninstall check);
   - `PackageManagerService.setApplicationHiddenSettingAsUser`: `"Not hiding protected package"`, returns `false`;
   - `DeletePackageHelper.deletePackageVersionedInternal` (`isPackageDataProtected`): `"Attempted to delete protected
     package"`, `DELETE_FAILED_INTERNAL_ERROR`.

   So a protected catalog app would stay usable through a bedtime rule or a used-up budget (fail-open on a core
   restriction), the camera lock and the update fence couldn't hold it, and an uncheck couldn't hide or uninstall it.
   Decided (the architect, 2026-10-20; the user is told): **dropped**. Nothing in the launcher calls
   `setUserControlDisabledPackages` (`CatalogBatteryTest` pins that); a narrowed, ordered protection would need its own
   design and device checks first.
2. **The parent sees the battery state, nothing more.** `installed_apps[]` gets `battery_exempt: true|false` for the
   catalog apps we installed - the stored catalog pins (`WindowRelock`'s `rememberCatalogPins`: the current checked
   list) ∩ installed for this user (`phonePackage`) ∩ installed by us (the installing package in `OWN_PACKAGES`, the
   server's `installed_by_launcher` test), never our own packages; pure `catalogAppsInstalledByUs` in
   `server/CatalogBattery.kt`. `isIgnoringBatteryOptimizations(pkg)` works for any package; the field is left out for
   the others and when it can't be read, as 19b does. `Option<bool>` with `serde(default, skip_serializing_if)` on
   `models.rs::InstalledApp`, kept by `cap_installed_apps` like `installer`. The app's row on the phone's Apps page
   shows a quiet "Background: unrestricted" or "Background: optimized (Android's default) - change it during a pause
   (Settings › Apps › <app> › Battery)". No button, no command. Status contract in both directories in one commit: the
   launcher's `InstalledAppReportTest` encodes `server/testdata/installed_apps_battery.json` (a byte-identical copy in
   `launcher/app/src/test/resources/`, the `power_report.json` pattern), the server's
   `intake_limits::the_battery_state_of_a_catalog_app_is_kept` posts it and both compare the copies; the root
   `CLAUDE.md`'s shared-file list and the CI path lists (`launcher.yml`, `server-ci.yml`) carry the fixture.
3. **Not in this round**: any battery-exemption prompt, on the kid's screen or elsewhere (the user's call); 19b phase
   2 for the launcher itself; anything for Play-installed apps; force-stop protection (1).

### 2.2 Tests (required)

`CatalogBatteryTest`: the set from the catalog pins (installed only, installed by us only, never ours, never a
Play-installed twin, an unchecked app out); `battery_exempt` for the set only, left out for the rest and when
unreadable; a source check that no main source calls `setUserControlDisabledPackages` and that the report computes the
set. `InstalledAppReportTest` against the shared fixture. Server: `cap_installed_apps` keeps the field and posts the
fixture, the Apps page's two lines.

### 2.3 Device checks

Emulator: install Fossify Clock from the catalog; the PWA's row says "Background: optimized" and, after the exemption
is granted during a pause, "unrestricted" at the next report; a Play-installed app and a preinstalled one have no
line; uncheck the app in the catalog - the line goes with it.

### 2.4 Implementation status (implementer B, branch `l7-b`)

- Launcher: `server/CatalogBattery.kt` (`catalogAppsInstalledByUs`, `withBatteryExempt`), computed in
  `MdmSyncWorker.collectInstalledApps` from `WindowRelock.store(..).catalogPins()`, `phonePackage` and the apps'
  reported installers. `InstalledApp.batteryExempt: Boolean?` (left out when `null`).
- Server: `models::InstalledApp.battery_exempt: Option<bool>`, kept by `cap_installed_apps` (the `..app` spread); Device
  › Apps' row gets a second `row-sub` (`devices::background_line`, `data-background`) for a reported value only.
- The force-stop protection was built first (commit `8ea5b8f0`) and removed in the code-QA fix commit (finding 1);
  `AppEnforcer.apply()` is back to what it was.
- Tests: `CatalogBatteryTest`, `InstalledAppReportTest`; server `intake_limits` (the contract's copies, the kept field,
  the two lines).
- Device checks owed: §2.3.

### 2.5 Removed (2026-10-20)

The user, seeing the phone's Apps page in the PWA, 2026-10-20: "dette ble ikke noe bra; en stor info tekst på alle
apper om å unndra de fra battery optimizations? , uansett om de trenger det eller ikke? Det burde vi ikke ha og det gir
heller ikke mening! ... La oss ikke blande oss i det! Kom med et bedre forslag eller bare ta det vekk."

Decided: taken away completely, in one commit over both directories. **Gone**: the launcher's
`server/CatalogBattery.kt` (`catalogAppsInstalledByUs`, `withBatteryExempt`) and its use in
`collectInstalledApps`, `InstalledApp.batteryExempt`, `CatalogBatteryTest`, `InstalledAppReportTest` and
`installed_apps_battery.json` (both copies, the root `CLAUDE.md` entry and the paths in `launcher.yml` and
`server-ci.yml`); the server's `InstalledApp.battery_exempt`, `devices::background_line`, `UnifiedAppRow.background`
and the Apps page's "Background:" sub-line; the server test `the_battery_state_of_a_catalog_app_is_kept`. **Kept**:
the source scan that no production source calls `setUserControlDisabledPackages` (§2.1.1; now in
`BackupServiceInvariantsTest`), and design 19b's own `battery_optimization_exempt` report and the PIN-gated Settings
row, which this section never touched. **Compatibility**: launcher 0.32.0 still sends `installed_apps[].battery_exempt`;
the server's `InstalledApp` has no `deny_unknown_fields`, so the field is ignored and never stored
(`intake_limits::an_older_launchers_battery_exempt_is_accepted_and_dropped`). Nothing else in round 7 changes; §2.1-§2.4
above are history.

## 3. Wake the screen for a notification - one switch per phone

The user, 2026-10-20: "er det en emulator ting at våre varsler ikke slår på skjermen? Eller er det by design?" ... "la
det være en global bryter i pwa? Under feks lock screen kategorien i phone settings". Builds on design 32
(`32-lock-flash.md` §7: the lock itself never gets turn-screen-on), design 17's `VoipWakeActivity` (reused, not
copied), design 25/51 (the glance rows, `GlanceStore`), design 12 (the missed-call notice).

### 3.0 Today

A notification of a ticked app or a missed call appears on the lock's glance page when the kid wakes the phone; the
screen never turns on for it (design 32b's exclusion: the lock is an ordinary activity without turn-screen-on, and
the phone's own heads-up never shows over our lock with the status bar disabled). A VoIP ring is the one thing that
wakes the screen, through `PinLockRuntime.show(wake = true)`, which starts `PinLockActivity` (REORDER_TO_FRONT), then
`VoipWakeActivity`, and clears `lockOnTop` (`lock/PinLockRuntime.kt:1094-1125`).

### 3.1 Decisions

1. **Server**: `device_policy.lock_wake_on_notification` (migration 0084, default 0), sent as
   `launcher_ui.lock_wake_on_notification: bool` (always; `policy_json_keys_snapshot` + `PolicyResponseCompatTest` in
   one commit). The Lock page's **Lock screen** group gets its own cell `id="lock-wake"` under Background, with a
   switch "Wake the screen for a notification", sub-line "For the apps ticked below and missed calls", `data-autosave`
   and a `<noscript>` Save, posting `POST /devices/{id}/lock-wake#lock-wake` (the form returns to `#lock-wake`, the
   no-jump rule, in `check_forms`; like `#lock-wallpaper`), the phone nudged, an event line like the glance ticks. The
   group's info text gains: "What the lock shows then (text included, if on below) shows to anyone near the phone."
2. **Launcher, the switch**: `LauncherUi.lockWakeOnNotification: Boolean = false` (missing = off). The runtime holds
   it in a volatile read from the cached policy's `LauncherUi` at process start and after every accepted sync (as
   `GlanceStore.refreshPolicy` does).
3. **Launcher, the wake reuses the VoIP path.** One main-thread step, `PinLockRuntime.noticeCame()`, which evaluates the
   pure `noticeWakeAllowed` and, when true, calls `show(context, WakeKind.NOTICE)`; no new activity. `PinLockRuntime.show(context, wake = true)` is generalised to a `WakeKind` (`VOIP`, `NOTICE`) so the
   lock's start and the wake's start always go together (a `VoipWakeActivity` or any wake window is **never started
   alone**: over a task that is not the lock it would light a live app on a LOCKED phone, against design 32 §7's
   "lock or black"). It accepts that `show` clears `lockOnTop`: the next screen-off runs a normal lock-up (the wake's
   own pause of the lock is interactive and clears it, harmless). For `NOTICE` the wake activity's `onPause` does **not**
   call `voipWakePaused` (17b's try-detection stays untouched). The pure
   `noticeWakeAllowed(switchOn, mode, interactive, busy, sinceLastLockUpMs, sinceLastWakeMs, wakesThisHour, near)` says so:
   the switch on, mode LOCKED, the display not interactive, not `busy` (our call, a system call, a VoIP phase, the
   emergency flow, a ringing alarm, `bootMark != Over`, the first 2 minutes after the process start), **no lock-up in
   the last 2 s** (a `lockUpAtElapsed` stamp in `lockUp`; replaces the `darkLockUp` gate, which stays true for most
   sleeps and would never open), at least `WAKE_GAP_MS` 30 s since the last wake and at most `WAKES_PER_HOUR` 20 (the
   gap and hour counters live **in memory**; a new process starts over and the 2-minute start busy covers it), and
   the phone not in a pocket: **a proximity check** (one `TYPE_PROXIMITY` read with a 300 ms budget, no permission;
   "near" -> no wake; no sensor or no answer in time -> wake). The lock underneath shows the glance page with the new
   row as today.
4. **On-time**: a notice-woken screen goes off after `NOTICE_WAKE_ON_MS` = 15 s untouched: a wake lock with that
   timeout, released early on any touch (after which the phone's normal screen timeout applies; the lock re-locks at
   screen-off as always, `ScreenOff`). A touch in a pocket still can't open anything; the PIN pad's wrong-PIN backoff
   is why the proximity check comes first.
5. **What triggers it**: **`GlanceReader.posted`** (the notification listener's callback, already on the main thread -
   not `GlanceStore.put` or `changed`, which get no post/update flag and are called for silent updates, sweeps and
   re-reads), only when **all** hold: the notice is kept (`glanceCandidate`) and the app is **not suspended** per the
   ranking (notifications of a suspended app still arrive, marked suspended); its channel alerts (`ranking.importance
   >= IMPORTANCE_DEFAULT`, since `glanceCandidate` lets LOW channels through); the notification passes Do Not Disturb
   (`ranking.matchesInterruptionFilter()`); it is not an only-alert-once update (`FLAG_ONLY_ALERT_ONCE` on a key that
   already existed); and the visible rows (`glanceRows`, after the opened marks) **gain a row** - compared before and
   after (a newer `timeMs` of an existing row is an update, not a wake). Plus the missed-call notice: a hook in
   `MissedCallNotifier` on its executor (`calls/MissedCallNotifier.kt:202-283`) after a successful `post(..., alert =
   true)` in `recomputeNow` - not its listeners (they fire only when `current` changes), not the boot re-send, `relearn`
   or `afterCall` updates (`alert = false`) - which posts `PinLockRuntime.noticeCame()` to the `main` handler, then the
   same `noticeWakeAllowed` gate. Never the music tile, the clock tile, our own notifications or anything `glanceCandidate`
   refuses. The trigger passes no text, only "a row came".
6. **Design 32 holds**: the lock activity itself never sets turn-screen-on; the only turn-screen-on activities are the
   existing `InCallActivity` and `VoipWakeActivity`. A refused start (BAL) is logged, not retried for that post.
7. **Off by default**; the override and the pause change nothing (not a restriction). A time rule that suspends the
   app is handled by the suspended check in §3.1.5, not by "no post arrives".

### 3.2 Tests (required)

`LockWakeTest` (pure): the table in §3.1.3 (every `busy` case, the 2 s lock-up gate, the gap, the hourly cap, the
switch off, not LOCKED, interactive, proximity near / far / no sensor / timeout) and the `NOTICE_WAKE_ON_MS` rule (off at
15 s untouched, early release on touch); `GlanceRowsTest`/`GlanceReader`: each condition of §3.1.5 on its own
(suspended, importance LOW, DND blocked, only-alert-once update, an update of a visible row gaining no row), a new
row triggers, a sweep/ranking/policy re-read doesn't; `MissedCallNotifier`: the alerting post triggers, the boot
re-send, `relearn` and `afterCall` don't, and the trigger reaches the main handler; `PinLockManifestTest`: **a new
assertion** pinning the turn-screen-on set to exactly `InCallActivity` and `VoipWakeActivity` (it checks only the lock
today; no new activity); a source/unit check that a `NOTICE` wake never calls `voipWakePaused`; `LockGlanceGuardTest`:
the trigger carries no text; `PolicyResponseCompatTest`: the key, missing = false. Server: migration 0084 in the order
test, the key snapshot, the switch's route landing on `#lock-wake`, the default off, the event line, `check_forms`.

### 3.3 Device checks

Emulator: switch off - an Element message with the screen off leaves it dark (today); switch on - the screen wakes
on the lock's glance page with the row, goes off about 15 s later untouched, a second message within 30 s doesn't
wake it, a missed call wakes it once; the first frame after a notice wake is the lock or black (design 32 §7), with and
without the step 2 cover, including when the phone was in an app when the screen went off; a muted-channel or DND
notification and a suspended app's notification wake nothing; during a VoIP ring or a music session nothing extra;
`adb shell dumpsys power` shows no wake lock held by us after the 15 s or after a touch. Jelly Star: in a pocket
(proximity near) nothing wakes.

### 3.4 Implementation status (implementer B, branch `l7-b`)

Built as §3.1 says, server and launcher in one commit:

- **Server**: migration `0084_lock_wake_on_notification.sql` (`device_policy.lock_wake_on_notification`, default 0;
  `migrations_73_to_84_in_order`), `LauncherUi.lock_wake_on_notification` always sent (`policy_json_keys_snapshot`
  pins the key and its `false` for a new phone). The Lock screen group's cell `#lock-wake` under Background (switch,
  sub-line, `data-autosave`, `<noscript>` Save) posts `POST /devices/{id}/lock-wake` (`lock_glance::save_wake`): back
  to `#lock-wake`, the phone nudged, a `lock_wake_changed` security-log line ("device 3: wake the screen for a
  notification: on"), 404 for an unknown phone with nothing written. The group's ⓘ gained the sentence.
- **Launcher**: `LauncherUi.lockWakeOnNotification = false`; `PinLockRuntime.noticeWakeOn` from the cached policy at
  process start (on the init's IO coroutine, never over a sync's value) and after every accepted sync. Pure
  `lock/NoticeWakePlan.kt` (`WakeKind`, `noticeWakeAllowed` with `NoticeBusy`, `wakesAfter`/`wakesInHour`,
  `proximityNear`, `noticeWakeOffDue`, the constants), `lock/NoticeProximity.kt` (the read), `badges/GlanceWake.kt`
  (`glanceAlerts`, `glanceWakes`, `glanceRowConversations`). `show(context, wake: WakeKind?)` replaces
  `wake: Boolean`; `VoipWakeActivity.intent(context, kind)` carries the kind and only a VOIP one calls
  `voipWakePaused`. Triggers: `GlanceReader.posted` -> `GlanceStore.putPosted` (the rows compared as conversation
  sets before and after the put), and `MissedCallNotifier` after a post that worked with `step.alert`, posted to its
  `main` handler.
- **Deviations**: (1) the on-time is not a wake lock: a wake lock can only keep a screen on, and the screen a
  turn-screen-on start wakes stays on for the phone's own timeout anyway (the wake counts as user activity), so the
  15 s is a main-thread timer that turns the screen off with the device owner's `DevicePolicyManager.lockNow()`
  (`force-lock` is declared) when `noticeWakeOffDue` - untouched (`PinLockActivity.onUserInteraction`), the screen
  on, LOCKED, the lock resumed, no call, none of the lock's own dialogs open (Foreldrekode, the emergency confirm:
  their touches never reach the activity) and TalkBack's touch exploration off (qa-l7-b-code 2); the timer is armed
  only when `show` started the lock (qa-l7-b-code 3); a touch, a screen-off or a screen-on more than 2 s after the
  wake (`noticeWakeEndsAtScreenOn`: the kid's own power press) ends it and the phone's timeout applies. The
  proximity read holds a 1 s partial wake lock instead. (2) The glance trigger's tests are a new `GlanceWakeTest`
  (beside `GlanceRowsTest`, so the round's other branch can't conflict); the missed-call cases are in `LockWakeTest`.
  (3) `NoticeBusy` makes the design's `busy` a value of its own, so each case is tested.
- Tests: `LockWakeTest` (the gate's table: every busy case, 2 s, the gap, the hourly cap, switch off, not LOCKED,
  interactive, proximity near/far/none; the 15 s rule and the touch; the missed-call steps and the notifier's hook;
  the glue by source), `GlanceWakeTest` (each §3.1.5 condition alone, a new row, an update of a shown row, the opened
  mark, only `posted` triggers, nothing passed), `PinLockManifestTest` (the turn-screen-on set is exactly
  `InCallActivity` and `VoipWakeActivity`; NOTICE never `voipWakePaused`); the code QA's rows (a dialog open, touch
  exploration, the screen-on rule, the timer only after a start, `glanceWakes`' suspended check alone), `LockGlanceGuardTest` (the reader's new
  identifiers reviewed), `PolicyResponseCompatTest` (the key, missing = off); server `lock_glance`, `device_pages`,
  `device_api`, `music_spotify`.
- **Device checks owed**: all of §3.3 - in particular whether `lockNow()` turns the screen off at 15 s on the
  emulator and the Jelly Star - that screen-off's lock-up is HOME_THEN_LOCK (LOCK under the keyguard cover): `show`
  cleared `lockOnTop`, so Home is brought under the lock while dark, as design 32 accepts, and the lock-up stamp's
  2 s gate follows (qa-l7-b-code 4); never with Foreldrekode open or TalkBack on -, the first frame after a
  notice wake (lock or black, both kiosk modes, from inside an app), DND/muted/suspended, a VoIP ring and music,
  `dumpsys power` after the 15 s, and the pocket on the Jelly Star.


## Revision (2026-10-20, after qa-r7-launcher-design.md)

- r7-1: §1.1.2 rewritten with the whole residual (no real time bound, the kiosk-on re-front to other allowed apps, kiosk-off Recents, any caller through unmanaged apps, noise); the automatic rule kept, a per-app budget `VOIP_RINGS_PER_HOUR` = 6 added (over budget = no ring, reported as `voip_rang[].limited`); all of §1 marked pending the user's yes (QA question 1).
- r7-2: the exclusion set is `smsPackages()` + `NagPolicy.essential` + `OWN_PACKAGES`; a duplicate/excluded test row added.
- r7-3: `voipAllowed: Set<String>?` computed once in `NotificationRuleRuntime.refresh()` from the effective allowlist, `null` when unmanaged; `voipCandidates(effective, voipAllowed, policyKnown)`; `eligible` makes no binder call and reads no raw allowlist.
- r7-4: `voip_rang` parsed leniently (`#[serde(default)]`, per-entry validation, a bad entry dropped, never the `lock_state`); `now_ms` passed to the sanitizer; the card's label from the catalog/app name else the package, times in the device's zone; the malformed-entry test added.
- r7-5: a ring is recorded only when it began LOCKED or the phone locked during it; tests added.
- r7-6: §2.1.1 follows the standing rule: lifted by the override and the pause, re-applied after; the "exception list grows" sentence dropped; §2.2/§2.3 changed to match.
- r7-7: the package set = catalog pins ∩ installed ∩ installed by us minus hidden minus `OWN_PACKAGES`; `TrackedAppUpdateState` not used; the same set carries `battery_exempt`.
- r7-8: its own try/catch after `applyHardening`; "from Android 12" dropped; hibernation/permission-reset effect is a device check, not a claim.
- r7-9: wording "Background: unrestricted" / "Background: optimized (Android's default) - change it during a pause (Settings › Apps › <app> › Battery)"; `battery_exempt` an `Option<bool>`, left out when unreadable.
- r7-10: no `LockWakeActivity`; the notice wake reuses `show(wake)` generalised to a `WakeKind`, never started alone; gate "no lock-up in the last 2 s" replaces `darkLockUp`; no `voipWakePaused` for a notice wake; design 32 §7 stays.
- r7-11: trigger moved to `GlanceReader.posted` with the five conditions (not suspended, importance >= DEFAULT, DND, not only-alert-once update, rows gain a row); the "time rule means no post arrives" claim replaced by the suspended check; tests per condition.
- r7-12: missed-call trigger is a hook in `MissedCallNotifier` on its executor at the alerting post, posted to the main thread, then the same gate; the "listener thread" wording fixed.
- r7-13: proximity check (300 ms budget) added; a woken screen goes off after `NOTICE_WAKE_ON_MS` = 15 s untouched (wake lock with timeout, released on touch) - the architect's decision on QA question 3.
- r7-14: `PinLockManifestTest` gets a new assertion for the turn-screen-on set (`InCallActivity`, `VoipWakeActivity`); gap/hour counters stated in memory; the switch read from the cached policy's `LauncherUi` at process start and after each accepted sync; the harmless `lockOnTop` clearing stated.
- r7-15: the PWA switch is its own cell `#lock-wake` with `data-autosave` and `<noscript>` Save, returning to `#lock-wake`; the info-text sentence added.
- r7-16: PLAN's three round 7 entries aligned with this revision (`voip_rang`, no "from Android 12", no `turnScreenOn` on the lock activity, the lift by override/pause, the new wording).
- 2026-10-20, after the QA (the user): the ring budget is dropped - "jeg liker ikke tanken på at man ikke kan ringe noen 7
  ganger på en time"; the residual is accepted with the reasoning in §1.1.2 (the lock is the kid's own PIN). `limited`
  is gone from `voip_rang`.
- 2026-10-20, after `qa-l7-b-code.md`: §2.1.1 (force-stop protection with `setUserControlDisabledPackages`) dropped -
  finding 1: a protected package can't be suspended, hidden or uninstalled by the device owner either; §2 is now the
  battery state in the report only (`catalogAppsInstalledByUs`). §3: the 15 s never under a lock dialog or with touch
  exploration on (2), armed only after a start and ended by a later screen-on (3), §3.4's device check expects
  HOME_THEN_LOCK (4), a test for `glanceWakes`' suspended check (5), the switch logs only a change and the proximity
  read cancels its timeout (6).

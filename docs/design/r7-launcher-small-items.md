# Launcher round 7 - small items (proposal, revised after `qa-r7-launcher-design.md`)

Asked 2026-10-20. Each item is its own section with its own tests; they ship in one launcher round after 0.31.0.
§1 any allowed app may ring over the lock (pending the user's yes to the residual, QA question 1); §2 catalog apps protected from force-stop and hibernation (no battery-exemption flow,
the user's call); §3 the global wake-on-notification switch.

## 1. Any allowed app may ring over the lock

**Pending the user's yes to the residual (QA question 1, §1.1.2 below).** Nothing in §1 is built before it.

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
   calls managed and on, or unmanaged; nothing while calls are off or `UnknownFailClosed` - and, when they pass,
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
     and `MODE_IN_COMMUNICATION`, which any app may set). **The budget below bounds how often it can restart, not how
     long one call lasts.**
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
   - **The bound we add: a ring budget.** `VOIP_RINGS_PER_HOUR` = 6 accepted rings per package per rolling hour
     (a pure function in `VoipCallPlan.kt`, counted in memory from the accepted-ring times). A ring over the budget is
     treated as **no ring**: the notification stays in the shade, no wake, no card, the lock stays; the in-call phase
     for a call the kid answered elsewhere is unaffected. It is reported as `voip_rang[].limited: true` (§1.1.3). The
     residual above is what remains inside the budget.
3. **The parent sees which apps rang.** `lock_state.voip_rang`: `[{"package": "<pkg>", "last_at_ms": <wall ms>,
   "limited": <bool>}]`, the packages whose ring the lock accepted (phase RINGING began) in the last 7 days, newest
   first, at most 8, valid package names only; always sent (empty list); `limited` is true when a ring of that package
   was refused by the budget since its last accepted one. **A ring is recorded only when it began while the lock mode
   was LOCKED, or when the phone locked during it** (`VoipCalls.evaluate` builds records in every mode, so a call taken
   on an unlocked phone is not "over the lock"). Kept in `VoipCalls`' CE prefs `voip_call` (`rang_v1`, package -> wall
   ms, pruned at each write and each report; nothing but package names and times). Server: `kid_lock::LockState` gets
   `voip_rang: Vec<serde_json::Value>` with `#[serde(default)]` (missing = empty); `sanitize_lock_state` validates
   **each entry** (`valid_package_name`, an integer `last_at_ms`, a future time dropped, `limited` a bool else false),
   dedupes by package, newest first, `take(MAX_FSI_DENIED)`; **a bad entry is dropped, never the whole `lock_state`**
   (the lock-off warnings stay). The sanitizer takes `now_ms` (its two callers pass it) for the future-time rule. The
   Lock Screen card shows an info note per package "Calls from <label> rang over the lock · <when>"
   (`LockNote::RangOverLock`; "... · the hourly limit stopped further rings" when `limited`; a quiet line, not a
   warning). The label is the catalog/app-display name for the package, else the package; the time is in the device's
   reported zone (`time_state.zone`), like the other device-page times. Contract: `StatusReportRequest.LockStateReport
   .voipRang` (`LockStateReport` is built in `PinLockRuntime` only) and the server's `step10` test, one commit for
   both directories.
4. **Unchanged**: the reader reads no text (`VoipCallGuardTest`), the in-call phase's rule (an app without a
   call-category foreground notification gets the lock back after the grace, its call going on in the background -
   a known limit, noted for the device checks), the kiosk-off lock-task list (the ringing package is pinned as
   today), `voip_fsi_denied`, the ring card and 17b's FSI-first send.

### 1.2 Tests (required)

`VoipCallPlanTest`: the new `voipCandidates` table - managed and on with `voipAllowed` (every allowed app), managed and
off / unknown (none), unmanaged with an allowlist (the allowlist), the two fallbacks (`policyKnown == false`,
`voipAllowed == null`), and that `voipAllowed` never widens the gates; a duplicate or excluded package stays out
(SMS packages, an essential/dialer package, our family); the budget table (6 in the hour pass, the 7th is limited, the
oldest ageing out of the rolling hour frees one, per package). `NotificationRuleRuntime`: `voipAllowed` from the
effective allowlist (a relock-blocked package is out), `null` when apps are unmanaged. `VoipCallGuardTest` unchanged and
still green (no new read of text). `VoipCalls`: `voip_rang`'s prune (7 days, 8 entries, newest first), a ring of a
non-candidate never recorded, a ring while unlocked never recorded, a ring during which the phone locks recorded, a
limited ring recorded as `limited` and doing nothing else; the status field's encoding in `LockStateReport`. Server
`kid_lock`: `voip_rang` sanitized (package names, cap, times, future time with the passed `now_ms`, `limited`), a
malformed entry leaves the rest of `lock_state` intact (`step10.rs::lock_state_is_stored_sanitized_and_capped`), the
card's note and its label/zone; `step10`'s lock-state round trip with the field. `PolicyResponseCompatTest` untouched
(no policy change).

### 1.3 Device checks

Emulator (§6 of `docs/testing/emulator.md`, the Element X flow of design 17 as the regression): Element still rings
over the lock in both kiosk modes; with Element removed from the contacts' message apps but kept on the allowlist it
still rings (the new rule); with Element off the allowlist it doesn't. A seventh ring inside an hour from the same app
does nothing and the card says the limit stopped it. With the kiosk off: whether Recents reaches other unsuspended apps
during a ring that began unlocked and then locked (the QA's open point). With the system dialer allowlisted and calls
unmanaged, a cellular ring is not a VoIP ring. Jelly Star with Signal: a Signal call rings over the lock and the
device page shows "Calls from Signal rang over the lock · today 14:02".

## 2. Catalog apps protected from force-stop

The user, 2026-10-20: "er det ikke noe vi kan fikse på for å sikre at vi kan installere apper direkte på telefonen, og
samtidig live gi de batteriunntak?" - and, on the proposal to open Android's battery dialog for the kid through a
time-boxed Settings window: "Nei vil ikke auto åpne settings, da er det bedre at vi ikke legger inn noe spesielt rundt
batteriunntaket. Da får folk styre det selv." So: **no battery-exemption flow at all** - no sheet on the kid's screen,
no Settings window, no `battery_ask` command. The exemption stays what design 19b made it: the parent grants it by
hand during a pause (Settings › Apps › the app › Battery). Builds on the enforcement pass (`AppEnforcer.apply`) and
`WindowRelock`'s catalog pins.

### 2.0 Today

A catalog app the launcher installs is an ordinary user-installed app: it can be force-stopped where a Settings
surface is reachable (a pause; an OEM battery manager), and Android may hibernate it after months unused and reset
its permissions. Fossify Clock's alarms survive all of it (`setAlarmClock`); its running timers and a messenger's
background work don't survive a force-stop.

### 2.1 Decisions

1. **Force-stop protection, plain code, lifted like every restriction.** Every `apply()` sets
   `setUserControlDisabledPackages(admin, pkgs)` to the **package set** = the stored catalog pins (`WindowRelock`'s
   catalog pins, package + signer, `rememberCatalogPins`: the current checked list) ∩ installed for this user
   (`phonePackage`) ∩ installed by us (the installing package, `InstallSourceInfo.installingPackageName`, is in
   `OWN_PACKAGES`; the same test as the server's `installed_by_launcher`) minus the packages enforcement hides
   (`plan.hide`) minus `OWN_PACKAGES`. `TrackedAppUpdateState` is **not** used (it is keyed by the catalog id, holds no
   package, and is never pruned when an app is unchecked). A Play-installed app the catalog also names is out by the
   installer test; an unchecked app is out by leaving the pins. Pure `userControlDisabledPackages(catalog, installed,
   installer, hidden, own, overrideActive)` in a new `server/UserControlPlan.kt`; applied **after `applyHardening`,
   next to `NotificationRuleRuntime.refresh`, in its own try/catch** (logged, never thrown out of `apply()`: a throw
   or an OEM `SecurityException` must never skip the PIN lock, the VPN or the hardening); only while `hardeningManaged`.
   **Lifted by the override and the pause like every other restriction** (the standing rule, no exception: the set is
   empty while `overrideActive`, and the parent can force-stop or clear data during a pause as before - Force stop and
   Clear storage are the first fixes for a stuck app); re-applied when the window ends; cleared (an empty list) when
   unmanaged. Logged once per change. `LastEnforcedPlan` doesn't carry it (recomputed). Whether this also keeps the app
   out of app hibernation and the unused-app permission reset is **not claimed**; it is a device check (§2.3).
2. **The parent sees the battery state, nothing more.** `installed_apps[]` gets `battery_exempt: true|false` for the
   same package set (`isIgnoringBatteryOptimizations(pkg)` works for any package; the field is left out for the others
   and when it can't be read, as 19b does), `Option<bool>` with `serde(default, skip_serializing_if)` on
   `models.rs::InstalledApp`, kept by `cap_installed_apps` like `installer`. The app's row on the phone's Apps page
   shows a quiet "Background: unrestricted" or "Background: optimized (Android's default) - change it during a pause
   (Settings › Apps › <app> › Battery)". No button, no command. Status contract in both directories in one commit.
3. **Not in this round**: any battery-exemption prompt, on the kid's screen or elsewhere (the user's call); 19b phase
   2 for the launcher itself; anything for Play-installed apps.

### 2.2 Tests (required)

`UserControlPlanTest`: the set from the catalog pins (installed only, installed by us only, never ours, never hidden,
never a Play-installed twin, an unchecked app out), empty while `overrideActive` and in a pause, cleared when
unmanaged; a source check that `setUserControlDisabledPackages` is called in `apply()` only, after `applyHardening`,
inside its own try/catch. The report: `battery_exempt` for the package set only, left out for the rest and when
unreadable. Server: `cap_installed_apps` keeps the field, the Apps page's two lines.

### 2.3 Device checks

Emulator: install Fossify Clock from the catalog; outside a pause `adb shell am force-stop` on it is refused and
Settings › Apps › Fossify Clock shows "Force stop" greyed out; during a pause (or the override) Force stop works again
and is greyed out again when the window ends; the PWA's row says "Background: optimized" and, after the exemption is
granted during a pause, "unrestricted" at the next report; uncheck the app in the catalog - the protection is gone with
it. Whether the app is also kept out of hibernation / the permission reset (`adb shell dumpsys app_hibernation`, or
the "Pause app activity if unused" row in a pause).

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
3. **Launcher, the wake reuses the VoIP path.** One main-thread step, `PinLockRuntime.wakeForNotice()`; no new
   activity. `PinLockRuntime.show(context, wake = true)` is generalised to a `WakeKind` (`VOIP`, `NOTICE`) so the
   lock's start and the wake's start always go together (a `VoipWakeActivity` or any wake window is **never started
   alone**: over a task that is not the lock it would light a live app on a LOCKED phone, against design 32 §7's
   "lock or black"). It accepts that `show` clears `lockOnTop`: the next screen-off runs a normal lock-up (the wake's
   own pause of the lock is interactive and clears it, harmless). For `NOTICE` the wake activity's `onPause` does **not**
   call `voipWakePaused` (17b's try-detection stays untouched). The pure
   `wakeForNotice(switchOn, mode, interactive, busy, sinceLastLockUpMs, sinceLastWakeMs, wakesThisHour, near)` says so:
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
   same `wakeForNotice` gate. Never the music tile, the clock tile, our own notifications or anything `glanceCandidate`
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

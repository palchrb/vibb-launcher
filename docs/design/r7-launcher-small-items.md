# Launcher round 7 - small items (proposal)

Asked 2026-10-20. Each item is its own section with its own tests; they ship in one launcher round after 0.31.0.
§1 any allowed app may ring over the lock; §2 catalog apps protected from force-stop and hibernation (no battery-exemption flow,
the user's call); §3 the global wake-on-notification switch.

## 1. Any allowed app may ring over the lock

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

1. **The candidate set is every allowed app.** `voipCandidates(effective, allowed, exclude)` keeps its gates - calls
   managed and on, or unmanaged; nothing while calls are off or `UnknownFailClosed` - and, when they pass, answers
   `allowed` minus `exclude` instead of a fixed list. `allowed` is the enforced policy's effective allowlist as
   `NotificationRuleRuntime.policy.allowed` already computes it in `apply()` (the allowlist plus the contacts'
   messaging apps plus a time lock's usable apps - the last matters not: a suspended app never rings anyway).
   `exclude` is the SMS app (today's rule: Google Messages posts call-category notifications for RCS video calls that
   our rules never saw) and our own package. **Fallbacks**: no enforced policy yet in this process
   (`NotificationRuleRuntime.policy == null`) or `allowlist == null` (apps unmanaged) -> today's sets (the messaging
   apps, or `KNOWN_VOIP_PACKAGES`), so a phone before its first apply and a calls-only phone behave as before.
2. **What the kid can do with it, accepted**: an allowed app that posts a call-style ring gets its own screen over
   the PIN lock for that ring (and, if it keeps a call-category foreground notification with audio in communication,
   for the call). The app is the parent's choice, the kiosk keeps every other app locked (the exemption pins that one
   package), and the 2-minute ring limit, the 15 s grace, the 3 h cap and "another call, the emergency flow and an
   alarm always win" stay. A kid with a "fake call" app gets into that app without the PIN - visible to the parent
   through §1.1.3.
3. **The parent sees which apps rang.** `lock_state.voip_rang`: `[{"package": "<pkg>", "last_at_ms": <wall ms>}]`,
   the packages whose ring the lock accepted (phase RINGING began) in the last 7 days, newest first, at most 8, valid
   package names only; always sent (empty list). Kept in `VoipCalls`' CE prefs `voip_call` (`rang_v1`, package ->
   wall ms, pruned at each write and each report; nothing but package names and times). Server: `kid_lock::LockState`
   gets `voip_rang` (sanitized like `voip_fsi_denied`: `valid_package_name`, capped `MAX_FSI_DENIED`, a non-numeric
   or future time dropped), the Lock Screen card shows an info note per package "Calls from <label> rang over the
   lock · <when>" (`LockNote::RangOverLock`, the app's catalog name or the package; a quiet line, not a warning).
   Contract: `StatusReportRequest.LockStateReport.voipRang` (`LockStateReport` is built in `PinLockRuntime` only) and
   the server's `step10` test, one commit for both directories.
4. **Unchanged**: the reader reads no text (`VoipCallGuardTest`), the in-call phase's rule (an app without a
   call-category foreground notification gets the lock back after the grace, its call going on in the background -
   a known limit, noted for the device checks), the kiosk-off lock-task list (the ringing package is pinned as
   today), `voip_fsi_denied`, the ring card and 17b's FSI-first send.

### 1.2 Tests (required)

`VoipCallPlanTest`: the new `voipCandidates` table - managed and on with an allowlist (every allowed app, the SMS
app and our package out), managed and off / unknown (none), unmanaged with an allowlist (the allowlist), the two
fallbacks (no policy yet, `allowlist == null`), and that `allowed` never widens the gates. `VoipCallGuardTest`
unchanged and still green (no new read of text). `VoipCalls`: `voip_rang`'s prune (7 days, 8 entries, newest
first), a ring of a non-candidate never recorded; the status field's encoding in `LockStateReport`. Server
`kid_lock`: `voip_rang` sanitized (package names, cap, times), the card's note; `step10`'s lock-state round trip
with the field. `PolicyResponseCompatTest` untouched (no policy change).

### 1.3 Device checks

Emulator (§6 of `docs/testing/emulator.md`, the Element X flow of design 17 as the regression): Element still rings
over the lock in both kiosk modes; with Element removed from the contacts' message apps but kept on the allowlist it
still rings (the new rule); with Element off the allowlist it doesn't. Jelly Star with Signal: a Signal call rings
over the lock and the device page shows "Calls from Signal rang over the lock · today 14:02".

## 2. Catalog apps protected from force-stop and hibernation

The user, 2026-10-20: "er det ikke noe vi kan fikse på for å sikre at vi kan installere apper direkte på telefonen, og
samtidig live gi de batteriunntak?" - and, on the proposal to open Android's battery dialog for the kid through a
time-boxed Settings window: "Nei vil ikke auto åpne settings, da er det bedre at vi ikke legger inn noe spesielt rundt
batteriunntaket. Da får folk styre det selv." So: **no battery-exemption flow at all** - no sheet on the kid's screen,
no Settings window, no `battery_ask` command. The exemption stays what design 19b made it: the parent grants it by
hand during a pause (Settings › Apps › the app › Battery). Builds on design 13 (`TrackedAppUpdateState`), the
enforcement pass (`AppEnforcer.apply`).

### 2.0 Today

A catalog app the launcher installs is an ordinary user-installed app: it can be force-stopped where a Settings
surface is reachable (a pause; an OEM battery manager), and Android 12+ hibernates it after months unused and resets
its permissions. Fossify Clock's alarms survive all of it (`setAlarmClock`); its running timers and a messenger's
background work don't survive a force-stop.

### 2.1 Decisions

1. **Force-stop and hibernation protection, plain code.** Every `apply()` sets
   `setUserControlDisabledPackages(admin, pkgs)` to the catalog apps the launcher installed and that are installed
   now (`TrackedAppUpdateState`'s installed records whose package Android has - `phonePackage` -, the music app among
   them when it is one; never our own package, never a Play-installed app the catalog also names, never a package
   enforcement hides). Pure `userControlDisabledPackages(records, installed, hidden)` in a new
   `server/UserControlPlan.kt`; applied right after the suspension pass, only while `hardeningManaged`; cleared (an
   empty list) when unmanaged - the standing rule's exception list grows by it, like hardening: the override and the
   pause don't lift it (it blocks nothing the kid does; it protects the parent's apps). From Android 12 this also
   keeps the app out of app hibernation and the unused-app permission reset. Logged once per change.
   `LastEnforcedPlan` doesn't carry it (recomputed from the records).
2. **The parent sees the battery state, nothing more.** `installed_apps[]` gets `battery_exempt: true|false` for the
   catalog apps (`isIgnoringBatteryOptimizations(pkg)` works for any package; left out for the others), kept by
   `cap_installed_apps` like `installer`; the app's row on the phone's Apps page shows a quiet "Background: exempt" or
   "Background: restricted - grant it during a pause (Settings › Apps › <app> › Battery)" (design 19b's words). No
   button, no command. Status contract in both directories in one commit.
3. **Not in this round**: any battery-exemption prompt, on the kid's screen or elsewhere (the user's call); 19b phase
   2 for the launcher itself; anything for Play-installed apps.

### 2.2 Tests (required)

`UserControlPlanTest`: the set from the records (installed only, never ours, never hidden, never a Play-installed
twin), cleared when unmanaged, kept through the override and the pause; a source check that
`setUserControlDisabledPackages` is called in `apply()` only. The report: `battery_exempt` for catalog apps only,
left out for the rest. Server: `cap_installed_apps` keeps the field, the Apps page's two lines.

### 2.3 Device checks

Emulator: install Fossify Clock from the catalog; during a pause Settings › Apps › Fossify Clock shows "Force stop"
greyed out and the PWA's row says "Background: restricted"; grant the exemption there and the row says "exempt" after
the next report; uncheck the app in the catalog - the protection is gone with it.

## 3. Wake the screen for a notification - one switch per phone

The user, 2026-10-20: "er det en emulator ting at våre varsler ikke slår på skjermen? Eller er det by design?" ... "la
det være en global bryter i pwa? Under feks lock screen kategorien i phone settings". Builds on design 32
(`32-lock-flash.md` §7: the lock itself never gets turn-screen-on), design 17's `VoipWakeActivity` (the pattern), design
25/51 (the glance rows, `GlanceStore`), design 12 (the missed-call notice).

### 3.0 Today

A notification of a ticked app or a missed call appears on the lock's glance page when the kid wakes the phone; the
screen never turns on for it (design 32b's exclusion: the lock is an ordinary activity without turn-screen-on, and
the phone's own heads-up never shows over our lock with the status bar disabled). A VoIP ring is the one thing that
wakes the screen, through `VoipWakeActivity`.

### 3.1 Decisions

1. **Server**: `device_policy.lock_wake_on_notification` (migration 0084, default 0), sent as
   `launcher_ui.lock_wake_on_notification: bool` (always; `policy_json_keys_snapshot` + `PolicyResponseCompatTest` in
   one commit). The Lock page's **Lock screen** group gets a switch row under Background: "Wake the screen for a
   notification", sub-line "For the apps ticked below and missed calls", `POST /devices/{id}/lock-wake` back to
   `#lock-screen` (the no-jump rule, `check_forms`), the phone nudged, an event line like the glance ticks.
2. **Launcher**: `LauncherUi.lockWakeOnNotification: Boolean = false` (missing = off). A new **`LockWakeActivity`** -
   `VoipWakeActivity`'s twin (manifest `turnScreenOn`, `taskAffinity` .pinlock, translucent, finishes after 500 ms;
   `PinLockManifestTest` pins exactly these turn-screen-on activities) - is started by `PinLockRuntime.wakeForNotice`
   when the pure `wakeForNotice(switchOn, mode, interactive, busy, sinceLastWakeMs, wakesThisHour)` says so: the switch
   on, mode LOCKED, the display not interactive, not `busy` (our call, a system call, a VoIP phase, the emergency
   flow, a ringing alarm, the boot mark, the first 2 minutes after the process start), at least `WAKE_GAP_MS` 30 s
   since the last wake and at most `WAKES_PER_HOUR` 20 (a chatty group never keeps the screen on). The lock underneath
   shows the glance page with the new row as today; the screen goes off by the phone's timeout and locks again
   (`ScreenOff`) - nothing new there.
3. **What triggers it**: a **new post** of a ticked app's conversation (`GlanceStore.put` with a `postTimeMs` newer
   than the conversation's last, from the listener's `onNotificationPosted`, never a ranking update, a sweep, a
   re-read after a policy change or an `update` of the same post) and the missed-call notice's post that alerts
   (`MissedCallNotifier`'s alerting post, design 12 - not its boot re-send). Never the music tile, the clock tile, our
   own notifications or anything `glanceCandidate` refuses. The trigger passes no text, only "a row came".
4. **Design 32 holds**: the lock activity itself never sets turn-screen-on; the wake activity is started only from
   the runtime's main-thread step with the lock already the top task (as the VoIP wake is; `lockOnTop`), never from
   the listener thread directly, and never while `darkLockUp`'s HOME_THEN_LOCK is in flight (it waits for the lock's
   next pause/resume, then re-checks). A refused start (BAL) is logged, not retried for that post.
5. **Off by default**; the override and the pause change nothing (not a restriction); a time rule that suspends the
   app means no post arrives, so no wake.

### 3.2 Tests (required)

`LockWakeTest` (pure): the table in §3.1.2 (every `busy` case, the gap, the hourly cap, the switch off, not LOCKED,
interactive); `GlanceRowsTest`/`GlanceStore`: a new post triggers, an update/sweep/ranking/policy re-read doesn't; the
missed-call alerting post triggers, the boot re-send doesn't; `PinLockManifestTest`: the turn-screen-on set is
exactly `InCallActivity`, `VoipWakeActivity`, `LockWakeActivity`; `LockGlanceGuardTest`: the trigger carries no text;
`PolicyResponseCompatTest`: the key, missing = false. Server: migration 0084 in the order test, the key snapshot, the
switch's route landing on `#lock-screen`, the default off, the event line.

### 3.3 Device checks

Emulator: switch off - an Element message with the screen off leaves it dark (today); switch on - the screen wakes
on the lock's glance page with the row, goes off again by the timeout, a second message within 30 s doesn't wake it,
a missed call wakes it once; during a VoIP ring or a music session nothing extra; `adb shell dumpsys power` shows no
wake lock held by us after the 500 ms.

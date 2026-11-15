# Code QA: Snarveier, the kid's quick panel in the shade (design 99)

Checked against `design-99` at c13ddfca1, the diff `86b5cd6d0..c13ddfca1` (86b5cd6d0 is `main` merged in). Read: root
`CLAUDE.md`, `launcher/CLAUDE.md`, the design (revision 2, with §11, §13, §14), `docs/review/qa-99-design.md`,
`docs/testing/emulator.md` §6an, design 98 §2.2. Code read in full: `shortcuts/` (model, runtime, service, receiver,
notification, `ShortcutAirplaneActivity`), `ui/kidsettings/ShortcutsPickerActivity.kt` and Kid Settings' row, the two
`RemoteViews` layouts and the picker's, the manifest, `Application.kt`'s tail and `onConfigurationChanged`,
`AnchorNotice`, the Home tile removal; and what the code leans on: `assistant/AirplaneMode.kt` (`airplaneRow`),
`AirplaneRoute.turnOn`/`turnOff`, `AirplaneRuntime` (the exported edge receiver and why), `QuickControls`,
`SoundMode.kt`, `MobileNetworkRuntime.kidRowNow`/`setKidOff`, `MusicSessions` (`reading`, `usable`, `playPause`),
`LockSessionPick.lockAppUsable`, `TimeAskReceiver`, `PinLockRuntime` (`mode`, `decided`), `LauncherLocales`. Server:
`shortcuts.rs`, migration 0148, `devices.rs`'s `update_policy` (`kept_bits`), `device_api.rs`'s intake,
`device_pages.rs` (Home and About), `device_home.html`, both i18n files, `tests/shortcuts.rs`, `intake_limits`. The
contract in both directories and both workflows' path filters.

**Verdict: the lock-safety core is sound.** Every tap - the receiver's and the Flymodus window's - goes through one
`shortcutTapAllowed`, built from a model computed at the tap from the cached policy (so the parent's bit is the one in
force then), and it fails closed: an unknown key, `PinLockRuntime.decided` false or a time-rule snapshot that throws,
LOCKED, the keyguard up or unreadable (`keyguard != false`), the panel not shown, the item absent, busy or dimmed are all
refused. Flymodus turns on only from `ShortcutAirplaneActivity`'s first focus (`act`'s `window` branch; a broadcast for
On is ignored), and `AirplaneRoute.turnOn` keeps design 98's own plan (role, restriction, emergency incl. ECBM and the
10 minutes, cool-down). The activity, service and receiver are not exported; every `PendingIntent` is explicit and
`FLAG_IMMUTABLE`; the activity is `excludeFromRecents`, `noHistory`, its own task, and finishes on the route's return or
after 3 s without focus. Musikk drives only `MusicTrust`'s Vibb music (`reading.session`/`controller` are Vibb music's
alone) and is gated by `isPackageSuspended`, which is exactly what `lockAppUsable` returns for Vibb music. The service
follows Q99-L4 (one starter, only `stopSelf`, `START_NOT_STICKY`, `startForeground` first), uses `specialUse` with its
subtype property and `FOREGROUND_SERVICE_SPECIAL_USE`, and `FOREGROUND_SERVICE_IMMEDIATE`. The process receiver's
actions all come from system_server (Bluetooth's `ACTION_STATE_CHANGED` too, from `BluetoothManagerService`), so the
`RECEIVER_NOT_EXPORTED` registration hears them; airplane's emergency-call edge comes through `AirplaneRuntime`'s own
exported receiver and listener.

Server: 0148 is the next number (`main` ends at 0147), sets bit 128 on every `device_policy` row; `update_policy` keeps
128 without `shortcuts_row` for `section=quick` and the whole form alike; `clean` matches every contract row; the row
autosaves through `#quick-controls`; nb + en complete; the PWA names the kid, no pronoun; no new page, so no bell.
`shortcuts.json` is byte-identical in both directories, both tests compare the copies and read every row, both path
filters list it, no policy key changed. Home's tile, its listener and `Torch.state` in `renderGrid` are gone; the lock
glance's torch (`GlancePanel`), `TorchRules`, `TorchOffReceiver` and the 10-minute auto-off are untouched. The picker
caps at four, has up/down buttons (no drag) and pads by the bar inset. "Spotify" appears nowhere the kid sees it.

Counts: **0 High, 1 Medium, 5 Low.**

## Findings

### C99-M1 Medium - a dimmed or busy Flymodus still opens the window: the tap closes the shade (D10, §3.3)

`ShortcutsNotification.kt:166-174`: the cell's intent is the activity whenever `item.key == AIRPLANE && !item.on`,
whatever `usable` and `busy` say. `ShortcutsModel.kt:122-123` makes Flymodus dimmed with airplane mode off in the row's
RESTRICTED (the parent's "Blokker flymodus"), EMERGENCY (ECBM or an emergency call in the last 10 minutes) and COOLDOWN
states, and busy in BUSY_ON - all with `on = false`.

Scenario: the parent has set "Blokker flymodus". Emma pulls the shade, sees Flymodus dimmed, taps it. Android collapses
the shade (a notification started an activity), `ShortcutAirplaneActivity` takes focus over the app in front (that app
is paused - a video that pauses on `onPause` stops), the check refuses (`DIMMED`, `ShortcutsRuntime.kt:408-412`) and the
window finishes. Nothing is weakened, but D10 is "a dimmed tap does nothing" and §3.3 "taps ignored": here a dimmed tap
closes the shade and interrupts the app - a visible cost the user didn't approve. Every other dimmed item is a broadcast,
refused with the shade left open.

Fix: take the activity only for an item that can act: `item.key == AIRPLANE && !item.on && item.usable && !item.busy`;
a dimmed or busy Flymodus gets the broadcast like the rest (refused by the check, the shade stays). Extend the source pin
in `ShortcutsTest` to say so.

### C99-L1 Low - the panel and its channel don't follow the launcher's own language

`ShortcutsRuntime.kt:123-125` listens for `ACTION_LOCALE_CHANGED` and `ACTION_CONFIGURATION_CHANGED`; the launcher's
language is a per-app locale (`LauncherLocales.ask`, `LocaleManager.applicationLocales`), which reaches the process as a
configuration change only - no broadcast (the project measured this: `Application.kt:258-279`, design 78 L3, which
renames the other channels from `onConfigurationChanged`). So when the parent changes the phone's language in the PWA,
the `apply()` refresh runs before `LauncherLocales` applies it (at Home's resume), and the panel keeps the old names and
"Snarveier"/"Shortcuts" subtext until some unrelated signal reposts (the `locale` key at `:247-249` then differs). The
channel itself is created once (`ensureChannel`, `:354-365`, returns when it exists) and is never renamed, so a channel
first created in the phone's language (design 78 L3's boot case) keeps that name in the long-press menu for good.

Fix: from `Application.onConfigurationChanged`, call `ShortcutsRuntime.requestRefresh()` and re-create the current
Snarveier channel with the new name (as `createNotificationChannels` does for the others; re-creating only renames).

### C99-L2 Low - the service's first notification can be stale (`posted` names a panel that wasn't posted)

`ShortcutsRuntime.kt:236-243`: `notificationForStart` builds from `panel` on the main thread, then queues
`posted = Triple(panel, ...)` on the refresh thread, reading `panel` again. `ShortcutsService.instance` is set in
`onCreate`, before `onStartCommand`, so a refresh in between sees a running service and `notify`s its newer model P2
(`:188-189`, `posted` = P2). If `startForeground` with the older P1 lands after that notify, the shade shows P1 while the
queued block records P2 as posted; every later refresh that computes P2 skips the post (`:224`), so the panel stays wrong
until the next real change.

Fix: capture the panel once - `val shown = panel; build(..., shown)` and `posted = Triple(shown, ...)`.

### C99-L3 Low - "None of the switches below is on" when the kid chose none

`ShortcutsPickerActivity` lets the kid uncheck all four (`togglePick` removes freely); the picks are then `[]`, the panel
has no items and is not shown, and the report says `{shown: false, hidden: false, items: []}`. `shortcuts.rs:184-186`
renders that, with bit 128 on, as `device.home.shortcuts_none` ("None of the switches below is on." /
"Ingen av bryterne under er slått på.", `en.json:4558`) - wrong: the switches are on, Emma chose nothing (Kid Settings
itself says "Ingen valgt"). §5.2's "nothing offered" case and this one can't be told apart from today's report.

Fix: a wording true in both cases ("Ingen snarveier vises nå." / "No shortcuts are shown now."), or, better, a
`chosen` count in the report (a contract change in both directories) and Emma's own line for zero.

### C99-L4 Low - Vibb music's sessions are watched on every phone with the default picks (Q99-L3)

`ShortcutsRuntime.kt:320-325`: `want = panel.shown && MUSIC in shortcutPicks(storedPicks(app))`. With the picks `null`
(every phone until the kid edits), `shortcutPicks` returns all nine defaults, MUSIC among them, so `MusicSessions` is
watched whenever the panel shows - although the default panel is Lommelykt, Lyd, Flymodus, Wi-Fi. §2.3 and Q99-L3 say
"while the panel holds Musikk". A session watch for nothing, process-wide, for the phone's life.

Fix: `want = panel.shown && panel.items.any { it.key == ShortcutKey.MUSIC }` (the item exists whenever the app is
installed, session or not, so the watch still starts before a session does).

### C99-L5 Low - Home's tile strings are left dangling

`torch_title` and `torch_state_on`/`torch_state_off` (`values/strings.xml:117-120`, `values-nb/strings.xml:102-105`) have
no reader left in `main/` or the tests: the lock glance uses `lock_torch`/`lock_torch_on`/`lock_torch_off`. §7 kept them
as if the lock used them, and their comment still says "Home's tile and the lock's button". (`kid_torch_tile` is still
read through `kid_lock_alarm_row` - keep it.)

Fix: remove the three strings in both languages, or keep them with a comment saying nothing reads them.

## Checked and fine (not findings)

- **Taps queued behind the route.** The route runs on the one tap thread; a broadcast tap behind it holds `goAsync` for
  at most the route's bounded waits (report 5 s, window, off 3 s), well under the background broadcast timeout, and is
  then judged against the state after the route, never the one drawn.
- **A second Flymodus tap** while the window lives brings its task to the front (NEW_TASK, same component, no new
  instance); no second route.
- **Off-only and the parent's block.** `airplaneRow` returns OFF_ONLY before RESTRICTED, and `offUnderMonitor` does
  nothing while the parent's restriction holds - the same as Kid Settings and the rule sheet.
- **Sound, Mobildata, Mer tid** go through the existing guarded paths (`soundTapMode` refuses under any DND,
  `kidRowNow`'s PARENT/PHONE_OFF dim, `TimeRequests.ask` exactly as `TimeAskReceiver`).
- **`notify` racing a stop** reposts a plain notification at worst; `onDestroy` -> `serviceGone` -> a new start
  replaces it with the foreground one.
- **The report** is a full-report field only (not in `status_heartbeat_fields`), in `REPORT_KEYS`, kept by
  `intake_limits`; the server shows the row and the About line only with `shortcuts_v1`.

## Fixes (2026-11-15, branch `design-99`)

With the user's change of the same day (design 99 revision 3, after the emulator run's screenshots: *"Må vi ha navn
under logoen? Det har ikke ios"*): **no names under the buttons.** One layout, `shortcuts_row.xml`, is both the
content and the big content view, so folded and unfolded show the same row; the discs grew from 36 to 40 dp (glyphs 20
-> 22 dp). `shortcuts_names.xml`, `shortcutNameSp` and its measuring, the short names `shortcut_name_*` (nb + en) and
their test are gone; the names stay in the picker and as each cell's content description (`shortcut_full_*`). The post
key's font scale and width went with the names (the discs are in dp), and so did the `ACTION_CONFIGURATION_CHANGED`
registration. The header's small icon is the Vibb mark, `ic_stat_vibb` (white rings and core, made by
`launcher/scripts/vibb-icon.py` from the same source as the app icon; NOTICE.md names it), not the gear that drew a
blank disc.

| Finding | Fix |
|---|---|
| C99-M1 | Pure `shortcutOpensWindow(item)`: the activity only for Flymodus off, usable and not busy; a dimmed or busy Flymodus is a broadcast like the rest, refused by the check with the shade left open. `ShortcutsTest` runs every non-clickable row state through the model and pins the notification's use of it |
| C99-L1 | `Application.onConfigurationChanged`, after the other channels are named again, calls `ShortcutsRuntime.languageChanged`: the current Snarveier channel is created again (a rename; a channel the kid turned off stays off - Android never raises a user-set importance) and a refresh posts the panel in the new language (the post key's language differs) |
| C99-L2 | `notificationForStart` reads `panel`, the channel and the language once; the queued `posted` records exactly what was built |
| C99-L3 | No contract change: `device.home.shortcuts_none` is now "Ingen snarveier vises nå." / "No shortcuts are shown now.", true both when nothing is offered and when the kid chose none (the report can't tell them apart). `tests::shortcuts` and `docs/notes/server/pwa.md` follow; the row's save path is unchanged (`#quick-controls`, no jump) |
| C99-L4 | `watchMusic` wants `panel.shown && panel.items.any { it.key == MUSIC }` |
| C99-L5 | `torch_title`, `torch_state_on`, `torch_state_off` removed in both languages (no reader); `home-and-photos.md` and design 99 §7 say so |

**Found in the emulator check** (`docs/testing/emulator.md` §6an, "Revision 3"): the parent's "Blokker flymodus" didn't
dim Flymodus until another signal refreshed the panel - the policy write the panel hears comes before
`AppEnforcer.applyHardening` sets `DISALLOW_AIRPLANE_MODE`, and no SDK broadcast says a restriction changed. A tap in
that window would open the window and be refused there, C99-M1's cost again. Fixed: `applyHardening` asks
`ShortcutsRuntime.requestRefresh()` at its end (set and cleared alike); checked both ways on the emulator.

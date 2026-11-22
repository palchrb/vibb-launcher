# Launcher notes: kid Settings: sound, touch feedback, ringtone, Bluetooth headphones, Snarveier

Moved from launcher/CLAUDE.md on 2026-11-01.

## Sound mode on kid Settings (design 18, `docs/design/18-sound-mode.md`, at the monorepo root)

- **Row** (`QuickControlFeature.SOUND` = 8, `ControlsSection.Rows.sound`; mask 8 alone is a card): two choices, Lyd = `RINGER_MODE_NORMAL` and "Lydløs (vibrerer)" = `RINGER_MODE_VIBRATE` - never `RINGER_MODE_SILENT`: an app's Silent is Android's DND (`ZenModeHelper`'s ringer delegate), and an app's Normal/Vibrate while DND is on ends it and every active automatic rule. Pure `server/SoundMode.kt` (`SoundModeTest`): no vibrator = no row (Vibrate would become Silent), read-only with "Ikke forstyrr er på" while the interruption filter isn't ALL (unknown included), nothing selected when fully silent (volume panel), leaving Silent only with `isNotificationPolicyAccessGranted` (the device owner has it; defensive). `QuickControls.setSound` re-reads the state at the tap and catches `SecurityException`; the page re-reads on `RINGER_MODE_CHANGED_ACTION`/`ACTION_INTERRUPTION_FILTER_CHANGED` while shown. Never on the PIN lock; the override/pause change nothing. The bit only shows the row - volume keys still work; never set `DISALLOW_ADJUST_VOLUME` (it master-mutes the phone).
- **Never set a manual DND, an interruption filter or the notification policy**; the ringer mode is set only in `QuickControls.setSound` (`SoundModeTest` scans the sources). The one Do Not Disturb the launcher owns is a silent time rule's `AutomaticZenRule` (server 0121, `timerules/ZenRuleRuntime.kt`, `docs/notes/launcher/time-rules.md` "A rule's Silent"): switching it off hands Android back the kid's state, and while it is on this row is read-only ("Ikke forstyrr er på"). Find My Device's `ring` is alarm-stream only (see the status section below).
- **Status**: `ringer_mode` (normal/vibrate/silent) and `interruption_filter` (all/priority/none/alarms), `null`/left out when unknown (`PolicyResponseCompatTest`). Not device-tested: the 18 doc's "Implementation status".

## Touch sounds and vibration on kid Settings (launcher round 9 §4, `docs/design/r9-launcher-glance-small-items.md`, at the monorepo root)

- **Row** (`QuickControlFeature.TOUCH` = 16, `ControlsSection.Rows.touch`; mask 16 alone is a card; the server keeps it
  **out of a new phone's default**): "Lyd ved trykk" = `Settings.System.SOUND_EFFECTS_ENABLED` (every view's click
  sound; our PIN keys and phone book follow it) and "Vibrasjon ved trykk" = `HAPTIC_FEEDBACK_ENABLED` (no switch without
  a vibrator), then "Tastaturet har egen lyd og vibrasjon i sine innstillinger." (HeliBoard plays its own click;
  nothing opens). `DTMF_TONE_WHEN_DIALING` is left alone (Vibb has no dial pad). Pure `server/TouchFeedback.kt`
  (`touchRowState`, `TouchFeedbackTest`; missing keys = on). Writable only with the "Modify system settings" app-op
  design 34 P12 already asks for (`Settings.System.canWrite`; Settings' "Modify system settings" row during a pause,
  `WriteSettingsAccess`); without it both switches show the phone's state at 50 %
  with "Kan ikke endres på denne telefonen ennå." Never on the PIN lock or the time-rule screen; not a restriction - the
  override/pause change nothing, the bit going off leaves the settings as they are.
- **Writes** only in `QuickControls.setTouchSounds`/`setTouchVibration` (`suspend`, `Dispatchers.IO`, one `Mutex` so two
  taps' writes finish in order - `loadSoundEffects()` waits for SoundPool and can take seconds; `putInt` caught for
  `SecurityException`/`IllegalArgumentException`; sounds then `loadSoundEffects`/`unloadSoundEffects` as Android's
  Settings does), called from `lifecycleScope` (`KidSettingsActivity.writeTouch`): while a write runs the switches keep
  what was tapped, and after the last one the row is read again, so a refused write flips back. Re-read on every resume.
  `TouchFeedbackTest` pins the writers by source.
- **Residual**: Android 13+'s own Touch feedback toggle also writes the hidden `HAPTIC_FEEDBACK_INTENSITY`, which an app
  can't write - on a phone where it was turned off before enrolment ours shows on while haptics stay off (accepted;
  `docs/setup/android-settings.md`: leave it on). Not device-tested: the note's §5 check 4.

## Ringtone and message sound in Kid Settings (PLAN, approved 2026-11-04, branch `kid-sounds`)

- **Rows** (mockup https://claude.ai/artifact/51wRYDkVCPi2XRyLeLhtH9): "Ringetone" and "Meldingslyd" between the sound
  row and the touch row, with the parent's **sound bit** (`QuickControlFeature.SOUND`, no new bit; also on a phone
  without a vibrator, where the sound row itself is hidden), each the phone's sound and a chevron. Pure
  `ui/kidsettings/SoundPicks.kt` (`SoundPicksTest`): `soundPicksState` (writable only with the "Modify system settings"
  app-op, `Settings.System.canWrite` - without it both at 50 %, not clickable, without the chevron (E9 L7), with the touch row's "Kan ikke endres på
  denne telefonen ennå."), `soundValue` (MediaStore's title, else a canonical URI's `title`, "Ingen lyd" when none is
  set, "Annen lyd" when unreadable). The ringer mode is no input: Lydløs changes nothing here. Read off the main thread
  on every resume (`KidSettingsActivity.loadSoundValues`).
- **Picker** `KidSoundPickerActivity` (not exported, our own page in the lock task; leaves when the sound bit or the
  app-op goes, a lock comes first; its back button says "Tilbake til Innstillinger", as Wi-Fi's and Bluetooth's - E9 L6,
  `KidHeader.bind(back = ...)`): the caption, RingtoneManager's cursor for `TYPE_RINGTONE` / `TYPE_NOTIFICATION`
  filtered to the **built-in** sounds (`content://media/internal/…`; the cursor also lists shared-storage files - not
  offered) as one `RadioGroup` (`item_kid_sound`), the closing line. **No "Ingen" row** (the kid must not silence the
  parents' calls). A sound the parent set from a file leads the list while the picker is open (`leadSound`), so the
  kid can go back to it. A tap plays it (`KidSounds.Preview`: `MediaPlayer`, `USAGE_MEDIA`, a ducking transient focus
  request - heard in Lydløs; silent at media volume 0) and, unless it is the phone's already (`pickerTapWrites`), writes
  it; the checked row is then read back, so a refused write flips back. `onStop` stops the sound. Never the system
  picker (`ACTION_RINGTONE_PICKER` is banned in `KidScreensEscapeTest`: its "add a sound" opens a file browser).
- **Writes** only in `KidSounds.set` (`suspend`, `Dispatchers.IO`, one `Mutex`):
  `RingtoneManager.setActualDefaultRingtoneUri` - the public `RINGTONE` / `NOTIFICATION_SOUND` keys plus Android's
  ringtone cache (played before the first unlock); it silently skips a URI whose MIME type it can't read, so the result
  is the read-back (`sameSound`, which ignores a canonical URI's query and a `0@` user id). Not a restriction: the
  override and the pause change nothing, the bit going off leaves the sounds as they are.
- **No vibration rows** (checked against AOSP 13/14, 2026-11-04): Telecom decides a call's vibration from the hidden
  `RING_VIBRATION_INTENSITY` alone (`SystemSettingsUtil.isRingVibrationEnabled`: "VIBRATE_WHEN_RINGING setting was
  deprecated"; `VibrationSettings` reads it too), so writing the still-public `VIBRATE_WHEN_RINGING` changes nothing on
  minSdk 34. `RING_VIBRATION_INTENSITY` and `NOTIFICATION_VIBRATION_INTENSITY` are `@hide` and not in
  `Settings.System.PUBLIC_SETTINGS`: SettingsProvider throws `IllegalArgumentException` at an unprivileged app with
  target SDK > 22, app-op or not. No device-owner API either. A vibrate row would lie to the kid.
- **Residual / device checks**: the message sound reaches only the apps whose channel uses the default sound
  (`content://settings/system/notification_sound`); a channel created with its own sound keeps it (check Element X and
  Google Messages on the Jelly Star). Not device-tested yet: the list on the Jelly Star, the preview while Lydløs, a
  call and a message with the new sounds, and the read-back with the phone's canonical URIs.

## Bluetooth headphones in Kid Settings (design 52, `docs/design/52-bluetooth-headphones.md`, at the monorepo root)

- **`server/BluetoothAudio.kt`** (in `KidScreensEscapeTest`'s `guarded` list by name): the pure rules - `btKind(uuids)`
  (A2DP sink `0000110b-…` = `A2DP`, LE Audio ASCS `0000184e-…` alone = `LE_ONLY`, else `OTHER`), `btLink` (an A2DP
  device's state from the A2DP proxy's `getConnectionState`, CONNECTED also on the ACL link when it speaks LE Audio; no
  proxy, LE-only and other devices: the ACL link), `isRouted`/`btTap` (a tap on a CONNECTED device that isn't the routed
  A2DP output - `AudioManager.getDevices(OUTPUTS)`'s address, the real MAC with `BLUETOOTH_CONNECT` - calls
  `setActiveDevice`; anything else `connect`), `btAttemptStep` (an attempt ends on connected - a late one clears the
  failure -, a `false` from the call, A2DP CONNECTING -> DISCONNECTED, or `BT_ATTEMPT_TIMEOUT_MS` 10 s). The glue:
  `Session` - one per Bluetooth page start, closed on stop - opens the A2DP and HEADSET proxies with
  `suspendCancellableCoroutine` + `withTimeoutOrNull(2 s)` on IO, the open itself `NonCancellable` (at most 2 x 2 s;
  what lands always reaches the session's keep-or-close step - a proxy that lands after the wait, or after the close,
  is closed; a repeated `onServiceConnected` after a Bluetooth restart is ignored; each proxy closed in its own
  `try`); no A2DP proxy is a failed open (HEADSET closed, tried again at the next call), no LE Audio proxy.
  `connect` cancels the page's discovery first (paging is slow while it runs), then
  `connect`/`disconnect` reflect `BluetoothA2dp.connect`/`setActiveDevice`/`disconnect` and, on disconnect, also
  `BluetoothHeadset.disconnect` (HFP, so a call stops ringing in it; its `false` is no failure), all on IO, through one
  `call(owner, method, ...)` whose `getMethod` sits inside its `try` - a method a later release blocks
  (`NoSuchMethodException`) fails soft to `false`, never an uncaught exception that would end the launcher's process
  (qa-52-a-code H1; `BluetoothAudioTest` pins every `getMethod` inside a `try`). The scan's found devices are read on
  IO too (`QuickControls.foundBluetoothDevice`). The music
  app has its own copy of the rules (`music/`, not byte-identical, no contract file).
- **Kid Settings › Bluetooth** (`ui/quickcontrols/BluetoothDevicesActivity`): grants `BLUETOOTH_CONNECT` in `onCreate`
  (`QuickControls.grantBluetoothConnectIfNeeded`, now `internal`) before the first list read in `onStart`; the bonded
  list, a row's re-read, Connect and Disconnect are `suspend` (`QuickControls.bondedBluetoothDevices`,
  `bluetoothDeviceInfo`, `connectBluetoothDevice`, `disconnectBluetoothDevice`) run from `lifecycleScope`.
  `BluetoothDeviceInfo` has `audio` and `state` (`BtLink`; `connected` = CONNECTED; its `toString` names no device).
  Rows by the pure `bluetoothRowActions(info, busy)` / `bluetoothRowStatus` (`BluetoothDeviceAdapter.kt`): not paired -
  a tap pairs; an audio device Connect (OFF) or Disconnect (CONNECTED) + Forget; a watch, keyboard or LE-only headset
  Forget only with the ACL mark. One receiver refreshes the row on `ACTION_BOND_STATE_CHANGED`, A2DP's
  `ACTION_CONNECTION_STATE_CHANGED` and `ACTION_ACL_CONNECTED`/`_DISCONNECTED` (a newer re-read of a row cancels the
  older). **Every receiver of Bluetooth broadcasts is registered `RECEIVER_EXPORTED`** (this one and the scan's
  `ACTION_FOUND`/`ACTION_DISCOVERY_FINISHED` in `QuickControls.scanBluetoothDevices`): the Bluetooth app (uid 1002)
  sends them, and AOSP's `BroadcastSkipPolicy` (14 :567, 15 :604, 16 :582) skips a NOT_EXPORTED receiver for any
  sender but root, system (1000) or the app itself ("Exported Denial"); they are `<protected-broadcast>`s, so EXPORTED
  lets no other app in. Broadcasts system_server sends (Wi-Fi's `SCAN_RESULTS_AVAILABLE_ACTION`, the battery's) stay
  NOT_EXPORTED (`BluetoothAudioTest` pins both). After Connect the row reads "Kobler til …" until the attempt ends, then "Fikk ikke kontakt med <name> – er
  den på?" (`bluetooth_devices_connecting`/`_no_contact`, nb + en). Not gated on the parent's Bluetooth switch; no
  Settings intent.
- **Names and addresses never leave memory**: no log line in `BluetoothAudio` or `QuickControls`' Bluetooth functions
  interpolates a device value; a failure logs `BluetoothAudio.failure(e)` (the exception's class and its cause's
  class, never the message). `BluetoothAudioTest` pins the rules and, by source, the reflected methods (never
  `BluetoothDevice.connect`/`disconnect`), no `CountDownLatch`/`runBlocking`/`.await(` in the Bluetooth sources, the log
  lines, the grant order and the broadcasts.
- Not device-tested: design 52 §4 (the emulator checks on API 34/35/36 and the Jelly Star with real headphones).

- **Audio devices only** (security review S-L5, round L4b; L4 already set `DISALLOW_BLUETOOTH_SHARING` while managed
  and finishes the page when the mask bit goes): the page lists and pairs a device the scan found only when it is an
  audio device (`btAudioClass`: an A2DP sink or LE Audio among its UUIDs, or the `AUDIO_VIDEO` major class - a found
  device has its class before its UUIDs; never a phone or a computer, whatever its UUIDs say - QA L3, a Linux
  laptop's PipeWire advertises an A2DP sink; never a service bit, phones set "audio" too), unless the policy's
  `bluetooth_any_device` (the server's "Any Bluetooth Device" switch, off by default) or the pause/override is on
  (`btPairOffered`). The tap checks again; a sync's apply drops rows it may no longer pair. A paired device keeps its
  row (Forget). Not device-tested: a friend's phone and a PC don't show, headphones and a speaker pair.

- **The pairing window** (design 122, `docs/design/122-bluetooth-pairing-window.md`, 2026-11-20): in the kiosk, lock
  task blocked Settings' `BluetoothPairingDialog` (`BlockedAppActivity`, seen on the Jelly Star). The pair tap now arms
  `server/BtPairingWindow` before `createBond` (off the main thread; kiosk on, not LOCKED, no pause - `btPairingArms`),
  which unhides Settings if it is hidden (its receiver must be in the ordered broadcast's list). Its own receiver
  (priority 999, ahead of Settings' 0, on its own thread, registered only while armed) takes `PAIRING_REQUEST` first.
  For the armed device, with a confirm-only variant (2, 3, 6) and the page started, it pins Settings through
  `LockTaskChrome.routeWindow(..., RouteOwner.BT_PAIRING)` (the shade closed, as for Flymodus) before Settings' receiver
  starts the dialog. A PIN or passkey variant aborts the broadcast and the row says "Trenger en voksen" (no failure
  toast). One idempotent closer runs on the device's bond BONDED or NONE, `PAIRING_CANCEL`, the page's `onStop`,
  screen off, an apply under a rule, a pause or the override, or 30 s (from the tap while armed, from the request once
  open). It takes Settings out in two passes, re-hides it if the arm unhid it, and clears the DE marker. A marker left
  by a dead process is put back at the start and before each sync (`putBackIfMarked`: one `apply()`). While armed or
  open, the apply's Settings hide step leaves it (`settingsHideStep`), and Home and the install receiver ignore
  Settings' events (`BtPairingWindow.inFlight`). `LockTaskChrome`'s route pin is keyed by owner, so Flymodus' close
  and the pairing window's never end each other. The pure rules are in `server/BtPairingPlan.kt` (`BtPairingPlanTest`).
  Code QA (2026-11-20): the PIN abort runs before the close, and the receiver goes only after its `onReceive` (M1). The
  marker is cleared only after a close or put-back that went through (L1). Owed: the Jelly Star with the Jabra - our
  "OPEN" must log before `START ... BluetoothPairingDialog`, because Android 16+ limits cross-process priority for
  target 36 (M2; design 122, "The Jelly Star check") - and Android 17's dialog component.

## The kid's hidden apps in Kid Settings (the user, 2026-11-03, branch `kid-unhide`)

- **What**: Home's long-press "Skjul" (`ContextMenuActions.toggleHidden`, the local `LauncherPreferences.apps().hidden()`)
  could be undone only by the snackbar's "Angre" or in the PIN-gated Settings › Skjulte apper (kept as it was). Kid
  Settings now has a card "Skjulte apper" / "Hidden apps" under the switches, shown only while the kid has hidden an
  app Home would otherwise show; its row opens the list in the card (chevron turned down, `stateDescription`
  "Utvidet"/"Lukket" for TalkBack), each app with its icon as Home draws it (`KidAvatars.renderAppIcon`, 40 dp), the
  name the kid sees (`getCustomLabel`) and "Vis"; a tap takes only that app off `hidden()` at once (TalkBack: "Vis
  <name> igjen"), Home and the drawer follow the preference, and the card goes when the last one is shown.
- **One filter**: `ui/home/HomeApps.kt` `homeApps(context, all, visibility)` is Home's grid filter (`AppFilter`, not
  Vibb One, the Play Store only in install mode), used by `HomeActivity.refreshApps` (`HIDDEN`) and by Kid Settings
  (`EXCLUSIVE`: the hidden set taken instead of left out), off the main thread. So the parent's off-Home apps
  (`app_display.on_home: false`) are not listed - except in a parent window, where Home shows them too. Pure
  `ui/kidsettings/HiddenApps.kt` `hiddenAppsView` (no row without an app; the list closes with its last app);
  `HiddenAppsTest`. The list is filtered again on every resume, policy and change of the hidden set.
- Not device-tested: the card in nb/en at font scale 1.0/2.0, TalkBack on the row and the apps, a hide on Home then
  the swipe left.

## Flymodus through the Digital assistant role (design 98)

Vibb One holds the assistant role only for Flymodus (`assistant/`): the session acts on the route's one-time nonce,
has no window and reads nothing (`AssistantInvariantsTest`). On: `AirplaneRoute.turnOn` - the refusals (role, the
parent's `DISALLOW_AIRPLANE_MODE`, ECBM or an emergency call in the last 10 min (not Vibb's 60-min callback window - the user, 2026-11-15), the 10 s cool-down), a report-only post
with `going_on`, Settings' `AirplaneModeVoiceActivity` (translucent or floating; on Android 16 floating) through
`LockTaskChrome.routeWindow` (never while LOCKED) with Settings unhidden and hidden again, the DE marker put back after a
process death. The window closes and blocks the shade through the lock-task features, without
`LOCK_TASK_FEATURE_NOTIFICATIONS` (`routeWindowFeatures`, Q99-H1, 2026-11-15): under lock task `setStatusBarDisabled` is
only recorded, so the window's `statusBarDisabled` does nothing there. The close is two passes: Settings out with the
shade still blocked (`routeClosing`), then the plan's features; after a process death the put-back's `apply()` writes them
(a plan-less pass keeps the platform's features). Left: a touch during SystemUI's ~300 ms collapse keeps the shade open
and clickable for the window; the close contains a page started then (design 98 §2.2 step 4, emulator.md §6am). Off: `DISALLOW_AIRPLANE_MODE` set and cleared - no role needed. The kid's row (bit 64 with the role,
Off-only whenever airplane mode is on), Home's line, the time-rule screen's "···" sheet. `AirplaneRuntime` keeps the
periods (the edge receiver is exported: an emergency call's edge comes from `com.android.phone`; `on_by` is `kid` within
5 s of the kid's route showing its session, the Wi-Fi setup step's edge `other`). The rows' emergency refusal is read off
the main thread and cached 5 s (`AirplaneRuntime.rowState`); the route reads it fresh. A plan-less chrome pass never
keeps Settings in the kiosk list (`fallbackKioskPackages`). "Fullfør oppsett"
(`AssistantSetup`): the default-apps window, Wi-Fi in airplane mode (each with its own deadline alarm), Samtalefilter,
the setup notification.
`docs/design/98-airplane-mode-assistant.md`, `docs/testing/emulator.md` §6am.

Design 121 §4.4 (step 2a, before the `going_on` post): when airplane mode will take the command stream's network
(`airplaneHoldsStream`: not on Wi-Fi, or Wi-Fi that `wifi_kept` doesn't say stays), `CommandListenerService.holdForAirplane`
cancels the stream - an RST_STREAM on the pooled HTTP/2 connection, which stays for the report, so the front has the close
before the report's answer - and holds every reconnect path until the route's `finally` (`releaseAirplaneHold`, at most
`AIRPLANE_STREAM_HOLD_MS`, then released as on success). After the report the pool is evicted. A failed turn-on
reconnects at once; a successful one only on another network than the held one, or after a 30 s wait
(`airplaneStreamReopens`, code QA H1). No drop bookkeeping: no reconnect wake lock, no earlier backstop. The server then shows grey
"Flymodus på siden 21:03" for as long as nothing else reaches it (`phone_status::Held`); ~140 B per turn-on.

## Snarveier, the kid's quick panel in the shade (design 99, `docs/design/99-snarveier.md`)

- **What it is.** One ongoing notification: since design 100 (`docs/design/100-one-vibb-notification.md`, step 1) the
  anchor's own, id 1002 (`CommandListenerService`'s foreground notification), posted by the one poster
  `server/AnchorNotification` while the panel is shown - else that notification is r13's plain notice
  (push-and-play.md, "One poster of id 1002"). Design 99's `ShortcutsService` (id 1011) is gone: Snarveier starts and
  stops no service, it only decides what the anchor's notification shows. Colorized in `kid_ground`,
  `FOREGROUND_SERVICE_IMMEDIATE`, `VISIBILITY_SECRET`, on the LOW channel `launcher:shortcuts` (`.N` after the kid
  showed it again); `notify(1002)` moves the one record between that channel and the listener's (M1). Only a colorized
  FGS notification stays at the top of Android 16's shade and out of its "Vibb One · 3" bundle (§1, S4/S5). Up to
  **four** round buttons in one row, four quarter cells, **no names** (revision 3, the user 2026-11-15: "Må vi ha navn
  under logoen? Det har ikke ios"): `shortcuts_row.xml` is the content view; the big view is the same row, and on a day
  with a screen-time budget `shortcuts_panel_big.xml` - the time line over it (r13's words, "1 t 20 min igjen i dag" /
  "Skjermtiden er brukt opp i dag", 14 sp `nunito_bold`, two lines at most) and the note line (13 sp, white 70 %) when
  there is one: "Spurt kl. 14:02 · venter på svar", the parent's answer for 30 minutes ("Pappa svarte nei kl. 14:10",
  "Mamma ga deg 30 min"), or time given without a request (design 100 step 2, §14.3; push-and-play.md, "The
  request"). No action in the panel (design 100 D1: "Be om mer tid" is the Mer tid disc's - usable while the kid may
  ask, dimmed "Mer tid, spurt" while a request is open, gone at the day's three).
  Folded, the row alone (the time is then hidden: accepted). 40 dp discs, 22 dp glyphs, in the 48 dp content height
  (45 dp cells folded, 55 dp unfolded at 320 dp); the discs are dp only. Off: white at 12 %, on: the peach disc with the
  glyph in `kid_ground`; dimmed 40 %, busy 50 %. The names (`shortcut_full_*`) are the picker's and each cell's content
  description. The header is the app's name alone (the sub-text "Snarveier" removed 2026-11-16, the user: "bare behold Vibb One"). The small icon is the Vibb mark, `ic_stat_vibb`, as the plain notice's (since 2026-11-16, the user; it was the gear
  `ic_stat_gear`, now deleted). Its colours are literal: a theme attribute draws an empty disc in SystemUI (design 100
  M10); checked filled in the status bar and the shade header on Android 14 and 16; Android 17 draws the app's launcher icon in the header instead (`docs/testing/emulator.md` §6ar).
- **Items** (`ShortcutsModel.kt`, pure, `ShortcutsTest`): torch, sound (bit 8), airplane (bit 64 + the role via
  `airplaneRow`, Off-only when on), wifi (1), bluetooth (2), mobile_data (32, `kidMobileRow`), brightness (4, one plain
  sun that opens the brightness sheet, below), more_time (the anchor notice's `ask` part, set by the poster from the time part it posts with -
  `ShortcutsRuntime.panelFor`, design 100 Q100-L6),
  music (Vibb music's own session only, gated by `MusicSessions.usable`). The panel = the kid's picks (`null` = the
  default order torch, sound, airplane, wifi, bluetooth, mobile_data, brightness, more_time, music) offered now, the first
  four. Shown with device owner, an `Ok` cache, bit **128** (`QuickControlFeature.SHORTCUTS`), an item, not hidden.
- **The glue** (`ShortcutsRuntime`): the platform's signals are the process's (`init` from `Application.initRest`'s
  tail, after the torch): airplane, ringer, DND, Wi-Fi, Bluetooth, channel block state, locale,
  the torch, `AirplaneRuntime`, the policy and `kidMobileOff` prefs; a `MusicSessions` listener while the panel holds
  Musikk (C99-L4). The launcher's own language (a per-app locale, no broadcast) reaches it from
  `Application.onConfigurationChanged` -> `ShortcutsRuntime.languageChanged`: the current channel is created again
  (only renamed; a channel the kid turned off stays off) and the panel posted again (C99-L1). The parent's restrictions
  (Flymodus's "Blokker flymodus") have no broadcast: `AppEnforcer.applyHardening` asks for a refresh at its end. Every
  signal asks the anchor's one poster for a refresh (`ShortcutsRuntime.requestRefresh` -> `AnchorNotification`): it
  posts a changed view at most once a second, only while the anchor runs; a panel wanted while the anchor isn't running
  shows when it comes back.
- **A tap** is a broadcast to the non-exported `ShortcutsReceiver` (`goAsync`, the key alone) or, for Flymodus On
  while usable and not busy (pure `shortcutOpensWindow`; a dimmed or busy Flymodus is a broadcast, refused with the
  shade left open - C99-M1, D10), `ShortcutAirplaneActivity` (translucent, its own task, `noHistory`; since 2026-11-16 a one-pixel window, `claimNoBars`, on `ShortcutWindowTheme`'s transparent bars: under `Theme.Translucent.NoTitleBar` the window's decor painted the legacy black bar backgrounds for its whole life and they moved with it as it closed, and a full-screen window of a dark theme would turn the status icons and gesture handle white over a light wallpaper; one pixel claims no bars, so they stay as the app in front left them - `ShortcutWindowTest`; checked on Android 14 and 16 over a dark and a light wallpaper, on 17 over a dark one): Android closes the shade to start it, and the
  route starts only on its window's first focus, so no finger is left in a collapsing shade (design 98's ~300 ms gap);
  no focus in 3 s - nothing. Every tap runs on `ShortcutsRuntime.taps` through the pure fail-closed
  `shortcutTapAllowed`: an unknown key, the lock mode undecided or the time rules unreadable, LOCKED, Android's keyguard
  (a Swipe keyguard shows silent notifications), not in the panel, dimmed, busy, Musikk's app unusable
  -> refused, logged.
- **Lysstyrke's sheet** (2026-11-19, the user's mockup "Valg 1"; it replaced D5's three steps): the cell opens
  `BrightnessSheetActivity` (pure `shortcutWindow`, as Flymodus On; Android closes the shade to start it). The
  airplane window's one pixel and theme, its own task, `noHistory`, out of Recents; it shows a
  `KidSheetDialog` (`setUpKidSheet` with `ownScrim`: edge to edge, the screen dimmed by its own scrim, not the
  window's dim - Android drops a dimming window's light navigation bar, which left 3-button navigation's buttons white on
  Lys's sheet, code QA M3 - drag down closes) only after `ShortcutsRuntime.verdict`
  allows Lysstyrke, else finishes. `sheet_brightness.xml`, in the kid's look (`lookOver`, page tokens;
  `kid_page_handle`, `kid_page_track`): the title, a small sun, kid Settings' slider (0-255, `setBrightness` while
  dragging), a big sun. Tap outside, Back or a drag down closes it; `onStop` finishes it (Home, the lock, screen off), and
  it holds no intent out (lock task: the launcher's own package). Never over a keyguard: Snarveier
  (`VISIBILITY_SECRET`) didn't show on a Swipe keyguard either, with silent notifications shown on the lock screen
  (Android 14 and 16, `docs/review/2026-11-19-brightness-sheet-code-qa.md` L1), so the sheet has no `showWhenLocked` and
  the tap check refuses Lysstyrke on a keyguard and under LOCKED as every item. A Lysstyrke broadcast (a stale view) does nothing; the glyph is `light_mode`
  (`shortcut_glyph_brightness`), the content description the name alone.
- **Hidden** (§2.6): the long-press menu's "Turn off" on the channel is the kid's choice; the report says `hidden`, Kid
  Settings' row "Skjult i varsellisten", and the anchor's notification goes back to the plain notice (with the time
  left) on the listener channel (design 100 §2.4, §5; the swap on a user's block is still to be checked, §11 check 7).
  Only the picker's "Vis i varsellisten igjen" deletes the channel and makes `launcher:shortcuts.N+1` - one task on the
  poster's thread, the delete only while the plain notice is up (Q100-L8).
- **Kid Settings' row** "Snarveier" (its own card "I varsellisten" at the top, with bit 128 and a candidate) opens
  `ui/kidsettings/ShortcutsPickerActivity` (a page in Home's task): check boxes, up to four, "Flytt opp"/"Flytt ned"
  buttons on the chosen rows (no dragging: gesture and 3-button navigation alike), a fifth refused with a line. The
  picks are CE prefs `shortcuts`/`picks` (a JSON list; unknown keys kept).
- **The report** `shortcuts: {shown, hidden, items}` (a full-report field), the contract
  `shortcuts.json` (root `CLAUDE.md`).

## Innstillinger as a task of its own (design 111, `docs/design/111-kid-settings-task.md`, branch `kid-settings-task`)

The user, 2026-11-17: "Alle 'apper' vi legger inn fra vibb one skal oppføre seg som native apper." Kid Settings was the
last built-in "app" in Home's task (`excludeFromRecents`, cleared by Home's `clearTaskOnLaunch`); it now behaves as
Meldinger, Kalender and the phone book do. The drawer and the parent's Settings stay in Home (decided).

- **The task.** `KidSettingsActivity` and its four sub-pages (`WifiNetworksActivity`, `BluetoothDevicesActivity`,
  `KidSoundPickerActivity`, `ShortcutsPickerActivity`) share `${applicationId}.kidsettings`; none of the five is
  `excludeFromRecents`; Kid Settings is `singleTop`; the sub-pages are started from its rows with plain in-task starts.
  **One builder**, `KidSettingsActivity.intent(context)`: the component and `FLAG_ACTIVITY_NEW_TASK`, never an action,
  data, extras or `CLEAR_TOP` - Home's tile and the swipe left both use it, an open task comes back as it was (a
  sub-page on top if one was open), and since nothing forwards into the root the task's base intent never drifts, so
  the phone book's code QA M1 (a second root stacked) can't happen. `KidSettingsTaskTest`.
- **The card.** `setTaskDescription` in `onCreate`: "Innstillinger"/"Settings" (`kid_settings_title`, the tile's name)
  and `R.mipmap.ic_launcher`. Back at the root finishes it (a non-root locked task's finish, never blocked) and Home
  shows; the card stays (AOSP keeps an emptied task in Recents). Home no longer clears it, and `HomeFront.bring` brings
  Home forward over it instead of clearing it.
- **Under a rule** (code QA L1): Overview stays on under time rules, so the card can bring Kid Settings back during
  one. Its content is made invisible in `onStart` and on a `lockReason` change while any rule or the budget is in force
  (`coverIfLocked`), and visible again in `onResume` without one, so the old screen takes no touch in the frames before
  the time-rule screen is up.
- **The look** (the user's Q1, 2026-11-17: "b er best. jeg trenger ikke bildebakgrunn her, bedre med vanlig fast
  bakgrunn"). Opaque, so WM Shell runs the task (wallpaper open/close) animation, Meldinger's, and not the
  wallpaper-intra transition two `FLAG_SHOW_WALLPAPER` windows get (`lock-and-glance.md`). `KidSettingsTheme` =
  `launcherBaseTheme` + `KidPageNightTheme`'s five window literals (window and splash ground `kid_ground`, dark bar
  icons off, navigation contrast on) + transparent bars; `showsSystemWallpaper()` is gone (`UIObject` applies
  `Background.SOLID`). **No copy of the wallpaper any more**: the root is the layout's own `kid_ground`, the same colour
  as the window and the splash, so a starting window already matches; the ink is the plain ground's (white, no scrim,
  no shadow: `inkForColours(kid_ground)`), the status-bar icons the theme's. `WallpaperStore` only feeds the wallpaper
  picker. Home is the one screen that shows Android's wallpaper (`HomeGroundTest`). Kid Settings stays night in both
  looks (design 78): no light twin, no `PageKind` row, and **not** in the look's `closesTask` - the look is chosen in it.
- **The sub-pages' gate** (`SubPageGuard`, the pure `subPageGate(lockReason, section, page, mask, soundWritable)`,
  `KidSettingsModelTest`). A card can bring a sub-page back under a rule or after the parent took its row away, so each
  runs the one gate **first in `onStart`, before any radio work** (Location, a Wi-Fi scan, Bluetooth discovery), again in
  `onResume`, on a `lockReason` change, on a stored policy and after each apply. Any lock reason -> `REDIRECT`
  (`LockActivity.start`, then `finish()`); a pause or Foreldrekode is `NONE`, as for Kid Settings. Else the page's own
  row: Wi-Fi's bit (`wifiPageAllowed`, new - the Wi-Fi page checked no mask before), Bluetooth's (`bluetoothPageAllowed`),
  the sound picker's bit with the "Modify system settings" app-op (`soundPicksState`), Snarveier's bit 128 of an
  accepted policy on a device-owner phone; else `FINISH`. It replaces the pickers' two copies of
  `redirectIfLocked`/`finishIfNotAllowed` and Bluetooth's `finishIfNotAllowed`. Kid Settings itself keeps its own
  redirect under any rule (`onResume` and the `lockReason` listener); nothing on it is PIN-gated.
- **The Wi-Fi page's Location** (pure `wifiLocationStep(event, enabledNow, byUs, polling, policyKeepsOn)`). Turned on in `onStart`
  after the gate (if it was off, marked as ours), then the scan; in `onStop` the connect poll is dropped first
  (`handler.removeCallbacksAndMessages`, `connectingSsid = null`, no toast - else a connect that worked ends as
  "couldn't connect" once Location is off, e.g. a connect and then "Logg på" at once), then Location off if ours,
  still on, and not wanted on by the cached policy now (`locationStaysOn`: the CONFIG_LOCATION switch for Find my
  device, or the zone watch - a sync while the page was shown found it on and left it; code QA L4). Never a setting
  the parent had on or wants on. Residue as before: a process death with the page in front leaves
  Location on.
- **A fresh MAC on open Wi-Fi** (design 126 A7, 2026-11-22). `QuickControls.connectToWifiNetwork` sets
  `macRandomizationSetting` from the pure `joinMacSetting(secured)` (`JoinMacSettingTest`) before
  `addNetworkPrivileged`: an open network (no key) `RANDOMIZATION_NON_PERSISTENT` - a new random address after 4 h
  away, at the DHCP lease (30 min to 24 h) and at least daily, not on every connection; a network with a key `null`,
  Android's persistent per-network address (a home or school network would see a new device daily). Android lets an
  admin set it only on a network it created, so the networks the parent saves in Android's Settings during a window and
  the setup QR's network keep Android's default, as do networks Vibb saved before this build. Cost: a café's sign-in
  page may ask again after a night away.
- **The Bluetooth page** stops discovery in `onStop` (a discovery cut short runs again at the next start, unless a pair
  or a connect runs then - `btStartScans`; a discovery that didn't start returns no receiver, so nothing waits for its
  end; code QA L3) and drops the
  rows it may no longer pair (`dropUnoffered`) in `onStart` after the gate and when `loadBonded` returns - the applied
  listener is off while stopped, so a page left in the card during a restrictions pause listed non-audio devices with
  "Pair" afterwards (the tap re-checked, so nothing could be paired).
- **The Wi-Fi sign-in inside the card** (design 102). `CaptivePortalEntry.start` from the Wi-Fi page has no `NEW_TASK`, so
  the entry and the window join Kid Settings' task, and under a rule the "Innstillinger" card can resume the window
  (`:portal`, no rule check). Allowed: design 102 §3.5 (D4) permits the window under a time rule; the entry's keypad
  finishes in `onStop`; the window ends itself. Kept as is.
- **Starting windows on a Recents restore**: after a process death or a destroyed activity Shell draws the top
  activity's splash - Kid Settings' is `kid_ground`, correct; a sub-page's is `KidPageTheme`'s `kid_page_ground` in the
  **phone's** night mode, a wrong-look flash when it differs from the kid's look. Accepted (design 111 §2.3,
  `ui-polish.md` R9); twins only if the user objects after §6az row 6. No icon on either splash: every theme inherits
  `launcherBaseTheme`'s `splash_icon_none` (design 111's "with the Vibb icon" was wrong; code QA L6).
- **Language and kiosk.** `languageClosesTask` names Kid Settings (`LauncherLocaleTest`): a stopped Kid Settings behind
  Home can be the process's newest activity, which makes the system refuse the language switch (design 78 §5.3), so a
  refused switch closes its task with the page tasks before it asks again. A switch that gets through at once leaves the
  task, and its card keeps "Innstillinger" until the screen is shown again (as Dagen's and Meldinger's cards; code QA
  L5). Our package is lock-task permitted, so `.kidsettings` joins lock task; Home stays
  `mLockTaskModeTasks[0]` (`kiosk-escapes-and-updates.md`); `clearLockedTask` empties the card when the kiosk goes off,
  as Dagen's. Flymodus's assistant window runs in a task of its own and focus goes back to Kid Settings' task.
  Process death: Kid Settings rebuilds everything in `onResume`; the hidden-apps list comes back closed (accepted).
- **Android 14-17.** `TaskDescription.Builder` is API 33; the cross-task `LockActivity.start` from a sub-page is our own
  uid's, allowed by 15's and 16's background-start hardening. **Android 17**'s behaviour changes (developer.android.com,
  "Behavior changes: all apps" and "apps targeting Android 17", read 2026-11-17) list nothing for tasks, Recents,
  `TaskDescription`, back navigation, lock task, starting windows, device-owner Location or Bluetooth discovery; the one
  activity item, "Activity Security" (background-start rules extended to `IntentSender`), applies to apps targeting 37,
  and we target 36. The gate runs rows 1-7 on 14 and 16 (`docs/testing/emulator.md` §6az).

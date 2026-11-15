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
  description. The small icon is the gear, `ic_stat_gear` (`baseline_settings_24`'s path with a literal white fill:
  the theme-attribute gear draws an empty disc in SystemUI, design 100 M10).
- **Items** (`ShortcutsModel.kt`, pure, `ShortcutsTest`): torch, sound (bit 8), airplane (bit 64 + the role via
  `airplaneRow`, Off-only when on), wifi (1), bluetooth (2), mobile_data (32, `kidMobileRow`), brightness (4, three steps
  40/128/230), more_time (the anchor notice's `ask` part, set by the poster from the time part it posts with -
  `ShortcutsRuntime.panelFor`, design 100 Q100-L6),
  music (Vibb music's own session only, gated by `MusicSessions.usable`). The panel = the kid's picks (`null` = the
  default order torch, sound, airplane, wifi, bluetooth, mobile_data, brightness, more_time, music) offered now, the first
  four. Shown with device owner, an `Ok` cache, bit **128** (`QuickControlFeature.SHORTCUTS`), an item, not hidden.
- **The glue** (`ShortcutsRuntime`): the platform's signals are the process's (`init` from `Application.initRest`'s
  tail, after the torch): airplane, ringer, DND, Wi-Fi, Bluetooth, channel block state, locale, a brightness observer,
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
  shade left open - C99-M1, D10), `ShortcutAirplaneActivity` (translucent, its own task, `noHistory`): Android closes the shade to start it, and the
  route starts only on its window's first focus, so no finger is left in a collapsing shade (design 98's ~300 ms gap);
  no focus in 3 s - nothing. Every tap runs on `ShortcutsRuntime.taps` through the pure fail-closed
  `shortcutTapAllowed`: an unknown key, the lock mode undecided or the time rules unreadable, LOCKED, Android's keyguard
  (a Swipe keyguard shows silent notifications), not in the panel, dimmed, busy, Musikk's app unusable -> refused, logged.
- **Hidden** (§2.6): the long-press menu's "Turn off" on the channel is the kid's choice; the report says `hidden`, Kid
  Settings' row "Skjult i varsellisten", and the anchor's notification goes back to the plain notice (with the time
  left) on the listener channel (design 100 §2.4, §5; the swap on a user's block is still to be checked, §11 check 7).
  Only the picker's "Vis i varsellisten igjen" deletes the channel and makes `launcher:shortcuts.N+1` - one task on the
  poster's thread, the delete only while the plain notice is up (Q100-L8).
- **Kid Settings' row** "Snarveier" (its own card "I varsellisten" at the top, with bit 128 and a candidate) opens
  `ui/kidsettings/ShortcutsPickerActivity` (a page in Home's task): check boxes, up to four, "Flytt opp"/"Flytt ned"
  buttons on the chosen rows (no dragging: gesture and 3-button navigation alike), a fifth refused with a line. The
  picks are CE prefs `shortcuts`/`picks` (a JSON list; unknown keys kept).
- **The report** `shortcuts: {shown, hidden, items}` (capability `shortcuts_v1`, a full-report field), the contract
  `shortcuts.json` (root `CLAUDE.md`).

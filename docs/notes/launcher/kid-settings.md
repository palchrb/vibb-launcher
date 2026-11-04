# Launcher notes: kid Settings: sound, touch feedback, Bluetooth headphones

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

# Design 98: Flymodus in the kid's settings, through the Digital assistant role

Status: design, **revision 1**, 2026-11-14 (branch `design-98`). Approved by the user on 2026-11-14: real Android
airplane mode as a row in the kid's settings, made possible by making Vibb One the phone's **Digital assistant**, on
the condition that the assistant step is easy when a new phone is enrolled. Both directories. A new shared contract
`server/testdata/airplane_mode.json`, migration `0146_airplane_mode.sql` (0145 is `abroad`; recheck at merge), and
`quick_controls_mask` bit **64**. Size: launcher **M**, server **S-M** (§11). §9's options (battery saver, the quick
panel, Flymodus from the PWA) are **not approved**; they are D-questions in §13 with sizes. §13 also holds the
decisions this design needs before it is built.

The user (2026-11-14): *"om vi kan gjøre on-boarding av assistenten enkelt når man in ruller er en ny tlf, så er jeg ok
med å ta det med, og ta med flymodus i barnets settings"*. Later the same day, about the quick panel (still a
question, not approved): the kid picks the items from what the parent allows, at most about six, and the panel never
shows over the lock screen.

**The short version.** A device owner cannot turn airplane mode on, and Quick Settings is always off under lock task.
The one route that works without any new permission: the app that holds the **assistant role** shows a voice session
of its own, and that session asks Settings' `AirplaneModeVoiceActivity` to turn airplane mode on. Turning it off
needs no role: setting and then clearing `DISALLOW_AIRPLANE_MODE` ends airplane mode. The parent chooses Vibb One as
the Digital assistant once, in three taps on Android's own page, from a new "Fullfør oppsett" ("Finish setup") step in
the launcher's PIN-gated Settings during Åpne for oppsett. The role cannot be granted by a device owner, by a role
request dialog (`requestable="false"`) or by any intent an app may send directly.

**New in this design (measured on `jelly36` on 2026-11-14, §1.2):** the assist gesture never reaches the assistant
under lock task, so **the quick panel opened by the assist gesture is not possible** on a managed phone (§9.2). Under
`DISALLOW_CONFIG_DEFAULT_APPS`, which every managed phone holds, Android's picker refuses every app except "None".
So the onboarding needs a short, PIN-gated lift of that restriction (§3.2). Settings is hidden on a managed phone, and
a hidden Settings breaks the route, so the route also shows Settings for about half a second (§2.2).

## 1. The facts this rests on

### 1.1 From the earlier research (the same AVD, a throwaway probe as device owner and assistant)

- No device-owner API sets airplane mode. `setGlobalSetting(AIRPLANE_MODE_ON)` throws `SecurityException`,
  `ConnectivityManager.setAirplaneMode` is signature-only, and the Telephony radio and SIM power calls need
  `MODIFY_PHONE_STATE`. Quick Settings is always disabled under lock task (`DISABLE2_QUICK_SETTINGS`), and no API
  picks Quick Settings tiles. Settings' airplane page can't be contained: it leads on to the SIMs, VPN, Private DNS,
  Data Saver and more.
- **On:** the assistant's `VoiceInteractionService.showSession(args)` -> the session's
  `startVoiceActivity(Intent(Settings.ACTION_VOICE_CONTROL_AIRPLANE_MODE).putExtra(EXTRA_AIRPLANE_MODE_ENABLED, true))`
  -> Settings' translucent `AirplaneModeVoiceActivity` sets it as the system and finishes. It takes about 300 ms. Under
  lock task `com.android.settings` must be on the lock-task packages, or the start is blocked. We stay in our own task,
  LOCKED. On a refusal the session window stays up, so it is hidden on every outcome.
- **Off:** add and then clear `DISALLOW_AIRPLANE_MODE` (device owner). Android ends airplane mode when it is set, and
  while it is set the voice route is refused. The restriction is applied asynchronously, so the clear waits for
  `ACTION_USER_RESTRICTIONS_CHANGED` (as design 94's relock does).
- **Wi-Fi:** Vibb can't turn Wi-Fi on while airplane mode is on: `setWifiEnabled` is refused even for a device owner
  (`WifiServiceImpl`: "only Settings can toggle wifi"). Wi-Fi stays on through airplane mode only through Android's
  memory: with `apm_enhancement_enabled`, a privileged UI (Settings, Quick Settings) turning Wi-Fi on once during
  airplane mode writes `Settings.Secure.wifi_apm_state = 1` and shows a one-time notification "Wi-Fi stays on in
  airplane mode". After that, airplane mode on kept Wi-Fi up and validated. Turning Wi-Fi off from a privileged UI
  during airplane mode resets the memory (`WifiSettingsStore.handleWifiToggled`). Bluetooth has `bluetooth_apm_state`
  (not tested).
- **Emergency:** a 112 call in airplane mode turns airplane mode off and places the call. Airplane mode **stays off**
  afterwards, and Emergency Callback Mode lasts about 5 minutes.

### 1.2 Measured for this design (`jelly36`, Android 16 Play image, gesture navigation, 2026-11-14)

The probe from §1.1 extended (scratchpad `d98/probe/`): the session logs its `onShow` args and flags, shows a small
dark panel when it isn't ours, and acts only on its own marker. The VIS sets
`setDisabledShowContext(SHOW_WITH_ASSIST | SHOW_WITH_SCREENSHOT)` in `onReady`. Started with `-wipe-data`; afterwards
`jelly36` was put back from `~/kid-look-1-avd-pre-start` (22 of 22 SHA-256 sums match). Nothing touched a phone.

| # | What | Result |
|---|---|---|
| M1 | The Android page | `Settings.ACTION_VOICE_INPUT_SETTINGS` resolves to `com.android.settings/.Settings$ManageAssistActivity` ("Digital assistant app"). Its first row, "Default digital assistant app", opens PermissionController's `DefaultAppActivity` with None / the probe / Google. Choosing the probe shows a confirmation ("The assistant will be able to read information about apps in use…", Cancel / OK). After OK, `isRoleHeld(ASSISTANT)` is true. **Three taps.** `Intent.ACTION_MANAGE_DEFAULT_APP` (the direct picker) is protected by `MANAGE_ROLE_HOLDERS`, so it can't be used. `android.settings.MANAGE_DEFAULT_APPS_SETTINGS` opens the list of all default apps (more taps, more to wander into) |
| M2 | The picker under `DISALLOW_CONFIG_DEFAULT_APPS` | The apps are greyed, and tapping Google gives "Blocked by work policy". **"None" stays enabled, and choosing it removed the role.** So the restriction blocks the onboarding and does not protect the role from someone who has Settings open |
| M3 | Settings hidden (`setApplicationHidden`, what `AppEnforcer` does on a managed phone) | The route fails: `ActivityNotFoundException`, "Cannot find ActivityInfo in startVoiceActivity". `setPackagesSuspended(com.android.settings)` is **refused** (returned as failed), so a managed phone's Settings is hidden but never suspended |
| M4 | Unhide, route, hide again (lock task LOCKED, features 127, Settings on the lock-task packages) | Settings resolves at once after the unhide. Airplane mode was on 270 ms after the session started. The unhide sends `PACKAGE_ADDED` and `PACKAGE_CHANGED` for Settings, and the hide sends `PACKAGE_REMOVED` and `PACKAGE_CHANGED`. We stayed LOCKED in our task |
| M5 | The route at the secure keyguard (PIN set, screen on, no lock task) | Airplane mode on in about 190 ms. Nothing showed on screen |
| M6 | The route with the screen off | Airplane mode on in about 60 ms. The screen stayed off |
| M7 | The role across an app update (`install -r`) and a reboot | Kept both times. Airplane mode also survives a reboot |
| M8 | Assist data | With `setDisabledShowContext(3)`: `onHandleAssist` got no data, no structure and no content, and `onHandleScreenshot` got `null`, also from the assist gesture. Android's "Use text from screen" and "Use screenshot" were both on |
| M9 | **The assist gesture, no lock task** (baseline) | `KEYCODE_ASSIST` (invocation type 7), long-press power with `power_button_long_press = 5` (type 6) and the gesture-navigation corner swipe (type 1) each opened the session |
| M10 | **The assist gesture under lock task (LOCKED)** | **Never.** Not `KEYCODE_ASSIST`, long-press power or the corner swipe, with features 63 (Vibb's default set) or 127 (with the block bit). `dumpsys statusbar`: `mDisabled1=0x6000000`, which includes `DISABLE_SEARCH`. AOSP `LockTaskController.getStatusBarDisableFlags` starts from `DISABLE_MASK`, and no lock-task feature clears `DISABLE_SEARCH` |
| M11 | The assist gesture at the keyguard, no lock task | It fired (`invocation_phone_state=2`, locked) and the session showed **over** the keyguard |

What M10 means: on a managed phone the kiosk is on, also under the keyguard, so the assist gesture never reaches
Vibb. It fires only while the kiosk is off (Åpne for oppsett, the parent override, an unmanaged phone). §2.1 makes the
session do nothing then.

## 2. The route

### 2.1 The assistant's parts (launcher, `assistant/`)

- `VibbAssistantService : VoiceInteractionService`. In `onReady` it calls
  `setDisabledShowContext(SHOW_WITH_ASSIST or SHOW_WITH_SCREENSHOT)` (M8) and publishes itself to `AirplaneRoute`. In
  `onShutdown` (the role moved away, or the service was unbound) it clears that and asks for a role report (§3.6).
- `VibbSessionService : VoiceInteractionSessionService` -> `VibbSession`. **It acts only on Vibb's own marker:**
  `args` must carry `me.vibb.assistant.ROUTE` with the 128-bit nonce `AirplaneRoute` made for this one request
  (process memory, used once). Any other `onShow` (the assist gesture, a stale request) calls `hide()` at once, shows
  nothing and starts nothing. A marked request with `KeyguardManager.isKeyguardLocked` is still served: M5 shows the
  route works there, and §9.3 may need it. `onCreateContentView` returns an empty view. `onHandleAssist` and
  `onHandleScreenshot` are overridden empty: they read nothing and log nothing (§8). The session hides itself on
  `onTaskFinished`, on a `startVoiceActivity` exception, and at the route's 2 s deadline.
- `VibbRecognizer : RecognitionService`, a stub the role requires (`VoiceInteractionServiceInfo` refuses a service
  without one). Every `onStartListening` answers `ERROR_CLIENT` at once.
- `res/xml/vibb_voice_interaction.xml`: `sessionService`, `recognitionService`, `supportsAssist="true"` (required for
  the role) and `supportsLaunchVoiceAssistFromKeyguard="false"`. The manifest declares both services with
  `android:permission="android.permission.BIND_VOICE_INTERACTION"`. No new permission.

### 2.2 On (`AirplaneRoute.turnOn`, pure plan `airplaneOnPlan`, `AirplaneRouteTest`)

It runs on a background thread, under `AppEnforcer`'s monitor, so no `apply()` interleaves. Only the kid's row (and
§3.5's setup step) calls it.

1. **Refuse early** (the row says why, §4): the role isn't held or the VIS isn't ready, `DISALLOW_AIRPLANE_MODE` is
   set, or airplane mode is already on.
2. **The report first** (up to 2 s): the launcher posts a status report with `airplane_mode.going_on = true`, so the
   parent's page says "Flymodus på" rather than "not heard from" (§6.2). With no answer within 2 s, it goes on; the
   report waits in the queue as any other does.
3. **Show Settings to the route, only while kiosk is on:** if Settings is hidden, unhide it (M3, M4), and add
   `com.android.settings` to the lock-task packages. `AirplaneRoute.inFlight` (with its start time) makes the
   instant-suspend-on-install receiver and Home's app list ignore Settings' `PACKAGE_ADDED`/`PACKAGE_CHANGED` for
   the route's length. While Åpne for oppsett or the override runs, Settings is already shown and nothing changes.
4. `showSession(args with the nonce, 0)`. The session calls `startVoiceActivity` with `EXTRA_AIRPLANE_MODE_ENABLED =
   true`.
5. **Wait** for `ACTION_AIRPLANE_MODE_CHANGED` with `state = true`, the session's `onTaskFinished`, or 2 s.
6. **Put back, always** (a `finally`): the lock-task packages as `apply()` last wrote them, and Settings hidden again
   when step 3 unhid it. Hide the session. Clear `inFlight`.
7. **Record** the period's start (§5.1, `on_by: "kid"`) and re-render the row.

If the process dies between 3 and 6, the next `apply()` puts both back: the plan hides Settings and writes the
lock-task list, as it does today. If the kiosk has the block bit off (`block_activity_start` false), step 3 still
needs the lock-task list (§1.1).

Measured cost (M4): Settings is shown for about 0.3-0.5 s, and the kid sees nothing but the switch moving. During
that half second, a tap on a system notification that opens a Settings screen would get through. A kid can't time
that, and the screen it reaches is Android's Settings for that half second only.

### 2.3 Off (`AirplaneRoute.turnOff`)

Under the same monitor: if the parent's hardening switch has `DISALLOW_AIRPLANE_MODE` set already, airplane mode is
off and there is nothing to do. Otherwise add the restriction, wait for `ACTION_USER_RESTRICTIONS_CHANGED` and
`AIRPLANE_MODE_ON == 0` (3 s at most), then clear it. The clear runs in a `finally`, and `applyHardening` writes the
switch's own value on the next `apply()` anyway. **Off never needs the role or Settings,** so a kid can always leave
airplane mode, also after the role is lost (§3.6).

### 2.4 The parent's hardening switch "Blokker flymodus" (`disallow_airplane_mode`, default off)

It stays as it is: Android's own block, which only the server lifts (the hardening exception to the standing rule).
With it on, airplane mode can't be on at all: the kid's row reads "Bestemt av mamma og pappa" (§4), and the PWA's
Flymodus switch gets a sub-line (§6.1). Turning it on while the kid's airplane mode is on ends airplane mode (§1.1),
and the period records `off_by: "parent"`. The kid's switch never overrides it. The bit and the switch answer two
questions: the switch is "may this phone ever be in airplane mode", and the bit is "may Emma turn it on herself".

## 3. Onboarding

### 3.1 What the parent does on Android's page (M1)

"Digital assistant app" › **Default digital assistant app** › **Vibb One** › **OK**. Android's confirmation says the
assistant "will be able to read information about apps in use…". That isn't true for Vibb (§8), and the launcher's
row says so before the page opens: "Android spør om assistenten kan lese skjermen. Vibb One leser den aldri." / "Android
asks whether the assistant may read the screen. Vibb One never does."

### 3.2 The default-apps window (M2)

Every managed phone holds `DISALLOW_CONFIG_DEFAULT_APPS` (while Vibb holds the dialer or the SMS role, design 09/77),
and the pause doesn't lift it, because the call rules aren't lifted by the pause. Under it, Android's picker refuses
Vibb One. So the setup step lifts it for a short, PIN-gated window:

- The step starts only from the PIN-gated Settings (§3.3), while Åpne for oppsett or the Settings pause runs (Settings
  must be shown anyway).
- It stores `assistant_pick_until = now + 5 min` (DE prefs, with `BootClock` like the pause, so a reboot ends it).
  `lockDefaultAppsFor` gains `&& !assistantPickOpen(now)`, and `apply()` runs before Android's page opens.
- It ends when the page returns (the activity result), when 5 minutes pass (an alarm runs `apply()`), when the pause
  ends, or at a reboot. `apply()` then sets the restriction again, and `applyDialerRole` and `applySmsRole` take back
  a dialer or SMS role someone moved meanwhile (device-owner calls, no prompt), with the own-permission fixing they
  already do.

This is not the lift that QA of cmd-expiry (Medium 2) dropped: that one was opened by a prompt on Home, which the kid
could hold open. This one is the parent's, behind the parent PIN, at most 5 minutes, only while the phone is open for
setup, and the roles are taken back when it ends.

### 3.3 "Fullfør oppsett" in the launcher's Settings

The PIN-gated Settings' pause section ("Sett alle begrensninger på pause") already holds the grant rows
Bakgrunnstilkobling and Endre systeminnstillinger (`WriteSettingsAccess`, `systemPageAccess`). Two rows join them, with
the same states (granted / open now / pause first / wait / no page):

- **Digital assistent** - "Vibb One er valgt" / "Vibb One is chosen", or "Velg Vibb One - trengs for Flymodus" /
  "Choose Vibb One - needed for Flymodus". A tap opens §3.2's window, then `ACTION_VOICE_INPUT_SETTINGS`, explicit to
  the system Settings component (`systemPageIntent`). On return it reads `isRoleHeld` and asks for a sync when it
  changed.
- **Wi-Fi i flymodus** (§3.5) - "Wi-Fi blir på i flymodus" / "Wi-Fi stays on in airplane mode", or "Ikke satt opp" /
  "Not set up". Shown once the role is held.

**One entry point during setup.** While the phone is open (Åpne for oppsett, or the Settings pause) and any of the four
rows isn't done, the launcher posts one quiet notification (LOW, a new channel `launcher:setup`, not on the
lock screen): "Fullfør oppsettet av Vibb One" / "Finish setting up Vibb One", "2 steg igjen" / "2 steps
left". A tap opens Settings at the section, behind the parent PIN as always. The notification goes when the steps are
done or the phone locks again. The kid may see it during a setup window. It leads only to the PIN.

### 3.4 At enrollment, and on a phone already in use

**A new phone.** The QR enrolls the phone, and its first policy starts the kiosk within seconds, so there is no
moment before the lock to do this. The PWA's QR page's "Ferdig" goes to the phone's summary, which shows an
**Oppsett** card while the latest report lists unfinished steps: "Fullfør oppsettet på telefonen: åpne den for oppsett,
og trykk på varselet «Fullfør oppsettet av Vibb One»." with the Åpne for oppsett button in the card (it posts to the
existing `/start-pause`, `back=summary`). The card lists the steps left in plain words (Bakgrunnstilkobling, Endre
systeminnstillinger, Digital assistent, Wi-Fi i flymodus). It goes when they are done, and the parent can hide it
("Ikke nå", stored per device). The runbook `docs/setup/google-account.md` gets the steps in its "On an enrolled phone"
list, since the parent opens the phone for setup for the Google account anyway. D2 asks whether the QR page should
offer "Åpne for oppsett nå" itself.

**A phone already in use:** the same card, the same notification, the same rows. No adb. `adb shell cmd role
add-role-holder android.app.role.ASSISTANT me.vibb.launcher` goes in the runbook as a shortcut for a phone with USB
debugging allowed, never as the way.

### 3.5 Wi-Fi in airplane mode, folded into the same step

The parent's one privileged Wi-Fi tap during airplane mode is what Android remembers (§1.1). The row "Wi-Fi i
flymodus", during the setup window:

1. turns airplane mode on through §2.2 (no lock-task step: Settings is shown),
2. opens Settings' Wi-Fi panel (`Settings.Panel.ACTION_WIFI`), with the line "Slå på Wi-Fi her, og gå tilbake" / "Turn
   on Wi-Fi here, then go back",
3. on return reads `WifiManager.isWifiEnabled` while airplane mode is on. True: the phone stores `wifi_kept = true`;
   false: nothing is stored, and the row stays "Ikke satt opp",
4. ends airplane mode through §2.3.

Android shows its own "Wi-Fi stays on in airplane mode" notification once. Vibb can't read `wifi_apm_state` (a hidden
Secure key; apps get `SecurityException` since Android 12), so the stored value is what the phone saw. Every later
airplane mode with Wi-Fi on checks it again: 3 s after §2.2, Wi-Fi still enabled keeps `true`, and Wi-Fi gone writes
`false` and reports it. On a phone without the memory (`apm_enhancement_enabled` off, an OEM choice), the step ends
`false` with "Denne telefonen slår av Wi-Fi i flymodus" / "This phone turns off Wi-Fi in airplane mode". The Jelly Star
is a device check.

The step is optional: Flymodus works without it, and then airplane mode is fully offline.

### 3.6 The role lost

Detected by `isRoleHeld` on every `apply()` and on Kid Settings' resume, and by the VIS's `onShutdown` (there is no
public broadcast for a role change). A change asks for one sync
(`roleReportNeeded`'s pattern, once per change). Then:

- **the kid's row** hides when airplane mode is off, and stays as an Off switch while airplane mode is on (§2.3),
- **the PWA** shows a warning on the summary and the About This Phone row (§6),
- nothing on the phone takes the role back. It can't.

How it could be lost: someone chose "None" or another app during a setup window (M2: "None" isn't blocked), the
parent chose another app, Vibb was uninstalled, or a Google app with a privileged role call (Gemini's switch flow on
some phones) took it. The last one was not measured. Under the kiosk the Google app is hidden unless the parent
allowed it, and the report catches every case.

## 4. The kid's row: "Flymodus" (pure `airplaneRow`, `KidSettingsModelTest`)

The first row of "Tilkobling og skjerm", above Wi-Fi: it decides what the rows under it can do. `controlsSection` gains
`Rows.airplane` (bit 64), `describe()` names it, and a mask of only 64 is a card, not `NoneEnabled`.

| State | Switch | Line under it (nb / en) |
|---|---|---|
| bit 64 off | no row | - |
| role not held, airplane mode off | no row (the parent's page says why, §6) | - |
| `DISALLOW_AIRPLANE_MODE` set (the parent's switch) | Off, 50 %, not clickable | "Bestemt av mamma og pappa" / "Set by mum and dad" |
| busy (§2.2 or §2.3 running) | as it will be, not clickable | "Slår på flymodus…" / "Turning on airplane mode…" (off: "Slår av…" / "Turning off…") |
| on, Wi-Fi up | On | "Ingen mobilnett. Wi-Fi er på. Nødsamtaler virker." / "No mobile network. Wi-Fi is on. Emergency calls still work." |
| on, no Wi-Fi | On | "Ingen nett. Nødsamtaler virker." / "No network. Emergency calls still work." |
| off | Off | - |
| the last turn-on failed | Off | "Fikk ikke slått på flymodus. Prøv igjen." / "Couldn't turn on airplane mode. Try again." (until the next tap or 1 min) |

While airplane mode is on, the rows under it say so: **Wi-Fi** is read-only with "Kan ikke endres i flymodus" / "Can't
be changed in airplane mode" (`setWifiEnabled` is refused, §1.1); **Mobildata** reads "Flymodus er på" / "Airplane
mode is on"; Bluetooth is measured in the build (§12 test 6), and gets the same line if `enable()` is refused.

A tap is a plain switch tap, so it works the same with gesture navigation and with the three buttons. The route never
leaves the kid's screen (M4), and the session hides itself on every outcome (§2.1). Back on the voice activity's frame
(if it ever stays) finishes it. The kid's tap also calls `CommandListenerService.requestSync` after the off, so the
parent's page follows. Under a time rule Kid Settings isn't reachable, as today. The pause and the override don't
change airplane mode or the row.

## 5. The report and the contract

### 5.1 The report: `airplane_mode` (a full-report field, not a heartbeat field)

```json
"airplane_mode": {
  "on": false,
  "going_on": false,
  "assistant": true,
  "wifi_kept": true,
  "last_error": null,
  "periods": [
    {"on_ms": 1763150580000, "on_by": "kid", "off_ms": 1763183400000, "off_by": "kid"}
  ]
}
```

- `on`: `AIRPLANE_MODE_ON`. `going_on`: true only in §2.2 step 2's report.
- `assistant`: `RoleManager.isRoleHeld(ROLE_ASSISTANT)`. `wifi_kept`: `true` / `false` / `null` (never set up).
  `last_error`: `null`, or the last failed turn-on within 24 h: `route_missing` (no voice activity), `refused`
  (the restriction was set), `timeout`.
- `periods`: at most 10, from the last 7 days, oldest first. `on_by`: `kid` (§2.2 within 5 s) or `other` (Quick
  Settings during a setup window, §3.5's step, anything else). `off_ms`/`off_by` are `null` while it lasts. `off_by`:
  `kid` (§2.3), `parent` (the hardening switch went on), `emergency` (airplane mode went off during an emergency call,
  or at most 10 s after `CallSystem.emergencyPlaced`), or `other`. The phone keeps the periods in DE prefs (a small
  JSON, `AirplaneLog`), written at each edge from `ACTION_AIRPLANE_MODE_CHANGED` (a manifest-free receiver registered by
  the anchor service, plus a check of the stored state at process start and boot, M7).

The field changes rarely, so it stays out of `status_heartbeat_fields`: a change changes the report's hash, and a
full report goes (design 95-T2). Capability `airplane_mode_v1`.

### 5.2 The contract: a new file `server/testdata/airplane_mode.json`

A new file, not `mobile_network.json`: this is about the role and the radio, and that file is about data. Byte-identical
in `launcher/app/src/test/resources/`. Its parts: `capability`, `bit` (64), `rows` (`{mask, role, restricted, on,
wifi_up, busy}` -> the row's state, §4), `report` (the exact object above, and one with an open period), `clean`
(the server's field-by-field cleaning: unknown `on_by`/`off_by` words dropped to `other`, more than 10 periods cut to
the newest 10, `off_ms` before `on_ms` dropped), and `emergency` (the rows of §7.1's rule: a call time and a period ->
whether the call's notice gets the airplane line). The launcher's `AirplaneModeTest` runs `rows` through
`airplaneRow`, encodes `report` exactly and runs `emergency`'s rule on its side; the server's `tests::airplane_mode`
posts `report`, checks what is stored, runs `clean` and `emergency`; both compare the copies. Root `CLAUDE.md`, the
three workflows' path lists and `intake_limits` get the file. No policy key changes: bit 64 rides in the existing
`quick_controls_mask`.

## 6. Server and PWA

### 6.1 The Home Screen page's quick controls (`/devices/{id}/home#quick-controls`)

A new switch row "Flymodus", `name="quick_control_airplane"`, in the `section=quick` form, shown only for a phone that
reported `airplane_mode_v1`. `update_policy` sets bit 64 from it like the five others, and keeps bit 32 as today
(`(quick_controls_mask & 32) | ?`). A sub-line under the label, only when it says something:

- the role isn't held: "Vises for Emma når Vibb One er valgt som digital assistent. Se Oppsett på telefonsiden." /
  "Shows for Emma once Vibb One is chosen as the digital assistant. See Setup on the phone's page.",
- the hardening switch is on: "Flymodus er blokkert under Avansert › Begrensninger." / "Airplane mode is blocked under
  Advanced › Restrictions.",
- `wifi_kept` is `false` or `null` while the bit is on: "Uten Wi-Fi i flymodus er telefonen helt uten nett når
  flymodus er på." / "Without Wi-Fi in airplane mode the phone is fully offline while airplane mode is on."

`DEFAULT_QUICK_CONTROLS` gains bit 64 if D1 says so. The form's ⓘ gets one sentence on what Flymodus does and that
112 still works. Autosave lands on `#quick-controls`, never the top. This page is a phone sub-page: no bell.

### 6.2 The summary and About This Phone

- **Summary, the state lines:** "Flymodus på siden 21:03" / "Airplane mode on since 21:03" when the latest report has
  `going_on` or `on` (the phone reported it before going quiet, so the "not heard from" line yields to it); "Flymodus
  var på 21:03–07:10" / "Airplane mode was on 21:03–07:10" for a period that ended within the last 12 hours; "Flymodus
  ble slått av av en nødsamtale 14:02" when `off_by` is `emergency` (§7.1). Times in the phone's zone as other
  lines.
- **Summary, the Oppsett card** (§3.4) while steps remain. The role lost after it was held (§3.6) shows as a warning
  line on the summary, "Flymodus virker ikke: Vibb One er ikke lenger digital assistent." / "Airplane mode doesn't
  work: Vibb One is no longer the digital assistant.", with the steps behind the ⓘ.
- **About This Phone:** a row "Digital assistent" / "Digital assistant": "Vibb One" or "Ikke valgt" / "Not chosen"
  with the steps as its detail (like `write_settings_steps`), and a row "Wi-Fi i flymodus" / "Wi-Fi in airplane mode":
  "Blir på" / "Stays on", "Slås av" / "Turns off" or "Ikke satt opp" / "Not set up".

### 6.3 Storage, migration `0146_airplane_mode.sql`

`device_status.airplane_mode_json` (TEXT, the cleaned object), read by `phone_status` for the lines. The summary's
"since" time is the open period's `on_ms` or the `going_on` report's time. No new table: the periods ride on every
full report, and the latest report holds the last 10. Retention as `device_status`.

### 6.4 An older launcher, an older server

- An older launcher on this server: no capability, so no Flymodus row on the Home page and no card step for the role.
- This launcher on an older server: bit 64 never comes, so no kid row. The report's `airplane_mode` is an unknown key,
  which the older intake drops. The setup rows in the launcher's Settings still work, since they need no server.

## 7. Emergency and the edge cases

### 7.1 After a 112 call

Android turns airplane mode off for the call and leaves it off (§1.1). **Recommended (D3): leave it off, and tell the
parent.** The callback (ECBM, about 5 minutes) and any follow-up call need the radio, and a kid who just called 112
should be reachable. The phone records `off_by: "emergency"`. The server adds one line to the existing
`emergency_call` event when the report's period ended within 10 minutes of the reported call ("Flymodus ble slått av
av samtalen." / "The call turned airplane mode off."), as a typed parameter of the event (`airplane_off: true`), never
phone text. No new notification kind. The emergency menu's other numbers (design 73) turn airplane mode off only when
Telephony counts them as emergency numbers for the SIM and network. 112 always does.

### 7.2 The other cases

- **Reboot in airplane mode:** it stays on (M7), and so does the role. At boot the launcher reads the state, keeps the
  open period, and the row is On. Off works at once (§2.3 needs no Settings or VIS).
- **Screen off, the keyguard:** the kid's row is only reachable unlocked. The route works at the secure keyguard and
  with the screen off (M5, M6). That matters only for §9.3.
- **The pause and the override:** they don't touch airplane mode. The kiosk is off then, so §2.2 skips step 3. The
  assist gesture works then (M9) and opens nothing (§2.1).
- **Unmanaged:** no policy, no row. Airplane mode is Android's own then.
- **Vibb's own connection:** in airplane mode without Wi-Fi the SSE stream drops and syncs fail fast, as with any
  lost network. The backstop keeps its 30 minutes. With Wi-Fi kept, everything runs as on any Wi-Fi.
- **The DNS filter and Bare Vibb (design 95/97):** unchanged. With no network there is nothing to filter, and on kept
  Wi-Fi the filter shape applies.
- **Two quick taps:** the row is not clickable while busy, and `AirplaneRoute` takes one request at a time.

## 8. Never: no screen reading

The assistant role lets the system hand the assistant the screen's text and a screenshot. **Vibb takes neither:**

- `VibbAssistantService.onReady` calls `setDisabledShowContext(SHOW_WITH_ASSIST or SHOW_WITH_SCREENSHOT)`. Measured
  (M8): no assist data, structure, content or screenshot arrives, with Android's own switches on.
- `VibbSession` overrides `onHandleAssist` and `onHandleScreenshot` empty, never calls `requestAssist` or reads
  `AssistStructure`, and logs neither.
- `AssistantInvariantsTest` scans the production sources: `setDisabledShowContext` is called with both flags in
  `onReady`, and the names `AssistStructure`, `AssistContent`, `requestAssist`, `getAssistData` and
  `onHandleScreenshot`'s bitmap never appear outside those empty overrides.
- Voice commands ("hey…") and "Vis mamma dette" (show a screen to a parent) are out, on the long list.

## 9. Options, not approved (D4-D6 in §13)

### 9.1 Battery saver ("Strømsparing") through the same route

`Settings.ACTION_VOICE_CONTROL_BATTERY_SAVER_MODE` with `EXTRA_BATTERY_SAVER_MODE_ENABLED` goes through the same
session. A device owner can't set battery saver otherwise. Off works the same route (no restriction exists for it).
A row "Strømsparing" (bit 128) with the same states. Not measured. Size **S** (launcher S, server XS).

### 9.2 The quick panel: falls as asked

**Measured (M10): the assist gesture never fires under lock task on a managed phone.** `LockTaskController` always sets
`DISABLE_SEARCH`, and no lock-task feature clears it. So a panel opened by long-pressing power or Home, or by the
corner swipe, **is not possible on a kid's phone.** It fires only with the kiosk off, and over the keyguard too (M11),
where the user doesn't want it. The design makes the session do nothing then (§2.1).

What remains, if the user still wants a kid-chosen panel "over any app": **the notification shade.** Lock task keeps
the shade (`LOCK_TASK_FEATURE_NOTIFICATIONS` is in Vibb's default features), so a pull-down works over any allowed app.
One ongoing notification "Snarveier" / "Shortcuts", at MIN importance (no status-bar icon, no sound), with
`VISIBILITY_SECRET` (never on the lock screen) and a custom expanded view of the kid's items as buttons. Each button is
a `PendingIntent` to Vibb's receiver or activity. Not measured: how the custom view looks in the Jelly Star's shade at
320 dp, and whether every OEM shade lets an ongoing MIN notification stay expanded. Size **M** (launcher M, server S).
A Home-only pull-down panel is the small alternative (S), but it isn't "over any app".

**Where the kid edits it:** Kid Settings › "Snarveier": the items the parent allows, each with a switch, at most 6
chosen, ordered with up and down buttons (dragging alone fails TalkBack). Stored on the phone (DE prefs), reported as
`kid_panel: {"items": [...]}`, and shown on the Home Screen page as a read-only line ("Emma har valgt: Ring Mamma,
Flymodus, Lommelykt").

**How the parent bounds it:** one new bit "Snarveier" (shows the panel at all), and **no per-item switches**: an
item is available when its own gate is open. The parent who hides Wi-Fi in Kid Settings hides it from the panel too.

| Item | What it needs | Possible | Its gate |
|---|---|---|---|
| Ring Mamma (the kid picks a contact) | `CallSystem.placeCall` (CALL_PHONE held); the call rules decide as today | yes | the contact is callable now (call rules, time rules) |
| Flymodus | this design | yes | bit 64, the role, not the hardening switch |
| Lommelykt | `CameraManager.setTorchMode`: **no permission** (design 41's `Torch`), 10-min auto-off, refused while the camera is disabled at the lock | yes (not on the emulator: no flash) | the phone has a flash |
| Lyd (lyd / vibrer) | design 18's `SoundMode`. Silent (do not disturb) needs notification-policy access, which a device owner can't grant: ring and vibrate only | yes | bit 8 |
| Lysstyrke | brightness `setSystemSetting` (device owner). A panel button steps it (low / middle / high) | yes | bit 4 |
| Wi-Fi | `setWifiEnabled` (device owner); refused in airplane mode | yes | bit 1 |
| Bluetooth | `BluetoothAdapter.enable/disable` (device owner) | yes | bit 2 |
| Mobildata | design 97's kid switch | yes | bit 32 |
| Musikk (spill / pause / neste) | the music app's now-playing session Home already shows (design 21 step 4) | yes | the music app installed and allowed |
| Be om mer tid | design r13's time request | yes | a screen-time budget or rule applies and requests are on |
| Send posisjonen min | a fresh fix and a report now, plus a new parent event "Emma sendte posisjonen sin" | yes, new event kind (S) | location mode isn't Off |
| Nød | design 73's emergency menu | yes | always (and Android's own emergency button stays) |
| Strømsparing | §9.1 | only if D4 | bit 128 |
| Kamera | opens the allowed camera app | yes | a camera app allowed and not locked |
| Dagen (suggested) | opens Dagen (design 69) | yes | Dagen shown on Home |
| Skjermtid igjen (suggested) | a display, "48 min igjen", no action | yes | a budget applies |
| Alarm (suggested) | opens the allowed clock app | yes | an alarm app spared (always) |

### 9.3 Flymodus from the PWA

**On, remotely:** the server nudges, the phone runs §2.2 (it works with the screen off and at the keyguard, M5, M6).
But the phone then goes offline, and **the parent can't turn it off again remotely** unless Wi-Fi was kept (§3.5)
and the phone is on a known Wi-Fi. So if it's built at all, it should be **"Flymodus til kl. 07:00"**: the phone ends
it by itself at the time (an exact alarm and §2.3, offline), and the kid can still turn it off from the row. Size
**M** (launcher S-M, server S). Not recommended in this round.

## 10. Risks

- **The route rests on a Settings activity meant for voice assistants.** An OEM could leave `AirplaneModeVoiceActivity`
  out, and Android could change it. On the Jelly Star it is a device check. A missing activity gives
  `ActivityNotFoundException` on a shown Settings: the row then shows "Fikk ikke slått på flymodus", and the
  report carries `last_error: "route_missing"` (one more report key, in the contract) for the PWA's line.
- **Settings shown for half a second** (§2.2). Measured 0.3-0.5 s. The broadcasts it causes are filtered by
  `inFlight`.
- **The 5-minute default-apps window** (§3.2): the phone and SMS roles can move during it. They are taken back at its
  end with device-owner calls, as when calls become managed.
- **The stub recognizer:** the assistant role also makes the VIS's recognizer the system default. An app that uses
  `SpeechRecognizer.createSpeechRecognizer` with the default service gets `ERROR_CLIENT`. Gboard's voice typing uses its
  own recognizer. To measure in the build (§12 test 7): Gboard's mic in Meldinger and in an allowed app.
- **The role lost silently** to a privileged Google flow (§3.6): not measured; the report catches it within a sync.
- **Wi-Fi memory on the Jelly Star** depends on the OEM's `apm_enhancement_enabled`. Without it Flymodus is offline.
- **The kid can be unreachable.** That is the feature. The report-first step (§2.2) and the summary line make it
  visible, and 112 always works.

## 11. Build steps and sizes

1. **Contract, both directories, one commit, S.** `airplane_mode.json` and its copy, the DTOs, `clean`, the capability,
   the root `CLAUDE.md` paragraph and the workflows' path lists.
2. **Launcher: the assistant and the route, M.** The three services and the XML, `AirplaneRoute` (on, off, the
   lock-task and hide steps, `inFlight` in the package receiver), `AirplaneLog` and the edge receiver, the report
   field, `AssistantInvariantsTest`, `AirplaneRouteTest` (the plan's steps and the `finally` on every failure).
3. **Launcher: onboarding, S-M.** The two Settings rows, §3.2's window in `lockDefaultAppsFor` with its alarm and
   boot end, the setup notification, the role-change sync. Tests: the window's ends, the rows' states.
4. **Launcher: the kid's row, S.** Bit 64 in `controlsSection`, `airplaneRow`, the Wi-Fi/Mobildata lines in airplane
   mode, strings nb + en. `KidSettingsModelTest` covers a mask of 64 alone.
5. **Server, S-M.** Migration 0146, intake and cleaning, the Home form's row and sub-lines (bit 32 kept on both
   sections, a test), the summary lines and the Oppsett card, About This Phone's rows, the emergency line,
   `DEFAULT_QUICK_CONTROLS` per D1, nb + en. Page tests: anchors, no bell, both languages.
6. **Emulator run (§12), S-M.**

Total: launcher **M**, server **S-M**. Options: §9.1 **S**, §9.2 **M**, §9.3 **M**.

## 12. Emulator tests (`jelly36` restored clean, `docs/testing/emulator.md` §6am)

The debug launcher of the branch as device owner, enrolled to a debug server of the branch, a phone made through the
PWA's Add Phone. Gesture navigation and 3-button navigation for 2 and 4.

1. **Onboarding:** Åpne for oppsett -> the notification -> the PIN -> "Digital assistent" -> Android's page -> Vibb
   One -> OK -> back. The row reads chosen, the report `assistant: true`, the card's step gone. Without §3.2's window
   (a test build flag) the picker refuses Vibb (M2). After 5 minutes, or at the pause's end, `dumpsys user` shows
   `disallow_config_default_apps` again and the dialer and SMS roles are Vibb's.
2. **On and off under the kiosk:** the kid's row On -> airplane mode on within 1 s, still LOCKED in Home's task,
   Settings hidden again (`dumpsys package com.android.settings | grep hidden`), the lock-task packages as before, the report with `going_on` reached the server first. Off -> airplane
   mode off within 3 s, `DISALLOW_AIRPLANE_MODE` not set afterwards.
3. **The parent's switch:** "Blokker flymodus" on while airplane mode is on -> it ends, `off_by: "parent"`, the row
   50 % "Bestemt av mamma og pappa". Off again -> the row works.
4. **Wi-Fi in airplane mode:** the setup step, Android's notification once, `wifi_kept: true`. Then the kid's On keeps
   Wi-Fi up and the server reachable, and the summary reads "Flymodus på siden …" from a live report.
5. **112:** airplane mode on, the lock's Emergency call to 112 (the emulator's modem) -> airplane mode off, the call
   placed, `off_by: "emergency"`, the server's emergency event with the airplane line.
6. **Bluetooth in airplane mode:** Kid Settings' Bluetooth switch -> on or refused, and the line matches.
7. **The recognizer:** Gboard's mic in Meldinger.
8. **Reboot in airplane mode:** on after boot, the row On, Off works before the first sync.
9. **The role lost:** during a setup window choose "None" -> the row hides (airplane mode off), the report
   `assistant: false`, the summary's warning.
10. **The assist gesture:** under the kiosk, long-press power and the corner swipe do nothing (M10). During Åpne for
    oppsett, they open no window (§2.1).
11. **The PWA:** both languages, the Home form keeps bit 32 on `quick` and `all`, every save on its anchor.

Not on the emulator: the Jelly Star's `AirplaneModeVoiceActivity` and Wi-Fi memory, and its long-press power. Those come
from the RC on the Jelly Star, through the reports, with no adb and no debug build.

## 13. Decisions for the user

- **D1. A new phone's default for the kid's Flymodus switch (bit 64).** Recommended: **on** for new phones. The row
  stays hidden until the parent has done the assistant step, so the kid never sees a dead switch, and the user asked
  for Flymodus in the kid's settings. Existing phones keep their mask (the parent turns it on). The alternative is off
  until the parent turns it on, one more step.
- **D2. The QR page's "Ferdig" offers "Åpne for oppsett nå".** Recommended: **yes**. It is the existing start-pause,
  one button where the parent stands after the QR, and it starts the setup steps (the Google account included). XS.
- **D3. After a 112 call in airplane mode.** Recommended: **leave airplane mode off, and add the line to the parent's
  emergency notice** (§7.1). The alternative, turning it back on after the callback mode ends, could cut off a
  callback.
- **D4. Battery saver through the same route (§9.1).** Recommended: **not now**. Android's own battery saver
  starts by itself at 15 % on most phones, and it adds a row the kid rarely needs. S.
- **D5. The quick panel (§9.2).** The assist gesture can't open it on a managed phone (measured). Recommended: **not
  this round**; if wanted, the shade notification "Snarveier" as its own design, with the item list above. M.
- **D6. Flymodus from the PWA (§9.3).** Recommended: **not now**; if wanted, only as "Flymodus til kl." with the end
  on the phone. M.
- **D7. A push to the parent when the kid turns on airplane mode.** Recommended: **no push**: the summary line says
  "Flymodus på siden 21:03" (§6.2), and the 112 case has its line in the emergency notice. A switch in the
  notification settings is S if wanted.

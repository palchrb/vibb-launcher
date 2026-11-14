# Design 98: Flymodus in the kid's settings, through the Digital assistant role

Status: design, **revision 2 (design QA)**, 2026-11-14 (branch `design-98`). Revision 1 was 82abae18. The design QA
is `docs/review/qa-98-design.md` (1 High, 9 Medium, 8 Low), and §14 says how each finding was handled.

Approved by the user on 2026-11-14: real Android airplane mode as a row in the kid's settings, made possible by making
Vibb One the phone's **Digital assistant**, on the condition that the assistant step is easy when a new phone is
enrolled. Both directories. There is a new shared contract `server/testdata/airplane_mode.json`, migration
`0146_airplane_mode.sql` (0145 is `abroad`; recheck at merge), and `quick_controls_mask` bit **64**. Size: launcher
**M**, server **S-M** (§11). §13 holds the decisions this design needs before it is built. Battery saver and Flymodus
from the PWA (§9) are not approved and are asked there.

The user (2026-11-14): *"om vi kan gjøre on-boarding av assistenten enkelt når man in ruller er en ny tlf, så er jeg ok
med å ta det med, og ta med flymodus i barnets settings"*. Later the same day the user approved **Snarveier** (a
kid-chosen panel of up to six round buttons in the shade notification) as **its own design, design 99**. Flymodus
becomes one of its items. This design doesn't specify Snarveier (§9.2).

**The short version.** A device owner cannot turn airplane mode on, and Quick Settings is always off under lock task.
The one route that works without any new permission: the app that holds the **assistant role** shows a voice session
of its own, and that session asks Settings' `AirplaneModeVoiceActivity` to turn airplane mode on. Turning it off needs
no role: setting and then clearing `DISALLOW_AIRPLANE_MODE` ends airplane mode. The parent chooses Vibb One as the
Digital assistant once, in three taps on Android's own page, from a new "Fullfør oppsett" ("Finish setup") step in the
launcher's PIN-gated Settings during Åpne for oppsett. The role can't be granted by a device owner, through a role
request dialog (`requestable="false"`), or by any intent an app may send directly.

**Measured for this design (§1.2):**

- The assist gesture never reaches the assistant under lock task, so a panel opened by the assist gesture isn't
  possible on a managed phone.
- Under `DISALLOW_CONFIG_DEFAULT_APPS`, which every managed phone holds, Android's picker refuses every app except
  "None". So the onboarding needs a short, PIN-gated lift of that restriction (§3.2).
- Settings is hidden on a managed phone, and a hidden Settings breaks the route. So the route shows Settings briefly,
  through `LockTaskChrome` and with the status bar disabled (§2.2).

## 1. The facts this rests on

### 1.1 From the earlier research (the same AVD, a throwaway probe as device owner and assistant)

- **No device-owner API sets airplane mode.** `setGlobalSetting(AIRPLANE_MODE_ON)` throws `SecurityException`,
  `ConnectivityManager.setAirplaneMode` is signature-only, and the Telephony radio and SIM power calls need
  `MODIFY_PHONE_STATE`. Quick Settings is always disabled under lock task (`DISABLE2_QUICK_SETTINGS`), and no API
  picks Quick Settings tiles. Settings' airplane page can't be contained: it leads on to the SIMs, VPN, Private DNS,
  Data Saver and more.
- **On:** the assistant's `VoiceInteractionService.showSession(args)` -> the session's
  `startVoiceActivity(Intent(Settings.ACTION_VOICE_CONTROL_AIRPLANE_MODE).putExtra(EXTRA_AIRPLANE_MODE_ENABLED, true))`
  -> Settings' translucent `AirplaneModeVoiceActivity` sets airplane mode as the system and finishes. It takes about
  300 ms. Under lock task, `com.android.settings` must be on the lock-task packages, or the start is blocked. We stay
  in our own task, LOCKED. On a refusal the session window stays up.
- **Off:** add and then clear `DISALLOW_AIRPLANE_MODE` (device owner). Android ends airplane mode when the restriction
  is set, and the voice route is refused while it is set. The restriction is applied asynchronously, so the clear waits
  for `ACTION_USER_RESTRICTIONS_CHANGED`, as design 94's relock does.
- **Wi-Fi:** Vibb can't turn Wi-Fi on while airplane mode is on. `setWifiEnabled` is refused even for a device owner
  (`WifiServiceImpl`: "only Settings can toggle wifi").
  - Wi-Fi stays on through airplane mode only through Android's memory. With `apm_enhancement_enabled`, a privileged
    UI (Settings, Quick Settings) turning Wi-Fi on once during airplane mode writes `Settings.Secure.wifi_apm_state = 1`.
    Android then shows a one-time notification "Wi-Fi stays on in airplane mode".
  - After that, airplane mode on kept Wi-Fi up and validated.
  - Turning Wi-Fi off from a privileged UI during airplane mode resets the memory
    (`WifiSettingsStore.handleWifiToggled`).
  - Bluetooth has `bluetooth_apm_state` (not tested).
- **Emergency:** a 112 call in airplane mode turns airplane mode off and places the call. Airplane mode **stays off**
  afterwards, and Emergency Callback Mode lasts about 5 minutes.

### 1.2 Measured for this design (`jelly36`, Android 16 Play image, gesture navigation, 2026-11-14)

The probe from §1.1 was extended (scratchpad `d98/probe/`). The session logs its `onShow` args and flags, shows a
small dark panel when it isn't ours, and acts only on its own marker. The VIS sets
`setDisabledShowContext(SHOW_WITH_ASSIST | SHOW_WITH_SCREENSHOT)` in `onReady`. The run started with `-wipe-data`.
Afterwards `jelly36` was put back from `~/kid-look-1-avd-pre-start` (22 of 22 SHA-256 sums match). Nothing touched a
phone. Each step was its own adb broadcast, so the times are per step, not end to end.

| # | What | Result |
|---|---|---|
| M1 | The Android page | `Settings.ACTION_VOICE_INPUT_SETTINGS` resolves to `com.android.settings/.Settings$ManageAssistActivity` ("Digital assistant app"). Its first row, "Default digital assistant app", opens PermissionController's `DefaultAppActivity` with None, the probe and Google. Choosing the probe shows a confirmation ("The assistant will be able to read information about apps in use…", Cancel / OK). After OK, `isRoleHeld(ASSISTANT)` is true. **Three taps.** `Intent.ACTION_MANAGE_DEFAULT_APP` (the direct picker) is protected by `MANAGE_ROLE_HOLDERS`. `android.settings.MANAGE_DEFAULT_APPS_SETTINGS` opens the list of all default apps |
| M2 | The picker under `DISALLOW_CONFIG_DEFAULT_APPS` | The apps are greyed, and tapping Google gives "Blocked by work policy". **"None" stays enabled, and choosing it removed the role** |
| M3 | Settings hidden (`setApplicationHidden`, as `AppEnforcer` does on a managed phone) | The route fails: `ActivityNotFoundException`, "Cannot find ActivityInfo in startVoiceActivity". `setPackagesSuspended(com.android.settings)` is **refused** (returned as failed), so a managed phone's Settings is hidden but never suspended |
| M4 | Unhide, then the route (lock task LOCKED, features 127, Settings on the lock-task packages) | Settings resolved at once after the unhide. Airplane mode was on 270 ms after the session started. The unhide sends `PACKAGE_ADDED` and `PACKAGE_CHANGED` for Settings, and the hide sends `PACKAGE_REMOVED` and `PACKAGE_CHANGED`. We stayed LOCKED in our task |
| M5 | The route at the secure keyguard (PIN set, screen on, no lock task) | Airplane mode on in about 190 ms. Nothing showed on screen |
| M6 | The route with the screen off | Airplane mode on in about 60 ms. The screen stayed off |
| M7 | The role across an app update (`install -r`) and a reboot | Kept both times. Airplane mode also survives a reboot |
| M8 | Assist data | With `setDisabledShowContext(3)`: `onHandleAssist` got no data, no structure and no content, and `onHandleScreenshot` got `null`, also from the assist gesture, with Android's "Use text from screen" and "Use screenshot" both on |
| M9 | **The assist gesture, no lock task** (baseline) | `KEYCODE_ASSIST` (invocation type 7), long-press power with `power_button_long_press = 5` (type 6) and the gesture-navigation corner swipe (type 1) each opened the session |
| M10 | **The assist gesture under lock task (LOCKED), unlocked** | **Never.** Tried `KEYCODE_ASSIST`, long-press power and the corner swipe, with features 63 (Vibb's default set) and 127 (with the block bit). `dumpsys statusbar` shows `mDisabled1=0x6000000`, which includes `DISABLE_SEARCH`. AOSP `LockTaskController.getStatusBarDisableFlags` starts from `DISABLE_MASK`, and no lock-task feature clears `DISABLE_SEARCH` |
| M11 | The assist gesture at the keyguard, no lock task | It fired (`invocation_phone_state=2`, locked), and the session showed **over** the keyguard |

What this means:

- On a managed phone the kiosk is on, so the assist gesture never reaches Vibb while unlocked (M10).
- That it never reaches Vibb **at the keyguard under lock task** too is inferred from AOSP and was not measured
  (device check, §12 test 10). The flags come from the lock task, which the keyguard doesn't end. It is harmless
  either way, since §2.1 hides an unmarked session.
- The gesture fires while the kiosk is off: Åpne for oppsett, the parent override, an unmanaged phone (D9).

## 2. The route

### 2.1 The assistant's parts (launcher, `assistant/`)

- **`VibbAssistantService : VoiceInteractionService`** (permission `BIND_VOICE_INTERACTION`):
  - In `onReady` it calls `setDisabledShowContext(SHOW_WITH_ASSIST or SHOW_WITH_SCREENSHOT)` (M8), publishes itself to
    `AirplaneRoute`, and ends §3.2's default-apps window if one is open (the role just became ours).
  - In `onShutdown` (the role moved away, or the service was unbound) it clears that and asks for a role report (§3.6).
- **`VibbSessionService : VoiceInteractionSessionService`** (permission `BIND_VOICE_INTERACTION`) -> `VibbSession`:
  - **It acts only on Vibb's own marker:** `args` must carry `me.vibb.assistant.ROUTE` with the 128-bit nonce that
    `AirplaneRoute` made for this one request (process memory, used once).
  - `onCreate` calls `setUiEnabled(false)` (API 29), so the session never adds a window that could take touches.
  - Any other `onShow` (the assist gesture, a stale request) calls `hide()` at once and starts nothing (§1.2 and D9).
  - A marked request is served also at the keyguard (M5); only §9.3 would use that.
  - Every assist callback is overridden empty: `onHandleAssist` (all overloads), `onHandleAssistSecondary` and
    `onHandleScreenshot`. They read nothing and log nothing (§8).
  - The session hides itself on `onTaskFinished`, on a `startVoiceActivity` exception, and at the route's deadline.
- **`VibbRecognizer : RecognitionService`**, exported with the `android.speech.RecognitionService` intent filter and
  no permission. It is a stub the role requires: `VoiceInteractionServiceInfo` refuses a service without one. Every
  `onStartListening` answers `ERROR_CLIENT` at once. The launcher declares no `RECORD_AUDIO`, and its self-grant list
  never gains it (§8's test checks the manifest).
- **`res/xml/vibb_voice_interaction.xml`:** `sessionService`, `recognitionService`, `supportsAssist="true"` (required
  for the role) and `supportsLaunchVoiceAssistFromKeyguard="false"`. No new permission is requested.

### 2.2 On (`AirplaneRoute.turnOn`; pure plan `airplaneOnPlan`, `AirplaneRouteTest`)

Only the kid's row (§4) and §3.5's setup step call it. It takes one request at a time, from a background thread.

1. **Refuse early.** The row says why (§4), and `last_error` records it (§5.1). The refusals:
   - the role isn't held, or the VIS isn't ready;
   - `DISALLOW_AIRPLANE_MODE` is set;
   - airplane mode is already on;
   - **Emergency Callback Mode is on** (the sticky `EMERGENCY_CALLBACK_MODE_CHANGED`, read as `SelfUpdate.kt` does),
     **an emergency call was placed within the last 10 minutes** (`RECENT_EMERGENCY_CALL_MS`), or Vibb's own callback
     window is open (`calls.md`): `emergency`. Settings' own `AirplaneModeEnabler` refuses during ECBM, and the voice
     activity doesn't, so Vibb does it;
   - **the cool-down**: less than 10 s since the last turn-on started: `cooldown`. This bounds both the window in
     step 4 and the reports a kid can cause by toggling.
2. **The report first, outside every lock.** A report-only post: no policy GET, no `apply()`, the same `StatusReports`
   hash state, under `syncMutex`. It carries `airplane_mode.going_on = true`, so the parent's page says "Flymodus på"
   and not "not heard from" (§6.2). Budget 5 s, with the row's busy line. With no answer by then the route goes on,
   and the report stays queued as any other does.
3. **Resolve the component.** `ACTION_VOICE_CONTROL_AIRPLANE_MODE` with the `VOICE` category, `MATCH_SYSTEM_ONLY`.
   Accept exactly one activity, in `com.android.settings`, with a translucent theme
   (`windowIsTranslucent` read from its theme). Anything else is `route_missing`, and nothing is started. The Jelly
   Star's component and theme are a device check (§12).
4. **Open the window, through the one writer.** This step and step 7 run under `AppEnforcer`'s monitor, so no
   `apply()` interleaves.
   - Write the DE marker `airplane_route_open` (with `BOOT_COUNT`).
   - Set `LockTaskChrome.routePin = "com.android.settings"` and call `LockTaskChrome.refresh`. `routePin` is an input
     of `LockTaskChrome`, like `sosNarrowing` and `voipPackages()`. While it is set and the kiosk is on and
     **not** `chromeLocked`, `applyNow` adds Settings to the kiosk list and disables the status bar
     (`setStatusBarDisabled(true)`, the write it already does for LOCKED). The shade then can't be pulled during the
     window, and an open one closes.
   - If the phone is LOCKED (the kid pressed power), the chrome leaves Settings out. The route's start is then blocked,
     and it ends with `timeout`. **Nothing in the route ever writes the lock-task list itself.**
   - Unhide Settings when the plan hides it (M3, M4). `AirplaneRoute.inFlight` (with its start time) makes the
     instant-suspend-on-install receiver and Home's app list ignore Settings' `PACKAGE_ADDED`/`PACKAGE_CHANGED`.
   - While Åpne for oppsett or the override runs, Settings is shown already and the kiosk is off, so this step does
     nothing but write the marker.
5. `showSession(args with the nonce, 0)`. The session calls `startVoiceActivity` with the resolved component
   explicitly and `EXTRA_AIRPLANE_MODE_ENABLED = true`.
6. **Wait** for `ACTION_AIRPLANE_MODE_CHANGED` with `state = true`, the session's `onTaskFinished`, or **1 s**. M4's
   whole route took 270 ms.
7. **Close the window, always** (a `finally`):
   - Clear `routePin` and refresh the chrome (the list and the status bar as the chrome's own inputs say).
   - Hide Settings again when step 4 unhid it, and check `setApplicationHidden`'s return value. On `false`, retry once,
     then request an `apply()`.
   - Hide the session, clear `inFlight`, and clear the DE marker last.
   - Removing Settings from the list clears its locked tasks (AOSP `LockTaskController.updateLockTaskPackages`), and
     the hide kills it. That containment is a device check (§12 test 2c).
8. **After.** On success, record the period's start (§5.1, `on_by: "kid"`) and re-render the row. On a failure after a
   `going_on` report went out, request a sync at once (the phone is still online).

**After a process death or a reboot mid-route:** `Application.initRest`, before anything else and before the first
sync, and `BootCallReceiver`'s unlocked path read `airplane_route_open`. If it is set, they run step 7's put-back at
once on a background thread: clear `routePin`, refresh the chrome, and hide Settings if the plan hides it. A marker from
an earlier boot means the same. `AirplaneRouteTest` covers "marker set at start -> put back before the first sync".

### 2.3 Off (`AirplaneRoute.turnOff`)

It runs under `AppEnforcer`'s monitor.

- If the parent's hardening switch has set `DISALLOW_AIRPLANE_MODE` already, airplane mode is off, and there is
  nothing to do.
- Otherwise add the restriction, wait for `ACTION_USER_RESTRICTIONS_CHANGED` and `AIRPLANE_MODE_ON == 0` (3 s at
  most), then clear it. The clear runs in a `finally`, and `applyHardening` writes the switch's own value on the next
  `apply()` anyway.

**Off never needs the role, Settings or the bit.** §4's row offers it whenever airplane mode is on (Q98-M5).

### 2.4 The parent's hardening switch "Blokker flymodus" (`disallow_airplane_mode`, default off)

It stays as it is: Android's own block, which only the server lifts (the hardening exception to the standing rule).

- With it on, airplane mode can't be on at all. The kid's row reads "Bestemt av mamma og pappa" (§4), and the PWA's
  Flymodus switch gets a sub-line (§6.1).
- Turning it on while the kid's airplane mode is on ends airplane mode (§1.1), and the period records
  `off_by: "parent"`.
- The kid's switch never overrides it.

The switch and bit 64 answer two questions. The switch is "may this phone ever be in airplane mode"; the bit is "may
Emma turn it on herself".

## 3. Onboarding

### 3.1 What the parent does on Android's page (M1)

"Digital assistant app" › **Default digital assistant app** › **Vibb One** › **OK**.

Android's confirmation says the assistant "will be able to read information about apps in use…". That isn't true for
Vibb (§8), and the launcher's row says so before the page opens: "Android spør om assistenten kan lese skjermen. Vibb
One leser den aldri." / "Android asks whether the assistant may read the screen. Vibb One never does."

### 3.2 The default-apps window (M2), snapshotted and closed on the role

Every managed phone holds `DISALLOW_CONFIG_DEFAULT_APPS` while Vibb holds the dialer or the SMS role (design 09/77). The
pause doesn't lift it, because the call rules aren't lifted by the pause. Under it, Android's picker refuses Vibb One,
so the setup step lifts it for a short, PIN-gated window.

**When it opens.** Only from the PIN-gated Settings (§3.3), while Åpne for oppsett or the Settings pause runs.

**On open:**

- Store `assistant_pick_until = now + 5 min` in DE prefs, with `BootClock` like the pause, so a reboot ends it.
- **Snapshot every exclusive role holder Vibb can read:** whether Vibb holds HOME, DIALER, SMS, CALL_SCREENING and
  CALL_REDIRECTION (`isRoleHeld`), the default browser package (`resolveActivity` for an `https` VIEW intent), and the
  HOME preferred activity.
- `lockDefaultAppsFor` gains `&& !assistantPickOpen(now)`, and `apply()` runs before Android's page opens.

**It closes on the first of these:**

- **the role becoming Vibb's** (`VibbAssistantService.onReady`, which runs the moment the role is granted);
- the page's activity result;
- the 5 minutes (an alarm runs `apply()`);
- the pause's end;
- a reboot.

That makes the window as short as the step: it ends at the parent's OK.

**On close:**

- `apply()` sets the restriction again.
- `applyDialerRole` and `applySmsRole` take back a moved dialer or SMS role with device-owner calls, as designed today,
  and fix our own permissions first.
- HOME is re-seated through `HomePin`'s `reseatPreferredActivities`. Whether that also moves the HOME *role* back
  (PMS's `updateDefaultHomeNotLocked`) is a device check (§12 test 1b). `home_role_held` feeds the update fence.
- **Compare with the snapshot.** CALL_SCREENING, CALL_REDIRECTION and the browser have no device-owner way back. A
  change to any of them, or to HOME if the re-seat didn't restore it, goes into the report as
  `airplane_mode.default_apps_changed` (role names) and into the PWA's Oppsett card (§6.2).
- A message that arrives while another app holds the SMS role goes to that app, and the window's shortness is the
  only guard. The card says so if the SMS role moved.

**What is and isn't taken back:**

| Role | Taken back? |
|---|---|
| Dialer, SMS | Yes, by device-owner calls |
| HOME | Probably, by the re-seat (device check) |
| Call screening, call redirection, browser | No: reported |

This isn't the lift that QA of cmd-expiry (Medium 2) dropped. That one was opened by a prompt on Home, which the kid
could hold open. This one is the parent's, behind the parent PIN, at most 5 minutes and usually seconds, only while
the phone is open for setup, and checked at its end.

**The same window can grant call screening and redirection (D10).** Today those two roles are granted with adb at
provisioning (`calls.md`), against the rule "no adb beyond the QR". Under the window, a row can open
`RoleManager.createRequestRoleIntent(CALL_SCREENING)` (and `CALL_REDIRECTION`). Both are requestable roles, and the
window lifts the restriction that blocks their prompt today. D10 asks whether to add the row.

### 3.3 "Fullfør oppsett" in the launcher's Settings

The PIN-gated Settings' pause section ("Sett alle begrensninger på pause") already holds the grant rows
Bakgrunnstilkobling and Endre systeminnstillinger (`WriteSettingsAccess`, `systemPageAccess`). New rows join them, with
the same states (granted / open now / pause first / wait / no page):

- **Digital assistent:** "Vibb One er valgt" / "Vibb One is chosen", or "Velg Vibb One - trengs for Flymodus" /
  "Choose Vibb One - needed for Flymodus".
  - A tap opens §3.2's window, then `ACTION_VOICE_INPUT_SETTINGS`, explicit to the system Settings component
    (`systemPageIntent`).
  - Shown only while bit 64 is on or the role is held.
- **Wi-Fi i flymodus** (§3.5): "Wi-Fi blir på i flymodus" / "Wi-Fi stays on in airplane mode", "Denne telefonen slår
  av Wi-Fi i flymodus" / "This phone turns off Wi-Fi in airplane mode", or "Ikke satt opp" / "Not set up".
  - Shown once the role is held.
- **Samtalefilter** ("Call filter", only with D10): the call screening and redirection roles, while calls are managed
  and either one isn't held.

**One entry point during setup.** While the phone is open (Åpne for oppsett, or the Settings pause) and a step the
phone needs isn't done (§6.2's rule), the launcher posts one quiet notification:

- channel: a new `launcher:setup`, LOW, not on the lock screen;
- text: "Fullfør oppsettet av Vibb One" / "Finish setting up Vibb One", with "2 steg igjen" / "2 steps left";
- a tap opens Settings at the section, behind the parent PIN as always;
- it goes when the steps are done or the phone locks again.

The kid may see it during a setup window, but it leads only to the PIN.

### 3.4 At enrollment, and on a phone already in use

**A new phone.** The QR enrolls the phone, and its first policy starts the kiosk within seconds, so there is no moment
before the lock to do this. Instead:

- The PWA's QR page's "Ferdig" goes to the phone's summary. That page shows an **Oppsett** card (§6.2) while the latest
  report lists steps the phone needs.
- The card holds the Åpne for oppsett button (`/start-pause` with a new `back=setup`, landing on `#oppsett`).
- D2 asks whether the QR page should offer "Åpne for oppsett nå" itself.
- The runbook `docs/setup/google-account.md` gets the steps in its "On an enrolled phone" list, since the parent opens
  the phone for setup for the Google account anyway.

**A phone already in use:** the same card, the same notification, the same rows. No adb. `adb shell cmd role
add-role-holder android.app.role.ASSISTANT me.vibb.launcher` goes in the runbook as a shortcut for a phone with USB
debugging allowed, never as the way.

### 3.5 Wi-Fi in airplane mode, folded into the same step

The parent's one privileged Wi-Fi tap during airplane mode is what Android remembers (§1.1). The row "Wi-Fi i
flymodus" runs during the setup window:

1. It stores `wifi_step_open` with a 5-minute deadline (DE prefs, `BootClock`, so a reboot ends it), then turns
   airplane mode on through §2.2. Settings is already shown, so §2.2 skips step 4.
2. It opens Settings' Wi-Fi panel (`Settings.Panel.ACTION_WIFI`), with the line "Slå på Wi-Fi her, og gå tilbake" /
   "Turn on Wi-Fi here, then go back".
3. **The step ends on the first of these:**
   - our Settings' `onResume`;
   - the activity result;
   - the deadline (an alarm);
   - the pause's end;
   - process start with the flag set.
4. **At the end:**
   - Read `WifiManager.isWifiEnabled` while airplane mode is on. True stores `wifi_kept = true`. False stores nothing,
     and the row stays "Ikke satt opp".
   - Then end airplane mode through §2.3, and clear the flag.

So a parent who presses Home in the panel, or a pause that ends, never leaves the phone in airplane mode.

**What Vibb can and can't know:**

- Android shows its own "Wi-Fi stays on in airplane mode" notification once. §2.2's step 4 disables the status bar
  during a kid's route, so that notification can't be tapped through a pinned Settings.
- Vibb can't read `wifi_apm_state`: it is a hidden Secure key, and apps get `SecurityException` since Android 12. The
  stored value is what the phone saw.
- Every later airplane mode with Wi-Fi on checks it again: 3 s after §2.2, Wi-Fi still enabled keeps `true`, and Wi-Fi
  gone writes `false` and reports it. On a phone without the memory (`apm_enhancement_enabled` off, an OEM choice), the
  step ends `false` with the OEM line above.

**Device checks:**

- Whether `Settings.Panel.ACTION_WIFI` is still a panel on Android 16 or the full Wi-Fi page.
- Whether a tap there sets `wifi_apm_state`. The research measured Settings and Quick Settings, not the panel.
- Both are §12 test 4. The Jelly Star's answer comes through the RC.

The step is optional: Flymodus works without it, and then airplane mode is fully offline.

### 3.6 The role lost

**Detection:** `isRoleHeld` on every `apply()` and on Kid Settings' resume, and the VIS's `onShutdown`. There is no
public broadcast for a role change. A change asks for one sync (`roleReportNeeded`'s pattern, once per change).

**Then:**

- the kid's row turns on only from bit 64 with the role. Off is always offered while airplane mode is on (§4);
- the PWA shows a warning on the summary and on the About This Phone row (§6);
- nothing on the phone takes the role back. It can't.

**How it could be lost:**

- Someone chose "None" or another app during a setup window (M2: "None" isn't blocked).
- The parent chose another app.
- Vibb was uninstalled.
- A Google app with a privileged role call (Gemini's switch flow on some phones) took it. This one was not measured
  (device check). Under the kiosk the Google app is hidden unless the parent allowed it.

The report catches every case.

## 4. The kid's row: "Flymodus" (pure `airplaneRow`, `KidSettingsModelTest`)

**Placement:** the first row of "Tilkobling og skjerm", above Wi-Fi, since it decides what the rows under it can do.
`controlsSection` gains `Rows.airplane` (bit 64) and `Rows.airplaneOffOnly`, and `describe()` names both.

**When the section shows** (the Q97-L1 pattern):

- A card shows when any row in it shows.
- A mask of only 64, with the role not held and airplane mode off, has no row. That is `NoneEnabled`: no card, the same
  as mask 0.
- With airplane mode on, the Off-only row makes it a card whatever the mask.

The row's states, in priority order:

| State | Switch | Line under it (nb / en) |
|---|---|---|
| airplane mode on, and (bit 64 off or role not held) | On, clickable to **Off only** | "Slå av flymodus" / "Turn off airplane mode" |
| bit 64 off, airplane mode off | no row | - |
| role not held, airplane mode off | no row (the parent's page says why, §6) | - |
| `DISALLOW_AIRPLANE_MODE` set (the parent's switch) | Off, 50 %, not clickable | "Bestemt av mamma og pappa" / "Set by mum and dad" |
| busy (§2.2 or §2.3 running) | as it will be, not clickable | "Slår på flymodus…" / "Turning on airplane mode…"; Off: "Slår av…" / "Turning off…" |
| refused: `emergency` | Off, 50 %, not clickable | "Etter en nødsamtale kan ikke flymodus slås på ennå" / "Airplane mode can't be turned on right after an emergency call" |
| refused: `cooldown` | Off, not clickable for the rest of the 10 s | - |
| the last turn-on failed (`timeout`, `route_missing`), for 1 min or until the next tap | Off | "Fikk ikke slått på flymodus. Prøv igjen." / "Couldn't turn on airplane mode. Try again." |
| on, Wi-Fi up | On | "Ingen mobilnett. Wi-Fi er på. Nødsamtaler virker." / "No mobile network. Wi-Fi is on. Emergency calls still work." |
| on, no Wi-Fi | On | "Ingen nett. Nødsamtaler virker." / "No network. Emergency calls still work." |
| off | Off | - |

**Off-only** is the fix for a phone left in airplane mode with no way out (Q98-M5). An example: the parent turned it
on from Quick Settings during Åpne for oppsett for a flight, and the kiosk came back with bit 64 off. This exists today,
and Off needs neither the role nor Settings.

**The rows under it, while airplane mode is on:**

- **Wi-Fi** is read-only with "Kan ikke endres i flymodus" / "Can't be changed in airplane mode" (`setWifiEnabled` is
  refused, §1.1).
- **Mobildata** reads "Flymodus er på" / "Airplane mode is on".
- **Bluetooth** is measured in the build (§12 test 6). It gets the same line if `enable()` is refused.

**Taps:**

- A tap is a plain switch tap, so it works the same with gesture navigation and with the three buttons.
- The route never leaves the kid's screen (M4), the session never has a window (§2.1), and the status bar is disabled
  only for the window's second (§2.2).
- After an Off, the launcher waits for the first validated network (a `NetworkCallback`, at most 60 s) and then asks
  for a sync, so the parent's page follows.
- The pause and the override don't change airplane mode or the row.
- Under a time rule Kid Settings isn't reachable, as today. D8 asks about leaving airplane mode then.

## 5. The report and the contract

### 5.1 The report: `airplane_mode` (a full-report field, not a heartbeat field)

```json
"airplane_mode": {
  "on": false,
  "going_on": false,
  "assistant": true,
  "wifi_kept": true,
  "last_error": null,
  "default_apps_changed": [],
  "periods": [
    {"on_ms": 1763150580000, "on_by": "kid", "off_ms": 1763183400000, "off_by": "kid"}
  ]
}
```

- `on`: `AIRPLANE_MODE_ON`. `going_on`: true only in §2.2 step 2's report.
- `assistant`: `RoleManager.isRoleHeld(ROLE_ASSISTANT)`.
- `wifi_kept`: `true`, `false`, or `null` (never set up).
- `last_error`: `null`, or the last failed or refused turn-on within 24 h. One of `route_missing` (no acceptable voice
  activity), `refused` (the restriction was set), `timeout`, `emergency`, `cooldown` (kept on the phone, not sent).
- `default_apps_changed`: the role names §3.2's comparison found moved and not taken back. It is cleared once the
  parent has re-done them, or at the next window.
- `periods`: at most 10, from the last 7 days, oldest first. Only the last may be open (`off_ms`/`off_by` `null`).
  - `on_by`: `kid` (§2.2 within 5 s), or `other` (Quick Settings during a setup window, §3.5's step, anything else).
  - `off_by`: `kid` (§2.3), `parent` (the hardening switch went on), `emergency`, or `other`. `emergency` means
    airplane mode went off during an emergency call, or at most 10 s after `CallSystem.emergencyPlaced`.
  - The phone keeps the periods in DE prefs (a small JSON, `AirplaneLog`).
  - **The edge receiver writes synchronously**, before it returns: `ACTION_AIRPLANE_MODE_CHANGED`, registered by the
    anchor service, plus a check of the stored state at process start and boot (M7). So the report that
    `emergencyPlaced`'s sync sends already carries the closed period (Q98-L1).

The field changes rarely, so it stays out of `status_heartbeat_fields`. A change changes the report's hash, and a full
report goes (design 95-T2). The capability is `airplane_mode_v1`.

### 5.2 The contract: a new file `server/testdata/airplane_mode.json`

It is a new file, not part of `mobile_network.json`: this is about the role and the radio, and that file is about
data. It is byte-identical in `launcher/app/src/test/resources/`.

**Its parts:**

- `capability`, `bit` (64).
- `rows`: `{mask, role, restricted, on, wifi_up, busy, last_error, error_age_ms}` -> the row's state (§4), with the
  Off-only rows (`{mask without 64, on: true}`, `{role: false, on: true}`), each refusal, and `{mask: 64, role: false,
  on: false}` -> no card.
- `report`: the exact object above, and one with an open period.
- `clean`: the server's field-by-field cleaning.
  - Unknown `on_by`/`off_by` words become `other`.
  - More than 10 periods are cut to the newest 10.
  - A period with `off_ms` before `on_ms` is dropped.
  - An open period that isn't the last is closed at the next one's `on_ms` with `off_by: "other"`.
  - An `on_ms` more than the usual clock slack in the future is dropped.
  - Unknown `last_error` and `default_apps_changed` words are dropped.
- `emergency`: the rows of §7.1's rule (a call time and a period -> whether the call's event gets the airplane line).

**Its tests:**

- The launcher's `AirplaneModeTest` runs `rows` through `airplaneRow` and `controlsSection`, encodes `report` exactly
  and runs `emergency`'s rule on its side.
- The server's `tests::airplane_mode` posts `report`, checks what is stored, and runs `clean` and `emergency`.
- Both compare the copies.

The root `CLAUDE.md`, the three workflows' path lists and `intake_limits` get the file. No policy key changes: bit 64
rides in the existing `quick_controls_mask`.

## 6. Server and PWA

### 6.1 The Home Screen page's quick controls (`/devices/{id}/home#quick-controls`)

**The row.** A new switch row "Flymodus", `name="quick_control_airplane"`, in the `section=quick` form. It shows only
for a phone that reported `airplane_mode_v1`.

**The mask (Q98-M9).** When the form renders the row, it also carries `airplane_row=1`. `update_policy` then:

- **without `airplane_row`**: keeps bit 64 as it keeps 32, `COALESCE((quick_controls_mask & (32 | 64)) | ?, …)`;
- **with `airplane_row`**: takes bit 64 from the switch, still keeping 32.

A server test pins that, for both `section=quick` and `section=all`: a save without the row keeps 64, and a save with
the row unchecked clears it.

**A sub-line under the label, only when it says something:**

- The role isn't held: "Vises for Emma når Vibb One er valgt som digital assistent. Se Oppsett på telefonsiden." /
  "Shows for Emma once Vibb One is chosen as the digital assistant. See Setup on the phone's page."
- The hardening switch is on: "Flymodus er blokkert under Avansert › Begrensninger." / "Airplane mode is blocked under
  Advanced › Restrictions."
- `wifi_kept` is `false` or `null` while the bit is on: "Uten Wi-Fi i flymodus er telefonen helt uten nett når
  flymodus er på." / "Without Wi-Fi in airplane mode the phone is fully offline while airplane mode is on."

**The ⓘ** gets: "Flymodus slår av mobilnettet. Nødsamtaler virker fortsatt, og etter en nødsamtale kan barnet ikke slå
på flymodus igjen med en gang." / "Airplane mode turns off the mobile network. Emergency calls still work, and right
after one the child can't turn airplane mode back on."

`DEFAULT_QUICK_CONTROLS` gains bit 64 if D1 says so. Autosave lands on `#quick-controls`, never the top. This page is a
phone sub-page: no bell.

### 6.2 The summary and About This Phone

**The summary's state lines** (times in the phone's zone, as other lines):

- "Flymodus på siden 21:03" / "Airplane mode on since 21:03":
  - when the latest report has `on`;
  - or when it has `going_on`, **for 10 minutes only** unless a later report confirms with `on` or a period (Q98-L2);
  - this line replaces the "not heard from" line.
- "Flymodus var på 21:03–07:10" / "Airplane mode was on 21:03–07:10", for a period that ended within the last 12 hours.
- "Flymodus ble slått av av en nødsamtale 14:02" / "An emergency call turned airplane mode off at 14:02", when
  `off_by` is `emergency` (§7.1).
- A warning line when the role was held before and is lost now (§3.6): "Flymodus virker ikke: Vibb One er ikke lenger
  digital assistent." / "Airplane mode doesn't work: Vibb One is no longer the digital assistant.", with the steps
  behind the ⓘ.

**The Oppsett card (`<section id="oppsett">`)** shows while a step the phone **needs** isn't done:

- "Bakgrunnstilkobling", always.
- "Endre systeminnstillinger", only with bit 16 or the clock format in use.
- "Digital assistent", only with bit 64.
- "Wi-Fi i flymodus", only with bit 64 and the role held. It counts as done when `wifi_kept` is `true` or `false`; the
  OEM line counts as done.
- "Standardapper endret" ("Default apps changed"), when `default_apps_changed` isn't empty, naming them in plain words
  ("Anropsfilter", "Nettleser", "Startskjerm").
- "Samtalefilter", with D10.

The card's words: "Fullfør oppsettet på telefonen: åpne den for oppsett, og trykk på varselet «Fullfør oppsettet av Vibb
One»." / "Finish the setup on the phone: open it for setup, and tap the notification «Finish setting up Vibb One»."

The card has two forms. Both land on `#oppsett`, and the flash shows in the card:

- **Åpne for oppsett**: `/start-pause` with `back=setup`. `window_relock::queue` gains `setup` next to `advanced`.
- **"Ikke nå"**: hides the card until the needed steps change (stored per device).

**About This Phone gets two rows:**

- "Digital assistent" / "Digital assistant": "Vibb One", or "Ikke valgt" / "Not chosen" with the steps as its detail
  (like `write_settings_steps`).
- "Wi-Fi i flymodus" / "Wi-Fi in airplane mode": "Blir på" / "Stays on", "Slås av" / "Turns off", or "Ikke satt opp" /
  "Not set up".

### 6.3 Storage, migration `0146_airplane_mode.sql`

- `device_status.airplane_mode_json` (TEXT, the cleaned object), read by `phone_status` for the lines.
- `device_policy.setup_card_hidden` (TEXT, the needed steps as they were when the parent hid the card).
- The summary's "since" time is the open period's `on_ms`, or the `going_on` report's time.
- No new table: the periods ride on every full report, and the latest report holds the last 10. Retention is as
  `device_status`'s.

### 6.4 An older launcher, an older server

- **An older launcher on this server:** no capability, so no Flymodus row on the Home page (and the form keeps bit 64,
  §6.1), and no card step for the role.
- **This launcher on an older server:** bit 64 never comes, so no kid row, but the Off-only row still shows while
  airplane mode is on. The report's `airplane_mode` is an unknown key, which the older intake drops. The setup rows in
  the launcher's Settings still work, since they need no server.

## 7. Emergency and the edge cases

### 7.1 After a 112 call

Android turns airplane mode off for the call and leaves it off (§1.1).

**Recommended (D3): leave it off, and tell the parent.** The callback (ECBM, about 5 minutes) and any follow-up call
need the radio, and a kid who just called 112 should be reachable. §2.2 step 1 also refuses a turn-on during ECBM and
for 10 minutes after the call.

**What the parent sees:**

- The phone records `off_by: "emergency"`.
- The server adds one line to the existing `emergency_call` event: "Flymodus ble slått av av samtalen." / "The call
  turned airplane mode off."
  - It is added when the report's period ended within 10 minutes of the reported call.
  - It is a typed parameter of the event (`airplane_off: true`), never phone text.
- **In the same intake** that raises the event, the line is part of the push too.
- If a later report brings the period, only the inbox entry gets the line, and no second push goes.
- No new notification kind.

The emergency menu's other numbers (design 73) turn airplane mode off only when Telephony counts them as emergency
numbers for the SIM and network. 112 always does.

### 7.2 The other cases

- **Reboot in airplane mode:** it stays on (M7), and so does the role. At boot the launcher reads the state and keeps
  the open period, and the row is On. Off works at once: §2.3 needs no Settings or VIS. A reboot mid-route is §2.2's
  marker.
- **Screen off, the keyguard:** the kid's row is only reachable unlocked. The route works at the secure keyguard and
  with the screen off (M5, M6). That matters only for §9.3. If the phone turns LOCKED during the route, the chrome
  leaves Settings out and the route ends with `timeout` (§2.2 step 4).
- **The pause and the override:** they don't touch airplane mode. The kiosk is off then, so §2.2 skips step 4's chrome
  part. The assist gesture fires then (M9), and D9 asks what it should do.
- **A time rule:** with airplane mode on before Leggetid or Skole starts, the phone stays unreachable through the rule.
  Kid Settings isn't reachable under a rule, and calls the rule allows can't arrive. D8 asks the user.
- **Unmanaged:** no policy and no bit, but the Off-only row still shows while airplane mode is on and Vibb is device
  owner. Airplane mode is otherwise Android's own then.
- **Vibb's own connection:** in airplane mode without Wi-Fi, the SSE stream drops and syncs fail fast, as with any lost
  network. The backstop keeps its 30 minutes. With Wi-Fi kept, everything runs as on any Wi-Fi.
- **The DNS filter and Bare Vibb (design 95/97):** unchanged. With no network there is nothing to filter, and on kept
  Wi-Fi the filter shape applies.
- **Two quick taps:** the row isn't clickable while busy, and the 10 s cool-down holds. `AirplaneRoute` takes one
  request at a time.

## 8. Never: no screen reading

The assistant role lets the system hand the assistant the screen's text and a screenshot. **Vibb takes neither:**

- `VibbAssistantService.onReady` calls `setDisabledShowContext(SHOW_WITH_ASSIST or SHOW_WITH_SCREENSHOT)`. Measured
  (M8): no assist data, structure, content or screenshot arrives, with Android's own switches on. The probe overrode
  only `onHandleAssist(AssistState)` and still got nothing, so the block is the system's.
- `VibbSession` calls `setUiEnabled(false)` and overrides every assist callback empty: `onHandleAssist` (all
  overloads), `onHandleAssistSecondary` and `onHandleScreenshot`.
- It never calls `requestAssist`, `registerVisibleActivityCallback`, `requestDirectActions` or `performDirectAction`,
  and never reads `AssistStructure`, `AssistContent` or `VisibleActivityInfo`.

`AssistantInvariantsTest` scans the production sources and the manifest:

- `setDisabledShowContext` is called only with both flags, in `onReady`.
- None of the names above appears outside the empty overrides.
- The manifest declares no `RECORD_AUDIO`, and the self-grant list doesn't contain it.

Voice commands ("hey…") and "Vis mamma dette" (show a screen to a parent) are out, on the long list.

## 9. Options and what follows

### 9.1 Battery saver ("Strømsparing") through the same route (D4)

`Settings.ACTION_VOICE_CONTROL_BATTERY_SAVER_MODE` with `EXTRA_BATTERY_SAVER_MODE_ENABLED` goes through the same
session. A device owner can't set battery saver otherwise. Off uses the same route, since no restriction exists for
it. It would be a row "Strømsparing" (bit 128) with the same states, §2.2's window included. Not measured. Size **S**
(launcher S, server XS).

### 9.2 The quick panel: Snarveier, approved as its own design (design 99)

**Measured (M10): the assist gesture never fires under lock task on a managed phone.** `LockTaskController` always
sets `DISABLE_SEARCH`, and no lock-task feature clears it. So a panel opened by long-pressing power or Home, or by the
corner swipe, **isn't possible on a kid's phone.**

The user approved the other host on 2026-11-14, as its own design: **Snarveier**, one ongoing notification in the shade.

- Lock task keeps the shade (`LOCK_TASK_FEATURE_NOTIFICATIONS` is in Vibb's default features).
- Up to six round buttons in a 3 x 2 grid, filled peach when on.
- The kid picks and orders them from what the parent allows.
- Never on the lock screen. No call or contact items.

**Design 99** specifies it, and Flymodus will be one of its items, through this design's `AirplaneRoute`.

The candidate items for design 99 (what each needs, from this research):

| Item | What it needs | Possible | Its gate |
|---|---|---|---|
| Flymodus | this design | yes | bit 64, the role, not the hardening switch |
| Lommelykt | `CameraManager.setTorchMode`: **no permission** (design 41's `Torch`), 10-min auto-off, refused while the camera is disabled at the lock | yes (not on the emulator: no flash) | the phone has a flash |
| Lyd (lyd / vibrer) | design 18's `SoundMode`. Silent (do not disturb) needs notification-policy access, which a device owner can't grant: ring and vibrate only | yes | bit 8 |
| Lysstyrke | brightness `setSystemSetting` (device owner), as steps (low / middle / high) | yes | bit 4 |
| Wi-Fi | `setWifiEnabled` (device owner); refused in airplane mode | yes | bit 1 |
| Bluetooth | `BluetoothAdapter.enable/disable` (device owner) | yes | bit 2 |
| Mobildata | design 97's kid switch | yes | bit 32 |
| Musikk (spill / pause / neste) | the music app's now-playing session Home already shows (design 21 step 4) | yes | the music app installed and allowed |
| Be om mer tid | design r13's time request | yes | a screen-time budget or rule applies and requests are on |
| Send posisjonen min | a fresh fix and a report now, plus a new parent event "Emma sendte posisjonen sin" | yes, a new event kind (S) | location mode isn't Off |
| Nød | design 73's emergency menu | yes | always (and Android's own emergency button stays) |
| Strømsparing | §9.1 | only with D4 | bit 128 |
| Kamera | opens the allowed camera app | yes | a camera app allowed and not locked |
| Dagen (suggested) | opens Dagen (design 69) | yes | Dagen shown on Home |
| Skjermtid igjen (suggested) | a display, "48 min igjen", no action | yes | a budget applies |
| Alarm (suggested) | opens the allowed clock app | yes | an alarm app spared (always) |

### 9.3 Flymodus from the PWA (D6)

**On, remotely:** the server nudges, and the phone runs §2.2. That works with the screen off and at the keyguard (M5,
M6), with the same ECBM refusal. But the phone then goes offline, and **the parent can't turn it off again remotely**
unless Wi-Fi was kept (§3.5) and the phone is on a known Wi-Fi.

So if it is built at all, it should be **"Flymodus til kl. 07:00"**: the phone ends it by itself at that time (an exact
alarm and §2.3, offline), and the kid can still turn it off from the row. Size **M** (launcher S-M, server S).

## 10. Risks

- **The route rests on a Settings activity meant for voice assistants.** An OEM could leave `AirplaneModeVoiceActivity`
  out, resolve the action to an ordinary page, or Android could change it. §2.2 step 3 accepts only a translucent
  Settings activity, so the worst case is `route_missing`, never a full Settings page pinned for a second. The Jelly
  Star's component and theme are a device check.
- **Settings shown during the window.**
  - The window is bounded by step 6's 1 s. M4 measured 270 ms for the route; the full unhide-to-hide time is a §12
    measurement.
  - The status bar is disabled for it, and the 10 s cool-down caps how often it opens.
  - What remains: an allowed app's own start of a Settings intent within that second. With the block bit on (the
    default), only pinned packages start, and Settings is the one added.
  - The containment after step 7 (Settings' task cleared, the app killed) is a device check.
- **The default-apps window** (§3.2): call screening, call redirection and the browser can't be taken back. The window
  is usually seconds, behind the parent PIN, and changes are reported.
- **The stub recognizer:** the assistant role also makes the VIS's recognizer the system default. An app that uses
  `SpeechRecognizer.createSpeechRecognizer` with the default service gets `ERROR_CLIENT`. Gboard's voice typing uses its
  own recognizer. To be measured in the build (§12 test 7): Gboard's mic in Meldinger and in an allowed app.
- **The role lost silently** to a privileged Google flow (§3.6): not measured. The report catches it within a sync.
- **Wi-Fi memory on the Jelly Star** depends on the OEM's `apm_enhancement_enabled`. Without it, Flymodus is offline.
- **The kid can be unreachable.** That is the feature. The report-first step (§2.2), the summary line and D8 make it
  visible, and 112 always works.

## 11. Build steps and sizes

1. **Contract, both directories, one commit, S.**
   - `airplane_mode.json` and its copy, the DTOs, `clean`, the capability.
   - The root `CLAUDE.md` paragraph and the workflows' path lists.
2. **Launcher: the assistant and the route, M.**
   - The three services and the XML.
   - `AirplaneRoute`: the refusals with ECBM and the cool-down, the report-only post, the resolved component, the
     window, Off.
   - `LockTaskChrome.routePin` with the status-bar write, and its `LockTaskHelpersTest` rows: `routePin` with
     `locked = true` leaves Settings **and** the recents provider out; with `locked = false` it adds Settings and
     disables the status bar.
   - The DE marker and its put-back at process start and boot.
   - `inFlight` in the package receiver.
   - `AirplaneLog` and the synchronous edge receiver, the report field.
   - `AssistantInvariantsTest`, and `AirplaneRouteTest` (the plan's steps, the `finally` on every failure, the
     marker's put-back).
3. **Launcher: onboarding, S-M.**
   - The Settings rows.
   - §3.2's window: the snapshot and comparison, the close on `onReady`, the alarm and boot end, `lockDefaultAppsFor`,
     the HOME re-seat.
   - §3.5's flag and deadline, the setup notification, the role-change sync.
   - Tests: every way the window and the Wi-Fi step end, the rows' states, the comparison.
4. **Launcher: the kid's row, S.**
   - Bit 64 and the Off-only row in `controlsSection`, and `airplaneRow`.
   - The Wi-Fi and Mobildata lines in airplane mode, and the network-wait before the sync.
   - Strings in nb and en.
   - `KidSettingsModelTest` covers a mask of 64 alone (no card) and airplane mode on with mask 0 (a card).
5. **Server, S-M.**
   - Migration 0146, intake and cleaning.
   - The Home form's row with `airplane_row` and the bit-64 keep (tests, both sections).
   - The summary lines with the `going_on` timeout, the Oppsett card with its rules and `back=setup`, About This
     Phone's rows.
   - The emergency line in the same intake, `DEFAULT_QUICK_CONTROLS` per D1, nb + en.
   - Page tests: anchors, no bell, both languages.
6. **Emulator run (§12), S-M.**

Total: launcher **M**, server **S-M**. Options: §9.1 **S**, §9.3 **M**, D8 **S**, D10 **S**. Snarveier is design 99.

## 12. Emulator tests (`jelly36` restored clean, `docs/testing/emulator.md` §6am)

Setup: the debug launcher of the branch as device owner, enrolled to a debug server of the branch, a phone made through
the PWA's Add Phone. Tests 2 and 4 run with gesture navigation and with 3-button navigation.

1. **Onboarding.**
   - Åpne for oppsett -> the notification -> the PIN -> "Digital assistent" -> Android's page -> Vibb One -> OK.
   - The window closes at the OK (`onReady`): `dumpsys user` shows `disallow_config_default_apps` again within a
     second, and the dialer and SMS roles are Vibb's.
   - The row reads chosen, the report has `assistant: true`, and the card's step is gone.
   - 1b: during the window, choose another Home and another Caller ID app first. After the window:
     - Home is Vibb's: `isRoleHeld(HOME)` and the preferred activity (device check, Q98-L6);
     - the screening change is in `default_apps_changed` and on the card.
2. **On and off under the kiosk.**
   - The kid's row On -> airplane mode on within 1 s, still LOCKED in Home's task.
   - Settings hidden again (`dumpsys package com.android.settings | grep hidden`), the lock-task packages as before,
     the status bar enabled again.
   - The `going_on` report reached the server first.
   - Measure the time from unhide to hide in the log.
   - Off -> airplane mode off within 3 s, and `DISALLOW_AIRPLANE_MODE` not set afterwards.
   - 2b: tap On, then power at once. While LOCKED, `dumpsys activity lock_task` shows no recents provider and no
     Settings, and the route ends `timeout`.
   - 2c: a Settings activity started during the window (from a test app) is gone after step 7.
3. **The parent's switch.** "Blokker flymodus" on while airplane mode is on -> it ends, `off_by: "parent"`, and the row
   is 50 % "Bestemt av mamma og pappa". Off again -> the row works.
4. **Wi-Fi in airplane mode.**
   - The setup step: Android's notification once, `wifi_kept: true`.
   - Then the kid's On keeps Wi-Fi up and the server reachable, and the summary reads "Flymodus på siden …" from a
     live report.
   - Check whether `Settings.Panel.ACTION_WIFI` is a panel and sets the memory (Q98-L6).
   - Press Home in the panel: after the deadline or Settings' resume, airplane mode is off.
5. **112.**
   - Airplane mode on, then the lock's Emergency call to 112 (the emulator's modem) -> airplane mode off, the call
     placed, `off_by: "emergency"`.
   - The server's emergency event carries the airplane line in its push.
   - The kid's row now refuses with the emergency line for 10 minutes.
6. **Bluetooth in airplane mode:** Kid Settings' Bluetooth switch is either on or refused, and the line matches.
7. **The recognizer:** Gboard's mic in Meldinger.
8. **Reboot in airplane mode:** on after boot, the row On, and Off works before the first sync. **Reboot mid-route**
   (kill the process at step 5 with a test hook): Settings is hidden again before the first sync.
9. **The role lost.** During a setup window choose "None":
   - airplane mode off: the row hides;
   - airplane mode on: the Off-only row shows;
   - the report has `assistant: false`, and the summary shows the warning.
10. **The assist gesture.** Under the kiosk, unlocked **and at the keyguard**, long-press power and the corner swipe do
    nothing (M10, and the keyguard case Q98-L6). During Åpne for oppsett they behave as D9 decides.
11. **Off-only.** Bit 64 off, airplane mode on from Quick Settings during Åpne for oppsett, then the pause ends -> Kid
    Settings shows "Slå av flymodus", and it works.
12. **The PWA.** Both languages. The Home form keeps bits 32 and 64 on `quick` and `all` without the row. The card's
    forms land on `#oppsett`. Every save stays on its anchor.

Not on the emulator:

- the Jelly Star's `AirplaneModeVoiceActivity` (component and theme), its Wi-Fi memory and its long-press power;
- whether a Google update can take the role.

Those come from the RC on the Jelly Star, through the reports, with no adb and no debug build.

## 13. Decisions for the user

- **D1. A new phone's default for the kid's Flymodus switch (bit 64).**
  - **Recommended: on** for new phones. The row stays hidden until the parent has done the assistant step, so the kid
    never sees a dead switch, and the user asked for Flymodus in the kid's settings.
  - Existing phones keep their mask (the parent turns it on).
  - The alternative is off until the parent turns it on: one more step.
- **D2. The QR page's "Ferdig" offers "Åpne for oppsett nå".** **Recommended: yes.** It is the existing start-pause,
  one button where the parent stands after the QR, and it starts the setup steps, the Google account included. Size XS.
- **D3. After a 112 call in airplane mode.**
  - **Recommended: leave airplane mode off, refuse turning it on for 10 minutes and during callback mode, and add the
    line to the parent's emergency notice** (§7.1).
  - The alternative, turning it back on after callback mode ends, could cut off a callback.
- **D4. Battery saver through the same route (§9.1).** **Recommended: not now.** Android's own battery saver starts by
  itself at 15 % on most phones, and the row would add one more short Settings window for something the kid rarely
  needs. Size S.
- **D5. The quick panel.** **Decided by the user (2026-11-14):** Snarveier as its own design (design 99), the shade
  notification, with Flymodus as one item. Nothing to decide here.
- **D6. Flymodus from the PWA (§9.3).** **Recommended: not now.** If wanted, only as "Flymodus til kl." with the end on
  the phone. Size M.
- **D7. A push to the parent when the kid turns on airplane mode.** **Recommended: no push.** The summary line says
  "Flymodus på siden 21:03" (§6.2), and the 112 case has its line in the emergency notice. A switch in the notification
  settings would be S.
- **D8. Airplane mode when a time rule starts (Q98-M6).** With airplane mode on, Leggetid or Skole keeps the phone
  unreachable, and the calls the rule allows can't arrive.
  - **Recommended:** while airplane mode is on and the rule in force allows calls, the time-rule screen and the lock
    glance show a "Slå av flymodus" / "Turn off airplane mode" row (§2.3: no Settings, no role). Size S.
  - The alternative: refuse a kid's turn-on within 30 minutes of a rule's start. That doesn't cover airplane mode
    turned on earlier.
  - Or both.
- **D9. The assist gesture while the phone is open (Q98-L4).** With Vibb as the assistant, the stock assist gestures
  stop opening Google while the kiosk is off: Åpne for oppsett, the parent override, an unmanaged phone. Concretely,
  during those 2 hours the parent loses:
  - long-press power, where it is set to the assistant;
  - the gesture-navigation corner swipe;
  - a headset's assist button;
  - Google Assistant / Gemini from those gestures;
  - Circle to Search, where the phone has it through the assistant.

  Under the kiosk nothing changes: those gestures are already dead there (M10). The options:
  - (a) **Recommended:** accept it. The setup window is the parent's, Google's apps still open from the app list then,
    and nothing reaches a kid.
  - (b) Have Vibb's session hand an unmarked request on to the Google app (`ACTION_ASSIST`, explicit to
    `com.google.android.googlequicksearchbox`) while the kiosk is off. It is fragile across Google updates, and Size S.
- **D10. Call screening and redirection without adb (§3.2, Q98-M4).** **Recommended: yes**, a "Samtalefilter" row in
  "Fullfør oppsett" that requests both roles inside the same PIN-gated window. It closes the last adb-only grant in
  provisioning. Size S.

## 14. Design QA (`docs/review/qa-98-design.md`): how each finding was handled

| Finding | Handled |
|---|---|
| H1 the route writes the lock-task list past `LockTaskChrome` | §2.2 step 4: `routePin` is an input of `LockTaskChrome`, applied only while the kiosk is on and not `chromeLocked`; the route never writes the list. A LOCKED pass leaves Settings and the recents provider out, and the route ends `timeout`. §11 step 2's `LockTaskHelpersTest` rows, §12 test 2b |
| M1 the window opened by the kid, repeatable, wider than said | §2.2: the status bar disabled through the chrome while `routePin` is set, the explicit component resolved `MATCH_SYSTEM_ONLY` and accepted only as a translucent Settings activity (step 3), the wait cut to 1 s, the 10 s cool-down. §10 rewritten. §12 tests 2 (the unhide-to-hide time), 2c, and the Jelly Star's component and theme |
| M2 process death or reboot mid-route | §2.2: the DE marker `airplane_route_open`, put back at `initRest` and `BootCallReceiver` before the first sync. `AirplaneRouteTest`, §12 test 8 |
| M3 the report under the monitor | §2.2 step 2: a report-only post outside every lock, 5 s budget. Only steps 4-7 take the monitor. A failure after `going_on` requests a sync (step 8) |
| M4 roles freed by the window, HOME assumed | §3.2: the window closes at the role's `onReady`. It snapshots every exclusive role at the start and compares at the end. HOME is re-seated, and its role is a device check. Screening, redirection and the browser are reported (`default_apps_changed`, the card). The table of what is and isn't taken back. §12 test 1b. D10 for the on-phone screening grant |
| M5 no row to leave airplane mode | §4: an Off-only row whenever airplane mode is on, whatever the bit and the role. Contract rows (§5.2), §12 test 11, §6.4 |
| M6 airplane mode under a time rule | D8, with the recommendation. §7.2 names it |
| M7 the route skips the ECBM guard | §2.2 step 1: refused during ECBM, within 10 min of an emergency call and in Vibb's callback window. `last_error: emergency`, §4's line, §12 test 5, D3 |
| M8 the Wi-Fi step can leave airplane mode on | §3.5: `wifi_step_open` with a 5 min deadline, ending on Settings' resume, the result, the deadline, the pause's end or process start. §12 test 4 |
| M9 the Home form drops bit 64 | §6.1: `airplane_row=1` only with the row, and the SQL keeps `32 \| 64` without it. Server tests for both sections |
| L1 the emergency line after the push | §5.1: the edge receiver writes synchronously. §7.1: the line in the same intake goes with the push, and a later one goes to the inbox only |
| L2 "Flymodus på" after a failed turn-on | §2.2 step 8: a sync after a failure. §6.2: `going_on` alone times out after 10 minutes |
| L3 contract gaps | §5.2: `last_error`, the Off-only rows, the refusals, the no-card row. `clean` closes non-last open periods and drops future `on_ms`. §4 says what a mask of 64 alone shows |
| L4 the assist gesture removed during setup | D9, with what the parent loses and the options |
| L5 the privacy invariants, the session window | §2.1 and §8: `setUiEnabled(false)`, every assist overload empty, visible-activity and direct-action APIs banned, `setDisabledShowContext` only with both flags, no `RECORD_AUDIO` (a manifest check) |
| L6 claims to mark as device checks | §1.2 (the keyguard under lock task inferred), §3.2 (the HOME role), §3.5 (the Wi-Fi panel and the memory), §2.2 and §10 (the Jelly Star's component and theme, the containment). §12 tests 1b, 2c, 4, 10 |
| L7 the card nags, its anchors, English | §6.2: only steps a feature needs, the Wi-Fi step done also when `false`, `back=setup` -> `#oppsett` with the flash in the card, "Ikke nå" per needed set. English added for the card, the emergency line and the ⓘ |
| L8 smaller points | §2.2 step 7: the chrome writes the list (no "as `apply()` last wrote"), and `setApplicationHidden`'s return is checked with a retry. §2.1: the permissions per service. §4: the sync waits for a validated network |

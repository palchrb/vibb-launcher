# Design 98: Flymodus in the kid's settings, through the Digital assistant role

Status: design, **revision 4 (design QA of revision 3)**, 2026-11-14 (branch `design-98`). Revision 1 was 82abae18,
revision 2 (9a9f77fe) took the design QA, `docs/review/qa-98-design.md` (1 High, 9 Medium, 8 Low), and revision 3
(54a046bb) took the user's answers. Revision 4 takes that file's "Revision 3" section (0 High, 5 Medium, 8 Low), which
reworks §4.4's rule pause. §14 and §16 say how each finding was handled. Revision 3 takes the user's answers of the evening of 2026-11-14 (§15). The user saw the mockups
of the time-rule screen (`https://claude.ai/artifact/V3FYVAfh5yMaUFA5JLkYnA`) and chose concept C. Revision 3 adds:

- Flymodus on the time-rule screen behind a dim "···" (§4.3);
- a **Foreldrekode** that pauses only the rule in force, offline (§4.4);
- a "Flymodus er på" line on Home (§4.2);
- the rule that Vibb never ends airplane mode because a rule starts (§7.2).

The old D8 is gone, and D9 is accepted.

Approved by the user on 2026-11-14: real Android airplane mode as a row in the kid's settings, made possible by making
Vibb One the phone's **Digital assistant**, on the condition that the assistant step is easy when a new phone is
enrolled. Both directories. There are two new shared contracts, `server/testdata/airplane_mode.json` and
`server/testdata/rule_pause.json`, migrations `0146_airplane_mode.sql` and `0147_rule_pause.sql` (0145 is `abroad`;
recheck at merge), and `quick_controls_mask` bit **64**. Size: launcher **M-L**, server **M** (§11). §13 holds the decisions this design needs before it is built. Battery saver and Flymodus
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
  through `LockTaskChrome` and with the shade closed and blocked (§2.2).

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

Only the kid's Flymodus switch (§4.1, and the same switch in §4.3's sheet) and §3.5's setup step call it. It takes one request at a time, from a background thread.

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
     **not** `chromeLocked`, `applyNow` adds Settings to the kiosk list and writes the plan's lock-task features
     **without `LOCK_TASK_FEATURE_NOTIFICATIONS`** (`routeWindowFeatures`), before the list. Android's
     `LockTaskController` then sets `DISABLE2_NOTIFICATION_SHADE`: SystemUI collapses an open shade, and the window
     can't open it. Quick Settings is never on in lock task.
   - **Corrected 2026-11-15 (design 99's QA, Q99-H1).** This step first said the chrome closes the shade with
     `setStatusBarDisabled(true)`. Under lock task DPMS only records that write (`lock/StatusBarHealPlan.kt`), so the
     shade stayed usable for the window, with Settings pinnable through a notification. The chrome still writes
     `statusBarDisabled = true` for the window, but only the features act. Measured in `docs/testing/emulator.md` §6am,
     "Addendum": the shade closes as Settings is pinned and can't be opened again, and the window's close takes Settings
     out before the shade comes back.
   - **What remains (measured, 2026-11-15).** SystemUI only *animates* the shade closed (~300 ms). A touch on the shade
     during that animation stops it, and the shade then stays open for the rest of the window with its notifications
     still clickable, `DISABLE2_NOTIFICATION_SHADE` notwithstanding: a notification tap can start a Settings page in the
     pinned package until step 7. Step 7 contains it (the baseline run: such a page cleared by `clear-task-all` 21 ms
     after it resumed, the process killed 190 ms later). Without a touch in that animation, nothing in the shade is
     reachable for the window.
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
   - Clear `routePin` and refresh the chrome in two passes (`LockTaskChrome.routeWindow`): the list without Settings
     with the shade still blocked, then the plan's features with the shade. A pass writes the features before the list,
     so one pass would give the shade back while Settings is still pinnable (seen on the emulator, §6am "Addendum").
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
an earlier boot means the same. The window's features outlive the process (DPM keeps them, across a reboot too), and a
new process has no plan, so its chrome pass reads them back as they are. The put-back's `apply()` writes the plan's
features, and the shade comes back with it. `AirplaneRouteTest` covers "marker set at start -> put back before the first sync".

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

- Android shows its own "Wi-Fi stays on in airplane mode" notification once. §2.2's step 4 closes and blocks the shade
  during a kid's route (the features without `LOCK_TASK_FEATURE_NOTIFICATIONS`; corrected 2026-11-15, Q99-H1), so that
  notification can't be tapped through a pinned Settings. After the window it stays in the shade, and a tap on it is
  blocked like any other Settings start under the kiosk.
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

## 4. What the kid sees, and the parent's door on the time-rule screen

### 4.1 Kid Settings' row "Flymodus" (pure `airplaneRow`, `KidSettingsModelTest`)

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
- The route never leaves the kid's screen (M4), the session never has a window (§2.1), and the shade is closed and
  blocked only for the window's second (§2.2).
- After an Off, the launcher waits for the first validated network (a `NetworkCallback`, at most 60 s) and then asks
  for a sync, so the parent's page follows.
- The pause and the override don't change airplane mode or the row.
- Under a time rule Kid Settings isn't reachable, as today. The time-rule screen's sheet carries the same row
  (§4.3), so turning airplane mode on or off works under a rule too.

**Turning airplane mode on is always allowed, also while a time rule is in force (the user, 2026-11-14).** It never
weakens a rule: rules are enforced on the phone, offline. Only the parent's "Blokker flymodus" (§2.4) and §2.2 step 1's
refusals stop it. `airplaneOnPlan` takes no rule input, and a contract row pins that a rule in force changes nothing
(§5.2).

### 4.2 Home: "Flymodus er på" (pure `homeAirplaneLine`)

While airplane mode is on, Home shows one calm line in the banner slot the pause's "Lås igjen" line uses
(`homeWindowBanner`'s place, under the status bar):

- the words: "Flymodus er på · Trykk for å slå av" / "Airplane mode is on · Tap to turn off";
- `kid_core` at the secondary text level, no colour of alarm, no icon animation;
- one tap runs §2.3. The line then reads "Slår av…" / "Turning off…", and it goes when airplane mode is off;
- shown whenever airplane mode is on, whatever bit 64 and the role (the Off-only rule, §4.1), unless
  `DISALLOW_AIRPLANE_MODE` is set (then airplane mode can't be on);
- when the pause's banner shows too, the pause's line comes first and this one sits under it;
- a plain tap, so it works the same with gesture and 3-button navigation.

### 4.3 The time-rule screen: "···" and its sheet (concept C, pure `ruleSheet`, `TimeLockModelTest`)

`LockActivity` (the time-rule screen) stays exactly as it is until someone taps a dim **"···"** at its top right:

- **The button.** A 48 x 48 dp touch target, placed 8 dp under the status bar inset (`KidInsets`), and clear of the
  rule card. The glyph is "···" in `kid_core` at 40 % alpha. Content description "Mer" / "More". It is a tap only,
  never a swipe from an edge, so it works the same with gesture and 3-button navigation, and it never competes with
  the system's back or home gestures.
- **The sheet.** A tap slides up a small sheet from the bottom. It has the `kid_ground_lift` surface, 16 dp corners, and
  its bottom padded by the navigation bar's inset (`KidInsets.buttonBarPx`, or the gesture area). It closes on a tap
  outside it, on Back (the screen keeps Back off otherwise: the sheet consumes Back only to close itself), or when the
  rule ends. It holds at most two rows:
  - **Flymodus**: the switch of §4.1 with the same states, lines and Off-only rule. It is shown when §4.1's row would
    show (bit 64 with the role) or airplane mode is on. The kid's own, no PIN.
  - **Foreldrekode** / **Parent code** ›: opens §4.4's choices, then the parent PIN keypad. Shown only when the lock
    is a **rule** and the budget isn't used up as well, `ParentPin.status(..).configured` is true, and the policy
    carries `rule_pause: true` (§4.4.1).
- **No rows, no button.** With neither row to show, there is no "···" at all, and the screen looks as today.
- **Where it isn't.** The sheet is on the unlocked time-rule screen only. Android's keyguard and the lock glance
  (design 59) are unchanged. A kid at the keyguard reaches neither row.
- **Under the kiosk.** `LockActivity` runs in Home's locked task, so nothing new is pinned. The Flymodus row's route
  runs as in §2.2. During a rule the kiosk pins only Vibb, and `routePin` adds Settings for the window in the same way.

### 4.4 Foreldrekode: an offline pause of the rule in force only (pure `rulePausePlan`, `RulePauseTest`)

**The user's decision (2026-11-14):** a narrow door on the phone, behind the parent PIN. It works offline, pauses only
the rule or rules in force now, and reaches the PWA at the next sync as a pause the parent can see and end there.

**History, so it isn't read as the old one coming back.** The old offline override ("Enter unlock code" on this screen,
`OfflineOverride`) opened **everything** for 2 hours. It was removed on 2026-11-01 by the user's choice of 2026-10-26,
that the parent lifts a rule from the PWA only. This door is the user's new decision, and it isn't that override:

- it pauses one rule's lock, and never the hardening, the kiosk or the allowlist;
- it never pauses a rule that starts during the pause;
- it offers only the PWA's own lengths.

#### 4.4.1 The steps on the phone: choose first, then the PIN (Q98-R3-M1)

As Settings' "Pause all restrictions" does, the parent chooses first, and **only the PIN gate stores the pause**.

1. **Foreldrekode ›** opens the choices: "Sett Leggetid på pause" / "Pause Leggetid" (`rule_pause_title`; with two
   rules in force, "Sett Leggetid og Skole på pause" / "Pause Leggetid and Skole").
   - **The lengths are exactly the PWA's `RULE_LIFT_MINUTES`:** **15 min**, **30 min**, **1 time** / **1 hour** and
     **2 timer** / **2 hours** (Q98-R3-M2). There is no "Til 07:00", which the mockup showed; D13 asks.
   - A length is offered only when it ends no later than the latest end among the rules in force.
   - The words under them: "Gjelder bare denne regelen. Du ser pausen i appen når telefonen har nett igjen, og kan
     avslutte den der." / "Applies to this rule only. You'll see the pause in the app when the phone is online again,
     and can end it there."
   - The button: "Sett på pause" / "Pause it".
2. **"Sett på pause"** opens the parent PIN keypad (`ParentPinDialog`/`ParentPinKeypad`) with the new entry
   `RULE_PAUSE("rule_pause")`.
   - The lockout is **the shared one** (`ParentLockoutState`, one per PIN): tries here count against every entry, so
     this door adds a place to type the PIN but no tries (Q98-R3-L1).
   - The keypad already handles `untilSync`, `foreignAccess` and the lockout.
3. **`ParentPin.check(RULE_PAUSE, pin, choice)`** works offline against the vault as every entry does. On `Ok`, the gate
   itself:
   - re-runs `rulePausePlan` at that instant (the rule may have ended meanwhile; nothing in force means nothing is
     stored and the screen just closes);
   - stores the pause in `startWindow`;
   - sends the `unlocked` event with `until` set to the pause's end.

   `ParentPinCallersTest` gains "only `ParentPin.kt` writes `phone_lifts_v1`".
4. **After the gate:** `TimeRulesRuntime.recheck` runs off the main thread (Q98-R3-M5). It re-arms `TimeRuleAlarm` with
   the pause's end among the boundaries (`liftExpiries` takes the active phone pauses), runs `AnchorRefresh.request`
   and re-evaluates the lock. The time-rule screen goes, Home opens, and the rule card on Home and the glance reads
   "Leggetid på pause til 21:40" / "Leggetid paused until 21:40", as for any lift.
5. **The choices and the keypad close on `onStop` and at screen-off.** A parent who hands the phone back with the
   choices open leaves nothing a kid can finish.

**When the row isn't offered** (§4.3's sheet):

- `ParentPin.status(..).configured` is false (Q98-R3-L6);
- the lock is the screen-time budget's alone (D11);
- **the budget is used up as well**: pausing the rule would leave the budget's lock, and Home wouldn't open
  (Q98-R3-L4). A `sheet` contract row pins this;
- the policy lacks `rule_pause: true`.

#### 4.4.2 The plan (`rulePausePlan(lock, minutes, clocks) -> PhonePause`)

- **The rules.** The active, un-lifted rules in `lock`, each with **its own end**: `min(now + minutes, that rule's
  current occurrence end)`. That isn't `lock.until`, which may chain into a rule that starts later (Q98-R3-L4).
- **A design 93 copy (a rule turned on now, negative id) is paused as the PWA pauses it** (Q98-R3-L3): its own id and
  the rule it copies, so the scheduled rule is paused with it, as the PWA's twin lift does.
  - For that, the server serves the copy with a new optional `copy_of: <rule id>` in `time_policy.rules[]`. This is a
    `lock_now.json` contract change, made in both directories in the same commit as `rule_pause.json`.
  - A copy without `copy_of` (an older server) is paused by its own id alone.
- **`key`:** 32 hex characters from `SecureRandom`, the pause's identity on both sides.
- **`started_wall_ms`, `elapsed_start_ms`, `boot_count`:** one `LiftRecord`-style window per rule
  (`untilWallMs`, `durationMs`), so `timedWindowActive` applies. Setting the clock back ends a pause **at once**, never
  later (Q98-R3-L8), and a reboot ends it.

#### 4.4.3 One clock source (Q98-R3-M3)

There are two stores, written by different code:

- **`phone_lifts_v1`** holds the windows: `{key, minutes, rules: [{id, untilWallMs, durationMs}], elapsedStartMs,
  bootCount}`. Only the PIN gate writes it.
  - It holds no PIN material: no PIN, hash, salt or attempt counter. `RulePauseTest` checks that the stored JSON's
    keys are exactly these.
  - An entry is kept until its last `untilWallMs` has passed by the clock slack (5 min), at most 8 entries, then
    pruned. One never posted stays until it is posted, or its start is older than the server's 7 days (code QA L5);
    over 8, the oldest posted entry goes first.
- **`phone_lifts_sync_v1`** holds the server's side: `{key, posted, acked_at_wall_ms, lift_ids}`. Only the post queue
  and the reconcile write it.

**The rule:**

- **A lift in the policy with a `phone_key` lifts only through the `phone_lifts_v1` entry with that key,** inside that
  entry's own window.
- `updateLiftRecords` never creates a `LiftRecord` for a lift with `phone_key`, and an unreadable `lift_records`
  can't restart one.
- An unknown key, a pruned entry, or an entry whose window ended (a reboot, the clock set back, its time up) lifts
  nothing, whatever the policy says. So a pause ended by a reboot never comes back when its rows arrive.

The dead pause is still posted, so the parent sees that it happened (§4.4.5).

**Whether the pause still lifts** (`phonePauseLifts(entry, sync, lastPolicy, clocks)`):

- its window is active; and
- either it isn't acked yet, or the newest policy whose GET **started after** `acked_at` still carries a lift with its
  key **for that rule** (or there is no such policy yet). Per rule (code QA M1): the PWA's "Avslutt nå" ends one row
  of a two-rule pause, and the phone ends that rule alone.

#### 4.4.4 Posting and acknowledging (Q98-R3-M4, Q98-R3-L2)

- **Post before the policy.** The queue's `POST /api/devices/rule-pause` runs **at the start of every sync, before the
  policy GET**, and at once when a network appears. So the sync that posts the pause also fetches a policy carrying
  it, and the status report of that sync lists the new lift ids in `lifts_active`. The PWA then never reads a fresh
  pause as "ended on the phone".
- **The body:**
  ```json
  {"key": "0123456789abcdef0123456789abcdef", "minutes": 30, "started_at_ms": 1794826200000,
   "rules": [{"rule_id": 2, "until_ms": 1794828000000}, {"rule_id": -1, "until_ms": 1794828000000}]}
  ```
- **The ack is the post's `200 accepted`.** The phone stores `lift_ids` and `acked_at`, the time the answer arrived.
  A policy carrying the key also acks, whichever comes first.
  - After the ack, a policy whose GET started after `acked_at` and lacks the rows **ends the pause** at once.
  - Then `TimeRulesRuntime.recheck` runs.
  - So "Avslutt nå" pressed in the PWA between the post and the phone's next policy is honoured at that next sync.
- **Every 4xx is final**, like a refusal: the entry is marked done and the pause stays the phone's alone until its end.
- **A 404** (a rolled-back server without the route) is retried until the pause's last `until_ms`, then dropped, as a
  past pause is (Q98-R3-L5).
- **A 5xx or no network** is retried with the queue's backoff.

#### 4.4.5 The server (`handlers::rule_pause`, Q98-R3-M2, Q98-R3-L5)

**The checks**, field by field. A body that fails any of them gets a `400`:

- `key` is 32 hex characters;
- `minutes ∈ RULE_LIFT_MINUTES` (`time_rules::valid_lift`'s set);
- `started_at_ms` is not more than the clock slack (5 min) in the future, and not older than 7 days;
- each rule's `until_ms` is greater than `started_at_ms` and at most `started_at_ms + minutes` plus the slack;
- 1 to 8 rules.

A rule id the device doesn't have is skipped. None left gives `200 {"accepted": false, "reason": "unknown_rule"}`.

**The rows.** One `time_lifts` row per rule id. A copy's negative id is written as the PWA writes a copy's pause (the
copied rule's row, from which `twin_lifts` serves the twin). Each row has:

- `target = 'rule'`, `rule_id`, `rule_name`, `minutes`, `expires_at = until_ms`;
- `phone_key = key` — the source marker (Q98-R3-L5);
- `created_by` **NULL**, so no username can collide with it; the PWA words the source from `phone_key IS NOT NULL`;
- `created_at` = the pause's start (`started_at_ms`, bounded by now).

`INSERT ... ON CONFLICT (device_id, phone_key, rule_id) DO NOTHING`, then the existing ids are selected. A retried post
returns the same ids and never revives a row the parent ended (`ended_early_at` stays).

**A pause that is already over** (every `until_ms` at or before now) is stored all the same as an over row. The Today
list shows "Leggetid på pause 21:10–21:40 (foreldrekoden)" / "Leggetid paused 21:10–21:40 (parent code)". The answer
is `200 {"accepted": true, "lift_ids": [..], "over": true}`. There is no `stale` refusal any more.

**A named security event** `time_lift_phone` (`security.rs`, as `record_lift` records `time_lift`), with the phone, the
rules and the minutes. The answer is `200 {"accepted": true, "lift_ids": [...]}`, and the server nudges the phone.

**The PWA's side:**

- The lift shows as every lift does: the summary's "Pause regler" line, the Time page's lifts, and "Avslutt nå"
  (`end_lift`, which any rule lift takes).
- It has a sub-line "Satt med foreldrekoden på telefonen" / "Set with the parent code on the phone".
- The PIN event's entry `rule_pause` is worded "Regelskjermen" / "The time-rule screen" in the inbox and the push: "Foreldre-PIN brukt på Emmas
  telefon · Regelskjermen" / "Parent PIN used on Emma's phone · The time-rule screen". The `until` of the event comes
  from the gate (§4.4.1 step 3).
- The Time page's ⓘ, for a phone reporting `rule_pause_v1`: "Foreldrekoden på telefonen kan sette regelen som gjelder
  på pause i 15 minutter til 2 timer." / "The parent code on the phone can pause the rule in force for 15 minutes to 2
  hours."

#### 4.4.6 Older servers and older launchers

- The policy's new top-level `rule_pause: true` says this server takes the post. The phone offers Foreldrekode only
  with it, so a pause always has a server to reach.
- `rule_pause`, `lifts[].phone_key` and `rules[].copy_of` are pinned by `policy_json_keys_snapshot` and the launcher's
  `PolicyResponseCompatTest`.
- An older launcher ignores all three; for it, a phone lift is an ordinary lift it never sees, since it never posts
  one.
- The launcher reports the capability `rule_pause_v1`.
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
- `rules`: a rule in force at the tap -> On allowed exactly as without it, and each time-rule edge (start, end, a
  pause, a lift) with airplane mode on -> no airplane action (§7.2). The parent's switch -> refused, rule or not.
- `home_line` and `sheet`: §4.2's line from `{on, restricted}`, and §4.3's sheet from `{lock kind, airplane row,
  pin cached, rule_pause}` -> `{button, rows}`.

**Its tests:**

- The launcher's `AirplaneModeTest` runs `rows` through `airplaneRow` and `controlsSection`, encodes `report` exactly
  and runs `emergency`'s rule on its side.
- The server's `tests::airplane_mode` posts `report`, checks what is stored, and runs `clean` and `emergency`.
- Both compare the copies.

The root `CLAUDE.md`, the three workflows' path lists and `intake_limits` get the file. No policy key changes: bit 64
rides in the existing `quick_controls_mask`.

### 5.3 The contract: a new file `server/testdata/rule_pause.json` (§4.4)

It follows `time_request.json`'s and `lock_now.json`'s patterns, and is byte-identical in
`launcher/app/src/test/resources/`.

**Its parts:**

- `capability` (`rule_pause_v1`).
- `policy`: the top-level `rule_pause: true`, and a `time_policy` whose `lifts[]` holds a phone pause's rows with
  `phone_key`.
- `request`: the exact `POST /api/devices/rule-pause` body (§4.4.4).
- `answers`: accepted with `lift_ids`, accepted `over`, `unknown_rule`, and the refused bodies with their `400`.
  - The refused bodies: minutes not in the set, `until` past `started + minutes`, `started` in the future, a bad key.
  - The phone's action for each: `done` for 200 and 4xx, `retry` for 404 until the end, `retry` for 5xx.
- `plans`: `{rules in force, budget used up, minutes, now}` -> `{offered lengths, rules: [{id, until_ms}]}`. With two
  rules ending at different times, a design 93 copy with `copy_of` (paused with its rule) and without it, a length past
  the latest end left out, and the budget used up (not offered).
- `adopt`: `{pause window, sync state, policies (GET start, lifts), clocks}` -> `{rule lifted?}`. The cases:
  - before the post;
  - acked by the 200 with the next policy carrying it;
  - **"Avslutt nå" before any policy carried it -> ended** (Q98-R3-M4);
  - **reboot, then the policy with the rows -> not lifted** (Q98-R3-M3);
  - the entry pruned, then the rows -> not lifted;
  - the clock set back -> ended at once;
  - a policy fetched before the ack and lacking the rows -> still lifted.

**Its tests:**

- The launcher's `RulePauseTest` encodes `request` exactly, reads every answer to its action, runs `plans` through
  `rulePausePlan` and `adopt` through `phonePauseLifts`, and checks the stored JSON's keys (no PIN material).
- The server's `tests::rule_pause` posts `request` (its instants moved to the last minutes) and produces each answer
  row. It checks the `time_lifts` rows (`created_by` null, `created_at` the pause's start) and that a retry adds none
  and doesn't revive an ended row. It checks the security event, and that `policy`'s lifts are served with
  `phone_key`.
- Both compare the copies.

`lock_now.json` gains `copy_of` on its copy rows, in the same commit. The policy's `rule_pause`, `lifts[].phone_key`
and `rules[].copy_of` are pinned by `policy_json_keys_snapshot` and the launcher's
`PolicyResponseCompatTest`. The root `CLAUDE.md` and the workflows' path lists get the file.

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
- ~~"Flymodus var på 21:03–07:10" box for a period that ended within the last 12 hours~~ - **moved to Activity by design 101 (the user, 2026-11-15)**: every ended period of the newest report is an Activity row "Flymodus på 21:03–07:10" / "Airplane mode on 21:03–07:10" (the week's bounds, no push, no unread); the summary keeps "Flymodus på siden …" while on, the emergency line and the lost-role warning.
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

**`0147_rule_pause.sql` (§4.4):**

- `time_lifts.phone_key TEXT` (null for the PWA's lifts), with a unique index on `(device_id, phone_key, rule_id)
  WHERE phone_key IS NOT NULL`. It is the source marker; `created_by` stays NULL for the phone's rows (Q98-R3-L5).
- The parent-PIN events' `entry` takes `rule_pause` (a Rust enum value, no schema change).

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
- **A time rule (the user, 2026-11-14):** **Vibb never ends airplane mode because a rule starts.** The kid could be
  on a flight. With airplane mode on before Leggetid or Skole starts, the phone stays offline through the rule, and
  calls the rule allows can't arrive until the kid turns it off. The kid can do that from the time-rule screen's sheet
  (§4.3) or Home's line (§4.2). Turning it on under a rule is allowed (§4.1). The parent sees "Flymodus på siden …"
  (§6.2), and only the parent's "Blokker flymodus" stops it altogether. A pure test pins that no time-rule edge (start,
  end, a pause, a lift) produces an airplane action (`AirplaneRouteTest`, and a contract row in §5.2).
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
  - The shade is closed and blocked for it (the features, §2.2 step 4), and the 10 s cool-down caps how often it
    opens.
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
- **The kid can be unreachable.** That is the feature, also through a rule (§7.2). The report-first step (§2.2) and the
  summary line make it visible, and 112 always works.
- **Foreldrekode on the phone is a door again** (§4.4), narrower than the removed override. It is guarded by
  `ParentPin`'s escalating lockout, and it is reported (the pin event at once, the lift when online). A kid who learns
  the PIN gains a pause of the rule in force, which the parent sees and can end.

## 11. Build steps and sizes

1. **Contract, both directories, one commit, S.**
   - `airplane_mode.json` and its copy (with §5.2's `rules`, `home_line` and `sheet` rows), the DTOs, `clean`, the capability.
   - The root `CLAUDE.md` paragraph and the workflows' path lists.
2. **Launcher: the assistant and the route, M.**
   - The three services and the XML.
   - `AirplaneRoute`: the refusals with ECBM and the cool-down, the report-only post, the resolved component, the
     window, Off.
   - `LockTaskChrome.routePin` with the status-bar write, and its `LockTaskHelpersTest` rows: `routePin` with
     `locked = true` leaves Settings **and** the recents provider out; with `locked = false` it adds Settings and
     drops `LOCK_TASK_FEATURE_NOTIFICATIONS` (Q99-H1).
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
6. **Launcher: Home's line and the time-rule sheet, S.**
   - `homeAirplaneLine` and the banner slot.
   - `ruleSheet`, the "···" button and the sheet in `LockActivity` with the insets for both navigation modes.
   - Unit rows: §5.2's `home_line` and `sheet`, and the `rules` rows (On allowed under a rule; no airplane action at
     any rule edge; the parent's switch wins).
7. **Rule pause, both directories, M.**
   - **Contract commit:**
     - `rule_pause.json` and its copy;
     - `lock_now.json`'s `copy_of`;
     - the DTOs (`Lift.phoneKey`, `TimeRule.copyOf`, `PolicyResponse.rulePause`);
     - the snapshot tests.
   - **Launcher (S-M):**
     - `ParentPinEntry.RULE_PAUSE`, with the gate storing the pause (`startWindow`, the event's `until`);
     - the choices screen (`RULE_LIFT_MINUTES` only, closing on `onStop`/screen-off);
     - `rulePausePlan` with per-rule ends and `copy_of`;
     - `phone_lifts_v1` and `phone_lifts_sync_v1`;
     - `phonePauseLifts` merged into `activeRuleLifts`;
     - `updateLiftRecords` skipping `phone_key`;
     - `liftExpiries` and `TimeRulesRuntime.recheck` after the start and after an end;
     - the post queue at the start of the sync, before the policy GET.
     - Tests:
       - `ParentPinCallersTest` ("only the gate writes `phone_lifts_v1`");
       - a `ParentPinStoreTest` row (four wrong tries at `PIN_LOCK`, a fifth at `RULE_PAUSE` -> locked out);
       - `RulePauseTest`, with one row per decision: only the rules in force; never `all`; each rule its own end; a
         rule starting later isn't paused; a copy paused with its rule; a reboot or the clock set back ends it at
         once; never revived by its rows; ended by "Avslutt nå" before or after the adoption; no PIN material stored.
   - **Server (S):**
     - 0147;
     - `handlers::rule_pause` with its checks, the over row, `ON CONFLICT DO NOTHING`, `created_at`, and the security
       event `time_lift_phone`;
     - `phone_key` in the policy's lifts, and `copy_of` on copies;
     - the sub-line from `phone_key`, the pin entry's words, the capability's ⓘ;
     - nb + en.
8. **Emulator run (§12), S-M.**

Total: launcher **M-L** (revision 2: M), server **M** (revision 2: S-M). Options: §9.1 **S**, §9.3 **M**, D10 **S**.
Snarveier is design 99.

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
     the shade back (`dumpsys statusbar`'s `mDisabled2` without `DISABLE2_NOTIFICATION_SHADE`); during the window it
     is set, and an open shade closes (Q99-H1).
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
13. **The time-rule sheet**, under the kiosk with a rule in force (Leggetid turned on now, design 93), with **gesture
    and 3-button navigation**:
    - The screen looks as today until "···" is tapped, and "···" sits clear of the status bar and the card.
    - The sheet's bottom row is above the navigation bar in both modes. Back and a tap outside close it.
    - Flymodus On from the sheet: the route runs under the rule (still LOCKED in Home's task, Settings hidden after)
      and the rule stays in force. Off from the sheet works.
    - Turn airplane mode on, then let a rule start (an edge 1 minute ahead): airplane mode stays on.
    - At Android's keyguard (the PIN lock LOCKED) the sheet isn't reachable. After unlocking, it is.
    - With bit 64 off and airplane mode off: no Flymodus row. With no PIN on the server as well: no "···".
14. **Foreldrekode**, offline and online:
    - **The basic path, offline.** In airplane mode with no Wi-Fi: "···" -> Foreldrekode -> 30 min -> "Sett på pause"
      -> a wrong PIN (the shared lockout counts) -> the right PIN. Home opens, and the card reads the pause. A second
      rule that starts within the 30 minutes locks.
    - **The alarm (Q98-R3-M5).** Pause 15 min offline and keep the screen on (a video): the rule is back at the
      minute.
    - **The choices close.** Open the choices, let the screen time out, unlock: the choices are gone.
    - **Online.** Turn airplane mode off. The post goes before the policy GET. The same sync's report lists the lift,
      the PWA's summary and Time page show "Leggetid på pause til …" with the sub-line, and the inbox has "Foreldre-PIN
      brukt · Regelskjermen" with its "til" time. "Avslutt nå" in the PWA -> after the sync the rule locks again.
    - **"Avslutt nå" before the phone's next sync (Q98-R3-M4).** Post with the nudge blocked (no SSE), press "Avslutt nå",
      then Sync now: the rule is back after it.
    - **Reboot, then online (Q98-R3-M3).** Pause offline, reboot (the rule is back), turn airplane mode off: the post
      goes, the PWA shows the pause, and the rule **stays** locked.
    - **The clock.** Date/time is locked while a time rule exists, so set it back with adb on the debug build: the pause
      ends **at once**, never later (Q98-R3-L8).
    - **No PIN material.** On the debug build, check that the prefs files hold `phone_lifts_v1`'s and
      `phone_lifts_sync_v1`'s keys only.
15. **Home's line:** airplane mode on -> "Flymodus er på · Trykk for å slå av" on Home in both navigation modes; a tap
    turns it off; with the pause's banner the two lines stack.

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
- **D8. Airplane mode and time rules. Decided by the user (2026-11-14), replacing revision 2's D8:**
  - Vibb never ends airplane mode because a rule starts (the kid could be on a flight).
  - Turning it on is always allowed, also under a rule (rules are enforced offline).
  - The parent's "Blokker flymodus" still wins.
  - The kid reaches Flymodus on the time-rule screen through concept C's "···" sheet (§4.3), and Home shows "Flymodus
    er på" with a tap to turn it off (§4.2).
- **D9. The assist gesture while the phone is open (Q98-L4). Accepted by the user (2026-11-14):** setup is only
  for setup, so losing these gestures while the phone is open for setup is fine. While the kiosk is off, Vibb's session hides unmarked requests, and the stock assist gestures
  (long-press power to the assistant, the corner swipe, a headset's assist button, Assistant/Gemini, Circle to Search
  through the assistant) open nothing. Under the kiosk they were dead already (M10). Nothing to build beyond §2.1.
- **D10. Call screening and redirection without adb (§3.2, Q98-M4).** **Recommended: yes**, a "Samtalefilter" row in
  "Fullfør oppsett" that requests both roles inside the same PIN-gated window. It closes the last adb-only grant in
  provisioning. Size S.
- **D11. Foreldrekode under the used-up screen-time budget alone** (no rule in force). §4.4 pauses rules, as the user
  asked. **Recommended: no row then**: the PWA's extra time and the kid's "Be om mer tid" (design r13) cover the
  budget, and a pause of "the budget" would be a new kind. Size XS to add later (a phone-originated budget lift of N
  minutes on the same path).
- **D12. Foreldrekode decided by the user (2026-11-14):** an offline pause of the rule(s) in force only (§4.4), in
  the PWA's choices, reported as a lift the PWA shows and can end. Nothing to decide.
- **D13. "Til 07:00" on the phone's choices** (it was in the mockup the user saw). **Recommended: leave it out.**
  - The PWA has no "until" for a rule lift (`RULE_LIFT_MINUTES` = 15, 30, 60, 120).
  - On an overnight Leggetid, "Til 07:00" would be a 10-hour pause from an offline PIN: five times anything the PWA
    gives.
  - Without it, the phone's door is never wider than the PWA's.
  - If the user wants it: it is a new length on both sides, in the PWA too, and the server's check grows. Size XS-S.

**Decided by the user (2026-11-14), all as recommended** - this is what is built:

- D1: bit 64 on in a new phone's default mask; the row hidden until the assistant step is done.
- D2: the QR page's "Ferdig" step offers "Åpne for oppsett nå".
- D3: airplane mode stays off after 112; no turn-on for 10 minutes or in emergency callback mode; the line in the
  emergency notice.
- D4: no battery saver.
- D5: Snarveier is design 99 (not here).
- D6: no Flymodus from the PWA.
- D7: no push when the kid turns airplane mode on.
- D8: as replaced in revision 3 (Vibb never ends airplane mode for a rule; turning it on always allowed).
- D9: accepted (the assist gesture does nothing while the phone is open for setup).
- D10: Samtalefilter without adb, in the setup window.
- D11: no Foreldrekode when only the budget locks.
- D12: Foreldrekode as specified.
- D13: no "Til <end>" choice - the PWA's 15/30/60/120 minutes only.

## 14. Design QA (`docs/review/qa-98-design.md`): how each finding was handled

| Finding | Handled |
|---|---|
| H1 the route writes the lock-task list past `LockTaskChrome` | §2.2 step 4: `routePin` is an input of `LockTaskChrome`, applied only while the kiosk is on and not `chromeLocked`; the route never writes the list. A LOCKED pass leaves Settings and the recents provider out, and the route ends `timeout`. §11 step 2's `LockTaskHelpersTest` rows, §12 test 2b |
| M1 the window opened by the kid, repeatable, wider than said | §2.2: the shade closed through the chrome while `routePin` is set (first said as the status bar disabled, which lock task only records; corrected 2026-11-15 to the features without `NOTIFICATIONS`, Q99-H1), the explicit component resolved `MATCH_SYSTEM_ONLY` and accepted only as a translucent Settings activity (step 3), the wait cut to 1 s, the 10 s cool-down. §10 rewritten. §12 tests 2 (the unhide-to-hide time), 2c, and the Jelly Star's component and theme |
| M2 process death or reboot mid-route | §2.2: the DE marker `airplane_route_open`, put back at `initRest` and `BootCallReceiver` before the first sync. `AirplaneRouteTest`, §12 test 8 |
| M3 the report under the monitor | §2.2 step 2: a report-only post outside every lock, 5 s budget. Only steps 4-7 take the monitor. A failure after `going_on` requests a sync (step 8) |
| M4 roles freed by the window, HOME assumed | §3.2: the window closes at the role's `onReady`. It snapshots every exclusive role at the start and compares at the end. HOME is re-seated, and its role is a device check. Screening, redirection and the browser are reported (`default_apps_changed`, the card). The table of what is and isn't taken back. §12 test 1b. D10 for the on-phone screening grant |
| M5 no row to leave airplane mode | §4: an Off-only row whenever airplane mode is on, whatever the bit and the role. Contract rows (§5.2), §12 test 11, §6.4 |
| M6 airplane mode under a time rule | Revision 2: D8 asked. Revision 3: the user decided (§15): never ended by a rule, the sheet on the time-rule screen (§4.3), Home's line (§4.2) |
| M7 the route skips the ECBM guard | §2.2 step 1: refused during ECBM, within 10 min of an emergency call (Vibb's callback window dropped 2026-11-15, the user: "112 10 min er greit"). `last_error: emergency`, §4's line, §12 test 5, D3 |
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

## 15. Revision 3: the user's answers (2026-11-14, evening)

| The user's answer | Where |
|---|---|
| Vibb never ends airplane mode because a rule starts; turning it on is always allowed, also under a rule; the parent's "Blokker flymodus" wins | §4.1, §7.2, §5.2's `rules` rows, D8 |
| Concept C on the time-rule screen: a dim "···" top right, a sheet with Flymodus and Foreldrekode, a tap in both navigation modes | §4.3, §12 test 13 |
| Foreldrekode pauses only the rule in force, offline, with the PWA's choices, and shows in the PWA, which can end it | §4.4, §5.3 (`rule_pause.json`), §6.3 (0147), §12 test 14, D12; revision 4: the PWA's lengths exactly, "Til" to D13 |
| "Flymodus er på" on Home, a tap turns it off | §4.2, §12 test 15 |
| D9 accepted: setup is only for setup | D9 |
| Snarveier is design 99 | §9.2, D5 |

## 16. Design QA of revision 3 (`docs/review/qa-98-design.md`, "Revision 3"): how each finding was handled

| Finding | Handled |
|---|---|
| R3-M1 the PIN and the pause are two steps | §4.4.1: choose first, then the PIN. `ParentPin.check(RULE_PAUSE, …)` re-runs the plan and stores the pause in `startWindow`, with the event's `until`. `ParentPinCallersTest`: only the gate writes `phone_lifts_v1`. The choices and keypad close on `onStop`/screen-off. §12 test 14 |
| R3-M2 the choices aren't the PWA's | §4.4.1: exactly `RULE_LIFT_MINUTES` (15/30/60/120), each offered only within the latest end. "Til" dropped and put to the user as D13. §4.4.5: the server's bounds. §4.4.4: every 4xx final. Contract rows for the refused bodies (§5.3) |
| R3-M3 two clock sources | §4.4.3: one source. A `phone_key` lift lifts only through its own `phone_lifts_v1` entry and window. `updateLiftRecords` skips them. Entries are kept to their end plus the slack, at most 8. `adopt` rows "reboot, then the rows -> not lifted" and "pruned -> not lifted". §12 test 14 |
| R3-M4 "Avslutt nå" before the adoption | §4.4.4: ack on the post's 200 (or a policy carrying the key). A policy whose GET started after the ack and lacks the rows ends the pause. The post runs before the policy GET. `ON CONFLICT DO NOTHING` returns the existing ids and never revives an ended row. An `adopt` row and §12 test 14 |
| R3-M5 no boundary alarm for the pause | §4.4.1 step 4 and §4.4.4: `TimeRulesRuntime.recheck` after the start and after an end. `liftExpiries` takes the phone pauses. §12 test 14 "keep the screen on" |
| R3-L1 "its own lockout" | §4.4.1 step 2: the shared `ParentLockoutState`. A `ParentPinStoreTest` row |
| R3-L2 the report marks the new pause ended | §4.4.4: the post goes before the policy GET, so the same sync's report lists the lift ids |
| R3-L3 a design 93 copy | §4.4.2: the PWA's meaning. A copy is paused with the rule it copies, through a new `copy_of` on the served copy (`lock_now.json`, both directories). Without `copy_of`, the copy's id alone. `plans` rows |
| R3-L4 two rules, the budget | §4.4.2: each rule its own end, `min(now + minutes, that rule's occurrence end)`. §4.4.1: not offered while the budget is used up as well (a `sheet` row) |
| R3-L5 the server row's details | §4.4.5: `created_by` NULL with `phone_key` as the marker, a security event `time_lift_phone`, `created_at` = the pause's start bounded by now, a past pause stored as an over row (accepted, `over`) instead of `stale`. §4.4.4: a 404 retried until the end, then dropped |
| R3-L6 "a PIN hash is cached" | §4.3 and §4.4.1: `ParentPin.status(..).configured` |
| R3-L7 English missing | English given for the sheet's row, the choices' title (one and two rules), the lengths, "Sett på pause", the line under them, the card's pause line, the PWA sub-line, the inbox entry and the Time page's ⓘ |
| R3-L8 test 14 | §12 test 14: the clock set back ends at once (through adb on the debug build, since Date/time is locked). Added: the M3 reboot case, the M4 "Avslutt nå" case, the alarm case, the choices closing |


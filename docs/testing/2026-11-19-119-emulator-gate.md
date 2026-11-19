# Emulator gate: Chrome only for logins (design 119), 2026-11-19

Branch `build-119` at 4b4665f45 (main merged in: map-open-fix, lookup-fix). Design 119 §9's measurements M1-M7, §11's
gate (1-7) and code QA M1's night window. Run 16:52-18:12 UTC, one emulator at a time under `flock -o ~/emulator.lock`,
the user's `jellystar2` not touched. No product code was changed.

**Builds.** The debug launcher (`assembleDebug -PwarningsAsErrors=true`, under the build lock) and the debug server
0.85.2 (`cargo build -j 1`).

**Rig** (scratchpad, not in the repo).
- **The server:** on a copy of the C2 gate's data, admin 3130 and phones 3131, with the debug `LAUNCHER_ADMIN_COMPONENT`.
  Device 1 is `compat34` and device 2 is `jelly36`.
- **Each phone:** the debug launcher as Device Owner with §4's grants, enrolled with a typed code. 114's setup hold
  ended with "Lås igjen".
- **Apps:** Organic Maps (F-Droid, the map-links gate's APK) and a probe app ("Login Probe", plain Java, built with
  aapt2/d8 and signed with a throwaway key). Both were let out with "Allow these apps…" and ticked.
- **The PWA:** the Sign-in role on with the Feide and Google presets and the own site `idp.localhost:8460`, map links
  on, calls and SMS unmanaged (SMS managed only for row 6's Meldinger part).
- **Logins:** a fake IdP (`fake_idp.py`, no real accounts) on the VM, reached as `http://idp.localhost:8460` through
  `adb reverse`. Chrome resolves `*.localhost` to loopback, so there is no DNS and no real login site. Its "Logg inn"
  link goes to `vibbprobe://cb` (the probe's `singleTask` Main, Element X's shape) or `vibbauth://cb` (an AppAuth-shaped
  `RedirectUriReceiverActivity` → `CLEAR_TOP` management activity). A `/passkey` page calls `navigator.credentials.get`.
- **The probe starts each link as an app does:**
  - `view`: a plain `VIEW`, no package, as Element X's timeline does;
  - `ct`: a session-less Custom Tab with `EXTRA_HEADERS` (`Accept-Language` and a non-safelisted `X-Probe-Secret`) and
    a colour scheme;
  - `appauth`: AppAuth's `BrowserSelector`, then `setPackage`;
  - `gpn`: `CustomTabsClient.getPackageName(null)`;
  - `result`: `startActivityForResult`;
  - `authtab`: Auth Tab's extras;
  - `newtask`: from the application context with `NEW_TASK`.
- **Element X itself was not used:** there is no homeserver offline. The probe's `view` is its exact intent.

Versions:
- **Android 14:** `compat34` (Google APIs image, `UE1A.230829.050`, patch 2023-09-05; userdebug, so `adb root` works),
  started with `-wipe-data`. Chrome 113.0.5672.136. Play services present, no Play Store.
- **Android 16:** `jelly36` (Google Play image, `BE2A.250530.026.D1`). Chrome 133.0.6943.137. Before the run it
  equalled `~/kid-look-1-avd-pre-start` (22 of 22 sums, the same file list). It was restored from that copy after the run
  (22 of 22, the same file list, `jelly36.ini` equal).
  - It was OOM-killed once at about 17:56, when another agent's Gradle build started, and was started again on the same
    state.
- **Android 17:** not run.

## Findings

1. **An unclean reboot within seconds of an unhide crashes the platform at boot.**
   - **Android 14 (both tries):** a boot loop. **Android 16:** one system crash, then it boots.
   - **What happens:**
     - `DevicePolicyEngine` reapplies the stored policies at boot (`reapplyAllPolicies…`).
     - Its `applicationHidden` value for Chrome is `false`. Package Manager's `package-restrictions.xml` still says
       `hidden="true"`: Package Manager writes the file about 10 s late.
     - The reapply unhides Chrome, which sends `PACKAGE_ADDED` before boot completed: `IllegalStateException: Cannot
       broadcast before boot completed`.
   - **How it was triggered:** a login window opened, then `adb reboot` 4-6 s later. `adb reboot` goes through init and
     skips the framework's shutdown, like a forced restart or a crash.
     - **On 14** `system_server` crashed 20-60 times, until `package-restrictions.xml` was set to match the engine by
       root (`adb root`, `abx2xml`/`xml2abx`, a rename because the file is fs-verity).
     - **On 16** it crashed once (`DevicePolicyManagerService$Lifecycle.onBootPhase`), and the second start booted.
     - A **hide** followed by a reboot within 3 s did not crash (14).
   - **The scope:** this is Android's bug, not 119's. Every unhide by the Device Owner opens the same ~10 s gap (a
     pause, the setup hold, a login window). 119 opens it at each login.
   - **Not fixed here.** A possible mitigation for the coordinator: after the window's unhide, force Package Manager's
     write, for example a `setComponentEnabledSetting(…, PackageManager.SYNCHRONOUS)` flip of an own no-op component.
     That writes the user's whole restrictions file at once, but it changes the role hash (as the nudge does). Whether
     later 14 builds fixed this is not known; the image here is 14's first release.
2. **When Vibb's Custom Tabs session isn't bound within 2 s, leaving the tab ends nothing (16).**
   - 2 of about 12 windows on 16 opened with `session: false`. Both followed a Chrome cold start that took about 2.3 s
     to bind; Chrome is stopped every time it is hidden. None of about 15 windows on 14 did.
   - Without the session there is no `TAB_HIDDEN`, so no grace. Home for 70 s left Chrome unhidden; only the ceiling,
     the screen lock or the anchor's events end such a window.
   - This is H2's named fallback, so not fixed. Raising `SESSION_WAIT_MS` (2 s) is the obvious small change, at the cost
     of the tap-to-tab time on a slow phone. The Jelly Star's number is owed.
3. **Play services' passkey and credential sheets are blocked by the kiosk (both).**
   - A passkey request in the tab started `com.google.android.gms/.fido.fido2.ui.Fido2FullScreenActivity` (14) or
     `.identitycredentials.ui.CredentialChooserActivity` (16).
   - Lock task refused it: "App is not available - Google Play services is not available right now". The page got
     `NotAllowedError`.
   - The window held (M5's intent), but a passkey login can't finish outside a pause. A design question: Play services
     is never pinned.
4. **Chrome 113's tab menu (14) has "Add to Home screen" and "Open in Vibb One (debug)"**; the latter goes to the gate.
   Chrome 133's menu (16) has neither, and no "Open in Chrome browser" either (L5: not visible on either version, so not
   checkable). The tab's "Minimise" (133) does nothing under lock task.
5. **The first login after Chrome's data clear passes through Chrome's first-run screen.** On 14 it showed "Welcome to
   Chrome / Continue" for about 5 s and passed by itself; Chrome then re-sent the tab with `NEW_TASK`, so that tab ran in
   Chrome's own task (it still ended `done`). On 16 it was a spinner of about 10 s in the caller's task. The tap-to-tab
   time was about 15 s on 16's cold first run, and 0.2-1.1 s afterwards.
6. **A `NEW_TASK` caller's window ends by the grace, not `done`.** The redirect brings the app's task forward and leaves
   the gate's task (the gate and the tab) behind. The window ended `left` 61 s later and the gate's task was removed.
   This is as §4.4's grace says; Chrome stays visible for that minute.
7. **AppAuth during an open window picks Chrome by name.** A probe `appauth` started while a window was open (16)
   listed `[Vibb One, Chrome]` and chose Chrome. Its start failed (`ActivityNotFoundException`) as the window closed.
   This is §4.8's named residue.

## Rows

| Row | Android 14 (`compat34`) | Android 16 (`jelly36`) |
|---|---|---|
| M1 a fresh phone | **Pass.** Chrome held the role before. In 114's setup hold every app is out, so the gate's enable left Chrome the holder; at the hold's end Chrome was hidden and the repair's pause-end nudge moved the role to Vibb One (`RoleControllerServiceImpl`: Chrome "no longer qualifies", Vibb One "default/fallback") | **Pass**, the same (the nudge 12 s after the hold's end) |
| M1 Chrome ticked → unticked | **Pass.** Ticked: the gate disabled, the role Chrome's within 1 s, `disallow_config_default_apps` lifted. Unticked: Chrome hidden, the gate enabled, the role Vibb One's within 1 s **without** the nudge; the restriction back; D13's clear ran | **Pass**, the same |
| M1 a pause that made Chrome the default | **Pass.** In the pause the restriction was off and Chrome visible; `cmd role add-role-holder … BROWSER com.android.chrome` (an adb stand-in for Default apps). The pause's end: Chrome hidden, the restriction back, the nudge at once (inside the hour since the last one), the role Vibb One's 7 s after | **Pass**, the same (the role back in about 1 s) |
| M1 the `vibb_browser` dialog | Not run (Settings' parent PIN gate) | Not run |
| M2 | **Pass.** An app sees one full browser, the gate (and it is the default); no Custom Tabs service; no `APP_BROWSER`; `getPackageName(null)` = null; AppAuth selects Vibb One. With Organic Maps' `omaps.app` reset to unverified (`pm set-app-links … 0 all`) the gate's per-package lookup opened Organic Maps by package (H1) | **Pass**, the same |
| M3 | **Pass.** The tab sits in the caller's task above the invisible anchor, lock task LOCKED. Tap to tab 1.1 s. The CORS-safelisted `Accept-Language: nb-NO` reached the site; `X-Probe-Secret` did not. A `NEW_TASK` caller: the gate is its task's root (finding 6) | **Pass**, the same; tap to tab 0.2-0.9 s warm |
| M4 `done` | **Pass.** A custom-scheme redirect into the `singleTask` activity: Chrome hidden 1.3 s after the redirect. AppAuth's `CLEAR_TOP` path: 2.0 s | **Pass:** 0.03 s; AppAuth 0.9 s |
| M4 ✕ `closed` | **Pass** | **Pass** |
| M4 for-result `result` | **Pass:** `RESULT_CANCELED` relayed on ✕, end `result` | **Pass** |
| M4 Auth Tab | Chrome 113 has no Auth Tab: a plain tab; the redirect reached the app through its filter (`done`), and the caller got `RESULT_CANCELED`. The relay of a real Auth Tab result and L6's https redirect: **not measurable** (Chrome 137+ needed) | Chrome 133: the same, not measurable |
| M4 the grace | **Pass.** Home, then back through Recents after 22 s: the tab was still there and the window open. Home for 61 s: `left`. (Back through the app's own icon clears a `singleTask` app's tab: `done`) | **Pass** with the session: Google Messages for 10 s, back through Recents, the tab there; Home 60.5 s: `left`. **Fails without the session** (finding 2) |
| M4 the screen off | **Pass:** `locked` | **Pass:** `locked` |
| M4 5 minutes | **Pass:** `timeout` at 5:00.97 | Not confirmed: the session-less window that should have timed out was in flight when the emulator was killed. After the restart the record was gone and Chrome hidden |
| M5 | Finding 3. The blocked sheet over the tab did not end the window | The same |
| M6 / gate 1 | **Pass.** `omaps.app` → Organic Maps (verified here, and through the gate once unverified). `maps.app.goo.gl` → Play services' activity, "App is not available", until map step 1 (done by adb: `pm set-app-links-allowed … gms false`); then the gate → `MapLinkActivity`. `www.google.com/maps/place/…` → the gate → the handler. `example.com` → "This link can't be opened here." Chrome never in front. "Stoppet nylig: example.com · Legg til" on the PWA | **Pass**, the same |
| M7 a process kill | **Pass:** `stale` 4 s after the kill, Chrome hidden | **Pass:** `stale` in 2 s |
| M7 a reboot with Chrome out | Finding 1 (a boot loop). After the repair, the first start ended the window `stale` and Chrome stayed hidden | Finding 1 (one crash). Then `stale`, Chrome hidden |
| Gate 2 a login in an app | **Pass** (the fake IdP, `end: done`) | **Pass** |
| Gate 3 leaving for another app | **Pass** (above) | **Pass** with the session (above) |
| Gate 4 the host not listed | **Pass:** the note, nothing else; the host into `refused` | **Pass** |
| Gate 5 Wi-Fi portal | Partly: Android resolves `CAPTIVE_PORTAL` to Vibb's `CaptivePortalEntry` (enabled beside the gate), Chrome hidden. The window itself not run (it needs §6ap's captive proxy rig) | The same |
| Gate 6 Meldinger | **Pass.** One SMS from a contact with three links: `example.com` → the note; `idp.localhost:8460/login` (a listed site) → the note (D3); `omaps.app` → Organic Maps. No Chrome | **Pass**, the same |
| Gate 7 a pause | **Pass:** the restriction lifted, Chrome visible, a plain link opened in Chrome; the end above | **Pass**, the same |
| Code QA M1 the night window | **Pass** (clock set to 02:30 by root, screen off, a nudged apply): Chrome unhidden, the role Vibb One's, a link went to the gate, not Chrome. The screen on hid Chrome within about 0.5 s. That `version_name` moves after a night: not measurable here (no Play Store) | Not run (no root on the Play image) |

Screenshots and logs (the boot-crash logs too) are kept outside the repo, in the session scratchpad's `g119/`.

**Owed:**
- Auth Tab and L6 on a Chrome 137+.
- The `vibb_browser` step's dialog.
- `version_name` moving after a night (the Jelly Star).
- The Wi-Fi window with §6ap's proxy.
- Element X itself with a homeserver.
- Android 17.
- The Jelly Star's session bind time (finding 2).
- Findings 1 and 3 for the coordinator.

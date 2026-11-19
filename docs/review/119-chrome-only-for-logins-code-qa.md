# Code QA: design 119, Chrome only for a login (branch `build-119`), 2026-11-19

This is a read-only review of `git diff 9d2459cc6..build-119` at `0a7fcd057`, steps 1, 3, 4 and 5. It covers:

- Meldinger's lookup;
- the gate, the window and the repair;
- the report, the setup step `vibb_browser`, the contracts and the PWA;
- the notes.

I read it against `docs/design/119-chrome-only-for-logins.md` rev. 2 and `docs/review/119-chrome-only-for-logins-design-qa.md`.
Nothing was built or run: no Gradle, no cargo, no emulator. Findings: **0 High, 2 Medium, 9 Low**.

## What holds (checked, no finding)

- **No non-login link reaches Chrome while Chrome isn't ticked.**
  - The gate never takes a package or extras from its caller. Every start is a fresh intent: the URL, a package the
    gate chose, `NEW_TASK` only for another app.
  - `linkTargetOf` admits only allowlisted packages, never Vibb One, Play services or a full browser.
  - During a window, a second link from any app still goes to the gate. Android's own answer is then the gate (filtered
    out as `own`), and Chrome is not in the allowlist, so `found` never names it.
  - The only ways to Chrome are `GateStep.Login` (role ready, Vibb holds the role, a host match, not from Meldinger)
    and `GateStep.Browser`. The second needs a pause, or Meldinger with a ticked browser, and with a ticked browser the
    gate is off.
- **Hide and unhide are serialised by the window's own lock** (M3).
  - `open` writes the record and then unhides.
  - `step` deletes the record and then hides.
  - The apply's Chrome step (`chromeStep`) and `enforceOnNewPackage` (`chromeAppeared`) re-read the record under that
    lock, so a plan read a moment earlier can't win. Neither path holds the enforcer's lock on the main thread.
- **A process death or a reboot is fail closed.**
  - The record carries the boot count and a pid-plus-start token.
  - `atProcessStart`, the first apply (`duringApply`, then `isOpen` and `chromeStep`) and the backstop alarm (which
    starts the process) all end it `stale` and hide Chrome.
  - In direct boot, the CE prefs throw. `readRecord` then returns null, the plan hides Chrome, and Chrome can't start
    before the unlock anyway.
  - The secondary processes (`:bootcover`, `:portal`, `:mmsparse`) return from `Application.onCreate` before
    `atProcessStart`, so none of them can call a live window stale.
- **The M1 order holds.** The loop hides Chrome, then `afterLoop` switches the gate, then the nudge runs.
  `justEnabled` skips the nudge on the apply that enables the gate, and a pause's end runs it at once. The kept flip
  (rather than enable then disable) is sound: the role service hashes the state when the broadcast arrives.
- **The PPA catch-all is harmless while the gate is disabled.** AOSP's `findPersistentPreferredActivity` keeps a PPA
  only if its component is in the resolved query, and a disabled component never is. `PlayInvariantsTest` pins it last,
  in both `apply` and the re-seat.
- **`DISALLOW_CONFIG_DEFAULT_APPS`:**
  - `gateLock = gateOn && !overrideActive` (D4) feeds both writers, the dialer's and the SMS role's;
  - `defaultAppsLockLifted` adds `BrowserRoleStep.isOpen` beside design 98's pick window;
  - no other writer leaves the gate's reason out.
- **The builder's departures** are sound:
  - one kept nudge flip;
  - the 8 s tab watchdog, which closes the gap of a lock-task refusal that throws nothing;
  - `browser_role` null while the gate is off;
  - no second orange row: the setup step's warning sits at `#logins-apps`, and its fix sits behind the switch's ⓘ;
  - "Stoppet nylig" only while the role is on, minus hosts a listed site covers;
  - the screen off ending the window: the kid's phone always has a secure lock, so this is just stricter;
  - the Activity Result API, with `onActivityResult` before `onResume`, so there is no double end.
- **Contracts.**
  - `login_windows.json` and `setup_steps.json` are byte-identical in both directories (`cmp`).
  - `LoginBrowserTest` runs `gate`, `link_match`, `target`, `window`, `window_open`, `order` and `plans`.
  - `logins::clean_browser` drops a host with a path or query, an instant older than 7 days, and a `last_window.host`.
    It keeps the last 10.
  - `intake_limits` passes `now_ms`.
  - `login_browser` is not among the heartbeat's fields, so a new stopped host changes the hash and sends a full report.
- **Dependency verification.** `browser-1.9.0.aar` (`b6aa2d57…`) and `.module` (`bfff4b0e…`) equal Google Maven's
  published `.sha256`. `concurrent-futures-1.0.0.pom` (`4505b9a5…`) matches its file, whose `.sha1` equals Google
  Maven's. androidx.browser is Apache-2.0.
- **The PWA.**
  - "Legg til" posts `section=site` back to `#logins-sites` with a 303, so there is no jump; the test checks it with
    `assert_redirect` and `check_forms`.
  - nb and en keys are in pairs.
  - The (i)s hold the explanations.
  - The list is a choice, never orange.
- **Android 14-17:** every API above 34 is gated; `ResolveInfoFlags` is 33, `registerReceiver` uses `NOT_EXPORTED`. On
  15 and 16, safer intents and the redirect hardening are met: each start is explicit and fresh, and comes from a
  visible activity.

## Medium

### M1. Chrome is now hidden almost always, so Play never updates it, and a stale Chrome does every login

- **Where:**
  - `launcher/app/src/main/java/com/kidslauncher/mdm/server/EnforcementPlan.kt:233`: `hide += roleApp` outside a window;
  - `logins/LinkGate.kt:236` (`loginChromeHidden`).
- **What:** under design 102, the role's Chrome was unhidden for good while it was ready, so the nightly Play update
  window (`play/PlayPolicy.kt` `UpdateWindow`) kept it current. Now it is unhidden only for ≤5 minutes per login. A
  hidden package is "not installed" to every caller without `MATCH_UNINSTALLED_PACKAGES`, so Play neither lists it nor
  updates it. The kid's logins (Google, Feide, Microsoft: third-party web content) then run in a Chrome that falls
  further behind on security fixes every week. Auth Tab (Chrome 137+, §4.5) and newer Custom Tabs callbacks may also
  go missing on a factory image. Neither the note nor the design QA names this cost.
- **Fix:**
  - Treat the role's Chrome as unhidden while `playState.updateWindow` is in force (inside the window, screen off).
    Chrome isn't reachable then: the screen is off, and the screen-on that ends the window re-applies and hides it again.
  - Hold any login window shut during it.
  - Add a `plans` row ("the update window: unhidden, no window") and an emulator check that `version_name` moves after
    a night.
  - If the user would rather not unhide at all, it is a visible-cost choice for the user. Either way, name it in §4.8.

### M2. A window open when a pause or the override starts later hides Chrome inside the pause

- **Where:**
  - `server/AppEnforcer.kt:497`: `ruleEnds` ends a window at a pause's **end**, not its start;
  - `logins/LoginWindow.kt:124-156`: `step()` hides Chrome at every end;
  - `server/AppEnforcer.kt:1683` (`enforceOnNewPackage` returns early in an override, so nothing unhides it).
- **What:** the kid is mid-login, and the parent starts a pause or enters the parent code. The apply unhides every app;
  the window stays open. Then its own end comes (✕, `done`, the grace, 5 minutes, screen off), and `step()` hides
  Chrome in the middle of the pause. It stays hidden until the next full apply. `SyncRunner.request` asks for one, but
  the offline override exists exactly for a phone without a server. That breaks the standing rule that a pause lifts
  every restriction (D4, D9).
- **Fix:** end the window at a pause's start. Add `(overrideActive && lastEnforcedOverride != true)` to `ruleEnds`, so
  the end and its hide come before the loop, and the loop then unhides Chrome with every app. As defence in depth,
  `step()` could skip the hide while `OfflineOverride.isActive() || RestrictionsPause.isActive()`. Add a `window` row
  and a `LoginBrowserTest` case for "a pause starts: rule, and the plan unhides".

## Low

### L1. On the apply that ticks a browser, the gate stays on

- **Where:** `server/AppEnforcer.kt:445`, `logins/LinkGateRuntime.kt:49-63`.
- **What:** `gateWanted` reads `browsers()` before the loop. At that point a just-ticked Chrome or Firefox is still
  hidden, and a hidden package doesn't resolve. So the gate stays enabled and holds the role, and
  `DISALLOW_CONFIG_DEFAULT_APPS` stays set, until the next full apply. Links still work: the gate's `freeBrowser`
  passes them on. But the role and the restriction lag behind the parent's choice.
- **Fix:** base the decision on the allowlist and the installed browsers:
  - query with `MATCH_UNINSTALLED_PACKAGES` (or `MATCH_ALL | MATCH_UNINSTALLED_PACKAGES`);
  - or decide `gateWanted` from the allowlist ∩ the known browser packages;
  - or re-run `gateWanted` in `afterLoop`.

### L2. `LoginWindow.open` doesn't re-check the gate's conditions under its lock

- **Where:** `logins/LoginWindow.kt:81-103`; `LinkGateActivity.kt:85-92`.
- **What:** the decision (role ready, not locked, not paused) is read before `open`. An apply in between can turn the
  role off: `BrowserConfig` hides Chrome and clears the lockdown, and `duringApply` sees no record. `open` then unhides
  a Chrome with no `URLBlocklist`. `enforceOnNewPackage` hides it again (the role isn't ready, so Chrome isn't
  allowed), which makes this a short race, not an escape. With a time rule, the unhidden but suspended Chrome shows
  Android's "Chrome is paused" dialog.
- **Fix:** inside `open`'s `synchronized(lock)`, read `BrowserConfig.roleReady(...)` and the lock reason again, and
  refuse (the note) when either changed.

### L3. Reading an untrusted caller's extras can throw on the main thread and take the launcher process down

- **Where:** `logins/LinkGateActivity.kt:223-239` (`copyHeaders`: `Bundle.get` on the caller's `EXTRA_HEADERS`
  bundle), called from `startTab` with no try.
- **What:** any visible app can send a login-host link whose `EXTRA_HEADERS` bundle holds a Parcelable class Vibb
  doesn't have. `get()` then throws `BadParcelableException` on the main thread. That crashes Vibb's main process: the
  call services and the VPN, and a call in progress. It fails closed (the window is `stale`), but it is a denial of
  service.
- **Fix:** wrap `copyHeaders` and `copyAuthTab` in `try/catch (e: RuntimeException)`. Read the headers with
  `getString(key)`, which gives null for a non-string, never `get`.

### L4. `java.net.URI` is stricter than Android and Chrome, so some real login links fail without a trace

- **Where:** `logins/LinkGate.kt:31-63` (`loginLinkMatches`, `refusedHostOf`).
- **What:** a URL with an unencoded `|`, space, `{`, `}`, `^` or `"` in its query throws `URISyntaxException`. Such
  URLs occur in some IdPs' hand-built links. `Uri.parse` and Chrome accept them. The login then gets the note, and
  `refusedHostOf` is also null, so "Stoppet nylig" shows nothing and the parent can't see why.
- **Fix:** split scheme, authority and path by hand: the authority ends at the first of `/ ? #`, and `userinfo@` is
  stripped. Or pass in the host, port and path the glue reads from `android.net.Uri`, and keep the pure matcher on
  those. Add `link_match` rows with `?scope=a|b` and a raw space.

### L5. androidx.browser 1.9.0 can turn off "Open in Chrome browser" (§4.8's residue)

- **Where:** `logins/LinkGateActivity.kt:189-199`.
- **What:** the builder already sets share, bookmark and download off. 1.9.0 also has
  `CustomTabsIntent.Builder.setOpenInBrowserButtonState(OPEN_IN_BROWSER_STATE_OFF)` (`@ExperimentalOpenInBrowser`; I
  checked it in the AAR). Where Chrome honours it, it removes the path from the tab to full Chrome for the grace minute.
- **Fix:** set it (opt-in annotation) and add it to M4's check. If Chrome ignores it, the residue stays as named.

### L6. Auth Tab with an https redirect will likely fail Chrome's verification

- **Where:** `logins/LinkGateActivity.kt:231-239`.
- **What:** Chrome checks the https redirect host's Digital Asset Links against the **launching** package. That is
  Vibb One here, not the app, so expect `RESULT_VERIFICATION_FAILED` relayed to the app. Custom-scheme redirects are
  unaffected.
- **Fix:** none in code. Name it in §4.5 and in M4's check, and leave such apps to ASK 2 (the pause).

### L7. A recreated anchor finishes without ending the window

- **Where:** `logins/LinkGateActivity.kt:78`; the manifest's `configChanges` for `.logins.LinkGateActivity`.
- **What:** some config changes are not in the list: `mcc|mnc` (a SIM swap), `navigation`, `touchscreen`,
  `colorMode`, `fontWeightAdjustment`, `grammaticalGender`. One of them recreates the anchor under the tab. The new
  instance sees `savedInstanceState != null` and finishes, but it isn't registered and doesn't end the window. The
  window then ends only by the grace or the ceiling: Chrome stays out up to 5 minutes after the ✕.
- **Fix:** on a restore, call `LoginWindow.event(applicationContext, WindowEvent.ANCHOR_RESUMED)` before finishing. If
  the process died, the record is stale anyway. Or add the missing `configChanges` flags.

### L8. Stopped hosts are collected and reported on every gated phone, though shown only while the role is on

- **Where:**
  - `logins/LinkGateActivity.kt:106`; `ui/messages/MessageLinks.kt:134`;
  - `server/handlers/logins.rs` (`stopped` only when `apps_on`).
- **What:** on a phone with the login role off (D2: the gate is on), every refused chat or app link's host goes to the
  server, with a timestamp, for a list nobody can see.
- **Fix:** record in `LoginWindow.refuse` only while `BrowserConfig.roleReady(...)`, and leave the report's `refused`
  empty otherwise. Or have the server drop it when `login_browser_enabled` is off.

### L9. A path entry hides every stopped link on its host

- **Where:** `server/src/handlers/logins.rs:274-280` (`stopped_hosts`, `taken`).
- **What:** `entry_host("school.example/login")` counts `school.example` as taken. A stopped link to
  `school.example/sso` therefore never shows, and the parent gets no hint that the path entry is too narrow.
- **Fix:** count a host as taken only by an entry without a path. A path entry should leave the row, so "Legg til" can
  widen it to the host. Or keep the rule and say so in the list's (i).

## Not findings (noted)

- The known residue: design 98's `default_apps` snapshot reads the browser by `resolveActivity`. It can list "browser"
  as moved if the gate switches on or off during a pick window. It is rare, and the note names it.
- A full-screen Play services "add account" flow over the tab sends `TAB_HIDDEN` and starts the 1-minute grace. A
  slow account entry could then end the login (`left`). That is M5's measurement, already owed. If it bites, the
  grace could pause while Play services is in front.
- `KidScreensEscapeTest` doesn't list `LoginWindow.kt`, `LinkGateRuntime.kt` or `LinkLookup.kt`. None of them starts an
  activity, so nothing needs guarding.

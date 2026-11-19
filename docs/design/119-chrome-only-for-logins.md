# Design 119: Chrome only for a login, never for a link

Status: design, revision 1, 2026-11-19 (branch `design-119`). Not built. It changes design 102 Part B
(`docs/design/102-captive-portals-and-logins.md` §4, `docs/notes/launcher/logins.md`). Part A, the Wi-Fi sign-in window,
stays as it is.

## The requirement

The user, 2026-11-19, after links tapped in Element X opened Chrome:

> "når du trykker på offisiell innlogging inne i en app så får du opp vinduet du trenger for å logge på - og that's it.
> samme med wifi captive portals. bortsett fra det får du aldri se chrome, og den skal absolutt ikke åpne en eneste
> lenke eller noe sånnn."

Earlier: "om det ikke er en lenke for de konkrete sign-in tjeneste vi har listet opp inn på web-filter og sign-in, så
burde ikke det skje". And: "det må ikke være akkurat slik jeg sier altså" - the behaviour is fixed, the mechanism is
ours to choose.

So the rule is:

- A link to one of the parent's login sites, tapped in an app, opens the login. Nothing else does.
- Chrome is never seen otherwise, and it never opens any other link.
- Wi-Fi portals keep Vibb's own window.

## 1. What happens today, and why (confirmed)

**Confirmed on the user's emulator (2026-11-19).** Turning off only the Wi-Fi sign-in window changed nothing. Turning
off Chrome as the login browser fixed it: `omaps.app` links went to Organic Maps again. The cause is the role making
Chrome reachable, not the portal window.

**The mechanism** is Android's own web-link rule, not a Custom Tab naming Chrome:

- **Android 12+ rule.** "A generic web intent resolves to an activity in your app only if your app is approved for the
  specific domain ... If your app isn't approved for the domain, the web intent resolves to the user's default browser
  app instead" ([Android 12 behaviour changes, "Web intent resolution"][a12]). Design 110 §6.2 read the same rule from
  AOSP: approved apps only; with none, the default browser (or all browsers); only if that is empty, every candidate.
- **Chrome hidden (role off).** The phone has no browser, so a link falls through to "every candidate". Organic Maps
  takes `omaps.app`, and Vibb's preferred activity takes the map hosts.
- **Chrome unhidden (role on).** Chrome is a browser again, and Android's role controller makes it the default (it is
  the only qualifying one, `BrowserRoleBehavior.getFallbackHolderAsUser`). Every link no app is *approved* for now goes
  to Chrome:
  - `omaps.app`: Organic Maps declares it but isn't verified for it on the phone.
  - Google's long map links: Vibb couldn't select them (design 110 finding 1; the gate's G6 row saw exactly this).
- **What Element X does.** A link in the timeline goes through `onLinkClick(url, customTab = false)` to
  `openUrlInExternalApp` - a plain `Intent(ACTION_VIEW, uri)` with no package and no category
  ([`MessagesView.kt`][ex-view], [`MessagesNode.kt`][ex-node], [`SystemUtils.kt`][ex-sys]).
  - Its Custom Tab helper ([`ChromeCustomTab.kt`][ex-ct]) is called with `session = null`. With no session,
    `CustomTabsIntent` sets no package ([`CustomTabsIntent.Builder.setSession`][ax-cti] is the only place that does).
  - So both reach "approved app, else the default browser".

**Vibb's own Meldinger** adds a second path: `MessageLinks.handler` counts the login browser among the allowed targets
(`messageLinkTarget`), so a plain link in a message opens Chrome while the role is on.

## 2. How apps open logins and links (sources, 2026-11-19)

| Library / app | How it opens the page | What it picks |
|---|---|---|
| Element X, ordinary links | plain `VIEW`, no package ([`SystemUtils.kt`][ex-sys]) | Android: approved app, else the default browser |
| Element X, OIDC login (MAS) | `openUrlInChromeCustomTab(null, …)` ([`LoginFlowNode.kt`][ex-login]) | the same: no session, no package |
| androidx `CustomTabsClient.getPackageName` | asks `resolveActivity` for the default `VIEW http` handler; returns it if it has a `CustomTabsService`, else the first package that has one ([`CustomTabsClient.java`][ax-ctc]) | the default browser if it supports Custom Tabs, else **Chrome by name** |
| `CustomTabsIntent` with a session | `setPackage(session's package)` ([`CustomTabsIntent.java`][ax-cti]) | **the bound provider by name** |
| AppAuth (`BrowserSelector`) | every "full browser" (`VIEW`, `BROWSABLE`, http **and** https, no host) with the default first; the first one with a Custom Tabs service wins, else the first plain browser; then `setPackage` ([`BrowserSelector.java`][appauth]) | **a browser by name**; some apps restrict the list to Chrome/Firefox/Samsung by signature |
| MSAL (Microsoft) | the first browser on its safe list; "If there's no browser packages on the device, MSAL falls back to use the in-app WebView" ([Microsoft Learn][msal]) | a safe-listed browser by name, else its own WebView |
| Google Sign-In, Credential Manager | Play services' own sheet | no browser at all |
| A Trusted Web Activity app | `CustomTabsClient.getPackageName` with a list of TWA browsers | Chrome by name, else its fallback (a Custom Tab or a WebView) |

So a login reaches a browser in two ways: by Android's resolution (no package), or **by name** once a library has
looked the browsers up. Any design that leaves Chrome visible lets the second way reach it - for logins and for
ordinary links alike.

## 3. The options

| | Mechanism | Stops plain links | Stops Chrome by name | Logins | Verdict |
|---|---|---|---|---|---|
| A | Today: Chrome visible, `URLBlocklist *` | no (Chrome is the default browser) | no | work | the bug |
| B | Vibb One the default browser, Chrome stays visible | yes | **no**: AppAuth, MSAL, `getPackageName` (unless Vibb offers Custom Tabs), TWAs and "Open in Chrome browser" still reach it | work | fails "never a single link" |
| C | **Vibb One the phone's browser, Chrome hidden except during a login window that Vibb opens** | yes | yes: a hidden package is no candidate and can't be started | work through Vibb; a library that wants Chrome by name meets no Chrome (§4.7) | **recommended** |
| D | Chrome suspended instead of hidden | no: a suspended Chrome stays a candidate and shows "Chrome is paused" | the dialog shows | - | worse than C |
| E | Vibb's own login WebView | - | - | Google refuses OAuth in a WebView (`disallowed_useragent`, design 102 §4.2) | rejected |
| F | Chrome policies alone | no Chrome policy refuses other apps' intents | - | - | rejected |
| G | Per-package visibility | a device owner can't change another app's visibility | - | - | rejected |

C is design 102's own "Chrome hidden" state (the role off) with one way in, owned by Vibb.

## 4. The design (option C)

### 4.1 Vibb One is the phone's browser: the link gate

- **A new activity `logins/LinkGateActivity`** with one filter: `VIEW`, `DEFAULT`, `BROWSABLE`, schemes `http` and
  `https`, no host. That makes it a "full browser" for Android's role controller and AppAuth alike
  (`handleAllWebDataURI`).
  - Translucent, no window of its own unless it shows the note, `excludeFromRecents`, `taskAffinity=""`, standard
    launch mode. Started without `NEW_TASK` it lands in the calling app's task, so the login tab does too (design 102
    M12).
- **Installed disabled, enabled by the apply** (Part A's `PortalRuntime.setEntryEnabled` pattern, `DONT_KILL_APP`)
  while the phone is managed and no free browser is allowed. A free browser is an allowed app that is a full browser:
  Chrome ticked under Apps, or Firefox. With one, the gate is off and that browser is the browser, as today.
- **The gate is on whether the login role is on or off** (D2). One behaviour on every managed phone:
  - with the role off, a login link gets the kid's note instead of each app's own error;
  - map links reach Vibb with or without design 110's step 2.
- **The role.** With Chrome hidden, Vibb One is the only full browser. `BrowserRoleBehavior`'s fallback then picks it:
  the only qualifying package ([source][role]).
  - Vibb checks `RoleManager.isRoleHeld(ROLE_BROWSER)` on every apply and reports it.
  - M1 measures that the fallback happens by itself, with `DISALLOW_CONFIG_DEFAULT_APPS` set. If it doesn't, the
    browser role becomes a design 114 setup step, `RoleManager.createRequestRoleIntent(ROLE_BROWSER)` in a pause, the
    way design 98 asks for the assistant role.
  - **No login window opens unless Vibb holds the role** (fail closed). An unhidden Chrome next to a phone with no
    default browser could take links. The kid gets the note, and the report says `failed`/`role`.
- **A persistent preferred activity** for the same filter is set on every apply and in `HomePin`'s re-seat (the
  seventh). It picks the gate among several candidates if the role is ever empty. It can't beat an approved app
  (design 110 §6.2), and it doesn't need to.

### 4.2 Chrome hidden outside a login window

- `computeEnforcementPlan`'s `loginBrowser` keeps Chrome **pinned** in the kiosk while the role is ready, as today. A
  hidden app can't start, so the pin costs nothing.
- **Chrome is unhidden only while a login window is open**: the plan gets `loginWindowOpen` from the pure
  `loginWindowOpen(record, nowElapsed, bootCount)`.
- `BrowserConfig` is unchanged:
  - the lockdown bundle is written and read back while Chrome is hidden;
  - D13's one clear stays;
  - every tightening still hides Chrome.

  "Ready" now means "a window may open", no longer "unhidden".
- `AppEnforcer.enforceOnNewPackage` (the unhide's `PACKAGE_ADDED`) and the apply read the same window record, so
  neither hides Chrome under an open window, and neither leaves it unhidden after one.

### 4.3 The gate's decision (pure `linkGatePlan`, `login_rules.json`'s new `gate` rows)

In order, for the tapped URL:

1. **The keyguard, the PIN lock or a time rule in force** → nothing (the gate finishes; as `MapLinkActivity`).
2. **A pause or the parent code's override** → the link as a plain `VIEW` to the first free browser (in a pause, Chrome
   is unhidden like every app). The parent is holding the phone (D9).
3. **A map link, with the map switch on** → `MapLinkActivity` with the link (design 110's own rules from there).
4. **A login link** → a login window (§4.4). That is the role on and ready, Vibb holding the browser role, not from
   Meldinger, and the URL matching one of `login_browser.hosts`. The match is Chrome's own `URLAllowlist` reading: a
   host includes its subdomains, a leading dot means that host only, an optional path is a prefix, and the port must
   match if given (pure `loginLinkMatches`).
5. **An allowed app that declares the link** → that app, by package: non-browser candidates of the same intent
   (`MATCH_ALL`), allowed, not suspended, never Vibb itself, never Play services. This is Meldinger's
   `messageLinkTarget` rule, shared. Example: `omaps.app` → Organic Maps.
6. **Otherwise** → the kid's note (§4.5). With ASK 4 yes, the host also goes into the report's `refused`.

**The gate never re-sends the intent it received.** It builds a fresh one each time: the URL, the target's package
and `NEW_TASK` only where §4.4 says. That keeps the caller's extras and any session binder out of Chrome, and it meets
Android 15's safer-intent matching and Android 16's intent-redirection hardening.

### 4.4 The login window

- **Open** (main thread, under the enforcer's lock):
  1. write the record (`opened_elapsed`, the boot count, `host`);
  2. unhide Chrome;
  3. start Chrome's Custom Tab: `VIEW` of the URL, `setPackage("com.android.chrome")`, the Custom Tabs session extra
     present and null (built by hand - no `androidx.browser` dependency), Element X's two extras that hide download
     and bookmark, no `NEW_TASK`;
  4. finish.

  The tab runs in the calling app's task. The redirect back (a custom scheme, `intent://`, or an https App Link whose
  host is listed) reaches the app as design 102 M15 measured.
- **Close**, at the first of:
  - 10 minutes (Part A's own length);
  - the keyguard (screen off and locked);
  - Home coming to the front (the kid left the app);
  - a time rule starting;
  - a tightening, the role off, a pause's end.

  Close means: delete the record, then hide Chrome. Hiding stops its process, its tab and any full Chrome task.
- **The timer** is a handler in the main process, which the anchor service keeps alive. Every apply and Home's
  `onResume` re-check the record by `elapsedRealtime`, so a process death can't leave Chrome unhidden past the next
  apply. A record from an earlier boot is void.
- **Each window is short and owned by Vibb.** A second login link while one is open just starts another tab
  (the record's end is not extended past the first 10 minutes).
- **The report** keeps design 102's `login_browser`, and adds `browser_role` (Vibb holds `ROLE_BROWSER`) and the new
  `failed` reason `role` (§6).

### 4.5 What the kid sees

| Case | The kid sees |
|---|---|
| A login link | the app's login in a Chrome tab over the app, then back in the app |
| A map link | Organic Maps on the place (design 110) |
| A link an allowed app declares | that app |
| Any other link, from any app | a small sheet over the app: "Denne lenken kan ikke åpnes her." / "This link can't be opened here." and OK (Meldinger's `message_link_cant_open`, the same words) |
| Chrome | never on Home, in the drawer, or from a link that isn't a login |

### 4.6 The pause and the override

- **A pause or the override** unhides Chrome with every app and lifts its lockdown (design 102 D9, unchanged).
- **The gate** sends other links to it then (§4.3 step 2).
- **The pause's end** is a tightening: Chrome hidden, its tabs end. The gate itself is not a restriction, so a pause
  leaves it on (the pause still reaches everything through step 2).

### 4.7 What stays (residue, named)

- **During an open window (at most 10 minutes) Chrome is visible:**
  - another app could start it by name;
  - the tab's "Open in Chrome browser" opens full Chrome.

  Both stay under `URLBlocklist *`, and both end with the window.
- **A library that wants a browser by name and finds none.**
  - AppAuth picks Vibb, a plain browser, and works through the gate.
  - MSAL finds no safe-listed browser and uses its own WebView, which is filtered by Vibb's DNS filter like any app.
  - An app that accepts **only** Chrome by signature can't log in outside a pause. ASK 2.
- **TWA apps** fall back to their Custom Tab or WebView. A Custom Tab goes through the gate (step 4 with their host
  listed, as design 102 already says).
- **A login link inside a message** never opens a window (Meldinger is not an app's login). Its host gets the note.

## 5. Step 1: Meldinger (small, first)

- `MessageLinks.handler` drops `loginRolePackage(...)` from the allowed set: the allowlist and Vibb One only.
- The set moves into a pure `messageLinkAllowed(allowlist, ownPackage)`, with `MessageLinksTest` rows showing Chrome
  is never in it, whatever the role.
- This builds and ships on its own, before the gate. With the gate in (step 2), Android names the gate for a plain
  link, and Meldinger runs the same `linkGatePlan` with `fromMessages = true` (never step 4).

## 6. Map links (design 110)

- **With the gate**, a map link from any app reaches Vibb:
  - an approved Vibb (step 2 done) → `MapLinkActivity` directly;
  - otherwise the default browser, which is the gate → `MapLinkActivity` (§4.3 step 3).

  Chrome is never in the path, role on or off. `omaps.app` reaches Organic Maps by step 5, or directly if it is
  verified.
- **The only map link that misses Vibb** is one another app is approved for (Play services' `maps.app.goo.gl` until
  step 1 is done, Google Maps if allowed). That is today's rule, and the report's `reach` shows it.
- **`reach`** compares the resolved package with Vibb One's, so the gate counts.
- **`MapLinkActivity`'s pause-time "Åpne i nettleseren"** must name a browser other than Vibb (`browserOffered`
  already excludes our own package). A test row pins it.
- **Not in this design:** with the gate, design 110's step 2 matters only while a free browser is allowed. Whether
  to drop it is a later question.

## 7. Policy, report, contract

- **No new policy key.** The gate's switch is the launcher's own reading: managed, and no free browser among the
  allowed apps.
- **The report's `login_browser`** gains `browser_role` (bool) and the `reason` value `role`. With ASK 4 yes, it also
  gains `refused`: the last 10 `{at_ms, host}`, hosts only, dropped after 7 days.
- **Both sides in one commit** with `server/testdata/login_windows.json` (byte-identical in the launcher's test
  resources): `report`, `report_off` and `clean` rows; `logins::clean` and `intake_limits` keep the new keys;
  `LoginWindowsTest` encodes them exactly.
- **The launcher-only rows** go into `login_rules.json`:
  - `gate`: the §4.3 order;
  - `link_match`: entry and URL to a match or not;
  - `window`: open, each close and a stale boot;
  - `plans`: hidden unless a window is open.

### The PWA (Words in the UI)

- **The phone's state.** `failed`/`role` is one orange line: "Vibb One er ikke nettleser" / "Vibb One isn't the
  browser". How to fix it goes behind the (i), only if M1 shows a manual step.
- **The switch's sub-line goes.** Its explanation moves behind the (i): "Chrome åpnes bare for sidene under, når en app
  sender deg dit, og lukkes etter 10 minutter. Andre lenker åpnes aldri i Chrome." / "Chrome opens only for the sites
  below, when an app sends you there, and closes after 10 minutes. Other links never open in Chrome."
- **With ASK 4 yes:** a "Stoppet nylig" / "Recently stopped" list under the sites. One row per host, with "Legg til" /
  "Add". It shows nothing when empty.

## 8. Android 14-17

| What | API | Note |
|---|---|---|
| `RoleManager.isRoleHeld`, `createRequestRoleIntent` | 29 | no gate |
| `setApplicationHidden`, PPA, `setLockTaskPackages` | 21 | no gate |
| The role fallback | - | M1 on 14 and 16 |
| Safer intents | 15 | every start is explicit and matches the target's filter (Chrome's `VIEW https`, Organic Maps' host filter) |
| Background activity starts | 14+ | every start comes from the visible gate |
| Intent redirection | 16 | the gate builds fresh intents (§4.3) |
| Android 17 | - | by source; the gate on 17 when an image is at hand |

## 9. Measure first (step 0; probe app, the emulators `compat34` and `jelly36`)

| # | What | Expected |
|---|---|---|
| M1 | Chrome hidden, the gate enabled, `DISALLOW_CONFIG_DEFAULT_APPS` set | `isRoleHeld(BROWSER)` true without a tap, on 14 and 16. If not: the setup step (§4.1) |
| M2 | A plain `VIEW`, a session-less Custom Tab, AppAuth's `select`, `getPackageName(null)` | all reach the gate; `getPackageName` returns null |
| M3 | Unhide, then an immediate explicit Custom Tab | the tab in the caller's task under lock task; time from tap to tab |
| M4 | The custom-scheme redirect from the tab | back in the app |
| M5 | The window's close | Chrome hidden, the tab and full Chrome gone, the app intact |
| M6 | Chrome ticked (the gate disabled) | the role back on Chrome |
| M7 | Element X timeline link (`omaps.app`, a Google short link) and its MAS login with the host listed | Organic Maps and the map handler; the login tab, then Element X |

## 10. Steps

1. **Meldinger** (§5): launcher, S, no contract.
2. **Step 0's measurements** (§9). Revise this note if one fails.
3. **The gate and the window** (§4): launcher, M. `LinkGateActivity`, `linkGatePlan`, `loginLinkMatches`, the window
   record and its closes, the plan's `loginWindowOpen`, the seventh PPA, the role reading.
4. **The report, contract and PWA** (§7): both sides in one commit.
5. **The emulator gate** (§11), on 14 and 16.

## 11. Tests and the emulator gate

**Unit tests (lean):**

- `MessageLinksTest`: `messageLinkAllowed`.
- `LoginBrowserTest` over the `gate`, `link_match`, `window` and `plans` rows.
- `LoginWindowsTest` over the contract.
- The server's `tests::logins` for the new keys and their cleaning.
- `PlayInvariantsTest` for the seventh PPA in the re-seat.

**The emulator gate, on Android 14 and 16, with the role on and three sites listed:**

1. **Element X.** A timeline link to `omaps.app`, a `maps.app.goo.gl` link and `example.com`: Organic Maps, the map
   handler, the note. Chrome never in front (`dumpsys activity activities`).
2. **A real OIDC login in an app** with its host listed: Element X's MAS on a test homeserver, or an AppAuth sample
   against Google. The tab, the login, back in the app. Chrome hidden again after the close.
3. **The same app with the host not listed**: the note, nothing else.
4. **A Wi-Fi portal** (`scripts/fake-portal.py`): Vibb's window as before, Chrome hidden throughout.
5. **A map link from a probe app** with no package: the handler. And Meldinger's links: no Chrome.
6. **A pause**: a link opens in Chrome; the pause's end hides it.

## 12. Decisions for the user

**ASK 1 - the mechanism.**

- (a) Chrome hidden except while Vibb opens a login, and Vibb One the phone's browser.
- (b) Vibb One the browser, but Chrome stays visible: apps that ask for Chrome by name, and the tab's "Open in Chrome
  browser", still reach it.

**Recommended: (a).** It is the only one where Chrome can't open a link.

**ASK 2 - an app that logs in only through Chrome by name** (rare: a library restricted to Chrome's signature).

- (a) The parent's pause for that one login (the app keeps its login afterwards).
- (b) A per-app "Chrome while this app is open". This needs the foreground app, which needs usage access, and is
  larger.

**Recommended: (a).** Revisit if a real app needs it.

**ASK 3 - the window.** 10 minutes. It closes when the screen locks, when the kid goes Home, or when a rule starts.

- (a) As above.
- (b) Longer, for slow two-factor logins.

**Recommended: (a).**

**ASK 4 - links Vibb stopped.** Show the parent the last hosts the gate stopped (host only, the last 10, gone after 7
days), each with "Add", on the Sign-in page. Without it, the parent can't learn which site an app's login needs:
Chrome's "blocked" page, which names the site, no longer shows.

- (a) Yes.
- (b) No: the parent finds the site some other way.

**Recommended: (a).** These are hosts tapped in apps, not pages visited.

Settled here without an ASK:

- **D1**: Wi-Fi portals stay Part A.
- **D2**: the gate is on whenever the phone is managed and no browser is a free app, role on or off.
- **D3**: Meldinger never opens a login window.

[a12]: https://developer.android.com/about/versions/12/behavior-changes-all#web-intent-resolution
[ex-view]: https://github.com/element-hq/element-x-android/blob/develop/features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/MessagesView.kt
[ex-node]: https://github.com/element-hq/element-x-android/blob/develop/features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/MessagesNode.kt
[ex-sys]: https://github.com/element-hq/element-x-android/blob/develop/libraries/androidutils/src/main/kotlin/io/element/android/libraries/androidutils/system/SystemUtils.kt
[ex-ct]: https://github.com/element-hq/element-x-android/blob/develop/libraries/androidutils/src/main/kotlin/io/element/android/libraries/androidutils/browser/ChromeCustomTab.kt
[ex-login]: https://github.com/element-hq/element-x-android/blob/develop/features/login/impl/src/main/kotlin/io/element/android/features/login/impl/LoginFlowNode.kt
[ax-cti]: https://github.com/androidx/androidx/blob/androidx-main/browser/browser/src/main/java/androidx/browser/customtabs/CustomTabsIntent.java
[ax-ctc]: https://github.com/androidx/androidx/blob/androidx-main/browser/browser/src/main/java/androidx/browser/customtabs/CustomTabsClient.java
[appauth]: https://github.com/openid/AppAuth-Android/blob/master/library/java/net/openid/appauth/browser/BrowserSelector.java
[msal]: https://learn.microsoft.com/en-us/entra/msal/android/customize-browsers-and-webviews
[role]: https://github.com/GrapheneOS/platform_packages_modules_Permission/blob/17/PermissionController/role-controller/java/com/android/role/controller/behavior/BrowserRoleBehavior.java

# Design 119: Chrome only for a login, never for a link

Status: **built** on branch `build-119` (2026-11-19), from revision 2 (READY TO BUILD, branch `design-119`; revision 1
was c3b03002e). It changes design 102 Part B (`docs/design/102-captive-portals-and-logins.md` §4,
`docs/notes/launcher/logins.md`). Part A, the Wi-Fi sign-in window, stays as it is.

**Owed before release** (the build ran no emulator; each box is a separate step):

- [ ] M1 - the role by `dumpsys role` on 14 and 16: a fresh role-on phone, Chrome ticked then unticked, a pause in
  which Chrome was made the default; hide-then-enable and the nudge (built as one kept flip of `logins.RoleNudge`, not
  an enable-and-disable pair: the hash is compared when the broadcast arrives); the `vibb_browser` dialog with the
  restriction lifted.
- [ ] M2 - a plain `VIEW`, a session-less Custom Tab, AppAuth's `select`, `getPackageName(null)`, the per-package lookup
  finding an unverified Organic Maps.
- [ ] M3 - unhide, bind and session, the tab under lock task in the caller's task; tap-to-tab time; a `NEW_TASK` caller.
- [ ] M4 - each end of §4.4 (Element X's MAS, an AppAuth sample, ✕, a for-result caller and an Auth Tab sample's relay,
  "Open in Chrome browser", a minimised tab, leaving for Meldinger and back within the minute).
- [ ] M5 - a Play services passkey or account sheet over the tab does not end the window.
- [ ] M6 - Element X timeline links: `omaps.app`, a Google short link, `example.com`.
- [ ] M7 - a process kill of Vibb mid-window; a reboot with Chrome unhidden.
- [ ] §11's emulator gate, steps 1-7, on Android 14 and 16 (17 when an image is at hand).

**Revision 2** applies the design QA (`docs/review/119-chrome-only-for-logins-design-qa.md` on `qa-119-design`,
97de1dd37: 2 High, 5 Medium, 10 Low) and the user's answers (2026-11-19, §12). What changed:

- **H1: the app lookup goes per package.** It runs `setPackage(pkg)` for each allowed app. Android's web-link filter
  hides unverified apps from a lookup without a package once a browser exists.
- **H2 and the user's ask 3: the window ends when the login is done, at most 5 minutes.** The gate stays as an
  invisible anchor under the tab, Vibb holds the tab's own Custom Tabs session (`TAB_HIDDEN`/`TAB_SHOWN`), and a
  5-minute ceiling is the backstop.
- **M4 (the user):** leaving the tab, for example to fetch a code, keeps the tab for 1 minute of grace; then Chrome
  hides.
- **M1: the order is hide Chrome, then enable the gate.** A repair re-runs Android's role grant, with measurement rows.
- **M2:** callers that want a result, and Auth Tab, get their result relayed. Otherwise they are a case for the pause.
- **M3:** no main-thread wait on the enforcer's lock.
- **M5:** `DISALLOW_CONFIG_DEFAULT_APPS` is set while the gate is on.
  - A pause lifts it, as it lifts every restriction (the user).
  - The role's setup step lifts it while its dialog is open.
  - The repair takes the browser role back when the pause ends.
- **L1-L10:** all applied.
- **ASK 4 = yes:** the stopped-hosts list with "Legg til".

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
- Once the login is done, Chrome is gone again.
- Chrome is never seen otherwise. While it isn't ticked under Apps, it is always hidden and comes out only for a real
  login.
- Wi-Fi portals keep Vibb's own window.

## 1. What happens today, and why (confirmed)

**Confirmed on the user's emulator (2026-11-19).** Turning off only the Wi-Fi sign-in window changed nothing. Turning
off Chrome as the login browser fixed it: `omaps.app` links went to Organic Maps again.

**The mechanism** is Android's own rule for web links:

- **Android 12+ rule.** "A generic web intent resolves to an activity in your app only if your app is approved for the
  specific domain ... If your app isn't approved for the domain, the web intent resolves to the user's default browser
  app instead" ([Android 12 behaviour changes][a12]).
- **AOSP's code** (`CrossProfileIntentResolverEngine.filterCandidatesWithDomainPreferredActivitiesLPrBody`, design 110
  §6.2, rechecked by the QA on `android16-release`):
  - if no app is approved for the host, only the browsers are kept - the default one, or all of them under
    `MATCH_ALL`;
  - only with no browser at all does Android fall back to every candidate.
- **Chrome hidden (role off):** the phone has no browser, so every candidate is in play. Organic Maps takes
  `omaps.app`, and Vibb's preferred activity takes the map hosts.
- **Chrome unhidden (role on):** Chrome is a browser, and most likely still the browser role's holder from the first
  boot (`config_defaultBrowser`). Even without the role, a lone browser gets every unapproved link. So `omaps.app`
  (Organic Maps declares it but isn't verified) and Google's long map links (design 110 finding 1) go to Chrome.
- **Unhiding doesn't move the role.** Android's role service re-grants on a package change only when its package hash
  changes. The hash counts paths and enabled components, not the hidden state (`RoleServicePlatformHelperImpl.
  computePackageStateHash`). That matters for §4.1.
- **What Element X does.** A link in the timeline goes through `onLinkClick(url, customTab = false)` to
  `openUrlInExternalApp`, a plain `Intent(ACTION_VIEW, uri)` with no package ([`MessagesView.kt`][ex-view],
  [`MessagesNode.kt`][ex-node], [`SystemUtils.kt`][ex-sys]). Its Custom Tab helper ([`ChromeCustomTab.kt`][ex-ct]),
  which it also uses for the MAS login, is called with `session = null`. Without a session, `CustomTabsIntent` sets
  no package ([`setSession`][ax-cti] is the only place that does). Its redirect lands in `MainActivity`, which is
  `singleTask`.

**Vibb's own Meldinger** adds a second path. `MessageLinks.handler` counts the login browser among the allowed targets
(`messageLinkTarget`), so a plain link in a message opens Chrome while the role is on.

## 2. How apps open logins and links (sources, 2026-11-19)

| Library / app | How it opens the page | What it picks |
|---|---|---|
| Element X, ordinary links | plain `VIEW`, no package ([`SystemUtils.kt`][ex-sys]) | Android: approved app, else the default browser |
| Element X, OIDC login (MAS) | `openUrlInChromeCustomTab(null, …)` ([`LoginFlowNode.kt`][ex-login]) | the same: no session, no package |
| androidx `CustomTabsClient.getPackageName` | the default `VIEW http` handler if it has a `CustomTabsService`, else the first package that has one ([`CustomTabsClient.java`][ax-ctc]) | the default browser if it supports Custom Tabs, else **Chrome by name** |
| `CustomTabsIntent` with a session | `setPackage(session's package)` ([`CustomTabsIntent.java`][ax-cti]) | **the bound provider by name** |
| AppAuth (`BrowserSelector`) | every full browser (`VIEW`, `BROWSABLE`, http **and** https, no host), default first; the first with a Custom Tabs service, else the first plain one; `setPackage` ([`BrowserSelector.java`][appauth]) | **a browser by name**; some apps restrict the list to Chrome/Firefox/Samsung by signature |
| Auth Tab (Chrome 137+) | `startActivityForResult`; the redirect comes back as the result, with no intent filter in the app ([guide][authtab]) | the browser by name |
| MSAL (Microsoft) | the first browser on its safe list; "If there's no browser packages on the device, MSAL falls back to use the in-app WebView" ([Microsoft Learn][msal]) | a safe-listed browser by name, else its own WebView |
| Google Sign-In, Credential Manager | Play services' own sheet | no browser at all |
| A Trusted Web Activity app | `CustomTabsClient.getPackageName` with a list of TWA browsers | Chrome by name, else its fallback (a Custom Tab or a WebView) |

So a login reaches a browser in two ways: by Android's resolution (no package), or **by name** once a library has
looked the browsers up. Any design that leaves Chrome visible lets the second way reach it, for logins and ordinary
links alike.

## 3. The options

| | Mechanism | Stops plain links | Stops Chrome by name | Logins | Verdict |
|---|---|---|---|---|---|
| A | Today: Chrome visible, `URLBlocklist *` | no | no | work | the bug |
| B | Vibb One the default browser, Chrome stays visible | yes | **no**: AppAuth, MSAL, `getPackageName`, TWAs and "Open in Chrome browser" still reach it | work | fails "never a single link" |
| C | **Vibb One the phone's browser, Chrome hidden except during a login Vibb opens** | yes | yes: a hidden package is no candidate and can't be started | work through Vibb; a library that wants Chrome by name meets no Chrome (§4.8) | **chosen (the user, ASK 1)** |
| D | Chrome suspended instead of hidden | no: a suspended Chrome stays a candidate and shows "Chrome is paused" | the dialog shows | - | worse than C |
| E | Vibb's own login WebView, or a Vibb Custom Tabs provider backed by a WebView | - | - | Google refuses OAuth in a WebView (`disallowed_useragent`, design 102 §4.2) | rejected |
| F | Chrome policies alone | no Chrome policy refuses other apps' intents | - | - | rejected |
| G | Per-package visibility | a device owner can't change another app's visibility | - | - | rejected |

C is design 102's own "Chrome hidden" state (the role off) with one way in, owned by Vibb.

## 4. The design

### 4.1 Vibb One is the phone's browser: the link gate

- **A new activity `logins/LinkGateActivity`** with one filter: `VIEW`, `DEFAULT`, `BROWSABLE`, schemes `http` and
  `https`, no host. That makes it a full browser for Android's role controller and for AppAuth
  (`handleAllWebDataURI`). It does not declare `CATEGORY_APP_BROWSER` or a `CustomTabsService`.
  - It is translucent and shows nothing unless it shows the note. It has `excludeFromRecents`, `taskAffinity=""` and
    standard launch mode.
  - Started without `NEW_TASK`, it lands in the calling app's task, and so does the tab (design 102 M12).
  - Started with `NEW_TASK` (from a non-activity caller, L3), it is the root of its own task, and so is the tab. The
    redirect still reaches the app, and the window's end finishes the gate by an in-package broadcast
    (`finishAndRemoveTask`).
- **When the gate is on.** It is installed disabled and enabled by the apply (Part A's `PortalRuntime.setEntryEnabled`
  pattern, `DONT_KILL_APP`) while the phone is managed and no free browser is allowed. A free browser is an allowed app
  that is a full browser: Chrome ticked under Apps, or Firefox. With one, the gate is off and that browser is the
  browser, as today.
  - **D2:** the gate is on whether the login role is on or off - one behaviour on every managed phone.
- **The order in the apply (M1).** Chrome is hidden first, then the gate is enabled, in the same apply. Enabling the
  gate is a component change, so it changes the role service's package hash. The controller then drops a hidden Chrome
  (it no longer qualifies) and grants the fallback, the only qualifying browser: the gate
  (`BrowserRoleBehavior.getFallbackHolderAsUser`, [source][role]).
- **The repair (M1).** Each apply checks `RoleManager.isRoleHeld(ROLE_BROWSER)`. If the gate is on, Chrome is hidden and
  the role isn't held, the apply flips a no-op component: `logins/RoleNudge`, a disabled activity-alias, enabled and
  disabled again. That changes the hash, and the controller re-grants.
  - At most once an hour, so a phone where it can't work doesn't loop. A pause's end runs it at once, outside that
    limit.
  - The report's `browser_role` says what it found.
- **The on-phone path.** If the repair doesn't give Vibb the role, a new design 114 setup step, `vibb_browser`
  ("Nettleser" / "Browser app"), opens `RoleManager.createRequestRoleIntent(ROLE_BROWSER)` in a pause or the setup
  hold. `DISALLOW_CONFIG_DEFAULT_APPS` is lifted while its dialog is open, the way design 98's `assistantPickOpen` does
  (M5). This is the on-phone path the "no adb beyond the QR" rule asks for, whatever M1 shows.
- **Fail closed.** No login window opens unless Vibb holds the role. The kid gets the note, and the report says
  `failed`/`role`.
- **Another visible full browser** (L2), such as a suspended system browser or one outside `controllable`, keeps the
  role from the gate. The fallback then picks the only system browser or `config_defaultBrowser`. The repair can't help
  that. The report's `browser_role: false` and the PWA's (i) name the cause.
- **`DISALLOW_CONFIG_DEFAULT_APPS` (M5)** is set whenever the gate is on, not only while calls or SMS are managed
  (`lockDefaultAppsFor` gains the gate). Without it, Chrome's own "make Chrome your default" prompt during a window, or
  Default apps in a pause, could take the role.
  - It stays set during a window.
  - **A pause lifts the gate's reason for it**, as it lifts every restriction (the user, 2026-11-19: the standing
    rule holds). The call and SMS rules keep their own reason, as today, so on a phone with managed calls or SMS the
    lock still holds.
  - It is also lifted for the `vibb_browser` step's dialog and design 98's pick window.
  - If the role moves - to Chrome in a pause, or anyway - the repair takes it back once Chrome is hidden again: at the
    pause's end, or the next apply.
- **The persistent preferred activity (L4)** for the same filter is set on every apply and in `HomePin`'s re-seat. It
  goes **after** the Play link blocker and the map handler's PPAs: Android keeps insertion order among equal
  priorities, and a catch-all added first could shadow them. `PlayInvariantsTest` pins the order. It earns its place
  only in a pause with no role holder; it can't beat an approved app (design 110 §6.2).
- **What holding the browser role brings (L1).**
  - `PROVIDE_OWN_AUTOFILL_SUGGESTIONS` (34+).
  - No location grant: that goes only to a system package.
  - Settings' Default apps shows Vibb One as the browser.
  - `makeMainSelectorActivity(MAIN, APP_BROWSER)` finds nothing outside a window.
  - `CustomTabsClient.getPackageName` returns null outside a window.
  - Vibb's approved map hosts are unchanged: the gate sits among the browsers, not among the approved apps.

### 4.2 Chrome hidden outside a login window

- `computeEnforcementPlan`'s `loginBrowser` keeps Chrome **pinned** in the kiosk while the role is ready, as today. A
  hidden app can't start, so the pin costs nothing.
- **Chrome is unhidden only while a login window is open.** The plan reads the window record (§4.4) through
  `LoginWindow.isOpen()`.
- `BrowserConfig` is unchanged:
  - the lockdown bundle is written and read back while Chrome is hidden;
  - D13's one clear stays;
  - every tightening still hides Chrome.

  "Ready" now means "a window may open", no longer "unhidden".
- **The window record has a small lock of its own (M3)**, not the enforcer's.
  - Opening a window holds it across "write the record, unhide Chrome".
  - `AppEnforcer.apply` (and `enforceOnNewPackage`, which the unhide's `PACKAGE_ADDED` reaches) re-checks the record
    under it right before its own Chrome hide step, and skips the hide while a window is open.
  - Both hold it for one or two `dpm` calls, never across the apply.

### 4.3 The gate's decision (pure `linkGatePlan`, `login_rules.json`'s `gate` rows)

The gate decides on a worker thread (M3), while its translucent window shows nothing. In order, for the tapped URL:

1. **The keyguard, the PIN lock or a time rule in force** → nothing; the gate finishes (as `MapLinkActivity` does).
2. **A pause or the parent code's override** → the link as a plain `VIEW` to the first free browser. In a pause Chrome
   is unhidden like every app, and the parent is holding the phone (D9).
3. **A map link, with the map switch on** → `MapLinkActivity` with the link (design 110's own rules from there).
4. **A login link** → a login window (§4.4). That needs:
   - the role on and ready, and Vibb holding the browser role;
   - the link not from Meldinger (D3);
   - the URL matching one of `login_browser.hosts`, read as Chrome reads `URLAllowlist`: a host includes its
     subdomains, a leading dot means that host only, an optional path is a prefix, and a given port must match
     (pure `loginLinkMatches`).
5. **An allowed app that declares the link** → that app, by package (H1). This is Meldinger's rule too, shared as pure
   `linkTargetOf(approved, declaring, allowed, locked)`:
   - First, Android's own answer with no package (`resolveActivity`), if it names an allowed app other than Vibb One.
     That is an app approved for the host.
   - Otherwise, the first allowed package, in the allowlist's order, for which
     `queryIntentActivities(view.setPackage(pkg), MATCH_DEFAULT_ONLY)` finds an activity. With a package set, Android's
     domain filter doesn't run, so an unverified Organic Maps is found.
   - Vibb One's own components, Play services and every full browser are never a target.
   - Example: `omaps.app` → Organic Maps.
6. **Otherwise** → the kid's note (§4.6), and the host goes into the report's `refused` (§7).

**The gate never re-sends the intent it received.** It builds a fresh one each time: the URL, the target's package,
and `NEW_TASK` only where §4.4 says. That keeps the caller's session binder and other extras out of Chrome, and it meets
Android 15's safer-intent matching and Android 16's intent-redirection hardening. Three kinds of extras are copied:

- `Browser.EXTRA_HEADERS`, CORS-safelisted headers only (Element X's `Accept-Language`), and the Custom Tab colour
  scheme int (L8);
- for a caller that asked for a result, the Auth Tab keys (§4.5).

### 4.4 The login window

**Opening.** On the gate's worker:

1. Take the window lock, write the record (`opened_elapsed`, the boot count, `host`), unhide Chrome, release the lock.
2. Bind Chrome's `CustomTabsService` (`CustomTabsClient.bindCustomTabsService`) and, within 2 s,
   `newSession(callback)`. If the bind or the session fails within 2 s, the tab opens without a session (H2) and the
   window's `session` is `false`.
3. Back on the main thread, the gate starts the tab:
   - `setPackage("com.android.chrome")`, the URL, the session when there is one, and the extras that hide download and
     bookmark;
   - no `NEW_TASK`;
   - `startActivityForResult` when the gate itself was started for a result (§4.5).

**The gate does not finish.** It stays as an invisible anchor under the tab.

**The window ends at the first of** (each with its `end` value, `login_rules.json`'s `window` rows):

| End | Seen as | `end` |
|---|---|---|
| The login finished into the app | the anchor destroyed by the system: the redirect into a `singleTask` or `CLEAR_TOP` activity clears it (Element X's `MainActivity`; AppAuth's `RedirectUriReceiverActivity` → `AuthorizationManagementActivity`) | `done` |
| The tab closed (✕, Back) | the anchor resumed after the tab showed | `closed` |
| The result came back | the anchor's `onActivityResult` (for-result callers and Auth Tab, §4.5) | `result` |
| The tab left and not back within 1 minute (M4, the user) | Vibb's own session's `onNavigationEvent(TAB_HIDDEN)`, or `onMinimized`, starts a 60 s grace; `TAB_SHOWN` or `onUnminimized` cancels it | `left` |
| 5 minutes from the opening (the user) | a handler timer, and an inexact elapsed alarm as its backstop (L5) | `timeout` |
| The keyguard | the screen off and locked | `locked` |
| A time rule starts, a tightening, the role off, a pause ends | the apply | `rule` / `tightened` |
| Vibb's main process restarted, or a record from an earlier boot | the first apply after the start or the boot | `stale` |

- **The grace (M4).** It covers fetching a code from Meldinger or an authenticator. It also covers what the anchor
  can't see:
  - a redirect into a `standard` activity on top of the tab;
  - an https App Link hand-off;
  - Home;
  - "Open in Chrome browser" (the tab is reparented, so `TAB_HIDDEN`).

  In each case Chrome stays visible at most 1 more minute.
- **Closing.** Delete the record under the lock, then hide Chrome. Hiding stops its process, its tab and any full
  Chrome task. Then finish the anchor if it is still there (`finishAndRemoveTask` in its own task, L3), and unbind the
  session.
- **A process death is fail closed (L5).** The session and the anchor die with the process. The restart's apply
  (`Application.onCreate`) finds the record and ends the window (`stale`), even mid-login.
  - A record from an earlier boot is void, and void means hide. Chrome's hidden state survives a reboot, so the first
    apply after the boot hides it. Chrome isn't direct-boot aware, so it is harmless before the unlock.
- **A second login link** while a window is open starts a new tab under a new anchor. The 5-minute ceiling stays the
  first window's.
- **The constants:** `LOGIN_WINDOW_MAX_MS = 300_000` and `LOGIN_TAB_GRACE_MS = 60_000`, the launcher's alone. They are
  apart from Part A's 10 minutes (`logins.rs` `MINUTES`).
- **`androidx.browser`** (Apache-2.0) comes in for the session: GPL-compatible, with its checksums in
  `gradle/verification-metadata.xml` (root `CLAUDE.md`, trust on first use, compared with Google Maven's `.sha256`).

### 4.5 Callers that want a result, and Auth Tab (M2)

- **A caller that started the "browser" with `startActivityForResult`** (`callingActivity != null`) would otherwise get
  `RESULT_CANCELED` at once. The anchor starts the tab for a result and relays Chrome's (`setResult(code, data)`)
  before it finishes, ending the window `result`.
- **Auth Tab.** The fresh intent copies only Auth Tab's own keys, as `androidx.browser`'s `AuthTabIntent` defines them:
  the launch flag, the redirect scheme, or the https redirect host and path. Chrome then opens an Auth Tab, and its
  result, which carries the redirect, is relayed.
- **If M4 shows the relay failing** for a kind of caller, those apps are ASK 2's case: the parent's pause.

### 4.6 What the kid sees

| Case | The kid sees |
|---|---|
| A login link | the app's login in a Chrome tab over the app, then the app again; Chrome gone |
| Leaving the tab for a code | back within a minute: the tab is still there |
| A map link | Organic Maps on the place (design 110) |
| A link an allowed app declares | that app |
| Any other link, from any app | a small sheet over the app: "Denne lenken kan ikke åpnes her." / "This link can't be opened here." and OK (Meldinger's `message_link_cant_open`, the same words) |
| Chrome | never on Home, in the drawer, or from a link that isn't a login |

### 4.7 The pause and the override

- **A pause or the override** unhides Chrome with every app and lifts its lockdown (design 102 D9, unchanged).
- **The gate** sends other links to Chrome then (§4.3 step 2). The gate is not a restriction, so a pause leaves it on.
- **The pause's end** is a tightening: Chrome is hidden, its tabs end, `DISALLOW_CONFIG_DEFAULT_APPS` is set again,
  and the repair takes the browser role back if the pause moved it.

### 4.8 What stays (residue, named)

- **During a window**, another app could start Chrome by name, and the tab's "Open in Chrome browser" opens full Chrome.
  Both stay under `URLBlocklist *`, and both end with the login, or at most 1 minute after the tab is left.
- **A library that wants a browser by name**:
  - AppAuth picks Vibb, a plain browser, and works through the gate.
  - MSAL finds no safe-listed browser and uses its own WebView, filtered by Vibb's DNS filter like any app.
  - An app that accepts **only** Chrome, by signature, can't log in outside a pause (ASK 2, the user: the pause).
- **TWA apps** fall back to their Custom Tab or WebView. A Custom Tab goes through the gate (step 4, with their host
  listed).
- **A login link inside a message** never opens a window (D3). Its host gets the note.

## 5. Step 1: Meldinger (small, first)

- `MessageLinks.handler` drops `loginRolePackage(...)` from the allowed set: the allowlist and Vibb One only. The set
  moves into a pure `messageLinkAllowed(allowlist, ownPackage)`.
- Its lookup becomes §4.3 step 5's per-package rule (`linkTargetOf`), off the main thread, so it keeps finding an
  unverified app once the gate is a browser (H1).
- `MessageLinksTest` rows:
  - Chrome is never allowed, whatever the role;
  - an unverified app that declares the host is found while a browser exists.
- This builds and ships before the gate. With the gate in, Meldinger runs `linkGatePlan` with `fromMessages = true`,
  so step 4 never applies.

## 6. Map links (design 110)

- **A map link from any app reaches Vibb**, role on or off: an approved Vibb (step 2 done) directly; otherwise the
  default browser, which is the gate, hands it to `MapLinkActivity` (§4.3 step 3). Chrome is never in the path.
- **`omaps.app`** reaches Organic Maps by step 5, or directly when it is verified.
- **The only map link that misses Vibb** is one another app is approved for: Play services' `maps.app.goo.gl` until
  step 1 is done, or Google Maps if allowed. That is today's rule, and the report's `reach` shows it. `reach` compares
  the resolved package with Vibb One's, so the gate counts.
- **`MapLinkActivity`'s pause-time "Åpne i nettleseren"** names a browser other than Vibb (`browserOffered` excludes our
  own package). A test row pins it.
- **Not in this design:** with the gate, design 110's step 2 matters only while a free browser is allowed. Whether to
  drop it is a later question.

## 7. Policy, report, contract

**No new policy key.** The gate's switch is the launcher's own reading: managed, and no free browser among the allowed
apps.

**The report's `login_browser` gains:**

- `browser_role`: a bool.
- The `reason` value `role`.
- `refused`: the hosts the gate's step 6 stopped, the last 10 as `{at_ms, host}`, older than 7 days dropped on the
  phone and in `logins::clean` (L9).
  - This is a host from **every** refused link, chat links included.
  - Hosts only, never a path or a query.
- `last_window`: `{opened_ms, closed_ms, end, session}`, with no host - the end and whether Vibb's session held, for
  the gate and for support.

**The setup step `vibb_browser` (§4.1)** goes into `server/testdata/setup_steps.json`: its `steps` row (`read_by:
phone`, `when: link_gate`, `phone_page: android.app.role.action.REQUEST_ROLE`, `features: ["login_browser"]`, names
"Nettleser" / "Browser app"), its `features` row (the logins page) and its `needed` rows.

**Both sides in one commit:**

- `server/testdata/login_windows.json` (byte-identical in the launcher's test resources): the `report`, `report_off`
  and `clean` rows. `logins::clean` and `intake_limits` keep the new keys, and `LoginWindowsTest` encodes them
  exactly.
- `setup_steps.json`, with `setup_status::tests` and `SetupStepsTest`.

**The launcher-only rows** in `login_rules.json`:

- `gate`: the §4.3 order;
- `link_match`: an entry and a URL to a match or not;
- `target`: `linkTargetOf`, with the unverified-app row;
- `window`: each end of §4.4, the grace and its cancel, a stale record, a second link;
- `plans`: Chrome hidden unless a window is open;
- `order`: hide before enabling the gate; the repair's once an hour; a pause's end runs it at once.

### The PWA (Words in the UI)

- **The phone's state.** `failed`/`role` is one orange line: "Vibb One er ikke nettleser" / "Vibb One isn't the
  browser". Its (i): "Fullfør «Nettleser» i Oppsett på telefonen. Er en annen nettleser synlig, tar den plassen." /
  "Finish "Browser app" in Setup on the phone. A visible other browser takes its place." (L2).
- **The switch's sub-line goes.** Its (i) (L10): "Chrome åpnes bare for sidene under, når en app ber deg logge inn, og
  lukkes når du er ferdig." / "Chrome opens only for the sites below, when an app asks you to sign in, and closes when
  you're done."
- **"Stoppet nylig" / "Recently stopped"** (ASK 4, yes). One row per host under the sites, with "Legg til" / "Add",
  which adds it as an own site. It shows nothing when empty.
  - Its (i): "Legg til bare innloggingssider." / "Add only sign-in sites." (L9).
  - Not a warning: an entry added from it opens the window for every link to that host, which is what an own site
    does.
- **`logins.sites.note`** stays. Inside a window, Chrome still names a blocked site in its own page.

## 8. Android 14-17

| What | API / source | Note |
|---|---|---|
| `RoleManager.isRoleHeld`, `createRequestRoleIntent` | 29 | no gate |
| `setApplicationHidden`, PPA, `setLockTaskPackages`, component enable | 21 | no gate |
| The role's package hash ignores the hidden state (L6) | read on 16's source | M1 checks it on 14 too |
| The role fallback to the only qualifying browser | `BrowserRoleBehavior` | M1 on 14 and 16 |
| Custom Tabs callbacks (`TAB_HIDDEN`, `onMinimized`) | Chrome's version, not Android's (androidx 1.2+, 1.8+) | the same on 14-17 |
| Auth Tab | Chrome 137+ | §4.5 |
| WebView providers since Android 10: never Chrome ([chromium][wv]) | - | a hidden Chrome leaves WebView alone |
| Safer intents | 15 | every start is explicit and matches the target's filter |
| Background activity starts | 14+ | every start comes from the visible gate |
| Intent redirection | 16 | fresh intents (§4.3) |
| Android 17 | - | by source; the gate on 17 when an image is at hand (the lowest and the highest it touches) |

## 9. Measure first (step 0; a probe app, the emulators `compat34` and `jelly36`)

| # | What | Expected |
|---|---|---|
| M1 | The role, read with `dumpsys role`, on 14 and 16: a fresh role-on phone (Chrome the holder); Chrome ticked → unticked; after a pause in which Chrome was made the default (the restriction lifted in it), read at the pause's end. Each outside a pause with `DISALLOW_CONFIG_DEFAULT_APPS` set | hide-then-enable gives the gate the role; where it doesn't, the nudge does; after the pause, the repair takes the role back; the `vibb_browser` step's dialog opens with the restriction lifted |
| M2 | A plain `VIEW`, a session-less Custom Tab, AppAuth's `select`, `getPackageName(null)`, and the per-package lookup | all reach the gate; `getPackageName` returns null; the lookup finds an unverified Organic Maps |
| M3 | Unhide, bind and session, then the tab | under lock task in the caller's task; the time from tap to tab; a `NEW_TASK` caller (L3) |
| M4 | Each end of §4.4: Element X's MAS redirect, an AppAuth sample, ✕, a for-result caller and an Auth Tab sample (the relay), "Open in Chrome browser" (`TAB_HIDDEN`, then the hide kills full Chrome), a minimised tab, leaving for Meldinger and coming back within a minute | each ends as its row says; the grace keeps the tab |
| M5 | A Play services passkey or account sheet over the tab | does **not** end the window (the tab only pauses) |
| M6 | Element X timeline links (`omaps.app`, a Google short link, `example.com`) | Organic Maps, the map handler, the note |
| M7 | A process kill of Vibb mid-window; a reboot with Chrome unhidden | Chrome hidden by the first apply |

A failing row revises this note before the build goes on.

## 10. Steps

1. **Meldinger** (§5): launcher, S, no contract.
2. **Step 0's measurements** (§9).
3. **The gate and the window** (§4): launcher, M+.
   - `LinkGateActivity`, `linkGatePlan`, `loginLinkMatches` and `linkTargetOf`;
   - the anchor, the session and the ends, the window record and its lock;
   - the hide-then-enable order and the repair, the seventh PPA, last in the re-seat;
   - `lockDefaultAppsFor` with the gate;
   - `androidx.browser`.
4. **The report, the setup step, the contract and the PWA** (§7): both sides in one commit.
5. **The notes**: `docs/notes/launcher/logins.md`, `docs/notes/launcher/map-links.md`'s Meldinger line, and the
   server's `logins.md`.
6. **The emulator gate** (§11), on 14 and 16.

## 11. Tests and the emulator gate

**Unit tests (lean):**

- `MessageLinksTest`: `messageLinkAllowed`, `linkTargetOf`.
- `LoginBrowserTest` over the `gate`, `link_match`, `target`, `window`, `plans` and `order` rows.
- `LoginWindowsTest` and `SetupStepsTest` over the contracts.
- The server's `tests::logins` and `setup_status::tests` for the new keys, their cleaning and the 7-day drop.
- `PlayInvariantsTest` for the PPA's place in the re-seat.
- `KidScreensEscapeTest` guards `logins/LinkGateActivity` and `linkGatePlan`, as it guards `map/` and `MessageLinks`
  (L7).

**The emulator gate, on Android 14 and 16, with the role on and three sites listed:**

1. **Element X.** Timeline links to `omaps.app`, `maps.app.goo.gl` and `example.com`: Organic Maps, the map handler,
   the note. Chrome never in front (`dumpsys activity activities`). `example.com` in "Stoppet nylig".
2. **A real OIDC login in an app** with its host listed (Element X's MAS on a test homeserver, or an AppAuth sample
   against Google): the tab, the login, back in the app, Chrome hidden within a second (`end: done`).
3. **The same login, leaving the tab for Meldinger:** back within a minute, the tab is still there; away longer,
   Chrome hidden (`left`).
4. **The same app with the host not listed:** the note, nothing else.
5. **A Wi-Fi portal** (`scripts/fake-portal.py`): Vibb's window as before, Chrome hidden throughout.
6. **A map link from a probe app** with no package: the handler. Meldinger's links: no Chrome.
7. **A pause:** a link opens in Chrome; the pause's end hides it and the role stays Vibb's.

## 12. Decisions

**Answered by the user (2026-11-19, relayed by the coordinator):**

- **ASK 1 = (a).** Chrome not ticked means always hidden, out only for a real login. Wi-Fi portals stay Vibb's own
  window.
- **ASK 2 = the parent's pause.** An app that logs in only through Chrome by name, or whose result relay fails (§4.5),
  logs in during a pause.
- **ASK 3: at most 5 minutes, and it ends when the login is done** (§4.4).
- **ASK 4 = yes:** the stopped-hosts list with "Legg til" (§7).
- **M4:** leaving the tab, for example to fetch a code, keeps the tab for about a minute, then Chrome hides; the 5
  minutes still holds.

**Settled here:**

- **D1:** Wi-Fi portals stay Part A.
- **D2:** the gate is on whenever the phone is managed and no browser is a free app, role on or off.
- **D3:** Meldinger never opens a login window.
- **D4 (the user, 2026-11-19):** a pause lifts the gate's `DISALLOW_CONFIG_DEFAULT_APPS` too - no exception to the
  pause rule. When the pause ends, the role repair takes the browser role back.

Nothing is open for the user.

[a12]: https://developer.android.com/about/versions/12/behavior-changes-all#web-intent-resolution
[ex-view]: https://github.com/element-hq/element-x-android/blob/develop/features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/MessagesView.kt
[ex-node]: https://github.com/element-hq/element-x-android/blob/develop/features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/MessagesNode.kt
[ex-sys]: https://github.com/element-hq/element-x-android/blob/develop/libraries/androidutils/src/main/kotlin/io/element/android/libraries/androidutils/system/SystemUtils.kt
[ex-ct]: https://github.com/element-hq/element-x-android/blob/develop/libraries/androidutils/src/main/kotlin/io/element/android/libraries/androidutils/browser/ChromeCustomTab.kt
[ex-login]: https://github.com/element-hq/element-x-android/blob/develop/features/login/impl/src/main/kotlin/io/element/android/features/login/impl/LoginFlowNode.kt
[ax-cti]: https://github.com/androidx/androidx/blob/androidx-main/browser/browser/src/main/java/androidx/browser/customtabs/CustomTabsIntent.java
[ax-ctc]: https://github.com/androidx/androidx/blob/androidx-main/browser/browser/src/main/java/androidx/browser/customtabs/CustomTabsClient.java
[appauth]: https://github.com/openid/AppAuth-Android/blob/master/library/java/net/openid/appauth/browser/BrowserSelector.java
[authtab]: https://developer.chrome.com/docs/android/custom-tabs/guide-auth-tab
[msal]: https://learn.microsoft.com/en-us/entra/msal/android/customize-browsers-and-webviews
[role]: https://github.com/GrapheneOS/platform_packages_modules_Permission/blob/17/PermissionController/role-controller/java/com/android/role/controller/behavior/BrowserRoleBehavior.java
[wv]: https://chromium.googlesource.com/chromium/src/+/HEAD/android_webview/docs/build-instructions.md

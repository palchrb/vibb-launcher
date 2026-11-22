# Launcher notes: Wi-Fi sign-in pages and Chrome only for logins (designs 102 and 119)

The design is `docs/design/102-captive-portals-and-logins.md` (revision 5 on top), its QA `docs/review/qa-102-design.md`;
Part B's link gate and login window are design 119 (`docs/design/119-chrome-only-for-logins.md`, revision 2, its QA
`docs/review/119-chrome-only-for-logins-design-qa.md`). The server's side is `docs/notes/server/logins.md`.

## Part A: the Wi-Fi sign-in window (`portal/`)

- **The handler.** `portal/CaptivePortalEntry` takes Android's `android.net.conn.CAPTIVE_PORTAL` (filter + `DEFAULT`) in
  the main process. Its guard is the manifest's `android:permission="android.permission.NETWORK_STACK"`: the system's
  start passes, another app's is refused (measured, design §1b R2/R4); our own explicit starts (the Wi-Fi page, Home's
  line, the reopen) pass as the same uid. `getLaunchedFromUid()` is not used (-1 for the system's start). The component
  is **disabled in the manifest** and enabled only while Part A is on (`PortalRuntime.setEntryEnabled`, from
  `AppEnforcer.applyHeld`, `DONT_KILL_APP`); a phone that isn't device owner never has it enabled, so no chooser.
  `AppEnforcer.enforceCaptivePortalPin` adds the device owner's persistent preferred activity on every apply and in
  `HomePin`'s re-seat (`PlayInvariantsTest` pins the re-seat's body); off is the disabled entry, never a cleared PPA.
  The stock login app (`com.google.android.captiveportallogin`) is never pinned in the kiosk: it follows any link and
  goes around the DNS filter (M4-M6).
- **The order** (pure `portalApprove`, `login_rules.json`'s `approve` rows): the PIN lock (no way in while LOCKED), one
  window at a time (read from the session files, R3-L5 - an entry after a main-process restart brings the open window
  forward), the network (Wi-Fi, `CAPTIVE_PORTAL`, not `VALIDATED`; a reopen: today's record), Vibb's https check (a
  pass: "Nettet virker allerede", `reportCaptivePortalDismissed`, no code asked), then the approval: a running PIN pause
  (`pause`), the parent code (`code`), the requirement off (`none`), a grant (`grant`), else the keypad. No daily count
  since 2026-11-18 (the user): each opening is bounded by its own 10 minutes alone.
- **The parent code** is `ParentPin.check` with `ParentPinEntry.WIFI_SIGNIN` (one-shot: no window, `windowDuration`
  null), through `ParentPinDialog` - in the main process, never in `:portal`. The lockout is the shared one; every try is
  a PIN event (the server's `Entry::WifiSignin`, "Wi-Fi-innlogging"). No parent code set fails closed (the dialog opens
  with OK disabled and "Ingen foreldrekode er satt – spør en voksen"); `ForeignAccessOn` refuses; `Unavailable(untilSync)`
  on a Wi-Fi-only phone is named residue (the PWA's switch off or a grant over another network ends it). Cancel tells
  Android (`reportCaptivePortalDismissed`), so its notification stays. The entry finishes on `onStop`.
- **The grants** (`PortalGrants`, pure `grantsToday`/`takeGrant`/`grantLeft`, the `grants` rows; the main process's own
  prefs `portal_budget`, key `grants` - the file keeps its first name; the old daily count's `budget` key gives its grant
  fields once and goes): the policy's `grant` counter, each step not seen one code-free opening, uncapped since 2026-11-18; taken at the
  entry (a killed window has used it); an unused one ends with the day (the latest date seen, so a clock set back gives
  nothing); a fresh install takes the current counter as seen. Code, pause and `none` openings take nothing.
- **`:portal`** (`PortalWindowActivity`, `android:process=":portal"`, not exported): `Application.onCreate` returns
  right after `preparePortalProcess()` (`WebView.setDataDirectorySuffix("portal")`), so nothing of the main process's
  start-up runs there and no preference is read. The window reads the snapshot `noBackupFilesDir/portal/state.json`
  (written by the entry right before the start; a stale one opens nothing), binds the process to the network
  (`bindProcessToNetwork`), writes `sessions/<opened_ms>.json` at its start and end, and tells the main process by an
  explicit broadcast to the non-exported `PortalSessionReceiver`. A window opened in a pause writes the pause's end into
  its session file, so it holds the one-window slot until then, not 11 minutes (code QA M2). Vibb One tells the window to close
  (`ACTION_CLOSE_WINDOW`, this package only, a `RECEIVER_NOT_EXPORTED` receiver): on the PIN lock's LOCKED edge
  (`PinLockRuntime.dispatch`), with Part A off, and at a pause's end (`RestrictionsPause.clear`; only a window opened in
  the pause - the snapshot's `pauseEndMs` is the fallback). The close is always sent, whatever the window's age (code
  QA M2). Only the instance that owns the session ends the `:portal` process when it finishes (code QA L6); the window
  declares every configuration change, so a locale or font change doesn't end it.
- **Where the page may go** (pure `portalNavigation`, `portalFrame` rows, `frameDocument`, `schemeVerdict`; the
  `navigation` rows): http and https only, `tel:` to Vibb's own dialer, everything else refused in place. Sites are
  registrable domains (OkHttp's `topPrivateDomain()`, an IP literal is its own). Until the kid first acts (a tap or a
  POST) the page's own moves and redirects carry the first site along; then the portal sites are that first site plus
  every site a POST from a portal site reached; every other site is a leaf. Each main-frame chain is tracked from its
  start (R3-M2): a tapped GET's chain may not leave the tapped link's site but to a portal site (a portal site's own
  redirector leads nowhere); the page's own move and a POST from a portal site may go anywhere; from a leaf only back.
  Sub-frame documents (`Accept: text/html`, not the main frame) only of the top page's site or a portal site; a refused
  one is a page of ours and counts in `blocked_frames` (only these: a document the lists block counts in `blocked_hosts`
  alone, code QA L2). The filter's lists (`classifyDomain` over the main process's compiled file, the Spotify flag and the
  secure-DNS asset) on every request while the filter is on and no pause; they are read **before** the first page loads
  (code QA M1), and a missing or unreadable list refuses every document (fail closed). A page of ours at a refused URL
  is never the current site or a visited one (code QA L1). A
  reopen keeps to the recorded first site alone, by any means. Not seen by these hooks (design §7): a sub-resource's
  redirect target, WebSockets, WebRTC, an app-like site's in-page moves, the parent's own DNS upstream. Service workers
  are off (`ServiceWorkerController`: network loads blocked, a client answering 403).
- **The WebView's settings** (Q102-L4): JavaScript, DOM storage and cookies on; file and content access off; no JS
  interface; debugging only in a debug build; Safe Browsing; no new windows; downloads refused; no file chooser; every
  permission and geolocation refused; mixed content never; TLS errors final (a line, no way past); the selection menu
  trimmed to Copy and Select all (`PortalWebView`), no text classifier.
- **Closing** (pure `portalClose`, the `close` rows): `validated` (`reportCaptivePortalDismissed()`) or `passes` (Vibb's
  check, every 5 s on the bound network) for a sign-in; `closed_by_vibb`; a pause lifts the limits while it lasts;
  `timeout` after 10 minutes for every opening. A reopen never closes on a working
  network. After each main-frame page Android re-probes at most every 3 s through `reportNetworkConnectivity` (best
  effort, E7); `reportCaptivePortalDismissed()` only at the window's end (code QA L7).
- **Vibb's https check** (`portal/PortalCheck.kt`, pure `portalPasses`, the `passes` rows): OkHttp on the network's
  socket factory and DNS, system trust, no proxy, no redirects; `https://www.gstatic.com/generate_204` and
  `https://cp.cloudflare.com/generate_204` (an exact 204 with an empty body), and the phone's own server only when its
  address is a public `https://` URL (a plain request: no bearer, no pinner, not through `ServerTransport`). A pass needs
  two operators: Google's and Cloudflare's, or either one and the server.
- **The reopen** (§3.8, `RevisitRecord`, `revisitMatches`, the `revisit` rows): a sign-in that ended `validated` or
  `passes` keeps one record per network in `noBackupFilesDir/portal/revisit.json` - the first page's URL and only its
  site, the SSID read at the session's end, the `Network` handle with the boot count, the date - never logged, never
  reported beyond `first_host`. Records go at the day's end, at boot (`PortalRuntime.start`) and with Part A off. The
  Wi-Fi page's "Åpne påloggingssiden" shows on the same handle (SSIDs agreeing when both are known) or, after a
  reconnect, the same SSID.
- **The kid's ways in** (`wifiRowState`, the `wifi_rows` rows): Android's notification (in the Silent bundle); the
  Wi-Fi page's row above the list ("Tilkoblet – må logges på" on the connected network's row, "Logg på"); Home's calm line "Wi-Fi-en vil at du logger på"
  (`home_portal_line`, never a sound or a badge); both read `PortalRuntime.row()` off the main thread (code QA L9).
  `PortalRuntime.start` watches the Wi-Fi networks
  (`FLAG_INCLUDE_LOCATION_INFO` for the SSID) for both.
- **The report**: `captive_portal` (`CaptivePortalReport`: `handler` from `resolveActivity`, `parent_code_set`,
  the last 10 sessions), a full-report field.
- **Tests**: `PortalRulesTest` (`login_rules.json`: navigation, sites, schemes, approve, frames, passes, close, grants,
  open sessions, wifi rows, revisit), `LoginWindowsTest` (the shared `login_windows.json`), `ParentPinStoreTest` (the
  one-shot entry), `ServerTransportTest` (both configs, the tailnet fence, the DoH rule, the source scan).
- **Checks owed**: `docs/testing/emulator.md` §6ap (E1-E7, E10-E13) with `scripts/fake-portal.py`, and the Jelly Star's
  D-C1/D-C2.

## Part B: Chrome only for logins (`logins/`, `server/BrowserConfig.kt`)

- **The role** (pure `loginRoleOf`, the `roles` rows): no role on an unmanaged phone (no allowlist, code QA L8); the
  policy's `login_browser` (null while off, or with Chrome ticked under Apps - the server keeps the two exclusive, and a ticked Chrome ignores the role here too); a
  `package` other than `com.android.chrome` is `failed`/`package` and unhides nothing (D8). Each site goes through
  `loginEntry` (the shared `entries` rule): an invalid one is dropped, never written to Chrome.
- **The lockdown first** (Q102-M3): `BrowserConfig.apply` (design 67's `BrowserDoh`, renamed and widened, the one owner
  of the browsers' managed configs) runs in `AppEnforcer.applyHeld` **before** `computeEnforcementPlan` and the
  hide/unhide loop. For Chrome under the role it writes `browserConfigPlan`'s bundle - each key in its Bundle type
  (`URLBlocklist ["*","chrome://*"]` and `URLAllowlist` as JSON in a string, the CHOICEs as ints, the BOOLEANs as
  booleans; the `bundles` rows), design 67's `DnsOverHttpsMode` over it while the filter is on -, reads it back with
  `getApplicationRestrictions` and compares it typed (`sameBundle`), and only a match makes the role ready. A throw, a
  mismatch or Chrome absent: `failed` (`write`, `readback`) or `absent`, Chrome hidden and unpinned.
- **Captchas** (2026-11-20, `jelly-round-2`; the user's Authentik needed reCAPTCHA v3 through www.recaptcha.net):
  `URLAllowlist` is the parent's sites and then the fixed `LOGIN_CAPTCHA_PATHS` (`logins/LoginBrowser.kt`), never shown
  in the PWA and not part of `login_windows.json`: `www.google.com/recaptcha`, `www.recaptcha.net/recaptcha`,
  `www.gstatic.com/recaptcha`, `challenges.cloudflare.com`, `newassets.hcaptcha.com`, `assets.hcaptcha.com`,
  `arkoselabs.com`, `frcapi.com` - in Chrome's format, where a host includes its subdomains (a leading dot would mean
  that host only), so Google is its `/recaptcha` paths only. hCaptcha is its widget's frame hosts only (code QA L1);
  Arkose keeps the domain (each customer has its own `*-api.` host) and `URLBlocklist` gains `LOGIN_CAPTCHA_BLOCKS`
  (`.www.arkoselabs.com`, `.arkoselabs.com/`), which win by Chrome's longest-host-then-longest-path rule, so Arkose's own
  site stays closed (`LoginBrowserTest`'s model of the rule, the `captcha.chrome` rows). `BrowserConfig` stores and
  compares the allowlist really written, `chromeAllowlist` (code QA L2), so a captcha path dropped in an update is a
  tightening too. Why only these: `URLBlocklist` stops navigations and frames (a captcha's widget is a frame;
  the user's own `www.recaptcha.net/recaptcha` entry was what fixed his login), not scripts, styles, fonts, images or
  XHR, so a login page's CDN hosts (Microsoft's `aadcdn.msauth.net`, Google's `ssl.gstatic.com`/`fonts.gstatic.com`,
  Apple's `appleid.cdn-apple.com`) load without an entry, and no preset carries helper hosts. The DNS filter has no
  login-window list: it is the parent's category lists for every app (StevenBlack's four, checked 2026-11-20: none of
  these hosts). Not a login link for the gate, not in Meldinger's plan (a source scan). `login_rules.json`'s `captcha`
  rows (the set, the frames that pass, the Google search and other pages that don't) and the `bundles` rows pin it.
- **Enforcement** (`computeEnforcementPlan`'s `loginBrowser`, set only when ready, and design 119's `loginWindowOpen`;
  the `plans` rows and two `EnforcementPlanTest` rows): **hidden outside a login window** and unhidden only while one
  is open (§4.2) - or while Play's nightly update window is in force (code QA M1: `loginChromeHidden`'s `updateWindow`;
  a hidden package is "not installed" to Play, which would never update it; the screen is off then, no window opens,
  the gate keeps `ROLE_BROWSER`, and the screen-on hides Chrome at once in `PlayRuntime.suspendStoreAtScreenOn` before
  the re-check's apply), never suspended for the role (a window's unhide alone brings it out), pinned in the kiosk while ready
  (a Custom Tab runs in the calling app's task, M12), suspended by a time-rule lock like any app and never pinned then
  (D7), never in the accessibility or input-method lists, never among the lock's usable apps. "Ready" means "a window
  may open". The loop's Chrome step for the role (`LoginWindow.chromeStep`) re-reads the window under its own lock.
- **Never on Home or in the drawer, in any state**: `loginRoleListable` (GPL, `logins/`) passed into `AppFilter` (MIT
  `apps/`) with `loginRolePackageNow()` - a pause included. The kid reaches Chrome only through an allowed app (a
  Custom Tab, a link) or the Custom Tab's "Open in Chrome browser", both under the policy.
- **Each tightening ends Chrome's tabs** (Q102-M4, R2-L4; `chromeTightened`, the `tighten` rows): the lockdown back
  (the role's first turn-on, a pause's or the override's end) or any old site gone (a set difference) - the same apply
  hides Chrome after the new bundle reads back (hiding stops the package and its tasks) and the plan unhides it. The
  role off (or another package) after its lockdown was in force: Chrome is hidden **before** its bundle is loosened
  (code QA L3), then the plan hides it or, ticked, unhides it. The written sites are kept in the main process's
  `login_browser` prefs.
- **Chrome's data cleared once** (D13): on the role's turn-on with no record (every phone at its first version with the
  role) or after a phase where Chrome was a free app (a ticked Chrome forgets the record),
  `dpm.clearApplicationUserData` first and on its own: the role waits (Chrome hidden) until the clear's callback reports
  success, which is recorded then (`cleared_ms` in the report) and asks for a sync; a failure is tried again (code QA L5). A pause needs no clear: its restored
  tabs reload through `URLBlocklist` (E9).
- **A pause or the override** lifts the lockdown with everything else (D9): the bundle falls back to the privacy keys
  and secure DNS (below), Chrome stays unlisted but reachable from apps; its end is a tightening.
- **Chrome's privacy keys** (design 126 A3, 2026-11-22): `CHROME_PRIVACY` (`MetricsReportingEnabled false`,
  `UrlKeyedAnonymizedDataCollectionEnabled false`, `NetworkPredictionOptions 2`, `BrowserSignin 0`,
  `SearchSuggestEnabled false`, `SafeBrowsingProtectionLevel 1`) in the bundle of each of Chrome's four packages
  (`CHROME_PACKAGES`: stable, beta, dev, canary; the other listed browsers keep the DoH key alone) **whenever the phone
  is managed** (`browserConfigPlan`'s `managed` = `hardeningManaged`): the role on or off, Chrome ticked, the lockdown in
  force or lifted by a pause, the filter on or off. Unmanaged: gone with the rest of the bundle. `BrowserSignin` and
  `SearchSuggestEnabled` hold the lockdown's values too, so the merge is plain and the role's read-back compares the
  merged bundle. `BrowserSignin` and `MetricsReportingEnabled` take hold at Chrome's next cold start (the role's first
  tightening hides Chrome, which stops it; a ticked Chrome waits for Android to end it, or a reboot). Not used:
  `SyncDisabled` (not on Android), `BlockThirdPartyCookies` (a login's SSO or captcha frames can need them). The
  `bundles` rows carry `managed` (unmanaged rows, a ticked Chrome on all four packages); `BrowserDohTest` checks no
  other browser gets them. Owed: `chrome://policy` shows the six with status OK (design 126 §10 step 4).
- **The redirect back** to the app needs no entry (M15): custom schemes and `intent://` reach the app before the
  blocklist; an https App Link callback's host must be on the list.
- **The report**: `login_browser` (`BrowserConfig.report`: Chrome's package, `on`/`off`/`absent`/`failed` with the
  reason, its `versionName`, `written_ms`, `cleared_ms`; design 119: `browser_role` - Vibb One holds Android's browser
  role, `null` while the gate is off -, `failed`/`role` when the role is on and Vibb One isn't the browser, `refused`
  the last 10 stopped hosts of 7 days `{at_ms, host}`, every refused link's host, chat links too, never a path, and
  `last_window` `{opened_ms, closed_ms, end, session}` with no host), a full-report field.
- **Checks owed**: `docs/testing/emulator.md` §6aq (E8, E9) and the Jelly Star's D-C3 to D-C6 (`chrome://policy` with a
  test bundle without `chrome://*`, typed `data:`/`view-source:`/`chrome://flags`/`javascript:`, "Add to Home screen"
  and "Install app", a real Feide or Google login, each preset's hosts).

## Design 119: the link gate and the login window (`logins/`)

Chrome never opens a link; it comes out only for a login an app opens on one of the parent's sites, and goes again
when the login is done (the user, 2026-11-19).

- **The gate** `logins/LinkGateActivity`: one filter (`VIEW`, `DEFAULT`, `BROWSABLE`, http and https, no host), so a
  full browser to Android's role controller and to AppAuth; no `APP_BROWSER`, no `CustomTabsService`. Translucent,
  `excludeFromRecents`, `taskAffinity=""`, standard launch mode: in the caller's task, and so is the tab; a `NEW_TASK`
  caller's gate is its task's root and finishes with `finishAndRemoveTask` (L3). Installed disabled; the apply enables it
  while managed and no free browser is allowed (an allowed app that answers a host no app declares, `LinkGateRuntime.
  gateWanted`, D2) - role on or off. A pause leaves it on (it is not a restriction).
- **The order in the apply (M1)**: the package loop hides Chrome first, then `LinkGateRuntime.afterLoop` switches the
  gate (a component change: the role service's package hash changes, a hidden holder no longer qualifies, the gate is
  the fallback), then the repair: when the gate was already on, Chrome hidden and `ROLE_BROWSER` not Vibb One's, one
  flip of the no-op alias `logins.RoleNudge` (kept, not undone: the hash is compared when the broadcast arrives) - at
  most once an hour, at once after a pause's end (`roleRepairDue`, the `order` rows).
- **`DISALLOW_CONFIG_DEFAULT_APPS` (M5)**: `lockDefaultAppsFor` gains `gateLock` (the gate on and no pause or override -
  the pause lifts the gate's reason, D4; the call and SMS reasons stay). Lifted for design 98's pick window and for the
  setup step's dialog (`defaultAppsLockLifted`).
- **The PPA (L4)**: `enforceLinkGatePin`, the seventh, last in `HomePin`'s re-seat and after the map handler's in the
  apply (`PlayInvariantsTest`). It matters only in a pause with no role holder.
- **The decision** (`LinkGateRuntime.decide`, pure `linkGatePlan`, the `gate` rows), on a worker: the keyguard, the PIN
  lock, a time rule or Play's update window - nothing; a pause or the override - the link to the first unsuspended browser; a map link with
  the switch on - `MapLinkActivity`; a login link (`loginLinkMatches`, Chrome's `URLAllowlist` reading, the
  `link_match` rows; the URL split by hand as Chrome reads it, `webUrlOf` - code QA L4: `java.net.URI` refused a raw
  `|` or space, and a backslash ends the authority as in Chrome) - a window, only with the role ready and `ROLE_BROWSER` Vibb One's (else the note: fail closed,
  `failed`/`role`), never from Meldinger (D3); an allowed app that declares the link (`linkTargetOf` over
  `LinkLookup`'s per-package lookup, H1: `setPackage` skips Android's web-link filter, so an unverified Organic Maps is
  found; never Vibb One, Play services or a full browser; the `target` rows); else the note "Denne lenken kan ikke
  åpnes her." and, while the role is ready, the host into `refused` (code QA L8: with the role off nothing is kept or
  reported). Every start is a fresh intent; copied are only CORS-safelisted `Browser.EXTRA_HEADERS` (read with
  `getString`), the colour scheme (L8) and Auth Tab's own keys (§4.5) - each read in a `try` (code QA L3: an extra of a
  class Vibb doesn't have throws on the main thread; it is left out). The tab sets "Open in Chrome browser" off
  (`setOpenInBrowserButtonState`, code QA L5) where Chrome honours it.
- **The window** (`LoginWindow`, pure `loginWindowStep`/`windowOpenRecord`, the `window` and `window_open` rows): its own
  lock (M3) across "write the record, unhide Chrome" and "delete the record, hide Chrome"; the opening reads the gate's
  conditions again under it (code QA L2: `windowStillAllowed`); the end's hide is skipped in a pause or the override
  (code QA M2: every app is out then); Vibb's own Custom Tabs
  session (androidx.browser 1.9.0, bound within 2 s, else none and `session: false`, H2); the gate stays under the tab
  as its invisible anchor. Ends: the anchor destroyed by the system (`done`), resumed after the tab (`closed`), the
  relayed result (`result`, Activity Result API), `TAB_HIDDEN`/`onMinimized` past a minute's grace that
  `TAB_SHOWN`/`onUnminimized` cancels (`left`, M4), 5 minutes (`timeout`; a handler timer and an inexact elapsed alarm,
  `LoginWindowReceiver`), the screen off with a secure lock (`locked`), the apply (`rule`, `loginWindowRuleEnds`: a time rule, the
  role off or not ready, the gate off, Play's update window, a pause's or the override's start (code QA M2) or end; `tightened`: `BrowserConfig`'s tightening, which ends the window before it
  hides Chrome), a record of another boot or main process (`stale`, at the process start and every apply). A second
  link keeps the first window's ceiling. An anchor recreated by a configuration change it doesn't handle ends the window
  `closed` (code QA L7: it saves `anchor` and comes back only once its tab is gone). `LOGIN_WINDOW_MAX_MS` and `LOGIN_TAB_GRACE_MS` are the launcher's alone.
- **Meldinger** runs the same plan with `fromMessages` (never a window); its allowed set (`messageLinkAllowed`) is the
  allowlist and Vibb One, never the login browser.
- **The setup step `vibb_browser`** ("Nettleser", design 114's table, `when: link_gate`): `RoleManager.
  createRequestRoleIntent(ROLE_BROWSER)` in a pause or the setup hold, the restriction lifted while its dialog is open
  (`BrowserRoleStep`, 5 minutes at most). The phone's reading and the server's are `browser_role`.
- **Residue (§4.8)**: during a window another app can start Chrome by name, and "Open in Chrome browser" (where Chrome
  ignores the off state) opens full Chrome - both under `URLBlocklist *`, both ending with the window. An app that
  accepts only Chrome by signature logs in during a pause (ASK 2), and so does one using Auth Tab with an https redirect
  (code QA L6: Chrome checks the redirect host's Digital Asset Links against the launching package, Vibb One). Chrome
  out in the nightly update window (M1) is reachable by no one: the screen is off and the gate holds the browser role.
- **Tests**: `LoginBrowserTest` (`gate`, `link_match`, `target`, `window`, `window_open`, `plans`, `order`, the
  hide-before-enable source order), `MessageLinksTest`, `LoginWindowsTest`, `SetupStepsTest`, `PlayInvariantsTest`,
  `KidScreensEscapeTest` (guards the gate and its plan), `EnforcementPlanTest`.
- **Checks owed**: design 119 §9's measurements M1-M7 and §11's emulator gate on 14 and 16 (the design note's status),
  and code QA M1's: Chrome's `version_name` moves after a night with the role on.


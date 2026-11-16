# Launcher notes: Wi-Fi sign-in pages and Chrome only for logins (design 102)

The design is `docs/design/102-captive-portals-and-logins.md` (revision 5 on top), its QA `docs/review/qa-102-design.md`.
The server's side is `docs/notes/server/status.md`, "Wi-Fi sign-in and logins (design 102)".

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
  (`pause`), the parent code (`code`, D17: whatever the day's budget), the requirement off (`none`, within the budget), a
  grant (`grant`, within it), else the keypad.
- **The parent code** is `ParentPin.check` with `ParentPinEntry.WIFI_SIGNIN` (one-shot: no window, `windowDuration`
  null), through `ParentPinDialog` - in the main process, never in `:portal`. The lockout is the shared one; every try is
  a PIN event (the server's `Entry::WifiSignin`, "Wi-Fi-innlogging"). No parent code set fails closed (the dialog opens
  with OK disabled and "Ingen foreldrekode er satt – spør en voksen"); `ForeignAccessOn` refuses; `Unavailable(untilSync)`
  on a Wi-Fi-only phone is named residue (the PWA's switch off or a grant over another network ends it). Cancel tells
  Android (`reportCaptivePortalDismissed`), so its notification stays. The entry finishes on `onStop`.
- **The budget** (`PortalBudget`, pure `budgetToday`/`chargeOpening`/`chargeTime`, main process's own prefs
  `portal_budget`): 4 code-free openings and 20 minutes a day, charged at the entry (a killed window still counts), its
  time when the session comes back (`PortalRuntime.fold`; a session file that never ended counts its whole minutes as
  `lost`); the latest date seen, so a clock set back gives nothing. The policy's `grant` counter: each step not seen
  adds one opening and 10 minutes, at most 3 a day; a fresh install takes the current counter as used. Code and pause
  openings use nothing.
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
  `timeout` after 10 minutes for every opening; `budget` for a code-free one. A reopen never closes on a working
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
  Wi-Fi page's row above the list ("Tilkoblet – må logges på" on the connected network's row, "Logg på", or "Spør en
  voksen om Wi-Fi-innlogging" with the code off and the budget spent); Home's calm line "Wi-Fi-en vil at du logger på"
  (`home_portal_line`, never a sound or a badge); both read `PortalRuntime.row()` off the main thread (code QA L9).
  `PortalRuntime.start` watches the Wi-Fi networks
  (`FLAG_INCLUDE_LOCATION_INFO` for the SSID) for both.
- **The report**: `captive_portal` (`CaptivePortalReport`: `handler` from `resolveActivity`, `parent_code_set`, today's
  code-free use, the last 10 sessions), a full-report field; capability `captive_portal_v1`.
- **Tests**: `PortalRulesTest` (`login_rules.json`: navigation, sites, schemes, approve, frames, passes, close, budget,
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
- **Enforcement** (`computeEnforcementPlan`'s `loginBrowser`, set only when ready; the `plans` rows and two
  `EnforcementPlanTest` rows): unhidden and unsuspended though not allowed, pinned in the kiosk (a Custom Tab runs in
  the calling app's task, M12), suspended by a time-rule lock like any app and never pinned then (D7), never in the
  accessibility or input-method lists, never among the lock's usable apps.
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
- **A pause or the override** lifts the lockdown with everything else (D9): the bundle falls back to secure DNS only (or
  empty), Chrome stays unlisted but reachable from apps; its end is a tightening.
- **The redirect back** to the app needs no entry (M15): custom schemes and `intent://` reach the app before the
  blocklist; an https App Link callback's host must be on the list.
- **The report**: `login_browser` (`BrowserConfig.report`: Chrome's package, `on`/`off`/`absent`/`failed` with the
  reason, its `versionName`, `written_ms`, `cleared_ms`), a full-report field; capability `login_browser_v1`.
- **Checks owed**: `docs/testing/emulator.md` §6aq (E8, E9) and the Jelly Star's D-C3 to D-C6 (`chrome://policy` with a
  test bundle without `chrome://*`, typed `data:`/`view-source:`/`chrome://flags`/`javascript:`, "Add to Home screen"
  and "Install app", a real Feide or Google login, each preset's hosts).

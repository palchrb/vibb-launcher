# Design 102: Wi-Fi sign-in pages and in-app logins without free Chrome

Status: design, **revision 2**, 2026-11-15 (branch `design-102`). Not built. Revision 1 was b724662cd.

**Revision 2** takes every finding of the design QA, `docs/review/qa-102-design.md` (1 High, 8 Medium, 9 Low). §13
says how each was handled. What changed:

- **The sign-in window can no longer become a browser (Q102-H1).** Revision 1 followed any link under the filter's
  lists and could be reopened without limit. That left a network Android keeps calling captive while it passes traffic
  (a kid's own laptop hotspot, a "soft" portal, an in-flight free tier) as a browser without an address bar. Now:
  1. **Vibb's own "the network works" check.** Every 5 s, and before the window opens, the window makes https requests
     over the bound network to fixed hosts, with a valid chain. When one passes, the network carries traffic whatever
     Android says: the window closes (`passes`) and won't open again on that network.
  2. **Another site only by the portal's own redirect or form post.** A link tap or a script's jump to another
     registrable domain is refused. Taps within the same site still work.
  3. **The parent sees the sites visited** (registrable domains, up to 20 per sign-in). A sign-in that went wide becomes
     an Activity event.
  4. **A daily budget for the phone**: 4 openings and 20 minutes a day in all, besides the 10 minutes per opening. Then
     "Spør en voksen" ("Ask a grown-up"), and the parent can give one more opening at once from the PWA.
- **Chrome is unhidden only after its lockdown is written and read back** (Q102-H1's sibling M3).
  - The package must be exactly `com.android.chrome`.
  - The bundle's keys carry their types.
  - Chrome's process ends at every tightening (M4).
  - Chrome's data is cleared when the role follows a phase as a free app (new D13).
- **Fixes to the plumbing.**
  - Migration **0150** (0149 is design 100's, M1).
  - A `:portal` guard in `Application.onCreate`, with the window's state in a snapshot from the main process (M2).
  - The preferred activity is re-seated with HOME's (M5).
  - The entry checks `getLaunchedFromUid() == SYSTEM_UID` and the network's state (M6).
  - The window's filter says plainly what it doesn't cover: the parent's own DNS upstream, WebSockets, WebRTC (M7).
    Service workers are off.
  - Ticked presets are stored as a set, own sites apart (M8).
  - Plus the Lows: Chrome keys, WebView settings, English strings and the save rule, the Activity events' kinds and
    keys, Chrome never listed, contract details, the re-probe's rate limit.

Approved for the queue by the user on 2026-11-15 (PLAN, "Captive portals and in-app browser logins without free
Chrome"). The user's words:

- *"Er det noen måter å få opp browser vinduer for feks å logge på hotel wifi og den type ting, uten å kunne bruke
  chrome på ordentlig for vanlig browsing? Captive portals. Og samme for login via browser i div apper"*
- *"Ja legg i køen å komme meg en bra løsning for dette uten å slippe fri chrome"*

**The short version.**

1. **Wi-Fi sign-in (captive portals).** Today a hotel or café Wi-Fi can't be signed in to under the kiosk. Android
   posts "Sign in to Wi-Fi network", the kid can tap it, and lock task refuses the login app (§1 M3).
   - The design makes **Vibb One itself the handler** of Android's sign-in intent. The device owner sets a persistent
     preferred activity; that works, and the system hands Vibb the portal's `CaptivePortal` object, the network and the
     URL (§1 M7).
   - Vibb shows the portal in **its own sign-in window**: a WebView in its own process, bound to that Wi-Fi network,
     with no address bar, http(s) only, and the filter's lists.
   - It moves to another site only by the portal's own redirect or form post.
   - It **closes the moment the network works**: Android validates it, or Vibb's own https check passes. It also closes
     after 10 minutes per opening, and it can be used 4 times and 20 minutes a day.
   - Android's own login app is not used. Measured, it follows any link to any site and gets around the DNS filter
     (§1 M4-M6).
2. **In-app logins.** The kid's apps open a Custom Tab or the browser for a login ("Logg inn med Google/Feide/
   Microsoft").
   - **Chrome gets a new role: "only for logins"** (`login_browser`). It stays installed and resolvable for other
     apps' Custom Tabs and links, is pinned in the kiosk, and is never on Home or in the drawer.
   - It runs under the device owner's managed configuration: `URLBlocklist ["*"]` and an `URLAllowlist` of login
     sites the parent keeps in the PWA. Also off: incognito, search, sign-in to Chrome, downloads, the first-run
     screen, and the New Tab page's feed. That configuration is written and read back **before** Chrome is unhidden.
   - Measured on the emulator (§1 M9-M15): the policy holds in Custom Tabs and in full Chrome, and the kid's typed
     address, search and the New Tab page's tiles all end at "… is blocked". The redirect back to the app works.
   - A Vibb login window was rejected (§4.2).

Both parts are liftable by the parent: the Wi-Fi window has a switch and a "one more" button, and the login role is
off until the parent turns it on. The PIN pause lifts both, as it lifts everything. No adb: both are set from the PWA.

## 1. Measured (`jelly36`, Android 16 Google Play image `BE2A.250530.026.D1`, 2026-11-15)

The probe was a small device-owner app of its own (scratchpad `d102/probe`, never in the repo), on a fresh `jelly36`
(`-wipe-data`). Its roles:

- It played the kid's app in front and Vibb's Home, in lock task with its own package pinned.
- It also ran a VPN that copies Vibb's **filter** shape: one route to `192.0.2.2/32`, DNS `192.0.2.2`, nothing
  answering, its own package excluded. A second run used the **sink** shape (`0.0.0.0/0` with lockdown).

The fake portal was a Python server on the VM, `fakeportal*.py` in the scratchpad.
`captive_portal_http_url`/`https_url`/`fallback_url` pointed at its `/generate_204`, `captive_portal_use_https 0`,
mobile data off. The portal answers 302 to its login page until `POST /accept`, then 204. The page has outside
links: Wikipedia, Wikipedia with `target=_blank`, a same-host page, `tel:`, and "Log in with Facebook". In later runs
it redirects to a hostname (`10-0-2-2.nip.io`), so a lookup is needed.

On this image the login app is `com.google.android.captiveportallogin` (`/system/app/CaptivePortalLoginGoogle`, version
16), activity `com.android.captiveportallogin.CaptivePortalLoginActivity`, filter `android.net.conn.CAPTIVE_PORTAL` +
`DEFAULT`. Chrome is 133.0.6943.137.

| # | What | Result |
|---|---|---|
| M1 | Detection | NetworkMonitor marks the Wi-Fi `CAPTIVE_PORTAL` within a second of connecting. Android posts **"Sign in to Wi-Fi network · AndroidWifi"** (package `android`, channel `NETWORK_STATUS`, importance LOW, `AUTO_CANCEL`) in the **Silent** section, bundled with another system notification as a collapsed "Android System · 2": the kid must expand the bundle before the row can be tapped. Nothing opens by itself on connect. Its content intent is a broadcast to NetworkMonitor, and ConnectivityService then starts the sign-in activity |
| M2 | How the system starts it | `START u0 {act=android.net.conn.CAPTIVE_PORTAL flg=0x10400000 ...} from uid 1000`: **an implicit intent, no package**. With a second app declaring the same filter, Android showed its "Complete action using" chooser (CP probe / Captive Portal Login) |
| M3 | **Under lock task, Vibb-style (only Home pinned)** | The shade opens (features with NOTIFICATIONS) and the row can be tapped. The start is refused: `Attempted Lock Task Mode violation r=...CaptivePortalLoginActivity`, `result code=101`. **Nothing appears.** This is today's behaviour on a kiosk phone: no Wi-Fi sign-in at all |
| M4 | The stock login app, pinned in the lock-task list (with the app-block bit too) | Opens: "Sign in to AndroidWifi", the host under it (`10.0.2.2`, later `10-0-2-2.nip.io`), a "⋮" menu with **"Do not use this network"** and **"Use this network as is"**, no address bar. A WebView (the system WebView's user agent) |
| M5 | **Links inside the stock window** | The outside link loaded **Wikipedia in the window**, with its search box. A `target=_blank` link did the same (`en.wikipedia.org/wiki/Hotel`). Nothing goes to Chrome; the window is a browser without an address bar for whatever the page links to. BACK **closes** the window (it does not step back) |
| M6 | **The DNS filter vs. the stock window** | With the filter-shaped VPN up, the shell's uid could not resolve (`ping: unknown host www.wikipedia.org`), yet the stock window loaded the portal by hostname **and Wikipedia**: it binds to the Wi-Fi network and goes around the VPN. With the **sink shape and lockdown**, it still loaded the portal page. The stock window is never filtered |
| M7 | **The device owner's own handler** | The DO set `addPersistentPreferredActivity(IntentFilter("android.net.conn.CAPTIVE_PORTAL", DEFAULT), probe/.Portal)`. `resolveActivity` then named the probe. Tapping the notification under lock task started the probe's activity directly (`cmp=me.vibb.probe.cp/.Portal ... result code=0`), with no chooser and no violation (its package is pinned). Extras: `android.net.extra.CAPTIVE_PORTAL` (a `CaptivePortal`), `android.net.extra.NETWORK` (the Wi-Fi `Network`), `android.net.extra.CAPTIVE_PORTAL_URL` (`http://10-0-2-2.nip.io:8103/login`) and `CAPTIVE_PORTAL_USER_AGENT`. `bindProcessToNetwork(network)` = true, and the page loaded by hostname with its own VPN up (the probe excluded from it, as Vibb is from its own) |
| M8 | Finishing from the DO's window | `CaptivePortal.reportCaptivePortalDismissed()` while still captive made NetworkMonitor re-probe at once (still a portal). After Accept, the same object's second call re-probed and the network became **VALIDATED** in under a second. The object can be called more than once |
| M9 | The stock window's close | After Accept, NetworkMonitor validated in ~1 s, the window logged `Result DISMISSED` and **closed by itself**; the kid's app was back in front |
| M10 | Chrome's managed config schema (`RestrictionsManager.getManifestRestrictions`) | `URLBlocklist`, `URLAllowlist`, `HomepageLocation`, `ManagedBookmarks`: **STRING** (a JSON array/object in a string). `IncognitoModeAvailability`, `BrowserSignin`, `DownloadRestrictions`, `DnsOverHttpsMode`, `TosDialogBehavior` ("Configuring the ToS behavior during first-run for CCT"), `ForceYouTubeRestrict`, `SafeSitesFilterBehavior`: CHOICE. `DefaultSearchProviderEnabled`, `SearchSuggestEnabled`, `PasswordManagerEnabled`, `NTPContentSuggestionsEnabled`, `PrintingEnabled`, `ForceGoogleSafeSearch`, `EditBookmarksEnabled`, `AutofillCreditCardEnabled`, `AutofillAddressEnabled`, `TranslateEnabled`, `PrivacySandboxPromptEnabled`, `ShoppingListEnabled`: BOOLEAN. **Not in 133's schema**: `DeveloperToolsAvailability` (Android since 154) and `AllowFileSelectionDialogs` (156) |
| M11 | **A Custom Tab under lock task with the app-block bit, Chrome not pinned** | "App is not available · Chrome is not available right now." (`BlockedAppActivity`) |
| M12 | The same with Chrome pinned | The Custom Tab runs **in the calling app's task** (`t18`: the probe's `Main` under Chrome's `CustomTabActivity`). Without `TosDialogBehavior`, Chrome's first run came first ("Welcome to Chrome · Your browser is managed by your organization · Continue"). With **`TosDialogBehavior: 2`** after `pm clear com.android.chrome`, the tab opened straight away |
| M13 | The Custom Tab's UI | Close, Minimize, the read-only host (`example.com`), Share, "⋮". The menu: Forward, Bookmark, **Download this page**, Page info, Refresh, **Chrome history**, Find in page, **Add to Home screen**, Desktop site, Translate, **"Open in Chrome browser"**. No address bar in the tab itself |
| M14 | **Full Chrome under the policy** (`URLBlocklist ["*"]`, `URLAllowlist ["10-0-2-2.nip.io","example.com"]`, incognito off, search provider off) | "Open in Chrome browser" opened full Chrome (a new task, allowed because Chrome was pinned). Typing `wikipedia.org` gave **"wikipedia.org is blocked · Your organization doesn't allow you to view this site"**. Typing "funny cats" and Enter **did nothing** (no search provider). **"New Incognito tab" is greyed** (`enabled=false`). The New Tab page shows tiles (Facebook, YouTube, Amazon, Wikipedia, ESPN) and "Discover by Google". The YouTube tile gave "m.youtube.com is blocked". A login host not on the list, in a Custom Tab: **"accounts.google.com is blocked"** |
| M15 | **The redirect back to the app** | An allowed page's link to `/redir` (302 to `vibbprobe://cb?code=42`) **reached the app** (`CALLBACK vibbprobe://cb?code=42 task=18`), with `*` blocklisted and no scheme on the allowlist. An `intent://cb?code=7#Intent;scheme=vibbprobe;end` link did too |

Not measured (owed, §11): Vibb's real filter (only a VPN of the same shape); the sink on a phone with cellular; a
real portal; the Discover feed after `NTPContentSuggestionsEnabled: false`; Chrome's "Add to Home screen" with Vibb as
Home; an `android:permission`-guarded entry activity; a Capport (RFC 8908) network whose login app chooses a Custom
Tab; Android's sign-in path on any other build.

From the sources (research, 2026-11-15; Chromium `main`, AOSP `main`):

- **Chrome on Android honours** `URLBlocklist`/`URLAllowlist` (86+), `IncognitoModeAvailability`, `BrowserSignin`,
  `DownloadRestrictions` (131+), `DefaultSearchProviderEnabled`, `SearchSuggestEnabled`, `NTPContentSuggestionsEnabled`,
  `PasswordManagerEnabled`, `ManagedBookmarks` and `HomepageLocation` (the policy YAMLs' `supported_on`).
- **Chrome does not honour** `SyncDisabled`, `NewTabPageLocation` or `BrowserGuestModeEnabled` on Android.
- **The URL filter format** (support.google.com/chrome/a/answer/9942583):
  - Hosts include their subdomains; a leading dot means that host only.
  - The longest host wins, then the longest path. `*` is checked last. On a full tie, allow beats block.
  - `chrome-native://` (the New Tab page) is exempt from `*`, and `chrome://` too since 147.
  - Custom schemes take only `scheme:*`.
  - The limit is 1000 entries.
- **`PolicyBlocklistNavigationThrottle`** is added for every navigation (Custom Tabs too). Android's
  `InterceptNavigationDelegate` is added before it, which explains M15.
- **AOSP's login app**:
  - It uses a WebView. It switches to a Custom Tab only when the DeviceConfig flag `captive_portal_custom_tabs` is on,
    the network opts in through Capport (`x-android-use-custom-tabs`, module 361335020+, January 2026), Private DNS
    isn't strict, and the default browser supports `setNetwork`.
  - In the WebView it loads http(s) links in place (M5), and starts `tel:`/`sms:`.
  - It downloads through its own `DownloadService` and closes on `VALIDATED`.
  - "Use this network as is" is `useNetwork()`, a system API.
- **`ConnectivityService.startCaptivePortalAppInternal`** sets no package or component (M2). The `CaptivePortal`
  object's `reportCaptivePortalDismissed()` and `ignoreNetwork()` need no permission. `reevaluateNetwork()` needs
  `NETWORK_STACK` and is silently ignored without it.

## 2. What happens today (read from the code)

- **The login app is never hidden or suspended.** It has no launcher icon, so `controllablePackages`
  (`AppEnforcer.kt:88`) leaves it out. **It is not in the lock-task list**: neither in `computeEnforcementPlan`'s
  kiosk packages nor among `lockTaskHelpers` (`LockTaskHelpers.kt`).
  - So with the kiosk on, the notification is reachable but the start is a lock-task violation (M3).
  - With the app-block bit on (`block_activity_start`, default 1 since migration 0029), any start of it in another
    task would be `BlockedAppActivity`.
- **With the kiosk off** (a phone with an allowlist but no kiosk, or in a pause/override) the stock app opens.
  Unfiltered, as M5-M6 show.
- **The DNS filter on a captive Wi-Fi.** When the Wi-Fi is captive and cellular is up, Android keeps cellular as the
  default network (design 95 §~479), so apps don't see the portal at all. On Wi-Fi only:
  - The filter's upstream (DoT/DoH on protected sockets) can't pass the portal, so every lookup through the tunnel
    fails until the portal is passed.
  - `KidVpnService`'s watcher resets the upstream when the network gains `VALIDATED` (`networkChanged`,
    `dns-and-vpn.md`). Nothing changes there.
  - The sink's lockdown doesn't stop a window bound to the Wi-Fi: not the stock app (M6), and not Vibb's own (Vibb is
    the always-on VPN's package and excluded from its own VPN; M7 measured this with the probe).
- **Chrome** is an ordinary controllable app. Not on the allowlist, it is **hidden** (`setApplicationHidden`). A
  hidden package does not resolve, so other apps' Custom Tabs and links have no browser: an app's "Log in with Google"
  fails or falls back to its own WebView, which Google refuses.
  - On the allowlist, Chrome is a free browser behind the DNS filter. `BrowserDoh` only turns its secure DNS off.
  - `BrowserDoh` (`BrowserDoh.kt`) already writes Chrome's managed config: `{"DnsOverHttpsMode": "off"}` while the
    filter is on, an empty bundle while it is off. It is the one owner of that bundle today.
- **Vibb's own Wi-Fi page** (`WifiNetworksActivity`, Quick Controls' "Manage networks") connects with the device-owner
  Wi-Fi APIs and never looks at `CAPTIVE_PORTAL`. A captive network shows as connected and nothing works.

## 3. Part A: the Wi-Fi sign-in window

### 3.1 Vibb becomes the handler (launcher, `portal/`)

- **`CaptivePortalEntry`** (new): an exported, theme-less trampoline activity in the main process. Its filter is
  `android.net.conn.CAPTIVE_PORTAL` + `DEFAULT`.
  - **Its guard is `getLaunchedFromUid() == Process.SYSTEM_UID`.** `Activity.getLaunchedFromUid()` is API 34, and the
    minSdk is 34. ConnectivityService starts it from system_server (M2, M7). Any other launcher, a forged intent from
    an allowed app included, is refused and finished. `Binder.getCallingUid()` and `getReferrer()` are not used: the
    first is our own uid in an Activity, and the second is the caller's to write (Q102-M6).
  - `android:permission="android.permission.NETWORK_STACK"` is added on top **only if** E2 shows that the system's
    start still passes it. The uid check is the guard either way.
  - It reads the four extras of M7.
  - It **checks the network**: the `Network` exists, is Wi-Fi, has `CAPTIVE_PORTAL` and not `VALIDATED`. It also
    checks the network isn't marked passing (§3.2 "the network works") and the day's budget (§3.3). If any of these
    fails, it finishes, and Android's notification stays as it is.
  - It refuses while the PIN lock is LOCKED (the shade is off then anyway).
  - Otherwise it writes the window's state snapshot (§3.2) and starts the non-exported `PortalWindowActivity`. It
    charges the opening to the budget (§3.3) before the start, so a killed window still counts.
- **The binderless start.** Vibb's Wi-Fi page and Home line (§3.4) open the window from Vibb's main process, with an
  explicit intent to the non-exported window. They make the same network and budget checks, and pass no
  `CaptivePortal` object (the system API `startCaptivePortalApp` isn't ours).
  - The window loads Android's own probe URL (`http://connectivitycheck.gstatic.com/generate_204`), which the portal
    intercepts and redirects.
  - It re-probes through the public `ConnectivityManager.reportNetworkConnectivity(network, true)`. NetworkMonitor
    rate-limits app requests, so this is best effort (E4). Vibb's own check (§3.2) is what closes the window either
    way.
- **The persistent preferred activity.** `AppEnforcer.applyHeld` adds `addPersistentPreferredActivity(admin,
  CAPTIVE_PORTAL filter, CaptivePortalEntry)` in every pass while the part is on, next to the other preferred
  activities it adds.
  - `AppEnforcer.reseatPreferredActivities` gets it too, as HOME's, the Play link blocker's and the call-log pin's
    (Q102-M5). Without it, the time between a re-seat's clear and the next apply would show Android's chooser (M2).
    With the kiosk off, the kid could pick the stock app there and tick "Always".
  - **Off is the disabled component**, not a rewrite: `setComponentEnabledSetting(CaptivePortalEntry, DISABLED)`. The
    package manager skips a preferred activity whose component doesn't resolve, so Android's own handler is the only
    one. The stock app then stays refused by lock task, as today. The preferred activity itself stays until the next
    re-seat, harmlessly.
- **Nothing is pinned for it.** Vibb's package is always in the lock-task list, so the start passes the kiosk, with
  the app-block bit too (M7). The stock login app is never pinned.

### 3.2 The window (`PortalWindowActivity`, process `:portal`)

- **Its own process, guarded.** `bindProcessToNetwork` binds the whole process. Vibb's main process must keep tsnet,
  the SSE stream and the sync on the default network, so the window runs in `android:process=":portal"`.
  - `Application.onCreate` gets an `isPortalProcess` guard next to `isBootCoverProcess` (`Application.kt:184`) and
    returns before any of the main process's start-up (Q102-M2). Without it the lock runtime, the receivers, the
    alarms and design 98's `AssistantSetup.atProcessStart` would all run a second time, bound to the portal's network.
  - The process calls `WebView.setDataDirectorySuffix("portal")` before its first WebView. This keeps the directory
    apart from any other process of the app's that may use WebView later; the launcher has no WebView today
    (revision 1 said otherwise).
  - It ends with the window.
- **Its state comes from the main process**, never from preferences read in `:portal`. Multi-process
  SharedPreferences aren't coherent (Q102-M2). The entry (or the Wi-Fi page) writes a snapshot,
  `noBackupFilesDir/portal/state.json`, and passes its path. It holds:
  - the filter's on/off;
  - whether a pause or the override is open;
  - `minutes` and the time left in today's budget;
  - the check URLs;
  - the path of the filter's compiled list and the parent's allowed domains;
  - the flags `classifyDomain` takes (the Spotify exemption).

  `DnsFilterEngine` gets a pure constructor from these inputs, so `:portal` never calls `loadFromDisk`'s preference
  reads. The window's result goes back as a session file (`portal/sessions/<opened_ms>.json`) and an explicit
  broadcast to a non-exported receiver of the main process. The main process folds it into the report (§6.2) and
  the budget.
- **The look**: Vibb's kid page header (`KidHeader`).
  - Title "Logg på Wi-Fi" / "Sign in to Wi-Fi", the network's name when Android gives it, else "Wi-Fi".
  - Under it the current page's host, read-only (as M4).
  - A close button. BACK steps back in the page's history and closes on the first page.
  - No address bar, no menu, no "use as is".
  - The first load sends the user agent the system passed (`CAPTIVE_PORTAL_USER_AGENT`); after that, the WebView's own.
- **The network works → close (Q102-H1.1).** `portalPasses` runs at the window's start and every 5 s, on the bound
  network. It is an OkHttp client with the system trust store and no proxy, with `Network.socketFactory`.
  - It sends `GET https://www.gstatic.com/generate_204` (wants 204) and `GET https://cp.cloudflare.com/generate_204`
    (wants 204) in turn.
  - When the phone's own server address is a public `https://` URL, the server is checked too (any answer with a valid
    chain). A Tailscale-only server is skipped.
  - A portal can't answer TLS with a valid chain for those hosts. **One pass means the network carries traffic**, and
    the window closes at once with the outcome `passes` and a short "Nettet virker nå" / "The network works now".
  - The main process records the `Network` handle as passing. The entry and the binderless start refuse to open on it
    while it stays connected.
  - This one rule ends both the kid's own hotspot (an http 302 to a search page, all else passed) and the "soft"
    portal, at no cost to a real one.
- **Where the page may go** (pure `portalNavigation`, `PortalRulesTest`):
  - **Schemes**: http and https only, main frame and sub-frames.
    - `tel:` goes to `ACTION_DIAL` with Vibb's own dialer as the package. Calls stay screened by Vibb's call rules, and
      emergency numbers work as always.
    - Everything else is refused in place: `intent:`, `market:`, `sms:`, custom schemes, `file:`, `content:`,
      `data:` as a top-level target, `javascript:` typed.
  - **Another site only by the portal's own redirect or form post (Q102-H1.2, D3).** A "site" is the registrable
    domain (OkHttp's `HttpUrl.topPrivateDomain()`; an IP literal is its own site).
    - A main-frame GET to another site passes `shouldOverrideUrlLoading` only with `WebResourceRequest.isRedirect()`.
    - A link tap, a `target=_blank` and a script's `location = …` to another site are refused with "Denne lenken går
      ut av innloggingssiden" / "This link leaves the sign-in page".
    - POST navigations never reach `shouldOverrideUrlLoading`, so a form post may change site, as portals do (vendor
      auth, payment).
    - Within the current site, taps are free (terms, room-number pages).
    - The cost: a portal's "Log in with Facebook" link straight to `facebook.com` is refused. A redirect there still
      works.
  - **The filter's lists**, while the phone's filter is on.
    - `shouldInterceptRequest` and `shouldOverrideUrlLoading` classify each request's host with `classifyDomain` over
      the snapshot's lists and allowed domains.
    - A blocked main frame shows "Denne siden er sperret" / "This page is blocked", and a blocked sub-resource gets an
      empty 403.
    - The hosts go into the session's `blocked_hosts` (§6.2) and into `BlockedEventLog` as the tunnel's blocks do.
  - **What the filter does not cover there (Q102-M7)**, stated in §7 and in the page's sub-line:
    - The parent's own upstream (design 36: their own AdGuard Home or family resolver) never sees the window's lookups.
      The window resolves through the portal network's DNS, so only Vibb's lists apply.
    - `shouldInterceptRequest` never sees WebSockets or WebRTC data channels. Both need a page that loaded in the
      window, and the window closes as soon as the network passes traffic.
    - Service workers are off: `ServiceWorkerController`'s settings `setBlockNetworkLoads(true)`, and a
      `ServiceWorkerClient` that answers 403.
  - **The WebView's settings, explicit (Q102-L4)**:
    - `javaScriptEnabled`, `domStorageEnabled` and cookies on (portals need them).
    - `allowFileAccess`/`allowContentAccess` false.
    - No `addJavascriptInterface`, and `setWebContentsDebuggingEnabled(false)` in release.
    - `safeBrowsingEnabled` true.
    - `setSupportMultipleWindows(false)`, so `target=_blank` is a navigation, judged as above.
    - A `DownloadListener` that refuses.
    - `onShowFileChooser` false.
    - Geolocation, camera, microphone, MIDI and protected media are refused in `onPermissionRequest`.
    - JS dialogs are allowed.
    - Mixed content is never allowed.
    - TLS errors show a page of ours with no way past.
    - The text-selection action mode is trimmed to Copy and Select all: "Web search", the `PROCESS_TEXT` items and
      smart selection's Open/Call would start other apps.
    - Long-press on a link has no context menu in a plain WebView. It stays so.
- **Closing**, from a `NetworkCallback` on the portal's network:
  - On `VALIDATED`: `reportCaptivePortalDismissed()` (with the binder), the outcome `validated`, finish (as AOSP, M8,
    M9).
  - After each main-frame page: a re-probe at most once per 3 s. With the binder that is `reportCaptivePortalDismissed()`
    (M8: re-probes, reusable); without it, `reportNetworkConnectivity`.
  - Vibb's check passes: `passes` (above).
  - The network is lost: `lost`.
  - The kid closes: `closed`, plus `reportCaptivePortalDismissed()` so Android re-probes and keeps its notification.
  - After `minutes` (10) from the opening, or when today's budget runs out first: `timeout` or `budget`. The window
    says "Innloggingen tok for lang tid. Prøv igjen, eller spør en voksen." / "Signing in took too long. Try again, or
    ask a grown-up.", or for the budget "Du har brukt dagens Wi-Fi-innlogginger. Spør en voksen." / "You've used
    today's Wi-Fi sign-ins. Ask a grown-up.", with one button "Lukk" / "Close".
- **A pause or the override** lifts the window's limits: the filter, the time limits, the budget and the cross-site
  rule. It does not lift the https close: a network that works needs no window. It is still Vibb's window, with no
  address bar either way.
- **The PIN lock**: the window finishes when the lock engages, and can't be started while LOCKED.

### 3.3 The day's budget (Q102-H1.4, D2)

- **Per phone per local day**: at most **4 openings** and **20 minutes** of window time in all. Each opening also ends
  after 10 minutes. It is per phone and not per network because the network's name is often unreadable (§6.2) and a
  per-network count is easy to reset.
- **The main process keeps the budget**: the date, openings and milliseconds, in `LauncherPreferences`.
  - An opening is charged at the entry, before the start.
  - Time is charged from the session file's open-to-close. If `:portal` died without a file, the whole `minutes` is
    charged.
  - A clock set back changes nothing: the date and time locks hold (`lockDateTime`).
- **The parent's "one more" (D14)**: the page's button "Gi én innlogging til nå" / "Allow one more sign-in now" bumps
  the policy's `captive_portal.grant`. When the phone sees a higher `grant` than it has used, it adds one opening and
  10 minutes to today's budget, at once and once. That is the one-shot rule: no window, no repeat.
- The PIN pause lifts the budget while it lasts.
- The budget running out raises one Activity event (§3.5).

### 3.4 Under a time rule (D4)

The window is Vibb's own and is not suspended by a lock. It opens during a time rule too: a hotel Wi-Fi at bedtime is
the case. The kid gets connectivity, not apps, since the rule still holds every app. The budget and the
cross-site rule apply as always. During the PIN lock (LOCKED) the shade is off, so there is no way in.

### 3.5 The kid's ways in

1. **Android's notification** (M1). It works once Vibb is the handler, but sits folded in the Silent bundle.
2. **Vibb's Wi-Fi page** (`WifiNetworksActivity`): the connected row of a network with `CAPTIVE_PORTAL` and without
   `VALIDATED` reads "Tilkoblet – må logges på" / "Connected – sign-in needed", with a button "Logg på" / "Sign in".
   Pure `wifiRowState`, `PortalRulesTest`.
3. **Home's status line (D5)**: while the phone's Wi-Fi is captive (`CAPTIVE_PORTAL`, not `VALIDATED`, not marked
   passing), Home shows one calm line, "Wi-Fi-en vil at du logger på" / "This Wi-Fi wants you to sign in", and a tap
   opens the window. It is the same kind of line as design 98's "Flymodus er på", and never a sound, a notification
   of ours or a badge. With the budget used up, the line says "Spør en voksen om Wi-Fi-innlogging" / "Ask a grown-up
   about Wi-Fi sign-in" and opens nothing.

### 3.6 The parent's side (Part A)

- **Default on (D1).** On a kiosk phone the kid reaches no sign-in at all today (M3), so this is a new surface. With
  §3.2's https close, the cross-site rule and the budget, it is narrower than the stock window a kiosk-off phone gets
  today (M5, M6).
  - With an older server, the launcher's default is on and the parent has no switch (§6.1). The PIN pause and a server
    update are the ways to change it then.
- **The switch.** The page `/devices/{id}/logins` (§5) has "Wi-Fi-innlogging (hotell, kafé, fly)" / "Wi-Fi sign-in
  (hotels, cafés, planes)". Its sub-line: "Vibb viser innloggingssiden til nettet, uten adressefelt, og lukker den når
  nettet virker. Nettfilterets lister gjelder, ikke din egen DNS-tjener." / "Vibb shows the network's sign-in page,
  with no address bar, and closes it once the network works. The web filter's lists apply, not your own DNS server."
- **The recent sign-ins (D6)**, from the report: the last 10 openings, with:
  - the time and how it started;
  - the network's name (when the phone could read it);
  - the outcome (`validated`, `passes`, `closed`, `timeout`, `budget`, `lost`);
  - **the sites visited** (registrable domains);
  - the hosts the filter blocked.

  Under the list: today's use ("2 av 4 innlogginger, 7 av 20 minutter i dag" / "2 of 4 sign-ins, 7 of 20 minutes
  today"), and the "one more" button.
- **Activity events** (`parent_events::raise`, design 101's unread count; Q102-L6). The dedup key is the session's
  `opened_ms`, because every report repeats the last 10 sessions and `ON CONFLICT DO NOTHING` on `(device_id, kind,
  dedup_key)` keeps one event each.
  - `wifi_signin_wide`: a session that visited more than 3 sites, had blocked hosts, or ended `timeout`. "Wi-Fi-
    innloggingen besøkte 5 nettsteder" / "The Wi-Fi sign-in visited 5 sites", with the list.
  - `wifi_signin_budget`: the day's budget ran out (key `budget:<local date>`). "Dagens Wi-Fi-innlogginger er brukt
    opp" / "Today's Wi-Fi sign-ins are used up", with the "one more" link.
  - Push: `Push::Now` outside the night, `Push::Held` until 07:00 inside it (no pings after 23:00). A `validated` or
    `passes` session raises nothing.

## 4. Part B: Chrome only for logins

### 4.1 The role (launcher, `logins/`)

The policy's `login_browser` (§6) names the role and its allowed sites.

- **The package (D8, Q102-M3).** The launcher accepts the role only when `login_browser.package` equals the constant
  `com.android.chrome`. Anything else is `failed` (reason `package`), and nothing is unhidden. A policy naming another
  browser can't free it.
- **The order: the lockdown first (Q102-M3).** `BrowserConfig` (today's `BrowserDoh`, renamed and widened) moves
  **before** the hide/unhide loop in `AppEnforcer.apply`. For the role's package it does three things:
  1. writes the bundle;
  2. reads it back with `getApplicationRestrictions` and compares it typed (`sameBundle` over the typed values);
  3. hands the plan `loginBrowserReady = true` only when they match.

  Only then does `computeEnforcementPlan` treat Chrome as the role's.
  - A throw, a mismatch or Chrome absent leaves Chrome **hidden and unpinned**, as an unallowed app. The report says
    `failed` with the reason (`write`, `readback`, `absent`).
  - Chrome is never unhidden on the hope that the bundle follows.
- **Enforcement** (`computeEnforcementPlan` gets `loginBrowser: String?`, set only when ready):
  - Chrome is treated as allowed for hiding and suspending: unhidden, so it resolves for Custom Tabs and links, and
    unsuspended, which would show "app paused".
  - It is pinned in the kiosk list (M11, M12).
  - A time-rule lock suspends it like any app (D7).
  - It is never in `permittedAccessibility`/`permittedInputMethods` and never among the lock's usable apps.
  - A ticked Chrome (the parent's allowlist) ignores the role. The server keeps the two exclusive (§5.2).
- **Never on Home or in the drawer, in every state (Q102-L7).** The rule is pure `loginRoleListable(pkg, rolePkg)` in
  the GPL `logins/` directory. `AppFilter` (MIT `apps/`) takes it as an input, as `playPackageLaunchable` is passed
  today.
  - A pause doesn't list it either. The parent who wants free Chrome ticks it.
  - The kid reaches Chrome only through an allowed app (a Custom Tab, a link) or the Custom Tab's "Open in Chrome
    browser" (M13). Both land under the policy.
  - Vibb has no `CONFIRM_PIN_SHORTCUT` handler, so the menu's "Add to Home screen" should add nothing (D-C4).
- **Each tightening ends Chrome's process (Q102-M4).** `URLBlocklist` is checked on navigations, so a page already
  loaded survives a tightening: a minimized Custom Tab, full Chrome in Recents, a single-page site's in-page moves.
  - The tightenings are: the role's first turn-on, a pause's or the override's end, a site removed, and the role off
    while Chrome isn't ticked.
  - At each one, the same apply writes and verifies the new bundle, then **hides and unhides Chrome**. Hiding stops
    the package, which ends its tasks and process.
  - A tightening is detected as "the written allowlist shrank, or the lockdown went from absent to present" (pure
    `chromeTightened(old, new)`).
- **Chrome's data is cleared (D13).** On the role's first turn-on after Chrome was a ticked, free app, the apply calls
  `dpm.clearApplicationUserData(admin, chrome, …)` before writing the bundle. Otherwise offline pages, the reading list,
  history and logged-in sites from the free phase remain. The phone records that it did, so it happens once per such
  change.
- **The bundle** (pure `browserConfigPlan(filterOn, role, windowOpen, installed)`; each key with its Bundle type,
  Q102-M3):

  | Key | Type | Value | Why |
  |---|---|---|---|
  | `URLBlocklist` | string | `["*","chrome://*"]` (JSON) | everything blocked; `chrome://` is exempt from `*` since 147 (Q102-L3) |
  | `URLAllowlist` | string | `[…the parent's sites…]` (JSON, M10) | these sites, subdomains included |
  | `IncognitoModeAvailability` | int | `1` | no incognito (M14) |
  | `BrowserSignin` | int | `0` | no sign-in to Chrome itself; web sign-in still works |
  | `DefaultSearchProviderEnabled` | bool | `false` | typed words go nowhere (M14) |
  | `SearchSuggestEnabled` | bool | `false` | no suggestions while typing |
  | `ContextualSearchEnabled` | bool | `false` | no Touch to Search panel (L3) |
  | `NTPContentSuggestionsEnabled` | bool | `false` | no Discover feed |
  | `PromotionalTabsEnabled` | bool | `false` | no promo tabs |
  | `AllowDinosaurEasterEgg` | bool | `false` | no offline game (L3) |
  | `TosDialogBehavior` | int | `2` | no first-run screen before the first login (M12) |
  | `DownloadRestrictions` | int | `3` | no downloads |
  | `PasswordManagerEnabled` | bool | `false` | no saved passwords to browse |
  | `AutofillCreditCardEnabled` | bool | `false` | no stored cards |
  | `PrintingEnabled` | bool | `false` | |
  | `ForceGoogleSafeSearch` | bool | `true` | if a Google host is allowed |
  | `ForceYouTubeRestrict` | int | `2` | the same |
  | `DeveloperToolsAvailability` | int | `2` | Chrome 154+ (M10), harmless before |
  | `DnsOverHttpsMode` | string | `"off"` | while the filter is on (design 67) |

  The keys `URLBlocklist` and `URLAllowlist` are strings holding JSON, per Chrome's schema (M10). The device check
  confirms Chrome reads them (D-C3).
- **A pause or the override** lifts the role's lockdown with everything else (D9). Chrome's bundle falls back to DoH
  only (empty with the filter off). Chrome stays unlisted (above) but reachable from apps. The pause's end is a
  tightening: verify, then hide and unhide.
- **The Chrome UI that stays** (M13, M14): the Custom Tab's "⋮" and full Chrome's address bar, New tab, History,
  Bookmarks and Recent tabs.
  - "Download this page" is refused by `DownloadRestrictions`.
  - Every navigation in them meets `URLBlocklist`.
  - Chrome's blocked page is Chrome's words ("Your organization doesn't allow you to view this site"), not Vibb's.
- **The redirect back** needs no entry (M15): custom schemes and `intent://` reach the app before the blocklist. An
  https App Link callback is a normal navigation, so its host must be on the list (§5.2 says so).
- **Trusted Web Activity apps (Q102-L2).** An allowed TWA app (a web app packaged with Bubblewrap) falls back to its
  WebView today, with Chrome hidden. With the role on, it opens in Chrome and gets "… is blocked" unless its host is
  listed. The same goes for any allowed app's in-app article links. The page's note says so (§5.2).

### 4.2 Why not a Vibb login window

- Apps choose the browser themselves. AppAuth and Google's and Microsoft's libraries query for Custom Tabs providers
  and `setPackage` one, so a login never passes through anything Vibb could catch.
- Google refuses OAuth in embedded WebViews (`disallowed_useragent`); Microsoft and Apple discourage them.
- A Vibb "browser" of its own for VIEW intents would catch only plain links.

## 5. The PWA: `/devices/{id}/logins` ("Innlogging" / "Sign-in")

A new page under the phone, linked from the Web Filter page (`/devices/{id}/filter`).

- **The row**: "Innlogging" / "Sign-in", sub-line "Wi-Fi: på · Apper: av" / "Wi-Fi: on · Apps: off".
- **Rendering**: server-rendered like its siblings, nb + en. There is no new client JS, so Safari 14 is fine
  (Q102-L5).
- **Saving, with no jump**:
  - The two switches use `autosave.js` (`data-autosave`), each with its own `section`.
  - Adding, removing and ticking presets are form posts with a `303` back to `#logins-sites`.
  - "One more" posts with a `303` back to `#logins-wifi`.

### 5.1 Wi-Fi sign-in (§3.6)

The switch, its sub-line, the recent sign-ins with the sites visited, today's use, and "Gi én innlogging til nå" /
"Allow one more sign-in now".

### 5.2 Logins in apps

- **The switch** "Innlogging i apper med Chrome" / "Sign-in in apps with Chrome", off by default (D10). Its sub-line:
  "Apper kan åpne innloggingssider i Chrome. Chrome vises ikke på telefonen og åpner bare sidene under." / "Apps can
  open sign-in pages in Chrome. Chrome isn't on the phone's screens and opens only the sites below."
  - **Refused while Chrome is ticked** under Apps: "Chrome er tillatt som vanlig app. Fjern haken under Apper for å
    bruke den bare til innlogging." / "Chrome is allowed as a normal app. Untick it under Apps to use it only for
    sign-in."
  - The Apps page's Chrome row says the reverse while the role is on: "Brukes bare til innlogging (Innlogging-siden)" /
    "Used only for sign-in (the Sign-in page)".
  - Shown only when the phone's report lists `com.android.chrome`. Otherwise: "Chrome finnes ikke på telefonen" /
    "Chrome isn't on the phone".
- **Presets (D11)**: ticks, stored as a **set** of preset names (`device_policy.login_browser_presets`, Q102-M8). The
  server expands `logins::PRESETS` when it builds the policy, so a corrected preset reaches every phone that ticked it
  with the next server release, with no migration.
  - **Feide** (Norwegian schools): `auth.dataporten.no`, `idp.feide.no`, `innsyn.feide.no`.
  - **Google**: `accounts.google.com`, `accounts.youtube.com`.
  - **Microsoft**: `login.microsoftonline.com`, `login.live.com`, `login.microsoft.com`, `account.live.com`.
  - **Apple**: `appleid.apple.com`, `idmsa.apple.com`.

  D-C6 settles each preset's exact hosts.
- **Own sites**: rows (the site, the parent's own note, remove) and an add field. They are kept apart from the
  presets: the table holds only own entries, and an own entry equal to a preset's host stays when the preset is
  unticked. The policy's `hosts` is the sorted union without duplicates.
- **The entry rules** (`logins::valid_entry`, the contract's `entries`):
  - A host (IDN to punycode, lower case) with an optional leading dot (that host only), an optional port and an
    optional path.
  - No scheme (http and https both), no `*`, no query, no custom scheme (M15: not needed).
  - At most 100 own entries per phone (Chrome takes 1000).
  - A pasted URL keeps its host and path: `https://school.example/login?x=1` becomes `school.example/login`.
- **The web filter's own lists still apply**: Chrome's DoH is off, so its lookups pass the tunnel. On save, each entry
  is checked against the phone's compiled lists, with the machinery of design 67's Allowed Domains notice. A blocked
  one shows "Sperret av nettfilteret – legg den til under Tillatte domener også" / "Blocked by the web filter – add it
  under Allowed domains too", with a link.
- **The note**: "Siden appen viser kan sende Chrome videre til andre sider. Står de ikke her, sier Chrome at siden er
  sperret – legg da til siden Chrome nevner. Apper som er nettsider (de åpner seg i Chrome) trenger sin egen side her."
  / "The page the app shows can send Chrome on to other sites. If they aren't listed, Chrome says the page is blocked –
  then add the site Chrome names. Apps that are web pages (they open in Chrome) need their own site here." That is the
  learning path for v1. D12 is the learning window.
- **The phone's state**:

  | Report | nb | en |
  |---|---|---|
  | `login_browser.state = on` | "Klar" | "Ready" |
  | no report since the save | "Venter på telefonen" | "Waiting for the phone" |
  | the capability missing | "Krever nyere Vibb One" | "Needs a newer Vibb One" |
  | `absent` | "Chrome mangler" | "Chrome is missing" |
  | `failed`, with the reason | "Kunne ikke settes" | "Couldn't be set" |

## 6. The policy, the report, the contract

### 6.1 The policy (top level)

```json
"captive_portal": {"enabled": true, "minutes": 10, "day_openings": 4, "day_minutes": 20, "grant": 0},
"login_browser": {"package": "com.android.chrome", "hosts": ["auth.dataporten.no", "school.example/login"]}
```

- Both keys are pinned by `policy_json_keys_snapshot` and `PolicyResponseCompatTest`.
- `login_browser` is `null` while the role is off. The server's DTO field is `Option<…>` and the launcher's is
  nullable, so the snapshot's null passes (Q102-L8).
- The limits are the server's constants (D2), sent so a later change needs no launcher release.
- **Older launcher**: ignores both keys. Chrome stays hidden and Wi-Fi sign-in stays impossible under the kiosk, as
  today. The page says "Krever nyere Vibb One" from the capabilities.
- **Older server**: neither key. The launcher reads `captive_portal` as absent and on, with the limits above and
  `grant: 0`. `login_browser` reads as off.

### 6.2 The report (full-report fields)

```json
"captive_portal": {"handler": "vibb", "today": {"openings": 1, "ms": 240000, "grant_used": 0},
  "sessions": [{"opened_ms": 0, "closed_ms": 0, "network": "Hotel Fjord", "first_host": "portal.example",
    "outcome": "validated", "visited": ["portal.example", "nomadix.com"], "blocked_hosts": [], "start": "system"}]},
"login_browser": {"package": "com.android.chrome", "state": "on", "version_name": "133.0.6943.137",
  "written_ms": 0, "reason": null, "cleared_ms": null}
```

- `handler`: who `resolveActivity(CAPTIVE_PORTAL)` names: `vibb`, `system` (the part is off) or `other` (shown to the
  parent).
- `sessions`: the last 10.
  - `network` is the SSID only when Android gives it, else `null`.
  - `start`: `system` (the notification) or `vibb` (the Wi-Fi page or Home).
  - `visited`: the registrable domains of the main frames, in order, at most 20.
  - `blocked_hosts`: at most 10.
  - Each name is at most 253 characters.
  - `outcome` is one of `validated`, `passes`, `closed`, `timeout`, `budget`, `lost`.
- `login_browser.state`: `on`, `off`, `absent`, `failed` (`reason`: `package`, `write`, `readback`, `absent`).
- Capabilities: `captive_portal_v1` and `login_browser_v1`.
- Both are **full-report fields**: they count in the heartbeat's hash and are not `status_heartbeat_fields`, so a new
  session forces a full report (design 95 §3.5).
- The server cleans both field by field (`logins::clean`) and keeps them in `device_status.captive_portal_json` /
  `login_browser_json`. `intake_limits` keeps their keys.

### 6.3 The contract: `server/testdata/login_windows.json`

The file is byte-identical in `launcher/app/src/test/resources/`. It holds:

- `capabilities`;
- `policy`: a new phone; Part A off; the role on with a preset, an own site equal to a preset host, and a grant;
- `older_server`: the launcher's reading of a policy with neither key;
- `report` and `report_off` (exact);
- `clean`: too many sessions or sites, a long host, an unknown outcome, a non-object;
- `entries`: the add field's input to an entry or a refusal.

Its tests:

- **Server**: `tests::logins` saves the page and produces each `policy` row, posts the reports and checks what is
  stored, and runs `clean` and `entries`.
- **Launcher**: `LoginWindowsTest` decodes `policy` and `older_server`, encodes `report` exactly, and runs `entries`
  through its own check. The launcher drops an invalid entry from a policy rather than writing it to Chrome.
- Both compare the copies.

The launcher-only rules are in `launcher/app/src/test/resources/login_rules.json`, run by `PortalRulesTest` and
`LoginBrowserTest`:

- `navigation`: scheme, site change, redirect or POST, the filter's verdict, a pause → allow or refuse;
- `close`: validated, passes, minutes, the budget → the outcome;
- `budget`: openings, ms, grants and date changes → open or refuse;
- `wifi_rows`;
- `bundles`: filter, role, window → the exact typed bundle;
- `plans`: allowlist, role ready or not, lock → hidden, suspended, pinned, listable;
- `tighten`: old and new bundle → the hide and unhide, and the clear.

Root `CLAUDE.md` gets the file's paragraph, and `scripts/fake-portal.py` goes into its scripts list. `launcher.yml` and
`server-ci.yml` get the file in their path lists, and `launcher.yml`'s changes job runs `fake-portal.py --selftest`
(Q102-L8).

### 6.4 Migration `0150_login_windows.sql`

Design 100 takes 0149 (Q102-M1). Recheck the number at the merge.

```sql
ALTER TABLE device_policy ADD COLUMN captive_portal_enabled INTEGER NOT NULL DEFAULT 1;
ALTER TABLE device_policy ADD COLUMN captive_portal_grant INTEGER NOT NULL DEFAULT 0;
ALTER TABLE device_policy ADD COLUMN login_browser_enabled INTEGER NOT NULL DEFAULT 0;
-- The ticked presets as a JSON array of names ('feide', 'google', 'microsoft', 'apple'); expanded at policy build.
ALTER TABLE device_policy ADD COLUMN login_browser_presets TEXT NOT NULL DEFAULT '[]';
-- The parent's own sites only.
CREATE TABLE login_browser_sites (
    device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    entry TEXT NOT NULL,          -- logins::valid_entry's form
    note TEXT,                    -- the parent's own words, <= 60 chars
    added_ms INTEGER NOT NULL,
    PRIMARY KEY (device_id, entry)
);
ALTER TABLE device_status ADD COLUMN captive_portal_json TEXT;
ALTER TABLE device_status ADD COLUMN login_browser_json TEXT;
```

## 7. Security and privacy

- **What the kid can reach, Part A.** Vibb's window: http(s), on a network that doesn't pass traffic, moving to other
  sites only by the portal's redirects and form posts, under the filter's lists, at most 10 minutes per opening and 20
  minutes a day. The parent sees every site visited.
  - **The residue**: a network built to fail Vibb's https checks (blocking gstatic, Cloudflare's check host and the
    server) while passing other traffic, whose own page form-posts to a site the lists allow. Within that site the kid
    can then follow links: at most 20 minutes a day, and reported by name to the parent. That takes a laptop set up on
    purpose.
  - Today's stock window, on a phone with the kiosk off, reaches more and is never filtered (M5, M6).
- **The window's filter** is Vibb's lists. The parent's own DNS upstream (design 36) doesn't see the window's lookups,
  and the page's sub-line says so. WebSockets and WebRTC are not seen by the WebView's hooks. Service workers are off.
- **What the kid can reach, Part B.** Only the parent's sites, in a Custom Tab or full Chrome. The rest of Chrome's UI
  stays reachable: history of those sites, bookmarks of them, Share, and the New Tab page's tiles (which lead to
  blocked pages).
  - Chrome is never unhidden before its lockdown reads back.
  - Each tightening ends the tabs that were open.
- **`URLBlocklist` filters navigations, not sub-resources.** The DNS filter covers those.
- **The entry activity** accepts only the system's start (`getLaunchedFromUid`). The window activity is not exported.
- **The `:portal` process** holds no token and no server client, and runs none of the app's start-up. Its state comes
  from a snapshot the main process wrote.
- **Privacy**: the SSID (when readable), the portal's hosts and the sites visited go to the parent. The parent sees
  the phone's location already.
- **The kid's name and "Spotify"** appear nowhere in this design's strings.

## 8. Risks

- **Android routes the sign-in intent elsewhere on some build** (an OEM `setPackage`, a future `ConnectivityService`).
  The report's `handler` shows it. The Wi-Fi page and Home line still work (binderless). There is no fallback to
  pinning the stock app: no insecure fallbacks.
- **Portals that break in Vibb's window** where AOSP's works: a TLS quirk, a required download, a direct link to
  another site (the cross-site rule), a slow payment. The parent's PIN pause lifts the limits; nothing more.
- **Vibb's https check hosts** become unreachable for an ordinary reason (a filtering network). The window then never
  closes early, but still validates, times out and counts in the budget.
- **Chrome's policy changes**: a policy dropped, a schema type change (the list policies are JSON strings in 133,
  M10). The device check reads `chrome://policy` after each Chrome major.
- **Chrome updates** come through Play's nightly window. A Chrome too old for a key ignores it. The policy must hold at
  Chrome's cold start after a boot and after an update (D-C3).
- **The redirect-before-blocklist order** (M15) is Chromium's current code, not a promise. If a later Chrome blocks the
  callback, the parent adds `scheme:*` (the format allows it), and §5.2's rules would then take custom schemes.
- **The re-probe's rate limit** may make the binderless window wait for Vibb's own check or NetworkMonitor's own
  schedule (E4).

## 9. Steps

1. **Server: the page, the policy, the contract** (M).
   - Migration 0150.
   - `logins.rs`: `valid_entry`, `PRESETS`, `clean`.
   - The page and its saves (`POST /devices/{id}/logins`, `section=wifi|apps|site|preset|grant`).
   - The policy keys, the report intake, the two Activity events, the filter page's row, the Apps page's Chrome row.
   - nb + en, `login_windows.json`, tests.
2. **Launcher Part A: the window** (L).
   - `portal/`: `CaptivePortalEntry` (the uid and network guards), `PortalWindowActivity` in `:portal` with the
     `isPortalProcess` guard, the snapshot and the session hand-back.
   - Pure `portalNavigation`, `portalPasses`, `wifiRowState` and `portalBudget`.
   - The preferred activity in `applyHeld` and `reseatPreferredActivities`.
   - The Wi-Fi page's row and Home's line.
   - The report's `captive_portal`, the capability, nb + en.
   - `scripts/fake-portal.py` (standard library, `--selftest`; modes: a portal, a soft portal that passes https, a
     hotspot that 302s to a search page) and `docs/testing/emulator.md` §6ao.
3. **Launcher Part B: the role** (M).
   - `BrowserConfig` before the hide loop, with the read-back.
   - `computeEnforcementPlan`'s `loginBrowser`, `loginRoleListable`, `chromeTightened` with the hide and unhide, and
     the one-time clear.
   - The report's `login_browser`, the capability.
   - `LoginBrowserTest`, emulator.md §6ap.

Steps 2 and 3 are independent of each other and both need step 1's contract. The launcher's half of the contract goes
in the same commit as the server's (API in both dirs).

## 10. Tests (lean)

- **Server**, `tests::logins`:
  - a new phone's policy (Part A on, role off);
  - the role refused while Chrome is ticked;
  - a preset ticked and unticked, with an own entry equal to its host kept;
  - each `entries` row;
  - the report stored and cleaned;
  - a `wifi_signin_wide` raised once across two reports with the same session, a `validated` session raising none, and
    `wifi_signin_budget`'s key;
  - "one more" bumping `grant`;
  - the page's anchors;
  - `policy_json_keys_snapshot` and `intake_limits`.
- **Launcher**:
  - `PortalRulesTest`: `navigation`, `close`, `budget`, `wifi_rows`.
  - `LoginBrowserTest`: `bundles`, `plans`, `tighten`; the package constant; not ready → hidden.
  - `LoginWindowsTest`: the contract.
  - `EnforcementPlanTest`: two rows. A ready role pins and unhides Chrome and leaves it off the lock's usable apps; a
    ticked Chrome ignores the role.
  - `PolicyResponseCompatTest`.
  - The entry's guard (`getLaunchedFromUid`) is glue, proved by E2.

## 11. Checks owed

### Emulator (`jelly36`, the real launcher and server, `scripts/fake-portal.py`)

- E1: the notification under the kiosk opens Vibb's window. Accept closes it within ~2 s, and the phone reports
  `validated`.
- E2: the system's start passes the `getLaunchedFromUid` check, and with `NETWORK_STACK` on the entry. An
  `am start` of the entry from the shell is refused.
- E3: Vibb's real filter shape and the sink shape. The portal loads by hostname, and a host on the phone's list is
  blocked in the window.
- E4: the "soft portal" and "hotspot" modes of `fake-portal.py` close the window as `passes` within 5 s, and it won't
  reopen on that network.
- E5: a link tap to another site is refused, and a 302 there passes.
- E6: the budget's fifth opening is refused, and "one more" lets one through.
- E7: the binderless start from the Wi-Fi page and Home, and whether `reportNetworkConnectivity` re-probes or is
  rate-limited.
- E8: the role.
  - An allowed app's Custom Tab to a listed site works.
  - Wikipedia typed in Chrome is blocked.
  - The New Tab page's feed is gone.
  - A ticked Chrome refuses the role.
  - A failed write (a test hook) leaves Chrome hidden.
- E9: a pause lifts both. At its end, a YouTube tab opened in the pause is gone (hide and unhide), and the role's first
  turn-on after a ticked phase clears Chrome's history.

### The Jelly Star (Android 16, the user's own Wi-Fi and apps)

- D-C1: `resolveActivity(CAPTIVE_PORTAL)` names Vibb after enrolment, and the package name of the phone's own login
  app.
- D-C2: real portals: a hotel, a café, Vy's or Flytoget's train Wi-Fi, an airline (a free tier).
- D-C3: `chrome://policy`.
  - The typed bundle, and the JSON-string lists.
  - The policy in force at Chrome's cold start after a boot and after a Chrome update.
  - Typed `data:`, `view-source:` and `chrome://flags`, and a `javascript:` URL typed while an allowed page is open.
  - "⋮ › Help & feedback".
- D-C4: "Add to Home screen" and "Install app" (WebAPK) from a Custom Tab add nothing with Vibb as Home.
- D-C5: a real "Logg inn med Feide/Google" in a kid's app returns to the app.
- D-C6: each preset's hosts, from a real login of each.

## 12. Decisions for the user (D-items, with the recommendation)

| # | Question | Recommendation |
|---|---|---|
| D1 | Wi-Fi sign-in on by default for every phone? | **Yes**, with the parent's switch. On a kiosk phone it is a new surface (today: no sign-in at all), but a closed one: it closes when the network works, can't change site except by the portal's redirects, is capped and reported. With an older server the parent has no switch until the server is updated |
| D2 | The window's limits | **10 minutes per opening, and 4 openings and 20 minutes a day per phone**, plus the parent's "one more" (D14). Per phone, not per network: network names are often unreadable and per-network counts are easy to reset |
| D3 | Moving to another site inside the window | **Only by the portal's own redirect or form post**, with link taps free within the same site, and **Vibb's https check closing the window once the network passes traffic**. Cost: a portal's direct "Log in with Facebook" link is refused (a redirect there works). Alternative: any link under the lists, which revision 1 had and the QA showed to be a browser |
| D4 | The window during a time rule (bedtime/school) | **Allowed** (a hotel at bedtime). Now that it can't browse, it gives connectivity, not apps; the budget still applies |
| D5 | Home's line "Wi-Fi-en vil at du logger på" | **Yes**: one calm line, tap to open. Android's notification sits folded in the Silent bundle (M1) |
| D6 | What the parent sees of the sign-ins | **Every sign-in with the network, the outcome, the sites visited and the blocked hosts**; an Activity event (pushed, held at night) for one that visited more than 3 sites, had blocked hosts or timed out, and when the day's budget runs out |
| D7 | Chrome-for-logins during a time rule | **Suspended like every app** (a login at bedtime waits). Alternative: spared when the rule's exempt apps include an app that needs it |
| D8 | Which browser takes the role | **Chrome only**, enforced by the launcher (`com.android.chrome` exactly). Others stay as today |
| D9 | The PIN pause and the role's URL lockdown | **Lifted with everything** (the pause's rule), with Chrome's process ended at the pause's end. Alternative: keep Chrome's URL lockdown in a pause, so a pause never means free Chrome |
| D10 | Chrome-for-logins on by default? | **No**: off until the parent turns it on and adds sites |
| D11 | Presets | **Feide, Google, Microsoft, Apple**, stored as a set and expanded by the server. No social logins (Facebook) and no Vipps/BankID presets; the parent can add them as own sites |
| D12 | A "learning window" (Chrome's lockdown lifted for 10 minutes while the phone records the sites Chrome looks up) | **Not in this round.** Chrome's blocked page names the site. The DNS log has no per-app attribution, and a lifted Chrome is free browsing for those minutes |
| D13 (new) | Clear Chrome's data when the role is turned on after Chrome was a free, ticked app | **Yes, automatically, once per change.** Otherwise the free phase's history, reading list, offline pages and logged-in sites stay reachable |
| D14 (new) | The parent's "one more sign-in now" | **Yes**: one opening and 10 minutes added at once, a one-shot (no window of time). The PIN pause is the other way |

## 13. Design QA (`docs/review/qa-102-design.md`): how each finding was handled

| Finding | Handling |
|---|---|
| H1 | All four of the QA's parts. Vibb's https "network works" close (§3.2), another site only by redirect or POST (§3.2, D3), the sites visited reported with an Activity event (§3.6, D6), a daily budget per phone with the parent's "one more" (§3.3, D2, D14). The residue is in §7 |
| M1 | Migration 0150, recheck at merge (§6.4) |
| M2 | The `isPortalProcess` guard. The window's state comes in a snapshot from the main process, and the session goes back by file and an explicit broadcast. `DnsFilterEngine` gets a pure constructor. The WebView claim is corrected (§3.2) |
| M3 | `BrowserConfig` runs before the hide loop and reads the bundle back; Chrome is unhidden only when ready, else hidden with `failed`. The package must be the constant. The bundle is typed (§4.1) |
| M4 | Each tightening hides and unhides Chrome after the new bundle is verified; plus a one-time data clear (D13) (§4.1) |
| M5 | The preferred activity is in `applyHeld` and `reseatPreferredActivities`. Off is the disabled component. The "steady-state check" phrase is gone (§3.1) |
| M6 | `getLaunchedFromUid() == SYSTEM_UID`, and the network and budget checks on both paths (§3.1) |
| M7 | The parent's upstream and WebSockets/WebRTC are named as uncovered (§3.2, §3.6, §7). Service workers are off |
| M8 | Presets as a set in `device_policy`, own rows apart, expanded at policy build (§5.2, §6.4) |
| L1 | D1's reason rewritten (§3.6, D1) |
| L2 | The TWA and in-app-link note (§4.1, §5.2). Suggested rows from the allowed apps' TWA hosts are left for later |
| L3 | `ContextualSearchEnabled`, `AllowDinosaurEasterEgg`, `chrome://*` in the blocklist, and the device checks added (D-C3, D-C4) |
| L4 | The WebView's settings, explicit (§3.2) |
| L5 | English for every string, the save mechanism named, Safari 14 (§5) |
| L6 | The kinds, the dedup keys and the push rule (§3.6) |
| L7 | Chrome is never listed, in any state, via a GPL pure function passed into `AppFilter` (§4.1) |
| L8 | The nullable DTO, the full-report fields in the hash, `fake-portal.py` in `CLAUDE.md` and in `launcher.yml`'s self-tests (§6) |
| L9 | The rate limit is in E7 and §8; the window's network check at open is in §3.1 |

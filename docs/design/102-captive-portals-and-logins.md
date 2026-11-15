# Design 102: Wi-Fi sign-in pages and in-app logins without free Chrome

Status: design, revision 1, 2026-11-15 (branch `design-102`). Not built.

Approved for the queue by the user on 2026-11-15 (PLAN, "Captive portals and in-app browser logins without free
Chrome"). The user's words:

- *"Er det noen måter å få opp browser vinduer for feks å logge på hotel wifi og den type ting, uten å kunne bruke
  chrome på ordentlig for vanlig browsing? Captive portals. Og samme for login via browser i div apper"*
- *"Ja legg i køen å komme meg en bra løsning for dette uten å slippe fri chrome"*

**The short version.**

1. **Wi-Fi sign-in (captive portals).** Today a hotel or café Wi-Fi can't be signed in to under the kiosk. Android
   posts "Sign in to Wi-Fi network", the kid can tap it, and lock task refuses the login app (measured, §1 M3).
   - The design makes **Vibb One itself the handler** of Android's sign-in intent. The device owner sets a persistent
     preferred activity; that works, and the system hands Vibb the portal's `CaptivePortal` object, the network and the
     URL (§1 M7).
   - Vibb shows the portal in **its own sign-in window**: a WebView bound to that Wi-Fi network, with no address bar and
     only http(s).
   - The DNS filter's lists apply to the window. Its tabs can't open other windows, downloads or apps.
   - It **closes the moment Android validates the network**, and after 10 minutes at the latest.
   - Android's own login app is not used. Measured, it follows any link to any site and gets around the DNS filter
     (§1 M4-M5).
2. **In-app logins.** The kid's apps open a Custom Tab or the browser for a login ("Logg inn med Google/Feide/
   Microsoft").
   - **Chrome gets a new role: "only for logins"** (`login_browser`). It stays installed and resolvable for other
     apps' Custom Tabs and links, is pinned in the kiosk, and is never on Home or in the drawer.
   - It runs under the device owner's managed configuration: `URLBlocklist ["*"]` and an `URLAllowlist` of login
     sites the parent keeps in the PWA. Also off: incognito, search, sign-in to Chrome, downloads, the first-run
     screen, and the New Tab page's feed.
   - Measured on the emulator (§1 M9-M15): the policy holds in Custom Tabs and in full Chrome, and the kid's typed
     address, search and the New Tab page's tiles all end at "… is blocked". The redirect back to the app works
     with `*` blocked (custom scheme and `intent://`).
   - A Vibb login window was rejected: Google refuses sign-in in embedded WebViews (`disallowed_useragent`), and
     apps open Chrome by package, so a Vibb window would never see the login.

Both parts are liftable by the parent: the Wi-Fi window has a switch, and the login role is off until the parent turns
it on. The PIN pause lifts both, as it lifts everything. No adb: both are set from the PWA.

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

### 3.1 Vibb becomes the handler (launcher)

- **`CaptivePortalEntry`** (`portal/`, new): an exported, theme-less trampoline activity with the filter
  `android.net.conn.CAPTIVE_PORTAL` + `DEFAULT` and `android:permission="android.permission.NETWORK_STACK"`.
  - The system starts it from system_server, which holds that permission. A third-party app can't start it with a
    forged intent.
  - It reads the four extras of M7 and starts the non-exported **`PortalWindowActivity`** with them. When the guarded
    start is refused, the entry activity runs unguarded and its body checks the caller: `getCallingUid()`/the referrer
    must be the system. This is not wired as a fallback; the build step decides by the measurement
    ([needs emulator check] E2).
  - It refuses (finishes) while the PIN lock is LOCKED: the shade is off then anyway.
- **`AppEnforcer.apply`** writes `addPersistentPreferredActivity(admin, CAPTIVE_PORTAL filter, CaptivePortalEntry)`
  while the part is on (§3.5), and has the same steady-state check as the dialer's.
  - Off: the filter is left out at the next rewrite. Vibb's persistent preferred activities can only be cleared all
    at once (`clearPackagePersistentPreferredActivities`), and HOME and the dialer's are Vibb's too, so a rewrite
    re-adds every other one in the same pass.
  - **Off also means the entry activity is disabled** (`setComponentEnabledSetting`), so Android's own handler is the
    only one. The stock app then stays refused by lock task, as today.
- **Nothing is pinned for it.** Vibb's package is always in the lock-task list, so the start passes the kiosk, also
  with the app-block bit (M7). The stock login app is never pinned.
- **The binderless start**: Vibb's own Wi-Fi page and Home line (§3.4) open the window without a `CaptivePortal`
  object (the system API `startCaptivePortalApp` isn't ours).
  - The window gets the `Network` from `ConnectivityManager` and loads Android's own probe URL
    (`http://connectivitycheck.gstatic.com/generate_204`), which the portal intercepts and redirects. That is how any
    browser finds a portal.
  - Re-evaluation then goes through the public `ConnectivityManager.reportNetworkConnectivity(network, true)`.

### 3.2 The window (`PortalWindowActivity`, process `:portal`)

- **Its own process.** `bindProcessToNetwork` binds the whole process, and Vibb's main process must keep tsnet, the
  SSE stream and the sync on the default network. The `:portal` process binds to the portal's Wi-Fi network and calls
  `WebView.setDataDirectorySuffix("portal")` before its first WebView: two processes may not share one WebView data
  directory, and the launcher's own login screens use WebView in the main process. The process ends with the window.
- **The look**: Vibb's kid page header (`KidHeader`).
  - Title "Logg på Wi-Fi" / "Sign in to Wi-Fi", the network's name when Android gives it, else "Wi-Fi".
  - Under it the current page's host, read-only (as M4).
  - A close button. BACK steps back in the page's history and closes on its first page.
  - No address bar, no menu, no "use as is".
  - The WebView sends the user agent the system passed (`CAPTIVE_PORTAL_USER_AGENT`) only for the first load, then
    the WebView's own, as AOSP does.
- **What the page may do** (pure `portalNavigation`, `PortalRulesTest`):
  - Main-frame and sub-frame navigations: `http`/`https` only.
    - `tel:` goes to `ACTION_DIAL` with Vibb's own dialer as the package. Calls stay screened by Vibb's call rules;
      emergency numbers work as always.
    - Everything else is refused in place: `intent:`, `market:`, `sms:`, custom schemes, `file:`, `content:`,
      `data:` as a top-level target, `javascript:` typed.
  - No new windows (`setSupportMultipleWindows(false)`: `target=_blank` loads in place, as M5).
  - Downloads, the file chooser, geolocation, camera, microphone, MIDI and protected media are refused.
  - JS dialogs (alert/confirm/prompt) are allowed: portals use them.
  - Mixed content is refused, and TLS errors show a page of ours with no way past. The portal's own pages are
    usually plain http, and an https page with a bad certificate is not one to type a room number into.
- **The DNS filter applies inside the window** while the phone's filter is on.
  - `shouldInterceptRequest` and `shouldOverrideUrlLoading` classify every request's host with the same engine as the
    tunnel: `DnsFilterEngine.classifyDomain`, loaded from disk in `:portal`, the same lists, the parent's allowed
    domains and the built-in secure-DNS hosts.
  - A blocked main frame shows "Denne siden er sperret" / "This page is blocked". A blocked sub-resource gets an empty
    403.
  - The window's traffic doesn't pass the tunnel (it is bound to the Wi-Fi), so this is how the filter reaches it.
    Blocked hosts go into `BlockedEventLog` as the tunnel's blocks do (the log has no app field) and into the
    session's `blocked_hosts` (§6.2).
- **Link taps to other sites are allowed (D3).** Portals hop between hosts: the venue, the vendor (Nomadix, Aruba,
  Cisco Meraki), a payment provider, a "Log in with Facebook" walled garden. On a real portal before sign-in, every
  site outside the walled garden is answered by the portal itself. After sign-in the window closes within a second
  (M9). What remains are (a) the walled garden and (b) a network that never validates while it passes traffic:
  - a probe URL blocked by the venue;
  - partial connectivity.

  For both, the filter's lists and the time limit (D2) hold.
- **Closing.** A `NetworkCallback` on the portal's network.
  - On `VALIDATED` it calls `reportCaptivePortalDismissed()` (with the binder), records the outcome `validated` and
    finishes. That is what AOSP does (M8, M9).
  - After each main-frame page finish it asks for a re-probe, at most once per 3 s:
    `reportCaptivePortalDismissed()` with the binder (M8: re-probes, reusable), else `reportNetworkConnectivity`.
  - On the network's loss: `lost`, finish.
  - On the kid's close: `closed`, and `reportCaptivePortalDismissed()` so Android re-probes and keeps its
    notification.
  - On the **time limit** (D2, 10 minutes from the opening): `timeout`. The window shows "Innloggingen tok for lang
    tid. Prøv igjen, eller spør en voksen." / "Signing in took too long. Try again, or ask a grown-up." with one
    button "Lukk" / "Close".

  The kid can open it again: from the notification while Android still posts it, from the Wi-Fi page or from Home.
- **A pause or the override** lifts the window's rules: no filter check and no time limit. It is still Vibb's window
  (the preferred activity stays; rewriting it around a pause would touch HOME's). Never "fully open" beyond that: no
  address bar either way.
- **The PIN lock**: the window finishes when the lock engages, as the lock clears every other task, and it can't be
  started while LOCKED.

### 3.3 Under a time rule

The window is Vibb's own and is not suspended by a lock. **D4: it opens during a time rule too.** A hotel Wi-Fi at
bedtime is the case. While a rule blocks, Home is the lock, and the notification is still in the shade if the lock's
features keep it. During the PIN lock (LOCKED) the shade is off, so there is no way in, which is right.

### 3.4 The kid's ways in

1. **Android's notification** (M1). It works once Vibb is the handler, but sits folded in the Silent bundle.
2. **Vibb's Wi-Fi page** (`WifiNetworksActivity`): the connected row of a network with `CAPTIVE_PORTAL` and without
   `VALIDATED` reads "Tilkoblet – må logges på" / "Connected – sign-in needed", with a button "Logg på" / "Sign in".
   Pure `wifiRowState(caps)`, `PortalRulesTest`.
3. **Home's status line (D5)**: while the Wi-Fi the phone is on is captive (Wi-Fi up, `CAPTIVE_PORTAL`, not
   `VALIDATED`), Home shows one calm line "Wi-Fi-en vil at du logger på" / "This Wi-Fi wants you to sign in". It is
   the same kind of line as design 98's "Flymodus er på", and a tap opens the window. It is never a sound, a
   notification of ours or a badge.

### 3.5 The parent's side (Part A)

- **Default on (D1)** for every phone the launcher manages: the window is narrower than anything the kid can reach
  today with the kiosk off.
- **The switch.** The phone's Web Filter page (`/devices/{id}/filter`) gets a row "Innlogging" that leads to a new page
  `/devices/{id}/logins` (§5). The switch there is "Wi-Fi-innlogging (hotell, kafé, fly)" / "Wi-Fi sign-in (hotels,
  cafés, planes)". It is on by default, with a sub-line saying what it is: "Vibb viser innloggingssiden til nettet,
  uten adressefelt. Lukkes når nettet virker." / "Vibb shows the network's sign-in page, with no address bar. It closes
  once the network works."
- **The recent sign-ins (D6)**, from the report (§6): the last 10 openings with time, network name (when the phone
  could read it), the portal's first host, the outcome (`validated`, `closed`, `timeout`, `lost`) and the hosts the
  filter blocked in it. A `timeout` or a window with blocked hosts becomes an Activity event (design 101's unread
  count). The others are rows of the page only.

## 4. Part B: Chrome only for logins

### 4.1 The role (launcher)

The policy's `login_browser` (§6) names the role and its allowed sites. While it holds:

- **Enforcement** (`computeEnforcementPlan` gets `loginBrowser: String?`):
  - Chrome is treated as allowed for hiding and suspending. It is not hidden, so it resolves for other apps' Custom
    Tabs and links, and not suspended, which would show "app paused".
  - It is pinned in the kiosk list. M11: not pinned, a Custom Tab with the app-block bit is "Chrome is not available
    right now"; M12: pinned, the tab runs in the app's task.
  - A time-rule lock suspends it like any app (D7).
  - It is never in `permittedAccessibility`/`permittedInputMethods` and never in the lock's usable apps.
  - The parent's allowlist and the role are exclusive (§5): a ticked Chrome is a full browser, as today.
- **Off Home and the drawer**: `AppFilter.kidListable` drops the role's package whatever `app_display` says, in a
  window too.
  - The kid reaches Chrome only through an allowed app (a Custom Tab, a link) or the Custom Tab's "Open in Chrome
    browser" (M13). Both land under the policy.
  - Vibb does not accept pinned shortcuts (no `CONFIRM_PIN_SHORTCUT` handler in the launcher), so the menu's "Add to
    Home screen" adds nothing. [needs device check] D-C4.
- **Only `com.android.chrome` (D8).** Another browser keeps today's rules: hidden unless ticked, with DoH off. A phone
  without Chrome (or with Chrome disabled) reports `absent` and the PWA says so; the role does nothing there.
- **The managed config: one owner.** `BrowserDoh` becomes `BrowserConfig`, and pure `browserConfigPlan(filterOn,
  loginRole, installed)` gives each listed browser its bundle. Chrome's bundle while the role holds and no window is
  open (a pause or the override lifts it, below) is:

  | Key | Value | Why |
  |---|---|---|
  | `URLBlocklist` | `"[\"*\"]"` | everything blocked … |
  | `URLAllowlist` | `"[…the parent's sites…]"` (JSON string, M10) | … but these (subdomains included) |
  | `IncognitoModeAvailability` | `1` | no incognito (M14) |
  | `BrowserSignin` | `0` | no sign-in to Chrome itself; web sign-in still works |
  | `DefaultSearchProviderEnabled` | `false` | typed words go nowhere (M14) |
  | `SearchSuggestEnabled` | `false` | no suggestions while typing |
  | `NTPContentSuggestionsEnabled` | `false` | no Discover feed on the New Tab page |
  | `PromotionalTabsEnabled` | `false` | no promo tabs |
  | `TosDialogBehavior` | `2` | no first-run screen before the first login (M12) |
  | `DownloadRestrictions` | `3` | no downloads |
  | `PasswordManagerEnabled` | `false` | no saved passwords to browse in Chrome's settings |
  | `AutofillCreditCardEnabled` | `false` | no stored cards |
  | `PrintingEnabled` | `false` | |
  | `ForceGoogleSafeSearch`, `ForceYouTubeRestrict` | `true`, `2` | belt and braces if a Google host is allowed |
  | `DeveloperToolsAvailability` | `2` | Chrome 154+ only (M10), harmless before |
  | `DnsOverHttpsMode` | `"off"` | while the filter is on, as today (design 67) |

  Written only when it differs (`sameBundle`), as today. **Whether the bundle reached Chrome can't be read back**
  (Chrome has no API). The report says what was written, and `chrome://policy` is a device check.
- **A pause or the override**: as everything else, the role's lockdown lifts. Chrome's bundle falls back to DoH only
  (or empty with the filter off), and Chrome is listed as any unhidden app (in a window every app is). At the window's
  end the bundle is written again in the same `apply()` that hides the rest. **D9** asks whether the lift should keep
  the URL lockdown instead.
- **The Chrome UI that stays** (M13, M14): Custom Tab "⋮" (Download this page: refused by `DownloadRestrictions`;
  Chrome history; Open in Chrome browser) and full Chrome's address bar, New tab, History, Bookmarks, Recent tabs.
  Every navigation in them meets `URLBlocklist`. Chrome's blocked page is Chrome's words ("Your organization doesn't
  allow you to view this site"), not Vibb's.
- **The redirect back** needs no entry (M15): custom schemes and `intent://` go to the app before the blocklist. An
  https App Link callback (`https://app.example/oauth/callback`) is a normal navigation, and its host must be on the
  list (§5.2 says so).

### 4.2 Why not a Vibb login window

- Apps choose the browser themselves. AppAuth and Google's and Microsoft's libraries query for Custom Tabs
  providers and `setPackage` one, so a login never passes through anything Vibb could catch.
- Google refuses OAuth in embedded WebViews (`disallowed_useragent`); Microsoft and Apple discourage them.
- A Vibb "browser" of its own for VIEW intents would catch only plain links.

## 5. The PWA: `/devices/{id}/logins` ("Innlogging" / "Sign-in")

A new page under the phone, linked from the Web Filter page with a row "Innlogging" and a sub-line ("Wi-Fi: på ·
Apper: av"). Server-rendered like its siblings, saved per section without a jump (the save rule). nb + en.

### 5.1 Wi-Fi sign-in (§3.5)

The switch, its sub-line and the recent sign-ins.

### 5.2 Logins in apps

- **The switch "Innlogging i apper med Chrome"** / "Sign-in in apps with Chrome", off by default (D10). Its sub-line:
  "Apper kan åpne innloggingssider i Chrome. Chrome vises ikke på telefonen og åpner bare sidene under." / "Apps can
  open sign-in pages in Chrome. Chrome isn't on the phone's screens and opens only the sites below."
  - **Refused while Chrome is ticked** under Apps. The page explains: "Chrome er tillatt som vanlig app. Fjern haken
    under Apper for å bruke den bare til innlogging." The Apps page's Chrome row says the reverse while the role is
    on.
  - Shown only when the phone's report lists `com.android.chrome`. Otherwise "Chrome finnes ikke på telefonen".
- **Allowed sign-in sites**: a list of rows (site, the parent's own note, remove), an add field, and **presets** as
  ticks (D11). Each preset is a fixed set of hosts:
  - **Feide** (Norwegian schools): `auth.dataporten.no`, `idp.feide.no`, `innsyn.feide.no`;
  - **Google**: `accounts.google.com`, `accounts.youtube.com`;
  - **Microsoft**: `login.microsoftonline.com`, `login.live.com`, `login.microsoft.com`, `account.live.com`;
  - **Apple**: `appleid.apple.com`, `idmsa.apple.com`;
  - **Vipps** is left out of the presets: Vipps login normally opens the Vipps app, and a bank sign-in on a kid's
    phone is a separate decision.

  The device check (D-C6) settles each preset's exact hosts; a preset is a list in the server, `logins::PRESETS`.
- **The entry rules** (server `logins::valid_entry`, the contract's `entries`):
  - A host (IDN to punycode, lower case) with an optional leading dot (that host only), optional port and optional
    path.
  - No scheme (http and https both), no `*`, no query, no custom scheme (M15: not needed).
  - At most 100 entries per phone (Chrome takes 1000).
  - Pasting a full URL keeps its host and path (`https://school.example/login?x=1` → `school.example/login`).
- **The web filter's own lists still apply**, since Chrome's DoH is off and its lookups pass the tunnel. On save, the
  server checks each entry against the phone's compiled lists (the Allowed Domains notice's machinery, design 67). A
  blocked one shows "Sperret av nettfilteret – legg den til under Tillatte domener også" with a link.
- **A note** on the page: "Siden appen viser kan sende Chrome videre til andre sider. Står de ikke her, sier Chrome at
  siden er sperret. Legg da til siden som står i Chrome." / "The page the app shows can send Chrome on to other sites.
  If they aren't listed, Chrome says the page is blocked. Then add the site Chrome names." That is the learning path
  for v1: Chrome's blocked page names the host. D12 is the learning window.
- The phone's state: "Klar" / "Ready" (the report's `login_browser.state = on`), "Venter på telefonen" (no report since
  the save), "Krever nyere Vibb One" (the capability missing), "Chrome mangler" (`absent`), "Kunne ikke settes"
  (`failed`, with the report's reason).

## 6. The policy, the report, the contract

### 6.1 The policy (top level, both keys pinned by `policy_json_keys_snapshot` and `PolicyResponseCompatTest`)

```json
"captive_portal": {"enabled": true, "minutes": 10},
"login_browser": {"package": "com.android.chrome", "hosts": ["auth.dataporten.no", "school.example/login"]}
```

- `login_browser` is `null` while the role is off. `minutes` is the server's constant (D2), sent so a later change
  needs no launcher release.
- **Older launcher**: ignores both keys. Chrome stays hidden and Wi-Fi sign-in stays impossible under the kiosk, as
  today. The page says "Krever nyere Vibb One" from the capabilities.
- **Older server**: neither key. The launcher reads `captive_portal` absent as **on** with 10 minutes: Part A is the
  launcher's own fix and needs no server, and an older server has no switch to say otherwise. `login_browser` absent
  reads as off.

### 6.2 The report (full-report fields, not heartbeat fields)

```json
"captive_portal": {"handler": "vibb", "sessions": [{"opened_ms": 0, "closed_ms": 0, "network": "Hotel Fjord",
  "first_host": "portal.example", "outcome": "validated", "blocked_hosts": ["x.example"], "start": "system"}]},
"login_browser": {"package": "com.android.chrome", "state": "on", "version_name": "133.0.6943.137",
  "written_ms": 0, "reason": null}
```

- `handler`: who `resolveActivity(CAPTIVE_PORTAL)` names: `vibb`, `system` (the part is off) or `other` (a device
  check finding, shown to the parent).
- `sessions`: the last 10.
  - `network` is the SSID only when Android gives it to Vibb (location on), else `null`.
  - `start`: `system` (the notification) or `vibb` (the Wi-Fi page or Home).
  - `blocked_hosts`: at most 10, each at most 253 characters.
- `login_browser.state`: `on`, `off`, `absent`, `failed` (`reason`: the exception's class name).
- Capabilities: `captive_portal_v1`, `login_browser_v1`.
- The server cleans both field by field (`logins::clean`), keeps them in `device_status.captive_portal_json` /
  `login_browser_json`, and `intake_limits` keeps their keys.

### 6.3 The contract: `server/testdata/login_windows.json`

The file is byte-identical in `launcher/app/src/test/resources/` and holds:

- `capabilities`;
- `policy` (a new phone; Part A off; the role on with a preset and an own site);
- `older_server` (the launcher's reading of the policy with neither key);
- `report` and `report_off` (exact);
- `clean` (the server's cleaning rows: too many sessions, a long host, an unknown outcome, a non-object);
- `entries` (the add field's input to an entry or a refusal, the presets' hosts included).

Test usage:

- **The server**: `tests::logins` saves the page and produces each `policy` row, posts the reports and checks what is
  stored, and runs `clean` and `entries`.
- **The launcher**: `LoginWindowsTest` decodes `policy` and `older_server`, encodes `report` exactly from its types,
  and runs `entries` through its own check (the launcher refuses an invalid host in a policy rather than writing it to
  Chrome).
- Both compare the copies.

Launcher-only rules go in `launcher/app/src/test/resources/login_rules.json` (`PortalRulesTest`, `LoginBrowserTest`):

- `navigation` (scheme, host, filter verdict, validated, minutes, window → allow/refuse/close);
- `wifi_rows`;
- `bundles` (filter, role, window → the exact bundle);
- `plans` (allowlist, role, lock → hidden, suspended, pinned, listable).

Root `CLAUDE.md` gets the file's paragraph. `launcher.yml` and `server-ci.yml` get it in their path lists.

### 6.4 Migration `0149_login_windows.sql`

```sql
ALTER TABLE device_policy ADD COLUMN captive_portal_enabled INTEGER NOT NULL DEFAULT 1;
ALTER TABLE device_policy ADD COLUMN login_browser_enabled INTEGER NOT NULL DEFAULT 0;
CREATE TABLE login_browser_sites (
    device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    entry TEXT NOT NULL,          -- logins::valid_entry's form
    note TEXT,                    -- the parent's own words, <= 60 chars
    preset TEXT,                  -- 'feide' | 'google' | 'microsoft' | 'apple' | NULL (own)
    added_ms INTEGER NOT NULL,
    PRIMARY KEY (device_id, entry)
);
ALTER TABLE device_status ADD COLUMN captive_portal_json TEXT;
ALTER TABLE device_status ADD COLUMN login_browser_json TEXT;
```

A preset ticked writes its hosts with `preset` set. Unticking removes exactly those rows, and an own row with the same
entry stays.

## 7. Security and privacy

- **What the kid can reach, Part A.** Vibb's window, http(s), filtered, closing on validation or after 10 minutes. The
  remaining reach is the walled garden of a portal and a network Android never validates, both under the filter's
  lists. **The DNS filter now applies to Wi-Fi sign-in**: today's stock window, with the kiosk off, gets around it
  (M6).
- **What the kid can reach, Part B.** Only the parent's sites, in a Custom Tab or full Chrome. The rest of Chrome's UI
  stays reachable: history of those sites, bookmarks the kid makes of them, Share, the Discover feed if a Chrome
  ignores `NTPContentSuggestionsEnabled`. A site on the list that links out stops at "… is blocked".
- **Sub-resources are not filtered by `URLBlocklist`**, only navigations; the DNS filter covers them.
- **The entry activity** is guarded by `NETWORK_STACK` (or the caller check, §3.1). The window activity is not exported.
- **The `:portal` process** holds no token and no server client. It reads the filter's lists from disk and the
  session it writes is picked up by the main process at the next report.
- **Privacy**: the SSID and the portal's hosts go to the parent. The parent sees the phone's location already; the SSID
  is read only when Android allows it, and nothing else of the network.
- **The kid's name and "Spotify"** appear nowhere in this design's strings.

## 8. Risks

- **Android routes the sign-in intent elsewhere on some build** (an OEM `setPackage`, a future `ConnectivityService`).
  The report's `handler` shows it. The kid still has the Wi-Fi page's and Home's binderless path (§3.1). No fallback to
  pinning the stock app: no insecure fallbacks.
- **Portals that break in Vibb's window** where AOSP's works: a TLS quirk, a download the portal requires, a WebView
  feature we refused. Then the parent's PIN pause lifts the rules (§3.2); nothing more.
- **Chrome's policy changes**: 147's `chrome://` exemption from `*`, a policy dropped, a schema type change (the list
  policies are JSON strings in 133, M10). The device check reads `chrome://policy` after each Chrome major.
- **Chrome updates** come through Play's nightly window like every catalog app. A Chrome too old for a key ignores it.
- **The redirect-before-blocklist order** (M15) is Chromium's current code, not a promise. If a later Chrome blocks the
  callback, the parent adds `scheme:*` (the format allows it), and §5.2's entry rules would then take custom schemes.
- **Discover and tiles** on the New Tab page: tiles stay (they only lead to blocked pages); the feed goes with the
  policy, not measured.

## 9. Steps

1. **Server: the page, the policy, the contract** (M). Migration 0149, `logins.rs`:
   - `valid_entry`, `PRESETS`, `clean`;
   - the page with both sections and its saves (`POST /devices/{id}/logins`, `section=wifi|apps|site|preset`);
   - the policy keys, the report intake, the Activity events, the filter page's row;
   - the Apps page's Chrome row;
   - nb + en strings; `login_windows.json`; tests.
2. **Launcher Part A: the window** (M-L). `portal/`:
   - `CaptivePortalEntry`, `PortalWindowActivity` in `:portal`, pure `portalNavigation`/`wifiRowState`;
   - the persistent preferred activity in `apply()` (with the other preferred activities rewritten);
   - the Wi-Fi page's row, Home's line;
   - the report's `captive_portal`, the capability; nb + en; `PortalRulesTest`, the contract's side.
   - `scripts/fake-portal.py` (the scratch server made a script: standard library, `--selftest`) and
     `docs/testing/emulator.md` §6ao.
3. **Launcher Part B: the role** (M):
   - `computeEnforcementPlan`'s `loginBrowser`, `AppFilter`, `BrowserConfig` (`BrowserDoh` renamed and widened);
   - the report's `login_browser`, the capability; `LoginBrowserTest`; emulator.md §6ap.

Steps 2 and 3 are independent of each other and both need step 1's contract. The launcher's half of the contract goes
in the same commit as the server's (API in both dirs).

## 10. Tests (lean)

- **Server** `tests::logins`:
  - a new phone's policy (Part A on, role off);
  - the role refused while Chrome is ticked;
  - a preset ticked and unticked with an own entry kept;
  - each `entries` row; the report stored and cleaned;
  - a `timeout` session raising one Activity event, a `validated` one none;
  - the page's sections save without a jump (the template's anchors);
  - `policy_json_keys_snapshot`; `intake_limits`.
- **Launcher**:
  - `PortalRulesTest`: every `navigation` and `wifi_rows` row;
  - `LoginBrowserTest`: every `bundles` and `plans` row;
  - `LoginWindowsTest`: the contract;
  - `EnforcementPlanTest`: two rows. The role pins and unhides Chrome but leaves it off the lock's usable apps; a
    ticked Chrome ignores the role;
  - `PolicyResponseCompatTest`.

## 11. Checks owed

### Emulator (`jelly36`, the real launcher and server, `scripts/fake-portal.py`)

- E1: the notification under the kiosk opens Vibb's window. Accept closes it within ~2 s. The phone reports a
  `validated` session.
- E2: the entry activity guarded by `NETWORK_STACK` is still started by the system. If not, the caller check (§3.1).
- E3: Vibb's real filter shape and the sink shape. The window loads the portal by hostname, and a host on the phone's
  list is blocked in the window.
- E4: the 10-minute limit (with a shorter `minutes` from a test policy). The Wi-Fi page's row and Home's line open the
  window without the binder, and `reportNetworkConnectivity` re-probes.
- E5: the role. An allowed app's Custom Tab to a listed site works. Wikipedia typed in Chrome, the New Tab page's feed
  (gone?), a ticked Chrome (role refused).
- E6: a pause lifts both and its end puts both back.

### The Jelly Star (Android 16, the user's own Wi-Fi and apps)

- D-C1: `resolveActivity(CAPTIVE_PORTAL)` names Vibb after enrolment, and the package name of the phone's own
  login app.
- D-C2: a real portal: a hotel, a café, Vy's or Flytoget's train Wi-Fi, an airline.
- D-C3: `chrome://policy` shows the bundle. `URLBlocklist`/`URLAllowlist` take the JSON-string form.
- D-C4: "Add to Home screen" from a Custom Tab adds nothing with Vibb as Home.
- D-C5: a real "Logg inn med Feide/Google" in a kid's app returns to the app.
- D-C6: each preset's hosts, from a real login of each.

## 12. Decisions for the user (D-items, with the recommendation)

| # | Question | Recommendation |
|---|---|---|
| D1 | Wi-Fi sign-in on by default for every phone? | **Yes**, with the parent's switch. It is narrower than today's kiosk-off path and fixes a real gap |
| D2 | The window's time limit | **10 minutes per opening**, reopenable. Shorter breaks slow portals (payment); none leaves the never-validating hole open |
| D3 | Link taps to other sites inside the sign-in window | **Allowed, under the filter's lists.** Portals hop hosts. Restricting taps to the portal's own domain breaks "Log in with Facebook" portals and payments. Alternative: same registrable domain only, at the cost of some portals |
| D4 | The window during a time rule (bedtime/school) | **Allowed** (hotel at bedtime). The kid gets connectivity, not apps: the rule still holds every app |
| D5 | Home's line "Wi-Fi-en vil at du logger på" | **Yes**: one calm line, tap to open. Android's notification sits folded in the Silent bundle (M1) |
| D6 | The parent sees the sign-ins (network name, first host, outcome, blocked hosts) | **Yes**: rows on the page, an Activity event only for a time-out or blocked hosts |
| D7 | Chrome-for-logins during a time rule | **Suspended like every app** (a login at bedtime waits). Alternative: spared when the rule's exempt apps include an app that needs it |
| D8 | Which browser takes the role | **Chrome only** (`com.android.chrome`, preinstalled, policy measured). Others stay as today |
| D9 | The PIN pause and the role's URL lockdown | **Lifted with everything** (the pause's rule). Alternative: keep Chrome's URL lockdown in a pause, so a pause never means free Chrome |
| D10 | Chrome-for-logins on by default? | **No**: off until the parent turns it on and adds sites |
| D11 | Presets offered | **Feide, Google, Microsoft, Apple**, ticks with fixed hosts. No social logins (Facebook) and no Vipps/BankID as presets; the parent can still add them as own entries |
| D12 | A "learning window" (Chrome's lockdown lifted for 10 minutes while the phone records the hosts Chrome looks up, offered to the parent as ticks) | **Not in this round.** Chrome's blocked page names the host, which the kid can show the parent. The DNS log has no per-app attribution today, and a lifted Chrome is free browsing for those minutes. Revisit if adding sites by hand proves painful |

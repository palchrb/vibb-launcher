# Design 102: Wi-Fi sign-in pages and in-app logins without free Chrome

Status: design, **revision 4**, 2026-11-15 (branch `design-102`). Not built. Revision 1 was b724662cd, revision 2
3fb9ac2bf, revision 3 7ceedfb81.

**Revision 4** takes the user's answers to the D-list (2026-11-15). What changed:

- **D15 → (a): cleartext is allowed in Vibb One; the server rule stays in code.** The user: *"Er det viktig for oss å
  stoppe all ukrypterte trafikk? Vi ville vel egentlig bare passe på at vibb one server kontakt ikke er http med mindre
  tailscale er i bruk?"*
  - The companion app is dropped. The window runs in Vibb One's own `:portal` process (revision 2's design).
  - The server path keeps its rule in code (`serverUrlVerdict`, `ServerTransport`, `server_url_vectors.json`: https,
    or http only to a Tailscale IP literal), re-pinned by tests.
  - §3.1 lists every connection the launcher opens today, and the one cheap test that keeps them on https by code.
- **D3 → (i)**, and **D1, D2, D4-D14 as recommended.**
- **New: reopening the sign-in page (§3.8, D16).** The user's worry: *"Ulempen er vel om captive portal ender i en
  slags portal man trenger for å feks logge av igjen eller lignende"*.
  - On Vibb's Wi-Fi page, while the phone is on a network it signed in to today, a row "Åpne påloggingssiden" opens
    that network's recorded portal page again.
  - It never changes site, and it counts against the same daily budget.
  - It doesn't widen the residue the user accepted under (i).

**Revision 3** (history; its D15 recommendation (b) was not taken, see revision 4) takes the second design QA (`docs/review/qa-102-design.md`, "Revision 2": 2 High, 4 Medium, 6 Low). Both
Highs were settled by measurement first (§1b). What changed:

- **Cleartext (R2-H1, new D15).** Measured: a WebView under the launcher's network security config refuses every
  `http://` load (`net::ERR_CLEARTEXT_NOT_PERMITTED`) - a portal host, an IP literal, Android's own probe URL. A portal is
  http, so the window can't run inside Vibb One as the config stands. Two ways out (§3.2):
  - **(a)** allow cleartext in Vibb One's config and keep the server's https rule in code;
  - **(b)** run the window in a **small companion app inside Vibb One** (`me.vibb.portal`, "Vibb Wi-Fi-innlogging")
    with its own config. Vibb One carries it, installs it and keeps it current, so there is no second release to
    manage.

  **Recommended: (b).** It keeps the user's S-M1 rule (no cleartext anywhere in Vibb One) exactly as decided, and it
  gives the window a process and storage of its own for free.
- **The entry's guard (R2-H2).** Measured:
  - `getLaunchedFromUid()` is `-1` for the system's start, so revision 2's check would have refused every real
    sign-in.
  - With `android:permission="android.permission.NETWORK_STACK"` on the entry, the system's start passes and another
    app's start is refused ("Permission Denial … requires android.permission.NETWORK_STACK").

  The permission is the guard now.
- **The site rule (R2-M1, R2-M2, D3 with three options).**
  - **Portal sites.** Sites become portal sites only through the first page and form posts. Other sites the portal
    sends the kid to are dead ends that may only lead back.
  - **The page's own jumps.** A meta refresh or a script jump (`hasGesture() == false`, measured) counts as the
    portal's own move. A tap (`hasGesture() == true`, measured) does not.
  - **Frames.** Sub-frames are judged in `shouldInterceptRequest`, since a tap inside an iframe never reaches
    `shouldOverrideUrlLoading` (measured).
  - **Form posts** are judged there too.
  - **Honest residue.** Redirects of sub-resources are invisible (measured) and named as residue. D3 now states that
    residue plainly, in words for the user.
- **The https check (R2-M3).**
  - A pass needs two separate operators (Google's and Cloudflare's check hosts, or either one and the phone's own
    server). A portal whose walled garden lets Google through no longer closes the window early.
  - It is checked afresh at each opening; nothing is remembered as passing.
  - The check client's rules are named.
- **The window hears from Vibb One (R2-M4).** Vibb One tells the window to close on the PIN lock, on Part A turned off
  and at a pause's end. A window opened in a pause closes when the pause ends.
- **The Lows (R2-L1 to L6).**
  - `DONT_KILL_APP`.
  - The budget's clock, one window at a time, and the `grant` edges.
  - The "wide" event counts blocked frames only.
  - A tightening is a set difference.
  - "Unknown" history clears once, and the switch shows the clear's cost.
  - The snapshot carries what `classifyDomain` really takes.
  - D-C3 reads `chrome://policy` with a test bundle.
  - The emulator checks are reworked.

Approved for the queue by the user on 2026-11-15 (PLAN, "Captive portals and in-app browser logins without free
Chrome"). The user's words:

- *"Er det noen måter å få opp browser vinduer for feks å logge på hotel wifi og den type ting, uten å kunne bruke
  chrome på ordentlig for vanlig browsing? Captive portals. Og samme for login via browser i div apper"*
- *"Ja legg i køen å komme meg en bra løsning for dette uten å slippe fri chrome"*

**The short version.**

1. **Wi-Fi sign-in (captive portals).** Today a hotel or café Wi-Fi can't be signed in to under the kiosk. Android
   posts "Sign in to Wi-Fi network", the kid can tap it, and lock task refuses the login app (§1 M3).
   - The design makes **Vibb the handler** of Android's sign-in intent. The device owner sets a persistent preferred
     activity; that works, and the system hands it the portal's `CaptivePortal` object, the network and the URL (§1
     M7).
   - The window is a WebView of Vibb's own, bound to that Wi-Fi network:
     - no address bar, http(s) only, the filter's lists;
     - another site only by the portal's own moves;
     - it **closes the moment the network works** (Android validates it, or Vibb's own https check passes);
     - 10 minutes per opening, 4 openings and 20 minutes a day.
   - It runs in a process of Vibb One's own (`:portal`). Vibb One allows cleartext (http); its server connection
     stays https by code (D15).
   - Afterwards the kid can reopen the portal's own page (to log out, or to see time left) from the Wi-Fi page, the
     same day, on that network (§3.8).
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

## 1b. Measured for revision 3 (`jelly36`, the same image, 2026-11-15)

The same probe app (device owner), rebuilt. These were its settings:

- **Its network security config is the launcher's release config**: `base-config cleartextTrafficPermitted="false"`,
  system CAs only, the `tailnet-server.vibb.invalid` exception. Two hostname exceptions, `nip.io` and `sslip.io`, were
  added so the WebView tests could run.
- **Its handler activity carries `android:permission="android.permission.NETWORK_STACK"`.**
- **A second, ordinary app (`me.vibb.probe.other`)** tries to start that handler.

The fake portal (`fakeportal4.py`, scratchpad) serves:

- the portal at `http://10-0-2-2.nip.io:8105`;
- "another site" at `http://10.0.2.2.sslip.io:8105` (another registrable domain, the same server);
- a meta-refresh page, an onload `location` page, a 302, a full-page iframe of the other site, and an image whose URL
  302s to the other site.

| # | What | Result |
|---|---|---|
| R1 | **Cleartext under the launcher's config** | `http://neverssl.com/` → `net::ERR_CLEARTEXT_NOT_PERMITTED`. `http://10.0.2.2:8105/login` (an IP literal) → the same. Android's probe URL `http://connectivitycheck.gstatic.com/generate_204` → the same. Only a host in the config's exceptions (`10-0-2-2.nip.io`) loaded. **As the config stands, Vibb One can't show a portal** (R2-H1 confirmed) |
| R2 | **The guarded entry, the system's start** | Tapping "Sign in to Wi-Fi network" (the probe as the preferred activity): `START … cmp=me.vibb.probe.cp/.Portal … from uid 1000 … result code=0`. The window got the `CaptivePortal`, network and URL, and loaded the portal. **`NETWORK_STACK` doesn't stop the system** |
| R3 | **`getLaunchedFromUid()` for that start** | `-1`. `getLaunchedFromPackage()` is `null`, and `getReferrer()` is `android-app://android` (which the caller writes). For the probe's own start the uid was its own (10217) (R2-H2 confirmed) |
| R4 | **Another app starts the guarded entry** | `Permission Denial: starting Intent { cmp=me.vibb.probe.cp/.Portal } from … uid=10218 requires android.permission.NETWORK_STACK`; the app got `ActivityNotFoundException`. **E2 passes both ways** |
| R5 | **The WebView's hooks** (`shouldOverrideUrlLoading` = SOUL, `shouldInterceptRequest` = SIR) | **Meta refresh** to the other site: SOUL `redirect=false gesture=false main=true`. **onload `location =`**: the same. **A 302 after `loadUrl`**: SOUL `redirect=true gesture=false`. **A link activated by the user** (keyboard Enter): SOUL `redirect=false gesture=true`. **The first `loadUrl`** itself: no SOUL, only SIR. **A main-frame POST** never reaches SOUL; SIR sees `method=POST` |
| R6 | **Frames and sub-resources** | **A full-page iframe** of the other site: no SOUL, only SIR (`main=false`, `Accept: text/html,application/xhtml+xml,…`; an image's is `image/avif,…`). `Sec-Fetch-Dest` is not in the headers. **A tap on a link inside the iframe** (to the portal host): again no SOUL, only SIR. **An image whose URL 302s to the other site**: SIR for the first URL only, **never for the redirect's target** |

Not measured: a real portal, Chrome's tab restore after a kill, and `chrome://policy` under `chrome://*`.

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

### 3.1 Where the window runs: `:portal`, with cleartext allowed (D15 = (a), the user's decision)

**Why cleartext.** A portal is http by nature (R1):

- Android's probe URL;
- the intercepted redirect;
- `CAPTIVE_PORTAL_URL`;
- portals on IP literals.

Vibb One's network security config refused all of it. A config names hosts, never ranges.

**S-M1's history, honestly.** The security review's S-M1 (2026-11-04) was about the **server path**: the phones'
server address must be `https://`, or `http://` only to a Tailscale IP literal. That rule lives in code:

- `serverUrlVerdict`;
- `ServerTransport`'s `Closed`/`NOT_HTTPS`, which refuses before the bearer or a socket;
- the shared vectors `server_url_vectors.json`.

The app-wide `cleartextTrafficPermitted="false"` was a **second safety net** under it: it made Android itself refuse
any http, whatever the code did. The user, 2026-11-15: the intent was the server path. So the net goes, and the
rule stays.

**The config.**

- `base-config cleartextTrafficPermitted="true"`, **system CAs only**, as now. The release config never trusts a user
  CA; the debug config keeps its `debug-overrides`.
- The `tailnet-server.vibb.invalid` exception is no longer needed for cleartext. It stays: it is how the tailnet
  client names its host, and removing it is churn.

**What in the launcher opens network connections today** (read 2026-11-15), each https by code:

| Connection | Where | Why it is never http |
|---|---|---|
| The server API, the SSE stream, the music files the launcher fetches | `MdmApi`, `CommandListenerService`, `music/MusicServer.kt`, all children of `ServerClients.base(path)` | `ServerTransport`: `serverUrlVerdict` gives https, tailnet http (an IP literal through tsnet) or closed. Pinned by `ServerTransportTest` ("every server client takes its way from the transport") |
| Catalog APKs, the self-update, app logos, weather, calendar feeds, the music library | through `createMdmApi(serverUrl, …)`, the same transport | the same rule: the server hands out paths, and the phone fetches them from the server. Weather is fetched by the server from MET (design 75), calendar feeds by the server (design 72) |
| The DNS filter's DoH upstream | `buildDohClient` (`UpstreamTransport.kt`) | `CustomUpstream` takes only an `https://` URL that OkHttp parses; the providers' URLs are constants |
| The DoT upstream, the bootstrap lookups | raw sockets (`SSLSocketFactory`, UDP) | not HTTP; the security config never applied to them |
| tsnet (Tailscale) | Go, inside the `.aar` | its own TLS and Noise; not covered by the config |
| The sign-in window's https check | new, in `:portal` (§3.3) | fixed `https://` constants |
| The sign-in window's WebView | new, in `:portal` | **the one place that may speak http**, by design |

**The cheapest test that keeps it so** (`ServerTransportTest`, replacing the pin of the config). One source scan over
`launcher/app/src/main/java`:

- `OkHttpClient.Builder()` appears only in `ServerTransport.kt`, `UpstreamTransport.kt` and `portal/PortalCheck.kt`;
- `HttpURLConnection`, `openConnection(` and `java.net.URL(` appear nowhere;
- `WebView` appears only under `portal/`.

A new HTTP client anywhere else fails the build until it is reviewed. The test keeps the existing checks: every server
client takes its way from the transport, no user CA in the release config, and the vectors.

**The `:portal` process** (revision 2's design, unchanged):

- the window runs with `android:process=":portal"`;
- `Application.onCreate` gets an `isPortalProcess` guard next to `isBootCoverProcess` and returns before the main
  process's start-up;
- `WebView.setDataDirectorySuffix("portal")` is called before the first WebView;
- the window's state comes in as a snapshot file written by the main process (`noBackupFilesDir/portal/state.json`);
- the session goes back by a file and an explicit broadcast to a non-exported receiver.

"The window's process" in the rest of §3 is `:portal`.

### 3.2 Vibb becomes the handler

- **The entry, `CaptivePortalEntry`.** An exported, theme-less trampoline activity: the filter
  `android.net.conn.CAPTIVE_PORTAL` + `DEFAULT`, and **`android:permission="android.permission.NETWORK_STACK"`**.
  - **That permission is the guard (R2-H2).** system_server starts the entry as uid 1000, which passes every permission
    check (R2). No installable app can hold `NETWORK_STACK`, and another app's start is refused (R4).
  - `getLaunchedFromUid()` is not used: it is `-1` for the system's start (R3).
  - It reads the four extras of M7.
  - It **checks the network**: the `Network` exists, is Wi-Fi, has `CAPTIVE_PORTAL` and not `VALIDATED`.
  - It **runs Vibb's https check once** (§3.3): a pass means "the network works", and the entry finishes.
  - It checks the day's budget (§3.4) and refuses while the PIN lock is LOCKED.
  - **One window at a time (R2-L2).** If a window is open, it brings that one to the front and starts nothing.
  - Otherwise it charges the opening to the budget and starts the window, which is not exported.

  The entry runs in Vibb One's main process; the window in `:portal`.
- **The binderless start.** Vibb's Wi-Fi page and Home line (§3.6) open the window with an explicit intent, after the
  same network, check, budget and one-window tests. They pass no `CaptivePortal` object (the system API
  `startCaptivePortalApp` isn't ours).
  - The window first loads Android's own probe URL (`http://connectivitycheck.gstatic.com/generate_204`), which the
    portal intercepts and redirects.
  - It re-probes through the public `ConnectivityManager.reportNetworkConnectivity(network, true)`. NetworkMonitor
    rate-limits app requests, so this is best effort (E7). Vibb's own check closes the window either way.
- **The persistent preferred activity.**
  - `AppEnforcer.applyHeld` adds it in every pass while the part is on: `addPersistentPreferredActivity(admin,
    CAPTIVE_PORTAL filter, the entry)`.
  - `reseatPreferredActivities` adds it too, with HOME's, the Play link blocker's and the call-log pin's.
  - **Off is the disabled entry**: `setComponentEnabledSetting(entry, DISABLED, DONT_KILL_APP)` (R2-L1: the flag
    keeps Home, the VPN and the call services alive). The package manager matches a persistent preferred
    activity only against components in the resolve list, so a disabled entry falls through to Android's own handler.
    The stock app then stays refused by lock task, as today.
- **Nothing extra is pinned**: Vibb's package is always in the kiosk list. The stock login app is never pinned.

### 3.3 The window

- **Its state** comes from Vibb One's main process at the start, as the snapshot file, never from preferences
  `:portal` reads itself. It holds:
  - the filter's on/off;
  - the pause's or override's end time (`null` when none);
  - `minutes` and the time left today;
  - the check hosts;
  - the compiled block list, the Spotify flag and the built-in secure-DNS hosts.

  These are exactly what `classifyDomain` takes (R2-L5). The parent's allowed domains are not a separate input:
  `classifyDomain` has none, and the server's compiled list already leaves them out (to confirm in the build).
  `DnsFilterEngine` gets a pure constructor from these inputs.
- **Its result.** The session (§6.2) goes back by an explicit broadcast to a non-exported receiver. Vibb One folds it
  into the report and the budget.
- **Vibb One → the window (R2-M4).** Vibb One sends "close now" (outcome `closed_by_vibb`) when:
  - the PIN lock engages;
  - the parent turns Part A off;
  - a pause or the override ends while a window opened in it is open. The snapshot also carries that end, so the
    window closes by itself if the message is lost.

  The receiver is registered in `:portal`, not exported (same uid only). A changed filter list doesn't reach an open window: it lives 10 minutes at most.
- **The look**: Vibb's kid page header.
  - Title "Logg på Wi-Fi" / "Sign in to Wi-Fi", the network's name when Android gives it, else "Wi-Fi".
  - Under it the current page's host, read-only.
  - A close button. BACK steps back in the page's history and closes on the first page.
  - No address bar, no menu.
  - The first load sends the system's `CAPTIVE_PORTAL_USER_AGENT`.
- **Vibb's "the network works" check (Q102-H1.1, R2-M3).** `portalPasses` runs at the entry, at the window's start and
  every 5 s, on the bound network.
  - The client: OkHttp with `Network.socketFactory`, the system trust store, no proxy, `followRedirects(false)` and
    `followSslRedirects(false)`.
  - The hosts are fixed constants, never from the policy:
    - `GET https://www.gstatic.com/generate_204` → an exact 204 with an empty body;
    - `GET https://cp.cloudflare.com/generate_204` → the same;
    - the phone's own server, when its address is a public `https://` URL: any answer with a valid chain.
  - **A pass needs two operators**: Google's **and** Cloudflare's, or either one **and** the server. A walled garden
    that lets Google sign-in through (`*.gstatic.com`) no longer looks like a working network.
  - Its lookups are not judged by the window's `classifyDomain`. It doesn't touch the tunnel either: the window's
    process isn't in the VPN's routes for the bound network.
  - **A pass closes the window** with `passes` and "Nettet virker nå" / "The network works now".
  - **Nothing is remembered** (R2-M3). Each opening checks afresh, so a wrong pass doesn't lock out a connection.
- **Where the page may go** (pure `portalNavigation`, `PortalRulesTest`). The rule is for the page's own moves, not
  the kid's taps (R5), and it judges frames and form posts where they really pass (R5, R6).
  - **Schemes**: http and https only.
    - `tel:` goes to `ACTION_DIAL` with Vibb's own dialer as the package; calls stay screened.
    - Everything else is refused in place: `intent:`, `market:`, `sms:`, custom schemes, `file:`, `content:`,
      top-level `data:`, `javascript:`.
  - **Sites.** A site is the registrable domain (OkHttp's `HttpUrl.topPrivateDomain()`; an IP literal is its own
    site). The window keeps a set of **portal sites**:
    - **the first page's site** (after the probe URL's redirect, on the binderless path);
    - **every site reached from a portal site by a form post** (a main-frame `POST`, seen in SIR, R5).

    Every other site the window reaches is a **leaf**.
  - **Main-frame moves to another site.** Judged in SOUL for a GET, in SIR for a POST:
    - allowed when the page's own move comes **from a portal site**: a 302 (`isRedirect`), a meta refresh or a script
      (`!hasGesture()`, R5), or a form post;
    - allowed **to a portal site** from anywhere: a leaf may hand back;
    - refused otherwise. A move from a leaf to a third site is refused, which ends open-redirector chains (R2-M1). A
      tap to another site (`hasGesture()`, R5) is refused everywhere, with "Denne lenken går ut av innloggingssiden" /
      "This link leaves the sign-in page".
    - Within the current site, taps are free.
  - **Sub-frames** never reach SOUL, not even a tap inside one (R6). So SIR judges every frame document: a request
    with `isForMainFrame() == false` and an `Accept` that starts with `text/html`.
    - A frame of the top page's own site or of a portal site is allowed.
    - Any other is answered with a small blocked page (403). That includes a payment provider's card form in an
      iframe; the PIN pause lifts it.
    - Frame sites count in `visited`.
  - **The filter's lists**, while the phone's filter is on. Every request's host goes through `classifyDomain` in SIR.
    - A blocked main frame or frame shows "Denne siden er sperret" / "This page is blocked".
    - A blocked sub-resource gets an empty 403.
    - The hosts go into `blocked_hosts` (§6.2), and blocked frames also into `blocked_frames`.
  - **What these hooks do not see (R2-M1, Q102-M7)**, stated in §7:
    - **the target of a sub-resource's redirect** (R6: SIR sees the first URL only);
    - WebSockets and WebRTC;
    - **in-page navigation** of app-like sites (a "single-page" site moves by script without a navigation);
    - the parent's own DNS upstream.

    Service workers are off: `ServiceWorkerController` settings with `setBlockNetworkLoads(true)`, and a client that
    answers 403.
  - **The WebView's settings, explicit (Q102-L4)**:
    - JavaScript, DOM storage and cookies on.
    - `allowFileAccess`/`allowContentAccess` false.
    - No `addJavascriptInterface`, and contents debugging off in release.
    - Safe Browsing on.
    - `setSupportMultipleWindows(false)`, so `target=_blank` is a navigation, judged as above.
    - A refusing `DownloadListener`, and no file chooser.
    - Every permission request refused (geolocation, camera, microphone, MIDI, protected media).
    - JS dialogs allowed.
    - Mixed content never allowed.
    - TLS errors show our page, with no way past.
    - The selection action mode trimmed to Copy and Select all.
    - No context menu on links.
- **Closing.** A `NetworkCallback` on the portal's network.
  - On `VALIDATED`: `reportCaptivePortalDismissed()` (with the binder), outcome `validated` (M8, M9).
  - After each main-frame page: a re-probe at most once per 3 s.
  - Vibb's check passes: `passes`.
  - The network is lost: `lost`.
  - The kid closes: `closed`, with `reportCaptivePortalDismissed()` so Android keeps its notification.
  - Vibb One's message: `closed_by_vibb`.
  - After `minutes` (10) from the opening: `timeout`, with "Innloggingen tok for lang tid. Prøv igjen, eller spør en
    voksen." / "Signing in took too long. Try again, or ask a grown-up."
  - Today's budget runs out first: `budget`, with "Du har brukt dagens Wi-Fi-innlogginger. Spør en voksen." / "You've
    used today's Wi-Fi sign-ins. Ask a grown-up."
- **A pause or the override** lifts the window's limits while it lasts: the filter, the time limits, the budget and
  the site rule. It does not lift the https close.
  - **At the pause's end the window closes** (R2-M4; `close` row "opened in a pause, the pause ends").
  - A new opening after the pause has every rule back.
- **The PIN lock**: Vibb One's message closes the window, and the entry refuses while LOCKED.

### 3.4 The day's budget (Q102-H1.4, D2)

- **Per phone per local day**: at most **4 openings** and **20 minutes** of window time in all. Each opening also ends
  after 10 minutes.
- **Vibb One keeps the budget**: the date, openings and milliseconds, in `LauncherPreferences`.
  - An opening is charged at the entry, before the start.
  - Time is charged from the session's open-to-close. If the window's process died without a session, the whole
    `minutes` is charged.
- **The clock (R2-L2).** Vibb One stores the latest local date it has seen and never treats an earlier one as a new
  day. A clock set back gives nothing, whether or not the date and time lock holds (`lockDateTime`).
- **The parent's "one more" (D14).** The page's "Gi én innlogging til nå" / "Allow one more sign-in now" raises the
  policy's `captive_portal.grant` by one.
  - When the phone sees `grant` above the last one it used, it adds one opening and 10 minutes to today for each step,
    **at most 3 a day**, and stores the new value.
  - **On a fresh install or after lost data, the phone stores the policy's current `grant` as used and adds nothing**
    (R2-L2). An old counter never gives a free opening.
- The PIN pause lifts the budget while it lasts.
- The budget running out raises one Activity event (§3.7).

### 3.5 Under a time rule (D4)

The window opens during a time rule too: a hotel Wi-Fi at bedtime is the case. The kid gets connectivity, not apps,
since the rule still holds every app. The budget and the site rule apply as always. During the PIN lock (LOCKED) there
is no way in.

### 3.6 The kid's ways in

1. **Android's notification** (M1). It works once Vibb is the handler, but sits folded in the Silent bundle.
2. **Vibb's Wi-Fi page** (`WifiNetworksActivity`): the connected row of a captive network reads "Tilkoblet – må logges
   på" / "Connected – sign-in needed", with a button "Logg på" / "Sign in". Pure `wifiRowState`.
3. **Home's status line (D5)**: while the phone's Wi-Fi is captive, Home shows "Wi-Fi-en vil at du logger på" / "This
   Wi-Fi wants you to sign in", and a tap opens the window. It is never a sound, a notification of ours or a badge.
   With the budget used up, it reads "Spør en voksen om Wi-Fi-innlogging" / "Ask a grown-up about Wi-Fi sign-in" and
   opens nothing.
4. **After the sign-in, Vibb's Wi-Fi page** offers "Åpne påloggingssiden" / "Open the sign-in page" on a network it
   signed in to today (§3.8).

### 3.7 The parent's side (Part A)

- **Default on (D1).** On a kiosk phone the kid reaches no sign-in at all today (M3), so this is a new surface. A
  bounded one: it closes when the network works, moves between sites only by the portal's own moves, is capped per
  opening and per day, and every site is reported.
  - With an older server, the launcher's default is on and the parent has no switch until the server is updated
    (§6.1).
- **The switch** on `/devices/{id}/logins`: "Wi-Fi-innlogging (hotell, kafé, fly)" / "Wi-Fi sign-in (hotels, cafés,
  planes)". Its sub-line: "Vibb viser innloggingssiden til nettet, uten adressefelt, og lukker den når nettet virker.
  Nettfilterets lister gjelder, ikke din egen DNS-tjener." / "Vibb shows the network's sign-in page, with no address
  bar, and closes it once the network works. The web filter's lists apply, not your own DNS server."
- **The recent sign-ins (D6)**: the last 10, with:
  - the time and how it started;
  - the network's name (when readable);
  - the outcome;
  - **the sites visited**, with their count;
  - the blocked hosts.

  Under the list: today's use ("2 av 4 innlogginger, 7 av 20 minutter i dag" / "2 of 4 sign-ins, 7 of 20 minutes
  today") and the "one more" button.
- **Activity events** (`parent_events::raise`; key = the session's `opened_ms`, so the repeated sessions of later
  reports raise nothing new):
  - `wifi_signin_wide`: a session that visited **more than 3 sites**, had **blocked frames** (main frames or
    sub-frames, not sub-resources: R2-L3; analytics hosts on a portal page are normal), or ended `timeout`. "Wi-Fi-
    innloggingen besøkte 5 nettsteder" / "The Wi-Fi sign-in visited 5 sites", with the list.
  - `wifi_signin_budget`: the day's budget ran out (key `budget:<local date>`). "Dagens Wi-Fi-innlogginger er brukt
    opp" / "Today's Wi-Fi sign-ins are used up", with the "one more" link.
  - Push: `Push::Now` outside the night, `Push::Held` until 07:00 inside it.

### 3.8 Reopening the sign-in page (D16, the user's worry)

Some portals keep a page for after the sign-in: log out, time left, buy more time. The window closes when the network
works, so without this the kid can't get back to that page.

- **What is recorded.** When a session ends `validated` or `passes`, Vibb One's main process keeps one record per
  network:
  - the network's identity: the `Network` handle, and the SSID when Android gives it;
  - the first portal page's URL (where the portal's redirect landed);
  - the session's portal sites (§3.3), at most 3;
  - the local date.

  The URL stays on the phone, since it may carry a session token; the report has `first_host` as before. Records
  older than today go, and so do all records when Part A is turned off.
- **The row.** On Vibb's Wi-Fi page, the connected network's row shows "Åpne påloggingssiden" / "Open the sign-in
  page", sub-line "Logg av eller se hvor lenge du har igjen" / "Log out or see time left". It shows only while the
  network matches a record of today: the same `Network` handle, or the same SSID (the Wi-Fi page turns Location on, so
  the SSID is readable there).
  - **Nothing to reopen on a network that was never captive**, or whose sign-in never ended in a working network.
  - Not on Home: one line there is enough (§3.6).
- **The mode (`start: "revisit"`).** The same window, bound to that network, opens on the recorded URL. Pure
  `portalNavigation` gets a `revisit` input:
  - **No site changes at all.** Main frames and frames may go only within the recorded portal sites, whether by
    redirect, script, form post or tap. Anything else gets "Denne lenken går ut av innloggingssiden" / "This link
    leaves the sign-in page".
  - **No auto-close by Vibb's https check**: the network works, that is the point. `VALIDATED` doesn't close it either.
  - It closes when the kid closes it, or on `timeout` (10 minutes), `budget`, `lost` or Vibb One's "close now".
  - The filter's lists apply as in a sign-in.
  - **It counts against the same daily budget** (an opening and its minutes), and it is **reported** as a session with
    `start: "revisit"`. The parent sees it in the list, and a `timeout` raises `wifi_signin_wide` as for any session.
  - The entry's tests are the same as a sign-in's, apart from the network check: a matching record, the budget, one
    window, not LOCKED.
- **H1 is not reopened.** A record exists only after a session Android or Vibb judged working.
  - A rig whose "portal" is an ordinary website can get there: after the "sign-in" it answers Android's probe with a
    204. Its record is then that one site (or the few sites the portal itself reached).
  - Reopening gives exactly those sites, with no new ones, within the same 20 minutes a day, reported by name. That is
    the residue the user accepted under (i).
  - The one difference, no https auto-close, changes nothing on that rig: the rig blocked Vibb's check hosts during the
    sign-in already.
- **The cost** (D16). A log-out at the end of the day uses the same budget as the sign-ins. If it is spent, the kid
  sees "Spør en voksen" / "Ask a grown-up", and the parent's "one more" lets one through.

## 4. Part B: Chrome only for logins

### 4.1 The role (launcher, `logins/`)

The policy's `login_browser` (§6) names the role and its allowed sites.

- **The package (D8).** The role is accepted only when `login_browser.package` equals the constant
  `com.android.chrome`. Anything else is `failed` (reason `package`), and nothing is unhidden.
- **The order: the lockdown first.** `BrowserConfig` (today's `BrowserDoh`, renamed and widened) runs **before** the
  hide/unhide loop. For the role's package it:
  1. writes the bundle;
  2. reads it back with `getApplicationRestrictions` and compares it typed;
  3. gives the plan `loginBrowserReady = true` only on a match.

  A throw, a mismatch or Chrome absent leaves Chrome **hidden and unpinned**, with `failed` and the reason (`write`,
  `readback`, `absent`).
- **Enforcement** (`computeEnforcementPlan` gets `loginBrowser: String?`, set only when ready):
  - unhidden and unsuspended (a suspended app shows "app paused");
  - pinned in the kiosk (M11, M12);
  - suspended by a time-rule lock like any app (D7);
  - never in `permittedAccessibility`/`permittedInputMethods`, and never among the lock's usable apps.

  A ticked Chrome ignores the role, and the server keeps the two exclusive (§5.2).
- **Never on Home or in the drawer, in any state.** Pure `loginRoleListable` in the GPL `logins/` directory, passed
  into `AppFilter` (MIT `apps/`). The kid reaches Chrome only through an allowed app or the Custom Tab's "Open in
  Chrome browser" (M13), both under the policy.
- **Each tightening ends Chrome's process (Q102-M4, R2-L4).** A tightening is:
  - the lockdown going from absent to present (the role's first turn-on, a pause's or the override's end);
  - **any old allowlist entry gone** (a set difference, so swapping one site for another counts; pure
    `chromeTightened(old, new)`);
  - the role off while Chrome isn't ticked.

  The same apply writes and verifies the new bundle, then hides and unhides Chrome. Hiding stops the package and its
  tasks.
- **Chrome's data is cleared, once (D13, R2-L4).** On the role's first turn-on after a phase where Chrome was a free
  app, or when the phone has **no record** (every phone at its first version with the role, since earlier versions
  never recorded it), the apply calls `dpm.clearApplicationUserData(admin, chrome, …)` before writing the bundle. The
  phone records that it did.
  - A pause needs no clear: a pause is short, the parent was there, and Chrome's restored tabs reload through
    `URLBlocklist` (E9).
  - The switch says the cost before the parent turns it on (§5.2).
- **The bundle** (pure `browserConfigPlan`; each key with its Bundle type):

  | Key | Type | Value | Why |
  |---|---|---|---|
  | `URLBlocklist` | string | `["*","chrome://*"]` (JSON) | everything blocked; `chrome://` is exempt from `*` since 147 |
  | `URLAllowlist` | string | `[…the parent's sites…]` (JSON) | these sites, subdomains included |
  | `IncognitoModeAvailability` | int | `1` | no incognito (M14) |
  | `BrowserSignin` | int | `0` | no sign-in to Chrome itself; web sign-in still works |
  | `DefaultSearchProviderEnabled` | bool | `false` | typed words go nowhere (M14) |
  | `SearchSuggestEnabled` | bool | `false` | no suggestions while typing |
  | `ContextualSearchEnabled` | bool | `false` | no Touch to Search panel |
  | `NTPContentSuggestionsEnabled` | bool | `false` | no Discover feed |
  | `PromotionalTabsEnabled` | bool | `false` | no promo tabs |
  | `AllowDinosaurEasterEgg` | bool | `false` | no offline game |
  | `TosDialogBehavior` | int | `2` | no first-run screen before the first login (M12) |
  | `DownloadRestrictions` | int | `3` | no downloads |
  | `PasswordManagerEnabled` | bool | `false` | no saved passwords to browse |
  | `AutofillCreditCardEnabled` | bool | `false` | no stored cards |
  | `PrintingEnabled` | bool | `false` | |
  | `ForceGoogleSafeSearch` | bool | `true` | if a Google host is allowed |
  | `ForceYouTubeRestrict` | int | `2` | the same |
  | `DeveloperToolsAvailability` | int | `2` | Chrome 154+ only (M10), harmless before |
  | `DnsOverHttpsMode` | string | `"off"` | while the filter is on (design 67) |
- **A pause or the override** lifts the role's lockdown with everything else (D9). The bundle falls back to DoH only,
  and Chrome stays unlisted but reachable from apps. The pause's end is a tightening.
- **The Chrome UI that stays** (M13, M14):
  - the Custom Tab's "⋮";
  - full Chrome's address bar, New tab, History, Bookmarks and Recent tabs.

  Every navigation in them meets `URLBlocklist`. Chrome's blocked page is Chrome's words.
- **The redirect back** needs no entry (M15). An https App Link callback's host must be on the list.
- **Trusted Web Activity apps and in-app links.** With the role on, an allowed web-app (TWA) app opens in Chrome and
  gets "… is blocked" unless its host is listed. The same holds for any allowed app's article links. The page's note
  says so (§5.2).

### 4.2 Why not a Vibb login window

- Apps choose the browser themselves. AppAuth and Google's and Microsoft's libraries query for Custom Tabs providers
  and `setPackage` one, so a login never passes through anything Vibb could catch.
- Google refuses OAuth in embedded WebViews (`disallowed_useragent`); Microsoft and Apple discourage them.
- A Vibb "browser" of its own for VIEW intents would catch only plain links.

## 5. The PWA: `/devices/{id}/logins` ("Innlogging" / "Sign-in")

A new page under the phone, linked from the Web Filter page.

- **The row**: "Innlogging" / "Sign-in", sub-line "Wi-Fi: på · Apper: av" / "Wi-Fi: on · Apps: off".
- **Rendering**: server-rendered, nb + en, no new client JS, so Safari 14 is fine.
- **Saving**:
  - The switches use `autosave.js` (`data-autosave`).
  - Add, remove and preset are form posts with a `303` to `#logins-sites`.
  - "One more" is a form post with a `303` to `#logins-wifi`.

### 5.1 Wi-Fi sign-in (§3.7)

The switch, its sub-line, the recent sign-ins with the sites visited, today's use, and "Gi én innlogging til nå" /
"Allow one more sign-in now".

### 5.2 Logins in apps

- **The switch** "Innlogging i apper med Chrome" / "Sign-in in apps with Chrome", off by default (D10).
  - Its sub-line: "Apper kan åpne innloggingssider i Chrome. Chrome vises ikke på telefonen og åpner bare sidene
    under." / "Apps can open sign-in pages in Chrome. Chrome isn't on the phone's screens and opens only the sites
    below."
  - Then the clear's cost (D13): "Når du slår dette på, slettes det som er lagret i Chrome på telefonen (historikk og
    innlogginger)." / "Turning this on clears what Chrome has stored on the phone (history and sign-ins)."
  - **Refused while Chrome is ticked** under Apps: "Chrome er tillatt som vanlig app. Fjern haken under Apper for å
    bruke den bare til innlogging." / "Chrome is allowed as a normal app. Untick it under Apps to use it only for
    sign-in."
  - The Apps page's Chrome row: "Brukes bare til innlogging (Innlogging-siden)" / "Used only for sign-in (the Sign-in
    page)".
  - Without Chrome: "Chrome finnes ikke på telefonen" / "Chrome isn't on the phone".
- **Presets (D11)**: ticks stored as a set (`device_policy.login_browser_presets`), expanded by the server's
  `logins::PRESETS` when it builds the policy.
  - Feide: `auth.dataporten.no`, `idp.feide.no`, `innsyn.feide.no`.
  - Google: `accounts.google.com`, `accounts.youtube.com`.
  - Microsoft: `login.microsoftonline.com`, `login.live.com`, `login.microsoft.com`, `account.live.com`.
  - Apple: `appleid.apple.com`, `idmsa.apple.com`.

  D-C6 settles the hosts.
- **Own sites**: rows (the site, a note, remove) and an add field, kept apart from the presets. The policy's `hosts`
  is the sorted union.
- **The entry rules** (`logins::valid_entry`):
  - A host with an optional leading dot, port and path.
  - No scheme, `*`, query or custom scheme.
  - At most 100 own entries.
  - A pasted URL keeps its host and path.
- **The web filter's lists still apply.** A blocked entry shows "Sperret av nettfilteret – legg den til under
  Tillatte domener også" / "Blocked by the web filter – add it under Allowed domains too".
- **The note**: "Siden appen viser kan sende Chrome videre til andre sider. Står de ikke her, sier Chrome at siden er
  sperret – legg da til siden Chrome nevner. Apper som er nettsider (de åpner seg i Chrome) trenger sin egen side her."
  / "The page the app shows can send Chrome on to other sites. If they aren't listed, Chrome says the page is blocked –
  then add the site Chrome names. Apps that are web pages (they open in Chrome) need their own site here."
- **The phone's state**:

  | Report | nb | en |
  |---|---|---|
  | `on` | "Klar" | "Ready" |
  | no report since the save | "Venter på telefonen" | "Waiting for the phone" |
  | the capability missing | "Krever nyere Vibb One" | "Needs a newer Vibb One" |
  | `absent` | "Chrome mangler" | "Chrome is missing" |
  | `failed` | "Kunne ikke settes" | "Couldn't be set" |

## 6. The policy, the report, the contract

### 6.1 The policy (top level)

```json
"captive_portal": {"enabled": true, "minutes": 10, "day_openings": 4, "day_minutes": 20, "grant": 0},
"login_browser": {"package": "com.android.chrome", "hosts": ["auth.dataporten.no", "school.example/login"]}
```

- Both are pinned by `policy_json_keys_snapshot` and `PolicyResponseCompatTest`.
- `login_browser` is nullable on both sides (`Option<…>`, a nullable Kotlin field).
- **Older launcher**: ignores both keys.
- **Older server**: neither key. The launcher reads `captive_portal` as on, with these limits and `grant: 0`, and
  `login_browser` as off.

### 6.2 The report (full-report fields)

```json
"captive_portal": {"handler": "vibb",
  "today": {"openings": 1, "ms": 240000, "grant_used": 0},
  "sessions": [{"opened_ms": 0, "closed_ms": 0, "network": "Hotel Fjord", "first_host": "portal.example",
    "outcome": "validated", "visited": ["portal.example", "nomadix.com"], "visited_count": 2,
    "blocked_hosts": [], "blocked_frames": [], "start": "system"}]},
"login_browser": {"package": "com.android.chrome", "state": "on", "version_name": "133.0.6943.137",
  "written_ms": 0, "reason": null, "cleared_ms": null}
```

- `handler`: `vibb`, `system` or `other`.
- `sessions`: the last 10.
  - `visited` holds at most 20 entries; `visited_count` is the true count (R2-M1).
  - `blocked_hosts` and `blocked_frames` hold at most 10 each.
  - `start` is one of `system` (the notification), `vibb` (the Wi-Fi page or Home) or `revisit` (§3.8).
  - `outcome` is one of `validated`, `passes`, `closed`, `closed_by_vibb`, `timeout`, `budget`, `lost`.
- `login_browser.state`: `on`, `off`, `absent` or `failed`, with a `reason`.
- Capabilities: `captive_portal_v1` and `login_browser_v1`.
- Both are full-report fields that count in the heartbeat's hash.
- The server cleans them (`logins::clean`) into `device_status.captive_portal_json` / `login_browser_json`, and
  `intake_limits` keeps their keys.

### 6.3 The contract: `server/testdata/login_windows.json`

The file is byte-identical in `launcher/app/src/test/resources/`. It holds `capabilities`, `policy`, `older_server`,
`report` and `report_off` (exact), `clean` and `entries`.

- **Server**: `tests::logins` produces the `policy` rows, posts the reports and runs `clean` and `entries`.
- **Launcher**: `LoginWindowsTest` decodes `policy` and `older_server`, encodes `report` exactly, and runs `entries`.
- Both compare the copies.

The launcher-only rules are in `launcher/app/src/test/resources/login_rules.json` (`PortalRulesTest`,
`LoginBrowserTest`):

- `navigation`: frame or main frame, GET or POST, redirect or gesture, from and to a portal site or a leaf, a pause, a
  revisit → allow or refuse. It includes the open-redirector chain, the meta refresh and the iframe tap;
- `frames`: an `Accept` header and `isForMainFrame` → a frame document or not;
- `passes`: answers of the check hosts → pass or not;
- `close`: validated, passes, minutes, the budget, "opened in a pause, the pause ends";
- `budget`: openings, ms, grants (a jump of 2, a fresh install) and the date moving back;
- `wifi_rows`;
- `bundles`;
- `plans`;
- `tighten`: a swap of one site, the role's first turn-on, a pause's end.

Root `CLAUDE.md` gets the file's paragraph and `scripts/fake-portal.py`. The CI path lists and `launcher.yml`'s
`fake-portal.py --selftest` are as revision 2. `docs/notes/launcher/security.md`'s "Cleartext" paragraph is rewritten
for D15 (§3.1).

### 6.4 Migration `0150_login_windows.sql`

Design 100 takes 0149. Recheck the number at the merge.

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

- **Part A, a real portal.** The window shows the portal's pages and the sites the portal itself sends to. It closes
  within a second or two of the sign-in, and the kid can't tap their way out.
- **Part A, the true residue** (R2-M1), stated as it is. A network **built on purpose** to fool Vibb (a laptop or
  travel router set up as a hotspot that blocks Vibb's check hosts and passes everything else) can send the kid to
  **one** site of its choosing. On that site the kid can tap around within the site. On an app-like site (one that
  moves by script, such as a video site) that means the whole site. That lasts at most 10 minutes per opening and 20
  minutes a day, under the filter's lists, and the parent sees the site by name with an Activity event. Sub-resource
  redirects, WebSockets and WebRTC are not seen by the WebView's hooks.
- **The window's filter** is Vibb's lists. The parent's own DNS upstream doesn't see the window's lookups, and the
  switch's sub-line says so.
- **Cleartext (D15).**
  - Android no longer refuses http in Vibb One.
  - The server path refuses it in code (`serverUrlVerdict`, `ServerTransport`, the shared vectors).
  - Every other HTTP client is https by code (§3.1's table).
  - The source scan in `ServerTransportTest` fails the build on a new client anywhere else.
  - The one http speaker is the sign-in window in `:portal`, which holds no token and no server client.
- **Part B.** Only the parent's sites, in a Custom Tab or full Chrome.
  - Chrome is never unhidden before its lockdown reads back.
  - Each tightening ends the tabs that were open.
  - `URLBlocklist` filters navigations, not sub-resources; the DNS filter covers those.
- **The entry** is guarded by `NETWORK_STACK` (R2, R4). The window and both receivers are not exported.
- **Privacy**: the SSID (when readable), the portal's hosts and the sites visited go to the parent.
- **The kid's name and "Spotify"** appear nowhere in this design's strings.

## 8. Risks

- **Android routes the sign-in intent elsewhere on some build.** The report's `handler` shows it. The Wi-Fi page and
  Home line still work, and there is no fallback to the stock app.
- **Portals that break in Vibb's window**:
  - a direct link to another site (refused);
  - a payment form in another site's iframe (refused);
  - a required download;
  - a TLS quirk.

  The PIN pause lifts the limits; nothing more.
- **A filtering network blocks one of Vibb's check hosts.** The window then never closes early, but still validates,
  times out and counts in the budget.
- **A later http client** added outside the scanned files: the source scan fails the build until it is reviewed.
- **Chrome's policy changes**: checked at each Chrome major (D-C3).
- **The redirect-before-blocklist order** (M15) is Chromium's current code, not a promise.
- **The re-probe's rate limit** (E7).

## 9. Steps

1. **Server: the page, the policy, the contract** (M).
   - Migration 0150.
   - `logins.rs`: `valid_entry`, `PRESETS`, `clean`.
   - The page and its saves (`section=wifi|apps|site|preset|grant`).
   - The policy keys, the report intake, the two Activity events, the filter page's row and the Apps page's Chrome row.
   - nb + en, `login_windows.json`, tests.
2. **Launcher Part A: the window** (L).
   - The entry and window with the guard, the https check, the site rules and the reverse channel; pure
     `portalNavigation`, `frameDocument`, `portalPasses`, `portalBudget`, `wifiRowState`.
   - The preferred activity in `applyHeld` and `reseatPreferredActivities`.
   - The config change (cleartext allowed), `ServerTransportTest`'s source scan, the `:portal` guard, and
     `security.md`'s rewrite.
   - The Wi-Fi page's rows (sign in, and §3.8's reopen) and Home's line; the report and the capability; nb + en.
   - `scripts/fake-portal.py`, standard library, `--selftest`, with modes: a portal; a meta-refresh portal; a script
     portal; a soft portal that passes https; a hotspot that 302s to a search page; a Google walled garden (gstatic
     passes, nothing else does); an iframe page.
   - emulator.md §6ao.
3. **Launcher Part B: the role** (M).
   - `BrowserConfig` before the hide loop, with the read-back.
   - `loginBrowser` in the plan, `loginRoleListable`, `chromeTightened` and the hide and unhide, the once-only clear.
   - The report; `LoginBrowserTest`; emulator.md §6ap.

Steps 2 and 3 are independent of each other and both need step 1's contract. The launcher's half of the contract goes
in the same commit as the server's.

## 10. Tests (lean)

- **Server** `tests::logins`:
  - the new phone's policy;
  - the role refused while Chrome is ticked;
  - a preset ticked and unticked, with an own entry kept;
  - each `entries` row;
  - the report stored and cleaned;
  - `wifi_signin_wide` raised once across two reports, and not for blocked sub-resources;
  - `wifi_signin_budget`'s key;
  - "one more" bumping `grant`;
  - the page's anchors;
  - `policy_json_keys_snapshot` and `intake_limits`.
- **Launcher**:
  - `PortalRulesTest`: `navigation`, `frames`, `passes`, `close`, `budget`, `wifi_rows`.
  - `LoginBrowserTest`: `bundles`, `plans`, `tighten`; the package constant; not ready → hidden.
  - `LoginWindowsTest`.
  - `EnforcementPlanTest` (two rows).
  - `PolicyResponseCompatTest`.
  - `ServerTransportTest`: the source scan (§3.1), and the release config with cleartext on and system CAs only.

## 11. Checks owed

### Emulator (`jelly36`, the real launcher and server, `scripts/fake-portal.py`)

**Always through a hostname** (`nip.io`), never `10.0.2.2`, which the debug config exempts.

- E1: the notification under the kiosk opens the window. Accept closes it within ~2 s, and the phone reports
  `validated`.
- E2: repeat R2/R4 with the real entry. The system's start passes, and a second ordinary app's start is refused (not
  the shell).
- E3: Vibb's real filter shape and the sink shape. The portal loads by hostname, and a listed host is blocked in the
  window.
- E4: the soft portal and the hotspot close the window as `passes` within 5 s. The Google walled garden does **not**.
- E5:
  - the meta-refresh and script portals work;
  - a tap to another site is refused;
  - a leaf's redirect to a third site is refused;
  - the iframe page's frame is refused and counted.
- E6: the fifth opening is refused, and "one more" lets one through.
- E7: the binderless start from the Wi-Fi page and Home, and the re-probe's rate limit.
- E8: the role.
  - A Custom Tab to a listed site works.
  - A typed blocked site is blocked.
  - The feed is gone.
  - A ticked Chrome refuses the role.
  - A failed write leaves Chrome hidden.
- E9:
  - a window opened in a pause closes at the pause's end;
  - a YouTube tab opened in a pause is gone after it, and a restored tab reloads blocked;
  - the role's first turn-on clears Chrome's history.
- E10: the reopen (§3.8).
  - After a `validated` sign-in, the Wi-Fi page offers "Åpne påloggingssiden" on that network and not on another.
  - It opens the recorded page; a tap to another site is refused; it stays open on a working network.
  - It counts in the budget.
  - The row is gone the next day and after a Wi-Fi with no sign-in.
- E11: with cleartext allowed in the config, the server path still refuses `http://` to a non-tailnet host (the
  vectors on the device). The source scan fails on a planted `HttpURLConnection`.

### The Jelly Star (Android 16, the user's own Wi-Fi and apps)

- D-C1: `resolveActivity(CAPTIVE_PORTAL)` names Vibb's entry after enrolment.
- D-C2: real portals: a hotel, a café, Vy's or Flytoget's train Wi-Fi, an airline. Log for each:
  - whether it uses client-side redirects or cross-site iframes;
  - whether its walled garden passes `www.gstatic.com`.
- D-C3: `chrome://policy`, read with a test bundle without `chrome://*`. Check:
  - the typed bundle and the JSON-string lists;
  - that the policy holds at a cold start after a boot and after an update;
  - typed `data:`, `view-source:`, `chrome://flags` and `javascript:`;
  - "⋮ › Help & feedback".
- D-C4: "Add to Home screen" and "Install app" add nothing with Vibb as Home.
- D-C5: a real Feide or Google login in a kid's app returns to the app.
- D-C6: each preset's hosts.

## 12. Decisions (the user, 2026-11-15)

| # | Question | Decision |
|---|---|---|
| D1 | Wi-Fi sign-in on by default for every phone? | **Yes**, with the parent's switch |
| D2 | Limits | **10 minutes per opening; 4 openings and 20 minutes a day per phone**, plus "one more" (D14) |
| D3 | Moving between sites inside the sign-in window | **(i)**: only the portal's own moves (a redirect, the page's own code, a form post) from the portal's pages; links free within one site; no taps out. The accepted residue: a network built on purpose to fool Vibb can send the kid to one ordinary site, where the kid can tap around within that site; at most 20 minutes a day, and the parent sees the site in Activity |
| D4 | The window during a time rule | **Allowed** |
| D5 | Home's line "Wi-Fi-en vil at du logger på" | **Yes** |
| D6 | What the parent sees | **Every sign-in with network, outcome, sites visited and blocked hosts**, and the Activity events of §3.7 |
| D7 | Chrome for logins during a time rule | **Suspended like every app** |
| D8 | Which browser | **Chrome only**, enforced by the launcher |
| D9 | The PIN pause and Chrome's URL lockdown | **Lifted with everything**, and Chrome's tabs ended at the pause's end |
| D10 | Chrome for logins on by default? | **No** |
| D11 | Presets | **Feide, Google, Microsoft, Apple** |
| D12 | A learning window | **Not now** |
| D13 | Clear Chrome's data when the role is turned on | **Yes, once, automatically**, with the cost under the switch |
| D14 | The parent's "one more sign-in now" | **Yes**, at most 3 a day |
| D15 | How the window may load http | **(a)**: cleartext allowed in Vibb One's config. The server path stays https, or http only to a Tailscale IP, enforced in code and tests (§3.1) |
| D16 (new, open) | Reopening the portal's page after the sign-in (log out, time left) | **Recommended: yes, as §3.8.** On Vibb's Wi-Fi page, the same day, on a network that was signed in to. Only the recorded portal sites, no site changes, no auto-close, and the **same daily budget**: a log-out can find the day's sign-ins used up, and then the parent's "one more" is the way. Reported like a sign-in. It adds nothing to D3's accepted residue |

## 13. Design QA: how each finding was handled

### Round 1 (`qa-102-design.md`, revision 1)

| Finding | Handling |
|---|---|
| H1 | Vibb's https close (§3.3), the site rule (§3.3, D3), the sites visited (§3.7, D6), the budget (§3.4, D2, D14). Revision 3 tightens all four (round 2) |
| M1 | Migration 0150 (§6.4) |
| M2 | The `:portal` guard and snapshot (§3.1, §3.3) |
| M3 | Written, read back typed, then unhidden; the package constant; typed keys (§4.1) |
| M4 | Hide and unhide at each tightening; the once-only clear (§4.1, D13) |
| M5 | `applyHeld` and `reseatPreferredActivities`; off is the disabled entry (§3.2) |
| M6 | Superseded by R2-H2: the permission guard plus the network checks (§3.2) |
| M7 | The uncovered parts named; service workers off (§3.3, §7) |
| M8 | Presets as a set, own rows apart (§5.2, §6.4) |
| L1-L9 | As revision 2 |

### Round 2 (`qa-102-design.md`, "Revision 2")

| Finding | Handling |
|---|---|
| R2-H1 | Measured (R1). The user chose D15 (a): cleartext allowed, the server rule in code, a source scan in `ServerTransportTest` (§3.1). The emulator checks go through hostnames |
| R2-H2 | Measured (R2-R4). `NETWORK_STACK` is the guard; `getLaunchedFromUid` dropped (§3.2) |
| R2-M1 | Portal sites and leaves; frames judged in SIR (R6); `visited_count`; the true residue in §7 and D3 |
| R2-M2 | The page's own moves (`!hasGesture()`, measured R5) allowed from portal sites; fake-portal modes and E5 |
| R2-M3 | Two operators; checked afresh at each opening; the client's rules named (§3.3) |
| R2-M4 | The pause's end in the snapshot; Vibb One's "close now" (§3.3) |
| R2-L1 | `DONT_KILL_APP` (§3.2) |
| R2-L2 | The latest date; one window at a time; `grant` steps capped at 3 a day; a fresh install takes `grant` as used (§3.4) |
| R2-L3 | `wifi_signin_wide` counts blocked frames only (§3.7) |
| R2-L4 | Tightening by set difference; "unknown" clears once; the pause needs no clear; the cost under the switch (§4.1, §5.2) |
| R2-L5 | The snapshot carries `classifyDomain`'s real inputs; `chrome://policy` read with a test bundle (§3.3, D-C3) |
| R2-L6 | E1/E3 by hostname, E2 with a second app, E4's walled garden, E5's modes, E9's pause window (§11) |

# Design 102: Wi-Fi sign-in pages and in-app logins without free Chrome

Status: design, **revision 5**, 2026-11-15 (branch `design-102`). Not built. Revision 1 was b724662cd, revision 2
3fb9ac2bf, revision 3 7ceedfb81, revision 4 337c8e52a.

**Revision 5** takes the third design QA (`docs/review/qa-102-design.md`, "Revisions 3-4": 0 High, 4 Medium, 6 Low)
and two decisions of the user (2026-11-15). What changed:

- **The parent code for every Wi-Fi sign-in and every reopen, by default (the user; R3-M4; §3.2).**
  - A PWA switch turns the requirement off. It sits on the phone's **Avansert** page (`#wifi-signin`).
  - The code is checked by the launcher's offline `ParentPin` with a new one-shot entry `WIFI_SIGNIN`, in the main
    process. The order is the network, then Vibb's https check, then the code (or a grant, or a pause), then the
    budget, then the window.
  - No parent code set fails closed.
  - The entry becomes a visible activity under the same `NETWORK_STACK` guard.
  - The contract gains `captive_portal.require_code` (an older server reads as true) and a session's `approved`.
    Migration 0150 adds the column, default 1.
- **D16 = yes (the user)**, and **D17 (recommended, with the user now)**: a correct code opens regardless of the day's
  budget, still 10 minutes per opening and reported. The budget and the grant govern code-free openings, and the grant
  button says it needs the phone online.
- **R3-M1**: the source scan on the music app's `SourceScanTest` pattern, with a token list and named exceptions
  (today's `AppEnforcer` WebView call included). Library HTTP is caught by the dependency review.
- **R3-M2**: a chain that began with a tapped GET may not leave the tapped link's site, which closes a portal site's
  same-site redirector. A POST's chain may. The window tracks each chain itself, so it doesn't rest on Chromium's flags (R7, measurement owed in E5).
- **R3-M3**: the reopen keeps to the first page's site only, and its record is never logged.
- **The Lows**:
  - both network-security-config files;
  - an origin fence on the tailnet path, and a https interceptor in the DoH client;
  - the reopen's network identity (records go at boot; the boot count; the SSID read at the session's end; the
    copied-SSID residue);
  - the check's server probe named;
  - "one window" read from the session file;
  - where each PWA control lives.

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
   - **Each sign-in and each reopen needs the parent code on the phone by default.** The parent can turn that off on
     the phone's Advanced page in the PWA (§3.2, §3.7).
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
Home; a Capport (RFC 8908) network whose login app chooses a Custom
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

Vibb One's network security config refused all of it, and a config names hosts, never ranges.

**S-M1's history, honestly.** The security review's S-M1 (2026-11-04) was about the **server path**: the phones'
server address must be `https://`, or `http://` only to a Tailscale IP literal. That rule lives in code:

- `serverUrlVerdict`;
- `ServerTransport`'s `Closed`/`NOT_HTTPS`, which refuses before the bearer or a socket;
- the shared vectors `server_url_vectors.json`.

The app-wide `cleartextTrafficPermitted="false"` was a **second safety net** under it. The user, 2026-11-15: the
intent was the server path. So the net goes, and the rule stays. The music app has run this way since qa-30-s3b.

**The configs, both files (R3-L1).**

- `src/main/res/xml/network_security_config.xml` and `src/debug/res/xml/network_security_config.xml` both get
  `base-config cleartextTrafficPermitted="true"` with `tools:ignore="InsecureBaseConfiguration"`, as the music app
  has.
- Trust stays **system CAs only**. The release config never trusts a user CA; the debug config keeps its
  `debug-overrides`.
- The `tailnet-server.vibb.invalid` domain-config stays: the tailnet client names its host by it. The debug
  `10.0.2.2` entry is moot and can go.
- The configs' comments, `ServerTransport.kt`'s file comment and `docs/notes/launcher/security.md`'s "Cleartext" are
  rewritten for D15.

**What in the launcher opens network connections today** (read 2026-11-15, verified by the third QA client by
client), each https by code:

| Connection | Where | Why it is never http |
|---|---|---|
| The server API, the SSE stream, the music files the launcher fetches | `MdmApi`, `CommandListenerService`, `music/MusicServer.kt`, all children of `ServerClients.base(path)` | `ServerTransport`: `serverUrlVerdict` gives https, tailnet http (an IP literal through tsnet) or closed. Pinned by `ServerTransportTest` |
| Catalog APKs, the self-update, app logos, weather, calendar feeds, the music library | through `createMdmApi(serverUrl, …)`, the same transport | the same rule. The server fetches MET and the feeds itself |
| The DNS filter's DoH upstream | `buildDohClient` (`UpstreamTransport.kt`) | `CustomUpstream` takes only `https://`, and the providers are constants. **New (R3-L2): an interceptor in `buildDohClient` refuses a non-https URL**, so it is the client's own rule, not its inputs' |
| The DoT upstream, the bootstrap lookups | raw sockets (`SSLSocketFactory`, UDP) | not HTTP; the config never applied |
| tsnet (Tailscale) | Go, inside the `.aar` | its own TLS and Noise |
| MMS | `SmsManager`, the system's MMS service | not under Vibb's config |
| The sign-in window's https check | new, `portal/PortalCheck.kt` | fixed `https://` constants. Its probe of the phone's own server is a plain request with no bearer and no design-76 pinner, not through `ServerTransport` (R3-L4) |
| The sign-in window's WebView | new, under `portal/` | **the one place that may speak http**, by design |

**The tailnet branch gets an origin fence (R3-L2).** `direct()` refuses any other scheme, host or port. The tailnet
branch of `toServer` passed another host's request through, which the config used to refuse: an IP-literal `@Url`
reaches tsnet's proxy, so only tailnet peers, over http, with no bearer. The same origin interceptor goes on the
tailnet branch: one line, and one `ServerTransportTest` row.

**The source scan (R3-M1)**, in `ServerTransportTest`, on the music app's `SourceScanTest` pattern:

- `code()` strips comments, so the scan only sees code.
- It covers every `.kt` under `launcher/app/src/main/java`.
- It has a token list, with **one named exception per file**:

  | Token | Allowed only in |
  |---|---|
  | `OkHttpClient(`, `OkHttpClient.Builder(` | `ServerTransport.kt`, `UpstreamTransport.kt`, `portal/PortalCheck.kt` |
  | `.newBuilder()` on a client | `MdmApi.kt`, `CommandListenerService.kt`, `music/MusicServer.kt` (from `ServerClients.base(path)`, as today's test pins) |
  | `Retrofit.Builder(` | `MdmApi.kt` and `music/MusicServer.kt`, each with `.client(` |
  | `java.net.URL`, `URLConnection`, `openStream(` | nowhere |
  | `DownloadManager`, `HttpEngine` | nowhere |
  | `setDataSource(` | `KidSounds.kt`, `LocateCommands.kt` (a content or alarm `Uri`) |
  | `WebView(`, `android.webkit` | under `portal/`; `android.webkit` also in `AppEnforcer.kt`, for `getCurrentWebViewPackage` only |
  | `bearerInterceptor(` | the server clients only |

  A new token or file fails the build until it is reviewed and named.
- **What a source scan can't see**: a **new library** that does its own HTTP. That gate is the existing dependency
  review: `gradle/verification-metadata.xml` fails the build on any new or bumped dependency until its checksums are
  added and reviewed. §3.1 and `security.md` say so.
- The test keeps its other checks: every server client takes its way from the transport, the release config has no
  user CA, and the vectors.

**The `:portal` process** (revision 2's design):

- the window runs with `android:process=":portal"`;
- `Application.onCreate` gets an `isPortalProcess` guard next to `isBootCoverProcess`, so `:portal` skips the main
  process's start-up;
- `WebView.setDataDirectorySuffix("portal")` is set before the first WebView;
- the window's state comes in a snapshot file the main process writes (`noBackupFilesDir/portal/state.json`);
- the session goes back by a file in `noBackupFilesDir/portal/sessions/` and an explicit broadcast to a non-exported
  receiver.

### 3.2 Vibb becomes the handler, and the parent code (R3-M4, the user's decision)

- **The entry, `CaptivePortalEntry`**, in Vibb One's main process.
  - Its filter is `android.net.conn.CAPTIVE_PORTAL` + `DEFAULT`, guarded by
    **`android:permission="android.permission.NETWORK_STACK"`** (R2-H2). system_server's start passes (R2), and another
    app's is refused (R4). `getLaunchedFromUid()` is not used: it is `-1` for the system's start (R3).
  - **It is a visible, translucent activity** (R3-M4), not a theme-less trampoline. It shows "Sjekker nettet …" /
    "Checking the network …", then the keypad when one is needed. A `Theme.NoDisplay` activity must finish before
    `onResume`, so it couldn't host either.
  - It keeps the `CaptivePortal` binder from the extras and hands it on.
- **The order, on every way in** (pure `portalApprove`, `login_rules.json` `approve` rows):
  1. **The network**: Wi-Fi, `CAPTIVE_PORTAL`, not `VALIDATED`. For a reopen (§3.8): a matching record.
  2. **Vibb's https check** (§3.3), for a sign-in. A pass means the network works: "Nettet virker allerede" / "The
     network already works", and no code is asked.
  3. **The approval**:
     - **the parent code**, when the policy's `captive_portal.require_code` is true (the default);
     - or **a grant** from the PWA (D14);
     - or **a running PIN pause** (everything is lifted);
     - or **nothing needed**, when the parent turned the requirement off.
  4. **The day's budget** (§3.4), for code-free openings only (D17).
  5. **One window at a time.**
  6. Then the opening is charged and the window starts.
- **The parent code** (`ParentPin`, the launcher's existing offline check). It is asked in the main process, where the
  vault, the lockout store and the Keystore key live, never in `:portal`.
  - **A new entry `ParentPinEntry.WIFI_SIGNIN("wifi_signin")`, one-shot** (`windowDuration` null). A match approves
    **exactly this opening, on this network**, and nothing after: the one-shot rule for parent overrides.
  - The escalating lockout is shared with every other entry, so the keypad can't be used to guess around the Settings
    gate's lockout. Every try is queued as a PIN event and synced later.
  - The keypad's title is "Foreldrekode for Wi-Fi-innlogging" / "Parent code for Wi-Fi sign-in", and it is the same
    keypad as the other gates. The reopen row (§3.8) asks the code too.
  - **The server** gets `Entry::WifiSignin` in `parent_pin.rs` and its words in `parent_events.rs` ("Foreldrekode for
    Wi-Fi-innlogging" / "Parent code for Wi-Fi sign-in"), in the same commit. An older server skips those events with a
    warning (`handlers/parent_pin.rs:217`), so a parent on an older server doesn't see wrong codes typed there.
  - **The states**:
    - `Ok` opens.
    - `Wrong` and `LockedOut` show as at the other gates.
    - **`NotConfigured`** (no parent code set) **fails closed**: the keypad says "Ingen foreldrekode er satt – spør en
      voksen" / "No parent code is set – ask a grown-up". Only a grant or the requirement turned off opens, and the
      Advanced page's row warns (§3.7).
    - `ForeignAccessOn` is refused, as at every gate.
    - **`Unavailable(untilSync)`** (the Keystore key failed; a re-wrap needs a sync) is **named residue**. On a phone
      whose only network is this captive Wi-Fi, the sync needs the sign-in: a deadlock. It is rare (a Keystore fault),
      the same as for the pause, and the requirement turned off in the PWA (reached once the phone has any network)
      ends it.
  - **Cancel** is outcome `closed`, with `reportCaptivePortalDismissed()` so Android keeps its notification.
- **The binderless start**: Vibb's Wi-Fi page and Home line open the same entry by an explicit intent from our own
  process (the same uid passes the guard), with no binder, and go through the same order.
  - The window first loads Android's probe URL (`http://connectivitycheck.gstatic.com/generate_204`).
  - It re-probes through `reportNetworkConnectivity`, which is best effort (E7).
- **One window at a time, across a main-process crash (R3-L5).** "A window is open" is read from the session file in
  `noBackupFilesDir/portal/` (written at the window's start, closed at its end), not from memory. An entry after a
  main-process restart brings the open window forward and starts none.
- **The persistent preferred activity**:
  - it is added in `applyHeld` and in `reseatPreferredActivities`;
  - off is `setComponentEnabledSetting(entry, DISABLED, DONT_KILL_APP)`;
  - the stock login app is never pinned.

### 3.3 The window

- **Its state** comes in the main process's snapshot file:
  - the filter's on/off;
  - the pause's end;
  - `minutes` and today's time left (for a code-free opening);
  - the opening's kind: `sign_in` or `revisit`, with the recorded first site;
  - the check hosts;
  - `classifyDomain`'s inputs (the compiled list, the Spotify flag, the secure-DNS hosts).

  The parent's allowed domains are already out of the compiled list (to confirm in the build). The snapshot carries no
  code and no approval state: the main process approved before the start.
- **Its result**: the session file and an explicit broadcast. **Vibb One → the window**: "close now" on the PIN lock,
  on Part A turned off, and at a pause's end, to a non-exported receiver in `:portal`. The snapshot's pause end is the
  fallback if the message is lost.
- **The look**:
  - Vibb's kid page header, "Logg på Wi-Fi" / "Sign in to Wi-Fi", the network's name, and the current host
    (read-only);
  - a close button; BACK steps back and closes on the first page;
  - no address bar, no menu.
- **Vibb's "the network works" check** (`portalPasses`, `portal/PortalCheck.kt`). It runs at the entry, at the
  window's start and every 5 s, on the bound network.
  - The client: OkHttp with `Network.socketFactory`, system trust, no proxy, no redirects. The hosts are fixed
    constants:
    - `https://www.gstatic.com/generate_204` and `https://cp.cloudflare.com/generate_204`, each wanting an exact 204
      with an empty body;
    - the phone's own server when its address is a public `https://` URL (any answer with a valid chain; a plain
      request, no bearer, no pinner).
  - **A pass needs two operators**: Google's and Cloudflare's, or either and the server. A family whose server is
    tailnet-only therefore needs both Google's and Cloudflare's hosts (R3-L4).
  - A pass closes a sign-in window (`passes`). It is checked afresh at each opening.
- **Where the page may go** (pure `portalNavigation`, `PortalRulesTest`):
  - **Schemes**: http and https only. `tel:` goes to `ACTION_DIAL` with Vibb's dialer. Everything else is refused in
    place.
  - **Sites.** A site is the registrable domain (OkHttp's `topPrivateDomain()`; an IP literal is its own site).
    - **Portal sites** are the first page's site, plus every site reached from a portal site by a form post. A
      main-frame POST is seen in SIR (R5).
    - Every other site is a **leaf**.
  - **Each main-frame navigation's chain** (R3-M2). The window tracks it itself, so it doesn't rest on how Chromium
    carries flags across redirects:
    - A navigation **starts** with a GET seen in SOUL (with `hasGesture()` true for a tap, false for the page's own
      code or a meta refresh, R5), or with a POST seen in SIR (from the page).
    - Its **redirect hops** (SOUL with `isRedirect()`) belong to that start.
    - **Measured (R7, the emulator check of 2026-11-15, emulator.md §6ap)**: `hasGesture()` is false on the redirect
      hops of a tapped link and of a form POST, so the window's own chain tracking is what holds. A POST answered
      with **307/308 reaches neither hook**, and neither does a history move: such a main-frame load is judged at its
      commit (`onPageStarted`) - the filter's lists, and on another site as a redirect hop of the chain in flight -
      and, refused, stopped with the window stepping back. The POST itself has reached the target by then (no page
      is shown).
  - **Main-frame moves to another site**:
    - **A chain that began with a tap on a GET may not leave the tapped link's site**: any hop to another site is
      refused, whatever the hop's own flag says. This closes a portal site's same-site redirector
      (`google.com/url?q=`, `l.facebook.com/l.php`, `youtube.com/redirect`).
    - **A chain that began with the page's own move** (meta refresh, script, a 302 of a non-tap load) **from a portal
      site** may go to another site. So may a chain that began with **a POST from a portal site**. The site reached by
      a POST becomes a portal site; one reached otherwise is a leaf.
    - **To a portal site**, from anywhere: a leaf may hand back.
    - Refused otherwise, with "Denne lenken går ut av innloggingssiden" / "This link leaves the sign-in page".
    - Within the current site, taps are free.
  - **Sub-frames** never reach SOUL (R6). SIR judges each frame document (`isForMainFrame() == false`, an `Accept`
    that starts with `text/html`). A frame of the top page's own site or of a portal site is allowed; any other gets a
    blocked page.
  - **The filter's lists** apply to every request's host in SIR. Blocked frames are counted.
  - **Not seen by these hooks** (§7): a sub-resource's redirect target (R6), WebSockets, WebRTC, the in-page
    navigation of app-like sites, and the parent's own DNS upstream. Service workers are off.
  - **The WebView's settings** as revision 2 (§3.2 there): no file or content access, no JS interface, Safe Browsing,
    no new windows, no downloads or file chooser, every permission refused, no mixed content, TLS errors final, the
    selection menu trimmed.
- **Closing**:
  - `validated` (with `reportCaptivePortalDismissed()`) or `passes`: a sign-in only;
  - `lost`, `closed`, `closed_by_vibb`;
  - `timeout` after 10 minutes from the opening, for every opening, code ones included;
  - `budget`, for code-free openings.
- **A pause or the override** lifts the window's limits while it lasts. At the pause's end the window closes.

### 3.4 The day's budget and the grant (D2, D14, D17)

- **A correct parent code opens regardless of the day's budget** (D17, recommended). The parent is there, and a
  Wi-Fi-only phone can't receive the PWA's grant before it is signed in. Such an opening still ends after 10 minutes
  and is reported with `approved: "code"`. It doesn't use up the code-free budget.
- **The budget governs code-free openings**: the requirement turned off, or a grant. That is at most **4 openings and
  20 minutes a day**, kept by the main process as revision 3 has it:
  - charged at the entry;
  - the latest date seen, so a clock set back gives nothing;
  - the whole `minutes` charged when no session came back;
  - `wifi_signin_budget` when it runs out.
- **The grant** ("Gi én innlogging til nå" / "Allow one more sign-in now") is one code-free opening of 10 minutes, at
  most 3 a day.
  - A fresh install takes the current `grant` as used.
  - The button's sub-line says it needs the phone online: "Virker når telefonen har nett, for eksempel mobildata" /
    "Works once the phone has a connection, for example mobile data".
- The PIN pause lifts the budget and the code while it lasts.

### 3.5 Under a time rule (D4)

The window opens during a time rule too (a hotel at bedtime), with the code as always. During the PIN lock there is no
way in.

### 3.6 The kid's ways in

1. **Android's notification** (M1), folded in the Silent bundle.
2. **Vibb's Wi-Fi page**: the connected row of a captive network, "Tilkoblet – må logges på" with "Logg på".
3. **Home's line (D5)**, "Wi-Fi-en vil at du logger på".
4. **After the sign-in, the Wi-Fi page's** "Åpne påloggingssiden" (§3.8).

Each leads to the entry: "Sjekker nettet …", then the keypad (with the code required), then the window. With the
requirement off and the budget spent, the line reads "Spør en voksen om Wi-Fi-innlogging".

### 3.7 The parent's side (Part A)

- **On `/devices/{id}/logins` (§5)**:
  - Part A's switch (D1), with its sub-line: "Vibb viser innloggingssiden til nettet, uten adressefelt, og lukker den
    når nettet virker. Nettfilterets lister gjelder, ikke din egen DNS-tjener." / "Vibb shows the network's sign-in
    page, with no address bar, and closes it once the network works. The web filter's lists apply, not your own DNS
    server."
  - Under it, the code's state: "Krever foreldrekode – endres under Avansert" / "Needs the parent code – change it
    under Advanced", with a link to `/devices/{id}/advanced#wifi-signin`.
  - The recent sign-ins (D6), each with how it was approved.
  - Today's use, and the grant button.
- **On `/devices/{id}/advanced` (the user's placement)**: its own section `<section id="wifi-signin">` with one
  autosave switch row:
  - "Krev foreldrekode for Wi-Fi-innlogging" / "Require the parent code for Wi-Fi sign-in", on by default;
  - sub-line "Gjelder også å åpne påloggingssiden igjen" / "Also for reopening the sign-in page";
  - while the phone reports no parent code set: "Ingen foreldrekode er satt – innlogging virker bare med «Gi én
    innlogging»" / "No parent code is set – sign-in works only with «Allow one more sign-in»";
  - without the capability: "Krever nyere Vibb One" / "Needs a newer Vibb One".

  The save is the Advanced page's own form (`data-autosave`, `section=wifi_signin`), handled by the logins module's
  handler and answered with a `303` to `#wifi-signin`. The no-jump rule holds.
- **Activity events**, as revision 3:
  - `wifi_signin_wide` and `wifi_signin_budget`, keyed by `opened_ms`;
  - pushed, held at night;
  - plus the PIN events of `wifi_signin` through the existing parent-code event path.

### 3.8 Reopening the sign-in page (D16, yes)

- **What is recorded.** When a sign-in ends `validated` or `passes`, the main process keeps one record per network, in
  `noBackupFilesDir/portal/revisit.json`. The record is **never logged**: no logcat, no report beyond `first_host`, and
  no backup (`allowBackup="false"`). It holds:
  - the first portal page's URL;
  - **only that URL's site** (R3-M3): the portal operator's own page, where log-out and time left live, never a site
    reached later by a form post (Facebook, Google, a payment provider);
  - the network's identity: the SSID read **when the session ends** (Location is turned on for that read, as the Wi-Fi
    page does), and the `Network` handle **with the boot count** (R3-L3; netIds restart at each boot);
  - the local date.

  Records go at the day's end, at boot, and when Part A is turned off.
- **The row** on Vibb's Wi-Fi page: "Åpne påloggingssiden" / "Open the sign-in page", sub-line "Logg av eller se hvor
  lenge du har igjen" / "Log out or see time left".
  - It shows only when the connected network matches:
    - the same `Network` handle with the same boot count, whose SSID, when both are known, is also the same;
    - or, after a reconnect, the same SSID.

    A reboot never carries a record across, since the records go at boot.
  - Nothing to reopen on a network that was never captive.
- **The mode** (`revisit`):
  - **only the recorded site**, by any means (tap, redirect, script, form);
  - no auto-close by the https check or `VALIDATED`;
  - the filter's lists apply;
  - 10 minutes per opening;
  - **the parent code by default**, as a sign-in;
  - code-free reopens count in the budget;
  - reported with `start: "revisit"`.
- **H1 and the residue.** A record exists only after a session judged working, and a reopen adds no site.
  - On a rig, it gives the rig's first site again, within the same bounds.
  - **An SSID is free to copy** (R3-L3). The same day, a hotspot named like the hotel matches on the SSID. Its DNS can
    answer the recorded http host with a page of its choosing, and a reopen never auto-closes. That is D3's residue on
    a copied name, bounded the same way: one site, 10 minutes, the parent code by default, reported. §7 says so.

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
  - The logins page's switches use `autosave.js` (`data-autosave`, `section=wifi|apps` on the logins handler).
  - Add, remove and preset are form posts with a `303` to `#logins-sites`.
  - "One more" is a form post with a `303` to `#logins-wifi`.
  - The parent-code requirement is **not** on this page. It is the Advanced page's own row
    (`/devices/{id}/advanced#wifi-signin`, §3.7), and this page shows its state with a link.

### 5.1 Wi-Fi sign-in (§3.7)

The switch, its sub-line, the code's state with a link to Avansert, the recent sign-ins with the sites visited and how
each was approved, today's use, and "Gi én innlogging til nå" / "Allow one more sign-in now" with its "needs the phone
online" line.

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
"captive_portal": {"enabled": true, "require_code": true, "minutes": 10, "day_openings": 4, "day_minutes": 20,
  "grant": 0},
"login_browser": {"package": "com.android.chrome", "hosts": ["auth.dataporten.no", "school.example/login"]}
```

- Both are pinned by `policy_json_keys_snapshot` and `PolicyResponseCompatTest`.
- `login_browser` is nullable on both sides (`Option<…>`, a nullable Kotlin field).
- **Older launcher**: ignores both keys.
- **Older server**: neither key. The launcher reads `captive_portal` as on, **`require_code: true`** (fail closed: a
  parent with an older server can't turn the code off until the server is updated), with these limits and
  `grant: 0`, and `login_browser` as off.

### 6.2 The report (full-report fields)

```json
"captive_portal": {"handler": "vibb",
  "today": {"openings": 1, "ms": 240000, "grant_used": 0},
  "sessions": [{"opened_ms": 0, "closed_ms": 0, "network": "Hotel Fjord", "first_host": "portal.example",
    "outcome": "validated", "visited": ["portal.example", "nomadix.com"], "visited_count": 2,
    "blocked_hosts": [], "blocked_frames": [], "start": "system", "approved": "code"}]},
"login_browser": {"package": "com.android.chrome", "state": "on", "version_name": "133.0.6943.137",
  "written_ms": 0, "reason": null, "cleared_ms": null}
```

- `handler`: `vibb`, `system` or `other`.
- `sessions`: the last 10.
  - `visited` holds at most 20 entries; `visited_count` is the true count (R2-M1).
  - `blocked_hosts` and `blocked_frames` hold at most 10 each.
  - `start` is one of `system` (the notification), `vibb` (the Wi-Fi page or Home) or `revisit` (§3.8).
  - `approved` is one of `code`, `grant`, `pause` or `none` (the requirement off).
- `captive_portal.parent_code_set`: whether the phone has a parent code (the Advanced page's warning).
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

- `navigation`: frame or main frame, GET or POST, redirect or gesture, the chain's start (a tapped GET, the page's own
  move, a POST), from and to a portal site or a leaf, a pause, a revisit → allow or refuse. It includes the
  open-redirector chain, **a tap on a portal site's same-site redirector** (refused), **a POST's 302 to another site**
  (allowed), the meta refresh, the iframe tap, and a revisit's tap to a form-post site (refused);
- `approve`: `{kind, require_code, pin result, grant, pause, budget}` → keypad, open or refuse. This includes
  `NotConfigured` (refused unless a grant or the requirement is off), a correct code with the budget spent (open, D17)
  and a grant with the budget spent (refused);
- `frames`: an `Accept` header and `isForMainFrame` → a frame document or not;
- `passes`: answers of the check hosts → pass or not;
- `close`: validated, passes, minutes, the budget, "opened in a pause, the pause ends";
- `budget`: openings, ms, grants (a jump of 2, a fresh install), the date moving back, and a window open in the
  session file after a main-process restart (R3-L5);
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
ALTER TABLE device_policy ADD COLUMN captive_portal_require_code INTEGER NOT NULL DEFAULT 1;
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
  redirects, WebSockets and WebRTC are not seen by the WebView's hooks. A tap on a site's own redirector can't take
  the kid further (R3-M2).
  - **With the parent code required (the default)** every opening needs the parent on the spot, so this residue
    matters only where the parent turned the code off. The site rule, the https close and the 10 minutes still bound
    the window after the parent hands the phone back: the code was typed to sign in, not to open a browser.
  - **A copied SSID** can match a reopen record the same day (§3.8), within the same bounds.
  - **A Keystore fault** (`Unavailable(untilSync)`) on a Wi-Fi-only phone is a deadlock until the requirement is
    turned off from the PWA, or a grant arrives, over any other network (§3.2).
- **The window's filter** is Vibb's lists. The parent's own DNS upstream doesn't see the window's lookups, and the
  switch's sub-line says so.
- **Cleartext (D15).**
  - Android no longer refuses http in Vibb One.
  - The server path refuses it in code (`serverUrlVerdict`, `ServerTransport`, the shared vectors).
  - Every other HTTP client is https by code (§3.1's table).
  - The source scan in `ServerTransportTest` (R3-M1) fails the build on a new client token anywhere else. A new
    library's own HTTP is caught by the dependency review (`verification-metadata.xml`).
  - The tailnet path has the same origin fence as the direct path, and the DoH client refuses a non-https URL itself
    (R3-L2).
  - The one http speaker is the sign-in window in `:portal`, which holds no token and no server client.
- **Part B.** Only the parent's sites, in a Custom Tab or full Chrome.
  - Chrome is never unhidden before its lockdown reads back.
  - Each tightening ends the tabs that were open.
  - `URLBlocklist` filters navigations, not sub-resources; the DNS filter covers those.
- **The entry** is guarded by `NETWORK_STACK` (R2, R4). The window and both receivers are not exported. The parent
  code is checked in the main process, shares the lockout with every gate, and every try is reported.
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
   - The page and its saves (`section=wifi|apps|site|preset|grant`); the Advanced page's `#wifi-signin` row
     (`section=wifi_signin`, a `303` to its anchor).
   - `Entry::WifiSignin` in `parent_pin.rs` and its words in `parent_events.rs`.
   - The policy keys, the report intake, the two Activity events, the filter page's row and the Apps page's Chrome row.
   - nb + en, `login_windows.json`, tests.
2. **Launcher Part A: the window** (L).
   - The entry (visible, the keypad) and the window with the guard, the https check, the site rules with chains, and
     the reverse channel; pure `portalApprove`, `portalNavigation`, `frameDocument`, `portalPasses`, `portalBudget`,
     `wifiRowState`.
   - `ParentPinEntry.WIFI_SIGNIN` (one-shot).
   - The preferred activity in `applyHeld` and `reseatPreferredActivities`.
   - Both configs (cleartext allowed); `ServerTransportTest`'s source scan; the tailnet origin fence and the DoH
     interceptor; the `:portal` guard; the comments' and `security.md`'s rewrite.
   - The Wi-Fi page's rows (sign in, and §3.8's reopen) and Home's line; the report and the capability; nb + en.
   - `scripts/fake-portal.py`, standard library, `--selftest`, with modes: a portal; a meta-refresh portal; a script
     portal; a soft portal that passes https; a hotspot that 302s to a search page; a Google walled garden (gstatic
     passes, nothing else does); an iframe page; **a same-site redirector** (a tapped link 302s to another site); and
     **a form whose POST 302s** to another site.
   - emulator.md §6ap.
3. **Launcher Part B: the role** (M).
   - `BrowserConfig` before the hide loop, with the read-back.
   - `loginBrowser` in the plan, `loginRoleListable`, `chromeTightened` and the hide and unhide, the once-only clear.
   - The report; `LoginBrowserTest`; emulator.md §6aq.

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
  - the Advanced page's `require_code` save and its anchor, and its default 1;
  - a `wifi_signin` PIN event worded;
  - the page's anchors;
  - `policy_json_keys_snapshot` and `intake_limits`.
- **Launcher**:
  - `PortalRulesTest`: `navigation`, `approve`, `frames`, `passes`, `close`, `budget`, `wifi_rows`.
  - `LoginBrowserTest`: `bundles`, `plans`, `tighten`; the package constant; not ready → hidden.
  - `LoginWindowsTest`.
  - `EnforcementPlanTest` (two rows).
  - `PolicyResponseCompatTest`.
  - `ServerTransportTest`: the token scan (§3.1), both configs with cleartext on and system CAs only, and the tailnet
    origin row.

## 11. Checks owed

### Emulator (`jelly36`, the real launcher and server, `scripts/fake-portal.py`)

**Always through a hostname** (`nip.io`), never `10.0.2.2`, so that the bound network's own DNS lookup is tested
(R3-L1).

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
  - the iframe page's frame is refused and counted;
  - **the same-site redirector**: a tapped link that 302s to another site is refused;
  - **the form whose POST 302s** to another site is allowed, and that site becomes a portal site.
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
- E11: with cleartext allowed in both configs, the server path still refuses `http://` to a non-tailnet host (the
  vectors on the device). The source scan fails on a planted `URL(x).readText()`.
- E12: the parent code.
  - The notification leads to "Sjekker nettet …", then the keypad. A wrong code is counted in the shared lockout; a
    right one opens this opening only.
  - A second sign-in asks again.
  - With the budget spent, a right code still opens (D17).
  - With no parent code set, only a grant opens.
  - Turning the requirement off on Avansert opens without the keypad.
  - Cancel keeps Android's notification.
  - The reopen asks the code.
  - The PWA shows the `wifi_signin` PIN events.
- E13: the reopen's identity. After a reboot the row is gone. On a second hotspot with another SSID it doesn't show.

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
| D2 | Limits | **10 minutes per opening; 4 openings and 20 minutes a day per phone** for code-free openings (D17), plus "one more" (D14) |
| D3 | Moving between sites inside the sign-in window | **(i)**: only the portal's own moves (a redirect, the page's own code, a form post) from the portal's pages; links free within one site; no taps out, not even through a site's own redirector. The accepted residue: a network built on purpose to fool Vibb can send the kid to one ordinary site, where the kid can tap around within that site; at most 20 minutes a day, and the parent sees the site in Activity. With the parent code on (the default), only where the parent turned it off |
| D4 | The window during a time rule | **Allowed**, with the code as always |
| D5 | Home's line "Wi-Fi-en vil at du logger på" | **Yes** |
| D6 | What the parent sees | **Every sign-in with network, outcome, sites visited, blocked hosts and how it was approved**, and the Activity events of §3.7 |
| D7 | Chrome for logins during a time rule | **Suspended like every app** |
| D8 | Which browser | **Chrome only**, enforced by the launcher |
| D9 | The PIN pause and Chrome's URL lockdown | **Lifted with everything**, and Chrome's tabs ended at the pause's end |
| D10 | Chrome for logins on by default? | **No** |
| D11 | Presets | **Feide, Google, Microsoft, Apple** |
| D12 | A learning window | **Not now** |
| D13 | Clear Chrome's data when the role is turned on | **Yes, once, automatically**, with the cost under the switch |
| D14 | The parent's "one more sign-in now" | **Yes**, a code-free opening, at most 3 a day; its button says it needs the phone online |
| D15 | How the window may load http | **(a)**: cleartext allowed in Vibb One's configs. The server path stays https (or http only to a Tailscale IP), enforced in code; the source scan and the dependency review keep the rest (§3.1) |
| D16 | Reopening the portal's page after the sign-in | **Yes** (§3.8): the first page's site only, the parent code by default |
| — | **The parent code for each sign-in and reopen** | **Yes, by default** (the user, 2026-11-15). A switch on the phone's Advanced page turns it off (§3.2, §3.7) |
| D17 (with the user) | With the code required: does a correct code open past the day's budget? | **Recommended: yes.** A right code opens whatever the day's count, still 10 minutes per opening, reported with `approved: "code"`. The budget and the grant govern only code-free openings, because the PWA's grant can't reach a phone whose only network is the hotel Wi-Fi. If the user wants the budget to bind code openings too, the code must at least act as the offline "one more" when the budget is spent |

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

### Round 3 (`qa-102-design.md`, "Revisions 3-4")

| Finding | Handling |
|---|---|
| R3-M1 | A token scan on the music app's pattern, with one named exception per file (`AppEnforcer`'s `getCurrentWebViewPackage` included); a new library's HTTP is caught by the dependency review (§3.1) |
| R3-M2 | Each main-frame chain is tracked from its start. A tapped GET's chain may not leave the tapped link's site; a POST's chain or the page's own move from a portal site may. The window remembers each chain's start; Chromium's flags on redirect hops are owed in E5 (R7). Fake-portal modes and E5 (§3.3) |
| R3-M3 | The reopen keeps to the first page's site only. Its record is never logged and is kept in `noBackupFilesDir` (§3.8) |
| R3-M4 | The parent code on `ParentPin` with `WIFI_SIGNIN` (one-shot), asked in the main process. The order: network → https check → code, grant or pause → budget → window. `NotConfigured` fails closed; the Keystore residue is named. The entry is visible. `require_code` (an older server → true), `approved`, the column default 1, the `approve` rows, the Avansert row, and D17 (§3.2, §3.4, §3.7, §6) |
| R3-L1 | Both config files, `tools:ignore`; the hostname rule's reason; §1's stale line; the comments rewritten (§3.1, §11) |
| R3-L2 | The tailnet branch's origin fence and the DoH client's https interceptor (§3.1) |
| R3-L3 | Records go at boot; the handle with the boot count; the SSID read at the session's end; both must match when known; the copied-SSID residue in §7 (§3.8) |
| R3-L4 | The check's server probe named and allowed in the scan; a tailnet-only server never counts (§3.3) |
| R3-L5 | "A window is open" read from the session file (§3.2, `budget` row) |
| R3-L6 | The logins page and the Advanced page, each with its own save and anchor (§3.7, §5) |

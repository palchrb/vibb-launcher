# 143 - Installed web apps on the kid's phone

Status: design note, revision 2, 2026-11-28, branch `pwa-design` (revision 1: 46669c860, on `main` at 7a6129144).
Docs only. The locked list's item 9b ("Installed PWAs on the kid's phone", moved from the inbox 2026-11-28; the owner:
"Vil vi helst ha ordentlig native pwa støtte"). Not built; step 0 (§6) and the owner's answers (§8) come first.
Revision 2 takes the design QA (2H 5M 7L, all taken, §9). Step 0's first pass (emulator, no account): §6, "Step 0 results".

Goal: the parent adds a site in the PWA, and the kid gets a tile that opens it as an app - full screen, its own icon,
working offline - that can't be used to reach the rest of the web. The first use: the family's own offline copy of a
children's encyclopedia (a couple of GB) served as a PWA from their own server, instead of a custom Kiwix build. The
condition: the site's offline store holds all of it.

## 0. Short answer

- **Chrome stays the engine in every Chrome option.** A WebAPK, Chrome's shortcut and a trusted web activity (TWA) all
  render in Chrome's process and keep their storage in Chrome's profile (§1). Chrome's managed config (`URLBlocklist *`
  plus `URLAllowlist`, design 119's lockdown) is what keeps the kid on the parent's sites, in all of them.
- **No policy installs a web app on Android.** `WebAppInstallForceList` and `WebAppSettings` are desktop and ChromeOS
  only (§1.4). A WebAPK is installed only by a person's tap in Chrome, minted by Google's server and installed through
  Play - and Play is suspended outside a pause.
- **Recommendation: option C, no install at all.** Vibb One opens each parent's site itself as a trusted web activity
  (androidx.browser, already a dependency): full screen with no address bar once the site names Vibb One in its
  `/.well-known/assetlinks.json`, Chrome's normal storage, Chrome out only while a web app or a login is in front
  (design 119's window, made one record per window, §3.3). No Play, no Google minting, no new package to trust, no
  pause needed. The tile shows at the next sync. A WebAPK adds only what this use doesn't need (an entry in Android's
  app list, notifications under its own name, a share target) and is measured in step 0 so the owner can switch (Q1).
- **The offline store is Chrome's quota**: up to 60% of the disk per origin, best-effort unless `persist()` is
  granted, evicted only when the disk is nearly full (§1.5). A couple of GB fits. Whether a TWA's `persist()` is
  granted is the main unknown (step 0, T3).
- **The site must be reachable by Chrome itself**: on the internet or the home network, not over the tailnet - tsnet
  lives inside Vibb One's process, so Chrome never sees it (§3.7).
- Size **M** (server S, launcher M), about two rounds plus an emulator gate on Play images and a check on the Jelly.

## 1. What Android and Chrome offer

### 1.1 WebAPK

- Chrome asks Google's minting server for a real APK built from the site's manifest and installs it "via Play Store or
  download" ([Chromium, Android web app architectures][arch]). Used by Chrome on phones with Google Mobile Services
  ([web.dev, WebAPKs][webapks]). Chrome checks that a WebAPK is signed by the minting server.
- The page runs in Chrome: `WebappLauncherActivity` routes the launch to `SameTaskWebApkActivity`/`WebappActivity`
  (a nearly UI-less Chrome, a `BaseCustomTabActivity`), in a task of its own ([arch]). Cookies, storage and the service
  worker are shared with Chrome's profile; clearing Chrome's data clears the app's ([webapks]).
- It declares a `VIEW` filter for its scope (links into it open the app), appears in Android's app settings, and Chrome
  hands its notifications to it so they look like the WebAPK's ([webapks], [arch]).
- Updates: on launch, at most once a day, Chrome fetches the manifest; a changed one is re-minted and applied only when
  all its windows are closed and the phone is charging on Wi-Fi ([web.dev, manifest updates][mupd]).
- There is no API to start an install: it is the "Install app" menu item or the site's install prompt, a tap in Chrome.

### 1.2 Chrome's fallback: a shortcut

When minting is unavailable (no Play, the server down, the manifest not good enough), Chrome adds a shortcut that
opens the site in Chrome's standalone window, with a Chrome badge on the icon ([web.dev, installation][inst]). It is a
pinned shortcut (`ShortcutManager.requestPinShortcut`), which the default launcher must accept
([`LauncherApps.PinItemRequest`][pin]). **Vibb One is the Home app and accepts no pin requests today** (no
`ACTION_CONFIRM_PIN_SHORTCUT` in the manifest), so on our phones the fallback adds nothing (design 102's D-C4 expects
exactly that). Chrome's launch intent carries a code only Chrome makes, so Vibb can't open the standalone window
without the shortcut.

### 1.3 Trusted web activity (TWA)

Any app can open a site in Chrome as a TWA: a Custom Tab with no browser UI, once Chrome has verified a Digital Asset
Links statement on the site that names the app's package and signing certificate ([Android, TWAs][twa]; [Chrome, TWA
guide][twaguide]). No new APK is made; Chrome verifies on the phone, fetching the site's `assetlinks.json` itself, and
caches the result, so a verified site opens verified offline ([chromium-dev thread][dalcache]; [offline-first][twaoff]).
The statement covers the whole origin; a failed check falls back to a Custom Tab with the read-only host in a toolbar
([arch]). A TWA needs a bound Custom Tabs session (`TrustedWebActivityIntentBuilder.build(session)`). Storage is
Chrome's profile, the same as a WebAPK's ([arch]). A Custom Tab runs in the calling app's task, and under lock task
needs Chrome pinned (design 102 M11, M12).

### 1.4 Chrome's enterprise policies (Chromium's policy YAMLs, `supported_on`, read 2026-11-28 [pol])

| Policy | Android | Use here |
|---|---|---|
| `WebAppInstallForceList`, `WebAppSettings` | **no** (desktop, ChromeOS) | none: no forced install |
| `DefaultBrowserSettingEnabled` | no | none (Vibb One holds the browser role, design 119) |
| `URLBlocklist`, `URLAllowlist` | 86+ | the lockdown: the parent's sites; a path is a string prefix (§3.2) |
| `DownloadRestrictions` | 131+ | already in design 119's bundle |
| `DefaultNotificationsSetting`, `NotificationsAllowedForUrls` | **142+** | web push for the parent's sites only, if wanted (§3.8) |
| `PersistentQuotaEnabled` | 106-107 only | gone; no policy grants persistent storage |
| `AllowDeletingBrowserHistory` | future only | the kid can clear Chrome's site data from Chrome's settings, if Chrome's UI is reached (§3.4) |

### 1.5 Storage

- Chromium lets the browser use up to 80% of the disk and **one origin up to 60%** ([web.dev, storage][storage]).
- Data is best-effort by default: under storage pressure Chromium evicts whole origins, least recently used first;
  persistent data is not evicted. "Data is very rarely cleared automatically by Chrome" ([storage]; [persist]).
- `navigator.storage.persist()` never prompts in Chrome: it is granted when the site is "important" - high engagement,
  installed or bookmarked, or allowed to notify ([persist]). An installed WebAPK should pass; a TWA and a shortcut are
  not named, though a notification grant may carry a TWA there (step 0, T3).
- Chrome keeps site storage in its app data, so Android's cache trimming should not touch it (T4).

### 1.6 Vibb's own WebView

A WebView runs service workers, Cache Storage and IndexedDB with the data in our app. It has no Push API or
notifications and no install, and Google refuses its sign-in in embedded WebViews ([Google, OAuth in native
apps][oauth]). Vibb would own the navigation rules (design 102 Part A's `portalNavigation` shows the work) and every
gap that Chrome's policy fills today.

## 2. The options

| | A. WebAPK | B. Chrome shortcut | C. TWA from Vibb One | D. Vibb WebView |
|---|---|---|---|---|
| Install | by hand in Chrome, in a pause (Play must run) | by hand in Chrome; Vibb must accept the pin request (new) | none: the tile comes with the policy | none |
| Google | manifest sent to the minting server; Play installs | none beyond Chrome | none beyond Chrome (T11) | none |
| Full screen | yes (`standalone`) | yes | yes once `assetlinks.json` names Vibb One; else a host toolbar | yes |
| New package to trust | yes: `org.chromium.webapk.*`, Google's signer; Vibb must allow it by its start URL and pin the signer | no | no | no |
| Offline store | Chrome's; `persist()` likely granted | Chrome's; likely | Chrome's; **unknown** (T3) | ours; quota unknown (T3) |
| Web push | yes, under its own name, Chrome must stay out | yes, as Chrome | yes, as Chrome or delegated to Vibb (§3.8), Chrome must stay out | no |
| Time rules, groups | its own package, or Chrome's activity (T7) | Chrome | Vibb knows each window | Vibb |
| Works without Chrome | no | no | no | yes |
| Cost | M + the trust rule + Play's windows | M + a pin-request host | M | L (an engine to own) |

All of A-C need Chrome unhidden while the site runs: a hidden package is not installed for the user, so its WebAPK,
shortcut or TWA can't start.

**Why C.** It gives the kid the same thing (a tile, full screen, offline, the same Chrome storage) without the parts
that fight our setup: Play suspended, a tap in Chrome during a pause, a Google-signed package that Vibb would have to
let past `enforceOnNewPackage`, and the manifest of a family's private server sent to Google. The owner controls the
site, so the one file it needs is theirs to add. It reuses design 119's machinery: the Custom Tabs session, Chrome's
bundle and read-back, unhide-then-launch, hide-at-the-end - with the window made one record per window (§3.3). **The
flip condition**: if step 0 shows `persist()` denied in a TWA (with and without a notification grant) but granted in a
WebAPK, and eviction at a free space a real phone reaches (T3, T5), A is the better engine for a large offline site,
and this note gets a revision. D waits until a phone without Chrome needs it.

## 3. The design (option C)

### 3.1 The parent's list

- Per phone, like the login sites (`login_role_sites`): a new table `web_apps` (`device_id`, `url`, `name`,
  `position`), the next free migration. `https://` only; an entry is an origin plus an optional path (its scope), read
  by the same entry rule as the login sites (`loginEntry`, the shared `entries` rows).
- The policy gains `web_apps: [{url, name}]` (absent while empty), pinned by `policy_json_keys_snapshot` and
  `PolicyResponseCompatTest`; a new `contracts/core/web_apps.json` (the entry rule's rows, the Chrome allowlist rows and
  the window rows of §3.3) with its README row, both sides in one commit.
- The server never fetches the parent's URL (nothing on the server reaches a typed address). The phone reads the
  site's manifest for the icon (§3.3).

### 3.2 Chrome's bundle

- `browserConfigPlan` treats "web apps listed" like the login role: the lockdown is in force when either is on, and
  `URLAllowlist` is the login sites, the web app entries and `LOGIN_CAPTCHA_PATHS`.
- **A path in `URLAllowlist` is a string prefix**, so a scope is written with a trailing `/` (`example.org/app/`, not
  `example.org/app`, which would also allow `/apple`). The site's `assetlinks.json` verifies the whole origin; the scope
  is kept only by the allowlist.
- Ready means the bundle read back typed (`sameBundle`), as today; not ready hides the tiles.
- **Removing a web app site is a tightening** (Chrome's tabs end, design 119 Q102-M4): it ends every open window,
  a login's too, as a removed login site does today.
- **Chrome's one-time clear (D13) is keyed on the lockdown's first turn-on, not the login role's**:
  `chromeClearDue(lockdownOn, cleared)` (today `chromeClearDue(roleOn, cleared)`, `LoginBrowser.kt:193`, called from
  `BrowserConfig.kt:108`), where `lockdownOn` = the role or web apps listed. So a phone that gets web apps first is
  cleared then, before any content, and turning the login role on later doesn't wipe a filled site. A ticked Chrome
  still forgets the record, so unticking it clears again (§3.5, §5).
- **Chrome ticked as a free app** (the lockdown off, design 119): the tiles still open the sites as TWAs in the free
  Chrome; no window is kept and nothing is hidden, since Chrome is out anyway. The server keeps the login role and a
  ticked Chrome exclusive today; web apps stay allowed with a ticked Chrome.

### 3.3 The tile and the windows

- **The tile**: on Home like an app, the parent's name for it, the icon from the site's manifest; until then a letter
  tile. **The icon fetch is not under the DNS filter** (Vibb One excludes itself from its own VPN,
  `KidVpnService.kt:303`), so it applies the filter's rules itself: https only; `classifyDomain` on the host before the
  fetch and nothing fetched if it is blocked; redirects only within the same origin; the start page read to 64 KiB for
  its `<link rel=manifest>`, the manifest to 64 KiB, one icon (the nearest 192 px) to 256 KiB; cached in
  `noBackupFilesDir`.
- **The launch**: an anchor activity of Vibb One per site (`documentLaunchMode="intoExisting"`, its own task and
  Recents entry with the site's name and icon). A tap unhides Chrome under the window lock, binds the Custom Tabs
  session, and opens the site with `TrustedWebActivityIntentBuilder` (`androidx.browser.trusted`, in 1.9.0). Before the
  first open, `validateRelationship(RELATION_HANDLE_ALL_URLS, origin)` records whether the site is verified. A second
  tap while the window is open brings the task back where the kid left it.
- **No session, no web app window.** Every tap after a window's end is a cold Chrome start, and design 119's gate saw
  the 2 s bind miss on 16. So the bind is retried for up to 10 s (a quiet spinner on the anchor); if it still fails,
  the site opens as a plain Custom Tab (the host shown) with a 30-minute ceiling, the anchor's own end (below) and
  `session: false` in the report. T1 measures the cold path.
- **Lock task**: Chrome is pinned while the lockdown is ready, as for the login role. The anchor is visible when it
  starts the TWA, so Android 14's background-start rules are met.
- **One record per window** (today `LoginWindow` holds one: one `KEY_RECORD`, one `graceSince`, one set of anchors and
  one ceiling, and "a second link keeps the first's opening"). Revision: a set of records, each `{kind: login |
  web_app, key (the site for a web app), opened, boot, process token, session, graceSince, anchor}`, under the one lock.
  An end ends its own record only: a login's `done`/`timeout` leaves an open web app alone, and `finishAnchors()`
  finishes that record's anchors only. `isOpen`, `chromeStep` and `chromeAppeared` read "any record open". **Chrome is
  hidden only when the set is empty** (and no pause, override or Play's update window).
- **The ends, per kind** (pure `windowStep(kind, ...)`, new `window` rows):

| End | Login | Web app |
|---|---|---|
| `timeout` | 5 min from the opening | none; a **2 h backstop** from the last `TAB_SHOWN`, renewed by it; 30 min for a session-less tab |
| `left` (grace past 1 min) | `TAB_HIDDEN`/`onMinimized` | `TAB_HIDDEN`; **or a Vibb-side sign that needs no session**: Home's `onResume` (Vibb is Home), or another package in front by the screen-time tracker's foreground reading - each starts the grace; the grace counts only while the screen is on |
| `locked` (screen off with a secure lock) | yes | **no**: the kid's phone always has a PIN, so this would end every web app at each screen-off |
| `done`, `closed`, `result` | yes | `closed` only (the anchor resumed after the tab, i.e. the tab closed) |
| `rule`, `tightened`, `stale` | yes | yes |

- **What the kid sees after an end**: hiding stops Chrome; the stored content stays; the next tap opens the site's
  start page, not where the kid was (Q2's cost, below). A Vibb One restart ends every record as `stale`, with the same
  result.

### 3.4 Staying on the parent's sites

- In a verified TWA there is no address bar and no menu. A link off the site opens Chrome's toolbar (close, the
  read-only host) and Chrome's "blocked by your organization" page unless the host is listed (design 102 M14).
- `URLBlocklist` stops navigations and frames, not a page's scripts, images or XHR (design 119's captcha note), so a
  site can still pull resources from other hosts; the DNS filter is what judges those hosts (§3.7).
- The way out to full Chrome (the toolbar's "Open in Chrome browser", a long-press "Open in Chrome", an app starting
  Chrome by name) lands in Chrome under the same bundle: the parent's sites and the login sites only, no search, no
  incognito (design 102 M14). That is design 119's residue, now open while any web app is in front. Chrome's own
  settings are reachable from there; the kid could clear site data and lose the offline copy - an annoyance, not a way
  out (no Android policy forbids it). Step 0's T8 rows try each way out.
- A link from another app to a web app's site goes through design 119's gate; its login match keeps to the login sites,
  so the link gets the gate's note ("Denne lenken kan ikke åpnes her."). Opening the tile's anchor from the gate is not
  in v1.
- Unverified sites show the toolbar with the host the whole time; the PWA notes it quietly (§5).

### 3.5 The offline store

- Chrome's quota (§1.5) holds a couple of GB on any supported phone; the Jelly has room to spare.
- **Chrome's one-time clear takes the offline copy and the verification cache** (T2's cleared row). With §3.2's key it
  runs at the first lockdown, before any content, and after Chrome was a free app (a parent's choice, a quiet note
  behind the (i) of Chrome's switch under Apps, §5). The first web app added on a phone without the login role clears
  Chrome once (its tabs and sign-ins): a quiet line behind the group's (i) (§5) and Q6.
- **The first fill needs the app open** in v1: Chrome is hidden when the window ends, which stops a service worker's
  download (and Background Fetch). What the site needs (the owner's code, not Vibb's): a service worker that stores the
  content in resumable pieces, a progress line, and the Screen Wake Lock while it fills. Over Wi-Fi a couple of GB is
  minutes.

### 3.6 Time rules, screen time, pause

- A time rule suspends Chrome like any app, so the tiles dim and an open window ends (`rule`). A web app allowed
  during a rule is not in v1.
- Screen time counts as it does for the phone. App groups (design 139) count by package and would see Chrome; Vibb knows
  each window's shown time, so a site can become a group member later (`web:<host>`), not in v1 (Q4).
- A pause lifts the lockdown with everything else (design 119 D9); its end is a tightening.

### 3.7 The network and the DNS filter

- Chrome uses the system resolver while the filter is on (design 67's `DnsOverHttpsMode`), so each site's host must
  pass the parent's lists. The phone checks each entry with `classifyDomain` and reports a blocked one.
- **Chrome can't reach the tailnet**: tsnet is embedded in Vibb One's process (`dns-and-vpn.md`), not a system VPN. A
  site served only on the tailnet is unreachable from Chrome; it must be on the internet or on the network the phone is
  on (the home Wi-Fi). Chrome's `assetlinks.json` check needs the same reach (T11 on a home-network-only host). The
  PWA's group (i) says so (§5).
- Offline nothing is asked. In "Bare Vibb" on cellular Chrome's traffic is sunk; a filled site still works from its
  store.

### 3.8 Notifications

Not in v1. Web push reaches a TWA's site through Chrome's FCM connection only while Chrome is not hidden, so it needs
Q2 (b). Then the bundle adds `DefaultNotificationsSetting: 2` and `NotificationsAllowedForUrls` (the parent's sites,
Chrome 142+), and the device owner grants Chrome's `POST_NOTIFICATIONS`. With a TWA, Chrome can also delegate the
site's notifications and their permission to the launching app through a `TrustedWebActivityService`
(androidx.browser), so they would show as Vibb One's under a channel per site; T3's variant checks whether that grant
also earns `persist()`.

### 3.9 The report

`web_apps` in the full report: per entry the host, `verified` (true, false, null until known), `filtered`, the icon
fetched or not, `last_open_ms`, `session`; and each kind's last window end, as `last_window` does for logins. The
phone's own package and signing certificate's SHA-256 (the release `me.vibb.launcher` or the debug
`me.vibb.launcher.debug`) for §5's file. Never a path or a page.

## 4. Android 14-17

| What | API / source | Note |
|---|---|---|
| TWA, Custom Tabs callbacks, DAL check | Chrome's version, not Android's | the same on 14-17 |
| `setApplicationHidden`, `setLockTaskPackages` | 21 | no gate |
| `documentLaunchMode`, `setTaskDescription` | 21 / 28 | no gate |
| Background activity starts | 14+ | every start from the visible anchor |
| Safer intents | 15 | the TWA intent is explicit to Chrome's package |
| `POST_NOTIFICATIONS` for Chrome | 13+ | only with §3.8 |
| Android 17 | - | by source; T1 and T8 on 17 owed until an image runs on the VM (the lowest and the highest touched) |

No version-specific path found in the sources; step 0 runs on 14 and 16.

## 5. The PWA (Words in the UI)

A group on the phone's Apps page, `#web-apps`, after the apps.

| Where | nb | en |
|---|---|---|
| Group heading | Nettapper | Web apps |
| Group (i) | Åpnes i Chrome, bare på disse sidene. Siden må nås fra telefonens nett, ikke bare via Tailscale. Første nettapp tømmer Chrome én gang. | Open in Chrome, on these sites only. The site must be reachable from the phone's network, not only over Tailscale. The first web app clears Chrome once. |
| Fields | Navn · Adresse | Name · Address |
| Button | Legg til | Add |
| Not https (inline) | Bare https | https only |
| Row | the name, the host quiet beside it; "Fjern" | the name, the host; "Remove" |
| Row (i), quiet, a site not verified | Vises med adresselinje. Uten den: legg denne filen på `/.well-known/assetlinks.json`: (the file) | Shows an address bar. To hide it, put this file at `/.well-known/assetlinks.json`: (the file) |
| Orange: the filter blocks it | Stoppet av filteret | Blocked by the filter |
| its (i) | Tillat siden under Filter. | Allow the site under Filter. |
| Orange: Chrome not ready | Chrome er ikke klar | Chrome isn't ready |
| its (i) | the login role's existing reason lines | |
| Chrome's switch under Apps, (i), quiet | Slås Chrome av igjen, tømmes Chrome - også nettappenes lagrede innhold. | Turning Chrome off again clears Chrome - the web apps' saved content too. |

- **The file** is built from what the phone reports (§3.9): its package and its certificate's SHA-256 as colon-separated
  upper-case hex, in a `delegate_permission/common.handle_all_urls` statement - not the base64url
  `LAUNCHER_SIGNATURE_CHECKSUM`. With phones on both the release and the debug build, the file lists both.
- **Not verified is a quiet note, not orange**: the file stays optional (Q5; a site the family doesn't run can't add
  it), and the tile works with the host shown. It would be orange only if the owner made the file required.
- A site that is fine shows nothing more. The kid's phone gets no new words: the tile is the name; a blocked link is
  Chrome's own page.

## 6. Step 0 (measure first)

A probe app (as design 102's) that opens a test site as a TWA from its own anchor, under lock task as device owner,
with Chrome's bundle written as Vibb writes it. The test site: a small PWA on the rig with a service worker that fills
N GB of generated blobs and a page showing `navigator.storage.estimate()` and `persisted()`; https with a publicly
trusted certificate on a throwaway test name (never the owner's), or `localhost` over `adb reverse` for the storage rows.
Needs the owner's OK for emulator time (and not before 23:00 CET on a quiet evening) and for a throwaway Google account
on the Play images (Play must update Chrome; `compat34` is a Google APIs image with Chrome 113, so Android 14 needs a
new `google_apis_playstore` AVD).

| # | What | Where | Expected |
|---|---|---|---|
| T1 | TWA with `assetlinks.json` naming the probe, from a **cold, hidden Chrome**: unhide, bind, verify, page - each step timed; the bind's misses | 14, 16, the Jelly; 17 owed | full screen, no toolbar, in the probe's task; the bind within 10 s |
| T2 | T1 offline (airplane mode); after a reboot; **after Chrome's data was cleared, offline** | 14, 16 | verified from the cache; after the clear: the toolbar (and no content) until online |
| T3 | `persist()` and `estimate()` in a TWA, an unverified tab, a WebAPK (T6), a shortcut if any; each TWA again with `NotificationsAllowedForUrls` for the site (Chrome 142+) and with notifications delegated to the probe (`TrustedWebActivityService`); a 3 GB fill | 16 + the Jelly | the quota and whether persistence is granted per engine and per variant (decides Q1) |
| T4 | The content after Chrome hidden and unhidden, suspended by a time rule and back, a Chrome update, a reboot, `pm trim-caches` | 16 | kept |
| T5 | The disk filled to near-full with a dummy file, persisted vs not | 16 (`-partition-size` large) | best-effort evicted first; where it starts |
| T6 | "Install app" in Chrome with Play suspended, then in a pause; the package's name, signer and `startUrl` meta-data | 16 (Play image, test account) + the Jelly | suspended: no WebAPK (and no shortcut, Vibb as Home); in a pause: a WebAPK |
| T7 | A WebAPK under lock task: the packages that need pinning; the usage events' package; its task | 16 | for the Q1 choice |
| T8 | **Ways out**, each from a verified TWA: (a) a link off the site; (b) the toolbar's "Open in Chrome browser" and a typed host there; (c) a long-press on a link (open in Chrome, share, download); (d) `target=_blank` and `window.open`; (e) `intent://` links; (f) an iframe from another host; (g) a same-origin path outside the scope; (h) Chrome's "Running in Chrome" first-launch notice and where it leads | 14, 16; 17 owed | Chrome's blocked page or nothing; full Chrome only on the list; (g) the allowlist's prefix decides |
| T9 | Web push with Chrome hidden and not hidden; in Doze on the Jelly | 16 + the Jelly | hidden: nothing arrives |
| T10 | Each web app end of §3.3: Home's resume, another app in front, the grace with the screen off, the 2 h backstop renewed by `TAB_SHOWN`, a session-less tab's 30 min; a Vibb One process kill during a web app | 14, 16 | as the ends table; the kill: `stale`, Chrome hidden by the first apply |
| T11 | Which hosts Chrome contacts for the DAL check and for minting (the filter's log); the DAL check against a host on the home network only | 16 | only the site for C; a home-network host verifies |
| T12 | A time rule starting with the TWA open | 14, 16 | the window ends, the tile dims |
| T13 | Two windows: a login opened over an open web app, done; a web app opened during a login, the login's timeout | 14, 16 | each ends alone; Chrome hidden only after the last |

**What the emulator can't show**: the Jelly's Chrome and Play versions and Unihertz's behaviour; real flash speed and
real storage pressure at the Jelly's size; push delivery in Doze; Play's real WebAPK update timing (charging on Wi-Fi);
battery. Those rows (T1, T3, T6, T9) also run on the Jelly with the owner's site. A failing row revises this note
before the build.

### Step 0 results (2026-11-28, emulator, first pass)

Run with the owner's OK for the day, 09:04-09:56 UTC, on a wiped copy of `jelly36` (Android 16 Google Play image,
`BE2A.250530.026.D1`, 8 GB data partition), no Google account, no device owner, the Pixel launcher as Home. Chrome
133.0.6943.137 (the image's own; Play can't update it without an account), Play 45.3.21 (it updated itself to 54.4.11
within six minutes, with no account), Play services 25.08.34. Test sites: a small PWA of our own (manifest, service
worker, a Cache Storage filler, `estimate()`/`persisted()`, a push handler) served from the host over `adb reverse` as
`http://localhost:8090` and `http://127.0.0.1:8090` (two origins, both secure contexts); a well-known public PWA
(`squoosh.app`) for the install. Pages driven over Chrome's DevTools socket (`adb forward ...
localabstract:chrome_devtools_remote`), pushes sent from the host with our own VAPID key through FCM. No probe app, so
no TWA (T1, T2, T8 open). The host ran short of memory with a second emulator on it: this one was killed three times
(the data kept each time, which became a row of its own) and the last kill ended the run.

| # | Row | Result |
|---|---|---|
| R1 | T6, no account, Play enabled: "Install app" (Chrome's install sheet) for `squoosh.app`, `localhost` and `127.0.0.1` | **No WebAPK, all three times.** Chrome got a minted package name each time (`org.chromium.webapk.a…_v2`), also for the two sites Google's server can't reach, and called Play's install API; Play refused: `WebAPK service unknown_account`. Chrome then fell back to a pinned shortcut (the launcher's "Add to home screen" sheet; `ShortcutInfo` of `com.android.chrome`, `ACTION_START_WEBAPP`, display standalone). With Vibb One as Home that sheet has no taker (§1.2), so nothing is added |
| R2 | T6, Play suspended | **Not run.** The shell can't suspend Play (`Cannot suspend package "com.android.vending": required for package verification`); only a device owner can (Vibb does, `emulator.md` §6b's check). Without an account the install fails at Play's account check anyway (R1), so the row needs the test account |
| R3 | T6/T7 with a WebAPK: its signer, `startUrl`, launch with Play suspended, lock task | **Not run**: no WebAPK without an account (R1) |
| R4 | T3, `persist()` per origin and variant | `squoosh.app` (a shortcut on the launcher): false; true once notifications were allowed for it (and stayed true with a bookmark). `127.0.0.1` in a tab: false; true once notifications were allowed. `localhost`: false in a tab, after the shortcut, with notifications allowed and with a bookmark, and false in its standalone window. A Custom Tab (`CustomTabActivity`, opened by intent) on `127.0.0.1` saw the origin's grant and data as the tab did. So the grant follows the origin, not how it is opened; notification permission is the lever that worked. Likely cause of the `localhost` result: Chromium matches its "important sites" by registrable domain, and `localhost` has none (our reading, not checked in the source this run); a site on a bare home-network name may be refused the same way, a real domain or an IP not |
| R5 | T3, a 3 GB fill | 3072 MB into Cache Storage on `127.0.0.1` (persisted) in 13 s with `ArrayBuffer` bodies; 1024 MB on `localhost` (best-effort). `estimate()`: quota 4750 MB for each origin (60% of the partition). The first fill, with `Blob` bodies (`new Response(new Blob([16 MB]))`), stopped at 512 MB with `UnknownError: ... Unexpected internal error` while 2.5 GB was free; the re-test was cut by the last emulator kill |
| R6 | T4, the content after `pm trim-caches 100G`, Chrome force-stopped, `adb reboot`, Chrome suspended and back (`pm suspend`), three hard emulator kills | **Kept**, both origins, every time (the entry count, `localStorage` and IndexedDB markers, `persisted()`). Not run: Chrome hidden and back (a device owner's call; the shell lacks `MANAGE_USERS`), a Chrome update (needs the account) |
| R7 | T5, the disk filled with a dummy file to 100% (10 MB free) | **No eviction**: in about 8 minutes at a full disk (and across two kills and boots) neither origin lost anything, the best-effort one included. Writes failed with `UnknownError` (not `QuotaExceededError`) at 1.8 MB free, and `estimate()` still said 4750 MB quota with 10 MB free. Android's own low-storage handling didn't touch Chrome's data either. Eviction order not seen |
| R8 | T9, web push to the probe's own origin (notifications allowed, Chrome not hidden) | **Arrives within seconds, with no account**, as `pkg=com.android.chrome`, channel `web:http://127.0.0.1:8090`, group `Web:127.0.0.1:8090`: under Chrome's name (no WebAPK to carry it, R1). With Chrome **suspended** the push still reached Chrome and the notification was posted, but the shade hid it; it showed when Chrome was unsuspended. After the first reboot three pushes in eight minutes didn't show (the emulator was starved, load ~40); after the next boot delivery worked again at once |
| R9 | T11, hosts for minting (DNS over mobile data, `-tcpdump`) | One name during an install attempt: `webapk.googleapis.com`. Play's part ended on the phone (R1), so the hosts of a real install (the APK's download) are still open |

**What it means for the WebAPK as the main path:**

- **A WebAPK needs a Google account in Play on the phone.** Without one Chrome falls back to a shortcut, and with
  Vibb One as Home that is nothing. The account is optional today (`docs/setup/google-account.md`), so the WebAPK path
  needs it for every phone that uses web apps, and the TWA (option C) stays the path for a phone without one.
- **Minting doesn't need the site reachable from Google**: package names came back for `localhost` and `127.0.0.1`.
  Whether such a WebAPK then installs and runs for a site on the home network only is open (the account rows).
- **Storage doesn't depend on the install**: a tab, a Custom Tab and Chrome's standalone window share the origin's
  data and its `persist()` grant. A 3 GB store fits, and survived everything we could throw at it here. What gets the
  grant: notification permission (a WebAPK's install is still untested); the owner's site needs a real domain or an
  IP, not a bare name. The site's service worker should write `ArrayBuffer`s or fetched responses, not big `Blob`s,
  until R5's `Blob` stop is re-tested, and should not trust `estimate()` for free space.
- **Push needs no account**, only Chrome not hidden; under Chrome's name without a WebAPK.

**Left for the owner's test account (emulator) and the Jelly**: T6 in a pause and with Play suspended by Vibb (a
WebAPK at all, its package, signer and `startUrl`; Vibb's new-package path hides and suspends it until it is listed);
T7 (the WebAPK under lock task); `persist()` and push under a WebAPK; the install's hosts (R9); a Chrome update and
Chrome 142's `NotificationsAllowedForUrls` (this image has 133); T1, T2, T8, T10, T12 and T13 with the probe app; R5's
`Blob` stop again; the Jelly's own Chrome and Play.

## 7. Size and steps

1. Step 0 (§6) - emulator S, then the Jelly rows by the owner.
2. Server (S): the table and migration, the policy key and contract, the Apps page group and its copy, the
   `assetlinks.json` text from the phones' reports, the report's lines.
3. Launcher (M): `LoginWindow` as a set of records with the per-kind ends (and its `login_rules.json` `window` rows,
   with a login over a web app and the reverse); the clear keyed on the lockdown; the tiles and the filtered icon fetch;
   the anchor, the bind retry and the TWA launch; the Vibb-side `left` signs; the bundle; the plan's hide rule; the
   report.
4. Code QA, the emulator gate on 14 and 16 (T1, T2, T8, T10, T12, T13 again with the real build), the Jelly with the
   site.

## 8. Questions for the owner

- **Q1. The engine: C (Vibb opens the site, no install) or A (a real WebAPK, installed by hand in Chrome in a
  pause)?** Recommended: **C**; A only if step 0 shows the offline store safe only in a WebAPK (T3, T5). Note this
  changes item 9b's wording ("real WebAPK installs").
- **Q2. When is Chrome out?** (a) only while a web app or a login is in front - no web push, the first fill with the app
  open, and **a cost the kid sees: away from a web app for more than a minute (screen on), or after Vibb One restarts,
  the next tap opens the site's start page, not where the kid was**; (b) whenever a web app is listed - push and
  background downloads work, the kid comes back where they were, and full Chrome on the listed sites is reachable from
  inside a web app at any time. Recommended: **(a)** for v1, (b) when a site needs push.
- **Q3. The list per phone or per family?** Recommended: **per phone**, as the apps and the login sites.
- **Q4. Time**: web apps follow the phone's rules and screen time, with no per-site limit or group in v1. Recommended:
  **yes**; a site in an app group later.
- **Q5. The site's file**: you add `/.well-known/assetlinks.json` (the PWA shows its text) to your encyclopedia copy;
  the file stays optional for other sites. Recommended: **yes, optional**; without it the tile works with the host in a
  toolbar.
- **Q6. The first web app clears Chrome once** on a phone without the login role (its tabs and sign-ins; nothing of the
  web apps exists yet). Recommended: **yes**, it is what keeps a later login role from wiping a filled site.
- **Q7. The Kiwix idea in the inbox**: recommended **parked until step 0's T3-T5 pass**, then closed.

## 9. Design QA (revision 2)

All 14 findings taken; none found wrong. H1 one record per window with per-kind ends (§3.3, T13); H2 no session no
web app window, the bind retry, a Vibb-side `left`, the 2 h backstop (§3.3, T1, T10); M1 the clear keyed on the
lockdown (§3.2, Q6); M2 the start-page cost in Q2 (a); M3 the icon fetch filtered by Vibb itself (§3.3); M4 the
verification cache and the tailnet (§3.5, §3.7, T2, T11); M5 the ways-out rows (T4, T8, T10); L1 T3's notification
variants; L2 the file from the phones' reports (§3.9, §5); L3 the trailing `/` (§3.2); L4 TWA notification delegation
(§3.8); L5 not verified a quiet note (§5); L6 a ticked Chrome and links from other apps (§3.2, §3.4); L7 17 owed
(§4, T1, T8); a removed site ends a login too (§3.2).

## Sources

[arch]: https://chromium.googlesource.com/chromium/src/+/main/components/webapps/docs/android_architecture.md
[webapks]: https://web.dev/articles/webapks
[mupd]: https://web.dev/articles/manifest-updates
[inst]: https://web.dev/learn/pwa/installation
[pin]: https://developer.android.com/reference/android/content/pm/LauncherApps.PinItemRequest
[twa]: https://developer.android.com/develop/ui/views/layout/webapps/trusted-web-activities
[twaguide]: https://developer.chrome.com/docs/android/trusted-web-activity/integration-guide
[dalcache]: https://groups.google.com/a/chromium.org/g/chromium-dev/c/Ki3TZIGfmTY
[twaoff]: https://developer.chrome.com/docs/android/trusted-web-activity/offline-first
[pol]: https://chromium.googlesource.com/chromium/src/+/main/components/policy/resources/templates/policy_definitions/
[storage]: https://web.dev/articles/storage-for-the-web
[persist]: https://web.dev/articles/persistent-storage
[oauth]: https://developers.googleblog.com/2016/08/modernizing-oauth-interactions-in-native-apps.html

- Chromium, Android web app architectures (WebAPK and TWA): [arch]
- web.dev: WebAPKs on Android [webapks]; manifest updates [mupd]; installation [inst]; storage [storage]; persistent
  storage [persist]
- Android: `LauncherApps.PinItemRequest` [pin]; trusted web activities [twa]
- Chrome: TWA integration guide [twaguide]; offline-first TWAs [twaoff]; chromium-dev, DAL verification on the device
  and cached [dalcache]
- Chromium policy definitions (`Miscellaneous/`, `ContentSettings/`; `supported_on` read 2026-11-28) [pol]
- Google: OAuth in embedded WebViews [oauth]
- Ours: design 102 (M10-M15, D-C4), design 119, `docs/notes/launcher/logins.md`, `docs/notes/launcher/push-and-play.md`
  (Play suspended), `docs/notes/launcher/hardening.md` (`enforceOnNewPackage`), `docs/notes/launcher/dns-and-vpn.md`
  (tsnet in-process), `logins/LoginWindow.kt`, `logins/LinkGate.kt`, `logins/LoginBrowser.kt:193`,
  `server/BrowserConfig.kt:108`, `server/KidVpnService.kt:303`

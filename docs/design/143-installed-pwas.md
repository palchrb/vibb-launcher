# 143 - Installed web apps on the kid's phone

Status: design note, revision 4, 2026-11-28, branch `pwa-design-3` (revision 3: 356f4daeb; revision 2: 91ca32f41 and
step 0's first pass faf07debd; revision 1: 46669c860). Docs only. The locked list's item 9b ("Installed PWAs on the kid's phone"; the
owner: "Vil vi helst ha ordentlig native pwa støtte", and after the clarification: a real installed PWA, Chrome always
on as the engine, never shown as an app). Not built; the step 0 rows left (§6) and the owner's answers (§8) come first.
Revision 3 follows step 0's first pass, which found that a WebAPK needs a Google account in Play, and the owner's
question back (2026-11-28): "Må man ha google konto for pwa? Makes no sense. Er det eneste vei?" It redesigns around
the paths that need no account (§1, §2) and recommends Chrome's own web-app shortcut, accepted by Vibb One as Home (§3).
Revision 4 takes the design QA of revision 3 (4H 6M 6L, all taken, §9).

Goal: the parent adds a site in the PWA, and the kid gets a tile that opens it as an app - full screen, its own icon,
working offline - that can't be used to reach the rest of the web. The first use: the family's own offline copy of a
children's encyclopedia (a couple of GB) served as a PWA, instead of a custom Kiwix build. The condition: the site's
offline store holds all of it.

## 0. Short answer

- **Is a Google account the only way? No.** The account is needed for one kind of install only, the WebAPK: Google's
  server mints the APK and Play installs it, and Play refuses without an account (step 0, R1: `unknown_account`).
  Without one, Chrome installs the same site as its own **web-app shortcut**: a pinned `ShortcutInfo` of Chrome's that
  opens the site in Chrome's standalone window, `WebappActivity` - the same window a WebAPK runs in: no address bar,
  its own task and Recents entry, a splash screen from the manifest, full screen or standalone as the manifest says
  ([WebappLauncherActivity][wla], [Chrome's manifest][cman]). What it lacks is an Android package of its own: no entry
  in Android's app settings, notifications under Chrome's name, no share target, the name and icon fixed at install
  ([web.dev, installation][inst]). Firefox, Edge, Opera, Brave and Samsung Internet off Samsung phones install every PWA
  as a shortcut too ([inst]). It needs one thing on our phones: **the Home app must accept Chrome's pin request**, and Vibb One
  refuses it today (no `ACTION_CONFIRM_PIN_SHORTCUT`, §1.2) - which is why step 0 saw nothing appear.
- **Recommendation: A, Vibb One accepts Chrome's web-app shortcut** (§3). It works for any installable PWA, needs no
  account, no Play, no `assetlinks.json` and no new package; the window is Chrome's own standalone one. The install is
  a tap in Chrome in a pause (the owner's idea: the parent adds the site in the PWA, the phone installs it in a pause),
  from a row on the pause screen. Two limits: each install still sends the site's manifest to Google's minting server
  first (R9), and the shortcut fallback rests on a Chrome feature that a Chrome field trial could switch off (§1.1), so
  B stays in reach.
- **Chrome must stay unhidden while web apps are listed** - a cost to decide (Q0): A requires it, because hiding
  Chrome removes its pinned shortcuts from every launcher (Android's `ShortcutService` treats a hidden package as
  removed, §1.2). **It reverses design 119's option C** ("Chrome hidden except during a login Vibb opens", the owner's
  ASK 1): any allowed app can then reach Chrome by name (Custom Tabs, AppAuth, MSAL, an explicit intent). What holds
  the kid instead (§3.4, §3.5): outside a login window Chrome's `URLAllowlist` holds only the web-app entries, and a
  guard brings Home back whenever any Chrome window other than the web-app window comes to the front.
- **The offline store is the same in every Chrome path**: Chrome's quota, up to 60% of the disk per origin; step 0 put
  3 GB in and lost nothing to trim, reboot, suspend or a full disk; `persist()` follows the origin and is granted with
  the notification permission, which the bundle grants for the listed sites on Chrome 142+ and the site's own prompt
  gets on an older Chrome (§3.4, §3.6).
- **Push works with no account** (step 0, R8), under Chrome's name, once Chrome stays unhidden and Vibb's nag rule
  keeps Chrome's notifications for the listed sites (§3.10).
- A site's own TWA app (C, like Bubblewrap) is the only no-account path with its own package and name in
  notifications; it costs a build and signing pipeline and full screen only on a site that publishes the key (§2).
  The WebAPK stays out of v1 (§2.5).
- Size **M** (server S, launcher M at its upper end): no per-window records and no icon fetch, but the guard, the hide
  rule's plan input, the nag rule and the bundle per login window.

## 1. What Android and Chrome offer

### 1.1 WebAPK

- Chrome asks Google's minting server for a real APK built from the site's manifest and installs it through Play
  ([Chromium, Android web app architectures][arch]; [web.dev, WebAPKs][webapks]). Only Chrome on phones with Google
  Mobile Services mints them; Samsung Internet mints its own, on Samsung phones only, through the Galaxy Store ([inst]).
- The page runs in Chrome (`SameTaskWebApkActivity`/`WebappActivity`), the storage is Chrome's profile ([arch]). It has
  its own package: a `VIEW` filter for its scope, an entry in Android's app settings, notifications under its own name
  ([webapks]); updates are re-minted when the manifest changes ([mupd]).
- **It needs a Google account in Play** (step 0, R1): Chrome got a minted package name, Play refused
  (`WebAPK service unknown_account`), and Chrome fell back to the shortcut of §1.2. Chrome does that on every definite
  failure (not on a "probable" one, when Play may still be busy) ([webapk_install_service.cc][wis]) - **unless the
  feature `kWebApkInstallFailureNotification` is on**: then a failed install shows a failure notification and adds
  no shortcut ([wis]). It is `FEATURE_DISABLED_BY_DEFAULT` ([features.cc][feat]), but a server-side field trial can
  turn a feature on for some installs of Chrome. The bundle sets `ChromeVariations: 1` (critical fixes only, Android
  112+, §1.5) to keep trials off; W1 and W12 run on the newest Chrome and on the Jelly's (§6), and B stays the fallback.
- No API starts an install: it is Chrome's "Install app" or the site's own install prompt, a tap in Chrome.

### 1.2 Chrome's web-app shortcut

- **What Chrome makes**: for an installable site whose manifest asks for a standalone or full-screen display,
  `ShortcutHelper.addWebapp` builds an intent with action `...WebappManager.ACTION_START_WEBAPP`, the start URL, scope,
  name, icon, display mode, theme colours and a MAC, and registers the web app in Chrome's own `WebappRegistry`; a site
  that isn't installable gets `addShortcut` instead, a plain `VIEW` intent that opens a browser tab ([ShortcutHelper][sh]).
  On Android 8+ the shortcut goes to `ShortcutManager.requestPinShortcut` when the default launcher supports it,
  otherwise to the old `INSTALL_SHORTCUT` broadcast if the launcher has a receiver for it, otherwise nowhere
  ([WebappsUtils][wu]). The shortcut's id is a random GUID.
- **The MAC**: `WebappLauncherActivity` (exported) opens the standalone window only if the launch came through its
  non-exported `SecureWebAppLauncher` alias, was sent by Chrome itself, or carries `webapp_mac`, an HMAC-SHA256 over the
  URL and icon with a key in Chrome's files (`webapp-authenticator`); anything else opens as a normal tab ([wla],
  [WebappAuthenticator][wauth], [cman]). Today's shortcuts use the exported action with the MAC; a TODO in
  `ShortcutHelper` plans an action without it on Android 8+, where a pinned shortcut starts under its publisher's uid
  ([sh], [cman]). Either way only a shortcut Chrome made opens the web-app window, and Vibb can't make one.
- **What the launcher gets**: the default launcher's activity for `LauncherApps.ACTION_CONFIRM_PIN_SHORTCUT` receives a
  `PinItemRequest` and calls `accept()` or not ([PinItemRequest][pin]; [LauncherApps][la]). The `ShortcutInfo` it sees
  has the publisher package, the id, the labels and the icon, **never the intent**: "Launcher apps cannot see the
  intent ... Launchers can only start a shortcut intent with `LauncherApps#startShortcut`" ([ShortcutInfo][si]). So
  Vibb can't read the shortcut's URL, and it can't tell a web-app shortcut from a bookmark shortcut by the request.
- **Starting it**: `LauncherApps.startShortcut(package, id, bounds, options, user)`, allowed to the app that has the
  shortcut host permission - the default Home, which Vibb One is ([la]). The window is `WebappActivity`
  (`documentLaunchMode="intoExisting"`, not exported, [cman]): a task of Chrome's own per web app.
- **What removes it**: Android's `ShortcutService` drops a publisher's pinned shortcuts from every launcher when the
  package is removed or its data is cleared ([ShortcutService][ss]). **Hiding a package counts as removing it**:
  `setApplicationHidden(true)` sends `ACTION_PACKAGE_REMOVED` for the user ([BroadcastHelper][bh]). Suspending it does
  not (no such broadcast). An update (`REPLACING`) keeps them.
- **What it lacks next to a WebAPK** ([inst]): no own package (so no app settings entry, no own notification name, no
  share target or manifest shortcuts), name and icon not updated after the install, and on a stock launcher a Chrome
  badge on the icon - Vibb draws its own tile, so no badge here.

### 1.3 Trusted web activity (TWA)

Any app can open a site in Chrome as a TWA: a Custom Tab with no browser UI, once Chrome has verified a Digital Asset
Links statement on the site that names the app's package and signing certificate ([Android, TWAs][twa]; [Chrome, TWA
guide][twaguide]). Chrome verifies on the phone and caches the result ([dalcache]; [twaoff]); a failed check falls back
to a Custom Tab with the read-only host in a toolbar ([arch]). A TWA from Vibb One runs in Vibb's task and needs a bound
Custom Tabs session (design 102 M11, M12; design 119's gate saw the bind miss on 16). A separate app per site can be a
TWA as well: Bubblewrap and android-browser-helper (both Apache-2.0) build one from a manifest ([bubblewrap]; [abh]),
with Chrome's notifications delegated to it so they show under its own name (`TrustedWebActivityService`).

### 1.4 Other browsers

| Browser | Install on Android | Lockdown by policy | Notes |
|---|---|---|---|
| Firefox | shortcuts, no WebAPK ([inst]) | **none**: no Android managed configuration ([ffmdm], an open request) | Gecko, its own profile; nothing like `URLBlocklist`, so only the DNS filter would hold the kid to the sites |
| Samsung Internet | WebAPK on Samsung phones only (Galaxy Store), shortcuts elsewhere ([inst]) | Knox, not the managed config we write | closed source; no gain on the Jelly |
| Edge, Opera, Brave | shortcuts ([inst]) | not checked | no gain over Chrome's own shortcut |
| Cromite (GPL-3.0), Vanadium | Chromium forks without Google's minting: Chromium's shortcut path, as Chrome's | not checked | Vanadium is GrapheneOS's; Cromite's managed-config and push support not checked; neither has Chrome's FCM push |

Depending on a browser as a separate app is no licensing question (nothing is linked or shipped). None of them adds
what Chrome's own shortcut lacks; each would replace the engine design 119 and 102 measured (the bundle, the
`URLAllowlist`, the login role).

### 1.5 Chrome's enterprise policies (Chromium's policy YAMLs, `supported_on`, read 2026-11-28 [pol])

| Policy | Android | Use here |
|---|---|---|
| `WebAppInstallForceList`, `WebAppSettings` | **no** (desktop, ChromeOS) | none: no forced install |
| `URLBlocklist`, `URLAllowlist` | 86+ | the lockdown: the parent's sites; a path is a string prefix (§3.4) |
| `DefaultNotificationsSetting`, `NotificationsAllowedForUrls` | **142+** | push and `persist()` for the listed sites (§3.6) |
| `PersistentQuotaEnabled` | 106-107 only | gone; no policy grants persistent storage |
| `AllowDeletingBrowserHistory` | future only | the kid could clear site data from Chrome's settings; the guard keeps them out of reach (§3.5) |
| `ChromeVariations` | **112+** | `1`, critical fixes only: no field trial turns the shortcut fallback into a notification (§1.1) |

### 1.6 Storage (step 0 measured it)

Chromium lets one origin use up to 60% of the disk; best-effort data is evicted only under storage pressure
([storage]; [persist]). Step 0 (R4-R7): the quota was 60% of the partition; 3 GB went in; nothing was lost to
`pm trim-caches`, a force-stop, reboots, `pm suspend`, three hard kills or a full disk; a tab, a Custom Tab and the
standalone window share the origin's data and its `persist()` grant, which came with the notification permission (not
for `localhost`). The storage is the same in A, B, C and a WebAPK: all of them are Chrome's profile.

## 2. The options

### 2.1 Compared

| | A. Chrome's web-app shortcut, accepted by Vibb | B. TWA opened by Vibb One | C. A TWA app per site | WebAPK (Chrome + Play) |
|---|---|---|---|---|
| Google account | **no** | **no** | **no** | **yes** (R1) |
| Which sites | **any installable PWA** | any site; full screen only if its `assetlinks.json` names Vibb One | any site; full screen only if its `assetlinks.json` names the app's key | any installable PWA |
| The window | Chrome's `WebappActivity`: standalone, splash, own task and Recents entry | a TWA in Vibb's task; the host toolbar without the file | a TWA in the app's task; the toolbar without the file | `WebappActivity`, as A |
| Install | a tap in Chrome, in a pause | none (the tile comes with the policy) | Vibb's catalog installs the APK | a tap in Chrome, in a pause, with Play unsuspended |
| Push | yes, under Chrome's name | yes, Chrome's, or delegated to Vibb One | **yes, under the app's own name** (delegation) | yes, under its own name |
| Storage | Chrome's (§1.6) | Chrome's | Chrome's | Chrome's |
| Size on the phone | a shortcut | nothing | an APK of about 1 MB per site | an APK per site |
| New package to trust | no | no | yes, one per site (a catalog app) | yes, Google-signed, `org.chromium.webapk.*` |
| Data to Google | **the manifest to `webapk.googleapis.com` at each install** (R9: Chrome tries a WebAPK first) | none beyond Chrome | none beyond Chrome | the manifest goes to the minting server |
| Chrome unhidden | always, while web apps are listed (reverses design 119's C) | while a web app is open | while a web app is open | always (its pushes and launches go through Chrome) |
| What breaks it | hiding Chrome or clearing its data (§1.2); a Chrome trial that turns the fallback into a notification (§1.1) | a cold Chrome without a session (design 119's gate: the bind missed on 16) | an APK to build and sign per site | no account; Play suspended |
| Cost | M: a pin-accept activity, the tile, the install step | M: rev 2's windows | L: a build and signing pipeline, or the parent builds with Bubblewrap | M + the trust rule + Play's windows |

### 2.2 Why A

It is what the owner asked for - a real installed PWA in Chrome's own app window - without the account, and for any
installable PWA, not only a site the family can put a file on. The site needs nothing beyond what makes it installable
in Chrome (a manifest with a name, icons, a start URL and a standalone display). It drops revision 2's hardest parts:
the Custom Tabs session that missed on a cold Chrome, the per-window records in `LoginWindow` and the icon fetch (the
shortcut brings the icon). What it costs: Chrome stays unhidden while web apps are listed, which gives up design 119's
"never a single link" and replaces it with the allowlist and the guard (Q0); notifications show under Chrome's name;
the manifest goes to Google at each install; and the install is a tap in Chrome.

### 2.3 B and C

- **B** was revision 2's recommendation. With Chrome unhidden (Q0) it gets simpler (no window to end), but the window is
  a Custom Tab in Vibb's task, full screen only with the file, and it still needs the bound session. Kept as the
  fallback if A's rows fail (§6; revision 2's §3, 91ca32f41), not in v1.
- **C** is the path to a site's own name in the shade and in Android's app settings without an account. It needs an APK
  per site, signed with a key the site's `assetlinks.json` names: built by the server (a JDK, Android build tools and a
  signing key in the server's image) or by the parent with Bubblewrap on a computer and uploaded to the catalog (design
  13's downloads, Wi-Fi only). Worth it for a family's own site once its notifications matter; not in v1 (Q3).

### 2.4 Rejected

- Vibb's own WebView (revision 2's D): no Push API, no install, Google refuses its sign-in ([oauth]); Vibb would own the
  navigation rules. Waits until a phone without Chrome needs it.
- The old `INSTALL_SHORTCUT` broadcast (§1.2): a launcher with a receiver for it and no pin support gets the full
  intent, URL and MAC included, and could start it itself. It is Chrome's path for launchers older than Android 8, kept
  in the code today but with a TODO to replace the MAC on 8+ ([sh]); a design on it would rest on a fallback Chrome may
  drop.

### 2.5 The WebAPK as an extra

On a phone with an account it would add its own notification name, an app settings entry and manifest updates. It
costs Play unsuspended during the install, the manifest sent to Google, and a Google-signed package Vibb must let past
`enforceOnNewPackage` by its start URL. Not in v1. So that every phone gets the same thing, the install step keeps Play
suspended while Chrome installs (§3.2), and Chrome falls back to the shortcut - if step 0's row W12 shows it does (a
"probable failure" adds no shortcut, §1.1).

## 3. The design (option A)

### 3.1 The parent's list

- Per phone, like the login sites (`login_role_sites`): a new table `web_apps` (`device_id`, `url`, `name`,
  `position`), the next free migration. `https://` only; an entry is an origin plus an optional path (its scope), read
  by the login sites' entry rule (`loginEntry`, the shared `entries` rows).
- The policy gains `web_apps: [{url, name}]` (absent while empty), pinned by `policy_json_keys_snapshot` and
  `PolicyResponseCompatTest`; a new `contracts/core/web_apps.json` (the entry rule's rows, the allowlist rows, the
  pin-accept rows, the hide rule rows, the guard rows and the nag rows below) with its README row, both sides in one commit.
- The server never fetches the parent's URL. The phone fetches nothing either: the icon comes with Chrome's shortcut.

### 3.2 The install, in a pause

- **The pause screen** ("Pause all restrictions") gets a row while a listed site has no shortcut: "Nettapper", the
  count left as a quiet value. A tap starts an **install step** for the first such entry: one record `{entry, opened,
  boot, state}`, at most 10 minutes, one at a time.
- **The step holds Chrome to that one site**, although the pause lifts everything else: Chrome's bundle is written with
  the lockdown on and `URLAllowlist` = the entry alone plus `LOGIN_CAPTCHA_PATHS`, and Chrome opens only after it read
  back (`sameBundle`). So the parent can install that site and no other in the step. Play stays suspended while the
  step is open (§2.5). Vibb then opens the entry's URL in full Chrome (an explicit `VIEW` with `setPackage`, since Vibb
  holds the browser role); the parent taps Chrome's ⋮ › "Install app" › Install, or the site's own install button
  (`beforeinstallprompt`, the owner's site can offer one).
- **Vibb's pin activity** (new; exported, the `ACTION_CONFIRM_PIN_SHORTCUT` filter; `taskAffinity=""`,
  `excludeFromRecents`, `noHistory` and a no-display theme, as `LinkGateActivity`): Android starts it with
  `NEW_TASK | CLEAR_TASK`, so in Home's affinity it would clear Home or the pause screen, and under lock task a clear of
  the root task is refused (W9). It accepts a request only when all hold: `isValid()`, a shortcut request, the
  publisher `com.android.chrome`, and a step in state `open`. **The first request ends the step**, accepted or not, so a
  late fallback of an earlier attempt lands in no step.
- **The order** (no window for the reconcile to undo it, and nothing bound from a forged request - the activity is
  exported, so any app can start it): write `pending {entry, id}` (CE prefs, committed), call `accept()`, confirm with
  `getShortcuts` (`FLAG_MATCH_PINNED`, Chrome, `[id]`), then mark the entry `bound`. The reconcile leaves a pending id
  alone for 30 s; a pending id that isn't confirmed by then is dropped.
- **The first-open check**: right after the bind, Vibb starts the shortcut once and reads from the usage events (with
  the class name, as design 139's `AppGroupUsage` does) which Chrome activity came up within 5 s: `WebappActivity`
  means installed; `ChromeTabbedActivity` means Chrome made a bookmark, not a web app (§1.2): Vibb unpins it
  (`LauncherApps.pinShortcuts` without it) and the entry reports `not_installable`. **Without usage access** the check
  reports `unchecked`, never installed (§3.3). Then the step for the next entry starts, or the pause screen comes back.
- An entry whose request was refused, or whose shortcut was unpinned, is installed again in the next step (W14).
- **Why binding by the step is enough**: Vibb can't read a shortcut's URL (§1.2), but in the step Chrome loads only the
  entry, so the shortcut Chrome makes is the entry's. Outside the step what any shortcut opens is decided by the
  allowlist (§3.4). Outside a pause there is no step and no request is accepted: neither the kid nor another app can add
  anything to Home.
- **The manifest's own addresses**: a `start_url` or `scope` outside the entry's prefix opens Chrome's blocked page in
  the web-app window, though the first-open check sees `WebappActivity` (Vibb can't see the URL). W19 shows what the
  kid sees; the address field's (i) in the PWA says the entry must cover the start page (§5).

### 3.3 The tile and the launch

- **The tile**: on Home like an app, the parent's name for it, the icon from the shortcut
  (`LauncherApps.getShortcutIconDrawable`); no fetch, no Chrome badge. An entry without a shortcut has no tile.
- **Usage access is a hard requirement** (the guard and the first-open check read it; `queryEvents` returns nothing,
  silently, without it): while it is off the tiles are hidden and the PWA shows the existing orange line "Data om appbruk
  er av"; the pause screen's grant row (design 114's `usage_access` step) turns it on.
- **The launch**: `LauncherApps.startShortcut(chrome, id, bounds, null, user)`. Chrome's `WebappActivity` opens in a
  task of Chrome's own; Chrome is a lock task package while web apps are listed. Vibb is the visible Home at the tap
  (Android 14's background-start rules). A second tap brings that task back where the kid left it (`intoExisting`).
- **A failed `startShortcut` keeps the binding**: the tile stays, the failure is reported (§3.9). Only the reconcile
  drops a binding.
- **The reconcile**, at each app-list load and in `LauncherApps.Callback.onShortcutsChanged` (an empty TODO in
  `Application.kt` today): `getShortcuts` with `FLAG_MATCH_PINNED` for Chrome; a bound entry whose id is no longer
  pinned goes back to not installed; a pinned Chrome shortcut no entry binds (and no pending one) is unpinned.
- **Removing an entry** unpins its shortcut. The site's stored data stays in Chrome's profile (no Android policy clears
  one origin); the allowlist keeps it unreachable.

### 3.4 Chrome's bundle and the hide rule

- `browserConfigPlan`: the lockdown is in force when the login role is on or web apps are listed. **Outside a login
  window `URLAllowlist` holds only the web-app entries**; the login sites and `LOGIN_CAPTCHA_PATHS` are added when a
  login window opens and taken out at its end (a tightening: Chrome's tabs end, design 119 Q102-M4). So a Chrome reached
  by name outside a login loads only the web apps. Its cost: each login window writes Chrome's bundle at its start and
  its end, and the start waits for the read-back (W22 measures it). In an install step: the entry alone (§3.2).
- **A path is a string prefix**, so a scope is written with a trailing `/` (`example.org/app/`, not `example.org/app`,
  which would also allow `/apple`). Ready means the bundle read back typed (`sameBundle`).
- **The hide rule, a plan input `webAppsListed`**: while it is true Chrome is never hidden, whatever the login role and
  the bundle's readiness. Today `EnforcementPlan` exempts Chrome from the hide only as `roleApp`, which exists only when
  `loginBrowser` is set - the role on and the bundle ready (`EnforcementPlan.kt:261-276`) - so "not ready" or the role
  off would hide Chrome and drop every pin (§1.2). With web apps listed and the bundle not ready, Chrome is
  **suspended** instead: the tiles dim, the pins stay. `BrowserConfig`'s hide-before-loosen (`BrowserConfig.kt:96`:
  the role turned off after its lockdown hides Chrome before the bundle loosens) becomes suspend-before-loosen while
  web apps are listed. Design 119's hide at a login window's end applies only when no web app is listed; a time rule
  suspends Chrome as before. Removing the last entry returns the phone to design 119 (Chrome hidden; Android drops the
  pins with it). W20 runs each case.
- **Chrome's one-time clear (D13) is keyed on the lockdown's first turn-on** (`chromeClearDue(lockdownOn, cleared)`,
  today `chromeClearDue(roleOn, cleared)`, `LoginBrowser.kt:193`, called from `BrowserConfig.kt:108`), so it runs
  before the first install. Any later clear (Chrome ticked as a free app, then unticked) removes every web app's
  shortcut and content (§1.2): the entries go back to not installed and are installed again in a pause.
- **Chrome ticked as a free app** (the lockdown off): the tiles still open the web apps; nothing is hidden.
- **Notifications**: `DefaultNotificationsSetting: 2` and `NotificationsAllowedForUrls` (the listed sites, Chrome
  142+), and the device owner grants Chrome's `POST_NOTIFICATIONS`. On an older Chrome - likely on a phone without an
  account, where Play never updates Chrome and the Jelly runs the Chrome of Unihertz's updates - the site asks during
  the install step and the parent allows it (W17 runs that half on Chrome 133 now).
- **`ChromeVariations: 1`** (critical fixes only), so no field trial switches the shortcut fallback off (§1.1).
- Removing a site is a tightening (Chrome's tabs end), as today.

### 3.5 Staying on the parent's sites; Chrome never shown

- In scope the window has no browser UI. A link off the site shows Chrome's toolbar with the host, and Chrome's
  "blocked by your organization" page unless the host is listed (design 102 M14). `URLBlocklist` stops navigations and
  frames, not a page's scripts, images or XHR; the DNS filter judges those hosts (§3.8).
- **Ways to the rest of Chrome**, now that it is unhidden: any allowed app reaches it by name, in that app's task or
  its own (a Custom Tab through `CustomTabsClient.getPackageName`, AppAuth, MSAL, an explicit intent); the web-app
  window's long-press "Open in Chrome" and its out-of-scope toolbar; Chrome's own surfaces - its settings (clearing site
  data), downloads (its download notification is tappable), history, bookmarks; perhaps a notification tap (W8).
- **The guard is an allowlist** (Q0): outside a login window, an install step or a pause, any Chrome activity in front
  other than `org.chromium.chrome.browser.webapps.WebappActivity` (that name, or a name beginning with it) and the
  launch trampolines `WebappLauncherActivity` and `SecureWebAppLauncher` (no display, they finish at once) brings Home
  to the front. A class Chrome renames fails closed - Home - and the report names the class (§3.9). Vibb reads the
  usage events once a second while the screen is on and Chrome is the package in front. With the allowlist of web-app
  entries only (§3.4), another app's Custom Tab outside a login ends at Home a moment after it opens: the outcome design
  119's hidden Chrome gave, a beat later. W13 and W22 measure how long a Chrome window shows.
- Links from other apps go through design 119's gate (Vibb holds the browser role); the gate opening a web app's tile
  is not in v1.

### 3.6 The offline store

- Chrome's quota (§1.6) holds a couple of GB on any supported phone; the Jelly has room to spare.
- `persist()` comes with the notification permission (R4), which §3.4 grants for the listed sites on Chrome 142+. The
  site's code calls it after the permission. The site's name must be a real domain or an IP, not `localhost` or a bare
  home-network name (R4).
- **The first fill can run with the app closed**: Chrome stays unhidden (§3.4), so a service worker's download (and
  Background Fetch, W17) goes on. What the site needs (the owner's code, not Vibb's): a service worker that stores the
  content in resumable pieces of `ArrayBuffer`s or fetched responses, not big `Blob`s (R5's stop), without trusting
  `estimate()` for free space (R7), and a progress line.

### 3.7 Time rules, screen time, pause

- A time rule suspends Chrome like any app: the tiles dim and an open web app's window stops; the shortcut stays. A
  time rule's usable list can't hold a web app in v1 (Chrome is one package; Q7).
- Screen time counts as it does for the phone. App groups (design 139) count by package and see Chrome; a site as a
  group member (`web:<host>`, from `WebappActivity` and the last tile tapped) is not in v1.
- A pause lifts the lockdown with everything else (design 119 D9); its end is a tightening.

### 3.8 The network and the DNS filter

- Chrome uses the system resolver while the filter is on (design 67's `DnsOverHttpsMode`), so each site's host must
  pass the parent's lists; the phone checks each entry with `classifyDomain` and reports a blocked one.
- **Chrome can't reach the tailnet**: tsnet is embedded in Vibb One's process (`dns-and-vpn.md`), not a system VPN. A
  site must be on the internet or on the network the phone is on (the home Wi-Fi). The PWA's group (i) says so (§5).
- Offline nothing is asked. In "Bare Vibb" on cellular Chrome's traffic is sunk; a filled site works from its store.

### 3.9 The report

`web_apps` in the full report: per entry the host, `installed` (bound and pinned), `not_installable`, `unchecked`,
`filtered`, `last_open_ms`, the last failed `startShortcut` (time and exception); the guard's last trip (time and
class); usage access on or off; Chrome's `version_name` as today. Never a path or a page.

### 3.10 Chrome's notifications and the nag rule

Vibb's nag rule (`badges/NotificationRule.kt`, `nagVerdict`) cancels a clearable notification from a package that is
neither allowed nor essential. Chrome is not an allowed app while it isn't ticked, so every web push would be deleted.
New keep reason (`NagKeep.WEB_APP`): a notification of `com.android.chrome` whose channel is `web:<listed origin>` -
the whole id, or the origin followed by `;` - is kept. Chrome's other clearable notifications (downloads, its own
promos) are still cancelled. The origin is written as Chrome writes it (R8: `web:http://127.0.0.1:8090`: the scheme,
the host, the port unless the default). `NagPolicy` gains the listed origins; a pure function with rows in
`contracts/core/web_apps.json`. W21 sends a push with Vibb managing the phone and the listener on.

## 4. Android 14-17

| What | API / source | Note |
|---|---|---|
| `ACTION_CONFIRM_PIN_SHORTCUT`, `PinItemRequest` | 26 | no gate (minSdk 34) |
| `startShortcut`, `getShortcuts`, `getShortcutIconDrawable`, `pinShortcuts` | 25 | the default Home only (`hasShortcutHostPermission`) |
| Hiding removes a publisher's pins | `ShortcutService`, `BroadcastHelper` on `main` | the rule of §3.4 on every version; W11 on 16 |
| `UsageEvents.Event.getClassName` | 21 | the first-open check and the guard; usage access required (§3.3) |
| The pin activity's start (`NEW_TASK \| CLEAR_TASK`) under lock task | 14+ | its own affinity (§3.2); W9 on 14 and 16 |
| Background activity starts | 14+ | every start from the visible Home or pin activity |
| `setApplicationHidden`, `setLockTaskPackages`, `setPermissionGrantState` | 21 / 21 / 23 | no gate |
| Chrome's web-app window, the MAC, the install fallback | Chrome's version, not Android's | the same on 14-17 |
| Android 17 | - | by source; W9, W10 owed until an image runs on the host (the lowest and the highest touched) |

No version-specific path found in the sources; the rows run on 14 and 16.

## 5. Words (Words in the UI)

**The PWA**: a group on the phone's Apps page, `#web-apps`, after the apps.

| Where | nb | en |
|---|---|---|
| Group heading | Nettapper | Web apps |
| Group (i) | Åpnes med Chrome, bare på disse sidene. Siden må nås fra telefonens nett, ikke bare via Tailscale. Første nettapp tømmer Chrome én gang. | Open with Chrome, on these sites only. The site must be reachable from the phone's network, not only over Tailscale. The first web app clears Chrome once. |
| Fields | Navn · Adresse | Name · Address |
| Address field (i), quiet | Adressen må dekke nettappens startside. | The address must cover the web app's start page. |
| Button | Legg til | Add |
| Not https (inline) | Bare https | https only |
| Row | the name, the host quiet beside it; "Fjern" | the name, the host; "Remove" |
| Orange: no shortcut yet | Ikke installert | Not installed |
| its (i) | Installer i en pause: Pause på telefonen › Nettapper, så Installer i Chrome. | Install in a pause: Pause on the phone › Web apps, then Install in Chrome. |
| Orange: Chrome made a bookmark | Kan ikke installeres | Can't be installed |
| its (i) | Siden mangler et nettappmanifest. | The site has no web app manifest. |
| Orange: usage access off (the existing `setup.warn.usage_access`) | Data om appbruk er av | App usage data is off |
| its (i) | Nettappene er skjult til den er på. | The web apps are hidden until it is on. |
| Orange: the filter blocks it | Stoppet av filteret | Blocked by the filter |
| its (i) | Tillat siden under Filter. | Allow the site under Filter. |
| Orange: Chrome not ready | Chrome er ikke klar | Chrome isn't ready |
| its (i) | the login role's existing reason lines | |
| Chrome's switch under Apps, (i), quiet | Slås Chrome av igjen, tømmes Chrome - nettappene må installeres på nytt. | Turning Chrome off again clears Chrome - the web apps must be installed again. |

- "Ikke installert" is orange because it is a step left to do; a site that is installed and fine shows nothing more.
- The first clear is a consequence of the parent's choice, so it is a quiet line behind the group's (i), not orange.

**The phone**: the pause screen's row "Nettapper" / "Web apps", the count left as a quiet value, only while a listed
site is not installed. The kid sees the tile (the parent's name) and nothing else new; a blocked link is Chrome's own
page.

## 6. Step 0 (measure first)

The rows left for A, in three phases. Each needs the owner's OK for emulator time (one emulator at a time on this host;
not before 23:00 CET on a quiet evening) and runs on a wiped copy of `jelly36` (16, Play image) and on an Android 14
`google_apis_playstore` AVD (still to make), with the small test PWA of the first pass (manifest, service worker,
filler, push handler) on `127.0.0.1` over `adb reverse` (a real-domain stand-in for `persist()`, R4) and `squoosh.app`.

**Phase 1 - no Vibb, no account, the Pixel launcher as Home.** It shows A's shortcut and window as Chrome makes them.

| # | What | Where | Expected |
|---|---|---|---|
| W1 | "Install app" for the test PWA and `squoosh.app`, the launcher's sheet accepted; `dumpsys shortcut`: the id, the intent (action, `webapp_url`, `webapp_mac`, the display mode, `webapp_source`), the icon. **Also on the newest Chrome** that can be put on the image without an account (a sideloaded stable APK), and with `ChromeVariations: 1` once a device owner writes it (phase 2) | 14, 16 | `ACTION_START_WEBAPP`, standalone, a GUID id (§1.2); a shortcut, not a failure notification (§1.1) |
| W2 | The shortcut launched: `dumpsys activity activities` - `WebappActivity`, its own task, the task's label and icon in Recents; no toolbar in scope; the splash; a second launch brings the same task back; any "Running in Chrome" or first-run notice | 14, 16 | an app window with the site's name and icon |
| W3 | A cold start offline: airplane mode, Chrome force-stopped, the shortcut launched | 14, 16 | the service worker's page |
| W4 | Ways out from the window (no policy yet): the long-press menu on a link, an out-of-scope link's toolbar and its menu, `target=_blank` and `window.open`, `intent://` links, the app menu if any; Chrome's own notifications posted from the web-app window (a download), its default-browser promo | 16 | the list the guard must cover (W13) |
| W5 | "Add to Home screen" and Chrome's "Install" on a page without a manifest, and on a manifest with `display: browser` | 16 | a `VIEW` shortcut that opens `ChromeTabbedActivity` (what the first-open check catches) |
| W6 | `pm clear com.android.chrome`; `pm suspend` and back, the shortcut launched while suspended | 16 | the clear removes the shortcut; the suspension keeps it, Android's "app paused" dialog while suspended |
| W7 | The install from a Custom Tab (opened by intent): does its menu offer "Install app", does `beforeinstallprompt` fire, is the result a web-app shortcut | 16 | decides whether the install step can avoid full Chrome |
| W8 | Push to the installed site, the notification tapped with the web app closed and with it open | 16 | which activity opens (`WebappActivity` or `ChromeTabbedActivity`); decides the guard's exemption |

**Phase 2 - Vibb One as Home and device owner**, with the first launcher step's pin activity and `startShortcut` (a
debug build; W9, W10 and W14 can run earlier with a small probe Home app without the device owner and lock task).

| # | What | Where | Expected |
|---|---|---|---|
| W9 | Chrome's install with Vibb as Home: `hasShortcutHostPermission()` logged; the `PinItemRequest` arrives; what its `ShortcutInfo` carries (id, labels, icon; the intent null); the pin activity's start under lock task over the pause screen (its own affinity: Home and the pause screen stay, no refused root clear); `accept()` with no UI; the pending-confirm-bound order; a request outside an install step and a second request in one step refused (nothing appears; what Chrome says); a `PinItemRequest` intent sent by another app | 14, 16; 17 owed | as §3.2 |
| W10 | `startShortcut` from Vibb under lock task with Chrome pinned: a cold and a warm Chrome, tap-to-page time, Recents, a second tap | 14, 16; 17 owed | Chrome's window in its own task, no block screen |
| W11 | Chrome hidden by the device owner, then unhidden | 16 | the shortcut gone (§1.2); the reconcile drops the binding |
| W12 | The install step with Play suspended, on the owner's test account (squeue 5a), on the newest Chrome (after Play updates it) | 16 | Chrome falls back to a shortcut; a probable failure or a failure notification and nothing added changes §2.5. Also: a Play install started earlier in the same pause |
| W13 | The guard: each way out of W4 under the bundle; the return to Home and its latency; a login window, an install step and a pause exempt; the poll's battery and CPU while a web app is open | 14, 16 | Home within about 1 s |
| W14 | The first-open check for a web app and for W5's bookmark and "Install"; the unpin; the same entry installed again after the unpin and after a refused request | 16 | installed vs `not_installable`; the second install binds |
| W15 | A time rule starting with a web app open; its end | 14, 16 | the window stops, the tile dims, the shortcut kept, the tile opens it after |
| W16 | A Vibb One restart and a reboot | 14, 16 | bindings and pins kept, the tiles open |
| W17 | `persist()` in the installed window with the site's own prompt allowed, **now on the image's Chrome 133**; later with `NotificationsAllowedForUrls` (Chrome 142+, after a Chrome update on the test account); a 3 GB fill with the window closed (a service worker and Background Fetch) | 16 | persisted; the fill goes on with the window closed |
| W18 | A Chrome update through Play (test account) | 16 | pins kept, the tiles open |
| W19 | A manifest whose `start_url` or `scope` lies outside the entry's prefix, under the bundle | 16 | Chrome's blocked page in the web-app window; the check says installed (§3.2) |
| W20 | Web apps listed and installed: the bundle not ready (a write refused), the login role turned off, the role on then off with a window open | 14, 16 | Chrome suspended, never hidden; the pins kept; the tiles back when ready |
| W21 | A push to a listed site with Vibb managing the phone and the nag listener on; a Chrome download notification beside it | 16 | the push kept, the download notice cancelled |
| W22 | The guard over another app's Custom Tab outside a login (`CustomTabActivity` in that app's task), over Chrome's settings, downloads, history and bookmarks, over a tap on Chrome's download notification; a login window's bundle writes (start and end) and the time they add | 14, 16 | Home each time, with the class in the report; the login's start delayed by the read-back only |
| W23 | Usage access turned off with web apps installed | 16 | the tiles hidden, the PWA's orange line; the first-open check says `unchecked` |
| W24 | Chrome unhidden and not ticked: Chrome's launcher entry on Home and in the drawer (`AppFilter`) | 14, 16 | absent |

**Phase 3 - the Jelly, by the owner**: first Chrome's `version_name` (no account, so Play never updates it; the
Jelly's Chrome comes with Unihertz's updates, and below 142 the notification policies don't exist); then W1's install,
W2, W3, W10 and W17 with the owner's site; push in Doze (revision 2's T9). The
emulator can't show the Jelly's Chrome and Play versions, Unihertz's behaviour, real flash speed and storage pressure,
or battery.

**What decides the design**: W1 on the newest and the Jelly's Chrome (the fallback still a shortcut), W9 and W10 (A
works under Vibb at all), W11 and W20 (the hide rule), W13 and W22 (the guard), W21 (push kept), W12 (a phone with an
account). A failing W1, W9 or W10 makes B the path (revision 2's §3 in 91ca32f41) and revises
this note.

### Revision 2's rows (the first pass ran against these)

Section numbers in these rows and in the results below are revision 2's (91ca32f41) where they name §3.

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

**What the first pass means (revision 3):**

- **A WebAPK needs a Google account in Play on the phone** (R1). Without one Chrome falls back to its web-app shortcut,
  which Vibb One as Home must accept (§1.2, §3.2) - the reason this revision exists.
- **Minting doesn't need the site reachable from Google**: package names came back for `localhost` and `127.0.0.1`.
  It no longer matters for v1.
- **Storage doesn't depend on the install**: a tab, a Custom Tab and Chrome's standalone window share the origin's
  data and its `persist()` grant; notification permission is what earned it (§3.6).
- **Push needs no account**, only Chrome not hidden; under Chrome's name (R8). With Chrome unhidden (§3.4) and the nag
  rule's keep (§3.10) it works.
- Revision 2's rows still useful are folded into phase 1-3 above: T1/T2 (the cold and offline start) into W3 and W10,
  T3/T5 done (R4, R5, R7) except W17, T4 into W6, W15, W16 and W18, T6/T7 into W12 (a WebAPK no longer needed), T8 into
  W4 and W13, T9 to the Jelly, T10 and T13 gone (no web app windows), T11: R9's minting host is also called by A's install (§2.1), T12 into W15.

## 7. Size and steps

1. Step 0 phase 1 (§6, emulator, no build), then the owner's answers (§8).
2. Server (S): the table and migration, the policy key and contract, the Apps page group and its copy, the report's
   lines and the "Ikke installert"/"Kan ikke installeres" states from them.
3. Launcher (M, at its upper end): the pin activity and the install step (the pause screen's row, the step's
   one-site bundle, Play kept suspended while it runs); the pending-confirm-bound order and the reconcile
   (`onShortcutsChanged`); the tile, usage access as a requirement, `startShortcut`; the first-open check; the plan's
   `webAppsListed` (never hidden; suspended when not ready; BrowserConfig's suspend-before-loosen) and the clear keyed
   on the lockdown; the bundle (the web-app entries only outside a login window, the login sites per window, the
   notification policies and grant, `ChromeVariations`); the guard as an allowlist; the nag rule's `WEB_APP` keep; the
   report.
4. Code QA, the emulator gate on 14 and 16 (step 0's phase 2 with the real build), the Jelly with the site.

## 8. Questions for the owner

- **Q0. Chrome unhidden while web apps are listed** - a cost you see: it gives up design 119's option C (your ASK 1,
  "Chrome hidden except during a login Vibb opens"). Any allowed app can then open Chrome by name. What holds the kid
  instead: outside a login Chrome loads only the web apps' sites, and Vibb sends any Chrome window other than a web app
  back to Home within about a second (a moment of Chrome may show). Recommended: **yes**, A needs it (hiding Chrome
  deletes the web apps); without it the choice is B with Chrome hidden between uses (revision 2).
- **Q1. The engine: A, Chrome's own web-app shortcut accepted by Vibb One?** Recommended: **A** (no Google account,
  any installable PWA, Chrome's own app window). Each install still sends the site's manifest to Google first (Chrome
  tries a WebAPK). B (Vibb opens the site as a TWA) if W1, W9 or W10 fail; C (a TWA app per site) later for a site
  whose notifications should carry its own name; the WebAPK not in v1. This replaces "a WebAPK as the main path, a TWA
  the fallback" (2026-11-28).
- **Q2. Where the install happens**: in a pause, from the pause screen's row "Nettapper", the parent tapping Install
  in Chrome (your idea), Chrome held to that one site during the step. Recommended: **yes**.
- **Q3. Notifications show under Chrome's name** (the site's name as the title); Chrome's other notifications are
  removed as today. Recommended: **fine for v1**; a site's own name needs C (or a WebAPK, which needs an account).
- **Q4. A site Chrome can't install** (no manifest): recommended **not offered** in v1 - the PWA says "Kan ikke
  installeres"; the alternative is a TWA tile with the host shown (B).
- **Q5. The first web app clears Chrome once** on a phone without the login role (its tabs and sign-ins; nothing of
  the web apps exists yet), and a later clear (Chrome ticked and unticked) means installing again. Recommended:
  **yes**.
- **Q6. Usage access becomes a requirement for web apps** (the guard reads it): off means no tiles and an orange line.
  Recommended: **yes** (no weaker fallback).
- **Q7. Revision 2's open ones** ("vet ikke enda"): the list per phone (recommended); web apps under the phone's rules
  and screen time, with no per-site limit, and a time rule's usable list can't hold a web app in v1 (recommended); the
  Kiwix idea parked until W17 passes on the Jelly.

## 9. Design QA and revisions

- **Revision 2** took all 14 findings of the design QA (2H 5M 7L). Under A, H1 (one record per window), H2 (no session,
  no web app window), M3 (the filtered icon fetch), M4 (the verification cache), L2 and L5 (the `assetlinks.json`
  file), L4 (TWA notification delegation) apply only to B and C; M1 (the clear keyed on the lockdown, §3.4), L3 (the
  trailing `/`), L6 (a ticked Chrome, links from other apps) and "a removed site ends a login too" are kept; M5's
  ways out are W4 and W13.
- **Revision 3**'s design QA (4H 6M 6L), all taken in revision 4, none found wrong (each checked against the code or
  Chromium): H1 the nag rule would delete Chrome's web pushes - a `WEB_APP` keep by channel (§3.10, W21); H2 Chrome
  unhidden reverses design 119's C - the guard as an allowlist of the web-app window, the login sites in the allowlist
  only during a window, Q0 (§3.4, §3.5, W22); H3 the fallback rests on `kWebApkInstallFailureNotification` staying off -
  `ChromeVariations: 1`, W1/W12 on the newest and the Jelly's Chrome, the manifest to Google at each install (§1.1,
  §2.1); H4 the plan hides Chrome when not ready or with the role off - `webAppsListed`, suspend instead (§3.4, W20);
  M1 the pin activity's affinity (§3.2, W9); M2 usage access required (§3.3, W23, Q6); M3 the step holds Chrome to its
  entry, one request per step (§3.2); M4 a failed launch keeps the binding (§3.3); M5 pending, accept, confirm, bound
  (§3.2); M6 Chrome without Play updates - the Jelly's version first, W17 on 133 now (§3.4, §6); L1 a manifest outside
  the entry (§3.2, §5, W19); L2 reinstall and "Install" without a manifest (W5, W14); L3 Chrome's own notifications and
  promo (W4); L4 `hasShortcutHostPermission` logged (W9); L5 Chrome off Home and the drawer (W24); L6 time rules' usable
  list (§3.7, Q7). One adjustment: the guard's allowlist also lets Chrome's two launch trampolines through
  (`WebappLauncherActivity`, `SecureWebAppLauncher`), which come to the front for a moment at every launch.
- **Revision 4** (this one) is not design-QA'd yet.

## Sources

[arch]: https://chromium.googlesource.com/chromium/src/+/main/components/webapps/docs/android_architecture.md
[webapks]: https://web.dev/articles/webapks
[mupd]: https://web.dev/articles/manifest-updates
[inst]: https://web.dev/learn/pwa/installation
[wla]: https://chromium.googlesource.com/chromium/src/+/main/chrome/android/java/src/org/chromium/chrome/browser/webapps/WebappLauncherActivity.java
[wauth]: https://chromium.googlesource.com/chromium/src/+/main/chrome/android/java/src/org/chromium/chrome/browser/webapps/WebappAuthenticator.java
[sh]: https://chromium.googlesource.com/chromium/src/+/main/chrome/android/java/src/org/chromium/chrome/browser/ShortcutHelper.java
[wu]: https://chromium.googlesource.com/chromium/src/+/main/components/webapps/browser/android/java/src/org/chromium/components/webapps/WebappsUtils.java
[wis]: https://chromium.googlesource.com/chromium/src/+/main/chrome/browser/android/webapk/webapk_install_service.cc
[feat]: https://chromium.googlesource.com/chromium/src/+/main/components/webapps/browser/features.cc
[cman]: https://chromium.googlesource.com/chromium/src/+/main/chrome/android/java/AndroidManifest.xml
[pin]: https://developer.android.com/reference/android/content/pm/LauncherApps.PinItemRequest
[la]: https://developer.android.com/reference/android/content/pm/LauncherApps
[si]: https://developer.android.com/reference/android/content/pm/ShortcutInfo#getIntent()
[ss]: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/pm/ShortcutService.java
[bh]: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/pm/BroadcastHelper.java
[twa]: https://developer.android.com/develop/ui/views/layout/webapps/trusted-web-activities
[twaguide]: https://developer.chrome.com/docs/android/trusted-web-activity/integration-guide
[dalcache]: https://groups.google.com/a/chromium.org/g/chromium-dev/c/Ki3TZIGfmTY
[twaoff]: https://developer.chrome.com/docs/android/trusted-web-activity/offline-first
[bubblewrap]: https://github.com/GoogleChromeLabs/bubblewrap
[abh]: https://github.com/GoogleChrome/android-browser-helper
[ffmdm]: https://connect.mozilla.org/t5/ideas/enterprise-support-for-firefox-for-android-via-android-managed/idi-p/132883
[pol]: https://chromium.googlesource.com/chromium/src/+/main/components/policy/resources/templates/policy_definitions/
[storage]: https://web.dev/articles/storage-for-the-web
[persist]: https://web.dev/articles/persistent-storage
[oauth]: https://developers.googleblog.com/2016/08/modernizing-oauth-interactions-in-native-apps.html

- Chromium (read on `main` 2026-11-28): Android web app architectures [arch]; `WebappLauncherActivity` (the launch
  rule, the MAC check, the tab fallback) [wla]; `WebappAuthenticator` (HMAC-SHA256, the `webapp-authenticator` key in
  Chrome's files) [wauth]; `ShortcutHelper` (`addWebapp` vs `addShortcut`, the intent's extras) [sh]; `WebappsUtils`
  (`requestPinShortcut`, the `INSTALL_SHORTCUT` fallback) [wu]; `webapk_install_service.cc` (the shortcut after a failed
  WebAPK install, and none with the failure notification on) [wis]; `features.cc`
  (`kWebApkInstallFailureNotification` off by default) [feat]; Chrome's manifest (`WebappLauncherActivity` exported, `SecureWebAppLauncher`, `WebappActivity`
  `intoExisting`) [cman]; policy definitions (`supported_on`; `ChromeVariations`
  android 112+) [pol]
- Android: `LauncherApps.PinItemRequest` [pin]; `LauncherApps` (`ACTION_CONFIRM_PIN_SHORTCUT`, `startShortcut`,
  `hasShortcutHostPermission`) [la]; `ShortcutInfo.getIntent` ("Launcher apps cannot see the intent") [si]; AOSP
  `ShortcutService` (pins removed on package removal and data clear) [ss]; AOSP `BroadcastHelper.sendApplicationHiddenForUser`
  (hiding sends `ACTION_PACKAGE_REMOVED`) [bh]; trusted web activities [twa]
- web.dev: WebAPKs [webapks]; manifest updates [mupd]; installation (who mints, the shortcut's limits) [inst]; storage
  [storage]; persistent storage [persist]
- Chrome: TWA integration guide [twaguide]; offline-first TWAs [twaoff]; chromium-dev, DAL verification cached [dalcache]
- Bubblewrap [bubblewrap] and android-browser-helper [abh] (Apache-2.0); Mozilla Connect, Firefox for Android managed
  configuration (an open request) [ffmdm]; Google, OAuth in embedded WebViews [oauth]
- Ours: step 0's first pass (§6); design 102 (M11-M15, D-C4), design 119, `docs/notes/launcher/logins.md`,
  `docs/notes/launcher/push-and-play.md` (Play suspended), `docs/notes/launcher/hardening.md` (`enforceOnNewPackage`),
  `docs/notes/launcher/dns-and-vpn.md` (tsnet in-process), `Application.kt` (`onShortcutsChanged`, a TODO),
  `Functions.kt:125` (the shortcuts TODO), `timerules/AppGroupUsage.kt` (the class name), `timerules/UsageAccess.kt`,
  `badges/NotificationRule.kt` (`nagVerdict`), `server/EnforcementPlan.kt:261-276` (`roleApp`),
  `server/BrowserConfig.kt:96` (hide before loosen) and `:108`, `logins/LoginBrowser.kt:193`, `LinkGateActivity`'s
  manifest entry

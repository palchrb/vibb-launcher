# 143 - Installed web apps on the kid's phone

Status: design note, 2026-11-28, on `main` at 7a6129144, branch `pwa-design`. Docs only. The locked list's item 9b
("Installed PWAs on the kid's phone", moved from the inbox 2026-11-28; the owner: "Vil vi helst ha ordentlig native
pwa støtte"). Not built; step 0 (§6) and the owner's answers (§8) come first.

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
  `/.well-known/assetlinks.json`, Chrome's normal storage, Chrome out only while a web app is in front (design 119's
  window, widened). No Play, no Google minting, no new package to trust, no pause needed. The tile shows at the next
  sync. A WebAPK adds only what this use doesn't need (an entry in Android's app list, notifications under its own name,
  a share target) and is measured in step 0 so the owner can switch (Q1).
- **The offline store is Chrome's quota**: up to 60% of the disk per origin, best-effort unless `persist()` is
  granted, evicted only when the disk is nearly full (§1.5). A couple of GB fits. Whether a TWA's `persist()` is
  granted is the main unknown (step 0, T3).
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
A failed check falls back to a Custom Tab with the read-only host in a toolbar ([arch]). Storage is Chrome's profile,
the same as a WebAPK's ([arch]). A Custom Tab runs in the calling app's task, and under lock task needs Chrome pinned
(design 102 M11, M12).

### 1.4 Chrome's enterprise policies (Chromium's policy YAMLs, `supported_on`, read 2026-11-28 [pol])

| Policy | Android | Use here |
|---|---|---|
| `WebAppInstallForceList`, `WebAppSettings` | **no** (desktop, ChromeOS) | none: no forced install |
| `DefaultBrowserSettingEnabled` | no | none (Vibb One holds the browser role, design 119) |
| `URLBlocklist`, `URLAllowlist` | 86+ | the lockdown: the parent's sites, paths allowed (scope) |
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
  not named (step 0, T3).
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
| Web push | yes, under its own name, Chrome must stay out | yes, as Chrome | yes, as Chrome, Chrome must stay out | no |
| Time rules, groups | its own package, or Chrome's activity (T7) | Chrome | Vibb knows each window | Vibb |
| Works without Chrome | no | no | no | yes |
| Cost | M + the trust rule + Play's windows | M + a pin-request host | M | L (an engine to own) |

All of A-C need Chrome unhidden while the site runs: a hidden package is not installed for the user, so its WebAPK,
shortcut or TWA can't start.

**Why C.** It gives the kid the same thing (a tile, full screen, offline, the same Chrome storage) without the parts
that fight our setup: Play suspended, a tap in Chrome during a pause, a Google-signed package that Vibb would have to
let past `enforceOnNewPackage`, and the manifest of a family's private server sent to Google. The owner controls the
site, so the one file it needs is theirs to add. It reuses design 119's machinery almost whole: the Custom Tabs
session, Chrome's bundle and read-back, unhide-then-launch, hide-at-the-end. **The flip condition**: if step 0 shows
`persist()` denied in a TWA but granted in a WebAPK, and eviction at a free space a real phone reaches (T3, T5), A is
the better engine for a large offline site, and this note gets a revision. D waits until a phone without Chrome needs
it.

## 3. The design (option C)

### 3.1 The parent's list

- Per phone, like the login sites (`login_role_sites`): a new table `web_apps` (`device_id`, `url`, `name`,
  `position`), the next free migration. `https://` only; an entry is an origin plus an optional path (its scope), read
  by the same entry rule as the login sites (`loginEntry`, the shared `entries` rows).
- The policy gains `web_apps: [{url, name}]` (absent while empty), pinned by `policy_json_keys_snapshot` and
  `PolicyResponseCompatTest`; a new `contracts/core/web_apps.json` (the entry rule's rows and the Chrome allowlist
  rows) with its README row, both sides in one commit.
- The server never fetches the parent's URL (nothing on the server reaches a typed address). The phone reads the
  site's manifest for the icon (below).

### 3.2 Chrome's bundle

`browserConfigPlan` treats "web apps listed" like the login role: the lockdown is in force when either is on, and
`URLAllowlist` is the login sites, the web app entries and `LOGIN_CAPTCHA_PATHS`. Each entry is written in Chrome's
format with its path, so a scope stays a scope. Ready means the bundle read back typed (`sameBundle`), as today; not
ready hides the tiles. Removing a site is a tightening (Chrome's tabs end, design 119 Q102-M4).

### 3.3 The tile and the window

- **The tile**: on Home like an app, the parent's name for it, the icon from the site's manifest (the phone fetches the
  start page, its `<link rel=manifest>` and the icon nearest 192 px, size-capped, through the filter like any app
  traffic, cached in `noBackupFilesDir`; until then a letter tile).
- **The launch**: an anchor activity of Vibb One per site (`documentLaunchMode="intoExisting"`, its own task and
  Recents entry with the site's name and icon). A tap unhides Chrome under `LoginWindow`'s lock, binds the Custom Tabs
  session, and opens the site with `TrustedWebActivityIntentBuilder` (`androidx.browser.trusted`, in 1.9.0). Before
  the first open, `validateRelationship(RELATION_HANDLE_ALL_URLS, origin)` records whether the site is verified. A
  second tap brings the existing task back where the kid left it.
- **Lock task**: Chrome is pinned while web apps are ready, as for the login role. The anchor is visible when it starts
  the TWA, so Android 14's background-start rules are met.
- **Chrome out only while a web app or a login is in front** (Q2, recommended): the window is design 119's, without its
  5-minute ceiling. It ends when the tab is hidden past a minute's grace (`TAB_HIDDEN`/`TAB_SHOWN`; the grace counts
  only while the screen is on, so a screen-off doesn't end it), a time rule, a tightening, the list emptied, or a
  record of another boot. Chrome is hidden when no login window and no web app window is open (and no pause, override
  or Play's update window). Hiding stops Chrome; the stored content stays; the next tap opens the start page.

### 3.4 Staying on the parent's sites

- In a verified TWA there is no address bar and no menu. A link off the site opens Chrome's toolbar (close, the
  read-only host) and Chrome's "blocked by your organization" page unless the host is listed (design 102 M14).
- The way out to full Chrome (the toolbar's "Open in Chrome browser", an app starting Chrome by name) lands in Chrome
  under the same bundle: the parent's sites and the login sites only, no search, no incognito (design 102 M14). That is
  design 119's residue, now open while any web app is in front. Chrome's own settings are reachable from there; the
  kid could clear site data and lose the offline copy - an annoyance, not a way out (no Android policy forbids it).
- Unverified sites show the toolbar with the host the whole time; the PWA says so (§5).

### 3.5 The offline store

- Chrome's quota (§1.5) holds a couple of GB on any supported phone; the Jelly has room to spare.
- **Design 119's one-time clear of Chrome's data (D13) takes the offline copy too**: it runs at the role's first turn-on
  and after Chrome was a free app. The first is before any content; the second is a parent's choice, so a quiet note
  behind the (i) of Chrome's switch under Apps (§5), not a warning.
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

### 3.7 The DNS filter

Chrome uses the system resolver while the filter is on (design 67's `DnsOverHttpsMode`), so each site's host must pass
the parent's lists. The phone checks each entry with `classifyDomain` and reports a blocked one. Offline nothing is
asked. In "Bare Vibb" on cellular Chrome's traffic is sunk; a filled site still works from its store.

### 3.8 Notifications

Not in v1. Web push reaches a TWA's site through Chrome's FCM connection only while Chrome is not hidden, so it needs
Q2 (b). Then the bundle adds `DefaultNotificationsSetting: 2` and `NotificationsAllowedForUrls` (the parent's sites,
Chrome 142+), and the device owner grants Chrome's `POST_NOTIFICATIONS`.

### 3.9 The report

`web_apps` in the full report: per entry the host, `verified` (true, false, null until known), `filtered`, the icon
fetched or not, `last_open_ms`; and the window's last end, as `last_window` does for logins. Never a path or a page.

## 4. Android 14-17

| What | API / source | Note |
|---|---|---|
| TWA, Custom Tabs callbacks, DAL check | Chrome's version, not Android's | the same on 14-17 |
| `setApplicationHidden`, `setLockTaskPackages` | 21 | no gate |
| `documentLaunchMode`, `setTaskDescription` | 21 / 28 | no gate |
| Background activity starts | 14+ | every start from the visible anchor |
| Safer intents | 15 | the TWA intent is explicit to Chrome's package |
| `POST_NOTIFICATIONS` for Chrome | 13+ | only with §3.8 |
| Android 17 | - | by source; the gate on 17 when an image runs on the VM (the lowest and the highest touched) |

No version-specific path found in the sources; step 0 runs on 14 and 16.

## 5. The PWA (Words in the UI)

A group on the phone's Apps page, `#web-apps`, after the apps.

| Where | nb | en |
|---|---|---|
| Group heading | Nettapper | Web apps |
| Group (i) | Åpnes i Chrome, bare på disse sidene. For å vises uten adresselinje må siden ha denne filen på `/.well-known/assetlinks.json`: (the file, made from `LAUNCHER_SIGNATURE_CHECKSUM`) | Open in Chrome, on these sites only. To show without an address bar, the site needs this file at `/.well-known/assetlinks.json`: (the file) |
| Fields | Navn · Adresse | Name · Address |
| Button | Legg til | Add |
| Not https (inline) | Bare https | https only |
| Row | the name, the host quiet beside it; "Fjern" | the name, the host; "Remove" |
| Orange: not verified | Vises med adresselinje | Shows an address bar |
| its (i) | Legg filen over på siden. | Put the file above on the site. |
| Orange: the filter blocks it | Stoppet av filteret | Blocked by the filter |
| its (i) | Tillat siden under Filter. | Allow the site under Filter. |
| Orange: Chrome not ready | Chrome er ikke klar | Chrome isn't ready |
| its (i) | the login role's existing reason lines | |
| Chrome's switch under Apps, (i), quiet | Slås Chrome av igjen, tømmes Chrome - også nettappenes lagrede innhold. | Turning Chrome off again clears Chrome - the web apps' saved content too. |

A site that is fine shows nothing more. The kid's phone gets no new words: the tile is the name; a blocked link is
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
| T1 | TWA with `assetlinks.json` naming the probe: unhide Chrome, bind, open, under lock task; tap-to-page time | 14, 16 | full screen, no toolbar, in the probe's task |
| T2 | T1 again offline (airplane mode), after a reboot | 14, 16 | verified from the cache; the page from the service worker |
| T3 | `persist()` and `estimate()` in a TWA, an unverified tab, a WebAPK (T6), a shortcut if any; a 3 GB fill | 16 + the Jelly | the quota and whether persistence is granted per engine |
| T4 | The content after Chrome hidden and unhidden, a Chrome update, a reboot, `pm trim-caches` | 16 | kept |
| T5 | The disk filled to near-full with a dummy file, persisted vs not | 16 (`-partition-size` large) | best-effort evicted first; where it starts |
| T6 | "Install app" in Chrome with Play suspended, then in a pause; the package's name, signer and `startUrl` meta-data | 16 (Play image, test account) + the Jelly | suspended: no WebAPK (and no shortcut, Vibb as Home); in a pause: a WebAPK |
| T7 | A WebAPK under lock task: the packages that need pinning; the usage events' package; its task | 16 | for the Q1 choice |
| T8 | Links off the site, "Open in Chrome browser", a typed host in full Chrome | 14, 16 | Chrome's blocked page; full Chrome only on the list |
| T9 | Web push with Chrome hidden and not hidden; in Doze on the Jelly | 16 + the Jelly | hidden: nothing arrives |
| T10 | Each window end of §3.3; screen off within the grace | 14, 16 | as written |
| T11 | Which hosts Chrome contacts for the DAL check and for minting (the filter's log) | 16 | only the site for C |
| T12 | A time rule starting with the TWA open | 14, 16 | the window ends, the tile dims |

**What the emulator can't show**: the Jelly's Chrome and Play versions and Unihertz's behaviour; real flash speed and
real storage pressure at the Jelly's size; push delivery in Doze; Play's real WebAPK update timing (charging on Wi-Fi);
battery. Those rows (T3, T6, T9) also run on the Jelly with the owner's site. A failing row revises this note before
the build.

## 7. Size and steps

1. Step 0 (§6) - emulator S, then the Jelly rows by the owner.
2. Server (S): the table and migration, the policy key and contract, the Apps page group and its copy, the
   `assetlinks.json` text, the report's lines.
3. Launcher (M): the tiles and icon fetch, the anchor and TWA launch, the window in `LoginWindow`'s lock, the bundle,
   the plan's hide rule, the report; rows in `login_rules.json`/`web_apps.json`.
4. Code QA, the emulator gate on 14 and 16 (T1, T2, T8, T10, T12 again with the real build), the Jelly with the site.

## 8. Questions for the owner

- **Q1. The engine: C (Vibb opens the site, no install) or A (a real WebAPK, installed by hand in Chrome in a
  pause)?** Recommended: **C**; A only if step 0 shows the offline store safe only in a WebAPK (T3, T5). Note this
  changes item 9b's wording ("real WebAPK installs").
- **Q2. When is Chrome out?** (a) only while a web app or a login is in front - no web push, the first fill with the app
  open; (b) whenever a web app is listed - push and background downloads work, and full Chrome on the listed sites is
  reachable from inside a web app at any time. Recommended: **(a)** for v1, (b) when a site needs push.
- **Q3. The list per phone or per family?** Recommended: **per phone**, as the apps and the login sites.
- **Q4. Time**: web apps follow the phone's rules and screen time, with no per-site limit or group in v1. Recommended:
  **yes**; a site in an app group later.
- **Q5. The site's file**: you add `/.well-known/assetlinks.json` (the PWA shows its text) to your encyclopedia copy.
  Recommended: **yes**; without it the tile still works, with the host in a toolbar.
- **Q6. The Kiwix idea in the inbox**: recommended **parked until step 0's T3-T5 pass**, then closed.

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
  (Play suspended), `docs/notes/launcher/hardening.md` (`enforceOnNewPackage`)

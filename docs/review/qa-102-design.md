# Design QA: Wi-Fi sign-in pages and in-app logins without free Chrome (`docs/design/102-captive-portals-and-logins.md`)

Checked against `design-102` (b724662cd, = `main` 7f4afcd31 + the design). Read: root `CLAUDE.md`; the launcher's
`server/AppEnforcer.kt` (`applyHeld`, the hide/suspend loop, `BrowserDoh.apply`'s place, the four PPA writers,
`reseatPreferredActivities`), `lock/HomePin.kt`, `lock/BootCover.kt` (the cover's PPA), `server/EnforcementPlan.kt`,
`server/LockTaskHelpers.kt` (the features), `server/BrowserDoh.kt`, `server/DnsFilterEngine.kt` (`classifyDomain`,
`loadFromDisk`), `server/KidVpnService.kt:251`, `apps/AppFilter.kt` (`kidListable`), `Application.kt` (the process
guards), the manifest's processes, `build.gradle.kts` (minSdk 34); the server's `migrations/` (`main` ends at 0148),
`0146`/`0148` (the report-column pattern), `parent_events.rs` (`raise`, the dedup key), `tests/device_api.rs`
(`policy_json_keys_snapshot`); `design-100`'s design (its migration). The probe in the scratchpad `d102/` was
spot-checked: `probe/.../Portal.java` (M7: no `android:permission` on the probe's filter, a WebView in the probe's
own main process after `bindProcessToNetwork`) and `chrome2.json` (M14's bundle). No emulator, no `cargo test`.

**Verdict: the measurements are good and both mechanisms are the right ones (Vibb as the device owner's handler of
`CAPTIVE_PORTAL`; Chrome's own URL lockdown for logins). Part B is close to sound. Part A's window doesn't meet the
user's "uten å slippe fri chrome" (without letting Chrome loose), because D3 (follow any link, under the filter's
lists) lets a network that stays `CAPTIVE_PORTAL` while passing traffic turn the window into a browser. A kid can set
up such a network, and a 10-minute limit that can be reopened as often as the kid likes doesn't close it. The
migration number is taken by design 100.**

Counts: **1 High, 8 Medium, 9 Low.**

## Findings

### Q102-H1 High - the sign-in window is a browser on any network that stays captive while passing traffic (§3.2 "Link taps", D2, D3, D4, D6)

§3.2 says the remaining reach is "the walled garden" and "a network that never validates while it passes traffic",
and that "for both, the filter's lists and the time limit (D2) hold". Neither one limits browsing:

- **The filter's lists block categories. They don't limit browsing** (`classifyDomain`, `DnsFilterEngine.kt:65`: a
  blocklist plus FCM/Spotify exemptions). An ordinary site passes them, and so does a search engine.
- **The kid can build that network.** A laptop hotspot (or a travel router with a splash page) answers
  `connectivitycheck.gstatic.com/generate_204` with a 302 to `https://www.google.com` and passes all other traffic.
  NetworkMonitor marks it `CAPTIVE_PORTAL`: the http probe's redirect wins over a passing https probe. Android posts
  the notification, and Vibb's window opens on Google with link-following allowed (D3). Search results lead anywhere
  the lists allow. It also happens without a kid building anything: "soft" portals that intercept only port 80, and
  in-flight portals whose free tier passes some sites and never validates.
- **The time limit is per opening and can be restarted without limit** (§3.2 "The kid can open it again"): from the
  notification after a Wi-Fi toggle, from the Wi-Fi page and from Home's line (D5). D4 adds bedtime.
- **The parent can't see it.** D6 reports `first_host` and `blocked_hosts`, never the hosts visited. The `timeout`
  event says "too long", not "browsed YouTube-free sites for 40 minutes".

What would close it while keeping real portals working. These are for the user as D3/D2 alternatives, priced:

1. **Vibb's own "the network works" check** in `:portal`. Every few seconds, over the bound network, it sends https
   requests to the phone's own server and to a fixed 204 URL (`https://www.gstatic.com/generate_204`), and needs a
   valid chain. A portal can't fake a TLS answer. Once one passes, the network carries traffic whatever Android says.
   The window then closes with a new outcome `passes`, and doesn't reopen for that network while the check passes.
   This one rule kills the kid's own hotspot and the soft portal at no cost to a real portal. The one gap is a network
   that blocks exactly those hosts. Blocking the phone's own server also stops its sync, which the parent sees.
2. **Cross-site top-level navigations only by redirect or form submission.** A tap on a link, or a JS-started GET to
   a new registrable domain that no redirect reached, is refused. Portals hop hosts mostly by 302s and form posts. The
   cost: a "Log in with Facebook" link that points straight at facebook.com. That is D3's own "same registrable domain"
   alternative, made less costly.
3. **D6: report the registrable domains visited** (at most 20 per session), not only the blocked ones. Raise the
   Activity event for a session that visited more than the portal's first registrable domain plus a few, not only on a
   `timeout`.
4. **D2: a budget per network per day** (for example 3 openings or 20 minutes), then "Spør en voksen" / "Ask a
   grown-up" and one Activity event.

I'd recommend 1 + 3 as the minimum, and 1 + 2 + 3 if the user accepts the Facebook-link cost. D4 (allowed at bedtime)
is fine only once the window can't browse.

### Q102-M1 Medium - migration 0149 is design 100's (§6.4, §9 step 1)

`design-100` is being built with `0149_time_request_answers.sql` (`docs/design/100-*.md:804`, "the next free number on
`main` today after 0148"). Design 102 must take **0150** (`0150_login_windows.sql`) and recheck at merge. Both places
in the doc say 0149.

### Q102-M2 Medium - the `:portal` process would run the whole app's start-up (§3.2 "Its own process")

`Application.onCreate` returns early only for an isolated process (`Application.kt:178`) and for the boot cover's
(`:184`). A `:portal` process would run everything else a second time: PinLockRuntime, the screen receiver, the alarms,
`AssistantSetup.atProcessStart` (design 98's "a Wi-Fi step left open by a process death ends"), the CE setup, and so
on. That process is bound to the portal's network. The design must add a `:portal` guard like `isBootCoverProcess`.

The state the window needs should come in its start intent from the main-process entry, not from prefs read in
`:portal`. Multi-process SharedPreferences aren't coherent, and `DnsFilterEngine.loadFromDisk` reads
`LauncherPreferences` and the cached policy. That state is: the filter on or off, a pause or override, `minutes`, and
the Spotify flag.

Also: "the launcher's own login screens use WebView in the main process" isn't so. There is no WebView in launcher
code (grep: only `getCurrentWebViewPackage`). The data-directory suffix is still right.

### Q102-M3 Medium - Chrome is unhidden before its lockdown is written, and a failed write isn't fail-closed (§4.1 "The managed config: one owner")

- **The order.** Today `BrowserDoh.apply` runs at `AppEnforcer.kt:635`, after the hide/unhide loop (`:509-535`). If
  `BrowserConfig` takes its place, then on the role's first apply (and at every pause end) Chrome is unhidden and
  pinned before `URLBlocklist` exists.
- **A failed write.** If `setApplicationRestrictions` throws (`state: failed`), nothing in the design keeps Chrome
  hidden: Chrome is free until the next good apply. That is the "no insecure fallbacks" rule.
- **The fix.** Write the bundle first and read it back equal (`getApplicationRestrictions`). Only then does the plan
  unhide and pin Chrome. Anything else leaves Chrome hidden and unpinned, and the report says `failed`.
- **The package.** The launcher must accept `login_browser.package` only when it equals the constant
  `com.android.chrome` (D8). Today the policy carries any string, and a policy naming Firefox or a browser without
  policy support would unhide a free browser. Anything else is `failed`, and nothing is unhidden.
- **Bundle types.** `BrowserDoh` writes only strings (`BrowserDoh.kt:68`, `values.forEach(::putString)`). The table
  has ints and booleans, and M10 says CHOICE, BOOLEAN or STRING per key. Name each key's Bundle type in the
  `bundles` vectors, or `sameBundle` sees a difference at every apply (Integer ≠ String) and rewrites the bundle each
  time.

### Q102-M4 Medium - tightening Chrome doesn't evict what is already open (D9, §4.1 "A pause or the override")

`URLBlocklist` is checked on navigations. Three moments tighten Chrome: a pause ends, the role is turned on after a
phase where Chrome was ticked, and a site is removed. At each one, a tab that is already loaded keeps working:

- in a minimized Custom Tab;
- in full Chrome's task, which stays in Recents (OVERVIEW stays in the kiosk, `EnforcementPlan.kt:260`).

A single-page site's same-document navigations never meet the throttle. YouTube opened during a pause is watchable
afterwards for as long as the tab lives.

Fix: at each tightening, end Chrome's process in the same apply. Hide and unhide it (hiding stops the package), or
suspend it for the pass. On the role's first turn-on after Chrome was ticked, offer `clearApplicationUserData`: offline
pages, the reading list and history from the free phase remain otherwise. Add it to E6.

### Q102-M5 Medium - the preferred activity's off/re-seat story doesn't match the code (§3.1)

- **"Off: the filter is left out at the next rewrite… a rewrite re-adds every other one in the same pass."**
  `applyHeld` never clears. It only adds (`AppEnforcer.kt:344-348`). The one clear is `HomePin`'s re-seat
  (`AppEnforcer.kt:1706-1715`, `HomePin.kt:78`). It re-adds HOME, the Play link blocker, the call-log pin and the
  cover, not a new filter.
- **What goes wrong.** After a re-seat and before the next apply, both Vibb's (enabled) entry and the stock app
  match. Android then shows the chooser (M2). With the kiosk off, the kid can pick the stock app, which is unfiltered
  (M5-M6), and even tick "Always" (a normal preferred, which the persistent one overrides only once it is back).
- **The fix.** Add the captive-portal PPA to `reseatPreferredActivities` while the part is on. Say that "off" is the
  disabled component (PMS skips a PPA whose component doesn't resolve), not a rewrite.
- "The same steady-state check as the dialer's": name what that check is. Nothing named so was found.

### Q102-M6 Medium - the entry's fallback guard is forgeable (§3.1, E2)

If `android:permission="android.permission.NETWORK_STACK"` blocks the system's own start (E2), the plan is "its body
checks the caller: `getCallingUid()`/the referrer". Neither one works:

- In an Activity, `Binder.getCallingUid()` is Vibb's own uid.
- `getReferrer()` takes the caller's `EXTRA_REFERRER`.

Any allowed app that can fire an intent (an automation app, a malicious one) would then open Vibb's window on its own
URL: a link-following, unlimited-reopen browser (H1).

The check must be `Activity.getLaunchedFromUid() == Process.SYSTEM_UID`. That is API 34, and the minSdk is 34. The
window should also ignore a start whose `Network` isn't currently `CAPTIVE_PORTAL` and not `VALIDATED`, on both the
system path and the binderless one.

### Q102-M7 Medium - "the DNS filter applies inside the window" is narrower than written (§3.2, §7)

- **The parent's upstream.** A parent can choose their own upstream (design 36: their own AdGuard Home, a family
  resolver). It filters what passes the tunnel. The window resolves through the portal network's DNS, so it gets
  Vibb's lists only. Say so in §7 and in the switch's sub-line, or once the portal lets traffic through, have the
  window ask Vibb's upstream (DoT/DoH on a socket bound to the network).
- **What `shouldInterceptRequest` misses.** It never sees WebSockets or WebRTC. Service-worker fetches need
  `ServiceWorkerController`'s own client: turn service workers off in `:portal`, or set that client with the same
  check. List these in the window's rules so the build doesn't assume a full cover.

### Q102-M8 Medium - preset rows frozen at tick time, and the key can't hold what §6.4 promises (§5.2, §6.4)

- **Frozen hosts.** Ticking a preset copies `logins::PRESETS`' hosts into `login_browser_sites`. D-C6 is expected to
  change those hosts, and a phone that already ticked the preset never gets the correction.
- **The key.** `PRIMARY KEY (device_id, entry)` can't hold an own row and a preset row of the same entry. Yet §6.4 says
  "an own row with the same entry stays". Adding an own `accounts.google.com` after ticking Google either fails or is
  swallowed. Unticking then removes the parent's own entry.
- **The fix.** Store the ticked presets as a set (a `device_policy.login_browser_presets` column), keep only own rows
  in the table, and expand `PRESETS` when the policy is built. The page's tick state is then exact. A preset fix is a
  server release, with no migration.

### Q102-L1 Low - D1's reason is wrong for a kiosk phone

"Narrower than anything the kid can reach today" holds with the kiosk off. On a kiosk phone, the common case, the kid
reaches nothing today (M3), so Part A is a new surface. Give the user that comparison. With an older server, the
parent also has no switch to turn it off (§6.1). Default on is still defensible once H1 is fixed.

### Q102-L2 Low - allowed TWA apps change when the role turns on (§4.1, §5.2)

Today, with Chrome hidden, a Trusted Web Activity app (Bubblewrap) falls back to its WebView. With the role on, it
opens in Chrome and gets "… is blocked" unless its host is listed. The page's note should say so. The launcher could
report the allowed apps' `android.support.customtabs.trusted.DEFAULT_URL` hosts as suggested rows. The same goes for
in-app article links in general: expected, but the PWA's note could name it.

### Q102-L3 Low - Chrome bundle and device checks

- Add `ContextualSearchEnabled: false` (Touch to Search opens a Google results panel) and
  `AllowDinosaurEasterEgg: false` (the offline game).
- `chrome://` is exempt from `*` since 147, so consider explicit entries (`chrome://flags`, `chrome://dino`).
- Add to D-C3/D-C4:
  - typed `data:`, `view-source:`, and a typed `javascript:` on an allowed page;
  - "⋮ › Help & feedback" (GMS help under the kiosk);
  - the "Install app" WebAPK path besides "Add to Home screen";
  - the policy at Chrome's cold start right after boot and after a Chrome update (that it applies before the first
    navigation).

### Q102-L4 Low - make the window's WebView settings explicit (§3.2)

Set these explicitly:

- `allowFileAccess`/`allowContentAccess` false (file access defaults true below API 30 for older targets, so don't
  rely on the default);
- no `addJavascriptInterface`;
- web contents debugging off in release;
- Safe Browsing on;
- `setDownloadListener` refusing;
- the text-selection action mode trimmed to Copy/Select all. "Web search", the `PROCESS_TEXT` items and smart
  selection's "Open"/"Call" start other apps; Chrome is pinned when the role is on.

Long-press on a link has no context menu in a plain WebView. Keep it so.

### Q102-L5 Low - PWA strings and the save rule (§3.5, §5)

- **English missing.** These have no English:
  - the filter page row's sub-line "Wi-Fi: på · Apper: av";
  - "Chrome er tillatt som vanlig app…";
  - "Sperret av nettfilteret – legg den til…";
  - "Venter på telefonen", "Krever nyere Vibb One", "Chrome mangler", "Kunne ikke settes";
  - "Chrome finnes ikke på telefonen";
  - the Apps page's reverse note.
- **The save mechanism isn't named.** It should be `autosave.js` with `data-autosave` for the two switches, and an
  anchor redirect (`#logins-sites`) for add, remove and preset.
- **Safari 14.** Not mentioned. Server-rendered forms are fine; say so.

### Q102-L6 Low - the Activity events need their kind, key and push rule (§3.5, §10)

Every report repeats the last 10 sessions, so the dedup key must be the session's `opened_ms` (`parent_events.rs`,
`ON CONFLICT DO NOTHING` on `(device_id, kind, dedup_key)`). Name the kinds. Say whether they push: a hotel at
bedtime is the case, and there are no pings after 23:00, so `Push::Held` or none.

### Q102-L7 Low - §4.1 contradicts itself, and the MIT directory

- "`kidListable` drops the role's package … in a window too" vs. "in a pause … Chrome is listed as any unhidden app
  (in a window every app is)". Say which one holds.
- `AppFilter.kt` is in the MIT `apps/` directory. Put the rule in a GPL pure function (as `playPackageLaunchable` in
  `play/`) and pass it in, as earlier designs did.

### Q102-L8 Low - contract details (§6)

- `login_browser: null` needs a nullable DTO field: `policy_json_keys_snapshot` fails on a null for a non-nullable
  one. State it.
- State that `captive_portal`/`login_browser` count in the heartbeat hash (full-report fields), so a new session
  forces a full report.
- The CI path lists and the root `CLAUDE.md` paragraph are named. Good.
- `scripts/fake-portal.py` also belongs in the root `CLAUDE.md` scripts list and in `launcher.yml`'s `--selftest`
  set.

### Q102-L9 Low - the binderless re-probe (§3.1, §3.2)

NetworkMonitor rate-limits app-requested re-evaluations, so `reportNetworkConnectivity` every 3 s may be ignored. Put
that in E4. The window must check at open that the network is `CAPTIVE_PORTAL` and not `VALIDATED` (see M6).

## The D-items

| # | Verdict |
|---|---|
| D1 | Sound once H1 is fixed; fix the reason (L1) |
| D2 | Not a limit as written; add a per-network daily budget (H1.4) |
| D3 | **Change**: add the TLS "network works" close (H1.1); offer redirect-only cross-site as the alternative (H1.2) |
| D4 | Sound only after H1 |
| D5 | Sound |
| D6 | **Widen**: report the registrable domains visited (H1.3) |
| D7 | Sound |
| D8 | Sound; the launcher must enforce it (M3) |
| D9 | Sound under the pause rule, but needs the eviction at the pause's end (M4) |
| D10 | Sound |
| D11 | Sound; store the presets as a set (M8) |
| D12 | Sound ("not now") |

## Couldn't verify

- M1-M15 beyond the probe's source and `chrome2.json`. E2 (whether system_server's start passes the `NETWORK_STACK`
  guard) is open.
- The Chromium/AOSP source claims:
  - `chrome://` exempt from `*` since 147;
  - the throttle order behind M15;
  - NetworkMonitor's http-portal-over-https result (H1 relies on it; Android's documented behaviour, not re-read);
  - the re-evaluation rate limit.
- Chrome's handling of loaded tabs when `URLBlocklist` tightens (M4 assumes no eviction).
- Whether the screen-time budget counts Chrome's foreground time under the role.

## Revision 2

Checked against `design-102` 3fb9ac2bf (revision 2: the "Revision 2" summary, §3.1-§3.6, §4.1, §6, §12, §13). Read
again: `Application.kt:170-200` (the guards), `KidAppComponentFactory.kt`, `AppEnforcer.kt` (`reseatPreferredActivities`
:1706, `applyDateTimeLock` :1391, `applyPrivateDnsLock` :1497), `EnforcementPlan.kt:267` (`lockDateTime`),
`DnsFilterEngine.kt` (`classifyDomain` :65, `loadFromDisk`, `writeAtomically`), `res/xml/network_security_config.xml`
(main and debug) and `ServerTransportTest`'s pin of it, `docs/notes/launcher/security.md` ("Cleartext"),
`parent_events.rs` (`Push`, `raise`); the migrations on every branch (`main` and `design-102` end at 0148, `design-100`
has `0149_time_request_answers.sql`, nothing else claims 0150). Android's `ActivityOptions.setShareIdentityEnabled`
reference (developer.android.com). No emulator, no `cargo test`.

**Verdict: every first-round finding is taken, and H1's four parts are all in. H1 is closed down to one residue - a
network built on purpose to fail Vibb's check - which is bounded (20 minutes a day) and reported, but wider than §7
says. Two new Highs stop Part A from working at all as written: the app's network security config forbids the http a
portal is made of, and `getLaunchedFromUid()` can't see the system's uid. Both have clean fixes; the first needs the
user (it reverses part of S-M1).**

Counts: **2 High, 4 Medium, 6 Low.**

### Q102-R2-H1 High - the window can't load http: cleartext is off for the whole app (§3.2, new)

- `res/xml/network_security_config.xml`: `<base-config cleartextTrafficPermitted="false">`, one exception
  (`tailnet-server.vibb.invalid`; debug adds `10.0.2.2`). `ServerTransportTest` pins exactly that ("cleartext is off but
  for the tailnet name"). It was the user's S-M1 decision of 2026-11-04.
- WebView honours the app's network security config for cleartext (targetSdk 26+): an http load ends in
  `net::ERR_CLEARTEXT_NOT_PERMITTED`. A network security config is per app, not per process, and names hosts, never
  ranges, so `:portal` can't be exempted alone.
- A portal is http by nature: Android's probe URL (`http://connectivitycheck.gstatic.com/generate_204`, the binderless
  start's first load), the intercepted redirect, `CAPTIVE_PORTAL_URL` (M7: `http://10-0-2-2.nip.io:8103/login`), and
  portals on IP literals (`http://1.1.1.1/login.html`, `192.168.x.y`). The probe in §1 was its own app with its own
  config, so M7 doesn't cover this. The design never mentions the config.
- Proxying http through `shouldInterceptRequest` doesn't work: it gets no POST body, and portal forms post over http.
- The debug config's `10.0.2.2` exception would hide it in the emulator loop, if the fake portal is reached by that
  address.

Fix, as a new D-item for the user (D15): (a) `cleartextTrafficPermitted="true"` in the base config, system CAs only
as now, with S-M1 carried by the code that already refuses a non-https server path before a socket
(`ServerTransport`'s `Closed`/`NOT_HTTPS`), and `ServerTransportTest`'s pin rewritten to pin that instead, plus a test
that every server client still refuses `http://` to a non-tailnet host; or (b) the window as a separate small APK with
its own config (a new package to sign, install, pin and update). I'd recommend (a). E1 must reach the fake portal by a
hostname (`nip.io`), never `10.0.2.2`.

### Q102-R2-H2 High - `getLaunchedFromUid()` returns `INVALID_UID` for the system's start (§3.1, M6's fix)

- Since API 34, `Activity.getLaunchedFromUid()`/`getLaunchedFromPackage()` return the launcher's identity only when it
  opted in with `ActivityOptions.setShareIdentityEnabled(true)` (default `false`), or the activity is platform-signed.
  ConnectivityService's `startCaptivePortalAppInternal` starts the intent with no options (§1: "sets no package or
  component", M2's `from uid 1000`). Vibb is not platform-signed. So the check sees `Process.INVALID_UID` on every real
  start, and the entry refuses all of them: Part A never opens from the notification.
- It fails closed, but E2 would find it during the build, and the easy patch (accept `INVALID_UID`) makes the entry
  forgeable again: M6's hole.
- **The fix: `android:permission` is the guard, not the fallback.** A signature permission (`NETWORK_STACK` or
  `NETWORK_SETTINGS`) on the entry: system_server's start runs as uid 1000, which passes every permission check
  (`checkComponentPermission`), and no installable app can hold either. Keep the network checks (they bound a forged
  start anyway: it could only open the window on a network that really is captive, but it would choose the first URL).
  Drop `getLaunchedFromUid` from §3.1, §7 and §10, or keep it as a log line only.
- E2: the system's start passes; a start from a second, ordinary app is refused. Not the shell: it holds many network
  permissions, and adb isn't the kid's way in.

### Q102-R2-M1 Medium - H1's residue is wider than §7 says, and the cross-site rule has three gaps (§3.2, §7, D3)

Against a network built to fail the check (a travel router or laptop hotspot that blocks `www.gstatic.com`,
`cp.cloudflare.com` and the server by DNS and passes everything else):

- **No form post is needed.** The probe's own 302 can point at any site the lists allow (YouTube, Google), and the
  initial load's redirect is allowed. Same-site taps are then free there. §7 describes a form post to one site.
- **The "current site" moves with every redirect**, so any same-site open redirector (a 302, or a page that sets
  `location`) carries the kid onward: a tap within the site, then a redirect out. Such redirectors are common on large
  sites.
- **Sub-frames aren't judged by the site rule** (§3.2 judges "a main-frame GET"). A full-page iframe of another site
  is a browser inside the window, and its own navigations go anywhere the lists allow.
- **Sub-resource redirects skip `shouldInterceptRequest`** (it is called once per request, not per redirect hop), so a
  sub-resource on an allowed host that redirects to a blocked one isn't filtered. List it with WebSockets and WebRTC.
- **`visited` caps at 20**, so a long session is under-reported. Add a `visited_count`.

The bound still holds: at most 20 minutes a day, 4 openings, each site named to the parent, the Activity event. So H1
is closed to "a deliberate rig gets 20 reported minutes a day", not "can't browse". Put that sentence in §7 and in D3,
so the user decides on the true residue. Cheap tightenings that keep real portals:

- A cross-site redirect is allowed only when its source or its target is a **portal site**: the first page's site and
  sites reached from a portal site by a form post. Sites reached by redirect (vendor auth, Facebook) are leaves: they
  may redirect back to a portal site, not onward. That kills the open-redirector chain.
- Sub-frame navigations to another site are judged like main frames (a tap is refused; the page's own load is
  allowed), and their sites count in `visited`.

### Q102-R2-M2 Medium - the cross-site rule refuses portals that redirect by meta refresh or script (§3.2, D3)

`WebResourceRequest.isRedirect()` is true only for server redirects. A `<meta http-equiv="refresh">` or an onload
`window.location = …` is a new navigation with `isRedirect() == false`. Real portals do this: the intercepted http
answer is a 200 page that refreshes to the controller's login (a virtual IP such as `1.1.1.1`, or the vendor's host),
or a "Redirecting…" page. In Vibb's window the first page's site is the probe host's (`gstatic.com`), and the jump is
refused as "a script's `location` to another site". The first QA's H1.2 ("portals hop hosts mostly by 302s and form
posts") understated this.

Fix: on a portal site (R2-M1), a cross-site main-frame GET with `!hasGesture()` (the page's own code or a meta refresh,
not the kid's tap) is allowed, as a redirect is. A tap (`hasGesture()`) is still refused. Add a meta-refresh mode and
a script mode to `fake-portal.py`, a `navigation` row for each, and E5.

### Q102-R2-M3 Medium - the https check false-passes on a portal that offers "Log in with Google" (§3.2)

- A portal whose walled garden allows Google sign-in lists `*.gstatic.com` (and `accounts.google.com`,
  `*.googleapis.com`). `GET https://www.gstatic.com/generate_204` then passes with a valid chain, while the internet
  does not. "One pass means the network carries traffic" closes the window as `passes`, and the network is marked so
  the entry refuses it while it stays connected. The kid can't sign in at that hotel at all, and Android keeps its
  notification.
- **The fix**: a pass needs **two operators**: `www.gstatic.com` **and** `cp.cloudflare.com`, or either one and the
  phone's own server. It costs no security: the rig blocks two names as easily as one.
- Replace the sticky "marked passing" with a fresh check at each opening (the entry already runs one), so a wrong
  pass isn't kept for the whole connection.
- Name the check client's rules: `followRedirects(false)`, an exact 204 with an empty body, the fixed constant hosts
  (never from the policy). Its lookups don't go through the window's `classifyDomain` (they never need to), and it
  doesn't touch the tunnel (Vibb is excluded from its own VPN).

### Q102-R2-M4 Medium - the snapshot is frozen for the window's life (§3.2 "Its state", "A pause", "The PIN lock")

- **A pause.** A window opened during a pause has no filter, no time limit, no budget and no cross-site rule (§3.2
  "A pause or the override lifts the window's limits"). Nothing ends that when the pause ends, so on a check-failing
  network it is an unlimited browser after the pause.
- **No signal into `:portal`.** "The window finishes when the lock engages", the parent turning Part A off, and a
  changed filter list all need one, and none is designed. The snapshot goes one way, at the start.
- **The fix**:
  - The snapshot carries the pause's end. At that moment the window applies the normal rules, with `minutes` counted
    from then, or simply closes.
  - The main process tells `:portal` to finish on the PIN lock, on Part A off and at the pause's end: an explicit
    broadcast to a non-exported receiver registered in `:portal` (same uid only), or a bound service.
  - A `close` row in `login_rules.json` for "opened in a pause, the pause ends".

### Q102-R2-L1 Low - disabling the entry needs `DONT_KILL_APP` (§3.1 "Off is the disabled component")

`setComponentEnabledSetting` on Vibb's own package kills its process unless the flag is `DONT_KILL_APP`. Without it,
each turn of the switch kills Home, the VPN service, the call services and the lock runtime. Name the flag. The
mechanism itself is sound (`findPersistentPreferredActivity` matches only components in the intent's resolve list, so
a disabled entry falls through to the stock app).

### Q102-R2-L2 Low - the budget's edges (§3.3)

- **The clock.** "The date and time locks hold" only while `lockDateTime` (`EnforcementPlan.kt:267`: apps, calls, SMS
  or time rules managed). Store the latest date seen and never treat an earlier date as a new day. That is a line,
  not a dependency.
- **One window at a time.** The entry must bring the open window forward (or refuse) while a session is open.
  Otherwise two windows each get the snapshot's "time left".
- **`grant`.**
  - Say what a jump by more than one adds.
  - On a fresh install or after a data loss, the phone stores the current `grant` as used without adding anything.
    Otherwise an old counter gives a free opening.

### Q102-R2-L3 Low - `wifi_signin_wide` will fire on ordinary sign-ins (§3.6)

"Had blocked hosts" counts sub-resources, and portal pages load analytics and ad hosts that the lists block. Nearly
every hotel sign-in would raise an event. Count blocked main frames and sub-frames only. Keep `blocked_hosts` in the
session for the list.

### Q102-R2-L4 Low - the tightening and the clear (§4.1, D9, D13)

- `chromeTightened`'s "the written allowlist shrank" must mean "some old entry is gone" (a set difference), not a
  smaller count: a swap of one site for another is a tightening.
- D13's "Chrome was a ticked, free app": the phone has no record of that from before this version. Say whether
  "unknown" clears (I'd say yes, once).
- The pause (D9) leaves the same remains as a free phase (history, logged-in sites). Say why no clear is needed
  there: restored tabs reload through `URLBlocklist`.
- D13 is a visible cost (the kid's web logins in Chrome are gone). The role's switch, or a line under it, should say
  so before the parent turns it on.

### Q102-R2-L5 Low - two small mismatches with the code

- **The snapshot's "the parent's allowed domains".** `classifyDomain` (`DnsFilterEngine.kt:65`) takes the list, the
  Spotify flag and the DoH hosts, and has no allowed-domains input. Name where they apply (the server's compiled list,
  I believe), or drop them from the snapshot. Do include the DoH hosts asset.
- **D-C3 reads `chrome://policy`, but the bundle now blocks `chrome://*`.** Confirm Chrome keeps `chrome://policy`
  reachable under a blocklist, or read it with a test bundle without that entry.

### Q102-R2-L6 Low - the checks owed

- E1/E3 through a hostname, not `10.0.2.2` (R2-H1).
- E2 with a second app, not the shell (R2-H2).
- E4 also with a "Google walled garden" mode (gstatic passes, nothing else does; R2-M3).
- E5 with the meta-refresh and script modes (R2-M2), and a full-page iframe (R2-M1).
- E9-style: a window opened in a pause, then the pause ends (R2-M4).

### The first round's findings, as handled

| Finding | Verdict |
|---|---|
| H1 | Taken in full. Closed to R2-M1's residue; R2-M2 and R2-M3 are the new rule's own costs |
| M1 | Right: 0150 is free on every branch (`design-100` has 0149, nothing claims 0150) |
| M2 | Right. The guard, the snapshot and a session file with an explicit broadcast to a non-exported receiver (same uid only, so no app can send it). The component factory is already safe (`instance` stays null). The reverse signal is missing (R2-M4) |
| M3 | Right. Written, read back typed, then unhidden; the package constant; the types in the table match M10's schema |
| M4 | Right. Hiding kills the package; Chrome's restored tabs reload through the blocklist (not verified on a device) |
| M5 | Right, plus `DONT_KILL_APP` (R2-L1) |
| M6 | The diagnosis was right; the chosen API can't work (R2-H2) |
| M7 | Right; add sub-resource redirects (R2-M1) |
| M8 | Right. A set column, own rows apart, expanded at build; the contract has the "own entry equal to a preset" row |
| L1-L9 | All taken. L6's dedup and `Push::Held` match `parent_events.rs`; L8's nullable field and full-report fields are stated |

The API and contract rule is met: one contract file, byte-identical in both directories, the launcher's half in step
1's commit, both CI path lists, the root `CLAUDE.md` paragraph, and the policy keys pinned by
`policy_json_keys_snapshot`/`PolicyResponseCompatTest` with `login_browser` nullable.

### The D-items

| # | Verdict |
|---|---|
| D1 | Sound once R2-H1/H2 are fixed. The text should carry R2-M1's residue, not "can't change site except by the portal's redirects" |
| D2 | Sound (per phone, 4 openings, 20 minutes), with R2-L2's edges |
| D3 | Sound as a choice. Add R2-M2's `!hasGesture()` allowance and R2-M1's portal-site rule. State the true residue. A third option for the user: no cross-site at all except by form post |
| D4 | Sound now that the window is bounded |
| D5 | Sound |
| D6 | Sound; fix the trigger (R2-L3) |
| D7, D8, D10, D11, D12 | Sound |
| D9 | Sound under the pause rule; the window side needs R2-M4 |
| D13 | Sound, automatic. Show its cost on the switch, and settle "unknown" (R2-L4) |
| D14 | Sound and one-shot, with R2-L2's `grant` edges |
| D15 (needed) | Cleartext for the app, or a separate portal APK (R2-H1). Recommend allowing cleartext in the config, with the server-path rule kept in code and re-pinned by tests |

### Couldn't verify

- **WebView and cleartext.** That WebView refuses http under this app's config (`ERR_CLEARTEXT_NOT_PERMITTED`) is from
  the platform's documented behaviour, not run here. One `loadUrl("http://…")` in a debug build of the real launcher
  settles it, at a hostname outside the debug exception.
- **ConnectivityService's start.** That it passes no `ActivityOptions` (so `INVALID_UID`) is from AOSP as I know it;
  the API reference confirms the opt-in rule.
- **WebView's hooks:**
  - whether `shouldOverrideUrlLoading` is called for the redirects of the first `loadUrl`;
  - `hasGesture()` on meta-refresh and script navigations;
  - `shouldInterceptRequest` and sub-resource redirects.
- **Chrome:** tab restore after the kill; `chrome://policy` under `chrome://*`.
- **Real portals:** how many whitelist `*.gstatic.com`, and how many use client-side redirects (D-C2 should log both).

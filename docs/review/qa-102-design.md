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

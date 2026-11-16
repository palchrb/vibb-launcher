# Code QA: design 102 (Wi-Fi sign-in pages and in-app logins without free Chrome)

Branch `design-102`. Reviewed against `docs/design/102-captive-portals-and-logins.md` (revision 5 authoritative, then 4,
3, 2; §12 decisions, §13 the design-QA mapping) and `docs/review/qa-102-design.md`. Commits reviewed:

- the main merge: fb686dc5c;
- step 1 (server, contract, migration 0150): a63b3d4b1;
- step 2 (the Wi-Fi sign-in window, the entry, the parent code, the https check, the site rule, the budget, the reopen,
  cleartext with the source scan): 0fd3de9d9;
- step 3 (Chrome's login role, `BrowserConfig`): 17b3850ca;
- the docs: 23967ec1d.

This is a reading review, with these checks run:

- `cargo check --tests` passes; `cargo fmt --check` passes;
- `node --test jstest/` passes (389/0);
- `python3 -I scripts/fake-portal.py --selftest` passes (9 modes);
- `cmp` of `server/testdata/login_windows.json` and `launcher/app/src/test/resources/login_windows.json`: identical;
- the 76 new catalog keys: in both catalogs, with the same `{parameters}` in en and nb;
- `git merge-tree` of today's `main` (design 100 merged) into the branch.

Not run: `cargo test`, clippy, Gradle (reported green), and anything on a device or emulator (E1-E13 and D-C1 to D-C6
are still owed).

**Verdict: no High, 3 Medium, 9 Low.** I found no way for the kid to get free browsing or to leave the kiosk through
the new code while its bounds hold. Two Mediums are gaps in those bounds:

- the filter's lists are not loaded yet when the window's first page loads (M1);
- a window opened in a pause outlives an early end of the pause (M2).

The third Medium is the merge with today's `main`, which conflicts in three files (M3). I found no server test that
should fail in CI on the branch as it stands.

## The hard requirement: no free browsing

What I checked and found holding:

- **The entry and its guard.**
  - `CaptivePortalEntry` is exported only under `android:permission="android.permission.NETWORK_STACK"` and is
    disabled until Part A is on.
  - No component of Vibb forwards an intent it was handed. I searched for `getParcelableExtra(..Intent..)`,
    `parseUri` and `EXTRA_INTENT` and found none, so no app can reach the entry through our uid.
  - The window, `PortalSessionReceiver` and the window's close receiver (`RECEIVER_NOT_EXPORTED`) are not exported.
- **The approval order.** `portalApprove` (`PortalRules.kt:256`) checks, in order:
  1. the PIN lock;
  2. one window at a time;
  3. the network;
  4. the https check;
  5. the pause;
  6. the code;
  7. the requirement off within the budget;
  8. a grant within the budget;
  9. the keypad.

  Some details:
  - `ParentPinDialog` calls `onOk` only on `ParentPinResult.Ok`.
  - `NotConfigured`, `ForeignAccess`, `untilSync` and a lockout disable OK, so no parent code set fails closed.
  - `WIFI_SIGNIN` has no `windowDuration`, so the code is one-shot, and it shares the lockout.
  - The code is asked in the main process; `:portal` holds no code.
  - D17 holds: `chargeOpening` charges `none` and `grant` only.
- **The WebView.** It has:
  - no file or content access, no JS interface and no multiple windows;
  - JS window opening off, mixed content never, Safe Browsing on;
  - permissions and geolocation denied, file chooser refused, downloads refused;
  - service workers blocked, `TextClassifier.NO_OP`, and the action mode trimmed to Copy and Select all;
  - TLS errors final (`cancel()`), and debugging in debug builds only.

  `tel:` goes to Vibb's own dialer; every other scheme is refused in SOUL. Sub-frames are never left to the WebView to
  launch anything (a WebView doesn't start intents itself).
- **The site rule.** `portalNavigation` matches the design's §3.3 table:
  - a tapped GET's chain may not leave the tapped link's site (the same-site redirector);
  - the page's own move or a POST may leave only from a portal site;
  - only a POST makes a new portal site;
  - frames are judged in SIR by `Accept`;
  - a reopen keeps to `recordedSite` by every means.

  The `navigation` rows cover the cases the design lists.
- **The https check.** `PortalCheck` uses fixed `https://` constants, the bound network's socket factory and DNS, no
  proxy and no redirects. A pass needs two operators. The server is asked only when exactly one of the two passed, and
  only for a public `https` address. Its client is the third named `OkHttpClient.Builder(` in the scan.
- **The reopen.** It keeps the first page's site only:
  - a record is kept only for a `sign_in` that ended `validated` or `passes`;
  - the boot count is checked, and the records are deleted when the boot count moves (`PortalRuntime.start`);
  - records are deleted when Part A is turned off;
  - they are filtered by today's date, and nothing of them is logged.
- **Chrome's role.**
  - `loginRoleOf` accepts only `com.android.chrome`, and a ticked Chrome is `Off`.
  - `BrowserConfig.apply` runs before `computeEnforcementPlan` (`AppEnforcer.kt:445`). It writes the bundle, reads it
    back and compares it typed, and only then says `ready`. `loginBrowser` is passed only when `ready`.
  - In the plan, the role's app is unhidden but never in the accessibility or input-method lists. It is pinned only
    outside a lock, and suspended by a lock (`EnforcementPlanTest`).
  - A tightening is a set difference or absent → present. It hides Chrome after the new bundle verified, and the plan
    unhides it.
  - `AppFilter` drops the role's package in every state, pause included.
  - D13's clear runs once, and a ticked Chrome forgets the record.
- **The pause.** A pause opens the window as `pause`, with the filter and the site rule lifted while it runs. That
  matches D9 and §3.3, but see M2 for an early end.
- **The cleartext change.**
  - Both configs allow cleartext, with `tools:ignore`, system CAs only, and the tailnet name kept.
  - The tailnet path gets `tailnetFence`. Its origin is the `tailnet-server.vibb.invalid` base, so the normal requests
    pass.
  - `buildDohClient` refuses a non-https URL (`DOH_HTTPS_ONLY`).
  - Every server client still comes from `ServerTransport`.
  - The source scan covers every `.kt` under `src/main/java/com/kidslauncher/mdm`, which is the only source root, and
    the launcher has no image-loading or HTTP library of its own (no Coil, Glide, Cronet or Media3). See L4 for the
    scan's own gaps.
- **The `:portal` process.**
  - `Application.onCreate` returns after `preparePortalProcess()`, before `instance` is set.
  - The window touches no preferences: `classifyDomain`, `isFcmHost` and `isSpotifyHost` are pure.
  - The state crosses processes only as files in `noBackupFilesDir/portal/` plus an explicit broadcast to the
    non-exported receiver.

## Medium

### M1. The filter's lists load after the window's first page has started

**Where:** `PortalWindowActivity.kt:164` and `:179`, `loadLists` `:184-204`, `listed` `:206`.

`loadLists()` reads `dns_blocklist.tsv` on a background thread, and `view.loadUrl(snap.url)` runs right after it. That
file is "potentially 100k+ domains" (`DnsFilterEngine`). Until the thread is done, `blocked` and `dohHosts` are empty, so
`listed()` is false for:

- the first load;
- its redirects, which are all allowed before the kid acts;
- any meta or script move in that time.

A network built to fool Vibb (D3's residue) can therefore 302 the probe straight to a listed site. That page is shown;
only its later sub-resources, and the kid's taps once the list is in, meet the lists. §3.3 and §7 bound the residue by
"the filter's lists apply to every request", and here they don't. The code is on by default, but this is the case where
the parent has handed the phone back.

**Fix:** read the lists before `loadUrl`. Either read them on a thread and post `loadUrl` after, or have
`shouldInterceptRequest`/`shouldOverrideUrlLoading` wait on a latch while `snapshot.filterOn` is set. A read that fails
should refuse documents, not pass them: today a missing or unreadable file silently means "no lists".

### M2. A window opened in a pause outlives an early end of the pause

**Where:** `PortalRuntime.closeWindow` `:206-213`, `windowOpen` `:146-150`, `sessionOpen` (`PortalFiles.kt:106`),
`PortalWindowActivity.pauseOn` `:208`.

`closeWindow` sends "close now" only while `windowOpen()` is true, and `sessionOpen` stops counting a session as open
`minutes + 1` (11) minutes after its opening. A window opened in a pause has no 10-minute limit (`portalClose`), so after
11 minutes it no longer counts as open. Then:

- When the pause ends early, through `RestrictionsPause.clear()` from "Lock again" or the PWA's end of windows
  (`WindowRelock.kt:386`), `pauseEnded()` sends nothing.
- The window keeps the pause it was opened with until the snapshot's original `pauseEndMs`, up to 2 h
  (`restrictions_pause.json`). Until then it has `filterOn = false` and the site rule lifted, on a network that hasn't
  validated (a rig that blocks the check hosts).
- The PIN lock's and Part A off's "close now" are suppressed the same way.
- `fold()` reports the still-running window as `lost` and deletes its file. `approveInput.windowOpen` turns false, so a
  second window can open beside it.

The parent ended the pause and believes the phone is closed again, but this unfiltered window stays open.

**Fix:**
- Send `ACTION_CLOSE_WINDOW` unconditionally. It is a package-only broadcast to a not-exported receiver; an idle process
  doesn't get it, and a running window ignores a pause close that isn't its own.
- Or make `sessionOpen` and `fold` honour a pause window's end, for example by writing `pauseEndMs` into the session
  file.

### M3. The merge with today's `main` conflicts in three files

Design 100 was merged on `main` after fb686dc5c. `git merge-tree main design-102` conflicts in:

- `server/src/tests/music_spotify.rs`: `migrations_from_73_in_order` must end `(148, "shortcuts"), (149, "time request
  answers"), (150, "login windows")`. The branch alone passes with 148 → 150, since 0149 isn't on it.
- `launcher/.../server/StatusCapabilities.kt`: both sides append a capability; keep both.
- `docs/testing/emulator.md`: §6ao is used by both designs. Design 100 took §6ao on main ("design 100's checks on
  jelly36 (§6ao)"), so design 102's sections need new letters (design 102 names §6ao and §6ap). Fix every reference in
  the design, the notes and PLAN.

These files merge cleanly:

- `parent_events.rs`: design 100 adds no `Kind`, so `Kind::ALL` 23 and the 14 switches hold;
- `device_api.rs` (handler and tests): `policy_json_keys_snapshot`'s `time_policy` keys and the top-level list are
  separate hunks;
- both i18n catalogs: valid JSON, no new duplicate key. The one duplicate, `error.unknown_section`, is already on both
  sides.

After the merge, run `cargo test` and the launcher's Gradle once more: `PolicyResponseCompatTest` was changed on both
sides and auto-merged.

## Low

1. **`onPageStarted` takes a refused page as the current site.** `PortalWindowActivity.kt:385-398`. A POST refused in
   SIR, or a listed main-frame document, is answered with our own page at the refused URL. `onPageStarted` then sets
   `sites.current` to the refused site and adds it to `visited`.
   - Not exploitable that I can see: the page has no links or script, and BACK resets `current`.
   - But the report's `visited` names a site the kid never saw, and the state is wrong until BACK.
   - Fix: skip the update when the URL's site isn't the one the last allowed step set, or remember the refused URL.
2. **Filter-blocked documents are counted as "blocked frames".** `PortalWindowActivity.kt:342` puts a main-frame tap on
   a listed host into `blockedFrames`, and `:370` puts every listed frame document there (ad iframes too).
   `logins::wide` then raises "the Wi-Fi sign-in showed a blocked site" for an ordinary portal page with a blocked ad
   iframe, or for a kid who tapped a blocked link. That is the noise R2-L3 meant to avoid. Fix: count only frames
   refused by the site rule (`:378`), or tell the two apart.
3. **The role off loosens Chrome's bundle before the plan hides it.** `BrowserConfig.kt:95-105` runs before the
   hide loop. With the role off, or `WrongPackage`, it writes the DoH-only bundle while the still-unhidden Chrome has
   its tabs, then the plan hides it a moment later. Chrome reads restriction changes live, so there is a window of a few
   milliseconds. Fix: hide Chrome first when the role goes from on to off, as a tightening does.
4. **Gaps in the source scan.** `ServerTransportTest.kt:284-289` matches `java.net.URL` only as the full name.
   `import java.net.*` with `URL(x).readText()` or `.openConnection()` passes the scan: neither `openConnection(` nor a
   raw `Socket(` is a token. E11's own example only fails while the import names the class. Fix: add `openConnection(`
   and `import java.net.*`. Raw sockets: name today's DoT and bootstrap files and refuse `Socket(` elsewhere.
5. **D13's clear is recorded before it is known to have worked, and it runs alongside the bundle's write.**
   `BrowserConfig.kt:98-101`. `cleared = true` is stored before `clearApplicationUserData`'s callback reports
   `ok`. A failed clear is never retried and the report still gives `cleared_ms`. The clear is asynchronous with the
   write and read-back that follow. AOSP's clear-data path doesn't remove application restrictions as far as I know, but
   E9 should read `chrome://policy` after the first turn-on to confirm. Fix: store the record in the callback when
   `ok`.
6. **A stray window instance kills the running window.** `PortalWindowActivity.onDestroy` `:517-532` kills the `:portal`
   process whenever an instance finishes.
   - `Approval.Forward` (`CaptivePortalEntry.kt:102-106`) starts the window with `REORDER_TO_FRONT` and no network
     extra. If the running window is in another task, a new instance is made, finishes in `onCreate` (no network) and
     kills the process with the real window in it. The session file then stays "open" for up to 11 minutes.
   - A configuration change the manifest doesn't list (locale, font scale, density) also ends the window as `closed`.
   - It fails closed, but the kid loses the sign-in. Fix: kill only when this instance owned a session
     (`::snapshot.isInitialized`), and add `locale|fontScale|density` to `configChanges`.
7. **Every main-frame page calls `reportCaptivePortalDismissed()`.** `reprobe` `:273-284` calls it at most every 3 s
   while the window is open. AOSP's login app calls it once, at its end. Each call makes NetworkMonitor re-evaluate, and
   may re-post Android's "Sign in to network" notification while the window is open. E7 should watch for this. If it
   flickers, re-probe with `reportNetworkConnectivity` for both starts.
8. **An unmanaged phone with the role on gets a locked-down Chrome.** `LoginBrowser.kt:82`. `loginRoleOf` returns `On`
   when `allowlist == null`, so the bundle with `URLBlocklist ["*"]` is written while every app is free. Fix: no role
   without a managed allowlist (`allowlist == null` → `Off`).
9. **File and preference I/O on the main thread.** `PortalRuntime.row()` is called from `HomeActivity.renderPortalLine`
   and from `WifiNetworksActivity.renderPortal`. It reads `revisit.json` and may `commit()` the budget. It is small, but
   Home renders often.

## Owed measurements this review leans on

- **E5 / R7: POST redirect hops.** The rule for a POST's chain from a leaf ("only back to its own site or a portal
  site") is enforced only if SOUL sees the hop. A POST→302 should reach SOUL with `isRedirect()`. A 307 or 308 that
  keeps the POST may reach neither hook. Add a fake-portal mode with **a POST from a leaf to its own site that 302s, and
  one that 307s, to a third site**, and record what the window does.
- E1 to E13 and D-C1 to D-C6 as the design lists them, after the M1 and M2 fixes.

## Server tests: reasoned, not run

I read every new and changed test against the code. On the branch as it stands, I expect these to pass:

- **`tests::logins`**:
  - `the_contract`: every `policy` row from the saves; the reports stored exactly (the contract rows are already
    clean); `clean`; `entries`; the copies identical.
  - `the_pages_and_their_saves`: the anchors; Advanced's form back on `#wifi-signin`; 400 and 404.
  - `the_apps_page_names_the_login_role`.
  - `the_reports_events`: one wide event across two reports, one budget event, nothing without the capability, and the
    PIN line "Wi-Fi sign-in opened with the parent code.".
- **`parent_events`**:
  - `Kind::ALL` is 23, with the two new rows last, so `kinds == Kind::ALL` keeps its order;
  - 14 switches;
  - every kind is told;
  - `notifications::every_switch_is_worded` (`push_wifi_signin` → `settings.notifications.switch.wifi_signin`).
- **`parent_pin::every_log_line`**: "wrong parent PIN at the Wi-Fi sign-in." follows from `place.wifi_signin` ("at the
  Wi-Fi sign-in").
- **`seeded_family`**: every kind seeded.
- **`intake_limits`**: `report` and `report_off` are kept key for key.
- **`status_heartbeat`**: `REPORT_KEYS` lists both new `StatusReportRequest` fields.
- **`policy_json_keys_snapshot`**: both lists are sorted; `captive_portal` is in the non-null list and `login_browser`
  is not.
- **i18n**:
  - `every_template_is_moved`: `device_logins.html` is in `MOVED`.
  - `moved_templates_have_no_literal_words`: `"com.android.chrome"` is no word.
  - `every_key_used_exists`:
    - hosts ending `.com` are exempt;
    - `idp.`, `innsyn.` and `login.` are no namespace;
    - `auth.dataporten.no` is split by `concat!`;
    - the new namespace `logins.` has no stray literal.
  - `every_key_is_used`: all 76 new keys are quoted somewhere, except `events.pin_used.entry.wifi_signin`, which is
    built at runtime and exempt under `events`.
  - `both_catalogs_have_every_key_with_the_same_parameters`: checked key by key.
- **`shell::every_form_on_every_page_lands_on_its_own_group`**: Advanced's new form has `#wifi-signin` inside
  `<section id="wifi-signin">` and a `<noscript>` Save. The logins page isn't in that test's list, but `tests::logins`
  runs `check_forms` on it.
- **`cleanup`**: `sqlite_master` names only. The table is `login_role_sites`, and nothing named `%browser%` is created;
  the `login_browser_*` columns aren't checked there.
- **`music_spotify::migrations_from_73_in_order`**: passes on the branch; see M3 for after the merge.

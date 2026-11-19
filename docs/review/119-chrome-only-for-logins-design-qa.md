# Design QA: 119, Chrome only for a login, never for a link (revision 1)

Adversarial review of `docs/design/119-chrome-only-for-logins.md` (branch `design-119`, c3b03002e) against `launcher/`
on that commit and Android's sources. Platform claims below are read from AOSP `android16-release`:
`frameworks/base` (`CrossProfileIntentResolverEngine.java`, `DomainVerificationUtils.java`, `PackageManagerService.java`,
`RoleServicePlatformHelperImpl.java`) and `packages/modules/Permission` (`BrowserRoleBehavior.java`,
`RoleControllerServiceImpl.java`, `RoleService.java`, `RequestRoleActivity.java`, `roles.xml`). Other sources are linked
where they are used. The user's answers to the four ASKs (2026-11-19, relayed by the coordinator) are reviewed too:
(1) option (a); (2) the parent's pause; (3) at most **5 minutes**, and the window **ends when the login is done**;
(4) the stopped-hosts list with Add, yes.

**Counts: H 2, M 5, L 10.**

## What holds (checked, no finding)

- **The cause.** `filterCandidatesWithDomainPreferredActivitiesLPrBody` (CPIRE l. 547-626) runs for a web intent with
  no package. If no app is approved for the host, it keeps only the browsers: just the default one, or all of them
  under `MATCH_ALL`. Only when no browser exists does it fall back to "all candidates". With Chrome unhidden, Chrome
  is a browser, so every unapproved link (`omaps.app`) goes to Chrome. With Chrome hidden there is no browser, and
  Organic Maps is reached. This matches the user's emulator finding.
- **Element X.** Its OIDC login calls `openUrlInChromeCustomTab(null, …)` (`LoginFlowNode.kt` l. 270), and the helper
  sets a session only when one is given. So the login and ordinary links both resolve through Android. Its redirect
  lands in `MainActivity`, which is `singleTask` (its manifest l. 58), so the redirect clears the activities above it
  in the task. That matters for H2.
- **The gate as a browser.** A `VIEW`/`BROWSABLE` filter with http and https and no authority sets
  `handleAllWebDataURI`. `BrowserRoleBehavior.getQualifyingPackagesAsUserInternal` (l. 108-133) requires exactly that,
  plus `exported`. With Chrome hidden, `getFallbackHolderAsUser` returns the only qualifying package (l. 64-70).
- **A Device Owner can't set the browser role.** DPM has `setDefaultDialerApplication` and `setDefaultSmsApplication`
  (both used in `AppEnforcer`) but no browser equivalent. `RoleManager.addRoleHolderAsUser` needs
  `MANAGE_ROLE_HOLDERS`, a system permission. So the note's choice is right: rely on the fallback, and use
  `createRequestRoleIntent` as the on-phone step. `ROLE_BROWSER` is requestable (`roles.xml` l. 172-206, no
  `requestable="false"`).
- **A PPA can't beat an approved app.** `chooseBestActivity` consults persistent preferred activities only over the
  list the domain filter left. A disabled gate's PPA is skipped, since its component isn't in the query.
- **A hidden Chrome doesn't touch WebView.** From Android 10 the allowed WebView packages are
  `com.google.android.webview` (and its channels) or `com.android.webview`, never Chrome
  ([chromium `android_webview/docs/build-instructions.md`, the "Allowed package names" table][wv]). The plan already
  keeps the WebView provider in `neverRestrict` (`EnforcementPlan.kt` l. 206). The music app's Spotify player in a
  WebView is unaffected. This also matches today's role-off phones, where Chrome is already hidden.
- **Hiding stops Chrome.** `setApplicationHiddenSettingAsUser` calls `killApplication(…, "hiding pkg")` (PMS l. 6018)
  before the hidden broadcast, as design 102 relies on.
- **The rest.** Option C is the smallest design that stops Chrome by name. Wi-Fi portals stay Part A (D1). Step 1
  (Meldinger) is safe to ship alone, though see H1 for what it shows. The contract is in one commit with
  `login_windows.json`. The PWA's orange line follows "Words in the UI".

## Findings

### H1 - The gate's step 5 can't find Organic Maps: `omaps.app` would get the note (and Meldinger breaks too)

§4.3 step 5 takes "non-browser candidates of the same intent (`MATCH_ALL`)", and §5 shares Meldinger's
`messageLinkTarget`. Once the gate is a browser, Android's own filter removes exactly those candidates:

- `isDomainVerificationIntent` is true for `VIEW` + `BROWSABLE` (or no category) with `MATCH_DEFAULT_ONLY`
  (`DomainVerificationUtils` l. 69-92). Meldinger's `handler` queries that shape (`MessageLinks.kt` l. 109, 134).
- For such an intent with no approved app, the result is the browsers only. `MATCH_ALL` adds *all browsers*
  (CPIRE l. 584-585), not the unapproved apps in `undefinedList`.
- "Add all candidates" runs only when that result is empty (l. 623-625). With the gate enabled it never is.

So `queryIntentActivities` returns the gate alone. Organic Maps, which declares `omaps.app` but isn't verified,
never shows up. Step 5 finds nothing and step 6 shows the note. That breaks the one behaviour the user confirmed:
`omaps.app` → Organic Maps.

Meldinger has the same problem. Its `first` would be Vibb One (the gate), and once the role is off, today's "every
candidate" fallback is gone.

**Fix.** Specify the query so Android doesn't filter it:

- **(a) Per package (preferred).** Run `queryIntentActivities(view.setPackage(pkg), MATCH_DEFAULT_ONLY)` once for
  each allowed package. With a package set, the domain filter doesn't run (CPIRE l. 407: `pkgName == null &&
  hasWebURI`).
- **(b) A non-verification shape.** Query with no category and without `MATCH_DEFAULT_ONLY`, using
  `GET_RESOLVED_FILTER`, and keep only the filters that have both `DEFAULT` and `BROWSABLE`.

Either way, drop Vibb's own components. Put the rule in `linkGatePlan`'s inputs, add a unit row "an unverified app
that declares the host is found while a browser exists", and add an M2 row that checks it on the emulator.

### H2 - The window doesn't meet the user's ASK 3 answer; "login done" has to be specified

The note closes after 10 minutes, at the keyguard, or when Home comes to the front. The user now wants at most
**5 minutes**, and the window should **end when the login is done**. On Android 14-17, here is what Vibb One can and
can't observe.

**Not available:**

- *The calling app resuming.* Another app's lifecycle needs usage access (`PACKAGE_USAGE_STATS`, an app-op Vibb
  doesn't hold and a DO can't grant through `setPermissionGrantState`) or an accessibility service. Vibb has neither
  for itself.
- *Chrome's task going away.* `getAppTasks`/`getRunningTasks` show only the caller's own tasks.
- *The redirect URL.* Custom Tabs callbacks carry events, not URLs, and a custom-scheme redirect isn't a Chrome
  navigation at all.

**Available, independent of the Android version:**

1. **The gate stays as an invisible anchor under the tab.** It starts the tab and does not finish. Two lifecycle
   events then mean the login is over:
   - The anchor is **destroyed by the system**. A redirect into a `singleTask` or `CLEAR_TOP` activity does this.
     That covers Element X's `MainActivity`, AppAuth's `RedirectUriReceiverActivity` → `AuthorizationManagementActivity`
     with `CLEAR_TOP`, and most OAuth libraries.
   - The anchor is **resumed**: the tab was closed with ✕ or Back. The anchor then ends the window and finishes, and
     the kid sees the app.

   This is plain activity lifecycle in Vibb's own process.
2. **Vibb holds the tab's `CustomTabsSession`.** After the unhide, Vibb binds Chrome's `CustomTabsService`, calls
   `newSession(callback)`, and puts that session's binder in the tab intent. Chrome then sends
   `onNavigationEvent(TAB_HIDDEN)` "when the tab becomes hidden" ([`CustomTabsCallback`][cct-cb], androidx 1.2+).
   That covers what (1) misses: a `standard` redirect activity landing on top of the tab, an https App Link handing
   off, "Open in Chrome browser" (the tab is reparented), Home, and the screen going off. Vibb is the tab's launcher,
   so the session belongs to the right package. This depends on Chrome's version, not Android's. Chrome is on 14-17
   alike.
3. **The 5-minute ceiling** stays as the backstop (a timer, and an elapsed alarm, see L5).

**What the note should specify:**

- The window ends at the first of: the anchor destroyed or resumed; `TAB_HIDDEN` from Vibb's own session; 5 minutes;
  the keyguard; a time rule; a tightening, the role off, or a pause's end.
- If the bind fails within a short time (say 2 s), the tab opens without a session, ends by (1) and (3) alone, and
  the report says so.
- A row in `login_rules.json`'s `window` rows for each of these ends.
- M rows for each end on 14 and 16:
  - Element X's MAS redirect;
  - an AppAuth sample;
  - ✕;
  - "Open in Chrome browser" (does `TAB_HIDDEN` fire, and does the hide then kill full Chrome?);
  - a Play-services passkey or account sheet over the tab, which must *not* end the window (it is translucent, so
    the tab should only pause - measure it);
  - a minimised Custom Tab ([`onMinimized`][cct-cb], androidx 1.8).

A good side effect: (2) also shuts the residue of §4.7, both "Open in Chrome browser" and another app starting Chrome
by name. Instead of lasting up to 10 minutes, they end with the login.

**Costs.**

- The anchor model changes §4.1's "finish" and the trampoline semantics (see M2).
- The session needs `androidx.browser` (Apache-2.0: a new dependency with `verification-metadata.xml` entries) or
  hand-copied AIDL. Either way, §4.4's "no `androidx.browser` dependency" goes.
- The (i) text "lukkes etter 10 minutter" changes: say "lukkes når du er logget inn". The 5 minutes is a constant of
  its own, apart from Part A's 10 (`logins.rs` `MINUTES` is Part A's).

### M1 - Hiding or unhiding Chrome never re-runs the role grant; the fallback needs a component change, and can stick on Chrome

`RoleService` re-runs `grantDefaultRoles` on `PACKAGE_ADDED`/`CHANGED`/`REMOVED` (l. 229-247), but only when the
packages hash changed (l. 363-372). `computePackageStateHash` (RSPH l. 304-360) hashes each installed package's path,
enabled state and enabled/disabled components, plus the owners. It does not hash the hidden state, and
`forEachInstalledPackage` includes hidden packages (PMS l. 7572-7580).

Consequences:

- **§1's mechanism is off, though its cause is right.** Unhiding Chrome doesn't "make it the default". Chrome is most
  likely still the holder from the first boot (`defaultHolders="config_defaultBrowser"`). Even if it weren't, a lone
  browser gets every unapproved link anyway (CPIRE l. 619).
- **§4.1 only works because enabling the gate is a component change**, which does change the hash. The controller
  then drops a hidden Chrome (it no longer qualifies, RCSI l. 158-168) and adds the gate as fallback. **Order
  matters:**
  - If the apply enables the gate while Chrome is still unhidden (role on today, or Chrome ticked then unticked), both
    packages qualify, and the current holder Chrome stays.
  - Hiding Chrome afterwards changes no hash, so Chrome keeps `ROLE_BROWSER` while hidden.
  - `isRoleHeld` then stays false. Fail-closed holds, but no login ever opens, until some unrelated app update.
- **The same stuck state follows Chrome taking the role during a window or a pause.** Chrome's own default-browser
  promo uses `createRequestRoleIntent`, and see M5 for why that isn't blocked.

**Fix.** Specify the order: hide Chrome first, then enable the gate, in the same apply. Add a repair: when the gate is
on, Chrome is hidden and `isRoleHeld(ROLE_BROWSER)` is false, flip the enabled state of a no-op component of Vibb's (a
disabled activity alias). That changes the hash, so the controller drops the hidden holder and falls back to the
gate. Add M1 rows for:

- a fresh role-on phone (Chrome the holder);
- Chrome ticked → unticked;
- after a pause in which Chrome was made the default.

Read each with `dumpsys role`.

### M2 - Callers that start the tab for a result, and Auth Tab

The gate finishes right after starting Chrome. An app that started the "browser" with `startActivityForResult` gets
`RESULT_CANCELED` at once, while the tab is still opening. Many apps do this to notice the tab closing. Auth Tab
(Chrome 137+, [guide][authtab]) also delivers its result this way.

§4.3's fresh intent also drops Auth Tab's extras, so Chrome opens an ordinary Custom Tab. An Auth-Tab-only app has
no intent filter for its redirect scheme (the guide: Auth Tab "eliminates the need for an intent" filter), so its
login can't complete.

**Fix.** With H2's anchor:

- When `callingActivity != null`, the anchor starts the tab with `startActivityForResult` and copies Chrome's result
  back (`setResult`) before it finishes.
- Copy only the Auth Tab keys (`EXTRA_LAUNCH_AUTH_TAB`, the redirect scheme, or the https host and path) into the
  fresh intent.

Otherwise, name these apps as ASK 2's case (the pause). Add a probe row to M2/M4.

### M3 - Opening a window on the main thread under the enforcer's lock can freeze the tap into an ANR

§4.4 opens "main thread, under the enforcer's lock". `AppEnforcer.apply` is `@Synchronized` (l. 329-336) and does a
lot under that lock: the hide and suspend loop, Chrome's bundle write and read-back, the roles, the PPAs, lock-task
packages. A tap during an apply blocks the gate's `onCreate` for that long. Past about 5 s that is an input-dispatch
ANR, and the kid sees a frozen app.

**Fix.** Take the lock on a worker thread while the translucent gate shows nothing, then start the tab back on the
main thread. Or keep the window record under a small lock of its own, which the apply reads at its plan and at its
hide step.

### M4 - Logins that need another app (an SMS code in Meldinger, an e-mail code, an authenticator) lose the tab

With H2's ends, or the note's "Home comes to the front", switching to Meldinger or another app to read a code ends
the window. Hiding Chrome kills the tab and the login starts over. This is a visible cost of the design's own rule.
By the standing rule, it goes to the user before the build. The choices:

- (a) end at once, and accept the restart (Feide and Google prompts mostly need no switch);
- (b) a short grace after `TAB_HIDDEN` (for example 60 s) that a `TAB_SHOWN` cancels, at the cost of Chrome staying
  visible that long after a finished login.

The user's "end when the login is done" points to (a). Name it in the ASK 3 summary so it isn't a surprise.

### M5 - `DISALLOW_CONFIG_DEFAULT_APPS`: the role isn't covered, and the fallback setup step needs it lifted

- **It is set only while the dialer or SMS role is managed** (`lockDefaultAppsFor`, `AppEnforcer.kt` l. 1048-1052).
  On a phone without managed calls or SMS:
  - during a window, Chrome's own "make Chrome your default" promo (full Chrome, via "Open in Chrome browser") can
    take the role with the kid's tap;
  - during a pause, Settings' Default apps can do the same.

  That leads to M1's stuck state. Set the restriction whenever the gate is on.
- **The M1 setup step needs the restriction lifted.** `RequestRoleActivity` refuses a request under a user
  restriction (`getApplicationRestrictionIntentAsUser` → `ACTION_SHOW_ADMIN_SUPPORT_DETAILS`, l. 63-79). The
  `createRequestRoleIntent(ROLE_BROWSER)` step must lift the restriction while the dialog is open, the way design
  98's `assistantPickOpen` does (`AppEnforcer.kt` l. 1050-1051). §4.1 should say so.

### L1 - Holding `ROLE_BROWSER`: name the side effects

None of them is harmful, but §4.1 should list them:

- **A permission.** The role grants `PROVIDE_OWN_AUTOFILL_SUGGESTIONS` on 34+ (`roles.xml` l. 203-205).
- **No location grant.** `BrowserRoleBehavior.grantAsUser` grants location only to a *system* package (l. 141-150),
  and Vibb is not one.
- **Settings.** Default apps shows "Vibb One" as the browser.
- **`CATEGORY_APP_BROWSER`.** `makeMainSelectorActivity(MAIN, APP_BROWSER)` ("open the browser") finds nothing
  outside a window, since the gate doesn't declare it, and finds full Chrome during one. H2's end closes that too.
- **Custom Tabs lookups.** `CustomTabsClient.getPackageName` returns null outside a window (the gate has no
  `CustomTabsService`) and Chrome during one. That is §4.7's residue, now as short as the login.
- **App Links.** Vibb's verified or selected map hosts are unchanged, because the gate sits in `matchAllList`, not in
  the approved list.

### L2 - Another visible full browser keeps the role from the gate

The note's switch reads "no free browser among the allowed apps". The role controller counts every visible,
exported full browser, though: a suspended (not hidden) system browser, or one outside `controllable`. With two
qualifying packages, the fallback takes the only *system* one, or `config_defaultBrowser` (`BrowserRoleBehavior`
l. 72-81). Fail-closed holds, and `browser_role: false` shows it in the report. Name the case, and give the PWA's
orange line's (i) the cause.

### L3 - A gate started with `NEW_TASK` is its own task's root

When the caller isn't an activity (an application context, a notification's `PendingIntent`), the gate gets
`taskAffinity=""`, so it becomes the root of a new task. The tab lands there, not "in the calling app's task".
`excludeFromRecents` acts only on a task's root activity. The redirect still reaches the app. The anchor then sits in
an orphan task, so it must finish when the window ends (by a broadcast in Vibb's own package). Add a row to the probe
(M3).

### L4 - The catch-all PPA's place in the re-seat

`IntentResolver` keeps insertion order among filters of equal priority. So the gate's http/https catch-all must be
added **after** the Play link blocker and the map handler's PPAs, or it can shadow them while several browsers are
candidates (a pause with no role holder). "The seventh" happens to be last today. Say that the order is load-bearing
and pin it in `PlayInvariantsTest`. The PPA is not needed for safety: with the role held, the gate is the only browser
result. It earns its place only in a pause with no holder.

### L5 - Process death and reboot: name the backstops

- **The main process restarting** (the anchor service, `Application.onCreate`'s apply, `Application.kt` l. 114) must
  re-check the window record. An elapsed `AlarmManager` alarm at the window's end (inexact is enough, no exact-alarm
  permission) keeps Chrome from waiting for the 5-minute periodic sync.
- **"A record from an earlier boot is void"** should say *void means hide*, done by the first apply after boot. The
  hidden state persists across a reboot, so a Chrome that was unhidden at the moment of a crash stays visible until
  then. It isn't direct-boot aware, so it is harmless before the unlock.

### L6 - §8's table: add what H2 and M1 rest on

- Custom Tabs callbacks (`TAB_HIDDEN`) depend on Chrome's version, not Android's.
- The role's package hash ignores the hidden state (checked on 16's source; check it on 14).
- WebView providers on Q+ (the source above).

The emulator gate is 14 and 16. CLAUDE.md asks for "the lowest and the highest it touches", which is 17 once an image
exists, and the note says so. Fine.

### L7 - `KidScreensEscapeTest`

The gate is a new way for Vibb One to start other apps: Chrome, an allowed app by package, `MapLinkActivity`, and a
browser in a pause. Put `logins/LinkGateActivity` and `linkGatePlan` under the test's guard, as `map/` and
`MessageLinks` are.

### L8 - Fresh intents drop harmless extras

Element X sends `Browser.EXTRA_HEADERS` (Accept-Language) and a colour scheme. The gate's fresh intent drops them, so
the tab may come up in the wrong language or theme. Copying `EXTRA_HEADERS` (CORS-safelisted headers only, as Chrome
allows) and the colour-scheme int is harmless. Optional.

### L9 - The stopped-hosts list (ASK 4 yes)

Name what the list holds: a host from *every* refused link, chat links included. The user said yes, so this is a
note, not a question: hosts only, the last 10, gone after 7 days, as written.

- An entry added from the list is a login host. It opens the window for every link to that host.
- Keep a warning out of the PWA (Words in the UI: a choice isn't a warning). One line behind the (i) is enough:
  "Legg til bare innloggingssider."

### L10 - Wording

- §4.5's sheet reuses `message_link_cant_open`. Good.
- The PWA's (i) text should follow H2: "Chrome åpnes bare for sidene under, når en app ber deg logge inn, og lukkes
  når du er ferdig." / "Chrome opens only for the sites below, when an app asks you to sign in, and closes when you're
  done." The "10 minutter" goes.

## Simpler options considered

- **Drop the seventh PPA (L4).** It is safe to drop, but costs a chooser in a pause with no holder. Keep it.
- **Drop the 5-minute timer in favour of H2's events alone.** No: the timer is the only end that needs nothing from
  Chrome or the calling app. Keep it as the backstop.
- **Vibb's own `CustomTabsService` backed by a WebView.** This would make the gate a Custom Tabs provider and remove
  Chrome altogether. It is option E in another form: Google refuses OAuth in a WebView (`disallowed_useragent`).
  Rejected for the same reason.

Nothing simpler meets "Chrome never opens a link" and "a login opens".

[wv]: https://chromium.googlesource.com/chromium/src/+/HEAD/android_webview/docs/build-instructions.md
[cct-cb]: https://developer.android.com/reference/androidx/browser/customtabs/CustomTabsCallback
[authtab]: https://developer.chrome.com/docs/android/custom-tabs/guide-auth-tab

# Design 109: the iOS fade that stays, and the Phone app's row on Device › Apps

Status: design, revision 2, 2026-11-16 (branch `server-round`, from main 5181d1f79; revision 1 619040cc8; revision 2
answers the design QA, §"Design QA" at the end). Server only: one static script, one CSS rule, the head's two cache
numbers, Device › Apps' row for the system dialer, the guards in four handlers and the calls save. No route, no
migration, no API or contract file, no launcher change.

Two PLAN items, both server rounds (PLAN, "Approved, not built"):

- **A** - "iOS: the grey fade over the status bar is still there on `server-v0.81.1`". The user (iOS 27, the installed
  app): the fade comes only after the app has been in the background, and *stays* until the app is killed and opened
  again ("Jeg må kill og åpne på nytt for at den skal forsvinne"). So design 104's retries (0.3, 1 and 3 s after a
  return) never reach WebKit - it is not a timing problem.
- **B** - "The PWA's Apps page shows the system Phone app as allowed and on Home", APPROVED 2026-11-16 ("Gjør det", and
  "ja" to putting the dialer back on the allowlist when calls go from managed to unmanaged).

Android 14-17 does not apply: nothing here runs on a phone. B only describes, and stops the parent from changing, what
the launcher already does on every version (`AppFilter`/`EnforcementPlan` are plain Kotlin over
`TelecomManager.getSystemDialerPackage()`, API 23+), and A runs in the parent's iPhone.

## A. The fade that stays

### A.1 What the code does today

- `server/static/top-strip.js` (`?v=2`, design 104): on `pageshow` and on `visibilitychange` to visible, `nudge()` adds
  `.top-strip-nudge` to the strip for one painted frame (two `requestAnimationFrame`s), and again at 300 ms, 1 s and
  3 s (`RETRY_MS`); a field's `focusout` and `visualViewport`'s `resize` nudge once, 300 ms after the last.
- `server/static/style.css:309-324`: `.top-strip` is the fixed night strip (`position: fixed`, top 0, full width,
  `height: var(--top-strip)`, `z-index: 4000`); `.top-strip.top-strip-nudge` is `background: #0C0C15` - a colour
  change only. `style.css:290-306`: in the installed iPhone app the canvas (`html:not(.doc-scroll)`) is night; the four
  `doc-scroll` pages (login, setup_2fa, verify_2fa, change_password) keep the body's light `--bg`.
- `server/templates/partials/head.html:6` `style.css?v=121`, `:38` `theme-color` `#0C0C14`, `:41`
  `apple-mobile-web-app-status-bar-style` `default` (`status_bar.rs` swaps in `black-translucent` for Safari 26 and
  older only), `:79` `top-strip.js?v=2`.
- Pinned by `server/src/tests/shell.rs:1588` (the nudge rule) and `:1591` (the script line), and by
  `server/jstest/top-strip.test.js` (seven tests, run by `server-ci.yml`'s `node --test jstest/*.test.js`).

### A.2 What the fade most likely is, and why the nudge never takes

As read in 2026-11-10's and 2026-11-12's rounds (`docs/notes/server/pwa.md`, "The status bar by iOS version" and "The
status bar's grey after a resume"): on iOS 26 and later UIKit draws a scroll-edge "pocket" (a soft blur and dim) over
the web view's top; WebKit hides it (`WKWebView _updateHiddenScrollPocketEdges`) and colours the status area itself
when the web process reports a *fixed container edge* at the top (`LocalFrameView::fixedContainerEdges`: our
`.top-strip`). The grey the user sees is that pocket's own material over the night band - which is why 0.76.1's night
canvas did not change it.

The new fact is that it **stays**. That means the UI process (WKWebView, which lives as long as the app does) holds a
stale state that nothing the page does today changes:

1. WebKit sends the edges to the UI process only when they differ from what it sent last, and the UI side re-runs its
   pocket and colour-extension logic only when it receives a different value. A new document (a tab switch) reports
   the same night edge again, so nothing changes - consistent with "only a kill helps".
2. Our nudge is a *paint-only* change of an existing box. As far as can be read from WebKit's source (not measured), the
   fixed-edge scan is scheduled from layout, scrolling and compositing updates, not from a repaint - so a background
   colour flip on the same box, at any time, is most likely never scanned, and never sent. That fits design 104's
   retries failing at every delay.
3. A likely trigger for the stale state: when an app goes to the background iOS takes app-switcher snapshots in both
   appearances, flipping the trait collection (and `prefers-color-scheme`) while the web process is being suspended.
   A pocket re-shown by UIKit at that point is never hidden again, because WebKit sees no new edge. This also explains
   "not every time".

So a nudge has to make WebKit *re-scan and re-send* the edge: a real layout or compositing change of the strip, held
long enough to be committed, then back.

### A.3 The design: a layered nudge, every layer invisible and idempotent

`top-strip.js` becomes `?v=3`. One `nudge()` runs these steps in order, each in its own `try` so one failing never stops
the next; one `endHold()` undoes them all.

1. **Layout, not just paint** (cheapest). `.top-strip-nudge` becomes `background: #0C0C15; left: -1px`
   (`style.css`, `?v=122`): the strip grows by one point *off the screen's left edge*, so its box and its layer's
   geometry change and layout and the compositor run - the scan's triggers - while nothing on screen moves or is
   uncovered: the visible strip is exactly as before (a negative left overflow is never scrollable, and the fixed
   box's width only grows, so the 90 % rule holds; the sampled middle of the top edge moves by half a point, still on
   the strip). On every page, the four light `doc-scroll` sign-in pages included, nothing changes on screen.
2. **A fresh strip.** `fresh = strip.cloneNode(false)`, `fresh.classList.add(NUDGE)`, then
   `strip.parentNode.replaceChild(fresh, strip)` - one DOM operation, so there is never a frame without a strip, and the
   clone keeps every attribute (`class`, `aria-hidden`) as the template wrote it. A new renderer, a new compositing
   layer and a new fixed node in the scrolling tree, should the stale state be tied to the old layer. The clone is the
   strip from then on (everything that reads the strip queries `.top-strip` at the time: `swap.js:178`,
   `locate.js:356`, `app-lock.js`'s veil rule by class).
3. **`theme-color`, as a cheap extra** (not counted as a layer: it may be inert in an installed app on Safari 26+,
   unverified). The existing `<meta name="theme-color">`'s `content` is set with one `setAttribute` to `#0C0C15` for the
   hold and back to the content it had (read at the start, never hard-coded) at its end - never removed and appended,
   so there is no moment without one. One step off the night: not visible.
4. **The hold.** The nudge stays on for two painted frames and then `HOLD_MS` = 150 ms (rAF, rAF, `setTimeout`), so at
   least one rendering update and one layer-tree commit carry the changed state even when the web view is still
   coming back on screen; then `endHold()`: the class comes off the (fresh) strip and `theme-color` gets its old content
   back - a second, real change that WebKit sends too.

**A hold always ends** (QA M1). `endHold()` is idempotent (a module flag `holding`, the strip it nudged, the meta's old
content, the cap timer): it removes the class, restores the meta, clears the cap and the flag. It runs:

- at the end of the normal hold;
- from a cap timer, `HOLD_CAP_MS` = 1 s after the start, so a hold whose frames never come (rAF deferred off-screen)
  still ends;
- at once on `visibilitychange` to hidden and on `pagehide` (a page going into the back-forward cache is stored clean);
- at the start of every `returned()`, before its own nudge - a hold left over by a frozen page can never block a return.

Guards: a nudge while `holding` does nothing (the flag, not the class - the node is replaced); the series rule of design
104 stays (a new return restarts the retries, hidden cancels them).

**Triggers.** A return (`returned()`: a nudge now and at each of `RETRY_MS`) on `pageshow`, `visibilitychange` to
visible, window `focus`, and a change of `matchMedia("(prefers-color-scheme: dark)")` (`addEventListener`, else
`addListener` as a fallback; the snapshot flip of A.2 point 3). The single debounced nudge (`keyboardMoved()`,
`KEYBOARD_MS` after the last) on a field's `focusout`, `visualViewport`'s `resize` and window `resize` (QA L1: a resize
comes in bursts - the keyboard, a rotation - and needs one nudge once it has settled, not a series per event). All
restart their own timer, so nothing stacks.

**Only where the pocket exists.** `install` runs only when `navigator.standalone === true` (iOS's installed web app
alone has it); everywhere else (Android, a desktop, a Safari tab) the script installs nothing. Before, it nudged
everywhere, harmlessly; with more steps it is better to touch nothing where nothing needs it. Design QA L4 agreed: the
fade is the installed iPhone app's alone, and every other place has no pocket.

Nothing else changes: the strip's colour and size on screen, the canvas, the bar style, `status_bar.rs`. Design 104's
retries and keyboard nudge are kept. ES5 as before (Safari 14 floor), no dependency.

**Rejected candidates**, with why: toggling `apple-mobile-web-app-status-bar-style` at runtime (iOS reads it once, from
the page the app was added from - `pwa.md`, "The status bar by iOS version"; a change does nothing until the app is
added again); re-setting the viewport meta or `viewport-fit` (a real change moves the safe-area insets - the tab bar
would jump for a frame - and an equal one is ignored); a 1 px `padding-top` on `html` (moves the whole page by a pixel:
visible, and step 1 already forces a layout); `right: 1px` (revision 1: under iOS 27's `default` bar the web view
starts below the status bar, so the uncovered column is not inside the screen's corner and shows the light page on the
sign-in pages - QA H1); taking the strip away for a frame (would show the very pocket on every return - kept as the
next round's knob in A.5, not built).

### A.4 Files

- `server/static/top-strip.js`: the steps above; exports `HOLD_MS` and `HOLD_CAP_MS` beside `NUDGE`, `RETRY_MS`,
  `KEYBOARD_MS`; the header comment says design 109.
- `server/static/style.css`: `.top-strip.top-strip-nudge { background: #0C0C15; left: -1px; }` and its comment (one
  point off screen to the left: a layout, nothing uncovered).
- `server/templates/partials/head.html`: `style.css?v=122` (grep every pinned `style.css?v=121` - only the head has it
  today), `top-strip.js?v=3`, the script's comment.
- `server/src/tests/shell.rs:1588`: `assert_eq!(block(".top-strip.top-strip-nudge"), "background: #0C0C15; left: -1px;")`;
  `:1591`: `?v=3`.
- `server/jstest/top-strip.test.js`: the fake page gains `navigator.standalone`, `cloneNode`/`replaceChild`, a
  `theme-color` meta with `getAttribute`/`setAttribute`, `pagehide` and a `matchMedia`. Tests (replacing the one-frame
  assertions): a return replaces the strip with a clone carrying the nudge (same attributes) and sets `theme-color` to
  `#0C0C15` with one `setAttribute`; after two frames and `HOLD_MS` the clone is plain and `theme-color` has its old
  content; a nudge while holding does nothing; **hidden mid-hold ends the hold at once, and the next visible return
  nudges again** (QA M1); `pagehide` mid-hold the same; with no frames at all the cap ends the hold at `HOLD_CAP_MS`;
  `focus` and the colour-scheme change each start a series (and restart it, never stacking); window `resize` gives the
  one debounced nudge, not a series; without `navigator.standalone` nothing is installed; a page without the strip or
  the meta still nudges what it has. The existing retry, hidden and keyboard tests keep their meaning with the new
  timing.
- `docs/notes/server/pwa.md`: a bullet after "Retries after a return" (design 109).

### A.5 How the user tests it (no iOS device here)

1. After the server update, kill the installed app once and open it (the new page loads `top-strip.js?v=3`; no
   re-adding to the Home Screen).
2. Try to bring the fade on: with the app open on any tab, go to another app for half a minute and come back; lock and
   unlock the phone; and once, with the app in the background, switch Dark Mode in Control Centre and come back (A.2
   point 3). Ten or so returns over a day of normal use, a sign-in page included if one comes up.
3. Report: the fade never comes; or it comes and goes by itself within about a second (the series reached WebKit);
   or it still stays - then also whether tapping another tab or pulling to refresh clears it. Anything else seen at the
   top of the screen (a line, a flicker) is a finding too: every step is meant to be invisible.

What is being tested is layers 1 and 2 (the layout change and the fresh strip); `theme-color` rides along and is not
counted on.

**If it still stays** (the next round, not built now): the strongest knob is taking the strip away for one hold (the
edge goes from night to none and back - a change WebKit cannot coalesce), on the 3 s retry only, at the cost of the
pocket showing for 150 ms on a return where it was not there - a visible cost, so the user's choice then.

### A.6 Rollback

Revert the one commit: `top-strip.js` back to `?v=2` (design 104's), the nudge rule to colour only, `style.css?v=121`,
the two shell assertions and the JS test. No data, no state on the phone.

## B. The system Phone app's row on Device › Apps

### B.1 What the code does today (checked against the code)

Server:

- `server/src/handlers/devices.rs:1899-1940` (`app_rows`, first pass): every installed app the report lists is one
  `UnifiedAppRow` with a switch, `checked` = in the allowlist - the system dialer like any app. Its `label` is the name
  the kid sees (this phone's rename first), `own_label` the app's own name when they differ (`devices.rs:1879-1893`).
- `server/src/handlers/device_pages.rs:2231-2326` (`render_apps`): each row gets Name and icon (with "På Hjem",
  `form.on_home`) when checked, Permissions (design 68) when checked, and goes to Allowed or Not allowed by `checked`.
  Then Meldinger (`:2328`, design 82 D10, directly under Vibb One, at
  `usize::from(allowed.first().is_some_and(|app| app.row.is_launcher))`, `:2385`) and Kalender (`:2398`, a `link` row
  to Device › Calendar) are inserted at the top as built-in rows (`AppView.builtin`, `AppView.link`, `:1915-1939`).
- `server/templates/partials/device_app_row.html`: a `link` row is an `<a class="row">` with the label, a sub-line, a
  value and a chevron - and, today, only a glyph tile and no refusal line.
- The dialer is known from the report: the launcher sends `call_state.system_dialer`
  (`launcher/.../calls/CallStateReport.kt:37`, `server/AppEnforcer.kt:125`), the intake keeps it
  (`device_api.rs:1293`, `CALL_STATE_FIELDS`), and `device_api::call_and_text_roles` (`device_api.rs:1020`) reads it
  for a new phone's first allowlist (`device_api.rs:1883-1910`). `calls::call_report` warns on the Calls page when calls
  are unmanaged, the phone has an allowlist and the dialer is not on it (`calls.rs:1620-1630`, "Bare nødanrop virker").
- Allowlist writers (all of them): `toggle_app` (`devices.rs:2653`, through `add_to_allowlist`/`remove_from_allowlist`
  over `edit_allowlist`, `:2925-2990`, a plain `begin()`), the first report's bootstrap (`device_api.rs:1907`, only
  while NULL) and `allow_installed_catalog_apps` (`device_api.rs:929`, catalog packages only - the dialer can never be
  one: `never_a_catalog_package`/`foreign_on_a_phone` refuse a system app). There is no bulk save of the allowlist.
  The other row forms: `save_app_display` (`devices.rs:1331`: name, icon, On Home), `save_app_permissions` and
  `save_lock_media` (`:1508`, `:1536`, both through `perms_target`, `:1640`).
- `calls_mode` is written in one place: `calls::save_settings` (`calls.rs:2025`, the `UPDATE` at `:2108-2125`, one
  `begin()` transaction); `DevicePolicy::calls_managed()` is `calls_mode != "unmanaged"` (`models.rs:461`; `off`
  counts as managed).

Launcher (why the row is wrong while calls are managed, and why changing it there changes nothing for the kid):

- `apps/AppFilter.kt:84-93` `blockedSystemDialer()`: with calls managed (or outgoing calls restricted) the dialer is
  never on Home or in the app list, whatever the allowlist or On Home says.
- `server/EnforcementPlan.kt:206`: the dialer is in `neverRestrict` - never hidden or suspended, managed or not.
  `:216`: with calls managed, outgoing calls depend on the rules and our dialer role, never on the allowlist; with calls
  unmanaged they are restricted only when apps are managed (an allowlist) and the dialer is not on it.
  `:256`: with calls managed the dialer is taken out of the kiosk list even when allowlisted (`:266` adds it back only
  as a helper under the block bit, whatever the allowlist).
- `ui/home/HomeModel.kt:51`: the "Telefonbok" tile is on Home whenever calls are not unmanaged.
- A rename or On Home for the dialer is read only by `AppFilter`, which has already dropped it.

So with calls managed the dialer's allowlist row, rename and On Home are read by nothing the kid sees; with calls
unmanaged they are exactly what the page says (allowed -> Android's Phone app on Home; unticked on a phone with an
allowlist -> outgoing calls restricted, emergency calls only). The approved change and the re-add below therefore
change nothing on the kid's phone except the one approved effect: after a switch to unmanaged, the dialer is allowed.

### B.2 The design

**Which package is the dialer**: the last report's `call_state.system_dialer`, through a new
`device_api::system_dialer(call_state: Option<&Value>) -> Option<String>` with `call_and_text_roles`' filters (a
package name, not Settings, not Play's core); `call_and_text_roles` uses it. No report, or a report without it: no
special row (today's row; before the first report there are no installed rows anyway).

**Calls managed** (`calls_managed()`, so `managed` and `off`) - `render_apps`:

- The dialer's installed row becomes a built-in link row, whether or not the package is in the allowlist. It is built in
  the loop and inserted after Meldinger and Kalender at `usize::from(allowed.first().is_some_and(|a|
  a.row.is_launcher))` - directly under Vibb One (Home's order: the phone book tile comes first), or first when there is
  no Vibb One row:
  - label: `apps.dialer.managed_label` - "{app} → Vibbs telefonbok" / "{app} → Vibb's phone book", `{app}` =
    `row.own_label.unwrap_or(row.label)` - the app's own name (never the parent's rename, which the phone does not
    show);
  - sub-line: `apps.dialer.managed_sub` ("Skjult på telefonen - barnet ringer fra Telefonbok på Hjem") or, with calls
    `off`, `apps.dialer.off_sub` ("Skjult på telefonen - bare nødnumre kan ringes");
  - value: the calls mode's own word (`device.calls.mode.managed` / `.off`), chevron, to
    `/devices/{id}/calls#settings` (the channel form on the page "Anrop og kontakter");
  - tile: its logo (design 87) or letter - the link branch of `device_app_row.html` gets the same tile chain as the
    other rows (logo, else glyph, else letter; Kalender, with a glyph and no logo, looks as before);
  - no switch, no Name and icon (so no On Home, no rename), no Permissions, no update line; the anchor stays
    `app-<package>`, so links and refusals land on it.
- The row is not in Not allowed even when the package is missing from the allowlist (it is not the parent's to tick
  there while calls are managed).

**Calls unmanaged** - today's row, plus one line under it (a new `AppView.dialer_note: Option<(&'static str, bool)>`,
rendered as a `row-sub`, the `bool` adding `text-alarm`):

- unticked, and only when the phone has a readable allowlist (`policy.allowlist_json.is_some()` and not
  `rows.allowlist_corrupt` - the launcher restricts calls only then, `EnforcementPlan.kt:216`, as `calls.rs:1622`):
  `apps.dialer.not_allowed` - "Av: bare nødanrop virker" / "Off: only emergency calls work" (plain);
- ticked with On Home off: `apps.dialer.off_home` - "Ikke på Hjem: barnet har ingen måte å ringe på" / "Not on Home:
  the kid has no way to make a call" (`text-alarm`).

**The guard** - new `devices::dialer_locked(state, id, package) -> Result<bool, sqlx::Error>`: one query for the
policy's `calls_mode` and the last report's `call_state_json`; true when calls are managed and `package` is the
reported dialer. While true, a post that would change the row is refused at the row - nothing written, back to
`apps_url(id, "notice=dialer_managed&app=app-<package>", from, "app-<package>")` wrapped in `settings_log::refused`, the
reason `device.apps.refused_dialer` ("Samtaler er styrt av Vibb: Telefon-appen følger Vibbs telefonbok. Endre det under
Anrop og kontakter." / "Calls are managed by Vibb: the Phone app follows Vibb's phone book. Change that under Calls &
Contacts." - the page's own name, `device.page.calls`):

- `toggle_app` - on *and* off (the row is frozen while managed; a missing row is put back on the switch to unmanaged,
  below), after the existing 400/404 checks and before any write; `back=music` never names the dialer;
- `save_app_display` - every action, reset included; the guard sits right after the package check and the device's
  404, before `validate` and before any `refuse` re-render, so a stale form never gets a field error for a row that has
  no form any more;
- `perms_target` - so `save_app_permissions` and `save_lock_media` (the launcher spares the dialer's camera anyway,
  `CameraLock.kt:233`; refused for one rule, not for a kid-visible effect).

`toggle_refusal` learns `dialer_managed`; the link branch of the row partial renders `app.refused` like the switch
branch. Only a page loaded before the switch to managed can send such a post (the managed row has no form).

**Where a refusal lands** (QA L6). The fragment is the link row's own anchor, `#app-<package>`, and it lands with the
reason on screen in both paths:

- without JS the browser goes to the fragment - the link row, with the reason under it;
- with JS (`swap.js`, design 103) the fragment is not followed; the place is kept by the saved form's chain
  (`anchorChain`: the form, its cell `#app-<package>`, its section). The managed row has no form, so after the morph the
  form is gone, and the cell, an id'd element, is *moved* into Allowed, never rebuilt (`swap.js:453-464`). With the form
  disconnected `anchorDelta` skips its "moved to another group" rule (`swap.js:201-206`, which applies only to a form
  still on the page) and follows the first chain element still drawn - the cell (`:207-212`): the page scrolls so the
  moved row sits where the tapped row was, the reason under it. Fragment `#allowed` would be no better with JS (also
  not followed) and worse without it (the group's top, the row further down). A JS test pins this (B.6).

**Back on the allowlist when calls go unmanaged** - `calls::save_settings`: its transaction becomes
`state.db.begin_with("BEGIN IMMEDIATE")` (it now reads before it writes, as `sms.rs:492`, `zones.rs:594`,
`mmsc_hosts.rs:258`); inside it, read the stored `calls_mode` first; after the `UPDATE`, when it was managed (`managed`
or `off`) and the new one is `unmanaged`, add the reported dialer to the allowlist if it is missing. Through a new
`devices::edit_allowlist_in(&mut SqliteConnection, device_id, edit)` - the read-modify-write on a connection already
in a transaction - which `edit_allowlist` wraps; `edit_allowlist`'s own transaction becomes `BEGIN IMMEDIATE` too (it
reads then writes: two concurrent toggles can no longer both read the old list). For this caller two differences: a
NULL allowlist is left NULL (apps not managed - adding would start managing them with one app) and an unreadable one is
left as it is (logged; the mode save goes through, and Device › Apps already shows the corrupt warning). No report, or
no dialer in it: nothing to add. The parent's other choices for the dialer (a rename, On Home off) are kept as stored:
they were made while calls were unmanaged, and the unmanaged row shows the warning if On Home is off. The save already
nudges the phone (`calls::saved`). No new words on the Calls page; Device › Apps shows the dialer allowed.

Not changed: the Apps tab's "N allowed" count (`tracked_apps::allowed_count`), the lock screen card, the time rules'
usable-app lists - they read the stored allowlist as before.

### B.3 Files and functions

- `server/src/handlers/device_api.rs`: `system_dialer`; `call_and_text_roles` uses it.
- `server/src/handlers/devices.rs`: `dialer_locked`; `edit_allowlist_in` (and `edit_allowlist` over it, `BEGIN
  IMMEDIATE`); the guard in `toggle_app`, `save_app_display`, `perms_target`; `toggle_refusal`'s `dialer_managed`.
- `server/src/handlers/calls.rs`: `save_settings` - `BEGIN IMMEDIATE`, the stored mode and the re-add in its
  transaction.
- `server/src/handlers/device_pages.rs`: `AppView.dialer_note`; `render_apps` - the dialer found once from `latest`,
  the managed link row (inserted after Meldinger and Kalender at the launcher-row index), the unmanaged notes.
- `server/templates/partials/device_app_row.html`: the link branch's tile chain and refusal line; `dialer_note` in the
  switch branch's label.
- `server/src/i18n/en.json`, `nb.json`: the keys below.
- `docs/notes/server/pwa.md` ("Device › Apps") and `docs/notes/server/calls-and-contacts.md` (the re-add): a bullet each.

### B.4 Words (en / nb)

| key | en | nb |
|---|---|---|
| `apps.dialer.managed_label` | {app} → Vibb's phone book | {app} → Vibbs telefonbok |
| `apps.dialer.managed_sub` | Hidden on the phone - the kid calls from Phone book on Home | Skjult på telefonen - barnet ringer fra Telefonbok på Hjem |
| `apps.dialer.off_sub` | Hidden on the phone - only emergency numbers can be called | Skjult på telefonen - bare nødnumre kan ringes |
| `apps.dialer.not_allowed` | Off: only emergency calls work | Av: bare nødanrop virker |
| `apps.dialer.off_home` | Not on Home: the kid has no way to make a call | Ikke på Hjem: barnet har ingen måte å ringe på |
| `device.apps.refused_dialer` | Calls are managed by Vibb: the Phone app follows Vibb's phone book. Change that under Calls & Contacts. | Samtaler er styrt av Vibb: Telefon-appen følger Vibbs telefonbok. Endre det under Anrop og kontakter. |

The value reuses `device.calls.mode.managed` / `.off`. "Telefonbok" is the launcher's own tile name
(`calls_phone_book`); "Anrop og kontakter" / "Calls & Contacts" is `device.page.calls`, the page's name (written out in
the sentence, as `toggle_refusal` returns a `&'static str`). No kid's name anywhere: "barnet" / "the kid", as
`apps.on_home_sub`.

### B.5 Saves in place (design 103)

Every refusal is a redirect to Device › Apps at `#app-<package>` with `notice`/`app`; the row exists in both modes with
that anchor, and it lands on screen with and without JS (B.2, "Where a refusal lands"); nothing jumps to the top. The
Calls page's save is unchanged (`#settings`). The managed row is a plain link (no form), so it posts nothing. `from` is
kept on every redirect (`apps_url`).

### B.6 Tests

Rust, one new module `server/src/tests/dialer_row.rs`. A phone with a report naming `com.google.android.dialer`
installed and preinstalled as `call_state.system_dialer`:

1. `the_managed_row_links_to_calls`: calls `managed` - the row's anchor is a link to `/devices/{id}/calls#settings`
   directly under Vibb One, its label "{own name} → …" (with a rename stored for the dialer, still the app's own name),
   no switch, Name and icon or Permissions for the package, in nb and en; `off` - the off sub-line. Not in Not allowed
   when missing from the allowlist.
2. `the_unmanaged_row_says_what_off_costs`: calls `unmanaged` - the switch as today; unticked shows "Av: bare
   nødanrop virker"; with a NULL allowlist (and with a corrupt one) it is absent; ticked with an On Home off row shows
   the alarm line; ticked and on Home shows neither.
3. `a_managed_dialer_is_never_changed`: calls `managed` - `toggle_app` off and on, `save_app_display` (save, a refused
   input and reset), `save_app_permissions` and `save_lock_media` for the dialer each answer the redirect with
   `notice=dialer_managed` to `#app-<package>`, and the allowlist, `device_app_display` and the permission and
   lock-media rows are byte for byte as before; the same posts for another app still save; with calls `unmanaged` the
   dialer's toggle saves as before. A GET of the refusal URL shows `device.apps.refused_dialer` inside the
   `#app-<package>` cell, in nb and in en.
4. `unmanaged_again_puts_the_dialer_back`: allowlist without the dialer; `managed` -> `unmanaged` and `off` ->
   `unmanaged` through `POST /devices/{id}/calls/settings` (`section=channels`) each add it once; `unmanaged` ->
   `unmanaged` and `managed` -> `off` do not; a NULL allowlist stays NULL; no report leaves the allowlist as it was.

JS, one case in `server/jstest/swap.test.js`: a saved form inside an id'd cell in one group; the new page has that cell,
without the form, in another group further up: `anchorDelta` keeps the cell at its old top (QA L6's landing).

`i18n::tests` and `i18n_pages::nb_pages_have_no_english` cover the new keys.

## Risks

- **A may still not reach WebKit.** Its reasoning is from WebKit's source and the user's symptoms, not a device. Layers
  1 and 2 are independent, so either one that works is enough; if neither does, A.5's answers tell the next round
  whether to take the strip away (the knob in A.5, a visible cost and so the user's choice). Nothing in A can break the
  page: every step is in a `try`, every hold ends (M1), and without `navigator.standalone` nothing runs.
- **A's 150 ms hold** overlaps a `swap.js` refresh at most by a strip one point wider off screen: `swap.js` reads only
  the strip's bottom, which does not change; `locate.js` only its height, the same.
- **B's dialer from the report**: a phone whose dialer changed since its last report (a dialer update renaming the
  package - unheard of for a system dialer) shows the old one until the next report, minutes later.
- **B's re-add** acts on the stored allowlist in the same `BEGIN IMMEDIATE` transaction as the mode, so a concurrent
  toggle cannot lose it, and two toggles no longer race each other either.

## Size

A: about 110 lines of JS, 1 CSS line, 2 head lines, 2 shell lines, the JS test reworked (~200 lines). B: about 210
lines of Rust and template, 6 keys in two catalogs, one test module (~280 lines) and one swap test case. One
implementer, one sitting; B's code QA should check the four tests against B.1's launcher claims.

## Design QA (revision 2)

The design QA of revision 1 (1 High, 1 Medium, 12 Low), each finding and what revision 2 does:

- **H1** - `right: 1px` uncovered a light 1 x 12 pt column on the four `doc-scroll` pages: under iOS 27's `default` the
  web view starts below the status bar, so the column is not inside the screen's corner. Taken: the nudge grows off
  screen, `left: -1px` (A.3 step 1), pinned in `shell.rs:1588` (A.4); the "nothing visible" claim now holds on every
  page, and `right: 1px` is listed with the rejected candidates.
- **M1** - a hold that never completed (hidden or bfcache mid-hold, rAF deferred) left the flag, the class and
  `#0C0C15` set and made every later nudge a no-op. Taken: `endHold()` on hidden, on `pagehide`, from a 1 s cap timer
  and at the start of every return (A.3, "A hold always ends"); JS tests for hidden mid-hold then visible, `pagehide`
  and the cap (A.4).
- **L1** - window `resize` goes to the debounced single nudge (`keyboardMoved()`), not a return; `focus` and the
  colour-scheme change stay returns (A.3, "Triggers"; a test in A.4).
- **L2** - `theme-color` is not counted as a layer (A.3 step 3, A.5) and changes by one `setAttribute` on the existing
  meta, never remove-then-append.
- **L3** - the fresh strip is `strip.cloneNode(false)` plus the class (A.3 step 2).
- **L4** - the `navigator.standalone` gate is kept, and the note says why it is right (A.3).
- **L5** - `save_settings`, `edit_allowlist_in`'s callers and `edit_allowlist` use `begin_with("BEGIN IMMEDIATE")`, as
  `sms.rs:492`, `zones.rs:594`, `mmsc_hosts.rs:258` (B.2, B.3, Risks).
- **L6** - the refusal's landing: kept on the link row's anchor `#app-<package>`, after checking `swap.js`: the managed
  row has no form, so the form leaves the page, `anchorDelta`'s group rule (which needs the form still on the page)
  does not apply, and the moved cell - an id'd element moved, never rebuilt - is followed, so the row and its reason
  land where the tapped row was; without JS the fragment is the row. `#allowed` is no better with JS and worse without.
  A swap JS test pins it (B.2, "Where a refusal lands"; B.6).
- **L7** - "Av: bare nødanrop virker" only with a readable allowlist (`allowlist_json.is_some()` and not corrupt), as
  the launcher restricts calls only then (B.2; test 2).
- **L8** - the placement index is `usize::from(allowed.first().is_some_and(|a| a.row.is_launcher))`, as Meldinger's
  (B.2).
- **L9** - `{app}` is `row.own_label.unwrap_or(row.label)` (B.2; test 1 with a stored rename).
- **L10** - in `save_app_display` the guard comes right after the package check and the 404, before `validate` and any
  `refuse` (B.2).
- **L11** - the refusal names the page "Anrop og kontakter" / "Calls & Contacts" (`device.page.calls`), not the channel
  row (B.2, B.4).
- **L12** - tests added: `off` -> `unmanaged` re-adds; a GET of the refusal URL shows the reason in the right cell in nb
  and en; the unmanaged note absent with a NULL (and a corrupt) allowlist; `save_lock_media` refused for the dialer
  (B.6).

Nothing left open.

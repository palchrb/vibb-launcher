# QA 31 design review - the parent PWA redesign (2026-10-13)

Scope: `docs/design/31-pwa-redesign.md` at `fe8fac07` (branch `design-31`), with main merged in for the 14 approved
mockups in `docs/design/mockups/parent-pwa/*.dc.html`. Read against the root and server `CLAUDE.md` and checked
against the code it reorganises:
- every template in `server/templates/` and `partials/`, `static/{style.css,scroll-restore.js,manifest.webmanifest,register-sw.js}`,
  `templates/sw.js`;
- the admin router (`src/main.rs:640-1150`), `admin_auth::{csrf_guard,admin_headers,no_store_path}`;
- the handlers that render and save the pages (`handlers/{devices,schedules,lifts,window_relock,parent_pin,calls,dns_filter,music,music_import,locate,settings,updates,system_update}.rs`);
- the connection signals (`streams.rs`, `limits.rs:265-349`, `security.rs:563`) and the launcher's sync cadence
  (`push/SyncSchedule.kt`, `push/BackstopAlarm.kt`);
- the tests that read pages (`src/tests/*.rs`, `jstest/`);
- design 30 step 1 (branch `design-30-s1`, `97b197f1`).

Line numbers are main's (`9e4b104f`) unless a branch is named. QA wrote no product code.

**The user's decisions folded in:**
- the tabs (Devices · Apps · Locate · Music · Settings);
- English only;
- the connection-first header;
- the orchestrator's answers to §7. The answer to Q6 is replaced by the user's own: the name is **"Vibb Phone"** (L4).

**Verdict: buildable after a spec fix round.** The information architecture is sound:
- every card on today's device page has a home (§3.9), with the gaps listed in L6;
- the sub-page split fits the router;
- CSRF needs no new work;
- the no-JS plan (`<details>`, links for pickers, server-rendered tab) is right.

But three things would go wrong if the spec and the mockups were built as written:
- **H1:** the iOS "one switch saves itself" pattern meets handlers that read one form's **missing** fields as "off" or
  "no limit". §5.6 lists 5 such forms; at least 5 more aren't listed. The worst one,
  `/schedules/device/{id}`, silently removes a phone's screen-time limit when its "Own rules" switch is saved alone.
- **H2:** the 45-minute "Online" window is shorter than a healthy phone's own check-in gap. The backstop is an
  *inexact* alarm that can fire up to 52.5 min apart. A connected phone would read "Offline · changes wait until it's
  back" on quiet nights, in the header the user asked for.
- **H3:** the mockups draw single values as chevron rows that open picker pages. Their saves land at the top of the
  parent page, which breaks the user's "never jump" rule. The flash is placed at the top of the page, where scroll
  restore never shows it. Several handlers are shared between two of the new pages, and the "`back` on every form"
  rule isn't specified for them.

The Mediums:
- M1: the build order's step 2 is too large;
- M2: the mockups drop controls and invent others;
- M3: a Settings value that calls GitHub;
- M4: local time needs a time-zone database;
- M5: Rust builds sentences where rows need values;
- M6: one-tap risky actions;
- M7: design 30;
- M8: the test plan;
- M9: the CSS floor for old phones.

Counts: 3 High, 9 Medium, 8 Low.

## High

1. **H1: forms whose handler reads a missing field as "off" can't be split into self-saving rows** (§3.0 "switch ...
   which saves itself", §5.6, the mockups' grouped switches).
   - **Evidence.** A checkbox that isn't ticked sends nothing, so these handlers write every field of their form on
     every save and read an absent field as off, empty or default:

     | Route | Fields written from one form | Absent means | In §5.6? |
     |---|---|---|---|
     | `/devices/{id}/policy` (`devices.rs:2009-2023`) | 4 quick controls + web filter | off | yes |
     | `/devices/{id}/hardening` | 10 restrictions | off | yes |
     | `/devices/{id}/music` (design-30-s1: `v=2`, `mobile_switches`) | stream data, download data, storage limit, volume cap, Storytel | off / none | yes (as 3 fields; now 5, M7) |
     | `/devices/{id}/music/sonos-rooms` | all + rooms | off | yes |
     | `/devices/{id}/kiosk-escapes` (`device_detail.html:194-208`) | update fence, notification filter, boot cover | off | **no** |
     | `/schedules/device/{id}` (`schedules.rs:665-680`, `parse_budget_form` `:623`) | own-rules switch **and** 7 daily budgets | budget `""` = **no limit** | **no** |
     | `/devices/{id}/calls/settings` (`calls.rs:535-550`) | managed, calls, SMS, message button | off | **no** |
     | `/devices/{id}/contacts/{cid}` (`calls.rs:732-734`) | in, out, home, message app, Matrix ID | off | **no** |
     | `/devices/{id}/launcher` (`devices.rs:2235-2239`) | language, apps per row | `system`, `3` | **no** |
     | `/schedules/rules[/{id}]` (`schedules.html:9-48`) | name, kind, calls, 7 days, exempt apps | off / not exempt | **no** |
   - **Failure.** The redesign naturally draws these as separate rows. M6/§3.5 put "the own-rules switch" and "its
     own screen time" on the device Time page. The Settings Time mockup makes "Daily limit ›" its own page, and the
     device page would follow it. If the own-rules switch becomes its own auto-saving form, every flip posts no `b0..b6`
     and `save_device_schedule` stores seven `None`s. The phone's own screen-time limit is gone, with no error. The same
     pattern resets the language to "Same as the phone" (launcher), turns off boot cover (escapes), or clears a
     contact's "Out" when its Matrix ID is saved from a sheet. The RuleEditor mockup's "Apps that still work ›" is a
     separate page. Saving the exemptions there would need the whole rule, and saving the rule without them clears
     them.
   - **Fix.** Add one rule to §5.6: **a form is split only if its handler writes only the sections it was sent.**
     - Each form that is split gets a hidden section marker, as design 30 did with `v=2`, for example
       `<input type=hidden name=section value=quick>`. The handler updates only that section's columns. Without a
       marker, today's "write all" stays, for old tabs and tests.
     - Do this for `policy` (quick controls / filter; keep `kiosk_desired = 1` in both), `schedules/device` (switch /
       budget) and, if the Home Screen page separates them, `launcher` (language / columns).
     - Every other form in the table moves as one block: one group, one form, and the switches inside it still
       auto-save the whole form.
     - The rule editor keeps the exempt apps inside its form as a `<details>`, not a linked page.
     - Tests: for each split form, saving one section leaves the other's stored values unchanged.
       `schedules/device` gets a test that a switch-only post keeps the budget.

2. **H2: the 45-minute Online window flags healthy connected phones as Offline** (§3.10).
   - **Evidence.** With the stream up, the phone's only regular contact is the backstop sync, every 30 min
     (`SyncSchedule.kt:17`). The keepalive is server to phone and updates nothing on the server. The backstop is armed
     with `setAndAllowWhileIdle` (`BackstopAlarm.kt:56`), which is inexact. Android gives an inexact one-shot alarm a
     window of 0.75 × its delay, so a 30-min backstop may fire up to 52.5 min after the last sync. §3.10's "plus 9-15
     min of Doze slack" models only the while-idle quota, not the window. "Last contact" is the newest of
     `last_access`, `last_seen_at` and the stream's `since` (`streams.rs:53-61`; it moves only on open or close). On a
     quiet night, with no nudges, it ages to 46-52 min on a phone with a perfectly good stream.
   - **Failure.** The table's Online rule ("stream open and last contact ≤ 45 min") fails, and so does Away ("no open
     stream"). So the phone shows **Offline · seen 50 min ago · changes wait until it's back** while a lock or a ring
     would reach it in seconds. It flickers back on the next sync. This is the header the user asked for, and it
     would be wrong in the most common idle state. Lesser points:
     - the dead-stream case is bounded: with a keepalive every 240 s, a vanished phone's stream fails within about
       20-30 min (Linux `tcp_retries2`). That isn't "many minutes" without a bound;
     - "changes may take up to 15 min" (Away) is also optimistic: a 15-min inexact backstop can fire after 26 min.
   - **Fix.**
     - **Online** = an open stream **and** last contact ≤ **60 min**: 30 × 1.75 = 52.5, plus margin.
     - **Away** = no open stream and last contact ≤ **45 min**: 15 × 1.75 = 26.25, plus the 9-min quota and margin.
     - **Offline** = everything else.
     - Two constants, `ONLINE_WINDOW_MINUTES` and `AWAY_WINDOW_MINUTES`, each with its derivation and a comment naming
       `BACKSTOP_INTERVAL_MS` and `BACKSTOP_SSE_DOWN_INTERVAL_MS`.
     - Away's line says "changes may be delayed", with no number.
     - Table tests add: stream open with last contact at 50 min = Online; at 61 min = Offline; no stream at 30 min =
       Away.
     - A check on the emulator: `adb shell dumpsys alarm | grep -A3 BACKSTOP` shows the window. Leave a phone idle for
       an hour and expect Online throughout.

3. **H3: "a save never jumps to the top" breaks on picker pages, on the top-of-page flash, and on shared handlers**
   (§3.0 "picker", "Flash", §5.1, §5.4 "picker sub-page", the mockups).
   - **Evidence.**
     - `scroll-restore.js` restores only on the **same path**, with no `#fragment` (`restoreTarget`,
       `scroll-restore.js:14-19`).
     - The mockups draw single values as chevron rows: "Turn off after · 1 minute ›", "Background · Same as Home ›"
       (LockScreen), "Type · School ›" (RuleEditor), "Daily limit ›" (Time), "Location · Only when asked · 7 days ›"
       (§3.6). §5.4 suggests a picker sub-page "each a submit button".
     - The flash is "one line at the top of the sub-page that was saved" (§1.12, §3.0).
     - Several POST routes are now used from two pages:
       - `lifts` and `lifts/{id}/end`: summary quick actions and Time page (`lifts.rs:123,154` → `/devices/{id}`);
       - `end-windows`, `start-pause`, `allow-blocked`: summary and Attention (`window_relock.rs:244`);
       - `apps/toggle`: Apps page and Music page (`anchor=music`, `devices.rs:1797`);
       - `dns/domains/*` and the override: Settings › Web Filter and the device Web Filter page
         (`dns_filter.rs:292-311,505`);
       - `/settings/calls` with no `device_id` → `/devices` (`calls.rs:946-950`), once it moves to Phone Setup.
   - **Failure.**
     - A picker page's POST redirects back to the parent page. That's a different path from the picker page, so the
       parent page loads at the top. A parent who scrolled to "Turn off after", picked a value and came back lands at
       the top: exactly what the rule forbids.
     - In the rule editor, a "Type ›" page also throws away the unsaved name and times (the editor has an explicit
       Save).
     - On a long page, a flash at the top is never seen: the restore keeps the parent where they were.
     - A Time-page "End now" lands on the summary: the wrong page, at the top.
     - The country code saved on Phone Setup lands on the Devices list.
   - **Fix.**
     - **No picker pages for saving.** A single value is an inline native `<select>` in the row (the mockup's
       Patterns "Picker" does exactly that). It auto-saves, with `<noscript>` Save. The OS picker on a phone is
       already the iOS-style list. Chevron rows are for navigation only.
     - **The flash sits in the group of the form that saved**, under its rows, not at the page top. Its id is that
       group's anchor, for the no-JS path.
     - **Shared routes get a `back` field**, checked against an allow-list built from the device id:
       - the summary and `/devices/{id}/{time,apps,music,filter,lock,home,advanced}`;
       - `/settings/provisioning` for `/settings/calls`;
       - `/dns` for the shared domains.

       Every other route redirects to the one page its form lives on, with no field. That is simpler than §5.1's
       "every form sends back".
     - **Anchors only where they help.** Keep a fragment where the redirect lands on a *different* page, or where
       no-JS needs it to find the row (long lists: `#app-{pkg}`, `#music-entry-{id}`). Same-page auto-saves on short
       pages redirect without a fragment, so the script restores the exact position.
     - A test: a table of `(form route, page) -> expected Location` for every POST the sub-pages render. Build it from
       the rendered `action`s, so a moved form can't keep a stale target.

## Medium

1. **M1: build step 2 is too large; split the device page one sub-page at a time** (§8 step 2, §5.5).
   - **Evidence.**
     - `render_device` builds all 21 cards in one function (`devices.rs:1139-1540`).
     - The tests fetch `/devices/{id}` about 110 times across 24 files: `window_relock` 17, `lock_glance` 15,
       `hardening` 14, `time_rules` 14, `music` 13, ...
     - The app-display 400 path re-renders the whole device page with the entered values (`devices.rs:685`,
       `app_display.rs` tests assert `id="display" open`).
     - The summary's Attention and the list's badge need about 10 reads per phone: relock, PIN, play, escapes,
       `call_report`, music, sound, boots, policy problem, notification access, Google backup. §3.8/§5.6 says "one
       query per phone".
   - **Failure.** One step that moves 9 sub-pages, the summary, every redirect and about 110 test fetches is
     unreviewable. It also can't be bisected: any one wrong redirect hides among the rest.
   - **Fix.**
     - Move one sub-page per step. Each step does three things:
       - it adds the GET route and the template;
       - it moves the card's forms and their redirects;
       - it leaves a navigation row where the card was on the old page.

       The old page shrinks until the last step turns what remains into the summary. A half-old page with rows to
       the new sub-pages is coherent.
     - Split `render_device` into card builders: `time_card`, `play_card`, `parent_pin::card` and `music::device_card`
       already exist. Each sub-page handler calls only its own.
     - Add one `attention::collect(state, device) -> Vec<AttentionItem>` (kind, level, short text, target URL). Use it
       in both the summary and the list's count, with a test that the count equals the summary's rows.
     - Add a test helper `device_page(id, Section)` that returns the URL, so a moved test changes one argument.
     - The step list below follows this.

2. **M2: the mockups drop controls and invent others; say which wins** (§3.9 "Nothing is dropped", §6, the 14
   artboards).
   - **Evidence.**
     - Dropped in the mockups:
       - Locate: Stop Ringing, **Erase Phone…**, the location mode, interval and history, Cancel on a waiting command,
         and the wipe-not-ready and location-off warnings;
       - Music: Import from Vibb, the per-entry Offline and Check now;
       - the summary: the Location row (§3.2 table);
       - About: "Time lock (raw)" (§3.9).
     - Invented in the mockups, with no server support or the facts wrong:
       - "Show what's playing" switch (LockScreen; no such setting);
       - "Unlock Now" and "waits 1 minute" (LockScreen ⓘ). Today's text is 4 free tries, then 30 s, 1, 2, 5 and
         15 min (`device_detail.html:571`);
       - Parent PIN "for 15 minutes" (LockScreen footer; not in today's text);
       - "Rosenhoff skole" (Locate): a place name needs reverse geocoding, which sends the child's coordinates to a
         third party, and CSP `connect-src 'self'` would block a client-side lookup;
       - a Search field (AnnaApps; needs JS);
       - "Show setup code" (HeaderStates): the code is shown once and can't be shown again; a new one is a POST that
         replaces it;
       - the Patterns rows "Vibb lost device owner" and "2 new apps wait for your OK" (no such signals).
     - Drift in labels:
       - "Screen Time" vs §3.2 "Time";
       - "Needs attention" vs "Attention";
       - "+15 min" / "Lock again" vs the glossary's "Add 15 min" / "Lock again now";
       - About's rows (Last contact / Last full report / Network) vs §3.2's;
       - Time's phone rows link to the summary, not `/devices/{id}/time`.
   - **Failure.** An implementer who builds from the approved pictures loses Erase and the location settings, which
     are the lost-phone path, and ships wrong facts about the PIN back-off.
   - **Fix.** Add to §6: **the mockups decide the look; §3 and §3.9 decide the content.**
     - Copy facts come from today's text or the code, never from the mockup's placeholder text.
     - No reverse geocoding: show "10 min ago · within 40 m".
     - No search field; or render it only from JS, with the `hidden` attribute removed by the script.
     - "New setup code" is a POST button.
     - Locate gets Stop Ringing and Erase Phone… in the actions, and the Location settings group below them.
     - Music › Add gets "Import from Vibb ›".
     - Pick one label per drift item and put it in §4.3. Suggested:
       - "Time" for the device row; "Screen Time" reads as the limit alone;
       - "Attention";
       - the short button labels "+15 min" and "Lock again", which fit the 3-column grid;
       - About's three rows from the mockup, plus "Time lock".
     - Q4 is kept as the Locate quick action. Keep the Location row only if the summary has room; the mockup shows
       the summary without it, and both entrances lead to the same tab.

3. **M3: a Settings row value must not call GitHub** (§3.6 "Software Updates · Update available", the Settings
   mockup's "0.25.0" pill).
   - **Evidence.**
     - `updates::render_page` calls `system_update::gather`, and so `latest_release_tag` (`system_update.rs:100-113`,
       an uncached GitHub API call with an 8 s timeout).
     - It also calls `system_maintenance::gather` (`apt` and `tailscale`).
     - Unauthenticated GitHub allows 60 requests per hour per IP.
   - **Failure.** If the hub's value reuses `gather`:
     - every visit to the Settings tab waits for GitHub, up to 8 s;
     - offline, it waits the full timeout;
     - a parent who taps around uses up the rate limit, so the real update check on `/updates` then fails.
   - **Fix.**
     - Every hub value comes from the database or memory only: rule count, filter state, log count, wallpaper count,
       newest backup's mtime.
     - "Update available" comes from the last result of a check: the `/updates` page or the scheduled auto-update
       check. Keep that result in `AppState` with its time, and show nothing when it's unknown.
     - "About" needs a target: either a small `/settings/about` (server version, licence, project link) or the row
       links to `/updates`. Say which.

4. **M4: local time (Q5) needs a time-zone database, and the badge's end time needs the phone's day** (§3.2 header
   line 3, §3.10 "Relative time", Q5).
   - **Evidence.**
     - The server has `chrono` without `chrono-tz` (`Cargo.toml:21`), so no IANA rules.
     - `provisioning_settings.time_zone` defaults to `Europe/Oslo` (`0034_locale.sql:7`). It may be empty, and
       `valid_time_zone` checks only the characters (`provisioning.rs:81-88`), so "Europe/Olso" passes.
     - Database stamps are `datetime('now')` strings with no zone.
     - The phone's report has `day` (its local date) and `active_rule_name`, not the window's end
       (`devices.rs:984-1016`).
   - **Failure.**
     - Without a tz library, "Today 14:05" is either UTC mislabelled as local, or wrong by an hour across DST.
     - An unknown zone must not panic or silently show UTC as local.
     - "Leggetid until 07:00" at 01:00 belongs to *yesterday's* window. The server knows the phone's `day` but not
       its clock time, so for a rule whose days differ it can pick the wrong day's end.
     - A report from yesterday still draws "42 of 90 min today".
   - **Fix.**
     - Add `chrono-tz` (MIT/Apache-2.0, GPL-compatible; it compiles the IANA data in, so there is no runtime
       `zoneinfo` dependency in the distroless image).
     - One `display_time` module: `parse_db_utc`, `local(&tz)`, `relative(now)` with §3.10's rules, and an exact
       time for `title=`. Tests: Oslo DST both ways, an empty zone, an unknown zone (falls back to UTC, labelled
       "UTC"), yesterday, a clock 2 min in the future.
     - Reuse `parent_pin::when_text` where it fits, or fold it in.
     - The badge's end:
       - show it only when the phone's `day` and the day before give the same end for that rule;
       - otherwise the rule name alone (§3.2 allows that);
       - never when the report's `day` isn't the server's local today, for both the badge and the bar.
     - A launcher `active_until` field would remove the guess. That is an API change (both directories, one commit),
       so leave it out of design 31.

5. **M5: the page needs values, but Rust builds sentences; the rewrite reaches the unit tests too** (§2.2b, §5.5).
   - **Evidence.**
     - About and the header need typed values: ringer On/Vibrate/Silent, a connection state, the time of last access.
       Today's builders return finished sentences:
       - `sound_mode::sound_lines` (`sound_mode.rs:31-63`);
       - `streams::instant_changes_line` (`streams.rs:158-172`);
       - `limits::last_access_line` (`limits.rs:327-349`);
       - `play.lines`, `escapes.lines`, `kid_lock` lines and warnings, `boot_witness`, `calls::call_report`,
         `music.lines`.
     - Many of these are pinned by `#[cfg(test)]` unit tests inside `src/*.rs`. §5.5's counts are `src/tests/` only.
   - **Failure.** A template-only rewrite can't produce "Ringer · Vibrate". A Rust rewrite all at once would break
     dozens of unit tests at once, outside the pages being moved.
   - **Fix.**
     - Each builder gets a typed sibling (`sound_mode::state(...) -> SoundState`, `ConnectionState`,
       `LastAccess` is already typed).
     - Its sentence function stays until its last caller is gone. The step that moves the card switches the caller
       and deletes the old function and its unit tests.
     - §2.2b becomes per-step: each step rewrites the runtime texts its pages show (step list below).
     - Attention rows take a short title from the typed value, not "the first sentence of today's text" (§3.2 item 2).
       Several first sentences run past 8 words and contain package names.

6. **M6: risky actions reachable in one tap** (§3.2 Quick actions, Q7).
   - **Evidence.**
     - "Unlock for 2 h" (`start-pause`) opens the whole phone: apps, Settings and Play (`device_detail.html:105`). In
       the 2×3 grid it sits next to "+1 h" and "Pause a rule".
     - "Remove the kid's PIN" is a one-button form that turns the lock off (`device_detail.html:590-594`).
     - A contact's Remove (`device_calls.html:104`) is one tap, with no confirm even with JS. It removes the contact's
       call rules, so their calls are blocked.
     - Q7's answer covers only the `confirm()` deletes (`schedules.html:53`, `music*.html`, `wallpapers.html:60`).
   - **Failure.** A thumb on a phone-sized grid, or a row that moved under the finger after a reload, unlocks the
     child's phone for two hours or removes its lock. Without JS, the `confirm()` deletes act at once.
   - **Fix.**
     - "Unlock for 2 h…" opens a `<details>` sheet: "Unlock Anna's phone for 2 hours? Apps, Settings and the Play
       Store open." plus a button that names the action.
     - Remove Child PIN and Remove Contact get the same `<details>` confirm as Q7's deletes.
     - Lock again, Lock (Locate), Ring and the extra-time buttons stay one tap: they protect the phone, or can be
       undone.
     - Every confirm stays a plain POST; no GET ever changes state (see "Checked and fine", CSRF).

7. **M7: design 30 step 1 changes the Music card the redesign moves** (`design-30-s1`, `97b197f1`).
   - **Evidence.**
     - On the branch, `device_detail.html:451-472` replaces the one mobile-data switch with two (`stream_mobile_data`,
       `download_mobile_data`).
     - It adds the `v=2` marker, a `data_note` warning and a "Music storage limit" select.
     - `handlers/music.rs` `mobile_switches` reads `v=2` and otherwise falls back to `mobile_data`.
     - `music_entry.html:91` now says "(its Music card)".
     - Design 30 step 3a also changes the server's `MusicState` and `storage_line` (design 30 §5.2), so the card's
       lines change again later.
   - **Failure.**
     - If the device Music page is built from main's card, the merge reverts design 30's form or drops `v=2`. Without
       the marker, the handler reads both switches from the missing `mobile_data`, and every save turns both off.
     - Rewriting `music.lines` copy in design 31 while 3a edits the same builders is a merge fight.
   - **Fix.**
     - **Merge `design-30-s1` into main before the device Music page step**, and build that step on top of it.
     - The page keeps the whole 5-field form, with `v=2`, as one group: "Mobile data" (two switches + footer), then
       "Storage limit" (select), "Volume limit" (select) and "Storytel" (switch + note). `data_note` sits under the two
       switches as an orange row.
     - The Patterns mockup already shows "Music storage limit" as a picker row (inline select, H3).
     - `music_entry.html`'s hint becomes "(the phone's Music page)".
     - The music lines (`storage_line`, library, older-phone note) are moved and restyled but **not reworded** until
       design 30 step 3a is in main.
     - The QA-30 tests that assert the card's text (`tests/music.rs`, +577 lines on the branch) are repointed to
       `/devices/{id}/music` in the same step.

8. **M8: the test plan needs helpers, not only `data-` hooks** (§5.5).
   - **Evidence.**
     - Tests assert attribute order and whitespace:
       - `checked onchange="this.form.submit()"` (`tests/hardening.rs:173-180`, `music.rs:2395-2557`,
         `lock_glance.rs:497`);
       - `value="on"  onchange` with two spaces for an unticked box.
     - Redirect `Location`s are pinned with anchors (`enrollment.rs:355,372`, `parent_pin.rs:68`,
       `music.rs:906-2606`).
     - `pages_restore_scroll_after_auto_save` checks only top-level `templates/*.html` for the head partial
       (`tests/device_api.rs:829-838`).
   - **Failure.** Adding `class="sw" role="switch"`, or moving `onchange` into a script (L7), breaks every such
     assertion without any behaviour change. Templates put in a `templates/device/` folder would escape the head check
     and its scroll restore.
   - **Fix.**
     - Step 1 adds test helpers in `tests/mod.rs` and converts these assertions to them:
       - `input_checked(html, name, value) -> Option<bool>`;
       - `autosaves(html, name) -> bool` (`onchange` or `data-autosave`);
       - `form_action_of(html, input_name)`;
       - `nav_current(html) -> &str`.
     - Facts are asserted on `data-` hooks plus a short phrase, as §5.5 says. Security pins
       (`crash_reports_reach_the_device_page_without_free_text`, `js_string`) stay exact.
     - The head-partial check walks subfolders.
     - Redirect tests assert path and fragment through one helper, so H3's table drives them.

9. **M9: the CSS must keep working on old phones** (§5.4, the mockups' CSS; the requirement that old browsers keep
   working).
   - **Evidence.**
     - Today's CSS already assumes custom properties, flexbox `gap`, grid and `env()`
       (`style.css:1-28,228,287,370`): about Chrome/WebView 84 and Safari 14.1. Older ones lose only spacing.
     - The mockups add:
       - `appearance:none` switches drawn with `::before` on the `<input>` (LockScreen, Patterns);
       - `:focus-visible` as the only focus style (Safari < 15.4 has none, so `appearance:none` leaves no focus ring);
       - `.5px` hairlines (0 px at 1× in some engines);
       - a translucent bar.
     - The current CSS has no `color-scheme`.
     - `register-sw.js` uses an arrow function.
   - **Failure.**
     - An engine that doesn't draw `::before` on a checkbox shows a blank pill whose state is colour alone.
     - Forced-colours / high-contrast modes drop the backgrounds, so on and off look the same.
     - Keyboard users on older Safari lose focus.
     - In dark mode, native `<select>` popups, `type=time` inputs and scrollbars stay light.
   - **Fix.** Add a "browser floor" paragraph to §5:
     - **Floor:** Chrome/WebView 80+ and iOS Safari 14+ render the design. Anything older gets plain, working
       controls.
     - **No** `:has()`, `color-mix()`, CSS nesting, `@layer`, container queries, or `dvh` without a `vh` fallback.
     - **The switch:**
       - a real checkbox (`-webkit-appearance:none; appearance:none`) followed by a sibling `<span class="knob">`
         drawn by CSS, never a pseudo-element on the input;
       - `@media (forced-colors: active) { .sw { appearance: auto } .knob { display: none } }`;
       - focus as `:focus` with an outline, reset by `:focus:not(:focus-visible)`.
     - Hairlines 1px in a lighter colour; 0.5px only under `(min-resolution: 2dppx)`.
     - `:root { color-scheme: light dark }` and `<meta name="color-scheme" content="light dark">`.
     - `prefers-reduced-motion` turns off the switch transition.
     - If the status bar becomes `black-translucent` (§5.3), add `viewport-fit=cover` and top/bottom safe-area
       padding, and test it on a real iPhone.
     - `register-sw.js` in ES5.

## Low

1. **L1: the server-side tab can be per template, not a path matcher** (§3.1).
   - Askama inlines includes at compile time, so a page can set its tab before the header:
     `{% let tab = "settings" %}{% include "partials/app_header.html" %}`.
   - That works because every template belongs to exactly one tab (`device_calls`, `provision_qr` and `enroll_code`
     to Devices; `schedules`, `dns_*` and `provisioning_settings` to Settings).
   - It needs no request plumbing through 30 template structs. The `/devices/locate` before `/devices/{id}` question
     goes away, and the inline active-tab script (`app_header.html:32-42`) is deleted.
   - A test GETs one route per tab and checks `nav_current`.

2. **L2: the Attention list and the header miss some states** (§3.2 item 2, §3.10).
   - **Attention misses:**
     - "Incoming calls aren't blocked" (`device_detail.html:234`, a school rule lets calls ring);
     - the kid-lock warnings (`lock.warnings`): a Child PIN set but the lock off on the phone is an alarm;
     - `ledger_unreadable` and `launcher_without_rules` (orange).
   - **Header states:**
     - A phone with its access revoked can't come back by itself. Show **Disconnected** (grey) with a link to Advanced
       › Connection, not "Offline · changes wait until it's back".
     - Enrolled but never seen: "Not seen yet".
     - Offline keeps "at last report" on the lock badge. Away needs it only past 15 min.

3. **L3: contrast and target sizes in the mockups** (§5.4). The text colours pass 4.5:1 in both themes (checked:
   grey 4.75 on `#F2F2F7`, tint 4.84, red 4.72 on `#FDECEE`, dark grey 5.94). These don't:
   - **Dark-mode strong button:** white on `#4A9BFF` is 2.83:1 ("Lock again"). Use `#0B66D6` in both themes (5.4:1)
     or black text.
   - **Switch tracks:** off `#E3E3E8` on white is 1.28:1, and on `#34C759` is 2.22:1. WCAG 1.4.11 wants 3:1 for a
     control's state. Use `#248A3D` for on (4.4:1) and give the off track a `#8E8E93` 1px border.
   - **The lock pill:** its colours are hard-coded (`#EEF0FF`/`#3A36B8`) in the dark frame too. Make them tokens.
   - **The ⓘ summary:** 28×28 px in LockScreen. §4.4 says 44×44: pad the hit area.
   - **Locate's phone switcher:** the state is a coloured dot only. Add the word in its `aria-label`.

4. **L4: the rename to "Vibb Phone" (the user's decision, replacing Q6) and installed PWAs** (§5.3).
   - **Change:**
     - `<title>{{ title }} · Vibb Phone`, the header brand, `login.html`'s heading;
     - the manifest `name` **and** `short_name`: "Vibb Phone" is 10 characters, under the about 12 that Android and
       iOS show beneath an icon, so both can be the same;
     - `apple-mobile-web-app-title`;
     - the manifest `description`;
     - README, `DEPLOY.md`;
     - the backup error message's brand (`backups.rs:1016`).
   - **Keep the identity:**
     - Add `"id": "/"`: it equals today's implicit id from `start_url`, so nothing reinstalls, and later changes
       can't orphan the app.
     - Keep the manifest URL unchanged, with no `?v=`: older Chrome keys a WebAPK to it.
   - **What existing installs see:**
     - Android Chrome updates the WebAPK's name at a later launch, after its own update check (it can take days).
       Newer Chrome may ask the user to accept the new name.
     - iOS never renames an existing home-screen icon: re-add it, or rename it by hand. Say so in the release note.
   - **Theme colour:** `theme_color` becomes the bar colour. Add two `<meta name="theme-color" media="(prefers-color-scheme: …)">`,
     light `#F9F9FA` and dark `#161618`.
   - **Leave out:** the TOTP issuer (`security.rs:155`, "Kids Device MDM") is outside the user's list. It only labels
     *new* authenticator entries, and changing it never breaks codes. Leave it, or change it in its own commit. The
     log line in `main.rs:179` may change freely.

5. **L5: old bookmarks and in-page links** (§3.1, §5.1).
   - `/schedules?device=X`, `/dns?device=X` and `/dns/log?device=X` lose their per-device content. Answer them with a
     redirect to `/devices/X/time` or `/devices/X/filter`, with tests.
   - These links must point at the new page and anchor:
     - `music_entry.html:164` (`/devices/{id}#music`);
     - `enroll_code.html:22` (`#access`);
     - `music_storytel.html:26,36,45,73` and `music.rs` redirects (`/music#storytel`, `#app`, `#sweep`, `#import`,
       `#categories`, `#category-{id}`) once those move to `/music/{settings,categories,import}`;
     - `handlers/music.rs:3688` (`?music_notice=too_big…#music-entry-…`).
   - `POST /music/import` (preview) renders the whole Music page through `render_import`. It must render the new
     import page.
   - New GETs that share a path with a POST (`/devices/{id}/music`, `/music/categories`, `/music/import`,
     `/schedules/rules/{id}`) are chained on one `.route(path, get(..).post(..))`, as `main.rs:677-682` does.

6. **L6: smaller gaps in §3.9's map.**
   - `offline_override_used` for old launchers (`device_detail.html:608`) → Lock Screen & PINs.
   - `relock.notice` → the summary's flash.
   - `lock_screen.unreadable` / `empty_text` → Lock Screen.
   - The rules' `corrupt_rules` and `budget_corrupt` errors (`schedules.html:61,80`) → the Time pages.
   - The not-enrolled `access.typed_paused` → the "Connect this phone" group.
   - The default country code becomes a **second form** on Phone Setup, with its own Save. The provisioning form's
     handler writes all its fields too (H1).
   - Wallpapers moves under Settings, and Q1 keeps its per-photo switch.

7. **L7: move the inline `onchange` to one static script while every template is open anyway** (§5.2, §5.4).
   - Today: 54 `onchange="this.form.submit()"`; CSP still allows inline script "until S7" (`admin_auth.rs:442-447`).
   - Replace them with `data-autosave` and one deferred `static/autosave.js`. It calls `form.submit()`, which
     scroll-restore already hooks (that script loads un-deferred first).
   - The same script can handle a `<select>` changed by the arrow keys: submit on Enter or blur, not on every key
     (§5.4's open decision).
   - The tests use M8's `autosaves()` helper.
   - Optional for design 31, but cheapest in step 1 or with each page.

8. **L8: docs and cross-references.**
   - Outside `docs/design`, these name today's labels: `server/DEPLOY.md`, `docs/testing/emulator.md`,
     `docs/setup/google-account.md`, `server/CLAUDE.md`, `launcher/CLAUDE.md`, `PLAN.md`. Examples: "DNS/Filters",
     "Provisioning settings", "Phone hardening", "the device page".
   - Each step updates the ones its pages rename.
   - §5.1 cites `music.rs:1798`; it is `devices.rs:1798`.

## Checked and fine

- **CSRF.** `admin_auth::csrf_guard` is one layer over the whole admin router (`main.rs:1171`). It refuses any
  non-GET with a cross-site `Sec-Fetch-Site` or `Origin`, and the session cookie is `SameSite=Strict`
  (`security.rs:369`). Every new form is covered without work, as long as **no GET changes state**:
  - picker pages that save (H3);
  - a "Show setup code" link (M2);
  - an "Add" day link in the rule editor: it shows empty time fields, it is not a request;
  - Sign Out stays `POST /logout`.
- **Routes.** None of the new GETs collides with an existing route:
  - `/devices/{id}/{about,advanced,lock,home,filter,time,apps}` are new;
  - `/devices/{id}/music` adds a GET to an existing POST;
  - `/devices/new` and `/devices/locate` are literal, and matchit prefers them over `{id}` whatever the order.
- **Scroll restore** compares `pathname` only, so `?notice=` redirects (`window_relock.rs:244`, `parent_pin.rs:86`,
  `devices.rs:2081`) still restore. The 3 s installing reload uses `location.reload()`, which keeps the position.
- **No-JS.**
  - The `<details>` confirms work without JS: revoke (with a `required` box), remove, wipe (type the name), restore,
    format.
  - The three `onchange` device pickers (`device_locate.html:20`, `dns_filter.html:99`, `dns_log.html:19`) and the
    submit-less GET form (`schedules.html:114`) disappear in the new IA.
  - The ⓘ panels are `<details>`. A "Pause a rule…" sheet is a `<details>` holding the two selects and a submit.
- **The online signals** are as §1.2b says:
  - the stream registry is memory-only and moves `since` on open and close (`streams.rs:53-61,118-130`);
  - `record_access` runs on every bearer request (`security.rs:563`, `limits.rs:266-288`);
  - `last_seen_at` is in SQLite.

  Only the window was off (H2).
- **The service worker** has no fetch handler and no cache (`templates/sw.js`). The rename and the CSS change can't
  serve stale pages. Bump `style.css?v=` with every CSS change, as §5.6 says.
- **Contrast:** the mockups' body, secondary, tint, red, orange and green text all pass AA in both themes (L3 lists
  the exceptions).
- **Q1-Q7 answers:** none conflicts with the code.
  - Keeping the DNS server choice keeps `dns_upstream_provider` in the policy.
  - Keeping "This is the launcher app" keeps `is-launcher`.
  - The Locate quick action is a plain link to `/devices/locate?device={id}`.

## Proposed implementation steps

Each step ships together:
- its pages, their copy (§4) and the Rust texts they show (M5);
- the redirect table for its forms (H3);
- its tests, repointed through M8's helpers;
- a check at 390 px, light and dark, with JS off once;
- `cargo fmt`, `clippy --all-targets`, `cargo test` and the `node --test` jstests.

Between steps, the PWA mixes old-style and new-style pages; each page is whole.

1. **Shell.**
   - CSS:
     - the new tokens (light and dark) and components: grouped list, row types, switch (M9), ⓘ, badge, Attention
       levels, flash, confirm;
     - today's classes (`.card`, `.error`, `.button-*`) restyled with the new tokens, so old pages look like the same
       app;
     - `color-scheme`, the browser floor (M9).
   - The 5-tab bar, server-rendered (L1), with `aria-current`, 12px+ labels and `aria-hidden` icons. The active-tab
     script is deleted.
   - The Settings hub rebuilt as navigation with cheap values (M3). It holds Time Rules (`/schedules`), Web Filter
     (`/dns`), Blocked Activity (`/dns/log`) and the existing rows, so nothing loses its entrance when the Time rules
     and DNS/Filters tabs go.
   - The Apps|Music switch removed.
   - The "Vibb Phone" rename (L4).
   - The test helpers (M8), and optionally `autosave.js` (L7).
2. **Display helpers + Devices list.**
   - `display_time` with `chrono-tz` (M4);
   - `connection_state` with H2's windows and the edge states (L2);
   - `attention::collect` (M1).
   - The list rows use all three: dot, state, "seen …", lock part, screen-time bar, badge count.
   - Pure table tests for each helper.
3. **About This Phone + Advanced.**
   - About is read-only: connection rows from the typed values; crashes; boots.
   - Advanced holds Restrictions (hardening as one form), App screens, Updates & notifications (escapes as one form),
     Connection (code, QR, Disconnect…) and Remove phone….
   - The old page keeps two navigation rows in their place.
   - Rewrite `instant_changes_line`, `last_access_line` and the play and escapes lines (M5).
4. **Lock Screen & PINs.**
   - Child PIN: the change form; Remove behind a confirm (M6).
   - Parent PIN: its form and events; `offline_override_used`.
   - Screen timeout and lock-screen background as inline selects (H3).
   - Lock-screen text and apps.
   - Flashes in their groups. The kid-lock and boot-witness texts.
5. **Home Screen + Web Filter.**
   - Home Screen: the launcher form, wallpapers, Quick controls.
   - Web Filter: filter switch, blocklist overrides, domains, log switch + table.
   - The `policy` route gets `section` (H1).
   - The `dns` handlers get `back` (H3).
   - `/dns?device=` and `/dns/log?device=` redirect (L5).
6. **Apps (device).**
   - The allowlist (split Allowed / Not allowed, `#app-{pkg}` kept), Wi-Fi-only, downloads, names and icons.
   - The app-display 400 re-render targets this page.
   - The installing reload moves here.
   - The blocks list's links point here.
7. **Music (device)**, after `design-30-s1` is in main (M7).
   - The 5-field form with `v=2`, ticks, Sonos.
   - `apps/toggle` with `back=music`.
   - `music.rs` device redirects.
   - `music_entry.html` links and hint.
8. **Calls & Contacts restyle.**
   - `device_calls.html` in the new components.
   - The calls form and the contact rows each stay one form (H1).
   - Remove Contact behind a confirm (M6).
   - The country code moves to Phone Setup as its own form (L6), with `/settings/calls` → `back`.
   - `calls.rs` warnings rewritten (no package names, §2.2b).
9. **Time Rules + device Time.**
   - `/schedules` as the Everyone page.
   - The rule editor page `/schedules/rules/{id}` (GET; exempt apps in a `<details>`, kind as an inline select,
     `<details>` delete confirm).
   - `/devices/{id}/time`: lifts with `back`, extra time, the own-rules switch with `section` (H1), its screen time,
     its rules.
   - `/schedules?device=` redirects.
   - `time_state_lines` and lift statuses rewritten in local time (M4).
10. **The summary.**
    - The rest of `device_detail.html` becomes the summary: header (connection, lock badge, bar), Attention, Quick
      actions, Today, Sections.
    - Quick actions: "Unlock for 2 h…" behind a sheet; "Pause a rule…" as a `<details>` sheet; the Locate link.
    - Not-enrolled variant. `window_relock`'s foreign-app and blocks texts shortened.
    - `device_detail.html`'s card builders that nothing uses are deleted.
11. **Apps tab.**
    - On phones, catalog rows with counts and badges, Add App.
    - The app page: top, Show as, On phones, Source & signing with "This is the launcher app", Remove.
    - "More options" on Add.
12. **Music tab.** May be split into 12a (the tab) and 12b (the entry page and the shelf) if large.
    - Library rows (`music-cards.js` selectors kept or updated together), Edit order.
    - Add (link, own files, Storytel shelf, Import from Vibb).
    - On phones.
    - `/music/settings`, `/music/categories`, `/music/import` (L5).
    - The entry page and the shelf in the new components.
    - `confirm()` deletes → `<details>`.
13. **Locate tab.**
    - Phone links; one phone shown at once.
    - Map, Update Location.
    - Ring, Stop Ringing, Lock, Erase Phone… (type the name).
    - Footer, warnings as orange rows, Location settings as inline selects.
    - Recent as stacked rows with labels and local times (`models.rs` status lines).
14. **The rest of Settings.**
    - Web Filter (global, the DNS server under Advanced).
    - Blocked Activity (a list of phones).
    - Wallpapers: `<details>` delete; the per-photo switch kept (Q1).
    - Phone Setup + country code.
    - Account, Security Log (event labels), Backups, Software Updates (the cached check, M3), About.
15. **Add phone, sign-in, clean-up.**
    - The 3-step add flow, the code and QR pages ("Server setup needed" + ⓘ), sign-in pages with the new brand.
    - The old CSS classes nothing uses are deleted.
    - Docs updated (L8).

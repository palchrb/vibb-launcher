# QA 31 step 10 (code) - the phone's summary

Reviewer: code QA, 2026-10-14. Branch `design-31-s10`, `83294eda..aa8fa44a` (one commit, 26 files, server and docs).
I read it against `docs/design/31-pwa-redesign.md`:
- §1.2, §3.0, §3.2 (the summary), §3.8, §3.10, §4, §5 (§5.1 incl. §5.1.4, §5.2, §5.5, §5.6), §8 step 10 and §9;
- step 9's status and fix-round table, and "Step 10 implementation status" with its old-vs-new table and its 9 choices;
- `docs/design/24b-override-relock.md` (§1.7's warnings, the commands, the once-per-relock rule, the fix round's
  foreign access) and the Parent PIN and design 24b sections of `server/CLAUDE.md`;
- `qa-31-s9-code.md` for the format and the bar.

I also read the code the step touches or leans on:
- `window_relock.rs` in full against the old file at `83294eda` (`blocks`, `relock_blocked`, `BlockContext::differs`,
  `foreign_view`, `findings`, `answer`, `flash`, `allow_form_value`/`parse_allow_form`, `allowed_since_relock`);
- `handlers/window_relock.rs` in full (`card`, `last_answers`, `catalog_pins`, `queue`, the three handlers);
- `handlers/devices.rs` (`render_device`, `time_report`, `badge_and_screen`, `managed_without_pin`), `attention.rs`
  (`facts`, `facts_of`, `target`), `parent_pin.rs` and `handlers/parent_pin.rs` (`notices`, the 24 h / since-saved cut),
  `handlers/lifts.rs` (`back_to`, `summary_group`, `recent`), the `schedules.rs` and `device_pages.rs` diffs;
- `device_detail.html` old and new side by side, `device_lock.html`'s no-PIN warning, the CSS and head diffs;
- every new and changed test (`tests/summary.rs` and `tests/window_relock.rs` in full, the `hardening.rs`,
  `parent_pin.rs`, `devices_list.rs`, `command_expiry.rs`, `enrollment.rs` diffs, the unit tests) and the docs diff.

Line numbers are `aa8fa44a`'s. No product code was changed: the mutations below were temporary and are reverted, and
`git status` was clean before this file was written.

**Verdict: merge after a small fix round.** No security-relevant warning lost its condition; one lost its explanation.
- **Foreign access.** Same condition (`parse_foreign_access` of the latest report, shown only with a report as
  before), same three steps plus "change the Parent PIN", the apps by the phone's label (escaped, pinned), the packages
  behind the ⓘ; it sits above the quick actions its steps name, and the Attention alarm now lands on it
  (`#foreign-access`; `facts_of` reads the same `relock.foreign`, so row and block can't disagree). Mutation 1.
- **Blocked apps.** `blocks` and `relock_blocked` keep the old rules exactly: an imposter is the phone's `catalog_key`
  or a server pin that differs from the reported key (`differs`, unchanged), it is never in `allow` and so never in the
  hidden `blocks` value (mutation 2); an older launcher's list lasts 7 days (mutation 5) and loses its button and its
  count once an Allow pressed after the relock was answered (mutation 3). The Attention count is unchanged in every
  branch: the old `facts_of` expression (`items.len()`, or for an older launcher `allow.len()`) is now
  `Blocks::waiting`, built in the same function as the list (mutation 7), and the row's target exists whenever the count
  is non-zero. "And N more" (16+) is kept as the footer.
- **Relock findings.** Same 7-day / found-anything window (mutation 4); still-on, turned-off and failed steps red, the
  failed steps in words, never codes. The "removed" line lost its red (L2).
- **Parent PIN notices.** `notices` and the `max(24 h, override_pin_set_at_ms)` cut are untouched; only the words are
  typed (mutation 6). Easy PIN and no PIN keep their Attention alarms with the same conditions, landing on Lock Screen &
  PINs' warnings (pinned by `hardening.rs` and `parent_pin.rs`). Unseen boots are unchanged.
- **The 24b commands.** Payloads, routes, auth, `parse_allow_form` and the own insert are unchanged; only the redirect
  gained its group's fragment (`flash(notice)` picks it, never the request). Lock again shows on the same condition as
  before (capability and a pause or a running install mode), Unlock while no pause is reported (capability), Allow only
  with something to let out. Each command is a fresh insert by design (24b QA #8), so a double tap queues two - as
  before; nothing a crafted POST sends can name more than `parse_allow_form` accepted before.
- **Save never jumps.** Every form on the summary is in `every_form_on_the_summary_returns_to_its_group`, built from
  the rendered forms, each action's fragment checked against the redirect and the flash found inside that group
  (mutations 8, 11); every answer is a redirect; Today shows for its flash alone, Blocked Apps too, so End now on the
  last running pause and Allow on the last block both land on a group that exists. The two confirms and the Pause
  sheet are plain `<details>` and work without JS. A refused pause from the summary now says why on the summary
  (qa-31-s9-code N4 closed).
- **Reads and texts.** The phone, its settings, its last report and its lifts are a 500 (mutation 12); the Sections
  rows keep "Couldn't read". Every time is in the Phone Setup zone with the exact one in `title` (mutation 10); phone
  times carry their age. Phone-supplied labels are escaped everywhere they show (two places unpinned, L3).

On the choices the review was asked to look at:
- **(4) every Settings row live for a phone not set up.** Right: every sub-page answers before setup (steps 3-9 pinned
  that), and what is saved reaches the phone's first policy. The header's "Not set up" says enough.
- **(5) "Restrictions paused" / "No Parent PIN" sentences dropped.** Right for No Parent PIN (and Easy): the alarm lands
  on Lock Screen & PINs, whose warning has the same condition and says what it costs. Not right for Restrictions paused:
  the alarm lands on the quick actions, which say nothing about a pause (M1). The no-PIN card also showed on a phone not
  set up, which now has no word for it (L1).
- **(1)/(2)** command flashes in their own group, and Blocked Apps / Locked Again / the foreign warning as summary
  groups: right - that is where the redirect and the Attention rows land.
- **(7)** "Today" holding a week's answers: acceptable, see N2.

Findings:
- **M1:** the "Restrictions paused" alarm lands on Quick Actions, where nothing says the phone is paused - and for an
  older launcher there is nothing there about it at all.
- **L1:** a phone not set up lost the "No Parent PIN" warning from its page.
- **L2:** a relock that removed proxies, certificates or a screen lock is no longer a warning.
- **L3:** the blocked app's name and the Allow confirm's names aren't pinned as escaped (mutations 9, 13 survived).
- **L4:** a failed read of the catalog's pins or of the commands' answers still fails open on the summary.

Counts: 0 High, 1 Medium, 4 Low, 4 Nits.

## What I ran

All cargo runs used my own target dir (`~/.cache/handy-target/qa-31-10`), under `~/build.lock`.
- **`cargo fmt --check`:** clean.
- **`cargo clippy --all-targets --locked`:** the same 13 warnings as on main (`models.rs` dead fields, `dns_engine.rs`,
  `storytel.rs`, the `sort_by` in `devices.rs:1232`, which is 2026-08 code). None is in the step's code.
- **`cargo test`, unchanged:** 845 passed, 0 failed.
- **`node --test server/jstest/*.test.js`:** 25 passed.
- **The mutations.** Each was run with a targeted filter (`window_relock`, `summary`, `parent_pin`), then reverted:

  | # | Mutation | Caught by |
  |---|---|---|
  | 1 | the foreign-access view always `None` | `window_relock::an_app_that_could_read_the_parent_pin_is_a_red_warning_with_the_steps` |
  | 2 | an imposter goes into `allow` (`if true`) in `blocks` | `an_install_time_block_is_listed_allowed_and_cleared`, `window_relock::tests::allow_sends_the_listed_apps_and_their_keys`, `…_a_catalog_imposter_stays` |
  | 3 | an older launcher's Allow stays after an answered Allow (`!allowed_since` dropped) | `the_phones_answers_show_and_an_answered_allow_hides_its_button`, `window_relock::tests::an_older_launchers_relock_lists_its_blocked_apps` |
  | 4 | the findings' window `2 * WARN_MS` | `the_last_relock_shows_on_the_summary_for_a_week`, `window_relock::tests::nothing_found_or_older_than_a_week_shows_nothing` |
  | 5 | an older launcher's blocked list `2 * WARN_MS` | `the_last_relock_shows_on_the_summary_for_a_week`, `an_older_launchers_relock_lists_its_blocked_apps` |
  | 6 | the PIN notices ignore `override_pin_set_at_ms` | `parent_pin::a_new_pin_ends_the_notices_but_not_the_list` |
  | 7 | `Blocks::waiting` = `allow.len()` (imposters not counted) | `an_install_time_block_is_listed_allowed_and_cleared`, `…_a_catalog_imposter_stays` |
  | 8 | `allow_blocked_sent`'s group `quick` | `summary::every_form_on_the_summary_returns_to_its_group` |
  | 9 | a blocked app's name `|safe` in Blocked Apps | **nothing** (L3) |
  | 10 | an answer's time in UTC (`Zone::utc_fallback()`) | `window_relock::tests::command_answers_say_what_the_phone_said_or_that_it_said_nothing` |
  | 11 | End now's summary group `quick` | `summary::every_form_on_the_summary_returns_to_its_group` |
  | 12 | a failed lifts read shows an empty Today | `summary::the_summary_fails_loudly_on_a_db_error` |
  | 13 | the Allow confirm's names `|safe` | **nothing** (L3) |

  Two of thirteen survived (L3).

## Medium

### M1. The "Restrictions paused" alarm lands on Quick Actions, where nothing says the phone is paused
`src/attention.rs:320`, `templates/device_detail.html:96-160`.

Before, a paused phone had a red sentence in the Status card - "All restrictions are paused on the phone (from here, or
someone entered the parent PIN in its Settings). The pause ends by itself, or press "Lock the phone again now"." (24b
§1.7's wording), with the button's subtitle ("Ends the pause or Play install mode as soon as the phone gets this ...").
Choice 5 drops the sentence and points the red Attention row at `#quick`, "Lock again is there". What the parent finds
there:
- **a `window_relock_v1` launcher:** a dark "Lock again" tile among +15 min, +30 min, +1 h and Locate - no word that
  the phone is paused now, that the pause ends by itself, or that Lock again is what ends it (the tile also shows for
  Play install mode, so it doesn't say which);
- **an older launcher** (`lock_again_follows_the_status_and_the_capability` posts exactly this): no Lock again, no
  Unlock - the alarm lands on the screen-time tiles and nothing on the page mentions the pause.

Restrictions paused is one of §3.2's alarms (the phone is open: apps, Settings, Play), and "does the target explain"
was the question for choice 5. For No Parent PIN the answer is yes (Lock Screen & PINs' warning); here it is no.

**Fix:** while the latest report has `restrictions_paused`, put one red row at the top of `#quick` (as the foreign
warning's step 1 already words it): "Unlocked on the phone · ends by itself" with "Tap Lock again to end it now." when
`relock.lock_again`, else "Ends by itself after 2 hours." (an older launcher). Pin it in
`lock_again_follows_the_status_and_the_capability` for both launchers (`group(&html, "quick")` holds it).

## Low

### L1. A phone not set up lost the "No Parent PIN" warning from its page
`templates/device_detail.html:32` (old file: the `managed_without_pin` card sat outside the `enrolled_at` branch).

The old red card ("No parent PIN is set for this phone ... the only way back is a reset") showed for any managed phone
without a Parent PIN, enrolled or not. The summary moves the signal to the Attention row, and a phone not set up has no
Attention rows (§3.2, by decision), so before setup the page no longer says it - exactly when a parent who manages calls
ahead of setup (or will block USB debugging) should set the PIN. Lock Screen & PINs still warns, and the row appears the
moment the phone enrolls, so this is a reminder lost, not a hidden state.

**Fix:** in the not-set-up variant, one orange row above Connect This Phone while `managed_without_pin` - "Set a Parent
PIN before setup ›" to `/lock#parent-pin` - or accept and say so in the status.

### L2. A relock that removed proxies, certificates or a screen lock is no longer a warning
`templates/device_detail.html:237-258`.

24b §1.7 has the device page "show a warning" for every finding, and the old card rendered each line red. Now
"Removed · 2 Wi-Fi proxies, 1 certificate, an Android screen lock" is a plain grey row, and the Locked Again group comes
after Quick Actions, Today and Blocked Apps with no Attention row. A removed CA certificate or proxy is evidence that
someone set up interception while the phone was open - the parent should notice it even though the phone undid it.
Still on, turned off and failed steps stay red, so the gap is the "Removed" line and the group's "Locked again" row.

**Fix:** `text-alarm` on "Removed" (as on "Still on"), or an orange Attention row "Phone found changes when it locked
again" → `#relock` while `findings` has anything but the time (that also gives the group a way to be seen; §3.2's list
would need the row added).

### L3. The blocked app's name and the Allow confirm's names aren't pinned as escaped
`templates/device_detail.html:208,223`.

The name is the phone's label for an app - for an app the child installed from outside Play, a string the child
chooses. Askama escapes both places today, but mutations 9 (`{{ b.name|safe }}`) and 13
(`{{ relock.blocks.allow_names()|safe }}`) pass every test: `an_install_time_block_is_listed_allowed_and_cleared`
uses the label "Music", and the foreign warning's and findings' escaping tests don't cover these two places.

**Fix:** in that test, report the label as `Mus<i>ic` and assert `group(&html, "blocked-apps")` holds
`Mus&#60;i&#62;ic` twice (row and confirm) and no `<i>`.

### L4. A failed read of the catalog's pins or of the commands' answers still fails open on the summary
`src/handlers/window_relock.rs:130-186` (`last_answers`, `catalog_pins`).

Older than this step, but step 10 made the summary's other reads a 500 and left these two "logged and read as none":
- **pins:** an app that is an imposter only by the server's pin (the phone didn't say `catalog_key`) is shown as an
  ordinary block, "Allowed"/"Not allowed", and goes into the Allow confirm's hidden list - the one thing the summary
  promises never to offer;
- **answers:** an older launcher's Allow that was already answered comes back (`allowed_since_relock` sees nothing),
  its count returns to Attention, and Today drops the answers as if no command was ever sent.

**Fix:** return `Result` from both and make `card` fail the summary's render (500 "Couldn't read this phone"), as the
lifts do; the Devices list's `attention::facts` can keep the lenient read (a badge, not an offer). Pin it in
`the_summary_fails_loudly_on_a_db_error` with `tracked_apps` and `device_commands` renamed.

## Nits

- **N1.** Two string tricks where a typed value is at hand: `reported_when` (`window_relock.rs:362`) decides whether to
  join "when · age" by `age.contains(&when)`, and the header builds "seen 3 min ago" only to strip "seen " again for
  `text_beside` (`devices.rs:1465`, copied from the list's line 125). Pass the relative time itself and add "seen " in
  the template.
- **N2.** Choice 7: "Today" lists a week's command answers ("Lock again · Mon 14:30 · Answered"). The weekday makes it
  readable, but a row from six days ago under "Today" invites "it's still going". Either cut the answers to the local
  today plus any still waiting, or name the group "Recent" (§4.3).
- **N3.** Today's lifts come from `lifts::recent` (the newest 10) and are then filtered; a pause still running behind
  ten newer lifts (a day of +15 min taps) loses its End now on the summary (Device › Time has the same cap). Filter in
  SQL (`created today OR (target = 'rule' AND still delivered)`) instead.
- **N4.** The answers' labels ("Allow these apps", "Unlock for 2 h") and the actions ("Allow These Apps…",
  "Unlock for 2 h…") differ in case on one page; `command_label` could carry §4.3's title case.

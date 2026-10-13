# QA 31 step 3 (code) - About This Phone and Advanced

Reviewer: code QA, 2026-10-13. Branch `design-31-s3`, `4d225da4..9a474a2d` (one commit, 47 files, server and docs).
I read it against `docs/design/31-pwa-redesign.md`:
- §1.2, §2.2b, §3.0, §3.2, §3.7, §3.9, §3.10, §4 and §5 (§5.1, §5.6, §5.7);
- §8 step 3 and the "Step 3 implementation status" with its 15 choices;
- `qa-31-s1-code.md`, `qa-31-s2-code.md`;
- the About, DeviceSummary and HeaderStates mockups.

I also read the code the step touches or leans on:
- `handlers/device_pages.rs`, the `devices.rs`, `attention.rs`, `streams.rs`, `display_time.rs`, `limits.rs`,
  `sound_mode.rs`, `boot_witness.rs`, `kiosk_escapes.rs`, `models.rs` and `crashes.rs` diffs;
- the two new pages, the new partials, the `device_detail.html`, `enroll_code.html` and CSS diffs;
- every changed test, `tests/device_pages.rs` in full;
- `scroll-restore.js`, `enrollment::new_code`, `crashes::sanitize`, the admin router's layers in `main.rs`;
- in the launcher, `INSTALL_MODE_DURATION_MS` (15 min).

Line numbers are `9a474a2d`'s. No product code was changed: the probe test and the mutations below were temporary
and are reverted, and `git status` was clean before this file was written.

**Verdict: merge after a short fix round.** The security-relevant controls moved intact:
- every form keeps its POST route and field names;
- Disconnect Phone keeps its `required` box, and the handler still refuses without it;
- Remove Phone and Disconnect Phone are `<details>` confirms;
- the setup code is never on Advanced (only its hash is stored);
- both new GET routes sit inside `require_full_auth` and give 404 for an unknown phone;
- the templates escape everything, crash traces and phone-supplied names included.

The redirect table is real: it is built from the rendered forms and catches a moved target (M1 and M8 below).

What needs fixing is in the typed values and in two places where the parent loses something:
- **M1:** About says "Last contact: Never" for a disconnected phone that reported a minute ago.
- **M2:** `Zone::when` is used for future end times. After local midnight it gives a date and no time: "Works until
  14 Oct", "Play Store open until 14 Oct".
- **M3:** two of Advanced's auto-saves redirect with a `#fragment`. That makes the page jump to the group header,
  which goes against the user's rule (§5.1.4).
- **M4:** Play install mode is no longer visible on the device page. "Lock the phone again now" still shows there,
  but nothing says why.

Counts: 0 High, 4 Medium, 6 Low, plus nits.

## What I ran

All cargo runs used my own target dir (`server-target-qa31s3`), under the shared build lock.
- **`cargo test`, unchanged:** 735 passed, 0 failed (the implementer's count).
- **`cargo fmt --check`:** clean.
- **`cargo clippy --all-targets`:** the same 13 warnings: `models.rs` (9), `dns_engine.rs` (2), `storytel.rs` (1) and
  `devices.rs:1445` (the old `sort_by`). None is in the step's new code.
- **`node --test jstest/*.test.js`:** 16 passed.
- **A temporary probe test.** It enrolled a phone, sent a report, revoked it, read About, and called `Zone::when`.
  The output is quoted in M1 and M2.
- **The mutations.** Each was run with targeted filters (`device_pages`, `enrollment`, `hardening`, `cleanup`, `step9`,
  `play`), then reverted:

  | # | Mutation | Caught by |
  |---|---|---|
  | 1 | revoke success → `/devices/{id}?notice=…#access` | `device_pages::advanced_forms_return_to_their_groups`, `enrollment::revoking_access_…` |
  | 2 | the revoke box loses `required` | `device_pages::destructive_actions_confirm_with_details` |
  | 3 | the revoke handler ignores the box | `destructive_actions_confirm_with_details`, `enrollment::revoking_access_…` |
  | 4 | Remove Phone outside its `<details>` | `destructive_actions_confirm_with_details` |
  | 5 | the crash trace `\|safe` (unescaped) | **nothing** (L3) |
  | 6 | the `disallow_config_vpn` switch renamed | `hardening::advanced_shows_the_switches_as_one_form` |
  | 7 | the `block_activity_start` switch renamed | `step9::kiosk_block_switch_and_play_not_suspendable` |
  | 8 | the code page's Back for an enrolled phone → `#access` | `advanced_forms_return_to_their_groups` |

- **The merge with `design-31-s11` (`cf7e1686`).** `git merge-tree` reports 3 conflicts (L6).

## Medium

### M1. About says "Last contact: Never" for a disconnected phone
`src/handlers/device_pages.rs:369-380`, `src/streams.rs` (`ConnectionState::Disconnected` carries no time).

`connection_rows` takes the last contact from `connection.last_contact()`. That is `None` for `Disconnected`, so the
row says "Never". The probe:

```
before revoke:  Last contact  21:02 · just now
after revoke:   Status        Disconnected access removed
                Last contact  Never
```

The old Access card said "Last access: … UTC" for a revoked phone too. That fact is how a parent tells "I cut it off
a minute ago" from "it has been gone for weeks", and §3.9 moves the line to About, not away. The same row on a
`NotSeenYet` phone is right.

**Fix:** compute Last contact from the inputs, not from the state. `connection_of` already has `last_access` and
`last_seen`; the newest of the two can be passed in, or `Disconnected` can carry `last_contact: Option<…>`. Add a
test: revoke a phone that reported, and About keeps its time.

### M2. Future end times after local midnight show a date with no time
`src/display_time.rs:148-157` (`Zone::when`); it is used for end times at `devices.rs:523` (live code), `devices.rs:535`
(typed codes paused) and `device_pages.rs:165` (Play install mode).

`when` is a past-tense formatter. It gives the clock time for today, "yesterday HH:MM", and otherwise a date. For a
time **after** today it falls through to the date. The probe, with now = 23:50 in Oslo:

```
zone.when(00:05 tomorrow) = "14 Oct"
```

So a code made at 23:50 (typed: 15 min; QR: 30 min) reads "Setup code · Works until 14 Oct, once". A typed-code
pause (1 h) reads "Typed codes paused until 14 Oct". Install mode (15 min, `INSTALL_MODE_DURATION_MS`) reads "Play
Store open until 14 Oct". In each case the one thing the line is for, the end, is gone.

Evenings are when parents set phones up. The old texts had the full UTC time. The unit test only covers "ahead, same
day".

**Fix:** add `Zone::until(t, now)`. It gives "14:05" today and "00:05 tomorrow" on the next day, the wording
`time_state` already uses for the lock badge, and a date only beyond that. Use it for all three. Add a test at 23:50.

### M3. Two auto-saves on Advanced redirect with a fragment, so the page jumps
`src/handlers/devices.rs:722` (`kiosk-block` → `/advanced#app-screens`), `:1043` (`kiosk-escapes` →
`/advanced#updates`).

`scroll-restore.js` restores the position only when the response has **no** `#fragment` (`restoreTarget`). With one,
the browser scrolls the anchor to the top of the viewport.

On a phone, a parent toggles "Boot cover", the third switch under Updates & notifications. The page reloads and
"Updates & notifications" snaps to the top, so the switch they just touched moves up by most of a screen. The same
happens for "Block other apps' screens". `hardening`, on the same page, has no fragment and keeps the position
exactly.

§5.1.4 says same-page auto-saves on short pages redirect without a fragment. A fragment is kept for another page, a
long list, or a flash; these two have none of those. The old fragments (`#play-kiosk`, `#kiosk-escapes`) were for the
21-card device page.

**Fix:**
- Redirect both to `/devices/{id}/advanced`, as `hardening` does. Keep `revoke`'s `#connection`: it has a flash.
- Update the redirect table, `step9` and `step11`.

Without JS the parent then lands at the page top. Advanced is short, and that is the trade §5.1.4 makes.

### M4. Play install mode left the device page; "Lock the phone again now" has no reason next to it
`src/handlers/device_pages.rs:144-203` (`PlayNote::InstallMode`, Advanced only); `templates/device_detail.html:90`
(`relock.lock_again`).

Before, the device page showed a red "Play install mode is on (until …): the Play Store can be opened on the phone …"
in Play and kiosk. Now that text is only on Advanced › App screens, and it is not an Attention row: §3.2's list has no
such row, and §3.9 sends all of Play's warnings to Advanced.

The device page still offers "Lock the phone again now" while install mode runs (`window_relock.rs:59`). It shows with
no line saying what is open.

Install mode lets the child open the Play Store. That is the same kind of window as "Restrictions paused", which is a
red Attention alarm. The user asked for Play install mode to be checked in this step.

A rarer one goes the same way: "Play can't be paused on this phone" while "Block other apps' screens" is off ("apps
can open the Play Store").

**Fix (needs the user's yes, since it adds to §3.2's list):** make both an orange Attention row, "Play Store open until
14:30" (with M2's `until`) and "Play Store can be opened", targeting `/devices/{id}/advanced#app-screens`. Facts come
from `play_notes`. If the user says no, put one line in the Status card next to "Lock the phone again now" until step
10.

## Low

### L1. "The switch above" points the wrong way
`src/handlers/device_pages.rs:196-198`.

`PlayNote::NotSuspendable` is a warning, and warnings render **above** the App screens group (`device_advanced.html:119`,
`note_warning.html`). So "Play is blocked only while the switch above is on" and "And the switch above is off" refer to
a switch that is below them. The detail also starts with "And".

**Fix:** name the switch instead: "Play is blocked only while Block other apps' screens is on." / "Block other apps'
screens is off, so apps can open the Play Store." Update the `step9` test and the unit test (`:785`).

### L2. Block safe mode lost its one caution
`templates/device_advanced.html:90-97`.

The old footer ended "Only turn this on once this launcher version has been tested on the phone." The ⓘ keeps the ways
back, but not the advice to test first. The switch is turned on by the first Child PIN, and it is the one that takes a
recovery path away. Everything else in the old 109 words is kept or rightly cut.

**Fix:** one more sentence in the ⓘ: "Turn it on once this version of Vibb has been tested on the phone."

### L3. Nothing pins the crash trace's escaping
`templates/device_about.html:72`; mutation 5.

Askama escapes the trace today, so this is not a hole. But `crashes::trace_line_ok` allows `<` and `>` in identifiers,
and real traces contain `<init>` and `<clinit>` frames. The trace is phone-supplied text rendered inside `<pre>`, and
`{{ c.trace|safe }}` passed every test.

**Fix:** in `cleanup.rs`, post a crash with a frame `    at com.x.Foo.<init>(Foo.kt:3)` and an exception line
`<b>Boom`, then assert About has `&lt;init&gt;` and no `<b>`.

### L4. The escapes and boot-cover alarms are now only on a page the parent rarely opens
`src/kiosk_escapes.rs:257-275`; spec §3.8: "Music and escapes have no row".

These were warnings on the daily device page and are now only on Advanced, with no Attention row:
- "Boot cover wasn't shown": Android picked another home screen at boot;
- "Filter has no notification access";
- "Some home screens weren't paused";
- "Vibb isn't the Home app": the fence is only partial, so check device owner.

This follows the spec, but "Vibb isn't the Home app" and "Boot cover wasn't shown" mean other apps' home screens reach
the child.

**Fix:** ask the user whether these two become orange Attention rows (target `/advanced#updates`). Otherwise record
the decision in the spec.

### L5. Test gaps the step's own docs claim to cover
- `tests/device_pages.rs:397-398`: the doc comment says "typed codes paused shows as a warning", but nothing tests the
  `typed-paused` warning on Advanced.
- No test for About on a disconnected phone (M1).
- No test for an end time after midnight (M2).
- `devices_list.rs` checks the Attention anchors on their pages, but not `about#software`. That anchor only exists
  while Software has a row.

**Fix:** add the three tests (M1, M2, typed-paused via `state.enrollment`), and add `("about", "software")` with a
`policy_state` report.

### L6. Merging with `design-31-s11`: three conflicts and one silent clash
`git merge-tree 9a474a2d design-31-s11` reports these conflicts:
- **`partials/app_header.html`:** two answers to the same problem. Step 3 splits the tab bar into
  `partials/tab_bar.html`, and a sub-page includes it alone. Step 11 adds `{% if subpage is not defined %}` around the
  brand bar, and its pages set `{% let subpage = true %}`.
- **`server/CLAUDE.md`** and **`docs/design/31-pwa-redesign.md`:** both appended a section. Keep both.

One clash merges silently: **`partials/head.html`** is at `style.css?v=11` on both branches with different CSS. The
second merge keeps `v=11`, and a browser that cached the first branch's v=11 never fetches the other's CSS.

**Fix at the merge:**
- Keep step 3's `app_header.html`, with no flag.
- Switch step 11's two pages (`tracked_app_detail.html`, `tracked_app_add.html`) to `{% include
  "partials/tab_bar.html" %}`. Step 3's `shell::every_template_with_the_bar_sets_its_tab` then counts them as sub-pages
  and checks their `.nav-back`, and `bar_alone >= 2` can become `>= 4`.
- Bump to `style.css?v=12`.
- Re-run `shell` and `tracked_apps`.

## Nits

- **N1.** `device_pages.rs:354-364`: the Status row reads "Disconnected access removed", with no separator. Use
  "Disconnected · access removed" as §3.10 writes it.
- **N2.** `version_of_code` (`device_pages.rs:270`): a release candidate shows a version that never existed ("0.2.999"
  for 0.3.0-rc.1). That is documented (#6) and the code is behind ⓘ. Still, "0.3.0 (pre-release)" when `code % 1000 ==
  999` would read better, with a small risk of mislabelling a real x.y.999.
- **N3.** `device_pages.rs:552-558`: the crash label is the first stored line split on `:` and `.`. A trace whose
  exception line was dropped by `sanitize` (a message with spaces) starts with a frame, so the label becomes "kt", or
  "Caused by" for a cause. Fall back to the whole first line when it starts with `    at ` or `Caused by:`.
- **N4.** `device_pages.rs:308-317`: `load_device` turns a DB error into 404 "Device not found". It is logged; a 500
  would be more honest.
- **N5.** The Backup-on warning lost "(launcher Settings -> 'Sync now') … report it" (`device_advanced.html:20`). The
  new sentence is fine; "Sync now" was a useful hint.
- **N6.** The `launcher/CLAUDE.md` edit is doc-only, but it starts the `launcher-build` CI job (path filter). It is
  harmless.

## The 15 choices

- **#1 (a phone not set up):** agree. Restrictions and Remove must stay reachable, and the not-enrolled card keeps its
  codes until step 10. I checked the dropped `access.notice` in that card: its only codes come from revoke, which
  can't apply to a phone without a token. A justified drop.
- **#2, #3:** agree. All 10 switches are one form across three groups, which H1 needs. Mutation 6 shows the names are
  pinned.
- **#4 (dropped lines):** agree for "Update fence off" (the switch says it) and the time-lock footer. I checked every
  other line of the five removed cards against §3.9:
  - Backup to Google, unseen boots, the policy problem, battery and "Instant changes" all have a new home with their
    content;
  - two losses: L2's caution, and install mode's visibility on the device page (M4).
- **#5, #7, #8, #9:** agree. #9's install-mode warning is right on Advanced; M4 is about the device page.
- **#6:** agree, see N2.
- **#10 (New Setup Code with no confirm):** agree. Making a code writes only `enrollment_code_hash/kind/expires_at`. It
  neither touches the token nor shows the code anywhere but the no-store code page. That page warns an enrolled phone
  that using the code disconnects it. Typed codes are rate-limited server-wide. §5.6 lists it nowhere, and §5.6's CSRF
  row only needs it to be a POST, which it is.
- **#11 (Remove → `/devices`):** agree. It saves the `/` hop, and the redirect table pins it.
- **#12 (the code page's Back → Advanced › Connection):** agree. That is where New Setup Code lives now; §3.7's
  "`/devices/{id}`" was written before Advanced existed. Update §3.7 when merging.
- **#13 (crash rows by class name):** agree, with N3. The full exception line is the trace's first line in the ⓘ, so
  nothing is lost.
- **#14 (renamed references):** agree. I grepped `src`, `templates`, `static` and the launcher for the removed anchors
  (`#access`, `#hardening`, `#play-kiosk`, `#kiosk-escapes`, `#crashes`). The only one left is `#access` on the
  not-enrolled card, which still exists.
- **#15:** agree.

## Checked and fine

- **The forms keep their POST targets and field names:**
  - `hardening`: 10 names;
  - `kiosk-block`: `block_activity_start`;
  - `kiosk-escapes`: `update_fence`, `notification_auto_cancel`, `boot_cover`;
  - `regenerate-code`, `revoke` (`confirm=yes`) and `delete`.

  The redirect table compares the rendered `action`s, in page order, against the table, so a form added to Advanced
  without a row fails the test.
- **Access:** both GET routes are inside `admin_routes`, under `require_full_auth`, CSRF and the session layer. The
  pages change nothing on GET, and `?notice=` maps only to two fixed texts, so nothing is reflected.
- **404:** `unknown_phones_are_404` covers both pages.
- **The setup code:** only the SHA-256 is stored (`enrollment::new_code`), so Advanced can't show it. The test also
  checks the live-code row has no code.
- **Escaping:** the crash trace, the crash label and `data-crash`, the package and channel names under "Removed since
  last report", `Unsuspendable` package names, `Release::Other`, `PolicyProblem::Other`, the time-lock rule name and
  the device name in titles and confirms all go through Askama's HTML escaping. The templates have no `|safe`.
  `sound_mode`'s test feeds `<b>loud</b>` and checks it is dropped.
- **Typed values:**
  - `ConnectionState::online_since` is the open stream's `since`. That moves to "now" on a reconnect and is not
    reset when one of two streams closes, so "Online since" is the current link.
  - `Zone::when` is right for past times, including DST and the UTC fallback's suffix.
  - The music version derivation matches the repo's scheme (`1_000_000` → "1.0.0"; the local build's code 1 →
    "0.0.1").
  - `boot_witness::unseen` keeps all three advice cases and the 30-day expiry.
  - `admin_policy_effects` keeps the Attention fact (`!is_empty()`), and `sound_mode::state` keeps the DND fact.
- **Information kept:**
  - Backup to Google: Off row, an On alarm, the Attention target;
  - unseen boots: count, when, advice;
  - policy problems: a ≤4-word title, the old sentence behind ⓘ, the "failed to build device policy" hint;
  - battery: On/Off with the fix behind ⓘ;
  - the last access: via, with the outside IPs behind ⓘ. Its time is in Last contact, except M1.
- **Attention targets:** all six moved targets exist on their pages (tested, except `about#software`, L5).
- **a11y:** every switch is a `role="switch"` in a `<label>`; every footer ⓘ has an `aria-label`; the row ⓘ icons are
  `role="img" aria-label="More info"` inside the summary; the sections are `aria-labelledby`; the flash is
  `role="status"`; `aria-current` is set on the tab. The value colours use the `--red-text` and `--orange-text`
  tokens, which have dark values.
- **Browser floor:** no `:has()`, `color-mix()` or nesting in the new CSS. `details { display: inline }` in a footer
  falls back to block, as the CSS comment says.

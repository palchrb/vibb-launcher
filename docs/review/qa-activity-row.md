# Code QA: branch `activity-row` (Activity as one row on the summary, its own page)

Reviewed 2026-11-13, read-only: 0b0408f9, db9cbea3, the merge 485270c4 and the CSS bump 198bdb17, diffed against main
(d81c5b5a). Read: `server/src/handlers/activity.rs`, `templates/device_detail.html`, `templates/device_activity.html`,
`templates/partials/recent.html`, `handlers/locate.rs` (Cancel, Find Phone), `main.rs` (the route and its layers),
`i18n/{en,nb}.json`, `static/style.css`, every `display_time::duration` caller, and the changed tests. No build, no
`cargo test`; `cargo fmt --check` is clean. "Verified" = read in the code and traced; "inferred" = a judgement about how
it reads or renders, not run.

No High findings.

## Medium

### M1. On the page, a Cancel's line lands at the bottom of up to 50 rows (verified)

`device_activity.html:48-50` draws the flash after every row (`{% for r in activity.rows %}` ... then
`<p class="flash" data-flash="activity">`), as the summary's group did. The redirect is
`/devices/{id}/activity?cancel=done#activity` (`locate.rs:2227`), which scrolls to the section's top. On the summary the
group held at most ten rows plus the waiting ones; on the page it holds up to 50 plus the waiting ones.

Scenario: a phone with a busy week (50 rows) and a waiting "Lock again" near the top. The parent taps Cancel on the page;
the browser lands on the first row, and "Cancelled." (or the red "Too late: the phone already has it") is ~50 rows,
some 2,500 px, below - out of sight. A waiting command older than a few rows (they sort by when asked; an eight-day-old
one sorts last) has its own row near the bottom too, so the parent lands far from both the row and its line. `role=status`
speaks it, but a sighted parent sees nothing happen. `assert_lands` passes (it checks only that `id="activity"` exists).

Fix: on the page, put the flash first in the group (before the rows), or give it its own id and land there
(`#activity-flash`, as long as the flash is always drawn when `?cancel=` is set). The summary can keep it where it is
(its group is short).

## Low

### L1. The summary's newest line drops a command's outcome (inferred, UX)

`Summary::of` -> `ActivityRow::newest` (`activity.rs:139-145`) takes a command's `label` and `asked`, not its `word`
(Done / Cancelled / Failed / Waiting). Right after a Cancel from the summary, the waiting row leaves (it is no longer
cancellable) and the newest line reads "Lock again · 13:41" with "Cancelled." under it; a failed Erase Phone reads
"Erase Phone · 13:41", the same as one that went through. A lift likewise drops `word` ("Ended early"). The brief says
"the newest row's text and clock", so this may be the intended minimum; if not, `label · word · asked` for a command
fits the same one line (it is cut with an ellipsis anyway).

### L2. The badge and its spoken words count rows the "7 days" sentence doesn't cover (verified)

`count = activity.rows.len()` (`activity.rs:98`) includes every command that can still be cancelled, at any age
(`rows()` keeps them outside the window and the cap). The spoken badge says "{count} in the last 7 days"
(`device.activity.count.*`). Scenario: a "Lock again" asked 8 days ago that never reached the phone, nothing else: the
summary shows it as its own row with Cancel, then the Activity row with badge "1", spoken "1 in the last 7 days", and
no sub-line (`newest` is `None`) - a row whose only content is the row already shown above it, and a sentence that
isn't true. Smaller: with the waiting rows included, `data-activity-count` is 51 when the badge says "50+" (the test
pins exactly that at `tests/summary.rs:974`, so it is deliberate). Options: count only the window's rows in the badge, or hide the
Activity row when every row is a waiting one; or word it "{count} rows" without the week. The page's footer "The last 7
days, newest first." has the same small untruth for an old waiting command.

### L3. Tests that would not fail if the behaviour broke (verified)

- **The newest line skipping the rows shown above with Cancel** (`activity.rs:118`) is untested in effect: in
  `activity_keeps_a_week_fifty_rows_and_what_can_be_cancelled` the waiting `end_windows` is eight days old, so it is the
  *oldest* row; with the `if c.cancel_id.is_some()` guard removed, `newest` would still be "Event 1" and the assert
  `>Event 1 · ` passes. Add a waiting command asked a minute ago (newest of all) and assert the line shows the next row's
  words, not its label.
- **The "50+" boundary**: the test has 52 events (more) and 4 rows (not more). `capped >= MAX_ROWS` instead of `>`, or
  `.bind(MAX_ROWS as i64 + 1)` reverted on the lifts or locks query, would pass. Seed exactly 50 window rows -> badge
  "50", spoken "50 in the last 7 days"; 51 -> "50+".
- **Escaping of the new sub-line**: askama escapes `{{ n.what }}` (no `askama.toml`, no `|safe`; verified), and the page
  rows' escaping is tested elsewhere, but nothing pins the summary's line. An event title is often the kid's or a
  sender's text (held SMS "«DNB»", app names). One event titled `<b>x</b>` and an assert on `&#60;b&#62;x` in
  `data-newest` would keep it.
- **Unknown phone**: no test GETs `/devices/999/activity` (404 via `device_pages::load_device`, verified by reading).
  The app-lock walk (`app_lock::every_route_is_locked_or_exempt`) covers the new route automatically, and it sits in
  `admin_routes` behind `require_full_auth` (verified, `main.rs:1118`, layers at `:1845-1855`).

### L4. Hairline and inset next to the tile (inferred, visual)

The summary's group is `class="group"`, not `group-icons` (`device_detail.html:404`), so with a waiting row above it the
hairline over the Activity row starts at 16 px, under the tile, where every other tile row's starts at 57 px. And the
waiting rows (no tile) sit left of the Activity row's label. Small; `group-icons` on the group when `waiting` is empty
fixes the common case, or accept it.

### L5. Stale or odd text (verified)

- `templates/partials/recent.html:2` still says the Cancel posts `back` (`find`, `summary`); `activity` is a third value.
- `parent_events.rs:1630-1632` and `:1777`: the duration sweep wrote a literal `1\u{a0}h 20\u{a0}min` into rustdoc
  prose; the comment reads worse than before. Plain spaces in comments are fine.
- Not changed and now inconsistent with the no-break rule: `device.summary.foreign.step_unlock` "Unlock 2 h below"
  (en/nb), `device.advanced.setup.button` "Open for setup for 2 h", `devices.status.last_heard_ago` "{minutes} min ago",
  `security.detail.*` "{minutes} min". Only the button-like ones can wrap visibly; optional.

## Checked and fine

- **Auth**: the route is in `admin_routes` (session + full auth + the app lock via `require_full_auth`; the router walk
  enforces the lock); the server holds one family (no `family_id` anywhere), so "another family's phone" is any id,
  answered 404 for an unknown one; a non-numeric id is `Path<i64>`'s 400 like every other `/devices/{id}/...`. Unread
  state is the reading admin's (`admin.id`).
- **Redirects**: `back=summary` -> `/devices/{id}?cancel=…#activity` (the section is drawn for the flash alone via
  `Summary::shown`); `back=activity` -> `/devices/{id}/activity?cancel=…#activity` (the page always draws the section,
  empty state included). Both tested with `assert_lands`. Find Phone's Show all is now the page unconditionally, which
  always answers 200 - the old empty-summary fallback is no longer needed, and Find Phone no longer reads Activity at
  all (one query set less).
- **Count and more**: each capped table reads `MAX_ROWS + 1`; `capped` counts every non-cancellable row before `retain`
  drops past 50, so any table with 51 or a sum over 50 reads as `more`. Correct.
- **i18n**: the five new keys are in en and nb; `t_count` resolves `.one`/`.other`, both present; nb "Over {count}"
  and en "More than {count}" match the badge's `50+` meaning (51 or more). The page is in the nb-English pass and in
  `i18n.rs`'s template list.
- **No-break space**: every `duration()` caller traced (security log words at read time, pushes and inbox words, status
  lines, quick actions, lifts, schedules). Nothing parses or compares those strings: pushes go as a JSON payload
  (`push.rs:243`), `audit::clip` and `fit_name` cut by `char`, `without_own_clock` strips clocks only, no stored event
  text is compared, no contract file shared with the launcher carries a duration, and `split_whitespace` in the
  nb-English check splits on U+00A0 too. Askama doesn't escape U+00A0, so the updated `contains` asserts match. Grep
  found no test left asserting a plain-space duration that `duration()` produces (the remaining " min" asserts are
  other keys: `security.detail.*`, `lock.choice.*`, `calendar.*`).
- **CSS**: `.row-sub-line` is a `display: block` `.row-sub` inside `.row-label` (`flex: 1 1 auto; min-width: 0`), so the
  ellipsis works and the row can't overflow at 200 % text; the badge and tile are `flex: none`; no `:has()`, no `gap`
  (Safari 14). Dark mode: the badge uses `--grey-bg`/`--grey-text` (dark values at `style.css:175`), the tile is the
  existing fixed `tile-grey`. The badge's visually-hidden span is absolutely positioned, so `.badge > * + *`'s margin
  doesn't widen it.
- **Escaping**: the sub-line and its `title` are askama-escaped (default for `.html`).

# Code QA: design 101, the summary page as mockup A and the app's logo in its band

Reviewed: branch `design-101` at a30e6add4 (28902fa86, fc9008ba2, f0a0ecdd5, a30e6add4 on main f775ec108), against
`docs/design/101-summary-page-a.md` and its design QA `docs/review/qa-101-design.md`. Read-only. Run:
`cargo check --tests` (clean, no warnings), `cargo fmt --check` (clean), `node --test jstest/` (389/389). Not run:
`cargo test`, `cargo clippy`.

**Verdict: 2 High, 2 Medium, 6 Low.** Both Highs are CI failures with one-line fixes (a catalog form and a test
string). The two Mediums are in `lock_now::next_start`: a paused rule that is running now, and a window that starts at
23:59. The second one also makes `the_tiles_say_their_state` fail when it runs at 22:29 Oslo time. The design QA's 7
Medium and 11 Low are all in the build (table at the end), except where this review says otherwise.

---

## High

### H1. `nb.json`'s "one" forms have no `{count}`: `i18n::tests` fails

`server/src/i18n/nb.json:1103` `"device.activity.new.one": "1 ny"` and `:1105` `"device.activity.new_spoken.one": "1 ny
hendelse"`. `i18n::tests::both_catalogs_have_every_key_with_the_same_parameters` (`i18n.rs:384-414`) asserts two
things that fail here: the placeholders are the same as en's (`{count} new`), and a counted pair has `{count}` in both
forms. The design QA's L4 table gave the wrong nb words, and the build copied them. **Fix**: `"{count} ny"` and
`"{count} ny hendelse"`.

### H2. `the_summary_row_counts_what_is_new_to_this_parent` expects "Unread " but the page says "Unread: "

`server/src/tests/summary.rs:1279` asserts `<span class="visually-hidden">Unread </span>`. The Activity row uses
`settings.notifications.unread`, which is `"Unread:"` (`en.json:3161`, the inbox's word). The page therefore renders
`Unread: </span>`, and the test fails. **Fix**: assert `"<span class=\"visually-hidden\">Unread: </span>"`.

---

## Medium

### M1. "Neste" leaves out a rule that is running now and paused: the phone locks again when the pause ends

`server/src/lock_now.rs:193-237`. The design QA's M3 point also covers a window that is already running. On the phone a
lift stops a rule only while the lift lasts (`TimeRules.kt:159`, `liftRecordActive`, `ScreenTime.kt:142`). Example:
Leggetid runs 21:00-07:00, and at 21:10 a parent pauses Leggetid until 22:00. The phone locks again at 22:00.
`next_start` only looks at starts after now (`start <= minute` → skip), so the tiles say "Leggetid på pause til
22:00" and then **"Neste: Leggetid i morgen 21:00"**, or name some other rule. The Pause tile shows the pause, but
the "Neste" line states a next lock that is wrong. **Fix**: for each rule, if `covering_ends(&days, minute)` is
non-empty and `paused_until(rule, pauses, minute)` is `Some(end)` with `covering_ends(&days, end)` non-empty, `end`
is a candidate. Add that vector to `the_next_start` (Leggetid at 21:10, paused until 22:00 → 22:00 today).

### M2. A window that starts at 23:59 is never "Neste", and `the_tiles_say_their_state` fails at 22:29 Oslo time

`lock_now.rs:214` uses `local_at(date, window.start)`. `local_at` follows the end-minute convention: 1439 means the
next midnight (`lock_now.rs:73-76`). A start of 1439 is valid (`{23:59, 23:59}`, `parse_days` takes 0-1439), but it
becomes tomorrow's 00:00. The minute before that is 23:59, which the window's own start covers, so the start is
skipped as "already running". Tomorrow's 1439 is then past the horizon, and the result is `None`.

The new test hits this case. `summary.rs:309` sets `start = (minute + 90) % 1440`, which is 1439 when the test runs at
22:29 local. It then expects "Next: Skole 23:59", gets `&nbsp;`, and fails. That is about 1 run in 1440, at random
in CI. **Fix the function, not the test**: build the start's time straight from the minute (`NaiveTime::from_hms_opt(m /
60, m % 60, 0)`) without `local_at`'s 1439 rule. Add a 23:59 start to `the_next_start`.

---

## Low

- **L1. "Ingen regel nå" when the rules couldn't be read** (`handlers/devices.rs:2383`). `time_rules` is `Err` →
  `unwrap_or(&[])` → `in_force_by_schedule` is false, so the line can still show. Pass `no_rule_now: false` when the
  read failed. The Neste line already disappears in that case.
- **L2. `the_tiles_say_their_state` across local midnight** (`summary.rs:300-320`). `local` is computed once, so a
  run that crosses Oslo midnight between that line and the first render flips `day`/`tomorrow`. The window is about a
  second a day. If you want it closed, recompute `local` and retry once when the test starts within a minute of
  midnight. DST is safe: the test and the server both use naive Oslo wall time.
- **L3. The first row looks pressed every time the sheet opens on touch** (`style.css` `.pic-action:focus-within`).
  The capture `toggle` handler focuses `.pic-file` by script after a tap too, so "Bytt bilde" shows `--row-active`
  at every open. The design QA's L7 asked for a ring that the `:focus:not(:focus-visible)` reset can hide. Either
  focus the first action only when the summary was opened from the keyboard (remember the last `keydown`), or accept
  the look.
- **L4. Stale module doc and a dead path** (`phone_status.rs:1-27`). The doc still describes "at most two" header
  lines and "4 min screen time today". `header` now passes `screen: None`, so `ScreenTime::words(…, true)` and the
  `devices.status.screen_summary.*` keys are never reached at runtime. `every_key_is_used` only greps literals, so it
  doesn't notice. Correct the doc, and drop the path when convenient.
- **L5. The clamped sub-line can hide the clock entirely** (`.row-sub-wrap`). When `n.what` fills two lines, the
  `.nowrap` clock moves to a third line and is clipped. That fits D5 ("never split"), but "never cut" doesn't hold.
  Note it for the 320 px walk.
- **L6. Fix-round scope**: main's 849f79a40 added to design 101 the airplane period's move to Activity ("Flymodus var
  på 18:01-18:02" box goes, an ended period becomes an Activity row). It isn't on the branch and isn't reviewed
  here. Also, `style.css?v=115` and `confirm.js?v=2` must be re-bumped if design 100 lands first with its own bump.

---

## Checked and fine

- **`next_start` vectors**: I worked through all 14 by hand (2026-11-16 is a Monday). Each holds, including: the
  Sunday night window running into Monday, a start exactly 24 h ahead, a 24 h window, ties
  (`(at, kind_rank, sort_order)`), a pause of the rule, a pause of all rules, another rule's pause, and the QA M3
  evening.
- **The 24 h window**: `covering_ends` at the minute before the start skips the start when the window runs every
  day. A copy (id < 0) is skipped in both functions. Unreadable days are skipped by `next_start` and count as in
  force for `in_force_by_schedule` (fail closed).
- **DST**: both functions work in the phone's naive wall time. Pause ends are converted with `with_timezone(&tz)`.
  On a spring-forward night the line says the rule's own time (as the design's Risks say). Neither function nor
  the test can flake on a DST change.
- **The phone's zone vs the server's**: `now_local` and the pauses come from `phone_zone(latest)`. "(phone's time)"
  is added when the zones differ. With no phone zone there is no Neste and no "Ingen regel nå".
- **Freshness (QA M4)**: `time_state::report_stale` is shared with `lock_badge` and behaves as before.
  `report_is_fresh` also requires a `day`. "Ingen regel nå" needs a fresh report, no badge or the screen-time badge,
  no lock turned on now, no running pause (that line wins), and no window covering now after pauses are taken out.
  The test covers yesterday's report, a report without a day, and the schedule disagreeing with the report.
- **Activity's count and read-marking (QA M7, L6)**: `Summary.new` counts only `ActivityRow::Event` rows with
  `unread`, in the same capped week-rows the page lists. `activity::view` builds the rows first (so the dots show
  the state before marking) and then marks exactly those ids for `admin.id`. The other parent's state is untouched
  (tested). `data-unread` on the page uses the bell's own `unread_count` (`Bell::load`), so the app badge stays in
  step. Nothing outside the page's rows is marked. The band (`band::phone`) has no bell, so the hidden span is the
  page's only `[data-unread]`.
- **The status line (QA M2)**: one `Line` through `one_line`. `with_roaming`/`with_airplane` still work on the
  `Vec<Line>` (unit test). The zone pin appears only on the summary (the row has no zone part). Boxed `.part` is
  `inline-block; max-width: 100%`, and the `.bat` icon sits in its battery's box.
- **The picture sheet (QA M1, M6, L1, L8-L10)**: -52 px pinned in `shell.rs` with `.summary-sheet > .summary-head`.
  The header is the sheet's first child, and the template comment tells design 100 to put its card after it. No
  positioned or transformed ancestor: `.sheet`, `.summary-head`, `.avatar-edit` and `details` are unpositioned
  (pinned for the first three), and `.container` has no z-index, transform or contain. The panel and dim therefore sit
  against the body above the tab bar.
- **Sheet keyboard handling (QA M6)**: Escape closes and focuses the summary. `focusout` with a related target
  outside closes it, and with no related target (a tap, the picker opening) it doesn't. `toggle` is captured. The
  backdrop has `cursor: pointer` and no `display`. The Avbryt `[hidden]` is kept with an explicit `display: none`.
  The file row autosaves through the existing hook. The remove form is present only with a photo and lands on
  `#avatar`. The error stays outside the `<details>` with `aria-describedby`.
- **App band (QA M5)**: the glyph size is keyed on `:not(.app-row-logo)` (0,3,1 over `.app-row-tile img`), and the
  logo keeps `.app-row-tile.app-row-logo img`'s fill. The hairline on night is correct. `catalog_logos` is read once,
  and a failure is logged and falls back. The test checks the logo class, glyph, letter and Vibb mark.
- **i18n**: every new key is in both catalogs (apart from H1's forms). The removed keys (`device.activity.count*`,
  `count_more`, `qa.no_limit`) have no users left. `time.tomorrow` and `settings.notifications.unread` exist.
- **Browser floor**: no `:has()`, nesting, `<dialog>`, `inset` or unguarded `:focus-visible`. `:focus-within`,
  `env()`, the prefixed line clamp and `closest`/`matches`/`toggle` are all within iOS 14 / Chrome 80.
  `focus({preventScroll})` is ignored on iOS 14, which does no harm because the panel is in view.
- **Light/dark tokens**: all are defined in both themes (`--surface`, `--sep`, `--row-active`, `--red-text`,
  `--orange-*`).
- **Cache bumps**: `style.css?v=115`, `confirm.js?v=2` (the latter pinned in `shell.rs`).
- **Other changed tests** (`avatars`, `power`, `zones`, `app_logos`, `shell`, the `phone_status` and `time_state`
  units): read against the rendered markup, they match.

## The design QA's items in the build

| Item | State |
|---|---|
| M1 -52 px | Done, pinned |
| M2 parts as boxes | Done (`.part`, `boxed` macro argument) |
| M3 pause delays a start | Done for a start inside a pause; **not** for a window running now (M1 above) |
| M4 schedule check + no-day report | Done (L1 above for a failed read) |
| M5 class-keyed tile rules | Done, tested |
| M6 Escape, focus-out, capture toggle | Done, jstest |
| M7 dots on Activity rows | Done (H2: the test's word) |
| L1 header first, `.summary-sheet > .summary-head` | Done |
| L2 tests listed | Done |
| L3 unused keys removed | Done |
| L4 Activity words | Done, **nb "one" forms wrong** (H1) |
| L5 screen-time lock counts as none | Done |
| L6 app badge `data-unread` | Done |
| L7 focus ring at iOS 14 | Partly (`:focus-within` background, L3 above) |
| L8 no `inset` | Done |
| L9 backdrop `cursor: pointer`, no `display` | Done |
| L10 details marker, summary positioned | Done |
| L11 wording | Design text only; the build uses "Bildet vises bare for foreldrene." (D8 as recommended) |

## Not verified

- `cargo test` and `cargo clippy` weren't run. The Highs and M2's flake are reasoned from the code and catalogs.
- No rendering checks at 320/390 px, light/dark, or with JS off. No iPhone or iOS 14 behaviour: the file row in the
  absolute panel, backdrop taps, VoiceOver on the `<summary aria-label>`. The design's measuring walk (§8) is still
  owed.
- Whether the launcher sends a report at a rule's edge. M4's schedule check makes that moot for "Ingen regel nå".

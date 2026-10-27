# QA L7 "Dagen" (code): the day view, the title rules, the glance's A and C, the kid's own events

Branch `day-view` at ddc8c97e (8 commits on 8e78dc96), against `docs/design/69-dagen.md` (revised), `qa-69-design.md`,
PLAN's "Round L7" entry (which wins) and the mockup `docs/design/69-mockups/dagen.html` (§1 screens 1-3, §1b, §2, §3 A
and C, §5).

**Verdict.** Mergeable after a short fix round. The rules engine is one grammar on both sides and the shared vectors
pin it; the calendar key, the report's sanitizer, the self-grant, the sync apps spared by a time rule, the calm row C
(no notification, not dismissable, rules applied, master switch first) and the ownership re-check on every kid write
are right and tested. One Medium: the PWA's Dagen form loses the parent's pick of the kid's calendar whenever the phone's
report doesn't list it. The rest are test gaps, i18n leftovers and small edges. **0 High, 1 Medium, 6 Low, 9 Nit.**

## Medium

**M1. The kid's calendar pick is shown as "Nowhere" and wiped by the next switch when the report lacks it.**
`handlers/calendar.rs:249-283` (`kid_choices`) marks "phone" selected only when nothing is picked and lists only the
reported writable calendars; a pick that is saved but not in the last report (DAVx5 re-login, a sync turned off, any
report with `readable: false` - e.g. a provider that threw, `CalendarStore.report`) gets no option, so the browser
shows and submits the first one, `off`. The form autosaves on any change (`device_calendar.html:23-35`), and
`save_dagen` (`:450-505`) then writes `calendar_kid_adds = 0` and clears `writable_for_kid`. Reproduced in the browser
pass: pick Familie, a report without it, the select reads "Nowhere (no +)", switching "Dagen on Home" off leaves
`kid_adds: false`, `writable_for_kid: false`. The kid silently loses "+", and the parent's choice is gone when the
calendar comes back. **Fix:** add the saved pick as a selected option ("<name> - not on the phone now", like the rule
editor's `scopes` does at `:825-831`), and a test: report without the pick, page has it selected, an On Home save
keeps it.

## Low

**L1. "Owned by Vibb One" is any Vibb One, so siblings sharing a calendar can edit each other's events.**
`day/DayModel.kt:100-102` / `KidEvent.kt:288-289`: the `vibb-` UID prefix (rightly enough on its own after a DAVx5
round trip) doesn't say which phone made the event. Two kids whose parent picks the same family DAVx5 calendar can each
change and delete the other's events (the row also shows in "mine" green). **Fix:** `vibb-<phone tag>-<uuid>` with a
tag from the phone's own stable id (e.g. the first 8 hex of the device token's hash or `Settings.Secure.ANDROID_ID` for
the app), checked by `ownedByVibb`; keep accepting the bare prefix only together with our `CUSTOM_APP_PACKAGE`.

**L2. New user-facing words hardcoded in English (the 2026-10-26 rule: none from now on).**
`templates/partials/app_display_form.html:48` ("On Home" and its sub-line), `handlers/device_pages.rs:1856`
(" · not on Home"), and the plain-text error bodies of `handlers/calendar.rs:52,75,467,473,542,1123` (the 500 is
visible to a parent). The Apps form is pre-i18n, but these lines are new: route them through `i18n.rs` (the form can
take `lang`) or list them explicitly for round S-i18n in PLAN. The rule editor's placeholders "Gruppe 2-"/"Skole:"
(`device_calendar_rule.html:44,62`) are examples, acceptable.

**L3. An open Dagen ignores "Dagen on Home" off and an unusable policy.** `CalendarStore.dayBlocking` (`:269-272`) and
`EventActivity.render` (`:66-99`) never check `policy.usable && policy.onHome`; with `CalendarPolicy.UNKNOWN` every
calendar counts as shown and no rule applies (`calendars` empty -> `setting()` default). Reachable only while Dagen is
already open (the tile, glance and reminders all check), but it is the one fail-open path: return `DayView.EMPTY` /
finish when Dagen is off. One line each.

**L4. The event page's header puts an all-day event on the day before west of UTC.** `ui/day/EventActivity.kt:47`
takes the local date of `BEGIN`; an all-day instance begins at UTC midnight (§2.4), so in New York it reads the
previous weekday (Norway's +1/+2 is unaffected). Use the UTC date for `allDay` (as `instanceDays` does); `timeLine`'s
multi-day branch (`:116-124`) is timed-only already.

**L5. Two fail-closed paths have no test (mutations survived).** (h) `calendar::policy_calendar` with
`.unwrap_or_default()` on the calendars and rules passes all 61 calendar/policy tests: a DB error would drop every hide
rule. Add one test in the zones' style (rename `device_calendar_rules`, expect the policy's 500). (g) The calendar
POST routes moved out of `require_full_auth` pass all 1406 tests: no test sends a calendar form without a session.
Add one: every calendar POST (and GET) without the cookie is the login redirect (a generic route-auth table would be
better, but that is beyond this round).

**L6. `rules_skipped` for an unknown action or field is untested (mutation c survived).** `CalendarPolicyTest` covers an
unknown `match`, a description replace, an empty pattern and an empty replacement, but not an unknown `action` or
`field`; counting only "known actions" passed. Add rows 15 (`"action": "rename"`) and 16 (`"field": "location"`) to the
skipped list. The shared vectors have a `rename` row but compare only the shown title; they lack an empty-pattern and
an unknown-field row (brief item 1) - add both.

## Nits

1. `kid_adds`' fallbacks disagree: column 1, `calendar.rs:380` false, `handlers/calendar.rs:315` true - one constant.
2. `EventEditActivity.kt:37`: after 23:00 the default start is 00:00 of the same (shown) day, i.e. in the past.
3. `CalendarStore.report` (`:386-392`) reads `Calendars.NAME` a second time (`readCalendars` has it: keep the source in
   `CalendarInfo`); `displayName.take(100)` can split a surrogate pair.
4. The English name differs: the phone says "My day" (`values/strings.xml` `day_title`, "Unlock and open My day"), the
   PWA in English "Dagen" ("Dagen on Home"). Pick one.
5. `sanitize_report` keeps `account_type` uncleaned (control/bidi characters reach the page's sub-line, escaped). Run it
   through `clean(.., 200)` like `name`.
6. Rule editor: the group header and its first row both read "Then" (`device_calendar_rule.html:51,54`); the preview's
   "Rule {n} matches first" counts positions the list doesn't number.
7. nb at 390 px: "Barnet kan legge til avtaler i" wraps to three lines with a lone "i" (the select's width); a shorter
   label ("Barnets avtaler i") or the value under the label.
8. Mockup §1b shows the kid's own event with the calendar's name as its second line even with a place; the build shows
   the place when there is one (`DayListAdapter.kt:96`). Either is fine; say which in the note.
9. A kid's calendar the parent hid ("Shown" off) still takes the kid's new events, which then don't show.

## The deviations, judged

- All contract fields (`writable_for_kid`, `access_level`, `on_home`, `kid_adds`) in step 1's commit: right, one snapshot.
- Kid picker: off / "My calendar (on the phone)" default / reported calendars >= 500 that are neither ICSx5 nor LOCAL:
  right (the phone re-checks `>= CAL_ACCESS_CONTRIBUTOR`, `CalendarStore.kt:414`); see M1 for the missing pick.
- Missing `calendar` = older server (shown, no "+"), unreadable cache = Dagen off, server DB error = 500: right.
- "Min kalender" made at Dagen's first open (re-read first since ddc8c97e; once, `ACCOUNT_TYPE_LOCAL` through the
  sync-adapter URI) and the tile shown before it exists: right per PLAN. Consequence to say in the release notes:
  with `kid_adds` defaulting on, **every managed phone gets the Dagen tile at the update**, even with no calendar.
- Line A only when row 3 keeps 16 dp: a sound, tested reading of §5.1 (yields with 3-button nav, shows with gestures).
- A "." after a digit never ends a sentence: right for the real feed ("09.11. Viktig ..." keeps the important part);
  a sentence ending in a number runs on to the 120 cap and two lines - acceptable.
- Framework DatePicker/TimePicker/AlertDialog for the kid's form: acceptable for step 3 (no escape surface); they
  don't wear design 34's look - a later polish item.

**Mockup fidelity.** As sketched: weekday over date on every page, "← I dag"/"I dag →" by side, none on today; two-line
caps with "›" only on cut rows; "+" bottom right; "Ny avtale" with Hva/Dag/Fra/Varighet/Sted; Endre/Slett only on an own
event's page; the PWA's four groups in order and the editor with its live preview (nit 8; a calendar is two PWA rows,
switch and reminder, not one row with a sub-menu - fine).

## Merge notes (B1 merges first)

- Migrations: B1 holds `0102_app_permissions.sql` and renames the order test to `migrations_73_to_102_in_order`
  (`server/src/tests/music_spotify.rs`). Dagen's merge must make it `migrations_73_to_103_in_order`: range `73..=103`,
  add `(103, "calendar".to_string())`, append "0103 (design 69's Dagen)" to the message and the doc comment
  ("was `migrations_73_to_102_in_order`"). Checked here: B1's 0102 then 0103 apply on an existing-shaped database
  (defaults land on old rows: on_home 1, kid_adds 1, glance_next 0, 'off'; one-writable index and the description
  CHECK hold; delete cascades). Dagen's `server/CLAUDE.md` already names both and "next free 0104"; keep B1's wording
  of 0102 where they overlap.
- `docs/testing/emulator.md`: B1's "6v. Per-app permissions" keeps 6v; Dagen's becomes **6w** (its heading and the
  "§6v" at `launcher/CLAUDE.md:1392`).
- Version: Dagen is at 0.56.0, B1 makes 0.58.0: Dagen takes the next minor when it ships.
- Conflicts likely in `EnforcementPlan.kt`, `device_pages.rs` `render_apps`/`app_display_form.html` (B1's Permissions
  and Dagen's On Home), `launcher/CLAUDE.md`. PLAN.md:266-272's "block account changes" note (design §7) is still open.

## Mutations (each alone, restored; worktree clean)

| | Mutation | Result |
|-|-|-|
| a | a rule-hidden event kept (so it reminds) | caught: DayModelTest, RemindersTest |
| b | the kid's delete skips `kidMayEditBlocking` | caught: KidEventTest (source check) |
| c | `rules_skipped` not counted for an unknown action | **survived** (L6) |
| d | ordinal guard removed | caught: FirstSentenceTest (3) |
| e | `lock_reminders` missing = row (launcher) / column default 'row' (server) | caught: CalendarPolicyTest / `policy_json_keys_snapshot`, calendar page test |
| f | nb catalog missing `calendar.summary.none` | caught: `i18n::both_catalogs_have_every_key...` |
| g | `/devices/{id}/calendar/lock` moved to the public routes | **survived** all 1406 (L5) |
| h | policy `calendar` DB error -> default lists | **survived** (L5) |
| i | "I dag" direction inverted (`todayJump`) | caught: DayPagerTest |
| j | `on_home` sent when true too | caught: 3 `tests::app_display` tests (wire shape) |
| k | key hash over type, NAME, account | caught: CalendarReportTest's vector |
| l | `onHomeListable` true outside a window | caught: AppDisplayTest |
| m | `kidMayEdit` without the kid-calendar check | caught: KidEventTest |
| n, c2 | any calendar offered as the kid's; `rules_skipped` not limited to the policy's ids | caught: `tests::calendar`, sanitizer |

**Runs.** Server: fmt clean, clippy (all targets) no warnings, `cargo test --locked` **1406 passed**, jstest **193 passed**.
Launcher: `assembleDebug testDebugUnitTest -PwarningsAsErrors=true` **1857 passed**. Both contract files byte-identical;
the key vector re-computed with Python's hashlib.
Browser pass (Chromium 390 px, light/dark x JS on/off x nb/en): language follows Accept-Language; the lock switch, a
reminder, a rule's move and save land on #lock/#calendars/#rule-1 in view; no horizontal scroll, no console/CSP error
(but the deliberate 400 of a refused rule, re-rendered as typed at #rule-form); the preview swaps only its fragment,
caret kept (no JS: answers at #preview); summary "Kalender · 3 vises" / "Calendar · 3 shown".

## Emulator list for E5 (with the implementer's)

1. ICSx5/DAVx5 `Calendars.NAME` (feed/collection URL), account type, access level; the key stable over a rename and
   a remove-and-re-add. 2. "Min kalender" made once via the sync-adapter URI (open twice fast, rotate), kept over a reboot.
3. Caps and "›" at font 1.0/1.3, TalkBack node and action. 5. `requestSync` at Dagen's open, at most every 30 min.
4. Glance geometry with A and with C: A yields with 3-button nav on music + 3 rows, shows with gestures; no
   "requestLayout during layout" loop in logcat from `fitNext`; C silent, not swipeable, first, gone at the start.
6. A 2040 and a 1990 day: empty, no crash; midnight and a zone change in Dagen keep the shown date. 7. Sync under a
time rule: not suspended, syncing; hidden when not allowed. 8. `DISALLOW_MODIFY_ACCOUNTS` doesn't stop either account.
9. DAVx5 round trip (Radicale): a kid event uploads with its `vibb-` UID, comes back after a re-download without
   `CUSTOM_APP_PACKAGE`, still editable; a parent's event in the same calendar is not; deleted ones vanish from Dagen.
10. ICSx5 "On Home" off: off Home and the drawer, back in a pause, gone at its end. 11. The kid form's selection
    toolbar (Share/Translate) opens nothing outside the kiosk.

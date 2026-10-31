# QA calendar-colours (code): a colour per calendar in Dagen (variant B)

Branch `calendar-colours` at 4a0cc7e9 (b8502a1c server + contract + the launcher's parse, 49d923b5 the launcher's UI,
7d412ec2 docs, fed50b1e the merge of main a6155d0f, 4a0cc7e9 the auto-pick's triggers), diff `a6155d0f...calendar-colours`
read whole, against mockup `docs/design/69-mockups/dagen.html` §6 (B as approved, v14), PLAN's entry "A colour per
calendar instead of a "Skole:" prefix" (which wins), `docs/notes/server/calendar.md` "A colour per calendar" and design 69.

**Verdict.** Mergeable after a short fix round. The contract is one commit on both sides, the launcher reads an unknown
name as no dot, the dot column keeps every row's two times on one left edge, the key shows only with two calendars or
more, the lock row's dot changes nothing in the row's geometry, every one of the eight is 5.9:1 or better on the night
grounds, and the auto-pick is deterministic and never peach. One Medium: the "hidden doesn't count" rule produces two
shown calendars with one colour as soon as the hidden one is shown again, and nothing resolves it. The rest are the
pool's bookkeeping (gone calendars and the kid's own "Min kalender" each hold a colour they shouldn't), one mockup line
not built, one test gap and a race. **0 High, 1 Medium, 7 Low, 9 Nit.** Cut short by the coordinator: the mutations
f and g were not run (table below); the emulator's part is §6w step 9 (session E6).

## What holds (the brief's seven checks)

1. **The approved design.** `item_day_event.xml:22-31`: the dot is an 8 dp `View` with 6 dp end margin, first in the
   row's horizontal `LinearLayout`, `invisible` by default and only ever `VISIBLE`/`INVISIBLE` in the adapter
   (`DayListAdapter.kt:135`), so the 56 dp time column starts at the same x on every row - all-day rows ("Hele" over
   "dagen"), rows without a colour, two-line titles; the font scale changes nothing horizontally. The key
   (`item_day_legend.xml`) has `paddingStart="14dp"` like the row, so its dots sit under the rows'. The legend's rule is
   `legend.size > 1` over `distinctBy(calendarId)` of all-day + timed (`DayModel.kt:93-97`): 0 and 1 calendar none, a day
   whose events are all one calendar none, the kid's own + one feed two entries - shown. The lock row keeps the 36 dp
   disc and `ScaleType.CENTER`; the 16 dp `bg_glance_dot` is set with `setImageResource` where the glyph was
   (`GlancePanel.kt:1335-1342`), nothing else in the binding moved. Contrast (WCAG, computed): on `kid_ground`
   #0C0C14 sky 7.7, forest 9.3, sun 10.7, berry 8.2, sea 10.3, lavender 7.5, sand 11.5, slate 9.1; on `kid_ground_lift`
   6.6-10.1; on the lock disc (#24F0A884 over the ground = #2C2224) 5.95-9.1. None below 3:1, none below 4.5:1.
2. **The auto-pick.** Order = the form's rows as posted, then the key (`calendar.rs:97-102`, mutation a caught); the
   candidates are shown rows without a colour that aren't the kid's, reported calendars without a row, and each link's
   own calendar by `calendar_feeds::calendar_key(uid)` (`:72-96`); "peach" is not in `COLORS` and the kid's row is
   excluded, `save_dagen` clears the new pick's colour (`handlers/calendar.rs:1015`, mutation d caught), a posted
   `color_<key>` for the kid's row is a no-op (`:1136`, `writable_for_kid = 0`). The intake runs inside the one
   transaction after the report's UPDATE and only with `colored > 0` (`calendar.rs:716-745`); the error path is the
   same `if let Err` log as before. 9+ shown: the ninth breaks out with no colour (`:104`). Concurrency: see L5.
   Hidden-and-shown-again: see M1.
3. **The contract.** b8502a1c holds `PolicyCalendar.color`, the launcher's `CalendarColor`/`CalendarSetting.color`,
   `the_policy_carries_the_rules_and_the_calendars` (the row's inner keys - `policy_json_keys_snapshot` pins the top
   level only, which is why it didn't move) and `PolicyResponseCompatTest` together. `CalendarColor.of` is
   `firstOrNull` by wire name: `null`, a non-string and a newer server's name are no colour (mutations i and j
   caught). Nothing else in the policy shape moved (the diff touches no other DTO).
4. **Dagen unchanged without a colour.** Every pre-existing Dagen test is unmodified in the diff (`DayModelTest`,
   `CalendarPolicyTest`, `RemindersTest`, `PolicyResponseCompatTest` gain cases only); on the server
   `the_switches_and_the_calendars_save_back_to_their_groups` and `the_kids_calendar_survives_a_report_without_it`
   changed their expectations only where the auto-pick now adds rows. QA M1's pick survives a report without it (the
   test still asserts it). `prune_default_rows` keeps rows with a colour and nothing else changed in it (`:1037-1039`);
   `remove_calendar` (`:1163`) still deletes, and a removed row isn't a candidate again (not reported, not a link).
5. **i18n.** Both catalogs gain the same 12 keys; `COLOR_WORDS` spells them as literals for `every_key_used_exists`;
   the template reads only `self.t(..)`; the 400 goes through `bad()` = `i18n::error` (`:69-71`); the launcher adds
   `day_legend_spoken` in `values` and `values-nb`. No literal user-facing word found in the template, handler, adapter
   or panel.
6. **The merge with ICS.** `CalendarView`'s three initializers all carry the three new fields (`:454-498`);
   `migrations_73_to_106_in_order` with `(106, "calendar colors")`; `server/CLAUDE.md` names 0105, 0106 and "next free
   0107"; `remove_and_a_deleted_phone_delete_the_source`'s upsert is legitimate - the add's auto-pick now makes the link
   calendar's row (with its colour), so the test's `INSERT` of the hidden setting would collide.
7. **Save never jumps.** The Colour select is inside the existing calendars form (`device_calendar.html:157`, action
   `.../calendars#calendars`); no new form. The four links forms and Dagen's keep their anchors.

## Medium

**M1. A hidden calendar's colour doesn't count, so showing it again makes two shown calendars share one.**
`calendar.rs:83` (`used` = colours of shown rows only). Scenario: Skolen (sky) and Fotball (forest) shown; the parent
hides Fotball; the kid's calendar is unpicked (`save_dagen`) or a third calendar arrives, the auto-pick finds forest
free and gives it away; the parent shows Fotball again - it "keeps its colour" (`save_calendars` writes `shown` only,
`assign_free_colors` fills missing colours only), and Dagen's day now has two forest dots and a key with two forest
entries. Nothing ever resolves it: the select offers eight names, the parent would have to notice and pick by hand.
The test pins the first half on purpose (`tests/calendar.rs:367-368`, "The hidden feed's forest doesn't count as
used"), so this is the rule, not a slip - the emulator step (§6w step 9, "Hide Fotball ... it keeps its colour")
checks only the lonely case. **Fix (pick one):** (a) count every row's colour as used, hidden too - the simplest, costs
one name per hidden calendar (there are eight, and a hidden calendar is usually hidden for good); or (b) keep the
pool rule but on the 0 -> 1 edge of `shown` in `save_calendars` clear the row's colour when another shown row has it,
so the auto-pick gives it the next free one. Either way a test: hide, give the colour away, show again, two distinct
colours in the policy.

## Low

**L1. The lock row's calendar name after the time isn't built.** Mockup §6's lock row reads "Trening G2016 om 15 min ·
17:30 · Fotball" ("kalendernavnet etter klokkeslettet. Gjelder uansett hvilken variant Dagen får");
`GlancePanel.kt:1327-1328` sets the subtitle to the time alone and `GlanceReminder`/`GlanceCalendarRow` carry no
calendar name. PLAN's approved line says "the lock row's dot" only, so this may be the user's trim - but neither
PLAN, the notes nor launcher/CLAUDE.md says the name was dropped. Build it (one field through `reminderNow`, the
subtitle "17:30 · Fotball" on a day with more than one calendar as the row's TalkBack does) or write the deviation.

**L2. A calendar that left the phone keeps its colour reserved for ever.** A shown row with a colour is never pruned
(`:1039`), counts as used (`calendar.rs:83`) and shows on the page as "Not on the phone now" until the parent presses
Remove. Before this branch a default row vanished with its calendar; now every calendar that was ever auto-coloured
lingers. A parent who re-subscribes a school feed each term (ICSx5 gives a new NAME, so a new key) drains the pool
one colour a term, and the page fills with gone rows. **Fix:** count as used only the colours of rows that are
reported or a link's (the `reported` list is right there), and let `prune_default_rows` drop a row that has only a
colour and is neither reported nor a link's (`reported_keys` + the link keys, both already computed).

**L3. The phone's own "Min kalender" is recognisable - the documented residual is avoidable.** The note and the ⓘ say
the PWA "can't tell it from another LOCAL one" and so offers it a colour and the auto-pick takes one for it. Its key is a
constant on both sides: `CalendarStore.kt:77-78` (`LOCAL_ACCOUNT = "Vibb One"`, `LOCAL_NAME = "vibb-kid-calendar"`,
type `LOCAL`) and the server already derives a link's key the same way (`calendar_feeds::calendar_key`, `:62-63`
`ACCOUNT = "Vibb One"`). `sha256("LOCAL\0Vibb One\0vibb-kid-calendar")[..16]` = `c211fc627cbde3c1`. Today, with the
kid's pick at "phone" (the default), that calendar is a shown rowless reported calendar: it gets a colour at the first
save (taking one of the eight), the page shows "Himmel" for it while the phone draws peach, and the kid's pick select
and the Colour row disagree. **Fix:** a `KID_LOCAL_KEY` constant (pinned against the launcher's two constants by a
test, like the feed key), excluded from the candidates and `used` and given no Colour row (the row says peach / the
kid's own); drop the residual from the note.

**L4. The reminder row's colour has no test (mutation k survived).** `Reminders.kt:56` passes `row.color` into
`GlanceReminder`; dropping it passes `RemindersTest` (its row has no colour and `:53` compares against the default),
`DayModelTest` and `PolicyResponseCompatTest`. The lock row's dot is the one place the colour leaves `DayView`. Add a
coloured calendar to `RemindersTest`'s fixture and assert `GlanceReminder(..., color = SKY)`.

**L5. Two writers for one rowless key: a UNIQUE violation, and the report goes with it.** `assign_free_colors` inserts
with a plain `INSERT` (`calendar.rs:111`) after reading the rows in a deferred transaction; the pool is sqlx's default
(several connections, WAL, 5 s busy timeout). A status report (`store_report`, now one transaction holding the
`calendar_report` UPDATE, `:716-745`) and a form autosave can both decide to insert the same key; the second to commit
hits `UNIQUE(device_id, calendar_key)` (or `SQLITE_BUSY_SNAPSHOT`) - the form answers 500 (a retry works), the intake
logs and **rolls back the report too**, which before this branch was stored unconditionally. Rare (one report per
minutes vs. a parent's tap) but the report is the kid-pick's lifeline (QA M1). **Fix:** `INSERT ... ON
CONFLICT(device_id, calendar_key) DO UPDATE SET color = COALESCE(color, excluded.color)`, and store the report before
the colour step (its own statement) or treat a colour failure as non-fatal inside the intake.

**L6. The ninth calendar: no colour and no word.** `calendar.rs:104` breaks out; the page then shows "Pick for me"
selected for ever (`color_choices`, `current.is_none()`), as if nothing had happened, and Dagen's key lists the
calendar without a dot. Say it: when all eight are in use the first option reads "All eight colours are in use" (both
catalogs), or let the pick wrap and reuse the least-used name.

**L7. A phone set up on the phone alone never gets dots.** The mockup's PWA text: "Første ledige farge velges
automatisk når en kalender dukker opp, så forelderen trenger ikke gjøre noe." The build colours at a save on the
Calendar page and at intake only once a scheme exists (`:740`); a parent whose feeds come through ICSx5 on the phone
and who never saves anything on Device › Calendar sees a key of names without dots. PLAN's status line now states the
built rule, so this may be accepted - have the user confirm it, or colour at intake when a report lists two or more
shown calendars (the moment a key would first show).

## Nits

1. `store_report`'s `new` (`calendar.rs:734-739`) is "reported keys without a row", not "new calendars": a long-known
   rowless calendar re-triggers the pick on every report until it has a row. Harmless (idempotent) - rename it and fix
   the comment ("A new calendar on a phone...").
2. `migrations_73_to_106_in_order`'s doc comment doesn't say "(was `migrations_73_to_105_in_order`)" as every rename
   before it does (`tests/music_spotify.rs`).
3. The dot's `layout_marginTop="6dp"` (`item_day_event.xml:27`) centres it on a 14 sp line at font scale 1.0 (centre
   ~9.5 dp vs the dot's 10 dp); at 1.3 the line's centre is ~12.4 dp, the dot sits ~2.5 dp high. Centre it on
   `day_event_start`'s first line (baseline - ascent/2) if E6 shows it, else leave.
4. Once a colour is picked there is no way back to "no dot" (the select has no "None"); by the design, but the notes
   should say it - and L6's ninth calendar is the only "none" a parent will ever see.
5. The PWA's swatches in light mode: 1.7-2.6:1 on `--surface` white (sand 1.69, sun 1.82). Non-text, so no WCAG
   failure, but the mockup draws them with an inset hairline (`box-shadow: 0 0 0 1px rgba(0,0,0,.08) inset`); add it to
   `.cal-dot`.
6. Berry #E8908C against peach #F0A884 is 1.21:1 - a berry calendar's dot next to the kid's own reads as the same
   colour; forest #7FC28F and sea #7FC9C4 are near in hue. The key names them, so acceptable; consider a cooler red for
   berry before the names are in anyone's muscle memory.
7. `wanting.sort_by_key` (`calendar.rs:97`) allocates `key.to_string()` per comparison; `sort_by` on `(pos, key)` with
   `&str` does the same without it.
8. `colors_after_link_save` also runs after "Fetch now" (`:846`), where no calendar can appear; harmless, one
   transaction per tap.
9. The emulator step 9 says "Hide Fotball in the PWA, then show it again: it keeps its colour" - add M1's case (give the
   colour away in between) once the rule is settled.

## Mutations (each alone, sources restored, `git status` clean before the review was written)

| | Mutation | Result |
|-|-|-|
| a | `assign_free_colors` ignores the form's order (`first` position -> 0) | caught: `a_colour_is_saved_picked_and_refused` (:414) |
| b | hidden calendars' colours count as used | caught: `the_switches_and_the_calendars_save_back_to_their_groups` (:368) - the rule of M1 is pinned on purpose |
| c | `valid_color` accepts any non-empty name | caught: `a_colour_is_saved_picked_and_refused` (:429, the 400) |
| d | `save_dagen` keeps the kid's pick's colour | caught: `a_colour_is_saved_picked_and_refused` (:446) |
| e | `prune_default_rows` without `AND color IS NULL` | caught: both calendar tests (:414, :319) |
| f | intake colours a phone with no scheme (`colored >= 0`) | **not run** (stopped by the coordinator; `a_link_save_and_a_new_report_extend_the_colours`'s last block should catch it) |
| g | `color_row` true for the kid's pick | **not run** (the same test's `!kids.contains("name=\"color_c04e...\"")` should catch it) |
| h (launcher) | `rowColor` without the `vibbLocal` check | caught: `DayModelTest` |
| i | `legendShown` with one calendar | caught: `DayModelTest` |
| j | `CalendarColor.of` reads an unknown name as Sky | caught: `CalendarPolicyTest` |
| k | the policy parse drops `color` | caught: `CalendarPolicyTest`, `DayModelTest`, `PolicyResponseCompatTest` |
| l | `reminderNow` drops the row's colour | **survived** the day package's and the compat tests (L4) |

The launcher mutations ran the `com.kidslauncher.mdm.day.*` and `PolicyResponseCompatTest` filter (109 tests), which
holds every pure test that names `GlanceReminder`; the server mutations ran `tests::calendar` (23 tests).

**Runs** (branch head, this worktree). Server: `cargo fmt --check` clean, `cargo test --locked` **1458 passed**,
`cargo clippy --all-targets --locked` no warnings. Launcher: `assembleDebug testDebugUnitTest -PwarningsAsErrors=true`
BUILD SUCCESSFUL, **1909 tests** (kapt's RELEASE_11 line only). No emulator: §6w step 9 is session E6's. No browser pass.

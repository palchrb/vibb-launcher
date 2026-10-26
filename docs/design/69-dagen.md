# 69 - "Dagen": a read-only day view with the parent's title rules (round L7)

Status: design note, architect 2026-10-26, on `main` at 7d6e8220. Not built. Mockups: `docs/design/69-mockups/dagen.html`
(the day list, the rules page with each calendar's reminder, one rule with a preview, the lock-glance variants A/B/C).
[AOSP] marks something read in AOSP's source for this note; [verify] marks what only the emulator or the phone can
answer (§7 lists the checks).

## 1. Why, and what was decided

The user (2026-10-26): "ville egentlig bare hatt noe som viser events per dag, og at man enkelt kan scrolle fra dag til
dag. knotete for et barn å scrolle seg i høyden etter en sånn time for time rutenett"; and "regexe bort ting fra
abonnerte kalender events og legge til noe? feks for min sønn begynner alle skole ting i ics kalender med en lang tittel
på gruppe 2 skoleåret xyz osv; og ville kanskje heller bare byttet det ut med Skole: og så resten av teksten". On the
mockups: "Enig med deg, mockups ser bra ut! enig i å la B ligge". On reminders: per calendar, "av eller rolig alert er
mest innafor", and "alle notifications må jo selvfølgelig følge alle regler på kalenderen".

Decided:
- **In Vibb One**, not a standalone APK. **Read-only**: no editing, no menus, no event detail page.
- The calendar lives in the parent's tools (the school's ICS feed, Spond, a family Nextcloud). Two F-Droid sync apps
  feed Android's calendar provider: **ICSx5** (`at.bitfire.icsdroid`, subscribed feeds) and **DAVx5**
  (`at.bitfire.davdroid`, CalDAV). Vibb One only reads the provider. **No event ever leaves the phone**.
- **Title rules per phone**, set in the PWA, applied on the phone whenever a title is shown - on Dagen, on the glance
  line and in every reminder.
- The lock glance gets **A** (one line "Neste: Fotball 17:30") and **C** (a calm reminder row before an event, chosen
  per calendar). **B** (a today card) is declined: it pushes the rows under the fold and Dagen is one tap away.

## 2. The kid's view

### 2.1 The Dagen page (`ui/day/DayActivity`, pure `day/DayModel.kt`)

As the mockup's four phone screens, in design 34's look (night ground, Nunito, the one peach accent, tiles `kid_tile`,
no calendar colours - principle 1, low stimulus):
- **Header**: `KidHeader` (back, title, text action) with a second line: the big line is "I dag" / "I morgen" / "I går" /
  the weekday ("Lørdag"), the small line the date ("Mandag 26. oktober"; for today and tomorrow the weekday + date).
  KidHeader gains a `bind(activity, header, title: CharSequence, subtitle: CharSequence?, action, onAction)` overload;
  the existing callers are unchanged. The text action **"I dag"** shows on every day but today and jumps back without
  an animation through the days between.
- **One day as a list** (a `RecyclerView` per page): all-day events first ("Hele dagen" in the time column, dimmed),
  then timed events: the start in peach (14 sp, 800), the end under it (12 sp, dim); title 15 sp 800, at most two lines,
  ellipsized; the location one line, 13 sp dim. Rows are 18 dp tiles, 10 dp apart (the mockup's). **The ongoing event**
  has a 1.5 dp peach inner ring and its second line starts "Pågår nå" (· the location) - not the ring alone, so it
  reads without colour and in TalkBack ("Pågår nå, 14:45 til 15:30, SFO: Leksehjelp").
- **Swipe between days**: `ViewPager2`, horizontal, with a 10 dp peek of the neighbours (`clipToPadding=false`,
  the mockup's fading edges). The range is 7 days back to 60 ahead (a fixed page count, today at index 7). The **five
  dots** are relative, not a page index: the middle one is today, the fourth tomorrow, the fifth any later day, the
  second yesterday, the first any earlier day (the mockup: Monday middle, Tuesday fourth, Saturday fifth). The page
  swipes from inside the screen; a swipe from the very edge stays Android's Back gesture (stock gestures stay).
- **The empty day**: one calm tile "Ingenting i kalenderen." (fixed words; the mockup's "Fri dag" is not a step-1
  feature - no parent text).
- **Nothing is tappable** in the list; a tap does nothing, no ripple. Live: a `ContentObserver` on
  `CalendarContract.CONTENT_URI` reloads the bound pages; a minute tick while resumed moves "Pågår nå";
  `ACTION_DATE_CHANGED`/`TIME_SET`/`TIMEZONE_CHANGED` rebuild the pages (today's index moves at midnight).
- **A reminder in Dagen**: while a reminder (§5.3) is up, the same row shows under Dagen's header (the row's words,
  not tappable). This is the only place a reminder reaches the kid while the phone is unlocked (§5.3).

### 2.2 Where it opens from

- **A Home tile "Dagen"** (`GridTile.Day`, the `calendar_month` glyph that `AppGlyphs` already has, the blue tone, no
  badge), right after the phone book (it is ours, like the phone book; apps follow by label). It shows when the
  launcher can read the provider and at least one calendar is shown (§3.1); no calendar = no tile.
- **The glance's reminder row** (C): its tap takes the keypad like any row (`GlanceTarget.Day`, "Skriv koden for å åpne
  Dagen"), and Dagen opens on that event's day after the code. The line A is **not** a target: 20 dp is under the 48 dp
  rule (design 34 principle 4), and the alarm line beside it is not one either.
- Not on the time-rule screen and not from Kid Settings in this round (a later choice; under "Leggetid" the phone is
  meant to be closed).

### 2.3 Which calendars

Every provider calendar with `SYNC_EVENTS = 1` is shown unless the parent unticks it (§3.1); its `VISIBLE` flag (a
calendar app's checkbox) is ignored. The Google account's own sync is off (`docs/setup/google-account.md` §5).

### 2.4 Reading the provider

- `READ_CALENDAR` in the manifest (never `WRITE_CALENDAR`), self-granted by `QuickControls.selfGrantPermission` from
  `AppEnforcer.apply` while managed and at the store's start (device owner, GRANTED = fixed by policy: the kid can't
  revoke it). Calendar is not a sensor permission, so no "admin granted" notice. A device owner may grant it to another
  package too; not needed - ICSx5 and DAVx5 ask for their own at setup (§6).
- **One query per day** [AOSP]: `Instances.CONTENT_BY_DAY_URI/<julianDay>/<julianDay>` (the provider expands
  recurrences, `EXDATE`s and exception events itself), projection `EVENT_ID, BEGIN, END, ALL_DAY, TITLE,
  EVENT_LOCATION, CALENDAR_ID, STATUS, SELF_ATTENDEE_STATUS`; the calendars from `Calendars.CONTENT_URI`
  (`_ID, ACCOUNT_TYPE, ACCOUNT_NAME, CALENDAR_DISPLAY_NAME, NAME, SYNC_EVENTS`). The Julian day is
  `LocalDate.toEpochDay() + 2440588`. Off the main thread (a single-thread executor), an LRU of 5 days in memory.
- **All-day events are UTC** [AOSP, `CalendarInstancesHelper.performInstanceExpansion`: "allDay events start at
  midnight ... `eventTimezone = Time.TIMEZONE_UTC`"]: an all-day instance is on the UTC dates from `BEGIN` to
  `END - 1 ms`; a timed one on the local dates (`ZoneId.systemDefault()`, the provider's instance zone in its default
  "auto" mode) from `BEGIN` to `END - 1 ms`; a zero-length one on `BEGIN`'s date. The model filters the provider's day
  rows by these dates, so a western zone or a provider quirk can't move an all-day event to the evening before.
- **Credential storage**: the provider lives in CE storage; before the first unlock after a boot there is nothing (no
  tile, no line, no row), never an error.

### 2.5 The day model (pure, `DayModelTest`)

`dayView(rows, calendars, policy, day, zone, now)` -> `DayView(allDay, timed, empty)`:
- out: `STATUS_CANCELED`; `SELF_ATTENDEE_STATUS = DECLINED`; a calendar not shown; an event a rule hides (§3.2).
- a **multi-day timed** event: its first day a timed row "17:00" over "→"; middle days an all-day row; its last day
  a timed row "→" over "14:00", sorted first.
- **two copies** of one event (the school feed and the family calendar) collapse to one row when the shown title,
  `BEGIN`, `END` and location are equal.
- order: all-day by shown title; timed by `BEGIN`, `END`, shown title, `EVENT_ID`.
- now: `BEGIN <= now < END`, timed rows only; several may be ongoing.
- times through `ClockFormat` (design 34 P12), so Dagen follows the phone's one clock format.

## 3. The parent's side: Device › Calendar

`GET /devices/{id}/calendar` (`device_pages::view_calendar`, `device_calendar.html`, `Section::Calendar`), a summary
row "Calendar · 3 shown" (or "Calendar · none on the phone"). The mockup's page 2, in its groups:

### 3.1 Calendars shown (`#calendars`, one form, `POST /devices/{id}/calendar/calendars`)

- The list is the **phone's last report** (§4.2): per calendar a switch "Shown", its name, a sub-line "<app> · <source>
  · Reminder: <mode>" (app from the account type: ICSx5, DAVx5, Phone for `LOCAL`, Google, else the type; source = the
  feed's host or the account name), and the **reminder** select: **Off** (the default; school lessons need none), **The
  calendar's own reminders**, **5 / 10 / 15 / 30 / 60 min before every event**. The footer as the mockup: "A reminder is
  the lock screen's row, without a sound."
- The reminder is chosen **per calendar, here, by the parent** - never per event, never by the kid.
- A calendar the parent saved once but the phone no longer reports shows greyed, "Not on the phone now", with a
  Remove (its settings row goes; rules naming it stay and say so).
- No report yet / `readable: false` / no calendars: the group says what to do instead (§6.1), with the phone's state.
- One form writes exactly the rows it was sent (design 31 §5.6's rule); 303 back to `#calendars`.

### 3.2 Title rules (`#rules`)

- A list in order ("First matching rule wins. The phone applies them when it draws the day; the calendar itself never
  leaves the phone."), each row "Starts with «Gruppe 2 skoleåret 2026/27 – 5B –» · Skolen · show as «Skole:» + the
  rest", with up/down buttons (`POST .../calendar/rules/{rule}/move`, 303 to `#rule-<id>`) and "Add a rule…".
- **The rule editor** (`/devices/{id}/calendar/rules/{rule}` and `/new`; mockup page 3): *When the title* - **Starts
  with** (default) / **Contains** / **Matches a pattern (regex)** - the pattern; *Then* - **Show as** the text, with
  **Keep the rest of the title** (on = `prefix`, off = `replace`), or **Hide the event**; *In* - one calendar of the
  report, or Any calendar; Delete.
- **Preview**: "Paste a real title to see what the phone will show." `POST .../calendar/rules/preview` answers 200
  with the editor re-rendered (not a 303, so not a save in the Security Log), running the server's Rust rule over the
  title; a small script posts it 400 ms after typing stops and swaps the fragment (no script: a Preview button). The
  preview also says "Rule 2 matches this title first" when an earlier rule would win.
- Save: 303 to `/devices/{id}/calendar#rule-<id>` (a save never lands at the page's top). A refused rule (an invalid
  or unsafe pattern, too long, too many rules) re-renders the editor with what was typed and the reason (400, like a
  refused upload) - no 303, so no `settings_log::Refused` is needed; a rule gone since the page loaded is a 303 to
  `#rules` with `?notice=rule_gone` marked `settings_log::refused(...)`.
- Caps: 30 rules a phone; pattern 1-120 characters; text 0-40 (empty only with Keep the rest: "strip the prefix").

### 3.3 The lock screen (`#lock`)

One switch "Next event on the lock screen" (A), on by default: the user's "always on when a calendar is chosen"; the
switch is there because design 25 lets the parent decide what a locked phone shows. `POST .../calendar/lock`.

### 3.4 The rule grammar (one pure implementation per side, pinned by one vector file)

- **The title** is cleaned first: control and bidi characters out, every whitespace run (NBSP, thin space, tab) one
  space, trimmed, cut to 200 code points. Rules see and produce cleaned titles.
- **Case**: Starts with and Contains compare case-insensitively per code point with Unicode's simple lowercase
  (`Character.toLowerCase(int)`; Rust: `char::to_lowercase` when it yields one char, `U+0130 → U+0069` by hand - the
  one unconditional difference). Vectors pin `ÆØÅ`, `İ`, `Σ`.
- **A rule applies** when its calendar is `null` or equals the event's calendar key (§4.1) and its match finds a span
  (Starts with: the prefix; Contains: the first occurrence; regex: the first match, `find`). Rules run in order; the
  **first rule that applies wins**; none = the cleaned title.
- **Actions**: `replace` = the text; `prefix` = the text + " " + the rest, where the rest is the title with the span cut
  out, whitespace collapsed and the separators ` - – — : · | , ; /` trimmed from both ends (an empty text gives the rest
  alone; an empty rest gives the text; both empty gives the cleaned title); `hide` = the event is gone. The mockup's
  cases: «SFO Solbakken avd. 3: Leksehjelp» → «SFO: Leksehjelp»; «Vibb IL G2017 – Trening» → «Fotball: Trening»;
  «Gruppe 2 skoleåret 2026/27 – 5B – Matte, norsk, gym» → «Skole: Matte, norsk, gym».
- **Regex, kept safe and identical**: a whitelisted syntax only - literals, escaped metacharacters, `.`, `\d \D \w \W \s
  \S \b`, `[...]` with ranges and `^` (no nested brackets, no `&&`, `--`, `~~` or `[:class:]`), `( )`, `(?: )`, `|`,
  `* + ? {n} {n,} {n,m}` (n, m <= 50), `^ $`; **no quantified group that contains a quantifier** (`(a+)+`). The server
  also compiles it with the `regex` crate (linear time); the phone compiles it with `CASE_INSENSITIVE |
  UNICODE_CHARACTER_CLASS` (so `\w`, `\d` and case match Rust's Unicode defaults) and runs it over a `CharSequence`
  that counts `charAt` reads and stops at **20 000** a title (an overlapping alternation like `(a|aa)*b` is valid and
  still exponential in Java's engine: the budget makes it "no match" there, and Rust finds no match either). The
  replacement text is literal (no `$1`). A rule the phone can't compile or that ran out of budget doesn't apply and is
  reported (§4.2). Titles are memoised per day load, so a rule runs once per distinct title.
- Implementations: `launcher/.../day/TitleRules.kt` and `server/src/calendar.rs`, each with its own syntax check;
  `server/testdata/calendar_title_rules.json` (`{"titles": [{rules, calendar, title, shown}], "patterns": [{pattern,
  valid, reason?}]}`, `shown: null` = hidden) is **byte-identical** in `launcher/app/src/test/resources/`; the server's
  `calendar::tests::the_title_rules_vectors` and the launcher's `TitleRulesTest` run every row and compare the copies.

## 4. The contract (one commit, both directories)

### 4.1 The policy: `calendar` (always sent, its own key like `lock_glance` and `music` - not under `launcher_ui`, which
is the look of Home)

```json
"calendar": {
  "calendars": [
    {"account_type": "at.bitfire.icsdroid", "account_name": "ICSx⁵", "name": "Skolen", "shown": true, "remind": "off"},
    {"account_type": "at.bitfire.icsdroid", "account_name": "ICSx⁵", "name": "Vibb IL G2017", "shown": true, "remind": 15},
    {"account_type": "bitfire.at.davdroid", "account_name": "mamma@sky.example", "name": "Familie", "shown": true, "remind": "own"},
    {"account_type": "LOCAL", "account_name": "Phone", "name": "Bursdager", "shown": false, "remind": "off"}
  ],
  "rules": [
    {"id": 7, "calendar": {"account_type": "at.bitfire.icsdroid", "account_name": "ICSx⁵", "name": "Skolen"},
     "match": "starts", "pattern": "Gruppe 2 skoleåret 2026/27 – 5B –", "action": "prefix", "text": "Skole:"},
    {"id": 9, "calendar": null, "match": "contains", "pattern": "faktura", "action": "hide", "text": ""}
  ],
  "glance_next": true
}
```

- The account types and names above are examples [verify: ICSx5's and DAVx5's on the emulator].
- **The calendar key** is `(account_type, account_name, name)` with `name` = `CALENDAR_DISPLAY_NAME`, not `_ID`: it
  survives a feed removed and added again. Two calendars with the same key are one for the parent (shown, reminder,
  rules) - stated on the page if the report has such a pair.
- A calendar not in `calendars` is shown with reminders off. `remind`: `"off"`, `"own"` or one of 5/10/15/30/60;
  anything else is off. An unknown `match`/`action` drops that rule. The launcher reads the object field by field
  (raw JSON, as `lockGlancePolicy`); a missing key = everything shown, no rules, the line on, reminders off.
- Pinned by `policy_json_keys_snapshot` and `PolicyResponseCompatTest`. Capability `calendar_v1`; the page says "This
  phone's Vibb One doesn't know Dagen yet" without it.

### 4.2 The status report: `calendar`

```json
"calendar": {"readable": true,
  "calendars": [{"account_type": "at.bitfire.icsdroid", "account_name": "ICSx⁵", "name": "Skolen", "source": "skole.kommune.no"}],
  "rules_skipped": [], "rules_slow": []}
```

- `readable` false = no permission or the provider threw (then `calendars: []`). At most 50 calendars, names cut to
  100 characters. `source` is only the **host** of a `Calendars.NAME` that parses as an `http(s)`/`webcal` URL (a feed
  URL often carries a private token: never the path or query), else null [verify: where ICSx5 keeps the URL].
- `rules_skipped`: rule ids the phone couldn't compile; `rules_slow`: ids that hit the budget since the last report.
  The page marks those rules "The phone couldn't use this rule".
- No event, title, count or time is ever in it. The calendar names do go to the server; they are the parent's own.
- Contract file `server/testdata/calendar_status_report.json`, byte-identical in `launcher/app/src/test/resources/`:
  the launcher's `CalendarReportTest` encodes exactly it, the server's `calendar::a_launcher_report_lists_the_calendars`
  posts it, and both compare the copies. `calendar_title_rules.json` and this file join `launcher.yml`'s and
  `server-ci.yml`'s path lists and the root `CLAUDE.md`'s contract paragraph.

### 4.3 Server storage

Migration `NNNN_calendar.sql` (the next free number): `device_calendars` (device_id, account_type, account_name, name, shown, remind; PK the
four; `remind` CHECK `off|own|5|10|15|30|60`), `device_calendar_rules` (id, device_id, sort_order, the key's three
columns nullable, match, pattern, action, text; CHECKs), `device_policy.calendar_glance_next` (default 1), and
`devices.calendar_report` (the last report's JSON, replaced on each report). All cascade with the device.

### 4.4 Also needed: "Not on Home" for a background app

ICSx5 and DAVx5 must be **allowed** (a hidden app is not installed for the user - no sync adapter runs; a suspended
one may not sync [verify]), but neither belongs on Home: the kid could delete the school feed. New: a per-phone switch
"On Home" (default on) on the app's row in Device › Apps, sent as `launcher_ui.background_apps: [package]` (always a
list, pinned by the snapshot). `AppFilter` leaves those out of Home and the drawer **except during a pause**, when the
parent sets them up. The kiosk's lock-task list keeps them (allowed apps), so a sync error notification still opens
them. Design 68 changes the same rows: build after it, or in one branch with it.

## 5. The glance

### 5.1 A: the line "Neste: Fotball 17:30"

- `lock_glance_next` under the alarm line (or the date without one), the alarm line's style (13 sp, Nunito Bold,
  `kid_core_dim`, 2 dp margin), at the date's start with the clock profile (`placeProfile`). Shown when `glance_next`
  is on and today has a timed event that starts after now; its words `"Neste: %1$s %2$s"` with the shown title (rules
  applied; one line, ellipsized) and the start in `ClockFormat`. Ongoing events and all-day events never; nothing when
  today has no more. While C's row is up for event E, the line names the next event after E (or goes).
- **Geometry** (`LockGlanceLayoutTest`'s model; the line is 20.2 dp like the alarm line): the user's case (the profile
  clock, the alarm line, music + 2 rows) goes from 370.6 to **390.8 dp** and still fits the 401 / 425 dp scroller;
  music + 3 compact rows 392 → 412.2 (scrolls 11.2 dp at 473, none at 497); r13's frame D with the alarm 439 → 459.2
  (scrolls 58.2 / 34.2 instead of 38 / 14). B would have added about 127 dp for three events (12 + 18.2 + 4 + 3 × 21 +
  12 + its 18 dp margin), the user's case 497.6 dp - 96.6 dp under the fold at 473: why it is declined.

### 5.2 C: the reminder row

- A `GlanceCalendarRow` in `badges/GlanceRows.kt` (a new `GlanceRow` kind, 56 dp like every row, first in the list:
  it is the one row with a deadline), the calendar glyph in the row's badge place, line 1 `"%1$s om %2$d min"` (the
  minutes counting down; "%1$s nå" in the last minute), line 2 the start time only. It counts toward `glanceLayout`'s
  rows like any row (music + 1 row full becomes compact while a reminder is up). Not dismissable, out of
  `swipeControls`; a tap = `GlanceTarget.Day` (§2.2). TalkBack: "Påminnelse: Fotball: Trening, om 15 minutter, 17:30".
- **Its form is always this calm row**: no notification, no heads-up, no sound, no vibration, no full-screen intent,
  no screen wake (`noticeCame()` is not called). While the phone is unlocked the reminder is only in Dagen's header if
  Dagen is open (§2.1); nothing in the shade (§8 question 3).
- **The instant** - the calendar's mode decides, never both: `off` none; a number N: `BEGIN - N min`; `own`: the
  event's own `CalendarContract.Reminders` rows of method `DEFAULT`, `ALERT` or `ALARM` (an ICS `VALARM`, a CalDAV
  alarm, ICSx5's per-feed default alarm - all land there), the earliest of those **at most 120 min** before the start
  (a day-before alarm would hang a row all day: ignored on the glance, Dagen shows the day); none = no row. The own
  mode picks only the instant; the form stays the row.
- The row is up from the instant until `BEGIN`; timed events of today only (all-day events get none).
- **Every reminder follows the calendar's rules**: the shown (rewritten) title; an event a rule hides gives no
  reminder; a calendar switched off gives none; a cancelled or declined event gives none.
- **Mechanics**: no alarm is needed - the row exists only to be seen, and the glance is only seen with the screen on.
  `GlancePanel` asks the calendar store for today's reminders at `resumeNow` and every render, and while the panel is
  started posts one main-thread callback at the next boundary (an instant, a start, the next minute while a row
  counts down; `nextReminderBoundary`, pure). `USE_EXACT_ALARM` and `TimeRuleAlarm` are untouched. The store reads
  `Reminders` for today's event ids once per day load (`Reminders.EVENT_ID IN (...)`).

## 6. Edge cases

1. **No sync app or calendar yet**: no Home tile; the PWA's Calendar page says: "Add ICSx5 (feeds) or DAVx5 (CalDAV)
   from the catalog's F-Droid source, tick it for this phone, switch On Home off, then in a pause on the phone open it
   and add the feed or log in." Tip on the page: send the `webcal://` link to the kid in Element X and tap it in the
   pause - ICSx5 opens its add dialog, no typing of a long URL. DAVx5: an app password, read-only shares
   (`PLAN.md` "Calendar", 2026-10-06); sync every 1-4 h for battery.
2. **The setup under restrictions**: the sync apps' own permission dialogs and DAVx5's battery prompt run in a pause.
   Both add an Android account with `addAccountExplicitly`, which AOSP's `AccountManagerService` gates only by the
   caller owning the type, not by `DISALLOW_MODIFY_ACCOUNTS` [AOSP; verify] - PLAN's 2026-10-06 note says the switch
   blocks it; if the emulator agrees, the page says "switch Block account changes off in Advanced › Restrictions for
   the setup".
3. **A rarely-run sync app**: an app the kid never opens drops to a low standby bucket and syncs seldom [verify].
   Dagen's open asks `ContentResolver.requestSync(account, CalendarContract.AUTHORITY, manual + expedited)` for each
   shown calendar's account, at most once per 30 min [verify that another app may ask for these accounts].
4. **Permission revoked**: can't happen while managed (fixed grant). Unmanaged or before provisioning: no grant,
   `readable: false`, no tile; the page says why.
5. **Recurring exceptions**: the provider's expansion handles `EXDATE`, moved and cancelled instances; the model drops
   `STATUS_CANCELED` rows (an exception may arrive as a cancelled row).
6. **DST**: timed instants are formatted in the zone; a 23/25-hour day is one `LocalDate`; an RRULE at 08:15 stays
   08:15 local across the change (the provider expands in the event's zone). Test rows on 2027-03-28 and 2027-10-31.
7. **Multi-day events**: §2.5; an all-day event over three days is on all three, at the top. **Two calendars with one
   name** in one account: one key (§4.1).
8. **An invalid regex**: refused in the editor with the reason; the phone ignores a rule it can't compile and reports
   it (§4.2) - the same check on both sides makes that a bug, not a normal path.
9. **Etar (or another calendar app) stays**: it would post its own reminder notifications with a sound for the same
    events, against "rolig alert" - §8 question 2.

## 7. Steps, tests, emulator

**Step 1** (one branch `day-view`; the contract commit first, both dirs: §4.1, §4.2, the two contract files, the CI
paths): **server S** - migration, `calendar.rs` (rules, validation, policy, report), the page and editor, `Section`,
the summary row, `background_apps` on Device › Apps; **launcher S-M** - the permission, `calendar/CalendarStore`
(queries, observer, LRU, reports), `DayModel`, `TitleRules`, `DayActivity` + layouts + strings (nb/en), the Home tile,
`background_apps` in `AppFilter`. **Step 2** (same branch or the next, launcher S): A and C on the glance, the Dagen
header row. The policy carries `glance_next` and `remind` from step 1; the launcher reads them in step 2.

Tests (lean):
- `DayModelTest`: rows → `DayView` - all-day in UTC at +1/+2 and at -5, a timed event ending at midnight, multi-day
  (first/middle/last), zero-length, cancelled, declined, a hidden calendar, a hidden rule, the duplicate collapse, the
  order, "now" at `BEGIN` and at `END`, the DST days.
- `TitleRulesTest` / `calendar::tests::the_title_rules_vectors`: every vector row; the copies identical; the budget row.
- `CalendarReportTest` / `a_launcher_report_lists_the_calendars`; `policy_json_keys_snapshot` + `PolicyResponseCompatTest`
  (`calendar`, `launcher_ui.background_apps`); `CalendarPolicyTest` (field by field, the defaults, unknown values).
- Glance: `LockGlanceLayoutTest` gains the line's 20.2 dp and the §5.1 table; `GlanceRowsTest` the reminder row first,
  its window per mode (`off`, N, `own` with 1440/60/15 → 60, none), **the rules on it** (rewritten title, a hidden
  event no row, a calendar off no row), no row for all-day; `nextReminderBoundary`; a source check that the row path
  posts no notification and calls no `noticeCame()`.
- Server pages: the forms' 303 anchors, a refused pattern re-rendered with what was typed, the preview's 200 (no
  `settings_saved` row), `rule_gone` marked `Refused`, the caps, move up/down.
- `HomeModelTest`: the Day tile after the phone book, gone without a shown calendar; `AppFilter`'s background apps
  out, and in during a pause.

Emulator (`jelly36`, `docs/testing/emulator.md` gets a §6 entry): seed the provider straight away (quote the whole
command for the device shell; if the shell uid lacks the calendar permission on the image, use the feed path below):
```
adb shell "content insert --uri 'content://com.android.calendar/calendars?caller_is_syncadapter=true&account_name=dagen&account_type=LOCAL' \
  --bind account_name:s:dagen --bind account_type:s:LOCAL --bind name:s:Skolen --bind calendar_displayName:s:Skolen \
  --bind ownerAccount:s:dagen --bind calendar_access_level:i:700 --bind sync_events:i:1 --bind visible:i:1"
adb shell content query --uri content://com.android.calendar/calendars --projection _id:account_type:calendar_displayName:name
adb shell "content insert --uri content://com.android.calendar/events --bind calendar_id:i:<id> \
  --bind title:s:'Gruppe 2 skoleåret 2026/27 – 5B – Matte' --bind eventLocation:s:'Rom 204' \
  --bind dtstart:l:<epoch ms> --bind dtend:l:<epoch ms> --bind eventTimezone:s:Europe/Oslo"
# weekly: --bind rrule:s:'FREQ=WEEKLY;BYDAY=MO' --bind duration:s:PT6H15M instead of dtend
# all-day: --bind allDay:i:1 --bind eventTimezone:s:UTC, dtstart/dtend at UTC midnights
```
Then the real path: ICSx5 from the catalog's F-Droid source, a feed served from the host
(`python3 -m http.server 8088` in a scratch dir, `http://10.0.2.2:8088/dagen.ics`) with a weekly series with an
`EXDATE` and a moved instance, an all-day event, a weekend camp, an event on 2027-03-28 and a `VALARM` of 15 min.
Check: the swipe, the peek and the dots, "I dag", "Pågår nå", the empty day; a rule from the PWA changes the title on
the next sync; A and C on the glance (C silent, gone at the start, tap → keypad → Dagen); no shade notification;
DAVx5 against a CalDAV test server; and the [verify] items: `DISALLOW_MODIFY_ACCOUNTS` against both apps' account,
where ICSx5 keeps the URL, sync while suspended, `requestSync` for their accounts.

## 8. Open questions for the user (with defaults)

1. **Where Dagen opens from**: default a Home tile right after the phone book, plus the reminder row's tap through the
   keypad. Alternatives: the tile last among ours (before the flashlight), or no tile and only from the lock.
2. **Etar stays or goes**: default **goes** off the allowlist once Dagen is in - Dagen covers reading, the parent edits
   in the source calendars, and Etar's own reminders would ring for the same events (and need the exact-alarm grant,
   design 68). Keeping it means its notifications blocked or its reminders off.
3. **A reminder while the phone is unlocked**: default **none** (the glance row, and the row in Dagen's header). The
   option: a silent shade notification with the same words (no sound, no vibration, no heads-up), per phone.

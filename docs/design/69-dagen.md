# 69 - "Dagen": a day view with the parent's title rules (round L7)

Revised 2026-10-26 after `docs/review/qa-69-design.md` (every finding applied; regex rules moved to a later step).

Status: design note, architect 2026-10-26, on `main` at 7d6e8220. Not built; builds after design 68 (round B1, which
changes the same Device › Apps rows). Mockups: `docs/design/69-mockups/dagen.html` (§1 the day list with the "›" on cut
rows, §2 the rules page, §3 the lock-glance variants A/B/C, §5 the user's real itslearning feed and the event's own
page). Its §1-§4 strings are invented examples; §5 is the real feed; its §4 caption "A always on" is superseded by §3.1.
[AOSP] marks something read in AOSP's source (cited by file:line, `main`); [verify] marks what only the emulator or the
phone can answer (§7 lists the checks).

## 1. Why, and what was decided

The user (2026-10-26): "ville egentlig bare hatt noe som viser events per dag, og at man enkelt kan scrolle fra dag til
dag. knotete for et barn å scrolle seg i høyden etter en sånn time for time rutenett"; and "regexe bort ting fra
abonnerte kalender events og legge til noe ... bytte det ut med Skole: og så resten av teksten". On the mockups: "Enig
med deg, mockups ser bra ut! enig i å la B ligge". On reminders: "av eller rolig alert er mest innafor", "alle
notifications må jo selvfølgelig følge alle regler på kalenderen", and "man bør få velge om det skal komme den rolige
reminderen eller absolutt ingenting".

Decided:
- **In Vibb One**, not a standalone APK. **Read-only in steps 1-2**: no editing, no menus; a tap on a row opens the
  event's own page, read-only (§2.3). Step 3 (the kid's own events) is later; step 1's contract leaves room (§4.5).
- The calendar lives in the parent's tools (the school's ICS feed, Spond, a family Nextcloud). Two F-Droid sync apps
  feed Android's calendar provider: **ICSx5** (`at.bitfire.icsdroid`, subscribed feeds) and **DAVx5**
  (`at.bitfire.davdroid`, CalDAV). Vibb One only reads the provider. **No event, title or description ever leaves the
  phone.**
- **Title rules per phone**, set in the PWA, applied on the phone wherever a title is shown: Dagen, the glance line,
  every reminder. Step 1: "starts with" and "contains" only (§3.4); regex is a later step (§3.5).
- **Nothing on the lock screen until the parent chooses** (§3.1): the "Next" line (A) and the reminder row (C) are two
  per-phone switches (§3.1), both off after setup, and every calendar's reminder is off. **B** (a today card) is declined.

## 2. The kid's view

### 2.1 The Dagen page (`ui/day/DayActivity`, pure `day/DayModel.kt`)

In design 34's look (night ground, Nunito, the one peach accent, `kid_tile`s, no calendar colours - principle 1):
- **Header**: `KidHeader` (back, title, text action) with a second line. The **heading always carries the weekday and the
  date**, today too: the big line the weekday ("Mandag"), the small line the date ("26. oktober") - never the word "I dag"
  (the mockup's "I dag" headings are superseded). `KidHeader` gains a `bind(activity, header, title: CharSequence,
  subtitle: CharSequence?, action, onAction)` overload; the existing callers are unchanged. The text action is the
  **jump back** and must never read like a heading saying "this is today": it shows only when the page is not today, as
  **"← I dag"** when the shown day is after today and **"I dag →"** when before (the arrow points towards today), and jumps
  back without an animation. The label is pure, `todayLabel(offsetDays) -> String?` in `day/DayPager.kt` (offset =
  shown day − today: > 0 "← I dag", < 0 "I dag →", 0 null), tested in `DayPagerTest`.
- **One day as a list** (a `RecyclerView` per page): all-day events first ("Hele dagen", dimmed), then timed events: the
  start in peach (14 sp, 800), the end under it (12 sp, dim); the title 15 sp 800; the second line 13 sp dim. Rows are
  18 dp tiles, 10 dp apart.
- **Text caps** (the user, 2026-10-26): the title at most **2 lines**, the second line at most **2 lines**, both
  ellipsised ("…"). The same line counts at font scale 1.3: the rows grow in height, not in words. The second line is the
  location when there is one, else the description's **first sentence** (§2.3); an ongoing event starts it with "Pågår nå"
  (· the location). Everything cut is read in full on the event's own page.
- **The "›"**: a row whose title or second line was cut shows a small "›" at its right edge in the dimmed ink (the
  mockup's `.ev.more`); a row whose text fits shows none. Every row is still tappable. The row model carries
  `cutByRule` (pure: the first-sentence rule or its 120-character clamp dropped text, §2.3); the holder sets
  `truncated = cutByRule || Layout.getEllipsisCount(lastLine) > 0` on either TextView after layout (an
  `OnLayoutChangeListener` on the row) and shows the chevron by it. The pure part is tested (`DayRowTextTest`); the layout
  part is a one-line `||`, checked on the emulator at font 1.0 and 1.3 (§7).
- **The ongoing event** has a 1.5 dp peach inner ring and "Pågår nå" in its second line - not the ring alone, so it
  reads without colour and in TalkBack ("Pågår nå, 14:45 til 15:30, SFO: Leksehjelp"). A row's TalkBack action label is
  "Åpne hendelsen".
- **The pager is endless both ways**, one day per swipe, no fixed range and no dots (the mockup's dots are gone). A
  `ViewPager2` (horizontal; a new direct dependency: `androidx.viewpager2` in `libs.versions.toml`, its checksums via the
  root `CLAUDE.md`'s `--write-verification-metadata` step) over an adapter with `itemCount = Int.MAX_VALUE` whose
  position maps to a day by an offset: `day = anchor + (position - CENTER)`, `CENTER = Int.MAX_VALUE / 2`, `anchor` =
  today when the page was built. A page binds lazily: it asks `CalendarStore.day(day)` (single-thread executor, an LRU
  of 9 days), shows nothing until the answer lands (no empty-day flash), and the adapter prefetches the two neighbours
  (`offscreenPageLimit = 1`, a 10 dp peek with `clipToPadding=false`; as built: the pager's list padded 13 dp, each
page 3 dp, so the card starts 16 dp in and 10 dp of the neighbour's card shows, and Dagen's root doesn't clip to its
padding - E5's L1). A day the provider can't answer (an
  out-of-range year) is an empty day [verify: 2040]. `ACTION_DATE_CHANGED`/`TIME_SET`/`TIMEZONE_CHANGED` move `anchor`
  and keep the page's date: `notifyDataSetChanged()` + `setCurrentItem(CENTER + (date - newAnchor), false)`. The pager is
  pure in `day/DayPager.kt` (position <-> day, "today?"), tested. A swipe from the very edge stays Android's Back gesture.
- **The empty day**: one calm tile "Ingenting i kalenderen." (fixed words; no parent text).
- **Live**: a `ContentObserver` on `CalendarContract.CONTENT_URI`, **debounced to one reload a second** (the provider
  notifies per sync batch, `CalendarProvider2.java:846` [AOSP]), invalidates the LRU and reloads the bound pages (the
  visible day and its neighbours). A minute tick while resumed moves "Pågår nå".
- **A reminder in Dagen**: while a reminder (§5.2) is up the same row shows under Dagen's header (the row's words,
  not tappable) - the only place a reminder reaches the kid while the phone is unlocked.

### 2.2 Where it opens from

- **A Home tile "Dagen"** (`GridTile.Day`, the `calendar_month` glyph that `AppGlyphs` has, the blue tone, no badge),
  right after the phone book (it is ours; apps follow by label). It shows when the launcher can read the provider and at
  least one calendar is shown; no calendar = no tile.
- **The glance's reminder row** (C): its tap takes the keypad like any row (`GlanceTarget.Day`, "Skriv koden for å åpne
  Dagen") and Dagen opens on that event's day. Under a time rule that leaves the app unusable the target is `null`
  (`GlanceStageEvent.Row(null)`, `LockGlance.kt:101`), like every row. The line A is **not** a target (20 dp is under the
  48 dp rule, design 34 principle 4).

### 2.3 The event's own page, and the second line

- **A tap** on a row opens `ui/day/EventActivity` (not exported; `KidHeader` back): the shown title (rules applied), the
  time ("13:00 - 14:30"; a date line when not today; "Hele dagen"), the location, the calendar's display name, and the
  **whole description**, read-only: a plain `TextView`, no `autoLink`, no `LinkMovementMethod`, links not tappable
  (kiosk). It reads the event again by id from the provider; a vanished event says so and goes back.
- **The description** is cleaned like a title (control and bidi characters out, whitespace runs one space, NFC, §3.4) and
  cut to 2 000 code points. It is shown as the feed holds it; only the title is rewritten by rules.
- **The first-sentence rule**, pure `firstSentence(description) -> Cut(text, cut)` in `day/RowText.kt`: the cleaned
  description up to and including the first ". ", "! " or "? " (or its end), then at most ~120 characters (cut at the last
  space before 120, hard at 120 if none); `cut` = text was dropped. A "." does not end a sentence after a run of digits
  (an ordinal, "5. trinn") or after `ca kl nr osv bl.a f.eks` - the real feed has "Jentene på 5. trinn, hentes med buss"
  and "ca. 13.15"; "08.56" has no space after its dot. The second line = the location when non-blank, else this text.
  Vectors in `FirstSentenceTest` from the real feed (§7).
- The description never reaches the glance or the server: A and C stay time + title.

### 2.4 Which calendars, and reading the provider

- Every provider calendar with `SYNC_EVENTS = 1` is shown unless the parent unticks it (§3.1); its `VISIBLE` flag is
  ignored. The Google account's own sync is off (`docs/setup/google-account.md` §5).
- `READ_CALENDAR` in the manifest (never `WRITE_CALENDAR` in steps 1-2), self-granted by
  `QuickControls.selfGrantPermission` (`QuickControls.kt:141`) from `AppEnforcer.apply` while managed and at the store's
  start (device owner; the kid can't revoke it). Calendar is not a sensor permission, so no "admin granted" notice. ICSx5
  and DAVx5 ask for their own at setup (§6).
- **One query per day** [AOSP]: `Instances.CONTENT_BY_DAY_URI/<julianDay>/<julianDay>`, projection `EVENT_ID, BEGIN, END,
  ALL_DAY, TITLE, EVENT_LOCATION, DESCRIPTION, CALENDAR_ID, STATUS, SELF_ATTENDEE_STATUS`; the calendars from
  `Calendars.CONTENT_URI` (`_ID, ACCOUNT_TYPE, ACCOUNT_NAME, CALENDAR_DISPLAY_NAME, NAME, SYNC_EVENTS,
  CALENDAR_ACCESS_LEVEL`). Julian day = `LocalDate.toEpochDay() + 2440588`. Off the main thread.
- **All-day events are UTC** [AOSP, `CalendarInstancesHelper.java:212-216`, `:706`, `:892-908`]: an all-day instance is
  on the UTC dates from `BEGIN` to `END - 1 ms`; a timed one on the local dates (`ZoneId.systemDefault()`) from `BEGIN` to
  `END - 1 ms`; a zero-length one on `BEGIN`'s date. The model filters the provider's rows by these dates, so a western
  zone can't move an all-day event to the evening before. Cancelled exceptions never become instances (`:510`).
- **Credential storage**: the provider lives in CE storage; before the first unlock after a boot there is nothing (no
  tile, no line, no row), never an error.

### 2.5 The day model (pure, `DayModelTest`)

`dayView(rows, calendars, policy, day, zone, now)` -> `DayView(allDay, timed, empty)`:
- out: `STATUS_CANCELED`; `SELF_ATTENDEE_STATUS = DECLINED`; a calendar not shown; an event a rule hides (§3.4, on the
  title or the description).
- a **multi-day timed** event: its first day a timed row "17:00" over "→"; middle days an all-day row; its last day a
  timed row "→" over "14:00", sorted first.
- **two copies** of one event collapse to one row when the shown title, `BEGIN`, `END` and location are equal.
- order: all-day by shown title; timed by `BEGIN`, `END`, shown title, `EVENT_ID`.
- now: `BEGIN <= now < END`, timed rows only; several may be ongoing. Times through `ClockFormat` (design 34 P12).

## 3. The parent's side: Device › Calendar

`GET /devices/{id}/calendar` (`device_pages::view_calendar`, `device_calendar.html`, `Section::Calendar` - the table in
`server/src/tests/mod.rs:697`), a summary row "Calendar · 3 shown" (or "none on the phone"). In its groups, top to bottom:

### 3.1 On the lock screen (`#lock`) - first, above the calendars

Two per-phone switches (`POST .../calendar/lock`, 303 to `#lock`; design 25 lets the parent decide what a locked phone
shows - a title can name a place, "Tannlege Solbakken"):
- **"Next event line"** (A) - off by default.
- **"Reminders"**: **Off** (default) / **The calm row** - the master switch (`lock_reminders: "off"|"row"`), overriding
  every calendar's reminder below. Off = no reminder row whatever a calendar says.

### 3.2 Calendars shown (`#calendars`, one form, `POST .../calendar/calendars`)

- The list is the **phone's last report** (§4.2): per calendar a switch "Shown", its name, a sub-line "<app> · <host>"
  (app from the account type: ICSx5, DAVx5, Phone for `LOCAL`, Google, else the type; host = the feed's host, §4.2) and
  the **reminder** select: **Off** (the default for every calendar), **The calendar's own reminders**, **5 / 10 / 15 /
  30 / 60 min before every event** - greyed with "Reminders are off for this phone" while the master switch is off.
- The reminder is chosen **per calendar, here, by the parent** - never per event, never by the kid.
- A calendar the parent saved but the phone no longer reports shows greyed, "Not on the phone now", with a Remove (its
  settings row goes; rules naming it stay and say so). Two calendars with one key are one (stated if the report has a pair).
- No report yet / `readable: false` / no calendars: the group says what to do instead (§6.1).
- One form writes exactly the rows it was sent (design 31 §5.6); 303 back to `#calendars`.

### 3.3 Title rules (`#rules`)

- A list in order ("First matching rule wins. The phone applies them when it draws the day; the calendar itself never
  leaves the phone."), each row "Starts with «Gruppe 2- skoleåret 2026/2027:» · Skolen · show as «Skole:» + the rest",
  with up/down buttons (`POST .../calendar/rules/{rule}/move`, 303 to `#rule-<id>`) and "Add a rule…".
- **The editor** (`/devices/{id}/calendar/rules/{rule}` and `/new`): *Look in* - **The title** (default) / **The
  description**; *When it* - **Starts with** (default) / **Contains** - the text; *Then* - **Show as** the text, with
  **Keep the rest of the title** (on = `prefix`, off = `replace`), or **Hide the event**; *In* - one calendar of the
  report, or Any; Delete. With *The description* only **Hide the event** is offered (replace and prefix stay
  title-only). No regex option in step 1.
- **Preview** ("Paste a real title to see what the phone will show", plus an optional Description box):
  `POST .../calendar/rules/preview` answers 200 with **only the preview fragment** re-rendered (never the form: the caret
  and "a save never jumps"), not a 303, so not a save in the Security Log; a small script posts it 400 ms after typing
  stops and swaps that fragment (no script: a Preview button). It also says "Rule 2 matches this title first".
- Save: 303 to `/devices/{id}/calendar#rule-<id>`. A refused rule (empty, too long, too many) re-renders the editor with
  what was typed and the reason (400); a rule gone since the page loaded is a 303 to `#rules` with `?notice=rule_gone`
  marked `settings_log::refused(...)`.
- Caps: 30 rules a phone; text to match 1-120 characters and text to show 0-40 (after cleaning; the latter empty only
  with Keep the rest: "strip the prefix").

### 3.4 The rule grammar (one pure implementation per side, one vector file)

- **Cleaning** (titles, descriptions, patterns and texts): control and bidi characters out, every whitespace run (NBSP,
  thin space, tab) one space, trimmed, **NFC** (Kotlin `java.text.Normalizer`; Rust `unicode-normalization`, already in
  `Cargo.lock`), a title cut to 200 code points. Rules see and produce cleaned text.
- **Matching** (Starts with, Contains) is case-insensitive per code point with Unicode's simple lowercase
  (`Character.toLowerCase(int)`; Rust `char::to_lowercase` when it yields one char, `U+0130 → U+0069` by hand) and
  **ignores whitespace**: both sides are compared with all whitespace removed and the span is mapped back onto the title,
  so the feed's broken word "R omerriket" still matches «Romerriket». The title shown is the title as it is - nothing
  repairs the word. Vectors pin `ÆØÅ`, `İ`, `Σ`, an NFD "å" and the broken word.
- A rule **applies** when its field's text matches (Starts with: the start; Contains: the first occurrence) and its
  calendar is `null` or equals the event's key (§4.1). **The first rule that applies wins**; none = the cleaned title.
- **Actions** (title rules): `replace` = the text; `prefix` = the text + " " + the rest, where the rest is the title with
  the span cut out, whitespace collapsed and the separators ` - – — : · | , ; /` trimmed from both ends (an empty text
  gives the rest; an empty rest the text; both empty the cleaned title); `hide` = the event is gone (any field).
  The real feed: «Gruppe 2- skoleåret 2026/2027: Litteraturhuset - Det kuleste fra R omerriket» → «Skole: Litteraturhuset
  - Det kuleste fra R omerriket».
- Implementations: `launcher/.../day/TitleRules.kt` and `server/src/calendar.rs`;
  `server/testdata/calendar_title_rules.json` (`{"titles": [{rules, calendar, title, description?, shown}]}`, `shown:
  null` = hidden) is **byte-identical** in `launcher/app/src/test/resources/`; the server's
  `calendar::tests::the_title_rules_vectors` and the launcher's `TitleRulesTest` run every row and compare the copies.
  The feed's titles, descriptions and DST days are the vectors.

### 3.5 Step 4, regex rules (later; not in step 1)

Android's `java.util.regex` is ICU-backed: `UNICODE_CHARACTER_CLASS` throws (`Pattern.java:1381-1383` [AOSP]), the Matcher
copies its input (`Matcher.java:1812`) so no `charAt` budget counts anything, and ICU folds case fully where Rust's
`regex` folds simply; JVM unit tests run OpenJDK's engine and would pass while the phone fails. So regex waits for a
linear engine on the phone: **RE2/J** (`com.google.re2j`, pure Java, BSD-3; new verification entries) and Rust `regex` on
the server, both compiling **one explicit whitelist form** printed from the parse (`\d`→`[0-9]`, `\s`→a space, `\w`→
`[\p{L}\p{N}_]`, no `\b`, `(?i)` with simple folding), with its vectors in `calendar_title_rules.json`. Then the PWA's
rule form gains "Matches a pattern" and `match: "regex"`; step 1's phone drops a rule with an unknown `match`.

## 4. The contract (one commit, both directories)

### 4.1 The policy: `calendar` (always sent, its own key like `lock_glance` and `music`)

```json
"calendar": {
  "lock_reminders": "off",
  "glance_next": false,
  "calendars": [
    {"key": "9f3a61c2d84be705", "shown": true, "remind": "off", "remind_minutes": null, "writable_for_kid": false},
    {"key": "c04e7b19a2f6d853", "shown": true, "remind": "before", "remind_minutes": 15, "writable_for_kid": false},
    {"key": "51d8e0aa3b7c9462", "shown": true, "remind": "own", "remind_minutes": null, "writable_for_kid": false}
  ],
  "rules": [
    {"id": 7, "calendar": "9f3a61c2d84be705", "field": "title", "match": "starts",
     "pattern": "Gruppe 2- skoleåret 2026/2027:", "action": "prefix", "text": "Skole:"},
    {"id": 9, "calendar": null, "field": "description", "match": "contains",
     "pattern": "6. og 7. trinn", "action": "hide", "text": ""}
  ]
}
```

- **The calendar key** = the first 16 hex digits of SHA-256 over `ACCOUNT_TYPE` NUL `ACCOUNT_NAME` NUL `Calendars.NAME`.
  `NAME` is the feed or collection URL in ICSx5 (`Subscription.kt:78`) and DAVx5 (so a rename of the display name, which
  DAVx5 copies from the server and ICSx5 lets the parent edit, can't change it; a feed removed and added again keeps it)
  [verify on the emulator]. Never the display name, never `_ID`. The account name (DAVx5: the login, often an e-mail)
  never goes on the wire. Two calendars with one key are one for the parent.
- A calendar not in `calendars` is shown with reminders off. `remind`: `"off"|"own"|"before"` + `remind_minutes` (5/10/15/
  30/60, else the calendar is off). `lock_reminders` `"off"|"row"` and `glance_next` default off; a missing `calendar`
  key = everything shown, no rules, the line off, reminders off. An unknown `field`/`match`/`action` drops that rule (and
  the phone reports its id, §4.2). A rule naming a key the phone doesn't have applies to nothing.
- The launcher reads the object field by field (raw JSON, as `lockGlancePolicy`, `GlanceRows.kt:54`). Pinned by
  `policy_json_keys_snapshot` and `PolicyResponseCompatTest`. Capability `calendar_v1` (`StatusCapabilities.kt`); the
  page says "This phone's Vibb One doesn't know Dagen yet" without it.

### 4.2 The status report: `calendar`

```json
"calendar": {"readable": true,
  "calendars": [{"key": "9f3a61c2d84be705", "account_type": "at.bitfire.icsdroid", "name": "Skolen",
                 "source": "skole.kommune.no", "access_level": 100}],
  "rules_skipped": []}
```

- `readable` false = no permission or the provider threw (then `calendars: []`). `name` is `CALENDAR_DISPLAY_NAME`, a
  label only. `source` is only the **host** of a `Calendars.NAME` that parses as an `http(s)`/`webcal` URL (never the
  path, query, userinfo or port; a feed URL often carries a private token), else null. `access_level` is
  `CALENDAR_ACCESS_LEVEL` (step 3 offers only >= `CAL_ACCESS_CONTRIBUTOR` 500). `rules_skipped`: ids of rules the phone
  dropped.
- **Its own sanitizer** (`calendar::sanitize_calendar_report` through `device_api::known_fields`,
  `handlers/device_api.rs:1126`, with a field table that `intake_limits::the_field_tables_keep_every_contract_key`
  (`tests/intake_limits.rs:354`) checks): known keys only; at most 50 calendars; `key` exactly 16 lowercase hex;
  `account_type` <= 200 (AccountManager's cap); `name` cut to 100; `source` a hostname (`[a-z0-9.-]`, <= 253, lowercased) or
  null; `access_level` an int; `rules_skipped` <= 30 ids, only ids the policy has; `readable` a bool; anything else dropped.
- No event, title, description, count or time is ever in it. Contract file `server/testdata/calendar_status_report.json`,
  byte-identical in `launcher/app/src/test/resources/`: the launcher's `CalendarReportTest` encodes exactly it, the
  server's `calendar::a_launcher_report_lists_the_calendars` posts it, and both compare the copies. This file and
  `calendar_title_rules.json` join `launcher.yml`'s and `server-ci.yml`'s path lists and the root `CLAUDE.md`'s paragraph.

### 4.3 Server storage

Migration `0102_calendar.sql` (or the next free number if design 68, built first, took it): `device_calendars` (device_id,
key; shown, remind, remind_minutes, writable_for_kid; `remind` CHECK `off|own|before`; **a partial unique index
`WHERE writable_for_kid = 1`** per device: at most one), `device_calendar_rules` (id, device_id, sort_order, calendar_key
nullable, field, match, pattern, action, text; CHECKs), `device_policy.calendar_glance_next` (default **0**) and
`calendar_lock_reminders` (default `'off'`), and `devices.calendar_report` (the last report's JSON). All cascade with the
device.

### 4.4 A background app: "On Home", and never suspended

ICSx5 and DAVx5 must be **allowed** (a hidden app is not installed for the user - no sync adapter runs), but neither
belongs on Home: the kid could delete the school feed.
- **"On Home"** (default on) is a field of the per-app `launcher_ui.app_display` entry (`PolicyAppDisplay`,
  `app_display.rs:38`, "Name and icon"): `on_home: bool`, a switch in that form, pinned by the snapshot. `AppFilter` leaves
  the apps with it off out of Home and the drawer **except during a pause**, when the parent sets them up. The kiosk's
  lock-task list keeps them, so a sync error notification still opens them.
- **Never suspended**: a time rule's lock suspends every allowed app but `neverRestrict`, the alarm app and
  `lockUsableApps` (`EnforcementPlan.kt:198`), which would suspend the sync apps at night and at school. The sync apps
  (a constant `CALENDAR_SYNC_APPS = {at.bitfire.icsdroid, at.bitfire.davdroid}`) are spared at `:198`, like the
  keyboards, when allowed - not through `neverRestrict`, which also skips the hide at `:193`. The pause's end re-suspends
  nothing of them either. PLAN's queued "kill the background processes of re-suspended apps" is not buildable (Android 14:
  `killBackgroundProcesses` ends only the caller's own processes, `launcher/CLAUDE.md`); if a hide-and-unhide ever
  replaces it, the sync apps stay out of that list. A suspended sync app would most likely keep syncing anyway
  (`SyncManager`, `JobSchedulerService`, `ActiveServices`, `BroadcastSkipPolicy` have no suspension check [AOSP]); the
  exception makes that not matter.

### 4.5 Room for step 3 (the kid's own events; not built in steps 1-2)

The parent picks ONE writable calendar as the kid's; Dagen then gets "Legg til" and the kid edits or deletes only the
events Vibb One made. Step 1 holds: `access_level` in the status rows (§4.2); `writable_for_kid` in the policy rows,
at most one (§4.3), in the snapshot from step 1 (the page offers it only for writable calendars: ICSx5's are read-only,
`Subscription.kt:81`; DAVx5's follow the server; local ones are writable). The "made by Vibb One" marker is a `vibb-`
prefix on `Events.UID_2445` (`CalendarContract.java:1375` [AOSP]), the iCalendar UID DAVx5 uploads, so it survives a
round trip [verify on DAVx5]; also set `CUSTOM_APP_PACKAGE` (`:1362`; local, lost on re-download). `_SYNC_ID` and
`SYNC_DATA*` are sync-adapter-only (`CalendarProvider2.java:4743`). Step 3 adds `WRITE_CALENDAR` and its self-grant.

## 5. The glance (step 2)

### 5.1 A: the line "Neste: Fotball 17:30"

- `lock_glance_next` under the alarm line (or the date without one), the alarm line's style (13 sp, Nunito Bold,
  `kid_core_dim`, 2 dp margin), at the date's start with the clock profile (`placeProfile`, `GlancePanel.kt`). Shown when
  `glance_next` is on and today has a timed event that starts after now; `"Neste: %1$s %2$s"` with the shown title
  (rules applied; one line, ellipsized) and the start in `ClockFormat`. Ongoing and all-day events never.
- **A yields while a reminder row (C) is up** (C already names the next event), and whenever its 20.2 dp would push the
  third row out of sight (pure `glanceNextFits` in `LockGlance.kt`, pinned by the table).
- **Geometry** (`LockGlanceLayoutTest`'s model, the line 20.2 dp; the Jelly Star runs the profile clock, 22.4 dp
  taller than the centred one, `LockGlanceTest.kt:1076-1087`; scroller 401 dp with 3-button navigation, 425 with
  gestures, fade 32 dp): music + 2 rows with the alarm line 370.6 → **390.8** (fits both). Music + 3 rows (compact):
  414.4, with the alarm line 434.6 (row 3 in sight 22.4 / 46.4 dp), and with A **454.8**: row 3 has **2.2 dp** in sight
  with 3-button navigation and 26.2 with gestures, all under the fade, so RC3-8's "every row partly in sight" breaks -
  hence the yield. C + music + 2 messages is exactly this page. Centred-clock phones: music + 3 rows 392 → 412.2 (scrolls
  11.2 dp at 473, none at 497). At font 1.3 a 13 sp line is ~25.7 dp and that page (rows ~67 dp) is already ~85 dp past
  the 401 dp scroller (an estimate, measured on the emulator): the alarm line and A push row 2 under the fold too, so
  `glanceNextFits` is tested at font 1.0 and 1.3.

### 5.2 C: the reminder row

- A `GlanceCalendarRow` in `badges/GlanceRows.kt` (a new `GlanceRow` kind, 56 dp, first in the list: the one row with a
  deadline), the calendar glyph in the badge place, line 1 `"%1$s om %2$d min"` (counting down; "%1$s nå" in the last
  minute), line 2 the start time only. It counts toward `glanceLayout`'s rows (music + 1 row full becomes compact while a
  reminder is up). Not dismissable, out of `swipeControls`; a tap = `GlanceTarget.Day` (§2.2). TalkBack: "Påminnelse:
  Fotball: Trening, om 15 minutter, 17:30".
- **Its form is always this calm row**: no notification, no heads-up, no sound, no vibration, no full-screen intent, no
  screen wake (`noticeCame()` is not called). Unlocked, the reminder is only in Dagen's header if Dagen is open; nothing in
  the shade (§8 question 3).
- **The instant** - checked in this order: `lock_reminders` `off` → none, whatever a calendar says; the calendar's `remind`
  `off` → none; `before` N: `BEGIN - N min`; `own`: the event's `CalendarContract.Reminders` rows of method `DEFAULT`,
  `ALERT` or `ALARM` (an ICS `VALARM`, a CalDAV alarm, ICSx5's per-feed default alarm), the earliest **at most 120 min**
  before the start (a day-before alarm would hang a row all day; Dagen shows the day); a row with `MINUTES_DEFAULT` (-1,
  `CalendarContract.java:2289` [AOSP]) counts as none; none = no row.
- Up from the instant until `BEGIN`; timed events of today only. **Every reminder follows the calendar's rules**: the shown
  (rewritten) title; a hidden event, a calendar switched off, a cancelled or declined event gives none. When two reminders
  fall together: one row, for the soonest event (ties in §2.5's order, after the dedupe).
- **Mechanics**: no alarm - the glance is only seen with the screen on. `GlancePanel` asks the calendar store for today's
  reminders at `resumeNow` and every render, and while started posts one main-thread callback at the next boundary
  (`nextReminderBoundary`, pure). `USE_EXACT_ALARM` and `TimeRuleAlarm` are untouched. `Reminders` are read once per day
  load (`Reminders.EVENT_ID IN (...)`).

## 6. Edge cases

1. **No sync app or calendar yet**: no Home tile; the PWA page says: "Add ICSx5 (feeds) or DAVx5 (CalDAV) from the
   catalog's F-Droid source, tick it for this phone, switch On Home off, then in a pause open it and add the feed or
   log in." Tip: send the `webcal://` link to the kid in Element X and tap it in the pause. DAVx5: an app password,
   read-only shares; sync every 1-4 h for battery.
2. **Accounts under restrictions**: `DISALLOW_MODIFY_ACCOUNTS` does not stop the sync apps adding their own accounts:
   `addAccountExplicitlyWithVisibility` checks only `isAccountManagedByCaller` (`AccountManagerService.java:493-503`
   [AOSP]); `canUserModifyAccounts` (`:6084`) gates Settings' add and remove. ICSx5 calls `addAccountExplicitly`. So no
   pause exception and no `setAccountManagementDisabled` (it would block the parent's removal). Only the apps' own
   permission dialogs and DAVx5's battery prompt need a pause. Keep the emulator check.
3. **A rarely-run sync app** drops to a low standby bucket [verify]. Dagen's open asks `ContentResolver.requestSync(account,
   CalendarContract.AUTHORITY, manual + expedited)` for each shown calendar's account, at most once per 30 min: a device
   owner sees every account (`AccountManagerService.java:6121`) and a TOP caller is exempted
   (`ContentService.java:1580`), so it works while Dagen is open.
4. **Permission revoked**: can't happen while managed. Unmanaged: `readable: false`, no tile; the page says why.
5. **Recurring exceptions**: the provider expands `EXDATE`s, moved and cancelled instances; the model drops
   `STATUS_CANCELED`.
6. **DST**: a 23/25-hour day is one `LocalDate`; an RRULE with a `TZID` stays 08:15 local across the change, but a UTC
   (`Z`) RRULE - the itslearning feed's - moves an hour locally (09:00Z is 11:00 before 25 October, 10:00 after). Test rows
   on 2026-10-25, 2027-03-28 and 2027-10-31.
7. **A rule the phone can't use** (an unknown field/match/action from a newer server): dropped, its id reported (§4.2).
8. **Etar (or another calendar app) stays**: it would post its own reminders with a sound for the same events - §8 question 2.

## 7. Steps, tests, emulator

**Step 1** (branch `day-view`, after design 68 is merged; the contract commit first, both dirs: §4.1, §4.2, the two
contract files, the CI paths): **server S** - migration, `calendar.rs` (rules, validation, policy, report sanitizer), the
page and editor, `Section::Calendar`, the summary row, `on_home` in the app-display form; **launcher S-M** - the
permission, `calendar/CalendarStore` (queries, observer, LRU, reports), `DayModel`, `RowText`, `DayPager`, `TitleRules`,
`DayActivity` + `EventActivity` + layouts + strings (nb/en), the Home tile, `on_home` in `AppFilter`, the sync apps at
`EnforcementPlan.kt:198`, the ViewPager2 dependency. One-line task at the merge: **correct PLAN.md:266-272** (the
2026-10-06 note says "block account changes" prevents DAVx5's account; §6.2 says it doesn't). **Step 2** (launcher S): A
and C, the Dagen header row. The policy carries the glance fields from step 1. **Step 3**: the kid's own events (§4.5).
**Step 4**: regex rules (§3.5).

Tests (lean):
- `DayModelTest`: all-day in UTC at +1/+2 and at -5, a timed event ending at midnight, multi-day, zero-length, cancelled,
  declined, a hidden calendar, a rule hiding by title and by description, the duplicate collapse, the order, "now" at
  `BEGIN` and `END`, the DST days (the UTC RRULE on 2026-10-25 too).
- `FirstSentenceTest` / `DayRowTextTest`: the first-sentence rule on the real feed's descriptions ("5. trinn", "ca. 13.15",
  "08.56", a 150-character sentence cut at a space, a description with no stop, empty), the second line = location else
  sentence, `cutByRule`.
- `DayPagerTest`: position <-> day both ways (positions never reach 0 or `MAX`), the anchor move at midnight keeps the
  page's date, `todayLabel`: +1 and +400 → "← I dag", -1 and -400 → "I dag →", 0 → none; ±100 years map back.
- `TitleRulesTest` / `calendar::tests::the_title_rules_vectors`: every row; the copies identical; the broken word, NFD,
  `İ`, a description rule.
- `CalendarReportTest` / `a_launcher_report_lists_the_calendars`; the sanitizer (junk dropped, the caps, a non-hex key,
  a source with a port/userinfo, ids the policy lacks) and the field-table test; `policy_json_keys_snapshot` +
  `PolicyResponseCompatTest` (`calendar`, `app_display.on_home`); `CalendarPolicyTest` (field by field, defaults, unknown
  values); the key (the phone alone hashes; a vector type + account + NAME → 16 hex).
- Glance: `LockGlanceLayoutTest` gains the line's 20.2 dp, the §5.1 table with `glanceNextFits` at font 1.0 and 1.3 and the
  yield while C is up; `GlanceRowsTest`: the reminder row first, its window per mode (`off`, `before` N, `own` with
  1440/60/15 → 60, `MINUTES_DEFAULT`, none), **master off → no row whatever `remind` says**, the rules on it, no row for
  all-day, two at once → the soonest, `GlanceStageEvent.Row(null)` under a time rule; `nextReminderBoundary`; a source
  check that the row path posts no notification and calls no `noticeCame()`.
- Server pages: the 303 anchors, a refused rule re-rendered with what was typed, the preview's 200 (fragment only, no
  `settings_saved` row), `rule_gone` marked `Refused`, the caps, move up/down, the lock group above the calendars, the
  master switch greying the reminders, the one-writable index.
- `HomeModelTest`: the Day tile after the phone book, gone without a shown calendar; `AppFilter`'s `on_home` off out, in
  during a pause; `EnforcementPlanTest`: a time rule suspends other allowed apps but not `CALENDAR_SYNC_APPS`.

Emulator (`jelly36`, `docs/testing/emulator.md` gets a §6 entry). Seed the provider (quote each command whole for the
device shell; if the shell uid lacks the calendar permission, use the feed):
```
adb shell "content insert --uri 'content://com.android.calendar/calendars?caller_is_syncadapter=true&account_name=dagen&account_type=LOCAL' --bind account_name:s:dagen --bind account_type:s:LOCAL --bind name:s:Skolen --bind calendar_displayName:s:Skolen --bind ownerAccount:s:dagen --bind calendar_access_level:i:700 --bind sync_events:i:1 --bind visible:i:1"
adb shell "content insert --uri content://com.android.calendar/events --bind calendar_id:i:<id> --bind title:s:'Gruppe 2- skoleåret 2026/2027: Matte' --bind description:s:'Viktig at man er klar 08.30. Ta med gymtøy.' --bind dtstart:l:<ms> --bind dtend:l:<ms> --bind eventTimezone:s:Europe/Oslo"
```
(a weekly one: `--bind rrule:s:'FREQ=WEEKLY;BYDAY=MO' --bind duration:s:PT6H15M` instead of `dtend`; all-day: `allDay:i:1`,
`eventTimezone:s:UTC`, UTC midnights.) Then the real path: ICSx5 from the catalog's F-Droid source, a feed from the host
(`python3 -m http.server 8088`, `http://10.0.2.2:8088/dagen.ics`) with a weekly series with an `EXDATE` and a moved
instance, an all-day event, a weekend camp, a UTC RRULE across 2026-10-25, a `VALARM` of 15 min, and the itslearning
shapes (a mid-word break "R omerriket", long descriptions, "5. trinn").
Check: the endless swipe both ways (a year out and back), the jump-back button ("← I dag" / "I dag →") on every page but today, the heading with weekday and date on all, "Pågår nå", the empty day; the
two-line caps and the "›" on cut rows only at font 1.0 and 1.3, the tap → the whole description, links dead; a rule from
the PWA changes the title on the next sync; with both lock switches off nothing on the glance; A and C (C silent, gone at
the start, tap → keypad → Dagen; the master switch off kills it); no shade notification; DAVx5 against a CalDAV test
server; and the [verify] items: ICSx5's and DAVx5's `NAME`, `ACCOUNT_TYPE`, `access_level`, `DISALLOW_MODIFY_ACCOUNTS`
against both apps' account creation, sync while a time rule is on (they are spared), `requestSync`, a far-future day.

## 8. Open questions for the user (with defaults)

1. **Where Dagen opens from**: default a Home tile right after the phone book, plus the reminder row's tap through the
   keypad. Alternatives: the tile last among ours (before the flashlight), or no tile and only from the lock.
2. **Etar stays or goes**: default **goes** off the allowlist once Dagen is in - Dagen covers reading, the parent edits in
   the source calendars, and Etar's own reminders would ring for the same events (and need the exact-alarm grant, design
   68). Keeping it means its notifications blocked or its reminders off.
3. **A reminder while the phone is unlocked**: default **none** (the glance row, and the row in Dagen's header). The
   option: a silent shade notification with the same words (no sound, no vibration, no heads-up), per phone.

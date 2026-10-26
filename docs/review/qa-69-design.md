# QA 69 (design): "Dagen" - the day view, the title rules, glance A and C

Note d603fb3a, mockups origin/main 15ed5b6e; against the code, AOSP `main` (frameworks/base, CalendarProvider, libcore), ICSx5.

**Verdict.** The shape holds: the provider reads, the all-day rule, the observer, the self-grant, the server's side
and the calm-row mechanics are right, and the three open-question defaults (the tile after the phone book, Etar goes,
nothing in the shade) are sound. One blocker: the phone's regex plan can't run on Android (H1). The revision must
take in the user's three additions (M1-M3), key calendars so a rename can't fail open (M4), and redo the glance
numbers for the Jelly Star's own page (M6). **1 High, 7 Medium, 8 Low, 4 Nits.**

## High

**H1. Android's `java.util.regex` is ICU4C, so §3.4's phone regex can't work.** `UNICODE_CHARACTER_CLASS` throws on
Android (`libcore/ojluni/.../regex/Pattern.java:1381-1383`), so every regex rule would land in `rules_skipped`. The
Matcher copies its input (`this.text = input.toString()`, `Matcher.java:1812`) and matches in native ICU, so the
counting `CharSequence` is never read and the 20 000 budget limits nothing (`(a|aa)*b` can hang a day load). ICU's
case-insensitive matching uses full case folding (ß ~ ss), Rust's `regex` simple folding, so the two sides disagree.
JVM unit tests run OpenJDK's engine: `TitleRulesTest` would pass while the phone fails. **Fix:** a linear engine on
the phone, RE2/J (`com.google.re2j`, pure Java, BSD-3, new verification-metadata entries, the RE2 family like Rust's
crate). Both sides compile one explicit form printed from the whitelist's parse: `\d`->`[0-9]`, `\s`->` `,
`\w`->`[\p{L}\p{N}_]`, no `\b`, `(?i)` (simple folding on both). The budget, `rules_slow` and the "exponential in
Java" text go. If that is too much for step 1, ship Starts with/Contains only and add regex later.

## Medium

**M1. The user's addition: the calm reminder or nothing at all, chosen per phone. The note has neither.** (1) The
policy's `calendar` gets `lock_reminders: "off"|"row"`, default `"off"`, overriding every `remind`: §5.2's instant
checks it first, and `GlanceRowsTest` adds "master off -> no row whatever `remind` says". (2) `glance_next` stays its
own switch but **defaults to off** (`calendar_glance_next DEFAULT 0`; §3.3's "on by default" and §4.1's "a missing
key = the line on" turn to off). PLAN's L7 entry already says "nothing on the lock screen until chosen", and the
mockup's §4 ("A alltid på") is out of date. (3) `remind` already defaults to `off` (§3.1, §4.1): keep it. (4) Device ›
Calendar puts the two phone-wide switches first, above "Calendars shown" (`POST .../calendar/lock`, 303 to `#lock`).
This is also the privacy answer: a title can name a place ("Tannlege Solbakken").

**M2. The user's step 3 (the kid's own events) needs room in step 1's contract.** (a) The status `calendars[]` gets
`access_level` (`CALENDAR_ACCESS_LEVEL`; writable = >= `CAL_ACCESS_CONTRIBUTOR` 500), so the page offers only
writable calendars: ICSx5's are read-only (`Subscription.kt:81`, `CAL_ACCESS_READ`), DAVx5's follow the server's
rights, local ones are writable. (b) The policy's `calendars[]` gets `writable_for_kid` (default false, at most one:
a partial unique index), in the snapshot from step 1. (c) The marker for "made by Vibb One": `_SYNC_ID` and
`SYNC_DATA1-10` can be written only by a sync adapter (`CalendarProvider2.java:4743` throws,
`CalendarContract.java:1867`). `CUSTOM_APP_PACKAGE` (`:1362`) is local and never uploaded, so a re-download loses
it. `UID_2445` (`:1375`) with a `vibb-` prefix is the iCalendar UID that DAVx5 uploads, so it survives the round trip
[verify on DAVx5]. Set both; test by the UID. (d) §1's "Read-only" becomes "in steps 1-2".

**M3. The real feed puts what matters in `DESCRIPTION`, not `LOCATION` (mockup §5).** (1) Add `DESCRIPTION` to the
projection. The row's second line is the location when there is one, else the description's first sentence (at most
two lines, ellipsized), cleaned like titles and cut to 2 000 code points. (2) A tap opens the whole description,
read-only, links not tappable (kiosk). §2.1's "nothing is tappable" and §1's "no event detail page" change.
(3) Rules get `field: "title"|"description"` (default title). A description match only hides; replace and prefix stay
title-only. It goes into the vectors and the snapshot in step 1, and the preview gets an optional Description box
(the mockup's second preview line needs it). (4) The description never reaches the glance or the server: A and C stay
time + title, as §5.1/§5.2 say now.

**M4. A calendar keyed by its display name fails open on a rename.** §4.1 keys on `(account_type, account_name,
CALENDAR_DISPLAY_NAME)`. DAVx5 copies the display name from the server and ICSx5 lets the parent rename a
subscription. A new key is "shown, reminders off" (§4.1), so a calendar the parent switched off comes back and its
calendar-bound hide rules stop. **Fix:** key on `Calendars.NAME`, the feed or collection URL for both apps
(`Subscription.kt:78`), which also survives a remove and re-add. Send it only as `key` = the first 16 hex of SHA-256
(`account_type` NUL `account_name` NUL `NAME`), with the display name as a label. That also keeps `account_name` off
the wire (DAVx5: the login, often an e-mail). The sub-line becomes "<app> · <host>". (ICSx5's account name is the
translated "Calendar subscriptions", `strings.xml:5`, not §4.1's "ICSx⁵".)

**M5. The time rules suspend the sync apps, and a queued fix would kill them; the suspend [verify] has an AOSP answer.**
During a lock `EnforcementPlan.kt:198` suspends every allowed app but `neverRestrict`, the alarm app and
`lockUsableApps`, so ICSx5/DAVx5 are suspended at night and at school. SyncManager, JobSchedulerService, JobStatus,
ActiveServices and BroadcastSkipPolicy on `main` have no suspension check (`grep -i suspend` finds nothing), so a
suspended sync app most likely keeps syncing. But PLAN:520's queued "kill the background processes of re-suspended
apps" (at a pause's end and at a rule's start) would cut a running sync. **Fix:** §4.4 states both; `background_apps`
stay out of that kill list; keep the emulator check "sync while a time rule suspends it" (§4.4's `AppFilter` is right).

**M6. §5.1's numbers use the centred clock, but the Jelly Star runs the profile clock** (`LockGlanceTest.kt:1076-1087`:
music + 3 rows 414.4 dp, 434.6 with the alarm line). With A it is 454.8: row 3 has **2.2 dp** in sight with 3-button
navigation (401 - (454.8 - 56)) and 26.2 dp with gestures, all of it under the 32 dp fade, so RC3-8's "every row partly
in sight" breaks. C is a row, so C + music + 2 messages is exactly this case. At font 1.3 a 13 sp line is ~25.7 dp,
and the same page (rows ~67 dp) is already ~85 dp past the 401 dp scroller (my estimate); the alarm line and A push row
2 under the fold too. **Fix:** A goes while C is up (C names the next event; §5.1's "the next after E" goes); §5.1's
table uses the profile rows (`row3InSight` with A); state the font-1.3 case.

**M7. The status `calendar` object needs its own sanitizer.** `server/CLAUDE.md` "Fail closed": every blob goes
through `sanitize_*`/`known_fields` (`device_api.rs:1126`) and `intake_limits::the_field_tables_keep_every_contract_key`.
§4.2 bounds only the count (50) and the names (100). Add: known keys only; `key`/`account_type` <= 200 (AccountManager's
cap); `source` a hostname (`[a-z0-9.-]`, <= 253, lowercased; `Uri.host`, never userinfo or port); `access_level` an
int; id lists <= 30 and only ids the policy has; `readable` a bool; anything else dropped.

## Low

**L1. `DISALLOW_MODIFY_ACCOUNTS` doesn't stop the sync apps adding their own accounts (the note's AOSP reading is right).**
`addAccountExplicitlyWithVisibility` checks only `isAccountManagedByCaller` (`AccountManagerService.java:493-503`).
`canUserModifyAccounts` (`:6084`) gates Settings' add and remove (`:2369`, `:3439`); ICSx5 calls `addAccountExplicitly`
(`AppAccount.kt:48-49`). So no pause exception and no `setAccountManagementDisabled` (it gates the same Settings paths
and would block the parent's removal). Keep the emulator check; fix PLAN:268-270 if it agrees. §6.3's `requestSync`: without
account access a silent no-op (`ContentService.java:615`), but a device owner sees every account (`AccountManagerService
.java:6121`) and a TOP caller gets `SYNC_EXEMPTION_PROMOTE_BUCKET_WITH_TEMP` (`ContentService.java:1580`): it works while Dagen is open.

**L2. Reminder corner cases.** `Reminders.MINUTES` = `MINUTES_DEFAULT` (-1, `CalendarContract.java:2289`) counts as no
own reminder. When two reminders fall together: one row, for the soonest event (ties after §2.5's dedupe); pin it in
`GlanceRowsTest`. Under a time rule C's tap is `GlanceStageEvent.Row(null)` (`LockGlance.kt:107`): say so.

**L3. Broken text must not defeat Starts with/Contains.** "Det kuleste fra R omerriket" (the producer's folding): the
phone shows what the provider holds, but Contains "Romerriket" should match. Compare with whitespace removed, map the
span back, and pin a vector. Apply NFC to the title and the pattern (an NFD "å" from a Mac-made ICS). The mockup's §5
shows the title repaired ("sammenslått ved import"), which nothing does: fix the caption.

**L4. Use the real feed's strings.** The prefix is "Gruppe 2- skoleåret 2026/2027: ". The pattern and the text go
through the title's cleaning ("Skole: " + rest = "Skole:" + " " + rest). The examples and vectors use it, not
"2026/27 – 5B –". The feed's `Z` times: a UTC RRULE moves an hour locally across DST, so §6.6's "stays 08:15" holds
only for TZID events. Add 2026-10-25 to the DST test days.

**L5. `remind` mixes types** (`"off"`, `"own"`, 15): an untagged serde enum and a hand-read in Kotlin. Use
`"off"|"own"|"before"` + `remind_minutes`.

**L6. `background_apps` vs design 68:** PLAN builds round L7 after B1, so write "68 first" and drop "or in one branch".
Consider `on_home` on the per-app `launcher_ui.app_display` entry (`models.rs:230`, "Name and icon") instead of a
second per-app list.

**L7. Debounce the observer:** `notifyChange` hits `CONTENT_URI` per sync batch (`CalendarProvider2.java:846`); reload at most once a second.

**L8. The preview swaps only its own output**, never the form (the caret, "never jump").

## Nits

- N1. `Section::Calendar` belongs to the test table (`server/src/tests/mod.rs:697`). The migration is 0102 unless 68 takes it.
- N2. Size: §5.1's B arithmetic (B is out) can go; §7: one `content insert` per shape, no `#` variant lines; §2.4's
  AOSP quote can be a citation.
- N3. Confirmed: all-day instances in UTC (`CalendarInstancesHelper.java:212-216`, `:706`, `:892-908`); cancelled
  exceptions never become instances (`:510`); `selfGrantPermission` (`QuickControls.kt:141-163`) on our own package and
  READ_CALENDAR isn't an Android 12 sensor permission; `calendar_v1` fits `STATUS_CAPABILITIES` (`StatusCapabilities.kt:11`).
- N4. The architect's mockup fixes are right: prefix "Skole: Matte, norsk, gym" (`dagen.html:154`), C's second line
  the time only (`:317`), the fixed empty-day words (`:189` says "i dag" on a Saturday), "I dag" hidden on today (`:201`).

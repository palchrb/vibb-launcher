# Emulator runs: the archive

The sections of `docs/testing/emulator.md` whose run gates are done (every row recorded), moved here on 2026-11-27 with
their § numbers and words unchanged; emulator.md keeps each heading with a pointer here. The recipe (§1-§6) and every
section with a row not run or owed stay in emulator.md, and so does any new section.

## 6k. The lock started with the screen on (design 32 step 1)

**Design 132: HOME_THEN_LOCK and Home's dark lock-up are gone - every lock-up is the lock alone (Android's face). This
section is history; design 132 §11 is the gate.**

Design 32 step 2's keyguard cover (Android's keyguard as a bare Swipe cover across the sleep) was a debug-only device
experiment. Its switch, `KeyguardCoverDebug` and its receiver, was deleted in round 0b (2026-10-26) unrun, so the
cover never arms and its checks are gone; the COVER mode's code goes with design 59 phase D. §5's H2 toggle
(`LockUpDebug`) went with it.
"Stay awake" still goes off in design 59's android mode, which sets `setMaximumTimeToLock` (DPMS then writes
`STAY_ON_WHILE_PLUGGED_IN = 0`): run `adb shell svc power stayon true` again after it where a check needs it (§6f, §6j).

**The frame-stepping rule** (every check below that records, §11):
```
adb shell screenrecord --bugreport --bit-rate 8000000 /sdcard/flash.mp4   # Ctrl+C to stop
adb pull /sdcard/flash.mp4 && mkdir -p frames && ffmpeg -i flash.mp4 -vsync 0 frames/%05d.png
```
Step through every frame from the first lit one after each power-on to the lock's first frame. **A run fails on any
frame with** app content; a status-bar time older than the live one (a snapshot, or a stale display buffer); Home's
grid; or SystemUI's lockscreen. Black frames, with or without the live status bar, pass. When in doubt, use the
§5 Perfetto layer trace in `docs/design/32-lock-flash.md`. Record with the device's own `screenrecord`, not the
emulator window: one stale full-display frame at power-on, with an old status-bar time, was the emulator's host
composer and not WM (2026-10-13). Check suspect frames with a different `-gpu` mode and on a real phone.

**The checks** (`smoke-test.sh`'s "Double press" step automates the first point):
- [ ] `adb shell input keyevent KEYCODE_SLEEP KEYCODE_WAKEUP` from an app, until the log says `interactive=true` (the
  fade really cancelled): the lock comes up. **Known residual** (design 32 §12, qa-32-s2-code M1):
  the lock's black splash (SystemUI logs `VRI[<last part of the package>]`, `debug` for the debug build) and Shell's
  default close of the app the kid was already in (the card). Frame-step a screenrecord: the app, then that close and
  black, then the lock - and no frame of any *other* app.
- [ ] The next screen-off after a wake whose ScreenOn backstop re-started the lock on top (`START ... result code=3`,
  `wm_new_intent PinLock`) logs `NONE`, not `HOME_THEN_LOCK`, and there is no `Set visible without transition
  PinLock`.

## 6w. Dagen: the day view, the title rules, the glance's line and reminder row, the kid's own events (design 69)

Branch `day-view`; `wm density 240` (§3b). Logcat: `CalendarStore`, `GlancePanel`. Nothing below needs adb for the
feature itself - the seed is a test shortcut; the real path is ICSx5 from the catalog (step 5).

1. **Seed the provider** (quote each command whole for the device shell; if the shell's uid lacks the calendar
   permission, skip to step 5's feed):
   ```
   adb shell "content insert --uri 'content://com.android.calendar/calendars?caller_is_syncadapter=true&account_name=dagen&account_type=LOCAL' --bind account_name:s:dagen --bind account_type:s:LOCAL --bind name:s:Skolen --bind calendar_displayName:s:Skolen --bind ownerAccount:s:dagen --bind calendar_access_level:i:700 --bind sync_events:i:1 --bind visible:i:1"
   adb shell "content query --uri content://com.android.calendar/calendars --projection _id:name"
   adb shell "content insert --uri content://com.android.calendar/events --bind calendar_id:i:<id> --bind title:s:'Gruppe 2- skoleåret 2026/2027: Matte' --bind description:s:'Viktig at man er klar 08.30. Ta med gymtøy.' --bind dtstart:l:<ms> --bind dtend:l:<ms> --bind eventTimezone:s:Europe/Oslo"
   ```
   A weekly one: `--bind rrule:s:'FREQ=WEEKLY;BYDAY=MO' --bind duration:s:PT6H15M` instead of `dtend`; all-day:
   `allDay:i:1`, `eventTimezone:s:UTC`, UTC midnights. `date +%s000` gives now in ms. **A colon inside a `--bind`
   value must be escaped as `\:`** (`title:s:'Skole\: Matte'`), else `content` answers "Binding not well formed".
2. **The tile and the page**: after a sync Home has "Dagen" right after the phone book (the blue tile); the PWA's Device ›
   Calendar lists "Skolen · Phone" (the report) and the local "Min kalender" once Dagen was opened (the kid may add events
   by default). Dagen: the heading is the weekday over the date on every page, today too; "← I dag" on a later day,
   "I dag →" on an earlier one, none on today; swipe a year out and back (endless both ways, a 10 dp peek of the
   neighbours' cards at rest: their edges show at x 0-10 dp on both sides, the card 16 dp in); "Pågår nå" and the peach ring on an ongoing event; "Ingenting i kalenderen." on an empty day; a far-future
   day (2040) is an empty day, no error.
3. **Caps and "›"** at font 1.0 and 1.3 (§3b): a long title and a long description wrap to two lines each with "…", and
   only those rows show the small "›"; a row whose text fits has none. At 1.3 the header's date under the weekday is
   whole (the header grows), an all-day row's time column is "All" over "day" ("Hele" over "dagen"), and in en-US every
   time carries AM/PM ("12:38 AM", the column as wide as the page's widest time); "Ny avtale"'s day reads "Tuesday,
   October 27". A tap opens the event's page with the whole
   description; a URL in it is not a link. TalkBack: one node per row, "Pågår nå, 14:45 til 15:30, …", action "Åpne
   hendelsen".
4. **Rules from the PWA**: add "Starts with «Gruppe 2- skoleåret 2026/2027:» · Skole: + the rest"; the preview shows
   "Skole: Matte" while typing (no page jump, no `settings_saved` line); after the next sync Dagen shows "Skole: Matte";
   a "Contains" hide on the description ("gymtøy") hides the event - **move the hide rule above the "Starts with" rule
   first** (Title rules' up arrow): the first rule that matches wins, and added last the "Starts with" rule matches the
   title before the hide is asked (as designed, design 69 §3.4).
5. **The real path**: ICSx5 from the catalog's F-Droid source, ticked for this phone, On Home off under Apps (it stays
   allowed, gone from Home and the drawer; in a pause it shows); in a pause add a feed from the host (`python3 -m
   http.server 8088`, `http://10.0.2.2:8088/dagen.ics`) with a weekly series with an `EXDATE` and a moved instance, an
   all-day event, a weekend camp, a UTC RRULE across 2026-10-25, a `VALARM` of 15 min, and the itslearning shapes ("R
   omerriket", long descriptions, "5. trinn"). **ICSx5 syncs only after its own "Calendar access - GRANT" card on its
   start screen is granted** (in the pause; until then its sync fails silently - from adb: `requestsync -n
   "'Calendar subscriptions'" -t at.bitfire.icsdroid -a com.android.calendar -F --is --ib --ex`). Check the [verify] items: the calendar's NAME is the feed URL (the key
   stays across a rename of the display name), its account type `at.bitfire.icsdroid`, access level 200; with a time
   rule on, ICSx5 is not suspended (`dumpsys package at.bitfire.icsdroid | grep suspended`) and still syncs; Dagen's
   open asks a sync (`dumpsys content` shows a manual sync of the account); `DISALLOW_MODIFY_ACCOUNTS` doesn't stop
   ICSx5 adding its account.
6. **The glance** (lock the phone): with both lock switches off, nothing of the calendar on the glance. "Next event line"
   on: "Neste: Skole: Matte 10:00" under the alarm line in its style; with music + 3 message rows and 3-button
   navigation it yields (row 3 stays partly in sight). Reminders "The calm row" and the calendar's reminder "15 min
   before": 15 minutes before the event the row comes first ("… om 15 min", the start under it), silent - no sound, no
   vibration, no heads-up, nothing in the shade, the screen stays dark until pressed; it counts down to "nå" and is gone
   at the start; a swipe sideways does nothing at all (not dismissed, no keypad), TalkBack has no "Fjern"; its tap asks
   the keypad ("Skriv koden for å åpne Dagen") and the code opens Dagen on that day (its own task, `wm_destroy_activity`
   shows no `DayActivity`); under a time rule the tap gives the plain keypad. The master
   switch off removes it whatever the calendar says; the "Neste" line yields while the row is up. "own": a `VALARM` of
   15 min gives the row at 15, a day-before alarm none. Dagen open while a reminder is up shows it under the header.
7. **The kid's own events**: the "+" is there from day one (the local "Min kalender"); "Ny avtale" with what, day (the
   shown day), from, length, place → Lagre → the row with a peach start (since 2026-11-09; green before) and "Min kalender" as its second line; its page
   has Endre and Slett; a feed's event has neither. In the PWA pick a DAVx5 calendar the kid may write to (a CalDAV test
   server, e.g. Radicale): the new event uploads with its `vibb-` UID, comes back on the next sync and can still be
   changed; "Nowhere (no +)" removes the "+". **The pickers** (the user's jellystar2, 2026-10-27): "Ny avtale"'s day and
   time pickers at font 1.0 and 1.3, with the system in light and in dark mode (`adb shell cmd uimode night yes|no`):
   every day number, the weekday row and both month arrows visible on the night panel, the chosen day night on peach;
   the time picker's clock-face numbers visible, the chosen one night on peach.
8. **Dagen's own task** (2026-10-27, branch `day-task`): open Dagen from Home, tap Recents (`KEYCODE_APP_SWITCH`) - a
   card titled "Dagen" with the launcher's icon shows the day (no empty frame); Home - Android's task-close animation
   (the view shrinks away, not the theme's fade); Back from the day - the same; the reminder row's tap + code from the
   lock - the same card, on the reminder's day. An event page open, then Home, then the tile: the task comes back with
   the page on top (as any app); the reminder row then lands on the day view itself. A time rule beginning while Dagen
   is open, then Recents › Dagen: the time-rule screen comes back on top at once (`dumpsys activity activities | grep
   -E "DayActivity|LockActivity"`); `adb shell dumpsys activity recents | grep -c "\.day"` is 1.
9. **A colour per calendar** (2026-10-27, branch `calendar-colours`, mockup §6 B): seed a second LOCAL calendar
   ("Fotball", step 1) with an event on the same day as Skolen's. In the PWA's Device › Calendar every calendar but
   the kid's own pick has a "Colour" row ("Farge") under its Reminder: before any save its select says "Pick for me"
   and no swatch shows; change anything on the form (a reminder) - the page comes back at the same place, not the top,
   and every shown calendar now has a swatch and its name selected, each a different colour (Skolen Sky, Fotball
   Forest, the kid's "Min kalender" none - peach is its own); pick "Sea" for Fotball and the swatch follows. A save on
   the links group alone does the same (add a link, §6y, without touching the calendars form: every reported calendar
   gets a swatch); a phone set up on the phone alone gets its dots from its own report as soon as it has two
   calendars (QA L7). After the
   sync Dagen's rows: an 8 dp dot in the calendar's colour in its own column before the time, the start **and** the
   end time of every row at one left edge (a row of a calendar without a colour keeps the empty column), the kid's own
   event's dot peach; under the list the key "● Skolen   ● Fotball   ● Min kalender" only on a day with events from
   more than one calendar (a day with Skolen's alone has none); TalkBack reads a row on such a day with its calendar
   last ("…, Gym, Skolen") and the key as "Kalendere: Skolen, Fotball". Lock the phone with the quiet reminder row
   up: the disc holds a 16 dp dot in the calendar's colour instead of the calendar glyph (the glyph stays for a
   calendar without one); the row's subtitle is the time alone - the calendar's name is the key's job on Dagen, not
   built on the lock row (QA L1). Hide Fotball in the PWA, seed a third calendar with an event (it takes the next
   colour, not Fotball's - a hidden calendar's colour stays reserved, QA M1), then show Fotball again: three different
   dots, three entries in the key. Delete the third calendar from the provider: after the phone's next report its
   Colour row is gone and its colour free again (QA L2). The phone's own "Min kalender" never has a Colour row
   (QA L3). A `color` the launcher doesn't know (edit the policy by hand) is no dot, never a wrong one.

**Run 2026-10-27, E5** (`docs/testing/2026-10-27-e4-e5-run.md`): steps 1-7 on `jelly36` (en-US, UTC) passed except: the
reminder row's tap + code lands on Home, not Dagen (`DayActivity` started from the PIN lock without a new task - High),
the header's date line is clipped at font 1.3, and the 10 dp peek doesn't show. Every [verify] item of step 5 passed
with ICSx5 2.4.3 from the catalog (grant its calendar access on its own start card in the pause, or it never syncs). The
"Contains" hide of step 4 works once moved above the "Starts with" rule (first match wins). A `--bind` value with a
colon needs `\:`. Device checks: Dagen on 2040 (the provider answers), TalkBack's action words, music + 3 rows with
3-button navigation, the DAVx5/Radicale half. **Fixed on `day-view` after the run** (to re-check in the next run): the
reminder row's Dagen in its own task (H1), the header and the time column at 1.3 (M1), the peek (L1), a sideways swipe
on the reminder row doing nothing (L2), AM/PM and "Tuesday, October 27" in en-US (L4).

**Run 2026-10-31 (E6)** (`docs/testing/2026-10-31-e6-run.md`, `jelly36` in Europe/Oslo): steps 8 and 9 passed. Step 8:
the Recents card `Vibb One (debug) Dagen`, one task with the `.day` affinity, the reminder row's code opens Dagen on the
reminder's day with no `wm_destroy_activity` of `DayActivity` (E5's H1 fixed), the event page comes back on top from the
tile, a time rule beginning over Dagen puts the time-rule screen on top and Recents › Dagen brings it back at once (the
bare `grep -c "\.day"` counts the one task's lines - 9 here; count `Recent #… A=…debug.day` instead). Step 9: the dots
`[45,235][57,247]` (8 dp) with every time at x=66, the key only with more than one calendar, TalkBack's calendar last,
the lock row's disc a 16 dp dot of exactly SEA (console screenshot) with the time alone under it, "Pick for me" before
any save, the auto-pick at the link add and at a report, a hidden calendar's colour reserved (Kor took Berry), a deleted
calendar's freed at the next report (K4 took it), "No free colour" with eight taken. E5's fixes seen: the 10 dp peek
(`[0,138][14,231]`), AM/PM, the header whole at 1.3. The animations and the 390 px page are device checks; a hand-edited
`color` wasn't run. **Note for the poster**: the calendars form carries one `key=` per calendar - a tool that folds
repeated fields into one (`pwam.py`) updates only the last calendar.

## 6y. Calendar links: a link pasted in the PWA, the phone's own calendar of it (design 72 step 1)

Branch `calendar-feeds-1`; `jelly36`, `wm density 240`. Logcat: `CalendarFeeds`, `CalendarStore`. No ICSx5. The feed is
served from the host as in §6w step 5: `python3 -m http.server 8088` in a scratch directory holding `school.ics` (start
from `server/testdata/calendar_feeds/series.ics` and `itslearning.ics`), the link `http://10.0.2.2:8088/school.ics`.

1. **Add**: Device › Calendar › Calendar links (Kalenderlenker): paste the link, no name -> the page lands on the
   link's row (`#link-<uid>`), the name is the feed's `X-WR-CALNAME` (else the host), the row says "Hentet hh:mm · N
   hendelser" and, since `10.0.2.2` is the host's LAN, the plain-http warning. "Calendars shown" lists it at once:
   "Kalenderlenke · Venter på telefonen". A `webcal://` link works the same; `http://` to a public host is refused,
   the form as typed. Without the music key (rename `data/keys/music-secret.key`, restart) the form says why.
2. **The phone**: at the next sync (the add nudges) a LOCAL calendar `vibb-feed-<uid>` under account `Vibb One`
   appears: `adb shell "content query --uri content://com.android.calendar/calendars --projection _id:account_name:account_type:name:calendar_displayName:calendar_access_level:cal_sync1:cal_sync2"`
   - access level 200, `cal_sync1` = the policy's rev. The PWA's row loses "Venter på telefonen"; Dagen shows the
   events: the weekly series without its EXDATE day, the moved instance on its new day and time, the cancelled one
   gone, the all-day camp on its dates, "skoleåret" whole and "R omerriket" as the feed has it, a title rule on it.
   The VALARM's minutes show in Dagen's "own" reminder mode.
3. **[verify] the provider's forms**: `adb shell "content query --uri content://com.android.calendar/events --projection _id:_sync_id:original_sync_id:original_id:originalInstanceTime:rrule:exdate:duration:eventTimezone:eventStatus --where \"calendar_id=<id>\""`
   - overrides linked (`original_id` = the master's `_id`), the EXDATE honoured (no instance that day - else set
   `EXDATE_COLUMN = false` in `day/FeedEvents.kt` and check the cancelled override rows instead), UNTIL ends the series
   on its day, the cancelled override makes no instance (`Instances` for that day).
4. **DST**: the itslearning `Z` weekly series moves an hour locally across 2026-10-25 (faithful); a `TZID=Europe/Oslo`
   one stays at its time across 2027-03-28.
5. **A change**: edit `school.ics` (a title), "Hent nå" -> the row's time updates, the next sync rewrites only that
   series (logcat `Applied a calendar link: 1 rows deleted, 1 series written`); a second sync makes no request.
   "Hent nå" again within 5 minutes: "Hentet for et øyeblikk siden".
6. **Empty**: replace the file with an empty `VCALENDAR` -> the row says "Lenken ga ingen hendelser - viser forrige";
   the day keeps its events (until the third empty fetch or 24 h).
7. **2000 events**: a generated feed of 2000 events (as `ics::tests::the_caps`) -> time the first apply (logcat
   timestamps from `Made a calendar link's calendar` to `Applied a calendar link`); kill the process mid-apply
   (`am kill`), the next sync completes it.
8. **Remove**: Fjern… -> the calendar goes from the phone at the next sync, its "Calendars shown" row greys (settings
   kept); add the same link again -> the same calendar key, its settings back.

**Run 2026-10-31 (E6)** (`docs/testing/2026-10-31-e6-run.md`, `jelly36` started with `-timezone Europe/Oslo`): steps
1-8 passed, every [verify] item included - the provider linked the overrides (`original_id` = the master's `_id`),
honoured both EXDATE forms (`EXDATE_COLUMN` stays true), ended the UNTIL series on its day with the RDATE after it, made
no instance for the cancelled override; the `Z` series moved an hour across 2026-10-25 and the `TZID=Europe/Oslo` one
stayed at 16:00 across 2027-03-28; 2000 events applied in 3.0 s; a SIGKILL mid-apply (`run-as me.vibb.launcher.debug
kill -9 <pid>` - `am crash` does nothing to the device owner) left 600 rows and the restart's sync rewrote all 2000
(`600 rows deleted, 2000 series written`). **The feed's link must be the VM's own LAN address** (`http://192.168.1.92:8088/…`
here): the server fetches it, and `10.0.2.2` is only the emulator's name for the host. Three words of this section don't
match the build: a `webcal://` link is https (a plain http host refuses it: "Couldn't reach the link"), a renamed music
key is replaced by a new one at start (the form says nothing; the old links' URLs are `key_lost` at their next fetch),
and a removed link's row doesn't grey - it reads "· Phone" until the phone's next report and then goes, with the
settings kept (the re-add got Sun back). The 390 px page wasn't rendered this session.

## 6aa. SMS reactions (design 81 steps 1-3)

Run on `handy36` (`-wipe-data`, `wm density 240`) on 2026-11-10, branch `sms-reactions` after merging main edeb78a6;
what was seen, and what an emulator can't show, is in `docs/review/qa-81-code.md`, "Emulator pass". The setup of §6v:
a server, the phone enrolled, SMS and calls "Styrt av Vibb", contacts with `sms_in`, `sms_out` and `mms` (the run used
Mamma `+4790000001`, Pappa `+4790000002`, Bestemor `+4790000005`), the own number `+4790000010` for groups. On
`jelly36`, or `handy36` with `adb shell wm density 240` for the Jelly Star's 320 x 569 dp (back with `wm density
reset`). The engine's store and the notifications as in §6v (`messages.db` and `-wal`, `dumpsys notification
--noredact`, `dumpsys vibrator_manager`'s "Recent vibrations"); the console's `sms send` takes the text after the number
as it is, quotes, `«»` and emoji included (quote the whole command for the shell).

- **Seed the lock-task features as a real phone gets them** (`device_policy.lock_task_features = 63`, the server's
  default): with 0 there is no NOTIFICATIONS feature, and in kiosk Android mutes every notification
  (`dumpsys notification` shows `mDisableNotificationEffects=true`). The same happens while handy's PIN lock is up
  (`featuresWhileLocked`) - so check sounds and buzzes unlocked.
- **The store's upgrade:** install main's debug build first, enroll, receive a few SMS (store v4), then this branch's
  build with `install -r`; `run-as ... cat databases/messages.db` (and `-wal`): `PRAGMA user_version` 5, the four
  `reaction_*` columns and `message_reaction`. A fresh store to compare with: kill the process from its own uid
  (`run-as me.vibb.launcher.debug kill <pid>` - a device owner can't be force-stopped), `run-as ... rm -f
  databases/messages.db*`, Home, one SMS.
- **A sync:** after a database change, kill the process as above and start Home (its start sync); while the debug
  hook's `mmsc_host`/`stand_in` are needed (memory only), an SMS from a stranger asks for a sync instead.
- **Android's notification cooldown** attenuates a buzz that follows another notification from the launcher within
  seconds: wait a minute between the posts you measure.

- **An incoming reaction attaches:** the kid sends Mamma "Kommer kl 5"; then
  `adb emu sms send +4790000001 'Likte «Kommer kl 5»'` -> the thread shows no new bubble, a chip with 👍 on the kid's
  bubble's bottom-left edge; `message` has the row with `reaction_to` = the bubble's id, `reaction_op` `ADD`; Home's
  badge and the list's last line don't change. The same with `'Liked “Kommer kl 5”'`, `'Loved "Kommer kl 5"'` (straight
  quotes), `'Satte et hjerte ved «Kommer kl 5»'` (replaces the 👍), `'Reagerte med 🎉 på «Kommer kl 5»'`, and
  `'Fjernet 🎉 fra «Kommer kl 5»'` (the chip goes, no notification).
- **An unknown form stays text:** `'Likte «filmen»'` (no such bubble), `'Reacted with a sticker to “Ok”'`,
  `'Liked “Kommer kl 5” !'` -> ordinary bubbles, notified as messages.
- **The notification:** after an add, `dumpsys notification --noredact` shows the conversation's notification on
  `launcher:messages_reactions` with the line "Reagerte med 👍 på «Kommer kl 5»" and public "Ny reaksjon"; the
  emulator's vibrator (`dumpsys vibrator_manager`) one 40 ms effect (80 ms until 2026-11-10), no sound - with the phone unlocked (above). Under a time rule without calls, and with
  Do Not Disturb on (`adb shell cmd notification set_dnd priority`, back with `off`), the same post goes on
  `launcher:messages_silent`, no vibration. No wake on the lock either way.
- **The long press:** on Mamma's bubble the panel - two rows of four 56 dp cells (❤️ 👍 👎 😂 / ‼️ ❓ + and the kid's
  other reaction), "Fjern reaksjonene" once Mamma reacted to it, "Slett" - fits at 288 dp; a tap on the thread closes
  it; TalkBack's actions list "Reager med ❤️" ... "Slett". On the kid's own bubble, or under a time rule, only
  "Fjern reaksjonene" / "Slett", or the delete confirm at once.
- **Sending:** tap 👍 -> the chip shows dimmed at once, the stored `OUT` row's body is exactly `Likte «Hei»` (nb
  launcher) or `Liked “Hei”` (en), `SENT` -> full colour. "+" -> the picker: "Velg en reaksjon", the group strip, five
  columns, no skin tones, no empty boxes; pick 🎉 -> `Reagerte med 🎉 på «Hei»`, and 🎉 leads "Nylig brukt" next time
  - every opening starts at the top, whatever group the last one scrolled to (the run's one fix).
- **MMS:** with `scripts/fake-mmsc.py` (§6v step 3b) a group text reaction (`Likte «…»` to the kid and Bestemor)
  attaches in the group's thread when its text arrives; a picture plus the same text stays a picture message. While
  an MMS waits (mobile data off), `'Likte et bilde'` by SMS stays a plain message, whatever the MMS turns out to be (a
  text, a reaction, dropped); once a picture is fetched, the same words attach to it (code QA M1).
- **The store's SQL** (no JVM test opens SQLite; `docs/review/qa-81-code.md`, "What the emulator pass must check"):
  a v4 install (design 80's) with messages upgraded to v5, and a fresh one - `PRAGMA user_version` 5, the same
  `.schema message`, `message_reaction` present; with reaction rows present, `unread()` and `lastMessage()` ignore
  them (Home's badge, the list's dot and last line).

Screenshots: the chip, the panel and the picker at 288 dp.

## 6ac. A thread's "Ny melding" mark (2026-11-10)

Run on `handy36` on its kept data (no `-wipe-data`, `wm density 240`, `install -r`) on 2026-11-10, branch
`thread-new-mark` (d1fc6b65). No server was up - the phone ran on its stored policy: managed SMS, Mamma
(`+4790000001`, a thread of 250 messages at the start) and Pappa (`+4790000002`). Measured in `uiautomator dump`
(1.5 px per dp): the list's frame is `[24,226][456,719]`, the compose bar `[24,725][456,803]`, and "at the end" is the
last row's bottom at 702. A slow `input swipe` at x=400 scrolls without a fling; the store is read with `run-as ...
cat databases/messages.db` (`message.is_read`).

- **The mark comes and the rows stay:** scrolled up, one SMS in: `thread_new`, a Button "Ny melding",
  `[161,647][318,719]` - 104.7 x 48 dp, its centre at x 239.5 of the frame's 240, its bottom the list's bottom, 6 px
  over the compose bar; `is_read` 0. A second: "Nye meldinger", `[146,647][333,719]`. Every visible row's bounds are
  the same before and after both. In en (`cmd locale set-app-locales me.vibb.launcher.debug --locales en`, for one
  thread visit) "New message" `[149,647][331,719]` and "New messages" `[144,647][336,719]`.
- **The tap, and the end by hand:** a tap on the mark: the last bubble's bottom at 702, the mark gone, unread 0. With
  Home between (the tile "Meldinger, 2 uleste", the notification posted): the thread comes back scrolled up with
  "Nye meldinger" and its rows where they were; the tap reads it, the notification is gone and the tile reads
  "Meldinger". Recents and back (`KEYCODE_APP_SWITCH` twice) keeps the mark and the rows too. A slow drag to the end
  does what the tap does. A tall last bubble is whole after the tap: a five-line text `[24,454][369,702]`, and a
  picture from the fake MMSC with its text `[24,347][369,702]` (237 dp) - on the kept data the push's location needs
  `?to=%2B4790000020`, the phone's own number there; the default `To` is blocked as `group`.
- **The keyboard, the font scale:** with the keyboard up the frame is `[24,226][456,330]` and the mark
  `[161,258][318,330]`, over the compose bar at 336; a tap goes to the end, the keyboard stays. Opening and closing
  the keyboard keeps the mark and the rows. At font scale 1.3 "Ny melding" is `[142,639][338,719]` (130.7 x 53.3 dp)
  and "Nye meldinger" `[121,639][358,719]`, centred, the rows unmoved.
- **No mark:** the kid's own send with the mark showing (the thread goes to the end and is read); the delivery report
  of that send arriving after the kid scrolled up again; Mamma's `'Likte «…»'` for a bubble on screen and for one above
  it - a reaction is no row of its own (a `message` row with `reaction_to`, never counted unread), the bubble's chip
  and "Reaksjoner: 👍 fra Mamma" are all that change; an SMS in Pappa's thread while Mamma's shows "Ny melding" (it
  stays "Ny melding", Pappa's gets its notification); a message followed at the end and the kid scrolling up 3 s, 1 s,
  0.5 s, 0.2 s and at once after it (five of five: no mark, read).
- **A fling to the end and straight back up:** with the drag back about 200 ms after the fling began (one `adb
  shell`, no pause) the end wasn't reached in 8 of 10 - the mark stayed and the message stayed unread - and was in 2:
  read, no mark. With 0.05-0.5 s between them: 14 of 14 read, no mark. The mark never showed over a read thread in the
  24 tries, and never stayed away over an unread one.
- **What is open for a bubble:** "Slette meldingen?" up (`messages_confirm [24,593][456,806]`, the frame ending at
  581), an SMS in: the mark is `[161,509][318,581]`, over the confirm; the tap closes the confirm, the compose bar is
  back, the thread at its end and read, and the bubble is still in the store. The same with the reaction panel
  (`messages_react` from 515, the mark `[161,431][318,503]`): the panel closes, nothing is sent. The emoji picker
  takes the list and the mark away and Back brings both back, the rows where they were, still unread; the picture
  choice in the compose bar's place leaves the mark over it (`[161,456][318,528]`).
- **A delete while the mark shows:** in the long thread a deleted bubble is filled from above - the rows under it keep
  their place - so the end doesn't come into view and the mark and the unread message stay. In a short one (Pappa's:
  three in, two of the kid's own, scrolled to its top, one more in) the first delete leaves the new message under the
  edge and the mark up; the second makes the thread fit, the new message's bottom is at 702, the mark is gone and the
  thread read.
- **Another thread opened meanwhile:** every way in from outside the list - the notification, `smsto:` - starts the
  list under the thread (`CLEAR_TOP`), so the thread is a new activity (another `ActivityRecord` in `dumpsys activity
  activities`), not `onNewIntent`. Pappa's notification tapped 0.7 s after a fling's lift in Mamma's thread (a fling
  coasts 1.9 s here), `am start -a android.intent.action.SENDTO -d smsto:...` 20 ms after a fling, and the same 1.2 s
  into a 4 s `input swipe` still held down: the other thread is at its end, without a mark and read, and its next SMS
  is followed. `onNewIntent` itself was reached only by two taps on two rows of the Meldinger list 20-30 ms apart
  (`input tap 240 400 & sleep 0.03; input tap 240 283`; two `START ... ThreadActivity` lines 70-85 ms apart, 5 of 26
  tries): the second row's thread shows, at its end, no mark, the next SMS followed.
- **TalkBack** (`settings put secure enabled_accessibility_services
  com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService`, `accessibility_enabled 1`;
  its first start in the kiosk puts "App is not available" over the screen - its tutorial is refused - and Back takes
  that away). No `uiautomator dump` while it runs - that connection suspends the other accessibility services; its
  focus is read as the green frame in a screenshot and its speech as the TTS engine's `Synthesis request` lines in
  logcat (this user build logs no text). A message followed at the end: no request. The mark coming: one request in
  nb and one in en-US 2.2-2.6 s later; "Ny melding" turning into "Nye meldinger": the same pair. The frame stays where
  it was when the mark comes. Keyboard focus moved to the mark (the field tapped, `input
  keycombination SHIFT_LEFT TAB` twice): the frame is on the mark; Enter: the thread is at its end and the frame is on
  the header's back button, 0.4 s and 3 s after - not on the last bubble and not on the mark that left.
- **Animations off:** frames taken in the same `adb shell` as the tap (`input tap 240 683; screencap ...`), compared
  in the mark's rectangle with the settled screen. At `animator_duration_scale` 1 the first frame showed the mark
  half faded over a list already at its end in 2 of 3 runs; at 0 the first frame was the settled screen in 4 of 4.
  Coming, a `screencap` takes about a second here: no frame caught the mark mid-fade at either scale.
- **A press:** held down, the pill is lighter (`#47455B` for `#34324A`) and the pixels at the target's corners and
  beside the pill are the picture and the bubble under it; let go, it goes to the end.
- **Found, not fixed:** (1) a drag that begins on the mark scrolls nothing and taps nothing: with the mark showing,
  `input swipe 240 683 240 400 1500` leaves every row's bounds and the mark as they were, the same drag at x=400
  scrolls - 105-158 dp of the list's lower edge, where a thumb may well start. (2) Over a received bubble the pill is
  the bubble's own colour with a 1 dp outline and covers part of its text or picture until the tap. (3) A reaction for
  a bubble on screen while scrolled up moves the rows over that bubble up by the chip's 18 dp (the list keeps its
  lower rows; the follow round's behaviour, not the mark's). (4) The Meldinger list's header action reads "Ny
  melding" too (a new conversation). After the pass: (1) and the focus after the mark's tap (the header's back
  button, above) were fixed on the branch (cc0e143f; `docs/notes/launcher/calls.md`, the mark) and run again the same
  day on the same AVD and data - the six "Re-check" points below.
- **Re-check, a drag from the mark:** with the mark showing, `input swipe 240 683 240 400 1500` moves every row 279 px
  (twice), the same drag at x=400 277; the mark stays, the message stays unread, the end isn't in view - no jump at
  the lift, no tap. Three rounds of 183 px up and 117 px down: from the mark 174, 174, 174 and 106, 106, 105; at x=400
  174, 173 and 106, 105; at x=60, on a bubble, 174, 172 and 105, 105. A short fling (77 px in 150 ms, six each way)
  ends 105-113 px on from the mark and 108-113 beside it. The slop is the list's own: 14, 20 and 30 px in 600 ms move
  the rows 2, 8 and 18 px from the mark and at x=400 alike and tap nothing; 10 px from the mark is a tap.
- **Re-check, the tap:** `input tap` on the mark (far up and near the end), in its target's corner (164,650) and five
  sloppy ones (`input swipe` of 3-9 px in 30-600 ms, up, down and sideways): each time the last row's bottom at 702,
  the mark gone, unread 0.
- **Re-check, the mark leaving under the finger:** 140 px and the new row from the end, one swipe from the mark of
  433 px in 3 s: a frame at 2.4 s has the list at its end and no mark, the finger still down; after the lift the
  thread is read, and the next drags at x=400 move the list (191 px for 200 down, 140 for 150 up). The finger in steps
  (`input motionevent DOWN 240 683`, then `MOVE` 20 px at a time): 380 px up the mark is gone and the thread read; 200
  px down again in the same gesture the rows follow (145 px - the end's stretch is let go first) and 60 px up again
  they move 60; the lift moves nothing, the next drag moves the list, a new SMS brings the mark and its tap goes to
  the end. A 50 ms fling from the mark about 1000 px from the end, three of three: the end is reached in the coast,
  read, no mark, and the next drag moves the list (191-193 px for 200).
- **Re-check, a tap on the mark while the list coasts:** `input tap 240 683` 20 ms after a fling's lift, in the same
  `adb shell`: after a fling up the list at x=400, after one towards the end from the mark (183 px in 50 ms from
  about 2000 px up - with no tap it stops short of the end, the mark still up, two of two) and after one up the list
  from the mark; two tries each, six of six at the end, read, no mark.
- **Re-check, the focus after the tap:** the field tapped, Back, `input keycombination SHIFT_LEFT TAB` twice: `focused`
  is on `thread_new` (the keyboard is up again, the mark `[161,258][318,330]`); Enter: `thread_field` is `focused` 0.4
  s and 3 s after, the last row's bottom at 313 (the frame ends at 330), read, no mark. With TalkBack on, the green
  frame is on the mark, and after Enter on the field `[132,342][348,408]`, 0.4 s and 3 s after - with both the
  keyboard's and TalkBack's focus on the mark the frame follows the keyboard's. A finger's tap: no view `focused`
  before or after, `mInputShown=false`; with the field focused, a keyboard that is down stays down and one that is up
  stays up, the focus on the field.
- **Re-check, long presses and the reaction panel:** the finger held 1.5 s on the mark over a wide bubble
  (`[24,583][369,719]`): no panel, the pill pressed (`#47455B`); lifted, the thread is at its end and read - a press,
  as before. A long press at (60,683), on the bubble beside the mark: the panel (`messages_react` from 515, the mark
  `[161,431][318,503]`). With the panel open a drag from the mark (100 px up) moves no row, closes nothing and taps
  nothing; the same drag at x=400 closes the panel; the mark's tap closes it and goes to the end, nothing sent (10
  sent rows before and after).
- **What `handy36` can't show:** TalkBack by touch - an injected `input tap` isn't touch exploration here (one tap on
  the mark with TalkBack on went straight to the end), so the double-tap, the words spoken and where accessibility
  focus lands without keyboard focus are the phone's to show (TalkBack's own keys - Alt+Left, Alt+Enter and the
  other keymaps' - did nothing injected with `input keycombination`); a second finger beside the one on the mark; the
  focus going to the last bubble where there is no compose bar; a thread switch through `onNewIntent` with the old list
  coasting or held (no way in keeps the activity; the two-tap way leaves it 0.1 s old); the touch sound (`-no-audio`);
  a real thumb's fling and return; the keyboard-up view (the list is 104 px, §6ab); the read-only line in the compose
  bar's place (needs a rule from a server); a portrait picture's bubble (the fake MMSC's is 160 x 120).
- Screenshots of the run are in the session's `scratchpad/mark-shots/`.

## 6ae. A message's time under the last bubble of its run (2026-11-10)

Run on `handy36` on its kept data (no `-wipe-data`, `wm density 240`, `install -r`) on 2026-11-10, branch
`bubble-time` (15f37355, without design 82; the re-check on the commit after it). No server was up - the phone ran on
its stored policy, as in §6ac: Mamma (`+4790000001`), Pappa (`+4790000002`), the list's frame `[24,226][456,719]`,
"at the end" the last row's bottom at 702 (1.5 px per dp). New messages by `adb emu sms send`; `settings put system
time_12_24 24` (it was unset). What the AVD doesn't do by itself:

- **A failed send** is airplane mode (`cmd connectivity airplane-mode enable`, the send, `disable`): the delivery is
  `FAILED` (`sms:2`). `adb emu gsm voice off` fails nothing - the message is delivered.
- **Pictures** are §6v step 3b's fake MMSC, the stand-in set by `MmsDebugReceiver` (in memory: a process death
  forgets it, and a picture sent without it fails as `mms:12`, a second way to a failed line). The kid's own:
  "+", the camera, the shutter and the tick at (240,764), send.
- **A reaction** is the contact's "Likte «text»" as an SMS: a 👍 chip on the bubble with that text, the kid's or the
  contact's.
- **A new day and an empty message** are an edit of the store, since every kept message is from one day and none is
  empty: the launcher's process killed as its own uid (`run-as me.vibb.launcher.debug sh -c 'kill -9 $(pidof ...);
  cat > databases/messages.db; rm -f databases/messages.db-wal databases/messages.db-shm'`, the edited copy on its
  stdin - `am force-stop` does not end the Device Owner's process here), four messages of one run moved to 23:58,
  23:59, 00:01 and 00:02 and one message's `body` set to `''`.

What it showed:

- **One time per run:** four received within a minute: one "14:27", under the last; three more: "16:53" under the
  third, the time of the last one. A message of the other side ends the run (its own line under it), and so does a gap
  over 5 minutes. Across midnight: "I går", two bubbles, "23:59"; "I dag", two bubbles, "00:02".
- **The measures (text size 1.0):** a one-line bubble is 61 px; inside a run the next bubble starts 10 px (6.7 dp)
  under it; the line's box starts 3 px (2 dp) under the run's last bubble and is 36 px (24 dp) high, its text at the
  top of the box, so the time stands close to its bubble; from a run's last bubble to the next run's first is 49 px
  (32.7 dp). A thread of short messages reads calmer than with a time in every bubble, and the runs stand apart as
  groups; the 24 dp under each run does not look loose at this size. Not changed: it is also the height of the failed
  line's tap.
- **The kid's own:** with the keyboard up "Sendt · 16:54" `[333,277][456,313]` becomes "Levert · 16:54"
  `[328,277][456,313]`: the same right edge and height, nothing else moves (the frames of a screen recording across
  the send too). "Sender …" is never on screen - the emulator's radio answers faster than a frame. A picture stops at
  "Sendt · 17:07" (MMS has no delivery report). The state is the newest sent message's alone: once a newer one
  exists, an older run's line is its time alone.
- **A failed send:** "Ikke sendt - trykk for å prøve igjen" in red `[161,666][456,702]`, no time; a run of four
  ending in it shows no time at all. With the radio back, a tap on the line sends it again: it is the thread's newest
  with "Levert · 17:00". A failed message with a delivered one after it keeps its red line, and the run's last has
  the state and the time.
- **A message joining a run while the thread follows:** the line leaves the bubble before, stands under the new last
  one, and the last row's bottom is at 702 before and after.
- **Reactions, pictures:** a chip `[321,629][367,663]` overlaps its bubble by 7 px and the line starts 3 px under the
  chip, on both sides ("Levert · 17:00" under the kid's, "17:00" under the contact's); the thread stays at 702 when
  the chip comes. A picture alone: the bubble `[112,235][456,663]`, the picture `[133,249][435,651]` - 14 px over and
  12 px under it, as a text bubble's text - and the line 3 px under the bubble. A received picture with a text as a
  run's last: `[24,336][369,663]`, "17:06" under it.
- **The text size:** at 1.3 the failed line is one line `[86,666][456,702]`. At 2.0 it is two lines
  `[24,602][456,702]`, set to the right, inside the row; "Levert · 17:09" `[214,651][456,702]` and, in 12 h,
  "Levert · 5:09 p.m." `[157,651][456,702]` are one line; a time alone is 51 px high.
- **The one move, measured:** the run's last bubble cut by the list's lower edge (`[24,664][137,719]`, its line under
  the edge), a message joining its run: every visible row comes down 39 px (26 dp: the line's 24 and its 2), once for
  each message, and the "Ny melding" mark comes. Scrolled further up, the last row off the screen: nothing moves.
- **The "Ny melding" mark (§6ac) with the new rows:** scrolled up, one SMS in: the mark `[161,647][318,719]`, the rows
  unmoved; its tap: the last line's bottom at 702, no mark. A drag of 120 px from the mark scrolls the thread 109 px,
  the mark stays.
- **TalkBack's tree** (`uiautomator dump --compressed`, which leaves out what accessibility ignores): a time alone is
  no node; a state's line is one node described "Levert" or "Sendt", the failed line by its whole text; every
  bubble's description has its time ("Pappa, 16:53, Hei Emma", "Du, 17:07, Bilde", the reactions after it).
- **An empty message** is a pill without content, `[24,558][66,584]` (28 x 17 dp), "16:53" under it as a run's last,
  described "Pappa, 16:53". Before, its time was the bubble's content.
- **A fault found here and fixed:** every line was `clickable="true"` in the tree, a time alone too -
  `setOnClickListener(null)` makes a view clickable, and it ran after `isClickable`. The listener is set first now.
  Re-check on the rebuilt APK: a time alone (both sides) and "Levert · 17:31" are `clickable="false"
  focusable="false"`, the failed line is `true`, and its tap still sends ("Hentes naa", "Levert · 17:31").
- **Seen beside it, the same on `main`, not changed:** a failed picture's line offers the tap to try again, but the
  retry sends the message's text alone (`MessageActions.retry`). A picture alone: nothing is sent (the row stays
  `FAILED`, no POST at the fake MMSC) and the line says "Ikke sendt" until the thread is read again. A picture with
  "Se": the tap sends "Se" as an SMS ("Levert · 17:36") and the failed picture message is gone from the store. And
  with a picture attached and the keyboard up the compose bar `[24,232][456,466]` is higher than the room over the
  keyboard (its top at 430): the list has no height, and the text field and the send button `[384,394][456,466]` are
  half under the keyboard - a tap on the button's visible half sends.
- **What `handy36` can't show:** "Sender …" on screen, and the line's width changing from it; a carrier's delivery
  report coming minutes later, and a real MMS; what TalkBack speaks (§6ac: an injected tap isn't touch exploration);
  the thumb's feel of the 26 dp move on the cut row; how the spacing reads on the phone's own panel.
- Screenshots of the run are in the session's `scratchpad/bubble-shots/`.

## 6af. The buzz for a second message, and the pause that hung on "App is not available" (2026-11-10)

Run on `handy36` on its kept data (no `-wipe-data`, `wm density 240`, `install -r`; the AVD's data copied first and put
back after) on 2026-11-10: part A and part B's count on `main` (92530147, with design 82's buzz for every message and
§6ae), the fix on branch `pause-hang`. Main's server ran on the host (port 3111) on §6ad's database copy, the PWA
through headless Chromium. The phone is in the kiosk (Home roots lock task, the app block on), managed SMS, Mamma
(`+4790000001`) and Pappa (`+4790000002`); new messages by `adb emu sms send`. Lock task is read from the system's own
protolog - `wm logging enable-text WM_DEBUG_LOCKTASK`, again after every boot: `Allowlisting`, `Allowing features`,
`removeLockedTask`, `setLockTaskMode`, `startLockTaskMode` - and from `dumpsys activity activities`
(`mLockTaskModeState`, `mLockTaskModeTasks`, `mLockTaskPackages`). After `install -r` on a fresh boot SystemUI may
answer "isn't responding" in a loop (load 20); a reboot and a wait for the load to fall under 3 ends it.

### A. The buzz for every new message (design 82 §14)

Meldinger's level "Bare navn", "Buzz for a notification" on, the wake switch on (set in the PWA for the pass), the
phone locked and dark. The emulator has no motor: a buzz is the launcher's `I/PinLock: Notice buzz` line and the
vibrator service's record of it (`dumpsys vibrator_manager`, "Recent vibrations": `me.vibb.launcher.debug`, `usage:
NOTIFICATION`, `played: Step=40ms(amplitude=1.00)`); a wake is `Notice wake` and `mWakefulness`.

- **A gained row, as before:** the first SMS from Mamma: `Notice buzz` 18:07:21.892, `Notice wake (proximity=false)`
  18:07:21.906, Asleep to Awake, the row on the glance.
- **A second message in the same thread, more than 10 s later:** 27 s after the first: `Notice buzz` 18:07:48.974 and
  no `Notice wake`. Again with the screen off first (off at 18:08:06, the SMS at 18:08:14): `Notice buzz`
  18:08:14.479, `mWakefulness=Asleep` before it and 4 s after it - the buzz alone, nothing woken.
- **Two more within 10 s** (after each of the two above): no line, no vibration.
- **The level Off** (saved in the PWA, the policy on the phone at 18:08:33), an SMS at 18:08:43: no buzz, no wake.
- **The vibrator service's whole record for the pass:** four NOTIFICATION entries, all the launcher's, 40 ms each, at
  18:06:16.006 (a gained row from Pappa while the wake switch was still off), 18:07:21.892, 18:07:48.974 and
  18:08:14.479 - one for one with the log lines, none for the messages inside the gap or at Off.
- **Seen beside it:** a message 116 s after the launcher's process started got no buzz and no wake - the first two
  minutes of a process are silent for both (`NoticeBusy`), so wait them out after an `install -r`. After a notice
  wake the emulator's screen stayed on, so "not woken" is the second case above, with the screen put out first.
- **What `handy36` can't show:** the buzz in the hand, whether the notification and the buzz feel like one, Android's
  own vibration settings on the phone, another app's second message (Element X's).

### B. "Pause all restrictions" and "App is not available"

One start: from Home in the kiosk (`mLockTaskModeState=LOCKED`), the drawer, Settings behind the Parent PIN, "Sett alle
begrensninger på pause", the Parent PIN again, 7 s, then the logcat and `dumpsys activity activities` kept. Ended the
way a parent would, with Home's "Lås igjen".

- **On main: 1 stuck of 10** (the second; an eleventh went through). The stuck one sat on Android's "App is not
  available" for Vibb One, `mLockTaskModeState=LOCKED`, `mLockTaskPackages` empty, the only locked task
  BlockedAppActivity's own (`type=home A=1000:android`) - §6ad's picture, on a quiet emulator this time.
- **The cause, from the protolog.** `setLockTaskPackages(admin, [])` is one call on one system thread: `Allowlisting
  0:[]`, then Home's task is taken out of lock task (`removeLockedTask ... last task, reverting locktask mode`) and
  cleared, and the system starts Home again inside the same call - and only after that the features are reset
  (`Allowing features 0:0x10`). The end of lock-task mode is only posted to a handler. If Home's start is checked
  before that handler ran, lock task still reads LOCKED with the app block (bit 64 of the kiosk's `0x7f`) and an empty
  list: the start is swapped for `com.android.internal.app.BlockedAppActivity`, whose own start then locks the phone
  to its task. The stuck start: `Allowlisting 0:[]` 18:12:58.406, the revert .407, Home's start .408, the
  BlockedAppActivity intent .410, `Allowing features 0:0x10` .414, `setLockTaskMode: Locking to Task{... type=home
  A=1000:android}` .620. In the ten clean starts the revert and Home's start were 2 to 31 ms apart - a race between
  two system threads, not something the load decides alone. (§6ad's `Attempted Lock Task Mode violation` lines are
  the launcher's own Home starts refused afterwards; none was tried in this start.)
- **What was guessed and what it is:** the guess - the launcher's `reapply` empties the list while lock task is
  LOCKED, Home's restart is refused as a violation, and only Home's `reconcileKioskMode()` would stop lock task - is
  right about the list emptied under LOCKED and about the blocked screen ending up the only locked task. It is not a
  refused start of the launcher's and it does not wait for `reconcileKioskMode()`: it is the system's own Home
  restart inside `setLockTaskPackages`, swapped by the app block before the posted end ran. `AppEnforcer` can't end
  lock task first itself (`stopLockTask()` is an activity's, and Settings is on top).
- **Out of it without a reboot:** the screen stays until its OK is tapped. OK finished BlockedAppActivity, lock task
  went to NONE, the system started Home, the pause was running, and "Lås igjen" put the kiosk back (LOCKED). The Home
  key does nothing while it is up (§6ad). Seen once, on the emulator.
- **Unlocking the PIN lock with the kiosk off** has no such race: the lock's task finishes and ends lock task before
  the list is emptied.
- **The fix, part 1** (`LockTaskChrome.writeLockTask`, pure `unpinFeatures`): before the list is emptied the features
  are written without the app block, in a write of their own; a failed emptying writes them back. Twelve starts on
  that build: none stuck, `Allowing features 0:0x3f` before `Allowlisting 0:[]` in every one, no BlockedAppActivity
  line.
- **A second race, seen at the twelfth of those:** the Home the system restarted resumed while the emptying call was
  still running, read the stored kiosk switch - still on, it was written after the call - and asked for lock task
  with nothing allow-listed: `startLockTaskMode: Task{... type=home ...}`, `Mode default, asking user` - Android's
  screen-pinning request, in the middle of a pause (the emptying 18:45:37.341, Home's start .353, the request .399).
  The pass's next tap accepted it: `mLockTaskModeState=PINNED`, the list empty, the pause still running. Back and
  "Lås igjen" from there put the kiosk back (LOCKED, `0x7f`, the list). This path is main's too; it was 1 of 23
  starts in this pass.
- **The fix, part 2:** the stored switch goes off before the list is emptied too (and back on if the emptying
  fails). Home, still alive under Settings, hears the switch change and stops lock task itself when it gets there
  first - then nothing is cleared and Settings stays in front; when the emptying gets there first Home is restarted
  as before, and reads the switch off.
- **Twenty starts on the fixed build: none stuck, no pinning request.** `Allowing features 0:0x3f` before
  `Allowlisting 0:[]` in all twenty, no BlockedAppActivity, no `startLockTaskMode`, `mLockTaskModeState=NONE` after
  each. Twelve went Home's way (`stopLockTaskMode` first, Settings left on top, Back to Home), eight the emptying's
  (Home restarted in a new task). Every end: `Allowing features 0:0x7f` before the list, `startLockTaskMode ...
  Locking fully`, LOCKED on Home's task, the launcher in the list. Three more started and ended from the PWA ("Unlock
  for 2 h", "Lock again") with Home in front: the same, Home's way each time.
- **The count is not the proof:** at 1 in 10 before, twenty clean starts alone would happen by luck about one time in
  eight. The proof is the order in the protolog - without the block bit a start is never swapped, and with the switch
  off Home never asks for lock task.
- **Seen beside it:** after a pause's end the allow-list is written three times within about two seconds (15, 14,
  then 13 packages): `com.google.android.documentsui` and `android` are in the first writes and leave as the apps
  are suspended again and the helper intents resolve to one handler. The same before the fix.
- **What `handy36` can't show:** how often either race comes on the Jelly Star, whether its build resets the
  features after the packages the same way, and whether OK gets a real phone out of the blocked screen.

## 6ag. A failed picture's retry, and the compose bar with a picture over the keyboard (2026-11-10)

Run on **`jelly36`** (480 x 854, 240 dpi, a 36 px status bar, the gesture bar `[0,818][480,854]`) on 2026-11-10, the
debug launcher of branch `retry-picture` at its merge of main (main cc1396b4 with §6af, plus the retry and the compose
bar's layout). The AVD had never been started, so the pass made its phone from nothing and put the AVD's directory
back after: `-timezone Europe/Oslo`, the launcher made Device Owner as in §4, the server's address
(`http://10.0.2.2:3111`) and "Enroll with code" in the launcher's Settings, main's server on the host on a copy of
§6ad's database (managed SMS, Mamma `+4790000001`, Pappa `+4790000002`, the kid's code and the Parent PIN its own),
the PWA through headless Chromium. Pictures are §6v step 3b's fake MMSC and `MmsDebugReceiver`'s `mmsc_host` and
`stand_in`; the kid's own picture is "+", "Ta bilde", the shutter and the tick, both at (240,764). Gboard's top with
the keyboard up is y = 429 (`dumpsys window`, `type=ime frame=[0,429][480,854]`), so the room under the header is
`thread_body` `[24,126][456,429]`, 303 px; with it down `[24,126][456,818]`.

What the AVD doesn't do by itself, beyond §6ae's list:

- **`adb shell input text` types ASCII only** ("å" throws a `NullPointerException` in `sendText`) and raises the
  keyboard; a Delete key event with the keyboard down does not raise it, and a Back with it down leaves the thread
  (the draft and its picture are kept). `input keycombination 113 29` and a Delete empty the field. The dumped *text*
  of the field can lag while it is typed; the bounds don't - count the lines on a screenshot.
- **`adb install -r` restarts the launcher:** the PIN lock comes up (a swipe on the glance, then the kid's code) and
  the debug hook's MMS settings are gone.
- **A contact's switches from the PWA:** "Bilder i meldinger" is the contact form's `mms`, "Meldinger" its `texts`
  (the one-way `sms_out` sits in a closed "Avansert"); the phone had the change within 12 s of the save.
- **`uiautomator dump` to `/data/local/tmp`**, and the dump read before any tap: `/sdcard/ui.xml` can't be removed by
  the shell on these images, and an old dump answers for a new screen.

### A. "Prøv igjen" on a failed picture

A failed picture is one sent with no stand-in set (`FAILED`, `mms:12`); the retry is the tap on its line with the
stand-in on. Read from the fake MMSC's kept POSTs (the JPEG inside and its SHA-256 against the `part` row's) and the
launcher's `messages.db`.

- **A picture with "Se hva jeg fant":** the tap: one POST, with the JPEG (19 067 bytes, the part's hash) and the
  text, to Mamma. The failed row is gone and one new row is there, `SENT`, its client id `retry:<the failed id>`.
- **Two taps 80 ms apart** (two `input tap` from one shell; the gap between the injected events was not measured):
  one POST, one new row.
- **A picture alone:** one POST with the JPEG and no text; the new row's body is empty.
- **"Bilder i meldinger" switched off for Mamma between the failure and the tap** (the stand-in on, so a send would
  have gone): the "+" leaves the compose bar; the tap: no POST, the row still `FAILED` with its picture, the toast
  "Dette bildet kan ikke sendes". After that refused tap the line says "Ikke sendt" without the offer until the
  thread is read again (reopened: the offer is back - texts are still allowed; `retryOffered` asks the text gate
  alone, not changed here). Pictures on again, the tap: the POST with the JPEG and the text, the row `SENT`.
- **A failed text alone** (airplane mode, `sms:2`; airplane mode off): the tap sends it as before, "Levert · 20:56",
  nothing at the MMSC.
- Four POSTs in the whole pass, one for each retry that should send; none for the refused tap.

### B. The compose bar, measured

`uiautomator` bounds in px (1.5 px per dp), Mamma's thread, the text typed with `input text`. The send button is
`[384,342][456,414]` with the keyboard up and `[384,731][456,803]` with it down in every row; the bar's bottom is 414
and 803.

| Picture, lines | Keyboard up: field | preview | list | Keyboard down: field | preview | list |
|---|---|---|---|---|---|---|
| yes, 1 | `[132,342][348,408]` | 144 x 144 | 48 | `[132,731][348,797]` | 144 x 144 | 437 |
| yes, 2 | `[132,341][348,408]` | 144 x 144 | 47 | `[132,730][348,797]` | 144 x 144 | 436 |
| yes, 3 | `[132,309][348,408]` | 144 x 144 | 15 | `[132,698][348,797]` | 144 x 144 | 404 |
| yes, 4 | `[132,277][348,408]` | 144 x 127 | 0 | `[132,666][348,797]` | 144 x 144 | 372 |
| yes, 5 | `[132,245][348,408]` | 144 x 95 | 0 | `[132,634][348,797]` | 144 x 144 | 340 |
| no, 5 | `[132,245][348,408]` | - | 107 | `[132,634][348,797]` | - | 496 |

- **Holds in every row:** the field's bottom (408) and the send button's (414) are above the keyboard's top (429)
  with one to five lines; the preview is 96 x 96 dp with up to three lines and gives way with four and five (its
  picture cropped to the row, the cross whole), 1 px lower than computed; with the keyboard down it is 96 x 96 dp
  throughout.
- **Main's build, the same phone, a picture and five lines, keyboard up:** the bar `[24,132][456,463]`, the field
  down to 457 and the send button `[384,391][456,463]` - 34 px under the keyboard, the list without height.
- **The other panels in `thread_body`**, each opened on this build and on main's (`install -r` of the main checkout's
  debug APK, built before this branch's layout: no `thread_body` in its resources), the same thread at its end:

  | Panel | Bounds, both builds | Pixels under the status bar |
  |---|---|---|
  | The line ("Du kan ikke svare her", Mamma's texts off) | `thread_line` `[24,728][456,800]`, the list to 722 | the same from the line down |
  | The picture choice | `[24,540][456,806]`, the list to 528 | the same from the panel down (the list had one message more) |
  | The react panel | `[24,515][456,806]`, the list to 503 | identical |
  | The delete confirm | `[24,593][456,806]`, the list to 581 | identical |
  | The emoji picker | `[24,132][456,806]` | identical |

### C. The Settings gate's Parent PIN dialog at this height

`am start` of the launcher's `SettingsActivity`, the phone in Norwegian. The title "Skriv inn koden for å åpne
innstillingene" is two lines.

- **No code set** (the PIN's hash and salt emptied in the pass's database copy, a PWA save for the sync; put back
  after): the dialog `[24,67][456,787]`, the reason "Innstillingene er låst. Sett en opplåsingskode for denne
  telefonen på serveren for å åpne dem." in three lines `[45,605][435,691]`, whole, the keys and OK dimmed, "Avbryt"
  and OK `[222,709]…[438,781]` whole.
- **A wrong code** (six digits): the dialog `[24,94][456,759]`, "Feil kode. Prøv igjen." one line
  `[45,632][435,663]`, whole. Five digits: "Skriv inn minst 6 sifre.", one line in the same place.
- Not raised here: the longer lines (one try left, the lockout, an app with foreign access).

### What `jelly36` can't show

The Jelly Star's own keyboard and its height (the table is Gboard's 425 px on this image); a carrier's MMSC and a
real refusal; two taps of a finger rather than two injected ones; a display size or font scale other than the
default. `handy36`'s rows of the compose bar stay computed (`docs/notes/launcher/calls.md`). Screenshots of the run
are in the session's `scratchpad/retry-shots/`.

## 6at. A HOME before the first unlock without an Android PIN (compat-fix-1, QA H1, 2026-11-16)

**Design 132: "own mode" is gone; its rows below are now the case of a managed phone without a Child PIN on Android
(no Child PIN yet, or `no_android_pin`) - G5 of design 132 §11. The PWA's lock switch (step 2's POST) is gone (404);
Android's lock is every phone's. The log lines keep their words (`Boot cover own mode ...`).**

Device 1 of the 14-17 rig, the `boot_cover` switch off, apps managed (Settings `hidden=true`). Watch
`getprop sys.boot_completed`, `dumpsys user` (`State:`) and `cmd package resolve-activity --brief -a
android.intent.action.MAIN -c android.intent.category.HOME`.

1. **Before the fix, `compat34` (API 34) in own mode**: `adb emu kill` + a cold boot, `adb reboot` and `adb shell svc
   power reboot` (with `ACTION_SHUTDOWN`) all hung - no `sys.boot_completed` after 150 s, user 0 BOOTING, a black
   screen, `cmd package query-activities ... HOME --user 0`: "No activities found". In the pause (Settings unhidden)
   `adb reboot` booted in 15 s. Shell can't rescue it (`pm unhide` needs MANAGE_USERS): wipe and provision again.
2. **Android mode** (PWA `POST /devices/1/kid-lock/keyguard keyguard=android` - gone with design 132), 34: `adb reboot` boots in 15 s,
   `BootCoverActivity` on top, RUNNING_LOCKED until the PIN (step M).
3. **`jelly36` (API 36) in own mode, before the fix**: `adb reboot` boots - its Pixel launcher's HOME runs before the
   unlock (it was displayed at boot).
4. **The fix**: 34, a fresh phone in own mode: `BootCover: Boot cover own mode BARE: before the unlock HOME has only the
   cover`; `adb reboot`, `svc power reboot` and `adb reboot` again each boot in about 30 s, the cover displayed once
   (bare), HOME then `HomeActivity`, no HomePin re-seat. 36: `Boot cover own mode NONE: before the unlock HOME also
   resolves to com.google.android.apps.nexuslauncher/...NexusLauncherActivity`, the cover disabled; both reboots boot,
   no `ResolverActivity` (with the cover enabled as well, every boot showed the system's chooser).
5. Known, not fixed (android mode, 34 only): a phone switched from android mode kept the cover's PPA in the policy
   engine, and HOME went on resolving to the cover after re-seats on 14 until a reboot; a fresh own-mode phone never.

## 6aw. Two badges and Siste (design 106, branch `a5-b2`, 2026-11-16)

`docs/notes/launcher/calls.md`, "Two badges and Siste". The rig: the server 0.81.0 debug build on port 3100 with a copy
of §6av's data, its contacts edited for the gate - Mamma (Home, Melding Element X `@mamma:example.org`, SMS in and out),
Pappa (Home, SMS in and out), Emma, Jack, Lily and Ambulanse 113 -, SMS "Styrt av Vibb"; the branch's debug launcher as
Device Owner (§4's adb lines), enrolled with a typed code, kiosk LOCKED, Telefonbok and SMS roles ours. Calls with `adb
emu gsm call`/`cancel` (the emulator's modem ends an outgoing call at once), SMS with `adb emu sms send`; the tab read
from the `RadioButton`s' `checked` in `uiautomator dump`; rules turned on now with `POST /devices/1/rules-now` and ended
with `POST /devices/1/lock-now/<id>/end`; calls off with `POST /devices/1/calls/settings section=channels
calls_mode=off sms_mode=managed`. The missed Element X call from the debug-only `ElementCallDebugReceiver`:
`adb shell am broadcast -n $P/com.kidslauncher.mdm.calls.ElementCallDebugReceiver --es mxid @mamma:example.org --es key k2`
(then `--es remove k2`; quote a key with `|` for the device's shell). Screenshots kept in the session's scratchpad.

**Android 16 (`jelly36`, restored clean before and after: 22 of 22 sums, the same file list):**

| Step | Result |
|---|---|
| 1. A missed call from Pappa | **Passed.** Home: Pappa's rust pill "1" at the top end with the phone-x glyph; no green badge on any contact; our notification "Tapt anrop fra Pappa" |
| 2. SMS | **Passed.** From Mamma: the teal pill "1" at her bottom end, the rust pill unchanged. The kid's SMS to Pappa (through Meldinger's "Ny melding", not his sheet, which would mark him seen): nothing changed |
| 3. The tile | **Passed.** Kontakter chosen, the Siste badge "1" inline after the word (its description "Siste, 1 tapt anrop"); Pappa's row with the rust pill and its glyph, Mamma's with the teal one; the notification gone |
| 4. Calls for Siste | Ring Emma from her row (OUTGOING), Mamma answered (INCOMING), Pappa twice cancelled (MISSED x2), Jack declined on the call screen (REJECTED, type 5), an unknown number (BLOCKED, type 6) |
| 5. Siste | **Passed.** Under "I dag": Pappa "2 tapte anrop" (red, one row), Mamma "Ringte deg", Emma "Du ringte", Pappa "Tapt anrop" (step 1, a row of its own); no Jack, no unknown. TalkBack: "Pappa, 2 tapte anrop, i dag 7:07", "Mamma, ringte deg, i dag 7:06", "Emma, du ringte, i dag 7:06". Mørk and Lys, font scale 1.0 and 2.0: the labels wrap ("Kontak-ter" at 2.0), the time under the word at 2.0 |
| 6. Seen | **Passed** (on fresh calls: step 4's had been marked seen by Kontakter's own rule, the book in front after the calls). Two missed calls from Pappa with Home in front; the tile, Siste within a second, 2.5 s on Siste, Kontakter: the Siste badge gone, Pappa's rust pill gone in his row, his row still first without its red line; Back: Home without his pill |
| 7. A Siste row | **Passed.** Mamma's missed row opened her sheet, no call placed (no new OUTGOING row); the sheet's rust pill at the top end, the teal pill at the bottom end |
| 8. Always Kontakter | **Passed:** Home then the tile; Home then Recents' card; Melding (Pappa's own thread, Meldinger's task) then Back twice; a font-size change kept Siste (and its rows); the process killed while Home was in front, then Recents' card; with the book on Siste, the missed-call notification's own intent (`VIEW` calls with Pappa as `EXTRA_MISSED_CONTACT`, to the entry) - Kontakter with Pappa's sheet - and a `tel:` link - Kontakter under "Ringe Jack?"; Back on Siste -> Kontakter, Back again -> Home. **Notes:** Recents' overview opened from the book itself keeps the page resumed (Android's live tile), so tapping its card again is no new visit and Siste stays - design 79's visit boundary; `am kill` can't kill the process while Home (ours) is in front (procState 2), so it was killed with `run-as <pkg> kill -9` (a low-memory kill's SIGKILL, not a force-stop: the alarms stayed); the notification itself can't be tapped while the book is in front (the call's own screen ends the visit and the resume clears it), hence its intent |
| 9. Element X | **Passed.** Posted: Mamma's rust pill on Home, Siste "Tapt anrop i io.element.android.x" (Element X isn't installed, so the shown label is the package name - the sheet's line says the same); removed: the pill gone, the row stays; the record `element_call_log` holds the number, the time, a 128-bit hash and `o:false` - no room, no MXID |
| 10. Time rules, calls off | **Passed.** Under Lekser (no calls) the call log's `VIEW` to the entry lands on the time-rule screen; under Kveld (calls) its "Telefonbok" row opens the book and Siste works; with Siste shown, calls off from the server: the book moved to Kontakter with only Ambulanse, "Samtaler er slått av ...", no switch |

**Android 14 (`compat34`, API 34), steps 1-3, 5, 6 and 8:** started with `-wipe-data -dns-server 8.8.8.8` under both
locks, the same server data and the build with TalkBack's lower-cased missed word; the first `dpm set-device-owner` right
after boot failed and passed when repeated. The rig's device had a daily budget of 0 (`[0,0,0,0,0,0,0]`), which on 14
showed "Screen time is used up" over Home: cleared on the PWA's screen-time page for the device (all days blank) before
the run. The phone in English, the launcher in Norwegian (the server's language). **All passed**, as on 16: Pappa's rust
pill and our notification; Mamma's teal pill, the kid's own SMS changing nothing; Kontakter with "Siste 1", both pills on
the rows, the notification gone; Siste under "I dag" - Pappa "2 tapte anrop", Mamma "Ringte deg", Emma "Du ringte", Pappa
"Tapt anrop" (TalkBack "Pappa, tapt anrop, i dag 10:05"), no Jack (REJECTED) and no unknown number (BLOCKED); 2.5 s on
Siste, then Kontakter: the badge and Pappa's pill gone, his row first without its red line, Home without his pill after
Back; Kontakter after Home and the tile, after Home and Recents' card, after Melding (his own thread) and Back twice,
after a SIGKILL (`am kill` left the process, as on 16) and Recents' card (the alarms kept); Siste kept through font scale
1.3 and back; the notification's intent and a `tel:` link moved Siste to Kontakter (Pappa's sheet; "Ringe Jack?"); Back
on Siste -> Kontakter -> Home. `compat34` stopped with `adb emu kill`; start it with `-wipe-data` next time.

**After the code QA** (`jelly36` restored clean before and after, the fixes' build; the re-gate of what changed):
- **M1, passed.** With the book left on Siste, Home in front, two callers missed (Jack, Emma): the shade's "2 tapte
  anrop" tapped - the book on Kontakter. With the book resumed on Siste: the several-callers notification's intent (the
  entry, `VIEW` calls, no contact) and a plain system call-log `VIEW` - each moved it to Kontakter. As in the first run,
  the notification itself can't be in the shade while the book is resumed (the call screens end the visit and the
  book's return clears it), hence the intents.
- **L2, passed.** On Siste with Emma's missed row, `POST /devices/1/contacts/5/remove`: within the sync Emma's row left
  Siste, the book stayed on Siste with the other rows.

## 6bn. A Wi-Fi fix carries what confirmed it (design 131, branch `wifi-confirm`) - run 2026-11-25 (branch `gate-131`)

`docs/design/131-wifi-fix-check.md`: on **Android 14 and 16** with the user's OK (17 is a device row). The debug
launcher, Regularly, a zone Home (150 m) on the phone, Google's location accuracy off (the `network` provider off),
Location on. The emulator hears no other network and its own is open, so the debug build's receiver hands the places a
made-up scan and connection:
`adb shell am broadcast -n me.vibb.launcher.debug/com.kidslauncher.mdm.places.PlacesDebugReceiver --es aps
"<bssid>,<ssid>,<MHz>,<dBm>;..." [--es connect "<bssid>,<ssid>,psk|sae|eap|open,<networkId>,<MHz>"]`, `--ez clear
true` to end it. **Satellite fixes: not `adb emu geo fix`** - the emulator's GNSS marks no satellite as used in a fix,
so every `geo fix` arrives with `satellites=0` in its extras whatever its count (both images, 2026-11-25), and none
teaches. The same receiver feeds `gps` as a test provider instead: `adb shell appops set me.vibb.launcher.debug
android:mock_location allow` once, then `--es gps "<lat>,<lng>,<accuracy>,<satellites>[,<seconds>]"` (a fix a second
for the seconds given, 20; the satellites in the extras), `--ez gps_off true` to remove it. With no feed running the
provider is silent - "GPS silent" below; after a reboot the first feed adds it again (until then the emulator's own GNSS
answers, at its default place). Debug fixes are mock, which a debug build accepts.
Teach first: the satellite feed at home on three occasions (30 min apart) over 2 local days with the made-up home scan
(three networks, 2.4 and 5 GHz) and the home connection (`psk`, id 7) until `WifiPlaces: taught` and the link holds (3
occasions on 2 local days). Logs: `adb logcat -s WifiPlaces ZoneWatch PlacesDebug`.

Setup notes from the run: a network's **device** is its BSSID's first five octets, so made-up neighbours need their own
(`00:00:5e:00:53:01`/`:02` Hjemme 5 and 2.4 GHz, `00:00:5e:00:54:03` Nabo, `00:00:5e:00:55:04` Naboen) - with one
prefix every AP is one device of one network and no fix comes. The Google APIs image has Google's `network` provider:
`adb shell cmd location providers add-test-provider network` and `set-test-provider-enabled network false` (again after
every boot); the AOSP image has none. A second local day without waiting for midnight: `adb shell cmd
time_zone_detector suggest_telephony_time_zone --slot_index 0 --zone_id America/Adak --quality single --match_type
test_network` (the launcher keeps automatic time zone on; UTC-10, the day before). Vibb's Wi-Fi page only lists what a
scan shows: as root, `adb shell cmd wifi start-faking-scans` and `add-fake-scan <ssid> <bssid> "[WPA2-PSK-CCMP][ESS]"
<MHz> <dBm>`. The saved networks and their ids: `adb shell cmd wifi list-networks` (`dumpsys wifi | grep -i networkid`
shows only the connected network's, `mLastNetworkId`). Locate stands in for the heartbeat's `FIX` (the same attempt and
keep rules; with zones the served interval is 30 min). The lockdown is `adb shell settings get global
wifi_device_owner_configs_lockdown` (`dumpsys device_policy` shows no line for it).

| # | Step | Expect |
|---|---|---|
| a | Home's scan, a `geo fix` 3 km away (8 satellites), a heartbeat `FIX` after a move | `satellites overruled a Wi-Fi fix`; the report's fix is `gps`; `zone_watch.wifi_disagreed` up by one (`run-as ... cat shared_prefs/zone_watch.xml`) - once, even with `learned contradiction` in the same attempt |
| b | Still (no motion), the last fresh fix at home by GPS; then home's scan replaced by another place's taught scan 1 km off, a 4-h cap `FIX` | `wifi fix check=none step=REFUSE`, then the GPS stream (`gps fixes=`); no Wi-Fi fix reported |
| c | No `geo fix` (GPS silent), home's scan, the connection `open` | the report's fix `vibb_wifi` without `check`; the PWA's Locate row and popup say "Wi-Fi estimate" (nb "Wi-Fi-anslag"), no "At Home"; no "arrived"; "Here now" lapses after two intervals |
| d | Twice (a) within an hour, then a third the next day | Security log: one "Wi-Fi and GPS disagreed" line ("1 time (1 in all)"), the next a day later with the rises summed; no Attention row, no orange line on Locate |
| e | The walk home: `geo fix` inside Home, then (GPS silent) home's scan and the home connection (`psk`, 7) 3 min later | the second fix `check: "link"` (Locate: no word, "At Home"); the PWA's inbox "arrived Home" at the satellite fix's time |
| f | A parent `locate` on the home connection; then on `open` | the first stops GPS after ~20 s (`locate` answered within ~25 s); the second runs GPS up to 60 s |
| g | Kid Settings › Wi-Fi: save and forget a network; design 102's sign-in window on a captive network; Android's own Wi-Fi settings (`am start -a android.settings.WIFI_SETTINGS`) on a network Vibb saved | saving and forgetting work on Vibb's page; the sign-in window works (§6ap); Android's screen can't modify or forget Vibb's network (`settings get global wifi_device_owner_configs_lockdown`: 1), and with the pause on it can |
| h | The home link held: forget an older saved network on Vibb's page, a teach at home (the count kept), `adb reboot`, the home connection with the id `adb shell cmd wifi list-networks` now shows, a teach at home | the link survives (the same id) or rebinds on that first teach after the boot (exactly one saved network of that name, the count unchanged): the next Wi-Fi fix at home `check: "link"`; the id before and after noted in the run's log |
| i | The home connection with the same id negotiated as WPA3 (`sae`, 7) | still `check: "link"` |

Not on the emulator: real neighbours, real bands, a real mesh - the owner's device check on the Jelly Star, later.

### The run of 2026-11-25 (00:00-04:40 UTC)

Android 14 (`compat34`, Google APIs, `-wipe-data`) and Android 16 (`aosp36`, AOSP, `-wipe-data`), one at a time, both
enrolled on one debug server of main `684fbfbae` with `emulator_keys` (§6bm), Kid14 and Kid16, each with Home (59.9139,
10.7522, 150 m) and Regularly every 10 min (served 30 min with a zone). The launcher of this branch (debug, with the
satellite feed above and the two fixes below). Link taught on both: 3 occasions 30 min apart, a local day apart by the
time zone. Locate stood in for the heartbeat (the same attempt and keep rules).

| Row | Android 14 | Android 16 |
|---|---|---|
| a | **Pass.** Home's trusted scan, no connection; Locate, then the feed 3 km north 2 s later: `wifi fix check=gnss step=USE`, `satellites overruled a Wi-Fi fix`, the report's fix `gps`, `wifi_disagreed` 3 -> 4 (`Wi-Fi and satellites disagreed (3 in all)`); the fence's crossing attempt right after: `step=REFUSE` and `learned contradiction` in one attempt, counted once (4). With the feed started *before* the attempt the passive fix arrives first and the jump gate refuses the Wi-Fi fix instead (`step=REFUSE`, counted) - the order matters on the emulator | **Pass.** The same order: `check=none step=USE`, `satellites overruled a Wi-Fi fix`, `gps` reported, 4 -> 5 once, `contradictions=3` in the same attempt's counts |
| b | **Not reachable**: neither image has a significant-motion sensor (`dumpsys sensorservice`), so the motion is always `NO_SENSOR`, never `STILL` (§6t step 9); `PlacesTest` covers the rule | **Not reachable** (the same) |
| c | **Pass, "Here now" not timed.** GPS silent, home's scan, `open`: `wifi fix check=none step=USE`, the report's `vibb_wifi` with `fix_check` NULL; Locate "1 min ago · within 65 m · Wi-Fi estimate", no "at Home"; no event. "Here now: Home" stayed, from the last fine fix: with a zone the served interval is 30 min, so the lapse is an hour of only unchecked fixes - not waited for (every later row made a fine fix) | **Pass, the same** ("Wi-Fi estimate", no event; the lapse not timed) |
| d | **Pass.** The first rise: "Wi-Fi and GPS disagreed - phone 1: 1 time (1 in all)"; rises 2-4 within the day: no line; `devices.wifi_disagreed_at` moved a day back in the database, the next report: "3 times (4 in all)". No Attention row, no orange line on Locate | **Pass.** "2 times (2 in all)" (two rises in the first report), then rises 3-5 held, the day moved back: "3 times (5 in all)" |
| e | **Pass.** Two fixes 1 km north: "left Home"; the feed at home (Locate: `check=none step=GPS` - the jump from the satellite fix 1 km off - then `gps`) set pending inside; 3 min later the home connection with GPS silent: `check=link`, `vibb_wifi` with `fix_check` `link`; "Kid14 arrived at Home 02:39" timed at the satellite fix; Locate "within 65 m · at Home" (no word) | **Pass.** The same; the arrival event made by the link fix's report (created 04:06:00, `happened_at` the satellite fix's 04:02:01) |
| f | **Pass.** On the home connection: `wifi fix check=link`, Locate answered after 20 s (`attempt ... result=vibb_wifi` 20 s after the scan) - once the fix was within 60 m (50 m: four occasions; at 65 m - three occasions, the median under 4 - GPS ran its 60 s, as the rule says). On `open`: GPS 60 s, then the Wi-Fi fix | **Pass.** At 50 m (four occasions): `check=link`, answered 20 s after the scan; at 65 m GPS ran 60 s; on `open` GPS 60 s, then the unchecked Wi-Fi fix |
| g | **Pass, the sign-in window not re-run.** Vibb's page saves (password) and forgets; with the pause Android's saved-network page has Forget and Modify, the lockdown 0; managed, Android's Settings is suspended and hidden (lock task) and the lockdown is 1 - with the pause on and the lockdown set to 1 by hand, Forget is gone. **Fixed here**: Vibb saved a secured network as WPA3 alone (below) | **Pass, the same** (FORGET gone at lockdown 1; Android's list now shows Vibb's network as "Saved" beside the router's WPA2 row) |
| h | **Failed, fixed, passed.** The real ids moved at the first reboot: Hjemme 7 -> 1 (`cmd wifi list-networks`; AndroidWifi stayed 0; the second reboot kept 1). The first teach after the boot did **not** rebind (the next fix `check=none`): Android lists a WPA2 network with its WPA3 upgrade twice, so "exactly one saved network of that name" was never true (below). After the fix: Gammel forgotten on Vibb's page, a teach (`saved_networks_v1` `2:2`), reboot, the home connection under another id: the passive teach rebound the link (`3:2`) and the next fix was `check=link` | **Pass (with the fix).** Gammel forgotten, a teach (`2:2`), reboot: Hjemme's id 2 -> 1 (it had been 3 -> 2 at an earlier reboot after Prøve was forgotten); the home connection with id 1 (the link learned with the made-up 7): rebound at the first teach, the next fix `check=link`. After the boot the emulator's GNSS reported its default place before the feed came back: the first Wi-Fi fix `step=REFUSE` (an impossible jump), counted - expected |
| i | **Pass.** `sae`, the same id: `check=link` | **Pass** |

**What the network id did** (row h): `WifiInfo.getNetworkId()` is Android's saved-network id and is real for the app
(the debug connection stands in for it here, the saved networks are real). Ids are compacted at a boot: a network's id
moves down when one saved before it was forgotten (14: 7 -> 1; 16: 3 -> 2 -> 1), and stays when none was. So the
rebind matters exactly after a forget followed by a reboot.

Fixes (this branch):

1. **The rebind counted list entries, not networks** (`savedNetworkCounts`, `PlacesTest`): `getConfiguredNetworks`
   lists a network once per security type (a WPA2 network saved by Android or, now, Vibb comes as `wpa2-psk` and
   `wpa3-sae^` under one id), so a WPA2 home network was "two saved networks of that name" and never rebound after a
   boot. Both the count and the name count are now distinct ids.
2. **Vibb's Wi-Fi page saved a secured network as WPA3 alone** (`joinSecurityType`, `JoinMacSettingTest`): with both
   `WPA_PSK` and `SAE` in `allowedKeyManagement` Android keeps only SAE (`cmd wifi list-networks`: `wpa3-sae`;
   Android's Settings: "WPA3-Personal", a row apart from the router's WPA2 one) - a WPA2-only router would never join.
   Now the scan's capabilities pick it (`wifiSecurityOf`): WPA2 `setSecurityParams(SECURITY_TYPE_PSK)` - `wpa2-psk`
   with Android's `wpa3-sae^` upgrade -, WPA3 alone `SECURITY_TYPE_SAE`, open open; an enterprise or WEP network shows
   "Not supported" and is never saved (code QA of the gate).
3. The satellite feed in the debug receiver (above), since `geo fix` never teaches.

Findings: the emulator's `geo fix` has no satellites (above); a far satellite fix heard passively before an attempt
makes the jump gate refuse rather than the satellites overrule (both count once); after a reboot the emulator's GNSS
answers at its default place until the feed is back.

## 6bo. Posisjon also covers the location stored in photos (branch `media-location-delete-lines`) - run 2026-11-25

App access's Posisjon key (design 127) also covers `android.permission.ACCESS_MEDIA_LOCATION`, the runtime permission
(since Android 10) without which the system hands an app its photos with the position taken out. On **Android 14 and
16** with the user's OK. The debug launcher and a server of this branch, managed apps. A third-party gallery that asks
for `READ_MEDIA_IMAGES` and `ACCESS_MEDIA_LOCATION` and shows a photo's coordinates - Fossify Gallery
(`org.fossify.gallery`, Properties), checked first with `aapt2 dump permissions <apk>`; if it doesn't ask for both, a
throwaway test app that reads `ExifInterface.getLatLong` on the picture it opens. A geotagged JPEG on the phone:
`adb push geo.jpg /sdcard/Pictures/` and `adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d
file:///sdcard/Pictures/geo.jpg` (any photo with GPS tags; `exiftool -GPSLatitude=59.9 -GPSLongitude=10.7 geo.jpg`).
Logcat `AppEnforcer`.

What the platform says (AOSP's `core/res/AndroidManifest.xml` and `DevicePolicyManagerService`, 14 to 17 alike):
`ACCESS_MEDIA_LOCATION` is `dangerous` with no group of its own, so `setPermissionGrantState` takes it like any runtime
permission; it is not one of the sensor permissions Android 12+ keeps a device owner from granting (a denial is never
limited anyway). The permission controller grants it together with the photos access (Android 14 and 15: "Photos and
videos"; with "Select photos" too) - a `POLICY_FIXED` denial stays through that. An app targeting below 29 gets it
implicitly from `READ_EXTERNAL_STORAGE` (the split permission, `server/src/apk.rs`), so the key covers it there too.
These rows confirm it on the two versions.

| # | Step | Expect |
|---|---|---|
| 1 | The gallery allowed, Posisjon on; grant its photos access in the app | the PWA's app page has the Posisjon switch (the report's `access.location` `requested`); the gallery shows the photo's coordinates |
| 2 | Posisjon off in the PWA, a sync | `adb shell dumpsys package org.fossify.gallery \| grep -A1 ACCESS_MEDIA_LOCATION`: `granted=false`, flags with `POLICY_FIXED`; the report's `access.location` `admin_denied` (quiet in the PWA) |
| 3 | Open the photo's properties in the gallery (or the test app's `getLatLong`) | no position (the EXIF GPS fields read empty or zero); the rest of the photo is there |
| 4 | Revoke and grant the photos access again in Android's app info (the pause on, Settings shown) | `ACCESS_MEDIA_LOCATION` comes back `granted=true` only during the pause (it lifts every key); after the pause ends, `granted=false` with `POLICY_FIXED` again |
| 5 | Posisjon on again in the PWA, a sync | the flag is gone (`POLICY_FIXED` cleared), the permission back to what the user gave; the gallery shows the coordinates once its photos access is granted again |

### The run of 2026-11-25 (branch `gate-release-043`)

The setup as §6bq's run below. No gallery: a throwaway probe app (`no.gate.probe`, its own throwaway key, asking for
`READ_MEDIA_IMAGES` and `ACCESS_MEDIA_LOCATION`) reads `ExifInterface.getLatLong` from the plain MediaStore stream and
from `MediaStore.setRequireOriginal`, allowed in the PWA. A made-up geotagged JPEG (59.9139, 10.7522, Make
"GateTest") pushed and scanned.

| Row | Android 14 | Android 16 |
|---|---|---|
| 1 | **Pass.** The app page's Posisjon switch (its (i): "...eller hvor bildene ble tatt"); the report `requested`; with Allow all: 59.9139,10.7522 from both streams | **Pass** |
| 2 | **Pass.** `granted=false`, `POLICY_FIXED`; the report `admin_denied`, quiet | **Pass** |
| 3 | **Pass.** The plain stream gives 0.0,0.0 (`GPSLatitude=0/1,0/1,0/1`), the rest of the picture (Make, size) there; the original refused ("Caller must hold ACCESS_MEDIA_LOCATION") | **Pass** |
| 4 | **Pass.** During the pause the flag lifted; Don't allow, then Allow all in Android's app info: `granted=true`; the pause ended: `granted=false`, `POLICY_FIXED` | **Pass** |
| 5 | **Pass.** The flag gone, `granted=false` until the photos were asked for again, then the coordinates | **Pass** |

## 6bu. The glance's handle room on Android 14, the boot cover's one ask (branch `glance-bootcover`) - run 2026-11-26 (§6bv)

Two findings of phase D's emulator gate (`phase-d-launcher`'s §6bt, run 2026-11-26) that `main` has too, fixed here
for launcher rc.5 (the same code is on `phase-d-fixes`):

- **Finding 1**: Android 14 (`compat34`), 3-button navigation: `lock_glance_emergency` `[136,698][343,770]` overlapped
  `lock_glance_handle` `[159,746][321,782]`, and a swipe from the handle's upper half opened "Call 112?" (16 had no
  overlap, `[136,662][343,734]`). The lock's window isn't edge to edge on 14, the insets arrive consumed and the
  glance's listener never ran; `KidInsets.apply` now sets the padding for no insets at once.
- **Finding 2**: the boot cover asked for Android's pad again every 3-6 s while the pad was up (RC3-1's silence
  re-ask), once on 14 with a cancel at the screen-off. A silence while the cover's window lacks the focus (Android's
  pad over it) is skipped, and the focus back after the open ask lost it is the silence at once.

| # | Row | 14 (`compat34`) | 16 (`jelly36`) |
|---|---|---|---|
| R1 | The glance in both navigation modes (Android's PIN set): `uiautomator dump` - `lock_glance_emergency`'s bottom at or above `lock_glance_handle`'s top (14 3-button: Nødsamtale up to 734 over the handle `[746,782]`); a swipe up from the handle's upper half opens Android's pad, never "Call 112?"; Nødsamtale's tap still asks "Call 112?" | x | x |
| R2 (code QA M1) | A restart on a **cold first boot** (`adb emu kill`, a cold boot, so SystemUI's keyguard is late), both navigation modes: while SystemUI is late (no pad yet) `adb shell dumpsys window \| grep mCurrentFocus` names the boot cover (`BootCoverActivity`) and the log has the re-asks ("no answer, again N"); once the pad is up `mCurrentFocus` names SystemUI's `NotificationShade` and `logcat -s` the cover's tag shows exactly one ask after it ("a window (Android's pad) is over the cover - no re-ask" once, no "again N" while the pad is up); the PIN opens Home | x | x |
| R3 | The same boot with the pad cancelled (Back on the pad): "Trykk for å låse opp" under the mark, no ask until a tap; a tap asks once | x | x |

## 6bx. Zones shared by the family, picked per phone (design 135, branch `family-zones`) - run 2026-11-26/27 (§6by)

`docs/design/135-family-zones.md` §11: **server only**, so the gate checks that the phones see nothing change and that
the per-phone pick, the shared edit and each parent's push work end to end. On **Android 14 and 16** only (the
launcher is unchanged; 15 and 17 don't run): an API 34 image ("Telefon 1") and `jelly36` (Android 16, "Telefon 2"),
both enrolled to one server, the debug launcher, Regularly, mock fixes as in §6t (`cmd location providers
add-test-provider fused`, then `set-test-provider-location`). Two parent accounts in the PWA (A and B), each with a
push subscription (the browser's, or §6ay's recorder). Logs: `adb logcat -s ZoneWatch`; fences: `adb shell dumpsys
location | grep -A3 -i geofence` (on 16 the proximity alerts are in the same dump). Server rows read with `sqlite3`
on a copy of `data/kidphone.db` (never the live file while it runs).

| # | Step | Expect |
|---|---|---|
| 1 | **Before the update**, a server with phase D's 0181 and not 0182: on Telefon 1 "Hjem" (typed centre, 150 m) and "Skole"; on Telefon 2 "Hjem" with the same numbers. Both Regularly; a fix inside Hjem on both, twice 2 min apart. Drar off on Telefon 1's Hjem; Telefon 1's weather zone Hjem. Note both phones' geofence dump and `SELECT id, latitude, longitude, radius_m FROM device_zones` | "Here now" on both phones' Hjem; two fences on Telefon 1, one on Telefon 2 |
| 2 | **Update the server** to this branch (0182 runs; a `data/backups/pre-migrate-0181-0184.db` made) | `/zones` lists Hjem "150 m · Telefon 1, Telefon 2" and Skole "· Telefon 1"; "Here now" still on both; no new inbox row; at the next sync `ZoneWatch` logs no re-registration and the geofence dump equals step 1's; the policy's `location_policy.zones` (`GET /api/devices/policy` with the phone's token, or `ZoneWatch`'s fingerprint line) has the same ids and circles; Telefon 1's Lock Screen & PINs › Weather still says Hjem; `zone_alerts` has a Drar-off row for A and B on Telefon 1's Hjem |
| 3 | **Pick per phone**: on Telefon 2's Zones page switch Skole on | the page comes back at Skole's cell with "Saved."; after the nudge Telefon 2 holds one more fence; a fix inside Skole twice raises nothing (a silent first side); a fix 1 km away and its confirm raise "Telefon 2 left Skole" for Telefon 2 only |
| 4 | **Edit a shared zone**: open Hjem from `/zones`, the place's (i) shows "The zone is shared…"; move it 300 m (tap the map) and Save | both phones re-register one fence with the new circle (`ZoneWatch` fence lines, the dump); no event on either; a crossing on each phone afterwards raises its own event |
| 5 | **Alerts per parent**: parent B turns Kommer off for Telefon 2's Hjem on Telefon 2's Zones page | parent A's page still shows Kommer on; an arrival on Telefon 2 shows in both inboxes and queues one `push_deliveries` row, for A's subscription only |
| 6 | **Stale page**: open Telefon 2's Zones page in two tabs; in tab 1 switch Fotball (a new family zone with no phone) on; then in tab 2 switch Kommer for Hjem | Fotball stays linked to Telefon 2 (its fence stays); the Kommer change is saved |
| 7 | **Unlink, relink, delete**: Hjem off for Telefon 1; then on again; then Delete Hjem in its editor | off: the fence goes from Telefon 1's dump and its Weather still says Hjem; on: a new fence (a new id in the policy), a silent first side; delete: both phones' Hjem fences go and Telefon 1's Weather says none |
| 8 | **Cap**: give Telefon 1 ten zones, then switch an eleventh on | the page comes back at that cell with the switch off and "10 zones is the most"; nothing changes on the phone |
| 9 | **A zone's Wi-Fi** (design 138 on a family zone): both phones in Hjem on the same made-up home router (`--es connect`, §6bw); Hjem's editor `/zones/{id}#zone-wifi` | one Bekreft row for the router (not one per phone); Bekreft: "Saved." in the group, both phones nudged, and both policies' `location_policy.zones[]` for Hjem carry the router under their own `networks[].id`; switch Hjem off and on for Telefon 2 - its new link's policy carries the router again; Fjern...: gone from both policies; a strike in Telefon 1's report (`zone_watch.wifi_struck` with its row's id) blocks it for Telefon 2 too ("Sett et annet sted", both nudged) |

What can't be seen here: a real phone's Wi-Fi zone link relearning after the moved zone in step 4 (design 125 D12, at
least 2 days) - the Jelly Star's, later.

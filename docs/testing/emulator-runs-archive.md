# Emulator runs: the archive

The sections of `docs/testing/emulator.md` whose run gates are done, moved here on 2026-11-27 with their § numbers and
words unchanged; emulator.md keeps each heading with a pointer here. A row a section here left not run or owed is listed
in emulator.md's "Owed rows", the one place they are tracked. The recipe (§1-§6), the fakes' how-tos, sections written
and not yet run, and any new section stay in emulator.md.

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

## 6v. Per-app permissions: camera off, the special accesses approved on the phone (design 68)

Design 68 §2.3 and §6 (`docs/design/68-app-permissions.md`), the debug launcher and a server of this branch, Element X,
Immich, Etar and Fossify Calendar installed and allowed. Logcat `AppEnforcer` (`Camera off waits for the call's end`,
`Platform refused android.permission.CAMERA = 2 for ...`).

0. **The app-op probe first - the gate before the launcher RC** (the code QA's L1; §2.3; each op for an app that has the access and one that doesn't, `write_settings` and
   `usage_access` with test apps): `adb shell cmd appops get <package> <OP>` against the report's `installed_apps[].access`
   (the PWA's line, or `device_status.installed_apps_json`) for `MANAGE_MEDIA` (Immich), `SCHEDULE_EXACT_ALARM` (Etar,
   Signal; and Etar again after it is battery-exempt), `WRITE_SETTINGS`, `GET_USAGE_STATS`, `USE_FULL_SCREEN_INTENT`
   (Element X). An op whose reading disagrees ships as `unknown`. No launcher RC with B1 until this passes (passed on
   `jelly36` on 2026-10-27; **the Jelly Star repeat is still owed**). ("Fullskjermvarsler" is Settings' own nb word,
   checked in the code QA.)
   **Glue only the device shows** (the code QA's L5; the pure halves are unit-tested): `AppAccessReader.states`
   feeding `readAccess` (a throwing read must show `unknown`, never `denied`), `BootHome.callMayHaveEnded`'s wake
   (`deferredCameraApplyDue` + `WindowRelock.liveCall`, step 2), and the hidden-target skip in `cameraPass` (no
   "Platform refused ... CAMERA" line per apply for a hidden camera-off app, step 2). **Still owed to a device**: a
   failed read showing `unknown`, and a camera change waiting for a live call's end with its wake.
1. **Camera off** for Element X in the PWA: `dumpsys package io.element.android.x` shows `CAMERA: granted=false` with
   `POLICY_FIXED` (done 2026-10-27). Back on: the flag is gone, the app asks again. Settings' own camera switch can't be
   seen: Android's Settings is hidden outside a pause, and the pause lifts the denial - `dumpsys` is the check.
2. **During an Element X call**: off while it holds the camera waits for the call's end; off for an app that doesn't
   hold it is written at once. A camera-off app hidden and allowed again: denied by the apply that unhides it. A
   preinstalled camera app, or any system app: no `access` at all (no switch, no line) and no App access row on the
   phone (E4's M3). In a pause the PWA says
   "Camera off · back on while restrictions are paused".
3. **Immich**: the PWA says "Media management: not approved"; Settings › App access's row says to pause first; in the
   pause it opens Android's page for `app.alextran.immich`; turned on and back, the row reads "Approved - tap to change"
   and the next report `granted`, **without the parent code asked again** on the way back (the gate holds across a page
   a row opened, at most 10 minutes; E4's L3 - Home or Recents from there asks again). The pause ending with that page
   open: Home comes to the front, the row stays.
4. **Etar**: "Etar: Alarms & reminders" approved in a pause, and **Etar's first start in the same pause** (it asks for
   the battery exemption at its first start, which can't open outside a pause - its own crash, E4's M2; the row's and
   the PWA's words say so); after it Etar works with no pause. Fossify
   Calendar has no row (it declares `USE_EXACT_ALARM`). `me.vibb.music.debug`: no row, no camera switch.

**Run 2026-10-27, E4** (`docs/testing/2026-10-27-e4-e5-run.md`): step 0's probe agrees for all five ops on `jelly36`
(granted and denied; Android's own page writes the uid mode, which the reader follows; a battery-exempt Etar reads
`granted` with Settings' switch off) - the gate passed, the Jelly Star repeat is owed. No app here declares
`WRITE_SETTINGS`/`PACKAGE_USAGE_STATS`: a manifest-only probe APK stood in. Steps 1-4 passed except: system apps get App
access rows on the phone, and Etar's first start outside a pause crashes on its own battery-optimization request (step
4). Owed to a device: the live-call deferral and its wake, a throwing read as `unknown`. Settings' camera words (step 1)
can't be reached: Settings is hidden outside a pause and the pause lifts the denial. Unticking an app under Apps
uninstalls it - don't nudge with it.

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

## 6ab. A thread follows its newest message, the phone book on the night ground (2026-11-10)

Run on `handy36` (`-wipe-data`, `wm density 240`) on 2026-11-10, branch `sms-follow-book-ground` (b2f5ebad), with §6v's
setup (lock-task features 63, a light colour wallpaper picked in kid Settings) and main's debug build of the same day
as the control (`install -r` over the branch's and back). "At the end" below is measured, not judged by eye: in
`uiautomator dump` the last row's bottom is the list's bottom less its 8 dp padding.

- **The thread at the end follows:** one incoming SMS; five back to back (`adb emu sms send` in a loop), three rounds,
  one of two-line texts; eight sent at the same moment, four rounds; the kid's "Levert" line and Mamma's
  `'Likte «Kommer kl 5»'` making the last bubble taller; a reaction on an earlier bubble; the keyboard opening and
  closing; the field growing to five lines and emptied again; a picture from the fake MMSC (§6v step 3b), alone and
  between texts. The last message was whole at the end every time.
- **Scrolled up stays put:** one and then three incoming SMS, and a picture: the visible rows' bounds are the same
  before and after. Back at the end by hand, the next one is followed again. The kid's own send while scrolled up goes
  to the end.
- **An older page keeps the place:** 125 messages, the thread reopened (a page is 100), dragged slowly until the page's
  first row shows: the rows are where they were once the older page is in, and the next drag shows it above. A fling
  runs through the page's edge without a jump.
- **The control did not show the miss:** main's build followed every burst too, the eight at once included. The
  overtaken list update that drops the old scroll depends on timing this AVD doesn't produce; that the miss is gone is
  for the Jelly Star to show.
- **The phone book:** the list, the search with hits and "Fant ingenting" at font scale 1.0 and 1.3, and the contact
  sheet and the request form at 1.0, are `kid_ground` (`#0C0C14`) from the top edge to the bottom, like Kalender, with
  light status icons and a light navigation handle; Home on the light wallpaper keeps its dark ones. Main's build shows
  the rows on the wallpaper.
- **The buzz:** a reaction with the thread closed plays one 40 ms step (`dumpsys vibrator_manager`, "Recent
  vibrations"). The 40 ms reaches only a channel not yet created: an install that already ran a build with reactions
  (a debug build on the lab phone, an AVD without `-wipe-data`) keeps 80 ms until its data is cleared.
- **What `handy36` can't show:** its status bar is 136 px (the Jelly Star's and `jelly36`'s: 36), so with the keyboard
  up the thread's list is 104 px, and 7 px under a five-line field - shorter than one bubble. The same on the phone
  book: with the keyboard up the list is shorter than a row, and on b2f5ebad the search's first hit lay against its
  bottom with its top cut (main's build too; fixed in the round below). The keyboard-up views are `jelly36`'s or the
  phone's to judge. The fake MMSC's picture is 160 x 120: a portrait picture's tall bubble was not run.

**The code QA's fix round (ee9ef7a1), the same day**, on the AVD's kept data (no `-wipe-data`, `install -r`; no server
was up - the phone ran on its stored policy):

- **Unread until the end is reached:** scrolled up, one SMS in: the rows' bounds are the same, `message.is_read` is 0.
  Home without scrolling down: the Meldinger tile reads "Meldinger, 1 ulest" with its
  badge. The tile brings the same thread back, still scrolled up and still unread; flung to the end it is read and the
  badge is gone. Back to the list instead: the row reads "1 ulest", and the thread opened from it is at its end and
  read. Scrolled up, Home, a message in (its notification posted), the thread again: unread and the notification
  still there until the end is reached, then read and the notification gone. At the end with the thread in front an
  incoming message is followed and read at once, as before.
- **The kid's own send while the list coasts:** a fling coasts for about 1.9 s here (`dumpsys gfxinfo ... framestats`).
  A draft typed, the keyboard closed, `input swipe 240 300 240 690 50` and a tap on Send 10 ms, 0.4 s and 0.9 s after
  the swipe: the thread ended at its end with the sent bubble whole every time (four runs). The build before the fix
  was not run against the same taps, so that the miss existed here is not shown.
- **The phone book's first hit:** the keyboard up, "o" (5 hits), "ol", back to "o", and "o" typed from a list scrolled
  down: the first hit's top is at the list's top every time (the row's picture 10 dp under it, as in a list at rest),
  at font scale 1.0 and 1.3; the list itself is still shorter than a row on this AVD.

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

## 6ad. Meldinger as an app: its Apps row, the lock level and the buzz (design 82) (2026-11-10)

Run on `handy36` on its kept data (no `-wipe-data`, `wm density 240`, `install -r`; the AVD's data copied first and put
back after) on 2026-11-10, branch `meldinger-app` (0952a091, with main's §6ac mark merged in). The branch's server ran
on the host (port 3111, the phone at `10.0.2.2`) on a copy of an earlier pass's database, at migration 0134 when it
started; the PWA was driven through headless Chromium (390 x 844, nb) with JavaScript and without it. The phone is in
the kiosk (Home roots lock task), managed SMS, Mamma (`+4790000001`) and Pappa (`+4790000002`) with their kept
threads. A save's place is read as the scroll of `main.container` and the form's cell's top, before the save and after
the page it lands on. A vibration is read in `dumpsys vibrator_manager`'s "Recent vibrations" and the launcher's
"Notice buzz" log line. Google Messages is allowed on this AVD, so in nb two rows are named "Meldinger" until the
built-in one is renamed.

- **Migrations 0135 and 0136 on an upgraded database:** the server took its own backup
  (`backups/pre-migrate-0134-0136.db`) and came up at 0136: `device_lock_glance_apps` got `me.vibb.builtin.messages`
  with `show_text` 0 (Name only) beside Element X's row, `lock_buzz_on_notification` is 0. A second copy of the 0134
  database with `sms_lock_text = 1` set by hand first came up with `show_text` 1 (Full text).
- **An older Vibb One against the new server** (the launcher already on the AVD, before `install -r`): `#lock-buzz`
  shows the orange "Denne telefonen vibrerer ikke ennå - oppdater Vibb One" under the switch, the Apps row "Vises på
  telefonen når Vibb One er oppdatert", and the lock row at Av "Denne telefonen viser fortsatt navn og antall -
  oppdater Vibb One". The old launcher does what the lines say: at Av its lock still shows the name and the count, and
  at Hele teksten it shows the text (the derived `call_policy.sms_lock_text`). After `install -r` and the new
  launcher's first report (`messages_app_v1`) the three lines are gone.
- **The Apps row:** Device › Apps lists "Meldinger" with the sub "Meldinger · En del av Vibb One" under Allowed, and
  "Navn og ikon" takes the name "Beskjeder", the icon "e-post", the colour "Plomme" and "På Hjem"; the summary reads
  "Beskjeder, med ikonet «e-post» på plomme". Saved with JavaScript: the name (moved 0 px), the icon and the colour,
  "På Hjem" on (the cell's top -396 before and after). Without it: "På Hjem" off lands on the row's anchor, the cell's
  top at the top of the view. After a save "Navn og ikon" is closed again (the form every app's row has). A refused
  save (a name of 30 characters) comes back with the form open and "Høyst 20 tegn - hjemskjermen viser omtrent 10.":
  the row's top is at the top of the view, with JavaScript too - 396 px from where the Save button was, not the
  page's top (scroll 211).
- **S1, a reset that leaves the row unlisted:** staged, not reached by the phone - the phone's network off, SMS set to
  not managed, and `has_history` set to false by hand in the newest report of the pass's own database (the kept
  threads can't go without a wipe; for the second run the display row was put back by hand). Tilbakestill without
  JavaScript: `/devices/1/apps#allowed`, the Allowed group at the top of the view (scroll 123), the row gone. With
  it: the hash is `#allowed`, the scroll 607 before and after, the row gone.
- **The Lock Screen row and the buzz switch:** "Varsler på låseskjermen" lists the built-in app under the name from
  the Apps row ("Beskjeder") with Av / Bare navn / Hele teksten; the row is not there while SMS is not managed.
  `#lock-buzz` is "Vibrer ved varsler", "Én kort vibrering, ingen lyd, for appene på låsen nedenfor og tapte anrop".
  Every save of the level and of the switch stays where it was with JavaScript and lands on its anchor without it.
  (Calls & Contacts' SMS mode, saved to stage the above, moved 26 px once: the page got shorter above a scroll that
  was near its end; the save back stayed.)
- **Home:** the rename, the icon and the colour reach the tile without a restart of the launcher (the same pid all
  through the pass): the tile "Beskjeder" with the envelope on plum, content description "Beskjeder, 3 uleste", the
  PIN prompt "Skriv koden for å åpne Beskjeder", the list's header "Beskjeder". "På Hjem" off: the tile leaves Home,
  messages still arrive and the lock's row still opens the thread; on: the tile is back. Under a pause of all
  restrictions with "På Hjem" off the tile is on Home ("Åpen til ...", "Lås igjen") and it is gone again when the
  pause ends. With SMS off and the kept threads the tile stays, without a badge, and Meldinger is read-only:
  "Meldingene her kan bare leses." in the list and as the thread's line, no "Ny melding" action; the Apps row's sub
  adds "SMS er ikke styrt av Vibb".
- **The lock at each level:** an SMS from Mamma on the locked phone. Hele teksten: "Mamma", "Hei Emma, middag klokka
  f…", the time, the row's small badge the chosen icon on plum. Bare navn: "Mamma", "Ny melding" (three unread: "3
  nye meldinger"). Av: no row, and no buzz for the next message. A level saved in the PWA while the lock shows - Bare
  navn, Hele teksten, Av, Bare navn - is on the lock's rows when they are read 5 s later.
- **The buzz:** the switch on, the phone locked, a message that brings a row: one step of 40 ms, `usage:
  NOTIFICATION`, from `me.vibb.launcher.debug` (the dump's durations read 41-88 ms here). Pappa's row and then Mamma's
  3 s later: one buzz, for the first; a row 19 s after that: one. None with the switch off (the row comes); none
  unlocked; none with the ringer on Silent, one on Vibrate; none with Do Not Disturb on and none under a time rule's
  silence ("Stilletid" on the lock) - the row comes in both. Mamma ringing and an SMS from Pappa meanwhile: no buzz
  while it rings (the lock yields to the call screen), then Pappa's row, "Tapt anrop fra Mamma" and one buzz as the
  call ends. No vibration from the notification's channel beside the buzz in any dump (`launcher:messages` has
  `mVibrationEnabled=false`) - one per message.
- **What the buzz doesn't do:** a message in a thread whose row is already on the lock (the second unread, the row not
  swiped away) gave no buzz - only a gained row does (`smsGainsRow`, the wake's rule too), so the rows were swiped
  away between the steps above. (Changed after this pass, 2026-11-10: every new message the lock shows buzzes, the
  10 s gap kept - design 82 §14, "The buzz for every new message"; seen in §6af.) Under Do Not Disturb and a rule's silence an SMS never gets as far as `noticeCame()`
  (`smsPostAlerts`), so the buzz's own filter and silence gates were not seen working apart from that.
- **Recents and TalkBack, by dump:** Meldinger's task has `taskDescription label="Beskjeder"` and the overview reads
  "Vibb One (debug) Beskjeder" (L4). Content descriptions: the tile "Beskjeder, 3 uleste" and "Beskjeder"; the lock's
  rows "Ny melding fra Mamma, 4:18" at Bare navn, "Ny melding fra Mamma, Hei Emma, middag klokka fem, 4:18" at Hele
  teksten, "3 nye meldinger fra Pappa, ...".
- **Main's mark in this build:** scrolled up in Mamma's thread, an SMS in: `thread_new` "Ny melding"
  `[161,647][318,719]`; the tap goes to the end and reads it.
- **Found, not design 82's** (reproduced on main, explained and fixed since: §6af)**:** the first pause of all restrictions (the Parent PIN in Settings; Home and
  Meldinger the locked tasks, a call screen and the lock's tasks earlier in the log) left the phone on Android's "App
  is not available" for Vibb One: the system's Home start was caught by the lock-task interceptor (`startActivity ...
  BlockedAppActivity`, then `Attempted Lock Task Mode violation ... HomeActivity` three times), with
  `mLockTaskPackages` already empty, `mLockTaskModeState=LOCKED` and the blocked screen's own task the only locked
  one; the Home key did nothing. `am task lock stop` didn't help, `am stack remove <its root task>` did. Two more
  pauses - one with the same two tasks, one after a ringing call and a lock row's unlock into the thread - went
  through: 1 of 3, on an emulator logging "Slow operation" at the time. The branch changes no lock-task or pause
  code; whether main alone does it wasn't tried. The log and two screenshots are in the session's
  `scratchpad/m82/logs/` and `m82/shots/`.
- **Not run, not proven:** L1 (a failed engine read dropping the lock's rows - no cheap way to make the read fail,
  and no unit test holds it: it stands on the code, `SmsHome.refresh`); the Recents label after a reset; TalkBack by
  touch and the lock row's action label (§6ac's note); whether the notification and the buzz are felt as one or two
  on the phone, Android playing the buzz from the background there, and Android's own vibration settings - the Jelly
  Star's; the buzz for another app's row (Element X's).
- Screenshots of the run are in the session's `scratchpad/meldinger-shots/`.

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

## 6ah. The kid's look: Mørk or Lys - step 1's pass (design 78)

**Steps, written with the build on 2026-11-10 and added to in the code QA's fix round the same day
(`docs/review/qa-78-step1-code.md`, L8). Run in part on 2026-11-11 on `jelly36`**: "The run of 2026-11-11" at the end
of this section has what passed, the one finding (the launcher's own language) and what was never reached - the host
killed the emulator for memory before B.9. B.13-B.16 and the control on main came on 2026-11-11 with the language
fix; "The second run of 2026-11-11" after it has them and the rest (B.7's install half, B.9-B.11, C-F), with what was
still not run. The steps themselves are no result. Step 1 is the mechanism and
Kalender (`docs/notes/launcher/ui-polish.md`, "The kid's look: Mørk or Lys"); the row in kid Settings exists in the
**debug** build only.

**Brought to the design's revision 3 on 2026-11-11** (§12: a night and a light manifest activity per page, nothing
asked of the system), for the pass of `kid-look-2`, with no emulator: the setup, the command block, A's create line,
B.3, B.4, B.7, B.10 and B.12 reworded, a note before B.13, B.16 closed, B.17-B.20 new. None of the reworded or new
steps had been seen running when they were written. The first two runs at the end of this section were `kid-look-1`'s,
under the old mechanism (the app's own night mode), and stay as they were written. **Run on 2026-11-11 on
`jelly36`**: "The run of 2026-11-11 on `kid-look-2`", the last of the three, has every open path in both looks
measured frame by frame (no frame of the other look), B.3-B.6, B.17-B.20, two findings under the kiosk after a process
kill, and what was not run.

Setup: **`jelly36`** (§3), the debug launcher of branch `kid-look-2` as Device Owner (§4) on a wiped AVD or a restored
clean one - a launcher that ran `kid-look-1` left a night mode in the system's record of the package, and only a wipe
or an uninstall takes it away (design §12.4) -, enrolled, with §6w's
calendars: Kalender on Home, events today in two calendars with colours (§6w step 9), one in progress, an all-day one,
one with a description and a place, the kid's own "Min kalender" (the "+"), and a reminder that can come up on the
glance (§6w step 6). The phone in Norwegian. For the before pictures: main's debug APK built from the commit the
branch started from, installed over it with `adb install -r` (§6ag: that restarts the launcher and the PIN lock comes
up).

```
adb shell cmd uimode night yes|no                                        # the SYSTEM's dark theme, not the look
adb shell run-as me.vibb.launcher.debug cat shared_prefs/kid_look.xml    # choice and applied
adb logcat -b events -c                                                  # before each open or change
adb logcat -b events -d | grep -E "wm_on_create_called|wm_relaunch_activity|wm_destroy_activity" | grep -E "\.day\.|kidsettings"
adb shell dumpsys activity recents | grep -c "\.day"                     # Kalender's task: 1, or 0 after a change
adb shell settings put system font_scale 1.3                             # and 1.0 after
adb shell dumpsys activity activities | grep -E "mLockTaskModeState|mLockTaskModeTasks"   # the kiosk and its tasks
adb logcat -d -s Application | grep -E "The launcher's language took|Unlocked setup"   # an unlocked start's cost
adb logcat -d -s PageLook                                                # E: a page of the other look's class, a refused change
adb shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.threebutton   # .gestural back
```

The look is picked in kid Settings (Home's "Innstillinger" tile): the section "Utseende" after the background
section, Mørk | Lys.

### A. The starting window, on the real code (step 0's recording again)

Record with the device's own `screenrecord` and step every frame, as §6k's frame-stepping rule. Eight recordings: Mørk
and Lys, each from Home's Kalender tile and from the glance's reminder row (the tap, the kid's code), each **cold**
(no Kalender task: right after a change of look, or the card swiped away in Recents) and **warm** (the task behind:
Kalender, Home, Kalender). Then the four tile recordings once more with the system's dark theme the other way
(`cmd uimode night no`).

- [ ] No frame of the other look between the last frame of Home (or the keypad) and the page's own first frame: the
  starting window has the look's ground.
- [ ] In Lys the status bar's icons and the gesture pill are dark from the first frame with the light ground. **Known
  from step 0**: on the cold open from Home's tile the icons stayed white for about 230 ms and then tinted over about
  95 ms; the user accepted it for now. Write down the frame count again for each of the eight; a longer or a new case
  is a finding, the known one is not a failure of the run.
- [ ] The events log for each open: `wm_on_create_called` once for the look's class (`DayActivity` in Mørk,
  `LightDayActivity` in Lys), no `wm_relaunch_activity`.
- [ ] With Kalender open in each look: `cmd uimode night no`, then `yes`, then Battery Saver on and off
  (`adb shell cmd battery unplug`, `adb shell settings put global low_power 1`, `0`, `adb shell cmd battery reset`).
  The page keeps its look and the events log shows no relaunch and no new `wm_on_create_called`.

### B. A change of look

1. Open Kalender, go Home, open kid Settings. The control shows the applied look. Screenshot.
2. Tap **Lys**. The preview strip turns to the paper ground with the lilac received bubble and the peach own one;
   nothing else on kid Settings changes (compare with step 1's screenshot: only the control and the strip differ; no
   flash, no relaunch of kid Settings in the events log).
3. `kid_look.xml`: `choice` and `applied` both `light`. `dumpsys activity recents | grep -c "\.day"` is 0, and the
   events log has `wm_destroy_activity` for the old look's class (`DayActivity` here, `LightDayActivity` on the way
   back). Recents shows no Kalender card. **With the kiosk on** (an
   allowlist and kiosk in the policy; do steps 1-3 once so): `mLockTaskModeState=LOCKED` before the tap and after it,
   Home's task still in `mLockTaskModeTasks` and Kalender's no longer; kid Settings stays in front and nothing of the
   kiosk shows or drops (no "App is not available", Back and Home as before).
4. Home, the Kalender tile: Kalender in Lys from its first frame (A), one `wm_on_create_called`, for
   `LightDayActivity`.
5. The same back to **Mørk**. Then with an event page open in Kalender's task before going Home: both activities are
   destroyed at the tap, and the next open is the day view.
6. Tap the look that is already applied: nothing is destroyed, Kalender's card stays in Recents.
7. Tap Lys, then `adb reboot` within a few seconds. After the unlock: `kid_look.xml` says `light`, Kalender opens in
   Lys from its first frame. The same after `adb install -r` of the same APK (the process restarted). This shows that
   the look survives a reboot and a process start, no more. Since revision 3 there is no value of the system's beside
   the stored one; a process that dies between the tasks' removal and the write leaves the old look and no Kalender
   task, which a reboot by hand cannot be timed to hit (`PageLookTest` holds the order).
8. A phone that never ran this build (the update): install main's debug APK on a wiped `jelly36` with the system's
   dark theme **off**, provision, then `adb install -r` this build. `kid_look.xml` has `applied` = `dark` without any
   tap; Kalender opens with a night starting window (no light frame), although the system is light.

9. **Kalender's task alive only in Recents.** Open Kalender, go Home, and kill the launcher's process as its own uid
   (`adb shell run-as me.vibb.launcher.debug sh -c 'kill -9 $(pidof me.vibb.launcher.debug)'`); the launcher starts
   again and the PIN lock comes up. Unlock: the recents count is still 1, although the new process has no
   `DayActivity`. Kid Settings, tap the other look: the count is 0, and the next open from Home is a clean one in the
   new look (A), one `wm_on_create_called`.
10. **A start names the stored look's class.** With Mørk applied and no Kalender task (right after a change),
   write `kid_look.xml` back with both values `light` and kill the process (`run-as me.vibb.launcher.debug sh -c 'cat >
   shared_prefs/kid_look.xml; kill -9 $(pidof me.vibb.launcher.debug)'`, the edited copy on its stdin). After the
   unlock Kalender's cold open from Home is Lys from the starting window on, although no change ran: the unlocked
   start read the stored look and the tile's start names `LightDayActivity` (one `wm_on_create_called` for it, no
   `E PageLook`). Tap Mørk after.
11. **A change that waits, written by hand.** With Mørk applied and Kalender's task behind, write the file with
   `choice` = `light` and `applied` = `dark` and kill the process the same way. After the unlock, Home's resume runs
   the change once: both values `light`, the recents count 0, and Kalender opens in Lys. Go to kid Settings and Home
   again: the events log shows nothing destroyed and nothing closed (the resume has nothing left to run).
12. **The unlocked start's cost.** After each process start above (the `install -r`, the reboot, the kills), read the
   `Application` lines: `The launcher's language took N ms` (the default prefs file, at most one call to Telecom and
   one binder call, on every unlock-to-Home path; the language fix's code QA, L2) beside `Unlocked setup took N ms`
   (design 59's head, which does not include it). Write the numbers down; one that isn't small beside the head's is a
   finding. `The kid's look took N ms` is gone with revision 3: the start only reads one small prefs file and makes no
   call (the spike measured that read: 122 ms at the first start, 3 and 6 ms after; design §12.7.2).

Steps 13-16 were written on 2026-11-11 with the language fix (design 78 §5.3, "The cause" and "Decision"), from
Android 16's source and with no emulator: **none of them has been seen running**. They need the phone's own language
to be English (`adb shell settings get system system_locales` reads `en-US`) and the launcher's `nb` from the policy
(the PWA, the phone's language choice). (They were run since: "The second run of 2026-11-11".) **Since revision 3**
the launcher sets no night mode, so on a wiped AVD the cause steps 13 and 14 were written for is gone - the spike read
`nb` after both of its boots with the unlocked start's ask switched off (design §12.7, §12.7.2) - and what step 13 says of every second boot no longer applies. The
language code stays as built (design §12.9 point 2), so steps 13-15 stay: 13 and 14 are expected to pass with no
English frame, and 15's refusals have nothing to do with a night mode.

```
adb shell cmd locale get-app-locales me.vibb.launcher.debug --user 0     # the launcher's languages in the system
adb logcat -d -s LauncherLocales PageLook                                # "Launcher language: nb", or a refused call
adb logcat -b events -d | grep -E "wm_on_create_called|wm_relaunch_activity" | grep -E "HomeActivity|PinLock"
adb shell aflags list | grep expedite_activity_launch_on_cold_start      # if the image has aflags: write its state down
```

13. **The language over three reboots.** `get-app-locales` reads `[nb]` and Home is Norwegian. Kid Settings, **Lys**.
   Open Kalender, go Home (its task stays behind). `adb reboot`. After the unlock: the keypad if one shows, Home, kid
   Settings, the phone book, Meldinger and Kalender ("onsdag", "Pågår nå", never "Wednesday" or "Happening now") are
   all Norwegian, `get-app-locales` reads `[nb]`, the log has `I LauncherLocales: Launcher language: nb` once for the
   process start and no `Couldn't set the launcher language`, and Kalender's cold open is Lys from its first frame
   (step 7). **Reboot twice more** with Kalender left open each time, the same checks after each: by the source every
   second boot starts from a stored record without the languages and the one after from a record with them, so three
   boots cover both. For each boot write down Home's lines in the events log - one `wm_on_create_called`, and whether
   a `wm_relaunch_activity` follows it (the design's named cost: the first activity created once more when the
   language comes back) - and step a recording of the boot's first Home (`screenrecord` started as soon as `adb` answers)
   for a frame in English: **an English frame the kid can see is a finding**, a relaunch with none is the cost as
   written. The stored record itself (`/data/system_ce/0/package_configs/`) can be read only on an image with root.
   Before the fix (4845b74d, the run of 2026-11-11) the first of these reboots left every screen in English.
14. **A process that was alive before the unlock.** With a PIN on Android's own lock screen (design 59) and the look
   Lys: `adb reboot`, wait at the lock screen until `adb shell pidof me.vibb.launcher.debug` answers, start a
   recording, unlock. The same checks as step 13, on two reboots in a row. Here Home can be mid-launch when the
   launcher's files open: a Home drawn in English first is the design's second named cost - write down whether it is
   there and for how many frames.
15. **A parent's change with the page tasks alive.** Open Kalender, go Home, open Meldinger, go Home (both cards in
   Recents). Change the phone's language to English in the PWA and wait for the sync. Nothing changes while Home just
   stays in front; leave Home and come back (the phone book and Back). Two outcomes are the design's, and which one
   comes depends on what the process's last such call ran under (write it down with the flag's state from the block
   above): **set at once** - `Launcher language: en`, `[en]`, Home created again in English, no `Couldn't set the
   launcher language`, both cards still in Recents and each page in English when opened; or **refused, then set** -
   one `W LauncherLocales: Couldn't set the launcher language` and right after it, in the same resume, `Launcher
   language: en`, `dumpsys activity recents | grep -c "\.day\|MessagesActivity"` is 0 and both cards are gone. A
   warning with no `Launcher language: en` after it is the design's "what is still refused": write down which
   activities were alive (`dumpsys activity activities | grep -E "Hist|mActivityType"`) and at which later Home resume
   it got through. Back to `nb` with **Kalender in front** when the sync lands: nothing happens on Kalender. **Home
   straight from the page** (the Home gesture with Kalender in front; once more from a Meldinger thread) is the
   code QA's M1 and has its own outcome: set at once, or **refused twice, then set late** - two warnings in that
   resume (the page is closed but not yet destroyed), both cards gone, and about two seconds later, with Home still in
   front, `Launcher language: nb` from the one late ask (`LANGUAGE_RETRY_DELAY_MS`). Write down the time between
   Home's `wm_on_resume_called` and the page's `wm_destroy_activity` in the events log: the delay has to be longer.
   A late ask that is refused too, or two warnings with no third ask while Home stayed in front, is a finding. Then
   open the page again and go straight Home twice more: once the language is set, nothing is asked and no card is
   closed. **A standard-type activity that stays** (the code QA's M2): open the phone book through a `tel:` link in
   another app (`adb shell am start -a android.intent.action.DIAL -d tel:123` from that app's task, or a link in the
   browser), go Home, change the language, and resume Home several times with Kalender opened again each time:
   Kalender's card is closed at the first refused resume only (when its late ask ran), and never again for that
   language; at each refusal write down `dumpsys activity processes` for the launcher. Then once with the PIN lock up
   when the sync lands (at the unlock's Home: set at once, or refused and set by the late ask or at the next Home
   resume), and once during a call (nothing until Home after the call, and nothing
   relaunched during it). **The rest the design names**: Kalender in front, kill the launcher's process as its own uid
   (step 9's line), unlock, go Home, change the language in the PWA, leave Home and come back. If it is refused here
   (twice in one resume, Kalender's card gone for nothing) it should stay refused at every plain Home resume and get
   through at the Home resume right after the PIN lock (screen off, on, the code) or after `adb install -r`: write down
   each. Refused still after an unlock with the PIN lock is a finding; so is any screen in a mix of the two languages.
   A text half typed in a thread is gone when a refusal closed Meldinger's task, until step 2 keeps it: known.
16. **Closed by revision 3** (2026-11-11). The step looked for `E PageLook: The app's night mode wasn't set` with
   `Can't change activity type once set`, seen twice in the second run below. The launcher no longer asks the system
   for a night mode (design §12.2 R1), so there is no call left to refuse and the code cannot print the line. What can
   refuse a change now is step 20's.

Steps 17-20 were written on 2026-11-11 from the code QA of `kid-look-2`, with no emulator: **none of them has been
seen running**. (They were run since: "The run of 2026-11-11 on `kid-look-2`".)

17. **A page of the other look's class, as its task's root** (design §12.2 R6's check). With Mørk applied and
   Kalender's card in Recents (open Kalender, go Home): write `kid_look.xml` with `choice` and `applied` both `light`
   and kill the process (step 10's line). Unlock, open Recents and **tap Kalender's card** - a tap on the card, not
   `am task focus`. Expected: one night starting window, `E PageLook: DayActivity was created in the light look: its
   task is closed`, nothing of the page drawn, no crash (`adb logcat -d -b crash` is empty), no card (the recents count
   is 0) and Home back in front; the next tap on Home's tile opens Kalender in Lys from its first frame (A), one
   `wm_on_create_called` for `LightDayActivity`. If the overview has no card to tap after the kill - the spike saw the
   task listed in `dumpsys activity recents` and no card for it (design §12.7.2) - try with the kiosk off, and only
   then `adb shell am task focus <id>`; write down which route was run: the card's own tap is what has never been
   seen. Tap Mørk after.
18. **The same, not as the root.** With Mørk applied: Kalender, an event's page open over the day view, Home; then
   step 17's file, kill, unlock and tap on the card. Expected: `E PageLook: EventActivity was created in the light
   look: its task is closed`, both records gone (`dumpsys activity recents | grep -c "\.day"` is 0), Home in front,
   and **no** `W PageLook: The other look's task didn't close as a task`. A day view that shows under the closing
   event page, in either look, is a finding.
19. **Under the kiosk.** Steps 17 and 18 once with the kiosk on, with step 3's checks: `mLockTaskModeState=LOCKED`
   before the tap and after it, Home's task still in `mLockTaskModeTasks` and Kalender's no longer, no "App is not
   available", Back and Home as before - both removals go through the lock-task controller.
20. **A refused change.** Read `adb logcat -d -s PageLook` after every part of the pass: any `E PageLook: The look
   stays dark: ...` or `The look stays light: ...` (`a page task is still there after its removal`, `the new look was
   not written`) is a finding - write down the step, `kid_look.xml` and the recents count as they were. So is
   `E Application: The kid's look wasn't read`.

**The control on main** (a bug older than step 1, by the source; main's debug APK on a wiped `jelly36`, the phone in
English, no look to choose): set the launcher's language to `nb` in the PWA - Home takes it (`Launcher language: nb`),
the first change of the process's life. Without a process start in between: open Kalender, go Home, change the
language to English in the PWA, leave Home and come back. Expected: `W LauncherLocales: Couldn't set the launcher
language` with `Can't change activity type once set` at every Home resume for as long as Kalender's card is in Recents,
the launcher staying Norwegian; with the card swiped away (kiosk off) or after a reboot it is set. If main sets it at
the first Home resume instead, the reading of the source is wrong about main: say so.
**Corrected by the second run of 2026-11-11** (seen on `jelly36`, main's APK installed over this build, not a wiped
AVD): main refuses, but not "at every Home resume for as long as the card is in Recents" - at every Home resume where
Kalender's activity is the one the process follows (Home straight from Kalender). An activity of Home's own task added
in between (the phone book and Back) lets the change through with the card still in Recents.

The waiting change by a real tap (a tap while a page task is started, run at Home's next resume) is **not reliably**
reached by hand: with kid Settings in front the page the kid left is stopped, so the tap would have to land before
that page has had its `onStop` - which comes once Home has resumed and gone idle, well before a finger is through
Home's tile and at the row. To try it (only where "Utseende" is on kid Settings' first screen): Kalender in front,
then Home, the Innstillinger tile and Lys as fast as one shell line sends them (`adb shell "input keyevent
KEYCODE_HOME; input tap <tile>; input tap <Lys>"`). A hit is `choice` = `light` with `applied` = `dark` in
`kid_look.xml` and Kalender's card still in Recents; the next Home runs the change. A miss is step 2's ordinary change
and no finding. `PageLookTest` holds the rule, and step 11 shows the stored half of it every time.

### C. Kalender in both looks

Each of these in Lys beside the mockup (`docs/design/78-mockups.html`) and in Mørk beside main's build of the same
screen (Mørk must be the same picture as main's; anything that differs is a finding):

- [ ] The day view: the header with its back disc and arrow, the date, an event row (start time in the accent, the
  title, the second line dim), the calendar dots of both calendars and the kid's own (each clear of the white tile and
  of the paper), "Pågår nå"'s ring, an all-day row, the key under the list, the "+" disc with its night glyph, an empty
  day's line, a reminder shown under the header. A row pressed: the ripple is visible and dark in Lys.
- [ ] An event's page: title, time, description and place, the calendar's dot and name, "Endre" and "Slett" (its own
  event), "Slett"'s confirm dialog - a white panel with dark text and accent buttons in Lys.
- [ ] "Ny avtale": the field labels and fields, the keyboard up, "Lagre". The **date picker** and the **time picker**
  in both looks at font 1.0 and 1.3: the panel, every day number, the weekday row, both month arrows, the header and
  the chosen day (night text on the peach fill), the clock face's numbers, the buttons. **Look at today's number while
  another day is chosen**: the framework draws it in the peach fill, which is 2.6:1 on Lys's white panel - say how it
  reads.
- [ ] The status bar's icons and the gesture pill on each of the three screens, and with the keyboard up.
- [ ] **3-button navigation** (the overlay command above; `adb shell settings get secure navigation_mode` reads 0;
  `.gestural` back after). In **Mørk**, the bottom of each of the three screens with main's build and with this build:
  the same picture, the system's scrim behind the three buttons included (`kid_page_nav_contrast`; the code QA's M1).
  In Lys: dark buttons on the paper with no scrim behind them, each one clear to see, with the keyboard up too.

### D. The screens that are not pages: before and after

Screenshots with main's build and with this build, each with the system's dark theme **on** and **off**, and on this
build with the look Mørk and again Lys: Home, the phone book, Meldinger's list and a thread, the contact card, the
emergency menu, kid Settings (its Wi-Fi, Bluetooth and sound lists too), the drawer, parent Settings behind its PIN
dialog, the time-rule screen, the PIN lock with its glance, an incoming and an ongoing call (§5), and the "link
blocked" notice (a `market://` link while the Play Store is suspended, `docs/notes/launcher/push-and-play.md`).

- [ ] System dark **on**: this build is main's picture on every screen, in both looks.
- [ ] System dark **off**: only what AppCompat draws by itself differs from main - a row's ripple while pressed, a
  control's tint, an unthemed switch or divider, parent Settings' preference widgets (design D2). Anything else - a
  ground, a text colour, an icon - is a finding. The "link blocked" notice: its ground, text and OK button as before.
- [ ] Lys against Mørk on this build: no difference on any of these screens; their starting windows are night in Lys
  too (record the phone book's and Meldinger's cold open from Home in Lys).
- [ ] None of them is relaunched by a change of look or by `cmd uimode night yes|no` (the events log), the call screen
  during a call included.

### E. TalkBack and the font scale

- [ ] TalkBack on kid Settings (the image's own TalkBack if it has one; otherwise read the nodes with
  `uiautomator dump`, to `/data/local/tmp` as §6ag): the heading "Utseende", then "Mørk" and "Lys" as two choices with
  their selected state, then the caption. The preview strip is not a stop and its two texts are not read.
- [ ] Font scale 1.3: the control's segments stay at least 48 dp high with their words whole, the strip keeps its
  56 dp with both bubbles inside it (the received one may end in an ellipsis), the caption wraps; Kalender's three
  screens and both pickers in Lys.

### F. To note, not to judge

- Android 16's "make more apps dark" (Accessibility; step 0's image had no such switch): if this image has it, what it
  does to a Lys page.
- A release build has no row. It is checked from CI's release APK on an emulator of its own, never from a local
  build on a phone: kid Settings shows no "Utseende", Kalender is night, and `kid_look.xml` has `applied` = `dark`
  after the first unlock.

### What `jelly36` can't show

The Jelly Star's own Android build (step 0's repeat there is still open - a debug build on a test phone, never the
kid's), a finger's tap instead of an injected one, an Android 14 phone's bars under a Lys page.

### The run of 2026-11-11 (`jelly36`), stopped before B.9

`kid-look-1` at 163aceb5 (the debug APK from one gated `assembleDebug` on the clean tree, 2026-11-10 23:54 UTC), on a
wiped `jelly36` (Android 16, 480x854, 36 px bar), headless, `-timezone Europe/Oslo`. Main's debug APK (bc547c84) was
installed and provisioned first, for the before pictures, and this build put over it with `adb install -r` (B.8). The
system's language en-US, the launcher's own `nb` from the policy, the kiosk on, gesture navigation. Every line is
**seen** (on `jelly36`: a picture or `dumpsys`), **measured** (a recording stepped frame by frame, or a log line) or
**inferred**, and says which; made-up names only.

**Stopped at 01:38 UTC**: the host's kernel killed the emulator for memory (`Out of memory: Killed process ...
(qemu-system-x86)`, 4.7 GB on a 12 GB host where another session's Kotlin daemon held 3.4 GB) during the pass's second
reboot. By the standing rule it was not started again. The AVD was put back from the copy taken before the pass (22
files, SHA-256 identical).

**Passed**

- **A, the events log** (measured; 12 recordings - system dark on: both looks, tile and glance, cold and warm; system
  dark off: both looks from the tile, cold and warm): one `wm_on_create_called` for `DayActivity` on every cold open,
  none on a warm one, no `wm_relaunch_activity` anywhere.
- **A, the other look's frame** (measured): Mørk never shows a light frame, with the system light too. Lys from the
  tile goes from Home's night frame through the system's cross-fade (66-240 ms) to the paper and never back; from the
  glance the page slides in from the right over the lock, already drawn in Lys.
- **A, the status bar in Lys** (measured: how long white ink stands on the paper, the tint's fade included). From the
  tile, cold: 255 ms (system light), 379 ms (system dark), 498 ms on the first open after a boot with the emulator
  loaded - step 0's case, somewhat longer here. From the tile, **warm: 201 ms and 165 ms** - new beside step 0's "cold
  only"; on a warm open the system's task-to-front animation also shows a thin night edge around the light page for
  about two frames. From the glance, cold and warm: dark ink from the first light frame.
- **The late tint's cause** (measured): the page has a starting window (`Splash Screen me.vibb.launcher.debug`,
  `ty=APPLICATION_STARTING`) although `launcherBaseTheme`'s `windowDisablePreview` is inherited, and it has the look's
  ground. With the launcher's process held (SIGSTOP) right after the tap, that window alone goes from night to paper in
  about 130 ms, and the bar's ink still turns dark only 210-310 ms after the first light frame. So it is neither the
  starting window's lifetime nor the inherited `windowDisablePreview`: the status bar follows the app transition late,
  whoever owns the window. No theme item changes that; nothing was changed.
- **A, the flips** (seen; Kalender open in Mørk and again in Lys): `cmd uimode night no`, `yes`, Battery Saver on and
  off, and Battery Saver with the system light - no lifecycle event, the same `ActivityRecord`, the pictures identical
  below the status bar (in Lys the last one differs in the reminder's minute only). On this image Battery Saver is on
  in `dumpsys power` and does not move `mComputedNightMode`, so that half says less than its name.
- **Home on a switch of the system's dark theme** (seen): no create and no relaunch on this build; main's Home was
  created again by the same `cmd uimode night yes`.
- **B.1-B.3, the kiosk on** (seen): at the tap on Lys only the control and the strip differ from the picture before
  (x 45..434, y 510..685) and kid Settings has no event; `DayActivity` is finished and destroyed, `kid_look.xml` is
  `light`/`light`, the recents count 0, `mLockTaskModeState=LOCKED` before and after with Home's task alone in
  `mLockTaskModeTasks`, nothing of the kiosk shown or dropped. **B.4**: A's cold Lys recordings are that open.
- **B.5** (seen): with an event page open in Kalender's task, the tap on Mørk finishes and destroys `DayActivity` and
  `EventActivity`, `dark`/`dark`, count 0, still `LOCKED`; the next open is the day view, one `wm_on_create_called`.
  **B.6**: a tap on the applied look - no event, the count stays 1.
- **B.7, the reboot** (seen, measured): after Lys and `adb reboot`, `light`/`light`, and Kalender's first open is Lys
  from its first light frame with one `wm_on_create_called`. (During that boot Android's own runtime restarted once:
  `system_server` died on a `DeadSystemRuntimeException` in `TelephonyManager` after `com.android.phone` stopped
  answering under the boot's load - not the launcher's - so the launcher's process started twice.)
- **B.8** (seen, measured): main's build with the system's dark theme off, this build over it: `applied` = `dark` with
  no tap, and Kalender's first open is night in every frame, one `wm_on_create_called`.
- **B.12** (measured), `The kid's look took` beside `Unlocked setup took`: 14 ms / 372 ms (B.8's install), 58 ms /
  578 ms (an `install -r`), 332 ms / 9300 ms and 405 ms / 14231 ms (the two starts of the loaded boot). Small beside
  the head's every time.

**Finding: the launcher's own language is gone after a reboot, and its repair is refused while Kalender's task lives**

- **Seen**: after B.7's reboot `cmd locale get-app-locales me.vibb.launcher.debug --user 0` read `[]` (`nb` before),
  and Home, the PIN lock ("Wrong code", "Emergency call", "Parent code"), kid Settings and Kalender ("Wednesday",
  "Happening now") were in English, the system's language.
- **Seen**: `LauncherLocales.applyIfSafe` (Home's resume) asked for `nb` again and the system refused it, five times
  in two launcher processes: `W LauncherLocales: Couldn't set the launcher language`, `IllegalStateException: Can't
  change activity type once set: {... mActivityType=home ...} activityType=standard`, thrown in the system server
  (`WindowConfiguration.setActivityType` under `ConfigurationContainer.applyAppSpecificConfig` and
  `PackageConfigurationUpdaterImpl.updateConfig`, from `ILocaleManager.setApplicationLocales`). The last time Home was
  in front and Kalender's task, opened after Home, was in Recents; the four before were around the PIN lock and
  Kalender's open after the boot.
- **Seen**: a change of look from kid Settings (which removes Kalender's task) went through with no `PageLook` error,
  and the Home resume after it set the language: `I LauncherLocales: Launcher language: nb`, `[nb]`, Home created again
  in Norwegian. So the phone stayed in the system's language from the reboot until the first Home resume with no task
  of the launcher's own above Home's; with the kiosk on the kid has no way to close Kalender's task.
- **Inferred, not seen**: Android keeps an app's night mode and its languages in one per-package record
  (`PackageConfigPersister`), and by AOSP's source as remembered a record that has a night mode is written to disk
  without its languages - then every phone on this build loses the launcher's language at every reboot. The refusal
  looks like the system's own scratch configuration for the process keeping the activity type of the last such call
  (Home's, from the unlocked start's `setApplicationNightMode`) and refusing the next call when the process's newest
  activity is of another type. Main asks for the language once per change and never sets a night mode, so it meets
  neither. The control for the first half - another app's language set from the shell, a reboot, both read right
  after it - was the reboot the emulator died in. Whether `setApplicationNightMode` can be refused the same way (a
  process whose first activity is the PIN lock or Kalender, then a change from kid Settings) was B.9's to show.
- **Read in source, 2026-11-11** (AOSP `frameworks/base`, tag `android-16.0.0_r1`; the design's §5.3, "The cause",
  has the files and methods; no emulator): the first half of the line above holds and is no race - with a night mode
  set, the record on disk loses the languages at the next boot's first call. The refusal is as guessed, with one
  correction: the scratch configuration keeps the type of the last call that **got through**, whichever it was, and
  what it is compared with is the activity the process follows - the one added to it last. "Main meets neither" is
  wrong in its second half: main never loses the language at a reboot, but it has the refusal too, narrowly - a second
  change of language in one process life while the followed activity's type is not the first change's (the control on
  main, above).
- A phone whose system language is the launcher's shows none of it. Not fixed in the pass; fixed on 2026-11-11 in the
  mechanism (the design's §5.3, "Decision") and **not seen running**: B.13-B.15 are its steps.

**Not run** (the emulator was gone)

- B.7's `install -r` half: it ran once from a wrong state (the pass's helpers find Home's tiles by their Norwegian
  names, which the finding had turned English), so only its cost line counts. B.9, B.10, B.11, the waiting change by
  a real tap.
- C on this build: both looks of the three screens, the pickers at 1.0 and 1.3 with today's unselected number, the
  dialogs, the "+" disc, the bars with the keyboard up, and 3-button navigation (the code QA's M1 - not seen). Main's
  before pictures were taken; on main (seen) the pickers' "AVBRYT"/"OK" are almost invisible in both system modes,
  "Varighet"'s choices almost invisible with the system light, the 3-button bar has dark grey buttons with the system
  light and white ones with it dark and no scrim band to see, the day view's key line runs under the "+" disc, and the
  neighbouring days' rows show a few pixels at both edges.
- D on this build (main's pictures were taken; the link-blocked notice had to be opened by its component, since
  `jelly36` refuses to suspend the Play Store), the pinned screens on a change of look, the unlock with the PIN lock
  showing on a system-light phone; E; F.
- B.13-B.16 and the control on main (written after the run, with the language fix of 2026-11-11): never run.

### The second run of 2026-11-11 (`jelly36`): B.13-B.16, the control, B.7-B.11, C-F

`kid-look-1` at 9ca55e8e (the debug APK from one gated `assembleDebug`, the emulator down), put with `adb install -r`
over 163aceb5 on the first run's AVD as that run left it (a copy; `jelly36` itself was put back from the copy taken
before the first run and verified, 22 files, SHA-256 identical). Main's debug APK (809bbcd0) for the control. The test
server was the binary main's tree had already built (0.73.0, no cargo). System language en-US, the launcher's `nb`
from the policy, the kiosk on, gesture navigation unless said, the phone's own PIN lock unless said, `-timezone
Europe/Oslo` (the times below are the phone's). **A "reboot" here is `adb shell reboot -p`, the memory gate read with
the emulator down (8.1-8.2 GB each time) and a cold start** (`-no-snapshot-load`): seven of them (r1-r4, a1, a2, t1),
the emulator never killed. `cmd locale set-app-locales` was **not run at all** in this pass
(`com.google.android.deskclock` still had `[nb]` from the first run); `get-app-locales` only reads. Every boot on this
host has ANRs in SystemUI, the phone app and others and a "System UI isn't responding" dialog over the lock (Android's
own, in English, the system's language); on r1 `system_server` died once more and the launcher started twice. Every
line is **seen** or **measured** on `jelly36` unless it says **read** (AOSP `android-16.0.0_r1`) or **inferred**.

**B.13 and B.14, the language over the boots.** Before each: Lys, Kalender's and Meldinger's cards behind Home.

| Boot | The system's record after the unlock | `Launcher language: nb` | Look / language / `Unlocked setup took` (ms) | Home | The channels named in the unlocked setup |
|---|---|---|---|---|---|
| r1, first runtime | `[]` right after the boot | 04:24:10.649 | 272 / 52 / 9642 | created once | not read |
| r1, second runtime | not read | 04:25:19.794 | 290 / 304 / 20393 | created once | Norwegian |
| r2 | **without** the languages | 04:39:06.127 | 37 / 53 / 2662 | **relaunched** (`wm_relaunch_resume_activity` 04:39:06.126; created 04:39:10.206 and 04:39:14.118) | **English** |
| r3 | with them (`[nb]` before the process started) | 04:49:14.291 | 38 / 62 / 4943 | created once | Norwegian |
| r4 | **without** | 05:19:58.671 | 127 / **1679** / 13828 | created once, after the call | **English** |
| a1 (Android's lock; the process alive before the unlock) | **without** | 05:35:28.841; 05:35:48.502 in the process after the ANR below | 677 / 839 / 11455; 39 / 59 / 2232 | created once, by the second process | Norwegian (made by the second process) |
| a2 (the same) | with them (`[nb]` two seconds after the code) | 05:43:30.152 | **2648** / 168 / 4739 | created once | **English** |
| t1 (the T1 control's boot) | **without** | 07:00:37.554 | 1011 / 1099 / 3326 | **relaunched** (`wm_relaunch_resume_activity` 07:00:37.547; created 07:00:41.895, destroyed 07:00:44.530, created 07:00:45.430) | **English** |

- After every boot `get-app-locales` read `[nb]` from the launcher's call on, with no `Couldn't set the launcher
  language`; the lock's glance ("Ons. 11. nov.", "Nødsamtale", "Lommelykt"), the keypad ("Skriv inn koden din",
  "Foreldrekode"), Home, kid Settings, the phone book, Kalender ("Onsdag", "Pågår nå") and Meldinger were Norwegian.
  **The first run's finding is gone.**
- **No launcher frame in English was seen** at any boot (the emulator console's screenshots at about two a second -
  the PIN lock is a secure window, `screenrecord` shows it black: 289-725 frames a boot). On r2 the screen was black
  behind Android's ANR dialog from 04:39:10 to 04:39:21, so Home's first instance - created before the relaunch - was
  not pictured; on t1 no frame between the dark ground and the Norwegian lock showed one either. On a2 Home's first
  visible frame behind the dialog was Norwegian; on a1 Home's first frame was not pictured (the cover and the dialog).
- **Home's relaunch** (the design's named cost) came on two of the four boots whose record had no languages (r2, t1),
  and on neither of the two with them.
- **Not every second boot**: r2 without, r3 with, r4 without is the alternation; a1 (without, right after r4) and t1
  are boots after changes of look and language in between. Why a1 had none was not found (the stored record cannot be
  read without root); no boot showed the launcher in the phone's language, whichever record it had.
- **The restored cards are gone when the kiosk starts**: Kalender's and Meldinger's tasks of before the reboot are in
  Recents from the user's unlock (r3: both there at the language call, which was not refused) and gone at the first
  read with `mLockTaskModeState=LOCKED` (r3 04:49:20, r4 05:20:15, a2 05:43:43, t1 07:00:45). So on a kiosk phone no
  page is opened "from its restored card": every open after a boot is a cold one (why: not read in source). The cold
  opens in Lys (recordings after r1 and r3): Home's last frame, the system's cross-fade, the paper (F3F1ED) starting
  window, no dark frame, one `wm_on_create_called`. Meldinger's cold open is night throughout, as step 1 means it.
- **`expedite_activity_launch_on_cold_start`**: not on this image - `aflags` needs root on a user build, and the
  flag is not among the 29 of `com.android.server.am` in the image's `aconfig_flags.pb` files. Its state is unread.
- **The costs** (measured, L2's line): the language call 52-304 ms on six starts and 839-1679 ms on three (r4, a1,
  t1), the look's call up to 2648 ms (a2), both on the main thread of a loaded boot. On a1 the launcher itself got an
  ANR ("executing service KidVpnService, waited 20166ms", 05:35:40.575, cpu pressure 69 %) and was killed; its next
  process did the setup in 2232 ms and everything after was right. One sighting, on this host's loaded boot; the first
  run's two loaded starts (9300 and 14231 ms, without the language call) had none.

**The application's own texts (the code QA's L3): seen, by two routes.** On r2, r4 and t1 - a record without the
languages - and on a2 - a process alive before the unlock, with a record that had them - the channels the unlocked
setup names from the application's context were in English in the app's notification settings (`dumpsys
notification`: "App installs and updates", "Calls", "Calls (call screen open)", "Find My Device background
connection", "Play install mode", "Missed calls", "Find My Device ring", "Content filter", "Opened with the parent
code"), while the three `SmsNotifier` makes before each post were Norwegian ("Meldinger", "Meldinger (stille)",
"Reaksjoner"). On r1's second runtime, r3 and a1's second process all twelve were Norwegian. The ongoing notification
of the listener service has the title "Vibb One" and no text; an ongoing call's heads-up was "Pågående samtale" with
Android's own "Hang Up". **Fixed after the pass, not seen running**: `Application.onConfigurationChanged` names the
channels again when the process's languages differ from the ones they were named in (the app's own languages reach a
process as a configuration change; `ACTION_LOCALE_CHANGED` is the phone's language). To see on the next pass: a boot
whose record has no languages, then `I Application: Notification channels named again: nb` and Norwegian names.

**B.15, a parent's change** (one process, r3's, then the ones named).

- Both cards alive, Home in front, English saved in the PWA: nothing until Home was left and resumed (the phone book,
  Back); then **set at once** (`Launcher language: en` 04:56:14.541, no warning, Home created again, both cards
  alive, each page in English from its card).
- Norwegian saved with Kalender in front: nothing on Kalender. **Home straight from Kalender: one warning**
  (04:56:54.826), both tasks closed, `Launcher language: nb` 04:56:54.834 **in the same resume**. From a Meldinger
  thread: one warning 04:57:42.660, `en` 04:57:42.663. So the second ask of the resume got through although the page
  was only paused: the code QA's M1 outcome ("refused twice, then set late") did not come here, and the late ask was
  not needed. Home's `wm_on_resume_called` to the page's `wm_destroy_activity`: 356 ms and 344 ms.
- The page opened again and straight Home, three times with the language set: nothing asked, nothing closed.
- **The kiosk around a repair**: before, `mLockTaskModeState=LOCKED` with Home's task #18, Kalender's #25 and
  Meldinger's #26 in `mLockTaskModeTasks`; after, `LOCKED` with #18 alone. After each repair only `HomeActivity` was in
  the launcher's process (`dumpsys activity processes`).
- **A standard-type activity that stays** (M2). A plain `am start -a android.intent.action.DIAL` from the shell puts
  the phone book in Home's task (type home): no lingering one. With `-f 0x18000000` (new task, multiple) it gets a task
  of its own, type standard - that stood in for the `tel:` link in another app, which was not done. Then a change of
  language was refused at three rounds of Kalender and Home (the ask and the late ask each time: 05:02:57.868 and
  05:02:59.871, 05:03:06.588 and 05:03:08.591, 05:03:15.329 and 05:03:17.331), **Kalender's card closed at the first
  only**, the process holding Home, the phone book's task and Kalender. The language did not land while that task
  lived. With the task removed (`am stack remove`, the run's own): Kalender then the Home gesture was **still refused**
  (the close is not made twice for one language, so Kalender stayed the followed activity); Kalender left with Back:
  refused at the resume (05:04:00.999) and **set by the late ask** two seconds later (`en` 05:04:03.003) - the late ask
  seen working, and the M2 rule's cost seen: after a close in vain the change waits for a Back out of the page, the
  PIN lock or a process start.
- The PIN lock up when the sync lands: nothing while locked; at the unlock one warning (05:04:55.108), Kalender's card
  closed, `nb` 05:04:55.113 in the same resume. During an incoming call: nothing for ten seconds and no lifecycle
  event; at Home after the caller hung up one warning (05:06:08.309), the card closed, `en` 05:06:08.335.
- Killed with Kalender in front (as its own uid): the new process created Home first, then the PIN lock; a change
  saved after the unlock was set at once at the phone book and Back. Nothing stayed refused on this route.
- No screen was seen in a mix of the two languages. Meldinger's tile and header read "Beskjeder" in both - the test
  database's own display name for the app, no missing string.

**B.16, a change of look refused: seen (the review's N1), on a natural route twice.**

- Route 1 (Kalender in front, killed, unlock, the other look): **not refused**, twice (the new process creates Home
  first on this image).
- A constructed start (the kill and a new-task `DIAL` in one shell line, so the phone book's standard-type task is
  the process's first activity): the tap on Mørk logged `E PageLook: The app's night mode wasn't set` with `Can't
  change activity type once set: {... mActivityType=standard ...} activityType=home`; `kid_look.xml` had `dark`/`dark`;
  Kalender's cold open showed **the paper starting window for about 440 ms** before the page in night. `adb install -r`
  (a process start) put it right: night in every frame.
- **Route 2, natural** (r4: the process started at boot for `KidVpnService`, no activity at the unlocked start's two
  calls; English saved in the PWA while the PIN lock was up; unlock): the language was set at once at that resume
  (05:24:09.763, the PIN lock finishing and not yet destroyed), and then the tap on Mørk in kid Settings was
  **refused** (05:24:50.734, the same exception); `dark`/`dark` stored; Kalender's cold open showed the paper starting
  window for about 330 ms, then night. A tap back on Lys was refused too. **Again on a2** (Android's lock screen,
  another boot): the language set at the unlock 05:45:39.242, the tap on Mørk refused 05:45:52.324.
- In r4's process the model's two predictions were then tried and held: a language change at a plain Home resume (the
  phone book and Back) refused, late ask included; the same change at Home straight from Kalender set at once.
- **Read and inferred**: the process's scratch configuration takes the type of the first call that passes with an
  activity followed; here that was the language call at the unlock, under the PIN lock (standard), and every later
  call under Home or kid Settings (home) is refused for that process's life. The call that fixes the wrong type is the
  first ask of a Home resume right after the PIN lock, a page or a call - the one this build makes at once.

**The control on main** (809bbcd0 with `adb install -r -d` over this build; the system's record still held this
build's night mode): the first change of the process's life was set at the phone book and Back (06:00:03.466). With
Kalender's card alive, the second change was **refused twice** at Home straight from Kalender (06:00:27.990,
06:00:36.093, `Can't change activity type once set`), and then set at the phone book and Back with the card still in
Recents (06:00:43.204). So the refusal is older than step 1, as read; the steps' "at every Home resume" was too wide
(corrected above).

**T1, the control for the reboot's cause** (boot t1): before the power-off both the launcher and
`com.google.android.deskclock` read `[nb]`. After the boot the launcher read `[]` until its own call
(07:00:37.554), and the clock app - which has no night mode and which nobody sets - read `[nb]` at its first read. One
boot; it agrees with the source reading (a record with a night mode loses its languages on disk).

**B.7's `install -r` half, B.9, B.10, B.11: passed.**

- B.7: `light`/`light` after the install, the look's call 12 ms, the language's 11 ms, the setup 178 ms; Kalender's
  cold open paper from its first light frame, one `wm_on_create_called`.
- B.9: killed with Kalender's card behind Home; the tap on Mørk: no error, `dark`/`dark`, the card removed
  (`wm_destroy_activity ... finish-and-remove-task`), `LOCKED` before and after, the next open night in every frame.
- B.10 (`light`/`light` written by hand, kill): no change ran; Kalender's cold open in Lys, one create.
- B.11 (`applied` = `dark`, `choice` = `light` written by hand with Kalender's card behind, kill): Home's first resume
  ran the change (the card destroyed 56 ms after Home's create, before the PIN lock came up), `light`/`light`, still
  `LOCKED`; the next open in Lys. That is the stored half of the first review's L4; **the death between the change's
  first write and the tasks' removal was not constructed** (nothing to stop the process there). **L5** (Home's resume
  closing a task just opened while a change waits) was **not constructed** either: two fast one-line tries put the tap
  into the outgoing Kalender window, and B.11's waiting change is finished before the PIN lock is up.
- B.12's lines on these starts: 2-47 ms for each call, 126-829 ms for the setup.

**C, Kalender in both looks** (sets of pictures compared pixel by pixel below the status bar).

- **Mørk is main's picture** with the system dark and light, on the day view, an event's page, the kid's own event,
  the delete dialog, the editor, both pickers and Varighet - but for the time-dependent rows and digits, a keyboard
  glyph, and one improvement: Varighet's choices are readable in Mørk on a system-light phone (almost invisible on
  main). The pickers' "AVBRYT"/"OK" in Mørk are as faint as on main: unchanged by step 1.
- **Lys** reads well on every one of them; the pressed row's ripple is visible; today's number while another day is
  chosen is peach on white, faint but legible; the delete dialog is a white panel with dark text and accent buttons.
  The system keyboard follows the system's theme under either look.
- **3-button navigation, the code QA's M1**: in Mørk the bottom of each screen is main's picture in both system
  modes (no scrim band on either build on this image). **Finding**: the three buttons follow the **system's** dark
  theme, not the page - Lys with the system light: dark grey buttons on the paper, clear, the keyboard up too; **Lys
  with the system dark: white buttons on the paper, nearly invisible**. SystemUI's `LightBarController` under that
  page: `mHasLightNavigationBar=true` (the page asks for dark buttons), `mNavigationLight=false`,
  `mForceDarkForScrim=true` - SystemUI's own override, inferred from its dump. A real phone's bar is unknown. Nothing
  built.
- The key under the day list: part-way down the list its last word runs under the "+" disc in Lys as on main; at the
  list's end it is clear of the disc in both looks. The neighbouring days' rows peek at both edges in both looks.
  Neither is step 1's.

**D, the screens that are not pages.**

- System dark on and off, Mørk: this build is main's picture on Home, the phone book, the contact card, Meldinger's
  list and a thread, kid Settings' lists (Wi-Fi, Bluetooth, sound), the drawer, the parent PIN dialog, the link-blocked
  notice, the PIN lock's glance and keypad, the emergency menu, the time-rule screen, an incoming and an ongoing call -
  but for clock digits, the debug "Utseende" row and Bluetooth's state text. Lys against Mørk on this build: the
  same, but for the row and its preview.
- With Lys applied and the system's dark theme flipped twice under each of them (the glance included): no
  `wm_relaunch_activity` and no new create anywhere, the incoming and the ongoing call included.
- A change of look during an ongoing call (kid Settings under the call's heads-up, Mørk then Lys): both applied, no
  lifecycle event on the call screen.
- The phone book's and Meldinger's cold opens in Lys on a system-light phone: night (090A11) in every frame.

**E.** TalkBack itself was **not run**: switched on under the kiosk it brought Android's "App is not available"
notice (`BlockedAppActivity`), and it was switched off again. The nodes instead (`uiautomator dump`): the heading
"Utseende", a group "Utseende" with "Mørk" and "Lys" as checkable choices, the applied one checked, the caption; the
preview strip and its two texts are not in the tree. Font 1.3: the segments 48 dp with whole words, the strip 56 dp
with both bubbles inside, the caption on three lines; Kalender's screens, dialogs and pickers in Lys fine.

**F.** The image has no `accessibility_force_invert_color_enabled` setting and the flag's state is unread. With the
key written by hand and the system dark, a cold Kalender in Lys was the same picture as without it; the key was
deleted again. Android's own switch was not looked for in its Settings; nothing is judged. The release build's half
was not run (no release APK).

**Not run**: TalkBack itself; Android's "make more apps dark" switch; L4's death between the write and the removal;
L5; the phone book through a `tel:` link in another app (a new-task `DIAL` from the shell instead); the control on
main on a wiped AVD; a release build; the channels' fix; anything on a phone. Pictures of the run are in the session's `scratchpad/kid-look-1/shots/`.

### The run of 2026-11-11 on `kid-look-2` (`jelly36`): every open path in both looks, B.3-B.6, B.17-B.20

`kid-look-2` at 1ae78e97, revision 3's mechanism (the debug APK from one gated `assembleDebug
-PwarningsAsErrors=true`, the emulator down; 0.23.6-debug, code 116, SHA-256 `0ae6662e...df32a69`, the installed file
the same), provisioned as Device Owner on `jelly36` put back from the clean copy taken before `kid-look-1`'s first run
(22 files, SHA-256 identical, so no app night mode in the system's record; `dumpsys uimode` named none). The test
server was the binary the earlier runs used (no cargo). System language en-US, the launcher's `nb` from the policy,
the kiosk on unless said, gesture navigation, the launcher's own PIN lock, `-timezone Europe/Oslo` (the times below
are the phone's). §6w's calendars with made-up names only. **A "reboot" is `adb shell reboot -p`, the memory gate read
with the emulator down and a cold start**: 8046 MB at the first start, 7769 and 7758 MB at the two reboots; the
emulator was never killed and went down by `reboot -p` at the end. `logcat -s PageLook` and the main and events logs
were kept for the whole pass (11:38:05-13:14:49). Every boot on this host has ANRs in SystemUI and Google's apps and a
"System UI isn't responding" dialog; none is the launcher's. Every line is **seen** or **measured** on `jelly36` unless
it says **read** or **inferred**.

**The measure.** 68 recordings with the device's own `screenrecord`, every frame stepped and sorted by the share of
the page area that is the night ground (`0C0C14`) or the paper ground (`F6F2EF`): NF/LF a flat night/paper frame
(97 % or more - a starting window), N/L mostly night/paper, X neither (a day view with its cards, or the overview
shrinking), B black. Home itself is N: 78 % night, at most 1.3 % paper (its labels). An open in Mørk is clean with no
L or LF frame and paper never above Home's 1.3 %; an open in Lys is clean with no NF or N frame after the first X, L or
LF frame (the N frames before it are Home with the press; the X frames of a tile open are the system's cross-fade out
of Home, night falling) and night at 0.1 % or less once the paper ground is up. In the table:
the film's frames / the frames in the second after the first changed one, then the kinds that matter. "Cold" made
the task, "warm" brought it forward.

**No frame of the other look on any path, the starting window included.** No page was relaunched (0
`wm_relaunch` lines for a `.day.` class in the whole events log), every cold open had one `wm_on_create_called` for
the look's class, and the starting window's theme was the look's every time (`Checking theme of starting window`:
`KidPageNightTheme` in Mørk, `KidPageLightTheme` in Lys, also for the snapshot windows of the warm opens).

| Path | Mørk (largest paper share) | Lys (night once the paper ground is up) |
|---|---|---|
| Home's tile, cold, the phone's theme the other way | 90/19, N 29 NF 37, 1.2 %; again 51/14, 1.3 % | 67/15, N 6 X 1 L 23 LF 13, no NF; again 63/13, no NF |
| Home's tile, warm, the same | 43/17, 1.2 %; 43/25, 1.3 % | 40/19; 40/16; no NF |
| Home's tile, cold, the phone's theme the look's own | 53/16 and 65/24, NF 8-10, 1.3 % | 66/23 and 71/19, N 13-15 (Home) then X, L 4-5, LF 10-11, no NF, 0.1 % |
| The PIN lock's glance, the reminder row and the code, cold | 85/32, 0.2 % | 86/29, 0.1 %; again 79/21 |
| The same, warm | 79/33, 0.8 % | 71/34, no NF |
| The time-rule screen's Kalender row, cold | 66/27, 0.8 % | 51/27, no NF |
| The same, warm | 45/32, 0.3 % | 54/28, no NF |
| Day view to an event | 69/29, 0.4 % | 70/31, 0.1 % |
| The event to its edit page ("Endre") | 84/32, 0.7 % | 90/29, 0.0 % |
| Back to the event | 56/27, 0.1 % | 53/6, 0.0 % |
| Back to the day view | 18/13, 0.1 % | 19/12, 0.1 % |
| The "+" (a new event) | 80/31, 0.5 % | 65/28, 0.1 % |
| Back from it | 45/30, 0.1 % | 45/10, 0.1 % |
| Home and the tile again, the event's page open | 89/18, 1.3 % | 91/20, no NF |
| The Recents card, the process alive, the kiosk on (a tap on the card) | 47/17, X 1 N 32, 0.1 % | 51/23, X 4 L 35, 0.0 % |
| After a process kill, the kiosk on: **no card**, `am task focus` | 86/31, N 32 NF 20, 0.2 %, `EventActivity` created | 50/29, X 6 L 22 LF 16, no NF, `LightEventActivity` created |
| After a process kill, the kiosk on, Home's tile | 57/29, 1.3 % | 69/31, no NF |
| After a process kill, the kiosk off: **the card's own tap** | 80/29, X 3 (the overview) N 38 NF 31, 0.2 % | 73/39, X 2 L 30 LF 31, 0.0 % in every frame |
| After a reboot: **no task left**, Home's tile | 48/19, N 18 NF 8 X 9, 1.2 %, one create, `DayActivity` | 47/26, N 24 (Home) X 9 LF 9 L 1, no NF, one create, `LightDayActivity` |
| The first open after a change of look | 49/14, 1.2 % | 57/13, no NF |
| The first open after a change of language | 62/12, 1.2 % (`[nb]`) | 70/14, no NF (`[en]`) |
| The first open after a forced other look (B.17-B.19, five) | 43/27 and 41/27, 1.2-1.3 % | 66/33, 44/24 and 60/29, no NF |

"The glance's row, the reminder row" is one row on this build: the PIN lock's glance opens Kalender from its reminder
row only; the other lock-side way in is the time-rule screen's Kalender row (the rule's `calendar_allowed` set in the
test database for it). The PIN lock is a secure window and records black, so the glance's films show the page's
window coming in over black (37-55 black frames, then the night ground rising in Mørk and the paper rising in Lys,
the other ground's share as in the table); what the lock itself drew at the tap is not in them. The films of the two
unlocks after the reboots: 26-27 black frames for the same reason, then Home; night at most 84.1 %, paper at most
1.3 %, Home created once, no relaunch line.

**After a reboot there is no card**: before each power-off Kalender's task (an event's page over the day view) was in
`dumpsys activity recents`; after the boot no `.day` task was there, before the unlock or after it, in either look
(the second run above saw the restored cards go when the kiosk starts). So every open after a boot is a cold one from
Home or the lock. `get-app-locales` read `[]` at `sys.boot_completed`, with no launcher process yet, and `[nb]` at the
first read with the launcher in front; `Launcher language: nb` once per boot (12:53:07.040, 13:01:25.166), no warning,
the lock's texts Norwegian, `LOCKED` after both. Whether the stored record had kept the languages was not separated
from the launcher's own ask.

**B.3-B.6, a change of look with a page task alive: passed with Home as the kiosk's first task.** Three changes (to
Lys from the day view's task; to Mørk and to Lys with an event's page in the task): the recents count 1 to 0 within
0.3-0.4 s, both activities destroyed (`finish-and-remove-task`), kid Settings in front in every frame (films of
39-47 frames, all N), `LOCKED` before with Home's and Kalender's tasks and after with Home's alone, no "App is not
available", the next open the day view in the new look. B.6 in both looks: 0 destroy or finish events, 0 `PageLook`
lines, the count stays 1.

**Finding 1: under the kiosk, right after a launcher process kill with Kalender's task alive, a change of look drops
kid Settings to Home.** The kill removes Home's task and the new process gets a new one, so Kalender's task is first
in `mLockTaskModeTasks` - the lock task's root. Removing the root makes the system clear every locked task:
`wm_destroy_activity [.., HomeActivity, finish-imm:clear-task-all]` and `wm_finish_activity [..,
KidSettingsActivity, clear-task-all]` 1-64 ms after the page task's `finish-and-remove-task`, then a new Home task.
Seen three times (12:17:11.706, 12:20:06.009, and recorded at 12:21:38.335: kid Settings gone and Home in front about
1.3 s after the tap; 65 frames, N 62 X 1, none of a page). The look was stored right each time, `LOCKED` after with
the new Home task alone, no crash, and no frame of the other look. B.3's "kid Settings stays in front" fails in this
state only. The same clearing came in both forced runs under the kiosk below (Home created again). It is what
revision 3's removal of a task adds to the older state of finding 2.

**Finding 2: in the same state Back does not leave Kalender.** With Kalender's task the lock task's root, the Back key
(three times) and the page's own back button (once) on the day view did nothing: `I ActivityTaskManager: Not finishing
task in lock task mode` (12:48:32.313, 12:49:00.712, 12:49:04.856, 12:49:14.336). The Home key works and the tile
brings the same task back; the overview shows no card for it (six tries in both looks). With Home as the root, Back on
the day view finishes the task as it should. The state ends when the task is removed (a change of look, with finding
1's effect) or at a reboot. **Inferred** older than design 78: main's manifest already gives the day view the task of
its own (`taskAffinity` `.day`); main was not run for it. **Both findings: cause and fix in §6ai** (Home's record
removed at the kill; reproduced on main's tip, gone with `stateNotNeeded`).

**A change of language with an event's page in front** (English, then Norwegian, saved in the PWA): nothing on the
page for 6 s; at the Home key two `W LauncherLocales: Couldn't set the launcher language` (12:27:59.732 and .748;
12:29:28.365 and .375, `Can't change activity type once set`), the page's task closed, and `Launcher language: en`
12:28:01.753, `nb` 12:29:30.383 from the late ask - step 15's "refused twice, then set late". Home's
`wm_on_resume_called` to the page's `wm_destroy_activity`: 587 ms and 455 ms, the late ask 2.0 s after the resume.
Home was relaunched once each time (`wm_relaunch_resume_activity` 12:28:01.751, 12:29:30.380): 5 black and 4 flat
night frames, then 3 black and 1 (films of 54 and 53 frames) - Home's own, night, no paper frame.

**B.17-B.19, a page of the other look's class: the check closes it every time.** The file written by hand and the
process killed as in the steps. In all five: one starting window of the task's own class for about a second (the old
look's - the step's "one night starting window", and a light one where the task was Lys's), the `E PageLook` line,
nothing of the page drawn, the crash buffer empty, the recents count 0, no `W PageLook`, Home in front, and the next
open from Home's tile in the stored look from its first frame with one create (the table's last row).

| Run | Kiosk | The route | The line | The film |
|---|---|---|---|---|
| Mørk's task, `light` stored, the day view the root | on | **no card after the kill**: `am task focus` | `DayActivity was created in the light look: its task is closed` 12:31:52.506 | 90/30, a night window, paper 1.3 % |
| Lys's task, `dark` stored, an event's page over the day view | on | **no card**: `am task focus` | `LightEventActivity was created in the dark look: its task is closed` 12:33:49.346 | 96/40, LF 33 (the light window, about 0.9 s), then Home; no day view under it |
| Mørk's task, `light` stored, the root (B.17) | off | **the card's own tap** | `DayActivity was created in the light look` 12:38:24.729 | 64/27, X 3 (the overview) N 29 NF 24, paper 1.2 % |
| Lys's task, `dark` stored, an event's page | off | the card's own tap | `LightEventActivity was created in the dark look` 12:40:12.766 | 64/30, X 4 L 12 LF 24, then Home |
| Mørk's task, `light` stored, an event's page (B.18) | off | the card's own tap | `EventActivity was created in the light look` 12:41:58.220 | 57/36, X 3 N 25 NF 19, paper 1.2 % |

- B.17's card: with the kiosk on the overview has no card for Kalender after the kill (the task is in `dumpsys
  activity recents`), so the card's own tap was run with the kiosk off, where the card shows - with the task's old
  picture on it - and the tap is a real `input tap` on it.
- B.18: both records gone each time (`LightDayActivity`/`DayActivity` destroyed `finish-and-remove-task`), no day view
  under the closing event page in any frame.
- B.19: `LOCKED` before and after in both kiosk runs, Kalender's task out of the list, no "App is not available". But
  "Home's task still in `mLockTaskModeTasks`" holds only as a **new** Home task (finding 1's clearing: Home finished
  `clear-task-all` 12:31:52.520 and 12:33:49.369, created again, `LOCKED` with the new task alone).
- The kiosk off and on for these runs was `kiosk_desired` in the test database: off in 5 s (the system finished
  Kalender's task, `clear-task-all`); on again after 25 s - the first `startLockTask` failed with an event's page in
  front (`W HomeActivity: Lock task change failed - retried at the next resume`) and held at Home's next resume.

**B.20: no refused change.** Over the whole kept log: `E PageLook` 5 lines, the five forced ones above; `The look
stays` 0; `The kid's look wasn't read` 0; `W PageLook` 0. `Can't change activity type once set` 4, the two language
changes' warnings. No crash and no ANR of the launcher (`am_crash` 0; 20 `ANR in`, none of `me.vibb`); the one `FATAL
EXCEPTION` is the shell's own `uiautomator dump` timing out at 11:41:27, during the install's load. B.12's lines on
the 16 process starts: the language 4-30 ms and the setup 146-547 ms after the kills, 8 and 1812 ms at the install,
306 and 4765 ms after the first reboot, 10 and 547 ms after the second.

**Not run**: the kid Settings row jumping back (B.20's refused change cannot be forced by hand); a Recents card after
a reboot (no page task survives a boot) and the card's tap after a kill under the kiosk (no card shows; `am task
focus` instead); finding 2 on main; A's status-bar icon frame counts and its theme and Battery Saver flips under an
open page; B.7's reboot within seconds of the tap, B.8, B.10, B.11 and B.13-B.15 as written (a reboot in each look and
one language change each way were run instead); C-F; a release build; anything on a phone. The pictures taken for the
eye (Kalender's day view, an event and the edit page and kid Settings' "Utseende" in both looks, Home once) are in the
session's `scratchpad/kid-look-2/shots/`; the pages' times are in the 12-hour form there ("8:30 a.m.": the emulator's
en-US system, inferred), which is not the look's.

## 6ai. Home stays the lock task's root after a process kill (kiosk-root, 2026-11-11)

§6ah's two findings under the kiosk, fixed by `android:stateNotNeeded` on `HomeActivity`
(`docs/notes/launcher/kiosk-escapes-and-updates.md`, "Home stays the lock-task root after a process death").

**Setup**: `jelly36` as §6ah left it (the debug launcher as Device Owner, enrolled, the kiosk on, the launcher's own PIN
lock, gesture navigation), the test server §6ah used; no `adb root` (a `user` build): the kill is the launcher's own uid.
Kalender is the tile at (388, 312), Meldinger (240, 312), kid Settings (240, 616), its Mørk/Lys at (145|335, 546), a
page's own back button (58, 84). Each check:

```sh
adb install -r app-debug.apk                       # the build under test, then unlock
adb shell input keyevent 3; adb shell input tap 388 312   # Kalender (or 240 312, Meldinger)
adb shell input keyevent 3                          # Home in front, the page's task stopped behind it
adb shell dumpsys activity activities | grep -E "mLockTaskModeState|mLockTaskModeTasks" -A3
adb shell "run-as me.vibb.launcher.debug sh -c 'kill -9 \$(pidof me.vibb.launcher.debug)'"
# the PIN lock comes back: unlock, Home; the lock task's order again
adb shell input tap 388 312; adb shell input keyevent 4   # the page from its tile, Back (or tap 58 84)
adb logcat -d | grep "Not finishing task in lock task mode"
adb logcat -b events -d | grep -E "wm_(finish|destroy|restart|create)_activity|wm_task_(created|removed)"
# the change of look: after the kill, Home, kid Settings (240 616), tap the other look, recorded with screenrecord
```

**Before, on main's tip (610eec36)**: both findings reproduced. The kill with Home in front:
`wm_finish_activity [0,137220399,103,..HomeActivity,proc died without state saved]` 13:50:16.460, `wm_task_created 106`
and a new Home record in it, the old Home task 103 removed 3 s later; the lock task's order went from `#0 #103 home,
#1 #105 .day` to `#0 #105 .day, #1 #106 home`. Kalender from the tile, then the Back key and the page's own button:
`Not finishing task in lock task mode` 13:50:45.937 and 13:50:49.008, the day view still in front. Kid Settings, the
tap on Mørk: `finish-and-remove-task` of the day view, then `wm_destroy_activity [.., HomeActivity,
finish-imm:clear-task-all]` and `wm_finish_activity [.., KidSettingsActivity, clear-task-all]` at 13:50:57.716, a new
Home task 108 at once, Home in front at the read 3 s after the tap; the look stored, `LOCKED` with task 108 alone.

**After, the fix (e490362f)**: `dumpsys activity activities` shows `stateNotNeeded=true` on Home's record. Eight kills
with Home in front: no `proc died without state saved` (the three in the whole log are main's install, the kill on
main above and the fix's install - a package replace removes every record), `wm_restart_activity` on the **same**
Home record in the same task (110 throughout), no new Home task, and the order before and after every kill `#0 #110
home, #1 <the page's task>`, `LOCKED`.

| Check (the kill with Home in front first, unless "plain") | Result |
|---|---|
| Kalender (Mørk), the Back key | Home in front, the day view `app-request` finished, task 112 removed, no refusal |
| Kalender, the page's back button | the same (task 114) |
| Kalender alive, kid Settings, Mørk to Lys | kid Settings in front throughout (film: 41 frames, all kid Settings' night ground; paper at most 6.0 %, its own Lys preview), the day view's task `finish-and-remove-task`, `LOCKED` with task 110 alone |
| Kalender (Lys) alive, Lys to Mørk | the same (40 frames), `LightDayActivity` removed |
| Meldinger, the Back key / the page's back button | Home in front, `MessagesActivity` finished, its task removed, no refusal |
| Meldinger alive, Mørk to Lys and back | kid Settings in front (43 and 34 frames, all kid Settings); Meldinger's task stays (this build closes only Kalender's at a change of look) |
| plain: Kalender, the Back key; Meldinger, the Back key | Home, the task removed |
| plain: Kalender alive, Mørk to Lys and back | kid Settings in front (43 and 40 frames), the day view finished (`finishIfPossible`), task removed |

No `Not finishing task in lock task mode` after the fix's install, `LOCKED` at every read, no crash of the launcher.
One ANR of the launcher at the cold boot (13:47:42, the build before both installs, in the boot's wave of ANRs in
SystemUI, Phone, GMS and the keyboard - §6ah's "every boot on this host"). The logs and films are in the session's
`scratchpad/kiosk-root/steps/` and `rec/`.

**Not run**: the `.call` task (no call placed - the same rule covers it: the cause is Home's record, not the page);
a crash of the launcher (no hook to force one; AOSP takes the same `handleAppDied` path after the crash's finish of
Home is blocked); a release build; a phone.

## 6ak. Mobilnett: Bare Vibb, the roaming lock and the mobile-network settings lock (designs 94/95) (2026-11-13)

**Steps, written with the build on 2026-11-13 (branch `mobilnett`); not run yet.** Design 95 revision 3: the VPN's
three shapes (filter on Wi-Fi, the sink for Bare Vibb on cellular, the pass shape standing ready when the filter is
off), one owner of always-on and lockdown, design 94's `DISALLOW_DATA_ROAMING` with its relock, and
`DISALLOW_CONFIG_MOBILE_NETWORKS`. The PWA's page is Device › Mobilnett (`/devices/{id}/mobile`).

Setup: **`jelly36`** (§3), the debug launcher of `mobilnett` as Device Owner, enrolled to a local server of the same
branch, the kiosk on. Wi-Fi on at the start. For the roaming steps (D) the stock AVD reads `roaming=false` under
`gsm data roaming` (the SIM and the network share MCC 310 and the name, `isSameNamedOperators`): use design 95 §1.1's
private image copy with a Norwegian SIM (`-sysdir`, `CIMI 242010000000000`) and its APN (the probe added one with
`addOverrideApn`; the launcher doesn't - add it through a throwaway DO-less step or Settings › APNs during Åpne for
oppsett). A second app to test with: any allowed browser, or `me.vibb.probe.other` from the d95 scratchpad, fetching
`https://1.1.1.1/cdn-cgi/trace` (an IP literal: TCP) and `https://www.google.com/generate_204` (a name: DNS).

```
adb logcat -s KidVpnService MobileNetwork AppEnforcer          # "Tunnel: sink", "Always-on: ... lockdown true (sink)"
adb shell dumpsys connectivity | grep -iE "VPN|lockdown|always"  # the VPN network, its routes; lockdown
adb shell settings get secure always_on_vpn_lockdown             # 1 only while the sink holds
adb shell dumpsys user | grep -E "no_config_vpn|no_data_roaming|no_config_mobile_networks"
adb shell run-as me.vibb.launcher.debug cat shared_prefs/*.xml | grep -E "vpn_inputs|vpn_shape"
```

### A. The filter still works on Wi-Fi (Mobildata: På)

1. Wi-Fi on, Mobildata "På". `Tunnel: filter`; lockdown off; a blocked domain in the other app's browser is blocked as
   before; an allowed one loads.
2. The launcher is outside the VPN now in every shape: `adb shell dumpsys connectivity` lists the VPN's UID ranges
   without the launcher's uid; a sync (`adb shell am broadcast` of the debug sync, or the PWA's nudge) works, and the
   server's name resolves through the network's resolver (the server's log shows the request).
3. **Split-horizon check** (design 95 §2.2): if the server's name only resolves through the parent's own DNS server
   (design 36), note whether the sync still works; report it either way.

### B. The sink on cellular, and the switch both ways

1. PWA: Mobilnett › Mobildata › **Bare Vibb**. The page lands on `#mobile-data`, never at the top; the phone's state
   line shows nothing new while on Wi-Fi.
2. `adb shell svc wifi disable`. Within a second: `Tunnel: sink`, then `Always-on: ... lockdown true (sink)`
   (the tunnel first, then lockdown). The other app: the IP literal fails at once (`ConnectException`, ~1 ms), the
   name fails at once (`UnknownHostException`); nothing hangs for seconds.
3. The launcher works: a PWA nudge (change a harmless setting) reaches the phone within seconds; Finn telefonen
   answers (GPS only: the emulator's fix is fake anyway). The summary in the PWA shows "Mobildata: bare Vibb".
4. **SSE and a sync survive the switch:** with the stream open (`adb logcat -s CommandListener`), switch Wi-Fi ->
   cellular -> Wi-Fi twice; the stream is never reopened because of the switch (no "stream closed" at the switch),
   and a sync started a moment before a switch completes.
5. `adb shell svc wifi enable`: lockdown off first (`Always-on: ... lockdown false (filter)`), then `Tunnel: filter`;
   the other app loads again.
6. **Lockdown is never off while sinking:** during step 2-4, run a few syncs (`adb shell am broadcast ...` or wait for
   the backstop) and a launcher process restart (`adb shell am crash me.vibb.launcher.debug` is refused for a DO; use
   `adb shell am kill` while it is in the background, or a reboot): `always_on_vpn_lockdown` stays `1` the whole
   time on cellular, and every `Always-on:` line on cellular says `lockdown true`.
7. Filter off (Nettfilter off in the PWA) with Bare Vibb on: on Wi-Fi `Tunnel: pass` (no DNS filtering - a blocked
   domain loads), on cellular `Tunnel: sink`. Check the pass shape doesn't break name resolution for other apps on
   Wi-Fi (design 95's pass shape has no DNS server: Android must use the network's; **report if names fail**).
8. Settings › Network › VPN: the VPN's settings are disabled by the admin while any shape runs.

### C. The pause lifts it

1. Bare Vibb on, cellular, sink up. Start "Åpne for oppsett" (PWA or the phone's parent PIN): the sink goes
   (`Tunnel: filter` or the VPN stops when the filter is off), lockdown off, other apps get cellular, the VPN settings
   unlock. The Mobilnett page says "Opphevet mens telefonen er åpnet for oppsett".
2. End the pause: the sink comes back at once on cellular.

### D. The roaming lock (design 94)

1. Default "Av (låst)": `no_data_roaming` set; Settings › SIM › Roaming greyed out "controlled by admin";
   `gsm data roaming` + `gsm voice roaming`: no data comes up. The PWA's page: "Av og låst på telefonen", and the
   summary's header ends with "Roamer (US)" (or the country the image reads).
2. The lingering session: start a pause, turn roaming on in Settings, let data come up on the roaming network, end the
   pause. `no_data_roaming` is set again, `settings get global data_roaming` reads `0`, data stays up (Android's gap,
   §2.4.1) - and now **the sink holds it** (`Tunnel: sink`, reason `roaming` in the report's `mobile_data.sink`): the
   other app fails at once, the launcher still syncs. The PWA shows "Bare Vibb bruker roamingdata fra før låsen".
   `gsm data home` then `roaming` (a re-evaluation) tears the session down; the sink goes with it.
3. "Telefonens valg": the restriction is cleared and roaming is **not** turned on by us
   (`settings get global data_roaming` unchanged). The page says "Telefonens valg: av".
4. Single-SIM emulator: the relock (§2.4.2) isn't seen here; `adb logcat -s MobileNetwork` shows no "relocking" line.

### E. The mobile-network settings lock

1. Default on: `no_config_mobile_networks` set. Settings › Network & internet › SIMs is disabled "by the admin"; the
   Quick Settings Internet tile's dialog has no mobile-data toggle.
2. Start "Åpne for oppsett": both are back; turn **Mobildata off** in Settings. End the pause: the lock is back, data
   stays off. The PWA's summary shows "Mobildata er slått av på telefonen" and the Mobilnett row "Mobildata av"
   within a sync (on Wi-Fi). On cellular nothing reaches the server (true zero) until Wi-Fi.
3. Switch "Lås mobilnett-innstillingene" off in the PWA: the restriction goes at the next sync; on again: back.

### F. The update line

1. With a launcher from `main` (before this round) reporting: the Mobilnett page's choices are disabled with "Virker
   etter at Vibb One er oppdatert"; nothing is enforced on the phone.

### The run of 2026-11-13 (`jelly36`, branch `mobilnett` at 1d0d4a9c, after the code QA fixes)

Setup: `jelly36` wiped (a copy kept and put back afterwards), the debug launcher as Device Owner over adb, enrolled to
a local server of the same commit, the PWA driven with form posts as the signed-in admin. The other app was
`me.vibb.probe.other` (design 95's probe, let out with Allow these apps and ticked). Phase 1 on the stock image;
phase 2 (D, M2) with a private `-sysdir` whose `iccprofile_for_sim0.xml` reads `CIMI 242010000000000` and `EF_AD` MNC
length 2 (the AVD's own `modem_simulator/` copy is overwritten from the sysdir at every boot - edit the sysdir's), and
an APN for 24201 added from the shell (`content insert --uri content://telephony/carriers ...`, allowed to the shell
user; no device-owner override needed).

| Check | Result |
|---|---|
| A.1 filter on Wi-Fi | **Passed.** `Tunnel: filter`, lockdown 0; a device-blocked `example.org` fails `UnknownHostException` in 7 ms, `generate_204` loads |
| A.2 launcher outside the VPN | **Passed.** The VPN's `Uids: <{0-10216, 10218-...}>`, the launcher is 10217; syncs and reports go on |
| A.3 split-horizon | Not run (no custom DNS server on the test server) |
| B.2 the sink on cellular | **Passed.** `svc wifi disable` -> `Tunnel: sink` in 385 ms, lockdown on 19 ms later; the other app: IP literal `ConnectException` in 1 ms, a name `UnknownHostException` in 4 ms. Bytes in the gap not measured (QA 4) |
| B.3 the launcher works on the sink | **Passed.** A PWA save reached the phone and its report was stored within 1 s; the summary shows "Mobildata: bare Vibb" |
| B.4 SSE across shape switches | **Passed.** Four filter <-> sink switches on cellular (Bare Vibb off/on): the same stream thread, no reconnect, the server saw no new open. Note: `apply()` writes the sink's lockdown 4-30 ms before the service's tunnel (L2, fail closed) |
| B.5 back to Wi-Fi | **Passed.** Lockdown off first, then `Tunnel: filter`; the other app loads |
| B.6 lockdown never off while sinking / QA 2 (M1) | **Passed.** 20 Wi-Fi <-> cellular switches: every cellular read `lockdown=1 sink`, every Wi-Fi read `lockdown=0 filter`; no "Failed to establish VPN" (the 20 "read failed" lines are the old tunnels' loops ending); the launcher's threads 58 -> 57. A process kill while sinking: lockdown stayed 1 every second through the restart, `vpn_shape` sink, the other app refused |
| B.7 / QA 5 the pass shape's DNS | **Passed.** Filter off + Bare Vibb: `Tunnel: pass`, the VPN has `DnsAddresses: [ ]`, and the other app resolves names (`generate_204` 204, `example.org` 200 - unfiltered, as the filter is off). Wi-Fi off: sink at once, lockdown on |
| B.8 VPN settings locked | **Passed.** Settings › VPN: "Blocked by work policy" |
| QA 6 (L1) hardening "Blokker VPN-innstillinger" off, sink up | **Passed - Android protects it anyway.** Without `no_config_vpn` the VPN page opens, but "Always-on VPN" and "Block connections without VPN" read "Controlled by admin" and a tap answers "Blocked by work policy": a user can't change a device owner's always-on or lockdown on Android 16 |
| C.1 / C.2 the pause | **Passed.** Åpne for oppsett from the PWA: `Always-on: none, lockdown false (none)` (QA 8: the `NONE` write succeeds, no "Couldn't set always-on"), the other app gets cellular, `no_data_roaming` and `no_config_mobile_networks` lifted, the page says "Opphevet mens telefonen er åpnet for oppsett" / "Låst opp ...". Ending it: lockdown and the sink back within 1 s. (`no_config_vpn` stays: it was the hardening switch's, which only the server lifts) |
| QA 1 (H1) reboot with no network | **Passed.** Bare Vibb, Wi-Fi off, `svc data disable`, reboot: from the first read after boot `lockdown=1`, `vpn_shape=sink`, `Tunnel: sink`. `svc data enable`: the other app's first five requests (one a second from the moment data came on) were all refused in 1-4 ms. Airplane mode + a process kill + airplane off: the same, 6 of 6 refused |
| D.1 roaming locked | **Passed.** `gsm data roaming`: data goes down and stays down, `data_roaming` 0; the report `roaming: true, country: "us", network: "311740"`; the summary's header ends "Roaming (US)" in orange |
| D.2 the lingering session | **Passed.** In a pause `data_roaming 1`, roaming data up; ending the pause: `data_roaming` 0 again, the roaming network stays up (Android's gap) and the sink holds it with Bare Vibb off (`mobile_data.sink: "roaming"`, `data_active: true`): the other app refused, the launcher reports. The page: "Bare Vibb bruker roamingdata fra før låsen". `gsm data home` -> `roaming` tears the session down |
| QA 3 (M2) | **Passed.** Filter off, Bare Vibb off, the lock on, the lingering session up: Wi-Fi on -> `Tunnel: pass` (the cellular session stays up in the background); Wi-Fi off -> `Tunnel: sink`, lockdown 1, the other app refused on the lingering network |
| D.3 Telefonens valg | **Passed.** The restriction goes, `data_roaming` stays 0 (nothing turned on), the page says "Telefonens valg: av" |
| QA 7 (L6) | **Passed.** Telefonens valg, then Av and the page read at once: "Ikke på plass på telefonen ennå..." (`pending`), never "Låst opp mens ..."; after the sync "Av og låst på telefonen" |
| E.1 the settings lock | **Passed.** Network & internet: "SIMs - Controlled by admin"; the Internet panel has no mobile-data toggle (`mobile_toggle` absent) |
| E.2 data off, true zero | **Passed.** During the pause the toggle is back; "Turn off mobile data"; the pause ended: the lock is back, `mobile_data` 0, on Wi-Fi off "Active default network: none" and the other app gets nothing. The report `enabled: false`; the summary "Mobildata er slått av på telefonen" and the row "Only Vibb · Mobile data off" |
| E.3 the switch | **Passed.** Off: `no_config_mobile_networks` gone at the sync; on: back |
| F the update line | Not run (no older launcher installed; the page tests cover it) |

**Found, open (not caused by the shapes, but felt more with them):** after the network the SSE stream ran on went
away (cellular data switched off, or the roaming session torn down), the phone kept the dead stream until its 5-minute
read timeout: twice the PWA's nudges were not heard for ~5 minutes although Wi-Fi was up and the server had a stream
"open". Reports went on at syncs. Hypothesis: on the emulator the cellular interface keeps its address when data goes
off, so netd destroys no sockets; on a phone the address goes and the socket with it - **[needs device test]**. Either
way the stream should reconnect on a change of the default network (design 95 §3.5's ping interval, 95-T1's
`ServerClients`). Once (D.1) the sink also held ~55 s after Wi-Fi came back before the watcher saw Wi-Fi as best -
fail closed, but worth a look with the stream fix.

**Fixed on `stream-reconnect` (2026-11-13):** `CommandListenerService` follows the default network
(`sseReconnectOnNetwork`, settled 1.5 s): a stream on a network that went away is dropped with the shared pool's idle
connections and opened on the new one; while backing off, a new network cuts the back-off short. Checked on `jelly36`
(no device owner - the stream doesn't need one): the stream on cellular, `svc data disable; svc wifi enable` - the dead
stream dropped 1.8 s after, the open with no network failed (`ENETUNREACH`), and the stream was up on Wi-Fi 5.9 s after
the switch (before: ~5 min); a PWA nudge 15 s later arrived at once. Wi-Fi off with the stream on Wi-Fi: up on
cellular after 2.2 s. One reconnect per change, no storm. The ~55 s sink hold was not reproduced and its cause is not
clear (the shape watcher's best-matching callback, or Wi-Fi's validation under lockdown); left open, [needs device
test].

### What `jelly36` can't show

The Jelly Star's dual-SIM relock (design 94 §2.4.2), VoLTE/MMS/Wi-Fi calling under lockdown and a real locate indoors
with the sink (design 95 §2.4): [needs device test].

## 6al. Mobilnett revision 4: "I utlandet", the roaming notice, the kid's switch (design 97) (2026-11-14)

Design 97 §10's tests. Setup: **`jelly36`** restored clean from `~/kid-look-1-avd-pre-start`, started under `flock -o
~/build.lock` with a private `-sysdir`: symlinks to the SDK image's files and a copy of its `data/`, whose
`misc/modem_simulator/iccprofile_for_sim0.xml` reads `CIMI 242010000000000` and `EF_AD` `00000002` (MNC length 2), so the
SIM is `24201` (no) on the network `311740` (us). An APN for 24201 from the shell (`content insert --uri
content://telephony/carriers --bind numeric:s:24201 --bind mcc:s:242 --bind mnc:s:01 --bind apn:s:internet --bind
type:s:default,supl ...`). The phone made through the PWA's own Add Phone (so a new phone's defaults: `abroad` `vibb`,
mask 47), the debug launcher as Device Owner, enrolled from its Settings. The other app is design 95's probe rebuilt as
`me.vibb.probe.other` with a `cellfetch` command (`requestNetwork(CELLULAR)`, then `network.openConnection` and a raw
socket through `network.bindSocket`), let out with Allow these apps. `gsm data roaming` + `gsm voice roaming` makes the
phone abroad; `gsm data home` + `gsm voice home` brings it home.

**Emulator limits:** the network operator (`311740`) is fixed in the modem simulator, so a second country (Denmark) can't
be faked - D6's per-country rule rests on `mobile_network::tests`. The modem forgets `gsm data roaming` at every radio
power cycle (airplane mode, a reboot): it comes back registered at home. `cmd phone restart-modem` is refused to the
shell and the console has no SIM power command, so no SIM reload.

| Test | Result |
|---|---|
| 1 abroad, Bare Vibb | **Passed.** `Tunnel: sink`, lockdown 19 ms later; the other app's IP literal `ConnectException` in 2 ms, a name `UnknownHostException` in 4 ms. The report `where: "abroad"`, `sink: "abroad"`; one `roaming_started` (`us:<instant>`, to `/devices/1/mobile#abroad`). Three home/roaming flips with a sync each: still one notice, the place cleared at home and back to `us` |
| 2 a flap and a reboot | **Passed after a fix.** Airplane mode under the sink: sink and lockdown held while no network was seen. **Found:** `gsm data unregistered` (out of service) kept the cellular network up with `-NOT_SUSPENDED+NOT_ROAMING` in one update and a stale operator, so `where` read home, the shape went to filter for ~25 s and the other app got a 200 in 69 ms (the emulator still passes traffic). Fixed in the build: a cellular network without `NOT_SUSPENDED` (or whose `where` is unknown) is a doubt that keeps the persisted sink; `getDataState()` was tried first and lags the callback by some ms both ways (it held the sink at home once). After: three flaps, lockdown 1 on every sample (203 of 203), the other app refused in 1-2 ms throughout; Wi-Fi, cellular abroad, cellular home (filter, 200) and abroad again all move as before. A reboot in airplane mode: lockdown 1 from the first read after boot, `Tunnel: sink` |
| 3 the upgrade | **Passed.** `launcher-v0.40.0-rc.1` built as debug from the tag, run under the parent's Bare Vibb on cellular (`vpn_inputs` 1,1,1, sink). Its leftover second record removed, the server stopped, r4 installed over it: lockdown 1 and `vpn_shape` sink from the first read; the second record was written a second later by `apply()` from the cached policy; the other app refused. The other way (rc.1 over r4) kept the sink too |
| 4 Android's switch | **Passed, but the SIM reload not run.** Ingen data: `data_roaming` 0, `no_data_roaming`, "Androids roamingbryter: av og låst". Bare Vibb: the restriction cleared, nothing written, "av – slå den på én gang …". Turned on during Åpne for oppsett ("åpnet for oppsett nå"); after: "på – låst, Emma kan ikke endre den"; still 1 after a reboot. Back to Ingen data: 0 and locked |
| 5 national roaming | **Passed.** SIM `311480` (MNC length 3) on `311740`: `roaming: true, where: "home"`; under Bare Vibb the filter (no sink), the other app loads, no notice; under Ingen data "Nasjonal roaming: Ingen data stenger mobildata her …" (en too). Airplane mode abroad with Wi-Fi on: the report `where: "unknown"`, the place kept |
| 6 the Wi-Fi bypass | **No leak.** Abroad, roaming data up, on Wi-Fi: `cellfetch` gets the cellular network in 1-2 ms, and binding to it fails `EPERM` for HTTP and a raw socket in 0-3 ms - under Bare Vibb (filter shape), Som hjemme (filter), and Bare Vibb with the filter off (pass shape). The control, Som hjemme with the filter off (no VPN): HTTP 200 in 58 ms, the raw connect in 12 ms |
| 7 the kid's switch | **Passed.** Bit 32 alone (the Home form's `section=quick` with nothing ticked keeps it): the row in its card. Off: the line "Only Vibb One uses mobile data. Wi-Fi works as before."; Wi-Fi filter; cellular `sink`/`kid`, lockdown 1, the other app refused; the report `kid: "off"`; the PWA "Mobildata: bare Vibb – Emma har slått av", "Emma har slått mobildata av", the row value "Mobildata av (Emma)". The parent's Bare Vibb: Off at 50 %, "Set by mum and dad". The bit hidden from the PWA: no row, `kid_mobile_off` false; shown again: On |
| 8 the pause | **Passed.** The abroad sink lifted 0.9 s after the PWA's POST (the other app 200), back 0.4 s after the end. During the pause the kid's row is the kid's own; its Off brought the `kid` sink with the pause running. Ingen data's lock lifted 1.3 s after the POST (the nudge and the policy fetch included) and came back at the end |
| 9 the page | **Passed.** Each save on its anchor (`#abroad`, `#mobile-data` for both forms there, `#mobile-lock`); no bell; the Home form keeps bit 32 on `quick` and the whole form; its footer names the switch; nb and en, light and dark, at 390 px |

## 6am. Flymodus through the Digital assistant role, the rule screen's sheet, Foreldrekode (design 98) (2026-11-14)

Design 98 §12's tests. Setup: **`jelly36`** restored clean from `~/kid-look-1-avd-pre-start` (22 of 22 sums), started under
`flock -o ~/build.lock` (no `-wipe-data`: the userdata partition needs 7.4 GB free), the branch's debug launcher as Device
Owner, a server of the branch with `LAUNCHER_ADMIN_COMPONENT` set to the debug component, the phone made through the PWA's
Add Phone (mask 111: bit 64 on, D1), enrolled with a typed code. The parent's Settings is reached through the drawer's
settings button (swipe up past the grid), or the setup notification. `POST_NOTIFICATIONS` granted with `pm grant` (on a
phone Home asks for it at the first start). The emulator's network time is UTC, and the launcher forces it.

| Test | Result |
|---|---|
| 1 onboarding | **Passed.** The card's one step, Åpne for oppsett (`#oppsett`), the notification "1 step left", its tap to the PIN and the rows. The row's tap lifted `disallow_config_default_apps` before Android's page; Default digital assistant app › Vibb One › OK: the window closed 249 ms after `onReady` (the role at .841, closed .090), the restriction back, dialer and SMS ours, `assistant: true`, the step gone |
| 1b moved roles | **Passed.** HOME chosen as Pixel Launcher during the window: the HOME role stayed Vibb's at once. Caller ID moved to Phone during a later window: `default_apps_changed: ["call_screening"]`, the card's "Default apps changed: Call screening" |
| D10 Samtalefilter | **Passed.** The row (calls managed, roles not held) asks Android twice in the window; both roles ours, the window closed, the row gone. No adb |
| 2 on and off under the kiosk | **Passed after a fix.** **Found:** Settings' `AirplaneModeVoiceActivity` uses `Theme.DeviceDefault.Light.Voice`, floating, not `windowIsTranslucent` - `route_missing`. Fixed: translucent or floating is accepted. Then: the window 309 ms (on at ~190 ms), still LOCKED in Home's task, Settings hidden again, the lock-task list without Settings, `mDisabled1` back to 0x6000000; the `going_on` report stored before the `on` report. Off from the row: `no_airplane_mode` not set afterwards. 2b (power during the route) and 2c not run |
| 3 the parent's switch | **Passed.** "Blokker flymodus" on while on: off, `off_by: "parent"`, the row "Set by mum and dad" at 50 %; off again: the row works |
| 4 Wi-Fi in airplane mode | **Passed after a fix.** `Settings.Panel.ACTION_WIFI` is a panel on Android 16 (`SettingsPanelActivity`), and Wi-Fi on there wrote `wifi_apm_state=1`. The step ended on the panel's answer (`wifi_kept: true`, airplane mode off) and on the pause's end. **Found:** the route's own voice activity paused and resumed Settings, which ended the step before the panel opened - fixed (only a resume after a pause while the panel is up). The deadline path not run. A later turn-on with Wi-Fi on: Wi-Fi stayed up and the summary read "Airplane mode on since …" from a live report |
| 5 112 | **Passed after a fix.** **Found:** an emergency call's end of airplane mode is broadcast by `com.android.phone`, which the not-exported receiver missed (the period stayed open, no line). Exported (a protected broadcast). Then: off 109 ms after the call was placed, `off_by: "emergency"`, the event's `airplane_off: true` in the same intake ("The call turned airplane mode off."), and the row refused with the emergency line - **for an hour** (Vibb's callback window, §2.2 step 1), not 10 minutes |
| 8 reboot | **Passed.** Rebooted in airplane mode with a pause running: on after boot, the role kept, the rule back, Off before the first sync. A period missed by a dead process was closed as `other` at the next start. Mid-route: the DE marker written by hand and the process killed - "left open: putting it back" 0.5 s before the first sync, the marker cleared (a real kill inside the 0.3 s window not run) |
| 9 the role lost | **Passed.** "None" chosen in Android's own page during a setup window (allowed under the restriction, M2): `assistant: false` in a second (the service's `onShutdown`), the summary's warning, the card's step back |
| 10 the assist gesture | **Passed.** Under the kiosk, unlocked and at the keyguard: `KEYCODE_ASSIST`, long-press power, the corner swipe - no session shown (`mShown=false`); at the keyguard `mDisabled1=0x7200000` (with `DISABLE_SEARCH`). During Åpne for oppsett: nothing opens (D9) |
| 11 Off-only | **Passed.** Bit 64 off, airplane mode on during the setup window, then "Lås igjen": Kid Settings "Turn off airplane mode", Wi-Fi read-only, Mobildata "Airplane mode is on"; the tap turned it off |
| 12 the PWA | **Passed.** The Home form keeps 32 and 64 without `airplane_row` (97) and clears 64 with the row unticked (47); every save lands on its anchor; no bell; nb and en |
| 13 the sheet | **Passed** with gesture and 3-button navigation: "···" 8 dp under the status bar, clear of the clock; the sheet's last row above the navigation bar in both (818/782 px); Back and a tap outside close it; Flymodus on under the rule (423 ms, LOCKED, Settings hidden after), the rule stays |
| 14 Foreldrekode | **Passed.** Offline: 15 and 30 min offered (the rule ends 23:59), a wrong PIN counted, the right one: Home, `phone_lifts_v1` with exactly its keys. Lekser started during the pause and locked at 23:20:01. The alarm at the pause's end (.168) fired at the next second. The choices closed at screen-off. Online: the post (200, acked) before the GET, the row (`created_by` NULL, `phone_key`), `lifts_active: [1]`, the summary, the sub-line, the Time page's ⓘ, the inbox's "The time-rule screen until …"; "Avslutt nå": locked at the next sync. Reboot, then online: posted, the rule stayed locked. The clock set back 10 min: the rule back at once. Two rules: "Pause Lekser and Kveld". "Avslutt nå" before the phone's next policy not run (the `adopt` rows) |
| 15 Home's line | **Passed** in both modes: under the pause's banner, a tap turns airplane mode off |

**Also found:** after an Off the sync waited for the default network, which is the always-on VPN and read validated at
once - the sync ran before Wi-Fi was up. Fixed: a real validated network. Not run: 6 (Bluetooth), 7 (Gboard's mic).

### Addendum (2026-11-15): the shade during the window (Q99-H1)

Design 99's QA (Q99-H1): `setStatusBarDisabled` is only recorded under lock task, so the window didn't close the shade.
Fix: the window's features without `LOCK_TASK_FEATURE_NOTIFICATIONS`. Setup: `jelly36` restored clean (22 of 22 sums),
under `flock -o ~/emulator.lock`; design 98's server and data, the phone enrolled again with a new code; the assistant role
with `cmd role add-role-holder` (an adb shortcut). An on-device loop read `dumpsys device_policy` (`LockTaskPolicy` mFlags,
Settings in the list), `dumpsys statusbar` (`mDisabled2`) and `dumpsys window` (`mCurrentFocus`) every 60-80 ms. The
shade was opened with `cmd statusbar expand-notifications` right after the row's tap. Base features 127, `mDisabled2`
0x11 (0x15 = the shade disabled too).

| Run | Result |
|---|---|
| Before the fix | The old window emulated with the debug override (`add_features 2`). Settings stayed pinned 272-639 ms with `mDisabled2=0x11` and the shade open. A tap on Android's "Physical keyboards" notification 74 ms after the pin started `Settings$PhysicalKeyboardActivity` in the pinned package. It resumed at .077, step 7 cleared it (`clear-task-all`) at .098, and the hide killed it at .283 |
| Kid Settings, the sheet | Fixed: at the pin `mFlags= 125 mDisabled2=0x15`, and the shade left focus within one sample. SystemUI got the disable (`QSDisableFlagsLog ... QiNgR`) at .638, 9 ms before Settings' unhide started its process (.647). `expand-notifications` before every sample: never open during the window, open 300 ms after it. After: 127, 0x11, the shade opens |
| The close | **Found:** one sample read `pinned=1 mFlags= 127 mDisabled2=0x11`. A pass writes the features before the list, so the shade came back while Settings was still on it. Fixed: the close is two passes (`routeClosing`). After: `pinned=0 mFlags= 125`, then 127 (787/916 ms from the sheet, 1474/1617 ms from Kid Settings) |
| A kill mid-window | `run-as ... kill -9` 252-280 ms after the pin, from the sheet and from Kid Settings. The new process logged "left open: putting it back" 0.2-0.6 s later. Settings left the list at 1.0-1.4 s (a plan-less pass, still 125). The put-back's `apply()` wrote 127 at 1.9-2.7 s. The marker was cleared, Settings hidden, and the shade opened again |
| **Left** | A touch on the shade during SystemUI's ~300 ms collapse stops it, and the shade stays open for the window. In one run, three of five taps on the low-storage notification (`cmd devicestoragemonitor force-low -f`) started `MANAGE_STORAGE` with 0x15 in force. Storagemanager isn't pinned, so that start was blocked. A Settings-bound notification would have opened in the pinned Settings until step 7. A single tap 59 ms after the pin started nothing, but it left the shade open |

Not run: Android's own "Wi-Fi stays on in airplane mode" notification (the clean AVD has no `wifi_apm_state`). A
Settings-bound notification for the last row was not tried either: the shell can't make one on this image (`cmd
notification post -c` is refused), and the keyboard notification is gone after its first tap. Undo the test helpers with
`cmd devicestoragemonitor reset`, `allow_listener` for `BadgeListenerService`, and the override's `reset`.

## 6an. Snarveier, the kid's quick panel in the shade (design 99) (2026-11-15)

Design 99 §10's tests. Setup: **`jelly36`** restored clean from `~/kid-look-1-avd-pre-start` (22 of 22 sums), started
under `flock -o ~/emulator.lock` without `-wipe-data`, density 240 (the AVD's own), the branch's debug launcher as
Device Owner (§4's adb lines), a server of the branch (`LAUNCHER_ADMIN_COMPONENT` the debug component) on design 98's
data, the phone enrolled again with a typed code; migration 0148 set bit 128 on it. Sizes from `uiautomator dump`
(px / 1.5 = dp); the names' widths from the screenshot's light pixels per quarter cell. The assistant role with `cmd
role add-role-holder android.app.role.ASSISTANT` (an adb shortcut). Afterwards `jelly36` restored again (22 of 22 sums,
the same file list).

| Test | Result |
|---|---|
| 1 the panel | **Passed.** Posted at the enrolment's first sync (`Shortcuts: Starting the panel`), at the top under lock task, colorized, not bundled. Before the assistant step the default was Lommelykt, Lyd, Wi-Fi, Bluetooth (the default follows the role, Q99-L9). Unfolded on every pull: cells 82 px = **55 dp**, discs 36 dp, the names under them; a header tap folds it to the icons (cells 67 px = **45 dp**), the next pull unfolds it again |
| 1 the names | **Passed** at font scale 1.0. en at 11 sp: Flashlight 42.7 dp, Bluetooth 42.7, Sound 27.3, Wi-Fi 20.7, Data 20.0 (of 51 dp). nb (the app's locale `nb-NO`) Lommelykt, Mobildata, Lysstyrke, Bluetooth: all four at one size of about 10 sp, Lommelykt 44.7 dp, Mobildata 40.7, Bluetooth 39.3, Lysstyrke 38.7 - none cut. **At font scale 1.3** the 9 sp floor is reached: en "Brightness" is cut ("Brightne…"), nb "Lommelykt" and "Mobildata" too. **Found:** a font-scale change didn't rebuild the panel; fixed (`ACTION_CONFIGURATION_CHANGED`, the scale in the post key) |
| 1 with calls (Q99-L8) | **Passed.** During an ongoing call the CallStyle notification is first and unfolded, the panel second and **folded** (the icons, still usable), the missed call under it; nothing bundled. Three Meldinger threads not run: the three test SMS posted no notification on this data |
| 2 taps, shade open | **Passed** for Lommelykt (the AVD has an emulated flash), Lyd (Lyd <-> Lydløs (vibrerer)), Wi-Fi, Bluetooth, Mobildata, Lysstyrke (lav -> middels -> høy, the glyph follows): each `Tap on <key>` in about 5 ms of the receiver, the disc follows, the shade stays open (focus `NotificationShade`). Mer tid (no budget on this phone) and Musikk (no Vibb music session) not run |
| 2 under a time rule | **Passed.** Kveld turned on now from the PWA: the time-rule screen in front, the panel the same, Lommelykt, Lysstyrke and Bluetooth taps work, the rule stays |
| 3 the status bar icon | **None** at LOW: no Vibb icon in the status bar (the gear seen early was Android's own keyboard notice) |
| 4 the keyguard | **Passed.** Android's Swipe keyguard (no PIN): the panel isn't on the lock screen, nor in a shade pulled there (silent and `VISIBILITY_SECRET`); the missed call is. The receiver's keyguard refusal can't be reached from the shell (a non-exported receiver), so it rests on `ShortcutsTest`. Vibb's PIN lock not run (no Child PIN on this phone) |
| 5 Flymodus from the shade | **Passed.** The Clock app in front, the shade open, a tap on Flymodus: our window had focus at ~290 ms (the shade gone), and only then the route: Settings pinned with `mDisabled2=0x15 mFlags=125` from 1.45 s, airplane mode on, the window closed (127, 0x11), and the Clock app in front again at 3.8 s. **Held shade:** the tap followed by a 4.5 s press on the shade - our window never got focus, "no focus within 3000 ms - nothing done", no window opened. Under the rule: the first try the route timed out on this slow AVD ("airplane mode not on", design 98's 1 s wait), the second on in 706 ms, the time-rule screen in front again. Off from the shade: a broadcast, the shade stayed open, off in ~160 ms |
| 6 the parent's switch | **Passed.** The Home page's row unticked (`section=quick`, `shortcuts_row=1`): "Panel removed: not shown" at the sync, About This Phone "Shortcuts: Off"; ticked again: back. The save redirects to `#quick-controls`. The sub-line read "Emma chose: Flashlight, Brightness, Airplane mode, Bluetooth" |
| 7 hiding | **Not possible on this phone:** the long-press menu says "These notifications can't be modified." Vibb One holds the dialer and SMS roles, and Android locks a default app's channels (`defaultAppLocked=true`, `mImportanceLockedDefaultApp=true` on every `launcher:` channel). design 99's probe (S16) wasn't a role holder. "Vis i varsellisten igjen" therefore not run |
| 8 the picker | **Passed after a fix,** gesture and 3-button navigation: four of nine, Flytt opp/ned (the ends' buttons dimmed), the row's sub-line for Mer tid; in 3-button the last row ends at 755 px, the bar starts at 782. **Found:** the refusal of a fifth was a line added above the list, which moved every row under the next tap; fixed (it takes the count line's place) and checked again |
| 9 reboot | **Passed.** The panel is back after the unlock (`Starting the panel` at the process start), the picks kept |
| 10 the Jelly Star | Owed (§10 test 10) |

**Revision 3** (no names, the Vibb mark in the header; design 99 §3, `docs/review/qa-99-code.md` "Fixes"), a short
check of the new look only, 2026-11-15. Setup as above: `jelly36` clean (22 of 22 sums, the same file list) before,
the branch's debug launcher (01e0bb93b, then with the hardening refresh below) as Device Owner with the assistant role,
the run's server data again, enrolled with a typed code; `jelly36` restored afterwards (22 of 22 sums; the run's
`data/misc/pstore/pstore.bin` removed, so the same file list). Sizes from `uiautomator dump` (px / 1.5 = dp).

| Check | Result |
|---|---|
| Folded and unfolded | **The same row.** Unfolded (first on every pull): the header line "Vibb One (debug) · Shortcuts" over the row, cells 82-83 px = **55 dp**, discs 60 px = **40 dp**, glyphs 33 px = 22 dp, the row 72 px = 48 dp. Folded (a header tap): the small icon and the chevron beside the row, cells 67-68 px = **45 dp**, the same 40 dp discs, 7 px (~4.7 dp) between two discs, the row 48 dp. Only Android's header moves; the buttons are the same |
| Font scale 1.3 | **No change** to the row: the same 40 dp discs and cells both ways (the header text grows and its app name is cut, Android's own) |
| The header icon | The Vibb mark (`ic_stat_vibb`): Android draws its rings and core dark in a light disc, folded and unfolded. The gear before drew an empty disc |
| A dimmed Flymodus | "Blokker flymodus" set in the PWA, the Clock app in front, the shade open: Flymodus at 40 %, "Airplane mode, not available now". A tap: `Tap on airplane refused: DIMMED` from the receiver, **the shade stayed open** (focus `NotificationShade`), no `ShortcutAirplaneActivity` (C99-M1) |
| **Found:** the block didn't dim it | With 01e0bb93b the panel kept Flymodus usable after the block until another signal (a brightness change) refreshed it: the policy write the panel hears comes before `applyHardening` sets `DISALLOW_AIRPLANE_MODE`, and no broadcast an app can register for says a restriction changed (`ACTION_USER_RESTRICTIONS_CHANGED` isn't in the SDK). **Fixed:** `AppEnforcer.applyHardening` asks `ShortcutsRuntime.requestRefresh()` at its end. Checked again: the block off and on from the PWA, Flymodus "off" and "not available now" within the sync, no nudge |
| The launcher's language (C99-L1) | `cmd locale set-app-locales ... nb-NO`: `Notification channels named again: nb-NO,en-US`, the channel `launcher:shortcuts` named "Snarveier", the header "Snarveier", the descriptions "Lommelykt, av", "Lyd, på", "Flymodus, ikke tilgjengelig nå", "Wi-Fi, på" |

## 6ao. One Vibb notification, and the request for more time answered (design 100) (2026-11-15)

Design 100 §11's checks. Setup: **`jelly36`** restored clean from `~/kid-look-1-avd-pre-start` (22 of 22 sums), started
under `flock -o ~/build.lock` and `flock -o ~/emulator.lock` without `-wipe-data`, density 240 (the AVD's own). First
main's debug launcher (design 99, 591da420d) as Device Owner (§4's adb lines), enrolled with a typed code on a server of
the branch (migration 0149, `LAUNCHER_ADMIN_COMPONENT` the debug component) on design 99's data; then the branch's debug
launcher (3a5dff456, later 401e03745) installed over it. Two parents: `admin` with the display name "Pappa" and a second
one, "Mamma" (put in by SQL with the first's password and TOTP; its name taken away for one answer to see D11). The
launcher's language from the PWA (Hjem-skjerm › language). Sizes from `uiautomator dump` (px / 1.5 = dp). The emulator's
zone is UTC; a new day for the phone came from `cmd time_zone_detector suggest_telephony_time_zone` (Asia/Dubai, then
waiting for its midnight), with Phone Setup's zone set to match (§3b). The PWA card in Playwright (Chromium 1140) at
390 px. Afterwards `jelly36` restored again (22 of 22 sums, the same file list) and the server stopped.

| Check | Result |
|---|---|
| 1 one notification | **Passed.** Main's build: 1011 (`launcher:shortcuts`, `ShortcutsService` in front) and 1002. After the update: **only `id=1002`**, on `launcher:shortcuts`, `CommandListenerService` `isForeground=true foregroundId=1002`, no `ShortcutsService`. Snarveier off from the PWA with the shade open: the same row turned into the plain notice in place (`launcher:command_listener`, `actions=1`), and at the next pull it sat at the bottom under "Silent". On again with the shade open: the panel in place, folded in "Silent"; at the next pull first and unfolded. Only 1002 at every step |
| 2 the time line and the note | **Passed after a fix.** No budget: the panel 177 px = **118 dp**. A budget today: 206 px = **137 dp** (§3.2's 137). With a note: 234 px = **156 dp** (§3.2's ~157). Font scale 1.3: 215 px = 143 dp, with a note 251 px = 167 dp; "Skjermtiden er brukt opp i dag" wraps to two lines at 1.3, never cut (284 px = 189 dp with the note). No button under the row (D1). A Mer tid tap -> "Spurt kl. 7:35 · venter på svar" (the server took it in ~1 s, so "sender …" only flashed), the disc "Mer tid, spurt". nb and en ("1 t 22 min igjen i dag" / "1 h 27 min left today"). **Found:** Nunito didn't draw - SystemUI inflates RemoteViews in a restricted context, where a TextView ignores an app's font resource, so `@font/nunito_bold` drew the default regular face. **Fixed** (401e03745): the time line takes `TextAppearance.Material.Notification.Title` (the shade's own title face, bold-ish), the note `TextAppearance.Material.Notification`; checked again, the heights unchanged. **Found:** the note's clock had no AM/PM in 12 h ("Spurt kl. 7:35" at 19:35; the AVD's system locale is en-US): it used the clock face's format. **Fixed:** `ClockFormat.formatClock` (pinned). Not seen again on the device (the phone was capped by then); 24 h phones never showed it |
| 3 Snarveier off | **Passed.** The plain notice: the title "1 h 26 min left today" / "4 min igjen i dag", the note as its text ("Mamma ga deg 15 min"), the action "Be om mer tid" / "Ask for more time" while the kid may ask; the Vibb mark |
| 4 icons | **Passed** for the panel's gear (dark in a light disc, folded and unfolded), the plain notice's Vibb mark (rings, no empty disc, at the first swap after the update), the ring (`command/ring`: "Telefonen ringer" with the Vibb mark) and the assistant's setup notice ("Fullfør oppsettet av Vibb One", the Vibb mark; in the window the panel has no time line). Play's install mode and the filter's "kobler til …" not seen (the same `ic_stat_vibb`, pinned by the source test) |
| 5 boot | **Passed as far as the AVD shows.** A calm reboot: the plain notice enqueued at 20:27:51.762 (the anchor's start, still at the keyguard), the panel 1.5 s later (53.237) - inside Android's FGS notification delay, and the shade at the keyguard shows neither (secret/private). The first reboot of the run was an ANR storm (load 54), the service started 95 s late, then the same swap in 1.4 s. The plain notice was never seen in a pulled shade |
| 6 the update from design 99 | **Passed.** See 1: 1011 gone with `ShortcutsService`, one notification at 1002 |
| 7 hiding | **Only the menu:** the long-press says "These notifications can't be modified." (Vibb One holds the dialer and SMS roles). The full form is owed (Q100-L9) |
| 8 the time-rule screen (a budget of 1 min) | **Passed** in gesture navigation, nb. On a new day: "Be om mer tid" (the accent pill) under the rule card; a tap -> "Spurt kl. 12:02 · sender …". **Network off** (`svc wifi/data disable`): "sender …" until +14 s, "sendes når telefonen er på nett" from +18 s; network on: the request went at the SSE reconnect's sync (+34 s, `ACCEPTED`), "venter på svar". Pappa's "Nei" on the card -> "Pappa svarte nei kl. 7:36" and the ringed "Spør igjen"; "Spør igjen" -> "sender …" -> "venter på svar" (the dim pill). Mamma's +30 on the card -> the screen gone at the sync, the panel "Mamma ga deg 30 min". Given minutes run out -> the screen back with "Mamma ga deg 15 min" and "Spør igjen". The third answered request -> "Pappa svarte nei kl. 7:47" and "Du har spurt tre ganger i dag.", no button (the Mer tid disc leaves the panel while capped). Kveld turned on now with the budget used up: the screen names Kveld, no ask block (D4). **Not run:** D6 (one app row and a Child PIN; this phone has three rows and no Child PIN - `TimeLockModelTest` keeps `unlockOpenRow`), TalkBack, 3-button navigation and en on the screen (the zone jumps left the phone with lifts it kept, so the screen didn't come back in time) |
| 8b the answer's rules | **Passed.** Quick actions' +15 (Mamma) while a used-up request was open: the card gone, the answer row `yes` with its lift, the screen gone, the panel "Mamma ga deg 15 min". A rule pause (`target=rule`, all) while one was open: the card stayed, no answer row, the phone still "venter på svar". Asked under Kveld turned on now (from the Mer tid disc): the card's sub-line "00:04 · En tidsregel" and "Knappene setter reglene på pause."; Mamma's +15 made a `rule` lift and the `yes` row, the rule paused, Home; with Mamma's name taken away the panel said "Du fikk 15 min mer" (D11) |
| 9 locale | **Passed** for the panel (nb/en lines, the header "Snarveier"/"Shortcuts"), the plain notice's action and the channels (`launcher:shortcuts` "Snarveier", `launcher:command_listener` "Vibb One"). The screen's block in en not seen (see 8) |
| 10 the PWA card | **Passed after a fix.** 390 px, light and dark, both parents, JS off: the card is the first thing after `</header>` (22 px under it), above Hurtighandlinger; four 77x44 px buttons ("+15 min", "+30 min", "+1 t"/"+1 h", "Nei"/"No"); the title "Emma ber om mer skjermtid", the sub-line the event's words ("19:45 · Skjermtiden er brukt opp"). Two parents in two browsers: Pappa's "Nei" (JS, the stay post) -> "You said no. Emma sees it on the phone." at `#quick`, scrolled 555 px, not the top; Mamma's stale "+30 min" -> "Pappa har allerede svart." at `#quick`, no lift. Activity: "Skjermtiden er brukt opp · Mamma ga 30 min ·" and "… · Pappa svarte nei ·". The events' target is `/devices/1#ask` (no push subscription on this server, so no push was opened). **Found:** an Attention row right after the card touched its ring (0 px). **Fixed** (401e03745): the card keeps the header's 20 px under it |

**Also seen, not design 100's:** the rule card's TalkBack text "Kveld, til 1:04 a.m.." has a double period (an en-US 12 h
"a.m." before the sentence's own). A gift made just before midnight stays in the panel's note up to 30 minutes into the
new day (the shade's note has no day check, the screen's has).

**Owed on the Jelly Star:** the swap and the sorting on its SystemUI (M1-M3); the gear in its header; the time line's
face (the shade's title face now) and the heights; the deferred-plain replacement at boot with a pull right after the
unlock; the screen's block with a child's finger, TalkBack ("Be om mer tid, knapp", the live announcements) and 3-button
navigation; D6 with a Child PIN; hiding in its full form (a phone without the dialer and SMS roles, Q100-L9).

## 6aq. Chrome only for logins (design 102 Part B)

Owed: design 102 §11's E8 and E9. Setup as §6ap, Chrome installed (the Play image's), not ticked under Apps; on the
Sign-in page the role on with the Google preset and an own site; a test app with a Custom Tab login (the probe app of
the design's §1 has one, or any allowed app with "Log in with Google"). Logs: `adb logcat -s BrowserConfig AppEnforcer`;
`adb shell dumpsys device_policy | grep -A3 com.android.chrome` for the written restrictions.

| Check | What to see |
|---|---|
| E8 the role | `BrowserConfig: Managed config written for com.android.chrome` before the apply's hide loop; the report says `login_browser.state: on` (the page "Klar"). A Custom Tab to a listed site opens (Chrome pinned, M12) with no first-run screen; a typed blocked site in full Chrome ("Open in Chrome browser") is "… is blocked"; typed words do nothing; the New Tab page's feed is gone; Chrome is on neither Home nor the drawer |
| E8 refusals | Tick Chrome under Apps: the Sign-in page's switch is disabled with the reason, the policy's `login_browser` is null, Chrome is a normal app. A failed write (e.g. the policy's package changed by SQL to another browser): `failed`/`package`, Chrome hidden and unpinned |
| E9 the pause | A Custom Tab of YouTube opened during a PIN pause (the lockdown lifted) is gone after the pause's end (Chrome hidden and unhidden in the same apply), and a restored tab reloads blocked |
| E9 the clear (code QA L5: the role waits for the clear's success) | The role's first turn-on clears Chrome's history and sign-ins once (`cleared_ms` in the report); turning it off and on again doesn't clear again; ticking Chrome and then the role again does |
| E9 the window | A Wi-Fi sign-in window opened in a pause closes at the pause's end (`closed_by_vibb`) - also when the pause ends early (Home's "Lock again", the PWA) more than 11 minutes after the opening, and no second window opens beside it meanwhile (code QA M2) |

### Run (2026-11-15)

Setup as §6ap's run, the proxy `open`. The role on from the Sign-in page (`section=apps`), the Google preset and an own
site `10-0-2-2.nip.io`; the Custom Tab from the scratchpad test app (a plain `VIEW` to `com.android.chrome` with
`android.support.customtabs.extra.SESSION`). Full Chrome was started with `am start -n com.android.chrome/…Main` (an adb
shortcut: on this Chrome the tab's menu has no way there, see E8).

| Check | Result |
|---|---|
| E8 the role | **Passed after a fix.** At the first apply: "Cleared com.android.chrome's data: true", then "Managed config written for com.android.chrome" and "Chrome's lockdown tightened: its tabs ended", before the plan's loop. **Found:** the role's unhide of Chrome fired `onPackageAdded`; `enforceOnNewPackage` hid it again as a new unallowed app and asked a sync, whose apply unhid it - "Sync for [package_added]" every 0.4 s, Chrome hidden most of the time. **Fixed** (e20a3fa8f): a Chrome the role holds is left to the plan there. Then: no `package_added` syncs, Chrome unhidden and in the lock-task list, on neither Home nor the drawer; the report `login_browser.state: on` with `cleared_ms` and `written_ms`. The Custom Tab to the listed site opened with no first-run screen (Chrome's `FirstRunActivity` started and passed through); a link to the unlisted other site: "10.0.2.2.sslip.io is blocked · Your organization doesn't allow you to view this site". **The tab's "⋮"** on Chrome 133 here: Go forward, Site information, Refresh, Find in page, Desktop site, Translate, "Running in Chrome" (a label) - **no "Open in Chrome browser"**, no Bookmark, Download, history or Add to Home screen (M13 had them). Full Chrome: the New Tab page has the tiles and **no Discover feed**; the YouTube tile "m.youtube.com is blocked"; a typed `wikipedia.org` "wikipedia.org is blocked"; typed words and Enter do nothing. **Seen:** full Chrome's first start showed two Privacy Sandbox prompts ("Turn on an ad privacy feature", then "Other ad privacy features") - no way out, but `PrivacySandboxPromptEnabled: false` (in Chrome 133's schema, M10) would spare them |
| E8 refusals | **Passed** for a ticked Chrome: the Sign-in page's switch disabled with "Chrome is allowed as a normal app. Untick it under Apps to use it only for sign-in.", Chrome a normal app on Home. The failed write (`failed`/`package`) not run: the server always sends `com.android.chrome` and the policy has no column to change by SQL; `LoginBrowserTest` keeps the rows |
| E9 the pause | **Passed.** In a PIN pause (lockdown lifted, the bundle written back to DoH only) a Custom Tab of `m.youtube.com` loaded. The pause ended from the PWA ("Lock again"): "Managed config written", "Chrome's lockdown tightened: its tabs ended", Chrome's process gone, Home in front under lock task. Full Chrome afterwards restored its last tab, which reloaded as "www.wikipedia.org is blocked"; the Custom Tab isn't restored |
| E9 `chrome://policy` | **Blocked** after the first turn-on: "policy is blocked" - the bundle's `chrome://*` covers it. The typed bundle stays owed on the Jelly Star with a test bundle without `chrome://*` (D-C3) |
| E9 the clear | **Passed.** The first turn-on cleared once (`cleared_ms`); off (Chrome hidden) and on again: written and tightened, no second clear; Chrome ticked, then unticked: "Cleared com.android.chrome's data: true" again, a new `cleared_ms` |
| E9 the window | **Passed.** A sign-in window opened in a PIN pause (no keypad, `approved: pause`) stayed open past 11 minutes; at 12 minutes Android's notification tapped on top of it brought the same window forward (`REORDER_TO_FRONT`, one instance, code QA M2). The pause ended early from the PWA at 13 minutes: the window finished at once, `outcome: closed_by_vibb` |

**Owed on the Jelly Star:** D-C1-D-C6 (real portals, `chrome://policy` with a test bundle, "Add to Home screen", a real
Feide/Google login back to its app, the presets' hosts); E13's second hotspot; Android's sign-in notification and its
Cancel on the Jelly Star's own build; the Bare Vibb sink with a SIM; "Open in Chrome browser" on the phone's Chrome.

## 6ar. Settings links in the shade, Flymodus's window, the panel's header (fix round `launcher-fix-1`, 2026-11-16)

Setup: **`jelly36`** restored clean from `~/kid-look-1-avd-pre-start` (22 of 22 sums), started under `flock -o
~/emulator.lock` and `~/build.lock` without `-wipe-data`; a server of the branch on the gear-bug run's data (bit 64 and
128, the airplane block switched off in Advanced › Restrictions); main's debug launcher (8ddf461b1) as Device Owner (§4's
adb lines, the assistant role with `cmd role add-role-holder`), enrolled with a typed code, kiosk LOCKED; then the
branch's build over it with `adb install -r`. The bars measured on `screenrecord` frames (pure-black rows at x 460 against
Home's ground); the successful Flymodus recorded from the host (`adb emu screenrecord`), because the guest's
`screenrecord` slows the route past design 98's 1 s wait. Afterwards `jelly36` restored again (22 of 22 sums, the same
file list).

| Check | Result |
|---|---|
| Before (main) | The long-press menu's gear: `ActivityNotFoundException` for `APP_NOTIFICATION_SETTINGS`, SystemUI killed, Android's keyguard. Flymodus from the panel: black rows 0-35 and 818-853 for the window's whole life (~5 s, the voice activity included), sliding down at its close (rows 4-39, 34-69, then gone). The header "Vibb One (debug) • Snarveier" |
| The gear (kiosk) | **Passed.** `START ... cmp=.../SettingsSinkActivity`, "SettingsSink: A Settings page while Settings is hidden: android.settings.APP_NOTIFICATION_SETTINGS"; no crash, no keyguard, Home focused, lock task LOCKED |
| Manage (kiosk, and from Android's keyguard) | **Passed.** `NOTIFICATION_SETTINGS` to the sink; from the keyguard after its swipe the same |
| History | **Passed** (notification history on, Settings disabled with `pm disable-user`, lock task off in the pause): `NOTIFICATION_SETTINGS` and `NOTIFICATION_HISTORY` both to the sink |
| The pause | **Passed.** Started from the PWA: Settings visible at priority 1, the sink at -1; the gear opened Settings' app-notification page and Manage its notification page, no chooser. Ended from the PWA: Settings hidden again, LOCKED |
| Other paths (Settings disabled, lock task off) | **No crash:** Quick Settings long-press on Internet (`WIFI_SETTINGS`), Bluetooth, Airplane (`result code=-91`), Alarm (the clock); the Internet dialog's network (`WIFI_DETAILS_SETTINGS`), Bluetooth's "Pair new device", Modes' Settings and a mode (`AUTOMATIC_ZEN_RULE_SETTINGS`); the footer's gear (`SETTINGS`); the clock and date; the volume panel's Settings (`SOUND_SETTINGS`); a system notification's own tap (`HARD_KEYBOARD_SETTINGS`). In the kiosk Quick Settings and the volume dialog's settings button aren't shown at all |
| Flymodus (the branch) | **Passed.** No black rows at top or bottom in any frame after the shade closed, through the voice activity and the close; airplane mode on (`Route window 1220 ms, airplane mode on`). Settings' `AirplaneModeVoiceActivity` (floating) shows no bars of its own |
| The header | **Passed.** "Vibb One (debug)" alone |

Screenshots: the scratchpad's `launcher-fix-1/shots/` (`01`-`04` before, `11`-`16` after, `30`-`32` the pause, `40`-`44`
the other paths).

**Code QA round** (`docs/review/qa-launcher-fix-1-code.md`; the versions rule of 2026-11-16). The window is one pixel now
(M1), the sink has the 14-17 actions at priority -999 (M2, L2), and the panel's small icon is the Vibb mark. Run on
three images, each a fresh phone (`-wipe-data`) of its own AVD made from the compatibility check's configs (`fix34`,
`fix370`: 480x854 at 240 dpi, `hw.mainKeys = no`) or `jelly36` restored as above; main's build first, then the branch's
with `adb install -r`; the rest as above. Bars and icon colours measured on host recordings (`adb emu screenrecord`): the
dark rows in the top and bottom 40 px at x 460, and the brightest and darkest pixel in the status bar's icon strip.

| Check | Android 14 (API 34, `google_apis`) | Android 16 (`jelly36`) | Android 17 (API 37, `google_apis`) |
|---|---|---|---|
| Before (main) | The gear: `ActivityNotFoundException`, SystemUI killed, the keyguard. Flymodus: black rows 0-35 and 818-853 for the window's life | From the first run above: the same crash and black rows | **Not reachable:** the platform refuses to hide Settings on this image (`AppEnforcer`: "Platform refused to hide com.android.settings"), so nothing crashes; the shade's Notification settings opened Android's "App is not available" (Settings blocked by lock task) with main and the branch alike |
| The gear, Manage (kiosk) | **Passed.** Both to the sink; Home focused, LOCKED, no keyguard | **Passed.** Both to the sink, Home focused, no keyguard | Settings visible (see Before): Settings at priority 1 above the sink at -999 for every action, the package actions included; the long-press menu didn't open by `input` on this image |
| History (kiosk, history on) | **Passed.** `NOTIFICATION_SETTINGS` and `NOTIFICATION_HISTORY` to the sink | Passed in the first run above | Not run (the system restarts, below) |
| The pause | **Passed.** Settings at priority 1 for every action, the sink at -999 (`pm query-activities`, the package actions with `-d package:`); the gear and Manage opened Settings's pages, no chooser. `AUTOMATIC_ZEN_RULE_SETTINGS` has no Settings filter on 14 (the sink answers it) | Passed in the first run above | Not run; the priorities as above |
| Flymodus, dark Home | **Passed.** No dark rows after the shade closed, Home drawn throughout (Settings's own voice activity flashes white for ~0.2 s on 14, before and after alike). Three early runs (after a reinstall and the gear checks) showed the whole screen black for the window's life, Home not drawn; not seen again in eight runs, one of them right after a reinstall and one right after the sink | **Passed.** No dark rows, Home drawn throughout | **Passed** once: no dark rows, airplane mode on in 390 ms (3-button navigation, below) |
| Flymodus, light wallpaper (M1) | **Passed.** The full-screen window of the first commit turned the status icons white for its life (the strip's darkest pixel 236, against 95 for Home's dark icons); the one-pixel window keeps them dark (93-95) | **Passed.** The icons dark (94) through the window | Not run |
| The small icon | **Passed.** The Vibb mark, filled (dark rings on a light disc) in the shade header; in the status bar the rings at LOW | **Passed.** The same: filled in the header and in the status bar, on the dark and the light wallpaper | Android 17 draws the app's launcher icon in the header (the Vibb mark there); no status icon at LOW. The header reads "Vibb One (debug)" alone |

**Android 17 on this VM:** the API 37.0 and 37.2 images abort in gfxstream (`Assertion failed:
!rcEnc->featureInfo()->hasReadColorBufferDma`) whenever a frame is read back - `screencap`, SystemUI's navigation-handle
region sampling and system_server's task snapshots - with `-feature -GLDMA,-GLDMA2`, `-gpu guest` and `-gpu off` alike;
surfaceflinger or system_server dies and the phone restarts. 3-button navigation (`cmd overlay enable-exclusive
--category com.android.internal.systemui.navbar.threebutton`) and host screenshots (`adb emu screenrecord screenshot`)
avoid the first two; a task snapshot still killed system_server after the Flymodus run. So 17's rows are partial. Retried 2026-11-16 with
emulator 37.2.13 and the canary 37.3.9 (`sdkmanager --channel=3 emulator`): `compat372` with `-wipe-data` still aborts
the same way at its first boot (surfaceflinger's RegionSampling, three times within a minute), so nothing more was run;
the stable 37.2.13 is back (`sdkmanager --uninstall emulator`, then `sdkmanager --channel=0 emulator` - a plain
`sdkmanager emulator` keeps the newer canary).

Retried 2026-11-17 with the canary emulator 37.4.1 (`sdkmanager --channel=3 emulator`), always `-no-window -no-audio -no-snapshot
-wipe-data -dns-server 8.8.8.8`: API 37.0 google_apis with `-gpu swiftshader_indirect` booted in 85 s but surfaceflinger
died 20 s later (screencap 72 bytes); `-gpu guest` and `auto-no-window` (not a valid option in 37.4.1, falls back to `auto`)
never reached `sys.boot_completed` in 10 minutes ("Failed to find ColorBuffer"). `aosp_atd` 37.0 with `swiftshader_indirect`
booted in 25 s but surfaceflinger died after 90 s (screencap empty); `guest` and `default` were not run (the round was stopped).
No working command line. 37.2.13 is the default `emulator` again (same uninstall/`--channel=0` steps as above).

**Found on Android 14, outside this round:** a reboot of the enrolled API 34 phone never finished booting (user 0 stays
`BOOTING`, `sys.boot_completed` unset after 7 minutes; no HOME resolves before the first unlock - Settings' FallbackHome is
hidden and Vibb's HOME isn't direct-boot aware). Seen twice (`fix34`, and the compatibility check's `compat34`); the logcat
is in the scratchpad (`launcher-fix-1/logcat-34-stuck-boot.txt`). `jelly36` (16) reboots fine (§6an).


## 6as. Android 14-17: Settings off the lists, the media button's hold, lint (compat-fix-1, 2026-11-16)

The fixes of `docs/testing/2026-11-16-android-14-17.md`, on `compat372` (API 37.2, CP41.260831.015), `compat34` (API
34, UE1A.230829.050) and `jelly36` (API 36). 37.2's graphics stack kills system_server on any CPU readback (a task
snapshot after a dialog, a screencap), so read it with `dumpsys` and `uiautomator`. The rig is the 14-17 check's (a
server on 3197, device 1; a QR-shaped enrolment code - 26 characters of A-Z and 2-7 - written into its database).

1. **Refused once, not retried** (37.2): after the install `PlatformRefusals: Android refuses to suspend
   com.android.settings (exempt from suspension); not asked again until a reboot or an OS update` and the same for
   hide - one line each; later applies (a sync, a system_server restart) make no call (`PackageManager: Cannot
   suspend package "com.android.settings"` stops). After `adb reboot` (boot_count 23 -> 24) it is asked and logged
   once more. On 36 the suspension is refused the same way (the app-op is there already) but the hide works; on 34
   nothing is refused (Settings `hidden=true suspended=true`, no `PlatformRefusals` line).
2. **Home and the drawer** (37.2, 34, 36): `dumpsys package com.android.settings` on 37.2 reads `hidden=false
   suspended=false`, yet Home's grid has only Vibb's own "Settings" tile (Kid Settings, `GridTile.Settings` - tap it:
   `KidSettingsActivity`) and the drawer lists Camera, Clock, Messages (and Music once allowed) - no Android Settings.
   Kiosk: `dumpsys activity activities` `mLockTaskModeState=LOCKED`.
3. **The pause** (PWA `POST /devices/1/start-pause`, 37.2 and 34): unlocked, `mLockTaskModeState=NONE`, the drawer
   lists Android's Settings; Home's "Lock again" takes it off again and the kiosk is LOCKED.
4. **SystemUI's Settings pages under the kiosk** (37.2): `am start -a android.settings.APP_NOTIFICATION_SETTINGS`
   resolves to Settings (not `launcher-fix-1`'s sink) and becomes `android/com.android.internal.app.BlockedAppActivity`:
   "App is not available - Settings is not available right now", OK. No SystemUI crash. (Its OK then hit the emulator's
   graphics crash - not ours.)
5. **The media button with nothing to resume** (37.2): the music app allowed (PWA "Allow these apps"), not running,
   `run-as me.vibb.music.debug` writes `shared_prefs/music.xml` with `resumable` true, `cmd media_session dispatch
   play`: the events log has `am_foreground_service_start` and 53 ms later `am_foreground_service_stop
   ...STOP_FOREGROUND`; Media3 logs the refusal as a warning; no crash in 40 s (`logcat -b crash` empty), the service
   gone, `resumable` false. On 34 (no media button receiver registered on a wiped phone):
   `am start-foreground-service -a android.intent.action.MEDIA_BUTTON -n me.vibb.music.debug/me.vibb.music.playback.PlaybackService`
   (a button press with no key, no resumption): in the foreground at once, out after the 2 s first look, no crash.
6. **Lint** (both apps, `./gradlew lint`): "Lint found ... (and 77 errors filtered by baseline lint-baseline.xml)" and
   16 for the music app; music's `assembleRelease` (lintVitalRelease) passes with the baseline. Launcher's
   lintVitalRelease needs `libs/tsnet.aar` (CI only).

**`compat34` after `adb emu kill`**: with Settings hidden, the boot cover off (own mode) and no `ACTION_SHUTDOWN`, the
next boot finds no HOME before the unlock and never completes (user 0 stays BOOTING, a black screen) - the case
`home-and-photos.md` step M describes for Android 15. Start it with `-wipe-data` and provision again (or switch the boot
cover on and stop it with `svc power reboot`). 37 can't hang there: Settings (FallbackHome) is never hidden.

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

## 6au. The clock during a rule (design 105, 2026-11-16)

The 14-17 rig's device 1 (server on 3197), the branch's debug launcher as Device Owner, android mode (kid PIN 2580),
block bit on, Fossify Clock 1.6.0 from GitHub (`clock-10-foss-release.apk`, SHA-256 `43cf9f0e...dca079`, the catalog's
signer) installed with `adb install`, "Allow these apps" and ticked on the Apps page. Grant it what a real phone has:
`pm grant org.fossify.clock android.permission.POST_NOTIFICATIONS` and `appops set org.fossify.clock
USE_FULL_SCREEN_INTENT allow` (without them the ring has no notification and no ringing screen). Alarms set with `am
start -a android.intent.action.SET_ALARM -p org.fossify.clock --ei ...HOUR --ei ...MINUTES --ez ...SKIP_UI true`. The
rule is the rig's rule 1 renamed Skole (`kind` school, `silent` 0), turned on with `POST /devices/1/rules-now mode=on
rule=1 on_len=60`. Read the list in `dumpsys activity activities` (`LockTaskController`'s `mLockTaskPackages`).

`compat34` (API 34), all steps:
1. **At the rule's start**: Fossify open in front, then Skole on: Fossify left the list, no Fossify ActivityRecord,
   `LockActivity` resumed. The emptied task stays in `dumpsys activity recents` (the design's "no clock task" is
   only true of its activities).
2. **Recents**: `KEYCODE_APP_SWITCH` shows the clock's card; its tap restarts nothing - `BlockedAppActivity` "Clock is
   not available right now" in that task, no Fossify activity. `am start -a android.intent.action.SHOW_ALARMS -p
   org.fossify.clock`: the same refusal.
3. **A ring while LOCKED**: power off - Fossify back in the list at once; at the ring `AlarmActivity` resumed over the
   keyguard (`isKeyguardShowing=true`). Snoozed by its slider. `dumpsys deviceidle force-idle` stays at INACTIVE on
   this image, so Doze itself was not reached.
4. **Unlock while it rings**: the PIN on the bouncer - Fossify out of the list, no Fossify activity, the ringing
   notification (id 9998) and `AlarmService` still there; the card reads "The alarm is ringing" with Snooze and
   Dismiss (the notification's actions: `startService` and `broadcastIntent`). Dismiss: the notification and the
   service gone, the row gone. Snooze (another ring): snoozed 10 min (`AlarmReceiver` re-armed).
5. **A ring unlocked and lit** (the snooze): a heads-up only - no `AlarmActivity`, Fossify not in the list, the row
   with Snooze and Dismiss.
6. **Ticked on Skole**: the rule again with `exempt_apps_json ["org.fossify.clock"]` - in the list, its row "Clock"
   opens `MainActivity`.

**`compat372` (API 37.2) could not run** (nor with emulator 37.2.13 or the canary 37.3.9, retried the same day - see
"Android 17 on this VM"): on every app transition system_server aborts in its
`TaskSnapshotPersister` (`hasReadColorBufferDma`), and with gesture navigation surfaceflinger's RegionSampling too -
with `-gpu swiftshader_indirect`, `guest` (lavapipe), `swangle_indirect` and `-feature -GLDMA,-GLDMA2,-GLDirectMem`
(3-button navigation stops only the surfaceflinger one). **`handy36` (API 36, `-read-only -wipe-data`) instead**,
steps 1 and 3-4 without a ring: the rule took Fossify out of the list with the clock open (no activity left), a
screen-off put it back in (LOCKED), the PIN unlock took it out again. The rings and the row's buttons ran on 34 only;
the bar under the row (the pills moved under the label after the 34 run) is not seen on an emulator yet.

**After the code QA** (`compat34`, android mode, the branch's build): an alarm at screen-off under Skole rang over
Android's keyguard and was still `AlarmActivity`, Fossify in the list, 3 s and 11 s in; its slider's Dismiss stopped
it; the PIN unlock took Fossify out of the list. **L4**: `lock_task_features` 61 (NOTIFICATIONS off; `statusbar`
`mDisabled2=0x15`), a ring unlocked and lit: no heads-up and no full-screen launch (SystemUI "No FullScreenIntent:
NO_FSI_NO_HUN_OR_KEYGUARD"), no `BlockedAppActivity`, `LockActivity` stayed resumed with "The alarm is ringing" over
Snooze and Dismiss as one card block (screenshot), and the row's Dismiss stopped it.

## 6av. The phone book as a task of its own (branch `phonebook-task`, 2026-11-16)

`docs/notes/launcher/calls.md`, "The phone book as a task of its own". Setup: **`jelly36`** (clean, 22 of 22 sums),
started under `flock -o ~/build.lock` and `flock -o ~/emulator.lock` without `-wipe-data`; a server on the
launcher-fix-1 run's data (device 1, four contacts, rules `Lekser` with calls off and `Kveld` with calls on); main's
debug launcher as Device Owner (§4's adb lines), enrolled with a typed code, kiosk LOCKED; then the branch's build over it
with `adb install -r`. A rule is turned on now with the PWA's `POST /devices/1/rules-now` (`mode=on rule=<id>
on_len=60`) and ended with `POST /devices/1/lock-now/<id>/end`. Home presses recorded with the guest's `screenrecord`
and cut into frames (`ffmpeg -vf fps=20`). Afterwards `jelly36` restored again (22 of 22 sums, the same file list).

| Check | How | Result (API 36) |
|---|---|---|
| Before (main) | Home's Telefonbok tile, `dumpsys activity activities` | The phone book in Home's task (`type=home`, `A=...launcher.debug`) |
| Own task | The same, the branch | **Passed.** `Task{... type=standard A=10218:me.vibb.launcher.debug.phonebook}`, `PhoneBookActivity` on top; LOCKED |
| Home's close | Home key, frames | **Passed.** The phone book shrinks and slides down to the bottom, frame for frame as Meldinger's (recorded the same way) |
| Recents | The square key, `uiautomator dump` | **Passed.** A card "Telefonbok" with the Vibb icon next to "Meldinger"; its tap brings back the same activity instance |
| Back | Back from the phone book | **Passed.** Home; the card stays in Recents. A card tap after Back starts the page from its first intent (`flg=0x14100000`, from history): no "Ring ...?" and no contact's card again |
| A call | Ring on a contact (the emulator's modem ends outgoing calls at once) | `InCallActivity` in its own task, then the phone book back in front |
| The pin | `cmd package resolve-activity` for `VIEW vnd.android.cursor.dir/calls`, DIAL and DIAL `tel:` after the update; `dumpsys device_policy` | **Passed.** All three resolve to `PhoneBookEntryActivity`; the policy engine holds one call-log PPA, its value the entry (the old `PhoneBookActivity` pin replaced); the dialer role stays ours |
| A rule without calls | `Lekser` on; Recents -> the phone book's card | **Passed.** The phone book's task comes forward and starts the time-rule screen 30 ms later (`sr=` the phone book) |
| ... a `tel:` link | `am start -a android.intent.action.DIAL -d tel:+47...` | **Passed after a fix.** Entry -> the page (`onNewIntent`) -> the time-rule screen. Before the fix the page showed "Dette nummeret er ikke tillatt." behind the time-rule screen, the first thing seen at the next open; now the asked number is dropped while redirected |
| A rule with calls | `Kveld` on; the time-rule screen's "Telefonbok" row; Back | **Passed.** The phone book opens and stays (no redirect, no left-over dialog); Back -> the time-rule screen |
| A rule beginning in front | `Lekser` turned on with the phone book open | **Passed.** The time-rule screen on top (the phone book's lock watch and the rule's own start) |
| Lys | Kid Settings -> Lys, then the tile | **Passed.** The change closed the phone book's and Meldinger's tasks; `LightPhoneBookActivity` in the `.phonebook` task, on the paper |
| Missed call | `adb emu gsm call` + `cancel` from Mamma; the notification's tap | **Passed.** Entry (`VIEW` calls) -> the open page (`onNewIntent`), Mamma's card open |

**Code QA round** (`docs/review/qa-phonebook-task-code.md`; the fixes' build over the first commit's on `jelly36`
restored clean, then restored again; the rig's DB with SMS "Ikke styrt" and Mamma's message app SMS, so Melding opens
Google Messages):

| Check | Result (API 36) |
|---|---|
| H1: Melding to an outside app | **Passed.** The first commit's start had no flags (Google Messages' own trampoline moved itself to its task, so the bug doesn't show with this app); the fix's `SENDTO` start has `flg=0x10000000`, Messages in its own task, the phone book's task only the page. A rule without calls on: Messages suspended, its task gone from Recents; the phone book's card redirects |
| H1: the time-rule screen's row | **Passed.** Under `Kveld` the row starts the page with `flg=0x14000000` (`NEW_TASK`, `CLEAR_TOP`), on the page itself |
| M1: the base intent | **Passed.** After a missed-call tap the task's base intent is component-only (`cmp=.../PhoneBookActivity`, no action or type); Home, then the tile: the same task, the same instance, one activity |
| M2: a `tel:` link | **Passed.** "Ringe Mamma?" from the number carried as an extra |

**Android 14 (`compat34`, API 34):** started with `-wipe-data -dns-server 8.8.8.8` (the first run's wipe without the
flag left the launcher unable to reach the server); the network worked before and after the install, the branch's
build as Device Owner, enrolled, kiosk LOCKED. **Passed:** its own `.phonebook` task; Home's slide to the bottom
(recorded); the kiosk's Recents card "Telefonbok" with the Vibb icon, its tap back to the same instance; the call log,
DIAL and DIAL `tel:` resolve to the entry, the dialer role ours; Melding with `NEW_TASK`, Messages' task removed by the
rule's suspension; under `Lekser` the card and a `tel:` link both end on the time-rule screen; under `Kveld` the row lands
on the page (`flg=0x14000000`) and Back returns to the time-rule screen; the missed-call tap (expand the shade's group
first) opens Mamma's card through the entry. **Not run on 14:** the update from an older build's pin (a fresh install
only). `compat34` stopped with `adb emu kill`: start it with `-wipe-data` next time (§6as).

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

## 6ax. Blocked notification channels (design 107) (2026-11-16)

Design 107 §10's gate. Setup: a server of the branch on port 3107 (design 102's data, the phone "Testtelefon", a
6-digit test parent code set in the PWA, quick-controls bit 128 off by SQL so the anchor posts its plain notice on
`launcher:command_listener`); the branch's debug launcher as Device Owner (§4's lines), `POST_NOTIFICATIONS` granted
with `pm grant` (Home asks for it on a phone), enrolled with a typed code. **`compat34`** (API 34) started with
`-wipe-data -dns-server 8.8.8.8`; **`jelly36`** (API 36) restored clean from `~/kid-look-1-avd-pre-start` (22 of 22
sums, the same file list), started without `-wipe-data`, and restored again afterwards. Both under `flock ~/build.lock`
and `flock -o ~/emulator.lock`. Calls and SMS set to "Ikke styrt" in the PWA (`POST /devices/1/calls/settings`):
`cmd role get-role-holders android.app.role.DIALER` names Google's dialer and `dumpsys notification` reads
`mImportanceLockedDefaultApp=false` on every `launcher:` channel. `POST_NOTIFICATIONS` read `POLICY_FIXED` while
managed and lost it once both were unmanaged (M2, both images). Logs: `NotificationChannels`, `SyncRunner`;
`dumpsys activity broadcasts history` for what Android sent and dropped.

| Step | Result |
|---|---|
| 1, the kid's block | **Passed after a fix.** Long-press the anchor's notice › Turn off notifications (36: "Turn off") › "Vibb One" › Apply: `importance=0`. **Found** (34): no receiver line - the broadcast history reads "skipped by policy at enqueue: Exported Denial … from android (uid=10168) due to receiver … not specifying RECEIVER_EXPORTED": the sheet's change is sent with SystemUI's uid (10168 on 34, 10187 on 36), so a `RECEIVER_NOT_EXPORTED` receiver never gets it; a change on Settings' own page (uid 1000) did arrive. **Fixed**: the receiver is `RECEIVER_EXPORTED` (the three actions are protected broadcasts; the receiver only asks for a sync). Checked on 36 and again on a wiped 34: `Block state changed (NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED): a sync` and `Sync for [notification_channels]` 3-50 ms after Apply; the row stored 1 s (34) and 9 s (36) later, a full report (the kept `device_report_base` body carries the new `notification_channels`). The PWA: About › Lyd › Varsler "Av: «Vibb One»" (en "Off: “Vibb One”"), plain, the ⓘ with the anchor's sentence and the fix; no Attention row for it. Snarveier's own receiver (`ShortcutsRuntime.init`, not exported) was dropped the same way (one Exported Denial left per block); since code QA L1 it no longer lists the action and the watch refreshes the panel |
| 1, the sheet afterwards | Opened from another Vibb notice (the pause's "Åpnet med foreldrekoden"), the sheet no longer lists "Vibb One" (34 and 36): AOSP's prediction (L7) holds - the kid has no way back from the sheet |
| 1, the app-wide switch (M2) | **Takes** on an unmanaged phone (34 and 36): "All Vibb One notifications" off › Apply revokes `POST_NOTIFICATIONS` (`granted=false, USER_SET`), and **Android kills Vibb One's process** for it (`exit-info`: reason 8, PERMISSION CHANGE). No `ACTION_APP_BLOCK_STATE_CHANGED` in the broadcast history for the revoke; the process start's `Sync for [start]` (3-4 s later) carries `all_off: true`. The PWA: "Alle av" orange, no Attention row (nothing managed). Turning it on again in Settings' page does send `APP_BLOCK_STATE_CHANGED` (36), and it arrives |
| 2, the row in the kiosk | Vibb One's Settings (parent code): "Varsler fra Vibb One · Av: «Vibb One» · Sett alle begrensninger på pause først". A tap opens nothing: Settings stays on top, `mLockTaskModeState=LOCKED`, no sink screen |
| 2, the row in the pause | "Sett alle begrensninger på pause" on the same page (the code again): the row reads "· Trykk for å slå på" ("Alle av · Trykk for å slå på" with the app switch off). A tap opens `com.android.settings/.Settings$AppNotificationSettingsActivity` for Vibb One; the app switch and "Vibb One" on (36: the blocked channel sits further down the list), Back: the receiver's sync, the row gone, the PWA "Alle på". The pause off: LOCKED |
| 3, a stale block and the role | Precondition: SMS managed, a contact allowed to text, one SMS (`adb emu sms send`): `launcher:messages` exists. SMS unmanaged, a pause (`POST /devices/1/start-pause`), Meldinger off on Android's page (the receiver's sync, `blocked: ["messages"]`), the pause off. SMS managed alone takes the SMS role, not the dialer: nothing locked yet, and the PWA already reads "Av: «Meldinger»" orange with "Varsler slått av: Meldinger" (the feature counts). Calls managed too: the dialer role is Vibb's, and **Meldinger stays `importance=0`, now locked** (34 and 36; L7's first prediction holds). About "Av: «Meldinger»" orange with the messages sentence; Attention "Varsler slått av: Meldinger" (en page: the names in nb, the phone's language) to `/about#sound`; the Devices list's badge counts it. **L7's second prediction fails**: in a pause Android's page greys Meldinger's switch (`enabled=false`, 34 and 36; the channel's own page: "Show notifications" disabled) and the app switch (POLICY_FIXED). With calls and SMS set to "Ikke styrt" in the pause the lock goes, the switch works, Meldinger on; managed again it stays on (locked at 3). The PWA's `.fix` now ends with that step (§11's wording change) |

Not run: Android 17 (no emulator here), the Jelly Star (owed: with Vibb as the dialer, a long-press on a Meldinger notice
should say "can't be modified").

## 6ba. App push held while the apps have no network (design 115, 2026-11-18)

Run on `jelly36` (16) and `compat34` (14) with §6ay's rig and a probe that fetches once per MESSAGE, one after another;
the rows, the results and the one fix it found (the 1.5 s settle after a shape change) are in
`docs/testing/2026-11-18-up-held-gate.md`. Owed: row 8 (abroad), Android 17, the Jelly Star with Element X.

## 6bb. The phone's setup: the hold and the guided flow (design 114, branch `design-114-build`)

**Run 2026-11-19** (`compat34` and `jelly36`): `docs/testing/2026-11-18-114-emulator-check.md` - every row run passed after
four fixes on the branch (the flow's invisible secondary buttons, the flow lost by its own pause, mobile data on 16's SIM
page, the Internet panel's return); owed: Android 15's SIM page, row 9d, the Jelly Star.


Needs the user's OK first. The rows are design 114 §11's table (rows 1-16), on `compat34` (14, `-wipe-data`) and
`jelly36` (16, a restore taken before enrolment); a fresh enrolment each time (the hold starts only on a phone that
never applied a policy). The phone "Testtelefon" with a test Parent PIN set before the code (Connect needs it). The
adb shortcuts the PWA no longer shows are in `docs/setup/android-settings.md`, "The emulator's adb shortcuts".
Owed besides §11's list (code QA L9): a wall-clock correction (NTP or NITZ) right after the enrolment ends the hold
early, by design (fail closed) - record it if seen, and that the PWA's "Åpne for oppsett" brings the flow back.

## 6bc. Failures made visible: "filter on, tunnel down", the Integration row, the backup check (design 116, branch `c2-build`)

**Run 2026-11-19** (`compat34` and `jelly36`): `docs/testing/2026-11-19-c2-emulator-check.md`. Design 116 §3.3's steps
0-4, code QA L1, one Integration row (a blocklist feed that 404s) and the backup check (an uploaded corrupted copy) all
passed. One fix went on the branch: the fast retry's wait doubled with every failed start.

Notes for a re-run:
- Always-on writes and a boot re-grant ACTIVATE_VPN, so the deny goes just before the kill
  (`run-as me.vibb.launcher.debug kill -9 <pid>`).
- Step 3's PASS comes from the parent's filter switch with Mobildata "Bare Vibb", not from a pause: a pause gives NONE.
- The row comes about 14 minutes into the outage: the next retry sees the 10 minutes, and the alarm is inexact (5 to
  8 min 45 s).

Owed: Android 15 and 17, the push on a subscribed browser, the alarm's Doze recovery on 16 (the emulator's Handler won
there), the Jelly Star.

## 6bd. The Bluetooth pairing window (design 122, branch `bt-pairing-window`, 2026-11-20)

The emulator has no headset to pair, so the debug build's `BtPairingDebugReceiver` (guarded by DUMP) drives the
window's own steps: `step` `arm` (the page's tap without `createBond`), `request` with `variant`, and `bond` with
`state` `bonded` or `none`, for a fake `address`:
`adb shell am broadcast -n me.vibb.launcher.debug/com.kidslauncher.mdm.server.BtPairingDebugReceiver --es step request --ei variant 3`.
Android's ordered delivery ahead of Settings' receiver is not simulated.

**Setup:** `jelly36` (Android 16, Google Play) restored clean (22 of 22 sums), started under
`flock -o ~/emulator.lock` on port 5570. The server was release 0.86.1's x86_64 binary, with
`LAUNCHER_ADMIN_COMPONENT` set to the debug component, set up over HTTP: an admin with 2FA, one phone, USB debugging
allowed, a parent PIN, the Bluetooth row, and the Clock allowed. The phone was enrolled with a typed code, and the
setup flow ended with Finish. Then the kiosk was LOCKED (`mFlags= 127`) and Settings `hidden=true` (case a). Kid
Settings › Bluetooth › Manage devices was in front. Each check read the lock-task list, `mFlags`, Settings' `hidden`
and the top activity. Afterwards `jelly36` was restored (22 of 22 sums).

| Test | Result |
|---|---|
| 1 open, bonded | **Passed.** The arm unhid Settings without pinning it (`hidden=false`, not in the list, 127). The request (variant 3) pinned it in 38 ms with the shade blocked (125). `bond bonded` closed it: out of the list, 127, `hidden=true` |
| 2 a Settings start in the window, the page's stop | **Passed.** With the window open (variant 2), `am start com.android.settings/.Settings` opened Settings. This is the package-wide reach the design accepts. Our page stopped and the window closed ("Closed: page_stopped"), and Settings' task went with the unpin. Our page came back on top. The same start afterwards: "Activity class ... does not exist" (hidden) |
| 3 a PIN | **Passed.** Variant 0: `GROWN_UP`, no pin, closed as `grown_up`, `hidden=true` |
| 4 timeouts | **Passed.** Armed with no request: closed at 30.08 s. Open with no answer: closed 30.1 s after the request. Both ended with 127 and `hidden=true` |
| 5 screen off | **Passed.** Closed at once. The page's `onStop` came before `SCREEN_OFF`, so the reason was `page_stopped` |
| 6 not ours | **Passed.** A request with no arm: `PASS`. Armed for one address with a request for another: `PASS`, no pin. Another device's `bond none` left the arm. The armed device's `bond none` in lower case closed it |
| 7 a process death | **Passed.** `kill -9` with the window open: 1.2 s later "A pairing window was left open: putting it back". Settings left the list, the apply wrote 127, `hidden=true`, the marker was cleared. Home was in front |
| 8 an apply during the window | **Passed.** A PWA save (an SSE sync) 0.4 s after the pin: "Settings stays unhidden for the pairing window". The pin and 125 held, and the window closed at its 30 s |

**Not run:** a real bond and the real dialog (owed on the Jelly Star with the Jabra), a pause (the arm refuses then:
`BtPairingPlanTest`), Android 14 and 17.

## 6be. Android Auto, the other Home apps, the notification listeners (branch `notif-readers`) - run 2026-11-24

On Android 14 and the highest image, a Play image with Android Auto (`com.google.android.projection.gearhead`).
`docs/notes/launcher/hardening.md`, "Android Auto, the other Home apps, the notification listeners".

1. Before enrolling: `cmd overlay lookup android android:string/config_recentsComponentName` and
   `cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME` - which package
   carries the gestures, which other Homes there are.
2. Managed: `dumpsys package <pkg> | grep -E "hidden=|suspended="` - Android Auto hidden, every other Home but the
   recents provider suspended (not hidden). Home, Recents and Back in both navigation modes.
3. Start the pause: both released; end it: both restricted again. Advanced › Android Auto › Tillat: unhidden at the
   next sync.
4. Reboot while locked and while unlocked: no "app paused" dialog, our Home after the unlock.
5. **Listeners** (record what you see, no fix expected): `dumpsys notification | grep -A20 "Live notification
   listeners"` with a listener (a) hidden and (b) suspended - is it still bound? Does `enabled_notification_listeners`
   (`settings get secure enabled_notification_listeners`) keep a hidden package, or does hiding drop the approval (then
   Android Auto comes back without notification access after Tillat or a pause)? The report counts only hidden as `off`.
6. A third-party listener granted in a pause: About › Varseltilgang names it in orange; allowed in Apps, quiet. A system
   listener (the stock launcher, `com.google.android.as`) is named quietly.

### The run of 2026-11-24

AVDs: `compat34` (Android 14, Google APIs, Android Auto `1.2.531830-stub` installed), `compat372` (Android 17, Google
APIs, Android Auto installed) and `aosp36` (Android 16, AOSP - no Android Auto, so a stand-in package named
`com.google.android.projection.gearhead`). Each phone was wiped and started with `-writable-system`. None of these images
has a Home besides the stock launcher (the recents provider) and FallbackHome, so another Home was made: a probe app
with only a HOME activity and a notification listener, pushed to `/product/priv-app/` (a system app, so it isn't
controllable and `otherHomes` picks it). A sideloaded app is always controllable, so as a user app the probe would be the
allowlist's, not an other Home. A second probe (`me.vibb.probe.reader`, a user app with a launcher icon and a listener)
was the third-party listener. All three are throwaway: a `javac`/`d8`/`aapt2` build, never in the repo. Enrolled
against a local server, through the setup hold; "pause" is the PWA's `start-pause`, its end `end-windows`.

| Step | Android 14 (`compat34`) | Android 16 (`aosp36`) | Android 17 (`compat372`) |
|---|---|---|---|
| 1. Recents provider, Homes | Pixel launcher's Quickstep; Homes: Pixel launcher, FallbackHome (+ the probe) | Launcher3's Quickstep; Launcher3, FallbackHome (+ the probe) | Pixel launcher's Quickstep; the same as 14 |
| 2. Managed: Auto hidden, other Home suspended not hidden, recents provider untouched | **Pass** | **Pass** (stand-in) | **Pass** |
| 2. Home, Recents, Back, gesture navigation | **Pass** (Recents by `KEYCODE_APP_SWITCH`: an injected swipe-and-hold never opened Overview on this image, before the probe Home was suspended either) | **Pass** (Home by swipe, Back by edge swipe, Recents as on 14) | **Not run**: gesture navigation's region sampling kills system_server on this VM (§6ar) |
| 2. Home, Recents, Back, 3-button navigation | **Pass** (bar taps) | **Pass** | **Not run**: the first app switch's task snapshot killed system_server (the VM's gfxstream, §6ar) |
| 3. Pause releases both; its end restricts both again | **Pass** | **Pass** | **Pass** |
| 3. Tillat: Auto unhidden at the next sync | **Fail, fixed**: unhidden, then a loop (below). With the fix: **Pass**, no loop | **Pass** (with the fix) | **Pass** (with the fix) |
| 4. Reboot locked: no dialog, our Home after the unlock | **Pass** | **Pass** | **Pass** |
| 4. Reboot unlocked: the same | **Pass** | **Pass** | **Pass** |
| 5a. Hidden listener (Auto) | Unbound, and its approval is **dropped** from `enabled_notification_listeners`; not back after Tillat or a pause | The stand-in: the same | The same as 14 |
| 5b. Suspended listener (the probe Home) | **Still bound** (in "Live notification listeners"), approval kept; reported `off: false` | The same | The same |
| 6. Third-party listener granted in a pause | **Pass**: About › Notification access "Probe Reader" in orange; allowed in Apps, the row quiet | Not run | **Pass**, the same |
| 6. System listener quiet | **Pass** (but named by package name, below) | - | **Pass**, the same |

Gestures: no standard gesture broke on any version that could run them, with the other Home suspended.

**The loop (fixed on the branch):** with Tillat on, `apply()` unhid Android Auto; the unhide is a PACKAGE_ADDED, and
`enforceOnNewPackage` hid and suspended it again (Auto is never on the allowlist), then asked for a sync, whose `apply()`
unhid it - 320 syncs in under a minute on Android 14 until Tillat was turned off. Now an added Android Auto or other Home
runs `apply()` instead (`planOwnsAddedPackage`, `docs/notes/launcher/hardening.md`); after the fix no `package_added`
sync on 14, 16 or 17.

**Seen, not fixed (for the owner):**
- Hiding drops a listener's approval and Android's role holders: after the Auto hide, `enabled_notification_listeners`
  has no Auto entry and `SYSTEM_AUTOMOTIVE_PROJECTION` has no holder (logcat: "Removing package that no longer qualifies
  for the role"); neither comes back with Tillat or a pause. So an allowed Android Auto has no notification access and
  no projection role until someone grants them again.
- A suspended listener stays bound and reads notifications (5b). The report counts it as reading, which matches.
- About names a system listener without a label in the report by its package name ("com.google.android.apps.nexuslauncher,
  com.google.android.as, me.vibb.probe.home, Probe Reader").
- Rig: an `adb install` right before `adb emu kill` was lost on the next boot (DPM "Failed loading admin"); run
  `adb shell sync` before killing. On a `-wipe-data -writable-system` start, the first `adb reboot` after `remount`
  segfaulted the emulator; kill and start it again without the wipe instead.

## 6bf. Time rules keep their own clock (branch `trusted-time`, 2026-11-24) - run 2026-11-24

`docs/notes/launcher/time-rules.md`, "The decisions' clock". Owed on Android 14 (`compat34`) and 16 (`handy36`); Android
17 on a device. The rig: a fresh server, a phone enrolled with a Leggetid rule in force now, a daily budget with some
minutes used, Location "Only when asked". The phone's own clock is moved by the owner's private test method (not in
this repo) to the row's offset; then:

```sh
A="adb -s emulator-5556"
$A logcat -d -s TrustedClock TimeRuleAlarm TimeRulesRuntime
sqlite3 data/kidphone.db "SELECT time_state_json FROM device_status WHERE device_id=1 ORDER BY id DESC LIMIT 1"
```

| Row | Android 14 | Android 16 |
|---|---|---|
| (a) The phone's clock at +26 h: Leggetid stays in force, the card's end and the glance's clock show the server's time | pass after the fix (1) | pass after the fix (1) |
| (b) The day's minutes kept across an offline `adb reboot` after (a); the server's next sync takes a carried reset back | pass | pass |
| (c) Update Location: the fix stored at the server's time (`device_locations.captured_at`, `phone_offset_ms` about +26 h) | pass | pass |
| (d) The PWA: Device › Time's "Klokken går feil (26 t foran)" in nb and "Clock is wrong (26 h ahead)" in en, the summary's Attention row, the Devices list's badge | pass after the fix (3) | pass after the fix (3) |
| (e) Again at -5 h: (a), (c), (d) "5 t bak" | pass | pass |
| (f) The clock at +26 h before Vibb's first read after a reboot: carried at +26 h, corrected by a checked time and then by the server; the ledger's reset taken back | pass (server step; see below) | pass (server step; see below) |
| (j) A clock that lost its time while off (behind the last checkpoint at boot): automatic time's first set is taken; offline, the rules follow the right time | pass after the fix (2) | pass after the fix (2) |
| (k) A server whose certificate isn't in the built-in set (the check not enforced): its answers anchor, no "Tiden er ikke sjekket" line | not run | not run |
| (g) Undo: the clock right again, the line gone, nothing jumps (rules, budget, the alarm) | pass | pass |
| (h) On 14: `canScheduleExactAlarms()` true with `USE_EXACT_ALARM`; the elapsed boundary alarm fires in Doze (`dumpsys deviceidle force-idle`) | pass | - |
| (i) Another time zone (`adb shell service call alarm 3 s16 Asia/Tokyo` with automatic zone off for the test): rules follow local time; Device › Time's quiet "Telefonens tidssone: Asia/Tokyo" | pass | pass |

Screenshots on `jelly36`.

### The run of 2026-11-24

`compat34` (Android 14) and `aosp36` (Android 16, the default image: `adb root` for the rig), the branch merged with
main, a fresh server, both phones enrolled with a Leggetid (bedtime) rule in force in their own zone and the global
daily budget of 120 minutes with some minutes used. The clock was moved by the private test method; offline = the
server stopped.

- **(a)** Leggetid stayed in force at +26 h, the card's "Until" right, the report's `day` unchanged and its minutes
  kept, `clock_skew_ms` 93600000. **Fault (1), fixed in c9b7fe6e8:** the lock's clock and date kept the phone's own time
  until the screen restarted, and the date even then (bound once, at the face's creation). `ClockFormat.watch` now also
  hears `ACTION_TIME_CHANGED`/`ACTION_TIMEZONE_CHANGED`, and the lock and the glance bind the date again
  (`LockClockFace.rebindDate`) at start and on a change. Retested on both: the server's time and date within seconds of
  the set, refreshed at the minute, the system's pattern again once right.
- **(b)** Reboots offline after (a): the lock stayed up, `day` and the minutes kept (7 and 16), `carried` until the server
  came back, then `server` with `clock_skew_ms` 0 (the emulators' own time set at boot had put the wall clock right).
- **(c)** Fixes stored at the server's receipt time: `phone_offset_ms` 93599936 / 93599965, and -18000078 / -18000047 at
  -5 h. The emulators' satellite time follows the guest's wall clock, so its anchor was refused ("A gnss anchor is
  -93600 s from the one in force") - as it should be, but the "checked time" step of (f) can't run on an emulator.
- **(d)** The orange line on Device › Time and the summary (Trenger oppmerksomhet), the Attention count one higher, in nb
  and en. **Fault (3), fixed in 56af98533:** the phone's five-minute steps go towards zero, so a clock exactly 26 h ahead
  is often reported as 25 h 55 min, which read "25 t foran"; the line now rounds to the nearest hour (a contract row in
  `clock_skew.json`).
- **(f)** The clock held at +26 h until Vibb's first read of the boot: `carried` at +26 h, the Home unlocked (the
  accepted gap), the ledger reset with the day before kept as `previous`; the emulator's own time set afterwards was
  ignored (forward of the checkpoint, so not open); with the server back the day, its minutes and the lock came back
  at once.
- **(j)** **Fault (2), fixed in c9b7fe6e8:** with the clock held 5 h behind until Vibb's first read, the carried time
  opened, but a checkpoint that ran between automatic time's set and its `TIME_SET` recorded the new offset, so the set
  looked like no move and was never taken (Android 16, first run). The checkpoint now keeps the offset while the
  carried time is open (`checkpointAt`, `TrustedClockTest`). Retested on both with a minute between the first read and
  the set: "The carried time follows the wall clock's first set in this boot", the boundary alarm on the right time;
  offline before the set the lock stayed up.
- **(g)** Back to the right time: the line gone, the Attention count back, `used_minutes` and the boundary unchanged.
- **(h)** The boundary alarm `ELAPSED_WAKEUP` with `exactAllowReason=policy_permission`, `USE_EXACT_ALARM` granted;
  forced into deep idle with the screen off, the rule's start fired on the second.
- **(i)** Asia/Tokyo with automatic zone off: the rule (in local time) let go at once, the report's `zone` Asia/Tokyo,
  "Telefonens tidssone: Asia/Tokyo" / "Phone's time zone: Asia/Tokyo" quietly on Device › Time.
- **(k)** Not run: it needs an https server with a publicly trusted certificate whose issuer isn't in the built-in set,
  which this rig doesn't have; `clock_rules.json`'s `connections` rows cover the rule.
- Noted, not a fault: a fresh image's zone came back as Etc/UTC after the first reboot (set from adb, not Phone Setup),
  so the rules moved with it - as designed - and the PWA showed "Telefonens tidssone: Etc/UTC".
- Screenshots on `jelly36` not taken.

## 6bg. Android's blocked numbers cleared while managed (branch `blocked-numbers`, 2026-11-23)

`docs/notes/launcher/calls.md`, "Android's blocked numbers cleared while managed". The rig: a fresh server on 3130/3131
(debug launcher component, a dummy `LAUNCHER_SIGNATURE_CHECKSUM`), a phone per AVD with calls and SMS managed (a new
phone's default), a Parent PIN, "Block USB debugging" off, the contact Mamma `+4791234567`, the debug launcher made
device owner and enrolled with a typed setup code (on 16 the Connect screen's fields take Tab between them).

```sh
A="adb -s emulator-5556"
# The action goes to Vibb's gate while managed:
$A shell cmd package resolve-activity -a android.telecom.action.MANAGE_BLOCKED_NUMBERS | grep -m1 name=
$A shell am start -W -a android.telecom.action.MANAGE_BLOCKED_NUMBERS | grep Activity     # BlockedNumbersGate
# Telecom's page started explicitly (not affected by the gate): Add a number, type it, hide the keyboard (BACK), Block.
$A shell am start -W -n com.android.server.telecom/.settings.BlockedNumbersActivity
$A logcat -d | grep -E "VibbBlockedNumbers|SyncRunner: Sync for \[blocked_numbers\]"
# The server: one security-log line per class, the number never.
sqlite3 data/kidphone.db "SELECT created_at, detail, params FROM security_events WHERE event_type='phone_blocked_cleared'"
```

| Row | Android 14 (`compat34`) | Android 16 (`handy36`) |
|---|---|---|
| Vibb One reads the list (dialer and SMS roles) | **Pass.** `blocked_readable: true`, `blocked_left: 0` | **Pass.** The same (the dialer role; SMS not yet handed over) |
| The action while managed | **Pass.** Resolves to `BlockedNumbersGate`, which closes at once | **Pass.** The same |
| A contact's number blocked on Telecom's page (`91234567`) | **Pass.** Cleared 19 ms after the insert (`Cleared blocked numbers: contact=1`), a sync at once; the server's `phone_blocked_cleared` line `blocked_cleared.contact` in the same second; the page's list empty again | - |
| Another number (`99887766`) | - | **Pass.** Cleared 19 ms after the insert (`number=1`); the line `blocked_cleared.number` in the same second |
| The PWA | **Pass.** Calls: "Blokkert nummer fjernet" in orange with "23:01 · nå nettopp", the sentence behind the ⓘ; the summary's Attention row; the security log "Block removed: 1 contact number" | not looked at (the same server) |
| Calls and SMS set unmanaged | - | **Pass.** The gate disabled, the action resolves to Telecom's page; the dialer role back with Google's dialer |
| `TelecomManager.createManageBlockedNumbersIntent()` (a probe app, emulator only, calling it and starting the result) | **Fails (not fixable by the gate).** The intent names Telecom's package (`act=...MANAGE_BLOCKED_NUMBERS pkg=com.android.server.telecom`), so the pin doesn't apply and Telecom's page opens. A contact's number added there: cleared 13 ms after the insert, the server's `blocked_cleared.contact` line in the same second | **The same.** Telecom's page opens; a contact's number cleared 25 ms after the insert, the line in the same second |
| The Google Dialer's settings row while managed | **Not reachable.** With Vibb One the phone app the Google Dialer shows only "Set Phone as default"; its own `BLOCKED_NUMBERS_SETTINGS` activity isn't exported | **The same** |
| The observer after a process restart | **Pass.** `run-as ... kill -9`; the process back by itself (new pid); a short number added on Telecom's page cleared 22 ms after the insert (`short=1`), a sync at once | **Pass after a reboot** (new process, before any sync of its own): a short number cleared 56 ms after the insert, the line in the same second. The `kill -9` run itself ended with Telecom's page ANR-ing ("Input dispatching timed out ... FocusEvent") and then a looping "Process system isn't responding" (no focused window), so it was repeated after a reboot; logcat in the session scratchpad |
| The initial sweep: two numbers blocked while unmanaged, then calls set managed | **Pass.** `Cleared blocked numbers on becoming managed: 2`; the report `blocked_cleared_initial: 2`, every class count 0, no `blocked_cleared_at`; no security line, no orange line on Calls, nothing in Attention | **Pass.** The same |

The phones were in the fresh enrolment's setup hold (no lock task): the clearing ran there too, as it does in a pause.
The last four rows ran on 2026-11-24 (00:00 CET) with the branch's second commit, a fresh server and fresh
phones enrolled with calls and SMS unmanaged, then calls set managed. Android 17 (`compat372`, 37.2) was tried: system_server restarted during `set-device-owner` (the image's known graphics
abort, §6as), so not run. Not run: the "Unknown" switch on Telecom's page (Android keeps it in the provider's own
settings, which only the system may read or write).

**Since 2026-11-24 (branch `blocked-keep`) only rows that hit the rules are cleared**, so the rows above that cleared
`99887766` (`number=1`) and a short number (`short=1`) no longer hold: those blocks stay. **Run 2026-11-24 (10:15-10:23
CET), main `6ce0dedf4`** (debug launcher 0.23.6 built from it, server 0.92.0 from it, fresh on 3130/3131), on Android 14
(`compat34`) and Android 16 (**`aosp36`** instead of `handy36`: the AOSP image takes `adb root`, which §6bh's rig
needs, and one AVD did both sections). Fresh phones enrolled with a typed setup code, calls and SMS managed (Vibb One
holds the dialer and SMS roles), the contact Mamma `+4791234567`. The first contact row was blocked on Telecom's page
(`BlockedNumbersActivity` started explicitly, Add a number, Block); the other rows were inserted as root with `content
insert --uri content://com.android.blockednumber/blocked --bind original_number:s:<n>` (the same provider insert, so
the same observer). The phones were in the enrolment's setup hold.

| Row | Expected (14 and 16) | Android 14 (`compat34`) | Android 16 (`aosp36`) |
|---|---|---|---|
| A contact's number blocked (`91234567`, also as `0047 912 34 567`) | Cleared at once (`Cleared blocked numbers: contact=1`), a sync, the line `blocked_cleared.contact`; "Blokkert nummer fjernet" on Calls | **Pass.** Both forms: `Cleared blocked numbers: contact=1` then `Sync for [blocked_numbers]` 3 ms later; the list empty; `phone_blocked_cleared` `{"key":"security.detail.blocked_cleared.contact","n":1}` in the next second; Calls "Blocked number removed · 1 min ago" (the admin in English), the summary's Attention row | **Pass.** The same (`contact=1`, the sync 10 ms later, the line in the next second) |
| An emergency number blocked (`112`, `113`) | Cleared (`emergency=1`), the line `blocked_cleared.emergency` | **Pass.** Each: `emergency=1`, a sync, `blocked_cleared.emergency` | **Pass.** The same |
| Another number (`99887766`), a short number (`2222`), an e-mail address | Kept: no log line, no sync; the report `blocked_left: 0`, `blocked_kept` counts them; nothing on Calls, no Attention, no security line | **Pass.** (`kid@example.com`) No `VibbBlockedNumbers` line, no sync; the rows stay; after a nudged report `blocked_left: 0`, `blocked_kept: 4` (with `98765432`); security lines still 8; no "Numbers blocked on the phone" on Calls | **Pass.** The same |
| A kept number (`98765432`) then added as a contact in the PWA | Cleared at the apply that follows the policy (`contact=1`), `blocked_kept` one lower | **Pass.** `contact=1` at the apply after the contact's save, a sync; `blocked_kept: 3`, `blocked_cleared_contact: 3` | **Pass.** The same |
| The initial sweep: a contact's number and `99887766` blocked while unmanaged, then calls set managed | `Cleared blocked numbers on becoming managed: 1`; `blocked_cleared_initial: 1`, `blocked_kept: 1`; no line, nothing orange | **Pass.** `Cleared blocked numbers on becoming managed: 1`, `99887766` stays; `blocked_cleared_initial: 1`, `blocked_kept: 1`, `blocked_cleared_at` unchanged; security lines 14 before and after | **Pass.** The same |
| The action with calls and SMS both managed | Resolves to `BlockedNumbersGate`, which closes at once | **Pass.** `resolve-activity` names `BlockedNumbersGate`; `am start` reports it | **Pass.** The same |
| The action with calls managed and SMS "Ikke styrt" (and the other way round) | The gate disabled: the action resolves to Telecom's page; a number the kid blocks there stays, a contact's is cleared | **Pass.** Both ways: the action resolves to and starts `BlockedNumbersActivity` (SMS role back with Google Messages, or the dialer role with Google's dialer); `55554444`/`55553333` blocked on that page stay; `91234567`/`+4791234567` cleared (`contact=1`) | **Pass.** The same (AOSP Messaging / Dialer get the roles back) |

## 6bh. The web filter's resolver: Quad9 first, Cloudflare as the fallback (branch `dns-upstreams`) - run 2026-11-24

`docs/notes/launcher/dns-and-vpn.md`, "The providers in a chain". Owed on Android 14 (`compat34`) and Android 16
(`handy36`); a fresh server (migration 0172 puts a stored `cloudflare` or `mullvad` on `quad9`), a phone with the Web filter on.

**Run 2026-11-24 (10:23-10:37 CET), main `6ce0dedf4`**, the same rig and phones as §6bg's second run (Android 14
`compat34`, Android 16 `aosp36`), after the PWA's "Lock again" ended the setup hold (the filter is off in it). The VM has
no root, so the host's `iptables`/`tcpdump` were replaced by the guest's own (`adb root`): a chain first in `OUTPUT`
counting Vibb One's uid to 9.9.9.9, 1.1.1.2, 1.0.0.2 and every UDP/TCP 53 packet not on `tun+` (IPv4 and IPv6, any uid),
then a chain of `REJECT`s for the blocks. Look-ups were made with `su 2000 ping -c1 <new name>` (through the VPN), a
report nudged with Apps' "Download apps on Wi-Fi only" off and on, the line read from `/dns`'s `data-dns-phone` rows.
A Wi-Fi off/on (`svc wifi`) reset the providers' state between rows.

| Row | Android 14 (`compat34`) | Android 16 (`aosp36`) |
|---|---|---|
| Default | **Pass.** No `DnsUpstream` line; "Fjorten · Quad9 answers" | **Pass.** "Seksten · Quad9 answers" |
| Fallback | **Pass.** Look-ups still answer; one query paid (`DoT attempt failed: ConnectException`, `DoH attempt failed: ConnectException`, 3 packets to 9.9.9.9, 15 to 1.1.1.2); "Cloudflare answers". Block lifted 10:25:41, look-ups every 10 s: back on "Quad9 answers" by 10:27:16 (63 packets to 9.9.9.9) | **Pass.** The same (3 to 9.9.9.9, 17 to 1.1.1.2; back on Quad9) |
| None | **Pass.** `unknown host` for each name; "DNS can't be reached since 10:27 - no DNS server answers from where the phone is" (orange: the template's `tone-orange`); 0 UDP/53 and 0 TCP/53 packets off `tun+` from any uid, IPv4 and IPv6, over 2 min 20 s | **Pass.** The same |
| 853 blocked | **Pass.** `DoT blocked: quad9 over DoH, periods=1`, 36 packets to 9.9.9.9:443, "Quad9 answers"; with 9.9.9.9 also blocked `DoT blocked: cloudflare over DoH`, 24 packets to 1.1.1.2:443, "Cloudflare answers" (the certificate check not tested apart) | **Pass.** The same (23 to 9.9.9.9:443; 28 to 1.1.1.2:443) |
| Parent's pick | **Pass.** Cloudflare picked: 0 packets to 9.9.9.9, 20 to 1.1.1.2; "Cloudflare answers"; the picker's options `quad9`, `cloudflare`, `custom`, no "mullvad" anywhere on `/dns` | **Pass.** The same (18 to 1.1.1.2) |
| Custom by name | **Pass.** `tls://family.adguard-dns.com` with backup `tls://unfiltered.adguard-dns.com`: `Bootstrap cache: 0 names`, 29 packets to 9.9.9.9, 0 to 1.1.1.2 and 1.0.0.2, the DoT connection up; no line (a working custom server is quiet) | **Pass.** The same (22 to 9.9.9.9) |

Noted, not faults: with a backup given as an IP literal (the default `https://1.1.1.3/dns-query`) the primary's name is
asked of that backup first (`bootstrapResolvers`), so `dns.adguard-dns.com` was looked up through 1.1.1.3 (Cloudflare
for Families, the parent's backup) and never through 9.9.9.9 - as the code says, outside this row (both by name). A
phone whose last report has no `provider` (after a Wi-Fi reset, before a look-up answered) has no line on `/dns` until
its next report (`phone_line`'s quiet case); seen once per phone.

**Changed after the run (branch `dns-bootstrap`, 2026-11-24) - owed again on Android 14 and 16:** the first note above
broke the owner's rule, so a custom server's name is now looked up through Quad9's unfiltered service alone
(`dns10.quad9.net` at 9.9.9.10, 149.112.112.10, 2620:fe::10, 2620:fe::fe:10 - never the parent's backup or primary, IP
literal or not; never Cloudflare; never the web filter's 9.9.9.9), and each provider has four fixed addresses (Quad9 9.9.9.9,
149.112.112.112, 2620:fe::fe, 2620:fe::9; Cloudflare 1.1.1.2, 1.0.0.2, 2606:4700:4700::1112, 2606:4700:4700::1002),
Quad9's DoH by name at them (`https://dns.quad9.net/dns-query`). The rows below are rewritten for that: the
"Fallback", "None" and "853 blocked" blocks now cover every address of a provider (IPv6 with `ip6tables`), and "One
address", "Custom, IP backup", "Custom, bootstrap blocked" and "Hanging handshake" are new (the last two from the
branch's code QA: the unfiltered bootstrap, and a DoT handshake that hangs leaving the DoH its time). The table above is the run's record of the code before.

| Row | What passes |
|---|---|
| Default | A fresh phone: `logcat` shows no `DnsUpstream` failures; the PWA's Web Filter footer says "<phone> · Quad9 svarer" within a report |
| One address | Block 9.9.9.9 alone (853 and 443), once with `REJECT` and once with `DROP`: pages still load and the line stays "Quad9 svarer"; packets go to 149.112.112.112, none to 1.1.1.2, 1.0.0.2 or Cloudflare's IPv6; no `DoT blocked: quad9` line with `REJECT` (the second address answers in the same query). With `DROP` the first query may take ~2.5 s more; the next ones start at 149.112.112.112 |
| Fallback | Block every Quad9 address (9.9.9.9, 149.112.112.112 with `iptables`; 2620:fe::fe, 2620:fe::9 with `ip6tables`; 853 and 443): pages still load; the line turns to "Cloudflare svarer"; within a minute of lifting the block (a back-off's probe) it is back on Quad9 |
| None | Block all eight (Quad9's four and Cloudflare's 1.1.1.2, 1.0.0.2, 2606:4700:4700::1112, 2606:4700:4700::1002): after 30 s the orange "DNS kan ikke nås siden ... - ingen DNS-server svarer ..."; never a plain UDP/53 or TCP/53 packet leaves the emulator (IPv4 or IPv6) |
| 853 blocked | Block port 853 only (IPv4 and IPv6): both providers' DoH on 443 answer, each by name at its fixed addresses (`dns.quad9.net`, `security.cloudflare-dns.com`; the certificate checked); `DoT blocked: quad9 over DoH` after the third query |
| Parent's pick | Pick Cloudflare in the PWA: the line says Cloudflare; the picker has no Mullvad |
| Custom by name | Custom with `tls://dns.example...` names (a server by name) and a backup by name: its name's look-up goes to Quad9's unfiltered service (9.9.9.10 first; any of its four addresses) on 853/443, never to Cloudflare (no packet to 1.1.1.2, 1.0.0.2 or their IPv6) and never to the web filter's 9.9.9.9 |
| Custom, IP backup | Custom with a primary by name (`tls://family.adguard-dns.com`) and the prefilled backup `https://1.1.1.3/dns-query`, the bootstrap cache cleared (Forget, then save again): the primary's name is looked up through 9.9.9.10 only - no packet to 1.1.1.3 (nor 1.0.0.3) while the primary answers, none to Cloudflare's security addresses |
| Custom, bootstrap blocked | The same, with 9.9.9.10 alone blocked (`REJECT`, 853 and 443): the look-up answers from 149.112.112.10; with all four unfiltered addresses blocked the look-up gives nothing (`Look-up gave nothing (primary=true)`, the backup answers) - still no packet to Cloudflare for the name |
| Hanging handshake | `DROP` only the server's answers to 9.9.9.9:853 after the TCP handshake (e.g. `iptables -I INPUT -s 9.9.9.9 -p tcp --sport 853 -m connbytes --connbytes 2: --connbytes-dir reply --connbytes-mode packets -j DROP`): a look-up still answers within ~5 s (the setup part ends ~1.4 s before the deadline, the DoH asks); the next ones start at 149.112.112.112 |

**Run 2026-11-24 (14:36-15:04 CET), main `32e22a1fd`** (debug launcher 0.23.6 and server from it, fresh on 3130/3131),
Android 16 (`aosp36`) and Android 14 (`compat34`), both with `adb root`, the Web filter on (the VPN up, the phones in
the enrolment's setup hold). The same counting chain as the run above (`OUTPUT`, every address of both providers,
Quad9's four unfiltered addresses and 1.1.1.3/1.0.0.3, IPv4 and IPv6, plus UDP/TCP 53 off `tun+`), a `REJECT` chain for
the blocks; look-ups with `su 2000 ping -c1 <new name>`; the custom rows each from a fresh bootstrap (Forget, Quad9, then
the custom server saved again); a fresh launcher process (`run-as ... kill -9`) where a provider's state had to start
clean.

| Row | Android 14 (`compat34`) | Android 16 (`aosp36`) |
|---|---|---|
| Default | **Pass.** 10 packets to 9.9.9.9, none elsewhere; "P14 · Quad9 answers" | **Pass.** 15 to 9.9.9.9; "P16 · Quad9 answers" |
| One address | **Pass** (`REJECT`): one `DoT attempt failed`, 14 packets to 149.112.112.112, none to Cloudflare, no `DoT blocked: quad9`; "Quad9 answers" | **Pass** (`REJECT`): the same (14 to 149.112.112.112). `DROP`: the next look-ups went straight to 149.112.112.112 (0 to 9.9.9.9); the first query's extra ~2.5 s not seen (the order was already remembered) |
| Fallback | **Pass.** `DoT/DoH attempt failed` for Quad9's four, 13 to 1.1.1.2, "Cloudflare answers"; back on Quad9 after the lift (33 to 149.112.112.112, 1 to 1.1.1.2, "Quad9 answers") - seen ~3 min after the lift, since the first look-ups after it repeated one cached name | **Pass.** 12 to 1.1.1.2, "Cloudflare answers"; 44 to 149.112.112.112 within ~90 s of the lift, "Quad9 answers" |
| None | **Pass.** `unknown host` for each; orange "DNS can't be reached since 15:01 - no DNS server answers from where the phone is"; 0 UDP/53 and 0 TCP/53 off `tun+`, IPv4 and IPv6 | **Pass.** The same ("since 14:41") |
| 853 blocked | **Pass.** `DoT blocked: quad9 over DoH, periods=1` (fresh process), 15 to 9.9.9.9, the DoH connection to 9.9.9.9:443, "Quad9 answers" (the certificate check not tested apart) | **Pass.** The same (27 to 9.9.9.9) |
| Custom by name | **Pass.** `tls://family.adguard-dns.com`, backup `tls://unfiltered.adguard-dns.com`: the names' look-ups to 9.9.9.10 (28 packets) - in a second run to 149.112.112.10, 9.9.9.10 having failed before; never 1.1.1.2/1.0.0.2; to 9.9.9.9 only 40-byte packets with no SYN (the old Quad9 connection closing at the switch), no query | **Pass.** 28 to 9.9.9.10, nothing else; the DoT connection to AdGuard's 94.140.14.15:853 up |
| Custom, IP backup | **Pass.** Backup `https://1.1.1.3/dns-query`: 11 to 9.9.9.10, 0 to 1.1.1.3 | **Pass.** The same |
| Custom, bootstrap blocked | **Pass.** 9.9.9.10 `REJECT`: 1 to it, 11 to 149.112.112.10; all four blocked: `Look-up gave nothing (primary=true)`, `Upstream: backup, reason=bootstrap`, 17 to 1.1.1.3, none to 1.1.1.2/1.0.0.2 | **Pass.** The same |
| Hanging handshake | **Not run:** the Android 14 image's kernel has no `connbytes` match | **Pass.** 8 answers from 9.9.9.9:853 dropped; `DoT attempt failed: SocketException`, the first look-up answered in ~3.6 s, the next ones from 149.112.112.112 (0.6 s, 0.1 s) |

## 6bi. Lyd: a notification sound on the locked phone (design 130, branch `lock-sound`) - run 2026-11-24

Design 130 (`docs/design/130-lock-sound.md`): Lock Screen & PINs' "Varsling" is Av / Vibrer / Lyd
(`launcher_ui.lock_notice_alert`). Run on the lowest and the highest image it touches (Android 14 and 16/17), with a
server from the branch, the debug launcher, calls and SMS managed, Meldinger on the lock (Name only) and the phone
locked and dark. The emulator has no motor and its audio is the host's: a buzz is `I/PinLock: Notice buzz`, a sound
`I/PinLock: Notice sound` beside `dumpsys audio` ("playback activity": a player with `usage=USAGE_NOTIFICATION`,
`content=CONTENT_TYPE_SONIFICATION`, the launcher's uid) and `dumpsys media.audio_flinger` showing a track on the
notification stream.

- **The PWA:** the cell under "Vekk skjermen ved varsler" reads "Varsling" with Av selected on a new phone; Lyd saved
  lands on the same row (no jump to the top), the security log says "telefon N: varsling: lyd", the policy's
  `launcher_ui.lock_notice_alert` is `"sound"` (`curl` the policy with the phone's token). A server upgraded from a
  database with the buzz switch on shows Vibrer.
- **Lyd, ringer Normal:** an SMS from a contact: one `Notice sound`, no `Notice buzz`; a second SMS in the same thread
  within 10 s: nothing; after 10 s: one more. A missed call: one.
- **Lyd, ringer Vibrate** (`adb shell cmd audio set-ringer-mode VIBRATE` or Kid Settings' sound choice): `Notice buzz`,
  no sound. **Silent:** neither.
- **Do Not Disturb** (`cmd notification set_dnd priority`) and a time rule's silence: neither, the row still comes.
- **Notification volume 0** (ringer Normal, `cmd media_session volume --stream 5 --set 0`): `Notice sound` logs, nothing
  is heard - Android's own behaviour.
- **Unlocked** (Vibb's lock off or the phone open): nothing from the launcher. **During a call:** nothing.
- **Default notification sound "None"** (`settings put system notification_sound ''`): nothing heard, nothing throws.
- **Av and Vibrer** as before (§6ad, §6af): Vibrer buzzes on Normal and Vibrate, Av never.
- **Not on the emulator:** whether the Jelly Star (16) plays it from the lock and how loud, a custom notification sound
  on storage (the remote-player fallback).

**Run 2026-11-24 (14:28-14:53 CET), main `32e22a1fd`**, Android 16 (`aosp36`) and Android 14 (`compat34`), the same
server and phones as §6bj's run: the contact Mamma `+4791234567` (calls and SMS both ways), Meldinger on the lock "Name
only", a Child PIN (Android's lock, `kid_keyguard` `android`), the phone locked by screen-off (`UNLOCKED -> LOCKED on
ScreenOff`). SMS with `adb emu sms send +4791234567 <text>` - without the `+` the sender is held as unknown and nothing
reaches the lock. On `compat34` the image floods the 2 MiB log buffer (an installed Thunderbird), so `adb logcat -G 16M`
first; and Android 14 has no `cmd audio set-ringer-mode` (it prints nothing and changes nothing): the ringer was set with
Settings' Ring volume slider (`am start -a android.settings.SOUND_SETTINGS`, the slider to 0 = Vibrate).

| Row | Android 14 (`compat34`) | Android 16 (`aosp36`) |
|---|---|---|
| The PWA | (the same server) | **Pass.** "Alert": Off / Buzz / Sound, Off on a new phone; Sound saved back to `#lock-alert`, `selected` on Sound; the security log `lock_alert_changed` "telefon 2: varsling: lyd"; `device_policy.lock_notice_alert` `sound`, the phone's `Notice alert: sound`. The upgraded-database row not run (a migration, `cargo test`) |
| Lyd, Normal: one SMS; a second within 10 s; one after 10 s; a missed call | **Pass.** `Notice sound`; nothing; `Notice sound`; the missed call `Notice sound` | **Pass.** The same; `dumpsys audio`: a player of uid 10150 (the launcher) with `usage=USAGE_NOTIFICATION content=CONTENT_TYPE_SONIFICATION` |
| Lyd, Vibrate | **Pass.** `Notice buzz` (41 ms in `vibrator_manager`) | **Pass.** `Notice buzz` |
| Lyd, Silent | **Not run** (no way to Silent from the shell on 14) | **Pass.** Neither |
| Do Not Disturb (`set_dnd priority`) | **Pass.** Neither | **Pass.** Neither; the lock's rows still came. A time rule's silence not run |
| Notification volume 0 | **Pass.** `Notice sound` logged | **Pass.** The same |
| Unlocked | **Pass.** Nothing | **Pass.** Nothing (`LOCKED -> UNLOCKED`, then an SMS) |
| During a call | **Pass.** Nothing while it rang; `Notice sound` for the missed call after it | **Pass.** The same |
| Default sound "None" | **Pass.** `settings delete system notification_sound` (the shell can't store an empty value): `Notice sound` logged, nothing played, no exception | **Pass.** The same; `Ringtone: Neither local nor remote playback available` |
| Vibrer and Av | **Pass.** Vibrer on Vibrate: `Notice buzz`; Av: nothing (Vibrer on Normal not run here) | **Pass.** Vibrer: `Notice buzz` on Normal and Vibrate; Av: nothing |

## 6bj. The hardware-bound phone key, L1 (design 129, branch `hw-key-l1`) - run 2026-11-24

Design 129 §8's gate, the launcher's half (`docs/notes/launcher/security.md`, "The hardware-bound phone key, L1").
**Android 14 and 16 only** (never 17: its image is broken; the code still targets 14-17). A server from main (S1) built
with the Cargo feature `emulator_keys` once §7.3 has read the gate images' chains - until then a release-feature server
refuses the emulator's chain (`software` or `root`), which is itself a row below. The debug launcher (`.debug`, its
digest in `LAUNCHER_DEBUG_SIGNATURE_CHECKSUM` for `emulator_keys`), enrolled as usual. Logs: `adb logcat -s
KeyRegistration DeviceKey ServerAuth Enrollment`; the server's side is About This Phone › Connection's "Innlogging" row
and the security log. The owner's OK for the emulator first, as always.

**Since design 129b's step E2 the feature exists** (`docs/notes/server/device-key.md`, "`emulator_keys`"): build the
server with `cargo build --features emulator_keys` (the debug profile - a release build refuses to compile it), set
`LAUNCHER_DEBUG_SIGNATURE_CHECKSUM` to the debug launcher's signing certificate in the QR's form (as
`LAUNCHER_SIGNATURE_CHECKSUM`: `apksigner verify --print-certs` on the debug APK, its SHA-256 as Base64url without
padding), and check the start's warning `built with emulator_keys` and Settings' orange "Serveren godtar testnøkler fra
emulatorer". A key it takes shows About's orange "Testnøkkel fra emulator" (level "Bare programvare", root "Emulator").
Unset, or the release launcher on the emulator: `app`. Design 129b §10.3 is that gate's table; the throwaway patch
below is no longer needed. The profile's vectors are made up in the images' shape, so that gate's first run is the
profile's first real check: keep one chain from each image (Android 14 and 16) with the time it was read and add them
as rows of `server/testdata/attestation/emulator/rows.json` then (done 2026-11-24: §6bm, both passed).

- **Step 0 of §7.3 (read only), done 2026-11-24 (below):** each image's chain was read and §7.3 corrected. The root
  changes with every wiped image, so it is deliberately not pinned in `server/testdata/attestation/roots/`:
  `emulator_keys` takes it by its name and its own signature.
- **A fresh enrolment with a key:** "Innlogging" reads "Maskinvarenøkkel" (or, on a release-feature server, "Nøkkel
  avvist (...)" - the phone enrols on its bearer either way); `I/KeyRegistration` nothing more to do; the next policy
  GET goes without `If-None-Match` once and the next report is full (the owner moved to `key:<id>`).
- **A phone enrolled before L1 (migration):** install the L1 build over the older one; the first sync's policy has
  `device_key.state: none`, the flow registers (`GET /key/challenge`, `POST /key`), adopts, and every later request
  carries `Vibb-Signature` beside the bearer (the server's `last_signed_at` moves; "Innlogging" "Maskinvarenøkkel").
- **Every refusal leaves the bearer:** with the server's `LAUNCHER_SIGNATURE_CHECKSUM` unset (`app`): the key is
  deleted, `lastRefusal` `app`, the phone syncs on as before, no second registration for a day (a process restart may
  try once). `list_stale` (an old `data/attestation/status.json`, the fetch blocked): the phone waits `retry_after_s`.
- **A restart's `stale`:** restart the server: a request signed with the old offset is `stale` against the new floor,
  answered 200 on the bearer (S1; the server never logs `stale`, `AuthReason::counts`); the answer's `Vibb-Time` moves the phone's offset and the
  next request verifies. With
  `adb shell settings put global auto_time 0` and the phone's clock a day off, signatures still verify (the offset is
  the server's, from `Vibb-Time`).
- **A lost answer:** a proxy (or `tc`) that holds `POST /api/devices/key`'s answer past the phone's 10 s read timeout:
  the flow's step 1 finds the key and adopts it; no second key is made in that run; the key book (`run-as
  me.vibb.launcher.debug cat shared_prefs/device_key.xml`) shows any extra key deleted at adoption.
- **Parallel workers during registration:** "Sync now" three times and a music sync while the registration runs: one
  run (`KeyRegistration` logs once), no second key.
- **The SSE closed at binding:** S1 never binds; the row is S2's - here only: the stream's connect carries
  `Vibb-Signature` once a key is adopted (server log), and reconnects work.
- **The key before the first unlock:** reboot, don't unlock: the phone is silent until the first unlock, as today (the
  token and the key book are CE prefs); after the unlock the first request is signed (the Keystore key itself needs no
  unlock).
- **Generation in the SINK shape with an empty pool:** Mobilnett "Ingen data" abroad (`docs/testing/emulator.md`
  §6al's roaming image) so the VPN is the sink with lockdown; `adb shell dumpsys connectivity` shows the always-on
  lockdown allow list with `com.android.rkpdapp` (when the image has it) and the VPN's disallowed apps include it;
  with the pool emptied (`adb shell cmd remote_provisioning` where the image has it) the key generation logs
  `No key made: keys_unavailable` and the next run after the backoff succeeds once rkpd refilled the pool.
- **Never "lost" on one answer (code QA H1):** no bearer can't happen in S1 - simulate with the debug build's token
  cleared and the key's row deleted on the server: the first step-1 `unknown_key` is a strike (`KeyRegistration`
  logs no "a new setup code"), the phone keeps signing; "Ny oppsettkode" shows only after three strikes over an hour;
  restoring the row (or a server whose key index is stale - it answers 503) clears it at the next step 1.
- **rkpd found by its service (code QA M4):** `adb shell cmd package query-services -a
  com.android.rkpdapp.IRemoteProvisioning` names the package the VPN excludes on each image (AOSP's
  `com.android.rkpdapp`; the Jelly Star has `com.google.android.rkpdapp`).
- **Android 14 vs 16:** StrongBox absent on both images (TEE); `attestationVersion` as each image reports it (100/200
  on 14, 300+ on 16) - noted for §10.

### The run of 2026-11-24 (13:52-14:27 CET)

Main `32e22a1fd` (debug launcher 0.23.6, server 0.92.x built from it, fresh on 3130/3131 with a dummy
`LAUNCHER_SIGNATURE_CHECKSUM` and the debug component), Android 14 (`compat34`, google_apis) and Android 16 (`aosp36`),
each from `-wipe-data`, "Block USB debugging" off before the enrolment, a typed setup code. The phones talked to the
server through a small logging proxy (port 3132 in front of 3131: each request's method, path, status, whether a bearer
and a `Vibb-Signature` came - only its `k=` and `t=` -, `Vibb-Auth` and `Vibb-Time`; the `chain` of enrol and key
bodies kept to read; a switch that holds `POST /api/devices/key`'s answer 15 s). No `emulator_keys` feature exists yet,
so the rows that need an accepted key ran against the same server with a **throwaway local patch, never committed**:
behind an environment variable, `attestation::verify` took an unpinned root, the provisioning info on the root, the
software list as the hardware one, `Software` as TEE and any app (exactly what step 0 below says `emulator_keys` needs).

**Step 0 (read only), the release-feature server.** Both images' chains have three P-256 certificates: the leaf
("Android Keystore Key"), an intermediate `O=TEE, CN=<32 hex>` (valid ~2 weeks) and a self-signed root `CN=Droid
Unregistered Device CA, O=Google Test LLC` (valid ~10 weeks) - remotely provisioned through rkpd from Google's backend
for unregistered devices. **The root is not stable:** each `-wipe-data` of either image brought a different root key
(three seen: 14 twice, 16 once), so `server/testdata/attestation/roots/` can't pin it; the provisioning-info extension
(`1.3.6.1.4.1.11129.2.1.30`) is on the intermediate **and** on the root (S1's check refuses it on the root as `chain`).
The KeyDescription: `attestationVersion` and KeyMint 300 on 14, 400 on 16, **both security levels `Software`**, every tag
in the software list (the hardware list empty), RootOfTrust unverified, unlocked, a zero boot key, `osPatchLevel`
2023-09 (14) / 2025-07 (16), the app `me.vibb.launcher.debug` with the debug signer. No StrongBox feature on either
(`hardware_keystore` 300 / 400). So §7.3's `emulator_keys` must accept that root by its name or any self-signed root
(not a pinned key), the provisioning info on the root, the software list, the `Software` level and the debug package;
design 129 §7.3 to be corrected. The release server refused both: `root` ("Key refused (unknown issuer)" on About This
Phone, the security log's `device_key_refused`).

| Row | Android 14 (`compat34`) | Android 16 (`aosp36`) |
|---|---|---|
| Fresh enrolment, release server | **Pass.** Enrolled on the bearer (`Enrollment: The server refused the enrolment's key: root`), syncs on; see finding 2 | **Pass.** The same |
| Fresh enrolment, accepting server | **Pass.** `via=enroll`, `observed`; the first policy right after it 200 (no ETag), every request signed with the new key at once, no `KeyRegistration` run; the old enrolment's keys gone from the key book | **Pass.** The same |
| A bearer-only phone registers (the migration's flow; the install over a pre-L1 build not run) | **Pass.** After a process restart: `GET /key` (none), the challenge, `POST /key` 200, step 1 again, `Key adopted (observed)`; the next policy 200 without the ETag, then every request with `Vibb-Signature` beside the bearer; About This Phone "Hardware key - In the phone's secure area (TEE), attestation 300, security patch 2023-09. Unknown or unlocked system. Google (2026). Registered from an unusual address."; `last_signed_at` moved | **Pass.** The same |
| Every refusal leaves the bearer | **Pass.** `root`: the key deleted (the book `{"next":4,"last_refusal":"root"}`), the phone synced on, two nudged syncs asked nothing; a process restart tried once more (refused again). `list_stale` (the stored list set 8 days old, Google unreachable through a dead proxy): `POST /key` 503, the key deleted, `last_refusal` `list_stale`, no further try on the next sync. `app` not reachable (the root is refused first) | **Pass** (`root`; the same) |
| A restart's `stale` | **Pass.** The first request after the restart signed ~98 s behind the new floor: 200 on the bearer, no `Vibb-Auth`, nothing logged (`stale` never is); the next request's `t` within 0.3 s of the server's. Signature-only (bearer removed, below): `401 stale`, sent once more, 304 | **Pass.** The same (98 s; signature-only `401 stale` then 200) |
| The phone's clock a day ahead (`auto_time 0`, `cmd alarm set-time`) | **Pass.** Signatures on the server's time; after a process restart the first request's `t` was the real time (TrustedClock), not +1 day | **Pass.** The same |
| A lost answer (the key POST held 15 s) | **Pass.** `The registration's POST had no answer: SocketTimeoutException` at 10 s, step 1 named the new key, `Key adopted (observed)`; one key made; the old key deleted, one key in the book | **Pass.** The same |
| Parallel workers during that registration (three nudged syncs) | **Pass.** One run (one challenge, one POST), the syncs ran beside it | **Pass.** The same |
| The SSE | **Pass.** The stream's connect signed once a key was adopted; reconnects after each server restart worked | **Pass.** The same |
| The key before the first unlock (an Android PIN, reboot) | **Pass.** No request for 40 s while locked; after the PIN the first request signed (`stale` after the boot, sent again, 304) | **Pass.** The same (65 s locked) |
| Never "lost" on one answer (bearer removed from the debug build's prefs, the key's row deleted on the server) | **Pass, with finding 1.** `unknown_key` on every call, no "a new setup code" line, the book's `suspicion` counting, the phone signing on; the row restored: requests verify again signature-only | **Pass, with finding 1.** The same |
| rkpd found by its service; outside the VPN | **Pass.** `com.google.android.rkpdapp` (the google_apis image has Google's, not AOSP's), its uid outside the filter VPN's uid ranges | **Pass.** `com.android.rkpdapp`, outside the VPN |
| Generation in the SINK shape with an empty pool | **Not run:** no roaming image here, and neither image can empty the pool (`cmd remote_provisioning` has only `list`, `csr`, `dump`) | **Not run** (the same) |
| Android 14 vs 16 | KeyMint/attestation 300, `Software`, no StrongBox | 400, `Software`, no StrongBox |

Findings (not fixed here; for the L1 and S1 owners):

1. **A suspicion of a lost key outlives the key's return** (launcher, `KeyRegistration`): `book.suspicion` is cleared
   only by a step 1 that finds the key, and once the server knows the key again nothing asks step 1 (the policy names
   the adopted key, signed calls succeed) - after the row came back the book still held `strikes: 15` (14) / `8` (16)
   and the first strike's time. A single `unknown_key` an hour or more after that first strike then makes the key
   "lost" at once, which is what code QA H1 ruled out. And `wake()` asks step 1 on every `unknown_key` answer whatever
   the backoff, so each SSE retry (every 5-10 s) is a step 1 and a strike: 12 strikes in the first minute. Fix
   suggestion: clear the suspicion on a 2xx to a signed request (or ask step 1 when a suspicion is held), and let
   `wake()` keep the backoff.
2. **A refused enrolment key is followed by a second key at once** (launcher): the enrolment's refusal sets no backoff,
   so the first sync's policy (`device_key.state: none`) starts a registration, which makes a new key and is refused
   again (then waits a day). One wasted key and a second `device_key_refused` per enrolment.
3. **The server's signature clock moves 100 s ahead of the wall clock at every restart, for good** (server,
   `sig_base`): the stored value is the signature clock itself, so `max(wall, stored + 100 s)` only grows; after ~10
   restarts in this run `Vibb-Time` was ~17 min ahead. With the launcher taking `Vibb-Time` only from signed exchanges
   (`credentialSigner`) and TrustedClock's real time before that, every process start's first signed request (and the
   first after an adoption) is `stale` - one extra round trip (in S2 a 401 and a retry). Harmless now; months of
   automatic restarts make it hours.

All three fixed on branch `hw-key-gate-fixes` (unit-tested, not re-run on the emulator): 1 - a 2xx or 304 to a request
the key signed with no bearer ends the suspicion (`signatureKnown`), `wake()` keeps the backoff, and a step-1
`unknown_key` within ten minutes of the last counted strike is none (`lostVerdict`); 2 - the enrolment's `refused`
sets the registration's day-long backoff (`KeyRegistration.refusedAtEnrolment`); 3 - a signature clock ahead of the
wall clock runs at nine tenths of real time until the wall clock catches it up (`sig_now_at`, design 129 §4.3). §7.3
now says what step 0 found and what `emulator_keys` must accept.

## 6bl. The glance back after a plug-in (PLAN (4i), branch `glance-back`) - run 2026-11-24

The charging glimpse (`docs/notes/launcher/lock-and-glance.md`, "(4i) The charging glimpse"): on **Android 14 and 16**,
with the user's OK. The debug launcher as Device Owner, the kiosk on, Android's PIN set (the glance is Android's face),
Settings not on the allowlist. Logs: `adb logcat -s AndroidLock PinLock ActivityTaskManager`. A plug-in is
`adb shell dumpsys battery unplug` then `adb shell dumpsys battery set usb 1` (it wakes the screen; the unplug alone is
a power change too); `dumpsys battery
reset` at the end. The blocked start is `adb shell am start -n com.android.settings/.Settings` (Android starts
`BlockedAppActivity` in a task of its own). The emulator may have no SOS app (`PinLockRuntime.sosPackage` null), so row 2
shows the plug-in's look, and rows 6 and 8 the SOS rule only where one is installed.

| # | Step | Expect |
|---|---|---|
| 1 | Locked, screen off, a plug-in | the screen wakes, the glance is up and stays; no `AndroidLock: power changed` (nothing lost) |
| 2 | Locked, screen off: a plug-in and the blocked start in one line (`... set usb 1; am start -n ...`) | `BlockedAppActivity` in `ActivityTaskManager`, the glance paused and stopped; within ~1 s `AndroidLock: power changed` and `re-front #1`, the glance in front again with the keyguard occluded (`dumpsys window | grep -i occluded`); no `probably Emergency SOS` line |
| 3 | Locked, screen on: swipe or tap the glance up to Android's PIN pad, then a plug-in | the pad stays (the ask is ours): no re-front; the right PIN unlocks as before |
| 4 | Locked: an incoming call (`adb emu gsm call 5551234`), then a plug-in | the call screen stays (`Lock yields to an exempt screen`); the glance back after the hang-up as before |
| 5 | Locked, one screen-on: three times `dumpsys battery unplug; dumpsys battery set usb 1; am start -n ...` | at most two re-fronts (`re-front #1`, `#2`), then Android's lock screen until a screen cycle - no loop. **Since `glance-replug`:** each plug-in gets the glance back (`re-front #1` each); "Row 5 again" below |
| 6 | With an SOS app: no plug-in, the power button, then the blocked start within 3 s | unchanged: the "sos" yield (`probably Emergency SOS`) |
| 7 | Unplugged (`dumpsys battery unplug`), the same blocked start with the screen on well after its screen-on | unchanged: the face's re-front (`re-front #1`) |
| 8 | With an SOS app: a plug-in (the screen wakes), then five power presses within 2 s | the "sos" yield (`probably Emergency SOS`, `Lock yields to an exempt screen: sos`); no `power changed` re-front over the SOS screen |
| 9 | Locked, screen off: `dumpsys battery unplug` (from charging) and the blocked start in one line | as row 2: the unplug's wake is no SOS, the glance back within ~1 s |

### The run of 2026-11-24 (23:21-00:01 CET)

Branch `gate-129b` at main `31a123725` (the merged `glance-back`), the debug launcher as Device Owner on Android 14
(`compat34`) and Android 16 (`aosp36`), each from `-wipe-data`, enrolled on the §6bm server (exempt), Android's PIN
`2580` (the Child PIN, `kid_keyguard` `android`), the setup hold ended from the PWA so the kiosk was on
(`mLockTaskModeState=LOCKED`), the contact Mamma `+4791234567` for row 4. Logs: `AndroidLock`, `PinLock`,
`ActivityTaskManager` after `logcat -G 16M`. What the table's method had to change on these images:

- **No plug-in wakes the screen on either image** (`dumpsys power`: `mWakeUpWhenPluggedOrUnpluggedConfig=false`; a
  fabricated overlay of `config_unplugTurnsOnScreen` as root didn't reach `system_server`). A plug-in was therefore
  `dumpsys battery unplug; dumpsys battery set usb 1; input keyevent KEYCODE_WAKEUP` in one shell line - the screen-on
  comes within milliseconds of `ACTION_POWER_CONNECTED`, the same input `powerWokeScreen` reads from a phone's own
  wake; an unplug the same with `unplug` alone.
- **Settings is hidden while it isn't allowed**, so `am start -n com.android.settings/.Settings` is "does not exist".
  The blocked start was `am start -a android.intent.action.SET_WALLPAPER` (the wallpaper picker, not pinned): Android
  answers it with `BlockedAppActivity` in a task of its own, as the table expects.
- **Both images have an SOS app**, `com.android.emergency` (the table guessed none), so rows 6 and 8 ran. Five presses
  for the SOS gesture (`settings put secure emergency_gesture_enabled 1`): `input keyevent` is either too slow (one
  process per press, the taps don't count as consecutive) or too fast in one call ("Emergency gesture detected but
  it's too fast. Gesture time: 15 ms"); what works is `adb root` and a press's four input events (key down, sync, key
  up, sync; 96 bytes) written to the "Power Button" evdev node five times 200 ms apart (`cat press.bin >
  /dev/input/eventN; sleep 0.2; ...`): "Emergency gesture detected, launching" after ~0.9 s.

| # | Android 14 (`compat34`) | Android 16 (`aosp36`) |
|---|---|---|
| 1 | **Pass.** The glance resumed with the screen-on and stayed; no `power changed` | **Pass.** The same |
| 2 | **Pass.** `BlockedAppActivity` started (`ActivityTaskManager`), the glance paused (and on another try stopped), `re-front #1` 0.2 s after the pause, the glance resumed, `Occluded=true`; no `probably Emergency SOS` line. The re-front came from the face's own rule at once (the power change's wake is exempt from the SOS rule, so the loss was an ordinary one), so `powerRecheck` found the glance in front and logged nothing | **Pass.** The same (paused, stopped, `re-front #1 (the glance stopped)` 0.2 s later, resumed) |
| 3 | **Pass.** A swipe up -> Android's PIN pad (`Lås opp: requestDismissKeyguard`); a plug-in: the pad stayed (seen through `emu screenrecord screenshot`), no AndroidLock line; `2580` -> `LOCKED -> UNLOCKED`, Home | **Pass.** The same |
| 4 | **Pass.** Mamma's call: `Lock yields to an exempt screen: call`, `InCallActivity` in front; a plug-in: the call screen stayed; after the hang-up the glance resumed | **Pass.** The same |
| 5 | **Pass.** The first plug-in and start: `re-front #1`; the second, 3 s later on the same screen-on: the screen-on was the first change's, 3 s old, so the SOS rule took the loss (`probably Emergency SOS`, `yields ... sos`) and Android's lock screen stayed - no loop. This is the narrow case the note names (a screen-on 2-3 s before a change can't be told from a press-SOS) | **Pass.** `re-front #1`, `re-front #2`, then the third: stopped, `power changed` 1 s later and no re-front (the cap): Android's lock screen (`Occluded=false`) until a screen cycle |
| 6 | **Pass.** The power key (the screen on), the blocked start 1 s later: `probably Emergency SOS`, `Lock yields to an exempt screen: sos` | **Pass.** The same |
| 7 | **Pass.** Unplugged, the blocked start 6 s after the screen-on: `re-front #1 (the glance paused)`, resumed | **Pass.** The same |
| 8 | **Pass.** A plug-in (the screen on), then the five presses: `com.android.emergency/.action.EmergencyAction` in front, `probably Emergency SOS`, `Lock yields to an exempt screen: sos`; no `power changed` re-front over it | **N/A: the image's SOS app crashes at launch.** The gesture fired ("Emergency gesture detected, launching", `LAUNCH_EMERGENCY` started) but `com.android.emergency` died at once (`ClassNotFoundException: com.android.emergency.action.action.EmergencyActionActivity` - the AOSP 16 image's own bug); the glance was paused 56 ms and back, nothing to yield to |
| 9 | **Pass.** Charging, screen off: the unplug, its wake and the start: paused, stopped, `re-front #1` 0.2 s later, resumed; no SOS line | **Pass.** The same |

Nothing failed. Not on the emulator: a real plug-in's own wake (the Jelly Star wakes on USB) and Unihertz's
`ChargingDialog`; the owner's case from the field is the Jelly Star's to confirm.

### Row 5 again: plug-ins on one screen-on (branch `glance-replug`) - runs 2026-11-26/27

The owner's log (rc.6, 2026-11-26 22:26): three plug-ins within 10 s with the screen on - the third met row 5's cap
of two per screen-on (`power changed`, no re-front) and Android's lock screen stayed. The fix (`lock-and-glance.md`,
"The power change's budget" and "The stamp is late"): the cap per screen-on stays; each power change opens a budget of
three re-fronts for a loss inside its window (-1 s to +1.5 s around its stamp), at most four spent per screen-on; the
plug-in's own wake is a screen-on that ends a dark spell of at least 1 s, up to 0.8 s before the stamp.

Method: `aosp36` (Android 16) and `compat34` (Android 14) as the day's gates left them (the debug launcher as Device
Owner, Android's PIN set, kiosk on), the branch's debug build installed over it (`adb install -r`, `adb shell sync`).
Each row of 5b-5d: a screen cycle (`input keyevent KEYCODE_SLEEP`, then `KEYCODE_WAKEUP`), 5 s (outside the SOS
window), then three cycles 4 s apart, each after `input keyevent KEYCODE_SHIFT_LEFT` - user activity, as the owner
touching the screen: without it the un-occluded keyguard sleeps after 10 s (`Going to sleep due to timeout ...
activityTimeoutWM=10000`), the screen-off resets the count and the run tests nothing. The blocked start is
`am start -a android.intent.action.SET_WALLPAPER` (`BlockedAppActivity`, as above). Logs `AndroidLock`, `PinLock`;
`dumpsys window` for `mKeyguardOccluded`; `dumpsys activity broadcasts history` for when `ACTION_POWER_CONNECTED`
reached the launcher (`DELIVERED scheduled +N ms` on our `BroadcastFilter`).

Two rounds. **First** (22:38-23:09 CET, both versions): `4b593eb73` - a count reset at each change and a reserved
re-front for the look; it found the late stamp (below). **Final** (00:05-00:18 CET, the code QA's budget and wake rule,
`6c10933c0`): all rows on Android 16; on Android 14 only the delivery measurements - the night gate had left `compat34`
without Android's PIN and with a policy without the Child PIN (the lock `DISABLED`, the glance not Android's face), and
that phone's server was the gate's, so its policy wasn't changed. Android 14's face rows are the first round's; the
rule changed since only in pure logic (`PowerGlanceTest`, `FaceRefronts`, `faceRefrontStep`).

| # | Step | Android 14 (`compat34`) | Android 16 (`aosp36`) |
|---|---|---|---|
| 5a | The build before the fix (versionCode 116): three times `dumpsys battery unplug; dumpsys battery set usb 1; am start ...` | **Reproduced** (first round). `re-front #1`, `#2`, the third: paused, `power changed`, stopped, no re-front, `mKeyguardOccluded=false` | **Reproduced** (first round). The owner's log: paused, stopped, `power changed`, no re-front, Android's lock screen |
| 5b | The same three on the fix | **Pass** (first round): `re-front #1` each time | **Pass** (final): `re-front #1 of the power change` each time, occluded after every cycle |
| 5c | Two blocked starts with no power change (the screen-on's cap reached), then `am start ...; sleep 0.05-0.12; dumpsys battery unplug; set usb 1` - the loss before the broadcast | **Pass** (first round, after the window fix): the broadcast 80-150 ms after the pause, `scheduled +501ms`; the pause's check met the cap, `power changed` and `re-front #1` by the look ~1.5 s after the pause | **Pass** (final). 0.05 s: the broadcast reached us at once (+6 ms), the pause's check took the budget (`re-front #1 of the power change`). 0.08 and 0.12 s: `+502ms`/`+500ms`, the pause's check met the screen-on's cap, the look's `power changed` and `re-front #1 of the power change (the glance stopped)` |
| 5d | Three blocked starts, no power change | **Pass** (first round): `#1`, `#2`, then Android's lock screen - no loop | **Pass** (final): the same |
| 5e | Screen off 3 s, `input keyevent KEYCODE_WAKEUP; am start ...; sleep 0.6; dumpsys battery set usb 1` - a press's screen-on, a probable SOS, then a plug-in | **Pass** (first round): `probably Emergency SOS`, `Lock yields to an exempt screen: sos`, no `power changed` | **Pass** (final): the same |
| 5e' | As 5e with `sleep 0.25` - the stamp 0.75-0.95 s after that single press's wake | not run | **As designed** (final): stamp 934 ms after the wake - the mark kept; 753 ms - read as the plug-in's wake (a long dark spell, inside 0.8 s): `power changed`, the mark cleared, `re-front #1 of the power change`. The edge the note names: one press after a long dark can't be told from the plug-in's wake; the SOS gesture's last screen-on ends a short dark spell (D3) |
| D1 | Screen dark 4 s, a plug-in with no wake: when the broadcast reaches the launcher | **`+500ms`, `+501ms`, `+501ms`** (final build; the lock `DISABLED`, the receiver is the same) | **`+501ms`, `+500ms`, `+500ms`** |
| D1' | Screen dark 4 s, a plug-in and `KEYCODE_WAKEUP` in one line | **`+57ms`, `+46ms`** | **`+22`..`+350ms`** over ten tries (D2, D3; the deferral ends once we are in front) |
| D2 | D1' and the blocked start in one line (row 2 above, with the late stamp) | not run (no face) | **Pass** (final): the pause and the stop came before the stamp, so the pause's mark was set; the look cleared the plug-in's own mark (`power changed`, `re-front #1 of the power change`), occluded ~1 s after |
| D3 | D1', then a press-SOS's pattern: `KEYCODE_SLEEP`, `KEYCODE_WAKEUP` 30-290 ms later, the blocked start 0.3 s after | not run (no face) | **Pass** (final), four tries: `probably Emergency SOS`, `Lock yields to an exempt screen: sos`, no `power changed`, no re-front. The emulator ended the deferral at the wake, so the stamp always came before the press's screen-off - the code QA's order (the stamp between the off and the on) is `PowerGlanceTest`'s |

**Found by the runs:** the stamp is the broadcast's delivery to us, and Android's broadcast queue holds it ~500 ms for
a process that isn't in front - once the glance has lost the front, and in the dark (D1, both versions). The first
round's look missed a pause 107 ms before the change (608 ms before the stamp): `POWER_LOSS_BEFORE_MS` 1 s. The code QA
found the same skew in the SOS rule (a press-SOS after a plug-in could read as its wake): the wake is now the one that
ends a long dark spell, `POWER_WAKE_BEFORE_MS` 800 ms (the deferral and the old 300 ms reorder).
Not on the emulator, as above: the Jelly Star's own wake on USB and Unihertz's `ChargingDialog` - the owner's
plug-in-and-out run on the Jelly confirms it.

## 6bm. Enrolment requires a key: design 129b §10.3 with `emulator_keys` (branch `gate-129b`) - run 2026-11-24

Design 129b §10.3's gate (`docs/design/129b-enrolment-requires-a-key.md`), on **Android 14 (`compat34`, google_apis)
and Android 16 (`aosp36`, AOSP)** only, each from `-wipe-data`, 22:51-00:17 CET. Main `31a123725` (E1 + L2 + E2
merged): the debug launcher (`me.vibb.launcher.debug`, versionCode 116) and two debug-profile servers 0.93.0 from the
same tree - one with the default features (`server-plain`: the release profile alone) and one with `--features
emulator_keys` (`server-ek`) - run in turn on **one data directory**, so a waiting phone's code, attempt and rows carry
across a swap (the phone keeps trying the same code with the same attempt; its next try meets the other server). Both
with a dummy `LAUNCHER_SIGNATURE_CHECKSUM` (a code needs it, D3) and the debug component; `server-ek` with or without
`LAUNCHER_DEBUG_SIGNATURE_CHECKSUM` set to the debug signer (`apksigner verify --print-certs`, its SHA-256 as Base64url).
The phones talked to the server through §6bj's logging proxy (each enrol's status and, for a non-200, its body; the
`chain` of every enrol and key body kept; a switch that holds an enrolment's `200` for 15 s). A typed code each time
(the Connect screen on a new phone, Settings' "Enroll with code" for a re-enrolment). **"Block USB debugging" off on
the phone's row before its enrolment**: with it on, the enrolment's first policy takes adb away for good on that image
(the first Android 14 image was lost that way after row 1 and wiped again). The PWA read as the signed-in parent (en).

**The real chains (the profile's first real check):** the first enrolment on each image enrolled with the key at the
first try - neither chain was refused, so the profile needed no change. Android 14: the leaf, the intermediate `O=TEE,
CN=cfba59c948676d0b5f07ff54326c4c1e` (valid 2026-11-22 to 12-08) and the root `CN=Droid Unregistered Device CA,
O=Google Test LLC` (serial `14F0...2D9B`, to 2027-02-01); attestation and KeyMint 300, both `Software`, patch 2023-09,
osVersion 140000. Android 16: the intermediate `CN=fb5590d6158e062a91314f95464f2c70` (to 12-08), another root (serial
`C213...BD80`); 400, both `Software`, patch 2025-07, osVersion 160000. Both: RootOfTrust unverified, unlocked, a zero
boot key; the app `me.vibb.launcher.debug` with this machine's debug signer. Kept as rows of
`server/testdata/attestation/emulator/rows.json` by `scripts/emulator-attestation-vectors.py` (the chains as read in
`scripts/emulator-attestation-captures/`, copied as `captured-*.pem`; each row at the `now` it was enrolled at, with
the leaf's challenge and the signer; one more row each with another debug signer: `app`).
`attestation::emulator_tests::the_rows` now counts five passing chains; without the feature every row is `root`.

| Row | Android 14 (`compat34`) | Android 16 (`aosp36`) |
|---|---|---|
| `emulator_keys` server, fresh enrolment | **Pass.** `200` at the first try, every request signed with the key at once; About "Sign-in: Emulator test key" (`row-value-action`, orange; "Software only, attestation 300, security patch 2023-09. Unknown or unlocked system. Emulator."); Settings' "The server accepts emulator test keys" (`tone-orange`); the start's warning `built with emulator_keys`; the security log "Hardware key registered ... At setup" | **Pass.** The same (attestation 400, 2025-07) |
| `emulator_keys` server without `LAUNCHER_DEBUG_SIGNATURE_CHECKSUM` | **Pass.** `409 {"reason":"app","number":"715146","retry_after_s":15}`; the summary and Phone Setup "Andre14 couldn't prove its key: wrong app signature" with the install-the-official-app (i) and **no button**; the Devices list "1 to check"; the phone "Waiting for a parent." / "Code: 715 146" | **Pass.** The same (a re-enrolment from Settings; "Code: 581 674") |
| Release-feature server, fresh enrolment | **Pass.** The binary swapped under the waiting phone: its next try `409 root`; tries every 15 s for the row's first 2 minutes, then `retry_after_s: 30` (the first at 22:01:34, the first 30 at 22:03:34); the orange line ("unknown issuer") on the summary and Phone Setup, the list's count; the security log "Hardware key refused ... at setup: unknown issuer, waiting for a parent", once per attempt | **Pass.** The same |
| The confirm: a wrong number, a wrong password, then right | **Pass.** `?key_error=number#key-exception` (the confirm open, "That code doesn't match."), `?key_error=password#key-exception` ("Wrong password.", the log's "Confirmation refused ... wrong password"), then `?notice=key_exception#key-exception`; the phone's next try `200` exempt (`Enrolled without a hardware key (root)`); About "Without hardware key" (quiet); the log "Set up without hardware key admin ... unknown issuer; the attempt 23:01" and "Phone connected ... Without hardware key." With a second attempt waiting, see finding 1 | **Pass.** The same; with one attempt the landing showed the quiet "Waiting for the phone" (row 10's run) |
| The confirm with a passkey | **Not run:** no browser with a passkey on the VM (the route is `admin_auth::reauth`'s, `tests::enrol_key`) | **Not run** (the same) |
| A second holder of the code (both images) | **Pass, the second holder a script** (one emulator at a time on this machine): the same code with its own 16-byte attempt and `key_error: keystore`, through the proxy: `409` with its own number (203832); the page "2 attempts", two attempt rows; the phone's number allowed only the phone's row (the second stayed `409`, a try within 10 s of its last `429`); after the phone enrolled the second got `401` | **Pass.** The same (192704; `429`, then `401`) |
| A stale tab | **Pass.** The form's tag read, a new code made, then the confirm with the old tag and the phone's right number: `?notice=key_exception_new_code`, "New code - look again.", nothing allowed | **Pass.** The same |
| The 429 pace | **Pass - the phone never sends one.** "Enroll with code" with the same code twice within 10 s while waiting: neither tap sent a request (the dialog joined the running wait and showed its number); the tries kept their 15 s schedule. The `429` itself came to the second holder (above). A tap whose typed text came out garbled sent that other code: `401`, counted as a wrong code, and the wait for the right code went on | **Pass.** The same |
| A no-key phone | **Server side pass, the phone side not run:** a body with `key_error: keys_unavailable` and no chain: `409 keys_unavailable`, the line "waiting for keys from Google" with the button. Neither image can empty the attestation-key pool (§6bj), and no debug hook forces it | **The same** |
| A lost exempt answer | **Pass.** The proxy held the exempt `200` 15 s, past the phone's timeout; the next try with the same attempt got a fresh token, enrolled; the hold stayed on for two tries here, so two "Phone connected ... without hardware key asked again for a lost answer" lines (finding 2 for the SSE's lines meanwhile) | **Pass.** One held answer: one "asked again" line, enrolled at the next try |
| The exempt phone's weekly try | **Pass.** No key request on two nudged syncs or after a process restart (only `GET /key`; the key book `{"exempt_tried_ms": <the enrolment>}`). A week on (the book's `exempt_tried_ms` moved 8 days back with `run-as`, the debug build): one try - first `GET /key/challenge` `503 address_history` (the phone's address history was minutes old: §8's X wait; `exempt_tried_ms` not stamped), then, the history aged 3 h in the database and the process restarted: challenge, `POST /key` `400 root`, the key deleted (`last_refusal: root`, `exempt_tried_ms` now), **not logged again**; a further restart asked nothing | **Pass.** The same (the history aged first: one try, `400 root`, not logged) |
| A new code while waiting | **Pass.** The old rows gone from the page (no line, no button); the phone's next try `401`, `The waiting enrolment stopped (code_refused)`, "The code has expired. Ask for a new one.", no more tries | **Pass.** The same |
| The 45-minute stop on a typed code | **Not run** (see Android 16) | **Not reachable - the code ends first** (finding 3): a re-enrolment waiting on `server-plain` from 00:02 CET: tries every 15 s, every 30 s from 2 minutes, every 60 s from 10 minutes; at 15 minutes the code expired, the try `401`, `The waiting enrolment stopped (code_refused)` - the same "The code has expired. Ask for a new one." the stop shows |
| Process death while waiting (QR) | **Not run (QR):** no camera QR on the VM. **The typed variant:** `am kill` during the wait: no further try (a typed code lives in the process); the enrolled phone stayed on its bearer | **The same** |
| A launcher without `attempt` (the L1 build) | **Server side pass:** a body without `attempt`: `426 {"reason":"update_app"}`; the summary and Phone Setup "Andre14 has an older Vibb One" with its (i) while the code lives. The L1 launcher itself not built here | **The same** |
| `list_stale` | **Pass.** `status.json` set 8 days old and Google behind a dead `HTTPS_PROXY`: without the debug digest the chain still `409 app`; with it `503 {"reason":"list_stale","retry_after_s":60}`, no new attempt row (the phone kept its 15 s pace: a 503 keeps the schedule, D11); the proxy gone: the fetch at the start, the next try `200` with the key ("Emulator test key") | **Pass.** The same |
| X: a phone enrolled before (E1 over 0.93.0's data) | **Not run** (no 0.93.0 data here). The wait itself was seen in the weekly row: `503 address_history` with `retry_after_s` the rest of the hour, then a registration once the history was an hour old, not marked unusual | **Not run** (the same) |
| An enrolled phone from before this build | **Not run** (needs an L1 launcher and an older server) | **Not run** |

Findings (nothing failed; none fixed here):

1. **An allowed attempt is hidden while another attempt waits** (server, `enrol_key::section`): with the second
   holder's row still waiting, the confirm of the phone's number landed on the same orange line and button (now with
   the newest waiting row's reason, "the key store failed") and one attempt row - nothing says the phone's attempt was
   allowed; "Waiting for the phone" shows only once no row waits. The phone enrolled at its next try, but a parent who
   confirmed sees no change. Suggestion: the quiet "Waiting for the phone" beside the line while an allowed row is
   unused.
2. **A re-enrolment's old token reads as a refused sign-in** (server, the security log): when a waiting re-enrolment
   of an enrolled phone ends exempt, the phone's SSE on the old token gets 401s until it reconnects - "Phone sign-in
   refused ... Invalid phone key (2-3 times in a minute) · /api/devices/commands/stream". Expected from the token
   swap, but it reads badly in the parent's log.
3. **The 45-minute stop can't be reached with a typed code**: a typed code lives 15 minutes (`TYPED_MINUTES`), so a
   waiting typed enrolment always ends at the code's expiry (`401`, the same "The code has expired" text) long before
   45 minutes - unless a confirm moved the expiry on, and then the next try enrols. The stop is the QR code's (30
   minutes, also under 45) only after a confirm's grace - i.e. practically never. Worth a look at D11's number.
   **Closed as by design** (code QA of `enrol-gate-fixes`): the stop is for a server that can't be reached; a
   reachable one ends the wait with the code-expired answer first. Design 129b D11 says so.

Findings 1 and 2 fixed on branch `enrol-gate-fixes` (`tests::enrol_key::the_exception`,
`a_replaced_token_is_refused_quietly`). To run again on Android 14 and 16: with a second attempt waiting, the confirm's
landing shows the orange line and the quiet "Waiting for the phone" in `#key-exception` (the page stays there); after
an exempt re-enrolment from Settings, the old token's stream reconnects get `401` and the security log has no "Phone
sign-in refused" line, while a revoked phone's next request still writes one.

Not on the emulator: the passkey confirm, the QR path, a really empty key pool, the L1 and 0.93.0 builds.

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

## 6bp. The Tailscale key sealed; Koble til after lost settings (branch `launcher-edges`) - run 2026-11-25

On **Android 14 and 16** with the user's OK. The debug launcher of this branch as Device Owner, a server of `main`
with a tailnet key for the phone (a reusable test key from the admin console, never a real one in a doc). Logcat
`TsnetClient`, `Provisioning`, `SealedPrefs`. The prefs file is read with `adb shell run-as me.vibb.launcher.debug cat
shared_prefs/me.vibb.launcher.debug_preferences.xml`.

| # | Step | Expect |
|---|---|---|
| 1 | A setup QR with `tailscale_auth_key` on a fresh emulator; read the prefs file before the join finishes (or with the network off, so the join fails) | `mdm.tailscale_auth_key` holds `ks1:` and a Base64 blob - never the key; Settings' Tailscale row shows `••••••••` alone (no part of the key) |
| 2 | The network on; the next Home resume or sync | the node joins (the PWA's Device › Tailscale row); the key is gone from the file, `tsnet_node_joined` true; Settings: "On the tailnet" |
| 3 | Settings › Tailscale key, type a key while not joined (a fresh emulator, setup by Server URL + code) | the row's summary shows `••••••••` at once; the file holds `ks1:` |
| 4 | Write a plain value by hand (`run-as ... sed` the line to a bare string), force-stop, open Settings, then Home | Settings says not set; at Home's resume (the connect attempt) the line is gone from the file and no join is tried with it |

The lost preferences (item 2 of this branch): an enrolled phone with a policy applied, Android's PIN set by the parent
(Lyd/Lås › Android's PIN), adb on by the policy. The main preferences replaced with a cut-short file: `adb shell
run-as me.vibb.launcher.debug sh -c 'head -c 200 shared_prefs/me.vibb.launcher.debug_preferences.xml > /data/local/tmp/x;
cp /data/local/tmp/x shared_prefs/me.vibb.launcher.debug_preferences.xml'` (or an empty `<map/>`), then `adb reboot`.
The witness is `/data/user_de/0/me.vibb.launcher.debug/files/enrolment_witness` (device-protected storage, apart from
the main preferences).

| # | Step | Expect |
|---|---|---|
| 5 | Before the cut: `run-as ... cat /data/user_de/0/me.vibb.launcher.debug/files/enrolment_witness` | `{"enrolled":true,"policy_applied":true,"server_url":"https://..."}` (right after the enrolment, before the first policy: `"enrolled":true` alone) |
| 6 | The cut and the reboot; unlock with Android's PIN | Android's PIN still asked (never cleared); Home shows Koble til with the server address filled in and "Nødsamtale" under the button; logcat has no "Resetting preferences" |
| 7 | Tap Nødsamtale | the emergency confirm ("Ring 112?") over the screen; Avbryt back to Koble til (no call placed in the test) |
| 8 | Back gesture, then the home gesture | Koble til closes for that resume; Home shows nothing allowed (the fallback: kiosk on, no apps); the next time Home comes to the front Koble til is back |
| 9 | Back on Koble til, then the drawer's Settings (or `adb shell am start -a android.intent.action.APPLICATION_PREFERENCES -p me.vibb.launcher.debug`) | the gate's dialog, and Koble til doesn't come up over it (the first try too); a wrong code refused; the parent's real PIN still opens Settings - the parent PIN lives in its own file (`pin_vault.xml`), which the cut leaves. The gate holds: never open without the code |
| 10 | adb | stays as the fallback plan leaves it (the last plan's or nothing allowed's), never opened by the lost file; `adb shell dumpsys device_policy` shows the restrictions still set |
| 11 | A new code from the PWA (the phone's page › Avansert › Ny oppsettkode) typed on Koble til | the phone enrols, the setup hold does not start (the witness counts as applied), the server's policy applies and Home is back |
| 12 | Before step 11: another server's address in the field (`https://other.example.org`), any code | "Det er ikke telefonens server." / "That isn't this phone's server." under the fields, nothing sent (no request in the server's log); an address that breaks S-M1's rule (`http://other.example.org`) still gets the address rule's line (`server_url_refused`) |
| 12b | Then the right server's address with an old (used) code | "That code didn't work"; within a sync (seconds, the command stream) the phone syncs with its old key, `MdmSyncWorker`'s `Enrolment restored: the adopted key synced with the witnessed server`, Koble til closes if open and doesn't come back at the next Home; Settings still behind the parent code |
| 13 | A fresh emulator: enrol with a code but keep the server from answering the policy (stop it right after the enrolment), cut the preferences as above, reboot | the witness holds `"enrolled":true` alone; Koble til shows (no first-launch reset), Settings opens without a code, Home is not locked down (no policy applied yet) |
| 14 | The calendar report: a calendar named `per.hansen@example.org` (DAVx5 or a local one), and an ICSx5 subscription whose display name is cleared (`content insert`/`update` on `content://com.android.calendar/calendars`, `calendar_displayName` null) | the PWA's Calendars list shows `p…@example.org` and an empty name for the subscription (its host as the source); Dagen on the phone shows the full names |

### The run of 2026-11-25 (branch `gate-release-043`)

The setup as §6bq's run below. Rows 3-4 on a fresh phone made Device Owner by adb, before its enrolment. The witness and
the cut as above; the console (`telnet` to the emulator's console port) drove the screen whenever adb was gone.

| Row | Android 14 | Android 16 |
|---|---|---|
| 1 | **Not run:** no QR on the VM (the admin extras can't be handed to `ADMIN_POLICY_COMPLIANCE` from adb); the sealed store itself is row 3's | Not run |
| 2 | **Not run:** the debug build's tsnet is a stub, nothing joins | Not run |
| 3 | **Pass.** `ks1:` and a Base64 blob in the file; the row `••••••••` | **Pass** |
| 4 | **Pass.** Settings "Not set"; the line gone from the file at Home's resume; no tsnet line in the log | **Pass** |
| 5 | **Pass.** `{"enrolled":true,"policy_applied":true,"server_url":"http://10.0.2.2:3150"}` | **Pass** |
| 6 | **FAIL** (finding 1). Android's PIN asked after the reboot, but no Koble til: Home shows the nothing-allowed fallback (Phone book, Settings) at every resume, the phone sends nothing, adb is closed (`34-bp6-koble.png`) | **FAIL, the same** (`36-bp6-bug-home.png`) |
| 6' | The variant with the key book cut too (`device_key.xml` and `device_key_adopted.xml` removed with the main file) | **Pass.** Koble til with `http://10.0.2.2:3150` filled in and Emergency call under the button (`36-bp6v-after-unlock.png`) |
| 7 | Not reachable (finding 1) | **Pass (6').** "Call 112?" with Cancel and Call; Cancel back to Koble til (`36-bp7-emergency.png`) |
| 8 | Not reachable | **Not run:** the console's back gesture never reached the screen |
| 9 | Not reachable. Home's Settings tile opens the kid's Settings | **Not run** (adb gone; Home's Settings tile is the kid's) |
| 10 | **Pass.** adb closed by the fallback after the cut, never opened | **Pass (6').** The same |
| 11 | Not reachable | **Pass (6').** A new code typed: enrolled with the key (`device_key_bound`), no setup hold, Home back with the policy's apps (`36-bp11-enrolled.png`); adb then needed developer options turned on again |
| 12 | Not reachable | **Pass (6').** `http://10.0.2.3:3150`: the address rule's line under the fields, no request reached the server (`36-bp12-refused.png`) |
| 13 | **Not run:** a third wipe | Not run |
| 14 | **Pass.** The PWA's Calendars list "p…@example.org" and "ICSx5 · ics.example.org" for the subscription with no name; Dagen "per.hansen@example.org" and the subscription's URL | **Pass, the address half.** The subscription half needs root to write a URL as the calendar's name (`content insert` can't bind a `:`) |

Findings:

1. **A phone with a hardware key never shows Koble til after its main preferences are lost** (launcher;
   `setup/ConnectScreen.kt` `connectScreenDue`, `server/DeviceCredential.kt` `DeviceCredentials.stored`). Due needs
   `!tokenStored`, and `stored()` is `DeviceToken.present() || KeyBook.adoptedStored(context)`. The adopted key lives
   in its own preferences (`device_key.xml`, mirrored in device-protected `device_key_adopted.xml`), so it survives a cut
   of the main file, and since design 129b every enrolled phone has one. Result: no Koble til, no server address (it was
   in the main file), so no sync and no parent pause; Settings behind a parent code that is gone; adb closed by the
   nothing-allowed fallback. Only the Phone book's emergency call works; a factory reset is the way back. Repro on 14
   and 16: enrol (key bound), `run-as` cut `shared_prefs/me.vibb.launcher.debug_preferences.xml` to 200 bytes, reboot,
   unlock. With the key book also removed (6') the flow works as designed. Likely fix: let the witness's "enrolled"
   win over a stored key when the main file says never enrolled (or count only the token there), and drop the
   orphaned key book at the new enrolment. Not fixed here. **Fixed on branch `koble-til-key`**: `connectScreenDue`
   takes the token and the adopted key apart; with the witness enrolled and `enrolled` gone, an adopted key no longer
   holds Koble til back (a token still does). The key stays until the new enrolment succeeds, which binds a fresh key
   and drops the old one (`enrolmentDrops`); the adopted key is never reused for an enrolment (`enrolKeyAlias`). Rows
   6-13 to run again with the key book kept (not 6').

### The run of 2026-11-25/26 (branch `gate-rc4-rest`, main `9f6aab016` with `koble-til-key`)

Rows 5-13 again with the key book kept, and a control. The debug launcher and a debug server (`--features
emulator_keys`, `LAUNCHER_DEBUG_SIGNATURE_CHECKSUM`) from the same tree, the phone behind §6br's relay on 3150 (its
policy switch for row 13); Android 16 on `jelly36` (Google Play, `-wipe-data`), Android 14 on `compat34` (Google APIs,
`-wipe-data`, `adb root` for the Keystore's database). Each phone: Device Owner by adb, enrolled on Koble til with a
typed code, the setup flow finished, Android's PIN by the policy (Lås: Android's lock, Barne-PIN 1357), "Block USB
debugging" off; saved as a snapshot (§3, "Faster gates") before the control, and loaded again for the rows. The cut as
above (the main file to 200 bytes, `device_key.xml` and `device_key_adopted.xml` kept). With adb gone, the console drove
the screen (touches, `event text`, `screenrecord screenshot`); 16 ran twice - the first pass on gesture navigation,
where the console's Back never registered, the second with 3-button navigation set before the snapshot. Row 13 on each
image from the not-yet-enrolled snapshot, a new phone in the PWA, the relay dropping `GET /api/devices/policy`.
Screenshots in the session's `scratchpad/g-rc4/` (not committed).

| Row | Android 14 (`compat34`) | Android 16 (`jelly36`) |
|---|---|---|
| Control | **Pass.** A healthy enrolled phone with its key, rebooted: Android's PIN, Home with the policy's apps, lock task `LOCKED`, syncs; no Koble til, no `ConnectActivity` start | **Pass.** The same |
| 5 | **Pass.** The witness `{"enrolled":true,"policy_applied":true,"server_url":"http://10.0.2.2:3150"}`; `device_key.xml` adopts `device_key_1`, `device_key_adopted.xml` the same; the Keystore's database has `device_key_1` for the app's uid | **Pass.** The same (`device_key_1`; the Keystore not read: no root on `jelly36`) |
| 6 | **Pass.** Android's PIN pad after the reboot; Koble til with `http://10.0.2.2:3150` filled in and Emergency call under the button (`34-bp6-koble.png`); `Home hasn't started yet, but the phone is enrolled - keeping its preferences`, no "Resetting preferences" | **Pass**, both passes (`36-bp6-koble-pass2.png`) |
| 7 | **Pass.** "Call 112?" with Cancel and Call; Cancel back to Koble til | **Pass** |
| 8 | **Pass.** The navigation bar's Back: Home with nothing allowed (Phone book, Settings); Phone book, then Back: Koble til again | **Pass** (second pass, the same; on the first pass the console's Back reached the screen once). Also by adb on row 13's phone: `input keyevent BACK` - Home; `HOME` - Koble til again |
| 9 | **Pass.** The drawer's gear: the gate's dialog; a wrong code "That code didn't match. Try again." (finding 3: the first try showed Koble til over the dialog) | **Pass, with a note.** The gate's dialog; a wrong code refused; **the parent's real PIN opens Settings** (Server URL "Not set", Setup "1 step left") - the parent PIN lives in its own file (`pin_vault.xml`), not in the cut one, so the row's "no parent code stored, OK disabled" no longer describes the build: the gate holds, the parent's code still works |
| 10 | **Pass.** adb `offline` from the fallback after the unlock until developer options were turned on again after row 11 (the restrictions not readable meanwhile) | **Pass.** The same |
| 12 | **Pass.** `https://other.example.org`: the address rule's line under the fields ("The phone only uses a server address that starts with https:// ..." - finding 4), no `Koble til: enrolling` line in the log, no request at the relay. The old used code on `http://10.0.2.2:3150`: "That code didn't work. Check it and try again, or make a new one." (`enrolment failed (EnrollFailure)`); the old key still in use (finding 2) | **Pass.** The same, on both passes |
| 11 | **Pass.** A new code typed: `Koble til: enrolled`, the stream and the policy at once, no setup flow, Home with the policy's apps (`34-bp11-enrolled.png`); `device_key.xml` adopts `device_key_3` (the new key id `vji-EX17yCYlwm_ojPX47w` in the server's `device_keys`), `device_key_1` gone from the key book **and from the Keystore** (`keyentry` for the uid: `device_key_3` alone) | **Pass.** The same (`device_key_3`, `YcdGe_El-...`; `device_key_1` gone from both files) |
| 13 | **Pass.** The witness `{"enrolled":true,"server_url":"http://10.0.2.2:3150"}` (the policy dropped by the relay), the cut, the reboot: Koble til with the address; Back: Home with every app, lock task `NONE`; `APPLICATION_PREFERENCES` opens Settings with no code | **Pass.** The same |

adb came back after row 11 only by hand: the fallback had turned developer options off, and Settings' "Re-enter your
PIN" before the seventh tap on Build number needs Enter, which the console can't send - the PWA's lock switched to
Vibb's own lock (Android's PIN cleared), a pause, then Build number and USB debugging by touch.

Findings (none blocks rc.4; no code changed here):

1. **ANRs right after a boot on both images, Vibb One's among them.** Every boot of these images brought a burst of
   "isn't responding" dialogs - System UI, Phone, Play services, Gboard, Pixel Launcher - with the guest's CPU pressure
   at 70-76 % and `kcompactd`/`kswapd` busy (2 GB images). Vibb One's were `executing service ...KidVpnService` (main
   thread in `VpnService.Builder.establish` from `KidVpnService.onCreate`, which also reads the 80 000-line block list
   from disk on the main thread, `DnsFilterEngine.loadFromDisk`), `executing service ...CommandListenerService`, and
   input timeouts with an idle main thread. Environment first; the one thing ours: `KidVpnService.onCreate` does the
   list load and the tunnel's `establish` on the main thread, which a slow phone's boot could push past the service's
   20 s. Repro: either image, any reboot, `adb logcat -d | grep -A3 'ANR in me.vibb'`; stacks in `dumpsys dropbox
   --print data_app_anr`. **Fixed on branch `launcher-edges-2`**: the list load, `establish()` and the rest of the
   service's work run on its own control thread; `onCreate` and `onStartCommand` only answer the foreground start
   (`docs/notes/launcher/dns-and-vpn.md`, "The service's work on its own thread"). To check: reboots on both images
   with no `executing service ...KidVpnService` ANR, the filter up (`Tunnel: filter`) and §6bp's rows unchanged.
2. **After a refused code on Koble til the phone syncs again with its old key** (14 and 16, three times). Typing the
   right server's address with an old (used) code: "That code didn't work", then 3-16 s later the command stream opens
   as the phone's old device (`command stream opened device=4`), the policy, apps and status follow, Android's lock and
   the policy's Home come back - while Koble til keeps coming back at every Home (`enrolled` is still gone). The typed
   address is kept, and the adopted key (kept by design until a new enrolment) signs the requests, which the server
   still takes. Not harmful - the phone is back under its policy - but the screen asks for a code the phone no longer
   needs, and a parent may not see why. Repro: §6bp row 12's second half, then watch the relay or `adb logcat -s
   CommandListenerService`. For the owner: keep (the code still re-binds a fresh key), or let a working stream with
   the adopted key restore `enrolled` and close Koble til. **Fixed on branch `launcher-edges-2`**: a policy accepted
   from the witnessed host for the adopted key restores `enrolled` (row 12b).
3. **Koble til comes up over the drawer's Settings dialog** (14 and 16, the first try each). After Back, swipe up for
   the drawer and tap its gear: the gate's dialog opens, then Koble til is started over it (the drawer's close is a new
   resume of Home); Back shows the dialog underneath. The documented way to the Tailscale key ("Back, the drawer's
   Settings") needs Back twice. Repro: rows 8-9 on a cut phone. **Fixed on branch `launcher-edges-2`**: Home's resume
   starts no Koble til while parent Settings is in front (`SettingsFront`; row 9).
4. **The address rule's text speaks of https** when the address is https but another host
   (`https://other.example.org`): "The phone only uses a server address that starts with https:// (plain http only to a
   Tailscale IP ...)". `server_url_refused` covers both rules; for the host rule the line doesn't say what is wrong.
   Words only. **Fixed on branch `launcher-edges-2`**: "Det er ikke telefonens server." / "That isn't this phone's
   server." for the host rule (row 12).

## 6bq. User certificates, the provisioning baseline, storage full (branch `storage-certs-baseline`) - run 2026-11-25

On **Android 14 and 16** with the user's OK. The debug launcher and a server of this branch. Logcat `AppEnforcer`,
`ProvisioningBaseline`, `Provisioning`, `TimeRulesRuntime`. Restrictions are read with `adb shell dumpsys
device_policy | grep -A30 'User restrictions'` (while adb is still there) or from Settings' greyed rows.

User certificates (Advanced › Restrictions › "Blokker sertifikater", on by default):

| # | Step | Expect |
|---|---|---|
| 1 | An enrolled, managed phone with the defaults; Settings › Security › More security settings › Encryption & credentials (the pause on, so Settings shows) | "Install a certificate" and "Clear credentials" greyed or refused ("blocked by your admin"); "Trusted credentials" shows the system CAs with no switch to turn one off; `dumpsys` lists `no_config_credentials` |
| 2 | Wi-Fi › Add network, EAP (PEAP), CA certificate | "Use system certificates" with a domain can be saved; no way to install a certificate from the dialog |
| 3 | The PWA: "Blokker sertifikater" off (no orange line anywhere, the page stays where it was), a sync | `no_config_credentials` gone; a CA certificate installs from Downloads (Settings › Install a certificate › CA certificate); the PWA shows no warning for the choice |
| 4 | The switch on again, a sync | the installed certificate stays (nothing removes it), but installing another is refused again; Vibb One's server connection is unaffected (it trusts no user CA) |
| 5 | The pause from the phone's parent code, or the setup hold of a fresh enrolment | the certificate rows stay greyed (hardening is never lifted by a pause or the hold) |
| 6 | Settings › Passwords, passkeys & accounts (14: Passwords & accounts) | not affected by the restriction (the credential provider choice is another setting); note what the build shows - if Android greys out the provider picker, that goes to the owner as a visible cost |

The provisioning baseline (a QR-provisioned phone locked down before its first policy). A fresh emulator (wiped),
QR provisioning from the welcome screen (six taps, the setup QR from the PWA shown to the emulator's camera through
the virtual scene's poster image, or any route that runs `ADMIN_POLICY_COMPLIANCE`), with the server stopped so the
enrolment fails at first:

| # | Step | Expect |
|---|---|---|
| 7 | Before the setup wizard ends, `adb logcat -s ProvisioningBaseline` (adb from the emulator console's `adb` stays only until the restriction lands) | `Admin policy compliance: baseline set` (on a phone without a role holder `Provisioning complete: baseline set`) |
| 8 | After setup: Settings › System › Developer options; Settings › System › Users; a sideloaded APK from Files; Encryption & credentials | developer options and USB debugging off and greyed (`adb devices` shows the emulator offline or gone); no "Add user"/guest; "Install unknown apps" refused; certificate install greyed |
| 9 | Settings › System › Reset options › Erase all data | allowed (not blocked before the first policy) - cancel it |
| 10 | Long-press power › long-press Power off | no "Reboot to safe mode" offer |
| 11 | Android's PIN: set one in Settings, lock and unlock | the PIN unlocks as always (the baseline touches no lock) |
| 12 | The server on; Koble til with a new code from the PWA (the phone's page › Avansert › Ny oppsettkode) | the phone enrols and takes its first policy; from then on the PWA's switches decide: "Blokker USB-feilsøking" off in the PWA brings developer options and adb back after a sync, and factory reset is blocked by the default |
| 13 | The server unmanages the phone (calls and apps unmanaged) | every restriction cleared, the baseline doesn't come back |
| 14 | A wiped emulator, adb's `dpm set-device-owner me.vibb.launcher.debug/com.kidslauncher.mdm.server.MdmDeviceAdminReceiver` (the dev path) | no `ProvisioningBaseline` line; adb stays until the first managed policy, as before; `run-as me.vibb.launcher.debug ls /data/user_de/0/me.vibb.launcher.debug/shared_prefs` has no `provisioning_baseline.xml` |

Storage full (the phone stays usable; the parent is told). An enrolled phone with a daily screen-time limit (say 30
minutes). Fill `/data` until a write fails: `adb shell 'dd if=/dev/zero of=/sdcard/Download/fill bs=1M count=100000'`
(stop at "No space left on device"; free a little and fill again until `df /data` shows 0 available):

| # | Step | Expect |
|---|---|---|
| 15 | Use the phone for 5 minutes (a policy sync in the full state fails its cache write too, the same flag) | logcat `Couldn't save the screen-time ledger: storage full?` once; the count goes on (Home's remaining time falls, the limit locks when it is used up) |
| 16 | A sync | the report's `time_state.storage_full` is `true`; the PWA's summary has the orange Attention row "Lagringen er full" to Device › Tid, whose orange line has the fix behind its (i); one push "Emma: lagringen er full" / "Skjermtiden lagres ikke." |
| 17 | More syncs the same day | no second push (one a day); the line stays |
| 18 | `adb reboot` with the disk still full | the day's count goes back to the last saved ledger: the time used since storage filled is lost (the accepted cost); the line comes back with the first failed save after the restart |
| 19 | Delete the fill file, use the phone a minute, a sync | the save works, `storage_full` is left out of the report, the line and the Attention row are gone; Notify Me About has the "Lagringen er full" switch, on |

### The run of 2026-11-25 (16:30-18:50 UTC, branch `gate-release-043`)

Main `dcb1487b9`: the debug launcher, the debug music app and a debug server (`--features emulator_keys`, with
`LAUNCHER_DEBUG_SIGNATURE_CHECKSUM`) from the same tree; Android 14 on `compat34` (Google APIs, `-wipe-data`, twice),
Android 16 on `jelly36` (Google Play, `-wipe-data`) and, for what needs root, `aosp36` (AOSP, `-wipe-data`). The PWA read
as the signed-in parent (nb). Screenshots: `docs/testing/screens/2026-11-25-gate-043/` (not committed).

**The provisioning baseline without a QR** (no camera QR on this VM): `dpm set-device-owner`, then, as root
(`adb root`, Google APIs image), `am start -a android.app.action.ADMIN_POLICY_COMPLIANCE -n
me.vibb.launcher.debug/com.kidslauncher.mdm.server.PolicyComplianceActivity` - the activity the QR's setup starts, with
no admin extras. adb goes at once, so rows 8-12 were read and driven through the emulator console alone (`telnet` to
the console port: `screenrecord screenshot`, `event text`, touch `event send EV_ABS:...`; `EV_KEY` key events did not
reach the screen). Android 16 has no root on `jelly36`, and on `aosp36` the phone was already enrolled, so the baseline
rows ran on Android 14 only.

| Row | Android 14 | Android 16 |
|---|---|---|
| 1 | **Pass.** Install a certificate, Clear credentials, User credentials, Certificate management app greyed; Trusted credentials' switches greyed (on); `no_config_credentials` | **Pass.** The same, each row "Controlled by admin" |
| 2 | **Pass, with a note.** "Use system certificates" with a domain saved (`cmd wifi list-networks`: `wpa2-enterprise`). The CA list still offers "Install certificates": picking it starts CertInstaller, which closes at once (refused) and the dialog stays | **Pass, the same note** |
| 3 | **Pass.** The switch off: the restriction gone, the landing `#restrictions`, no orange line; a CA from Downloads installed (`cacerts-added/ad5d5efd.0`) | **Pass.** The same (Android 16 asks for the PIN before the file picker); "Gate Test CA Throwaway" under Trusted credentials › User |
| 4 | **Pass.** On again: the certificate stays, Install anyway returns without the picker, the syncs go on | **Pass.** The certificate stays, Install a certificate greyed again |
| 5 | **Pass.** With the pause on, `no_config_credentials` stays | **Pass** (the rows greyed during the pause) |
| 6 | **Pass.** Passwords & accounts › "Passwords, passkeys and data services": the None/Google picker is enabled; only "Add account" greyed (`no_modify_accounts`) | **Pass, nothing for the owner.** Passwords, passkeys & accounts › Change: the picker enabled (`36-q6-passwords-accounts.png`, `36-q6-provider-picker.png`) |
| 7 | **Pass.** `Admin policy compliance: baseline set` (read from the log once adb was back); `armed=true` in `provisioning_baseline.xml` (device-protected) | Not run (see above) |
| 8 | **Pass.** `adb devices`: offline at once; Multiple users greyed; Developer options and USB debugging not found in Settings; a CA certificate: Install anyway returns without the picker. Unknown sources: `no_install_unknown_sources` was already set before the baseline; not tried from Files | Not run |
| 9 | **Pass.** Erase all data opens its confirm with ERASE ALL DATA (not tapped) | Not run |
| 10 | **Pass.** The quick settings' power menu: Emergency, Power off, Restart; a long touch on Power off brought no safe-mode offer | Not run |
| 11 | **Not run:** a PIN set before the enrolment would spend the image's escrow token (§3) | Not run |
| 12 | **Pass.** Koble til with a new code (typed through the console), the setup flow ended with Finish; with USB debugging allowed in the PWA, developer options came back (seven taps on Build number) and the USB debugging switch brought adb back; factory reset blocked by the default | Not run |
| 13 | **Not reachable from the PWA:** calls and SMS can be set unmanaged, but apps can't - an app list set to `NULL` in the database is filled again at the phone's next report (`device_api.rs`, the first heartbeat's list), so the phone stays managed | **The same** |
| 14 | **Pass.** No `ProvisioningBaseline` line, no `provisioning_baseline.xml`; adb stays | **Pass** |
| 15 | **Pass.** `Couldn't save the screen-time ledger: storage full?` once in 2.5 minutes; the count went on | **Pass** |
| 16 | **Pass.** `time_state.storage_full: true`; the summary's Attention "Lagringen er full"; Device › Tid's line with the fix behind its (i); one `parent_events` row `storage_full` (no browser subscribed, so no push was delivered) | **Pass** |
| 17 | **Pass.** Further syncs: still one `storage_full` event that day | **Pass** |
| 18 | **Pass.** After `adb reboot` with the disk full: the count from the last saved ledger (3 minutes), the flag back with the first failed save | **Pass, the line not seen again:** the count went back 13 -> 12; two failed saves right after the boot, but the boot freed ~64 MB and the next saves went through, so no `storage_full` in the reports after the restart (the disk wasn't full any more) |
| 19 | **Pass.** The file deleted: `storage_full` left out, the line and the Attention row gone; Notify Me About has `push_storage_full`, on | **Pass** (the shell can't delete the fill file after a reboot on 16 - "Operation not permitted" - `truncate -s 0` frees it) |

## 6br. The collected gate before launcher rc.4 (branch `gate-release-043`) - run 2026-11-25

The rows owed before rc.4 that have no section of their own: the fail-closed saved policy (`failclosed-restore`,
squeue 15), the music app's foreign controllers (`lookup-share-music`, squeue 18), §6bm's reruns
(`enrol-gate-fixes`) and §6bn's Wi-Fi fixes. The setup as §6bq's run. The phone talked to the server through a small
test relay (`127.0.0.1:3152` behind `0.0.0.0:3150`) that can answer the phone's `GET /api/devices/policy` by closing the
connection while every other request passes - the policy fetch fails as unreachable while the report goes through. The
saved policy was changed with `run-as` (the debug build) with the launcher's process killed first, then Home started.

The fail-closed rows (`docs/notes/launcher/lock-and-glance.md`, "Rollback"; `PolicyGate.judgeCached`):

| # | Step | Expect |
|---|---|---|
| a | Android's PIN set by the parent; the saved policy `{}`; the policy unreachable; restart (and on 14 a reboot with the server stopped) | `No usable cached policy - enforcing the last-enforced plan`; Android's PIN stays; the kiosk and the app list as before; adb as before; the report `cache_corrupt` and the PWA's "Telefonen får ikke brukt innstillingene" |
| a1 | The saved policy without `call_policy` (with its app list), `calls_managed_last` set | the same: unreadable, the last-enforced plan, `cache_corrupt` |
| a2 | The saved policy readable but with `kid_lock: null`, the policy unreachable; then the server's own choice (Lås: Android's lock -> own lock) | the saved copy never clears Android's PIN; the server's policy clears it at that sync (`rollback: Android's PIN cleared`) |
| a3 | A phone whose apps are unmanaged keeps a saved copy without an app list (no `cache_corrupt`) | (the commit's third case) |

| Row | Android 14 | Android 16 |
|---|---|---|
| a | **Pass.** Reboot with the server stopped: the fallback applied, `CredentialType: PIN`, `mLockTaskModeState=LOCKED`, Chrome hidden and suspended, adb kept; behind the relay: reports `cache_corrupt`, the summary "Telefonen får ikke brukt innstillingene" | **Pass** (restart behind the relay; the same) |
| a1 | **Pass.** `cache_corrupt`, the PIN kept | **Pass** |
| a2 | **Pass.** From the saved copy the PIN stayed (no rollback line); the server's switch to own lock: `rollback: Android's PIN cleared: true`; back to Android's lock: the PIN set again | **Pass** |
| a3 | **Not reachable:** an unmanaged app list can't be made on this server (§6bq row 13); `PolicyGateTest` covers the rule | **The same** |

The restore check (squeue 16, server, the same on both): `kid_phone_server check-backup` on a copy of the gate's live
database: `ok`; the same copy with a planted trigger: `refused: schema`.

Music: another app's controller (squeue 18; `music/CLAUDE.md`). The probe app's Media3 `MediaController` on
`me.vibb.music.debug`'s `PlaybackService` while an RSS episode from `scripts/fake-audio.py --feed 5` played:

| Row | Android 14 | Android 16 |
|---|---|---|
| An outside app's controller | **Pass.** `GET_TIMELINE`, `GET_METADATA`, `GET_CURRENT_MEDIA_ITEM`, `SET_MEDIA_ITEM`, `CHANGE_MEDIA_ITEMS` false; title null, no current item, 0 items; pause and play took effect; `setMediaItem` changed nothing | **Pass** |
| System UI's media card | **Pass.** "Bilde 5 / Fake feed with pictures" with the picture (the shade with the pause on; the managed shade shows Vibb's panel instead) | **Pass** (`36-music-systemui-card.png`) |
| The launcher's Home card | **Pass.** "Now playing: Fake feed with pictures. Bilde 5" - once notification access was on (the setup step had been skipped: no card before) | **Pass** (`36-music-homecard.png`) |

§6bm's reruns (the plain-feature server and the `emulator_keys` one swapped on one data directory, as §6bm):

| Row | Android 14 | Android 16 |
|---|---|---|
| A second attempt waiting, the phone's number confirmed | **Pass.** A re-enrolment from Settings waiting (`root`), a script's attempt (`keystore`): the landing `?notice=key_exception#key-exception` shows the orange line ("kunne ikke bevise nøkkelen sin: nøkkellageret feilet", "2 forsøk") and the quiet "Venter på telefonen"; the phone enrolled exempt at its next try | **Pass** |
| The old token after an exempt re-enrolment; a revoked phone | **Pass.** No "Phone sign-in refused" (`device_auth_failed`) after the re-enrolment (the stream reopened on the new token); after Revoke the phone's next request wrote one (`invalid_token`) | **Pass** |

§6bn's Wi-Fi fixes (fake scans need root: `compat34` and `aosp36`; Vibb's Wi-Fi page):

| Row | Android 14 | Android 16 (`aosp36`) |
|---|---|---|
| An enterprise and a WEP network | **Pass.** "Not supported" under both; a tap saves nothing | **Pass** |
| A WPA3-only (SAE) network | **Pass.** Saved as `wpa3-sae` alone; the join `connectToNetwork "GateSae"SAE` (the network isn't there, so not found); a WPA2 one saves as `wpa2-psk` with `wpa3-sae^` | **Pass** (the SAE part) |

Not run here: the QR (row 1 of §6bp, §6bq's baseline through a real QR), Android 17 (no working image).

## 6bs. After a small move: inconclusive scans, the shadow fingerprint, a mismatch ends still (design 134, branch `still-print`) - run 2026-11-25/26

`docs/design/134-still-place-fingerprint.md` §8, revision 3's row (j) and revision 4's row (k): on **Android 14 and 16** with the owner's
OK (the 17 images don't run; 17 is read from its behaviour-change list; the Jelly Star is on 16), a debug and a release
build. The setup is §6bn's: the debug launcher, Regularly, Google's location accuracy off (the `network` provider off -
on the Google APIs image `adb shell cmd location providers add-test-provider network` and `set-test-provider-enabled
network false`, again after every boot), Location on, `adb shell appops set me.vibb.launcher.debug
android:mock_location allow`, the satellite feed (`--es gps "<lat>,<lng>,<accuracy>,<satellites>[,<seconds>]"`) and the
made-up scan and connection of `PlacesDebugReceiver`. **Settled rows first**: home's three networks of their own
devices (`00:00:5e:00:53:01`/`:02` Hjemme 5 and 2.4 GHz, `00:00:5e:00:54:03` Nabo, `00:00:5e:00:55:04` Naboen) taught on
three occasions over two local days by §6bn's time-zone step - a device is settled once one of its APs has a row on
two local days, never moved. Design 134's hooks (debug only): `--ei scan_age_s N` (the made-up results heard N s ago),
`--ez refuse_scan true|false` (every `startScan` throttled), `--es motion sensor|fire|off` (a fake significant-motion
trigger: `sensor` stands it in, armed at the next sync, `fire` fires it, `off` the real sensor again - the emulator
has none). Logs: `adb logcat -s WifiPlaces ZoneWatch StillPrint PlacesDebug`. The record: `adb shell dumpsys activity
service me.vibb.launcher.debug/com.kidslauncher.mdm.server.CommandListenerService still-print` (the release build:
`me.vibb.launcher/...`). Locate stands in for a heartbeat only where a row says so: the rows below need the heartbeat's
own `FIX` (wait for the beat, or `am broadcast` a sync and let the beat be due).

| # | Step | Expect |
|---|---|---|
| a | `motion sensor`, a sync; a satellite feed fix (8 satellites, 10 m) at home with home's scan, fresh | `Motion trigger armed`; `StillPrint: taken devices=3` |
| b | `motion fire`, GPS silent, home's scan with `scan_age_s 600`, the next beat | `scan answer 0/4 ask=answered old` (the attempt waits its 8 s past the emulator's own callback, code QA M2), `second scan old` (`refuse_scan` off), `attempt path=TOGETHER result=withheld reason=old`, `Location heartbeat: FIX result=inconclusive reason=old n=1/3`; nothing reported (Locate unchanged); `StillPrint: inconclusive reason=old devices=3`; the next beat tries again (`n=2/3`, `n=3/3`); the 4th logs `result=none` and the beats after it `QUIET` |
| c | As (b) with `refuse_scan true` | `scan refusals throttled=..`, `reason=refused`, no `second scan` line |
| d | `refuse_scan false`, `scan_age_s 0`, `motion fire`, GPS silent, home's scan fresh | `StillPrint: verdict would=yes ...`; nothing renewed, no `still` (Locate as today); then the feed at home (10 m): `StillPrint: outcome confirmed d=.. after=..`, and a new print (`taken`) after `print dropped (replaced)` |
| e | `motion fire`, a neighbour's scan (their own device strongest, home's 18 dB weaker) | `StillPrint: verdict would=no reason=anchor` |
| f | `motion fire`, one made-up AP, fresh | no `second scan`, no `withheld`; `would=no reason=` as the print says (a print of one device: `thin`) |
| g | `adb reboot`, the first heartbeat try after it | `print dropped (boot)`; no verdict event at that try |
| h | The release build (installed over adb as §6bn's note says, never on a kid's phone): the dump with and without `still-print`; `./gradlew assembleRelease` | the summary (`prints taken=.. dropped ..`, `verdicts=..`, `inconclusive=..`, `mismatches=..`) and the ring newest first, no key, BSSID, SSID or position; without the argument one line; `checkReleaseHasNoDebugHook` passes; `PlacesDebugReceiver`'s extras do nothing (no receiver) |
| i | Location Off from the PWA | `WifiPlaces: cleared (off)` and `StillPrint: print dropped (off)`; `run-as me.vibb.launcher.debug ls no_backup` has neither `still_print_v1` nor `still_print_ring_v1`; the dump's ring empty |
| j | (revision 3) `motion sensor`, a fresh print at home (a), no fire; the clock 4 h on (an image with `adb root`: `adb shell date MMDDhhmm`; else this row is the Jelly Star's), a neighbour's scan without home's devices (3 APs of their own prefixes), GPS silent, the cap try | `Location heartbeat: STILL ended by a scan mismatch clause=wifi`, `StillPrint: mismatch clause=wifi devices=0/3`, no `still` and no fix in the report; the next beat a `FIX` (GPS), `QUIET` between the later failed tries; the dump's `mismatches=1 wifi=1`. Again with home's scan but one device missing: no mismatch, `cap try failed fails=1 vouch=re-sent`, `STILL fails=1 retry=..`. With zones, the neighbour's scan while connected to the linked home network (§6bn's link): `StillPrint: mismatch clause=wifi devices=0/3 vetoed`, `STILL renewed by a zone link`, `STILL` goes on. A scan of one neighbour device only (the owner's refinement of code QA H1): no mismatch while the emulator modem's registered cell has no row or a row near home; once the modem's registered cell has an Apple cell row 5 km off (§6bk's fake answer, stored by a Locate with the switch on before the clock step), `StillPrint: mismatch clause=cell devices=0/3` |
| k | (revision 4) `motion sensor`; the made-up connection to home's settled router (`--es connect "00:00:5e:00:53:01,Hjemme,psk,7,5180,-42"`) and home's scan; a fresh fix at home (a) | `vouch link read level=scan` (the router in home's fresh scan; without it `level=link`); then the clock 4 h on (as j), a poor scan of one neighbour AP heard 600 s ago (`scan_age_s 600`), still connected at `-45`, GPS silent, the cap try: `scan answer 0/1 ask=answered old`, no GPS start, `connected witness renews level=link screen=off dbm=-45 vouch=-42 diff=-3` (screen as it is), `attempt path=LEARNED_FIRST result=renewed`, `Location heartbeat: STILL renewed by the connected AP`, `still` in the report. Again with the connection at `,-62`: `connected witness no reason=level ... diff=-20`, the cap try runs GPS and fails as part A (`fails=1`); at `,-127`: `reason=nolevel`. The router's other BSSID (`00:00:5e:00:53:02`): `reason=other`. The clock 24 h past the vouch with renewals between (code QA M1): `reason=ceiling`, a try, then `QUIET` and no `still`. With `motion fire` first: no witness asked (`TOGETHER`), no renewal. With networkId `-1`: `reason=binding`; with the BSSID `02:00:00:00:00:00`: `reason=unreadable` (on the Jelly Star a redacted read logs `connection read by callback yes|no` first). A fresh neighbour's scan of 3 devices of their own while connected at `-45`: the answer `noconnected` (inconclusive), no `STILL ended` |

What can't be faked here: real room-to-room levels, real neighbours and flats, the HAL's behaviour in deep Doze, the
real throttle under the anchor's importance with other apps scanning, a real sensor's delay - the Jelly Star's §9
(`docs/testing/new-phone-model.md` §2).

### The run of 2026-11-25/26 (branch `gate-rc4-rest`, main `9f6aab016`)

Android 14 on `compat34` (Google APIs, the `network` test provider off as above) and Android 16 on **`aosp36`** (AOSP,
no `network` provider) - not `jelly36`: the row needs `adb root` for the clock (`date`), and so do the beats below. The
debug launcher and server of §6bp's run; a phone each (Kid14 from §6bp's snapshot, Kid16c fresh), Regularly every 10
min, Location on, no Android PIN in the way of adb on 16. **Settled rows by the clock**: the made-up home scan (the
setup above) and the home connection (`psk`, 7), a Locate with the satellite feed (8 satellites, 10 m), the clock 31
min on (`date`, root), the time zone moved to a zone where it is already tomorrow (`Pacific/Kiritimati` - `America/Adak`
is still today when the first teach was in the UTC evening), a second Locate: `scan usable=4 trusted=4 networks=3`.
**The beats by the clock too**: each "next beat" below is the clock 10 min on (root `date`), then a PWA save as the
nudge (`Sync for [clock]`/`[sse]`) - the heartbeat's own `FIX`, not a Locate, unless a row says so. A print was taken
afresh (home's scan, the feed, a Locate: `StillPrint: taken devices=3`) before each row that needs one. Logs and the
dumps in the session's `scratchpad/g-rc4/` (`34-bs-*`, `36-bs-*`).

| Row | Android 14 (`compat34`) | Android 16 (`aosp36`) |
|---|---|---|
| a | **Pass.** `motion sensor`: the next sync `Motion trigger armed (start)` and `Location heartbeat: STILL` (the teaching Locate's fix is the vouch); the feed and a Locate: `StillPrint: taken devices=3` | **Pass.** The same |
| b | **Pass, with notes.** `scan answer 0/4 ask=answered old`, `second scan old`, `attempt path=TOGETHER result=withheld reason=old`, `FIX result=inconclusive reason=old n=1/3`, `StillPrint: inconclusive reason=old devices=3`; no new location row; `n=2/3`; `n=3/3` with `reason=refused` - Android's own `startScan` throttle (4 per 2 min): the clock-stepped beats came ~40 s apart in real time; the 4th `result=none` (`print dropped (notfresh)`); then `FIX` again at the next two beats (path `LEARNED_FIRST`, a cell fix reported as `vibb_cell` once) and `QUIET backoff=25 min` at the third: the back-off is `max(interval, last try - still since)`, so `QUIET` shows only once that passes the interval | **Pass, the same notes** (`n=3/3 reason=refused`; `QUIET backoff=10 min` at the second beat after the 4th) |
| c | **Pass.** With `scan_age_s 600` kept (with 0 the cached results answer and no `startScan` is asked): `scan refusals throttled=3`, `scan answer 0/4 ask=refused refused`, `reason=refused`, no `second scan` | **Pass.** The same |
| d | **Pass, judged at once.** `StillPrint: verdict would=yes j=1.00 diff=0.0 devices=3/3 near=2/2 agree=2`; no renewal, no `still` - the beat reported its own Wi-Fi fix (`vibb_wifi`, `check=gnss`, 50 m), as before 134, and that checked fix judged the verdict in the same attempt: `outcome confirmed d=0 after=0 acc<=100`, `print dropped (replaced)`, `not taken (accuracy)` (50.000153 m); the feed and a Locate: `taken devices=3` | **Pass.** The same |
| e | **Pass.** Their own device at -45, home's 18 dB weaker, connected to theirs (`psk`): `would=no reason=anchor j=0.52 diff=18.0 devices=3/3 near=2/1 agree=0`. The made-up connection must be in the scan: with none, the emulator's real `AndroidWifi` BSSID made it `inconclusive reason=noconnected` | **Pass.** The same |
| f | **Pass.** One AP: no `second scan`, no `withheld`; `would=no reason=agree j=0.49 devices=3/1 agree=1` (the print has 3 devices); `cell fix acc=500 WITHHOLD` | **Pass.** The same |
| g | **Pass.** `adb reboot`: the first beat after it `StillPrint: print dropped (boot)`, no verdict at that try (the emulator's own GNSS answered that beat at 0 satellites and became the next print) | **Pass.** The first try came at the boot itself (before the made-up scan was set again): the dump `dropped boot=1`, no new verdict in the ring, `no print` (that beat's fix was the emulator's GNSS at its default place, 0 satellites) |
| h | **Pass.** `./gradlew assembleRelease` with a throwaway stub `libs/tsnet.aar` (removed afterwards): `checkReleaseHasNoDebugHook: no debug hook in [app-release-unsigned.apk]`. The APK signed with this machine's debug key for the emulator only, installed on a wiped, unmanaged image and Home started: `dumpsys activity service me.vibb.launcher/...CommandListenerService still-print` gives the summary (`no print`, `prints taken=0 dropped ...`, `verdicts=0 ...`, `inconclusive=0 ...`, `mismatches=0 ...`, `ring (newest first, 0 of 500)`), no key, BSSID, SSID or position; without the argument one line; `am broadcast -n me.vibb.launcher/...PlacesDebugReceiver`: `No receivers found` | **Pass.** The same (the build once; the release install on a wiped `aosp36`) |
| i | **Pass, one line differs.** Location Off: `StillPrint: print dropped (rule)` - the still rule goes off before the clear - then `WifiPlaces: cleared (off)`; `no_backup` holds neither `still_print_v1` nor `still_print_ring_v1`; the dump `no print`, every count 0, `ring (newest first, 0 of 500)` | **Pass.** No print in force, so no drop line; `WifiPlaces: cleared (off)`; neither file in `no_backup`; the ring `0 of 500` |
| j, mismatch | **Pass.** A print, the clock 245 min on, three foreign devices (one connected): `StillPrint: mismatch clause=wifi devices=0/3`, `Location heartbeat: STILL ended by a scan mismatch clause=wifi`, `cap try failed fails=1 vouch=dropped`, no new location row; the next beat `FIX` (`LEARNED_FIRST`, nothing: GPS silent), then `QUIET backoff=10 min`, then `FIX`; the dump `mismatches=1 wifi=1 ... pending=1` | **Pass.** The same lines; no new location row; the next two beats `FIX` (nothing), then `QUIET backoff=21 min`; the dump `mismatches=1 wifi=1 ... pending=1` |
| j, one missing | **Pass for "no mismatch"; the failed cap try needs a thinner scan.** Home's scan without Naboen (2 of 3 networks): no mismatch, but the cap try **succeeded** - a checked Wi-Fi fix (`vibb_wifi`, `check=gnss`, 75 m), `FIX result=fresh`. With Hjemme's two APs alone (one network): no mismatch, `cap try failed fails=1 vouch=re-sent`; `fails=2` and `fails=3` at the next beats, then `STILL fails=3 retry=60 min`. A `STILL fails=1 retry=..` line can't come on a 10-min interval: `capRetryMs` is the interval for the first two fails, so those beats try again | **The same** (run with Hjemme's two APs: no mismatch, `cap try failed fails=1 vouch=re-sent`, `fails=2`, `fails=3`, then `STILL fails=3 retry=60 min`) |
| j, zone link | **Pass.** Zone Hjem (150 m), the link taught by three Locates 31 min, 31 min and a day apart (`wifi fix check=link`); a print; the foreign scan plus a Hjemme node that is no print device (`00:00:5e:00:57:09`, connected, `psk` 7): `StillPrint: mismatch clause=wifi devices=0/3 vetoed`, `STILL renewed by a zone link`, `FIX result=renewed` | **Pass.** The same (`wifi fix check=link`, `zone=1/1` after the third Locate; `mismatch clause=wifi devices=0/3 vetoed`, `STILL renewed by a zone link`) |
| j, cell | **Half.** One foreign device, the modem's cell with its own row at home: no mismatch (`cap try failed fails=1 vouch=re-sent`). **Not run:** the Apple cell row 5 km off - there is no fake Apple answer on the emulator (§6bk: Apple's answer is the Jelly Star's), so `mismatch clause=cell` is the Jelly Star's | **Half, the same** (no mismatch, `cap try failed fails=1 vouch=re-sent`; the cell clause not run) |

The dump without the argument (both): one line, "Vibb: the place fingerprint's record needs the argument still-print".
Findings: none in the code; three rows' expectations are off (b's `QUIET` timing, i's `print dropped (rule)` before
`cleared (off)`, j's "one device missing" with a three-network home gives a fix) - the table above says what the build
does. Not run: row j's cell clause (above).

Row k (revision 4) was run on 2026-11-26 before launcher rc.5: §6bv.

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

## 6bv. The gate before launcher rc.5 (branch `gate-rc5`) - run 2026-11-26

On **Android 14 and 16** with the owner's OK (the 17 images don't run). `main` at `1682889d6`: the debug launcher
(`0.23.6-debug`, versionCode 116), the debug music app and a debug server (`--features emulator_keys`,
`LAUNCHER_DEBUG_SIGNATURE_CHECKSUM`) from the same tree, **one server per image** (14: the relay `0.0.0.0:3150` ->
`127.0.0.1:3152`; 16: `3160` -> `3162`, §6br's relay with its policy switch), so each image's snapshots go with their
own server data. Android 14 on `compat34` (Google APIs, `-wipe-data`, `adb root` where a row needs it). Android 16 on
`jelly36` (Google Play) for R1, R3, the first reboots and the first cut; its System UI then hung for good (an "isn't
responding" dialog that took no input, `Failed to find ColorBuffer` in the emulator's log, the screen off with adb gone
and no way to wake it from the console - no key event, AC toggle or simulated call did), so every other row ran on
**`aosp36`** (AOSP, `-wipe-data`, root). Each phone: §4's adb lines, the debug music app installed by adb, "Blokker
USB-feilsøking" off in the PWA **before** the enrolment (so adb outlives the first policy), Koble til with a typed
code, the setup flow finished, Lås: Android's lock, Barne-PIN 1357 and Foreldre-PIN 246813 from the PWA, 3-button
navigation, the screen timeout 10 min (Lås, so the console-driven rows don't lose the screen), then `gate-do` (Device
Owner, not enrolled) and `gate-base` (enrolled with its key) saved as snapshots with a copy of the server data (§3,
"Faster gates"). **A snapshot survives an emulator restart**: `aosp36` crashed once during an `adb reboot` (a crashpad
dump in its log, not out of memory) and came back with `-snapshot gate-do -no-snapshot-save` in 19 s. With adb gone
after the cut, the console drove the screen (touches, `event text` in pieces, a long-press's Cut to clear a field,
`screenrecord screenshot`). Logs and screenshots in the session's `scratchpad/g-rc5/` (not committed).

### §6bp rows 5-13 and 12b (Koble til with the key book kept)

| Row | Android 14 (`compat34`) | Android 16 (`aosp36`) |
|---|---|---|
| 5 | **Pass.** `{"enrolled":true,"policy_applied":true,"server_url":"http://10.0.2.2:3150"}`; `device_key.xml` adopts `device_key_1`, `device_key_adopted.xml` the same | **Pass.** The same (`:3160`, `device_key_1`) |
| 6 | **Pass.** The cut (200 bytes, key book kept), `adb reboot`: Android's pad, 1357, Koble til with `http://10.0.2.2:3150` and Emergency call under the button; no "Resetting preferences" | **Pass**, with finding 1 at the boot (the pad came only after a screen off/on): Koble til with `:3160`, `Home hasn't started yet, but the phone is enrolled - keeping its preferences`. On `jelly36` the first cut reached the pad, then System UI hung (above) |
| 7 | **Pass.** "Call 112?" with Cancel and Call; Cancel back to Koble til | **Pass** |
| 8 | **Pass.** The navigation bar's Back: Home with nothing allowed (Phone book, Settings); Phone book, then Back: Koble til again | **Pass** |
| 9 | **Pass.** The drawer (a fast console swipe) and its gear: "Enter code to open settings", **Koble til not over it** (also 3 s later); 111111: "That code didn't match. Try again."; 246813 opens Settings (Server URL "Not set") | **Pass.** The same |
| 10 | **Pass.** adb `offline` from the fallback after the unlock, never opened by the lost file (the restrictions not readable meanwhile) | **Pass.** The same |
| 12 | **Pass.** `https://other.example.org`: "That isn't this phone's server." under the fields, no request at the relay; `http://other.example.org`: the address rule's line ("The phone only uses a server address that starts with https:// ...") | **Pass.** The same |
| 12b | **Pass.** `http://10.0.2.2:3150` with the used code: "That code didn't work. Check it and try again, or make a new one."; 21 s later the relay has `GET commands/stream`, the policy, apps and status; Koble til closed and didn't come back (Phone book and Back, Home); the drawer's gear still asks for the code. The `Enrolment restored` line not read: adb was closed by the cut (the relay shows the sync). A second time by accident with an empty code: the same | **Pass.** The same, the stream 4 s after the refused code |
| 11 | **Pass** (from `gate-base`, cut, reboot): a new code typed: the stream and the policy at once, no setup flow, Home with the policy's apps; the server's `device_keys` holds only the new key (`jfQj...`), the old one gone; the phone's key book not read (adb closed) | **Pass.** The same (the new key `fmCK...` alone) |
| 13 | **Pass** (from `gate-do`, the relay dropping `GET /api/devices/policy`): the witness `{"enrolled":true,"server_url":"http://10.0.2.2:3150"}`, the cut, the reboot: Android's swipe screen (no PIN yet), then Koble til with the address; Back: Home with every app, lock task `NONE`; `APPLICATION_PREFERENCES` opens Settings with no code | **Pass.** The same |

### The VPN service at a reboot (§6bp's finding 1, fixed on `launcher-edges-2`)

Every reboot's log checked for `ANR in me.vibb...` and `Tunnel: filter`:

| Image | Boots | Result |
|---|---|---|
| 14 `compat34` | 6 `adb reboot` (both navigation modes, the cuts) and 2 cold boots | **Pass.** No Vibb One ANR; `Tunnel: filter` and `Filter up and always-on` after every unlock. GMS ANRs at most boots (environment) |
| 16 `aosp36` | 3 `adb reboot`, 2 cold boots | **Pass.** No Vibb One ANR; `Tunnel: filter` after every unlock |
| 16 `jelly36` | 3 `adb reboot` | **Fail, finding 2.** One `executing service ...KidVpnService, waited 20117ms` (3-button reboot, 20 s after the unlock) and one `executing service ...BadgeListenerService, waited 22356ms` (the cut's reboot); both times the main thread was in `Application.initUnlocked`, not in the service. The tunnel came back with the restarted process (`tun0` up) |

### §6bu R1-R3

| # | 14 (`compat34`) | 16 |
|---|---|---|
| R1 | **Pass.** 3-button: `lock_glance_emergency` `[136,662][343,734]` over `lock_glance_handle` `[159,746][321,782]` (the bar `[0,782][480,854]`); gestures: `[136,698][343,770]` over `[159,782][321,818]`. A swipe from the handle's upper half (y 755 / 791): `NotificationShade` with "Enter your PIN" (Android's pad), no "Call 112?"; Emergency call's tap: "Call 112?" | **Pass** on `jelly36`: 3-button the same bounds as 14; gestures `[136,734][343,806]` over `[159,818][321,854]`; the swipe opens "Enter PIN", the tap "Call 112?" |
| R2 | **Pass, with a note.** Cold boots (`adb emu kill`, `-no-snapshot-load`), both modes: the cover focused while System UI is late, the asks (`resumed`, `no answer, again 1`), the pad (`NotificationShade`) and one "a window (Android's pad) is over the cover - no re-ask"; no "again N" while the pad is up; 1357 opens Home (`Boot cover leaves (taken over, Home over it): 27525 ms after the unlock`). The note: on 14 the cover is relaunched two or three times during the boot (`wm_relaunch_resume_activity`, configuration changes), and each relaunch asks again (`asks ... (relaunched, automatic 1 of 3)`), once while the pad was already up - the pad stayed, nothing visible (finding 3) | **Fail** on `aosp36`, both modes (finding 1): the first ask lands before System UI's keyguard (`wm_set_keyguard_shown` 5-10 s later), the next silence is skipped as "covered" while the cover has no window focus yet, no "again N" follows when the focus arrives, and once the keyguard's own lock screen takes the focus (`NotificationShade`, the clock - not the pad) every silence is skipped: System UI's clock over the cover, no pad, swipes and taps do nothing, until a screen off/on (`asks ... (resumed)`, the pad). The same at 2 of 3 `adb reboot`s on `aosp36` and once on `jelly36` (3-button) |
| R3 | **Pass**, both modes (`adb reboot`): Back on the pad: `Android's PIN pad cancelled`, "Tap to unlock" under the mark, no ask in the next 20 s; a tap: one ask (`tapped`), then the pad and one "no re-ask" | **Pass** on `jelly36`, both modes (in 3-button the pad first had to come up, finding 1) |

### §6bs row k (design 134 rev 4, the connected router as a still witness)

The setup as §6bs's run (Regularly 10 min, Location on, `mock_location` for the launcher; on `compat34` the `network`
test provider off - it has to be added as the shell user, before `adb root`: as root `cmd location providers` is
refused with `android from uid 0 not allowed to perform MOCK_LOCATION`, and a Locate then gives a `fused` fix that
takes no print). Home's networks (§6bs's made-up scan) taught by Locates with the satellite feed on two local days
(the clock 31 min on, the zone to `Pacific/Kiritimati`): `scan usable=4 trusted=4 networks=3`; `motion sensor` armed.
Each case is a fresh vouch at home (home's scan, connected to `00:00:5e:00:53:01` at `-42`, the feed, a Locate) and then
the cap try with the clock 245 min on (root `date`), GPS silent, one neighbour AP heard 600 s ago, a PWA save as the
nudge.

| Case | Android 14 (`compat34`) | Android 16 (`aosp36`) |
|---|---|---|
| The vouch | **Pass.** `vouch link read level=scan`, `StillPrint: taken devices=3` | **Pass.** The same |
| Connected at `-45` | **Pass.** `scan answer 0/1 ask=answered old`, `connected witness renews level=link screen=off dbm=-45 vouch=-45 diff=0`, `attempt path=LEARNED_FIRST result=renewed`, `Location heartbeat: STILL renewed by the connected AP`; no GPS. **`vouch=-45`, not the row's `-42`**: the vouch's level is the answering scan's entry for the connected BSSID (D20.6, `level=scan`), and home's made-up scan has `53:01` at `-45` | **Pass.** The same lines |
| At `-62` | **Pass.** `connected witness no reason=level level=link screen=off dbm=-62 vouch=-45 diff=-17`, `result=none`, `cap try failed fails=1 vouch=re-sent` | **Pass.** The same |
| At `-127` | **Pass.** `connected witness no reason=nolevel`, `fails=1` | **Pass** |
| The other BSSID (`53:02`) | **Pass.** `connected witness no reason=other ... diff=0` | **Pass** |
| networkId `-1` | **Pass.** `connected witness no reason=binding` | **Pass** |
| BSSID `02:00:00:00:00:00` | **Pass.** `connected witness no reason=unreadable` | **Pass** |
| `motion fire` first | **Pass.** `attempt path=TOGETHER result=withheld reason=old`, `FIX result=inconclusive reason=old n=1/3`; no witness line, no renewal | **Pass** |
| A fresh neighbour scan of 3 devices of their own, connected at `-45` | **Pass, with a note.** `scan answer 3/3 ask=cache noconnected`, no `STILL ended`; the witness then renewed (`connected witness renews ...`, `STILL renewed by the connected AP`) - the row says nothing of a renewal here; D23 lets a renewing witness stand | **The same** |
| The 24-h ceiling | **Pass.** Renewed at 245, 490, 735, 980 and 1225 min; at 1470 min `connected witness no reason=ceiling`, `cap try failed fails=1 vouch=dropped`; the next two beats `fails=2`, `fails=3` (the ladder's retries), then `QUIET backoff=60 min` and no `still` | **Pass.** The same |

The server's side of `still` was not readable: with the phone's clock hours ahead of the server's real time the
server drops the report's fix (`dropped locations that aren't valid ... refused=1`), so no `resent_at` - the clock
step's own limit, as §6bs's "no new location row".

### music-nudge: the launcher's report ask (rows written here)

`docs/notes/launcher/music-bridge.md`, "The report ask": the launcher asks (`BRIDGE_CHANGED` with
`me.vibb.music.extra.REPORT_ASK`) when the music app's last report is 12 h old and no ask went out in 3 h; it calls the
bridge `unbound` only when the report is older than `REPORT_STALE_MS` (**26 h**) and the first ask since it is 30 min
old and unanswered. The times are moved in the launcher's `music_bridge` preferences (`report_at`,
`report_ask_first`, `report_ask_last`, epoch ms) with `run-as me.vibb.launcher.debug` and the launcher's process
killed first (the restarted process syncs at once); the values are worked out on the host (the device shell's
arithmetic is 32-bit). The sync's nudge is a PWA save; the log is `adb logcat -s MusicSync`; the bridge word is the
server's newest `device_status.music_state_json`.

| # | Step | Expect |
|---|---|---|
| N1 | A report 11 h old (`report_at` 660 min back), no ask recorded; a sync | no `asked for one`; `report_at` unchanged; `bridge` `bound` |
| N2 | The music app idle: `am set-standby-bucket me.vibb.music.debug restricted`, left cached until `dumpsys activity processes` shows `isFrozen=true`; `report_at` 780 min back | `MusicSync: The music app's report is 780 min old: asked for one`; the music app reports within seconds (`report_at` now) and is frozen again; `bridge` `bound` |
| N2b | The same with the music app force-stopped (`stopped=true`) | the ask reaches it (`FLAG_INCLUDE_STOPPED_PACKAGES`); it reports; `bound` |
| N3 | The music app disabled (`pm disable-user --user 0`): the ask goes, no answer; then the asks set 31 min back, with the report 13 h and then 27 h old; then the last ask 181 min back (the first 211); then the app on again | the ask unanswered: `bound` while the first ask is under 30 min, and with the report 13 h old even after 30 min (under 26 h); with the report 27 h old and the first ask 31 min old: `unbound`; at 181 min a new ask (`asked for one`), `report_ask_first` kept, still `unbound`; the app on again reports by itself: `bound` |
| N4 | A phone without a policy (§6bp row 13's: the relay drops the policy), the music app installed, never reported | no `asked for one`, no `music_bridge` preferences at all |

| # | Android 14 (`compat34`) | Android 16 (`aosp36`) |
|---|---|---|
| N1 | **Pass.** 660 min, a new status row, no ask, `bound` | **Pass** |
| N2 | **Pass.** Bucket 45 (restricted), `isFrozen=true`; `asked for one` at 11:16:48, `report_at` 0 min, frozen again after; `bound` | **Pass, the bucket not restricted:** `set-standby-bucket` left the music app at 5 (exempted) on this image; frozen (`isFrozen=true`); the ask at `...929847` and the report at `...929881` ms (34 ms); `bound` |
| N2b | **Pass.** The ask at `...373881`, the report 1.3 s later | **Pass** |
| N3 | **Pass.** 13 h + 31 min: `bound`; 27 h + 31 min: `unbound`; the re-ask kept the first (211 min), `unbound`; the app on: it reported on its own (the package change's nudge), `bound` | **Pass.** The same |
| N4 | **Pass** | **Pass** |

### Findings (no product code changed here)

1. **Medium - the boot cover takes System UI's lock screen for Android's pad and stops asking** (launcher,
   `lock/BootCoverActivity.kt` `padStep`: `covered = !hasWindowFocus()`; `lock/BootCoverPlan.kt`). Android 16
   (`aosp36`): both cold boots and 2 of 3 `adb reboot`s; `jelly36` once (3-button reboot); never on 14. The cover's first
   ask (`resumed`) comes before System UI's keyguard is shown (`wm_set_keyguard_shown` 5-10 s later) and gets no answer;
   the next silence falls while the cover's window has no focus yet ("a window (Android's pad) is over the cover - no
   re-ask"); when the focus does arrive (`input_focus` "Focus entering ...BootCoverActivity" 3-4 s later) no re-ask
   follows; then the keyguard's own lock screen (`NotificationShade`, the clock, **not** the bouncer) takes the focus and
   every later silence is skipped. The kid sees System UI's clock over the cover; on `aosp36` swipes and taps did
   nothing; a screen off/on asks again (`resumed`) and the pad comes. Repro: `aosp36`, Android's PIN set by the policy,
   `adb emu kill`, cold boot, `adb shell dumpsys window | grep mCurrentFocus` (`NotificationShade`) with a console
   screenshot showing the clock, `adb logcat -b events | grep input_focus`. §6bu's fix assumed a `NotificationShade`
   with the focus is the pad; it is also the lock screen.
2. **Low-medium - the launcher's main thread is busy over 20 s after the first unlock on a slow boot**, so a service
   started then can't be created: on `jelly36` (2 GB, CPU pressure 73 %) one `executing service ...KidVpnService, waited
   20117ms` and one `executing service ...BadgeListenerService, waited 22356ms`, both with the main thread in
   `Application.initRest` <- `initUnlocked` <- `ensureUnlockedInit` <- `KidAppComponentFactory.instantiateProvider`
   (`TimeRulesRuntime.recheck`, `TrustedClock.read`, `ClockAnchor.getBootCount`), and the log full of the debug
   build's `Verification of ... took N ms`. The VPN service's own work is off the main thread now (that fix holds); this
   is the process's unlock work. Environment first (not seen on `compat34` or `aosp36` in 13 boots), but the unlock init
   on the main thread is ours. Repro: `jelly36`, reboot, unlock, `adb shell dumpsys dropbox --print data_app_anr`.
3. **Low - on Android 14 the boot cover is relaunched two or three times during a boot and each relaunch asks for
   the pad again** (`asks ... (relaunched, automatic 1 of 3)` after `wm_relaunch_resume_activity`), once while the pad
   was up; the pad stayed, nothing visible. Repro: `compat34`, a cold boot, `logcat -s BootCover`.
4. **Words in the rows** (no code): §6bs row k's `vouch=-42 diff=-3` is `vouch=-45 diff=0` with §6bs's home scan (the
   level is the answering scan's, D20.6); its noconnected case also renews by the witness. The music-nudge rule's
   `unbound` needs a report older than 26 h (`REPORT_STALE_MS`), not 12 h (12 h is when the ask goes).

**Rerun of 1-3** (branch `rc5-gate-fixes`, 2026-11-26, the debug launcher of that branch; `aosp36` and `jelly36` set up
anew with `-wipe-data` against a fresh debug server - an `emu kill` right after an `adb install` without `adb shell
sync` had left `aosp36` with a truncated APK, the launcher gone at the next boot -, `compat34` as the gate left it):
- **1, Android 16: pass.** `aosp36` (3-button and gestures, an `adb reboot` and a cold boot each) and `jelly36` (two
  `adb reboot`s, a cold boot): every boot had SystemUI's `OFF -> LOCKSCREEN` over the occluding cover; the asks before
  its show read `keyguard not up`, the next silence's ask (`keyguard up, no focus`) brought `LOCKSCREEN ->
  PRIMARY_BOUNCER` 0.5-6 s after the show (the next 3-6 s tick), then one "Android's pad is over the cover - no re-ask" and no further ask;
  1357 opens Home. R3 on `aosp36`: Back on the pad, "cancelled", no ask; a tap asks once (an earlier build of the
  branch, the same cancel and tap rule).
- **3, Android 14: pass.** `compat34` (both modes, an `adb reboot` and a cold boot each): the relaunches before the pad
  still ask (the pad isn't up); the relaunch with the pad up logs "relaunched with Android's pad up - no ask"; Back
  then closes the ask with the line and a tap asks once (an earlier build, the same orphan rule); 1357 opens Home.
  When the cover never had the focus and its readings were all "keyguard reads up, not yet occluded by the cover?" (2 of 4 boots), the first silence after the pad
  asked once more (nothing visible), then no more.
- **2, `jelly36`: pass.** Two `adb reboot`s and a cold boot, unlocked each time: no `ANR in me.vibb...`, no `Timeout
  executing service` of ours, the dropbox empty; `Unlocked setup took` 469-723 ms, the rules' own message 878-2421 ms,
  `Boot Home started` 2.7-7.0 s after the unlock.
- **After code QA** (the 30 s re-ask with the pad up, a not-shown reading taking `reached` back, the rules' step at an
  idle moment): `aosp36` an `adb reboot` (3-button) and a cold boot, `compat34` an `adb reboot` (gestures): no
  regression - the pad once System UI was up, then "no re-ask" and one ask every 30 s while it stayed up unanswered
  (only `Adding callback` in System UI's log, the pad not hidden or redrawn; on 14 it also brings back the answer of a
  relaunch's orphaned ask: "succeeded" at the PIN); 1357 opens Home, no ANR, the rules' step 0.5-1.2 s.

Not run: Android 17 (no working image); §6bp row 10's `dumpsys device_policy`, row 11's phone-side key book and 12b's
`Enrolment restored` line (adb closed by the cut; the server's tables and the relay stand in); R1 and R3 on `aosp36`
(run on `jelly36`); R2 on `jelly36` (System UI hung; run on `aosp36`).

## 6bt. Phase D: Android's PIN lock is the only lock (design 132 §11, D7) - run 2026-11-26 (branch `gate-phase-d`)

Design 132 §11's gate (G1-G12b) on **Android 14 (`compat34`, Google APIs, `adb root`) and Android 16 (`jelly36`,
Google Play)**, each from `-wipe-data`, 00:50-02:50 CET. The branch `phase-d-launcher` at `7f40601ff` (server and
launcher of phase D): its debug launcher (versionCode 116) and a debug server 0.93.0 `--features emulator_keys` with
`LAUNCHER_DEBUG_SIGNATURE_CHECKSUM`; for G3 also `main` at `6567f11fc` (the previous launcher and a server before
migration 0180 (0181 since the post-release chain), the same versionCode 116, installed with `install -r -d`). Each phone: Device Owner by adb (§4's
lines), Koble til with a typed code to `http://10.0.2.2:3160`, the setup flow finished, "Block USB debugging" off,
Parent PIN 246813, Child PIN 1357. The state after Device Owner, after the enrolment and after the Child PIN was saved
as a snapshot with a copy of the server's data (§3, "Faster gates"); after each load the phone's network was dropped
for 2 s (`svc wifi disable`/`enable`), else the command stream stayed half-open to the restarted server for minutes.
The PWA read as the signed-in parent (nb). Screenshots in the session's `scratchpad/g-phase-d/` (not committed).

| # | Android 14 (`compat34`) | Android 16 (`jelly36`) |
|---|---|---|
| G1 | **Pass.** `set_kid_pin` answered `pin set` 4 s after the save; `locksettings verify --old 1357` passes; `Password quality: {0=131072}` (NUMERIC) only after it (`{0=0}` before); the report `keyguard: android`, `pin_rev: 1`, `inactive: null`; screen off/on: the glance, its handle's swipe opens Android's pad, 1357 opens Home. Gesture and 3-button (finding 1) | **Pass.** The same (`password quality: NUMERIC` 2 s after `PIN command: pin set`); both navigation modes (`36-g1-glance.png`, `36-g1-pad.png`, `36-g1-glance-3btn.png`) |
| G2 | **Pass.** QR's case (no credential at the enrolment): `The reset token at enrolment: active`, `locksettings set-pin 1111` after it, the Child PIN saved: `pin set` replaced 1111, no "can't change" line. Koble til with 1111 set before the enrolment: `not active yet`, `refused: no_token`, the orange "Vibb One kan ikke endre Androids PIN" with "Lås opp telefonen én gang først." behind its (i); one unlock with 1111, and the next report (`reset_token: true`) made the server send one more row: `pin set`, NUMERIC (finding 4) | **Pass.** The same, both halves (`36-g2k-locked.png`, `36-g2k-done.png`). The QR half by the same stand-in on both: Device Owner by adb, Koble til before any screen lock, then `set-pin 1111` (no QR scan on the emulator) |
| G3 | **Pass.** `main`'s launcher with `main`'s server on Vibb One's lock (`keyguard: own`, `locksettings get-disabled` `true`, `pin_lock_state.xml` with `pin_hash`, `pin_salt`, `keyguard_requested`); this time the new launcher first: `The own lock's stored keys removed (12): true`, `get-disabled` `false` at its first apply, `no Android PIN: no lock until set_kid_pin` against the old server; then the new server on the same data (`pre-migrate-0179-0180.db`): its first `set_kid_pin` `pin set`, NUMERIC, the glance and the pad work | **Pass.** The design's order: the old RC on own mode, the new server (migration 0180, now 0181; the old RC took `set_kid_pin` and reported `android`), then the new launcher: `The own lock's stored keys removed (12): true`, only `keyguard_android`, `android_pin_rev`, `active` and the guard keys left, `get-disabled` `false`, NUMERIC, `KeyguardCover: Home (after our package was replaced) waits for Android's unlock`; the glance and the pad work |
| G4 | **Pass**, both navigation modes. The boot cover, then Android's pad by itself; 1357, then `Boot cover taken over`, Home (no stock launcher started, no second PIN). Home came 21-35 s after the unlock on this loaded VM. The restart line not checked: the boot cover asks for the pad at once, which covers the keyguard's info line | **Pass**, both navigation modes. The boot cover (the Vibb rings), then Android's pad (`36-g4-boot-gesture2.png`); `Android's PIN pad succeeded`, Home 25-28 s after the unlock (`Boot Home started 24706 ms after the unlock`). Finding 2 |
| G5 | **Pass.** `adb reboot` and `adb emu kill` + a cold boot: `Boot cover inert: the boot's HOME only`, Home under Android's Swipe screen (the AVD's own lock type), no glance, quality `{0=0}`; the setup page lists Barne-PIN under "På telefonen" | **Pass** (`adb reboot` only). The same; Swipe, then Home (`36-g5-boot-home.png`). Finding 2 |
| G6 | **Pass.** No remove control on the page; a stale `POST kid-lock clear_kid_pin=on`: "Ikke fjernet: barne-PIN-en kan byttes, ikke fjernes.", the hash kept, Android's PIN still 1357 | **Pass.** The same |
| G7 | **Pass.** Five wrong PINs by taps: "You have incorrectly typed your PIN 5 times. Try again in 30 seconds." (`34-g7-wait.png`); no keypad or back-off line in the log. (`input text` into 14's pad gave "Wrong PIN" without a lockout; taps count) | **Pass.** "Try again in 19 seconds." after five (`36-g7-wait.png`); no keypad or back-off line |
| G8 | **Partial.** `am crash` is refused for the Device Owner on 14 too ("Can not crash protected package"), so the three-crash guard was not reachable. A `kill -9` of the process while LOCKED, both navigation modes: the glance back, Android still locked | **Partial.** The same refusal; `run-as ... kill -9` while LOCKED, both modes: the glance back, `isKeyguardShowing=true` |
| G9 | **Pass.** A parent pause; Settings' screen lock (after Android's "Re-enter your PIN"): PIN 2468 set; the PWA's "Telefonens PIN er ikke barne-PIN-en"; Send again: `pin set`, 1357 again; "Lock again": the pause over, 1357 kept, NUMERIC, the line gone | **Pass.** The same |
| G10 | **Pass, without VoIP.** A call from a contact over the locked glance: Vibb's call screen, answer, hang up, the glance back, lock task LOCKED; an alarm over the glance, Stop, the glance back; the power double press opens nothing. **VoIP not run**: no VoIP app on the image | **Pass, without VoIP.** The same (`36-g10-call-locked.png`, `36-g10-alarm.png`); VoIP not run |
| G11 | **Pass.** The Child PIN saved while the phone was offline, its queued row deleted, `kid_pin_sealed = NULL`: the server's start logged `legacy Child PINs without a copy: 1 device_ids=[1]`; the phone's report `no_android_pin`; the orange "Telefonen har ingen PIN" with "Lagre barne-PIN på nytt." behind its (i), Attention "Låsen er av på telefonen"; saving 1357 again: `pin set`, NUMERIC, both gone (finding 5) | - (not required; G12b's stand-in shows the same line) |
| G12a | **Pass.** `locksettings clear --old 1357` and `set-pattern` refused ("New credential doesn't satisfy admin policies: Weak credential type") while NUMERIC; Settings' screen lock: None, Swipe and Pattern disabled (`34-g12a-picker.png`) | **Pass.** The same; None, Swipe, Pattern "Disabled by admin" (`36-g12a-picker.png`) |
| G12b | **Pass by the stand-in** (finding 3: unmanaged is not reachable): the Child PIN saved offline, its row answered `refused: failed`; the phone's report `no_android_pin`, Android's Swipe screen at a wake (no glance, no "Lås opp"); the orange "Telefonen har ingen PIN" with "Send barne-PIN-en igjen under.", Attention "Låsen er av på telefonen"; Send again: `pin set`, NUMERIC, the line and the Attention row gone | **Pass by the same stand-in** (`36-g12b-nopin-wake.png`) |
| Gone | The notice wake: the screen stayed on 30 s and more (to the screen timeout), no turn-off at 15 s | The same. At every lock-up: `Lock-up at screen-off: LOCK`, the glance alone, no Home under it. No 3 s boot mark: Home came straight after the boot cover |

Not run: VoIP (G10, and "VoIP with Android not locked"), the crash guard's three crashes (G8), the re-front's and the
RELOCK backstop's timings, "kiosk with a live call", J1 (the Jelly Star's row).

Findings (no product code changed here):

1. **Android 14, 3-button navigation: Emergency call overlaps the glance's handle.** `lock_glance_emergency`
   `[136,698][343,770]` and `lock_glance_handle` `[159,746][321,782]` on `compat34` (the same after a process restart;
   the navigation bar `[0,782][480,854]`); on `jelly36` in the same mode Emergency call is `[136,662][343,734]`. A
   swipe up that starts on the handle's upper half opens "Call 112?" (`34-g1-after-3btn-try.png`,
   `34-g1-glance-3btn.png`). Repro: `compat34`, `cmd overlay enable-exclusive --category
   com.android.internal.systemui.navbar.threebutton`, screen off/on, `uiautomator dump`. Not phase D's change; the
   glance's bottom room on 14 seems not to count the 3-button bar.
2. **The boot cover asks for Android's pad every 3-6 s while the pad is up** (`Boot cover asks for Android's PIN pad (no
   answer, again 1..10, automatic 1 of 3)`) and once cancelled it when the screen went off on 14; the unlock still
   worked each time. With the VM's boot load (ANRs of System UI, GMS and Vibb One in the first minute, load 20-30) Home
   came 21-35 s after the unlock, and on 16 the Pixel launcher's Recents activity was resumed behind Vibb's Home after
   its own ANR (not visible). Environment first.
3. **G12b's premise is not reachable**: a managed phone can't be made unmanaged from the server. The PWA has no switch
   for the apps; `allowlist_json = NULL` set in the database is filled again from the phone's next report
   (`device_api.rs`, the first-heartbeat bootstrap), and the launcher's `PolicyGate` refuses a fresh policy without an
   app list on a managed phone anyway. So Android's PIN can't be cleared while NUMERIC (G12a holds), and the
   `ownedGone` path (Android's PIN removed on the phone) was not reached. The stand-in (a row answered `refused:
   failed`) checks the server's rule, the phone's `no_android_pin`, the PWA and Send again.
4. **Koble til with a screen lock: two orange lines say different things.** With `refused: no_token` the page shows
   "Telefonens PIN er ikke barne-PIN-en - Den ble trolig endret på telefonen. Å sende barne-PIN-en igjen setter den
   tilbake." above "Vibb One kan ikke endre Androids PIN" (unlock first): the first is wrong here (the PIN was set in
   the wizard, and Send again can't work before the unlock), and the row's own note repeats the second ("... før barnet
   har låst opp telefonen én gang. Send den så igjen."). Words only; for the owner.
5. **"Velg en barne-PIN under." while a Child PIN is stored.** Between the save and the phone's next report the
   newest report is still `no_pin`, and the page shows "Telefonen har ingen PIN" with "Velg en barne-PIN under." -
   for a legacy PIN without a copy (G11) until the phone reports `no_android_pin`, then "Lagre barne-PIN på nytt.".
   Words only.
6. **Side notes, not phase D:** after a snapshot load a pending `set_kid_pin` waited until a parent save nudged the
   phone, although the command stream had reconnected (no `sse_open` sync within minutes of an earlier sync); a
   contact's "Kan ringe og ringes" switches saved by the gate's form client didn't stick (`allow_inbound` 0) - set in
   the database instead, not looked into; once on `jelly36` adb went offline for good after a Koble til enrolment from a
   loaded snapshot (the next snapshot load brought it back; not seen on a fresh boot).

### 6bt-r. The fixes' rows (branch `phase-d-fixes`) - run 2026-11-26/27 (§6by)

Fixed on `phase-d-fixes`: finding 1 (`KidInsets.apply` keeps the handle's room when no insets reach the page), 2 (the
boot cover asks once and again only when its window has the focus back - code QA M1/L1), 4 (no "not the Child PIN"
without the reset token), 5 (`kid_lock::no_pin_line`), 3 (design 132 §11 G12b corrected) and side note 6 (the
command stream's open nudge for a due command, at most one a minute per phone). Re-run on the same images:

| # | Row | 14 (`compat34`) | 16 (`jelly36`) |
|---|---|---|---|
| R1 | G1's glance in both navigation modes: `uiautomator dump` - `lock_glance_emergency`'s bottom at or above `lock_glance_handle`'s top (14 3-button: Nødsamtale up to 734 over the handle `[746,782]`); a swipe up from the handle's upper half opens Android's pad, never "Call 112?"; Nødsamtale's tap still asks "Call 112?" | x | x |
| R2 (code QA M1) | G4 on a **cold first boot** (`-wipe-data` snapshot not loaded: `adb emu kill`, a cold boot, so SystemUI's keyguard is late), both navigation modes: while SystemUI is late (no pad yet) `adb shell dumpsys window \| grep mCurrentFocus` names the boot cover (`BootCoverActivity`) and the log has the re-asks ("no answer, again N"); once the pad is up `mCurrentFocus` names SystemUI's `NotificationShade` and `logcat -s` the cover's tag shows no 3-6 s re-ask while it is up ("Android's pad is over the cover - no re-ask"), only the 30 s one of `rc5-gate-fixes` ("again N, the pad unanswered 30 s", each followed by "no re-ask"; the pad not hidden or redrawn); the PIN opens Home | x | x |
| R3 | G4 with the pad cancelled (Back on the pad): "Trykk for å låse opp" under the mark, no ask until a tap; a tap asks once | x | x |
| R4 | G2's Koble til half (Android's PIN set **before** `dpm set-device-owner`: the launcher sets its reset token at every apply, so a PIN set after the Device Owner leaves the token active; then Koble til, `The reset token at enrolment: not active yet`): with `refused: no_token` the Lock page shows "Vibb One kan ikke endre Androids PIN" alone - no "Telefonens PIN er ikke barne-PIN-en", no Attention row for it | x | - |
| R5 | G11: between the save and the phone's next report "Telefonen har ingen PIN" with "Lagre barne-PIN på nytt." behind the (i), never "Velg en barne-PIN under." | x | - |
| R6 | Side note 6: after a snapshot load with a waiting `set_kid_pin` and the 2 s network drop, the row goes out within seconds of the stream's reconnect (the server log `command stream opened`, the phone's `Command stream nudge received`), no parent save | x | - |

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

## 6by. The night gate after the post-release chain (branch `gate-night-1126`) - run 2026-11-26/27

On **Android 14 and 16** with the owner's OK for the night (the 17 images don't run), 22:20-00:35 UTC. `main` at
`28d4d29e3`: the debug launcher (`assembleDebug`) and a debug server 0.94.1 (`--features emulator_keys`,
`LAUNCHER_DEBUG_SIGNATURE_CHECKSUM`) from the same tree; for §6bx step 1 also a server from `3232933ca` (phase D and
its fixes, migrations up to 0181, not 0182). Servers on `127.0.0.1` (the emulator reaches them as `10.0.2.2`, no
relay): one per image for §6bt-r (14: `:3170`, 16: `:3172`), one shared by both phones for §6bx and §6bw (`:3180`).
Android 14 on `compat34` (Google APIs, `-wipe-data`), Android 16 on `jelly36` (Google Play, `-wipe-data`; its first
`dpm set-device-owner` answered "already some accounts" for about a minute, as the note in the SMS engine's section says, then took). Each phone:
§4's adb lines, "Blokker USB-feilsøking" off and Foreldre-PIN 246813 in the PWA before Koble til with a typed code,
the setup flow finished, Barne-PIN 1357 where a row needs it; snapshots `gn-do` (Device Owner, not enrolled), `gn-enr`
(enrolled, no Barne-PIN), `gn-base` (Barne-PIN set) with a copy of the server data (§3, "Faster gates"). Logs and
screenshots in the session's `scratchpad/gn/` (not committed).

### §6bt-r R1-R6

| # | Android 14 (`compat34`) | Android 16 (`jelly36`) |
|---|---|---|
| R1 | **Pass**, both modes. Gestures: `lock_glance_emergency` `[136,698][343,770]` over `lock_glance_handle` `[159,782][321,818]` (the bar `[0,818][480,854]`); 3-button: `[136,662][343,734]` over `[159,746][321,782]` (the bar `[0,782]`). A swipe from the handle's upper half: `NotificationShade`, "Enter your PIN"; Emergency call's tap: "Call 112?" | **Pass**, both modes. Gestures `[136,734][343,806]` over `[159,818][321,854]`; 3-button as 14. The swipe: "Enter PIN"; the tap: "Call 112?" |
| R2 | **Pass** (the row's text corrected), both modes, a cold boot each (`emu kill`, `-no-snapshot-load`). Gestures: `mCurrentFocus` the boot cover for 3-6 s, then `NotificationShade`; the ask `(relaunched, automatic 1 of 3; keyguard up, no focus)`, "Boot cover relaunched with Android's pad up - no ask", then "Android's pad is over the cover - no re-ask" and no 3-6 s re-ask; every 30 s one "again N, the pad unanswered 30 s", each followed by "no re-ask"; 1357 opens Home (`Boot cover leaves ... 5136 ms after the unlock`). 3-button the same (9.8 s) | **Pass** (the same correction), both modes. The cover's asks while System UI is late (`resumed`, `again 1-2 ... keyguard not up`, `again 3 ... keyguard up, no focus`), then "no re-ask" and the 30 s re-asks; 1357 opens Home 9.2 s (3-button) and 18.5 s (gestures) after the unlock |
| R3 | **Pass** (gestures, `adb reboot`). Back on the pad: "the focus back, the relaunch's pad gone - the line, no ask"; "Tap to unlock" under the rings (the phone in English), no ask in 20 s; a tap: one ask (`tapped`), the pad, one "no re-ask" | **Pass.** Back: "Android's PIN pad cancelled"; the same line and tap |
| R4 | **Pass** (the row's setup corrected). A PIN set after the Device Owner left the token active (`The reset token at enrolment: active`, the row then `pin set`): the launcher sets its token at every apply. With `locksettings set-pin 1111` before `dpm set-device-owner` (a fresh `-wipe-data`): `not active yet`, the Barne-PIN's row `refused: no_token`; the Lock page: "Vibb One kan ikke endre Androids PIN" with "Lås opp telefonen én gang først." behind its (i), no "Telefonens PIN er ikke barne-PIN-en", no Attention row. One unlock with 1111 and a nudge: a second row `pin set`, the line gone | - |
| R5 | **Pass.** From `gn-enr`: the Barne-PIN saved before the phone fetched it, its row deleted and `kid_pin_sealed` set NULL, the server restarted (`legacy Child PINs without a copy: 1 device_ids=[1]`); with the newest report still `no_pin`: "Telefonen har ingen PIN" with "Lagre barne-PIN på nytt.", no "Velg en barne-PIN under."; after the next report (`no_android_pin`) the same, and Attention "Låsen er av på telefonen" | - |
| R6 | **Pass.** From an idle snapshot (Barne-PIN 1357 set): the emulator paused, a new Barne-PIN 2468 saved (the row waits), the snapshot loaded and resumed, Wi-Fi off 2 s: the stream came back after its own back-off (23 s), `Command stream connected` and `Command stream nudge received, syncing early` in the same ms, `Sync for [sse]`, `PIN command: pin set (rev 2)` 2 s later; the server `command stream opened`; no parent save. (A first try from `gn-enr` didn't test the nudge: that snapshot's own queued `[setup]` sync fetched the row before the stream opened) | - |

### §6bx rows 1-9

One server, both phones: Telefon 1 on `compat34` (14), Telefon 2 on `jelly36` (16); two parents (A, and B added under
Konto › Foreldre), each with a push subscription to a made-up endpoint under `.push.apple.com` that doesn't resolve (no
push leaves the machine; the queue's rows are what the rows read). Fixes by the launcher's satellite feed
(`PlacesDebugReceiver --es gps`) and a Locate; the policy read from the phone's cached policy (`run-as`), fences from
`dumpsys location`.

| # | Result |
|---|---|
| 1 | **Pass.** The `3232933ca` server (0181): Hjem and Skole on Telefon 1, Hjem (the same numbers) on Telefon 2; Drar off on Telefon 1's Hjem; Telefon 1's weather zone Hjem; two fixes in Hjem on each, 2 min apart: "Her nå" on both Hjem; two fences on Telefon 1, one on Telefon 2 |
| 2 | **Pass** (the backup's name in the row corrected). The new server on the same data: `migrations pending - the database before them is in data/backups/pre-migrate-0181-0184.db`, `_sqlx_migrations` 184; `/zones`: "Hjem 150 m · Telefon 1, Telefon 2", "Skole 150 m · Telefon 1"; no new inbox row; at the next sync no `Fences:` line, the dumps as step 1, the policy's zones ids 1, 2 and 3 with the same circles; Telefon 1's weather zone still Hjem (`weather_family_zone_id` 1); `zone_alerts` leave off on link 1 for both parents |
| 3 | **Pass.** Skole switched on for Telefon 2: back at `#zone-2` with "Lagret."; `Fences: 1 removed, 2 of 2 added`, two fences; two fixes in Skole: nothing for Skole (they also confirmed Telefon 2 out of Hjem: "Telefon 2 dro fra Hjem", as the geometry says); two fixes 1 km away: "Telefon 2 dro fra Skole" for Telefon 2 only |
| 4 | **Pass.** `/zones/1`'s (i): "Sonen er felles. Endringer gjelder alle telefonene som bruker den."; Hjem moved 300 m north: on each phone `Fences: 2 removed, 2 of 2 added` (the phone re-registers its set), the dump with the new circle; no event; a fix outside, then two inside the new circle: "Telefon 1 kom til Hjem" and "Telefon 2 kom til Hjem", each its own |
| 5 | **Pass.** Parent B switched Kommer off for Telefon 2's Hjem: B's `zone_alerts` row (arrive 0); A's page still on. Telefon 2 left and came back: both events in both inboxes; the left one queued for A's and B's subscriptions, the arrival for A's alone |
| 6 | **Pass.** Fotball (no phone) made; Telefon 2's page read twice; the first switched Fotball on, the second (read before it) switched Kommer for Hjem off: Fotball stays linked (three fences), the Kommer change saved |
| 7 | **Pass.** Hjem off for Telefon 1: its fence gone, the policy without it, the weather still Hjem; on again: a new link id (6) in the policy, the side pending and then confirmed silently; Delete Hjem: both phones' Hjem fences gone, Telefon 1's weather "Ingen" |
| 8 | **Pass.** Ten zones on Telefon 1, an eleventh switched on: `?refused=full#zone-13`, "10 soner er det meste", still ten links, no `Fences:` line on the phone |
| 9 | **Pass.** Hjem made again for both phones, both on the made-up `Net-A` (psk) with a fix in Hjem: one Bekreft row (naming the phone whose report showed it); Bekreft: "Lagret." in the group, both phones nudged (`Command stream nudge received`), the policies' Hjem with `networks` ids 1 and 2 (one row per link); Hjem off and on for Telefon 2: its new link (18) carries the router (id 3); Fjern: gone from both policies. Bekreft again (ids 4, 5), then Telefon 1 on Net-A with the feed 3 km north twice 90 s apart: `zone link struck by=confirmed`, both rows `blocked_at` set, both policies without the router, Telefon 2 nudged, the group "Sett et annet sted" with its (i) |

### §6bw rows o and p

On the §6bx server and phones (Hjem 59.9139, 10.7522, 150 m), with a fresh made-up router `Net-C`
(`00:00:5e:00:70:01`, psk) confirmed for Hjem - a router with no rows of its own, so the single-router fix stands at the
zone's centre (`at=zone acc=152`); Net-A's own rows had moved with the strike of §6bx row 9. GPS silent as §6bw's run
(on 14 the `network` test provider off; the shell's `mock_location` allowed for the test providers).

| Row | Android 14 (`compat34`) | Android 16 (`jelly36`) |
|---|---|---|
| o | **Pass for the reboot; the 15-min half not measured** (finding 1). A router fix at home, then `svc power reboot` (`phone_off` 00:12:42, `phone_on` 00:12:48): `power_boot_gap.xml` `boot_off_gap_v1` `2:5585`; Net-C alone, GPS silent, Locate: `router fix fix at=zone acc=152`, `wifi fix check=link step=USE`, a `vibb_wifi` `link` fix. Then `svc power shutdown` (`phone_off` 00:15:27), a cold boot at 00:30:49: `router fix continuity`, `result=none`, but with `power_boot_gap.xml` empty | **Pass.** The reboot: `2:6137`, `router fix fix at=zone acc=152`, a `link` fix. The shutdown (`phone_off` 00:15:32), 15 min, a cold boot (`phone_on` 00:30:50): `boot_off_gap_v1` `3:918073` (15.3 min, over the 10 min), `router fix continuity` |
| p | **Not run.** No placed cell: a cell-only Locate (GPS silent, one unknown AP, not connected) gave `router fix unconfirmed`, `result=none`; the phone's own cell row needs three teaches 30 min apart (`CELL_OCCASIONS`), and the gate stopped after the first | **Not run**, the same |

### Findings (no product code changed here)

1. **Low, to look at (14 only) - a `phone_on` while the emulator was off, and no gap after the cold boot** (row o's
   second half on `compat34`): `svc power shutdown` at 00:15:27 gave its `phone_off` and the emulator process ended at
   00:15:34, yet the server stored a `phone_on` for Telefon 1 at 00:16:02 (received 00:16:29), and another at the real
   cold boot (00:30:51); after that boot `power_boot_gap.xml` was empty, so the gate saw an unknown gap - `continuity`,
   the expected outcome, but not by the measured 15 min as on 16. Repro: `compat34`, a router fix at home, `adb shell
   svc power shutdown`, a cold boot 15 min later, then `run-as me.vibb.launcher.debug cat
   /data/user_de/0/me.vibb.launcher.debug/shared_prefs/power_boot_gap.xml` and the server's `parent_events`
   (`phone_off`/`phone_on`). Environment first (the Google APIs image's shutdown path), not seen on 16.
2. **Words (no code)** - after `refused: no_token` the send row's own note still repeats the line above it ("Vibb One
   kan ikke endre Androids PIN før barnet har låst opp telefonen én gang. Send den så igjen."; §6bt finding 4's second
   half), for the owner.

**Rows corrected** (no code): §6bt-r R2 (the 30 s re-ask with the pad up, `rc5-gate-fixes`), R4 (the PIN before the
Device Owner); §6bx row 2 (the backup's name).

Not run: §6bw p (above) and o's measured 15-min gap on 14 (finding 1); Android 17; the Jelly Star's device rows
(§6bw's, §6bx's relearning).

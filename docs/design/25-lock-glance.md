# 25 - The lock's glance page (buildable spec)

Status: spec, architect 2026-10-11, on `main` at 17b89834 (music step 4 with its fix round and design 24b are in). Not
built. Mockup: `docs/design/mockups/vibb-music/LockGlance.dc.html` (three 320x568 screens: A the glance page while
music plays, B without music, C the keypad stage after a row tap - tap a row on A or B and C's prompt follows).

Sources: the request and decisions below, `lock/PinLockActivity.kt`, `PinLockRuntime.kt`, `PinLockState.kt`,
`LockTaskRoot.kt`, `LockMedia.kt`, `music/MusicLockPanel.kt`, `badges/*`, `calls/MissedCall*.kt`, `lock/VoipCalls.kt`,
the server's `device_detail.html`, `models.rs` and `handlers/devices.rs`, designs 10, 12, 15, 16, 17, 21 (step 4) and
24b. [AOSP] marks something read in `android14/15/16-release` for this design; [device] marks what only the phone can
answer.

## The request (user, 2026-10-11)

The two-stage lock from music step 4 (design 20 "PIN lock while media is actively playing", mockup `Lock.dc.html`)
becomes permanent. The power button always shows the clean first page first; **"Lås opp"** leads to the keypad.
The first page shows:
- the clock and the date;
- the music tile while something plays (as step 4);
- a short list of notifications, mainly missed calls and messages (e.g. Element X);
- "Lås opp" and the emergency call button.

## User decisions (2026-10-11)

- **Message content:** the parent chooses in the PWA. The default is the sender only (e.g. "Element X: Pappa (2)"); a
  switch shows the text too.
- **Tapping a notification:** the keypad comes up, and after the right code the conversation or the call log opens
  directly.
- **Sources:** missed calls always. Plus apps the parent marks "vis på låseskjerm" in the PWA's app list; Element X is
  on by default.

## 0. In short

- The PIN lock always comes up on the **glance page**: clock, date, the music tile while something plays, up to three
  notification rows, "Lås opp", "Nødsamtale". Step 4's media view becomes this page; its stage machine stays.
- **Rows**: missed calls (ours, from the kid's phone book) always; messages from the apps the parent ticks (Element X
  ticked by default). Sender only, or with the text when the parent switches it on. Read in memory only, never
  logged, never sent to the server.
- **A tap on a row** opens the keypad with "Skriv koden for å åpne Element X". After the right code (or the parent
  code) the lock sends the notification's own content intent from its visible window, after Home is in front, and
  finishes. Nothing else ever runs from the lock: no reply, no mark-as-read, no dismiss.
- **Server and PWA**: a per-phone app list "On the lock screen" and a "Show the message text" switch in the Apps card;
  `lock_glance: {apps, show_text}` in the policy, both directories in one commit.
- Only with handy's PIN lock active. No kid PIN = no lock = no glance page (Home as today).

## 1. The lock

### 1.1 Stages (pure `lock/LockGlance.kt`, replaces `lock/LockMedia.kt`)

- `enum class LockStage { GLANCE, KEYPAD }` (step 4's `MEDIA` becomes `GLANCE`).
- **When the lock comes up** (`lockStageDue` unchanged: the first show, and the first resume with the screen on after
  every screen-off) the stage is `GLANCE` - always, music or not. Coming back with the screen on from the emergency
  dialer, an alarm, a call or a VoIP call keeps the stage the kid was on (as step 4), and the target with it.
- `GLANCE` -> `KEYPAD`: "Lås opp" (no target) or a row (with that row's target, §1.4).
- `KEYPAD` -> `GLANCE`: the back arrow (drops the target). A lock-up drops it too.
- The VoIP ring card stays on top of both stages (design 17, unchanged).
- **The music tile** (pure `glanceTile(session, shown)`): it appears when the music app's session (`shownSession`,
  step 4) is playing, stays while it is paused, and goes when the session ends; at the next lock-up a paused session
  shows no tile. `session == null` (no listener access, the app suspended or gone) = no tile. Its controls are step
  4's: play/pause, previous, next (the compact tile: play/pause only, §1.2). Nothing opens the player.
- **The keypad stage** always has the back arrow (to the glance page), never the clock or the date (the glance page
  has them; mockup C). The compact music row stays while a session is live (step 4). The prompt: the usual
  wait/wrong/checking/unusable texts first; else with a target "Skriv koden for å åpne Element X" (app label,
  `AppDisplay.shownLabel`) or "Skriv koden for å se tapte anrop"; else "Skriv inn koden din". The prompt row already
  takes two lines between the arrow's columns (qa-21-step4-code L5), so the hint costs no height.
  - `pin_clock`/`pin_date` leave `activity_pin_lock.xml`; `PinKeypadLayout`'s date/clock compact steps go
    (`finalKeySizePx(.., compactExhausted = true)` always: keys shrink to the 32 dp floor rather than clip).
  - The arrow is always visible, so the prompt's side margins are static (`setMediaKeypad` goes).
  - Budget (`PinLockLayoutTest`, 320x569 dp, status bar 48 dp, nav 24/48 dp): with the compact row the stage is
    step 4's music stage today (keys >= 52 dp); without it the keys get more room.

### 1.2 The glance page (`view_lock_glance.xml`, replaces `view_lock_media.xml`; mockup A and B)

Top to bottom, 16 dp side padding (content 288 dp at 320 dp), 8 dp top, 16 dp bottom:
1. Clock 56 sp (`kid_ink`, extrabold), or 40 sp when the fit needs it.
2. Date (`EEEEdMMMM`), 14 sp, `kid_core_dim`, 6 dp below.
3. The music tile, 18 dp below, while `glanceTile` says so:
   - **full**: step 4's tile unchanged (72 dp art, item, entry, 5 dp progress, 52/64/52 dp previous/play/next);
   - **compact** (mockup A): one row, 10 dp padding - 52 dp art, item (15 sp, 1 line), entry (13 sp), a 3 dp progress
     bar under them, a 48 dp peach play/pause.
4. Rows, 14 dp below the tile (22 dp below the date without one): each a button on `kid_music_tile` (#1B1B28),
   16 dp corners, min 56 dp, padding 10/12 dp, 8 dp apart: a 36 dp icon, title (15 sp extrabold, 1 line,
   ellipsized), subtitle (13 sp bold, `kid_core_dim`, 1 line), time (12 sp) at the top right.
   - Message row: the app's icon as Home draws it (`KidAvatars.renderAppIcon`, the parent's glyph and colour
     included); title = the sender (a group: its name); subtitle = "Element X · 2 meldinger" (the app's shown label),
     or with text on the newest message ("Pappa: Kommer kl 5" in a group).
   - Missed-call row: the contact's Home avatar (`KidAvatars.bindContact`: photo or initial) with a small red phone
     badge; title = the phone-book name, else the number; subtitle "Tapt anrop" / "3 tapte anrop". The plain row
     (§2.4): a phone glyph, title "2 tapte anrop", no subtitle.
   - Time: `HH:mm` today, "i går" / "yesterday", else `d. MMM` (`relativeDay`).
   - "+ 2 varsler til" (13 sp, 24 dp, not a button) under the rows when some are left out.
5. A spacer (weight 1), "Lås opp" (56 dp, peach, 20 dp corners), "Nødsamtale" (red text, 44 dp; the lock's own
   button via `pinEmergency.performClick()` as step 4).

Ids: `lock_glance_root`, `lock_glance_clock`, `lock_glance_date`, the tile's step-4 ids (`lock_media_*`) plus
`lock_media_compact_*`, `lock_glance_rows`, `lock_glance_more`, `lock_glance_unlock`, `lock_glance_emergency`.

**Fit** (pure `glanceFit(availableDp, heights, tile, rowCount)`): the glance never scrolls. The panel measures each
part once per render (the views measured at the screen's width, so font scale counts) and takes the first candidate
that fits:
1. big clock, full tile, rows = min(n, 3) (+ the "more" line when n > 3);
2. small clock, full tile, same rows;
3. big clock, compact tile, same rows;
4. small clock, compact tile, same rows;
5. small clock, compact tile, one row less each time down to 1 (the "more" line counts the rest);
6. as 5 with 1 row and no date;
7. the same with 0 rows (only the "more" line).

Without a tile, 2-4 are only the clock step; without rows, 1-2 always fit. A re-render (rows or tile changed, a
lock-up) starts again from 1. Expected at 320x569 dp with both bars at 48 dp (473 dp, the 1.4 x text model of
`LockMediaLayoutTest`): full tile alone -> 1 (445 dp); 3 rows + more, no music -> 1 (464); music + 1 row -> 3 (394);
music + 2 rows -> 3 (458, mockup A); music + 3 rows -> 5 with 2 rows + more (460). With 24 dp navigation (497 dp)
music + 1 row keeps the full tile (2, 493). `LockGlanceLayoutTest` pins these from the XML.

**Widths**: every round control has fixed equal width and height (never a weight - the Android form of the mockup's
`flex: none`); at 320, 360 and 411 dp the tile controls (204 dp), a row's fixed parts (116 dp) and the compact tile's
(144 dp) fit the 288/328/379 dp content and leave >= 120 dp for text.

**The window** gets `FLAG_SECURE` in `PinLockActivity.onCreate`, before `setContentView`, on both stages: names and
texts of the kid's conversations, and the PIN keys' ripples, never reach a screenshot, a screen recording or a cast
(the parent-PIN dialogs have it since 24b). `adb exec-out screencap` then shows black over the lock; QA takes
screenshots with the emulator console (`adb emu screenrecord screenshot <file>`) [device: that it captures secure
windows] and checks content with UI dumps.

**TalkBack**: the glance root blocks touches to the keypad below and has no node (step 4's L7); the keypad stage is
`IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS` while covered. Order: clock, date, tile controls, rows, more line,
"Lås opp", "Nødsamtale". A row is one node: "Element X, Pappa, 2 meldinger, 14:10" / "Tapt anrop fra Mamma, 14:02",
click action label "Lås opp og åpne" / "Lås opp og se tapte anrop". Each row view (and its children) is
`setAccessibilityDataSensitive(YES)`: only accessibility tools read them (TalkBack is one; UiAutomation is one by
default [AOSP 16 `UiAutomation`], so UI dumps still see them).

### 1.3 Who draws what

- `lock/GlancePanel.kt` (new, glue): the glance page in the lock's frame (between the keypad and the VoIP ring
  screen, where `MusicLockPanel` put the media view), the stage, the target, the rows, the fit. Listens to
  `GlanceStore` and `MissedCallNotifier` between the lock's onStart and onStop. Its render is wrapped: an exception
  hides the rows and logs the exception class only - a glance fault must never trip the lock's crash guard (3
  crashes switch the lock off).
- `music/MusicLockPanel.kt`: binds the full and the compact tile and the keypad's compact row while a session is live;
  no stage logic any more. `LockMediaGuardTest` keeps it opening nothing.
- `PinLockActivity`: `glance` replaces `music`; `setMediaKeypad`/`mediaKeypad`/`compactStep` go; `FLAG_SECURE`; the
  unlock path of §1.4.

### 1.4 The tap and the open after the code

**The target** (in the panel, memory only): a message row -> `Conversation(packageName, notificationKey,
conversationKey, postTimeMs, contentIntent)`; a missed-call row -> `MissedCalls(contactNumber?)`. Not taken while a
time rule locks the phone (`LauncherPreferences.mdm().lockReason() != NONE`): the row then just opens the keypad with
the plain prompt.

**At the unlock** (`PinLockActivity.unlock(checkedAt)`, reached only by the right kid PIN or the parent code with no
screen-off since the check started):
```
open = glance.takeTarget()                          // null after "Lås opp"
wasRoot = LockTaskChrome.kioskOn && startedLockTask // read before stopOwnLockTask()
pendingOpen = open?.let { PendingOpen(it, wasRoot) }
stopOwnLockTask(); PinLockRuntime.unlocked(this, checkedAt); leave()   // as today

go(plan):                                           // leave()'s step, the only place pendingOpen is used
  if (plan.stopLockTaskFirst) stopLockTaskQuietly()
  if (plan.homeFirst) HomeFront.bring(this, "the lock left")
  pendingOpen?.takeIf { PinLockRuntime.mode == UNLOCKED }?.let { GlanceOpener.open(this, it) }; pendingOpen = null
  if (!isFinishing) finishAndRemoveTask()
```
- **Why from the lock, after Home, before the finish**: a PendingIntent's *sender* may start an activity from the
  background only with a visible window (or a foreground process); the device-owner and HOME exemptions are the
  caller's, not the sender's [AOSP `BackgroundActivityStartController`: `checkBackgroundActivityStartAllowedBySender`
  in 14/15, `checkBackgroundActivityStartAllowedByRealCaller` in 16]. The lock is visible until it finishes, so the
  send goes out with `VoipCalls`' options - the sender's opt-in: `MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE`
  on 36, `MODE_BACKGROUND_ACTIVITY_START_ALLOWED` on 34/35. Kiosk on, Home comes first (design 16: the lock leaves
  through Home), then the app's task on top of it, inside the lock task Home roots; kiosk off there is no Home step
  and no lock task after `stopOwnLockTask`, and Back from the app returns to what was open before the lock.
- `lock/VisibleSender.kt` (new): the options helper `VoipCalls` and `MusicSessions` each have today, moved to one place;
  both call it (their guard tests pin the sends, not the helper).
- **`GlanceOpener.open`** (new, glue; pure decision `glanceOpenStep`):

| Case | Result |
|---|---|
| a time rule locks the phone now | nothing (the time-rule screen comes up as today, `afterUnlock`) |
| kiosk on and the lock was the lock-task root (`wasRoot`, the 1 s fallback) | nothing: the app would cover Home before Home re-roots lock task |
| missed calls | `PhoneBookActivity.missedCallsIntent(ctx, number)` (one contact's sheet, else the phone book; unmanaged with our dialer role: passed on to the system dialer, design 12) - opening it marks them read |
| conversation, package not installed, suspended (`isPackageSuspended`) or hidden (`dpm.isApplicationHidden`) | nothing |
| conversation, the newest content intent of that conversation (else the one captured at the tap) is an activity (`PendingIntent.isActivity()`) | `send(ctx, 0, null, null, null, null, VisibleSender.options())` |
| no intent, not an activity, or `send` threw `CanceledException` | the app's launch intent (`getLaunchIntentForPackage`, `NEW_TASK`) |

  - After a send or a launch: `GlanceStore.markOpened(conversationKey, postTimeMs)` (§2.6). Logs "Glance: opened
    <package> (intent|app)" or "nothing opened (<reason>)" - the package only.
  - The open does what the kid's own tap in the shade would do right after the unlock, nothing more: a content
    intent that leads into a package outside the kiosk ends in lock task's refusal (BlockedAppActivity with the kiosk
    app block), exactly as from the shade. Its creator can't be checked beforehand (only `creatorPackage` is
    public).
  - The rare case where the lock was the root without knowing it (an earlier instance before a process restart: the
    finish is refused and `rootLeave` follows) brings Home over the app; the kid lands on Home.
  - We never cancel or snooze the app's notification: the app clears it when the conversation opens (Element X does
    [device]); a listener's cancel would fire its delete intent, unlike SystemUI's click.

## 2. Data

### 2.1 Sources

- **Missed calls, always**: ours (design 12) - `MissedCallNotifier`'s notice, not the listener. They show without
  notification access.
- **Messages**: notifications of the packages in `GlancePolicy.apps`, read by our listener (`BadgeListenerService`).
  A package that isn't ticked is never read past its package name. Without notification access: no message rows (and
  no tile, as step 4).
- **The policy** (`GlanceStore.policy`): from the `Ok` cached policy only - at process start and after every accepted
  sync (where `AppDisplay.refresh` runs). Pure `lockGlancePolicy(element)`:
  - key missing or JSON `null` (an older server) -> the server's own default: `{ELEMENT_X}`, sender only;
  - an object -> field by field: `apps` = the valid package names (`isValidPackageName`) in it, `show_text` true only
    for JSON `true`; nothing in it can fail the policy (raw `JsonElement`, like `app_display` and `music`);
  - anything else, or no `Ok` cache (corrupt, missing, the `LastEnforcedPlan` fallback) -> **unknown: no apps** -
    missed calls only, since the parent may have unticked something.

### 2.2 What the reader reads (`badges/GlanceReader.kt`, the only reader of names and text)

Per ticked notification, on the listener's main-thread callbacks (posted, removed, ranking update, connected - a
sweep of `activeNotifications`), each in its own try/catch (a bad notification is skipped; the log names the package
and the exception class):

- **Skipped** (pure `glanceCandidate`): our own package (and its `.debug`/release sibling), group summaries, ongoing
  or non-clearable ones, media notifications (`isMediaNotification`), `Ranking.getImportance() <= IMPORTANCE_MIN`,
  categories `call`, `alarm`, `stopwatch`, `progress`, `transport`, `navigation`, `service`, `sys`, `status`,
  `location_sharing`, `workout`, `promo`, `recommendation` (calls are design 17's; a missed VoIP call - `missed_call` -
  is a row).
- **Suspended** apps: for a listener targeting P+ a suspended app's notifications stay in `getActiveNotifications`
  and are never "removed"; the ranking update sets `Ranking.isSuspended()` [AOSP 16 NMS `notifyHiddenLocked`,
  `notifyRemovedLocked` skips `REASON_PACKAGE_SUSPENDED` for P+]. So the store keeps the flag per notification,
  refreshed on every ranking update (only for its own keys), and a suspended one is no row - a time rule, a
  disallowed app or a relock-blocked one hides itself, and comes back when unsuspended.
- **The level** (pure `glanceLevel`): not ticked -> not read; the notification's `visibility` or its channel's
  `lockscreenVisibility` (`Ranking.getChannel()`) is `VISIBILITY_SECRET` -> `APP_ONLY`; else `TEXT` when the parent's
  switch is on, else `SENDER`.
  - `VISIBILITY_PRIVATE` is not honoured: the parent's switch decides. Android's own lock-screen settings aren't read
    either - `Ranking.getLockscreenVisibilityOverride` folds them in [AOSP 16 `VisibilityExtractor`], and on these
    phones Android's keyguard is off and its settings out of reach.
- **Read at `SENDER`** (and `TEXT`):
  - MessagingStyle (`EXTRA_MESSAGES` non-empty): of each message bundle only `sender_person` (`Person.name`) or
    `sender` (CharSequence) - never MessagingStyle's parser (it copies the bodies); the count = bundles with a sender
    (the kid's own have none); the title = a group's `EXTRA_CONVERSATION_TITLE` (`EXTRA_IS_GROUP_CONVERSATION`), else
    the newest sender, else `EXTRA_CONVERSATION_TITLE`, else `EXTRA_TITLE`;
  - other styles: the title = `EXTRA_TITLE`; the count = `number` when > 0, else 1;
  - always: `key`, `postTime`, `when`, `getShortcutId()`, `contentIntent` (kept, never read) and its `isActivity()`.
- **Read at `TEXT` only**, inside one function `readTexts` that only the `TEXT` branch calls: the newest message's
  `text` from another sender (MessagingStyle) or `EXTRA_TEXT` (others); a group's text gets "Sender: " in front.
- `APP_ONLY` reads no title or text: the row says "Element X" + "2 nye varsler".
- **Never read**: `actions`, `RemoteInput`s, `deleteIntent`, `fullScreenIntent`, `publicVersion`, pictures, icons,
  `Person.key`/`uri`/`icon`, `EXTRA_BIG_TEXT`, `EXTRA_TEXT_LINES`, `extractMessagingStyleFromNotification`.
- **Cleaning** (pure `cleanGlanceText`): control and bidi-override characters out (U+202A-202E, U+2066-2069),
  whitespace collapsed, a title capped at 60 characters, a text at 200; empty = absent (then the app's label).
- **Redacted** notifications: on 15/16 a notification NMS marks sensitive (an OTP) reaches an untrusted listener with
  the app's label as title, "Sensitive notification content hidden" as text and an empty MessagingStyle sender
  [AOSP 16 `redactStatusBarNotification`]; the empty sender falls back to the app's label - nothing special.

### 2.3 Rows (pure `badges/GlanceRows.kt`)

- `glanceRows(notices, missed, policy, opened)`: one row per **conversation** = (package, `shortcutId` else the
  notification key); several notifications of one conversation add their counts, the newest gives title, text, time
  and intent. Missed-call rows join them; all **newest first** (`when`, else `postTime`; a missed contact's last
  call). The fit shows at most **3** (§1.2); the rest is the "more" count.
- `toString()` of every glance type is redacted ("GlanceRow(io.element.android.x)"), so a stray log line or crash
  report can't carry a name.

### 2.4 Missed calls

- `MissedCallNotice` gets `contacts: List<MissedContact(number, name, count, lastAtMs)>`, newest first (pure, from the
  same rows `missedCallNotice` already counts: phone-book contacts of `phoneBookView(effectiveState())`, unread, up to
  the pass's bound); `names` derives from it. One row per contact: the phone-book name, else the number.
- The plain notice (fail-closed, or unmanaged with our dialer role) is one row "N tapte anrop" with no names; its tap
  opens the call-log view (phone book, or the system dialer while unmanaged).
- **The glance shows exactly what our missed-call notification shows**: `MissedCallNotifier` exposes `current`
  (`lastPosted`, written on its thread) and listeners (posted to the main thread) - set on a post, cleared on every
  cancel. A new process runs one `recompute(mayPost = false)` from `Application.initRest` (`refreshAtStart`): a
  notification still on screen is re-posted silently and known again.
- Unmanaged calls without our dialer role: Telecom's own notification, never read - no missed-call rows (that phone's
  calls aren't ours).

### 2.5 Where it lives

- **Memory only**, in `badges/GlanceStore.kt` (main thread): per notification key the facts, the suspended flag and the
  content intent; the opened marks; the policy. Nothing glance-related in any preferences file, DE or CE, in the
  status report, a DTO, the crash report or a log line beyond package names and counts (`LockGlanceGuardTest`).
- Rebuilt from `activeNotifications` at every listener connect; cleared at disconnect. A policy change re-sweeps (so
  switching the text off drops every text at once, and unticking an app its rows).
- The missed-call names are the phone book's (the policy's call rules, already on the phone).

### 2.6 When a row goes

- its notification is removed - read in the app, swiped in the shade while unlocked, cancelled by the app or by the
  notification rule (step 11) - at once, live on a shown glance;
- it was **opened through the glance**: hidden until its conversation is posted again with a newer `postTime` (a new
  message); a process restart forgets the mark;
- its app is suspended (time rule, disallowed, relock-blocked) - back when unsuspended;
- the parent unticks the app (next sync), or the listener loses access;
- missed calls: when our notification goes - the phone book or a contact sheet opened, a swipe, a call back.

Not by unlocking, by being seen on the glance, or by a screen-off: the rows stay until dealt with, like the badges.

## 3. Policy, server and PWA

### 3.1 Data (migration `00NN_lock_glance.sql`)

`NN` = the next number after the highest on `main` **after the final rebase** (0065 today; never 0058-0060; another
worktree may land one first).
```sql
CREATE TABLE device_lock_glance_apps (
    device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    package_name TEXT NOT NULL,
    PRIMARY KEY (device_id, package_name)
);
ALTER TABLE device_policy ADD COLUMN lock_glance_text INTEGER NOT NULL DEFAULT 0;
INSERT INTO device_lock_glance_apps (device_id, package_name) SELECT id, 'io.element.android.x' FROM devices;
CREATE TRIGGER devices_get_lock_glance_default AFTER INSERT ON devices
BEGIN
    INSERT INTO device_lock_glance_apps (device_id, package_name) VALUES (NEW.id, 'io.element.android.x');
END;
```
A table, not a JSON column: no corrupt state to handle, one row per tick (like `device_tracked_apps`); the trigger
follows `devices_get_builtin_wallpapers`, so every path that creates a device gets the default.

### 3.2 Policy JSON (server and launcher in the same commit)

- `PolicyResponse.lock_glance = {apps: [..], show_text: bool}`, always sent: `apps` sorted, only valid package names
  (`is_package_name`), `show_text` from `lock_glance_text`. A failing query sends `apps: []` and logs - fewer rows,
  never a 500.
- Server: `policy_json_keys_snapshot` gets `lock_glance` (non-null) and its two keys. Launcher:
  `PolicyResponse.lockGlance: JsonElement? = null`, decoded by `lockGlancePolicy`; `PolicyResponseCompatTest` decodes
  the server's literal, a missing key, `null`, an array and a bad `apps` entry without failing the policy.
- Deploy order is free: an older server gives the same default as a new one; an older launcher ignores the key.

### 3.3 PWA (`device_detail.html`, the Apps card)

A subsection `<h3 id="lock-screen">On the lock screen</h3>` after the allow list, before "Names and icons":
- Text: "When the phone is locked, the power button first shows the clock, what's playing and the newest
  notifications: missed calls always, and messages from the apps ticked here. Tapping one asks for the kid's PIN and
  then opens it. Nothing can be answered, marked as read or dismissed from the lock screen, and it can't be
  screenshotted. Each choice saves itself immediately."
- Notes (error style, pure `lock_glance::notes`): no kid PIN ("There is no lock screen without a kid PIN - see
  Screen lock"); `notification_listener_enabled` false ("... shows missed calls only; see Launcher for the fix"); a
  reporting launcher without `lock_glance_v1` ("update the launcher").
- The switch: `POST /devices/{id}/lock-screen`, checkbox `show_text`, auto-saving: "Show the message text too, not
  just who it's from - anyone holding the locked phone can read it."
- One auto-saving mini-form per allowed app (the `display_forms` set: allowed, a valid package, not the launcher, not
  Play core): `POST /devices/{id}/lock-screen/apps` with `package_name` and the checkbox `on`.
- Both (`handlers/lock_glance.rs`, full admin auth, CSRF as every form): 404 unknown device; 400 for a package that
  isn't a package name, or Play core (nothing written); 500 on a DB error (nothing written); a
  security event (`lock_screen_text_changed` / `lock_screen_app_changed`, "device 3: io.element.android.x on the lock
  screen: on"); `command_notify`; **redirect to `/devices/{id}` without a fragment** - the same path, so
  `scroll-restore.js` puts the parent back where they were in a long app list (never a jump to the top).
- The Launcher card's listener warning also names "the notifications on the lock screen"; the Screen lock card's text
  says the lock's first page shows the clock, music and new missed calls and messages (Apps -> On the lock screen).

### 3.4 Status

Capability `lock_glance_v1` (launcher, `STATUS_CAPABILITIES`) and nothing else: no counts, names or rows ever go to the
server. The listener's state is already reported (`notification_listener_enabled`).

## 4. States

| State | The glance |
|---|---|
| Kiosk on / off | the same page; the open differs only as in §1.4 |
| PIN lock off (no kid PIN, unmanaged, an Android credential, the crash guard) | no lock, no glance - Home as today (with an Android credential Android's own keyguard and its settings apply, untouched) |
| A time rule (bedtime, school, budget) | the glance is over the time-rule screen as the lock is today; suspended apps have no rows and no tile; a row opens nothing (no target taken); after the code the time-rule screen |
| An incoming phone call | our call screen over the lock (unchanged); the stage is kept; a missed call appears as a row at once |
| A VoIP ring | the ring card over the glance (design 17, unchanged); Element X's call notification is never a row |
| Music | the tile per §1.1; full or compact per the fit |
| `ForeignAccess` | unchanged: the keypad stage's "Foreldrekode" refuses as in 24b. The glance adds nothing for such a service: an accessibility service already receives notifications through accessibility events, and the rows are data-sensitive (a non-tool service can't read them off the lock) |
| Emergency call | "Nødsamtale" on the glance is the lock's own (confirm, the 2-minute flow exemption) |
| TalkBack | §1.2 |
| `FLAG_SECURE` | always on the lock's window (§1.2) |
| 320 / 360 / 411 dp | §1.2 fit and widths; font scale 1.3 shrinks to fewer rows (measured, not assumed) |
| No notification access | missed-call rows only, no tile; PWA notes in the subsection and the Launcher card |
| Boot, process restart | the listener binds later: rows appear live; missed calls from `refreshAtStart`; opened marks forgotten |
| Bad kid-PIN hash, a PIN wait | the glance as usual; the keypad shows "only the parent code" / the wait |
| Calls unmanaged, no dialer role of ours | no missed-call rows (§2.4) |

## 5. Privacy and safety

- **What a stranger holding the locked phone sees**: the time, what's playing, and per row the app (and its icon),
  the sender's or group's name, the count and the time; the text only if the parent switched it on; missed calls by
  the phone-book name. Less than Android's own lock screen shows by default (it shows the content); the text only
  when the parent chooses it.
- **What they can do**: play, pause and skip the music; "Lås opp" and a row both only reach the keypad; "Nødsamtale".
  Nothing opens without the kid's PIN or the parent PIN.
- **What never runs from the lock**: no notification action (reply, mark as read, archive, call back), no remote
  input, no dismiss or snooze, no `setNotificationsShown`, no content intent before the code; rows can't be swiped
  away (a stranger can't hide a message from the kid). The only send is §1.4's, after the code, from the unlock path.
- **What never leaves memory**: §2.5. The server learns nothing new (§3.4).
- **Guarded by** `LockGlanceGuardTest` (§7, steps 2 and 3).

## 6. Defaults chosen (veto if wrong)

- No glance page without a kid PIN (there is nothing to unlock; the phone opens to Home as today).
- The compact tile (play/pause only) when rows compete for the height; previous/next come back when there is room.
- Up to 3 rows plus a "more" line; rows are grouped per conversation.
- `FLAG_SECURE` on the whole lock, always.
- An app's `VISIBILITY_SECRET` shows its rows as "app + count" only; `VISIBILITY_PRIVATE` is overruled by the parent's
  switch.
- No row opens anything while a time rule locks the phone; the time-rule screen lists what the kid may open.
- An unknown policy (corrupt cache) shows missed calls only; an older server's missing key means Element X, sender only.
- Names are what the app gives (an Element X display name); phone-book names for known Element contacts (design 15's
  MXID mapping) would be a follow-up, not part of this design.

## 7. Steps (one implementer; each green on its own, with its tests)

1. **Server + the contract** (both directories, one commit):
   - migration, `src/lock_glance.rs` (validation, `notes`, `log_line`), `PolicyResponse.lock_glance`, `build_policy`;
   - `handlers/lock_glance.rs` + routes in `build_admin_router`, the template subsection and texts;
   - launcher: `PolicyResponse.lockGlance`, `lockGlancePolicy` (pure, in `badges/GlanceRows.kt`) and the compat test.
   - Tests: `lock_glance::tests` (package rules, every note, the log line); `src/tests/lock_glance.rs` - the migration
     gives existing devices Element X and `lock_glance_text = 0`, a new device gets it by the trigger, a device delete
     cascades, the policy carries sorted valid apps and the switch, a dropped table gives `apps: []` with the rest of
     the policy intact, both routes on/off/idempotent, 404/400/500 cases write nothing, an unauthenticated POST writes
     nothing, the security events, the nudge, the redirect is exactly `/devices/{id}`, the page lists the allowed apps
     with their state and each note; `policy_json_keys_snapshot`; launcher `PolicyResponseCompatTest`.
   - Checks: the PWA on a phone - tick/untick and the switch keep the place (no jump), the notes appear.
2. **Launcher data, no UI change**:
   - `badges/GlanceReader.kt`, `badges/GlanceStore.kt`, `badges/GlanceRows.kt` (policy, `glanceLevel`,
     `glanceCandidate`, `cleanGlanceText`, `glanceRows`, opened marks); `BadgeListenerService` forwards posted,
     removed, `onNotificationRankingUpdate`, connect and disconnect to the reader - and stays free of extras, text,
     title and name (`ElementRoomsTest`'s scan of the listener stays as it is);
   - the policy refresh at process start and after each accepted sync; a policy change re-sweeps;
   - `MissedCallNotice.contacts`, `MissedCallNotifier.current` + listeners + `refreshAtStart`.
   - Tests: `GlanceRowsTest` (policy decode cases; levels incl. SECRET; every skip rule; suspended; titles for DM,
     group, other styles and an empty sender; counts; text only at TEXT with the group prefix; grouping by shortcut
     id; newest-first with missed rows; opened marks hidden until a newer post; cleaning; redacted `toString`);
     `MissedCallNoticeTest` (per-contact rows, number fallback, plain notice); `LockGlanceGuardTest` part 1 -
     `GlanceReader`'s imports, identifiers and bundle reads allowlisted like `ElementRoomsTest` does for
     `ElementDmReader`, `"text"`/`EXTRA_TEXT` read only inside `readTexts`, `readTexts` called only behind
     `GlanceLevel.TEXT`, none of §2.2's never-read members, no `cancelNotification`/`snoozeNotification`/
     `setNotificationsShown`, no `Log.` but the package + exception-class lines; no glance facts or row type in
     `server/`, `push/` or a DTO (the sync only calls `GlanceStore.refreshPolicy`).
3. **Launcher UI and the open**:
   - stages and `glanceTile`, `glanceFit`, `glanceOpenStep` (pure, `lock/LockGlance.kt`); `view_lock_glance.xml`,
     `view_lock_glance_row.xml`, the compact tile; `GlancePanel`, `MusicLockPanel` reduced, `PinLockActivity` (glance,
     keypad stage, `FLAG_SECURE`, the unlock path), `GlanceOpener`, `VisibleSender`; strings nb + en (below);
     capability `lock_glance_v1`;
   - `scripts/smoke-test.sh`: tap `:id/lock_glance_unlock` before `enter_pin` (the keypad is hidden from UI dumps
     while the glance covers it); a "missed call shows on the glance" step after the unanswered call (the row's
     contact name in the dump); `docs/testing/emulator.md` §6c: screenshots via the emulator console.
   - Tests: `LockGlanceTest` (stages: GLANCE at every lock-up, keypad by "Lås opp" or a row, the target dropped by the
     arrow and a lock-up and kept when coming back with the screen on, no target under a time rule; the tile rule;
     `glanceFit`'s candidates; `glanceOpenStep`'s table); `LockGlanceLayoutTest` (§1.2's expected fits from the XML
     at 473 and 497 dp; widths 320/360/411; fixed square round controls; the glance's touchables are exactly the
     tile controls, the rows container's rows, "Lås opp" and "Nødsamtale"; the root has no TalkBack node; "Nødsamtale"
     last); `PinLockLayoutTest` (no clock or date; keys >= 52 dp with and without the compact row); `LockMediaGuardTest`
     (MusicLockPanel still opens nothing; its touchables now include the compact tile's play); `LockGlanceGuardTest`
     part 2 (`GlancePanel` opens nothing; `GlanceOpener` has exactly one `.send(` and two `startActivity` and is
     called only from `PinLockActivity.go`; `pendingOpen` is set only in `unlock`; `FLAG_SECURE` is added before
     `setContentView`; the row views are data-sensitive); `VoipCallGuardTest` green after `VisibleSender`.
   - Checks: §9.
4. **Docs**: both `CLAUDE.md`s (the listener now has a reader of names and text; the lock's stages; the PWA
   subsection; the migration), `PLAN.md`, this file's implementation status.

**Strings** (nb / en): `glance_messages` plural "%d melding(er)" / "%d message(s)"; `glance_notices` plural "%d nytt
varsel / %d nye varsler" / "%d new notification(s)"; `glance_missed` "Tapt anrop" / "Missed call";
`glance_missed_many` plural "%d tapte anrop" / "%d missed calls"; `glance_more` plural "+ %d varsel til / + %d
varsler til" / "+ %d more"; `glance_yesterday` "i går" / "yesterday"; `glance_open_action` "Lås opp og åpne" / "Unlock
and open"; `glance_missed_action` "Lås opp og se tapte anrop" / "Unlock and see missed calls"; `glance_missed_from`
"Tapt anrop fra %s" / "Missed call from %s"; `pin_lock_prompt_open` "Skriv koden for å åpne %s" / "Enter your code to
open %s"; `pin_lock_prompt_missed` "Skriv koden for å se tapte anrop" / "Enter your code to see missed calls".
"Lås opp" and "Nødsamtale" stay `lock_media_unlock` and `pin_lock_emergency`.

## 8. Platform notes

- [AOSP 14/15/16] A PendingIntent sender's own visibility is what lets it start the activity (§1.4); the creator's
  privileges (Element X's) are not counted unless it opted in, and aren't needed.
- [AOSP 16] Suspension hides notifications through the ranking for P+ listeners (§2.2); sensitive-content redaction
  for untrusted listeners (§2.2).
- [AOSP 16] `Ranking.getChannel()`, `isSuspended()` and `getImportance()` are public; `hasSensitiveContent()` is a
  system API (not used).
- [device] Element X's notification shape: one notification per room with the room ID as tag, MessagingStyle with the
  senders' display names, a room's name as conversation title, a summary per account; its `contentIntent` is an
  activity that opens the room; it clears the room's notification when the room opens.
- [device] The send from the visible lock right after Home's start (kiosk on) and before its finish brings Element X
  up inside lock task on 34-36; with the kiosk off over another app.
- [device] Whether the Jelly Star's notification assistant marks anything as sensitive (the redacted case).

## 9. Device checks (emulator 320x569 dp nb + en, then the Jelly Star)

- **The page**: screen off/on in both kiosk modes, gesture and 3-button navigation, with and without music, with 0,
  1, 2, 3 and 5 rows: what the fit picks (§1.2's table), nothing clipped or squeezed, round buttons round, at 360 and
  411 dp too, and at font scale 1.3; the clock and date gone on the keypad stage, keys >= 52 dp.
- **Element X**: a DM and a group from another account - sender only ("Pappa", "Element X · 2 meldinger"), then the
  switch on (the newest text, "Pappa: ..." in the group), off again (texts gone at the next sync without a restart);
  untick Element X (rows gone), tick again. A DM read on another device: the row goes.
- **The tap**: a row -> keypad with "Skriv koden for å åpne Element X" -> right code -> the room opens (kiosk on and
  off; Back from it); the parent code instead of the kid's; a wrong code (stays); the arrow back (plain prompt); a
  screen-off on the keypad (glance again, the target gone); the row's notification removed before the code (the room
  still opens); Element X suspended meanwhile (nothing opens); under a bedtime rule (no hint, the time-rule screen after
  the code); a missed-call row -> the contact's sheet, its row gone afterwards.
- **Missed calls**: one, then two from Pappa and one from Mamma (rows per contact, newest first), calling back (row
  gone), after a launcher force-stop (still there via `refreshAtStart`); calls unmanaged with our dialer role (plain
  row).
- **States**: an incoming call and a VoIP ring over the glance; Nødsamtale from it; an alarm; a reboot (rows appear when
  the listener binds); no listener access (missed calls only, the PWA notes); `ForeignAccess` (Foreldrekode refused as
  in 24b, the glance unchanged).
- **TalkBack**: the order, one node per row with its action label, the keypad unreachable while covered; a UI dump
  lists the rows (UiAutomation as a tool).
- **FLAG_SECURE**: `screencap`, power + volume-down and a screen recording show the lock black; the emulator console
  screenshot shows it [device].
- **Nothing runs from the lock**: long-press, swipe and double-tap on rows do nothing; Element X's quick reply never
  appears; the notification is still unread in Element X until the room opens.

## 10. Open questions

None that a default can't settle - §6 lists the defaults this spec chose.

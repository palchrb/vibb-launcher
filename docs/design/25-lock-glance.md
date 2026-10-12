# 25 - The lock's glance page (buildable spec)

Status: spec, architect 2026-10-11, on `main` at 17b89834 (music step 4 with its fix round and design 24b are in).
Built 2026-10-12 (see "Implementation status" at the end); not device-tested. Mockup:
`docs/design/mockups/vibb-music/LockGlance.dc.html` (three 320x568 screens: A the glance page while music plays, B
without music, C the keypad stage after a row tap - tap a row on A or B and C's prompt follows).

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
- **The music tile** (pure `glanceTile(session)`; **user decision 2026-10-12**, bug 25b-G - was "appears while
  playing, stays paused, none for a paused session at a lock-up"): it shows whenever the music app has a session
  (`shownSession`, step 4), **playing or paused**, also at a lock-up, and goes when the session ends.
  `session == null` (no listener access, the app suspended or gone) = no tile. Its controls are step 4's:
  play/pause, previous, next. The full tile (with previous/next) is preferred; the compact one (play/pause only,
  §1.2) only when the rows don't fit beside the full one (`glanceFit`'s order, unchanged). Nothing opens the player.
- **The keypad stage** always has the back arrow (to the glance page), never the clock or the date (the glance page
  has them; mockup C). **No music** (user decision 2026-10-12, 25b-G: "skal være kjedelig" - everything
  interesting is on the glance page): step 4's compact row (`view_lock_media_row.xml`) is gone. The prompt: the usual
  wait/wrong/checking/unusable texts first; else with a target "Skriv koden for å åpne Element X" (app label,
  `AppDisplay.shownLabel`) or "Skriv koden for å se tapte anrop"; else "Skriv inn koden din". The prompt row already
  takes two lines between the arrow's columns (qa-21-step4-code L5), so the hint costs no height.
  - `pin_clock`/`pin_date` leave `activity_pin_lock.xml`; `PinKeypadLayout`'s date/clock compact steps go
    (`finalKeySizePx(.., compactExhausted = true)` always: keys shrink to the 32 dp floor rather than clip).
  - The arrow is always visible, so the prompt's side margins are static (`setMediaKeypad` goes).
  - Budget (`PinLockLayoutTest`, 320x569 dp, status bar 48 dp, nav 24/48 dp): keys >= 56 dp (the compact row's
    64 dp went with 25b-G).

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
- `music/MusicLockPanel.kt`: binds the full and the compact tile while a session is live (the keypad's compact row
  went with 25b-G); no stage logic any more. `LockMediaGuardTest` keeps it opening nothing.
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

## Design QA (2026-10-11)

QA, 2026-10-11, against b1be8ace. Read:
- this design, its mockup `LockGlance.dc.html`, design 21 §3 (step 4), design 24b, and the cited parts of 12, 15, 16 and
  17; the root, launcher and server `CLAUDE.md`s;
- launcher: `PinLockActivity`, `PinLockRuntime` (`dispatch`, `unlocked`, `afterUnlock`, `guardTripped`), `LockTaskRoot`,
  `HomeFront`, `LockMedia`, `MusicLockPanel`, `activity_pin_lock.xml`, `view_lock_media.xml`, `BadgeListenerService`,
  `ElementDmReader`, `MissedCallNotice`, `MissedCallNotifier`, `PhoneBookActivity.missedCallsIntent`, `VoipCalls.send`,
  the options in `MusicSessions`, `KidAvatars.renderAppIcon`, `decideTimeLock`, `storeAcceptedPolicy`; and the tests
  `LockMediaTest` (with `LockMediaGuardTest` and `LockMediaLayoutTest`), `PinLockLayoutTest` and `KidHeaderLayoutTest`;
- server: `build_policy`, `PolicyResponse`, `policy_json_keys_snapshot`, `app_display_forms` and the Apps card, the
  migrations (the highest is 0064), `record_security_event`, and the `scroll-restore.js` rule;
- AOSP `android14/15/16-release`: `BackgroundActivityStartController`, `PendingIntentRecord`, `PendingIntent`,
  `NotificationManagerService` (posting, hiding, redaction), `NotificationAttentionHelper`, `UiAutomation` and
  `UiAutomationConnection`.

**Verdict:** one implementer can build it, and I found no way past the code. There are three Mediums:
- a suspended app's new notifications become rows;
- the open runs inside the unlock dispatch without a guard;
- under the screen-time budget a row never opens the conversation, although the app stays usable. This one needs a
  user decision (Q1).

The rest is Low: layout numbers, the PWA on phones without an allowlist, the meaning of "sender only", missed-call
details, tests and device checks.

**Checked and holds:**
- **The send.** [AOSP 14] `checkPiBackgroundActivityStart` allows a sender that has a visible window. [AOSP 15]
  `checkBackgroundActivityStartAllowedBySender` does the same, and HOME counts as app-switch-allowed. [AOSP 16]
  `checkBackgroundActivityStartAllowedByRealCaller` runs only the visible checks under `ALLOW_IF_VISIBLE`, from
  `VisibleActivityProcessTracker.hasVisibleActivity(uid)`. In all three, the device-owner and HOME exemptions belong to
  the caller (here the creator). ASM [15/16] lets a NEW_TASK start with `BAL_ALLOW_VISIBLE_WINDOW` through.
  - One nuance: after `HomeFront.bring`, Home (an opaque activity) can already cover the lock. Home has the same uid,
    so the uid still has a visible activity. The claim holds for "our uid", not "the lock's window".
- **Nothing opens before the code.**
  - `pendingOpen` is set only in `unlock`, and `go` checks `UNLOCKED`.
  - An `unlocked` that the runtime refuses (a screen-off during the check) still runs `leave`, but with mode LOCKED, so
    nothing opens.
  - The switch-off, crash-guard and `onResume` leaves have no `pendingOpen`.
  - `wasRoot` must be read before `stopOwnLockTask()`, as the spec says: `stopLockTaskQuietly` clears
    `startedLockTask`.
- **Lock task.**
  - A PendingIntent send has no source record, so the start is a NEW_TASK start.
  - Kiosk on: only a package that lock task permits can start. An allowlisted task joins the lock task. Any other
    package is refused, or gets BlockedAppActivity when the block bit is on.
  - In the rare root case, `stopLockTask` clears the joined task (`clearLockedTask` -> `performClearTaskForReuse`), so
    the kid lands on Home as stated.
  - Kiosk off: the content intent runs as the ticked app's own identity. It reaches only what that app could start
    itself after the unlock. A hidden or suspended app doesn't start.
  - A trampoline into another app gains nothing that SystemUI's click wouldn't.
- **Redaction** [AOSP 16 `redactStatusBarNotification`]: the title is the app's label, the text is the redacted
  string, and the MessagingStyle's user and its only sender are an empty `Person`. The actions stay but are retitled,
  and the content intent survives. "Nothing special" holds.
- **The two tool claims.** `UiAutomationConnection` sets `setAccessibilityTool(true)`, so UI dumps see the
  data-sensitive rows. §4's `ForeignAccess` row holds: with no Android keyguard, `isDeviceLocked` is false, and
  `NotificationAttentionHelper`'s accessibility event carries the full notification today.
- **What can be checked on a PendingIntent.** `PendingIntent.getIntent()` is hidden and `queryIntentComponents` is a
  system API, so the target can't be checked. `isActivity()` and `getCreatorPackage()` are public.
- **Server.** 0065 is the next migration on `main` (0058-0060 stay skipped). A redirect to `/devices/{id}` without a
  fragment restores the scroll position. The trigger follows `devices_get_builtin_wallpapers`, and foreign keys are
  on. `build_policy` already treats cosmetic parts as "an empty list and a log line" (wallpapers, app display).
- **Widths.**
  - The tile controls are 204 dp within the full tile's 260 dp.
  - The compact tile's fixed parts are 144 dp, leaving 144 dp for text.
  - A row's fixed parts leave >= 120 dp at 320 dp, also with the widest time label ("yesterday", about 60 dp at 12 sp,
    so about 143 dp fixed) and at font scale 1.3.

### Medium

1. **Medium - a notification posted while its app is suspended becomes a row.**
   - Where: §2.2 "Suspended" says the flag is "refreshed on every ranking update". §2.6 relies on it.
   - Why [AOSP 16]:
     - NMS calls `r.setHidden(isPackagePausedOrSuspended(..))` at enqueue.
     - `prepareNotifyPostedLocked` skips only listeners that target below P. Our listener gets `onNotificationPosted`
       for the hidden notification, with `isSuspended()` in the ranking map of that same post. No ranking update
       follows.
     - `getActiveNotificationsFromListener` (the connect sweep) returns hidden records too.
     - Today `BadgeListenerService` overrides only the one-argument `onNotificationPosted`.
   - Scenario:
     1. Bedtime suspends Element X at 21:00.
     2. At 21:30 Pappa writes. FCM wakes Element X, and Element X posts.
     3. Until the rule ends, the bedtime lock shows "Pappa - Element X · 1 melding", with the text if the switch is on.
     4. A process restart during bedtime does the same for every message already there.
   - Fix:
     - The reader reads the key's `Ranking` on every post (`onNotificationPosted(sbn, rankingMap)` or
       `currentRanking`), in every sweep and on every ranking update, and keeps `isSuspended` with the facts.
     - No ranking found counts as suspended (fail closed).
     - Tests: a suspended post makes no row; the unsuspend ranking update makes the row.
     - Device check: a message during bedtime shows no row, and it appears when the rule ends.
2. **Medium - the open runs inside the unlock dispatch, and a throw there crashes the lock and counts for the crash guard.**
   - Where: §1.4 `go(plan)` and the `GlanceOpener` table. §1.3 wraps only the render.
   - The call path:
     - `go()` runs from `PinLockActivity.modeListener`.
     - `PinLockRuntime.dispatch(Unlocked)` calls that listener from the PIN check's coroutine, on the main thread.
     - It runs after `HomeFront.bring`, before `finishAndRemoveTask()`, and before `afterUnlock`.
   - Scenario:
     1. The parent ticked an app whose content intent is a broadcast and which has no launcher activity, so
        `getLaunchIntentForPackage` is null. The table has no row for that case. Or the launch throws
        (`ActivityNotFoundException`, `SecurityException`).
     2. The kid types the right code, and the exception leaves `go()`.
     3. The process dies with `PinLockActivity.instances > 0`, so the crash guard counts it, and `afterUnlock` never
        ran.
     4. The new process starts LOCKED. Three such unlocks within 2 minutes trip the guard: the lock is off until a sync
        re-arms it, 10 minutes later at the earliest.
   - Other glance code has the same exposure:
     - the reader catches `Exception` only;
     - the icon render runs on a background thread (#6);
     - the `MissedCallNotifier` listeners run on the main thread.
   - Fix:
     - `GlanceOpener.open` never throws. Every step is caught (`Throwable`, nothing rethrown) and logged by exception
       class. Add a table row: "no launch intent -> nothing".
     - Every glance entry point catches the same way: the reader per notification, the store and notifier listeners,
       the icon job and the panel render.
     - `LockGlanceGuardTest` checks each entry point is wrapped, and `glanceOpenStep` tests a null launch intent.
3. **Medium (user decision, Q1) - under the screen-time budget a row never opens the conversation, although the app
   stays usable.**
   - Where: §1.4 "Not taken while a time rule locks the phone", §4's time-rule row, and §6's default 6.
   - What stays usable (`decideTimeLock`):
     - a used-up budget gives `SCREEN_TIME` with `usableApps = messagingApps + musicApps`;
     - a school or bedtime rule keeps its `exemptApps`.
     These apps aren't suspended, so their rows show.
   - Scenario:
     1. It is evening and the budget is used up, the usual state after school.
     2. Pappa writes in Element X, and the kid taps the row. The prompt is the plain one.
     3. After the code comes the time-rule screen. The kid taps Element X there and gets the room list, not the
        conversation.
     4. The user's decision ("after the right code the conversation ... opens directly") fails in the state where
        messages matter most.
     5. A missed call under a rule that allows calls goes the same way, although the time-rule screen has the phone
        book.
   - Fix (recommended):
     - Take the target when the row's package is in the time lock's usable apps. Take a missed-call row's target when
       the rule allows calls.
     - After the code the time-rule screen comes first (`afterUnlock`). Then the content intent is sent over it from our
       visible window, so Back lands on the time-rule screen. For example, `afterUnlock` hands the target to
       `LockActivity` in memory, and the lock doesn't open anything itself in that case.
     - Keep "nothing" for packages the rule suspends. They have no rows anyway (#1).

### Low

4. **Low - the expected fits assume 56 dp rows, but the spec's row is taller.**
   - Where: §1.2. A row is "min 56, padding 10/12 dp", with a 15 sp title and a 13 sp subtitle.
   - The heights:
     - in `LockMediaLayoutTest`'s model a row is 21 + 18.2 + 20 = 59.2 dp;
     - with Nunito's line of about 1.36 em it is about 58 dp on the phone;
     - the spacing above the "more" line isn't given (mockup B puts it inside the 8 dp gap).
   - Scenario:
     - "3 rows + more, no music -> 1 (464)" needs 473.6 dp in the model, or 481.6 dp with the mockup's gap. That is
       more than 473 dp, so `LockGlanceLayoutTest` as specified fails, or gets loosened.
     - "music + 3 rows -> 5 with 2 rows + more" is 466 dp, or 474 dp with the gap. With the gap it falls to 1 row.
     - The others hold with 3-7 dp less margin. Music + 1 row with 24 dp navigation is 495.8 of 497 dp.
   - Fix:
     - Give rows `paddingTop`/`paddingBottom` of 8 dp: 55.2 dp in the model and about 54 dp on the phone, so the 56 dp
       minimum holds.
     - The "more" line gets `minHeight` 24 dp instead of a fixed 24 dp (that clips 13 sp text above font scale
       about 1.3), and 0 dp above it.
     - The test model also reads `paddingVertical`.
     - Say where `availableDp` comes from: the glance root's height after `KidInsets` padding. Re-run the fit on a
       layout change (gesture <-> 3-button navigation), and never before the first layout.
5. **Low - the keypad prompt can take three or more lines.**
   - Where: §1.1, "the hint costs no height". The arrow is always there now, so the prompt has 288 - 2 x 48 = 192 dp.
   - The label is long-capable: `AppDisplay.shownLabel` is the parent's label, up to 100 characters (the server's
     clip).
   - Measured with qa-21-step4-code L5's rate (28 characters = 213 dp at 16 sp):
     - "Skriv koden for å åpne Meldinger fra familien" is about 340 dp: two lines at font scale 1.0, three at 1.3;
     - "Only the parent code works right now" (283 dp) takes three lines from about 1.4.
   - Scenario: with the compact music row at font scale 1.3, the keys drop below 52 dp, towards the 32 dp floor.
   - Fix:
     - Give the prompt `maxLines="2"` and `ellipsize="end"`.
     - Cap the label in the hint before formatting, for example 20 characters plus "…".
     - `PinLockLayoutTest` keeps counting two lines.
6. **Low - the row icon is specified as `renderAppIcon`, which may only run off the main thread.**
   - Where: §1.2 message row. `KidAvatars.renderAppIcon` is documented "off the main thread only".
   - It is keyed by a serialized `AppInfo` key and the size. 36 dp isn't Home's size, so Home's cache never hits.
   - Mockups A and B draw a rounded square; Home's icon is a circle.
   - Scenario: the lock's render (main thread) loads an adaptive icon and runs a palette pass at a lock-up with new
     rows. That is jank on the lock, and one more crash path (#2).
   - Fix:
     - The panel binds `cachedAppIcon`, with a placeholder tile when nothing is cached.
     - One background job (wrapped as in #2) renders the missing icons and re-binds.
     - Settle the shape: Home's circle is fine; update the mockup.
7. **Low - on a phone without an app allowlist the parent can't untick Element X.**
   - Where: §3.3, "the `display_forms` set: allowed ...".
   - `app_display_forms` filters on `a.checked`. With `allowlist_json` NULL (apps unmanaged) no app is checked. A
     calls-only phone has no allowlist but can still have a kid PIN, and so the lock.
   - Scenario:
     - A calls-only phone with a kid PIN gets the migration's Element X row, and its senders' names show on the lock.
     - The subsection is empty, so there is nothing to untick.
     - Also: an app the parent later disallows leaves the list but stays ticked. That is harmless, but invisible.
   - Fix:
     - With an allowlist, list the allowed apps. Without one, list every app the phone reports.
     - Always add every ticked package not in that list, labelled "not allowed - ticked", so any tick can be removed.
8. **Low - "sender only" means the title, and outside MessagingStyle the title is often the content.**
   - Where: §2.2, "other styles: the title = `EXTRA_TITLE`" at `SENDER`.
   - The PWA offers every allowed app, and what the title holds varies:
     - Gmail's title is the sender;
     - a calendar's is the event;
     - a school app's may be "Karakter i matte: 3";
     - a game's is a promotion.
   - Scenario: the parent ticks the school app to see messages. With the text switch off, the lock shows grades to
     anyone holding the phone.
   - Fix:
     - At `SENDER`, a notification that isn't MessagingStyle shows its title only when it has `CATEGORY_MESSAGE`,
       `CATEGORY_EMAIL` or `CATEGORY_SOCIAL`, or a `Person` in `EXTRA_PEOPLE_LIST`. Otherwise it shows as `APP_ONLY`
       ("<app> · N nye varsler").
     - The PWA text says:
       - names show to anyone holding the phone even with the switch off, missed-call names included;
       - with the switch on, a ticked SMS app's login codes show too.
9. **Low - missed-call rows: when they go, and what the type prints.**
   - **When `current` clears.**
     - `MissedCallNotifier.swiped` sets `lastPosted = null` without a cancel when the swipe covered the shown range.
     - A notification snoozed from the shade, or cancelled by the system, never reaches our code.
     - So `current` must also clear on the swipe and notify its listeners. With listener access, the listener seeing
       our own id 1006 removed can clear it too.
   - **One tap clears every missed-call row.**
     - A row's tap opens the phone book or a sheet, and `PhoneBookActivity`'s resume `dismiss`es the whole notification
       and marks its whole range read.
     - So every missed-call row goes, not only the tapped one (§9 says "its row gone afterwards").
     - That is right for design 12; state it.
   - **What the type prints.** `MissedCallNotice` and the new `MissedContact` are data classes, and their `toString()`
     carries names and numbers. They now live in the lock's panel, so redact them like the glance types (§2.3).
10. **Low - "its creator can't be checked" should read "its target can't be checked".**
    - Where: §1.4. `getCreatorPackage()` is public. What can't be checked is the intent inside the PendingIntent.
    - A notification may carry a PendingIntent another app created: one it was handed, or a notification delegate's.
      The send then runs as that creator, with our visibility.
    - That is not a bypass (the kiosk and suspension still apply, and SystemUI's click would send it too), but it is
      cheap to close.
    - Fix: send only when `contentIntent.creatorPackage == sbn.packageName`; otherwise use the launch intent. Test it in
      `glanceOpenStep`.
11. **Low - after the launcher update the cached policy has no `lock_glance`, which reads as "an older server".**
    - Why: `storeAcceptedPolicy` stores the DTO re-encoded by the build that stored it, so a cache written by a pre-25
      build never has the key.
    - Scenario:
      1. The server is updated first, and the parent unticks Element X.
      2. At the launcher update, the first start reads the old cache: Element X, sender only.
      3. That lasts until the first sync: seconds to minutes, longer offline.
    - Fix: a key missing from the cached policy means unknown (missed calls only). A key missing from a fresh policy
      means the older server's default; the store keeps that in memory.
12. **Low - the test list, the steps and the device checks.**
    - **Tests that break and aren't named in §7:**
      - `LockMediaLayoutTest`: it parses `view_lock_media.xml`. It also pins Home's music card and the keypad's compact
        row at 320/360/411 dp; move those into `LockGlanceLayoutTest`.
      - `LockMediaTest`'s stage tests (MEDIA only while playing).
      - `KidHeaderLayoutTest`'s `allScreens` (`view_lock_media`).
      - `PinLockLayoutTest`'s "the clock in the layout matches PinKeypadLayout".
    - **Missing tests:** #1's suspended post, #2's throwing opener, #10's creator check, and `current` clearing on a
      swipe (#9).
    - **Step 3 holds too much:** the layouts, the panel, the open, `VisibleSender` and the smoke test. Split it so each
      part is green on its own:
      - 3a: stages, layouts, fit, `FLAG_SECURE` and the smoke test. Nothing opens yet.
      - 3b: `GlanceOpener`, `VisibleSender` and the unlock path.
    - **Device checks to add to §9:**
      - a message while Element X is suspended (bedtime) shows no row;
      - the kiosk-on root case: force the 1 s fallback; nothing opens, and the kid lands on Home;
      - a ticked app's content intent into an app that isn't allowlisted: BlockedAppActivity with the kiosk on,
        nothing with it off;
      - the open on an API 34 or 35 emulator (`MODE_BACKGROUND_ACTIVITY_START_ALLOWED`). The Jelly Star is Android 16
        and covers only `ALLOW_IF_VISIBLE`.
    - **Threading:** say that `GlanceStore.refreshPolicy`, called from the sync thread, posts to the main thread.

### §6 "Defaults chosen" - QA's view

1. No glance page without a kid PIN: agree.
2. The compact tile when rows compete for height: agree.
3. Up to 3 rows plus "more", grouped per conversation: agree (with #4's numbers).
4. `FLAG_SECURE` on the whole lock, always: agree.
   - The cost is only QA screenshots. Use the emulator console [device]. The smoke test's `screencap` of the lock goes
     black; its UI-dump checks are unaffected.
5. `VISIBILITY_SECRET` as "app + count", and `VISIBILITY_PRIVATE` under the parent's switch: agree for messaging apps.
   For other styles, see #8.
6. No row opens anything while a time rule locks the phone: change it for apps the rule leaves usable (#3, Q1).
7. An unknown policy shows missed calls only, and an older server means Element X, sender only: agree. For the cache
   written before the update, see #11.
8. Names are what the app gives: agree for this design.
   - A Matrix display name is the sender's own choice, so a stranger can appear as "Pappa". Element X shows the same,
     but the lock shows it out of context.
   - Design 15's MXID -> phone-book mapping (the phone-book name for known contacts) is the right follow-up.

### Questions for the user

1. **Under a time rule that leaves the app usable, should a row open the conversation after the code?**
   - When: the screen-time budget always keeps the contacts' messaging apps; a school or bedtime rule keeps its exempt
     apps.
   - **Recommendation: yes.** The time-rule screen comes first, and the conversation opens over it, so Back returns to
     the time-rule screen.
   - Apps the rule suspends keep opening nothing (they have no rows).
   - A missed-call row opens the phone book when the rule allows calls.

## Decisions after the design QA (orchestrator, 2026-10-11)

- **All 12 findings are accepted** as the QA proposes them. The implementer builds them in, with their tests:
  - **Medium 1:** read the ranking on every post and every sweep, and treat a missing ranking as suspended.
  - **Medium 2:** a guarded opener, plus the "no launcher activity" row.
  - **Lows 4-12:** as the QA proposes. For Low 8, in sender-only mode a notification that isn't MessagingStyle shows
    the app name and count, never its title. For Low 10, send only when `creatorPackage` is the notification's app.
- **Q1 / Medium 3 (time rules), decided by the existing rules, not a new choice:** the user's rule is that an app a
  time rule leaves usable can be opened. So after the code, a row opens the conversation over the time-rule screen,
  and Back returns there. Apps the rule suspends still open nothing. A missed-call row opens the phone book when the
  rule allows calls. This replaces §6's "no row opens anything while a time rule locks the phone".
- The other §6 defaults stand.

## Implementation status (2026-10-12)

Built on branch `lock-glance-25`, rebased onto `main` at 79465d85 (the launcher security wave). Commits:
- fc377696 - step 1: the contract, both sides (migration, `lock_glance` in the policy, the PWA's "On the lock screen";
  `PolicyResponse.lockGlance` and `lockGlancePolicy`).
- fe7b97a0 - step 2: the data - `GlanceReader`, `GlanceStore`, `GlanceRows`, `MissedCallNotice.contacts`,
  `MissedCallNotifier.current`.
- b8d9ffa9 - step 3a: the page - stages, layouts, the fit, `FLAG_SECURE`, the smoke test. Nothing opens yet.
- f9db15f8 - step 3b: the open - `GlanceOpener`, `VisibleSender`, the unlock path, the time-rule hand-over, the
  capability `lock_glance_v1`.
- e7721410 - step 4, the docs (both `CLAUDE.md`s, `PLAN.md`).
- The code QA's fix round: 420b0e50 (Lows 1, 2, 7), f5dac12c (Lows 3, 4, 5), d9e50fd9 (Low 6), and the commit with
  the report and this update.

**Migration:** `0066_lock_glance.sql`, the next number after `main`'s 0065 (`0065_command_receipts.sql`, the
launcher security wave).

**Tests (on 79465d85, after the code QA's fix round):** launcher `./gradlew testDebugUnitTest assembleDebug
assembleRelease -PwarningsAsErrors=true` with the stub `tsnet.aar` (dependency verification on): 983 unit tests, 0
failures, both builds green. Server: `cargo test` 574 passed, `cargo fmt --check` clean, `cargo clippy --all-targets`
12 warnings, all from before this branch (unused model fields, `dns_engine.rs`, an old `sort_by` in `devices.rs`),
none new. The PWA's subsection checked headless at 360 px, light and dark, after the merge (no horizontal scroll).
Each step was green on its own before the first rebase: the step-1 compatibility test and the full server suite on
the server half; the full launcher suite after steps 2, 3a and 3b.

**All 12 QA findings are built in:**
- Medium 1: the reader takes the ranking of every post (`onNotificationPosted(sbn, rankingMap)`), every sweep
  (`currentRanking`) and every ranking update; no ranking counts as suspended.
- Medium 2: every glance entry point catches `Throwable` - the reader per notification, the store's and the
  notifier's listeners, the icon job, the panel (render, stage, tap, fit, start/stop) and the opener. A panel
  that can't be built leaves the plain keypad. The open table has the "no launch intent -> nothing" row.
- Medium 3 / Q1 (decided): see the first deviation.
- Low 4: rows have 8 dp vertical padding (56 dp in the model); the "more" line has `minHeight` 24 dp and nothing
  above it; the fit uses the page's height after `KidInsets` and runs again on every height change, never before
  the first layout. §1.2's expected fits hold exactly (445, 464, 394, 458, 460 and 493 dp).
- Low 5: the prompt has `maxLines="2"` and `ellipsize="end"`; the label in the hint is cut to 20 characters.
- Low 6: icons come from Home's `renderAppIcon`, drawn by one background job, with a placeholder circle until then;
  the shape is Home's circle, and the mockup is updated.
- Low 7: without an allowlist the PWA lists every app the phone reports, and every ticked package outside the
  list is always shown, so any tick can be removed.
- Low 8 (as decided): in sender-only mode a notification that isn't MessagingStyle shows the app name and count,
  never its title. The PWA says names show to anyone holding the phone, and that with the switch on an SMS app's
  login codes show too.
- Low 9: `current` clears on a cancel, a swipe and when our listener sees id 1006 leave the shade;
  `MissedCallNotice` and `MissedContact` print no names or numbers. Tapping any missed-call row clears every
  missed-call row, as design 12's phone book does.
- Low 10: only a content intent whose `creatorPackage` is the notification's app is sent; otherwise the launch
  intent.
- Low 11: a key missing from the cached policy means unknown (missed calls only); missing from a fresh policy means
  the older server's default, kept in memory.
- Low 12: the tests it names were moved or added (`LockGlanceLayoutTest` took over `LockMediaLayoutTest`'s widths,
  `KidHeaderLayoutTest` checks `view_lock_glance`, and the old clock test is gone); step 3 is split into 3a and 3b;
  `GlanceStore.refreshPolicy` posts to the main thread; the device checks are below.

**Deviations, with reasons:**
1. **The open has two callers** (Q1). Under a time rule the lock doesn't open anything. It hands the target to the
   time-rule screen: `GlanceOpener.openHandedOver` runs from `LockActivity.onResume`, within 10 s of the unlock, and
   checks the rule again. So the conversation opens over the time-rule screen, and Back returns there.
   `LockGlanceGuardTest` pins both callers (`open` only in `PinLockActivity.go`, `openHandedOver` only in
   `LockActivity.onResume`). A missed-call row's target is taken under a rule only when calls are managed and
   allowed. That is the time-rule screen's own condition for its phone book.
2. If the rule ends between the unlock and the time-rule screen, that screen doesn't come up. The hand-over then
   expires: the kid lands on Home and nothing opens. This edge is accepted.
3. **Server package check:** `apps` uses `app_display::valid_package_name` (the launcher's grammar) instead of the
   looser `is_package_name`, so the server never sends a name the launcher would drop. Without an allowlist an
   unlisted tick is labelled "not on the phone - ticked", since every app is allowed then.
4. `PendingIntent.isActivity()` and `creatorPackage` are read at the open, not at each post. It is the same
   information with fewer binder calls.
5. A ranking update refreshes only the suspended flag. A channel's lock-screen visibility that the user changes
   applies at the next post or sweep.
6. At TEXT, a notification without a text shows the subtitle "Element X · N meldinger".
7. **Smoke test:**
   - The missed-call check finds the row by its description ("Tapt anrop fra ..."), because the script doesn't know
     the contact's name.
   - `shot` still uses `screencap`, which is black over the lock; a comment points to the emulator console.
8. The "more" line shares a wrapper (`lock_glance_list`) with the rows. Its 14/22 dp top margin then also applies
   when no row fits (candidate 7).
9. `MissedCallNotifier.refreshAtStart` (§2.4; since the code QA `relearn`) never clears, it only re-learns what is
  on screen. During a no-calls time rule the phone book's view has only emergency contacts. A process restart then,
  such as the night's self-update during bedtime, would otherwise cancel a missed-call notification the kid hasn't
  seen.

**Fix round after the code QA (`qa-25-code.md`, 0 High, 0 Medium, 7 Low; all fixed):**
1. The lock-task checks hold for the time-rule hand-over: `lockWasRoot` travels with the hand-over and refuses before
   it, and with the kiosk on an open with no lock task running opens nothing ("lock task not running"), from either
   place.
2. A refused send: `PendingIntent.send` throws only for a negative result, its `OnFinished` reports 0 for an
   activity, and the result-returning send is hidden. So the opener watches our own windows for 1.5 s after a
   send (`glanceSendCheck`): Home or the time-rule screen back in front means the start was refused, and the app's
   launch intent goes from that window. Otherwise the row is marked opened, but only when Home or the time-rule
   screen was below (kiosk on, or the hand-over). With the kiosk off, nothing is concluded, and the app clears its
   own notification when the room opens.
3. `MissedCallNotifier.relearn` (the process start, every time-lock change, and ours posted again while `current`
   is unknown - a snooze that ended) counts against the call rules without the time rule's block
   (`noticeState`). A notification on screen during a no-calls rule was posted before it, so the glance knows it
   again.
4. The tests the QA named: `glanceSuspended` (no ranking = suspended) in both reader paths,
   `glanceUsableUnderLock` (the budget's apps, a rule's exempt apps, a no-calls rule, unmanaged calls),
   `nextGlancePolicy` (a fresh policy beats the start's cache), and guard checks for `removedFromShade` and the
   re-learn wiring.
5. `cleanGlanceText` collapses Unicode whitespace (line and paragraph separators, no-break spaces) and drops the
   invisible marks (U+061C, U+200E/200F, U+2060-2064, U+FEFF). Joiners stay, since emoji need them. The test file's
   bidi characters are now escapes instead of literal characters.
6. PWA: an unlisted tick says "not allowed" only when the allowlist lacks it, else "not on the phone". An empty
   list says why: no report yet, no allowed app, or none reported.
7. The tile's progress ticks only while the glance page shows (`glanceTicks(tileShown, pageShown, session)`), not
   behind the keypad.

**Not run here:** no emulator or phone in this sandbox. Everything below is a device check.

**Device checklist** (emulator at 320x569 dp, nb and en, then the Jelly Star; §9 plus QA Low 12):
- [ ] The page in both kiosk modes, with gesture and 3-button navigation, with and without music, with 0, 1, 2, 3
      and 5 rows:
      - the fit picks §1.2's candidates;
      - nothing is clipped or squeezed, and round buttons stay round;
      - the same at 360 and 411 dp and at font scale 1.3;
      - the keypad stage has no clock or date, and its keys are >= 52 dp.
- [ ] Element X, a DM and a group from another account:
      - sender only ("Pappa", "Element X · 2 meldinger");
      - the switch on shows the newest text ("Pappa: ..." in the group);
      - the switch off removes the texts at the next sync, without a restart;
      - unticking Element X removes its rows, and ticking it brings them back;
      - a DM read on another device removes its row.
- [ ] A row's tap: the keypad says "Skriv koden for å åpne Element X", and the right code opens the room (kiosk on
      and off; Back from it). Also check:
      - the parent code instead of the kid's PIN;
      - a wrong code (the keypad stays);
      - the arrow back (the plain prompt);
      - a screen-off on the keypad (the glance page again, the target gone);
      - the row's notification removed before the code (the room still opens);
      - Element X suspended meanwhile (nothing opens);
      - a missed-call row opens the contact's sheet, and every missed-call row is gone afterwards.
- [ ] Under the screen-time budget (Element X usable): a row's tap shows the hint, and after the code the time-rule
      screen comes, then the room over it, and Back returns to the time-rule screen. Under bedtime with Element X
      not exempt: no hint, and after the code the time-rule screen only. A missed-call row under a rule that
      allows calls opens the phone book over the time-rule screen.
- [ ] A message while Element X is suspended (bedtime) shows no row; it appears when the rule ends.
- [ ] Missed calls:
      - one call, then two from Pappa and one from Mamma (one row per contact, newest first);
      - calling back removes the row;
      - after a launcher force-stop the row is still there (`refreshAtStart`);
      - calls unmanaged with our dialer role show the plain row;
      - a missed-call notification snoozed from the shade (while unlocked) removes the row.
- [ ] States:
      - an incoming call and a VoIP ring over the glance page;
      - "Nødsamtale" from it;
      - an alarm;
      - a reboot (rows appear when the listener binds);
      - no listener access (missed calls only, and the PWA's notes);
      - `ForeignAccess` ("Foreldrekode" refused as in 24b, the glance page unchanged).
- [ ] TalkBack:
      - the reading order;
      - one node per row with its action label;
      - the keypad unreachable while covered;
      - a UI dump lists the rows.
- [ ] `FLAG_SECURE`:
      - `screencap`, power + volume-down and a screen recording show the lock black;
      - whether `adb emu screenrecord screenshot` captures it.
- [ ] Nothing runs from the lock:
      - long-press, swipe and double-tap on rows do nothing;
      - Element X's quick reply never appears;
      - the notification stays unread in Element X until the room opens.
- [ ] The kiosk-on root case: force the 1 s fallback. Nothing opens, and the kid lands on Home - also under the
      screen-time budget with an Element X row (the hand-over refuses too, code QA Low 1).
- [ ] A refused send (code QA Low 2): a ticked app whose content intent the platform refuses (the API 34/35 opt-in,
      or a target lock task doesn't permit with "Block other apps" off) - within 1.5 s the app's launch intent
      opens instead (kiosk on: from Home; under a rule: from the time-rule screen), and the row isn't hidden when
      nothing opened. A Back within 1.5 s of a room that did open re-opens the app's main screen once (accepted).
- [ ] Missed calls and the glance after the night's update during bedtime: the row is there in the morning (code QA
      Low 3); a missed-call notification snoozed from the shade comes back as a row when the snooze ends.
- [ ] A ticked app whose content intent goes into an app that isn't allowlisted: BlockedAppActivity with the kiosk
      on, nothing with it off.
- [ ] The open on an API 34 or 35 emulator (`MODE_BACKGROUND_ACTIVITY_START_ALLOWED`); the Jelly Star (16) covers
      only `ALLOW_IF_VISIBLE`.
- [ ] Whether the Jelly Star's notification assistant marks anything as sensitive (the redacted case).
- [ ] The PWA on a phone: tick, untick and the switch keep the place (no jump), and the notes appear.

## Lock fix round 25b (2026-10-12)

The user's Android 16 emulator run of 22a62658 and three logcats. Branch `lock-fixes-25b`, one commit per bug.
Every root cause except G predates design 25.

| Bug | Root cause | Fix |
| --- | --- | --- |
| A (High) Recents on the lock | Quickstep 16 refuses Overview only in screen pinning; in a DO's LOCKED lock task the button follows SystemUI's disable flags (desync). The lock re-fronted only on onStop, and Recents only *pauses* it | A pause while LOCKED re-fronts after 200 ms (`pauseStartsRefront`), a stop too (was 500 ms) |
| A/C (High) camera double-press | GestureLauncherService -> SystemUI started the camera that CameraLock had suspended -> NPE in system_server -> **SystemUI crashed** (the "small restart"); whatever came up meanwhile (quickstep's RecentsActivity, its app list = the "Calendar", a plain background) showed over the lock | While LOCKED (and while a time rule suspends the gesture's camera) the DO disables the cameras by policy (`setCameraDisabled`, `<disable-camera />`, `cameraPolicyDisabled`); AOSP 16 `CameraGestureHelper.canCameraGestureBeLaunched` checks that first on both double-press paths, so the gesture starts nothing. The user's first choice; the camera never opens over the lock, so no gallery or picker either |
| C (High) status line flicker | The lock-resumed status-bar heal (16d) flipped features 113 -> 112 -> 113 right after each power-on | The first lock resume after a screen-off heal skips its own heal |
| B (High) Home before the glance | The lock is started at the screen-off as a new task; its open transition ran at the screen-on from Home's last frame; the glance page could draw before its fit | No transition (`FLAG_ACTIVITY_NO_ANIMATION`, `overrideActivityTransition`, no window animation); a fit that changes the page skips its frame |
| B residual: a plain-colour flash | (1) the screen-off receiver kept the main thread (fence, self-update, policy re-check, heal) before the new lock could be created - meanwhile the system shows its starting window (`windowBackground`); (2) mashing = five presses = SOS -> BlockedAppActivity -> re-front (H) | (1) that work runs 400 ms later and only while the screen is still off (`SCREEN_OFF_WORK_DELAY_MS`, `screenOffWorkDue`); (2) H |
| D (Medium) clock splash | No code path starts the clock app (it is only pinned as a helper). Gone after an emulator restart; the theory - a clock task started by a tap during boot staying behind the lock, its starting window shown at each lock-up - can't be checked from code | Device check |
| E Element X calls | Element X posts a missed Element Call with `CATEGORY_MISSED_CALL` (its `NotificationCreator.kt`) | `glanceMissedCall`: "Tapt anrop · Element X" with the red phone badge |
| G music tile on the wrong stage | Design 25 kept step 4's compact row on the keypad and showed no tile for a paused session at a lock-up | User decision 2026-10-12 (§1.1): tile for any session, playing or paused; the keypad stage has no music |
| H SOS -> "Emergency information is not available" | SystemUI starts the `com.android.systemui.action.LAUNCH_EMERGENCY` activity (system handlers only, `config_preferredEmergencySosPackage` first) as a new task; its package wasn't pinned | `HelperKind.EMERGENCY_SOS` (`emergencySosHandler`): pinned with the kiosk on (with or without the block bit) and off, never hidden or suspended, never listed. The re-front yields as "sos" for 60 s (or until the lock is back) when the lock is *stopped* within 3 s of a screen-on (the gesture's presses come <= 500 ms apart and are swallowed while the screen is on) and none of our own screens is in front |

**Deviations and limits**
- **The SOS yield is a timing heuristic.** No app can see which activity is in front (usage events need a permission
  a device owner can't grant itself). A stop of the lock within 3 s of a screen-on leaves whatever is in front for up to
  60 s. While LOCKED only pinned packages can be there: Home and Recents are out of lock task, the camera is off by
  policy, our own screens are excluded, Recents only pauses (re-fronted as always, within 700 ms in that window).
- **A re-front still shows one plain frame.** Bringing the lock's task back is a task switch; the system's snapshot of
  a `FLAG_SECURE` window is the theme background (`shouldUseAppThemeSnapshot`), shown until the lock draws.
- The gesture keys (`camera_double_tap_power_gesture_disabled`; Android 16's `double_tap_power_button_gesture_enabled`,
  read instead in 16's default multi-target mode) are in `docs/testing/emulator.md` §4 and `docs/setup/google-account.md`.
  They aren't reported in the status report (both are readable; a later step could add them).
- `disable-camera` is a new admin policy tag: an updated phone may need a restart before `setCameraDisabled` works,
  shown in `admin_policies_pending` and on the device page.

**Device checks**
- [ ] Double press power on the lock, gesture keys on: no SystemUI crash, nothing opens, the lock stays.
- [ ] The Overview button on the glance page: Recents doesn't stay (`adb shell dumpsys statusbar` disable flags).
- [ ] Power on after unlock, and quick power mashing: no Home and no plain frame (screenrecord).
- [ ] Five presses: the SOS countdown (Pixel: Personal Safety) or Emergency information shows and stays; its call
      places; cancel brings the lock back. With the kiosk on and off.
- [ ] Wake, then Overview within 3 s: Recents goes within a second.
- [ ] The clock splash (D) after tapping during boot.
- [ ] The per-frame "Out of order buffers" for PinLockActivity (likely the emulator's mirroring of a secure surface).
- [ ] Element X: a missed Element Call shows as "Tapt anrop · Element X".
- [ ] A paused session's tile at a lock-up; the keypad stage without music.

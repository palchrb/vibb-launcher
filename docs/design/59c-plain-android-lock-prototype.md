# Design 59c - the plain Android lock, a throwaway prototype

Branch `proto-plain-lock` from `d88509ae`. Debug build only, behind a debug-only switch; nothing here is meant to be
merged as is. The question: instead of design 59's hybrid (Android's secure keyguard as the gate, our glance page as
its `showWhenLocked` face), could the kid just see **Android's own lock screen**, with our curated rows posted as our
own notifications? This note says what was built, what the emulator showed, and how the two compare.

Run: 2026-10-22, AVD `handy36` (Android 16, Pixel launcher, gesture navigation, English), `wm density 240` (the Jelly
Star's 320x569 dp) unless noted, `-wipe-data`, a local server from this commit over HTTP, provisioned as
`docs/testing/emulator.md` §4. Android's lock came on from the PWA (Lock row, `keyguard=android`); the server's
`set_kid_pin` made the Child PIN `1357` Android's PIN at the first sync, no keypad migration needed.

## 1. What was built

All of it is inert in release: `src/release`'s `PlainLockDebug` is `enabled = false` and `start = Unit`, the receiver
lives only in `src/debug` and its names are in `CheckDebugHook` and `DebugHookAbsentTest` (as the hybrid prototype's
`HybridDebug` was).

| Piece | Where | What |
|---|---|---|
| The switch | `src/debug/.../lock/PlainLockDebug.kt`, `PlainLockDebugReceiver.kt` (DUMP-guarded) | CE prefs `plain_lock_debug`: `on`, `mode` = `redact` or `cancel`. The broadcast runs a full apply, sets the lock wallpaper and refreshes the rows. |
| No face | `PinLockRuntime.show` | With Android's lock as the gate (`AndroidLock.faceOn`) and the switch on, `show` starts nothing and returns false. LOCKED is still recorded at every screen-off; Android's `USER_PRESENT` unlocks as in design 59; the re-front stays held while Android's keyguard is locked. Lock task is unchanged. |
| Keyguard features | `AndroidLockPlan.kt` `PLAIN_KEYGUARD_FEATURES`, `KeyguardCoverPlan.kt` (`plain` param) | Design 59's set minus `SECURE_NOTIFICATIONS` (0x4) and `SHORTCUTS_ALL` (0x200), plus `UNREDACTED_NOTIFICATIONS` (0x8). Read back as `0x19b`. The switch back takes out exactly what it added (pinned in `DebugHookAbsentTest`). |
| Curated rows | `PlainLockDebug.refresh` | For each row `GlanceStore.rows(MissedCallNotifier.current)` gives, one notification of ours on channel `launcher:plain_lock_rows_high` (HIGH, no sound, no vibration): the glance row's title and subtitle, the app's icon or the contact's initial disc as the large icon, peach colour, `publicVersion` = the same text, `VISIBILITY_PUBLIC`, its own group (see §3.4), the row's content intent (the app's own, or the phone book for a missed call). Posted while LOCKED, all removed at the unlock. Our real missed-call notification is `VISIBILITY_SECRET` while the switch is on, so the curated row stands for it. |
| Variant `cancel` | `PlainLockDebug.cancelOriginals`, `NotificationRuleRuntime.connectedListener()` | The listener cancels a ticked app's notifications behind a row (never ongoing, non-clearable, foreground-service, full-screen-intent or `call` ones), then a group summary of that app left without children. Our row is then held - in the shade too, after the unlock - until tapped or swiped. |
| Lock wallpaper | `PlainLockDebug.setLockWallpaper` | `FLAG_LOCK` only: `kid_ground` with the launcher icon's vector mark drawn at runtime (no new asset), alpha 55 %, below the middle. Off: navy, design 08's default lock fill. |

Switch it on (Android's lock already on from the PWA):

```sh
P=me.vibb.launcher.debug
R=$P/com.kidslauncher.mdm.lock.PlainLockDebugReceiver
adb shell am broadcast -n $R --ez on true --es mode redact     # or --es mode cancel; --ez wallpaper false skips it
adb shell am broadcast -n $R                                   # report only
adb shell am broadcast -n $R --ez on false                     # back to the hybrid (wallpaper navy)
adb logcat -s PlainLock AndroidLock PinLock
```

The result data reads like `plain=true variant=redact faceOn=true mode=UNLOCKED features=0x19b secure=true ...`.

## 2. Screenshots

All are from the emulator console (it captures secure windows), 480x854 px, in `docs/design/mockups/59c/`.

| File | What it shows |
|---|---|
| `1-hybrid-glance.png` | For comparison: today's hybrid, our glance over Android's keyguard (no rows at that moment). |
| `2-plain-redacted.png` | Plain, `redact`: Android's face with the night ground and mark. First "Messages · 13m" - the app's two conversations, **redacted** (header only, no sender, no text) and sorted first because Messages posts conversations at HIGH. Then our curated row "Mamma · 2 missed calls" with the initial disc. The two other curated rows (the two SMS conversations) are only **icons in the shelf** pill below - there is no room for them. |
| `3-plain-cancelled.png` | Plain, `cancel`: Messages' notifications are gone (cancelled by our listener), so nothing redacted is left; our "Mamma · Missed call" row in full, our two message rows again only as shelf icons. |
| `4-media-player-180dpi.png` | Android's media player for the music app's Media3 session (the fake podcast, "Bilde 2 · Fake feed with pictures", pause, previous/next, the scrubber, "This phone" output chip) - **only at `wm density 180`**. At the Jelly Star's 240 dpi the player never showed; the lock had only the icon shelf (the music app's icon among them). |
| `5-pin-pad.png` | Android's PIN pad after a tap on our missed-call row: system palette (light), "Enter PIN", Emergency. After `1357` our phone book opened directly (the row's own target), no Home in between. |
| `6-call-over-lock.png` | An allowed caller ringing over the plain lock: our `InCallActivity` (showWhenLocked) as today. The unknown caller was rejected by `KidCallScreening` without UI. |
| `7-shade-pull-blocked.png` | After a swipe down from the top and `cmd statusbar expand-settings` over the plain lock: nothing opened, focus stayed on `NotificationShade` (the keyguard). Also the cleanest `redact` frame: one redacted Messages header, one curated row, the shelf. |
| `8-after-reboot.png` | After `svc power reboot`, before the first unlock: Android's face with our lock wallpaper, the line "Unlock for all features and data", and a system notification **with its text** ("Disconnected from always-on VPN · Change network or VPN settings"). The hybrid's `SECURE_NOTIFICATIONS` hides that one. |

Recents (not pictured, the frames equal the lock): `KEYCODE_APP_SWITCH`, a swipe up from the bottom edge and a
swipe-and-hold all left the focus on `NotificationShade`; `dumpsys activity activities` had no `RecentsActivity`
record. The torch shortcut: see §3.6.

## 3. What the emulator showed

### 3.1 The lock itself

- `PinLock: Lock-up at screen-off: LOCK ... kg=true`, then `UNLOCKED -> LOCKED`, then at the wake
  `AndroidLock: plain lock: no glance started: keyguardLocked=true deviceLocked=true secure=true`. Android's face is
  what the kid sees; LOCKED is kept. The PIN on Android's pad (or a row tap -> pad -> PIN) gave `USER_PRESENT` and
  `LOCKED -> UNLOCKED`, and the curated rows went (`rows: 0 posted`).
- Lock task without `NOTIFICATIONS` (features 113) does **not** hide the keyguard's notifications, but it does keep
  the shade and Quick Settings shut over the lock (§2, 7). So the kid sees the lock-screen rows and can't expand them.
  (Checked the other way too: with `NOTIFICATIONS`+`HOME` added through the 16d override, the lock looked the same.)
- SystemUI's face sleeps again after ~10 s without a touch (its own lock-screen timeout), not after the phone's 60 s.
- A grey panel shows behind the stack in some frames right after a wake (`2-plain-redacted.png`); it is gone a moment
  later (`7`). It looks like SystemUI's wake transition on SwiftShader; not investigated further.

### 3.2 Room on a 3-inch phone - the decisive finding

| Density (dp of the 480x854 screen) | What Android's lock showed |
|---|---|
| 240 dpi = 320x569 dp (the Jelly Star) | 1-2 full rows, the rest as icons in the shelf |
| 240 dpi with the music app playing | **only the shelf** (icons), no row and no media player |
| 320 dpi = 240x427 dp (the AVD's own) | only the shelf, no row at all |
| 180 dpi = 427x759 dp (a normal phone) | the media player and the shelf |

SystemUI sizes the lock-screen stack from the room under its big clock. On the Jelly Star that is one or two rows, and
the shelf can't be opened over the lock (the shade is shut by lock task, and opening it would show everything anyway).
`lock_screen_notification_minimalism` (Android 16's compact mode) was unset and setting it to 0 changed nothing; the
silent-notifications switch neither. So on the kid's phone the plain lock shows **at most two** things, often one,
and nothing readable while music plays.

### 3.3 Variant `redact` (`KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS`)

- Every other app's notification shows, but without content: Messages showed as the header "Messages · 13m" (its
  conversations collapsed into the app's group, no sender, no text). WhatsApp isn't on the image; a WhatsApp message
  would show the same way - the app's name and time, no sender or text - and **it would still be there**, next to our
  curated row for the same message: the kid sees each ticked app's message twice (once redacted, once ours).
- Messages (conversation, HIGH) sorts above our rows, so the redacted header takes the first of the one or two places.
- Our rows show their public version in full (`publicVersion` with the same text; `VISIBILITY_PUBLIC`).
- The always-on-VPN system notification shows with its text before the first unlock (§2, 8): system notifications that
  say `PUBLIC` are not redacted by this bit.

### 3.4 Variant `cancel` (the listener cancels, ours replaces)

- It works: a new SMS's notification was cancelled within the same listener callback (`PlainLock: cancelled 1 of
  com.google.android.apps.messaging`), our row took its place, and Messages did **not** post it again by itself.
- Messages keeps a group summary that outlived its children; it showed as a redacted "Messages" row until the
  prototype also cancelled a childless summary of that app.
- The cost:
  - the app's own notification is gone from the shade after the unlock too; ours stands for it (its tap opens the
    app's own content intent, kept before the cancel), and when ours is swiped or tapped, nothing is left but the app;
  - ours lives in our process: a launcher update (Android clears an updated app's notifications) or a reboot lost the
    held rows - after the reboot the content was only in the Messages app;
  - whether the app's "dismissed" handling marks the conversation read was not checked (Messages was still starting
    after the reboot) - a listener cancel sends the app's delete intent, and an app may treat that as "seen";
  - every app we cancel for loses its own grouping, inline reply, actions and, for a call-style app, its call
    notification - which is why `call`, ongoing, foreground-service and full-screen-intent notifications are never
    cancelled. Element X's and WhatsApp's re-post behaviour after a listener cancel is untested here (not on the image).
- The same room limit (§3.2) applies: one or two of our rows, the rest shelf icons.

**For the record - per-notification redaction through the Notification Assistant.** Android 15 added
`Adjustment.KEY_SENSITIVE_CONTENT`, with which the assistant can mark a single notification sensitive (redacted on the
lock) - the clean per-app control. But the assistant is the `NotificationAssistantService` holder
(`cmd notification allow_assistant`, or a system/privileged app); a QR-provisioned device owner can't take it, so it is
out for the phones we provision.

### 3.5 Android's media player

Only at 180 dpi (§2, 4): the music app's Media3 session gives SystemUI's own player (art, title, pause, previous/next,
seek, output chip). At the Jelly Star's 240 dpi it was never drawn - the lock had only the shelf. The glance's
own music tile, by contrast, fits at 240 dpi by design (`glanceFit`).

### 3.6 The torch shortcut

Not possible on this AVD: it has no flash unit (our own `Torch.state` is null there too), and SystemUI only offers
the Flashlight shortcut where `hasFlashlight()`. Where it exists, the user picks it in Wallpaper & style -> Lock screen
shortcuts; the prototype drops `SHORTCUTS_ALL` so it may show (`SECURE_CAMERA` still keeps the camera shortcut off).
**A device owner can't set it**: the selection goes through SystemUI's `CustomizationProvider`
(`content://com.android.systemui.customization`), which needs `CUSTOMIZE_SYSTEM_UI` (signature|privileged) - even shell
got `Permission Denial`. On a kid's phone the parent would set it once by hand, during a pause (Wallpaper & style
isn't in the kiosk).

### 3.7 Look

- The lock wallpaper worked (`lock wallpaper night + mark: true`): SystemUI's face is on the night ground with the
  mark, before and after the first unlock. SystemUI's clock, date, notification cards and the PIN pad stay in the
  system palette (light cards, a light pad) - not ours to set (59b §5).
- The generic disclosure "This device belongs to your organization" sits at the bottom (`setDeviceOwnerLockScreenInfo`
  stays unset, as 59b Q59b-11 decides; the BFU frame showed "Unlock for all features and data" instead of the restart
  line this time).

### 3.8 Not possible in the plain lock (what the glance has)

- The glance's own rows: sender + text per conversation, the app's Home icon, our missed-call row with the contact's
  avatar - as the kid's **first thing**. Plain gives at most two, sorted by Android, the rest as icons.
- "+ N varsler til" counting all of them (plain: unlabelled icons in a shelf that can't be opened).
- Our buttons: "Lås opp" (plain: swipe up, or tap a row), "Nødsamtale" (plain: Android's "Emergency" on the pad
  only), the torch button (plain: a user-set shortcut where the phone has a flash), the music tile at 240 dpi, the
  running-timer / stopwatch tile, the next alarm.
- The notice wake (round 7 §3): plain has Android's own behaviour - a new notification lights the lock if the app's
  channel alerts - with no parent switch, no proximity check, no rate limit, no 15 s timer.
- Per-row tap targets as ours: a plain row's tap goes Android's way (row -> pad -> the content intent). That did
  work for our curated rows (missed call -> phone book), but there is no "Skriv koden for å åpne <app>" hint, no time-
  rule check before opening (the glance only opens what the rule allows), and no "opened through the glance" mark.
- Our look (wallpaper aside), our font, our language (SystemUI follows the system locale).

What the kid **can** do from Android's lock: tap a row (-> pad -> that row's target), use the media player (on a
bigger screen), a lock-screen shortcut if set, Emergency on the pad, answer an allowed call. The shade, QS and Recents
stay shut (lock task).

## 4. Plain vs hybrid

| | Hybrid (59, our glance as Android's face) | Plain (59c) |
|---|---|---|
| **Gate** | Android's PIN (same) | Android's PIN (same) |
| **What shows on the lock** | Ours: up to 3 rows + "+ N", fitted to 320x569 dp, sender/text per the parent's choice, missed calls with avatars, music tile, timer, alarm | Android's: 1-2 rows on the Jelly Star, the rest as shelf icons; nothing readable while music plays; other apps redacted (`redact`) or gone (`cancel`) |
| **Control** | Full: we decide every pixel and every tap; the time rule is checked before a row opens | Android decides order, room, grouping (Android 16 bundles an app's ungrouped notifications unless each has its own group), wake behaviour; a redacted duplicate of each ticked app's message (`redact`), or cancelled originals (`cancel`) |
| **Look** | Vibb night, Nunito, our layout; Android's pad in system colours | System clock and cards; our wallpaper and mark only; system pad |
| **Media** | Our tile at 240 dpi | Android's player only on bigger screens |
| **Torch** | Our button | User-set shortcut only, never set by us; none without a flash |
| **Complexity** | High: `showWhenLocked` face over the keyguard, the face steps (USER_PRESENT, sleep check, ask recovery, re-front holds), 59b's fast re-front | Lower for the face (none), but a notification mirror with its own lifecycle; `cancel` adds a listener that deletes other apps' notifications |
| **Risks** | The face/keyguard dance (59 B1/B2, F2, 59b's flash) | Content of non-ticked system notifications on the lock (§2, 8); `cancel` loses content on update/reboot and breaks apps' own notification UX |
| **Lost** | - | §3.8: rows, folding, our buttons, timer, notice wake rules, per-row rule checks |

## 5. Judgement

Keep the hybrid. The plain lock is simpler only on paper: on a 320x569 dp phone Android's lock screen has room for one
or two rows and nothing readable while music plays, so the kid would mostly see icons - the main thing the glance was
built for (who wrote, missed calls, the music) is what the plain lock can't show. `redact` doubles every ticked app's
message (a redacted original plus ours); `cancel` removes the duplicates but takes the app's own notification away for
good and loses our copy at every update and reboot, and per-notification redaction (the assistant's
`KEY_SENSITIVE_CONTENT`) is out of reach for a QR-provisioned owner. Two plain-lock pieces are worth keeping for the
hybrid: the lock wallpaper with the mark (59b step 3, confirmed here: SystemUI's face and the pad's backdrop are night),
and the observation that lock task keeps the shade, QS and Recents shut over Android's own face too.

## 6. Notes for a next run

- `pwa_setup.py`'s restrictions regex found no checked switches on this server's Advanced page, so every hardening
  switch went off (not only Block USB debugging). It didn't matter for the lock; fix the regex before a hardening run.
- The music app takes its library from the launcher's bridge on a managed phone; its debug feed is ignored there. A
  library entry from the PWA works: `fake-audio.py --mb 2 --feed 3` and `POST /music link=http://127.0.0.1:8731/feed.xml
  category=4`, then tick it for the phone.
- SystemUI ANRs once after each cold boot on this AVD ("Wait").

## 7. Re-run with the small clock (2026-10-23)

The question: does `adb shell settings put secure lockscreen_use_double_line_clock 0` (Android's small clock forced)
give our curated rows or Android's media player more room at the Jelly Star's 240 dpi? Same setup as §1 and §6, from
this commit's builds: `handy36` with `-wipe-data`, English, `wm density 240`, a fresh local server over HTTP, adb
provisioning (emulator.md §4), the Lock row on `keyguard=android`, Child PIN `1357` from the server, the switch on in
`cancel`. Messages ticked on the Lock page with Full text, contacts Mamma, Pappa, Emma, Bestemor; SMS through the
emulator console, the missed call as `gsm call` + `gsm cancel` from Emma. Music: `fake-audio.py --feed 3` as a library
entry ticked for the phone (§6), playing in the music app. Frames from the emulator console, 480x854, in
`docs/design/mockups/59c/small-clock/`. Besides the frames, SystemUI's own numbers for the lock-screen stack were read
from `dumpsys activity service com.android.systemui/.SystemUIService` (`SharedNotificationContainerViewModel`:
`bounds`, `availableHeight`, `LockscreenDisplayConfig(maxNotifications)`; `KeyguardMediaController`: `visible`, the
`MediaContainerView` size).

### 7.1 Room table (small clock forced unless noted)

"Rows" = our curated rows posted (`PlainLock: rows: N posted`). Full = drawn as a card; shelf = an icon in the pill.

| Density | Scenario | Full rows | Shelf | Media player | Frame |
|---|---|---|---|---|---|
| 240 (320x569 dp) | 0 rows, setting unset | - | - | - | `240-empty-default.png` (the big double-line clock) |
| 240 | 0 rows | - | - | - | `240-empty-small.png` (small clock, nothing else) |
| 240 | 1 row | 1 | 0 | - | `240-1row.png` |
| 240 | 2 rows | 1 | 1 | - | `240-2rows.png` |
| 240 | 3 rows (2 SMS + missed call) | 1 | 2 | - | `240-3rows.png` |
| 240 | 4 rows | 1 | 3 | - | `240-4rows.png` |
| 240 | 5 rows | 1 | 4 | - | `240-5rows.png` |
| 240 | `redact`, 2 rows + Messages' own | Messages' redacted header + 1 row | 1 | - | `240-redact.png` (as 59c frame 7) |
| 240 | 3 rows, setting = 1 (control) | 1 | 2 | - | `240-control-double-line.png` |
| 240 | music playing, 0 rows | 0 | 0 | **no** (nothing under the date) | `240-music-0rows.png` |
| 240 | music playing, 1 row | 0 | 1 | no | `240-music-1row.png` |
| 240 | music playing, 3 rows (2 SMS + missed call) | 0 | 2 icons | no | `240-music-3rows-grouped.png` |
| 240 | music playing, 3 rows (3 SMS) | 0 | 3 | no | `240-music-3rows.png` |
| 220 (349x621 dp) | music playing, 0 rows | - | - | **yes** (art, title, pause, prev/next, scrubber, "This phone") | `220-music-0rows.png` |
| 220 | music playing, 3 rows | 0 | 3 | no | `220-music-3rows.png` |
| 320 (240x427 dp) | 3 rows | 0 | 3 | - | `320-3rows.png` |
| 320 | music playing, 3 rows | 0 | 3 | no | `320-music-3rows.png` |

SystemUI's numbers behind it (px of the 480x854 screen, dp in brackets):

| Density | Stack bounds (top-bottom) | Room for the stack | Media player height | `maxNotifications` |
|---|---|---|---|---|
| 240 | 365-593 | 228 px (152 dp) | 276 px (184 dp) | 1 with rows only; 0 with music (with or without rows) |
| 220 | 346-602 | 256 px (186 dp) | 253 px (184 dp) | 1 with music alone (the player); 0 with music + 3 rows |
| 200 | 328-612 | 284 px | 230 px | 1 with music alone |
| 180 | 308-622 | 314 px | 207 px | 1 with music alone |
| 320 | (stack 112 px) | 112 px (56 dp) | 368 px | 0 with 3 rows, with or without music |

At 240 dpi a curated row card is about 130 px (87 dp) and the shelf 72 px (48 dp): one card plus the shelf fits in
152 dp, two cards don't. The player is 184 dp at every density, so it fits only where the stack has more than that
and nothing else is on the lock; with rows and music together SystemUI drew neither the player nor a row, only
the shelf, at 240 and at 220. While the music played, `KeyguardMediaController` said `visible=true` with its 276 px
container: the player is a candidate at 240 dpi, it is left out for room (`maxNotifications=0`), not filtered away.

### 7.2 The control

With 3 rows on the lock, `lockscreen_use_double_line_clock` 1 and the setting deleted both gave the same small
single-line clock (`com.android.systemui:id/lockscreen_clock_view`), the same stack bounds 365-593 and the same
1 full row + shelf as with 0. The same holds with music playing and no rows: setting 1 still gave the small clock
and 228 px. The big double-line clock (`lockscreen_clock_view_large`) showed only on an empty lock. So Android
already uses the small clock whenever rows or a media session are there, and 59c's §3.2 numbers were taken with
the small clock: its frames with rows (`2-plain-redacted.png`, `3-plain-cancelled.png`, `7-shade-pull-blocked.png`)
show the same single-line clock as this run's frames.

### 7.3 Surprises

- **The media player didn't show at 240 dpi with the small clock** (the user saw it on their own emulator). On this
  AVD at 240 dpi it needs 184 dp and the stack has 152 dp; at 220 dpi it showed. This run doesn't explain the
  difference; that emulator's density and screen size weren't known here.
- **Grey panel**: a lighter panel (x 24-456, down to y about 735) behind the stack showed in many frames (empty
  small clock, 1 and 2 rows, all 220 and 320 frames) and stayed: the 1-row frame was the same 4 s and 8 s after the
  wake. Others (3-5 rows, the control, the music frames at 240) had none. Not investigated.
- **A stopped music session kept the media slot**: after `cmd media_session dispatch stop` the music app's session
  (state NONE) and its notification stayed, `KeyguardMediaController` stayed `visible=true`, and 3 rows went into
  the shelf with no player drawn (`maxNotifications=0`). After `am force-stop` of the music app the same 3 rows gave
  1 full row again.
- **Playback stopped when SMSes came in**: three console SMSes 4 s apart left the session `STOPPED`; the cause wasn't
  checked.
- **Android bundled**: once 3 rows were posted, dumpsys had an Android-made summary
  `g:Aggregate_AlertingSection` for our package although each row has its own group; in
  `240-music-3rows-grouped.png` the shelf shows 2 icons for 3 rows (likely the summary's icon for the two SMS rows
  and the missed-call icon). It was gone later in the run.
- The curated SMS rows show the sender's number ("+47 92 34 56 78") as Messages titles them (the test contacts are in
  our phone book only); the missed-call row shows the name ("Emma").
- No SystemUI ANR dialog in any frame; the always-on-VPN notification didn't show (no reboot this run, VPN up).

Notes for a next run: `pwa_setup.py` doesn't tick Messages for the lock - `POST /devices/1/lock-screen/apps
package_name=com.google.android.apps.messaging show=text`; the console's `gsm call` needs the `+47` form to match a
contact (`4793456789` was rejected by `KidCallScreening`); the music app plays its cached copy, so a longer
`--mb` on a restarted fake host didn't lengthen the 45 s episodes - `cmd media_session dispatch rewind` every 20 s
kept one playing.

### 7.4 What this changes for §5's judgement

Forcing the small clock changes only the empty lock (big clock vs small); with rows or music on the lock Android was
already using the small clock, so §3.2 stands as measured. At the Jelly Star's 240 dpi, `cancel` shows 1 full
curated row and the rest as shelf icons for 1 to 5 rows, and `redact` shows Messages' redacted header and 1 row.
Android's media player does not show at 240 dpi on this AVD, alone or with rows; it needs 184 dp and the stack has
152 dp. With music and any row, the lock shows only shelf icons at 240, 220 and 320 dpi.

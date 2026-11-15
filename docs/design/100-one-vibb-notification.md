# Design 100: One Vibb notification - the anchor carries Snarveier; the request for more time, answered

Status: **revision 2, built** on `design-100` (step 1 c9f245e42/77a4bdf62, step 2 308a10001, step 3 efa11c62c), not
emulator-checked yet (§11; `docs/testing/emulator.md` §6ao). Revision 1 was 913764505; its design QA is
`docs/review/qa-100-design.md` (afbc5dfcf: 1 High, 1 Medium, 10 Low, all taken as decided fixes, §15); revision 2's
QA round (0 High, 4 Medium, 11 Low) is handled in the build (§16). The mockups are `docs/design/100-mockups/`
(`Foresporsel.dc.html` the PWA's card, `Telefon.dc.html` the time-rule screen).

Approved by the user on 2026-11-15 (PLAN, "One Vibb notification"). The user asked *"Er vi nødt til å vise det tomme
vibb one varselet?"* and then approved the merge: *"Genialt! Vi slår det sammen med snarvei baren vår, det er
ideelt!"*:

- **While Snarveier shows, the anchor's foreground service carries the Snarveier panel as its one notification.** The
  time left ("1 t igjen i dag") moves into the panel.
- **With Snarveier off** (the parent's bit 128 off, or nothing to show), the plain anchor notice comes back. Android
  requires a notification for the service.
- **Icons** (the same message): the panel gets the gear. The plain anchor, Play's install mode, the ring, the setup
  notice and the filter's "Innholdsfilteret kobler til …" get the round Vibb mark (`ic_stat_vibb`). Calls, missed
  calls and SMS keep their icons.

Added the same day (the user: *"Ja legg inn det du sa!"*): a **"Be om mer tid" button on the time-rule screen** when the
screen time is used up (§8).

## Revision 2 (the user's answers and additions, 2026-11-15)

- **D1: no "Be om mer tid" in the panel at all** (*"Trenger egentlig ikke mer tid der? Det tenker ikke et barn på før
  tiden er gått ut og telefonen låst"*). The time line stays. The `more_time` disc from design 99 stays a choice, as it
  is. D2 is moot. D3, D4, D6, D7 and D8 as recommended. D5 as recommended, with the rule below (§9).
- **One request open at a time** (§14.1). A request stays open until a parent answers or the phone's day ends. r13's
  30 minutes go. The kid can't send another while one is unanswered. After an answer the kid may ask again, at most
  three a day as today.
- **The parent's answer card in the PWA** (§14.4; mockup `Foresporsel.dc.html`, approved *"ser bra ut"*). While a
  request is open, the phone's summary shows "Emma ber om mer skjermtid" / "14:02 · Skjermtiden er brukt opp" with
  +15 min, +30 min, +1 t and Nei. It sits after design 101's header, never as the sheet's first child. It replaces
  today's "Asked for more time at 14:02" note. One parent's answer closes it for every parent. The push stays and opens
  the card.
- **"Nei" reaches the phone, and the kid sees who answered** (§14.2, §14.3; the phone mockup `Telefon.dc.html`):
  "Pappa svarte nei kl. 14:10", "Mamma ga deg 30 min", with the parent's display name (design 62). **This is an API
  change**: new keys in the policy's `time_policy`, the contract `time_request.json` extended, a capability
  `time_answer_v1` (§14.5).
- **The QA's findings** are fixed in §2, §4, §8 and §10 (§15 lists each).

**The short version.** `ShortcutsService` goes. The anchor (`CommandListenerService`, id 1002) is the one service and
posts one notification. It shows the colorized Snarveier panel while Snarveier is shown. Otherwise it shows r13's plain
notice. The switch is a `notify(1002, …)` that moves the notification to the other channel. On `jelly36` that kept one
record and the foreground state through every swap, with no blank frame (§1). The panel gains a line of its own over the
row: the time left. Under it, when there is something to say, comes a second line: "Spurt kl. 14:02 · venter på svar",
"Pappa svarte nei kl. 14:10" or "Mamma ga deg 30 min". The panel has no button of its own. One poster on one thread
posts every update of id 1002, at most once a second. The time-rule screen of the used-up budget gets a small card under
the rule card: "Be om mer tid", then "Spurt kl. 14:02 · venter på svar", then the parent's answer with "Spør igjen". A
request stays open until a parent answers it on the phone's summary card or the day ends. The answer comes back to the
phone in the policy, with the parent's name.

## 1. Measured (`jelly36`, Android 16 Play image, 480 x 854 at 240 dpi, 2026-11-15)

The probe was a throwaway app, built with the SDK's tools in the scratchpad (`d100/probe`, never in the repo). It was
the device owner, in lock task with features 63, `targetSdk` 36 like the launcher. It ran one `specialUse` foreground
service that started as the anchor does: `startForeground(1002, plain)` on a MIN channel, without
`FOREGROUND_SERVICE_IMMEDIATE`. `notify(1002, …)` then swapped that notification between the plain notice (MIN channel
`anchor_min`) and design 99's panel: LOW channel `snar_low`, colorized `#0C0C14`, `DecoratedCustomViewStyle`, one row
of four 40 dp discs in 48 dp cells, `FOREGROUND_SERVICE_IMMEDIATE`, `VISIBILITY_SECRET`. Records came from `dumpsys
notification --noredact` and the service's state from `dumpsys activity services`. Sizes came from `uiautomator dump`
(px / 1.5 = dp), and the swap with the shade open from `screenrecord` at 10 frames a second. `jelly36` matched
`~/kid-look-1-avd-pre-start` before the run (22 of 22 sums, the same file list). It ran alone under `flock -o
~/emulator.lock` and was restored from that copy afterwards (22 of 22 sums, the same file list).

| # | What | Result |
|---|---|---|
| M1 | **Swapping one FGS notification between channels** (`notify(1002)` plain MIN -> panel LOW -> plain) | **One record at every step** (`id=1002`, its channel the new one), and `isForeground=true foregroundId=1002` throughout. The flags keep `FOREGROUND_SERVICE` and `NO_DISMISS`. The MIN record is shown at importance 2 (Android lifts a foreground service's notification to LOW) |
| M2 | The swap **with the shade open** | The row changes **in place**: a ~300 ms height animation, **no blank frame, no second row**, and back the same way. With the shade open SystemUI keeps the order, so the panel stays in "Silent" where the plain one was |
| M3 | The swap **with the shade closed** | At the next pull the panel is **first, colorized and unfolded**, not bundled. Back to plain: one line in "Silent" again, folded |
| M4 | Moving the FGS to another id instead (`startForeground(1011, …)` while on 1002) | Android cancels 1002 and shows 1011. One record, `foregroundId=1011`. It works, but not needed (§2.1) |
| M5 | **12 swaps in 7 ms** | `NotificationService: Package enqueue rate is 6.08. Shedding 0\|me.vibb.probe.one\|1002`: one update dropped, no crash. The rate limit is per package (`mMaxPackageEnqueueRate=5.0`), and a dropped **last** update would leave the wrong view up. So the poster needs a trailing post (§2.3) |
| M6 | **The panel's channel blocked** (importance 0, set by the probe itself re-creating its channel, not by a user's "Turn off": Q100-L9) while the FGS notification is on it | The notification **leaves the shade** (SystemUI's listener: "notification removed"), and the service **stays foreground** (`isForeground=true`). A post on the blocked channel is accepted without an error. A swap to the MIN channel shows it again. An app-side lowering sends no `ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED`, so the swap to plain on a **real** block is not measured (§11 check 7, owed) |
| M7 | **`notify(1002)` on a channel that doesn't exist** | While the FGS notification was **still deferred** (the plain one, within its ~10 s): **the app crashed** when the deferral ran out (`exit-info`: `reason=4 APP CRASH(EXCEPTION)` at the moment the notification would have shown; the ActivityManager had adopted the bad notification as the service's). Once the panel was up, the same call only logged `No Channel found … id=1002` and the old panel stayed. **So every build of id 1002 must have its channel created first** (§2.2) |
| M8 | The plain notice without `FOREGROUND_SERVICE_IMMEDIATE` | Shown ~10 s after `startForeground` (as design 99's S6). The anchor's plain notice is deferred like this today |
| M9 | After a reinstall (the process killed) | **No notification of the package left**. An update removes design 99's panel (id 1011) with its service |
| M10 | **The gear** `baseline_settings_24` (`fillColor="?android:textColor"`) as a small icon | **An empty disc**, both as the MIN row's icon (today's "Vibb One") and in the colorized header. The theme attribute doesn't resolve in SystemUI. **A copy with a literal white fill** draws a dark gear in a light disc in the colorized header. The Vibb mark draws its rings in the accent disc on the plain row. Once, the first panel post right after a plain one with the attribute gear showed an empty disc although it carried the white gear; two later swaps drew it right. Not reproduced, a build check (§11 check 4) |
| M11 | **Subtext width** in the colorized header ("One probe · …", like "Vibb One · …") | 108 dp left for the subtext at font scale 1.0. "1 t 20 min igjen i dag" fits exactly, "Skjermtiden er brukt opp i dag" is cut ("Skjermtiden er bruk…"), and "Spurt kl. 14:02" fits at 1.0 and is cut at 1.3 ("Spurt kl. 14:…") |
| M12 | `setContentTitle` / `setContentText` on the decorated custom view | **Not shown** anywhere (the custom view takes their place) |
| M13 | **A line of our own over the row** (the big view, 14 sp, white) | 220 dp wide. "Skjermtiden er brukt opp i dag" fits at 1.0. At 1.3 it is cut on one line (so it may wrap, §3.2). The line costs **19-20 dp** |
| M14 | **Android's action** "Be om mer tid" with `setAuthenticationRequired(true)` | Shown **under the row** as a text button in white on the colorized ground, unfolded only. It costs **60 dp** |
| M15 | **Our own pill** "Be om mer tid" at the end of the line | The pill is 110 dp wide, so the nb line in front of it is cut ("1 t 20 min igje…") |
| M16 | Heights, unfolded (the whole notification) | The row only: **118 dp**. With the line: **137 dp**. With the line and the action: **177 dp** at 1.0, **183 dp** at 1.3. The shade is 537 dp (design 99 S1) |

Not measured: whether an IMMEDIATE update that lands inside the plain notice's ~10 s deferral shows at once (§2.4).
Also not measured: the launcher itself, a real role-holding phone, the Jelly Star's SystemUI and the time-rule screen
(§11, §12).

What this means:

- **One id, two channels, `notify` swaps them (M1-M3).** No second notification ever exists, so r13's rule holds
  literally. No `startForeground` call or second service is needed after the first.
- **The time text can't live in the header (M11, M12).** It goes in a line of our own (M13), visible unfolded. Android
  always unfolds the first notification (design 99 S2).
- **Android's action costs 60 dp (M14), a pill of our own doesn't fit in nb (M15).** Revision 2: the user dropped the
  panel's "Be om mer tid" altogether (D1), so neither is built.
- **The channel must exist before every build (M7)**, and the poster must always post the last state (M5).
- **The gear needs a white copy (M10).** The approved gear is kept, drawn so it shows.

## 2. The mechanism

### 2.1 One service, one id

- **`ShortcutsService` is removed** (its class, its manifest entry and its `specialUse` subtype). Design 99 §2.4's start
  and stop logic goes with it: `startForegroundService`, `stopNow`, `serviceStarted`/`serviceGone`,
  `notificationForStart` and `startAskedAtMs`. That leaves two foreground services in the process, the anchor and the
  VPN's (Q99-L6 had three).
- **The anchor posts the panel** under its own id **1002** (`COMMAND_LISTENER_NOTIFICATION_ID`). The service type
  stays `specialUse` (both services had it). The manifest's `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` gains the panel:
  "Parental control enforcement (device owner): screen-time and time-rule signals, policy sync, the kid's quick
  controls".
- **Two channels under the one id, unchanged:**
  - the plain notice on `launcher:command_listener` (MIN);
  - the panel on `launcher:shortcuts` / `launcher:shortcuts.N` (LOW, `VISIBILITY_SECRET`, no badge, design 99 §2.1, §2.6).
- **Why not move ids (M4):** cancel-and-show on another id gains nothing, and it would be the one place where a gap
  could appear. `notify` on the one id is an update of one record (M1, M2).
- **The runtime never starts or stops the anchor.** The anchor's life stays design 07's: started at enrolment, at
  boot, by every sync request, and `START_STICKY`. Snarveier only decides what the anchor's notification shows. A panel
  wanted while the anchor isn't running (BFU before the first unlock, a crash before its restart) shows when the anchor
  comes back. Nothing else posts it.

### 2.2 One poster (`server/AnchorNotification.kt`, new glue)

One object owns every post of id 1002. It merges `ShortcutsRuntime.post` with `AnchorNotice.post` and their two
"last posted" caches:

- **Its own context (Q100-M1).** The poster keeps the application context from whichever caller reaches it first:
  `forStart`, `notice(...)`, or `ShortcutsRuntime.init`. It doesn't depend on `ShortcutsRuntime.init`, which runs in
  `Application`'s skippable tail. `init` only adds the platform's signals.
- **One thread.** It uses `ShortcutsRuntime`'s single-thread `shortcuts-refresh` executor, which moves here as the
  notification's thread. `AnchorNotice.refresh` (main thread, the tracker's minute tick, the tap thread) still computes
  the time part, because it holds the snapshot. It hands it over (`AnchorNotification.notice(time)`, §2.5) and asks for
  a refresh. It no longer posts.
- **What is posted** is the pure `anchorView(panel, time)` (§2.5): `Plain(time)` or `Panel(panel, time)`.
  - **One source for the time part (Q100-L6):** the poster first sets `ShortcutsRuntime`'s ask cache (the Mer tid
    disc's state) from the time part it was handed, then builds the panel. The disc and the line can't disagree in one
    post.
  - **Never nothing (Q100-M1):** with no panel model yet, or with `computePanel` throwing, the view is
    `Plain(time)`, so the time left still shows.
- **Only a change is posted.** The key is `(view, locale, channel id)`. A new anchor's `onCreate` sets the key to what
  it started with (as r13's `reset`).
- **At most one post a second, always with a trailing post** (design 99 Q99-L7's `shortcutPostDelay`, now for both
  views). The first change after a quiet second posts at once. Any change within the second is posted once, at its
  end, from the state at that moment. Android's limit is per package (M5). The VPN's 1003 posts rarely and isn't
  coalesced.
- **A shed update is caught (Q100-L4).** Android drops updates over its rate without an error (M5), and the
  launcher's other notifications in the same second count too. In the trailing slot the poster reads
  `getActiveNotifications()` and compares id 1002's channel with the key's. On a mismatch it forgets the key and posts
  again. Taps stay gated by `shortcutTapAllowed`'s `panel.shown`, so a stale panel can't act meanwhile.
- **Channels first (M7).** Right before every build, on the same thread, the poster creates the view's channel. That
  is the listener channel with `createNotificationChannel` (idempotent; it doesn't undo a kid's block) or the panel's
  `ensureChannel`. A build never names a channel it hasn't just made sure of.
- **Only while the anchor runs, exactly (Q100-L5).** The `isRunning` check and the `notify` are one step under the
  poster's lock. The anchor's `onDestroy` takes the same lock before it clears `running`. So no post can land after the
  anchor is gone and leave a notification no service owns.
- **`showAgain` runs on the poster's thread (Q100-L8).** The picker's "Vis i varsellisten igjen" hands the whole task to
  the poster's executor: the check that the posted view is plain, the delete, and the bump of the channel number. That
  orders it against the next build's ensure (M7).

### 2.3 The anchor's start

```kotlin
override fun onCreate() {
    super.onCreate()
    startForeground(COMMAND_LISTENER_NOTIFICATION_ID, AnchorNotification.forStart(this))
    ...
}
```

- `forStart` stays literally first (the existing pin, `QuietNotificationsTest`). It **never throws**:
  - it builds the view this process last posted, from the last key, including that key's channel id (Q100-L7). It
    makes sure of that channel (one idempotent `createNotificationChannel`) and records the view as posted. It never
    calls `computePanel` and never reads the shortcuts prefs: no Bluetooth, music or DPM reads before
    `startForeground`;
  - with no key yet (a fresh process: `ShortcutsRuntime.init` runs in `Application`'s tail), or on any exception, it
    returns r13's `AnchorNotice.plain` on the listener channel, made sure of first.
- Then the anchor asks for a refresh (r13's `refreshNow` stays), and the poster swaps to the panel if it is wanted.
- **Deferral (Q100-L2).** The plain notice has no `FOREGROUND_SERVICE_IMMEDIATE`, so Android normally shows it ~10 s
  after `startForeground` (M8). At boot the panel (with IMMEDIATE, design 99 S6) normally replaces it before it is ever
  shown, so the kid never sees "Vibb One" first. Android shows an FGS notification **with an action** at once, though,
  so a plain notice with r13's "Be om mer tid" (Snarveier off, a budget day) isn't deferred. That is harmless, and it
  shortens M7's window. Whether an IMMEDIATE update inside the deferral shows at once was not measured (§11 check 5). If
  it doesn't, the panel shows ~10 s after boot, which nobody watches.
- **Design 99's old panel (id 1011)** goes with its service at the update (M9). `onCreate` also cancels 1011 once, at
  no cost.

### 2.4 When the view changes

| Event | What happens |
|---|---|
| Boot, process start | The plain notice (deferred), then the panel at the first refresh |
| Enrolment before a policy | No `Ok` cached policy: plain. The first sync's policy write (`prefsListener`) brings the panel |
| Bit 128 off / on (a sync) | The next refresh: `notify(1002)` with the other view. In place if the shade is open (M2), sorted at the next pull (M3) |
| The last item leaves (a parent switch, the role gone) | Plain. Design 99 §2.2: no item, no panel |
| The kid turns off the panel's channel (S16; not possible on a phone where Vibb holds the dialer or SMS role, design 99 §14) | The notification leaves the shade at once (M6). `ShortcutsRuntime.hidden` reads importance 0 at `ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED` and the panel isn't shown, so the next refresh posts the **plain notice** on the listener channel (the approved rule). The service stays foreground. M6 lowered the importance from the app, so the swap on a user's block is still to be checked (§11 check 7, Q100-L9) |
| "Vis i varsellisten igjen" (design 99 §2.6) | On the poster's thread (§2.2): deletes the blocked channel only while the plain view is up, then makes the next one. AOSP refuses to delete a channel that holds a foreground-service notification (read from the source, not measured); `runCatching` keeps that harmless |
| The time part changes (the minute tick, a lift, the request, an answer) | A new time part gives a new view: one post (≤ 1/s) |
| Vibb's PIN lock (LOCKED) | Nothing changes. The shade can't open (`featuresWhileLocked`, design 99 §2.2) |
| Android's keyguard | Nothing changes. The panel is secret (S14). The plain notice is a silent MIN notice, as today, and its action needs the unlock |
| A locale change | Both channels renamed (`languageChanged`). The key's locale differs, so one post |
| The anchor restarts in a live process | `forStart` starts with the last view directly |
| The anchor stops (never by Snarveier) | Android removes its notification. The next anchor starts again (§2.3) |

### 2.5 The view (pure, `server/AnchorView.kt`, `AnchorViewTest`)

The time part is r13's `TimeLeftNotice` widened by revision 2's request states (§14.3): `text` (the time left, used
up, or none) and `note` (the second line), plus `canAsk` (r13's action on the plain notice, and the Mer tid disc).

```kotlin
sealed interface AskNote {
    data class Sending(val atMs: Long, val offline: Boolean) : AskNote   // "Spurt kl. 14:02 · sender …" / "· sendes når telefonen er på nett"
    data class Waiting(val atMs: Long) : AskNote                        // "Spurt kl. 14:02 · venter på svar"
    data class No(val by: String?, val atMs: Long) : AskNote            // "Pappa svarte nei kl. 14:10"
    data class Given(val by: String?, val minutes: Int) : AskNote       // "Mamma ga deg 30 min"
    data object None : AskNote
}
data class TimePart(val text: TimeLeftText?, val note: AskNote, val canAsk: Boolean)

sealed interface AnchorView {
    data class Plain(val time: TimePart) : AnchorView
    data class Panel(val panel: ShortcutsPanel, val time: TimePart) : AnchorView
}

fun anchorView(panel: ShortcutsPanel?, time: TimePart): AnchorView =
    if (panel == null || !panel.shown) AnchorView.Plain(time) else AnchorView.Panel(panel, time)
```

Design 99's panel rules (`shortcutsShown`) and r13's time-left rule are unchanged. `anchorView` only combines them, and
`timePart` (§14.3) makes the time part.

## 3. The panel with the time

### 3.1 What it shows

Unfolded (the normal view: always first, so Android unfolds it, design 99 S2):

```
[gear]  Vibb One · Snarveier                       ^
        1 t 20 min igjen i dag                         <- the time line (only with a budget today)
        Spurt kl. 14:02 · venter på svar               <- the note line (only with a note, §14.3)
        ( o )  ( ● )  ( o )  ( o )                     <- the row, unchanged
```

- **The header** stays "Vibb One · Snarveier" (`shortcuts_subtext`). The time doesn't fit there (M11), and the
  title and text fields aren't shown (M12).
- **The time line**: r13's words exactly (`anchor_time_left` "%1$s igjen i dag" / "%1$s left today",
  `anchor_time_used_up` "Skjermtiden er brukt opp i dag" / "Screen time is used up today"), 14 sp, the
  notification title's face (`TextAppearance.Material.Notification.Title`), white. Not Nunito: SystemUI inflates
  RemoteViews in a restricted context, where a TextView ignores an app's font resource, and `nunito_bold` drew the
  default regular face (emulator, 2026-11-15; the note line takes `TextAppearance.Material.Notification`).
  **Two lines at most** (`maxLines=2`, ellipsized after the second): at font scale 1.3 the used-up line wraps rather
  than being cut (M13). Only in the big view (`setCustomBigContentView`). The folded view keeps the row alone,
  since design 99's folded content is limited to 48 dp (S8).
- **The note line** (§14.3's `AskNote`): 13 sp, white at 70 %, text only, never a button (R13-8). It shows only with a
  budget today, under the time line. A `Given` note from a lift without a request (D10) shows here too.
- **No "Be om mer tid" in the panel** (D1, revision 2). The kid who wants it in the shade picks the Mer tid disc
  (design 99), which is usable while `canAsk` and dimmed with "Mer tid, spurt" while a request is open.
- **Without a budget today, in the pause or the override:** no time line and no note. The panel is exactly design 99's.
- **The row** is unchanged (design 99 §3, revision 3).
- **Small icon: the gear**, a new `ic_stat_gear.xml`: a copy of `baseline_settings_24`'s path with
  `fillColor="#FFFFFFFF"` instead of `?android:textColor` (M10's measured look; Material Symbols' settings glyph,
  Apache-2.0, as the original).

Folded (the kid folded it, or a call's notification is first, design 99 §14): the small icon, the row and the chevron.
No time line, no note. The next pull unfolds it again (S2).

### 3.2 Heights (M16)

| State | Unfolded | Share of the 537 dp shade |
|---|---|---|
| No budget today | 118 dp (as design 99) | 22 % |
| A budget today | 137 dp | 26 % |
| A budget today and a note | ~157 dp (estimated: one more line of 13 sp) | 29 % |

### 3.3 TalkBack

The time line and the note are plain text. The discs keep their content descriptions (design 99 §3).

### 3.4 With Snarveier off: the plain notice (D3)

r13's notice, widened by revision 2's note. On the MIN channel it sits at the bottom in "Silent" as one line: the
title "Vibb One", or the time left as its title. The note (§14.3) is its content text, where r13's "Spurt kl. 14:02"
was. r13's action "Be om mer tid" stays on this notice while `canAsk` (D1 was about the panel). Design 99 S4 measured
that two silent notifications of one app are bundled. While the filter's "kobler til …" notice is also up, the two can
fold into one "Vibb One · 2" bundle, **as today**. The plain notice gets the Vibb mark (§4), which draws (M10).
Today's gear drew an empty disc.

## 4. Icons

| Notification | Today | After |
|---|---|---|
| The panel (id 1002, panel view) | `ic_stat_vibb` | **`ic_stat_gear`** (new, white fill; M10) |
| The plain anchor (id 1002, plain view, `AnchorNotice.build`) | `baseline_settings_24` (draws empty, M10) | `ic_stat_vibb` |
| Play's install mode (`play/PlayRuntime.kt:245`) | `baseline_settings_24` | `ic_stat_vibb` |
| The ring (`server/LocateCommands.kt:591`) | `baseline_settings_24` | `ic_stat_vibb` |
| The assistant's setup notice (`assistant/AssistantSetup.kt:314`) | `baseline_settings_24` | `ic_stat_vibb` |
| The filter's "kobler til …" (`server/KidVpnService.kt:566`) | `baseline_bug_report_24` | `ic_stat_vibb` |
| Calls, missed calls, SMS | their own | unchanged |
| Not named by the user: the window's "Lock again" (`ic_window_open`), app installs (`stat_sys_download`/`_warning`) | their own | unchanged |

`baseline_bug_report_24.xml` then has no reader and is removed. **`baseline_settings_24.xml` stays**: the app list's
settings button uses it (`res/layout/activity_list.xml:39`, MIT code; Q100-L1). A source test pins that every
`ic_stat_*` drawable has literal colours, never a `?` attribute (M10).

## 5. Channels

- **No channel id changes.** The plain notice stays on `launcher:command_listener`, the panel on
  `launcher:shortcuts[.N]`, and the kid's block of either carries over. No cleanup is needed.
- **The listener channel is renamed "Vibb One"** (D7, nb and en; was "Finn enheten – bakgrunnstilkobling" / "Find My
  Device background connection", a leftover from design 07). That is the name the kid reads in the long-press "Turn
  off" sheet. Only the name changes.
- **What the kid's long-press "Turn off" does now** (on a phone without the dialer or SMS role; with them, every
  `launcher:` channel is locked, design 99 §14):
  - on the panel: the panel channel off -> the panel hidden, and the plain notice (with the time left) comes back on
    the listener channel (§2.4);
  - then on the plain notice: the listener channel off -> nothing of the anchor's in the shade. The time left is gone
    from the shade for that kid. The time-rule screen's card (§8) still offers the request when it matters.
- **For the queued "Blocked notification channels named in the PWA" (after this design):** it must report both of the
  anchor's channels: `launcher:command_listener`, and the panel channel in use (`launcher:shortcuts.N`, read through
  `ShortcutsRuntime`'s id, never a fixed string). It also reports Meldinger's and the missed calls'. Its PWA line must
  say what is lost: with both off, the shade shows neither the shortcuts nor the time left. With only the panel's off,
  the plain notice shows the time left, and design 99's "hidden" line already covers it (`shortcuts.hidden`). The item
  stays moot on role-holding phones.

## 6. The server and the API

Snarveier needs no API change. Nothing in `server/testdata/shortcuts.json` changes:

- `shortcuts.shown` keeps its meaning: the model says shown, which now means "the anchor's notification is the panel".
- `hidden` and `items` are unchanged.

**Revision 2's request round trip is an API change** (§14.5): new `time_policy` keys, `time_request.json` extended,
the capability `time_answer_v1`. Server and launcher go in one commit with the contract.

One PWA string (D8): the Home Screen page's ⓘ (`device.home.shortcuts_info`) gains a sentence. Revision 2: without
the panel's button it names only the time.

- nb: "Når {name} har skjermtid i dag, viser den også tiden som er igjen."
- en: "On days with screen time it also shows the time left."

## 7. Design 99 and r13: what changes in their rules

- **r13 "never a second notification"**: holds. One id, one record (M1). "Nothing else of ours in the shade" (U5)
  was already traded for Snarveier by the user in design 99. This design removes the second notification design 99
  added.
- **r13 §2.3's "posted only while the anchor runs" and the reset in `onCreate`**: kept, in the one poster (§2.2-§2.3).
- **r13 §2.2's builder** (`AnchorNotice.build`): kept for the plain view, with the Vibb mark and the note as content text.
- **r13's 30-minute request** (R13-8's `openUntilMs`, the boundary alarm for its end): replaced by revision 2's one
  open request (§14.1).
- **r13 §2.7's "Asked for more time at 17:21"** on the summary (`open_extra_time_request`, `.quick-note
  data-asked`): replaced by the answer card (§14.4).
- **Design 99 §2.1** ("separate from the anchor, whose notification stays exactly as it is") and **§2.4** (the
  service's life) are replaced by §2 here. Design 99's header gets one line pointing here.
- **Design 99 §2.3's throttle**: now for every post of id 1002 (§2.2).
- **Design 99's `more_time` item**: unchanged in the picker. It is the shade's one way to ask while Snarveier shows
  (D1). It is usable while `canAsk` and dimmed ("Mer tid, spurt") while a request is open.

## 8. "Be om mer tid" on the time-rule screen

### 8.1 Why

The request lived in the shade: the anchor's action and the optional Mer tid disc. On the time-rule screen
(`LockActivity`, after the PIN) the shade can be pulled down, but a kid whose screen time just ran out looks at the
screen, not the shade. The screen gets the button where it is looked for.

### 8.2 When it shows (pure `lockAsk`, `ui/TimeLockModel.kt`, `TimeLockModelTest`)

```kotlin
sealed interface LockAsk {
    data object Button : LockAsk                                        // "Be om mer tid"
    data class Note(val note: AskNote, val again: AgainPart) : LockAsk  // the note line, then "Spør igjen" or the cap
}
enum class AgainPart { ASK_AGAIN, CAPPED, NONE }

fun lockAsk(ruleInForce: Boolean, budgetToday: Boolean, windowOpen: Boolean, enrolled: Boolean,
            state: RequestState, note: AskNote): LockAsk? =
    if (ruleInForce || !budgetToday || windowOpen || !enrolled) null
    else when (state) {
        RequestState.Idle -> if (note == AskNote.None) LockAsk.Button else LockAsk.Note(note, AgainPart.ASK_AGAIN)
        is RequestState.Pending, is RequestState.Waiting -> LockAsk.Note(note, AgainPart.NONE)
        is RequestState.Answered -> LockAsk.Note(note, if (state.canAskAgain) AgainPart.ASK_AGAIN else AgainPart.CAPPED)
        RequestState.Capped -> LockAsk.Note(note, AgainPart.CAPPED)
    }
```

- **The used-up budget's screen only** (D4): `ruleInForce` = the screen names a rule (`rule != null` in
  `LockActivity.render`). Under school, bedtime or a custom rule it doesn't show, even with a budget set today, for
  three reasons:
  - more minutes don't open a phone a rule locks;
  - a button on the bedtime screen invites a request every night;
  - the shade's ways to ask stay there (the Mer tid disc, the plain notice's action).
- `budgetToday` = `snap.budget?.effectiveMinutes != null`. It is true on the used-up screen by definition, and also on
  "Ingen skjermtid i dag" (a budget of 0 today). The kid may ask then too, as the notice allows.
- `windowOpen` (the pause, the override) and `enrolled` are as `askStep`. The screen isn't up in a window anyway.
- `state` and `note` are §14.3's, the same as the panel's.
- **A "yes" ends the screen**: the lift unlocks the phone, and the screen finishes as today (`lockReason` NONE). If
  the given minutes run out too, the used-up screen comes back with "Mamma ga deg 30 min" and "Spør igjen" (or the cap).

### 8.3 Where it sits and what it says (the phone mockup `Telefon.dc.html`)

`activity_lock.xml` gets one block, `lock_ask`, **directly under the rule card and above the rows card**: 14 dp under
the rule card (as the rows card is), with no card ground of its own. It reads as the lower half of the mockup's card,
under the rule card's hourglass and "Skjermtiden er brukt opp".

| State | What shows |
|---|---|
| Button | A full-width pill, 52 dp, radius 26 dp, `kid_accent` fill, "Be om mer tid" (`anchor_ask_more_time`) in `kid_ground`, `nunito_extrabold` 17 sp. The pill is the button (`ButtonRole`) |
| Note, no again part (Sending, Waiting) | A pill the same size in a new `kid_lock_pill_dim` (`#2A2836`, the mockup's), not clickable, the note's text centred in `kid_core` 16 sp: "Spurt kl. 14:02 · venter på svar", "Spurt kl. 14:02 · sender …", "Spurt kl. 14:02 · sendes når telefonen er på nett" |
| Note, ASK_AGAIN (answered, or a lift without a request) | The note as a line, 16 sp, `kid_core` ("Pappa svarte nei kl. 14:10."), then a 52 dp outline pill (1.5 dp `kid_accent` ring on `kid_lock_pill_dim`, text `kid_accent`): "Spør igjen" (`lock_ask_again`), the button |
| Note, CAPPED | The note line (when there is one), then "Du har spurt tre ganger i dag." (`lock_ask_capped`) as a line, `kid_core_dim`. No button |
| `null` | The block is gone |

Lines wrap and are never cut.

Why not inside the rule card: that card is one view shared with the glance. It is information only and one TalkBack
stop (r13 U3, launcher round L6). A button inside it would change the glance's card too. Why not a row in the rows card:
the rows are "what still works" and they open things. The rows card keeps its rows (`timeLockRows` is unchanged).

**The glance (the PIN lock's page) is unchanged**: its rule card stays information only (U3). The button is behind
the PIN, on the time-rule screen.

**After the unlock (D6).** PLAN 2026-11-02 opens the card's only row straight after the PIN (`unlockOpenRow`). On the
used-up screen that is often the music app or Kalender alone. While the ask block shows a button ("Be om mer tid" or
"Spør igjen"), there are two things to do, so the screen stays: `unlockOpenRow(marked, handedOver, rows, askButton)`
returns `null` while `askButton` is true. Otherwise it behaves as today.

### 8.4 The tap and the updates

- **One request path.** `TimeAskReceiver`'s body moves to `timerules/TimeAsk.kt` (`TimeAsk.askNow(context)`: the
  snapshot, `windowOpen`, `TimeRequests.ask`, `AnchorNotice.refresh`, `SyncRunner.request(.., "time_request")`; no
  boundary alarm for an `openUntilMs` any more, §14.1). The receiver, the Mer tid disc (`ShortcutsRuntime.askForTime`)
  and the screen call it. The screen calls it only on a tap of "Be om mer tid" or "Spør igjen". `askStep` decides again
  inside, so a stale screen can't ask twice.
- **The screen follows the request, on the main thread (Q100-H1).** `AnchorNotice` gains `addListener`/`removeListener`.
  Whenever its computed time part changes, it **posts** the listener loop to the main `Handler`, whatever thread
  `refresh` ran on: main, the tracker, or the `shortcuts-tap` thread of a Mer tid tap in the shade over the used-up
  screen. `LockActivity` listens from `onStart` to `onStop`. Its listener only calls `render()`, which re-reads the
  snapshot and the request state itself and never trusts a value passed in.
- **Nothing is missed when the anchor isn't running (Q100-L3).** `refreshNow`'s early return moves below the time
  part's computation and the listener dispatch. Only the post needs the anchor. The screen also re-reads in `onStart`
  and at every boundary alarm's `recheck` (its `lockKey` listener).
- **"Sendes når telefonen er på nett" (Q100-L10).** A request still Pending 15 s after the tap says that instead of
  "sender …" (`timePart`, from the record's `askedAtMs`). While the screen shows a Pending note, it re-renders once at
  that mark (one `Handler` callback, removed in `onStop`).
- **TalkBack.** The block is one stop for its note and one for its button. The buttons read "Be om mer tid, knapp" and
  "Spør igjen, knapp". The note pill and the lines are text, with `accessibilityLiveRegion="polite"`, so the change to
  "venter på svar" and to the answer is read out without moving focus.
- **i18n**, the phone's strings (r13's `anchor_*` stay):

  | Key | nb | en |
  |---|---|---|
  | `ask_note_sending` | Spurt kl. %1$s · sender … | Asked at %1$s · sending… |
  | `ask_note_offline` | Spurt kl. %1$s · sendes når telefonen er på nett | Asked at %1$s · sends when the phone is online |
  | `ask_note_waiting` | Spurt kl. %1$s · venter på svar | Asked at %1$s · waiting for an answer |
  | `ask_note_no` | %1$s svarte nei kl. %2$s | %1$s said no at %2$s |
  | `ask_note_no_unnamed` | Svaret ble nei kl. %1$s | The answer was no at %1$s |
  | `ask_note_given` | %1$s ga deg %2$s | %1$s gave you %2$s |
  | `ask_note_given_unnamed` | Du fikk %1$s mer | You got %1$s more |
  | `lock_ask_again` | Spør igjen | Ask again |
  | `lock_ask_capped` | Du har spurt tre ganger i dag. | You've asked three times today. |

  The name is the parent's display name as typed (§14.2). The minutes use `durationText` ("30 min", "1 t"). The
  clock uses `ClockFormat`. "Tre" is D5's fixed three: a test pins `TIME_REQUESTS_A_DAY == 3` next to the string's test
  (Q100-L10). A Capped that comes from the server's own count (its zone's day) while the phone's count is lower is rare
  and shows the same.

### 8.5 The parent's side

The answer card on the summary (§14.4).

## 9. Decisions

Revision 1's, with the user's answers (2026-11-15):

- **D1. "Be om mer tid" in the panel**: **No button in the panel at all** (the user). The time line and the note line
  stay. The Mer tid disc stays a choice.
- **D2.** Moot (it was about the panel's button and the Mer tid disc).
- **D3. With Snarveier off, the plain r13 notice comes back as today**: **yes**.
- **D4. The time-rule screen's ask only on the used-up budget's screen**: **yes**.
- **D5. The screen says why there is no button**: **yes**, with the one-open rule (§14.1). The states are "sender …",
  "sendes når telefonen er på nett", "venter på svar", the answer, and "Du har spurt tre ganger i dag."
- **D6. The ask keeps the screen after the unlock**: **yes** (also for "Spør igjen").
- **D7. The anchor's channel renamed "Vibb One"**: **yes**.
- **D8. The ⓘ names the time left**: **yes** (§6, without the button's words).

New in revision 2, for the user (my recommendation in each):

- **D9. A request asked under a rule.** The shade's ways to ask (the Mer tid disc, the plain notice's action) work
  under a rule with a budget today (r13 D3). More screen time doesn't open a rule's lock, so on such a request the card's
  +15 min / +30 min / +1 t **pause the rules in force** for that long (the lift `target=rule`, `rule=all`, as Quick
  actions' "Pause regler"). The labels are the same, and the sub-line names the rule ("21:40 · Leggetid"). **Recommendation:
  yes.**
- **D10. Time given without a request shows on the phone too.** When a parent adds minutes from Quick actions with no
  request open, the panel's note line says "Mamma ga deg 30 min" for 30 minutes (and the used-up screen, if that time
  runs out too). **Recommendation: yes.** It is the same line, and the name rides on the lift (§14.2).
- **D11. No display name, no name.** A parent without a display name (design 62) is never shown by login name on the
  phone, since a login can be an e-mail address. The phone then says "Svaret ble nei kl. 14:10" / "Du fikk 30 min mer".
  **Recommendation: yes.**
- **D12. How long an answer shows.** On the used-up screen it shows as long as it is today's newest answer (until the
  kid asks again or the day ends). In the panel's note line, and as the plain notice's content text, it shows for 30
  minutes after the answer, so the shade isn't cluttered all evening. **Recommendation: yes.**

## 10. Tests (lean)

Unit:

- **`AnchorViewTest`** (new): `anchorView` for each case:
  - no panel model or not shown -> `Plain` (Q100-M1's case included);
  - shown -> `Panel` with the time part as handed;
  - equal inputs -> equal views (an unchanged view posts nothing).
- **`TimeRequestsTest`**: `requestState`, `timePart` and `uploadStep` with revision 2's rows, run from the contract's
  `states` rows (§14.5):
  - one open request until an answer or the day's end;
  - no 30 minutes;
  - an answer of the phone's id, no or yes, named or unnamed;
  - an older server (no `time_request_answers`): closed by a newer lift as r13;
  - Capped after three accepted;
  - Pending's `offline` at 15 s;
  - a `Given` note from a lift without a request for 30 minutes;
  - D12's 30 minutes in the panel.
- **`TimeLockModelTest`**:
  - `lockAsk`: a rule in force, no budget today, a window, not enrolled -> `null`; each state to its block;
  - `unlockOpenRow` with `askButton` (D6).
- The coalescing stays `shortcutPostDelay`'s test. The trailing post is pinned: a change inside the second schedules
  exactly one later post.

Source pins (the guard style of `QuietNotificationsTest`, `TimeLeftNoticeTest`, `ShortcutsTest`):

- no `ShortcutsService` class and no manifest entry; no `startForegroundService` or `stopForeground` under
  `shortcuts/`;
- `notify(COMMAND_LISTENER_NOTIFICATION_ID` only in `AnchorNotification.kt`, and no other `notify` of 1002 anywhere;
- `onCreate`'s first line after `super` is `startForeground(COMMAND_LISTENER_NOTIFICATION_ID,
  AnchorNotification.forStart(this))`; `forStart` catches every exception and falls back to `AnchorNotice.plain`;
  **`forStart` has no `computePanel(` and no `channelId(`** (Q100-L7);
- in `AnchorNotification`, every `build` is preceded by its channel's ensure on the same path (M7); the `notify` is
  inside the lock that `CommandListenerService.onDestroy` takes (Q100-L5);
- **the poster builds `Plain(time)` when the panel model is missing or throws** (Q100-M1), and sets the ask cache
  from the handed time part before `computePanel` (Q100-L6);
- **`AnchorNotice`'s listener loop is inside a main-`Handler` `post`** (Q100-H1); `refreshNow`'s anchor check comes
  after the listener dispatch (Q100-L3);
- the panel's builder sets `setColorized(true)`, `FOREGROUND_SERVICE_IMMEDIATE` and `VISIBILITY_SECRET` (the existing
  pins move with it), and **adds no action** (D1);
- the plain builder has no `FOREGROUND_SERVICE_IMMEDIATE` (§2.3);
- the small icons: the panel `ic_stat_gear`, and the five of §4 `ic_stat_vibb`; every `res/drawable/ic_stat_*.xml`
  without a `?` attribute (M10);
- `TimeAskReceiver`, `ShortcutsRuntime.askForTime` and `LockActivity` call `TimeAsk.askNow`, and nothing else calls
  `TimeRequests.ask`;
- `TIME_REQUESTS_A_DAY == 3` beside `lock_ask_capped`'s test (Q100-L10);
- the strings of §3, §8.4 and §14.4 exist in nb and en.

Server: §14.6's tests. `tests::shortcuts` renders the ⓘ with D8's sentence in nb and en.

## 11. Emulator checks (`jelly36` restored clean, density 240; a new section in `docs/testing/emulator.md`)

1. **One notification, both views.** The branch's debug launcher as device owner and enrolled, then Snarveier on, then
   bit 128 off and on again from the PWA. Check with the shade closed and with it open:
   - `dumpsys notification --noredact`: only `id=1002` for the package at every step, on the panel channel and then
     the listener channel;
   - `dumpsys activity services`: the anchor `isForeground=true`, no `ShortcutsService`;
   - with the shade open, the row changes in place; at the next pull the panel is first.
2. **A budget set today** (Device › Time):
   - the time line, and Nunito draws in SystemUI's RemoteViews (Q100-L10);
   - no button under the row (D1);
   - a Mer tid disc tap -> the note line "Spurt kl. HH:MM · venter på svar" after the sync;
   - nb and en, font scale 1.0 and 1.3 (the lines wrap, never cut); the heights against §3.2.
3. **With Snarveier off**: the plain notice with the time left, its action and the note as content text.
4. **The icons**: the panel's gear (dark in a light disc), the plain notice's Vibb mark, Play's install mode, the ring
   (a `ring` command), the assistant's setup notice, the filter's "kobler til …" (the VPN started before always-on). M10's
   one-time empty disc: watch for it on the first swap after boot.
5. **Boot**: after the unlock, does the panel replace the deferred plain notice before it shows (§2.3; log the post
   times and pull the shade early)?
6. **The update from a design-99 build**: one notification afterwards (id 1002), none at 1011.
7. **Hiding, in its full form (owed, Q100-L9)**: on a phone without the dialer and SMS roles (an emulator set up
   without them, or the Jelly Star with them off), turn off Snarveier from the long-press menu -> the plain notice;
   "Vis i varsellisten igjen" -> the panel on the new channel. On the role-holding emulator only the menu's "can't be
   modified" can be checked, which doesn't settle §2.4's row.
8. **The time-rule screen** (a budget of 1 min):
   - the used-up screen shows "Be om mer tid"; a tap -> "sender …" -> "venter på svar";
   - with the network off: "sendes når telefonen er på nett" at 15 s, and the request goes when it comes back;
   - the PWA's card: Nei -> "Pappa svarte nei kl. HH:MM" and "Spør igjen"; +30 min -> the screen goes, and the panel
     says "Mamma ga deg 30 min";
   - the third answered request of the day -> "Du har spurt tre ganger i dag.";
   - Kveld turned on now with a budget set -> no ask block (D4);
   - a used-up screen with one app row: after the PIN it stays on the screen (D6);
   - TalkBack: the buttons, then the live announcements;
   - gesture and 3-button navigation, nb and en.
9. **Locale**: `cmd locale set-app-locales` nb <-> en: the panel's lines, the channel names (D7), and the screen's
   block.
10. **The PWA card** (browser pass, 390 px, light/dark, JS off): §14.4's states, two parents (the second's stale tap),
    the push's target.

**Owed on the Jelly Star** (the emulator can't settle these):

- the swap and the sorting on its SystemUI (M1-M3);
- the gear in its header;
- the time line and note heights;
- the deferred-plain replacement at boot;
- the screen's block with a child's finger.

## 12. Risks

- **Another SystemUI.** M1-M3 are AOSP Android 16. An OEM shade could animate a channel swap as a remove and an add,
  or keep the old position longer. The approved fallback costs nothing: it is still one notification. Check 1 on the
  Jelly Star.
- **A missing channel crashes the launcher (M7).** It would crash the launcher in the deferral window, and a crash of
  the device owner's process ends calls. §2.2's ensure-before-build and its source pin are the guard. Channel ids
  never change here.
- **The rate limit (M5)** sheds updates over ~5 a second per package, counting all of the launcher's notifications.
  The poster's trailing post and its active-notification check (§2.2, Q100-L4) put the last state back.
- **The time is hidden while folded**: during a call, or after the kid folds the panel until the shade closes. That is
  accepted. The time-rule screen and Home's rule card are unchanged.
- **A request no one answers blocks asking for the rest of the day** (§14.1). That is the user's rule. The parent's push
  and the card at the top of the summary are its counterweight. With an older server (no answers), the only answer is a
  lift (§14.7).
- **Deleting a channel that holds the FGS notification** throws in AOSP (read, not measured). `showAgain` only
  deletes while the plain view is up, on the poster's thread (§2.2).
- **M10's one empty disc**, seen once and not reproduced. Check 4.

## 13. Build steps and sizes

1. **Launcher, the one notification** (one commit):
   - `AnchorView.kt` + `AnchorViewTest`;
   - `AnchorNotification.kt`: the poster, `forStart`, the channels, the throttle moved from `ShortcutsRuntime`, the
     active-notification check, the lock shared with `onDestroy`, `showAgain`'s task;
   - `ShortcutsNotification.build` with the time line and the note line (a second layout `shortcuts_panel_big.xml` for
     the big view, the row included), no action;
   - `ShortcutsService` and its manifest entry removed;
   - `CommandListenerService.onCreate` (`forStart`, cancel 1011) and `onDestroy` (the lock);
   - `AnchorNotice` hands over instead of posting;
   - `ic_stat_gear.xml`, the five icons, `baseline_bug_report_24.xml` removed;
   - D7's channel name;
   - the pins and tests updated (`QuietNotificationsTest`, `TimeLeftNoticeTest`, `ShortcutsTest`).

   This step uses r13's time part as it is: the note line shows "Spurt kl. HH:MM". **S-M.**
2. **Server + launcher, the request round trip** (one commit, §14): migration, the answer route and card, the
   policy keys, the contract, the capability; on the launcher `TimeRequests` (one open request), `timePart`, the note
   line's states. **M** (server S-M, launcher S).
3. **Launcher, the time-rule screen's block** (§8): `TimeAsk.kt`, `lockAsk`, `unlockOpenRow`'s `askButton`,
   `AnchorNotice`'s main-thread listeners, `activity_lock.xml`'s `lock_ask`, `LockActivity`'s bind and listener, the
   strings, `TimeLockModelTest`. **S.**
4. **Server**: D8's ⓘ sentence (it can ride in step 2). **XS.**
5. **Docs**:
   - `docs/notes/launcher/push-and-play.md` (the anchor's notification now carries the panel; the request's one-open
     rule);
   - `kid-settings.md` (Snarveier's section: one notification);
   - `time-rules.md` and `lock-and-glance.md` (the time-rule screen's block);
   - `docs/notes/server/` (the answer card, `time_request_answers`);
   - the root `CLAUDE.md`'s `time_request.json` paragraph;
   - design 99's header line pointing here;
   - `docs/testing/emulator.md`'s new section;
   - `launcher/CLAUDE.md`'s index if a note moves.

**Sizes: launcher M, server S-M.** Step 2 goes after design 101 is merged (§14.4).

## 14. The request for more time, answered (revision 2)

### 14.1 One open request at a time (launcher, `timerules/TimeRequests.kt`)

- **A request is open** from the tap until (a) a parent's answer to its id arrives in the policy (§14.2), (b) the
  phone's day ends (the ledger's `day` changes), or (c) the server answers `stale`, `capped` or 404 (r13's verdicts,
  unchanged).
- **r13's 30 minutes go.**
  - `TIME_REQUEST_OPEN_MS`, the record's `openUntilMs` as a rule, and `TimeRulesRuntime.nextBoundary`'s extra instant
    for it are removed.
  - `openUntilMs` stays a field with a default, so a stored `record_v1` still decodes. A new record writes 0, and
    nothing reads it.
  - The day's end is already a boundary (midnight).
- **While open, the kid can't ask again**: `askStep` asks only in Idle, and the Mer tid disc and the plain notice's
  action follow `canAsk`.
- **After an answer the kid may ask again**: `requestState` gives `Answered`, which `askStep` treats as Idle while the
  day's accepted count is under `TIME_REQUESTS_A_DAY` (3, unchanged). The phone's count still counts the server's
  `accepted`.
- **The upload** (`uploadStep`) sends a Pending record while it is of today: offline all afternoon, it goes when the
  phone is back. `DROP` is now only another day's record, or one a newer lift answered on an older server (§14.7). The
  server still pushes only while the request is news (r13's 30 minutes at intake, `EXTRA_TIME_NEWS_MINUTES`; its doc
  comment's "the phone stops sending it after the same 30 minutes" is corrected), so a late request lands in the card
  and the inbox without a push.

```kotlin
sealed interface RequestState {
    data object Idle : RequestState
    data class Pending(val atMs: Long) : RequestState
    data class Waiting(val atMs: Long) : RequestState           // r13's Asked: taken by the server, no answer yet
    data class Answered(val no: Boolean, val by: String?, val atMs: Long, val minutes: Int?, val canAskAgain: Boolean) : RequestState
    data object Capped : RequestState
}
fun requestState(record: TimeRequestRecord?, count: TimeRequestCount?, day: String?, answers: TimeAnswers?, maxLiftId: Long): RequestState
```

`answers` is the policy's `time_policy.answer` with the server's `time_request_answers` flag (§14.2). `null` means an
older server.

### 14.2 How the phone learns the answer (the policy)

The policy's `time_policy` gains two keys. A top-level flag tells an older server apart.

```json
"time_request_answers": true,
"time_policy": {
  "rules": [...], "daily_budget_minutes": [...],
  "lifts": [{"id": 812, "target": "budget", "rule_id": null, "minutes": 30, "expires_at_ms": 1793206800000,
             "by": "Mamma", "at_ms": 1793034600000}],
  "answer": {"request_id": "0123456789abcdef0123456789abcdef", "answer": "yes", "by": "Mamma",
             "at_ms": 1793034600000, "lift_id": 812}
}
```

- **`answer`**: the newest answered request of the phone's day (its `phone_zone`, as the intake's cap and `stale`), or
  absent.
  - `request_id` is the phone's own id.
  - `answer` is `"yes"` or `"no"`.
  - `by` is the parent's display name, absent without one (D11).
  - `at_ms` is when the parent answered.
  - `lift_id` (yes only) is the lift it made, which comes in `lifts` as every lift does.
- **`lifts[].by` and `lifts[].at_ms`**: who gave a lift (display name, absent without one, absent on design 98's
  phone-key pauses) and when (`time_lifts.created_at`). These let the phone say "Mamma ga deg 30 min" for an answer
  and for a lift without a request (D10).
- **`time_request_answers: true`** (top level, as design 98's `rule_pause`): this server answers requests and closes
  them only by an answer. Without it the launcher keeps r13's rule that a newer lift closes the request (§14.7).
- **The phone matches by id.** An `answer` whose `request_id` isn't the phone's open record's (an older request, or
  one the phone dropped) closes nothing. It can still give D12's note when it is today's newest.
- **Delivery.** Every answer nudges the phone (`command_notify`), as a lift does. The answer reaches the phone at its
  next sync, offline or not, and stays in every policy for the rest of the day.

### 14.3 What the phone shows (pure `timePart`, `timerules/TimeLeftNotice.kt`, `TimeLeftNoticeTest`)

`timePart(budget, windowOpen, state, lifts, nowMs)` widens r13's `timeLeftNotice`. The time text is r13's. The note is:

| State | Note | `canAsk` |
|---|---|---|
| Idle, no `Given` within 30 min | None | yes |
| Idle, a lift with `by` or without, given within 30 min (D10, D12) | `Given(by, minutes)` | yes |
| Pending, < 15 s | `Sending(at, offline = false)` | no |
| Pending, ≥ 15 s | `Sending(at, offline = true)` | no |
| Waiting | `Waiting(at)` | no |
| Answered no | `No(by, at)`: always on the screen; for 30 min in the shade (D12) | while under three |
| Answered yes | `Given(by, minutes)`: as above | while under three |
| Capped | the last answer's note, if any | no |

The panel's note line and the plain notice's content text use the note within D12's 30 minutes. The time-rule screen
uses it with no time limit (§8.2). The Mer tid disc is usable exactly while `canAsk`.

### 14.4 The parent's answer card (server; the mockup `Foresporsel.dc.html`)

**Where.** On the phone's summary (`device_detail.html`), right **after design 101's `</header>`**, never as the
sheet's first child (design 101's QA L1: its header geometry is `.summary-sheet > .summary-head`). It sits above
Hurtighandlinger. **Design 101 merges first**, and step 2 is built on it. The card replaces today's
`<p class="quick-note" data-asked>` and its string `device.summary.asked`.

**When.** While the phone has an open request on the server: its newest `extra_time_request` event of the phone's day
(`phone_zone`, the intake's day) has no `time_request_answers` row. `open_extra_time_request` returns that request
(its id, its `happened_at`, its params) instead of an instant. It keeps today's "no lift since" condition only for rows
made before the migration.

**What.**

```
┌ (hourglass)  Emma ber om mer skjermtid                  ┐   section#ask, aria-label "Forespørsel om mer skjermtid"
│              14:02 · Skjermtiden er brukt opp            │   the event's own summary words (extra_time_told)
│  [ +15 min ] [ +30 min ] [  +1 t  ] [  Nei  ]            │   one form, four submit buttons
└──────────────────────────────────────────────────────────┘
```

- The look is the mockup's: a white card (the sheet's card ground, dark in dark mode) with a 2 px accent ring, the
  hourglass on an amber tile, the title 17 px, the sub-line 14 px. A four-column grid of 44 px buttons: the three
  minute buttons in the accent, "Nei" in the neutral button colour. It uses the stylesheet's tokens, in light and dark.
- **The title** is "{name} ber om mer skjermtid" / "{name} asks for more screen time", with the phone's name. **The
  sub-line** is the event's existing words (`extra_time_told`'s summary: "14:02 · Skjermtiden er brukt opp", "14:02 ·
  1 t 20 min igjen", "21:40 · Leggetid").
- **The buttons**: one `<form method="post" action="/devices/{id}/time-request/answer">` with the hidden `request`
  (the phone's request id) and four `<button name="answer" value="15|30|60|no">`. They are labelled "+15 min", "+30
  min", "+1 t" / "+1 h", "Nei" / "No". With JS off it is a plain post. With JS it goes through `scroll-restore.js`'s
  staying post (`X-Handy-Stay`), so it never jumps to the top and never re-posts after a network error (pwa-back).
- **Under a rule** (the request's state `rule`, D9): the three buttons pause the rules in force for 15/30/60 minutes
  instead of adding screen time.
- **Return**: to `#quick` with a `done` flash, where Quick actions' flashes already land. The card is gone after the
  reload.
  - "+30 min": the lift's existing `extra` flash (or `pause` under D9).
  - "Nei": "Du svarte nei. {name} ser det på telefonen." / "You said no. {name} sees it on the phone." when the phone
    claims `time_answer_v1`, else "Du svarte nei." / "You said no." (§14.7).
  - A stale card (the other parent answered first): "{parent} har allerede svart." / "{parent} has already answered."
    Nothing is written.

**The route** `POST /devices/{id}/time-request/answer` (admin session, CSRF as every form):

- **One transaction** (`BEGIN IMMEDIATE`):
  1. The request must be the phone's open one. Otherwise the "already answered" flash, or a 404 for an unknown id.
  2. For minutes, the lift is written through `record_lift`'s core, made to take the transaction: `target=budget`, or
     `rule`/`all` under D9.
  3. The `time_request_answers` row is written: `INSERT … ON CONFLICT DO NOTHING`. A conflict rolls back, so two
     parents can never both give minutes.
- **After the commit**: the security event (`time_lift` as today; a new `time_request_no` for "Nei") and
  `command_notify`.

**Every lift answers an open request.** A lift from Quick actions (or design 93's Regler sheet) made while the phone has
an open request writes that request's `yes` answer row in the same transaction. Its `lift_id` is that lift, and the
parent is the one who made it. So the card closes, and the kid sees "Mamma ga deg 30 min", whichever button the parent
used. Design 98's phone-key pause (the parent code on the phone) answers nothing: it has no parent account behind it.

**For every parent.** The card is gone for both parents once one answers. The other parent sees the answer in
Activity and the inbox: `extra_time_told` gains the answer, read from `time_request_answers` where the rows are read.
The words are "Emma ber om mer skjermtid · Mamma ga 30 min" / "… · Pappa svarte nei" ("… · Mamma gave 30 min" / "… ·
Pappa said no"). Here the PWA's usual parent name is used (display name, else login), since this is the parents' own
page.

**The push** stays as r13's (`Push::Now` while news). Its target becomes `/devices/{id}#ask`. A push opened after the
answer finds no `#ask` and lands at the top of the summary. Web push gets no action buttons (iOS has none).

**The migration** `0149_time_request_answers.sql` (the next free number on `main` today after 0148; recheck at merge):

```sql
CREATE TABLE time_request_answers (
    device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    request_id TEXT NOT NULL,                       -- the phone's id = the event's dedup_key
    answer TEXT NOT NULL CHECK (answer IN ('yes', 'no', 'superseded')),
    lift_id INTEGER REFERENCES time_lifts(id) ON DELETE SET NULL,
    answered_by INTEGER REFERENCES admin_users(id) ON DELETE SET NULL,
    by_name TEXT,                                   -- the display name at the answer (NULL: none, D11)
    answered_at TEXT NOT NULL DEFAULT (datetime('now')),
    PRIMARY KEY (device_id, request_id)
);
```

- `by_name` is a snapshot: the phone shows the name the parent had when answering.
- `lifts[].by` is read live from `admin_users.display_name` through `time_lifts.created_by` (the username), and is
  absent for a deleted parent.
- **`superseded`**: a new request taken at intake while an older one is open (an older launcher, §14.7, or a phone that
  lost its record) closes the older one with `superseded`. That answer never reaches the policy's `answer`, and the
  card shows the newest request.
- **Retention**: `retention.rs` gains a step that deletes answers older than `LIFT_DAYS` (= `EVENT_DAYS`, 90), as
  `time_lifts` and `parent_events` go.

### 14.5 The contract and the capability

**`server/testdata/time_request.json` is extended** (byte-identical in `launcher/app/src/test/resources/`; one file,
since it is the same request):

- `request`, `answers`: unchanged.
- `capability`: `time_answer_v1`.
- `policy_no`, `policy_yes`, `policy_unnamed`, `policy_lift_only`: the `time_request_answers` flag and the
  `time_policy` fragment (`answer`, `lifts` with `by`/`at_ms`) the server serves in each case. The server's
  `time_request::the_contract_answers` produces them from seeded rows at a fixed now (`TIME_REQUEST_CONTRACT_WRITE=1`
  rewrites them).
- `states`: rows `{record, count, policy, day, now_ms} -> {state, note, can_ask}`, run by the launcher's
  `TimeRequestsTest` through `requestState` and `timePart`.
- `older_server`: a policy without the flag, closed by a newer lift.

The launcher's `TimeRequestsTest` decodes every policy row into its DTOs (`TimePolicy.answer`, `Lift.by`, `Lift.atMs`,
`PolicyResponse.timeRequestAnswers`) and runs every `states` row. The server produces the policy rows. Both compare the
copies.

**The policy keys** are pinned by `policy_json_keys_snapshot` and the launcher's `PolicyResponseCompatTest`:
`time_request_answers`, `time_policy.answer.{request_id, answer, by, at_ms, lift_id}`, `time_policy.lifts[].{by,
at_ms}`.

**The capability `time_answer_v1`** (the launcher's `STATUS_CAPABILITIES`): this launcher shows a parent's "Nei" and
the name. The server reads it only for the "Nei" flash's words (§14.4). Nothing else depends on it.

The root `CLAUDE.md`'s `time_request.json` paragraph is rewritten for the new rows. Both workflows already list the
file.

### 14.6 Server tests (`src/tests/time_request.rs`)

- the contract rows (above);
- the card shows for an open request, after the header (after 101), not for an answered or superseded one, not for
  another day's;
- each button: the lift and the answer row in one transaction, the flash, `command_notify`;
- "Nei" with and without the capability;
- D9's pause under a rule;
- two answers at once: one wins, the other gets "already answered", one lift;
- a Quick actions lift answers the open request; a phone-key pause doesn't;
- a second request supersedes the first;
- Activity's and the inbox's words with the answer;
- the push's `#ask` target;
- the old `.quick-note data-asked` gone;
- `policy_json_keys_snapshot`.

### 14.7 Older launchers, older servers

- **An older launcher, this server.** It ignores the new keys (`ignoreUnknownKeys`) and keeps r13's 30 minutes. After
  30 minutes it may ask again. The server supersedes the open request, and the card shows the newer one. It never shows
  "Nei", so the "Nei" flash doesn't promise it (§14.4). It closes its request on a newer lift, as before.
- **This launcher, an older server.** No `time_request_answers` flag: the launcher closes the open request on a lift
  newer than the one at the ask (r13's rule), or at the day's end. There is no "Nei" and no name: a yes says "Du fikk 30
  min mer". An unanswered request blocks asking for the rest of the day, which is the new rule anyway. Release order:
  the server first, as usual.

## 15. Design QA (`docs/review/qa-100-design.md`): how each finding was handled

| Finding | Handled |
|---|---|
| Q100-H1 the listeners off the main thread | `AnchorNotice` posts the listener loop to the main `Handler`; `LockActivity.render()` re-reads everything; a pin (§8.4, §10) |
| Q100-M1 the poster's dependency on `ShortcutsRuntime.init` | The poster keeps the context from its first caller; no panel model or a throwing `computePanel` gives `Plain(time)`; a pin (§2.2, §10) |
| Q100-L1 `baseline_settings_24` has a reader | It stays; only `baseline_bug_report_24` goes; `ic_stat_gear` is a literal-white copy of its path (§3.1, §4) |
| Q100-L2 an FGS notification with an action isn't deferred | §2.3 reworded; the pin is only "no IMMEDIATE on the plain builder" |
| Q100-L3 the listener when the anchor isn't running | `refreshNow`'s anchor check moves below the dispatch; the screen re-reads on `onStart` and the boundary alarm (§8.4) |
| Q100-L4 a shed post recorded as posted | The trailing slot compares `getActiveNotifications()` and posts again on a mismatch (§2.2) |
| Q100-L5 a post after `onDestroy` | The check and the `notify` under a lock that `onDestroy` takes (§2.2) |
| Q100-L6 two sources for the time part | The poster sets the ask cache from the handed time part before `computePanel` (§2.2) |
| Q100-L7 `forStart`'s reads | It builds from the last key and its channel id, never `computePanel`; a pin (§2.3, §10) |
| Q100-L8 `showAgain` on IO | One task on the poster's executor (§2.2) |
| Q100-L9 M6 wasn't a user's block | Said in M6 and §2.4; check 7's full form is owed (§11) |
| Q100-L10 small gaps | Nunito in check 2; `TIME_REQUESTS_A_DAY == 3` pinned; "sendes når telefonen er på nett" adopted (§8.4, §14.3); the pins added; D8's en sentence has no quoted button any more (§6) |

## 16. Revision 2's design QA: what the build did with each finding

| Finding | Built as |
|---|---|
| R2-M1 the card's form | `section#ask`'s form posts to `/devices/{id}/time-request/answer#ask` (a staying post); the route lands on `#quick` with its flash. In `summary.rs`'s form list and `check_forms` |
| R2-M2 a lift that can't unlock | `time_answers::need` decides at the answer from the phone's fresh report (`report_is_fresh`, `TimeAsk::of`), else the request's own state; `fits`: screen time for a spent budget, a pause of every rule or of the report's rule for a rule. The card's minutes pause the rules when a rule locks (D9), with the line "Knappene setter reglene på pause." |
| R2-M3 the other contracts | `lock_now.json` and `rule_pause.json` carry the lifts' `at_ms`/`by` and `request_answers`, in both directories; `policy_json_keys_snapshot` pins `request_answers`; `time_request.json`'s `policy_*` rows (made by `time_request::the_contract_policies`) pin `answer`'s and the lifts' keys; `PolicyResponseCompatTest` decodes them |
| R2-M4 the old rows | Migration 0149 backfills a `yes` for every request a lift answered under r13's rule; the open request is one query |
| R2-L1 | `answer.minutes` for a yes |
| R2-L2 | D10's gift: a `budget` lift with a positive id, no `phone_key`, an `at_ms` (`givenLift`) |
| R2-L3 | `noteChangeMs` (the 15-second mark, the 30 minutes' end) is a `nextBoundary` instant; the screen has no timer of its own |
| R2-L4 | `timePart(.., surface, dayStart)` gives the shade's or the screen's note; `Capped` reads the policy's answer; the flag is `time_policy.request_answers` |
| R2-L5 | `answer` is the newest yes/no of a request asked in the phone's day |
| R2-L6 | The supersede runs only after `raise` stored the new request; stale cards: "… har allerede svart.", "… har spurt på nytt.", "Forespørselen er ikke åpen lenger."; `record_lift` is one IMMEDIATE transaction for every caller, the event and nudge after commit |
| R2-L7 | The answer in the event's `params` (`answer`, `by`, `minutes`); `by` is the parent's PWA name at the answer (a snapshot: the wording reads params only) |
| R2-L8 | nb + en for every string; the button row a grid, no flex `gap`; the mockups committed (above); the card above `#access`/`#oppsett`/`#attention` (pwa.md) |
| R2-L9 | Not built (optional `open` key) |
| R2-L10 | The account page's name hint says it shows on the phone; a lift that answered a request carries the answer's name |
| R2-L11 | The trailing check compares the channel and a view key in the extras (step 1); "sendes når …" also for a 5xx accepted; PLAN is the coordinator's |

## 17. Code QA (`docs/review/qa-100-code.md`): what each finding became

| Finding | Fix |
|---|---|
| L1 a lost 200, then an answer | `UploadStep.COUNT`: a PENDING record this server answered takes ACCEPTED's step (ASKED, count + 1), nothing sent |
| L2 repeated announcements | The ask block's live regions get their text only when it changed |
| L3 TalkBack focus after the tap | The pill stays focusable (a button only while clickable), so focus stays on it as it turns into the note |
| L4 an older server's pause | Any newer lift still closes the request (§14.7); only a PWA screen-time lift is "Du fikk N min mer", a pause closes it with no words of time |
| L5 `forStart`'s fallback | `AnchorNotice.plain(app, cached)`: the time part already cached, else the bare plain notice |
| L6 the wrong parent named | The "already answered" redirect carries `&request=`; `last_answerer` names who answered that request |
| L7 a copy's pause | `time_answers::copied_rule` maps a rule turned on now's negative id to the rule it copies; a pause of that rule answers |
| L8 an ended-early yes | The policy's answer names no lift and no minutes once the lift was ended early: the phone shows no time given, and the kid may ask again ("Spør igjen" alone). Pinned by the contract's `policy_ended` row and its state row |
| L9 the card's buttons | Asked under a rule a fresh report says is over: "Ingen regel låser nå: knappene gir mer skjermtid." (`device.summary.ask.adds`) |
| L10 backfilled pauses | 0149 (unpushed, so changed in place) names a lift only for screen time and snapshots the giver's display name |
| L11 the doc comment | `render_device`'s doc comment back on it |

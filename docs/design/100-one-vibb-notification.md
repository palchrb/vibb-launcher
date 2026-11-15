# Design 100: One Vibb notification - the anchor carries Snarveier, and "Be om mer tid" on the time-rule screen

Status: design, revision 1, 2026-11-15 (branch `design-100`). Not built.

Approved by the user on 2026-11-15 (PLAN, "One Vibb notification"). The user asked *"Er vi nødt til å vise det tomme
vibb one varselet?"* and then approved the merge: *"Genialt! Vi slår det sammen med snarvei baren vår, det er
ideelt!"*:

- **While Snarveier shows, the anchor's foreground service carries the Snarveier panel as its one notification.** The
  time left ("1 t igjen i dag") and "Be om mer tid" move into the panel.
- **With Snarveier off** (the parent's bit 128 off, or nothing to show), the plain anchor notice comes back. Android
  requires a notification for the service.
- **Icons** (the same message): the panel gets the gear. The plain anchor, Play's install mode, the ring, the setup
  notice and the filter's "Innholdsfilteret kobler til …" get the round Vibb mark (`ic_stat_vibb`). Calls, missed
  calls and SMS keep their icons.

Added the same day (the user: *"Ja legg inn det du sa!"*): a **"Be om mer tid" button on the time-rule screen** when the
screen time is used up. Until now the request lived only in the shade, where a kid on the used-up screen would never
look (§8).

**The short version.** `ShortcutsService` goes. The anchor (`CommandListenerService`, id 1002) is the one service and
posts one notification. It shows the colorized Snarveier panel while Snarveier is shown. Otherwise it shows r13's plain
notice. The switch is a `notify(1002, …)` that moves the notification to the other channel. On `jelly36` that kept one
record and the foreground state through every swap, with no blank frame (§1). The panel gains one line of its own over
the row: the time left. Under the row it gains Android's own "Be om mer tid" button. While the request waits, a second
line shows "Spurt kl. 14:02". One poster on one thread posts every update of id 1002, at most once a second. The
time-rule screen of the used-up budget gets its own small card under the rule card. It holds the "Be om mer tid" button,
or "Spurt kl. 14:02" as text, and uses the same request path. No API change.

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
| M6 | **The panel's channel blocked** (importance 0) while the FGS notification is on it | The notification **leaves the shade** (SystemUI's listener: "notification removed"), and the service **stays foreground** (`isForeground=true`). A post on the blocked channel is accepted without an error. A swap to the MIN channel shows it again |
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
- **"Be om mer tid" as Android's own action** keeps r13's `setAuthenticationRequired` and TalkBack's button word.
  It costs 60 dp while it can be asked (M14, D1). A pill of our own on the line doesn't fit in nb (M15).
- **The channel must exist before every build (M7)**, and the poster must always post the last state (M5).
- **The gear needs a white copy (M10).** The approved gear is kept, drawn so it shows.

## 2. The mechanism

### 2.1 One service, one id

- **`ShortcutsService` is removed** (its class, its manifest entry and its `specialUse` subtype). Design 99 §2.4's start
  and stop logic goes with it: `startForegroundService`, `stopNow`, `serviceStarted`/`serviceGone` and
  `startAskedAtMs`. That leaves two foreground services in the process, the anchor and the VPN's (Q99-L6 had three).
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

- **One thread.** It uses `ShortcutsRuntime`'s single-thread `shortcuts-refresh` executor, which moves here as the
  notification's thread. `AnchorNotice.refresh` (main thread, the tracker's minute tick, the receiver) still computes
  the `TimeLeftNotice`, because it holds the snapshot. It hands the notice over (`AnchorNotification.notice(notice,
  requestState)`) and asks for a refresh. It no longer posts.
- **What is posted** is the pure `anchorView(panel, notice)` (§2.5): `Plain(notice)` or `Panel(panel, time, ask)`.
- **Only a change is posted.** The key is `(view, locale, channel id)`. A new anchor's `onCreate` sets the key to what
  it started with (as r13's `reset`).
- **At most one post a second, always with a trailing post** (design 99 Q99-L7's `shortcutPostDelay`, now for both
  views). The first change after a quiet second posts at once. Any change within the second is posted once, at its
  end, from the state at that moment. Android's limit is per package (M5). The VPN's 1003 posts rarely and isn't
  coalesced.
- **Channels first (M7).** Right before every build, on the same thread, the poster creates the view's channel. That
  is the listener channel with `createNotificationChannel` (idempotent; it doesn't undo a kid's block) or the panel's
  `ensureChannel`. A build never names a channel it hasn't just made sure of.
- **Only while the anchor runs** (`CommandListenerService.isRunning`, as r13). Otherwise the view is kept and nothing
  is posted.

### 2.3 The anchor's start

```kotlin
override fun onCreate() {
    super.onCreate()
    startForeground(COMMAND_LISTENER_NOTIFICATION_ID, AnchorNotification.forStart(this))
    ...
}
```

- `forStart` stays literally first (the existing pin, `QuietNotificationsTest`). It **never throws**:
  - it builds the view this process last computed (in memory, no CE read), with its channel ensured, and records it as
    posted;
  - with no view yet (a fresh process: `ShortcutsRuntime.init` runs in `Application`'s tail), or on any exception, it
    returns r13's `AnchorNotice.plain` on the listener channel, made sure of first.
- Then the anchor asks for a refresh (r13's `refreshNow` stays), and the poster swaps to the panel if it is wanted.
- **The plain notice keeps today's ~10 s deferral (M8).** It has no `FOREGROUND_SERVICE_IMMEDIATE`. At boot the panel
  (with IMMEDIATE, design 99 S6) normally replaces it before it is ever shown, so the kid never sees "Vibb One" first.
  Whether an IMMEDIATE update inside the deferral shows at once was not measured (§11 check 5). If it doesn't, the panel
  shows ~10 s after boot, which nobody watches.
- **Design 99's old panel (id 1011)** goes with its service at the update (M9). `onCreate` also cancels 1011 once, at
  no cost.

### 2.4 When the view changes

| Event | What happens |
|---|---|
| Boot, process start | The plain notice (deferred), then the panel at the first refresh (`ShortcutsRuntime.init`'s) |
| Enrolment before a policy | No `Ok` cached policy: plain. The first sync's policy write (`prefsListener`) brings the panel |
| Bit 128 off / on (a sync) | The next refresh: `notify(1002)` with the other view. In place if the shade is open (M2), sorted at the next pull (M3) |
| The last item leaves (a parent switch, the role gone) | Plain. Design 99 §2.2: no item, no panel |
| The kid turns off the panel's channel (S16; not possible on a phone where Vibb holds the dialer or SMS role, design 99 §14) | The notification leaves the shade at once (M6). `ShortcutsRuntime.hidden` reads importance 0 at `ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED` and the panel isn't shown, so the next refresh posts the **plain notice** on the listener channel (the approved rule). The service stays foreground throughout (M6) |
| "Vis i varsellisten igjen" (design 99 §2.6) | Deletes the blocked channel and makes the next one. Hidden means the plain view is up, so the deleted channel never holds the FGS notification. `showAgain` checks the posted view is plain before it deletes (AOSP refuses to delete a channel that holds a foreground-service notification; read from the source, not measured) |
| The time left changes (the minute tick, a lift, the request) | A new `TimeLeftNotice` gives a new view: one post (≤ 1/s) |
| Vibb's PIN lock (LOCKED) | Nothing changes. The shade can't open (`featuresWhileLocked`, design 99 §2.2) |
| Android's keyguard | Nothing changes. The panel is secret (S14). The plain notice is a silent MIN notice, as today, and its action needs the unlock |
| A locale change | Both channels renamed (`languageChanged`). The key's locale differs, so one post |
| The anchor restarts in a live process | `forStart` starts with the panel directly (the view in memory) |
| The anchor stops (never by Snarveier) | Android removes its notification. The next anchor starts again (§2.3) |

### 2.5 The view (pure, `server/AnchorView.kt`, `AnchorViewTest`)

```kotlin
sealed interface PanelAsk { data object Button : PanelAsk; data class Asked(val atMs: Long) : PanelAsk; data object None : PanelAsk }

sealed interface AnchorView {
    data class Plain(val notice: TimeLeftNotice) : AnchorView
    data class Panel(val panel: ShortcutsPanel, val time: TimeLeftText?, val ask: PanelAsk) : AnchorView
}

fun anchorView(panel: ShortcutsPanel, notice: TimeLeftNotice): AnchorView =
    if (!panel.shown) AnchorView.Plain(notice)
    else AnchorView.Panel(panel, notice.text, when (val a = notice.ask) {
        AskPart.Action -> if (panel.items.any { it.key == ShortcutKey.MORE_TIME }) PanelAsk.None else PanelAsk.Button  // D2
        is AskPart.Asked -> PanelAsk.Asked(a.atMs)
        AskPart.None -> PanelAsk.None
    })
```

The panel's rules (`shortcutsShown`, design 99 §2.2) and r13's notice (`timeLeftNotice`) are unchanged. `anchorView`
only combines them.

## 3. The panel with the time

### 3.1 What it shows

Unfolded (the normal view: always first, so Android unfolds it, design 99 S2):

```
[gear]  Vibb One · Snarveier                       ^
        1 t 20 min igjen i dag                         <- the time line (only with a budget today)
        Spurt kl. 14:02                                <- only while the request waits (Asked)
        ( o )  ( ● )  ( o )  ( o )                     <- the row, unchanged
        Be om mer tid                                  <- Android's action (Button only)
```

- **The header** stays "Vibb One · Snarveier" (`shortcuts_subtext`). The time doesn't fit there (M11), and the
  title and text fields aren't shown (M12).
- **The time line**: r13's words exactly (`anchor_time_left` "%1$s igjen i dag" / "%1$s left today",
  `anchor_time_used_up` "Skjermtiden er brukt opp i dag" / "Screen time is used up today"), 14 sp,
  `nunito_bold`, white. **Two lines at most** (`maxLines=2`, ellipsized after the second): at font scale 1.3 the
  used-up line wraps rather than being cut (M13). Only in the big view (`setCustomBigContentView`). The folded view keeps
  the row alone, since design 99's folded content is limited to 48 dp (S8).
- **"Spurt kl. 14:02"** (`anchor_asked_at`): a second line under the time line, 13 sp, white at 70 %, **text, never a
  button** (R13-8). The Mer tid disc, when the kid has it, is dimmed with "Mer tid, spurt" as today.
- **"Be om mer tid"**: `Notification.Action` with `setAuthenticationRequired(true)` (`Notification.Action.Builder`,
  API 31+; the launcher's `minSdk` is 34) to the explicit, non-exported `TimeAskReceiver`. It is r13's
  `askIntent`, with the same request code and flags. Shown only for `PanelAsk.Button`: a budget today, the request Idle,
  and Mer tid not among the four discs (D2). Android shows actions only unfolded (M14).
- **Without a budget today, in the pause or the override:** no line and no action. The panel is exactly design 99's.
- **The row** is unchanged (design 99 §3, revision 3).
- **Small icon: the gear**, drawn as a new `ic_stat_gear.xml`: Material Symbols' settings path (Apache-2.0, as
  `baseline_settings_24`) with `fillColor="#FFFFFFFF"` (M10). The approved gear is kept, drawn so it shows.

Folded (the kid folded it, or a call's notification is first, design 99 §14): the small icon, the row and the chevron.
No time line, no action. The next pull unfolds it again (S2).

### 3.2 Heights (M16)

| State | Unfolded | Share of the 537 dp shade |
|---|---|---|
| No budget today | 118 dp (as design 99) | 22 % |
| Time left, request asked or pending/capped | ~137 dp (Pending, Capped), ~157 dp with "Spurt" (estimated: one more line of 13 sp) | 26-29 % |
| Time left, "Be om mer tid" | 177 dp (183 dp at font scale 1.3) | 33 % |
| Mer tid among the discs | ~137 dp (no action row, D2) | 26 % |

### 3.3 TalkBack

The time line and "Spurt" are plain text. Android reads the action as "Be om mer tid, knapp". The discs keep their
content descriptions (design 99 §3).

### 3.4 With Snarveier off: the plain notice (D3)

Exactly r13's notice. On the MIN channel it sits at the bottom in "Silent" as one line: the title "Vibb One", or the
time left as its title. Folded, the action shows on expanding, and "Spurt kl. 14:02" is the content text. Design 99 S4
measured that two silent notifications of one app are bundled. While the filter's "kobler til …" notice is also up,
the two can fold into one "Vibb One · 2" bundle, **as today**. This design changes nothing there. The plain notice
gets the Vibb mark (§4), which draws (M10). Today's gear drew an empty disc.

The approved rule brings this plain notice back whenever Snarveier is off. It costs nothing new: it is what every phone
shows today.

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

`baseline_settings_24.xml` and `baseline_bug_report_24.xml` then have no reader in the launcher. They are removed in the
same commit (a grep over `res/` and the sources first). A source test pins that every `ic_stat_*` drawable has literal
colours, never a `?` attribute (M10).

## 5. Channels

- **No channel id changes.** The plain notice stays on `launcher:command_listener`, the panel on
  `launcher:shortcuts[.N]`, and the kid's block of either carries over. No cleanup is needed.
- **The listener channel's name** is "Finn enheten – bakgrunnstilkobling" / "Find My Device background connection",
  a leftover from design 07. The kid reads it in the long-press menu's "Turn off" sheet. D7: rename it "Vibb One" (nb
  and en). That changes only the name.
- **What the kid's long-press "Turn off" does now** (on a phone without the dialer or SMS role; with them, every
  `launcher:` channel is locked, design 99 §14):
  - on the panel: the panel channel off -> the panel hidden, and the plain notice (with the time left) comes back on
    the listener channel (§2.4);
  - then on the plain notice: the listener channel off -> nothing of the anchor's in the shade. The time left and the
    shade's "Be om mer tid" are gone for that kid. The time-rule screen's button (§8) still offers the request when it
    matters.
- **For the queued "Blocked notification channels named in the PWA" (after this design):** it must report both of the
  anchor's channels, `launcher:command_listener` and the panel channel in use (`launcher:shortcuts.N`, read through
  `ShortcutsRuntime`'s id, never a fixed string), besides Meldinger's and the missed calls'. Its PWA line must say
  what is lost: with both off, the shade shows neither the shortcuts nor the time left. With only the panel's off, the
  plain notice shows the time left and design 99's "hidden" line already covers it (`shortcuts.hidden`). The item stays
  moot on role-holding phones.

## 6. The server and the API

**No API change.** Nothing in the contract `server/testdata/shortcuts.json` changes:

- `shortcuts.shown` keeps its meaning: the model says shown, which now means "the anchor's notification is the panel".
- `hidden` and `items` are unchanged.
- The time request is r13's (`POST /api/devices/time-request`, `time_request.json`), whether it comes from the panel,
  the Mer tid disc or the time-rule screen.

One PWA string (D8): the Home Screen page's ⓘ (`device.home.shortcuts_info`) gains a sentence:

- nb: "Når {name} har skjermtid i dag, viser den også tiden som er igjen og «Be om mer tid»."
- en: "On days with screen time it also shows the time left and «Ask for more time»."

nb and en, no other server change.

## 7. Design 99 and r13: what changes in their rules

- **r13 "never a second notification"**: holds. One id, one record (M1). "Nothing else of ours in the shade" (U5)
  was already traded for Snarveier by the user in design 99. This design removes the second notification design 99
  added.
- **r13 §2.3's "posted only while the anchor runs" and the reset in `onCreate`**: kept, in the one poster (§2.2-§2.3).
- **r13 §2.2's builder** (`AnchorNotice.build`): kept for the plain view, with the Vibb mark.
- **Design 99 §2.1** ("separate from the anchor, whose notification stays exactly as it is") and **§2.4** (the
  service's life) are replaced by §2 here. Design 99's header gets one line pointing here.
- **Design 99 §2.3's throttle**: now for every post of id 1002 (§2.2).
- **Design 99's `more_time` item**: unchanged. It is "Be om mer tid" as a disc. When the kid has it, the panel's
  action row is left out (D2). "Spurt kl." shows on the line either way.

## 8. "Be om mer tid" on the time-rule screen

### 8.1 Why

The request lives in the shade: the anchor's action and the optional Mer tid disc. On the time-rule screen
(`LockActivity`, after the PIN) the shade can be pulled down, but a kid whose screen time just ran out looks at the
screen, not the shade. The screen gets the button where it is looked for.

### 8.2 When it shows (pure `lockAsk`, `ui/TimeLockModel.kt`, `TimeLockModelTest`)

```kotlin
sealed interface LockAsk {
    data object Button : LockAsk                     // "Be om mer tid"
    data object Sending : LockAsk                    // "Sender …" (Pending)
    data class Asked(val atMs: Long) : LockAsk       // "Spurt kl. 14:02"
    data object Capped : LockAsk                     // "Du har spurt tre ganger i dag."
}

fun lockAsk(ruleInForce: Boolean, budgetToday: Boolean, windowOpen: Boolean, enrolled: Boolean, state: RequestState): LockAsk? =
    if (ruleInForce || !budgetToday || windowOpen || !enrolled) null
    else when (state) {
        RequestState.Idle -> LockAsk.Button
        RequestState.Pending -> LockAsk.Sending
        is RequestState.Asked -> LockAsk.Asked(state.atMs)
        RequestState.Capped -> LockAsk.Capped
    }
```

- **The used-up budget's screen only** (D4): `ruleInForce` = the screen names a rule (`rule != null` in
  `LockActivity.render`). Under school, bedtime or a custom rule it doesn't show, even with a budget set today, for
  three reasons:
  - more minutes don't open a phone a rule locks;
  - a button on the bedtime screen invites a request every night;
  - r13 D3's action stays in the shade there (the panel or the plain notice), so the request is still possible.
- `budgetToday` = `snap.budget?.effectiveMinutes != null`. It is true on the used-up screen by definition, and also on
  "Ingen skjermtid i dag" (a budget of 0 today). The kid may ask then too, as the notice allows.
- `windowOpen` (the pause, the override) and `enrolled` are as `askStep`. The screen isn't up in a window anyway.
- `state` is `TimeRequests.state(context, snap)`, the same as the notice's.
- **Sending and Capped** (D5): r13's notice shows nothing in both (AskPart.None). On the screen the kid looks for the
  button, so it says why there is none. "Sender …" shows for the second or two until the server takes it, or for as
  long as the phone is offline (the request is retried until its 30 minutes are over, r13 §2.4). "Du har spurt tre
  ganger i dag." shows after D5's third.

### 8.3 Where it sits

`activity_lock.xml` gets one card, `lock_ask`, **between the rule card and the rows card**. It is 14 dp under the rule
card, as the rows card is, and in the rows card's look (`bg_lock_card`, the same 14/10 dp padding, `minHeight` 52 dp):

- **Button**: the Mer tid glyph (`shortcut_glyph_more_time`) in a 34 dp `kid_accent` disc, as the rows' icons, and
  the label "Be om mer tid" (`anchor_ask_more_time`), `nunito_bold` 15 sp, `kid_ink`. The whole card is the button
  (`ButtonRole`, `selectableItemBackground`, ≥ 48 dp). No chevron: it opens nothing.
- **Sending / Asked / Capped**: the same card, not clickable, no `ButtonRole`, the glyph's disc at 40 % alpha, the
  text in `kid_core_dim`: "Sender …" (`lock_ask_sending`), "Spurt kl. 14:02" (`anchor_asked_at`, `ClockFormat`'s
  time), "Du har spurt tre ganger i dag." (`lock_ask_capped`). Wraps, never cut.
- `null`: the card is gone.

Why not inside the rule card: that card is one view shared with the glance. It is information only and one TalkBack
stop (r13 U3, launcher round L6). A button inside it would change the glance's card too. Why not a row in the rows card:
the rows are "what still works" and they open things. A row that turns into text after the tap would read as a broken
row. The rows card keeps its rows (`timeLockRows` is unchanged).

**The glance (the PIN lock's page) is unchanged**: its rule card stays information only (U3). The button is behind
the PIN, on the time-rule screen.

**After the unlock (D6).** PLAN 2026-11-02 opens the card's only row straight after the PIN (`unlockOpenRow`). On the
used-up screen that is often the music app or Kalender alone. With the ask card showing as a button there are two
things to do, so the screen stays: `unlockOpenRow(marked, handedOver, rows, askShown)` returns `null` while `lockAsk`
is `Button`. In the Sending, Asked and Capped states it behaves as today.

### 8.4 The tap and the updates

- **One request path.** `TimeAskReceiver`'s body moves to `timerules/TimeAsk.kt` (`TimeAsk.askNow(context)`: the
  snapshot, `windowOpen`, `TimeRequests.ask`, `AnchorNotice.refresh`, `TimeRuleAlarm.schedule`, `SyncRunner.request(..,
  "time_request")`). The receiver, the Mer tid disc (`ShortcutsRuntime.askForTime`) and the screen call it. The screen
  calls it on a tap of the Button state only (the card isn't clickable otherwise). `askStep` decides again inside, so a
  stale card can't ask twice.
- **The screen follows the request.** `AnchorNotice` gains `addListener`/`removeListener` (main thread). It calls
  them whenever its computed `(TimeLeftNotice, RequestState)` changes, **before** the anchor-running check. That covers
  the tap (Pending), the server's answer (Asked, or back to Idle), D5's cap, and the 30 minutes' end through the
  boundary alarm's `recheck`. `LockActivity` listens from `onStart` to `onStop` and re-renders. Pending -> Capped
  changes only the `RequestState`, which is why it is part of the key.
- **TalkBack.** The card is one stop. As a button: "Be om mer tid, knapp". In the other states it is text, with
  `accessibilityLiveRegion="polite"`, so the change to "Sender …" and to "Spurt kl. 14:02" is read out without moving
  focus.
- **i18n**, new strings in en and nb (the others are r13's):

  | Key | nb | en |
  |---|---|---|
  | `lock_ask_sending` | Sender … | Sending… |
  | `lock_ask_capped` | Du har spurt tre ganger i dag. | You've asked three times today. |

  The count is D5's fixed three (`TIME_REQUESTS_A_DAY`). The text is a whole sentence per language, never assembled.

### 8.5 The parent's side

Nothing new. The request is r13's: the `extra_time_request` event and push, and the "Asked for more time at 17:21" row on
Device › Time (`#extra`). The event's state is the server's own, "Screen time used up".

## 9. Decisions for the user

- **D1. "Be om mer tid" in the panel as Android's own button under the row.** It keeps r13's unlock rule and the
  button word for TalkBack. **Cost: 60 dp while it can be asked** (the panel 177 dp, a third of the shade, M16).
  Alternatives:
  - (b) no button in the panel: the kid picks the Mer tid disc to ask (the panel stays at 137 dp with the time line);
  - (c) a pill of our own at the end of the time line: it fits only if the nb time text is shortened, e.g. "1 t 20 min
    igjen" (M15).

  **Recommendation: (a), as approved.**
- **D2. When the kid has Mer tid among the four discs, the button row is left out**: the disc is the request, and
  the panel is 40 dp shorter. "Spurt kl. 14:02" still shows on the line. **Recommendation: yes.**
- **D3. With Snarveier off, the plain r13 notice comes back as it is today**: one quiet line at the bottom, possibly
  bundled with the filter's notice. Alternative: the colorized notice without buttons at the top (the time left
  always visible, but a dark card at the top every day with a budget, against r13 U5). **Recommendation: as today (the
  user's approval says so).**
- **D4. The time-rule screen's "Be om mer tid" only on the used-up budget's screen**, not under school, bedtime or a
  custom rule, even with a budget today (§8.2). Under a rule the shade's action stays (r13 D3). **Recommendation: used-up
  screen only.**
- **D5. The screen says why there is no button**: "Sender …" while the request is being sent (or the phone is
  offline), "Du har spurt tre ganger i dag." after the third. The notification shows nothing in both. **Recommendation:
  yes.**
- **D6. The ask keeps the screen after the unlock**: with the button showing, the used-up screen no longer opens its
  one row by itself after the PIN. **Recommendation: yes.**
- **D7. The anchor's channel is renamed "Vibb One"** (was "Finn enheten – bakgrunnstilkobling"), the name the kid
  reads in the long-press "Turn off" sheet. **Recommendation: yes.**
- **D8. The Home Screen page's ⓘ says the panel also shows the time left and «Be om mer tid»** (§6).
  **Recommendation: yes.**

## 10. Tests (lean)

Unit:

- **`AnchorViewTest`** (new): `anchorView` for each case:
  - not shown -> `Plain` with every notice (no budget, Left, UsedUp, each ask);
  - shown, no budget -> `Panel(time = null, None)`;
  - shown with Left/UsedUp and Action -> `Button`, and `None` with Mer tid among the items (D2);
  - Asked -> `Asked(at)`;
  - Pending/Capped (`AskPart.None`) -> `None`;
  - equal inputs -> equal views (an unchanged view posts nothing).
- **`TimeLockModelTest`**:
  - `lockAsk`: a rule in force, no budget today, a window, not enrolled -> `null`; each `RequestState` to its state;
  - `unlockOpenRow` with `askShown` (D6).
- The coalescing stays `shortcutPostDelay`'s test. The trailing post is pinned: a change inside the second schedules
  exactly one later post.

Source pins (the guard style of `QuietNotificationsTest`, `TimeLeftNoticeTest`, `ShortcutsTest`):

- no `ShortcutsService` class and no manifest entry; no `startForegroundService` or `stopForeground` under
  `shortcuts/`;
- `notify(COMMAND_LISTENER_NOTIFICATION_ID` only in `AnchorNotification.kt`, and no other `notify` of 1002 anywhere;
- `onCreate`'s first line after `super` is `startForeground(COMMAND_LISTENER_NOTIFICATION_ID,
  AnchorNotification.forStart(this))`; `forStart` catches every exception and falls back to `AnchorNotice.plain`;
- in `AnchorNotification`, every `build` is preceded by its channel's ensure on the same path (M7);
- the panel's builder sets `setColorized(true)`, `FOREGROUND_SERVICE_IMMEDIATE` and `VISIBILITY_SECRET` (the existing
  pins move with it); its action is built with `setAuthenticationRequired(true)` and names `TimeAskReceiver`
  explicitly;
- the plain builder has no `FOREGROUND_SERVICE_IMMEDIATE` (§2.3);
- the small icons: the panel `ic_stat_gear`, and the five of §4 `ic_stat_vibb`; every `res/drawable/ic_stat_*.xml`
  without a `?` attribute (M10);
- `TimeAskReceiver`, `ShortcutsRuntime.askForTime` and `LockActivity` call `TimeAsk.askNow`, and nothing else calls
  `TimeRequests.ask`;
- the strings of §3 and §8.4 exist in `values/` and `values-nb/`.

Server: `tests::shortcuts` renders the ⓘ with the new sentence in nb and en (D8). The contract tests are unchanged.

## 11. Emulator checks (`jelly36` restored clean, density 240; a new section in `docs/testing/emulator.md`)

1. **One notification, both views.** The branch's debug launcher as device owner and enrolled, then Snarveier on, then
   bit 128 off and on again from the PWA. Check with the shade closed and with it open:
   - `dumpsys notification --noredact`: only `id=1002` for the package at every step, on the panel channel and then
     the listener channel;
   - `dumpsys activity services`: the anchor `isForeground=true`, no `ShortcutsService`;
   - with the shade open, the row changes in place; at the next pull the panel is first.
2. **A budget set today** (Device › Time):
   - the time line;
   - "Be om mer tid" under the row; a tap -> the action goes, then "Spurt kl. HH:MM" after the sync, and the PWA's
     "Asked" row;
   - a lift -> the line and the button again;
   - Mer tid picked as a disc -> no action row (D2);
   - nb and en, font scale 1.0 and 1.3 (the line wraps, never cut); the heights against §3.2.
3. **With Snarveier off**: the plain notice with the time left and its action, as r13 §2.9.
4. **The icons**: the panel's gear (dark in a light disc), the plain notice's Vibb mark, Play's install mode, the ring
   (a `ring` command), the assistant's setup notice, the filter's "kobler til …" (the VPN started before always-on). M10's
   one-time empty disc: watch for it on the first swap after boot.
5. **Boot**: after the unlock, does the panel replace the deferred plain notice before it shows (§2.3; log the post
   times and pull the shade early)?
6. **The update from a design-99 build**: one notification afterwards (id 1002), none at 1011.
7. **Hiding** (on a phone without the dialer and SMS roles, which takes setting up): turn off Snarveier -> the plain
   notice; "Vis i varsellisten igjen" -> the panel on the new channel. On the role-holding emulator, check only that the
   menu says "can't be modified".
8. **The time-rule screen** (a budget of 1 min):
   - the used-up screen shows the ask card; a tap -> "Sender …" -> "Spurt kl. HH:MM";
   - the third request of the day -> "Du har spurt tre ganger i dag.";
   - Kveld turned on now with a budget set -> no ask card (D4);
   - a used-up screen with one app row: after the PIN it stays on the screen (D6);
   - TalkBack: "Be om mer tid, knapp", then the live announcements;
   - gesture and 3-button navigation, nb and en.
9. **Locale**: `cmd locale set-app-locales` nb <-> en: the panel's line and button, the channel names (D7), and the
   screen's card.

**Owed on the Jelly Star** (the emulator can't settle these):

- the swap and the sorting on its SystemUI (M1-M3);
- the gear in its header;
- the time line and action heights;
- the deferred-plain replacement at boot;
- the screen's card with a child's finger.

## 12. Risks

- **Another SystemUI.** M1-M3 are AOSP Android 16. An OEM shade could animate a channel swap as a remove and an add,
  or keep the old position longer. The approved fallback costs nothing: it is still one notification. Check 1 on the
  Jelly Star.
- **A missing channel crashes the launcher (M7).** It would crash the launcher in the deferral window, and a crash of
  the device owner's process ends calls. §2.2's ensure-before-build and its source pin are the guard. Channel ids
  never change here.
- **The rate limit (M5)** sheds updates over ~5 a second per package. The one poster with its trailing post keeps the
  last state right. The VPN's notice is outside it but posts rarely.
- **The time is hidden while folded**: during a call, or after the kid folds the panel until the shade closes. That is
  accepted. The lock screen, the time-rule screen and Home's rule card are unchanged.
- **A taller panel** (177 dp with the button, M16). D1 and D2 are the levers.
- **Deleting a channel that holds the FGS notification** throws in AOSP (read, not measured). `showAgain` only
  deletes while the plain view is up (§2.4).
- **M10's one empty disc**, seen once and not reproduced. Check 4.

## 13. Build steps and sizes

1. **Launcher, the one notification** (one commit): `AnchorView.kt` + `AnchorViewTest`; `AnchorNotification.kt` (the
   poster, `forStart`, the channels, the throttle moved from `ShortcutsRuntime`); `ShortcutsNotification.build` with
   the time line, "Spurt" and the action (`shortcuts_row.xml` gains the big view's lines as a second layout
   `shortcuts_panel_big.xml`, the row included); `ShortcutsService` and its manifest entry removed;
   `CommandListenerService.onCreate` (`forStart`, cancel 1011); `AnchorNotice` hands over instead of posting;
   `ic_stat_gear.xml`; the five icons; the two `baseline_*` drawables removed; D7's channel name; the pins and tests
   updated (`QuietNotificationsTest`, `TimeLeftNoticeTest`, `ShortcutsTest`). **S-M.**
2. **Launcher, the time-rule screen's ask** (§8): `TimeAsk.kt` (the shared path; `TimeAskReceiver` and
   `ShortcutsRuntime.askForTime` use it), `lockAsk` and `unlockOpenRow`'s `askShown`, `AnchorNotice`'s listeners,
   `activity_lock.xml`'s `lock_ask`, `LockActivity`'s bind and listener, two strings en + nb, `TimeLockModelTest`.
   **S.**
3. **Server**: D8's ⓘ sentence, nb + en, `tests::shortcuts`. **XS.** No migration, no API, no contract file.
4. **Docs**: `docs/notes/launcher/push-and-play.md` (the anchor's notification now carries the panel),
   `docs/notes/launcher/kid-settings.md` (Snarveier's section: one notification), `docs/notes/launcher/time-rules.md` and
   `lock-and-glance.md` (the time-rule screen's ask card), design 99's header line pointing here, `docs/testing/emulator.md`'s
   new section, `launcher/CLAUDE.md`'s index if a note moves.

**Sizes: launcher M (S-M + S), server XS.**

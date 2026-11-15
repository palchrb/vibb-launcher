# Design 99: Snarveier, the kid's quick panel in the shade

Status: design, **revision 3**, 2026-11-15 (branch `design-99`), built. **Revision 3** (the user, 2026-11-15, after the
emulator run's screenshots: *"Må vi ha navn under logoen? Det har ikke ios"*): **no names under the buttons.** The
panel is one row of four 40 dp discs, the same view folded and unfolded (§3); the names stay in the picker and as each
button's content description for TalkBack. With it the header's small icon became the Vibb mark (`ic_stat_vibb`), and
the code QA's findings were fixed (`docs/review/qa-99-code.md`, "Fixes"). Revision 1 was 631e74067. Revision
2 takes the user's decisions of 2026-11-15 (§11) and every finding of the design QA, `docs/review/qa-99-design.md` (1
High, 2 Medium, 11 Low; §13 says how each was handled). What changed: **four items in one row**, not six with a 3 x 2
grid; Flymodus from the shade starts its route only after a Vibb window has focus (§4.1); one pure fail-closed tap check
(§2.1); the listeners live in the process, not the service (§2.3).

**Superseded in part by design 100** (`docs/design/100-one-vibb-notification.md`, built in its step 1): there is no
`ShortcutsService` any more. The panel is the anchor's own notification (id 1002) while it is shown, posted by one
poster that swaps it with r13's plain notice; §2.1's "separate from the anchor" and §2.4 (the service's life) are
replaced by design 100 §2, §2.3's throttle covers every post of id 1002, and the panel's small icon is the gear
(`ic_stat_gear`).

Approved by the user on 2026-11-14 (PLAN, "Snarveier"), after design 98 measured that the long-press panel can't work
(§5b and §9.2 there: lock task always sets `DISABLE_SEARCH`, so the assist gesture never reaches Vibb). The user's
words:

- *"Jeg likte best ... KONSEPT A · RUTENETT"*: round buttons, filled peach when on.
- *"l oss droppe tlf nr der, jeg har jo allerede det på home menyen for key contacts"*: no call or contact items.
- *"bør vi bruke det smale utseende med bare enkle ikoner så vi ikke bruker mye plass"*: a narrow row of small round
  icons.
- *"Ja det er greit mtp lommelykt. Ønsker en på lock screen og"*: the flashlight moves from Home into Snarveier in the
  same round. The lock screen's torch button stays.
- 2026-11-15, after revision 1's measurements (§11): **four items, not six, in one row.** Folded, the row of four icons.
  Unfolded (Android always shows the top notification unfolded, S2), the same single row with each item's name under
  it. No grid, no "⋯" cell.
- 2026-11-15, after the build's screenshots: *"Må vi ha navn under logoen? Det har ikke ios"*: **no names** - the same
  row of icons folded and unfolded (revision 3).

The approved mockup is `https://claude.ai/artifact/14trZrh8r52SwX3Geex1aD`, section 3 ("Snarveier i varsellisten");
revision 2 keeps its look and drops its grid.

**The short version.** Snarveier is one ongoing notification. It is the notification of a small foreground service of
its own, **colorized** in the kid's dark ground. Only a colorized foreground-service notification stays at the top
of the shade, and only it escapes Android 16's grouping, which otherwise folds it into a collapsed "Vibb One · 3"
bundle with the launcher's other notifications (§1, measured). It holds **up to four round buttons in one row**: the
kid's picks from nine items, Lommelykt, Lyd, Flymodus and Wi-Fi by default, with no names under them (revision 3).
Android always opens the top notification unfolded; folded or unfolded, the kid sees the same row of icons. Each button is a broadcast to our own receiver: a tap works at once and the shade stays
open. Flymodus On is the one exception: the tap opens a small invisible Vibb window, the shade closes as it opens, and
the route starts only once that window has focus (§4.1). Snarveier is hidden on Android's keyguard, every tap is refused
there, and Vibb's own lock disables the shade.

## 1. Measured (`jelly36`, Android 16 Play image, 480 x 854 at 240 dpi = the Jelly Star's 320 x 569 dp, 2026-11-15)

The probe was a throwaway app, built with the SDK's tools in the scratchpad (`d99/probe`, never in the repo). It was
the device owner, in lock task with features 63 (Vibb's default set), `targetSdk` 36 like the launcher. It posted the
row and the grid as `RemoteViews` and reported each tap. `jelly36` was restored from `~/kid-look-1-avd-pre-start`
before the run (22 of 22 sums) and afterwards (22 of 22 sums and the same file list), and it ran alone under `flock -o
~/emulator.lock`. Sizes come from `uiautomator dump` (px / 1.5 = dp).

| # | What | Result |
|---|---|---|
| S1 | The shade under lock task | No Quick Settings. The notifications start right under the status bar, as `lt-shade.png` showed. The shade's stack is 806 px = **537 dp** tall. A pull has no stable half-open state: it animates to the full shade |
| S2 | **Does Android unfold the top notification by itself?** | **Yes, every time.** The first notification in the list is shown expanded, our LOW silent one too when it is first, and a colorized foreground-service one always (it is always first). When the kid folds it (a tap on its header or chevron), the fold holds while the shade is open, also across our updates of the notification. **When the shade closes, Android forgets the fold**: the next pull shows it unfolded again (four rounds: fold, close, reopen; fold, update, reopen; each came back unfolded). A notification that isn't first opens folded |
| S3 | Ways to keep it narrow by default | **None that keeps the grid.** MIN importance shows only a one-line header ("Snarveier") at the bottom, without the buttons; a tap opens the grid, so every action costs two taps. A group with our own summary is regrouped by Android (S4). A row-only notification (no big view) stays narrow: it has nothing to unfold to |
| S4 | **Android 16's grouping** | **Two silent notifications from one app are bundled.** Snarveier at LOW with one other silent notification of the same app became a collapsed "Snar probe · 2" bundle (`g:Aggregate_SilentSection`), the buttons hidden behind a tap. The launcher always has at least two (the anchor's, the VPN's). An explicit `setGroup` was overridden, and so was a group with its own summary. A plain (not colorized) foreground-service notification was bundled too. **Only the colorized foreground-service notification stayed on its own** |
| S5 | Where the colorized FGS notification sits | **At the top**, above alerting notifications (a "Mamma" message on a DEFAULT channel sat under it) and above the "Silent" section. Its background is our colour (`#0C0C14`); Android turns its own header text white, our own text views keep the colours we give them |
| S6 | Deferral | A foreground-service notification showed **~10 s late** without `setForegroundServiceBehavior(FOREGROUND_SERVICE_IMMEDIATE)`, and at once with it |
| S7 | Width | The custom content is always **decorated** at `targetSdk` 36 (an undecorated build looked the same). Folded: **180 dp** wide (x 102-372 px; the small icon on the left, the chevron on the right). Unfolded: **220 dp** (x 102-432) |
| S8 | Heights | Folded content at most **48 dp** (a 400 dp view was cut to 72 px). The whole folded notification is 68 dp with a 28 dp row, 76 dp with a 36 dp row, 88 dp at the limit. Unfolded content at most **288 dp** (a 600 dp view was cut to 432 px) |
| S9 | **Six in the folded row at 320 dp** | **They fit at 28 dp each**: 180 dp / 6 = 30 dp per cell. Six 36 dp discs overlapped and were cut. The cell's tap target is 30 x 48 dp |
| S10 | The grid, unfolded | 3 x 2 with names fits the 220 dp width (columns of 73 dp; "Bluetooth" at 12 sp is 52 dp wide, "Lommelykt" 61 dp). With 44 dp discs, a name and a "På/Av" line: content 164 dp, notification **234 dp**. With 40 dp discs and the name only: content 125 dp, notification **195 dp** (36 % of the 537 dp shade) |
| S11 | A row-only notification (no big view) | When first: a header line ("Vibb One · Snarveier") over the row at the full 220 dp width, **98 dp** in all. When not first, or folded: the 68 dp row at 180 dp |
| S12 | A tap on a button (a broadcast `PendingIntent`), under lock task | Delivered at once. The probe flipped its state and reposted in 5 ms. The shade **stayed open**, the disc turned peach in place, and a fold held |
| S13 | A tap on the header | It folds and unfolds the notification. It never fires a content intent |
| S14 | Android's keyguard (PIN set) | Snarveier (`VISIBILITY_SECRET`, channel lockscreen visibility secret) **wasn't shown**. A control message on a DEFAULT channel was |
| S15 | Dismissing | Swiping it sideways did nothing. The record carries `NO_DISMISS` (Android exempts a device owner's ongoing notifications from the Android 14 rule that lets users dismiss them). Not in "Clear all" |
| S16 | Long-press (the notification's own menu) | Under lock task it offers Default / Silent and **"Turn off"**. "Turn off" opens a sheet with the app's channels and "All Snar probe notifications". **Turning off the Snarveier channel worked** (importance 0, the notification gone). Turning off "All" did not take: `POST_NOTIFICATIONS` stayed granted (`POLICY_FIXED`) and every notification stayed. The menu's gear and the shade's "Manage" open Settings, which lock task refuses (`result code=101`, the lock-task violation) |
| S17 | The status bar icon | A colorized FGS on a DEFAULT channel shows its small icon in the status bar. LOW wasn't checked (a device check in the build, §10 test 3) |

What this means:

- **Top and on its own needs the colorized foreground-service notification (S4, S5).** A plain notification ends up
  inside a collapsed bundle on every managed phone.
- **Then it is always first, so Android always opens it unfolded (S2).** Revision 2 makes that the normal view: the
  unfolded notification is the same row as the folded one (revision 3: without names, so the same view). Nothing has
  to be narrow by default any more, so S2 costs nothing.
- **Four fit with room to spare.** Folded, 180 dp / 4 = 45 dp a cell, so a 40 dp disc (revision 2: 36 dp) fits in a
  45 x 48 dp target (S7, S8; six needed 28 dp discs, S9). Unfolded, 220 dp / 4 = 55 dp a cell. Revision 2 put each
  item's name under its disc there (revision 1's 12 sp names were 52-61 dp wide, S10, too wide for 55 dp, so they had
  their own size rule); revision 3 has no names.

## 2. The notification

### 2.1 Its parts (launcher, `shortcuts/`)

- **`ShortcutsService`**: a foreground service (`foregroundServiceType="specialUse"`, subtype "Kid's quick controls
  (device owner)"), not exported. Its one notification is Snarveier. It runs only while Snarveier has something to
  show (§2.4). A device owner is exempt from Android's background-start limits for foreground services. It is
  separate from the anchor (`CommandListenerService`), whose notification stays exactly as it is (design r13's
  time-left notice and the rule that it never becomes a second notification).
- **The channel** `launcher:shortcuts` (and `launcher:shortcuts.N` after §2.6): `IMPORTANCE_LOW`, no sound, no
  vibration, `setShowBadge(false)`, lockscreen visibility `VISIBILITY_SECRET`. Name "Snarveier" / "Shortcuts".
- **The notification**: `DecoratedCustomViewStyle`, the one row (§3) as both `setCustomContentView` and
  `setCustomBigContentView` (revision 3), `setColorized(true)` with `kid_ground` (`#0C0C14`),
  `setForegroundServiceBehavior(FOREGROUND_SERVICE_IMMEDIATE)` (S6), `setOngoing(true)`, `setOnlyAlertOnce(true)`,
  `setSilent(true)`, `setShowWhen(false)`, `setVisibility(VISIBILITY_SECRET)`, `CATEGORY_STATUS`, the launcher's
  small icon `ic_stat_vibb` (revision 3: the Vibb mark's rings and core in white on transparent, made by
  `launcher/scripts/vibb-icon.py`; the gear it had drew a blank disc in the header), `setSubText("Snarveier")` (the header reads "Vibb One · Snarveier"), no content intent (S13: a header tap
  folds, it never opens anything).
- **`ShortcutsReceiver`**: not exported. Each button's `PendingIntent.getBroadcast` is explicit, `FLAG_IMMUTABLE`,
  with the item's key as the only extra and a request code per key. The receiver trusts nothing but the key. It calls
  `goAsync()` and hands the tap to `ShortcutsRuntime`'s one app-level background executor (Q99-L5), so a tap that lands
  while the service is stopping, or before it exists, is still handled, one at a time. There the tap is checked by
  `shortcutTapAllowed` (below) against the current inputs, and only then acted on.
- **`ShortcutAirplaneActivity`**: the one button that opens a window, Flymodus On (§4.1). Not exported, translucent,
  no UI of its own, its own task (`taskAffinity=""`, `excludeFromRecents`, `noHistory`). Its `PendingIntent.getActivity`
  is explicit and immutable like the broadcasts. It is the only `getActivity` under `shortcuts/` (a source pin).
- **The one tap check: pure `shortcutTapAllowed`** (`ShortcutsTest`, Q99-M1). Inputs:
  - `key`, and the model built now (`shortcutsModel`, §2.3) from `currentPolicyDecision()`, the kid's picks and the
    platform's state;
  - `lockKnown`: `PinLockRuntime.decided` and a time-rule snapshot read without error (`TimeRulesRuntime.snapshot`).
    A tap that lands in a process that is just starting, before either is known, is refused;
  - `locked`: `PinLockRuntime.mode == LOCKED`;
  - `keyguardLocked`: `KeyguardManager.isKeyguardLocked` (Q99-L1: a Swipe keyguard shows silent notifications);
  - for Musikk, `musicUsable`: the music app usable now (`MusicSessions.usable`, the same gate as the lock tile's
    `lockAppUsable`: not while a rule, the used-up budget or the camera lock suspends it).

  It allows the tap only when every input is known, the phone isn't LOCKED, the keyguard isn't locked, and the item is
  in the model, usable (not dimmed) and not busy. The parent's bit is part of the model: an item whose bit is off isn't
  in it. Anything else is refused: logged, nothing done, and the panel is redrawn from the platform's state. A stale
  notification can't act.

### 2.2 When it is shown (pure `shortcutsShown`, `ShortcutsTest`)

All of these:

- device owner, and an `Ok` cached policy (as Kid Settings' `controlsSection`; a corrupt or missing cache shows no
  panel);
- the parent's bit **128** (`QuickControlFeature.SHORTCUTS`, §5) in `quick_controls_mask`;
- at least one item in the panel (§4.3);
- the kid hasn't hidden it (§2.6).

**Never usable on a lock screen:**

- Android's keyguard: hidden by the notification's and the channel's `VISIBILITY_SECRET` on a secure keyguard (S14).
  A Swipe keyguard (`KeyguardMode.UNTOUCHED` without a PIN on the phone) may show it, so the tap check refuses every tap
  while `isKeyguardLocked` (§2.1).
- Vibb's PIN lock (the glance): the shade can't open, because `featuresWhileLocked` drops
  `LOCK_TASK_FEATURE_NOTIFICATIONS` while LOCKED (revision 1 named `setStatusBarDisabled`, which lock task only
  records, Q99-H1). The tap check refuses taps while LOCKED as well.
- The panel itself stays posted then: removing and re-posting it at every lock would make it jump and isn't needed.

**Everywhere else it is the same panel:** Home, any allowed app, the time-rule screen (§4.5), and while the kiosk is
off (Åpne for oppsett, the pause; Quick Settings show then too).

### 2.3 Keeping the icons current (pure `shortcutsModel`, glue `ShortcutsRuntime`)

`shortcutsModel(policy, picks, platform)` returns the panel: the items in order, at most four, each with `{key, on,
level, usable, busy}`. `ShortcutsRuntime.refresh()` computes it and posts only when it differs from the last posted
model (equality on the data class). **At most one post a second** (Q99-L7): Android's limit of about five updates a
second is per package and shared with the anchor's and the VPN's notifications, so a burst of radio events must never
reach it raw. The first change after a quiet second posts at once; the next waits for the rest of the second.

`refresh()` runs on:

- `apply()` (a new policy, the bit, the parent's restrictions), the kid's picks (§4.2), locale changes;
- the platform's own signals, registered **once per process by `ShortcutsRuntime.init` from `Application.initRest`**
  (Q99-L3; the HOME process lives anyway), not by the service, so a panel with nothing to show still hears the change
  that brings it back: the torch (`Torch.addListener`), `ACTION_AIRPLANE_MODE_CHANGED`, `RINGER_MODE_CHANGED_ACTION`
  and `ACTION_INTERRUPTION_FILTER_CHANGED`, `WIFI_STATE_CHANGED_ACTION`, `BluetoothAdapter.ACTION_STATE_CHANGED`, a
  `ContentObserver` on `SCREEN_BRIGHTNESS`, `ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED` (§2.6), the kid's
  Mobildata switch, the time request's state, `AirplaneRuntime`'s listener (the route's busy state, the role);
- Vibb music's session: while the panel holds Musikk, `ShortcutsRuntime` is a `MusicSessions` listener (Vibb music
  only), so the sessions are watched although neither Home nor the lock shows a music surface (Q99-L3);
- each tap's end (a refused write flips back: the disc follows the platform's state, never the tap).

### 2.4 The service's life

- Started (`startForegroundService`) by `ShortcutsRuntime.refresh()` when §2.2 says shown and no instance runs: from
  `Application.initRest` (process start, boot), `apply()`, the kid's picks, and the signals above.
- **One owner, no outside stop** (Q99-L4). `onStartCommand` always calls `startForeground` first, with the model
  `ShortcutsRuntime` holds now, and then asks it again: not shown, and the service calls
  `stopForeground(STOP_FOREGROUND_REMOVE)` and `stopSelf` itself. A running instance is told of a new model on the main
  thread; when the model says not shown (the bit off, no item, not device owner, hidden by the kid), the instance stops
  itself the same way. Nothing ever calls `stopService`, so the `startForegroundService` / stop race that `VpnForeground`
  guards against can't happen.
- `START_NOT_STICKY`. After a process death, `initRest` decides again.

### 2.5 The notification rule, badges, kiosk

- **The notification rule** (`BadgeListenerService`, handy step 11) never touches our own package (`OWN`), and
  Snarveier is ongoing and non-clearable anyway. Nothing changes there.
- **Badges** ignore ongoing notifications and our own package (`badgeCounts`). Nothing changes there.
- **Lock task**: the shade needs `LOCK_TASK_FEATURE_NOTIFICATIONS`, which is always in `DEFAULT_LOCK_TASK_FEATURES`.
  The broadcast buttons open no activity. Flymodus On opens our own `ShortcutAirplaneActivity` (our package is always
  on the lock-task list), and its route opens Settings' voice activity inside design 98's `routePin` window, through
  `LockTaskChrome` only (design 98 §2.2).
- **The shade's "Manage" and the long-press menu's gear** open Settings, which lock task refuses (S16). Nothing new.

### 2.6 The kid hides Snarveier (S16)

The long-press menu lets the kid turn off the Snarveier channel. That is the kid's own panel, so it is the kid's
choice:

- `ShortcutsRuntime` reads the channel's importance at every `refresh()` and on
  `ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED`. Importance `NONE` = hidden: the service stops, the report says
  `hidden: true` (§6), and Kid Settings' Snarveier row reads "Skjult i varsellisten" / "Hidden in the notifications".
- The picker page (§4.2) has a button "Vis i varsellisten igjen" / "Show in the notifications again". It deletes the
  blocked channel and creates `launcher:shortcuts.N+1` (N kept in prefs). A re-created channel with the old id would
  come back blocked; a new id is what Android allows an app to do.
- Vibb never re-creates the channel by itself (that would fight the kid).

## 3. The view

The ground is the colorized `kid_ground`. Off: a disc of white at 12 % with the glyph in white. On: a filled
`kid_accent` peach disc with the glyph in `kid_ground`, as Home's torch tile today. No yellow, no animation (the
launcher-look rule). Glyphs are Material Symbols (Apache-2.0) from `scripts/material-symbols.sh`, as the torch's are
(`shortcut_glyph_<key>.xml`).

**One view, folded and unfolded** (revision 3, the user: *"Må vi ha navn under logoen? Det har ikke ios"*):
`shortcuts_row.xml`, set as both the content and the big content view. Revision 2's second view with the names under
the buttons (`shortcuts_names.xml`), its names' size rule (`shortcutNameSp`, 11-9 sp) and the panel's short names
(`shortcut_name_*`) are gone.

- **Four quarter cells** in one horizontal `LinearLayout` 48 dp tall (the folded content's full height, S8), each
  `layout_weight` 1. The items fill the cells from the left in the kid's order; with fewer than four, the cells on the
  right stay empty (invisible, not gone), so a button never moves when another leaves. The whole cell is the tap
  target (`setOnClickPendingIntent` on the cell).
- **The disc is 40 dp, the glyph 22 dp**, centred. Folded the cells are 45 x 48 dp at 320 dp, unfolded 55 x 48 dp
  (S7): 40 dp is the largest round size that leaves a gap between two discs folded (2.5 dp on each side) and fits the
  48 dp height. Discs and glyphs are in dp, so the font size changes nothing (the measurements: `docs/testing/emulator.md`
  §6an, "Revision 3").
- **The names** are each button's content description only: "Lommelykt, på" / "Flashlight, on" and so on, "Lysstyrke,
  middels" for the level, "Mer tid, spurt" while a request waits, and "ikke tilgjengelig nå" / "not available now" for
  a dimmed item (Q99-L10). The picker shows the same full names (`shortcut_full_*`).
- **No "På/Av" line** (D6): the peach fill says it and TalkBack reads the state.
- About 76 dp folded with the header beside the row, a little more unfolded with the header line above it (S8, S11).

### 3.3 Busy and not usable

- **Busy** (Flymodus's route or its Off, §4.1): the disc at 50 % alpha, taps ignored until it ends.
- **Not usable now** (a temporary state, the table in §4.1): the disc at 40 % alpha, the tap does nothing (D10). The
  content description says "ikke tilgjengelig nå" (Q99-L10). Kid Settings' row says why where there is a row; under a
  time rule Kid Settings can't be opened, and Mer tid and Musikk have no row there, so then nothing on screen says why.
  That is the cost of D10's calm.
- **The parent turned an item's switch off**: the item leaves the panel, and the others close up. It stays in the
  kid's picks and comes back when the switch does.

## 4. The kid's side

### 4.1 The items (what each costs, and whether the kid can really use it under the parent's restrictions)

The candidates are what Vibb can really do as device owner, through code that already exists. Each item follows the
same switch that shows its row in Kid Settings, so "allowed" means one thing in both places.

| Key | Name (nb / en) | On (peach) means | A tap does | The parent's gate | Not usable now (dimmed) when | Absent when | Cost |
|---|---|---|---|---|---|---|---|
| `torch` | Lommelykt / Flashlight | the torch is lit | `Torch.toggle` (design 41: no permission, 10-min auto-off, off when a call begins) | none (as Home's tile today) | - | no flash unit (the emulator) | XS |
| `sound` | Lyd / Sound | the ringer rings (`RINGER_MODE_NORMAL`) | Lyd <-> Lydløs (vibrerer), `QuickControls.setSound` (design 18: never Silent, never a DND) | bit 8 | Do Not Disturb is on (design 18's read-only case, `RuleSilence` included) | no vibrator (`soundState` null) | XS |
| `airplane` | Flymodus / Airplane mode | airplane mode is on | On: `ShortcutAirplaneActivity`, then `AirplaneRoute.turnOn` (below). Off: `AirplaneRoute.turnOff`, a broadcast | bit 64 and the assistant role (`airplaneRow`) | the row's dimmed states: the parent's "Blokker flymodus", the emergency refusal, the cool-down | `airplaneRow` HIDDEN: bit 64 off or the role not held, **unless airplane mode is on** (then it shows on, and a tap turns it off: design 98's Off-only rule) | S |
| `wifi` | Wi-Fi | Wi-Fi is on | `QuickControls.setWifiEnabled` | bit 1 | airplane mode is on (`setWifiEnabled` is refused then, design 98 §1.1) | - | XS |
| `bluetooth` | Bluetooth | Bluetooth is on | `QuickControls.setBluetoothEnabled` | bit 2 | - | no adapter | XS |
| `mobile_data` | Mobildata / Mobile data (panel: Data) | apps may use mobile data | the kid's switch, `MobileNetworkRuntime.setKidOff` (design 97 §4.3) | bit 32 | `kidMobileRow` is PARENT or PHONE_OFF (Bare Vibb, Ingen data, abroad, Android's own data off), or airplane mode is on | - | S |
| `brightness` | Lysstyrke / Brightness | never peach (a level, not on/off) | the next of three steps, lav -> middels -> høy -> lav, `QuickControls.setBrightness` (manual mode, as the slider); the glyph shows the step (D5) | bit 4 | - | - | S |
| `more_time` | Mer tid / More time | never peach | "Be om mer tid": the same path as the anchor's action (`TimeAskReceiver`'s `TimeRequests.ask`, then a sync) | none: shown exactly when the anchor's notice has its time part (`timeLeftNotice`'s `ask` is Action or Asked) | a request is waiting (TalkBack: "spurt" / "asked") | no budget today, or the pause or override runs | S |
| `music` | Musikk / Music | Vibb music is playing | play / pause of **Vibb music's** session (`MusicSessions.playPause`, as Home's card; never another app's) | none: the music app installed | no session, or the music app isn't usable now (`MusicSessions.usable`: a rule, the used-up budget, the camera lock) | the music app not installed | S |

The kid's Bluetooth item turns the radio on and off. Connecting headphones stays in Kid Settings › Bluetooth: an item
can only toggle, it opens nothing.

**Musikk under a rule** (Q99-M1). `apply()` pauses a suspended app's playing session before it suspends it, and the
paused session stays registered, so "a session exists" says nothing. The gate is the music app's own usability, read at
the tap: a rule that suspends Vibb music dims Musikk, and the tap check refuses a play then. A pause always goes (as
`MusicSessions.playPause`), but a suspended app is paused already.

**Flymodus from the shade** (Q99-H1 and its addendum, design 98 §2.2):

- Design 98's window drops `LOCK_TASK_FEATURE_NOTIFICATIONS`, and SystemUI then animates an open shade closed (~300 ms).
  A touch on the shade during that animation stops it, and the shade stays open for the window with its notifications
  clickable (`docs/testing/emulator.md` §6am, "Addendum"). From the shade the kid's finger is in the shade, so the route
  must not start from the tap itself.
- **So Flymodus On is not a broadcast.** Its button is an explicit, immutable `PendingIntent.getActivity` for
  `ShortcutAirplaneActivity`. Android collapses the shade when a notification starts an activity, and the activity's
  window only gets focus once the shade has let it go.
- **Only while it can act** (code QA C99-M1): the activity intent is used only for Flymodus off, usable and not busy
  (pure `shortcutOpensWindow`). A dimmed (the parent's block, the emergency refusal, the cool-down) or busy Flymodus is
  a broadcast like every other item: the check refuses it and the shade stays open (D10), with no window over the app
  in front.
- In `onWindowFocusChanged(true)`, the first time, `ShortcutAirplaneActivity` hands the tap to the shortcuts executor:
  the tap check (§2.1) first, refused and nothing happens, then `AirplaneRoute.turnOn`; when that returns, it finishes.
  (The window itself opens nothing, so the check runs once, at the focus, with the freshest inputs.) No focus within 3 s (the kid held the shade open): it finishes without the route.
  Focus means the shade is closed, so the window opens with no shade to touch.
- Its own task, so finishing it returns to whatever was in front (Home, an app, the time-rule screen), never to Home
  by accident.
- Off needs no Settings and no window: it stays a broadcast, and the shade stays open.
- An item drawn as Off whose tap finds airplane mode on (or the reverse) does what the current state needs, or nothing:
  the activity turns it off; a broadcast for Off with airplane mode already off is refused as stale.

**Considered and left out:**

| Candidate | Why not |
|---|---|
| Ikke forstyrr / Do Not Disturb | Design 18's rule: Vibb never sets a manual DND. An app's DND ends every automatic rule, Vibb's silent time rules included. The Lyd item's Lydløs (vibrerer) is the kid's quiet |
| Mørk modus / Dark theme | No device-owner API; `UiModeManager.setNightMode` needs a privileged permission. The kid's screens are always night anyway |
| Posisjon / Location | The parent's safety setting (Find my device), never the kid's |
| Wi-Fi-sone / Hotspot | No device-owner API for tethering, and it would share the phone's network with devices outside the filter |
| Strømsparing / Battery saver | Design 98 D4: no |
| Skjermrotasjon / Auto-rotate | Only through "Modify system settings", which not every phone has; little use on a phone used upright |
| Ring Mamma, contacts | The user: no call or contact items (Home has them) |
| Openers (Kamera, Dagen, Alarm, Bluetooth headphones) | Home and Kid Settings open them. An item that opens an activity from the shade would need its own lock-task and rule checks |
| Send posisjonen min | A new parent event kind; not asked for |
| Nød / Emergency | Android's own emergency button and the lock's "Nødsamtale" are there. A one-tap emergency in the shade invites pocket calls |

### 4.2 Picking and ordering: Kid Settings › Snarveier

- **The row.** Kid Settings gets a row "Snarveier" with the picks as its sub-line ("Lommelykt, Lyd, Flymodus, Wi-Fi")
  and a chevron, in its own card at the top ("I varsellisten" / "In the notifications"). It shows while bit 128 is on
  and there is at least one candidate. It is never PIN-gated: it changes no rule. Hidden (§2.6): the sub-line reads
  "Skjult i varsellisten".
- **The page** (`ui/kidsettings/ShortcutsPickerActivity`, the pattern of `KidSoundPickerActivity`, in Home's task):
  - the title "Snarveier", the line "Velg opptil 4. De vises øverst når du drar ned fra toppen av skjermen." / "Choose
    up to 4. They show at the top when you pull down from the top of the screen.";
  - the count "3 av 4 valgt" / "3 of 4 chosen";
  - one row per candidate the parent allows, as in §4.1, with its glyph, its full name, a check box, and for the
    chosen ones two 48 dp buttons "Flytt opp" / "Flytt ned" (glyphs, with content descriptions). Buttons, not
    dragging, so it works the same with gesture and 3-button navigation and with TalkBack (the mockup's note);
  - the chosen ones first, in their order; the rest under them, at 55 % opacity until checked;
  - a fifth check is refused with the count line in `kid_accent` for a moment ("Du har valgt 4. Fjern en først." /
    "You have chosen 4. Remove one first.");
  - an item that is allowed but not usable now (no budget today for Mer tid, no music playing) is listed and can be
    chosen. Its sub-line says when it shows ("Vises når du har skjermtid i dag" / "Shows on days with screen time");
  - with the panel hidden (§2.6), the button "Vis i varsellisten igjen" at the top;
  - the list's last row clears the navigation bar: the page pads by `KidInsets`' bar inset, in gesture and 3-button
    navigation alike (Q99-L11).
- **Storage.** The picks are the launcher's alone: an ordered list of keys in CE prefs (`shortcuts_picks`).
  `null` means "the default" (§4.3) and is what a new phone has. The first edit writes the list. Unknown keys from a
  later build are kept and skipped. Nothing is sent to the server but the report (§6).

### 4.3 The panel's items, and the default for a new phone (pure, `ShortcutsTest`)

- The panel = the picks (or the default order when `null`), keeping only the items **offered** now (§4.1's gate and
  "absent" columns), the first four. A dimmed item keeps its place.
- **The default order** (D4): `torch`, `sound`, `airplane`, `wifi`, then `bluetooth`, `mobile_data`, `brightness`,
  `more_time`, `music`. With the default untouched, the panel is the first four of these that are offered. On a new
  phone with the default mask (1, 2, 4, 8, 32, 64, 128) and the assistant step done, that is Lommelykt, Lyd,
  Flymodus, Wi-Fi. A phone without a flash gets Bluetooth in fourth place.
- **The default follows what is offered** (Q99-L9): before the assistant step, the default panel is Lommelykt, Lyd,
  Wi-Fi, Bluetooth; when the role comes, Flymodus takes third place and Bluetooth leaves. That is what the default means;
  once the kid has picked, the panel only changes with the parent's switches.
- Once the kid has picked, nothing is added by itself: a new item the parent allows shows in the picker, not in the
  panel.

### 4.4 Every restriction stays liftable

Snarveier adds no restriction and no new way past one:

- Each item is one the kid already has in Kid Settings, under the same parent switch, or the torch, Mer tid and Musikk,
  which exist on Home, the lock and the anchor's notification today.
- The parent's own blocks (Blokker flymodus, Bare Vibb, Ingen data, the abroad lock) win and show the item dimmed.
  They are lifted where they are today (the PWA, the pause).
- The pause and the override change nothing in the panel.
- Bit 128 off removes the panel at the next sync. Every item's own bit removes that item.

### 4.5 Under a time rule (D9)

- The shade stays reachable on the time-rule screen (it is in Home's locked task, and the notifications feature is
  on). The panel is the same there.
- **Said plainly:** this makes the device switches - Wi-Fi, Bluetooth, Lyd, Lysstyrke, Mobildata and Flymodus -
  reachable under a time rule, where Kid Settings isn't today. None of them weakens a rule: rules are enforced on the
  phone, offline, through suspended apps and the lock, and no switch touches either. Flymodus on is always allowed under
  a rule (design 98 D8). Mer tid is the time request the rule screen offers already. Musikk is gated by the music app's
  usability (§4.1), so a rule that suspends it dims it.
- Kid Settings stays unreachable under a rule, as today, so the picks can't be changed then.

## 5. The parent's side

### 5.1 The policy: bit 128 of `quick_controls_mask`

- `QUICK_CONTROL_SHORTCUTS = 128` (server `handlers/devices.rs`, launcher `QuickControlFeature.SHORTCUTS`). It is
  free: design 98 D4 declined battery saver, which had been proposed for 128.
- No new policy key. Which items are offered follows the existing bits (§4.1), so the parent's switches on the Home
  Screen page decide both Kid Settings and Snarveier.
- **New phones:** in `DEFAULT_QUICK_CONTROLS` (D2).
- **Existing phones:** migration `0148_shortcuts.sql` sets bit 128 on every phone (`quick_controls_mask | 128`), D2.
  Home's flashlight tile goes in the same launcher (§7), so a phone left without the bit would lose its flashlight
  outside the lock screen. An older launcher ignores the bit. (0148 is the next free number on `main` today; recheck
  at merge.)

### 5.2 The PWA: the Home Screen page's quick controls (`/devices/{id}/home#quick-controls`)

- **The row.** A new first switch row "Snarveier i varsellisten" / "Shortcuts in the notifications",
  `name="quick_control_shortcuts"`, in the `section=quick` form. It shows only for a phone that reported
  `shortcuts_v1` (§6). Then the form also carries `shortcuts_row=1`.
- **The mask, as design 98's Q98-M9:** without `shortcuts_row`, `update_policy` keeps bit 128, as it keeps 32 and
  64: `kept_bits` gains `QUICK_CONTROL_SHORTCUTS` when the row is absent. With the row, the bit comes from the switch.
  A server test pins both, for `section=quick` and the whole form.
- **A sub-line under the label, only when it says something** (from the report, §6). The kid is named, never given a
  pronoun (Q99-L2):
  - shown: "Emma har valgt: Lommelykt, Lyd, Flymodus, Wi-Fi" / "Emma chose: Flashlight, Sound, Airplane mode, Wi-Fi"
    (the item names in the PWA's language, from the contract's keys);
  - hidden by the kid: "Emma har skjult Snarveier i varsellisten. Emma kan vise dem igjen under Innstillinger ›
    Snarveier." / "Emma has hidden the shortcuts in the notifications. Emma can show them again under Settings ›
    Shortcuts.";
  - the bit on but no items: "Ingen snarveier vises nå." / "No shortcuts are shown now." True both when nothing is
    offered and when the kid chose none, which the report can't tell apart (code QA C99-L3; it was "Ingen av bryterne
    under er slått på.", untrue in the second case).
- **The ⓘ** gets: "Snarveier er et fast varsel øverst i varsellisten med inntil fire knapper. Emma velger dem blant
  bryterne her og lommelykten. Det vises aldri på låseskjermen." / "Shortcuts is a fixed notification at the top of the
  notifications with up to four buttons. Emma picks them from the switches here and the flashlight. It never shows on
  the lock screen."
- **The footer** (`device.home.quick_footer`) gains: "Bryterne gjelder både Innstillinger og Snarveier." / "The
  switches apply to both Settings and Shortcuts."
- A save autosaves on `#quick-controls` through the existing staying-POST path (`scroll-restore.js`, `X-Handy-Stay`),
  so it never jumps to the top. This page is a phone sub-page: no bell. nb and en for every string.

### 5.3 About This Phone

One line in the settings list: "Snarveier: Lommelykt, Lyd, Flymodus" / "Shortcuts: Flashlight, Sound, Airplane mode",
or "Skjult av Emma" / "Hidden by Emma", or "Av" / "Off". Nothing on the summary's state lines.

## 6. The report and the contract

### 6.1 The report: `shortcuts` (a full-report field, not a heartbeat field)

```json
"shortcuts": {"shown": true, "hidden": false, "items": ["torch", "sound", "airplane", "wifi"]}
```

- `shown`: the panel is posted now (§2.2). `hidden`: the kid turned the channel off (§2.6). `items`: the panel's keys
  in order, dimmed ones included, at most four.
- It changes rarely, so it stays out of `status_heartbeat_fields`; a change changes the report's hash and a full
  report goes (design 95-T2).
- The launcher reports the capability `shortcuts_v1`.
- The server stores the cleaned object in `device_status.shortcuts_json` (migration 0148) and reads it for §5.2 and
  §5.3. `intake_limits` keeps its keys.

### 6.2 The contract: a new file `server/testdata/shortcuts.json`

Byte-identical in `launcher/app/src/test/resources/`.

- `capability` (`shortcuts_v1`), `bit` (128), `max_items` (4);
- `items`: every key with its gating bit (`null` for `torch`, `more_time`, `music`) and its names in nb and en (the
  full names, as the PWA and the picker show them);
- `defaults`: the default order (§4.3);
- `report`: the exact object above, and `report_hidden`, a hidden one;
- `clean`: the server's cleaning - unknown keys dropped, duplicates dropped, cut to four, `shown` false when `hidden`
  is true, a non-object dropped.

The launcher's `ShortcutsTest` checks its key enum, gating bits and default order against `items` and `defaults`,
encodes `report` exactly and compares the copies. The server's `tests::shortcuts` posts `report`, checks what is
stored, runs `clean`, renders §5.2's sub-lines from it and compares the copies. The panel's own rules (§2.2, §4.3, the
table's dimmed cases, `shortcutTapAllowed`) are the launcher's alone and are tested there.

The root `CLAUDE.md` gets the contract paragraph, and `launcher.yml` and `server-ci.yml` get the file in their path
lists (both copies). No policy key changes: bit 128 rides in the existing `quick_controls_mask`, already pinned by
`policy_json_keys_snapshot` and `PolicyResponseCompatTest`.

### 6.3 Older launchers, older servers

- **An older launcher** ignores bit 128 and reports no `shortcuts_v1`: the PWA hides the row, and the save keeps the
  bit (§5.2). It keeps its Home flashlight tile.
- **This launcher with an older server** (no bit 128, no migration): no panel, and Home has no flashlight tile; the
  lock screen's torch stays. Release order: the server first, as usual.

## 7. Home's flashlight tile goes

Design 41 put two things on Home: the tile and its listener. Both go. Nothing else on Home depends on the torch.

- `ui/home/HomeModel.kt`: `GridTile.Flashlight` and `homeGrid`'s `torch` parameter go, and `gridTileId`'s
  `"flashlight"`.
- `ui/home/HomeGridAdapter.kt`: both `when` branches.
- `ui/HomeActivity.kt`: `torchListener`, its `addListener`/`removeListener`, and `Torch.state` in `renderGrid`.
- Tests: `HomeModelTest` and `HomeRound7Test` lose the tile's cases. `TorchRulesTest`'s pinned callers of
  `Torch.toggle` become the lock's button and the shortcuts' torch item. `BootCoverManifestTest` and
  `LockGlanceGuardTest` only need a check that they don't name the tile.
- **Stays:** `torch/Torch.kt`, `TorchRules` (the 10-minute auto-off, off at a call), `TorchOffReceiver`, the lock
  glance's button (`lock/GlancePanel.kt`, design 41 §1.3; its own `lock_torch*` strings - the tile's `torch_title`/
  `torch_state_on`/`_off` had no reader left and went, code QA C99-L5), the
  glyphs, and the colour `kid_torch_tile` (`kid_lock_alarm_row` uses it).
- Home gets one tile fewer. `docs/testing/emulator.md` §6 check 12 (Home's fold, 11 tiles in 3 columns) counts no
  flashlight tile, since the emulator has no flash; its numbers stand, and the build checks the fold on the Jelly
  Star's count.
- `docs/notes/launcher/home-and-photos.md`'s "The flashlight on Home" section becomes "The flashlight" (Snarveier and
  the lock).

## 8. Security and privacy

- Nothing new is reachable. Every button is a broadcast to our own non-exported receiver with an immutable intent,
  or, for Flymodus On, our own non-exported activity, and every tap passes the one fail-closed check (§2.1). No
  button opens another app. Flymodus's route is design 98's, with its window, started only once the shade is closed.
- Nothing is read from other apps. The panel shows only Vibb's own words and the platform's states; Musikk drives
  only Vibb music's session.
- The report carries only item keys and two flags.
- Snarveier is never usable on a lock screen (§2.2).
- The kid can hide the panel (§2.6), which removes only the kid's own buttons.

## 9. Risks

- **Android's unfolding (S2) is AOSP SystemUI's behaviour.** The Jelly Star's SystemUI may differ: an OEM may not
  unfold the first notification, or may limit custom views differently. With one row in both views (revision 3: the
  same view) that costs nothing. Device check (§10 test 8).
- **Grouping (S4) is new in Android 16** and could change in a later Android. The colorized foreground service is
  what escapes it today; if a later Android bundles that too, the panel would hide behind a tap. The report's `shown`
  and a device check on each Android update catch it.
- **Three foreground services in one process** (the anchor, the VPN's `KidVpnService` while it holds its foreground
  state, and Snarveier; Q99-L6). The third costs a notification, no receivers of its own (they are the process's,
  §2.3), no wake lock and no work while idle.
- **The long-press menu can turn off other Vibb One channels too** (S16), Meldinger and missed calls among them. That
  is true today and not part of this design (D11, a separate item).

## 10. Tests

Unit (`ShortcutsTest`, `ShortcutsPickerTest`, the changed Home and torch tests):

- `shortcutsShown`: each condition of §2.2, the corrupt cache, not device owner, hidden.
- `shortcutsModel`: the default order and the first four offered; picks kept and the unoffered skipped; each table
  row of §4.1 (gates, dimmed cases, absent cases); Flymodus's Off-only case; the airplane-mode effects on Wi-Fi and
  Mobildata; Mer tid's states; Musikk's usability gate; the default following the role; no automatic additions after
  a pick; equality, so an unchanged model posts nothing.
- `shortcutTapAllowed`: lock state or time-rule snapshot unknown, LOCKED, the keyguard, an unknown key, an item not
  in the model, dimmed, busy, Musikk with the music app unusable; the one allowed case per item.
- `shortcutOpensWindow`: only Flymodus off, usable and not busy opens the window; dimmed or busy it is a broadcast
  (C99-M1).
- The coalescing: a burst posts once now and once a second later, never more.
- The picker: at most four, up and down at the ends, the order written, `null` until the first edit.
- The contract (§6.2) on both sides; the server's mask keep for `section=quick` and the whole form; the sub-lines.
- Source pins: the only `getActivity` under `shortcuts/` is `ShortcutAirplaneActivity`'s; the channel is created with
  `VISIBILITY_SECRET`; `setColorized` and `FOREGROUND_SERVICE_IMMEDIATE` are set; nothing calls `stopService` on
  `ShortcutsService`.

Emulator (`jelly36` restored clean, `wm density` stays 240; a new section in `docs/testing/emulator.md`):

1. The panel at the top under lock task, with three Meldinger threads, a missed call and an ongoing call (Q99-L8): not
   bundled, and where the call and the panel sit. Unfolded on the first pull (revision 2: with four names, their
   measured size and widths, nb and en; revision 3: the same row as folded, the disc size at font scale 1.0 and 1.3);
   folded by a header tap; unfolded again on the next pull. In **gesture and 3-button
   navigation** (Q99-L11).
2. Each emulator-testable item's tap with the shade open: Lyd, Wi-Fi, Bluetooth, Mobildata, Lysstyrke, Mer tid,
   Musikk; the disc follows the platform's state; a refused change flips back. The same under a time rule.
3. The status bar icon at LOW (S17).
4. Android's keyguard with a PIN: no panel; with Swipe: taps refused. Vibb's PIN lock: the shade doesn't open.
5. Flymodus from the shade with a third-party app in front: the shade closes, the route starts only after the
   activity's focus (`dumpsys device_policy`, `dumpsys statusbar`, `dumpsys window` as §6am's addendum read them), the
   item turns peach, the app is in front again; Off from the shade.
6. The parent's switch off: the panel goes at the next sync; an item's bit off: it leaves the panel.
7. The kid hides it from the long-press menu: the report, the PWA line, "Vis igjen" brings it back on a new channel.
8. The picker in gesture and 3-button navigation: the last row above the bar, up and down, the fifth check refused.
9. A reboot: the panel back after the unlock, the picks kept.
10. **On the Jelly Star** (owed, the emulator can't): the torch item; the unfolding (S2) and grouping (S4) on its
    SystemUI; the row with a child's finger.

## 11. Decisions (the user, 2026-11-15)

- **D1. Four items in one row** (settled by the user, replacing revision 1's a/b and QA's c). Folded: four icons
  (36 dp discs in 45 x 48 dp cells). Unfolded, which Android shows first: the same row with each item's name under it
  (55 dp cells, §3.2's names rule). No 3 x 2 grid, no "⋯" cell. **Revision 3** (the user, after the screenshots: *"Må
  vi ha navn under logoen? Det har ikke ios"*): no names; the same row of 40 dp discs folded and unfolded (§3).
- **D2. Bit 128 on** for new phones (`DEFAULT_QUICK_CONTROLS`) and for every existing phone by migration 0148, because
  Home's flashlight tile goes in the same launcher.
- **D3. Mer tid and Musikk are items.**
- **D4. The default order**: Lommelykt, Lyd, Flymodus, Wi-Fi, then Bluetooth, Mobildata, Lysstyrke, Mer tid, Musikk.
  The first four offered show; the default follows what is offered (§4.3).
- **D5. Lysstyrke has three steps** (lav, middels, høy; a tap moves to the next, the glyph shows the step). The slider
  stays in Kid Settings.
- **D6. No "På"/"Av" line.** The filled peach disc is on.
- **D7. The parent sees what the kid picked**, or that the kid hid it (§5.2's sub-line, About This Phone).
- **D8. Hiding is the kid's choice**: a line in the PWA, and "Vis i varsellisten igjen" on the kid's own picker page
  only. Vibb never brings it back by itself.
- **D9. The same panel under a time rule** (§4.5), said plainly: it makes the device switches reachable under a rule,
  where Kid Settings isn't today; none weakens a rule.
- **D10. A dimmed tap does nothing.** The content description says it isn't available now.
- **D11. Blocked channels** (the long-press menu can turn off Meldinger and the missed calls): a separate later item,
  not in this build.

## 12. Build steps and sizes

1. The contract `shortcuts.json` in both directories, the root `CLAUDE.md` paragraph, both workflows' path lists.
2. Server: bit 128 and the default, migration 0148 (the bit on existing phones per D2, `device_status.shortcuts_json`),
   `shortcuts.rs` (capability, `clean`), the Home Screen row with `shortcuts_row` and the keep, the sub-lines, About
   This Phone, i18n nb + en, `tests::shortcuts`. **S.**
3. Launcher, the model: `shortcuts/ShortcutsModel.kt` (the items, `shortcutsShown`, `shortcutsModel`,
   `shortcutTapAllowed`, `shortcutNameSp` (gone in revision 3), the default), the picks' storage, the report field and the capability, the
   contract test. In the same commit as the server's intake (an API change crosses both). **S.**
4. Launcher, the notification: `ShortcutsService`, the channel, the two `RemoteViews` layouts, the glyphs,
   `ShortcutsRuntime` (refresh, coalescing, the process's listeners, the channel re-creation), `ShortcutsReceiver`,
   `ShortcutAirplaneActivity` and the nine items' actions. **M.**
5. Launcher, Kid Settings: the row and `ShortcutsPickerActivity`, strings nb + en. **S.**
6. Launcher, Home: the flashlight tile removed (§7) and its tests. **XS.**
7. Docs: the area notes (`kid-settings.md` gains a Snarveier section, `home-and-photos.md` §7), the `launcher/CLAUDE.md`
   index, `docs/testing/emulator.md`'s new section.

**Sizes: launcher M, server S.**

## 13. Design QA (`docs/review/qa-99-design.md`): how each finding was handled

| Finding | Handled |
|---|---|
| Q99-H1 the window's "status bar disabled" does nothing under the kiosk | Fixed in `main` before this revision (design 98 §2.2: the window's features drop `LOCK_TASK_FEATURE_NOTIFICATIONS`; §6am "Addendum"). What that left - a touch during SystemUI's ~300 ms collapse keeps the shade open - is why Flymodus On from the shade goes through `ShortcutAirplaneActivity` and starts the route only on its window's focus (§4.1). §2.2's wording is corrected |
| Q99-M1 the tap check's inputs, Musikk's gate | One pure fail-closed `shortcutTapAllowed` with named inputs: the model, `lockKnown`, LOCKED, the keyguard, Musikk's usability (§2.1). Musikk drives Vibb music only and is gated by `MusicSessions.usable`, not by a session existing (§4.1) |
| Q99-M2 D1 narrower than it is | Moot: the user chose four in one row, which needs no fold of its own (D1) |
| Q99-L1 a Swipe keyguard | The tap check refuses while `isKeyguardLocked`; §10 test 4 adds Swipe |
| Q99-L2 a pronoun for the kid | The PWA repeats the name (§5.2); the design's prose says "the kid" |
| Q99-L3 listeners only while the service runs | The process's receivers from `initRest`, and a `MusicSessions` listener while Musikk is in the panel (§2.3) |
| Q99-L4 `START_STICKY` against the one owner | `START_NOT_STICKY`; `onStartCommand` always calls `startForeground` and then asks `ShortcutsRuntime`; nothing calls `stopService` (§2.4) |
| Q99-L5 the receiver's executor | `goAsync()` and an app-level single executor (§2.1) |
| Q99-L6 three foreground services | §9 corrected |
| Q99-L7 the shared update rate | One post a second at most (§2.3) |
| Q99-L8 grouping with Meldinger and calls | §10 test 1 |
| Q99-L9 one default order | One order everywhere (§4.2, §4.3, §5.2, D4), and the default follows the role (§4.3) |
| Q99-L10 a dimmed item's reason under a rule | In D10's cost (§3.3) and in the content description |
| Q99-L11 gestures and buttons | The picker pads by the bar inset (§4.2); §10 tests 1 and 8 in both modes |

## 14. Build notes (2026-11-15, `docs/testing/emulator.md` §6an)

- **The kid can't hide the panel on a phone where Vibb One holds the dialer or SMS role.** Android locks a default
  app's notification channels (`mImportanceLockedDefaultApp`): the long-press menu says "These notifications can't be
  modified." The probe of §1 (S16) held no role. §2.6's hidden state, its report flag and "Vis i varsellisten igjen"
  stay for a phone without those roles. D11 (blocked channels) is moot on such phones for the same reason.
- **Names at large font sizes** (moot since revision 3: no names). At font scale 1.3 the 9 sp floor was reached and
  the longest names were cut ("Brightne…", "Lomme…").
- **During a call** the CallStyle notification is first and the panel second and folded (the icons, usable).
- The picker's refusal line stands in the count line's place (a line above the list moved the rows under the next tap).
- **Revision 3's check** (§6an, "Revision 3"): folded and unfolded show the same row of 40 dp discs (45 dp cells
  folded, 55 dp unfolded), unchanged at font scale 1.3; the header shows the Vibb mark. It found that "Blokker
  flymodus" didn't dim Flymodus until another signal (the restriction is set after the policy write the panel hears,
  and no SDK broadcast says a restriction changed): `AppEnforcer.applyHardening` now asks for a refresh at its end.

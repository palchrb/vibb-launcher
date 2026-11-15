# Design 99: Snarveier, the kid's quick panel in the shade

Status: design, revision 1, 2026-11-15 (branch `design-99`). Not built. §11 lists the decisions this design needs
before it is built.

Approved by the user on 2026-11-14 (PLAN, "Snarveier"), after design 98 measured that the long-press panel can't work
(§5b and §9.2 there: lock task always sets `DISABLE_SEARCH`, so the assist gesture never reaches Vibb). The user's
words:

- *"Jeg likte best ... KONSEPT A · RUTENETT"*: up to six round buttons, filled peach when on.
- *"l oss droppe tlf nr der, jeg har jo allerede det på home menyen for key contacts"*: no call or contact items.
- *"bør vi bruke det smale utseende med bare enkle ikoner så vi ikke bruker mye plass"*, and the same evening *"ditt
  forslag om å utvide den ved å trykke på den er bra og det vil jeg ja"*: a narrow row of six small round icons, and
  the 3 x 2 grid with names on a tap or drag.
- *"Ja det er greit mtp lommelykt. Ønsker en på lock screen og"*: the flashlight moves from Home into Snarveier in the
  same round. The lock screen's torch button stays.

The approved mockup is `https://claude.ai/artifact/14trZrh8r52SwX3Geex1aD`, section 3 ("Snarveier i varsellisten").

**The short version.** Snarveier is one ongoing notification. It is the notification of a small foreground service of
its own, **colorized** in the kid's dark ground. Only a colorized foreground-service notification stays at the top
of the shade, and only it escapes Android 16's grouping, which otherwise folds it into a collapsed "Vibb One · 3"
bundle with the launcher's other notifications (§1, measured). Each button is a broadcast to our own receiver. A tap
works at once and the shade stays open, except Flymodus, whose route closes the shade for under a second (design
98 §2.2). It is hidden on Android's keyguard, and Vibb's own lock disables the shade.

**What Android decides, measured (§1):** Android **unfolds the top notification every time the shade opens**, and it
forgets a fold when the shade closes. A Snarveier with a grid therefore opens as the grid, and a tap on its header
folds it to the narrow row until the shade closes. That is the opposite of the user's wish, and no API changes it.
D1 asks which way to go.

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
- **Then it is always first, so Android always opens it unfolded (S2).** "Narrow by default, unfold on a tap" can't be
  had with a grid. Either the grid opens and a tap folds it (D1 a), or there is no grid (D1 b).
- The width is enough for six in the folded row only at 28 dp discs (S9), and the grid with names fits (S10).

## 2. The notification

### 2.1 Its parts (launcher, `shortcuts/`)

- **`ShortcutsService`**: a foreground service (`foregroundServiceType="specialUse"`, subtype "Kid's quick controls
  (device owner)"), not exported. Its one notification is Snarveier. It runs only while Snarveier has something to
  show (§2.4). A device owner is exempt from Android's background-start limits for foreground services. It is
  separate from the anchor (`CommandListenerService`), whose notification stays exactly as it is (design r13's
  time-left notice and the rule that it never becomes a second notification).
- **The channel** `launcher:shortcuts` (and `launcher:shortcuts.N` after §2.6): `IMPORTANCE_LOW`, no sound, no
  vibration, `setShowBadge(false)`, lockscreen visibility `VISIBILITY_SECRET`. Name "Snarveier" / "Shortcuts".
- **The notification**: `DecoratedCustomViewStyle`, the folded view (`setCustomContentView`, §3.1) and the grid
  (`setCustomBigContentView`, §3.2; not with D1 b), `setColorized(true)` with `kid_ground` (`#0C0C14`),
  `setForegroundServiceBehavior(FOREGROUND_SERVICE_IMMEDIATE)` (S6), `setOngoing(true)`, `setOnlyAlertOnce(true)`,
  `setSilent(true)`, `setShowWhen(false)`, `setVisibility(VISIBILITY_SECRET)`, `CATEGORY_STATUS`, the launcher's
  small icon, `setSubText("Snarveier")` (the header reads "Vibb One · Snarveier"), no content intent (S13: a header tap
  folds, it never opens anything).
- **`ShortcutsReceiver`**: not exported. Each button's `PendingIntent.getBroadcast` is explicit, `FLAG_IMMUTABLE`,
  with the item's key as the only extra and a request code per key. The receiver trusts nothing but the key: it
  re-reads the cached policy, the kid's picks and the platform state, and acts only when the pure model (§2.3) says
  the item is in the panel and usable now. It hands the work to the service's single background executor at once
  (Flymodus can take a few seconds), so the receiver returns at once.
- **Taps are refused** (logged, nothing done) while the PIN lock is LOCKED, while not device owner, or when the item
  isn't in the model. A stale notification can't act.

### 2.2 When it is shown (pure `shortcutsShown`, `ShortcutsTest`)

All of these:

- device owner, and an `Ok` cached policy (as Kid Settings' `controlsSection`; a corrupt or missing cache shows no
  panel);
- the parent's bit **128** (`QuickControlFeature.SHORTCUTS`, §5) in `quick_controls_mask`;
- at least one item in the panel (§4.3);
- the kid hasn't hidden it (§2.6).

**Never on the lock screen:**

- Android's keyguard: hidden by the notification's and the channel's `VISIBILITY_SECRET` (S14). Silent notifications
  are hidden there by Android's default too.
- Vibb's PIN lock (the glance): the shade can't open, because `LockTaskChrome` disables the status bar while LOCKED.
  The receiver refuses taps while LOCKED as well (§2.1).
- The panel itself stays posted then: removing and re-posting it at every lock would make it jump and isn't needed.

**Everywhere else it is the same panel:** Home, any allowed app, the time-rule screen (§4.5), and while the kiosk is
off (Åpne for oppsett, the pause; Quick Settings show then too).

### 2.3 Keeping the icons current (pure `shortcutsModel`, glue `ShortcutsRuntime`)

`shortcutsModel(policy, picks, platform)` returns the panel: the items in order, each with `{key, on, level, usable,
busy}`. `ShortcutsRuntime.refresh()` computes it and posts only when it differs from the last posted model (equality
on the data class), coalesced to at most one post per 250 ms. Android sheds an app's notification updates beyond about
five a second, so a burst of radio events must never reach it raw.

`refresh()` runs on:

- `apply()` (a new policy, the bit, the parent's restrictions), the kid's picks (§4.2), locale changes;
- the platform's own signals, registered by `ShortcutsService` while it runs: the torch (`Torch.addListener`),
  `ACTION_AIRPLANE_MODE_CHANGED`, `RINGER_MODE_CHANGED_ACTION` and `ACTION_INTERRUPTION_FILTER_CHANGED`,
  `WIFI_STATE_CHANGED_ACTION`, `BluetoothAdapter.ACTION_STATE_CHANGED`, a `ContentObserver` on `SCREEN_BRIGHTNESS`, the
  kid's Mobildata switch (`MobileNetworkRuntime`), the time request's state (`TimeRequests`), the music session
  (`MusicMedia`), `AirplaneRoute.busy`;
- each tap's end (a refused write flips back: the disc follows the platform's state, never the tap).

### 2.4 The service's life

- Started (`startForegroundService`) by `ShortcutsRuntime.refresh()` when §2.2 says shown and it isn't running:
  from `Application.initRest` (process start, boot), `apply()`, the kid's picks.
- Stopped (`stopForeground(STOP_FOREGROUND_REMOVE)`, `stopSelf`) when §2.2 says not shown: the bit off, no item, not
  device owner, hidden by the kid.
- `START_STICKY`. After a process death, `initRest` decides again.
- The `startForegroundService` / `stopService` race that `VpnForeground` guards against (a stop landing before
  `startForeground`) gets the same guard: one owner (`ShortcutsRuntime`), the stop only after `startForeground` has
  run.

### 2.5 The notification rule, badges, kiosk

- **The notification rule** (`BadgeListenerService`, handy step 11) never touches our own package (`OWN`), and
  Snarveier is ongoing and non-clearable anyway. Nothing changes there.
- **Badges** ignore ongoing notifications and our own package (`badgeCounts`). Nothing changes there.
- **Lock task**: the shade needs `LOCK_TASK_FEATURE_NOTIFICATIONS`, which is always in `DEFAULT_LOCK_TASK_FEATURES`.
  No button opens an activity, so nothing new is pinned. The one exception is Flymodus's route, which opens Settings'
  voice activity inside design 98's `routePin` window, through `LockTaskChrome` only (design 98 §2.2).
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

## 3. The two views

The ground is the colorized `kid_ground`. Off: a disc of white at 12 % with the glyph in white. On: a filled
`kid_accent` peach disc with the glyph in `kid_ground`, as Home's torch tile today. No yellow, no animation (the
launcher-look rule). Glyphs are Material Symbols (Apache-2.0) from `scripts/material-symbols.sh`, as the torch's are.

### 3.1 Folded: one row (the `RemoteViews` `shortcuts_row.xml`)

- One horizontal `LinearLayout`, one cell per item (1-6), each cell `layout_weight` 1 and the content's full 48 dp
  height. The whole cell is the tap target (`setOnClickPendingIntent` on the cell), so with six it is 30 x 48 dp (S9).
- The disc is **28 dp**, the glyph 16 dp. With fewer than six items the cells widen and the discs stay 28 dp,
  centred in their cells.
- Content descriptions: "Lommelykt, på" / "Flashlight, on" and so on, "Lysstyrke, middels" for the level.
- 68 dp in all (S8).

### 3.2 Unfolded: the 3 x 2 grid (`shortcuts_grid.xml`; not with D1 b)

- Two rows of three columns (73 dp each at 320 dp), filled in the kid's order, left to right then down. One to three
  items make one row.
- A 40 dp disc with a 22 dp glyph, then the name, 12 sp, one line, `autoSizeTextType="uniform"` down to 10 sp (for
  large font sizes: "Lommelykt" is 61 dp at 1.0).
- **No "På/Av" line by default** (D6): the peach fill says it and TalkBack reads the state. The grid is then 195 dp in
  all, not 234 dp (S10).
- Names in white, `kid_core` for a dimmed item's name.

### 3.3 Busy and not usable

- **Busy** (Flymodus's route or its Off, §4.1): the disc at 50 % alpha, taps ignored until it ends.
- **Not usable now** (a temporary state, the table in §4.1): the disc at 40 % alpha, the tap ignored, nothing else.
  No toast (D10). Kid Settings' row says why.
- **The parent turned an item's switch off**: the item leaves the panel, and the others close up. It stays in the
  kid's picks and comes back when the switch does.

## 4. The kid's side

### 4.1 The items (what each costs, and whether the kid can really use it under the parent's restrictions)

The candidates are what Vibb can really do as device owner, through code that already exists. Each item follows the
same switch that shows its row in Kid Settings, so "allowed" means one thing in both places.

| Key | Name (nb / en) | On (peach) means | A tap does | The parent's gate | Not usable now (dimmed) when | Absent when | Cost |
|---|---|---|---|---|---|---|---|
| `torch` | Lommelykt / Flashlight | the torch is lit | `Torch.toggle` (design 41: no permission, 10-min auto-off, off when a call begins) | none (as Home's tile today) | - | no flash unit (the emulator) | XS |
| `airplane` | Flymodus / Airplane mode | airplane mode is on | On: `AirplaneRoute.turnOn` (design 98 §2.2). Off: `AirplaneRoute.turnOff` | bit 64 and the assistant role | the route's refusals: emergency, cooldown; the parent's "Blokker flymodus" | bit 64 off or role not held, **unless airplane mode is on** (then it shows on, and a tap turns it off: design 98's Off-only rule) | S |
| `sound` | Lyd / Sound | the ringer rings (`RINGER_MODE_NORMAL`) | Lyd <-> Lydløs (vibrerer), `QuickControls.setSound` (design 18: never Silent, never a DND) | bit 8 | Do Not Disturb is on (design 18's read-only case) | no vibrator | XS |
| `wifi` | Wi-Fi | Wi-Fi is on | `QuickControls.setWifiEnabled` | bit 1 | airplane mode is on (`setWifiEnabled` is refused then, design 98 §1.1) | - | XS |
| `bluetooth` | Bluetooth | Bluetooth is on | `QuickControls.setBluetoothEnabled` | bit 2 | - | no adapter | XS |
| `mobile_data` | Mobildata / Mobile data | apps may use mobile data | the kid's switch, `MobileNetworkRuntime.setKidOff` (design 97 §4.3) | bit 32 | `kidMobileRow` says set by the parent (Bare Vibb, Ingen data, abroad), or airplane mode is on | - | S |
| `brightness` | Lysstyrke / Brightness | never peach (a level, not on/off) | the next of three steps, lav -> middels -> høy -> lav, `QuickControls.setBrightness` (manual mode, as the slider); the glyph shows the step (D5) | bit 4 | - | - | S |
| `more_time` | Mer tid / More time | never peach | "Be om mer tid": the same path as the anchor's action (`TimeAskReceiver`'s `TimeRequests.ask`, then a sync) | none: shown exactly when the anchor's notice offers the action (a budget applies, requests are on, none waiting) | a request is waiting ("Spurt" / "Asked" as its name) | no budget today | S |
| `music` | Musikk / Music | the music app is playing | play / pause of the music app's session (`MusicMedia`, as the glance tile) | none: the music app allowed | no session | the music app not installed | S |

The kid's Bluetooth item turns the radio on and off. Connecting headphones stays in Kid Settings › Bluetooth: an item
can only toggle, it opens nothing.

**Flymodus from the shade:**

- The route needs no activity of ours: the assistant's session starts Settings' voice activity itself. It worked with
  the screen off and at the keyguard in design 98 (M5, M6).
- It closes the shade: the route disables the status bar for its window (under a second, design 98 §2.2), which
  collapses an open shade. That is acceptable, and the item shows busy until the window ends.
- It is the first time the route runs while a third-party app is in front. The build checks that on the emulator
  (§10 test 5).

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

- **The row.** Kid Settings gets a row "Snarveier" with the picks as its sub-line ("Lommelykt, Flymodus, Lyd …") and a
  chevron, in its own card at the top ("I varsellisten" / "In the notifications"). It shows while bit 128 is on and
  there is at least one candidate. It is never PIN-gated: it changes no rule. Hidden (§2.6): the sub-line reads
  "Skjult i varsellisten".
- **The page** (`ui/kidsettings/ShortcutsPickerActivity`, the pattern of `KidSoundPickerActivity`, in Home's task):
  - the title "Snarveier", the line "Velg opptil 6. De vises øverst når du drar ned fra toppen av skjermen." / "Choose
    up to 6. They show at the top when you pull down from the top of the screen.";
  - the count "4 av 6 valgt" / "4 of 6 chosen";
  - one row per candidate the parent allows, as in §4.1, with its glyph, its name, a check box, and for the chosen ones
    two 48 dp buttons "Flytt opp" / "Flytt ned" (glyphs, with content descriptions). Buttons, not dragging, so it
    works the same with gesture and 3-button navigation and with TalkBack (the mockup's note);
  - the chosen ones first, in their order; the rest under them, at 55 % opacity until checked;
  - a seventh check is refused with the count line in `kid_accent` for a moment ("Du har valgt 6. Fjern en først.");
  - an item that is allowed but not usable now (no budget today for Mer tid, no music session) is listed and can be
    chosen. Its sub-line says when it shows ("Vises når du har skjermtid i dag" / "Shows on days with screen time");
  - with the panel hidden (§2.6), the button "Vis i varsellisten igjen" at the top.
- **Storage.** The picks are the launcher's alone: an ordered list of keys in CE prefs (`shortcuts_picks`).
  `null` means "the default" (§4.3) and is what a new phone has. The first edit writes the list. Unknown keys from a
  later build are kept and skipped. Nothing is sent to the server but the report (§6).

### 4.3 The panel's items, and the default for a new phone (pure, `ShortcutsTest`)

- The panel = the picks (or the default order when `null`), keeping only the items **offered** now (§4.1's gate and
  "absent" columns), the first six. A dimmed item keeps its place.
- **The default order** (D4): `torch`, `sound`, `airplane`, `wifi`, `bluetooth`, `mobile_data`, `brightness`,
  `more_time`, `music`. With the default untouched, the panel is the first six of these that are offered. On a new
  phone with the default mask (1, 2, 4, 8, 32, 64, 128) and the assistant step done, that is Lommelykt, Lyd,
  Flymodus, Wi-Fi, Bluetooth, Mobildata. A phone without a flash gets Lysstyrke in sixth place.
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

### 4.5 Under a time rule

- The shade stays reachable on the time-rule screen (it is in Home's locked task, and the notifications feature is
  on). The panel is the same there (D9).
- No item weakens a rule: rules are enforced on the phone, offline. Flymodus on is always allowed under a rule (design
  98 D8). Mer tid is the time request the rule screen offers already. Musikk has no session while the music app is
  outside the rule's usable apps, so it is dimmed then.
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
  outside the lock screen. An older launcher ignores the bit. (Recheck the number at merge.)

### 5.2 The PWA: the Home Screen page's quick controls (`/devices/{id}/home#quick-controls`)

- **The row.** A new first switch row "Snarveier i varsellisten" / "Shortcuts in the notifications",
  `name="quick_control_shortcuts"`, in the `section=quick` form. It shows only for a phone that reported
  `shortcuts_v1` (§6). Then the form also carries `shortcuts_row=1`.
- **The mask, as design 98's Q98-M9:** without `shortcuts_row`, `update_policy` keeps bit 128, as it keeps 32 and
  64: `kept_bits` gains `QUICK_CONTROL_SHORTCUTS` when the row is absent. With the row, the bit comes from the switch.
  A server test pins both, for `section=quick` and the whole form.
- **A sub-line under the label, only when it says something** (from the report, §6):
  - shown: "Emma har valgt: Lommelykt, Flymodus, Lyd, Wi-Fi" / "Emma chose: Flashlight, Airplane mode, Sound, Wi-Fi"
    (the item names in the PWA's language, from the contract's keys);
  - hidden by the kid: "Emma har skjult Snarveier i varsellisten. Hun kan vise dem igjen under Innstillinger ›
    Snarveier." / "Emma has hidden the shortcuts in the notifications. She can show them again under Settings ›
    Shortcuts.";
  - the bit on but nothing offered: "Ingen av bryterne under er slått på." / "None of the switches below is on."
- **The ⓘ** gets: "Snarveier er et fast varsel øverst i varsellisten med inntil seks knapper. Emma velger dem selv
  blant bryterne her og lommelykten. Det vises aldri på låseskjermen." / "Shortcuts is a fixed notification at the
  top of the notifications with up to six buttons. Emma picks them herself from the switches here and the
  flashlight. It never shows on the lock screen."
- **The footer** (`device.home.quick_footer`) gains: "Bryterne gjelder både Innstillinger og Snarveier." / "The
  switches apply to both Settings and Shortcuts."
- A save autosaves on `#quick-controls` through the existing staying-POST path (`scroll-restore.js`, `X-Handy-Stay`),
  so it never jumps to the top. This page is a phone sub-page: no bell. nb and en for every string.

### 5.3 About This Phone

One line in the settings list: "Snarveier: Lommelykt, Flymodus, Lyd" / "Shortcuts: Flashlight, Airplane mode, Sound",
or "Skjult av Emma" / "Hidden by Emma", or "Av" / "Off". Nothing on the summary's state lines.

## 6. The report and the contract

### 6.1 The report: `shortcuts` (a full-report field, not a heartbeat field)

```json
"shortcuts": {"shown": true, "hidden": false, "items": ["torch", "sound", "airplane", "wifi"]}
```

- `shown`: the panel is posted now (§2.2). `hidden`: the kid turned the channel off (§2.6). `items`: the panel's keys
  in order, dimmed ones included, at most six.
- It changes rarely, so it stays out of `status_heartbeat_fields`; a change changes the report's hash and a full
  report goes (design 95-T2).
- The launcher reports the capability `shortcuts_v1`.
- The server stores the cleaned object in `device_status.shortcuts_json` (migration 0148) and reads it for §5.2 and
  §5.3. `intake_limits` keeps its keys.

### 6.2 The contract: a new file `server/testdata/shortcuts.json`

Byte-identical in `launcher/app/src/test/resources/`.

- `capability` (`shortcuts_v1`), `bit` (128);
- `items`: every key with its gating bit (`null` for `torch`, `more_time`, `music`) and its names in nb and en;
- `defaults`: the default order (§4.3);
- `report`: the exact object above, and a hidden one;
- `clean`: the server's cleaning - unknown keys dropped, duplicates dropped, cut to six, `shown` false when `hidden`
  is true.

The launcher's `ShortcutsTest` checks its key enum, gating bits and default order against `items` and `defaults`,
encodes `report` exactly and compares the copies. The server's `tests::shortcuts` posts `report`, checks what is
stored, runs `clean`, renders §5.2's sub-lines from it and compares the copies. The panel's own rules (§2.2, §4.3, the
table's dimmed cases) are the launcher's alone and are tested there.

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
  `Torch.toggle` become the lock's button and `ShortcutsReceiver`'s torch item. `BootCoverManifestTest` and
  `LockGlanceGuardTest` only need a check that they don't name the tile.
- **Stays:** `torch/Torch.kt`, `TorchRules` (the 10-minute auto-off, off at a call), `TorchOffReceiver`, the lock
  glance's button (`lock/GlancePanel.kt`, design 41 §1.3), the strings `torch_title`/`torch_state_on`/`_off` (now
  Snarveier's), the glyphs, and the colour `kid_torch_tile` (`kid_lock_alarm_row` uses it).
- Home gets one tile fewer. `docs/testing/emulator.md` §6 check 12 (Home's fold, 11 tiles in 3 columns) counts no flashlight tile, since the
  emulator has no flash; its numbers stand, and the build checks the fold on the Jelly Star's count.
- `docs/notes/launcher/home-and-photos.md`'s "The flashlight on Home" section becomes "The flashlight" (Snarveier and
  the lock).

## 8. Security and privacy

- Nothing new is reachable. Every button is a broadcast to our own non-exported receiver with an immutable intent,
  and the receiver re-checks the policy, the lock and the item (§2.1). No button opens an activity. Flymodus's route
  is design 98's, with its window.
- Nothing is read from other apps. The panel shows only Vibb's own words and the platform's states.
- The report carries only item keys and two flags.
- Snarveier is never on a lock screen (§2.2).
- The kid can hide the panel (§2.6), which removes only her own buttons.

## 9. Risks

- **Android's unfolding (S2) is AOSP SystemUI's behaviour.** The Jelly Star's SystemUI may differ: an OEM may not
  unfold the first notification, or may limit custom views differently. Device check (§10 test 8).
- **Grouping (S4) is new in Android 16** and could change in a later Android. The colorized foreground service is
  what escapes it today; if a later Android bundles that too, the panel would hide behind a tap. The report's `shown`
  and a device check on each Android update catch it.
- **28 dp discs in 30 dp cells** (S9) are small for a child's finger in the folded row. The grid has 73 x 64 dp
  targets. With D1 a the grid is what opens.
- **Two foreground services in one process** (the anchor and Snarveier). The second costs a notification and a few
  receivers, no wake lock and no work while idle.
- **The long-press menu can turn off other Vibb One channels too** (S16), Meldinger and missed calls among them. That
  is true today and not part of this design (D11).

## 10. Tests

Unit (`ShortcutsTest`, `ShortcutsPickerTest`, the changed Home and torch tests):

- `shortcutsShown`: each condition of §2.2, the corrupt cache, not device owner, hidden.
- `shortcutsModel`: the default order and the first six offered; picks kept and the unoffered skipped; each table row
  of §4.1 (gates, dimmed cases, absent cases); Flymodus's Off-only case; the airplane-mode effects on Wi-Fi and
  Mobildata; Mer tid's states; no automatic additions after a pick; equality, so an unchanged model posts nothing.
- The receiver's refusals: LOCKED, unknown key, an item not in the model.
- The picker: at most six, up and down at the ends, the order written, `null` until the first edit.
- The contract (§6.2) on both sides; the server's mask keep for `section=quick` and the whole form; the sub-lines.
- Source pins: no `RemoteViews` click opens an activity (`getActivity` is never used under `shortcuts/`); the channel
  is created with `VISIBILITY_SECRET`; `setColorized` and `FOREGROUND_SERVICE_IMMEDIATE` are set.

Emulator (`jelly36` restored clean, `wm density` stays 240; a new section in `docs/testing/emulator.md`):

1. The panel at the top under lock task with a message and the launcher's own notifications; not bundled; the grid
   on the first pull (D1 a), the fold by a header tap, unfolded again on the next pull.
2. Each emulator-testable item's tap with the shade open: Lyd, Wi-Fi, Bluetooth, Mobildata, Lysstyrke, Mer tid,
   Musikk; the disc follows the platform's state; a refused change flips back.
3. The status bar icon at LOW (S17).
4. Android's keyguard with a PIN: no panel. Vibb's PIN lock: the shade doesn't open.
5. Flymodus from the shade with a third-party app in front: the route runs, the shade closes, the item turns peach;
   Off from the shade.
6. The parent's switch off: the panel goes at the next sync; an item's bit off: it leaves the panel.
7. The kid hides it from the long-press menu: the report, the PWA line, "Vis igjen" brings it back on a new channel.
8. **On the Jelly Star** (owed, the emulator can't): the torch item; the unfolding (S2) and grouping (S4) on its
   SystemUI; the folded row at 28 dp with a child's finger.

## 11. Decisions for the user

- **D1. Narrow by default isn't possible with a grid (S2).** Android opens the top notification unfolded every time
  the shade opens, and forgets a fold when it closes.
  - **a. Recommended: the grid opens, a tap on its header folds it to the row.** The kid sees the names at once; it
    takes 195 dp of the 537 dp shade (36 %), and the shade of a managed phone is otherwise nearly empty. A tap folds
    it to the 68 dp row while the shade is open.
  - b. Only the row, never a grid: 98 dp at the top (the row at full width under a header line), 68 dp folded. No
    names on screen (TalkBack reads them).
  - Not possible: the row by default with the grid on a tap. MIN importance gives a one-line header without buttons,
    so every action takes two taps, at the bottom of the shade; a plain notification is bundled by Android 16 (S4).
- **D2. Bit 128 on.** **Recommended: on for new phones, and on for every existing phone by migration 0148**, because
  Home's flashlight tile goes in the same launcher. The alternative (existing phones keep their mask) leaves those
  kids with the lock screen's torch only until the parent turns Snarveier on.
- **D3. Mer tid and Musikk as items.** **Recommended: both.** Mer tid is the anchor notification's "Be om mer tid",
  which today sits folded at the bottom of the shade; Musikk is play/pause of the music app's session. Each S. Without
  them the list is the seven device switches.
- **D4. The default order** for a phone where the kid hasn't picked. **Recommended:** Lommelykt, Lyd, Flymodus,
  Wi-Fi, Bluetooth, Mobildata, then Lysstyrke, Mer tid, Musikk (the first six offered show).
- **D5. Lysstyrke as three steps** (lav, middels, høy; a tap moves to the next, the glyph shows the step).
  **Recommended: yes.** The slider stays in Kid Settings. Two steps or none are the alternatives.
- **D6. A "På"/"Av" line under each name in the grid.** **Recommended: no.** The peach fill says it, TalkBack reads it,
  and the line adds 39 dp (234 dp against 195 dp).
- **D7. The parent sees what Emma picked** (§5.2's sub-line, About This Phone). **Recommended: yes**; it is the
  report's `items`, part of the server's S.
- **D8. When Emma hides Snarveier** from the long-press menu. **Recommended: her choice**: a line in the PWA, and "Vis
  i varsellisten igjen" on her picker page. Vibb never brings it back by itself.
- **D9. Under a time rule. Recommended: the same panel** (§4.5). No item weakens a rule. The alternative is no panel
  while a rule is in force.
- **D10. A dimmed item's tap. Recommended: nothing happens** (calm; Kid Settings says why). The alternative is a short
  toast with the reason.
- **D11. Outside this design, found while measuring (S16):** the long-press menu under the kiosk can turn off any of
  Vibb One's notification channels, Meldinger and missed calls included. Today that goes unnoticed. **Recommended: a
  small follow-up**: the report names a blocked channel, and the PWA says "Emma har slått av varsler for Meldinger".
  Size S (both sides). Not in this design's sizes.

## 12. Build steps and sizes

1. Server: bit 128 and the default, migration 0148 (the bit on existing phones per D2, `device_status.shortcuts_json`),
   `shortcuts.rs` (capability, `clean`), the Home Screen row with `shortcuts_row` and the keep, the sub-lines, About
   This Phone, i18n nb + en, the contract and `tests::shortcuts`. **S.**
2. Launcher, the model: `shortcuts/ShortcutsModel.kt` (the items, `shortcutsShown`, `shortcutsModel`, the default),
   the picks' storage, the contract test. **S.**
3. Launcher, the notification: `ShortcutsService`, the channel, the two `RemoteViews` layouts, the glyphs,
   `ShortcutsRuntime` (refresh, coalescing, the listeners, the channel re-creation), `ShortcutsReceiver` and the nine
   items' actions, the report field and the capability. **M.**
4. Launcher, Kid Settings: the row and `ShortcutsPickerActivity`, strings nb + en. **S.**
5. Launcher, Home: the flashlight tile removed (§7) and its tests. **XS.**
6. Docs: the area notes (`kid-settings.md` gains a Snarveier section, `home-and-photos.md` §7), the root `CLAUDE.md`
   contract paragraph, the workflows' path lists, `docs/testing/emulator.md`'s new section.

**Sizes: launcher M (towards M-L with D3's two items), server S.**

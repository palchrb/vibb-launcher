# 107 - Blocked notification channels, named in the PWA (revision 2)

Round `blocked-channels`, 2026-11-16. Launcher and server. **Status: built** (2026-11-16, branch `blocked-channels`; gate `docs/testing/emulator.md` §6ax; "Built" at the end). PLAN: "Blocked notification
channels named in the PWA" (approved 2026-11-15, design 99 D11, size S). The status report names every Vibb One
notification channel that is turned off, and the phone's page in the PWA says so. The Q list at the end holds the choices
with a cost the parent or the kid can see. The user hasn't answered it yet.

Revision 2 answers the design QA (0 High, 2 Medium, 12 Low). §13 maps each finding to its change.

## 1. Today (checked against the code and the measurements)

- **Nothing reports or shows a blocked channel.** The status report (`server/dto/StatusReportRequest.kt`) has no such
  field. The server stores nothing about it, and no page says it. Two narrow cases are covered today. Snarveier's own
  channel is reported as `shortcuts.hidden` (design 99 §2.6), and the window relock's `own_notifications_off`
  (`WindowRelock.detect`) goes into the security log's relock line, but only at the end of a parent-code window.
- **The channels, from the code** (design 100 changed only the listener channel's name; §5 there):

  | Channel id | nb / en name (the phone's strings) | Importance | Used when |
  |---|---|---|---|
  | `launcher:messages` | Meldinger / Messages | DEFAULT | SMS managed (Vibb's Meldinger) |
  | `launcher:messages_silent` | Meldinger (stille) / Messages (silent) | LOW | the same, under a time rule |
  | `launcher:messages_reactions` | Reaksjoner / Reactions | DEFAULT | the same |
  | `launcher:missed_calls` | Tapte anrop / Missed calls | DEFAULT | calls managed (Vibb is the dialer) |
  | `launcher:calls` | Samtaler / Calls | HIGH, silent | the same |
  | `launcher:calls_silent` | Samtaler (samtaleskjermen er åpen) / Calls (call screen open) | LOW | the same |
  | `launcher:command_listener` | Vibb One / Vibb One | MIN | the anchor's plain notice (time left) while Snarveier isn't shown |
  | `launcher:shortcuts[.N]` | Snarveier / Shortcuts | LOW | the panel (design 99, 100) |
  | `launcher:ring` | Finn enheten – ringing / Find My Device ring | HIGH | a `ring` command's "Stop" |
  | `launcher:window` | Åpnet med foreldrekoden / Opened with the parent code | LOW | an open pause's "Lock again" |
  | `launcher:install_mode` | Play-installasjonsmodus / Play install mode | LOW | Play install mode |
  | `launcher:app_install` | Installering og oppdatering av apper / App installs and updates | LOW | a catalog install |
  | `launcher:vpn_filter` | Innholdsfilter / Content filter | MIN | the filter's foreground state before always-on |
  | `launcher:setup` | Oppsett / Setup | LOW | the setup window's steps |

  No channel groups exist (no `NotificationChannelGroup` in the code). `launcher:crash` is deleted at start.
- **What the kid can do, measured.**
  - S16 (design 99) measured that the long-press "Turn off" sheet blocks a channel under lock task. The test app was a
    probe app without roles.
  - **On Vibb One itself every `launcher:` channel is locked while Vibb holds the dialer role.** `docs/testing/emulator.md`
    §6an found `defaultAppLocked=true` / `mImportanceLockedDefaultApp=true` on every one, and the long-press menu says
    "These notifications can't be modified" (design 99 §14, design 100 §11 check 7).
  - AOSP keeps this lock for the holders of `NON_BLOCKABLE_DEFAULT_ROLES` (the dialer and the emergency role; read from
    the source, not measured).
  - Vibb takes the dialer role whenever `managed` is true (`calls/DialerRole.kt`). That is every `calls_mode` /
    `sms_mode` pair except unmanaged/unmanaged (`sms_contract.json`'s `modes`). So **on a phone where Vibb runs calls or
    SMS, the kid can block nothing today.** PLAN's line "Meldinger and missed calls included" comes from the probe, not
    from Vibb One.
- **The app-wide switch** (M2).
  - While Vibb holds the roles, POST_NOTIFICATIONS is granted by policy (`OwnPermissions.kt:47`), so the sheet's "All
    Vibb One notifications" doesn't take (S16).
  - When the dialer role is handed back and SMS isn't managed, `AppEnforcer.apply` returns the fixed permissions,
    POST_NOTIFICATIONS among them, to `GRANT_STATE_DEFAULT` (`AppEnforcer.kt:1020-1025`). Only the end of a pause fixes
    the permission again (`WindowRelock.regrantNotifications`, `WindowRelock.kt:692`).
  - **So on an unmanaged phone the app-wide switch may take from the sheet.** Not measured; gate step 1 records it.
- **Where a channel block is still possible:**
  1. A phone with calls and SMS both unmanaged. Vibb hands the dialer role back, and the kid can block the anchor's
     notice, Snarveier, the ring, the window's notice, the minor channels, and perhaps all of Vibb One's notifications
     (above). Meldinger and the call channels are unused there.
  2. A phone where the device-owner take of the role failed (`CallState`'s dialer error).
  3. A block made while the role was off that survives when the role is taken later (gate step 3).
  4. Another SystemUI (the Jelly Star's Unihertz build is not checked) or a later Android (17 not checked).
  5. Inside a pause (lock task off, Settings visible, §6ar), where the gear opens Vibb One's notification page.
- **What AOSP predicts (read, not measured; the gate measures both, L7):**
  - A NONE that was set before the lock stays NONE; the lock only stops later user changes. The app's own page in
    Settings still lets it be raised: the lock greys "off", not "on".
  - The long-press sheet (`ChannelEditorDialogController`) lists neither a channel at NONE nor a locked one. So the kid
    has no way back from the sheet.
- **The broadcast exists.** The brief said "no broadcast for its own channels on 34+". That is wrong:
  `NotificationManager.ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED`, `..._GROUP_BLOCK_STATE_CHANGED` and
  `ACTION_APP_BLOCK_STATE_CHANGED` exist since API 28. They are sent only to the owning package (`setPackage`), so a
  runtime `RECEIVER_NOT_EXPORTED` receiver gets them. AOSP's `maybeNotifyChannelOwner` sends the channel broadcast when a
  user's change crosses NONE, which is the long-press sheet's path (`updateNotificationChannelForPackage`).
  `ShortcutsRuntime.init` already registers the channel action. An app's own change sends nothing (design 100 M6).
  Delivery for a real user's block was never measured, because the role lock stopped it, so the gate measures it.

## 2. What is reported

A new full-report field, `notification_channels`, with capability `notification_channels_v1`
(`server/StatusCapabilities.kt`):

```json
"notification_channels": {"all_off": false, "blocked": ["messages", "missed_calls"]}
```

- **`blocked`**: the keys of the table's channels that are blocked now, in the table's order (§7.1). A channel is
  blocked when its importance is `IMPORTANCE_NONE`, or when its group exists and is blocked
  (`NotificationChannelGroup.isBlocked`). The group case is generic: no groups exist today, and the read costs nothing.
  - **The key** is the id without `launcher:`. Snarveier's channel in use (`ShortcutsRuntime.currentChannelId`, never a
    fixed string) is `shortcuts`.
  - **Skipped:** older `.N` ids and any id not in the table. That includes Android 16's reserved `android.app.*`
    channels (the system's classification channels, e.g. `android.app.promotions`), which the system may create for an
    app (L6).
  - **A channel that doesn't exist** (Meldinger before the SMS engine ever started) is not blocked.
- **`all_off`**: `NotificationManagerCompat.areNotificationsEnabled()` is false, the app-wide switch.
  - While Vibb holds the roles, POST_NOTIFICATIONS is fixed by policy, so only a pause (Settings) can turn it off, and
    the window relock grants it again.
  - On an unmanaged phone the permission is back at DEFAULT (§1), so the sheet may turn it off too.
  - Either way the report also names `blocked` as read, since the channels keep their own importance.
- **One read**: `nm.notificationChannels` and `nm.notificationChannelGroups` (two binder calls) and
  `areNotificationsEnabled()`, at each report build, off the main thread. A pure `channelsReport(channels: List<Channel>,
  blockedGroups: Set<String>, appEnabled: Boolean, shortcutsId: String)` makes the object
  (`notify/NotificationChannels.kt`, new, GPL).
- **Not reported**: a channel the kid made "Silent" (LOW). That is the same kind of choice as the ringer mode (Q6).
- **Not a heartbeat field.** `status_heartbeat_fields` doesn't change, so the field is in the report's hash
  (`reportHash`). Any change sends a full report at the next sync, even while heartbeats run (design 95 T2).
- Every report from a launcher with the capability carries the object, `{"all_off": false, "blocked": []}` included. A
  launcher whose read throws sends no object (`null`), and the server then shows nothing (§4).

## 3. When it is reported

- **At once, through the broadcast.** `Application.initRest` registers one runtime receiver (`RECEIVER_NOT_EXPORTED`,
  in the HOME process, which lives anyway) for the three block-state actions. Each one calls
  `SyncRunner.request(app, "notification_channels")`. `SyncRunner` already coalesces a burst, and the sheet's Apply sends
  one broadcast per changed channel. The sync's report reads the state (§2). The hash differs, so it goes as a full
  report. With the stream up, the PWA has it within seconds.
- **The receiver never reads the intent's extras** (L5). A comment in the receiver says why: no fast path on
  `EXTRA_BLOCKED_STATE`. A change the platform refused still sends the broadcast, so the extra can claim a block that
  never happened. The report's own read (§2) is the only truth.
- **The fallback is the read at every report.** If a broadcast is missed (a dead process, an OEM that doesn't send it,
  a change made by a revoke in Settings during a pause), the next sync carries the change: the backstop (30 min), an SSE
  nudge, or a process start. That is the same freshness as every other report field.
- **Two of our own triggers.** The Settings row's return (§6) and Snarveier's "Vis i varsellisten igjen"
  (`ShortcutsRuntime.showAgain`) also request a sync. An app-side delete and re-create sends no broadcast (M6), so these
  two would otherwise wait.
- No polling, no new alarm, no wake lock.

## 4. The server: intake, storage, cleaning

- **Migration** (the next free number; 0151 if nothing lands first: A5 + B2 adds none):
  `ALTER TABLE device_status ADD COLUMN notification_channels_json TEXT;`. It is retained with its status row, so it
  needs no retention change.
- **`src/notification_channels.rs`** (new):
  - `CAPABILITY`.
  - `CHANNELS`: the table of §7.1 (key, nb, en, `feature`: `sms` / `calls` / none, `orange`, `attention`), the
    contract's `channels`.
  - `clean(&Value) -> Option<Value>`:
    - not an object -> `None`, stored `NULL`;
    - `all_off`: a bool, else false;
    - `blocked`: only strings that are table keys, each once, re-ordered to the table's order, so at most the table's
      length. A missing or non-array `blocked` gives `[]` (L9);
    - every other key dropped.
  - `report(latest) -> Option<Report>`: only from a launcher with the capability *and* a stored object. With the
    capability but no object, the read failed or the object was dropped, so there is no row. Showing "All on" without
    a reading would be a false all-clear.
- **Intake** (`handlers/device_api.rs`, next to `shortcuts_json`): `report.notification_channels` through `clean`, then
  bound into the insert. The heartbeat's expansion keeps it through the stored base (`device_report_base`), like every
  full-report field.
- **`intake_limits`**: `notification_channels.json`'s `report`, `report_none` and `report_all_off` are kept key for key by
  `clean`, in the same loop as `shortcuts` (L9).
- **No security event.** The security log records what opens or weakens the phone. A notification turned off in the
  shade is neither (Q5). So `handlers/admin.rs`'s `every_written_event_has_a_label` scan has nothing new to label.
  - Builder's note: the module's snake_case keys (`missed_calls`, `messages_silent`, `command_listener`, ...) are
    literals the scan would take for event ids if they sat within 15 lines before or 12 after an event writer, or in the
    70 lines after a `let (... event` tuple.
  - Keep them in `notification_channels.rs`, which has no writer, and never build an event from them.
  - If one does end up next to a writer, add it to `NOT_EVENTS` with a comment, and raise the array's declared size
    (`[&str; 40]`) to match (L9).

## 5. The PWA

### 5.1 Which channels are shown

- **A channel counts only while its feature is in use**, by the policy now. `messages`, `messages_silent` and
  `messages_reactions` count while `sms_mode == "managed"`. `calls`, `calls_silent` and `missed_calls` count while
  `calls_mode == "managed"`. Every other channel always counts. A blocked Meldinger on a phone whose SMS Vibb doesn't
  handle carries nothing, and naming it would be a false alarm.
- **`shortcuts` is never named here.** Snarveier's own rows (Home Screen's sub-line, About's "Snarveier: Skjult av Emma")
  already say it (design 100 §5).
- **The names are the phone's own words** (the contract's nb/en, equal to the launcher's strings, §8), so the parent can
  tell the kid exactly what to turn on (L4).
  - They are in the phone's language when the server knows it: the policy's `launcher_ui.language` is `nb` or `en`.
  - With `system`, the server can't know the phone's language, and the report doesn't carry it. The names are then in
    the PWA's language, and may differ from what the kid sees. That mismatch is accepted.
  - The names are quoted («Meldinger» / "Messages"), so a name in the other language reads as a name.

### 5.2 Where

- **About This Phone › Lyd / Sound** (`device_pages.rs`, `sound`): a new row after Ikke forstyrr, key `notifications`.
  Like Do Not Disturb, it is about what the phone lets through, and the group's footer already says "at last report".
  - Only with a `report` (§4). An older launcher has no row.
  - **The value:**
    - "Alle på" / "All on" when nothing that counts is blocked;
    - "Alle av" / "All off" when `all_off`;
    - else "Av: Meldinger, Tapte anrop" / "Off: Messages, Missed calls". Up to four names are shown in full. With five
      or more: three names, then "og 2 til" / "and 2 more" (L12).
  - **The class:** `row-value-action` (orange) when `all_off`, or when an `orange` channel that counts is blocked
    (`messages`, `missed_calls`, `calls`). Else plain.
  - **The ⓘ** (only when something is off): the lead, one sentence for each named channel that has an effect (§5.4),
    then the fix (§6).
- **Attention** (§5.3) links to `/devices/{id}/about#sound`.
- Nothing else. Not on the summary's body (Attention puts it there), not on Home Screen, not in the Devices list's
  status line.

### 5.3 Attention

- **One row, `Kind::NotificationsOff`, level Action (orange), key `notifications-off`.** It is raised only for:
  - a blocked `messages` or `missed_calls` while that feature counts (§5.1);
  - `all_off` while SMS or calls are managed.
- **Samtaler is orange on About but raises no row** (QA on Q2). The phone still rings (Telecom plays the ringtone), and
  the call screen is started directly (`CallNotifications.kt:85-96`), so a blocked call channel costs only the shade's
  entry.
- The others never raise it: the anchor, the ring, the window's notice, the minor channels and Snarveier. Losing them
  costs a parent nothing they must act on, and Snarveier is the kid's choice (design 99 D8).
- **Where in the list** (design 31 §3.2's order; `attention::items`, L10): right after `NoNotificationAccess` and before
  the calls rows. It is a notification fact like the one before it, and the Action rows keep their order.
- **Title** (at most 8 words): "Varsler slått av: Meldinger, Tapte anrop" / "Notifications off: Messages, Missed calls"
  (only the attention channels' names, in the names' language of §5.1). With `all_off`: "Alle varsler fra Vibb One er
  av" / "All Vibb One notifications are off".
- `read_from_report` is true: an Offline phone's row says "At last report" and still counts.
- **No false alarms, by construction:**
  - only a launcher that claims the capability and sent an object;
  - only importance NONE (or the app-wide switch) as read, never a guess, and a missing channel never counts;
  - only a feature in use;
  - never for the kid's own panel.
  - On a role-holding phone the row can't come from a sheet at all (§1). It would mean a stale block or a SystemUI that
    ignores the lock, both of which are real.
- The Devices list's badge counts it (the same `items`).

### 5.4 The words (nb / en)

| Key | nb | en |
|---|---|---|
| `device.about.notifications` | Varsler | Notifications |
| `.all_on` | Alle på | All on |
| `.all_off` | Alle av | All off |
| `.off` | Av: {names} | Off: {names} |
| `.more` | {names} og {count} til | {names} and {count} more |
| `.lead` | Disse varslene fra Vibb One er slått av på telefonen. | These Vibb One notifications are turned off on the phone. |
| `.lead_all_off` | Alle varsler fra Vibb One er slått av på telefonen. Ingen varsler fra Vibb One vises. | All Vibb One notifications are turned off on the phone. No Vibb One notices show. |
| `.effect.messages` | {name}: nye meldinger gir ingen lyd og vises ikke i varsellisten eller på låseskjermen. Tallet på Meldinger på Hjem-skjermen vises fortsatt. | {name}: new messages make no sound and don't show in the notification shade or on the lock screen. The count on Messages on Home still shows. |
| `.effect.missed_calls` | {name}: et tapt anrop vises ikke i varsellisten. Merket på kontakten vises fortsatt. | {name}: a missed call doesn't show in the notification shade. The badge on the contact still shows. |
| `.effect.calls` | {name}: et innkommende anrop vises ikke i varsellisten. Telefonen ringer fortsatt, og samtaleskjermen åpnes. | {name}: an incoming call doesn't show in the notification shade. The phone still rings, and the call screen opens. |
| `.effect.command_listener` | Kanalen {name} (tid igjen): når Snarveier ikke vises, vises heller ikke tiden som er igjen i varsellisten. | The {name} channel (time left): while the shortcuts aren't shown, the time left doesn't show in the notification shade either. |
| `.effect.ring` | {name}: stoppknappen vises ikke mens telefonen ringer. Den ringer likevel; stopp den under Finn telefonen. | {name}: the stop button doesn't show while the phone rings. It still rings; stop it under Find Phone. |
| `.effect.window` | {name}: varselet med «Lås igjen» vises ikke mens telefonen er åpen. | {name}: the notice with "Lock again" doesn't show while the phone is open. |
| `.fix` | Slik slår du dem på igjen: åpne Innstillinger i Vibb One på telefonen (foreldrekode), slå på «Sett alle begrensninger på pause», og trykk så «Varsler fra Vibb One» på samme side. Slå på de som er nevnt over, og slå av pausen igjen. | To turn them on again: on the phone, open Vibb One's Settings (parent code), turn on "Pause all restrictions", then tap "Vibb One notifications" on the same page. Turn on the ones named above, then turn the pause off again. |
| `device.attention.notifications_off` | Varsler slått av: {names} | Notifications off: {names} |
| `device.attention.notifications_all_off` | Alle varsler fra Vibb One er av | All Vibb One notifications are off |

- `{name}` is the channel's quoted name (§5.1).
- The sentences come from the code:
  - The messages sentence is from `SmsNotifier.wakeIfAlerts`: importance NONE means no alert and no wake, and Home's
    count is the engine's own (`SmsHome.unreadFrom`).
  - Missed calls: the contact badge is `MissedCallsRepo`'s.
  - Calls: Telecom plays the ringtone (`createCallChannels`' comment), and the call screen is started directly
    (`CallNotifications`).
  - The anchor: the plain notice shows only while the panel isn't posted (design 100 §2.4), for any reason. That
    covers bit 128 off, no item, or hidden, so the sentence says "ikke vises", never "skjult" (L1).
  - The ring: `LocateCommands` plays it apart from the notice. Stop is on the Find Phone page (`/devices/{id}/find`,
    "Finn telefonen", L3).
- The lead names no one. A pause can turn a channel off as well as the kid can, and the page doesn't blame.
- The minor channels (`install_mode`, `app_install`, `vpn_filter`, `setup`) are named in the value and have no sentence.

## 6. What the parent can do about it

- **Text, and one row in Vibb One's Settings. No remote re-enable** (Q4).
  - An app can't raise a channel the user blocked. Only deleting it and creating it again under a new id brings it back
    (design 99 §2.6). For 13 channels that would mean versioned ids everywhere they are posted.
  - It would also override the kid in a way design 99 D8 refused for Snarveier.
  - And on the phones where it matters most (role holders) a block can't happen from the sheet at all.
- **The row "Varsler fra Vibb One" / "Vibb One notifications"** (M1), on Vibb One's Settings page, among the rows that
  open Android's pages ("Modify system settings", "Background connection").
  - **Built on their pattern** (`ui/settings/launcher/SettingsFragmentLauncher.kt`):
    - the page list: `NotificationChannelsAccess.pageIntents`, `ACTION_APP_NOTIFICATION_SETTINGS` with
      `EXTRA_APP_PACKAGE` through `systemPageIntent`. That resolves only to a system Settings activity, never our
      `SettingsSinkActivity`, so in the kiosk nothing resolves;
    - the tap: `openFirstPage(pages, launcher)` with `ownPageOpening()`;
    - the state: `pageAccess` / `systemPageAccess`, with CAN_OPEN, PAUSE_FIRST, WAIT and NO_PAGE;
    - the return: a sync if the read changed, as `writeSettingsLauncher` does;
    - the re-read on resume, after a tap, at the pause's start and after each apply (`updateWriteSettingsRow`'s callers).
  - **When it shows:** only while a channel other than `shortcuts` is blocked, or while `all_off`, by the same reader
    as §2. Snarveier has its own "Vis i varsellisten igjen".
  - **Its summary**, in the phone's language and the phone's names, never Snarveier's:
    - CAN_OPEN: "Av: Meldinger · Trykk for å slå på" / "Off: Messages · Tap to turn on";
    - PAUSE_FIRST (the kiosk, no window): "Av: Meldinger · Sett alle begrensninger på pause først" / "Off: Messages ·
      Pause all restrictions first";
    - WAIT and NO_PAGE: "Av: Meldinger · " plus the pattern's own words for those states.
  - **A tap outside a window opens nothing**, since no page resolves. The summary already says what to do. The gate
    checks this (§10 step 2).
- **Nothing on the server acts on the host.** Nothing here touches the server's machine (the PWA has no host actions),
  and the server sends no command.
- **No new restriction**, so the rule "every restriction is liftable by the pause" has nothing to lift.
- **The kid's own way back**: AOSP's sheet lists no channel at NONE (§1), so the kid has none. The gate records it
  (§10 step 1). The PWA text uses the path that surely works.

## 7. The contract

### 7.1 `server/testdata/notification_channels.json`

It is byte-identical in `launcher/app/src/test/resources/`:

- `capability`: `notification_channels_v1`;
- `channels`: the table in display order, `{key, id, nb, en, feature, orange, attention}`. Snarveier's `id` is
  `launcher:shortcuts` (with `.N` read as it).

  | key | id | nb | en | feature | orange | attention |
  |---|---|---|---|---|---|---|
  | `messages` | `launcher:messages` | Meldinger | Messages | `sms` | true | true |
  | `missed_calls` | `launcher:missed_calls` | Tapte anrop | Missed calls | `calls` | true | true |
  | `calls` | `launcher:calls` | Samtaler | Calls | `calls` | true | false |
  | `messages_silent` | `launcher:messages_silent` | Meldinger (stille) | Messages (silent) | `sms` | false | false |
  | `messages_reactions` | `launcher:messages_reactions` | Reaksjoner | Reactions | `sms` | false | false |
  | `calls_silent` | `launcher:calls_silent` | Samtaler (samtaleskjermen er åpen) | Calls (call screen open) | `calls` | false | false |
  | `command_listener` | `launcher:command_listener` | Vibb One | Vibb One | null | false | false |
  | `ring` | `launcher:ring` | Finn enheten – ringing | Find My Device ring | null | false | false |
  | `window` | `launcher:window` | Åpnet med foreldrekoden | Opened with the parent code | null | false | false |
  | `install_mode` | `launcher:install_mode` | Play-installasjonsmodus | Play install mode | null | false | false |
  | `app_install` | `launcher:app_install` | Installering og oppdatering av apper | App installs and updates | null | false | false |
  | `vpn_filter` | `launcher:vpn_filter` | Innholdsfilter | Content filter | null | false | false |
  | `setup` | `launcher:setup` | Oppsett | Setup | null | false | false |
  | `shortcuts` | `launcher:shortcuts` | Snarveier | Shortcuts | null | false | false |

- `report`: `{"all_off": false, "blocked": ["messages", "command_listener", "shortcuts"]}`;
- `report_none`: `{"all_off": false, "blocked": []}`;
- `report_all_off`: `{"all_off": true, "blocked": ["messages"]}`;
- `read`: the launcher's pure reading, `{channels: [{id, importance, group?}], blocked_groups, app_enabled,
  shortcuts_id} -> report`. It covers NONE, a blocked group, an unknown id, an old Snarveier id, a missing channel, app
  off, and Android 16's reserved `android.app.*` channels at NONE (skipped, L6);
- `clean`: the server's rows `{in, out}`:
  - not an object;
  - `all_off` not a bool;
  - `blocked` missing, and `blocked` not an array (both give `[]`);
  - unknown keys, duplicates, the wrong order, non-strings, an extra key;
- `pages`: `{sms_mode, calls_mode, language, shortcuts_bit, report} -> {value, orange, attention}`. The value is in the
  PWA's en; `attention` is the title or `null`. The rows:
  - SMS managed and Meldinger off;
  - SMS unmanaged and Meldinger off (not named: "All on", no attention);
  - calls managed and only Samtaler off (orange, no attention);
  - only `command_listener`, once with bit 128 on and once with it off (the same sentence, L1);
  - only `shortcuts` ("All on");
  - all off, with SMS managed (attention) and with both unmanaged (no attention);
  - four names (all four shown) and five names (the "more" form, L12);
  - `language: "nb"` (the names in nb inside the en page, L4).

### 7.2 Repo plumbing (in the same commit as the API change)

- The root `CLAUDE.md`: the contract paragraph, and the file in both CI path lists of "CI and releases" (L9).
- `launcher.yml`'s and `server-ci.yml`'s path filters: `server/testdata/notification_channels.json` and the launcher's
  copy. Also each workflow's comment header that lists the shared files (`launcher.yml:16`, `server-ci.yml:70`, L9).
- `policy_json_keys_snapshot` doesn't change: there is no policy key.

## 8. Tests (lean)

Launcher (`NotificationChannelsTest`):

- every `read` row through `channelsReport`; `report` encoded exactly from the DTO;
- the table's keys and ids equal the contract's `channels`, and their nb/en names equal the launcher's own strings
  (`values/strings.xml`, `values-nb/strings.xml`, read as files);
- the row's summary: each `SystemPageAccess` state with a blocked list, Snarveier left out, and no row with only
  Snarveier blocked (a pure `notificationsRowText`);
- a source pin: every channel id created under `src/main` (each `NotificationChannel(` call's id constant) is in the
  table, so a new channel without a key fails here. The pin also checks that the three block-state actions are
  registered with `SyncRunner.request(..., "notification_channels")`, and that the receiver reads no extra;
- the two contract copies are identical.

Server:

- `notification_channels::tests`: every `clean` row; `CHANNELS` equal to the contract's `channels`;
- `tests::notification_channels`: posts `report` with the capability and checks what is stored, then runs every `pages`
  row through the About page and the summary's Attention. It also checks an older launcher (no capability: no row),
  the row's place after `NoNotificationAccess`, and the Devices list's badge for one row;
- `intake_limits`: `report`, `report_none` and `report_all_off` kept key for key; the contract copies compared.

No new pure rule beyond these. No PWA JS.

## 9. Android 14-17

- **The APIs** are `getNotificationChannels`, `getNotificationChannelGroups`, `NotificationChannelGroup.isBlocked`,
  `areNotificationsEnabled`, the three block-state actions and `ACTION_APP_NOTIFICATION_SETTINGS` with
  `EXTRA_APP_PACKAGE`. All are API 28 or older, so there is no `SDK_INT` gate and lint's NewApi has nothing to catch.
- **14 (34), 15 (35), 16 (36), read from AOSP:**
  - the channel broadcast is sent to the owner when a user's change crosses NONE;
  - the long-press sheet (`ChannelEditorDialog`, since Android 11) lists the app's channels with switches, but not those
    at NONE or locked;
  - the default-dialer lock (`NON_BLOCKABLE_DEFAULT_ROLES`) greys them.
  - 16 is measured: the lock (§6an), the sheet without the role (S16), the gear in a pause (§6ar).
  - 16 also adds the reserved `android.app.*` classification channels, which the report skips (§2).
  - 34 is measured in this gate (§10).
  - 35 has no change here that we know of; it is not run (the gate's rule: the lowest and the highest it touches).
- **17 (37):** not measured; the emulator aborts on this VM (§6ar). The same public APIs. Owed on a 17 image or device:
  the lock on a role holder, and the broadcast on a block.
- **The Jelly Star (Unihertz Android 16):** owed device check. With Vibb as the dialer, the long-press on a Meldinger
  notice should say "can't be modified". If Unihertz's SystemUI lets it through, this design is what shows it.

## 10. The emulator gate (`compat34` and `jelly36`; `docs/testing/emulator.md` §6ax)

Setup:

- The branch's debug launcher is Device Owner, enrolled, kiosk LOCKED, with the server of the branch.
- In the PWA, set Samtaler and Meldinger to "Ikke styrt" / unmanaged. Then `cmd role get-role-holders
  android.app.role.DIALER` shows the system dialer, and `dumpsys notification` shows `mImportanceLockedDefaultApp=false`
  on the `launcher:` channels.
- Check `dumpsys package` for POST_NOTIFICATIONS: expect no `POLICY_FIXED` flag (M2).
- Restore both AVDs clean afterwards.

Steps 1, 2 and 3 run on both images. Screenshots go on `jelly36` (the user's look) only.

1. **The kid's block.** Long-press the anchor's notice › Turn off › "Vibb One" › Apply.
   - `dumpsys notification`: `importance=0`.
   - logcat: the receiver's line and a `SyncRunner` run with reason `notification_channels` within 2 s.
   - The stored row is a full report, not a heartbeat.
   - The PWA's About › Lyd › Varsler reads "Av: «Vibb One»" (plain), its ⓘ the `command_listener` sentence and the fix.
   - No Attention row.
   - Record whether the sheet, opened from another Vibb notice, lists "Vibb One" (AOSP predicts not, L7).
   - **The app-wide switch (M2):** in the same sheet, turn off "All Vibb One notifications". Record whether it takes
     (`areNotificationsEnabled`, the permission's state) and whether `ACTION_APP_BLOCK_STATE_CHANGED` arrives (the
     receiver's line).
     - If it takes: the PWA reads "Alle av" in orange and raises no Attention row (nothing managed).
     - Undo it in step 2's pause.
2. **The row, in the kiosk and in the pause (M1).**
   - In the kiosk, Vibb One's Settings (parent code) shows "Varsler fra Vibb One · Av: «Vibb One» · Sett alle
     begrensninger på pause først". A tap opens nothing: no sink screen, no Settings, the page stays, lock task LOCKED.
   - Turn on "Sett alle begrensninger på pause" on the same page. The row reads "· Trykk for å slå på". Tap it, and
     Android's page for Vibb One opens. Turn the channel on (and the app-wide switch, if step 1 took it). Back.
   - A sync follows, and the PWA reads "Alle på". The row is gone.
   - Turn the pause off.
3. **A stale block and the role.**
   - **Precondition (L8):** Meldinger managed once, and one SMS received (`adb emu sms send`), so `launcher:messages`
     exists. Then set SMS back to unmanaged.
   - In a pause, turn off "Meldinger" in Android's page. End the pause.
   - In the PWA, set Meldinger to "Styrt". The role is taken.
   - `dumpsys notification`: is Meldinger still `importance=0`, and locked? (AOSP predicts that it is, L7.)
   - **If it is:** About reads "Av: «Meldinger»" in orange with the messages sentence. The summary's Attention row says
     "Varsler slått av: Meldinger", and the Devices list's badge counts it.
     - In a pause, check that Android's page can still turn it on while locked (AOSP predicts that it can). That is the
       `.fix` path, and the line clears.
   - **If the role reset it:** record that. The attention row then only covers another SystemUI or Android 17.

Revision 1's network-off fallback step is dropped (L11): the unit tests cover the hash, and the time goes to M1 and M2.

## 11. Risks

- **The item may rarely fire.** On a phone where Vibb runs calls or SMS, nothing can be blocked from the sheet (§1). The
  report is cheap (one read, no new trigger beyond a broadcast), and it is the only way a parent learns if an OEM or a
  later Android drops the lock (Q1).
- **A stale, locked block** (§10 step 3). AOSP predicts that it stays and can still be raised in Settings, since the
  lock greys only "off" (L7). If the gate shows otherwise, the fix path needs the role released first: "set Samtaler and
  Meldinger to Ikke styrt, fix it in the pause, then set them back". That is a wording change only.
- **The broadcast's delivery** on a user's block is unmeasured until the gate. The report-time read makes it a question
  of minutes, not of correctness.

## 12. Build steps and sizes

1. The contract, the root `CLAUDE.md` paragraph and CI lists, both workflows' path filters and comment headers.
2. Server: the migration, `notification_channels.rs`, the intake, the About row, `Kind::NotificationsOff`, i18n nb + en,
   the tests. **S.**
3. Launcher:
   - `notify/NotificationChannels.kt`: the table, `channelsReport`, the reader, and `notificationsRowText`;
   - the report field and the capability;
   - the receiver in `initRest`;
   - `showAgain`'s sync;
   - the Settings row on the page-row pattern, with strings nb + en;
   - the tests.

   This goes in the same commit as step 2's intake (the API crosses both). **S.**
4. Docs:
   - `docs/notes/launcher/app-permissions.md` (the row) and `push-and-play.md` (the trigger);
   - `docs/notes/server/pwa.md` (the About row, Attention);
   - `docs/testing/emulator.md` §6ax.

**Sizes: launcher S, server S.**

## 13. Design QA: how each finding was handled

| Finding | Handled |
|---|---|
| M1 the pause row | §6: on the page-row pattern (`systemPageIntent`, `openFirstPage`/`ownPageOpening`, `pageAccess`/`systemPageAccess` with all four states, the re-reads). Shown while something but Snarveier is off or `all_off`. PAUSE_FIRST's summary "Av: X · Sett alle begrensninger på pause først". "Only shows inside the pause" and the own "no such page" text are gone. §10 step 2 checks that a tap in the kiosk opens nothing |
| M2 `all_off` | §1 and §2 corrected: POST_NOTIFICATIONS goes back to DEFAULT when the role is handed back (`AppEnforcer.kt:1020-1025`) and is fixed again only by a window's relock. §10's setup checks the flag. Step 1 records whether the app-wide switch takes and whether `ACTION_APP_BLOCK_STATE_CHANGED` arrives. `.lead_all_off` is generic |
| L1 the anchor's sentence | "når Snarveier ikke vises" (bit 128 off, no item, hidden); a `pages` row with bit 128 off (§5.4, §7.1) |
| L2 the fix text | `.fix` names the pause switch and the row on the same page, and "de som er nevnt over". The row's summary leaves out Snarveier (§5.4, §6) |
| L3 the ring | `.effect.ring` names Find Phone, where Stop is (§5.4) |
| L4 the names' language | The phone's language from `launcher_ui.language` when it is nb or en, else the PWA's with the mismatch accepted. Names quoted. The anchor's sentence leads with "Kanalen «Vibb One» (tid igjen)" (§5.1, §5.4) |
| L5 the extra | The receiver reads no extra, with a comment why; pinned in the source test (§3, §8) |
| L6 reserved channels | Android 16's `android.app.*` skipped; a `read` row (§2, §7.1, §9) |
| L7 AOSP's predictions | A stale NONE stays and is still undoable in Settings; the sheet hides NONE and locked channels. Both said as predictions and measured in the gate; §11 softened (§1, §10, §11) |
| L8 step 4's precondition | One SMS received while SMS is managed, so `launcher:messages` exists (§10 step 3) |
| L9 plumbing | The root CLAUDE.md's CI lists and the workflows' comment headers; `report_none` in the keep loop; a missing or non-array `blocked` gives `[]`; `NOT_EVENTS`'s size raised with any addition (§4, §7) |
| L10 Attention's order | After `NoNotificationAccess`, before the calls rows (§5.3) |
| L11 the gate | The network-off step dropped. Steps 1, 2 and 3 (revision 1's 1, the pause and 4) run on `compat34` and `jelly36` (§10) |
| L12 four names | Up to four in full; three plus "og N til" from five (§5.2, a `pages` row) |
| Q2 (QA) | Samtaler stays orange on About but raises no row (§5.3); Q2 amended |

## Q - choices with a visible cost

- **Q1. Build it though the role lock makes it rare?** On every phone where Vibb runs calls or SMS, the kid can't block
  a Vibb One channel today (§1). **Recommended: yes, as designed.** It costs S + S. It covers the unmanaged phones, a
  failed role take, a stale block, another SystemUI and Android 17, and it turns "can't happen" into something the page
  would show. The alternative is to shelve it until a real phone shows a block.
- **Q2. An Attention row** (orange, Action) for Meldinger, Tapte anrop or "all off", only while that feature is managed.
  **Recommended: yes**, amended after the design QA: Samtaler is orange on About but raises no row, since the phone
  still rings and the call screen opens. A parent's message that makes no sound is worth a row. The alternative is the
  About row alone, with no badge in the Devices list.
- **Q3. About names every blocked channel that counts**, the minor ones included (Play install mode, App installs,
  Content filter, Setup), by the phone's own names. Snarveier is never named here. **Recommended: yes**, so the parent
  can tell the kid exactly what to turn on. The alternative is to name only the six with a sentence.
- **Q4. How the parent turns one back on.** **Recommended:** the ⓘ's text plus the row in Vibb One's Settings, shown
  only while something is off. The alternative is a "Slå på igjen" button in the PWA: a command that deletes the channel
  and creates it under a new id, as Snarveier's "Vis igjen" does. That is M, needs versioned ids for 13 channels, and
  overrides the kid's choice from afar.
- **Q5. A security-log line when a channel goes off.** **Recommended: no.** It is a notification setting, not something
  that opens the phone. The About row and Attention say it.
- **Q6. Channels the kid made "Silent"** (still shown, no sound). **Recommended: not reported.** That is the same kind
  of choice as the ringer mode, and reporting it would turn a normal setting into a warning.

## Built (2026-11-16)

As designed, with Q1-Q6 as recommended, and three changes from the gate (§6ax, Android 14 and 16):

- **The receiver is `RECEIVER_EXPORTED`** (§3 said not exported). The long-press sheet's change reaches Android from
  SystemUI, so the block-state broadcast goes out with SystemUI's uid, and Android drops it at enqueue for a
  not-exported receiver ("Exported Denial"); only a change on Settings' own page arrived. The three actions are protected
  broadcasts and the receiver only asks for a sync, so exporting it opens nothing. Snarveier's own receiver
  (`ShortcutsRuntime.init`, design 99) had the same drop for its refresh on a block; since code QA L1 the watch
  refreshes the panel.
- **A stale block can't be raised while the role is held** (L7's second prediction failed): Android's page greys the
  locked channel's switch, and the app switch while POST_NOTIFICATIONS is policy-fixed. §11's fallback is in: the
  `.fix` text ends "If a switch is grey, first set Calls and SMS to “Not managed” under Calls and SMS, and set them back
  afterwards" (nb likewise), checked on both images.
- **The app-wide switch takes on an unmanaged phone and kills the process** (the permission revoke); no
  `ACTION_APP_BLOCK_STATE_CHANGED` was seen for it, and the process start's sync carries `all_off`.

- **§1 and §11 overstated the role lock**: the lock comes with the dialer role only, which Vibb takes while calls are
  managed. SMS managed with calls "Ikke styrt" takes only the SMS role (§6ax step 3: Google's dialer kept the dialer role,
  `mImportanceLockedDefaultApp=false`), so on such a phone the kid can block Meldinger from the sheet - the case where
  the Attention row matters most. The rest of §1 holds for a phone whose calls Vibb manages.

Code QA fixes (L1-L5): the block-state watch also refreshes Snarveier's panel (its own receiver stays not exported and
no longer lists the channel action); the Settings row keeps its last read as the "before" of a tap (no read on the main
thread) and cancels an older read in flight; while Vibb holds the dialer role the row's "Trykk for å slå på" also says to
set Samtaler and SMS to «Ikke styrt» under Anrop og SMS first, as the PWA's ⓘ does.

Also: the PWA quotes the names in the page's quotes («» on nb pages, “” on en) in About's value and the ⓘ, and leaves
them unquoted in the Attention title (§5.3's example); the phone's row quotes them in the phone's language. With
`all_off` the ⓘ is `.lead_all_off`, then the named channels' sentences, then the fix. Owed: Android 17 and the Jelly
Star (§9).


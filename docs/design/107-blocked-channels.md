# 107 - Blocked notification channels, named in the PWA

Round `blocked-channels`, 2026-11-16. Launcher and server. **Status: design, revision 1.** PLAN: "Blocked notification
channels named in the PWA" (approved 2026-11-15, design 99 D11, size S). The status report names every Vibb One
notification channel that is turned off, and the phone's page in the PWA says so. The Q list at the end holds the choices
with a cost the parent or the kid can see.

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
- **Where a block is still possible:**
  1. A phone with calls and SMS both unmanaged. Vibb hands the dialer role back, and the kid can block the anchor's
     notice, Snarveier, the ring, the window's notice, and the minor channels. Meldinger and the call channels are
     unused there.
  2. A phone where the device-owner take of the role failed (`CallState`'s dialer error).
  3. A block made while the role was off that survives when the role is taken later. Not measured; gate step 4 checks it.
  4. Another SystemUI (the Jelly Star's Unihertz build is not checked) or a later Android (17 not checked).
  5. Inside a pause (lock task off, Settings visible, §6ar), where the gear opens Vibb One's notification page.
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
    fixed string) is `shortcuts`. Older `.N` ids and unknown ids are skipped.
  - **A channel that doesn't exist** (Meldinger before the SMS engine ever started) is not blocked.
- **`all_off`**: `NotificationManagerCompat.areNotificationsEnabled()` is false, which is the fallback for app-level off.
  POST_NOTIFICATIONS is granted with `POLICY_FIXED`, so the sheet's "All Vibb One notifications" doesn't take (S16).
  Only a pause (Settings) can turn it off, and the window relock grants it again (`regrantNotifications`). The report
  then names `blocked` as read too: the channels keep their own importance.
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
- **The fallback is the read at every report.** If a broadcast is missed (a dead process, an OEM that doesn't send it,
  a change made by a revoke in Settings during a pause), the next sync carries the change: the backstop (30 min), an SSE
  nudge, or a process start. That is the same freshness as every other report field.
- **Two of our own triggers.** The pause row's return (§6) and Snarveier's "Vis i varsellisten igjen"
  (`ShortcutsRuntime.showAgain`) also request a sync. An app-side delete and re-create sends no broadcast (M6), so these
  two would otherwise wait.
- No polling, no new alarm, no wake lock.

## 4. The server: intake, storage, cleaning

- **Migration** (the next free number; 0151 if nothing lands first: A5 + B2 adds none):
  `ALTER TABLE device_status ADD COLUMN notification_channels_json TEXT;`. It is retained with its status row, so it
  needs no retention change.
- **`src/notification_channels.rs`** (new):
  - `CAPABILITY`.
  - `CHANNELS`: the table of §7.1 (key, nb, en, `feature`: `sms` / `calls` / none, `attention`), the contract's
    `channels`.
  - `clean(&Value) -> Option<Value>`:
    - not an object -> `None`, stored `NULL`;
    - `all_off`: a bool, else false;
    - `blocked`: only strings that are table keys, each once, re-ordered to the table's order, so at most the table's
      length;
    - every other key dropped.
  - `report(latest) -> Option<Report>`: only from a launcher with the capability *and* a stored object. With the
    capability but no object, the read failed or the object was dropped, so there is no row. Showing "All on" without
    a reading would be a false all-clear.
- **Intake** (`handlers/device_api.rs`, next to `shortcuts_json`): `report.notification_channels` through `clean`, then
  bound into the insert. The heartbeat's expansion keeps it through the stored base (`device_report_base`), like every
  full-report field.
- **`intake_limits`**: `notification_channels.json`'s `report` and `report_all_off` are kept key for key by `clean`, in
  the same loop as `shortcuts`.
- **No security event.** The security log records what opens or weakens the phone. A notification turned off in the
  shade is neither (Q5). So `handlers/admin.rs`'s `every_written_event_has_a_label` scan has nothing new to label.
  Builder's note: the module's snake_case keys (`missed_calls`, `messages_silent`, `command_listener`, ...) are literals
  the scan would take for event ids if they sat within 15 lines before or 12 after an event writer, or in the 70 lines
  after a `let (... event` tuple. Keep them in `notification_channels.rs`, which has no writer, and never build an
  event from them. If one does end up next to a writer, add it to `NOT_EVENTS` with a comment.

## 5. The PWA

### 5.1 Which channels are shown

- **A channel counts only while its feature is in use**, by the policy now. `messages`, `messages_silent` and
  `messages_reactions` count while `sms_mode == "managed"`. `calls`, `calls_silent` and `missed_calls` count while
  `calls_mode == "managed"`. Every other channel always counts. A blocked Meldinger on a phone whose SMS Vibb doesn't
  handle carries nothing, and naming it would be a false alarm.
- **`shortcuts` is never named here.** Snarveier's own rows (Home Screen's sub-line, About's "Snarveier: Skjult av Emma")
  already say it (design 100 §5). It still shapes the `command_listener` sentence below.
- The names are the phone's own words (the contract's nb/en, equal to the launcher's strings, §8). So the parent can tell
  the kid exactly what to turn on. They are shown in the PWA's language.

### 5.2 Where

- **About This Phone › Lyd / Sound** (`device_pages.rs`, `sound`): a new row after Ikke forstyrr, key `notifications`.
  Like Do Not Disturb, it is about what the phone lets through, and the group's footer already says "at last report".
  - Only with a `report` (§4). An older launcher has no row.
  - **The value:**
    - "Alle på" / "All on" when nothing that counts is blocked;
    - "Alle av" / "All off" when `all_off`;
    - else "Av: Meldinger, Tapte anrop" / "Off: Messages, Missed calls". At most three names, then "Meldinger, Tapte anrop,
      Samtaler og 2 til" / "... and 2 more".
  - **The class:** `row-value-action` (orange) when `all_off`, or when an attention channel that counts is blocked
    (`messages`, `missed_calls`, `calls`). Else plain.
  - **The ⓘ** (only when something is off): the lead, one sentence for each named channel that has an effect (§5.4),
    then the fix (§6).
- **Attention** (§5.3) links to `/devices/{id}/about#sound`.
- Nothing else. Not on the summary's body (Attention puts it there), not on Home Screen, not in the Devices list's
  status line.

### 5.3 Attention

- **One row, `Kind::NotificationsOff`, level Action (orange).** It is raised only for:
  - a blocked `messages`, `missed_calls` or `calls` while that feature counts (§5.1);
  - `all_off` while SMS or calls are managed.
- The others never raise it: the anchor, the ring, the window's notice, the minor channels and Snarveier. Losing them
  costs a parent nothing they must act on, and Snarveier is the kid's choice (design 99 D8).
- **Title** (at most 8 words): "Varsler slått av: Meldinger, Tapte anrop" / "Notifications off: Messages, Missed calls"
  (only the attention channels' names). With `all_off`: "Alle varsler fra Vibb One er av" / "All Vibb One notifications
  are off".
- `read_from_report` is true: an Offline phone's row says "At last report" and still counts.
- **No false alarms, by construction:**
  - only a launcher that claims the capability and sent an object;
  - only importance NONE as read, never a guess, and a missing channel never counts;
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
| `.lead_all_off` | Alle varsler fra Vibb One er slått av på telefonen. Ingen meldinger, tapte anrop eller andre varsler fra Vibb One vises. | All Vibb One notifications are turned off on the phone. No messages, missed calls or other Vibb One notices show. |
| `.effect.messages` | Meldinger: nye meldinger gir ingen lyd og vises ikke i varsellisten eller på låseskjermen. Tallet på Meldinger på Hjem-skjermen vises fortsatt. | Messages: new messages make no sound and don't show in the notification shade or on the lock screen. The count on Messages on Home still shows. |
| `.effect.missed_calls` | Tapte anrop: et tapt anrop vises ikke i varsellisten. Merket på kontakten vises fortsatt. | Missed calls: a missed call doesn't show in the notification shade. The badge on the contact still shows. |
| `.effect.calls` | Samtaler: et innkommende anrop vises ikke i varsellisten. Telefonen ringer fortsatt. | Calls: an incoming call doesn't show in the notification shade. The phone still rings. |
| `.effect.command_listener` | Vibb One: når Snarveier er skjult, vises ikke tiden som er igjen i varsellisten. | Vibb One: while the shortcuts are hidden, the time left doesn't show in the notification shade. |
| `.effect.ring` | Finn enheten – ringing: stoppknappen vises ikke mens telefonen ringer. Den ringer likevel, og du kan stoppe den her. | Find My Device ring: the stop button doesn't show while the phone rings. It still rings, and you can stop it here. |
| `.effect.window` | Åpnet med foreldrekoden: varselet med «Lås igjen» vises ikke mens telefonen er åpen. | Opened with the parent code: the notice with "Lock again" doesn't show while the phone is open. |
| `.fix` | Slik slår du dem på igjen: på telefonen, Innstillinger i Vibb One (foreldrekode) › Sett alle begrensninger på pause › Varsler fra Vibb One. Slå på det som er av, og avslutt så pausen. | To turn them on again, on the phone: Vibb One's Settings (parent code) › Pause all restrictions › Vibb One notifications. Turn on what is off, then end the pause. |
| `device.attention.notifications_off` | Varsler slått av: {names} | Notifications off: {names} |
| `device.attention.notifications_all_off` | Alle varsler fra Vibb One er av | All Vibb One notifications are off |

- The sentences come from the code:
  - The messages sentence is from `SmsNotifier.wakeIfAlerts`: importance NONE means no alert and no wake, and Home's
    count is the engine's own (`SmsHome.unreadFrom`).
  - Missed calls: the contact badge is `MissedCallsRepo`'s.
  - Calls: Telecom plays the ringtone (`createCallChannels`' comment), and the call screen is started directly
    (`CallNotifications`).
  - The ring: `LocateCommands` plays it apart from the notice.
- The lead names no one. A pause can turn a channel off as well as the kid can, and the page doesn't blame.
- The minor channels (`install_mode`, `app_install`, `vpn_filter`, `setup`) are named in the value and have no sentence.

## 6. What the parent can do about it

- **Text, and one on-phone row. No remote re-enable** (Q4).
  - An app can't raise a channel the user blocked. Only deleting it and creating it again under a new id brings it back
    (design 99 §2.6). For 13 channels that would mean versioned ids everywhere they are posted.
  - It would also override the kid in a way design 99 D8 refused for Snarveier.
  - And on the phones where it matters most (role holders) a block can't happen from the sheet at all.
- **The pause screen's row "Varsler fra Vibb One" / "Vibb One notifications"**
  (`ui/settings/launcher/SettingsFragmentLauncher.kt`, beside "Modify system settings"; the rule "every grant needs an
  on-phone path"):
  - **When it shows:** only while a channel other than `shortcuts` is blocked, or `all_off`. It is read at the screen's
    resume, with the same reader as §2.
  - **Its summary:** "Av: Meldinger" / "Off: Messages", in the phone's language and the phone's names.
  - **A tap** opens `Settings.ACTION_APP_NOTIFICATION_SETTINGS` with `EXTRA_APP_PACKAGE`. In a pause, Settings answers
    it at priority 1 (§6ar).
  - **On return**, a sync if the read changed (the `writeSettingsLauncher` pattern). If the page throws
    `ActivityNotFoundException`, the row says "Siden finnes ikke på denne telefonen" / "This phone has no such page",
    as the other rows do.
- **Nothing on the server acts on the host.** Nothing here touches the server's machine (the PWA has no host actions),
  and the server sends no command.
- **No new restriction**, so the rule "every restriction is liftable by the pause" has nothing to lift. The row only
  shows inside the pause.
- **The kid's own way back**, if any, is recorded by the gate (§10 step 1): does the long-press sheet on another Vibb
  notice still list the blocked channel with its switch? If it does, a later revision may say so in `.fix`. The PWA text
  uses the path that surely works.

## 7. The contract

### 7.1 `server/testdata/notification_channels.json`

It is byte-identical in `launcher/app/src/test/resources/`:

- `capability`: `notification_channels_v1`;
- `channels`: the table in display order, `{key, id, nb, en, feature, attention}`. Snarveier's `id` is
  `launcher:shortcuts` (with `.N` read as it).

  | key | id | nb | en | feature | attention |
  |---|---|---|---|---|---|
  | `messages` | `launcher:messages` | Meldinger | Messages | `sms` | true |
  | `missed_calls` | `launcher:missed_calls` | Tapte anrop | Missed calls | `calls` | true |
  | `calls` | `launcher:calls` | Samtaler | Calls | `calls` | true |
  | `messages_silent` | `launcher:messages_silent` | Meldinger (stille) | Messages (silent) | `sms` | false |
  | `messages_reactions` | `launcher:messages_reactions` | Reaksjoner | Reactions | `sms` | false |
  | `calls_silent` | `launcher:calls_silent` | Samtaler (samtaleskjermen er åpen) | Calls (call screen open) | `calls` | false |
  | `command_listener` | `launcher:command_listener` | Vibb One | Vibb One | null | false |
  | `ring` | `launcher:ring` | Finn enheten – ringing | Find My Device ring | null | false |
  | `window` | `launcher:window` | Åpnet med foreldrekoden | Opened with the parent code | null | false |
  | `install_mode` | `launcher:install_mode` | Play-installasjonsmodus | Play install mode | null | false |
  | `app_install` | `launcher:app_install` | Installering og oppdatering av apper | App installs and updates | null | false |
  | `vpn_filter` | `launcher:vpn_filter` | Innholdsfilter | Content filter | null | false |
  | `setup` | `launcher:setup` | Oppsett | Setup | null | false |
  | `shortcuts` | `launcher:shortcuts` | Snarveier | Shortcuts | null | false |

- `report`: `{"all_off": false, "blocked": ["messages", "command_listener", "shortcuts"]}`;
- `report_none`: `{"all_off": false, "blocked": []}`;
- `report_all_off`: `{"all_off": true, "blocked": ["messages"]}`;
- `read`: the launcher's pure reading, `{channels: [{id, importance, group?}], blocked_groups, app_enabled,
  shortcuts_id} -> report`. It covers NONE, a blocked group, an unknown id, an old Snarveier id, a missing channel and
  app off;
- `clean`: the server's rows `{in, out}`: not an object, `all_off` not a bool, unknown keys, duplicates, wrong order,
  non-strings, an extra key;
- `pages`: `{sms_mode, calls_mode, report} -> {value, action, attention}`. The value is in en; `attention` is the
  title or `null`. The rows: SMS managed and Meldinger off, SMS unmanaged and Meldinger off (no row text for it, "All
  on", no attention), only `command_listener`, only `shortcuts` ("All on"), all off, five names (the "more" form).

### 7.2 Repo plumbing (in the same commit as the API change)

- The root `CLAUDE.md`'s contract paragraph.
- `launcher.yml`'s and `server-ci.yml`'s path lists: `server/testdata/notification_channels.json` and the launcher's
  copy.
- `policy_json_keys_snapshot` doesn't change: there is no policy key.

## 8. Tests (lean)

Launcher (`NotificationChannelsTest`):

- every `read` row through `channelsReport`; `report` encoded exactly from the DTO;
- the table's keys and ids equal the contract's `channels`, and their nb/en names equal the launcher's own strings
  (`values/strings.xml`, `values-nb/strings.xml`, read as files);
- a source pin: every channel id created under `src/main` (each `NotificationChannel(` call's id constant) is in the
  table, so a new channel without a key fails here. The pin also checks that the three block-state actions are
  registered with `SyncRunner.request(..., "notification_channels")`;
- the two contract copies are identical.

Server:

- `notification_channels::tests`: every `clean` row; `CHANNELS` equal to the contract's `channels`;
- `tests::notification_channels`: posts `report` with the capability and checks what is stored, then runs every `pages`
  row through the About page and the summary's Attention. It also checks an older launcher (no capability: no row) and
  the Devices list's badge for one row;
- `intake_limits`: `report` and `report_all_off` kept key for key; the contract copies compared.

No new pure rule beyond these. No PWA JS.

## 9. Android 14-17

- **The APIs** are `getNotificationChannels`, `getNotificationChannelGroups`, `NotificationChannelGroup.isBlocked`,
  `areNotificationsEnabled`, the three block-state actions and `ACTION_APP_NOTIFICATION_SETTINGS` with
  `EXTRA_APP_PACKAGE`. All are API 28 or older, so there is no `SDK_INT` gate and lint's NewApi has nothing to catch.
- **14 (34), 15 (35), 16 (36), read from AOSP:**
  - the channel broadcast is sent to the owner when a user's change crosses NONE;
  - the long-press sheet (`ChannelEditorDialog`, since Android 11) lists the app's channels with switches;
  - the default-dialer lock (`NON_BLOCKABLE_DEFAULT_ROLES`) greys them.
  - 16 is measured: the lock (§6an), the sheet without the role (S16), the gear in a pause (§6ar).
  - 34 is measured in this gate (§10).
  - 35 has no change here that we know of; it is not run (the gate's rule: the lowest and the highest it touches).
- **17 (37):** not measured; the emulator aborts on this VM (§6ar). The same public APIs. Owed on a 17 image or device:
  the lock on a role holder, and the broadcast on a block.
- **The Jelly Star (Unihertz Android 16):** owed device check. With Vibb as the dialer, the long-press on a Meldinger
  notice should say "can't be modified". If Unihertz's SystemUI lets it through, this design is what shows it.

## 10. The emulator gate (`jelly36`, then `compat34`; `docs/testing/emulator.md` §6ax)

Setup:

- The branch's debug launcher is Device Owner, enrolled, kiosk LOCKED, with the server of the branch.
- In the PWA, set Samtaler and Meldinger to "Ikke styrt" / unmanaged. Then `cmd role get-role-holders
  android.app.role.DIALER` shows the system dialer, and `dumpsys notification` shows `mImportanceLockedDefaultApp=false`
  on the `launcher:` channels.
- Restore both AVDs clean afterwards.

1. **The kid's block.** Long-press the anchor's notice › Turn off › "Vibb One" › Apply.
   - `dumpsys notification`: `importance=0`.
   - logcat: the receiver's line and a `SyncRunner` run with reason `notification_channels` within 2 s.
   - The stored row is a full report, not a heartbeat.
   - The PWA's About › Lyd › Varsler reads "Av: Vibb One" (plain), its ⓘ the `command_listener` sentence and the fix.
   - No Attention row.
   - Record whether the sheet, opened from another Vibb notice, still lists "Vibb One" with a switch.
2. **Back through the pause.** In the PWA, "Lås opp i 2 t". On the phone, Vibb One's Settings (parent code) › the pause
   screen shows "Varsler fra Vibb One · Av: Vibb One". Tap it, Android's page opens, turn it on, Back.
   - A sync follows, and the PWA reads "Alle på".
   - The row is gone from the pause screen.
   - End the pause.
3. **The fallback** (one check): turn the network off (`svc wifi disable`, `svc data disable`), so the broadcast's
   sync fails, and block "Vibb One" again. Turn the network on: the first report after that carries the block (a
   full report, the hash changed).
4. **A stale block and the role.**
   - In a pause, turn off "Meldinger" in Android's page. End the pause.
   - In the PWA, set Meldinger to "Styrt". The role is taken.
   - `dumpsys notification`: is Meldinger still `importance=0`, and locked?
   - **If it is:** About reads "Av: Meldinger" in orange with the messages sentence. The summary's Attention row says
     "Varsler slått av: Meldinger", and the Devices list's badge counts it. Record whether the pause's Android page can
     still turn it on while locked. If it can't, the `.fix` text needs a revision before the merge (§11).
   - **If the role reset it:** record that. The attention row then only covers another SystemUI or Android 17.
5. **`compat34`:** steps 1 and 4 only.

Screenshots go on `jelly36` (the user's look) only.

## 11. Risks

- **The item may rarely fire.** On a phone where Vibb runs calls or SMS, nothing can be blocked from the sheet (§1). The
  report is cheap (one read, no new trigger beyond a broadcast), and it is the only way a parent learns if an OEM or a
  later Android drops the lock (Q1).
- **A stale, locked block** (§10 step 4) might be impossible to undo in Settings while Vibb holds the role. Then the fix
  path would need the role released first: "set Samtaler and Meldinger to Ikke styrt, fix it in the pause, then set them
  back". That is a wording change only, and the gate decides it.
- **The broadcast's delivery** on a user's block is unmeasured until the gate. The report-time read makes it a question
  of minutes, not of correctness.

## 12. Build steps and sizes

1. The contract, the root `CLAUDE.md` paragraph, both workflows' path lists.
2. Server: the migration, `notification_channels.rs`, the intake, the About row, `Kind::NotificationsOff`, i18n nb + en,
   the tests. **S.**
3. Launcher: `notify/NotificationChannels.kt` (the table, `channelsReport`, the reader), the report field and the
   capability, the receiver in `initRest`, `showAgain`'s sync, the pause row with strings nb + en, the tests. This goes
   in the same commit as step 2's intake (the API crosses both). **S.**
4. Docs:
   - `docs/notes/launcher/app-permissions.md` (the pause row) and `push-and-play.md` (the trigger);
   - `docs/notes/server/pwa.md` (the About row, Attention);
   - `docs/testing/emulator.md` §6ax.

**Sizes: launcher S, server S.**

## Q - choices with a visible cost

- **Q1. Build it though the role lock makes it rare?** On every phone where Vibb runs calls or SMS, the kid can't block
  a Vibb One channel today (§1). **Recommended: yes, as designed.** It costs S + S. It covers the unmanaged phones, a
  failed role take, a stale block, another SystemUI and Android 17, and it turns "can't happen" into something the page
  would show. The alternative is to shelve it until a real phone shows a block.
- **Q2. An Attention row** (orange, Action) for Meldinger, Tapte anrop, Samtaler or "all off", only while that feature is
  managed. **Recommended: yes.** A parent's message that makes no sound is worth a row. The others stay on About only.
  The alternative is the About row alone, with no badge in the Devices list.
- **Q3. About names every blocked channel that counts**, the minor ones included (Play install mode, App installs,
  Content filter, Setup), by the phone's own names. Snarveier is never named here. **Recommended: yes**, so the parent
  can tell the kid exactly what to turn on. The alternative is to name only the six with a sentence.
- **Q4. How the parent turns one back on.** **Recommended:** the ⓘ's text plus the pause screen's row, shown only while
  something is off. The alternative is a "Slå på igjen" button in the PWA: a command that deletes the channel and creates
  it under a new id, as Snarveier's "Vis igjen" does. That is M, needs versioned ids for 13 channels, and overrides the
  kid's choice from afar.
- **Q5. A security-log line when a channel goes off.** **Recommended: no.** It is a notification setting, not something
  that opens the phone. The About row and Attention say it.
- **Q6. Channels the kid made "Silent"** (still shown, no sound). **Recommended: not reported.** That is the same kind
  of choice as the ringer mode, and reporting it would turn a normal setting into a warning.

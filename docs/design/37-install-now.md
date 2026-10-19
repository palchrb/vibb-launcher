# 37 - Install the launcher update now (a parent's grant from the PWA)

Architect, 2026-10-19. Branch `design-37` from `main` (`badbc91d`). A buildable spec: one step (§8).

Sources: the user, 2026-10-19: "hadde egentlig vært fint om det var mulig å force update for foreldrene, inne på
launcherens setting i app tabben i server pwa". Server `models.rs` (`EXPIRING_COMMANDS`, `DeviceCommand::status`,
`CommandStatus`, `command_label`), `handlers/locate.rs` (`queue_command`, `replaced_kinds`, `status_words`, `reword`),
`handlers/window_relock.rs`, `handlers/device_api.rs` (the pop, `end_expiring_at_start`), `kiosk_escapes.rs`
(`wait_text`, `EscapeNote::UpdateWaiting`), `handlers/tracked_apps.rs` (`render_detail_full`, `PhoneRow`),
`templates/tracked_app_detail.html` ("On phones"), `templates/device_advanced.html` ("Updates & notifications",
`#updates` - what the brief calls the "Play and kiosk" card since design 31 step 3); launcher `SelfUpdatePlan.kt`,
`SelfUpdate.kt`, `MdmSyncWorker.kt` (`dispatchPendingCommand`, `checkForTrackedAppUpdates`, `handleLauncherUpdate`,
`commitPendingSelfUpdateIfDue`), `AppDownloadPlan.kt` (`anyNetworkAtMs`), `CommandAnswers.kt`,
`PackageReplacedReceiver.kt`, `UpdateFence.report`; design 11, 13, 24b.

## 0. Goal and non-goals

**Today** Vibb One downloads its own update as soon as the launcher row advertises it, then commits it only at
02:00-05:00 local with the screen off >= 30 s, no live call and no emergency - or, once it has waited 24 h (2 min on
debug), at any such quiet screen-off. A parent who wants a fix on the phone today waits for the night.

**Goal**: an **Install Now** per phone, on the launcher's page under Apps and next to the "Update waiting" note on the
phone's Advanced › Updates & notifications. It grants the phone one install "at the next quiet screen-off": the gate
keeps screen off >= 30 s, no live call, no emergency, and drops only the night window. The phone downloads first if it
has nothing yet, then answers Done (from the new process) or Failed with the reason; the PWA shows the progress in the
row and on the Locate tab's Recent.

**Non-goals**: installing while the screen is on or during a call (never, whatever the parent taps); relaxing any APK
check (size, SHA-256, package, signer, versionCode); other catalog apps (they install as soon as downloaded already);
the music app; a parent notification when it is done (§7.3); a server-pushed tag ("install exactly 1.4.0").

## 1. Decisions

1. **Where** (the user: "inne på launcherens setting i app tabben"): the launcher's page `/apps/tracked/{id}` (only
   when `is_launcher`), in its "On phones" group, one row per set-up phone; and the phone's Advanced ›
   Updates & notifications, a group right under the switches form, where the parent reads "Update waiting" (§2.3).
2. **One tap, no confirm `<details>`.** The PWA confirms only what lowers protection or can't be undone (Unlock for
   2 h, Erase); Install Now lowers nothing - the phone still decides when it is safe, and the worst case is the ~1 min
   update with the screen off (design 11's known gap, QA 11 #8, unchanged). Like "Lock again", a quick action.
3. **Mechanism**: a new `device_commands` kind **`install_update`**, queued by `locate::queue_command` (receipt,
   redelivery, the stream's nudge); a new one replaces a waiting one of its kind (`replaced_kinds`). No payload: the
   grant covers whatever launcher release the phone is offered. No migration: `device_commands.command` has no
   CHECK (`0010_find_my_device.sql`); 0080 stays free.
4. **It expires** (`EXPIRING_COMMANDS`, 1 h to be fetched): "now" means now - a phone that comes back after a day
   has had its night window by then. A server start cancels it like the others. The grant on the phone has its own
   lifetime (5.).
5. **The grant** lives **24 h from receipt**, in DE prefs, survives the process kill, and is cleared by the answer
   (installed, already running, refused, not offered, lapsed), a newer `install_update`, or an enrollment. **A newer
   release during the grant is covered too** (deviation from the brief's "cleared by a newer tag"): the command names
   no tag, every check runs on the new APK the same way, and clearing would only make the parent tap again (§7.2).
6. **Answers**: receipt before acting (as every command); **Done** "installed: X" from the new process after
   `MY_PACKAGE_REPLACED`, or "already running: X" at once; **Failed** for a refusal, no release for this phone, or a
   lapsed grant (with why it never went in). A transient install failure keeps the grant (the existing 1 h backoff).
7. **Waiting words**: `update_fence.waiting_for` gets `granted` ("installs at the next screen-off") and
   `granted_download` (no APK yet). A call or an emergency keeps its own word.
8. **Capability `install_now_v1`.** Without it the row says the update installs at night and Install Now comes with
   it - the first update to a launcher with the capability still goes through the night window: the parent's
   one-time wait. The server refuses to queue for a phone without it.
9. **Debug builds** keep their 2-minute overdue path; the grant doesn't change it.
10. **Security**: admin-only like every tracked-app POST (full auth layer); `device_command_queued` with
    "install_update"; the redirect goes back to the row (`#phone-{id}`, `#install-now`), with and without JS.

## 2. Server

### 2.1 Routes and handler (`handlers/install_now.rs`, full auth, plain forms)

- `POST /apps/tracked/{app_id}/install-now/{device_id}` -> `/apps/tracked/{app_id}?install=<word>#phone-{device_id}`.
  404 when the row isn't the launcher (`is_launcher`) or the phone is unknown.
- `POST /devices/{id}/install-update` -> `/devices/{id}/advanced?install=<word>#install-now`.
- Both call `install_now::queue(state, device_id, admin) -> Result<Queued, Response>`: reads the launcher row, the
  phone and its latest report; decides with the pure `install_now::offer` (§2.2); only `Offer::Button` queues
  (`queue_command` made `pub(crate)`; `replaced_kinds("install_update") = ["install_update"]`), then
  `record_security_event("device_command_queued", admin, "install_update, device {id}")`. Words: `sent`, or without
  queuing `up_to_date`, `too_old` (no capability), `no_release`, `not_set_up`, `in_progress` (a live grant, §2.2). A
  DB error is a 500 "nothing was queued", as on Locate.

### 2.2 The row's state (`src/install_now.rs`, pure, typed; the template words it)

`launcher_tag_version_code(tag) -> Option<i64>`: the launcher's formula (`launcher-vX.Y.Z` = `X*1_000_000 + Y*1_000
+ Z`, `-rc.N` minus 1, the part before `@`), mirrored from `launcherTagVersionCode` with the same test cases.

`offer(release_tag, status: Option<&DeviceStatus>, fence: Option<&UpdateFence>, newest: Option<&DeviceCommand>,
capable, now) -> InstallRow`:

| State | When | Row (`row-value` / `row-sub`) | Button |
|---|---|---|---|
| `NotReported` | no report yet | sub "Hasn't reported yet" | - |
| `UpToDate` | running code >= the tag's code, no `pending_tag` | value "1.4.0" | - |
| `Behind` | the tag's code > running code, or `pending_tag` set (also when the tag doesn't parse: a manual upload) | value "1.3.2", sub "1.4.0 installs tonight" (`pending_tag`) / "1.4.0 not downloaded yet" | **Install Now** |
| `TooOld` | `Behind` without `install_now_v1` | sub "1.4.0 installs tonight. Install Now works from this update on." | - |
| `Asked` | newest `install_update` is `Waiting`/`Sent` | sub "Install Now: " + `status_words` ("Waiting · Expires 14:30 if the phone isn't back.") | - |
| `Granted` | newest is `Received`, no result, not overdue | sub by `waiting_for`: `granted` "Installs at the next screen-off"; `granted_download` "Downloading, then installs at the next screen-off"; `call` "Installs after the call"; `emergency` "Waits: an emergency call was recent"; none yet "The phone got it (14:02)" | - |

An answer under 24 h old adds a line to the row's ⓘ (`reword`, §2.4) - "Install Now: Installed 1.4.0 (14:31)" or
"Install Now failed: Refused: signed with another key" - and a Failed/Expired/Cancelled one gives `Behind` back its
button. `?install=` shows its flash as a `row-note` under that phone's row only (`sent` shows nothing: the row says it).

### 2.3 Templates

- `tracked_app_detail.html`, launcher only: the "On phones" rows become `<div class="row" id="phone-{id}">` with the
  avatar, the name as a link to the phone's `/devices/{id}/advanced#updates` (where the night note is) with the
  `row-sub`, the `row-value`, and the form with `<button class="button-small">Install Now</button>` (a button can't sit
  inside the row's `<a>`; the Music Library's rows carry Check Now the same way). Other apps' rows are unchanged. The
  group footer: "Vibb One installs updates at night with the screen off. Install Now: at the next screen-off."
- `device_advanced.html`: after the `#updates` form (a form can't nest), `<div class="group" id="install-now">` with one
  row from the same partial `partials/install_now_row.html` (label "Vibb One"), shown for `Behind`, `TooOld`, `Asked`,
  `Granted` or a fresh answer; nothing when up to date. `view_advanced` reads the launcher row and the newest
  `install_update` for the phone.

### 2.4 Status words, labels, expiry

- `command_label("install_update") = "Install Now"` (Recent on Locate: "Install Now · 14:02 · Done").
- `EXPIRING_COMMANDS` gets `install_update` (five). The pop and `end_expiring_at_start` bind the array by index
  (`[0]`..`[3]`, `device_api.rs`): build the `IN (...)` from the array's length, or bind `[4]` too.
- `DeviceCommand::status`: `Received` is overdue ("No answer") after `received_overdue_minutes(command)` - 10, and
  25 h for `install_update` (the grant's 24 h + its answer).
- `reword`: `installed: X` -> "Installed X"; `already running: X` -> "Already on X"; `not offered` -> "No update for
  this phone"; `refused: wrong_signer` -> "Refused: signed with another key", `not_ours` -> "Refused: not Vibb One",
  `downgrade` -> "Refused: older than the phone's", `unparseable` -> "Refused: not a readable app", `release` ->
  "Refused: this release can't be installed"; `expired: screen_on|screen_off_short` -> "Not installed within a day:
  the screen was never off for 30 seconds", `expired: call` -> "... a call was on each time", `expired: emergency` ->
  "... an emergency call was recent", `expired: download` -> "... the download didn't finish"; `replaced` -> "Replaced
  by a newer Install Now". Anything else is shown as sent.
- `kiosk_escapes::wait_text`: `granted` -> "installs at the next screen-off (Install Now)", `granted_download` ->
  "downloading for Install Now". `UpdateWaiting`'s detail with `granted`: "Downloaded (tag). Install Now: it goes in at
  the next screen-off (30 seconds, no call)." instead of the night sentence.

## 3. Wire

- **Command**: `pending_command = {"id", "command": "install_update", "requested_at"}` - no new key, so
  `policy_json_keys_snapshot` and `PolicyResponseCompatTest` don't change (the step confirms both pass untouched).
- **Receipt**: `POST /api/devices/command-receipt` before acting, as today. **Result**: `POST
  /api/devices/command-result` with `succeeded` and the messages of §2.4 (`installed: 1.4.0` carries the new
  `VERSION_NAME`). Sent through `CommandAnswers` (outbox when it doesn't get through).
- **Capability**: `install_now_v1` in `STATUS_CAPABILITIES`; server `install_now::CAPABILITY`.
- **Status**: `update_fence.waiting_for` may be `granted` or `granted_download`; `granted_download` comes with
  `pending_tag: null` (today `waiting_for` is null without a pending APK). Shape unchanged; the contract files
  (`time_status_report.json`, `dns_upstream_status.json`) list their own capabilities and have no `update_fence`, so
  they stay. No new contract file: like `paused for 2 hours`, the words are pinned by a test on each side (§5).

## 4. Launcher

### 4.1 Pure (`SelfUpdatePlan.kt`, `SelfUpdatePlanTest`)

```kotlin
const val INSTALL_NOW_CAPABILITY = "install_now_v1"
const val INSTALL_GRANT_MS = 24 * 60 * 60_000L

@Serializable data class InstallGrant(val commandId: Long, val requestedAt: String?, val grantedAtMs: Long,
    val fromVersionCode: Long)

fun grantLive(g: InstallGrant?, nowMs: Long) = g != null && nowMs - g.grantedAtMs in 0 until INSTALL_GRANT_MS
```
- `UpdateWindowInputs` + `val granted: Boolean = false`; `updateWindowDecision`: `val overdue = i.granted ||
  i.pendingForMs >= i.overdueMs` - call, emergency, screen on and the 30 s stay ahead of it.
- `commitCheckDelayMs(now, pendingForMs, overdueMs, granted = false)`: granted -> `UPDATE_SCREEN_OFF_MS`.
- `enum class GrantRow { NO_LIST, NOT_OFFERED, RUNNING, REFUSED, ON_ITS_WAY }` - from `handleLauncherUpdate`: no
  launcher row or one naming another package -> `NOT_OFFERED`; advertised == `refusedTag` -> `REFUSED`;
  `ALREADY_RUNNING` or advertised == `lastInstalledTag` -> `RUNNING`; else `ON_ITS_WAY`; no list -> `NO_LIST`.
- `sealed interface GrantOutcome { Keep, Installed, AlreadyRunning, NotOffered, Refused(reason), Lapsed(why) }` and
  `grantOutcome(grant, nowMs, runningVersionCode, row, lastWait, pendingPresent)`, in this order: running code >
  `fromVersionCode` -> `Installed`; not `grantLive` -> `Lapsed(lastWait?.wire ?: if (pendingPresent) "screen_on"
  else "download")`; `NOT_OFFERED`/`RUNNING`/`REFUSED("release")` by the row; else `Keep`. `GrantOutcome.answer(
  versionName): Pair<Boolean, String>?` gives §2.4's message (`Keep` = null).
- `reportedWait(grantLive, pendingPresent, lastWait): String?`: no grant -> today's (`lastWait?.wire` with a pending
  APK, else null); a grant without an APK -> `granted_download`; with one, `call`/`emergency` as they are, else
  `granted`.
- `AppDownloadPlan.anyNetworkAtMs(isLauncher, firstSeenMs, grantedAtMs: Long? = null)`: the launcher's Wi-Fi grace
  ends at `min(firstSeenMs + LAUNCHER_WIFI_GRACE_MS, grantedAtMs)` - Install Now downloads on mobile data at once,
  never roaming (§7.1).

### 4.2 The grant's store (`server/SelfUpdateGrant.kt`)

DE prefs (`createDeviceProtectedStorageContext()`), file `self_update_grant`, one key `grant_v1` (the `InstallGrant`
JSON), every write `commit()` (a commit is followed by the kill). Ids and times only, nothing secret. `load` (an
unreadable value = none, logged), `store`, `clear`, `live(context)`, `asCommand(grant) = PendingCommand(id,
"install_update", requestedAt)` for `CommandAnswers.result`. `clear` runs next to `CommandAnswers.clear` (Provisioning,
the Settings re-enroll).

### 4.3 Dispatch and the sync (`MdmSyncWorker.kt`)

- `dispatchPendingCommand`, after the receipt: `"install_update" ->` store `InstallGrant(id, requestedAt, now,
  VERSION_CODE)`; a stored grant of another command is answered `replaced` (success) first. No answer here, no
  follow-up sync (this sync goes on to the list and the commit).
- `checkForTrackedAppUpdates`: after `handleLauncherUpdate` (which now also returns its `GrantRow`), and in the
  no-list branch with `NO_LIST`: `settleGrant(context, api, row)` - `grantOutcome`, then for an answer
  `CommandAnswers.result` + `SelfUpdateGrant.clear`. The grant's `grantedAtMs` goes into the launcher's
  `WantedDownload`/`DownloadRecord` for `anyNetworkAtMs`; a download already waiting for Wi-Fi is re-gated at
  `AppDownloads.request`.
- `commitPendingSelfUpdateIfDue`: `SelfUpdate.windowInputs` passes `granted = SelfUpdateGrant.live(context)`
  (also in `beforeCommit`'s second check). A `check.deterministic` refusal under a live grant answers
  `refused: <check.name.lowercase()>` there and clears it. The pending/committed paths are unchanged otherwise.
- `UpdateFence.report`: `waitingFor = reportedWait(SelfUpdateGrant.live(app), pending != null, SelfUpdate.lastWait)`.

### 4.4 Alarms and receivers

- `SelfUpdate.armIfPending` passes `granted` to `commitCheckDelayMs`, so a screen-off with a grant and an APK arms
  `SelfUpdateReceiver` for 30 s; `onPendingStored` (the download landed) does the same. Storing a grant while the
  screen is already off arms it too (`SelfUpdate.onGrant`). Screen-on still cancels.
- `PackageReplacedReceiver`: with a stored grant, `SyncRunner.request(context, "install_now_done")` - its settle
  answers `installed: X`. Over tsnet that sync reaches the server after the next unlock (tsnet starts from Home's
  `onResume`); the answer waits in the outbox until then.
- `AppInstallReceiver`, a failed self-update with a stored grant: request a sync, so a refusal (`recordRefused`)
  answers within seconds rather than at the next backstop.

## 5. Tests (lean)

- **Launcher** (`SelfUpdatePlanTest`): granted at 14:00 - off 31 s Commit; off 10 s `SCREEN_OFF_SHORT` 20 s; screen on
  `SCREEN_ON`; call `CALL`; emergency `EMERGENCY`; not granted `OUTSIDE_WINDOW` (exists). `commitCheckDelayMs` granted
  = 30 s. `grantLive` at 0, 24 h - 1, 24 h, negative. `grantOutcome`: installed beats lapsed, lapsed by `lastWait`/
  download, each `GrantRow`. `reportedWait`'s table. `AppDownloadPlanTest`: the grant ends the grace, roaming still
  waits. `SyncScheduleTest`: `install_now_v1` announced. The message strings in one test that lists them.
- **Server** (`src/tests/install_now.rs`): each route queues one `install_update` (event, nudge, redirect with
  `#phone-{id}` / `#install-now`); a second POST while waiting replaces (`replaced`); `up_to_date`, `too_old`,
  `no_release`, `not_set_up`, `in_progress` queue nothing; another app's row and an unknown phone 404; unauthenticated
  POSTs write nothing; the pop expires it after 1 h; `Received` is not "No answer" at 11 min, is at 25 h.
  `install_now::tests`: `offer`'s table, `launcher_tag_version_code`'s cases (the Kotlin ones). `reword` and
  `wait_text` for every word. `every_form_on_the_app_pages_returns_to_its_page` and the Advanced page's form test
  include the new forms.
- **Pins**: `policy_json_keys_snapshot`, `PolicyResponseCompatTest`, the contract files - unchanged (§3).

## 6. Emulator checks (`docs/testing/emulator.md` new §6o)

Debug launcher of this step, enrolled; §6d's setup for a test self-update (manual row with `me.vibb.launcher.debug`,
"This is the launcher app" moved to it, an APK built with a higher `-PversionCode`). The debug 2-minute overdue path
still applies, so each check acts inside 2 minutes of the download. Emulator clock outside 02:00-05:00. Watch `adb
logcat -s SelfUpdate MdmSyncWorker UpdateFence AppInstall CommandAnswers`.

1. **Too old first**: with the previous launcher (no capability) the launcher page's row says "installs tonight.
   Install Now works from this update on", no button; a crafted POST answers `too_old`, nothing queued.
2. **Grant before the download**: upload the APK, at once tap Install Now on the launcher page: the row "Install
   Now: Waiting", then "Downloading, then installs at the next screen-off"; the page stays at `#phone-{id}`.
3. **Screen on holds it**: the download lands with the screen on - logcat "waits: screen_on", row "Installs at the
   next screen-off". `adb shell input keyevent 26` (screen off): commit ~30 s later, well under the 2 minutes;
   `MY_PACKAGE_REPLACED`; after the unlock the row is "1.x · Installed 1.x", Recent on Locate "Install Now · Done".
4. **A call holds it**: grant again with a newer APK, `adb emu gsm call 5551234` and answer, key 26: logcat "waits:
   call", row "Installs after the call"; hang up (`adb emu gsm cancel 5551234`) and screen off: it goes in.
5. **Refused**: an APK signed with another debug key: Failed "Refused: signed with another key", the button is back,
   nothing fenced.
6. **Already running / expiry**: Install Now on a phone that already runs the release ("Done · Already on 1.x"); with
   the emulator offline (`adb emu network speed 0` or airplane mode) past an hour: "Expired · Never sent".

## 7. Open questions for the user

1. **Mobile data**: with "Download apps on Wi-Fi only" on, may Install Now download the launcher (~tens of MB) on
   mobile data at once (proposed; never roaming), or should it wait for Wi-Fi like today (up to 3 days)?
2. **A newer release during the grant**: proposed, the grant covers it (§1.5). Or clear it with "a newer release
   came: tap again", as in your brief?
3. **Tell the parent when it is done?** Proposed no (the row and Recent say it). Design 35's inbox/push could carry
   "Vibb One 1.4.0 installed on Anna" - one event kind more.

## 8. Steps

**One step, server and launcher in one branch and one commit for the wire** (the command kind, its answers, the
capability and the waiting words are read on both sides; CLAUDE.md "a change to the API goes into both directories
in the same commit"). A split would only make a server that queues for a phone that can't take it testable, which
the capability already prevents. Then `docs/testing/emulator.md` §6o, both CLAUDE.md files (server: the Find My Device
bullet's command list, `EXPIRING_COMMANDS`, the Apps tab bullet; launcher: the Self-update bullet), PLAN.md.

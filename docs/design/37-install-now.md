# 37 - Install Now: push the launcher's newest release to one phone at once

Architect, 2026-10-19. Branch `design-37` from `main` (`badbc91d`). A buildable spec: one step (§8). Revised the same
day after the user's decisions and the design QA (`docs/design/qa-37-design.md`, branch `qa-design-37`); §9 lists
each finding and what was done with it.

Sources: the user, 2026-10-19: "hadde egentlig vært fint om det var mulig å force update for foreldrene, inne på
launcherens setting i app tabben i server pwa", and after the first proposal: "Push release skal skje uansett
tilkoblingsstatus, det er et valg forelderen tar. jeg ønsker heller at dette er en one time 'push the button and the
update happens', ikke noe åpent tilattelsesvindu etc. Det er en overrride knapp inne på launcherens app fane settings
for bare vibb launcher som lar deg trykke knappen og sette igang ummidelbar install av akkruat den nyeste versjonen man
har funnet ved å trykke check now. Trenger ikke noe varsel, dette ser jeg jo live fra pwa tenker jeg." Code: server
`models.rs`, `handlers/locate.rs` (`queue_command`, `replaced_kinds`, `cancel_command`, `status_words`, `reword`),
`handlers/device_api.rs` (the pop, `payload`), `kiosk_escapes.rs`, `app_downloads.rs`, `handlers/tracked_apps.rs`,
`templates/tracked_app_detail.html`; launcher `SelfUpdatePlan.kt`, `SelfUpdate.kt`, `MdmSyncWorker.kt`,
`AppDownloadPlan.kt`/`AppDownloads.kt` (`downloadGate`, `RETRY_DELAYS_MS`, `MAX_HASH_FAILURES`), `CommandAnswers.kt`,
`AppInstallReceiver.kt`, `PackageReplacedReceiver.kt`, `calls/OngoingCalls.kt`; designs 11, 13, 24b.

## 0. Goal and non-goals

**Today** Vibb One downloads its own update when the launcher row advertises it (Wi-Fi only for 3 days when the
parent's switch is on) and commits it at 02:00-05:00 with the screen off >= 30 s, no call and no emergency - or, after
24 h (2 min on debug), at any such quiet screen-off.

**Goal**: an **Install Now** button per phone on the launcher's page under Apps. One tap queues a one-time command:
when the phone gets it, it downloads the launcher row's newest release (on any network), verifies it exactly as today,
and installs it **at once** - screen on or off, day or night. The only wait is a live call or an emergency flow; it
installs the moment that ends. The parent watches it in the row ("Downloading 40 of 80 MB", "Installing", "Done ·
1.4.0" or "Failed: ...") and on the Locate tab's Recent.

**Non-goals**: relaxing any APK check (size, SHA-256, package, signer, newer versionCode); an open permission window;
a notification or push when done (the user: "Trenger ikke noe varsel"); a button on the phone's own pages (the user:
"bare vibb launcher", on its app page); other catalog apps (they install when downloaded already); changing the night
window, the 24 h overdue path or debug's 2 minutes for the ordinary self-update.

## 1. Decisions

1. **Where**: only `/apps/tracked/{id}` with `is_launcher`, one button per set-up phone in the "On phones" group
   (`id="phones"`, each row `id="phone-{id}"`). No Advanced route.
2. **One tap, no confirm `<details>`** - the user's "push the button and the update happens"; the ⓘ next to it says
   what the kid sees (§2.3; QA M5).
3. **Mechanism**: a `device_commands` kind **`install_update`** through `locate::queue_command` (receipt, redelivery,
   the stream's nudge; a waiting one is replaced, `replaced_kinds`). `device_commands.payload` (0068) holds the release
   tag the row showed; the pop sends it as `pending_command.release_tag` (left out for other commands, like `allow`).
   No migration (`command` has no CHECK); 0080 stays free.
4. **No expiry** (not in `EXPIRING_COMMANDS`). The user: "Push release skal skje uansett tilkoblingsstatus, det er et
   valg forelderen tar" - the parent decided, and the phone acts when it next checks in, however long that takes, as a
   Lock does. The cost - a tap fetched a day later installs at once in the daytime - is the same daytime install the
   user chose for a phone that is online; the row says "Waiting for the phone" the whole time and has **Cancel** while
   it is unfetched (also on Recent), so a parent who changes their mind can stop it. An enrollment cancels it as any
   undelivered command. The three index-bound sites (`device_api.rs` pop and `end_expiring_at_start`,
   `locate::cancel_command`'s `NOT IN (?, ?, ?, ?)`) stay untouched.
5. **Act at once**: the gate for this command is only "no live call, no emergency" (`installNowDecision`, §4.1); no
   screen-off, no night window, no 1 h retry backoff (the tap is the retry). The fence goes up when its switch is on,
   as for every self-update.
6. **The newest found**: the phone installs the release the row advertises when it acts - newer than the command's
   tag if Check Now found one meanwhile; the answer names the version installed.
7. **Any network**: the download ignores "Download apps on Wi-Fi only" and roaming for this command (the user: "det er
   et valg forelderen tar"). It still needs a network and the space.
8. **Answers**: receipt before acting; **Done** "installed: X" from the new process after `MY_PACKAGE_REPLACED`, or
   "already running: X"; **Failed** promptly with the reason (§3). "No answer" only for a Received row with no answer
   after 25 h.
9. **Capability `install_now_v1`**. A phone without it gets no button ("1.4.0 is waiting. Install Now works from this
   update on") - the first update to such a launcher still goes in at night: the parent's one-time wait.
10. **No wall clocks across the command** (QA M1): the phone keeps the command id, `requested_at` (echoed), the tag
    and the running versionCode at receipt; "Done" is "running versionCode > the stored one". Server times are compared
    only with server times.
11. **Security**: admin-only like every tracked-app POST; `device_command_queued` "install_update <tag>, device {id}";
    Cancel logs `device_command_cancelled`. The redirect returns to the row or the group (§2.1), with and without JS.

## 2. Server

### 2.1 Routes (`handlers/install_now.rs`, full auth, plain forms)

- `POST /apps/tracked/{app_id}/install-now/{device_id}` -> `?install=<word>#phone-{device_id}`; 404 unless the row
  is the launcher. Reads the launcher row, the phone, its latest report and its newest `install_update`, decides with
  the pure `install_now::offer` (§2.2) and queues only when it gives the button (`queue_command` made `pub(crate)`,
  payload = `latest_release_tag`). Words: `sent`, or nothing queued: `up_to_date`, `too_old`, `no_release`,
  `in_progress`, `not_set_up`, `unknown_phone`.
- `POST /apps/tracked/{app_id}/install-now/{device_id}/cancel/{command_id}` -> `?install=cancelled|too_late#phone-…`:
  the `cancel_command` UPDATE (moved to `locate::cancel_waiting(db, device, command)`, shared), `install_update` only.
- **Fragments that exist** (QA M4): `#phone-{id}` when that phone has a row (set up), else `#phones`; the group is
  always rendered with its id - also with no phones - and a word for a phone without a row shows as a `row-note` at
  the group's top. A DB error on any read is a 500, never a made-up row; a failed queue is a 500 "nothing was queued".

### 2.2 The row (`src/install_now.rs`, pure, typed; the template words it)

`launcher_tag_version_code(tag)` mirrors the launcher's `launcherTagVersionCode` (part before `@`, `launcher-vX.Y.Z`
= `X*1_000_000 + Y*1_000 + Z`, `-rc.N` minus 1), with the Kotlin test's cases.

| State | When | `row-value` / `row-sub` | Action |
|---|---|---|---|
| `NotReported` | no report | sub "Hasn't reported yet" | - |
| `NoRelease` | no `latest_release_tag`, or the row is off (`enabled = 0`) | sub "No release to install" | - |
| `UpToDate` | tag code <= running `app_version_code`, no `pending_tag` | value "1.4.0" | - |
| `Behind` | tag code > running, or `pending_tag` set; a manual upload's tag never parses: `Behind` unless the report's version equals `version_of(tag)` (it may answer "Already on X" - accepted) | value "1.3.2", sub "1.4.0 is waiting" / "1.4.0 not downloaded yet" | **Install Now** |
| `TooOld` | `Behind` without `install_now_v1` | sub "1.4.0 is waiting. Install Now works from this update on." | - |
| `Asked` | newest `install_update` `Waiting`/`Sent` | sub "Install Now: waiting for the phone" (`Sent`: "… sent, not confirmed yet") | **Cancel** while unfetched |
| `Working` | `Received`, no result, under 25 h | §2.4's words | - |

An answer under 24 h old is a `details.info` in the row: "Install Now · 14:31 · Done", the reworded line inside
(`Installed 1.4.0`); `Failed`, `Cancelled` and "No answer" (Received > 25 h) give `Behind` its button back.

### 2.3 Template (`tracked_app_detail.html`, launcher only)

The rows become `<div class="row" id="phone-{id}">`: avatar, the name as the link to the phone's page (unchanged
`phone_apps_href`) with the `row-sub`, the `row-value`, the answer's ⓘ, and the form with `<button
class="button-small">Install Now</button>` (or Cancel) - a button can't sit inside the old `<a class="row">`; the Music
Library's rows carry Check Now the same way. Other apps' rows are unchanged. The group footer, with an ⓘ:
"Install Now installs the newest release found by Check Now right away, also on mobile data. It waits only for a call."
ⓘ: "The phone restarts Vibb One; for about a minute the child sees the phone's own home screen (Update fence, under
the phone's Advanced, hides it once it is checked on this phone model)." While a row is `Asked`/`Working`,
`static/install-now.js` (defer) polls `GET /apps/tracked/{id}/install-now.json` every 5 s and replaces those rows'
sub-lines (`textContent`); without JS a reload shows the same.

### 2.4 Words, labels, the Received limit

- `command_label("install_update") = "Install Now"`; Recent shows it with `status_words` as every command.
- `Working` from the latest report, only when `reported_at` is after `received_at` (both server time), else "The phone
  got it (14:02)". `update_fence.waiting_for`: `install_now_download` + the launcher's `app_downloads` entry ->
  "Downloading 40 of 80 MB" / "Waiting for a network" / "Waiting: not enough space"; `install_now_call` -> "Waits for
  the call to end"; `install_now_emergency` -> "Waits: an emergency call was recent"; `install_now_ready` ->
  "Installing".
- `DeviceCommand::status`: `Received` is overdue after `received_overdue_minutes(command)` - 10, and 25 h for
  `install_update`.
- `reword`: `installed: X` -> "Installed X"; `already running: X` -> "Already on X"; `not offered` -> "No update for
  this phone"; `refused: wrong_signer` -> "Refused: signed with another key", `not_ours` -> "Refused: not Vibb One",
  `downgrade` -> "Refused: older than the phone's version", `unparseable` -> "Refused: not a readable app", `release`
  -> "Refused: this release can't be installed"; `download failed` -> "The download kept failing"; `download corrupt`
  -> "The download didn't match the release"; `no space` -> "Not enough space on the phone"; `install failed: N` ->
  "Android refused the install"; `superseded` -> "Ended: a newer Install Now took over". Anything else as sent.
- `kiosk_escapes::wait_text` (Advanced's "Update waiting" note, "Last check: …"): `install_now_call` -> "Install Now
  waits for the call to end", `install_now_emergency` -> "Install Now waits: an emergency call was recent",
  `install_now_ready` -> "Install Now is installing it"; `install_now_download` comes with `pending_tag: null`, so it
  needs none.

## 3. Wire

- **Command**: `pending_command = {"id", "command": "install_update", "requested_at", "release_tag": "launcher-v1.4.0@…"}`.
  No top-level policy key: `policy_json_keys_snapshot` unchanged. `PolicyResponseCompatTest` gets a case: a
  `pending_command` with `release_tag` decodes (launcher `PendingCommand.releaseTag: String? = null`), one without it
  too. Server `PendingCommand.release_tag: Option<String>`, `skip_serializing_if = "Option::is_none"`.
- **Receipt** and **result** through `CommandAnswers` (`command-receipt`, `command-result`), messages as in §2.4.
- **Capability** `install_now_v1` (`STATUS_CAPABILITIES`; server `install_now::CAPABILITY`).
- **Status**: `update_fence.waiting_for` may be `install_now_download` (with `pending_tag: null`), `install_now_call`,
  `install_now_emergency`, `install_now_ready` (all < `MAX_WORD`). The launcher's `app_downloads` entry reports its
  real gate (§4.2). Contract files unchanged (neither has `update_fence`); the words are pinned by a test on each side.

## 4. Launcher

### 4.1 Pure (`SelfUpdatePlan.kt`, `SelfUpdatePlanTest`)

```kotlin
const val INSTALL_NOW_CAPABILITY = "install_now_v1"
@Serializable data class InstallNowRecord(val commandId: Long, val requestedAt: String?, val tag: String?,
    val fromVersionCode: Long, val failure: String? = null)
```
- `installNowDecision(liveCall, emergency): UpdateWindowDecision` - `Wait(CALL, INSTALL_NOW_CALL_RECHECK_MS = 2 min)`,
  `Wait(EMERGENCY, EMERGENCY_RECHECK_MS)`, else `Commit`. `updateWindowDecision` stays for the ordinary path.
- `enum class LauncherRow { NO_LIST, NOT_OFFERED, RUNNING, REFUSED, ON_ITS_WAY }` from `handleLauncherUpdate` (no row
  or another package; advertised == `refusedTag`; `ALREADY_RUNNING` or == `lastInstalledTag`; else).
- `installNowOutcome(record, runningVersionCode, row)`: running > `fromVersionCode` -> `Installed`; `failure` set ->
  `Failed(failure)`; `NOT_OFFERED` -> `Failed("not offered")`; `REFUSED` -> `Failed("refused: release")`; `RUNNING`
  -> `AlreadyRunning`; else `Keep`. `.answer(versionName): Pair<Boolean, String>?`.
- `installNowWait(recordPresent, pendingPresent, decision)`: `install_now_download` without an APK, `install_now_call`/
  `_emergency` from a wait, else `install_now_ready`; no record -> today's word.
- `launcherUpdateStep(…)` with a record: `failedBackoffMs = 0` (refusals still hold).
- `downloadGate(…, parentOverride: Boolean = false)`: with it, `WAIT_WIFI` and `WAIT_ROAMING` never apply; network and
  space still do.

### 4.2 Glue

- **`server/InstallNow.kt`**: DE prefs (`createDeviceProtectedStorageContext()`), file `install_now`, key `record_v1`,
  every write `commit()`. DE because `SelfUpdate.init` reads it at process start, which a direct-boot-aware call
  component can trigger before the first unlock after an install-and-reboot; it holds ids, a tag and a versionCode
  only. `load`/`store`/`fail(reason)` (sets `failure`, then `SyncRunner.request("install_now")`)/`clear`; cleared next
  to `CommandAnswers.clear` (Provisioning, the Settings re-enroll).
- **Dispatch** (`dispatchPendingCommand`, after the receipt): `"install_update"` -> a stored record of another command
  is answered `superseded` (failed); store `InstallNowRecord(id, requestedAt, releaseTag, VERSION_CODE)`; clear
  `SelfUpdate.lastWait`; `AppDownloads.request` (re-gates a download waiting for Wi-Fi); `requestFollowUpSync` (a
  command queued behind it, QA L7). This sync goes on to the list and the commit.
- **Settle** (`checkForTrackedAppUpdates`, after `handleLauncherUpdate`; the no-list branch with `NO_LIST`):
  `installNowOutcome` -> `CommandAnswers.result` + `InstallNow.clear`. Every failure elsewhere goes through
  `InstallNow.fail`, so one place answers.
- **Download** (`AppDownloads.kt`): one helper `gate(record, caps, now, …)` replaces the five `downloadGate(…,
  anyNetworkAtMs(…))` sites (gate, report, running check, the `waiting` log, the network callback) and passes
  `parentOverride = record.isLauncher && InstallNow.load(app) != null` - read live, never stored in a record, so
  reconcile can't drop it and a cleared record ends it (QA M3). With a record: the run giving up on the launcher after
  `RETRY_DELAYS_MS` (3 retries, 30/60/120 s - about 3.5 min) -> `fail("download failed")`; `MAX_HASH_FAILURES` (2) ->
  `fail("download corrupt")`; `WAIT_SPACE` -> `fail("no space")`; a refusal (`CatalogRefusal`) -> `fail("refused: …")`.
  `WAIT_NETWORK` waits (no answer can leave anyway). `storeSelfUpdate` -> with a record, `SyncRunner.request`.
- **Commit** (`commitPendingSelfUpdateIfDue`): with a record, `installNowDecision(windowInputs.liveCall, .emergency)`
  instead of `updateWindowDecision`, in both checks (`beforeCommit` too), skipping `pendingCommitAllowed`'s backoff (not
  its refusal); a deterministic `pendingApkCheck` -> `fail("refused: <check>")`; `InstallStart.FAILED` -> `fail("install
  failed: start")`. A wait arms `SelfUpdate.armAlarm` with the decision's recheck **whatever the screen** (today only
  with the screen off), and `SelfUpdate.onScreenOn` keeps the alarm while a record exists.
- **The call's end** (QA L8): `SelfUpdate.init` adds an `OngoingCalls` listener; when it fires with no live call and a
  record waiting on `CALL`, `SyncRunner.request("install_now_call_ended")`. A VoIP or Telecom-only call (calls
  unmanaged) isn't in `OngoingCalls`: the 2-minute alarm re-checks it. Emergency: the 10-minute recheck, as today.
- **Results after the commit**: `AppInstallReceiver`, a failed self-update with a record -> `fail("install failed:
  <status>")` (transient or not: the tap was one attempt; the ordinary path keeps its own backoff and record).
  `PackageReplacedReceiver` -> with a record, `SyncRunner.request("install_now_done")` (coalesced with the service's
  start sync); the settle answers `installed: <VERSION_NAME>`. Over tsnet it reaches the server after the next unlock;
  the answer waits in the outbox.
- **Report**: `UpdateFence.report`'s `waitingFor = installNowWait(...)`.

## 5. Tests (lean)

- **Launcher**: `installNowDecision` (call, emergency, quiet; screen on doesn't matter); `installNowOutcome` per row,
  installed beats a failure; `installNowWait`'s table; `launcherUpdateStep` with a record ignores the backoff, not the
  refusal; `downloadGate` with `parentOverride` (metered + Wi-Fi only -> GO, roaming -> GO, no network/space -> wait);
  a source pin that all `downloadGate` calls in `AppDownloads.kt` go through `gate(`; `PolicyResponseCompatTest`'s
  `release_tag`; `SyncScheduleTest` announces `install_now_v1`; one test listing the answer strings.
- **Server** (`src/tests/install_now.rs`): POST queues one `install_update` with the tag as payload (event, nudge,
  redirect); the pop sends `release_tag`; every refused word redirects to an element the page renders (`assert_lands`:
  `#phone-{id}` / `#phones`); a second POST while waiting replaces, while Received answers `in_progress`; Cancel
  before and after the fetch; another app's row 404; unauthenticated POSTs write nothing; a server start does **not**
  cancel it; `Received` is not "No answer" at 11 min, is at 25 h; the page's reads 500 on a DB error.
  `install_now::tests`: `offer`'s table (incl. a disabled row, a manual upload, "No answer"), `launcher_tag_version_code`,
  `Working`'s words from `waiting_for` + `app_downloads`, a report older than the receipt. `reword`, `wait_text` per
  word. `every_form_on_the_app_pages_returns_to_its_page` covers Install Now and Cancel.

## 6. Emulator checks (`docs/testing/emulator.md` new §6o)

Debug launcher of this step, enrolled; §6d's test self-update setup (manual row `me.vibb.launcher.debug`, "This is the
launcher app" moved to it, an APK with a higher `-PversionCode`). Clock outside 02:00-05:00; act inside the debug
build's 2 minutes. Watch `adb logcat -s SelfUpdate MdmSyncWorker AppDownloads UpdateFence AppInstall CommandAnswers`.

1. **Too old**: the previous launcher: "Install Now works from this update on", no button.
2. **Screen on, on mobile data**: the phone's Apps › "Download apps on Wi-Fi only" on, `adb shell svc wifi disable`;
   upload the APK, tap Install Now with the screen on and an app open: the row goes "Waiting for the phone" ->
   "Downloading …" -> "Installing"; the commit comes without a screen-off; Home (and the lock if LOCKED) back after
   `MY_PACKAGE_REPLACED`; the row "Done · Installed 1.x", Recent "Install Now · Done"; the page never jumps.
3. **A call holds it**: newer APK, `adb emu gsm call 5551234`, answer, tap Install Now: "Waits for the call to end";
   `adb emu gsm cancel 5551234`: the install starts within seconds (logcat "install_now_call_ended").
4. **Newest found**: tap Install Now, then before the phone acts upload a higher build and Check Now: the higher one
   is installed and named.
5. **Refused / failed**: an APK of another debug key, higher versionCode -> "Failed · Android refused the install" (or
   "signed with another key" from the pre-check), the button back, no retry; airplane mode during the download for
   > 4 min, then back -> "The download kept failing".
6. **Reboot mid-download** (`adb reboot` while "Downloading"): after the unlock the download resumes and installs.
7. **Offline phone**: airplane mode, tap: "Waiting for the phone" with Cancel; Cancel -> "Cancelled"; tap again, then
   airplane mode off: it installs.

## 7. Open question for the user

1. **"Install on all phones"** - a group action above the rows that queues Install Now for every phone that shows the
   button? Proposed: not now (one tap per phone; usually one or two phones).

The fence: with `update_fence` off (default until A3 passes on the Jelly Star) a daytime install shows Android's own
home screen for about a minute; the ⓘ says so. It is not recommended on until that check has passed.

## 8. Steps

**One step, server and launcher in one branch, the wire in one commit** (the command, its tag, the answers, the
capability and the waiting words are read on both sides). Then §6o, both CLAUDE.md files (server: Find My Device's
command list, the Received limit, the Apps tab; launcher: Self-update and Catalog downloads), PLAN.md.

## 9. Revision (after the user's decisions and `qa-37-design.md`)

The user's model replaces the 24 h grant: a one-time command that acts at once on any network, installs the newest
release found, no notification, only on the launcher's page. The old open questions are answered (mobile data yes,
newer release yes, notification no).

- **M1** (clock back lapses the grant): gone with the grant; nothing compares wall clocks across the command (§1.10).
- **M2** (honest row words): the row reads the real state - `install_now_download` worded from `app_downloads`,
  `install_now_call`/`_emergency`/`_ready`; no backoff state (the command skips it); failures answer at once (§2.4, §4.2).
- **M3** (the grace in a record): the override is read live at one `gate(…)` helper behind all five sites (§4.2).
- **M4** (fragments): `id="phones"` always rendered, `#phone-{id}` only for rows that exist; tests post every word.
- **M5** (daytime, fence off): the ⓘ sentence (§2.3); the fence is not recommended until A3 passes (§7).
- **L1**: not expiring, so the array and its three index-bound sites stay; named in §1.4.
- **L2**: `NoRelease` for a disabled row or no release; "No answer" gives the button back (§2.2).
- **L3**: no screen-off wording left; "older than the phone's version"; "1.4.0 is waiting"; `granted*` dropped.
- **L4**: `lastWait` is cleared at the receipt and the command's words come from its own state; no `expired:` answers.
- **L5**: `superseded` is answered failed, "Ended: a newer Install Now took over".
- **L6**: no Advanced group; the answer is a `details.info` in the row.
- **L7**: `requestFollowUpSync` at the dispatch.
- **L8**: an `OngoingCalls` listener starts the install when the call ends; a 2-minute alarm for calls it can't see.
- **L9**: "already running" and the offline hour go to the server tests; reboot and failed-install checks added (§6).
- **L10**: tests for the gate helper, the words, the redirects, the no-expiry restart and the 500s (§5); the clock
  test is moot.
- **L11**: a manual upload's tag stated in §2.2.
- **Nit 1**: DE kept (the user's choice) with the reason in §4.2. **Nit 2**: the extra sync request kept; it is
  coalesced and says why it is there.

## Implementation status

Implementer, 2026-10-19, branch `install-now` from `main` (`c6232081`): the one step of §8, server and launcher in one
commit (the command, its tag, the answers, the capability and the waiting words), then the docs. The design QA's
revision check (`qa-37-design.md`, "Revision check (ae03013d)") is done in the step: R-M1 (the ⓘ also says calls aren't
filtered for that minute), R-L1, R-L2, R-L3, R-L4, R-N1, R-N2 - below. Built and tested in CI (the VM was short of
memory for a local build): server `cargo test` (all new tests in `src/tests/install_now.rs`, `install_now::tests`,
`locate::tests`, `kiosk_escapes::tests`), `cargo fmt --check`, clippy, `node --test jstest/`; launcher `./gradlew build
-PwarningsAsErrors=true` (`InstallNowPlanTest`, `PolicyResponseCompatTest`).

**In** (server, `server/CLAUDE.md` "Install Now"): the `install_update` command through `locate::queue_command_with`
with the row's `tag@asset_id` as payload, replaced while waiting; `pending_command.release_tag` for it only; no expiry;
"Install Now" in Recent; Received is "No answer" after 25 h for it only; the pure row (`src/install_now.rs`:
`launcher_tag_version_code`, `release`, `offer`, `refusal`, `working_words`, `answer_fresh`, `flash`); the two POST
routes and the poll JSON (`handlers::install_now`); the launcher page's rows `id="phone-{id}"` in an always-rendered
`id="phones"`, Install Now / Cancel, the answer's ⓘ for a day, the footer and its ⓘ; `static/install-now.js`; the
reworded answers; the three `wait_text` words. (launcher, `launcher/CLAUDE.md` "Install Now"): the record in DE prefs,
the dispatch and the settle, the commit gate, the download override through one `gate(` helper, every failure through
`InstallNow.fail`, "Done" from the new process, the call-end listener and the 2-minute alarm that a screen-off keeps,
`install_now_v1`.

**Deviations, with the reason**:
- **One more refusal word, `not_reported`** (a set-up phone that never reported): §2.1's list had no word for §2.2's
  `NotReported`; it lands on the phone's row like the others ("This phone hasn't reported yet.").
- **`in_progress` also for a fetched, unconfirmed command** (Sent), not only Received: the phone has it, so a second
  tap must not queue a second one; a waiting one is still replaced. A Sent command whose deliveries ran out ("Not
  confirmed") gives the button back, as an answer does.
- **The poll (R-L3)**: 5 s while the page is visible, stopped while hidden (restarted on `visibilitychange`), given up
  after 360 polls (30 min; a reload starts it again) - so never 25 h of polling. A changed state word reloads the page
  as `auto-reload.js` does (its keep-place record), never during a save or with a `<details>` open; the reload logic is
  copied into `install-now.js` (16 lines) rather than shared, because `auto-reload.js` installs itself on load.
- **A call that never ends (R-L4)**: the record keeps when the commit began waiting on a call (elapsed realtime - local
  to the phone, never compared with a server time; a reboot starts it again); after 6 h the command fails, "call never
  ended" -> "Not installed: a call stayed on".
- **The commit's other outcomes (R-L2)**: `CORRUPT` (the file changed after its own hash check) answers "download
  corrupt"; `MISSING` and `SAME_VERSION` ask for a sync at once (the download restarts, or the settle answers "Already
  on X").
- **The screen-off (R-L1)**: with a record `armIfPending` arms `installNowRecheckMs(lastWait)` (2 min on a call, 10 min
  after an emergency, else 30 s), never the night window's delay; the screen-on keeps the alarm.
- **R-N1/R-N2**: §4.2's DE reason is stated as R-N1 says (nothing reads it before the first unlock today; DE is the
  user's choice); the `CatalogRefusal` bullet is dropped - the launcher's refusals come from the commit's
  `pendingApkCheck` ("refused: <check>").
- **A superseded record with its own failure** is answered with that failure, not "superseded" (it failed first).
- **The redirect table**: the launcher page's forms are posted from the rendered page in `src/tests/install_now.rs`
  (`settings_pages::check_forms` plus `assert_lands` for every word) rather than in
  `tracked_apps::every_form_on_the_app_pages_returns_to_its_page`, which posts every form of a non-launcher page
  (adding the launcher there would post its "This is the launcher app" switch off mid-table).
- **`render_detail_full` fails closed now**: a failed read of the app or its phones was an empty list or a 404; it is a
  500 on every app's page (§2.1's rule, applied to the whole page rather than only the new reads).
- **Working words** also say "Waiting: the phone is roaming" for an `app_downloads` entry in that state (never reached
  while the override runs; a report from before the receipt could carry it).

**Owed to device checks** (`docs/testing/emulator.md` §6o for the emulator part): the whole §6 list on the emulator
(too old, screen on and mobile data, the call holding it, the newest found, refused / failed, a reboot mid-download,
the offline phone and Cancel); on the Jelly Star: a daytime install with the update fence off (the stock home screen
and the unfiltered calls for about a minute - the ⓘ's two sentences) and on (A3 not passed yet: not recommended),
the download on real mobile data with "Wi-Fi only" on, the 2-minute alarm catching the end of a VoIP or Telecom-only
call, the alarm with the screen on in Doze, "Done" arriving over tsnet only after the next unlock (the answer in the
outbox); in the PWA on the iPhone: the poll stopping in the background and reloading at the same place.

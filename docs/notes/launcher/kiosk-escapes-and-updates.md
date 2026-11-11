# Launcher notes: kiosk escapes, update fence, Install Now, catalog downloads

Moved from launcher/CLAUDE.md on 2026-11-01.

## Kiosk escapes: update fence, self-update window, notification rule (handy step 11, design `docs/design/11-kiosk-escapes.md`, at the monorepo root)

- **The escape**: committing our own update freezes and force-stops us - lock task ends, Home resolves to the stock launcher (drawer, Recents, wallpaper picker, shade) for the ~1 min of install + dexopt, and nothing brought Home back afterwards. Same for `adb install -r` (dev only, **unfenced**) and briefly for a crash. Other apps' notifications (Play services' "set a screen lock", Safety Center, setup/wellbeing nags) were the second escape: their taps open screens outside the kiosk (BlockedAppActivity in kiosk, the real screen with the kiosk off).
- **Pure parts**: `server/UpdateFencePlan.kt` (`fencePlan`, the versioned record + codec, `runFence`/`runRelease` with a `FencePlatform`, the release rule `fenceRelease`, `suspendTarget`; `UpdateFenceTest`), `server/SelfUpdatePlan.kt` (`launcherUpdateStep`, `pendingApkCheck`, `updateWindowDecision`, `msUntilUpdateWindow`/`commitCheckDelayMs`, `bringHomeAfterUpdate`; `SelfUpdatePlanTest`), `badges/NotificationRule.kt` (`nagVerdict`, `RepostBudget`, `NagCounts`, `nagLogDue`; `NotificationRuleTest`), `lockTaskWhileLocked(fenced)` + `StatusBarLatch` (`LockTaskHelpersTest`/`PinLockTaskTest`).
- **Self-update** (`MdmSyncWorker.handleLauncherUpdate`/`commitPendingSelfUpdateIfDue`, glue `server/SelfUpdate.kt`): the launcher row's APK is downloaded once into `noBackupFilesDir/self_update` with its SHA-256 and kept as `TrackedAppState.pending` (a newer tag replaces it, a withdrawn/installed one drops it, a row naming another package - a debug build vs the release APK - is never downloaded). A row whose release isn't ahead of the running build is recorded installed at once (`ALREADY_RUNNING`): a QR/adb install has no record of itself, and would otherwise download its own version and show "Update waiting". "Ahead" is the pure `launcherBuildAhead` of the tag's versionCode and name (`launcherTagVersionCode`/`launcherTagVersionName`, the release workflow's formula, `scripts/release-version.sh` - the name is the tag minus `launcher-v`; an unparseable tag goes on as before; the cases are the shared `launcher_tag_codes.json`, see "Release builds") against `BuildConfig.VERSION_CODE`/`VERSION_NAME`: a higher code - since `launcher-v0.36.0-rc.4` every build has its own code, so the code decides -, or (legacy of the old rule, for builds tagged before it, where every RC of one version shared a code and an RC of X.Y.Z+1 had X.Y.Z's) the same code under another, newer name, so rc.2 went over rc.1, never rc.1 over rc.2; two names that can't be ordered count as ahead, an unknown name never does (the server's `install_now::build_ahead` is the same rule). `performMdmSync`'s **last** step commits it only when the gate passes: local 02:00-05:00, screen off >= 30 s, no live call (`OngoingCalls.hasLiveCall`, which counts CONNECTING, or Telecom/audio mode), no emergency (the lock's emergency flow, the callback window, an emergency call in the last 10 min, ECBM) - or, once it has waited 24 h (**2 min on debug builds**, for emulator A4), at any such quiet screen-off. Before fencing: size + SHA-256, `getPackageArchiveInfo` = our package, the archive's versionCode and versionName ahead of ours (`launcherBuildAhead`; an older code = failed, the same code with the same, an older or an unreadable name = installed, `SAME_VERSION`); the signer and package checks are unchanged. A screen-off arms `SelfUpdateReceiver` (request code 12, while-idle exact alarm) for when the gate could pass; screen-on cancels it. Product defaults proposed in the design, pending the user's confirmation. Known gap (QA 11 #8): the freeze also kills our screening, redirection and in-call services, so a call arriving in the ~1 min window rings unscreened in the system in-call UI.
- **Update fence** (`server/UpdateFence.kt`, server switch `update_fence`, missing = **off**; default off until A3 passes on the Jelly Star): right before `session.commit()` (`AppInstaller.installSilently(beforeCommit)`, under the sync mutex) it suspends every other HOME-capable package (`fencePlan`: never ours/.debug, `android`, SystemUI, the phone process, Telecom, Settings/FallbackHome (negative priority), persistent packages, dialers, emergency dialer, IMEs, kiosk/PIN-lock helpers, lock-task packages, Play core, **controllable** packages - enforcement owns those - or already suspended ones; the system's recents provider is fenced although pinned or controllable, 16d guard 2) and disables the status bar **through `LockTaskChrome` only** (`UpdateFence.fenced` is an input of `lockTaskWhileLocked`; `fenceChanged` invalidates the latch, so a release always re-enables the bar). Write-ahead: the record (own CE prefs `update_fence`, `v` = 1, `planned`/`suspended` string sets, session id, our `lastUpdateTime`, boot count, start times) is committed **before** suspending, then only what the platform really suspended. Everything takes `AppEnforcer`'s lock - never on the main thread. **The fence is a contract between builds: never remove or rename the release code, the prefs file or its keys (`UpdateFenceTest` pins them); an unknown `v` still releases `planned` + `suspended`.**
- **Release rule** (`fenceRelease`, one idempotent check): run at process start (= boot), every install result (`AppInstallReceiver`, any app; the fence's own session's result is the input), every `apply()` (`UpdateFence.duringApply` - apply never unsuspends a fence-held package meanwhile), Home/lock resumed, screen-off, `MY_PACKAGE_REPLACED` and the backstop alarm (`UpdateFenceReceiver`, request code 11). Releases on: unreadable/other version, unmanaged/not device owner, switch off, another boot, a failed/user-action result, our package replaced (once Home or the lock is in front or the screen is off, at the latest 2 min after the replacement - wall time, so a crash-looping Home can't hold it), a success that didn't replace us, > 10 min, no/uncommitted session in a process that isn't committing it, the session gone. Never in the committing process while its session is open (the kill comes seconds after `commit()`; a wake in between resumes the lock in the **old** process). A release unsuspends only the recorded packages that aren't controllable now (those are left to `apply()`), then clears the record.
- **Home after the update**: `PackageReplacedReceiver` (and `AppInstallReceiver` for a failed self-update in a restarted process) brings Home to the front, **then** posts the PIN lock on top if LOCKED (`PinLockRuntime.showIfLocked`), only on a managed phone and never over a call (`bringHomeAfterUpdate`); Home's `onResume` re-enters lock task. The tsnet note in `PackageReplacedReceiver` still holds (tsnet starts from Home's first `onResume`).
- **Notification rule** (server switch `notification_auto_cancel`, missing = **off**; default off until B5): `BadgeListenerService` cancels a clearable notification of a package that is neither ours, allowed (allowlist + contacts' messaging apps + a time rule's usable apps) nor essential, only while apps are managed (not during the override/pause) and only with a known policy (`NotificationRuleRuntime.policy` is `null` until the first `apply()` in the process; `LastEnforcedPlan` doesn't carry the switch). Never ongoing, non-clearable, full-screen-intent, insistent, CALL/MISSED_CALL/ALARM/STOPWATCH, alert-like channels (earthquake/emergency/cmas/...: GMS earthquake alerts), `android`, SystemUI, `com.android.phone`, Telecom, every cell-broadcast name (AOSP, module, Google, plus every resolved `SMS_CB_RECEIVED` receiver), dialers, emergency dialer, the SMS app while SMS is on, the clock app, IMEs - resolved in `apply()` (background), the callbacks only read the cache. The one exception among them: `android`'s Safety Center nags on channels `safety_center_recommendation`/`safety_center_information` ("set a screen lock", `PLATFORM_NAG_CHANNELS`) are cancelled like any nag; `safety_center_critical_warning` and every other `android` notification stay. Its tap would open the permission controller's SafetyCenterActivity inside the lock task (the permission controller is a pinned helper for permission dialogs), where the kiosk's app block stops Settings' ChooseLock, so no lock can be set from there; Safety Center itself can still be reached from other system entry points, and Home doesn't chase it - a home/device-owner app has no clean, non-polling signal for another package's activity in front (usage stats need the PACKAGE_USAGE_STATS app-op, `getRunningTasks` shows only our own tasks, accessibility and overlays are ruled out by design 32 §7). Past 3 cancels per (package, channel) a minute it snoozes for an hour (no delete intent). Privacy: the input has no text fields (tested), `channelId` is `Notification.getChannelId()`, logs carry package/channel and are rate-limited (1st/10th/100th), keys and tags are never logged. Since 2026-10-27 (the GMS welcome nag kept with no line) a notification the rule keeps is logged too, all under tag `NotificationRule`: `kept <package> / <channel>: <REASON> (N so far)` by `keepLogDue` (never the phone-wide POLICY_UNKNOWN/SWITCHED_OFF/UNMANAGED/OWN, once per package+channel for ONGOING/NOT_CLEARABLE/FULL_SCREEN/INSISTENT, the 1st/10th/100th rule for ESSENTIAL_*, `ALLOWED via <allowlist|messaging|lock-usable>`, `GROUP_CHILD_KEPT`, `ACTIVE_UNREADABLE`, `FACTS_FAILED <Exception>`; `KeepCounts`, process lifetime, never reset by the report), `Rule active: N essential packages: <names>` whenever the resolved set changes, and 1 s after each cancel `platform kept <package> / <channel>` once when the same post is still there (`checkAfterCancel`, at most 8 checks in flight); emulator.md B6. Without notification access nothing is cancelled (server warns).
- **Status**: `update_fence` (`enabled`, `state` none/planned/fenced, `unsuspendable`, `last_release`, `home_role_held`, `pending_tag`, `pending_since_ms`, `waiting_for`, `last_fenced_at_ms`, `last_released_at_ms`) and `notification_cancels` (`active`, <= 20 `{package_name, channel, cancelled, snoozed}`, ids <= 64 chars, `dropped`), every field always sent; capability `kiosk_escapes_v1`.
- **Fix round (qa-11-code.md)**: (#1) with **no** record - lost, or corrupt (SharedPreferences swallows the parse error and loads an empty map) - process start and every `apply()` run the orphan sweep: every HOME candidate only a fence can have suspended (pure `orphanFenceTargets` = `fencePlan` without the already-suspended rule) that is suspended now is unsuspended (`last_release` "orphan"); cheap unless a non-controllable HOME is suspended. (#2) the window gate runs again inside `beforeCommit` (after the hash and the session copy); a wake or call there abandons the session and keeps the pending APK, no backoff (`InstallStart.DEFERRED`). (#3) screen-off time counts from the process start only before any screen event in this process (pure `screenOffForMs`). (#4) a commit that can't start and a transient failed result keep the verified APK (`recordFailed(keepPending)`, `deleteOnFailure = false`) and wait out the hour (`pendingCommitAllowed`); deterministic refusals - not ours, older, unparseable, a **wrong signer** (`GET_SIGNING_CERTIFICATES` of the archive vs ours must share a certificate, `signerMatches`), and INVALID/CONFLICT/INCOMPATIBLE/BLOCKED/PENDING_USER_ACTION results (`selfUpdateFailure`) - set `TrackedAppState.refusedTag`: never downloaded, fenced or tried again until the tag changes; PENDING_USER_ACTION abandons its session. (#5) the last fence's reason, refusals, ROLE_HOME and times live in their own prefs file `update_fence_last` (keys pinned) and go out as `update_fence.last_fenced_at_ms`/`last_released_at_ms` + `unsuspendable`/`last_release`/`home_role_held`. (#6) a group summary is cancelled only when every non-ongoing child would be (`groupSummaryCancellable` - NMS cancels the children with it). (#7) essentials also take the system receivers of `android.provider.action.SMS_EMERGENCY_CB_RECEIVED` (OEM alert apps).
- **Not device-tested**: the A1-A5 and B1-B5 checks in the 11 doc and `docs/testing/emulator.md` §6d - in particular whether suspended quickstep breaks gesture Home/Recents during the tail (both nav modes, Pixel and Jelly Star), the frozen-vs-unknown HOME timing, the lock-task list after an update, session info after commit, ECBM detection, GMS's real channel ids.

## Home stays the lock-task root after a process death (kiosk-root, 2026-11-11)

- **The rule**: with the kiosk on, Home's task is `mLockTaskModeTasks[0]`, never a page task of our own affinity
  (`.day` Kalender, `.messages` Meldinger, `.call`, `.pinlock` but for the lock's own fallback in `LockTaskRoot.kt`).
  A root's last activity can't finish (AOSP `activityBlockedFromFinish`: Back does nothing, "Not finishing task in
  lock task mode"), and removing the root clears every other locked task (`clearLockedTask`: `clear-task-all` on Home
  and kid Settings).
- **The cause it guards against** (§6ah's two findings): a launcher process death with Home in front - a kill, the
  low-memory killer, a crash (the crash's own finish of Home is blocked while Home is the root). AOSP
  `ActivityRecord.handleAppDied` removes an activity that has no saved state (`!mHaveState && !stateNotNeeded`); a
  resumed or only paused Home has none (`wm_finish_activity [.., HomeActivity, proc died without state saved]`), so
  Home's task went, the system started a new Home task, and it joined the running lock task **at the end**
  (`realStartActivityLocked` -> `startLockTaskMode` appends). A page task left stopped behind Home keeps its saved state
  and its place, and became the root.
- **The fix**: `android:stateNotNeeded="true"` on `HomeActivity` (as AOSP's Launcher3; `HomeFrontTest` pins it). In
  Android 16 only `handleAppDied` reads the flag: Home's record stays and is restarted in its own task
  (`wm_restart_activity` on the same record), so the root never changes. `onSaveInstanceState` still runs (the client
  never reads the flag). Left: AOSP still removes a record that is `finishing`, or not visible and launched 3 times in
  the last minute (a crash loop) - then the old state can come back. Run: `docs/testing/emulator.md` §6ai.

## Install Now: the parent's one-time update (design 37, `docs/design/37-install-now.md`, at the monorepo root)

- **What**: the server's launcher page has an Install Now button per phone; it queues the command `install_update`
  (`INSTALL_NOW_COMMAND`, `PendingCommand.releaseTag` = the release the row showed, `null` for every other command -
  `PolicyResponseCompatTest`). The phone downloads the launcher row's **newest** release (the list as it is when it
  acts) on any network, checks it exactly as every self-update (size, SHA-256, package, signer, ahead of the running
  build by `launcherBuildAhead` - a newer versionCode, or (old-rule builds only) an RC over an RC of the same code - nothing relaxed) and installs it **at once**, screen on or off, day or night; only a live call or an emergency holds
  it. The update fence goes up when its switch is on, as for every self-update.
- **Pure** (`server/SelfUpdatePlan.kt`, `InstallNowPlanTest`): `InstallNowRecord(commandId, requestedAt, tag,
  fromVersionCode, failure, callWaitSinceElapsed, fromVersionName)` - the name since the RC-to-RC fix, `null` in an
  older record; `installNowDecision(liveCall, emergency)` (call: wait, re-check in
  `INSTALL_NOW_CALL_RECHECK_MS` 2 min; emergency: `EMERGENCY_RECHECK_MS`; else Commit); `launcherRow` (`LauncherRow`
  NO_LIST / NOT_OFFERED - no row or another package / RUNNING - `ALREADY_RUNNING` or the installed tag / REFUSED /
  ON_ITS_WAY); `installNowOutcome(record, running, row, runningName)` - a running build ahead of the record's
  (`launcherBuildAhead`: a higher code, or - old-rule builds - the same code under a newer name; a record without a name by the code) is Installed
  (beats a failure), then the recorded failure, then NOT_OFFERED "not offered", REFUSED "refused: release", RUNNING
  AlreadyRunning, else Keep - and `.answer(versionName)` ("installed: X", "already running: X"); `installNowWait`
  (`update_fence.waiting_for`: `install_now_download` without an APK, `install_now_call`/`_emergency`, else
  `install_now_ready`; no record = the night gate's word); `installNowRecheckMs`; `callWaitSince`/`callWaitExpired`
  (`INSTALL_NOW_CALL_LIMIT_MS` 6 h, qa-37-design R-L4); `downloadGate(..., parentOverride)` lifts `WAIT_WIFI` and
  `WAIT_ROAMING` (network and space still hold); the answer strings are constants (`INSTALL_NOW_*`,
  `PendingApkCheck.installNowAnswer` = "refused: wrong_signer" etc.), the words the server's `locate::reword` knows.
- **The record** (`server/InstallNow.kt`): device-protected prefs `install_now`, key `record_v1`, every write
  `commit()` - DE by the user's choice; nothing reads it before the first unlock today (`SelfUpdate.init` runs from
  `initRest`, R-N1), it holds ids, a tag, versionCodes and a versionName only. `active()` = a record without a failure (the override
  and the gate); `fail(reason)` keeps the first reason and asks for a sync, whose settle answers; `callWait` stamps and
  ends the call wait; `clearCommand` after the answer; `clear` next to `CommandAnswers.clear` (enrollment in
  `Provisioning` and the Settings re-enroll).
- **Dispatch** (`dispatchPendingCommand`, after the receipt; pure `installNowOnReceipt`): a record of another command
  is answered first (its own failure, else `superseded`, failed); the new record stores the running versionCode and
  versionName (the same command delivered again keeps its record); `SelfUpdate.lastWait` cleared;
  `AppDownloads.request`; `requestFollowUpSync`. The same sync goes on to the list and, last, the commit.
- **Settle** (`checkForTrackedAppUpdates`): after `handleLauncherUpdate` (now `LauncherHandled(download, row)`), and in
  the no-list branch with `NO_LIST` (the new process answers "installed" also before tsnet is up). With a record
  `launcherUpdateStep` gets `failedBackoffMs = 0` (the tap is the retry; a refusal still holds).
- **Download** (`AppDownloads.kt`): one helper `gate(app, record, ...)` is the file's only `downloadGate(` call, behind
  the run, the report, the running download's check and the network callback; `parentOverride = record.isLauncher &&
  InstallNow.active(app) != null` - read live, never stored in a download record (QA M3), so a cleared or failed record
  ends it. With a record the launcher downloads **first** (`downloadOrder`/`nextDownload(launcherFirst)`, qa-37-code
  M2), and every record has its own retries in a run (`retryAfterFailure`: 30/60/120 s, then that record waits for the
  next trigger while the others go on - a failing catalog app no longer ends the run). With a record: the launcher's
  give-up after `RETRY_DELAYS_MS` -> `fail("download failed")`, `MAX_HASH_FAILURES`
  -> `fail("download corrupt")`, the launcher's `WAIT_SPACE` -> `fail("no space")`; `WAIT_NETWORK` waits; a stored
  APK asks for a sync (`install_now_downloaded`) so the commit comes now. The launcher's package/signer refusals come
  from the commit's `pendingApkCheck` (R-N2), never `CatalogRefusal`.
- **Commit** (`commitPendingSelfUpdateIfDue`): with a record `commitDecision` = `installNowDecision` in both checks
  (`beforeCommit` too), the backoff skipped (not the refusal) - and the same unconditional `pendingApkCheck` as every
  self-update (size, SHA-256, package, signer, ahead by code and name; pinned by `InstallNowPlanTest`); a wait arms
  `SelfUpdate.armAlarm` whatever the screen, and a wait whose reason changed asks for one more sync so the row reads
  `install_now_call`/`_emergency`, not "Installing" (qa-37-code L1);
  a call wait over 6 h -> `fail("call never ended")`; `CORRUPT` -> `fail("download corrupt")`, `MISSING` and
  `SAME_VERSION` ask for a sync (R-L2: the download restarts, or the settle answers "already running"); a
  deterministic check -> `fail("refused: <check>")`; `InstallStart.FAILED` -> `fail("install failed: start")`.
- **The alarm and the call** (`SelfUpdate.kt`): a screen-off's `armIfPending` arms `installNowRecheckMs(lastWait)` with
  a record, never the night window's delay over the call's 2 minutes (R-L1, one alarm, request code 12); `onScreenOn`
  keeps the alarm while a record runs; `init` adds an `OngoingCalls` listener (it reads the record on `SelfUpdate`'s
  scope, not the main thread) - no live call left while the record
  waits on `CALL` -> `SyncRunner.request("install_now_call_ended")`. A VoIP or Telecom-only call isn't in
  `OngoingCalls`: the 2-minute alarm re-checks it.
- **Results**: `AppInstallReceiver` - any failed launcher result with a record -> `fail("install failed: <status>")`
  (the ordinary path keeps its own backoff and refusal). `PackageReplacedReceiver` - with a record,
  `SyncRunner.request("install_now_done")` (coalesced with the anchor's start sync); the settle answers `installed:
  <VERSION_NAME>`. Over tsnet the answer waits for the next unlock, in the outbox.
- **Status**: `UpdateFence.report`'s `waitingFor = installNowWait(...)`; capability `install_now_v1`
  (`INSTALL_NOW_CAPABILITY`, `STATUS_CAPABILITIES`), and since the RC-to-RC fix `same_code_update_v1`
  (`SAME_CODE_UPDATE_CAPABILITY`): the server offers a same-code release under a newer name only to a launcher that
  claims it - an older build is shown "Install Now works from this update on" (qa-rc-to-rc-code R2R-1). Legacy since
  `launcher-v0.36.0-rc.4` (every build its own code), but still load-bearing while any phone runs 0.36.0-rc.1 to
  rc.3: those builds read rc.4 to rc.8 with their own old parser as their own code (35999), so rc.3 takes them only
  by the name rule and this capability, and rc.1/rc.2 not at all (the server shows them TooOld, reading the tag as
  they do - `install_now::tag_code_as_read_by`); never remove either before those phones are gone.
- Tests: `InstallNowPlanTest` (the gate, the settle's table, the answers against the server's words, the row, the
  waiting words, the alarm, the 6 h call, the skipped backoff and its glue line, the override's gate, the one `gate(`
  helper, the alarm/screen-on/call-end glue, the record's codec, the capability), `PolicyResponseCompatTest`
  (`release_tag`). Device checks: design 37 "Implementation status", `docs/testing/emulator.md` §6o.

## Catalog downloads: Wi-Fi only, resumable (design 13, `docs/design/13-app-downloads.md`, at the monorepo root)

- **Downloads left the sync**: `performMdmSync` only fetches `GET /api/devices/apps` and turns it into the queue before
  the status report (`checkForTrackedAppUpdates`: the installed/backoff/Play/in-flight rules as before;
  `handleLauncherUpdate` returns the release to download). `AppDownloads.reconcile` makes the records exactly that list -
  only after a successful fetch; without one only installed releases' records and files without a record are swept (QA #4).
  `server/AppDownloads.kt` runs one download at a time on its own coroutine, started through the anchor
  (`CommandListenerService.ACTION_DOWNLOADS`), with the `kidslauncher:download` wake lock (5 min, renewed every minute of
  progress, released while waiting) and no overall timeout: OkHttp's 10 s read timeout makes a stall a retry (30 s, 1, 2
  min, then the next trigger). Triggers: every sync, process start (`AppDownloads.init`), a default-network callback while
  something waits (only on the edge to "one may run"). The `cacheDir`/`nanoTime` files and the in-sync download in the
  Architecture bullets are history; old `cacheDir/tracked_app_*.apk` go once an hour old.
- **Gate** (pure `server/AppDownloadPlan.kt`, `AppDownloadPlanTest`) on `getNetworkCapabilities(activeNetwork)` (our VPN's,
  with the underlying transport): INTERNET + a physical transport (QA #1), never roaming (switch on or off, our own update
  too), with the switch NOT_METERED - our own update only for 3 days from first sight (`LAUNCHER_WIFI_GRACE_MS`), catalog
  apps without grace; room for the rest + a session copy + 100 MB (`getAllocatableBytes`). A network change that fails it
  cancels the `Call<ResponseBody>` (`MdmApi.downloadTrackedApp`); the partial stays. `PolicyResponse.appUpdatesWifiOnly`
  is nullable (missing/`null` = off), `LastEnforcedPlan` carries it, the override/pause don't lift it.
- **VPN not metered**: `KidVpnService`'s builder calls `setMetered(false)` - the default made the whole phone metered on
  Wi-Fi, so Play's and our unmetered work never ran. Never set underlying networks (`VpnMeteredInvariantTest` scans).
- **Resume and checks**: partial `noBackupFilesDir/app_downloads/<appId>-<16 hex SHA-256(tag)>.part`, record
  `DownloadRecord` in CE prefs `app_downloads` (`AppDownloadStore`, synchronized, `commit()`; id, tag, URL, server
  SHA-256, first `ETag` and size, first seen, hash failures). `Range` + `If-Match`: 206 appends, 200 truncates (older
  server: no `ETag`, no resume), 412 or an incomplete 416 restart at once, 404 drops it, backs off an hour and re-lists
  (`resumeAction`). `X-Release-Tag` differing (`releaseTagHeader`) = drop + a sync for the list (at most every 10 min);
  the whole file's SHA-256 against `TrackedAppUpdate.sha256` (`null` = unchecked): a mismatch re-lists and downloads
  again at once, the second waits out the backoff (counted per release in `TrackedAppState.hashMismatches`, so a new
  record doesn't reset it). A running download checks every 2 s that its record is still wanted; records change only
  through `AppDownloadStore.mutate`/`update`/`remove` under the store lock, and the sync and the runner both skip a
  release that is installed, refused, between commit and result or already pending (`releaseStillWanted`,
  qa-13-code #3).
- **Install**: catalog apps install from the runner under `AppDownloads.installMutex`; `attemptStartedAtMs` now covers
  only commit -> `AppInstallReceiver` (which also clears the record, off the main thread); an install that can't
  start waits out the hourly backoff (qa-13-code #2). Our own update's file is renamed into `self_update/` +
  `recordPending` (under `synchronized(SelfUpdate)`, like `cleanup`); the night commit takes `installMutex` and sets
  `committedInThisProcess` inside it - no session opens while that commit's attempt runs (QA #3,
  `selfUpdateCommitting`); a failed result in the same process clears the flag (qa-13-code #1).
  `TrackedAppUpdateState`'s mutators are `@Synchronized`.
- **Status** `app_downloads`: `{wifi_only, network, entries}` (<= 20 `{tracked_app_id, release_tag, state, bytes, total,
  since_ms, any_network_at_ms}`), a full snapshot in every report; a record that may run but hasn't started says
  `downloading`.
- **Not device-tested**: the checks in the 13 doc ("Implementation status").

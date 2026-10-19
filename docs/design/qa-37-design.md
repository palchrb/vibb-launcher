# QA 37 (design) - install the launcher update now

Reviewer: design QA, 2026-10-19. Branch `design-37` at `4ab337e1` (on main `badbc91d`). I read it against:
- `docs/design/37-install-now.md`; the root `CLAUDE.md` (contract files, both-directories rule); `server/CLAUDE.md`
  (Find My Device: `device_commands`, receipts, expiry, `queue_command`, status words; kiosk escapes; the Apps tab;
  the "no jump to the top" rule and `assert_lands`); `launcher/CLAUDE.md` ("Kiosk escapes", "Catalog downloads",
  command receipts); `docs/design/11-kiosk-escapes.md`.
- server: `models.rs` (`EXPIRING_COMMANDS`, `DeviceCommand::status`, `CommandStatus`, `command_label`),
  `handlers/locate.rs` (`reword`, `status_words`, `replaced_kinds`, `queue_command`, `cancel_command`),
  `handlers/device_api.rs` (the pop, `end_expiring_at_start`, `command_result`), `handlers/window_relock.rs`,
  `kiosk_escapes.rs` (`wait_text`, `UpdateWaiting`, `MAX_WORD`), `handlers/tracked_apps.rs` (`PhoneRow`, the launcher's
  phones), `templates/tracked_app_detail.html`, `templates/device_advanced.html`, the migrations;
- launcher: `SelfUpdatePlan.kt`, `SelfUpdate.kt` (+ `SelfUpdateReceiver`), `MdmSyncWorker.kt` (`performMdmSync`'s
  order, `dispatchPendingCommand`, `checkForTrackedAppUpdates`, `handleLauncherUpdate`,
  `commitPendingSelfUpdateIfDue`, `waitForWindow`), `AppDownloadPlan.kt` (`anyNetworkAtMs`, `reconcileRecords`,
  `DownloadRecord`), `AppDownloads.kt` (the gate's call sites), `CommandAnswers.kt`, `PackageReplacedReceiver.kt`,
  `AppInstallReceiver.kt`, `StatusCapabilities.kt` and the tests that read it.

No code was built or run.

**Verdict: approve with the listed changes.** The shape is right: one command kind on the existing queue, the gate
unchanged except the night window, every APK check untouched, the answer from the new process, no new contract.
Nothing makes the feature unable to work, but the grant's clock (M1), the row's honesty while the phone waits for
something else (M2), the download grace's plumbing (M3), the redirects on refusals (M4) and the daytime fence gap (M5)
need a revision of the note before the step.

Counts: 0 High, 5 Medium, 10 Low, 2 Nits.

## Medium

### M1. A clock set back lapses the grant at once, and the phone answers "Failed"

**What.** `grantLive` is `nowMs - grantedAtMs in 0 until INSTALL_GRANT_MS`: a negative difference is "not live", and
`grantOutcome` then answers `Lapsed`. An NTP correction of a few seconds backwards right after the receipt (common on
a phone that just reconnected), or a timezone/clock fix, turns the parent's tap into "Install Now failed: Not
installed within a day: ..." within minutes. The note's test list even pins "negative" - presumably as not live.

**Fix.** Treat the grant as a timed window the way the launcher already does (`timedWindowActive`: wall clock +
elapsed realtime + boot count, `BootClock`): store `grantedAtElapsed` and the boot count with `grantedAtMs`, live while
either clock says < 24 h; a negative wall difference counts as just granted. Test: clock 10 s back -> `Keep`.

### M2. The Granted row says "installs at the next screen-off" while the phone waits for something else

**What.** `reportedWait` knows only grant/pending/`lastWait`. Three real states read wrong for up to a day:
- **the download can't run**: `granted_download` = "Downloading, then installs ..." also while the gate says
  `waiting_wifi` (open question 1 answered "wait"), `waiting_roaming`, `waiting_space`, or the second hash mismatch's
  hour; the lapse then says "the download didn't finish" for what was a 24 h wait for Wi-Fi;
- **the 1-hour backoff** after a transient install failure (the grant is kept, §1.6): `commitPendingSelfUpdateIfDue`
  returns at `pendingCommitAllowed` before the gate, so `lastWait` isn't touched and the row says "Installs at the
  next screen-off" for an hour; nothing re-arms the alarm when the hour ends (the next screen-off or the 30-min
  backstop does);
- a release the phone refused earlier and the server still lists is answered at once (fine), but nothing tells the
  parent before the tap.

**Fix.** The server already has the launcher row's `app_downloads` entry (state, bytes, total, any-network time):
word `granted_download` from it ("Waiting for Wi-Fi", "Downloading 40 of 80 MB", "Waiting: not enough space",
"Waiting: roaming"). Add one wait word `granted_retry` (with the retry time in a new field, or reuse
`last_release`'s failure) for the backoff, and have the commit step arm the alarm for the backoff's end while the
screen is off and a grant is live. Lapse reasons: `expired: download_waiting_wifi` etc. from the gate's wire.

### M3. `grantedAtMs` in `WantedDownload`/`DownloadRecord` doesn't reach the gate in the cases that matter

**What.** §4.3 puts the grant's time into the launcher's download record. `reconcileRecords` keeps an existing record
by `copy(name, isLauncher, downloadUrl, sha256, packageName, signerSha256)` - a new field must be added there, or a
release first seen before the grant (the usual case: downloading on Wi-Fi only) never gets it. The no-list branch
never reconciles, so a grant received in a sync whose list fetch failed waits for Wi-Fi. And once the grant is
answered or lapsed, the record keeps the shortened grace - the launcher then downloads on mobile data without a grant.

**Fix.** Don't persist it: at the five `anyNetworkAtMs(...)` call sites in `AppDownloads.kt` (gate, report, running
check, `waiting` log, network callback) pass `SelfUpdateGrant.live(context)?.grantedAtMs` for the launcher record.
One source of truth, cleared with the grant; the report's `any_network_at_ms` follows by itself. The re-gate on
grant ("`AppDownloads.request`") stays.

### M4. Refusal redirects name fragments the answer doesn't render

**What.** The standing rule: every redirect lands on an element the answer still has (`assert_lands`). §2.3 renders
Advanced's `#install-now` group only for `Behind`/`TooOld`/`Asked`/`Granted`/a fresh answer, so `?install=up_to_date#
install-now` (a second tab, a stale page) and `no_release` land at the top. On the launcher page, `#phone-{id}` exists
only for set-up phones, so `not_set_up` lands at the top too.

**Fix.** Render the `#install-now` group whenever `?install=` is present (it carries the flash), and give the "On
phones" group an `id="phones"` for `not_set_up`/unknown rows. The test list's redirect table should post the refused
words too, not only `sent`.

### M5. Daytime installs make the fence-off gap routine - say it, or ask the user

**What.** The non-goals cite QA 11 #8 (calls ring unscreened for ~1 min). The other half of design 11's escape is
the home screen: with `update_fence` off (the default until A3 passes on the Jelly Star), Android's stock launcher,
its Recents and wallpaper picker are reachable for the ~1 min of install + dexopt. At 02:00 nobody is holding the
phone; with Install Now it happens by design at 15:00, a minute after the parent tapped, with the child nearby and
possibly told "your phone is updating". Suspended apps stay suspended (a DPM state), so the escape is the stock home
screen itself, not the allowlist. The 24 h overdue path already allows this, but rarely and unannounced.

**Fix.** Add a sentence to the Install Now ⓘ (both places): "For about a minute the phone shows Android's own home
screen. Turn on Update fence (Advanced) once it has been checked on this phone." And put it to the user as a fourth
open question: Install Now with the fence off - allowed (proposed), or only with the fence on?

## Low

1. **`EXPIRING_COMMANDS` is bound by index in three places, not two**: `device_api.rs` (pop, `end_expiring_at_start`)
   and `locate::cancel_command` (`NOT IN (?, ?, ?, ?)`). `[&str; 4]` -> `[&str; 5]` keeps `[3]` compiling, so a
   missed site is silent. Fix: one helper (`expiring_in_list()` -> placeholders + binds) used by all three; a test
   that a server start cancels a waiting `install_update` (the note tests only the pop).
2. **Unspecified row states**: `Received` past 25 h ("No answer") isn't in §2.2's table - give `Behind` its button
   back, as for Failed/Expired/Cancelled. A disabled launcher row (`enabled = 0`, not in `scoped_apps`) should be
   `no_release`, not `Behind` (the phone would answer "No update for this phone").
3. **Wording in the PWA's voice**: "screen-off" is jargon - "Installs when the phone is next put down" (the ⓘ says
   "screen off for 30 seconds, no call"). "Refused: older than the phone's" -> "Refused: older than the phone's
   version". "1.4.0 installs tonight" is wrong while it isn't downloaded (Wi-Fi) or already overdue - "1.4.0 is
   waiting". `wait_text("granted")` lands after "Last check: " ("Last check: installs at the next ...") - the note
   replaces the detail for `granted`, so drop it from `wait_text`; `granted_download` comes with `pending_tag: null`,
   so `UpdateWaiting` never shows and its `wait_text` is dead - drop it too.
4. **`lastWait` is in memory and outlives the grant**: an `OUTSIDE_WINDOW` from before the tap becomes `expired:
   outside_window`, which `reword` doesn't know (shown raw); after a process restart the reason is a guess
   ("screen_on"). Fix: clear `lastWait` when a grant is stored; keep the last wait reason in the grant record (written
   only when it changes); map every `UpdateWaitReason` in `reword`.
5. **`replaced` is answered as a success**: Recent then says "Install Now · Done · Replaced by a newer Install Now",
   and "Replaced" is already the server's word for a command that was never sent. The path is nearly unreachable (the
   server refuses `in_progress`). Fix: answer it `succeeded = false`, worded "Ended: a newer Install Now took over",
   or drop the case (overwrite and answer nothing - the old row then reads "No answer" after 25 h).
6. **Advanced placement**: "a group right under the switches form" sits between the form and its group-footer ("Vibb
   One installs its own updates at night ...", with the ⓘ) and orphans the footer. Put the group after the footer. On
   the launcher page, the "row's ⓘ" for the answer line doesn't exist yet - say it is a `details.info` in the row.
7. **A command queued behind `install_update`** on a phone that comes back with several waiting gets one per sync;
   the 24b commands call `requestFollowUpSync` for this (QA #8). Do the same (the follow-up's commit check is harmless).
8. **A call that ends with the screen still off** arms nothing (`CALL` has no recheck): the grant waits for the next
   screen-off or the 30-min backstop - as the night path does today. Fine for the user's "within a minute" only if
   the kid puts the phone down after the call; say so in §6 check 4, or arm the alarm from `OngoingCalls`' end while a
   grant is live.
9. **Emulator checks**: check 6's "already running" can't be reached from the UI (the server answers `up_to_date` and
   shows no button); use a report that lags (a crafted POST before the phone's next report) or drop it. The offline
   hour belongs in the server test only. Add a reboot with a live grant (screen off at boot: the alarm from
   `SelfUpdate.init`) and a failed install (another debug key's APK with a higher versionCode, then the backoff row).
10. **Missing tests for the risky paths**: the clock-back grant (M1), `reportedWait`/the server words with a download
    waiting for Wi-Fi and with the backoff (M2), the gate reading the live grant and losing it when cleared (M3), the
    refused words' redirects (M4), a server start cancelling a waiting `install_update` (L1), the launcher page's and
    Advanced's reads as 500 on a DB error (the standing rule; §2 doesn't say).
11. **A manual upload's tag** never parses, so `Behind` keeps its button even when the phone already runs that build
    (the tap then answers "Already on X"). Accepted, but say it in §2.2 next to "a manual upload".

## Nits

1. The grant in DE prefs: the sync never runs before the first unlock and its neighbours (`command_answers`,
   `tracked_app_update_state`) are CE. Use CE, or state why DE (harmless either way: ids and times only).
2. `PackageReplacedReceiver`'s extra `SyncRunner.request` is redundant - `CommandListenerService.start` syncs once at
   start - but cheap and coalesced; keep it if it reads clearer.

## Confirmed

- **No migration**: `device_commands.command` has no CHECK (0010, 0065, 0068); the highest migration is 0079, 0080
  stays free.
- **Contracts**: `time_status_report.json` and `dns_upstream_status.json` (both copies) have no `update_fence`; the
  command adds no policy key (`pending_command` is `{id, command, requested_at}`), so `policy_json_keys_snapshot` and
  `PolicyResponseCompatTest` stay. `STATUS_CAPABILITIES` is pinned by no snapshot - each capability is asserted on
  its own (`SyncScheduleTest`, `CommandAnswerPlanTest`, ...), so `install_now_v1` needs only its own assertion. The
  waiting words fit `MAX_WORD` (32).
- **The gate**: `granted` turns `overdue` on, and call (no recheck), emergency (10 min), screen on and the 30 s stay
  are checked before it in `updateWindowDecision` - the overdue path exactly. `armIfPending` at every screen-off
  passes through `commitCheckDelayMs`, so with `granted` the `SelfUpdateReceiver` alarm is armed for 30 s at the next
  screen-off (exact while idle, `USE_EXACT_ALARM`); a download landing with the screen off arms through
  `onPendingStored`; the grant's own sync commits at once when the screen has been off >= 30 s (the commit is its last
  step, after the status report and the outbox flush). `beforeCommit`'s second check needs `granted` too - the note
  says so.
- **The process kill**: the receipt goes before acting; the grant is committed before anything else in that sync; the
  new process settles `Installed` first (running code > `fromVersionCode`), also in the no-list branch (tsnet before
  the unlock), and the answer waits in the CE outbox. `command_result` is an idempotent UPDATE, so an outbox resend
  after a lost 2xx can't make a second, different "Done". A failed install after the commit: `AppInstallReceiver`
  records the failure (transient keeps the APK and the grant; deterministic sets `refusedTag`, the settle answers
  `refused: release`).
- **"Already running"** is the same versionCode compare as `ALREADY_RUNNING` (`launcherTagVersionCode(tag) <= running`)
  plus `lastInstalledTag`, and the server's `UpToDate` uses the same formula on the report's `app_version_code`.
- **The download first**: the grant's sync dispatches the command, then fetches the list and queues the download in
  the same pass (`checkForTrackedAppUpdates` after `dispatchPendingCommand`).
- **Server side**: the launcher page lists every enrolled phone (`is_launcher`); the "Play and kiosk" card is gone
  (Advanced's `#updates` is "Updates & notifications" since design 31 step 3); `replaced_kinds` replaces only waiting
  rows; `Received` is "No answer" after a fixed 10 minutes today, so the 25 h exception is needed; the security event
  and admin-only routes follow `window_relock::queue`.

## Answers to the open questions

1. **Mobile data**: yes, download at once on mobile data (never roaming) - the parent asked for now; say "may use
   mobile data" in the ⓘ, and read the grant live at the gate (M3).
2. **A newer release during the grant**: agree with the note - the grant covers it; every APK check still runs.
3. **Tell the parent when done**: no for now; the row and Recent say it. If anything later, a Failed answer, not Done.
4. (new, M5) **Daytime installs with the update fence off**: recommended allowed, with the ⓘ sentence; the user decides.

## Revision check (ae03013d)

Re-read 2026-10-19 against `docs/design/37-install-now.md` at `ae03013d` (§9 Revision), merged into `qa-design-37`.
The user's model replaces the grant: a one-time, non-expiring command that installs at once on any network, waiting
only for a call or an emergency. Re-checked against `AppDownloads.kt` (the five gate sites, `RETRY_DELAYS_MS`,
`MAX_HASH_FAILURES`, the refusal path), `calls/OngoingCalls.kt` (`addListener`), `SelfUpdate.kt` (`armIfPending`,
`onScreenOn`, the one alarm `PendingIntent`), `commitPendingSelfUpdateIfDue`, `locate::cancel_command`, the pop.

### The earlier findings

- **M1** resolved: no grant window, no wall clock compared across the command (§1.10).
- **M2** resolved: `install_now_download` worded from `app_downloads`; call/emergency/ready words; no backoff state
  (skipped); failures answer at once.
- **M3** resolved: one `gate(…)` helper behind all five sites, the override read live from the record.
- **M4** resolved: `#phones` always rendered, `#phone-{id}` only for existing rows, every word posted in the tests.
- **M5** partly: the ⓘ names the home screen; the other half of the minute is still unsaid - see R-M1 below.
- **L1** reasonably declined: not expiring, so the three index-bound sites stay as they are.
- **L2** resolved (`NoRelease` for a disabled row; "No answer" gives the button back). **L3** resolved. **L4**
  resolved (no `expired:` answers; `lastWait` cleared). **L5** resolved (`superseded`, failed). **L6** moot (no
  Advanced group; the ⓘ is a `details.info`). **L7** resolved. **L8** resolved (listener + 2-minute alarm; but R-L1).
  **L9** resolved. **L10** resolved. **L11** resolved (stated, with the version-name compare).
- **Nit 1** kept DE by choice - harmless, but its stated reason is off (R-N1). **Nit 2** kept, fine.

### New findings

**R-M1 (Medium). The ⓘ should also say that calls aren't screened for that minute.** QA 11 #8: the freeze kills
the screening, redirection and in-call services, so any caller - a blocked number included - rings in the system's
call screen while the install runs. At night that was a rare corner; Install Now makes it a routine daytime minute,
at a moment the parent chose. Fix: one more clause in the ⓘ ("...and calls aren't filtered for that minute"). No
code change; the user decided the at-once model knowing the home-screen half, this half should be in front of them too.

**R-L1. A screen-off overwrites the 2-minute call re-check.** `armIfPending` (every screen-off with a pending APK)
re-arms the one `SelfUpdateReceiver` alarm (request code 12, `FLAG_UPDATE_CURRENT`) with `commitCheckDelayMs` - the
night-window delay, often hours. A record waiting on a call the listener can't see (VoIP, Telecom-only) then waits for
the 30-min backstop instead of 2 minutes. Fix: with a record, `armIfPending` arms the record's recheck (or leaves the
alarm alone).

**R-L2. Commit-step outcomes that ask for no sync.** With a record, `MISSING`/`CORRUPT` at the commit drop the APK
("downloading again at the next sync") and `SAME_VERSION` records it installed - all three return without a sync, so
the row says "Installing" until the 30-min backstop. Fix: with a record, request a sync there (the settle then answers
"Already on X", or the download restarts at once); a `CORRUPT` after the download's own hash check can go straight to
`fail("download corrupt")`.

**R-L3. The poll updates only the sub-lines.** After the fetch the Cancel button stays (it answers `too_late`), and
after Done/Failed the Install Now button doesn't come back until a reload; it also polls every 5 s for up to 25 h.
Fix: reuse `static/auto-reload.js` (it keeps the place, never reloads mid-save or with a `<details>` open) when the
JSON's state word changes, else keep the `textContent` swap; stop while the page is hidden. No jump either way.

**R-L4. A call that never ends holds the record indefinitely.** `liveCall` counts `telecomInCall == null` and, without
READ_PHONE_STATE, an audio mode of `IN_COMMUNICATION`/`RINGTONE` - a background voice app can hold that for hours. With
no expiry the record then keeps overriding Wi-Fi-only for the launcher's download and installs whenever the mode
drops, possibly hours later; the server says "Waits for the call to end" for 25 h, then "No answer". Fix (a failure,
not a window): after e.g. 6 h waiting on `CALL`, `fail("call never ended")`, worded "Not installed: a call stayed on";
or state the accepted behaviour in §1.5.

**R-N1.** §4.2's reason for DE is inaccurate: `SelfUpdate.init` runs from `initRest`, which `Application.onCreate`
defers until the unlock (only the call policy and `BootClock` run while locked), so nothing reads the record before
the first unlock. Keep DE if wanted; fix the sentence.

**R-N2.** §4.2's "a refusal (`CatalogRefusal`) -> fail" never fires for the launcher: `CatalogRefusal` is the catalog
install's PM pre-check, and the launcher's file goes to `storeSelfUpdate` unchecked - its package/signer refusals come
from the commit's `pendingApkCheck` (already `fail("refused: <check>")`). Drop the bullet. Also say the pop maps
`payload` to `release_tag` only for `install_update` (its payload is a plain tag, `allow_blocked`'s is JSON).

### Checked and fine

- `pending_command.release_tag` (`skip_serializing_if`, launcher `String? = null`) leaves the policy keys unchanged;
  the compat test case with and without it is the right pin.
- The prompt failure path: `RETRY_DELAYS_MS` = 30/60/120 s and `MAX_HASH_FAILURES` = 2 exist and are the run's own
  give-up points; `WAIT_SPACE` can only fail once the size is known; `WAIT_NETWORK` waits. One `InstallNow.fail` +
  one settle answers once.
- "Done" from the new process: the record (command id, `requested_at`, `fromVersionCode`) survives the kill;
  `installed beats a failure` in `installNowOutcome`; `command_result` is idempotent.
- `OngoingCalls.addListener` exists; the call-end sync plus the commit's `installNowDecision` in both checks is right.
- The four `install_now_*` words fit `MAX_WORD` (32); `wait_text` covers the three that come with a pending tag.
- No expiry: `cancel_command`'s `NOT IN (expiring)` guard never applies, so Cancel works for the whole unfetched time;
  the 25 h `Received` limit and "Waiting for the phone" are consistent with Recent's "Goes out when the phone is back".
- Redirects: `#phone-{id}` / `#phones` with and without JS; the JS swap uses `textContent` and never scrolls.

**Verdict: approve with the listed changes** (R-M1's ⓘ clause; the Lows can be done in the step).

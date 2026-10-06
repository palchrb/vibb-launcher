# QA step 13 code review: app downloads (4c8015db, b4c673a1)

Reviewed 2026-10-06 against 13-app-downloads.md (QA review, decisions, implementation status). L = `launcher/app/src/main/java/com/kidslauncher/mdm/server/`, S = `server/src/`. **No H.** `cargo test` passes (236) on a clean worktree of b4c673a1. I didn't rebuild the launcher. I checked these and they match the decisions:
- **Gate:** INTERNET plus a Wi-Fi, cellular or ethernet transport. Roaming always waits. NOT_METERED is required while the switch is on. Only the launcher gets the 3-day grace, and a clock set before first sight doesn't count. `onCapabilitiesChanged`/`onLost` cancel the call. `setMetered(false)` is on KidVpnService's only builder, nothing calls `setUnderlyingNetworks`, and minSdk is 34.
- **Resume:** ServeFile (tower-http 0.7) sends a strong ETag, checks If-Match (412) before Range, and answers 416 with `bytes */n`. `X-Release-Tag` comes from the same row as the file path, which is unique per release. 206 appends, 200 truncates, 412 and a short 416 restart, and a full 416 finishes. A tag mismatch drops the record and re-lists (at most every 10 min). A hash mismatch deletes and restarts without installing.
- **Sweep:** it skips the active file and drops tags only after a good list.
- **Wake lock:** `setReferenceCounted(false)`, renewed on progress, released before retry delays and in `finally`. Runs are started through the anchor (`specialUse`).
- **Scoping:** `scoped_apps` serves every id an older launcher lists.
- **Self-update commit:** `installMutex` covers createSession through commit, so the commit can't kill a catalog session.
- **Switch:** it posts back to its own path, and `scroll-restore.js` hooks `form.submit()`.
- **Policy and migration:** the key is pinned on both sides, nullable in L, and carried by LastEnforcedPlan. 0043 only adds columns that are defaulted or nullable.

## Findings
1. **M, L `AppDownloads.kt:644-655`, `AppInstallReceiver.kt:45`, `MdmSyncWorker.kt:626`: `committedInThisProcess` never resets after a committed self-update fails before the kill.** This is the qa-11 #9 case, where the result arrives in the same process. For the rest of the Home process's life (which can be days), every catalog `install()` returns STOP. Every later trigger re-hashes the whole finished file (326 MB) and stops again. **Fix:** clear the flag in `AppInstallReceiver` on a launcher failure, after reading `restarted`. Better, gate on "launcher attempt in flight" (`attemptStartedAtMs` within INSTALL_ATTEMPT_TIMEOUT) instead of the sticky flag.
2. **M, L `AppDownloads.kt:663-671`: an install that can't start has no backoff.** On `InstallStart.FAILED` (a synchronous session error, e.g. no room for the copy) the code deletes the file, the attempt and the record. The next sync then downloads the whole APK again, so it's one full download per sync. Before, the attempt marker held this to at most every 10 min. None of the existing loop guards cover this. **Fix:** call `recordFailed(app, key, rec.tag)` here (the hourly backoff the receiver uses), or keep the file and record and retry only the install.
3. **L, L `AppDownloads.kt:210-228`, `MdmSyncWorker.kt:436-476`: `reconcile`/`sweepWithoutList` load and save outside the store lock, against a `TrackedAppUpdateState` snapshot taken earlier.** A runner step that lands in between gets overwritten. A record removed at COMMITTED comes back, causing a second download and possibly a second overlapping install (the bug the attempt marker exists for). A lost etag means the partial is thrown away at the next attempt. A reset `hashFailures` gets past the guard in #4. **Fix:** add `AppDownloadStore.mutate { }` under the lock, re-read the state inside it and skip releases that are in flight, installed or pending. The runner re-checks the state before it downloads.
4. **L, L `AppDownloads.kt:484-490, 621-636`: a 404 doesn't re-list, despite S `handlers/device_api.rs:1081`.** A hash mismatch retries with the run's old `rec.sha256`. So a re-upload under the same label fails twice and reports "Install failed". After the hourly backoff, `hashFailures` starts at 0 again, so a persistent mismatch costs 2 full downloads an hour. **Fix:** call `relist()` on a 404 and on the first mismatch. Keep the mismatch count per (app, tag) in TrackedAppUpdateState.
5. **L, L `AppInstallReceiver.kt:88-96`: the new `AppDownloadStore.remove` runs on the main thread before `goAsync()`.** It decodes prefs JSON and calls `commit()`, synchronized with the runner's 2 s polls. **Fix:** move it into the IO coroutine.
6. **L, L `AppDownloads.kt:384-394` vs `357-362`: a network change can be missed.** `anyGoLast` is set after the caps are read. If a network starts to qualify in between, there's no edge, so nothing is requested, and the run breaks on "Waiting" until the next sync or caps change. **Fix:** recheck `currentCaps()` once before `break`.

## Fixes (2026-10-06)

All six fixed; none skipped. Launcher only (no API change).
1. **Fixed.** `AppInstallReceiver` clears `committedInThisProcess` on a failed result of our own update (after reading
   `restarted`). The runner no longer gates on the sticky flag alone: `selfUpdateCommittingNow()` (pure
   `selfUpdateCommitting`) also needs the launcher's attempt in flight (`attemptStartedAtMs` within
   `INSTALL_ATTEMPT_TIMEOUT_MS`, the pending row's key). It is checked in the run loop before an attempt, so a finished
   file is no longer re-hashed only to stop, and again under `installMutex` before a session opens.
2. **Fixed.** `InstallStart.FAILED` calls `recordFailed(key, tag)` (the hourly backoff the receiver uses) instead of
   `clearAttempt`. The file is gone (`installSilently` deleted it), so a new download comes at most once an hour.
3. **Fixed.** `AppDownloadStore.mutate` reads, changes and writes the records under the store lock. `reconcile` and
   `sweepWithoutList` use it, and re-read `TrackedAppUpdateState` inside: pure `releaseStillWanted` drops a release that
   is installed, refused, between commit and result, or already the pending self-update. The runner checks the same
   before every attempt and drops such a record and its file. Running downloads still check their record every 2 s.
4. **Fixed.** A 404 calls `relist()` after the backoff entry. The hash-mismatch count moved from the record to
   `TrackedAppState.hashMismatchTag`/`hashMismatches` (`recordHashMismatch`, carried by `recordFailed`/`recordRefused`,
   reset by another release or an install), so a re-created record or the end of a backoff doesn't reset it. The first
   mismatch re-lists before the restart, so a re-upload under the same label brings its own hash.
   `DownloadRecord.hashFailures` is gone (an old stored record still decodes).
5. **Fixed.** `AppDownloadStore.remove` in `AppInstallReceiver` runs in the `goAsync` IO coroutine.
6. **Fixed.** When no download qualifies, the run sets `anyGoLast = false` and then reads the caps once more. If they
   changed and now qualify, it looks again (at most 3 times a run). A change after that read is an edge for the callback.

Tests: `AppDownloadPlanTest` (`a release is wanted only while nothing else has it`,
`our own commit holds installs back only while its attempt runs`). Not device-tested, like the rest of design 13.

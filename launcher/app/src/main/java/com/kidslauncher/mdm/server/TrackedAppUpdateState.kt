package com.kidslauncher.mdm.server

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.kidslauncher.mdm.preferences.LauncherPreferences
import kotlinx.serialization.Serializable

private const val LOG_TAG = "TrackedAppUpdateState"

@Serializable
data class TrackedAppState(
    val lastInstalledTag: String? = null,
    val lastFailedTag: String? = null,
    /** When [lastFailedTag] was recorded - lets [MdmSyncWorker.checkForTrackedAppUpdates] retry
     * automatically after a backoff window instead of skipping the same release forever. Null for
     * state persisted before this field existed, which is treated as "eligible to retry now" (see
     * that function) rather than crashing or defaulting to some other timestamp. */
    val lastFailedAtMs: Long? = null,
    /** Set right before a download+install attempt starts, cleared once it resolves (success or
     * failure) - see [TrackedAppUpdateState.recordAttemptStarted]'s doc comment for what this
     * guards against. */
    val attemptStartedAtMs: Long? = null,
    /** The launcher's own downloaded update waiting for the update window (handy step 11,
     * [PendingSelfUpdate]) - only ever on the launcher's row. [TrackedAppUpdateState.recordInstalled]
     * and [TrackedAppUpdateState.recordFailed] drop it; the attempt marker keeps it. */
    val pending: PendingSelfUpdate? = null,
    /** A release refused for good (handy step 11, qa-11-code #4: wrong signer, older, not ours,
     * an invalid or incompatible APK) - not downloaded or tried again until the tag changes. */
    val refusedTag: String? = null,
    /** How often a finished download of [hashMismatchTag] failed the server's SHA-256 (design 13,
     * qa-13-code #4) - kept across records and backoffs, reset by another release. */
    val hashMismatchTag: String? = null,
    val hashMismatches: Int = 0,
)

/**
 * Per-app install/failure tracking for apps tracked from GitHub Releases (see [AppInstaller],
 * [MdmSyncWorker]'s tracked-app sync) - cached as one JSON blob in a single preference, the same
 * "small JSON blob in a String preference" pattern already used for `kid_mode_policy`, rather than
 * a new custom preference-annotation serializer for a `Map` type. Without this, a release that's
 * already installed would be re-downloaded and re-installed every 2-minute sync forever; one that
 * fails to install is re-attempted after a backoff window instead of forever, see
 * [MdmSyncWorker.checkForTrackedAppUpdates] - an earlier version of this skipped a failed release
 * permanently until its tag changed, which left an app that failed once (e.g. from a since-fixed
 * overlapping-install race) stuck silently un-retried indefinitely, with no notification and no
 * server-side visibility either. Keyed by [TrackedAppUpdate.id] (as a string - stringified once at the
 * call site, not here, to keep this a plain `Map<String, _>` like the preference blob it mirrors),
 * not the app's Android package name - that's optional server-side now and can't be trusted to be
 * present or unique across tracked apps.
 */
object TrackedAppUpdateState {
    // The mutators are @Synchronized (design 13 §4): the sync and the download runner both write,
    // each one a read-modify-write of the whole blob.

    fun load(): Map<String, TrackedAppState> {
        val raw = LauncherPreferences.mdm().trackedAppUpdateState() ?: return emptyMap()
        return try {
            ServerJson.decodeFromString(raw)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to decode tracked-app update state", e)
            emptyMap()
        }
    }

    // Writes synchronously (commit(), not the generated preference setter's apply()) because a
    // successful self-update record is written from AppInstallReceiver right as Android is about
    // to SIGKILL this process to replace it with the new APK - an async apply() write frequently
    // never reached disk before that kill, so the launcher kept re-downloading and reinstalling
    // the exact same release forever (confirmed live: same asset_id installed twice ~25s apart,
    // killing CommandListenerService's SSE connection each time). recordFailed shares this path
    // for consistency, though it isn't itself racing a process kill.
    private fun save(context: Context, state: Map<String, TrackedAppState>) {
        val key = LauncherPreferences.mdm().keys().trackedAppUpdateState()
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putString(key, ServerJson.encodeToString(state))
            .commit()
    }

    @Synchronized
    fun recordInstalled(context: Context, appKey: String, releaseTag: String) {
        val state = load().toMutableMap()
        state[appKey] = TrackedAppState(lastInstalledTag = releaseTag)
        save(context, state)
    }

    /** [keepPending]: the launcher's verified APK stays for a retry after the backoff (a
     * transient failure, qa-11-code #4) instead of being downloaded again. */
    @Synchronized
    fun recordFailed(context: Context, appKey: String, releaseTag: String, keepPending: Boolean = false) {
        val state = load().toMutableMap()
        val current = state[appKey]
        state[appKey] = TrackedAppState(
            current?.lastInstalledTag,
            lastFailedTag = releaseTag,
            lastFailedAtMs = System.currentTimeMillis(),
            pending = current?.pending?.takeIf { keepPending && it.releaseTag == releaseTag },
            refusedTag = current?.refusedTag,
            hashMismatchTag = current?.hashMismatchTag,
            hashMismatches = current?.hashMismatches ?: 0,
        )
        save(context, state)
    }

    /** The launcher's [releaseTag] is refused for good (until the server advertises another): the
     * pending APK is dropped (the caller deletes the file). */
    @Synchronized
    fun recordRefused(context: Context, appKey: String, releaseTag: String) {
        val state = load().toMutableMap()
        val current = state[appKey]
        state[appKey] = TrackedAppState(
            current?.lastInstalledTag,
            lastFailedTag = releaseTag,
            lastFailedAtMs = System.currentTimeMillis(),
            refusedTag = releaseTag,
            hashMismatchTag = current?.hashMismatchTag,
            hashMismatches = current?.hashMismatches ?: 0,
        )
        save(context, state)
    }

    /**
     * Marks a download+install attempt as in flight for this app - checked by
     * [MdmSyncWorker.checkForTrackedAppUpdates] before starting a *new* attempt for the same app,
     * so a later sync cycle (triggered by checking a different app while this one's still
     * mid-install) doesn't fire a second, overlapping install for the same target.
     *
     * This matters because [AppInstaller.installSilently]'s `PackageInstaller.Session.commit()`
     * returns immediately - the real result only arrives later via [AppInstallReceiver], well after
     * `performMdmSync` (and the `syncMutex` guarding it) has already returned. The mutex prevents
     * two sync cycles from running *concurrently*, but does nothing to stop a *later, non-
     * overlapping* cycle from re-attempting an app whose previous attempt simply hasn't resolved
     * yet - confirmed live: checking a second app while the first was still installing caused the
     * first app's install to restart from a fresh cycle's redundant attempt, and the second app's
     * own attempt never completed, ending with it selected/allowed but never actually installed.
     *
     * Recorded before the download even starts (not just before `installSilently`), since the
     * whole download+install span is the window a later cycle shouldn't re-enter. Cleared by
     * [recordInstalled]/[recordFailed] once `AppInstallReceiver` resolves the real result, or by
     * [clearAttempt] on an earlier failure (download error, exception) where that receiver is never
     * reached at all. If neither ever fires - the process dies mid-attempt, or the callback is
     * somehow lost - the timeout in `checkForTrackedAppUpdates` reclaims it instead of blocking
     * retries forever.
     */
    @Synchronized
    fun recordAttemptStarted(context: Context, appKey: String) {
        val state = load().toMutableMap()
        val current = state[appKey] ?: TrackedAppState()
        state[appKey] = current.copy(attemptStartedAtMs = System.currentTimeMillis())
        save(context, state)
    }

    /** One more failed hash for [releaseTag] (another release starts over at 1); returns the count. */
    @Synchronized
    fun recordHashMismatch(context: Context, appKey: String, releaseTag: String): Int {
        val state = load().toMutableMap()
        val current = state[appKey] ?: TrackedAppState()
        val count = if (current.hashMismatchTag == releaseTag) current.hashMismatches + 1 else 1
        state[appKey] = current.copy(hashMismatchTag = releaseTag, hashMismatches = count)
        save(context, state)
        return count
    }

    /** The launcher's pending update and its row key, if one is waiting (at most one row has one). */
    fun pendingEntry(): Pair<String, PendingSelfUpdate>? =
        load().entries.firstNotNullOfOrNull { (key, state) -> state.pending?.let { key to it } }

    /** Keeps [pending] on [appKey]'s row (and ends its attempt); any other row's pending is dropped. */
    @Synchronized
    fun recordPending(context: Context, appKey: String, pending: PendingSelfUpdate) {
        val state = load().mapValues { (_, value) -> value.copy(pending = null) }.toMutableMap()
        val current = state[appKey] ?: TrackedAppState()
        state[appKey] = current.copy(pending = pending, attemptStartedAtMs = null)
        save(context, state)
    }

    /** Drops the pending update on every row (the caller deletes the file). */
    @Synchronized
    fun dropPending(context: Context) {
        val state = load()
        if (state.values.none { it.pending != null }) return
        save(context, state.mapValues { (_, value) -> value.copy(pending = null) })
    }

    /** Clears an in-flight marker without touching `lastInstalledTag`/`lastFailedTag` - used for a
     * failure that happens before `installSilently` is ever reached (download error, exception),
     * where nothing else will ever resolve this attempt otherwise. Deliberately doesn't set
     * `lastFailedTag` itself - a transient download failure should still be retried next cycle,
     * not treated as a sticky "don't retry this release" the way a real install failure is. */
    @Synchronized
    fun clearAttempt(context: Context, appKey: String) {
        val state = load().toMutableMap()
        val current = state[appKey] ?: return
        if (current.attemptStartedAtMs == null) return
        state[appKey] = current.copy(attemptStartedAtMs = null)
        save(context, state)
    }
}
